#!/usr/bin/env bash
#
# End-to-End (E2E) system verification for Bhukkad.
#
# Brings up the production-parity stack (MySQL 8 + Redis 7 + the bhukkad app)
# via docker/docker-compose.yml, waits for readiness, then exercises the FULL
# user journey and cross-service interoperability with docker/scripts/test-all-apis.sh
# (which issues every API request and asserts status codes / response schemas).
#
# Usage:
#   ./scripts/e2e-smoke.sh
#
# Prerequisites: Docker + Docker Compose v2, `mvn` to build the image, `curl`.
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
COMPOSE="$ROOT_DIR/docker/docker-compose.yml"
API_TEST="$ROOT_DIR/docker/scripts/test-all-apis.sh"
HOST="${E2E_HOST:-localhost}"
PORT="${E2E_PORT:-8080}"

echo "==> Building application image"
( cd "$ROOT_DIR" && ./mvnw -q -DskipTests package )
docker compose -f "$COMPOSE" build app

echo "==> Starting production-parity stack (mysql, redis, app)"
docker compose -f "$COMPOSE" up -d mysql redis
# give mysql/redis a moment before the app depends on them
sleep 20
docker compose -f "$COMPOSE" up -d app

echo "==> Waiting for /health/ping"
for i in $(seq 1 30); do
  if curl -fs "http://$HOST:$PORT/api/v1/health/ping" >/dev/null 2>&1; then
    echo "    app is up after ${i} tries"
    break
  fi
  sleep 5
done

echo "==> Running full API journey (cross-service interoperability)"
bash "$API_TEST" "$HOST" "$PORT"

RESULT=$?
echo "==> API test exit code: $RESULT"

echo "==> Tearing down stack"
docker compose -f "$COMPOSE" down -v || true

exit $RESULT
