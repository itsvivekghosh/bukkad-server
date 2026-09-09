# Backup & Restore Runbook (roadmap #15)

**Status:** W3-RESTRUCTURE deliverable. Replaces the single-DB backup that the
audit flagged ("backups cover one database; the platform is
database-per-service").

## What is backed up

| What | Where | How | Schedule |
|---|---|---|---|
| Every Postgres service DB (auto-discovered, non-template) | in-cluster PVC `bhukkad-backups` **and** `s3://$S3_BUCKET/backups/YYYY/MM/DD/postgres/<db>/` | `k8s/backup-scripts.yaml` `backup-postgres.sh` (plain SQL + gzip, `--no-owner --no-acl`) via the `bhukkad-backup` CronJob | daily 02:00 (`concurrencyPolicy: Forbid`) |
| Redis RDB snapshot | PVC **and** `s3://…/redis/` | `backup-redis.sh` (BGSAVE + LASTSAVE wait) | same job |

Key properties:

- **Per-DB granularity** — one `bhukkad_<db>_<ts>.sql.gz` per service DB; a
  corrupt dump fails only its DB (the loop continues; exit code reflects the
  whole set; partial dumps are deleted).
- **Off-site copy is mandatory** — `S3_BUCKET` is read from `bhukkad-config`
  by the pinned `amazon/aws-cli` `s3-sync` container; it fails the job if the
  dump failed or the upload broke. **A successful job means the backup exists
  on the PVC AND in S3.**
- **Least privilege** — only `s3-sync` holds AWS keys
  (`bhukkad-secrets: AWS_ACCESS_KEY_ID/AWS_SECRET_ACCESS_KEY`, Vault path
  `bhukkad/prod/backup`). The dump runs as the Postgres superuser from
  `bhukkad-secrets: DB_USERNAME/DB_PASSWORD` (per-service roles cannot read
  each other's DBs — R-08/G-14).
- **Retention** — 30 days local (`RETENTION_DAYS`), 30 days remote via the S3
  bucket lifecycle rule (configure once on the bucket; do not delete from the
  job).

## Restore drill (monthly)

`scripts/ci/restore-drill.sh` restores the latest dump set into a throwaway
namespace (`bhukkad-restore-drill-<ts>`) with an ephemeral Postgres, checks
per-DB table counts + Flyway history + a 7-day freshness gate, then tears down.

Monthly CI hook — **documented for the integrator to wire** (do not edit
`.github/workflows` in this batch):

```yaml
  restore-drill:
    if: github.event_name == 'schedule'   # add cron: '0 4 1 * *' to on.schedule
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Restore drill (#15)
        env:
          AWS_ACCESS_KEY_ID: ${{ secrets.BACKUP_AWS_ACCESS_KEY_ID }}
          AWS_SECRET_ACCESS_KEY: ${{ secrets.BACKUP_AWS_SECRET_ACCESS_KEY }}
        run: |
          aws eks update-kubeconfig --name bhukkad-staging   # or a kind cluster
          ./scripts/ci/restore-drill.sh --source s3 --bucket "$S3_BUCKET"
```

Sources supported: `--source s3 --bucket B` (CI), `--source dir --dir D`
(local), `--source pvc --namespace bhukkad` (extracts straight from the
in-cluster PVC). `--keep-on-fail` preserves the drill namespace for inspection.

## Alerts (k8s/monitoring/prometheus-rules.yaml)

| Alert | Expression (shape) | Meaning |
|---|---|---|
| `BackupJobFailed` | `kube_job_status_failed{job_name=~"bhukkad-backup.*"} > 0` | the nightly job failed — dump and/or upload |
| `BackupJobMissing` | `time() - kube_cronjob_status_last_schedule_time{cronjob="bhukkad-backup"} > 90000` | CronJob has not scheduled for >25 h |
| `BackupStale` | `time() - backup_last_success_epoch_seconds > 90000` | >25 h since the last successful dump set (the script's `.last_success_epoch` is exported by the log exporter) |

`BackupJobFailed`/`BackupStale` page; RPO with this design is 24 h (WAL
archiving/PITR via pgBackRest or wal-g is the follow-up listed in roadmap #15
and is intentionally out of scope here).

## Manual restore procedure

```bash
# 1. Pick the dump set (S3 prefix = date folder)
aws s3 ls s3://$S3_BUCKET/backups/
# 2. Restore one service DB (example: orders)
gunzip -c bhukkad_orders_<ts>.sql.gz | psql -h <host> -U postgres -d orders
# 3. Full-fleet verification instead of manual checks
./scripts/ci/restore-drill.sh --source s3 --bucket "$S3_BUCKET"
```

RTO target: < 30 min for the full fleet (drill timing printed by the script);
single-DB restore is minutes.
