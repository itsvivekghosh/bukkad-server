#!/usr/bin/env bash
# Run Bhukkad API locally (Maven + host MySQL/Redis — no Docker/Kubernetes).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

export SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-dev}"
export DB_HOST="${DB_HOST:-localhost}"
export DB_PORT="${DB_PORT:-3306}"
export DB_NAME="${DB_NAME:-bhukkad}"
export DB_USERNAME="${DB_USERNAME:-root}"
export DB_PASSWORD="${DB_PASSWORD:-root}"
export REDIS_HOST="${REDIS_HOST:-localhost}"
export REDIS_PORT="${REDIS_PORT:-6379}"
export SERVER_PORT="${SERVER_PORT:-8080}"
export FRAUD_BLOCKING_ENABLED="${FRAUD_BLOCKING_ENABLED:-false}"

echo "Starting Bhukkad on http://localhost:${SERVER_PORT} (profile=${SPRING_PROFILES_ACTIVE})"
echo "MySQL: ${DB_USERNAME}@${DB_HOST}:${DB_PORT}/${DB_NAME}"
echo "Redis: ${REDIS_HOST}:${REDIS_PORT}"
echo ""

exec ./mvnw spring-boot:run -DskipTests "$@"
