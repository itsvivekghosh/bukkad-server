# PostgreSQL PITR (Point-In-Time Recovery) — wal-g + S3

P2 cluster-ops: continuous physical backup of the PostgreSQL primary with
**wal-g** on S3, giving a bounded RPO and a full point-in-time recovery path
that complements (does not replace) the nightly logical dumps and the monthly
`scripts/ci/restore-drill.sh` drill.

```
postgres (archive_mode=on, archive_timeout=60s)
   │  archive_command: cp %p /wal-spool/%f          (fast, local, no network)
   ▼
wal-spool emptyDir (2Gi, shared)  ── drained by ──▶  wal-g sidecar
                                                      │  wal-g wal-push
                                                      ▼
                                        s3://$S3_BUCKET/pitr/wal_*/…
                                        s3://$S3_BUCKET/pitr/base_*/…  (backup-push, daily)
```

| Piece | Where | Notes |
|---|---|---|
| `archive_mode=on`, `archive_command`, `archive_timeout=60s` | `k8s/postgres/configmap.yaml` (`bhukkad-postgres-config`) | `archive_timeout` bounds the RPO for idle periods (a quiet cluster otherwise archives nothing until 16 MB fills). |
| wal-spool emptyDir | `k8s/postgres/deployment.yaml` (`wal-spool`, `sizeLimit: 2Gi`) | The **S3-outage budget**: while S3 is unreachable the spool fills; after that `archive_command` fails and Postgres retains segments in `pg_wal` (slower, but no data loss). Restore the S3 path (or bump sizeLimit) before it comes to that. |
| wal-g sidecar | `k8s/postgres/deployment.yaml` (`wal-g` container) | Drains the spool (`wal-g wal-push`) and takes a **daily delta base backup** (`wal-g backup-push`). Liveness = heartbeat file in the spool. |
| wal-g binary installer | `k8s/postgres/deployment.yaml` (`wal-g-installer` initContainer) | See “wal-g image” below. |
| WAL freshness gate | `k8s/backup-scripts.yaml` (`verify-wal.sh`), wired into the `s3-sync` container of `k8s/backup-cronjob.yaml` | Fails the nightly job when the newest WAL segment is >30 min old → trips the existing `BackupJobFailed` alert. Disable with `PITR_WAL_VERIFY=false` only when the sidecar is not deployed. |
| S3 root | `WALG_S3_PREFIX = s3://$S3_BUCKET/pitr` | `$S3_BUCKET` from `bhukkad-config` (non-secret); AWS keys from `bhukkad-secrets` (`AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY`, Vault `bhukkad/prod/backup` — the same secretKeyRef pattern as the s3-sync container). |

## wal-g image

WAL-G publishes **no official Docker image** (verified: Docker Hub `wal-g/*`
does not exist; the project’s GitHub workflow pushes only build *base* images).
Its README’s documented install path is the **precompiled release binary**.
Accordingly the sidecar runs `ubuntu:22.04` and the `wal-g-installer`
initContainer fetches the pinned official release
`wal-g-pg-22.04-amd64.tar.gz` @ **v3.0.9**, verifies the released `.sha256`
checksum, and installs it into a shared emptyDir.

For air-gapped / stricter supply chains: build an internal image
(`FROM ubuntu:22.04` + `COPY wal-g /usr/local/bin/`) in CI, push to your
registry, and replace the sidecar image + drop the initContainer. Keep the
version pin. (Coordination note: this swap is ops-owned.)

## What is protected

- **RPO** ≈ ≤60 s (archive_timeout) + sidecar poll (10 s) + upload latency,
  *after* the first successful base backup exists. Until a base backup has
  completed, WAL alone cannot be replayed — verify with the commands below
  before relying on PITR.
- The read-replica StatefulSet streams from the primary but does **not**
  archive; PITR restores happen from S3, not from the replica.

## Full PITR restore (base backup + WAL replay)

Run in a throwaway namespace (mirrors the `scripts/ci/restore-drill.sh`
pattern of an ephemeral `bhukkad-restore-drill-<ts>` namespace) so the live
primary is never touched.

### 1. Pick the base backup

```bash
kubectl -n bhukkad run wal-g-restore-list --rm -i --restart=Never \
  --env="WALG_S3_PREFIX=s3://$S3_BUCKET/pitr" \
  --env="AWS_REGION=$AWS_REGION" \
  --env="AWS_ACCESS_KEY_ID=$AWS_ACCESS_KEY_ID" \
  --env="AWS_SECRET_ACCESS_KEY=$AWS_SECRET_ACCESS_KEY" \
  --image=ubuntu:22.04 -- bash -ec
  #   apt-get update -qq && apt-get install -y -qq wget >/dev/null
  #   wget -qO- https://github.com/wal-g/wal-g/releases/download/v3.0.9/wal-g-pg-22.04-amd64.tar.gz | tar xz
  #   ./wal-g-pg-22.04-amd64 backup-list
```

Note the backup’s name (`base_...`); `LATEST` works if you always want the
most recent one.

### 2. Fetch the base backup + replay WAL into an empty postgres pod

```bash
kubectl create namespace bhukkad-pitr-drill
kubectl -n bhukkad-pitr-drill run pitr-postgres --restart=Never \
  --image=postgres:16-alpine \
  --env="POSTGRES_USER=restore" --env="POSTGRES_PASSWORD=drill" \
  --env="PGDATA=/var/lib/postgresql/data" \
  --env="WALG_S3_PREFIX=s3://$S3_BUCKET/pitr" \
  --env="AWS_REGION=$AWS_REGION" \
  --env="AWS_ACCESS_KEY_ID=$AWS_ACCESS_KEY_ID" \
  --env="AWS_SECRET_ACCESS_KEY=$AWS_SECRET_ACCESS_KEY" \
  --command -- bash -ec '
    # wal-g binary (pinned, same as the sidecar installer)
    wget -qO- https://github.com/wal-g/wal-g/releases/download/v3.0.9/wal-g-pg-22.04-amd64.tar.gz | tar xz
    mv wal-g-pg-22.04-amd64 /usr/local/bin/wal-g
    wal-g backup-fetch "$PGDATA" LATEST
    # PG12+ recovery is declared by files, not recovery.conf:
    touch "$PGDATA/recovery.signal"
    wal-g wal-fetch "%f" "%p" >/dev/null 2>&1 || true   # probe connectivity
    # restore_command is passed via command line; add PITR targets here:
    exec postgres -D "$PGDATA" \
      -c restore_command='"'"'wal-g wal-fetch "%f" "%p'"'"'" \
      -c recovery_target_action=promote
    # For point-in-time (instead of latest): add e.g.
    #   -c recovery_target_time="2026-09-11 10:30:00+05:30"
    #   -c recovery_target_inclusive=true
  '
kubectl -n bhukkad-pitr-drill wait --for=condition=Ready pod/pitr-postgres --timeout=600s
```

The pod starts in recovery (`pg_is_in_recovery()` = on), replays
`wal_*/` segments from S3, and promotes automatically at the target
(`recovery_target_action=promote`). `recovery_target_time` is **IST
(Asia/Kolkata, the fleet `TZ`)** — prefer a slightly early target; you cannot
roll forward past it.

### 3. Verify, then cut over

```bash
kubectl -n bhukkad-pitr-drill exec pitr-postgres -- \
  psql -U restore -d postgres -tAc "SELECT pg_is_in_recovery();"
# expect: f  (promoted)
kubectl -n bhukkad-pitr-drill exec pitr-postgres -- \
  psql -U restore -d postgres -tAc \
  "SELECT datname FROM pg_database WHERE NOT datistemplate;"
# expect the per-service DBs (identity restaurants orders … realtime personalization growth)
# spot-check the recovery window data, then re-point traffic / restore services
# (or dump selected databases out of the drill pod into the live cluster).
kubectl delete namespace bhukkad-pitr-drill
```

## Relationship to scripts/ci/restore-drill.sh (do not edit — coordination)

- The **monthly drill** restores the *logical* `pg_dump` set
  (`bhukkad_*_<ts>.sql.gz` under `s3://$S3_BUCKET/backups/<date>`) into a
  throwaway namespace, checks schema/Flyway/row-counts, and enforces a
  **freshness gate ≤7 days** on the newest dump. That path is unchanged.
- **PITR** restores the *physical* backup set under
  `s3://$S3_BUCKET/pitr`, gated operationally by the nightly `verify-wal.sh`
  freshness check (**≤30 min**).
- Both share the S3 bucket, the AWS secret pattern, and the throwaway-
  namespace discipline. Recommended follow-up (integrator-owned, per the
  script header): extend the monthly CI restore drill with a PITR step that
  executes the sequence above (backup-fetch + WAL replay + promote) — the
  drill script itself is intentionally untouched by this batch.

## Operations cheat-sheet

```bash
# Is the sidecar draining? (spool should hover near empty)
kubectl -n bhukkad exec deploy/bhukkad-postgresql -c wal-g -- ls -la /wal-spool
# Last sidecar log lines (uploads / base backup result)
kubectl -n bhukkad logs deploy/bhukkad-postgresql -c wal-g --tail=50
# Manual base backup
kubectl -n bhukkad exec deploy/bhukkad-postgresql -c wal-g -- \
  wal-g backup-push /var/lib/postgresql/data
```

Troubleshooting: `archive_command` failures appear in the postgres container
log as `archive command failed`; they mean the spool write failed (disk) —
the sidecar handles S3 failures itself and retries.
