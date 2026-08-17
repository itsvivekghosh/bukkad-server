#!/usr/bin/env bash
# Run Flyway repair on EC2 — RDS is reachable from the app host, not GitHub runners.
set -euo pipefail

SSH_USER="${1:?SSH user required}"
SSH_HOST="${2:?SSH host required}"
SSH_KEY="${3:?SSH key path required}"
MIGRATIONS_DIR="${4:?Migrations directory required}"
ENV_FILE="${5:?Env file path required}"

SSH_OPTS=(-i "$SSH_KEY" -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o ConnectTimeout=30)
REMOTE="${SSH_USER}@${SSH_HOST}"

if [ ! -d "${MIGRATIONS_DIR}" ]; then
  echo "Migrations directory not found: ${MIGRATIONS_DIR}"
  exit 1
fi

echo "Repairing Flyway schema history via ${REMOTE}..."
ssh "${SSH_OPTS[@]}" "$REMOTE" "rm -rf /tmp/flyway-sql && mkdir -p /tmp/flyway-sql"
scp -r "${SSH_OPTS[@]}" "${MIGRATIONS_DIR}/." "${REMOTE}:/tmp/flyway-sql/"
base64 "$ENV_FILE" | ssh "${SSH_OPTS[@]}" "$REMOTE" "base64 -d > /tmp/flyway-env.txt && chmod 600 /tmp/flyway-env.txt"

ssh "${SSH_OPTS[@]}" "$REMOTE" "MIGRATIONS_REMOTE=/tmp/flyway-sql bash -s" <<'REMOTE_SCRIPT'
set -euo pipefail
set -a && source /tmp/flyway-env.txt && set +a

DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-3306}"
DB_NAME="${DB_NAME:-bhukkad}"

if [ -z "${DB_URL:-}" ]; then
  DB_URL="jdbc:mysql://${DB_HOST}:${DB_PORT}/${DB_NAME}?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true"
fi

echo "Running Flyway repair (db=${DB_NAME})..."
docker run --rm \
  -v "${MIGRATIONS_REMOTE}:/flyway/sql:ro" \
  flyway/flyway:10-alpine \
  -url="${DB_URL}" \
  -user="${DB_USERNAME}" \
  -password="${DB_PASSWORD}" \
  -locations="filesystem:/flyway/sql" \
  repair

rm -rf /tmp/flyway-sql /tmp/flyway-env.txt
echo "Flyway repair completed"
REMOTE_SCRIPT
