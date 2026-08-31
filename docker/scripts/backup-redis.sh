#!/bin/bash
# =============================================================================
# Bhukkad Redis Backup Script
# =============================================================================
# Triggers a Redis BGSAVE and archives the resulting RDB (plus AOF when
# enabled), retains 7 daily backups, and optionally uploads to S3.
#
# Usage:
#   docker run --rm -v backup_volume:/backup -v redis_data:/data \
#     -e REDIS_HOST=redis -e REDIS_PORT=6379 -e REDIS_PASSWORD=... \
#     -e BACKUP_DIR=/backup -e REDIS_DATA_DIR=/data \
#     redis:7-alpine bash /backup/backup-redis.sh
#
# Env variables:
#   REDIS_HOST     - Redis host (default: redis)
#   REDIS_PORT     - Redis port (default: 6379)
#   REDIS_PASSWORD - Redis password (optional)
#   REDIS_DATA_DIR - Directory containing dump.rdb (default: /data)
#   BACKUP_DIR     - Output directory (default: /backup)
#   RETENTION_DAYS - Days to keep local backups (default: 7)
#   S3_BUCKET      - S3 bucket for off-site (optional)
# =============================================================================
set -euo pipefail

REDIS_HOST="${REDIS_HOST:-redis}"
REDIS_PORT="${REDIS_PORT:-6379}"
REDIS_PASSWORD="${REDIS_PASSWORD:-}"
REDIS_DATA_DIR="${REDIS_DATA_DIR:-/data}"
BACKUP_DIR="${BACKUP_DIR:-/backup}"
RETENTION_DAYS="${RETENTION_DAYS:-7}"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)

mkdir -p "${BACKUP_DIR}"

echo "[$(date)] Starting Redis backup: ${REDIS_HOST}:${REDIS_PORT}"

# Auth args if a password is configured
AUTH_ARGS=()
if [ -n "${REDIS_PASSWORD}" ]; then
  AUTH_ARGS=(-a "${REDIS_PASSWORD}")
fi

# Trigger a save (BGSAVE forks a child; poll LASTSAVE until it advances so the
# RDB on disk is complete before we archive it).
redis-cli "${AUTH_ARGS[@]}" -h "${REDIS_HOST}" -p "${REDIS_PORT}" BGSAVE >/dev/null
echo "[$(date)] BGSAVE triggered, waiting for it to complete..."
BEFORE=$(redis-cli "${AUTH_ARGS[@]}" -h "${REDIS_HOST}" -p "${REDIS_PORT}" LASTSAVE)
for _ in $(seq 1 60); do
  AFTER=$(redis-cli "${AUTH_ARGS[@]}" -h "${REDIS_HOST}" -p "${REDIS_PORT}" LASTSAVE)
  if [ -n "${AFTER}" ] && [ "${AFTER}" -gt "${BEFORE}" ]; then
    break
  fi
  sleep 1
done

# Archive the RDB (and AOF if present)
RDB_FILE="${REDIS_DATA_DIR}/dump.rdb"
if [ -f "${RDB_FILE}" ]; then
  cp "${RDB_FILE}" "${BACKUP_DIR}/bhukkad_redis_${TIMESTAMP}.rdb"
  echo "[$(date)] RDB archived: ${BACKUP_DIR}/bhukkad_redis_${TIMESTAMP}.rdb ($(du -h "${BACKUP_DIR}/bhukkad_redis_${TIMESTAMP}.rdb" | cut -f1))"
fi

AOF_FILE="${REDIS_DATA_DIR}/appendonly.aof"
if [ -f "${AOF_FILE}" ]; then
  cp "${AOF_FILE}" "${BACKUP_DIR}/bhukkad_redis_${TIMESTAMP}.aof"
  echo "[$(date)] AOF archived: ${BACKUP_DIR}/bhukkad_redis_${TIMESTAMP}.aof"
fi

# Rotate old backups
find "${BACKUP_DIR}" \( -name "bhukkad_redis_*.rdb" -o -name "bhukkad_redis_*.aof" \) -mtime +${RETENTION_DAYS} -delete
echo "[$(date)] Rotated backups older than ${RETENTION_DAYS} days"

# Upload to S3 if configured
if [ -n "${S3_BUCKET:-}" ]; then
  echo "[$(date)] Uploading to S3: ${S3_BUCKET}/redis/${TIMESTAMP}"
  aws s3 cp "${BACKUP_DIR}" "s3://${S3_BUCKET}/redis/${TIMESTAMP}/" --recursive --exclude "*.tmp" --no-progress
  echo "[$(date)] S3 upload complete"
fi

echo "[$(date)] Redis backup finished successfully"