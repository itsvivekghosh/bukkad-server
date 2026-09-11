# PITR Runbook — PostgreSQL base backup + WAL replay (wal-g)

Scope: the **physical** point-in-time-recovery (PITR) path for the primary
PostgreSQL (`bhukkad-postgresql`, k8s/postgres/). The **logical** per-service
dump path (`pg_dump` set + S3 sync) is covered by
`docs/backup-and-restore-runbook.md` and verified monthly by
`scripts/ci/restore-drill.sh` — see "Relationship to the logical backup path"
at the bottom.

---

## 1. What is automated

```
PostgreSQL primary (archive_mode=on)
  archive_command: cp %p -> /wal-spool/pending/<segment>   (shared emptyDir)
        │
        ▼
wal-g-push sidecar (poll 10s)  ── wal-g wal-push ──▶  s3://$S3_BUCKET/postgres-wal/
```

| Piece | Where | Notes |
|---|---|---|
| `archive_mode = on`, `archive_command`, `archive_timeout = 60s` | `k8s/postgres/configmap.yaml` | Idempotent spool copy (`test -f … || cp`); `archive_timeout` forces a segment switch every 60s so a quiet primary still archives. |
| WAL spool | shared `emptyDir` `wal-spool`, mounted at `/wal-spool` | Transient by design: un-pushed segments are re-archived from `pg_wal` after a pod restart. The S3 copy is the durable one. |
| `wal-g-push` sidecar | `k8s/postgres/deployment.yaml` | Pinned `busybox:1.36` loop running `wal-g wal-push` from the binary copied by the `wal-g-init` init container (`apecloud/wal-g:postgres-1.2` — the wal-g project publishes no official image; mirror/rebuild if your supply chain requires it). |
| S3 credentials | `bhukkad-secrets` keys `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` (Vault `bhukkad/prod/backup`) | Same keys as the backup CronJob's s3-sync container. |
| Destination | `WALG_S3_PREFIX = s3://$(S3_BUCKET)/postgres-wal` | `S3_BUCKET` comes from `bhukkad-config`. **Must not be `CHANGE_ME_BACKUP_BUCKET`** — see guardrails. |
| Nightly verification | `wal-verify` container in `k8s/backup-cronjob.yaml` running `verify-wal-archive.sh` (k8s/backup-scripts.yaml) | Fails the CronJob (→ `BackupJobFailed` alert) when the newest archived segment is older than `WAL_MAX_AGE_HOURS` (default 6h); writes `.wal_archive_last_success_epoch` on success. |

### Guardrails

1. **S3_BUCKET placeholder**: `bhukkad-config` ships `S3_BUCKET: CHANGE_ME_BACKUP_BUCKET`.
   With the placeholder, every `wal-push` fails, `archive_command` retries,
   `pg_wal` grows unbounded and the pod eventually fills its disk. Do not
   enable the stack against the placeholder bucket.
2. **pg_wal pressure on S3 outage**: if uploads stall (S3 down, credentials
   rotated, network policy), segments accumulate in `pg_wal` and in the spool
   (the spool is `emptyDir` → counts against the node's ephemeral storage).
   The tripwires are the nightly `wal-verify` freshness gate and
   `BackupJobFailed`; for a fast signal watch the postgres pod's
   `pg_wal` size (`kubectl exec … pg_controldata` / `ls /var/lib/postgresql/data/pg_wal`).
3. **RPO**: with `archive_timeout = 60s` and the 10s sidecar poll, committed
   transactions are in S3 within ~1–2 minutes under load; the enforced
   freshness gate is 6h.
4. **Base backups are an ops step** (see §2): WAL segments alone cannot be
   replayed — a PITR needs a `wal-g backup-push` base backup to replay onto.
   Schedule one per backup-policy window (e.g. nightly, after the dump job)
   until a dedicated CronJob is wired (coordination note below).

---

## 2. Taking a base backup (ops)

Run inside the postgres pod (the `wal-g` binary is on the shared emptyDir, so
execute through the sidecar's filesystem or copy the env):

```bash
# 1. Exec a shell with the sidecar's env (PG* + WALG_S3_PREFIX + AWS keys):
kubectl -n bhukkad exec deploy/bhukkad-postgresql -c wal-g-push -- sh -c \
  'PGDATA=/var/lib/postgresql/data /wal-g-bin/wal-g backup-push /var/lib/postgresql/data'

# 2. List stored base backups:
kubectl -n bhukkad exec deploy/bhukkad-postgresql -c wal-g-push -- \
  /wal-g-bin/wal-g backup-list
```

`backup-push` uploads a physical base backup under the same `WALG_S3_PREFIX`
and records the WAL segment range it spans — the range replayed in §3.

---

## 3. Full PITR restore (base backup + WAL replay)

Target: a fresh PostgreSQL instance (same major version, PostgreSQL 16) that
replays WAL up to a chosen timestamp. Run in a throwaway namespace first —
never restore over the live primary in place.

```bash
NS=pitr-restore                                  # throwaway namespace
kubectl create namespace "$NS"

# --- 3.1 Helper pod that runs wal-g against the backup prefix -------------
kubectl -n "$NS" run pitr --restart=Never --image=postgres:16-alpine \
  --env="WALG_S3_PREFIX=s3://$S3_BUCKET/postgres-wal" \
  --env="AWS_REGION=$AWS_REGION" \
  --env="AWS_ACCESS_KEY_ID=$AWS_ACCESS_KEY_ID" \
  --env="AWS_SECRET_ACCESS_KEY=$AWS_SECRET_ACCESS_KEY" \
  --overrides '{"spec":{"containers":[{"name":"pitr","image":"postgres:16-alpine",
    "command":["sleep","infinity"],"volumeMounts":[{"name":"d","mountPath":"/var/lib/postgresql/data"}],
    "envFrom":[]}],"volumes":[{"name":"d","emptyDir":{}}]}}'
kubectl -n "$NS" wait --for=condition=Ready pod/pitr --timeout=180s

# --- 3.2 Fetch the newest base backup --------------------------------------
# wal-g reads $WALG_S3_PREFIX for both base backups and WAL.
kubectl -n "$NS" exec pitr -- \
  sh -c 'apk add --no-cache wal-g 2>/dev/null || true; \
         wal-g backup-fetch /var/lib/postgresql/data LATEST'

# (If the image lacks wal-g, use the pinned apecloud/wal-g:postgres-1.2 image
#  for step 3.2 — it ships the binary; keep PGDATA identical.)

# --- 3.3 Configure recovery to the target time -----------------------------
# PostgreSQL ≥ 12: recovery settings go in postgresql.auto.conf + a
# `recovery.signal` file. restore_command pulls archived segments from S3.
kubectl -n "$NS" exec pitr -- sh -c 'cat >> /var/lib/postgresql/data/postgresql.auto.conf <<EOF
restore_command = '"'"'wal-g wal-fetch "%f" "%p"'"'"'
recovery_target_time = '"'"'2026-09-11 15:30:00+05:30'"'"'   # ← the PITR target
recovery_target_action = '"'"'promote'"'"'
EOF
touch /var/lib/postgresql/data/recovery.signal'

# --- 3.4 Start PostgreSQL and let it replay --------------------------------
# With the helper pod pattern: replace the sleep with postgres, or attach the
# emptyDir PVC to a normal postgres pod. Replay is complete when the log shows
# "archive recovery complete" and the promote has run.
```

Verification after promote (mirrors what `restore-drill.sh` asserts for the
logical path):

```bash
psql -c "SELECT pg_is_in_recovery();"                 # → f (promoted)
psql -c "\dt"                                          # schema present
psql -d <service_db> -c \
  "SELECT count(*) FROM information_schema.tables WHERE table_name='flyway_schema_history';"  # ≥ 1
psql -d <service_db> -c "SELECT max(created_at) FROM <burning_table>;"  # ≤ recovery_target_time
```

When the restored instance checks out, re-point consumers at it (Service
selector / JDBC URLs) — or, if the drill target was verification only, tear the
namespace down (`kubectl delete namespace "$NS"`).

---

## 4. Relationship to the logical backup path (`scripts/ci/restore-drill.sh`)

`scripts/ci/restore-drill.sh` (not modified in this batch) validates the
**logical** path only, and its expectations are the ones this runbook mirrors:

| Drill expectation | Physical-path equivalent |
|---|---|
| Dump set `bhukkad_<db>_<ts>.sql.gz` under `s3://$S3_BUCKET/backups/<date>/` | Base backups + segments under `s3://$S3_BUCKET/postgres-wal/` (`wal-g backup-list`) |
| Freshness gate: newest dump ≤ 7 days old | `wal-verify` (nightly, 6h) for WAL; base-backup age ≤ policy window (§2) |
| Restore into throwaway namespace, verify tables + `flyway_schema_history` + row counts | §3.4 verification block above |
| `.last_success_epoch` marker | `.wal_archive_last_success_epoch` marker (same convention, WAL path) |

The two paths are complementary: the dump drill proves schema/data
restorability per service; PITR proves the ability to recover to a *moment in
time* (e.g. before a bad migration). Run the monthly drill AND a periodic PITR
rehearsal (§3) — recommend quarterly, or after any `wal-g` image/config change.

---

## 5. Coordination notes (owners outside k8s/)

* **Base-backup scheduling**: `wal-g backup-push` is an ops command today;
  wiring a dedicated CronJob (or a `pgbackrest`-style schedule) needs a
  decision on base-backup cadence + retention (`WALG_…` retention flags).
* **`S3_BUCKET` / `AWS_*` provisioning**: Vault `bhukkad/prod/backup` —
  integrator keeps real values out of git (placeholders per
  `k8s/secrets.yaml` guardrails).
* **Alert wiring**: `BackupJobFailed`/`BackupJobMissing` already fire on the
  shared CronJob; a dedicated `WalArchiveStale` alert can be added once a
  metrics sidecar exports `.wal_archive_last_success_epoch` (same gap as the
  existing `BackupStale` TODO in k8s/monitoring/prometheus-rules.yaml).
