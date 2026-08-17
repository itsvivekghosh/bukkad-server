#!/usr/bin/env bash
# Repair Flyway schema history on staging before deploy (failed migrations + checksum realignment).
set -euo pipefail

DB_URL="${1:?JDBC URL required}"
DB_USER="${2:?DB user required}"
DB_PASSWORD="${3:?DB password required}"
MIGRATIONS_DIR="${4:?Migrations directory required}"

if [ ! -d "${MIGRATIONS_DIR}" ]; then
  echo "Migrations directory not found: ${MIGRATIONS_DIR}"
  exit 1
fi

echo "Running Flyway repair against staging database..."
docker run --rm \
  -v "${MIGRATIONS_DIR}:/flyway/sql:ro" \
  flyway/flyway:10-alpine \
  -url="${DB_URL}" \
  -user="${DB_USER}" \
  -password="${DB_PASSWORD}" \
  -locations="filesystem:/flyway/sql" \
  repair

echo "Flyway repair completed"
