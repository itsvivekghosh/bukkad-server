# PITR Runbook — WAL-G Continuous Archiving + Base Backups (k8s/)

**Status:** P2 backlog deliverable. Complements
`docs/backup-and-restore-runbook.md` (roadmap #15): the nightly
`pg_dump` set is a **logical, T-1** restore; THIS file documents the
**physical point-in-time** path — WAL-G continuous WAL archiving + nightly
FULL base backups — so the cluster can be recovered to any instant inside
the retention window, e.g. 30 seconds before a bad migration or a
data-corrupting deploy.

Structure mirrors `scripts/ci/restore-drill.sh` (fetch → freshness gate →
throwaway environment → restore → verify → verdict → teardown). The drill
script covers the dump set only; extending it to a WAL-G PITR drill is a
**coordination item** (scripts/ci is owned by the separate ops batch — not
edited here).

## What exists in-cluster (this batch)

| Piece | Where | Notes |
|---|---|---|
| WAL archiving config | `k8s/postgres/configmap.yaml` → `archive_mode=on`, `archive_command` = spool-copy to `/wal-spool`, `archive_timeout=60` | minikube overlay keeps `archive_mode=off` |
| WAL spool volume | `k8s/postgres/pvc-wal-spool.yaml` (5Gi) | PVC, not emptyDir — survives pod churn so staged-but-unpushed segments never vanish |
| Archiver sidecar | `k8s/postgres/deployment.yaml` → container `walg-archive` (image `quay.io/wal/wal-g:postgresql-latest`) | drains `/wal-spool` via `wal-g wal-push`, deletes on success, retries in-order on failure; **discards with WARN while `WALG_S3_PREFIX` is unset/`CHANGE_ME`** (see Step 0) |
| Nightly base backup + retention | `k8s/backup-cronjob.yaml` → container `walg-base-backup`, script `backup-walg.sh` in `k8s/backup-scripts.yaml` | remote `wal-g backup-push` (BASE_BACKUP protocol, no PGDATA mount), then `wal-g delete retain FULL 7` |
| Secrets | `bhukkad-secrets` keys `WALG_S3_PREFIX`, `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` (Vault `bhukkad/prod/backup` — see `k8s/external-secret.yaml`; placeholders in minikube-only `k8s/secrets.yaml`) | same pair the s3-sync container already uses |
| Non-secret config | `bhukkad-config` → `AWS_REGION`, `WALG_COMPRESSION_METHOD=zstd` everywhere | one compression format for base + WAL |

All objects are inert **until `WALG_S3_PREFIX` is real**: the sidecar
discards (WARN) and the cron skips (exit 0). No PITR chain exists in that
state — take no backup for granted until Step 0 goes green.

## Step 0 — Enable & verify archiving (one-time)

1. Provision the object-store target (Vault path `bhukkad/prod/backup`):
   - `walg_s3_prefix` — e.g. `s3://bhukkad-pg-walg/prod-pg16` — a
     **dedicated, EMPTY prefix/bucket** (wal-g's timeline integrity checks
     assume the prefix is cluster-exclusive; reusing it across clusters or
     after `pg_upgrade` breaks restore — see
     `docs/PRODUCTION-READINESS-AUDIT-GUIDE.md` §wal-g).
   - `aws_access_key_id` / `aws_secret_access_key` — same IAM pair as
     `bhukkad-backup` s3-sync, with read/write on that prefix.
2. Apply and restart (archive_mode is postmaster-level → **full pod
   restart required**):
   ```bash
   kubectl apply -k k8s/
   kubectl -n bhukkad rollout restart deployment/bhukkad-postgresql
   kubectl -n bhukkad rollout status  deployment/bhukkad-postgresql
   ```
3. Confirm the chain is live on BOTH planes:
   ```bash
   # server side: segments archived (last_error must be NULL, count climbing)
   kubectl -n bhukkad exec deploy/bhukkad-postgresql -- \
     psql -U "$DB_USER" -d postgres -c \
     "SELECT archived_count, last_archived_wal, failed_count, last_error FROM pg_stat_archiver;"
   # no discard WARNs from the guard:
   kubectl -n bhukkad logs deploy/bhukkad-postgresql -c walg-archive --tail=50
   # storage side: gap-free chain from the oldest retained backup forward
   kubectl -n bhukkad exec deploy/bhukkad-postgresql -c walg-archive -- \
     wal-g wal-verify integrity
   ```
   Green = `integrity check status: OK`. **Do not continue until green.**

## Step 1 — First base backup + schedule

The nightly CronJob (02:00 Asia/Kolkata, same job as the dumps —
container `walg-base-backup`) performs:
`wal-g backup-push` **in remote mode** (no PGDATA directory arg — it
streams via the BASE_BACKUP protocol using the `PG*` env vars; documented
as the Kubernetes-CronJob pattern in the WAL-G PostgreSQL guide), then
retention: `wal-g delete retain FULL 7 --use-sentinel-time --confirm`
(7 newest FULLs + the WAL each still needs).

Run the FIRST one manually right after Step 0:
```bash
kubectl -n bhukkad create job walg-base-once --from=cronjob/bhukkad-backup
kubectl -n bhukkad logs -f job/walg-base-once -c walg-base-backup
kubectl -n bhukkad exec deploy/bhukkad-postgresql -c walg-archive -- wal-g backup-list
```
A PITR restore is only as old as the newest base backup: **never** let the
7-backup retention window drift past your worst-case incident-detection
time (window ≈ 7d; alerting: `BackupJobFailed` / `BackupStale` in
`k8s/monitoring/prometheus-rules.yaml`).

## Step 2 — Incident: choose the recovery target

1. Freeze writes first (scale app deployments to 0 or set
   `DB_REPLICA_ENABLED`-safe maintenance) so the bad state cannot advance.
2. Pick the target instant (business timestamp of "before the damage"):
   ```bash
   kubectl -n bhukkad exec deploy/bhukkad-postgresql -c walg-archive -- \
     wal-g backup-list        # choose a FULL backup START_TIME <= target
   ```

## Step 3 — Restore into a THROWAWAY environment (never the primary first)

Mirror of the drill's "fresh namespace + ephemeral PostgreSQL" step. All
recovery happens against a scratch PVC — the live primary is only touched
after verification passes.

```bash
TS=$(date +%Y%m%d-%H%M%S)
NS=bhukkad-pitr-$TS
kubectl create namespace $NS
# Secrets the wal-g/postgres containers need (copy the real names out of
# bhukkad-secrets / bhukkad-config):
kubectl -n $NS get secret bhukkad-secrets -o yaml | sed "s/namespace: bhukkad/namespace: $NS/" | kubectl apply -f -

kubectl -n $NS apply -f - <<'EOF'
apiVersion: v1
kind: Pod
metadata:
  name: pitr-recovery
spec:
  # wal-g ships inside the recovery-pod via initContainer (same immutable
  # image → same binary version that took the backup). The postgres:16-alpine
  # main container has NO wal-g of its own; restore_command must find it.
  initContainers:
    - name: walg-copy
      image: quay.io/wal/wal-g:postgresql-latest
      command: ["/bin/sh", "-c"]
      args: ["cp $(command -v wal-g) /opt/walg/wal-g"]
      volumeMounts:
        - { name: walg-bin, mountPath: /opt/walg }
  containers:
    - name: postgres
      image: postgres:16-alpine       # SAME major as the source (16)
      env:
        - name: POSTGRES_HOST_AUTH_METHOD
          value: trust                # throwaway namespace, drill posture only
        - name: WALG_S3_PREFIX
          valueFrom: { secretKeyRef: { name: bhukkad-secrets, key: WALG_S3_PREFIX } }
        - name: AWS_ACCESS_KEY_ID
          valueFrom: { secretKeyRef: { name: bhukkad-secrets, key: AWS_ACCESS_KEY_ID } }
        - name: AWS_SECRET_ACCESS_KEY
          valueFrom: { secretKeyRef: { name: bhukkad-secrets, key: AWS_SECRET_ACCESS_KEY } }
      volumeMounts:
        - { name: data,     mountPath: /var/lib/postgresql/data }
        - { name: walg-bin, mountPath: /opt/walg }
  volumes:
    - name: data
      emptyDir: { sizeLimit: 8Gi }    # size to > source dataset in prod
    - name: walg-bin
      emptyDir: {}
EOF
```

3a. Base backup fetch (runs INSIDE the pod — the wal-g image is available
via the initContainer binary at `/opt/walg/wal-g`):

```bash
kubectl -n $NS exec pitr-recovery -- sh -c '
  set -eu
  WALG=/opt/walg/wal-g
  DATA=/var/lib/postgresql/data
  # empty-PGDATA guard: postgres entrypoint is not used here — we write the
  # datadir ourselves. Confirm before fetch:
  [ -z "$(ls -A "$DATA" 2>/dev/null)" ] || { echo "PGDATA not empty"; exit 1; }
  "$WALG" backup-fetch "$DATA" LATEST      # or the base_... name chosen in Step 2
'
```

> `backup-fetch` refuses a non-empty target and restores the FULL tree —
> the postgres superuser is NOT bootstrapped (no initdb ran); expect
> `PG*` auth by `POSTGRES_HOST_AUTH_METHOD=trust` in the throwaway only.

3b. Configure archive recovery to the target instant, then start:

```bash
TARGET="2026-09-11T09:30:00+00"   # YOUR recovery point (UTC)
kubectl -n $NS exec pitr-recovery -- sh -c "
  set -eu
  PGDATA=/var/lib/postgresql/data
  cat >> \$PGDATA/postgresql.conf <<CONF
  listen_addresses = 'localhost'
  archive_mode = off
  restore_command = '/opt/walg/wal-g wal-fetch %f %p'
  recovery_target_time = '${TARGET}'
  recovery_target_action = 'promote'
  CONF
  touch \$PGDATA/recovery.signal
"
kubectl -n $NS exec pitr-recovery -- sh -c '
  su-exec postgres /usr/local/bin/postgres -D /var/lib/postgresql/data >/tmp/pg.log 2>&1 &
'
kubectl -n $NS exec pitr-recovery -- sh -c 'sleep 5; tail -40 /tmp/pg.log'
# … until the log shows: 'database system is ready to accept connections'
# and recovery ended (consistent recovery state reached / promoted).
```

## Step 4 — Freshness gate + verification (the drill's contract)

Same assertions the CI drill makes per DB, now against the recovered clone:

```bash
PSQL="psql -h localhost -U postgres"
# 4.1 Freshness: newest base backup must be inside the retention window.
kubectl -n $NS exec pitr-recovery -- /opt/walg/wal-g backup-list | head
# 4.2 Schema + Flyway + row sanity on every service DB (mirror of
#     scripts/ci/restore-drill.sh §4), plus the INCIDENT CHECK:
DBS=$(kubectl -n $NS exec pitr-recovery -- sh -c "$PSQL -At -d postgres -c \"SELECT datname FROM pg_database WHERE datallowconn AND NOT datistemplate AND datname<>'postgres'\"")
for db in $DBS; do
  kubectl -n $NS exec pitr-recovery -- sh -c "$PSQL -d $db -At -c \"
    SELECT count(*) FROM information_schema.tables WHERE table_schema='public'\""
  # expect: >0 tables, flyway_schema_history present, and
  # your business spot-check (e.g. the pre-deletion row count):
done
```

Verdict rule (same as the drill): **every** service DB restored with
tables + Flyway history = PASS → proceed to cutover; any FAIL →
re-check target time / storage health (`wal-verify integrity`) and retry.

## Step 5 — Promote to primary (cutover)

Option A (recommended, audited-rollback-friendly): the recovered clone is
the known-good state at TIME — then re-point the fleet:

1. Scale down every app Deployment (`kubectl -n bhukkad scale --replicas=0`).
2. Back up the OLD primary volume before destroying anything:
   `wal-g backup-push` (remote) or PVC snapshot — the incident itself is
   evidence.
3. Logical replay (safe, cross-cluster): from the throwaway,
   `pg_dumpall`/`pg_dump` → restore into the cluster primary (this is how
   the #15 runbook restores anyway) — or
   In-place physical: replace `bhukkad-postgres-pvc` content with the
   recovered tree, scale `bhukkad-postgresql` back up (its wrapper starts
   the promoted timeline), and **retire the old `WALG_S3_PREFIX`**:
   start a new prefix immediately so the WAL-G `timeline` integrity check
   never sees the pre-incident cluster writing the same timeline range
   (wal-g pitfall: mixed-prefix timelines cause split-brain restores).
4. Scale apps up; spot-check business reads/writes; `rollout` backlogs
   clear.

Option B: restore in place directly if the damage is a recent
`TRUNCATE`/migration and you accept the RTO — steps are the same minus
the throwaway pod.

## Step 6 — Chain hygiene AFTER a PITR (mandatory)

After any point-in-time promotion, the WAL chain from the old state is
history. Do, in order:

```bash
# integrity of the new timeline from its first backup forward:
kubectl -n bhukkad exec deploy/bhukkad-postgresql -c walg-archive -- wal-g wal-verify integrity
# if wal-verify complains about foreign timelines, clean per vendor guidance:
kubectl -n bhukkad exec deploy/bhukkad-postgresql -c walg-archive -- wal-g delete garbage --confirm
# then take a NEW base backup to anchor the new chain:
kubectl -n bhukkad create job walg-base-post-promote --from=cronjob/bhukkad-backup
kubectl -n bhukkad delete namespace $NS   # teardown, mirror of the drill
```

## Failure modes / notes

- **Sidecar discard mode**: `WALG_S3_PREFIX` unset/`CHANGE_ME` → segments
  are deleted with WARN (pg_wal never bloats, NO chain). Step 0 green is
  the only proof PITR is live.
- **Spool PVC fill** (`bhukkad-wal-spool-pvc`, 5Gi): sustained push
  failures (S3 outage, bad creds) back the queue; PostgreSQL keeps WAL
  until `archive_command` succeeds, and a full spool stops recycling —
  `failed_count`/`last_error` in `pg_stat_archiver` will show archiving
  stalls; alert-worthy before pg_wal grows.
- **wal-g major/minor**: the image ships PG client tools; pin
  `quay.io/wal/wal-g:postgresql-<exact tag>` at first Step 0 success and
  keep the initContainer in Step 3 using the SAME tag the backups were
  taken with.
- **read replica**: WAL archiving is primary-only. Streaming replica is
  unaffected; a PITR restore rebuilds the primary — restart the
  read-replica against the new timeline (pg_rewind or re-clone) per
  postgres docs.
- **TLS flip interaction**: if `TLS_ENABLED=true` (k8s/tls/README-TLS.md)
  was set AFTER WAL-G was configured, wal-g connections still use the
  libpq default unless `PGSSLMODE` env/`pg_hba` require TLS — wal-g obeys
  the same PG* env: add `PGSSLMODE=verify-full` + CA to the cron +
  sidecar ONLY in lockstep with the client cutover (same staged order as
  README-TLS).

## Related

- `docs/backup-and-restore-runbook.md` — logical dumps/RDB, monthly drill
- `scripts/ci/restore-drill.sh` — CI drill for the dump set (read; owned
  by the ops batch)
- `k8s/monitoring/prometheus-rules.yaml` — `bhukkad.backup` alert rules
- `k8s/postgres/*` + `k8s/backup-*.yaml` + `k8s/components/kafka-lag-exporter/*`
