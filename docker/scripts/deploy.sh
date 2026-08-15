#!/usr/bin/env bash
set -euo pipefail

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

log() { echo -e "${GREEN}[$(date '+%H:%M:%S')] $1${NC}"; }
warn() { echo -e "${YELLOW}[$(date '+%H:%M:%S')] $1${NC}"; }
error() { echo -e "${RED}[$(date '+%H:%M:%S')] $1${NC}"; exit 1; }

wait_for_healthy() {
  local label=$1
  local container=$2
  local timeout=${3:-120}
  local elapsed=0
  log "Waiting for ${label} (up to ${timeout}s)..."
  while [[ $elapsed -lt $timeout ]]; do
    local status
    status=$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$container" 2>/dev/null || echo "missing")
    case "$status" in
      healthy)
        log "${label} is healthy"
        return 0
        ;;
      unhealthy)
        warn "${label} is unhealthy"
        return 1
        ;;
      missing)
        warn "${label} container not found (${container})"
        return 1
        ;;
      *)
        if (( elapsed > 0 && elapsed % 10 == 0 )); then
          warn "Still waiting for ${label}... ${elapsed}s (status: ${status})"
        fi
        sleep 2
        elapsed=$((elapsed + 2))
        ;;
    esac
  done
  warn "${label} did not become healthy within ${timeout}s"
  return 1
}

resolve_container_names() {
  if [[ "$PROFILE" == "prod" ]]; then
    MYSQL_CONTAINER="bhukkad-mysql-prod"
    REDIS_CONTAINER="bhukkad-redis-prod"
    APP_CONTAINER="bhukkad-app-prod"
  else
    MYSQL_CONTAINER="bhukkad-mysql-dev"
    REDIS_CONTAINER="bhukkad-redis-dev"
    REDPANDA_CONTAINER="bhukkad-redpanda-dev"
    APP_CONTAINER="bhukkad-app-dev"
  fi
}

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DOCKER_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
PROJECT_ROOT="$(cd "$DOCKER_DIR/.." && pwd)"
COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.dev.yml}"
PROFILE="${PROFILE:-dev}"
CLEAN="${CLEAN:-false}"
SKIP_BUILD="${SKIP_BUILD:-false}"

usage() {
  cat <<EOF
Usage: $(basename "$0") [options]

Options:
  --prod           Use docker-compose.prod.yml
  --clean          docker compose down -v before start
  --skip-build     Skip Maven + image build
  --no-tools       Skip redis-commander/phpmyadmin (dev only)
  -h, --help       Show help
EOF
}

WITH_TOOLS=true
while [[ $# -gt 0 ]]; do
  case "$1" in
    --prod) COMPOSE_FILE="docker-compose.prod.yml"; PROFILE="prod"; shift ;;
    --clean) CLEAN=true; shift ;;
    --skip-build) SKIP_BUILD=true; shift ;;
    --no-tools) WITH_TOOLS=false; shift ;;
    -h|--help) usage; exit 0 ;;
    *) error "Unknown option: $1" ;;
  esac
done

cd "$DOCKER_DIR"
export DOCKER_BUILDKIT=1
export COMPOSE_DOCKER_CLI_BUILD=1
resolve_container_names

if [[ "$CLEAN" == "true" ]]; then
  warn "Removing existing stack and volumes..."
  docker compose -f "$COMPOSE_FILE" down -v --remove-orphans || true
fi

if [[ "$SKIP_BUILD" != "true" ]]; then
  log "Building JAR..."
  (cd "$PROJECT_ROOT" && mvn -q -B clean package -DskipTests)
  log "Building application image..."
  docker compose -f "$COMPOSE_FILE" build app
else
  log "Skipping build"
fi

log "Starting infrastructure (MySQL, Redis, Redpanda)..."
if [[ "$PROFILE" == "dev" ]]; then
  docker compose -f "$COMPOSE_FILE" up -d mysql redis redpanda
else
  docker compose -f "$COMPOSE_FILE" up -d mysql redis
fi
if ! wait_for_healthy "MySQL" "$MYSQL_CONTAINER" 180; then
  docker logs "$MYSQL_CONTAINER" 2>&1 | tail -25 || true
  error "MySQL failed health check. Retry with: $(basename "$0") --clean"
fi
if ! wait_for_healthy "Redis" "$REDIS_CONTAINER" 60; then
  docker logs "$REDIS_CONTAINER" 2>&1 | tail -15 || true
  error "Redis failed health check."
fi
if [[ "$PROFILE" == "dev" ]]; then
  if ! wait_for_healthy "Redpanda" "$REDPANDA_CONTAINER" 120; then
    docker logs "$REDPANDA_CONTAINER" 2>&1 | tail -25 || true
    error "Redpanda failed health check."
  fi
fi

log "Starting application..."
docker compose -f "$COMPOSE_FILE" up -d --no-deps app
if ! wait_for_healthy "Application" "$APP_CONTAINER" 180; then
  warn "App health check pending. Tail logs: docker logs -f $APP_CONTAINER"
fi

if [[ "$PROFILE" == "dev" && "$WITH_TOOLS" == "true" ]]; then
  log "Starting dev tools..."
  docker compose -f "$COMPOSE_FILE" --profile tools up -d redis-commander phpmyadmin || true
fi

log "Stack status"
docker compose -f "$COMPOSE_FILE" ps

HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/api/v1/health/ping || echo "000")
if [[ "$HTTP_CODE" == "200" ]]; then
  log "Health check passed"
else
  warn "Health check returned ${HTTP_CODE}. Tail logs: docker logs -f $APP_CONTAINER"
fi

cat <<EOF

Endpoints:
  API:        http://localhost:8080/api/v1/health/ping
  Swagger:    http://localhost:8080/swagger-ui.html
EOF

if [[ "$PROFILE" == "dev" ]]; then
  cat <<EOF
  Redis UI:   http://localhost:8081
  phpMyAdmin: http://localhost:8082
EOF
fi
