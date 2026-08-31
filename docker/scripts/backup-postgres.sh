#!/bin/bash
# =============================================================================
# Bhukkad PostgreSQL Backup Script
# =============================================================================
# Creates a compressed PostgreSQL dump with a timestamp, retains 30 daily backups,
# and uploads to S3 (when AWS_CREDENTIALS are set). Intended for docker
# cron/backup service or kubernetes CronJob.
#
# Usage:
#   docker run --rm -v backup_volume:/backup \
#     -e DB_HOST=postgres -e DB_PORT=5432 -e DB_USERNAME=bhukkad \
#     -e DB_PASSWORD=... -e BACKUP_DIR=/backup \
#     postgres:16-alpine bash /backup/backup-postgres.sh
#
# Env variables:
#   DB_HOST       - PostgreSQL host (default: postgres)
#   DB_PORT       - PostgreSQL port (default: 5432)
#   DB_USERNAME   - PostgreSQL user (default: bhukkad)
#   DB_PASSWORD   - PostgreSQL password (required)
#   DB_NAME       - Database name (default: bhukkad)
#   BACKUP_DIR    - Output directory (default: /backup)
#   RETENTION_DAYS - Days to keep local backups (default: 30)
#   S3_BUCKET     - S3 bucket for off-site (optional)
# =============================================================================
set -euo pipefail

DB_HOST="${DB_HOST:-postgres}"
DB_PORT="${DB_PORT:-5432}"
DB_USERNAME="${DB_USERNAME:-bhukkad}"
DB_PASSWORD="${DB_PASSWORD:?DB_PASSWORD is required}"
DB_NAME="${DB_NAME:-bhukkad}"
BACKUP_DIR="${BACKUP_DIR:-/backup}"
RETENTION_DAYS="${RETENTION_DAYS:-30}"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
FILENAME="bhukkad_postgres_${TIMESTAMP}.sql.gz"

mkdir -p "${BACKUP_DIR}"

echo "[$(date)] Starting PostgreSQL backup: ${DB_NAME}@${DB_HOST}:${DB_PORT}"

PGPASSWORD="${DB_PASSWORD}" pg_dump \
  --host="${DB_HOST}" \
  --port="${DB_PORT}" \
  --username="${DB_USERNAME}" \
  --dbname="${DB_NAME}" \
  --format=plain \
  --no-owner \
  --no-acl \
  | gzip > "${BACKUP_DIR}/${FILENAME}"

echo "[$(date)] Backup created: ${BACKUP_DIR}/${FILENAME} ($(du -h "${BACKUP_DIR}/${FILENAME}" | cut -f1))"

# Rotate old backups
find "${BACKUP_DIR}" -name "bhukkad_postgres_*.sql.gz" -mtime +${RETENTION_DAYS} -delete
echo "[$(date)] Rotated backups older than ${RETENTION_DAYS} days"

# Upload to S3 if configured
if [ -n "${S3_BUCKET:-}" ]; then
  echo "[$(date)] Uploading to S3: ${S3_BUCKET}/postgres/${FILENAME}"
  aws s3 cp "${BACKUP_DIR}/${FILENAME}" "s3://${S3_BUCKET}/postgres/${FILENAME}" --no-progress
  echo "[$(date)] S3 upload complete"
fi

echo "[$(date)] PostgreSQL backup finished successfully"
