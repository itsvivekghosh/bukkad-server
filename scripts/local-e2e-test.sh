#!/usr/bin/env bash
# Local E2E Test Runner
# Usage: ./scripts/local-e2e-test.sh [core|full]
#   core - runs only identity + gateway (minimal, ~2GB RAM)
#   full - runs all 5 services (~4GB RAM)

set -euo pipefail

MODE="${1:-core}"
COMPOSE_FILE="services/docker/docker-compose.local.yml"
export COMPOSE_PROFILES="${COMPOSE_PROFILES:-full}"
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

echo "=== Bhukkad Local E2E Test Runner ==="
echo "Mode: $MODE"
echo "Project: $PROJECT_DIR"
echo ""

# Check prerequisites
command -v docker >/dev/null 2>&1 || { echo "docker not found"; exit 1; }
docker compose version >/dev/null 2>&1 || { echo "docker compose v2 not found"; exit 1; }

# Set profile
if [[ "$MODE" == "full" ]]; then
    export COMPOSE_PROFILES="full"
    echo "Starting FULL stack (identity, commerce, catalog, engagement, admin, gateway)..."
else
    export COMPOSE_PROFILES="core"
    echo "Starting CORE stack (identity, gateway)..."
fi

# Start infrastructure
echo "Starting infrastructure..."
cd "$PROJECT_DIR"
docker compose -f "$COMPOSE_FILE" up -d postgres redis

# Wait for postgres
echo "Waiting for PostgreSQL..."
for i in {1..30}; do
    if docker compose -f "$COMPOSE_FILE" exec -T postgres pg_isready -U app -d core >/dev/null 2>&1; then
        echo "PostgreSQL ready"
        break
    fi
    sleep 2
done

# Wait for redis
echo "Waiting for Redis..."
for i in {1..15}; do
    if docker compose -f "$COMPOSE_FILE" exec -T redis redis-cli ping >/dev/null 2>&1; then
        echo "Redis ready"
        break
    fi
    sleep 1
done

# Build and start services
echo "Building and starting services (profile: $COMPOSE_PROFILES)..."
docker compose -f "$COMPOSE_FILE" build --parallel
docker compose -f "$COMPOSE_FILE" up -d

# Wait for services to be healthy
echo "Waiting for services to be healthy..."
SERVICES=("identity" "gateway")
if [[ "$MODE" == "full" ]]; then
    SERVICES+=("commerce" "catalog" "engagement" "admin")
fi

for svc in "${SERVICES[@]}"; do
    echo "Waiting for $svc..."
    for i in {1..60}; do
        if docker compose -f "$COMPOSE_FILE" exec -T "$svc" curl -sf "http://localhost:8080/actuator/health/liveness" >/dev/null 2>&1; then
            echo "$svc is healthy"
            break
        fi
        sleep 3
    done
done

# Wait for gateway
echo "Waiting for gateway..."
for i in {1..60}; do
    if curl -sf "http://localhost:8095/actuator/health/liveness" >/dev/null 2>&1; then
        echo "Gateway is healthy"
        break
    fi
    sleep 3
done

echo ""
echo "=== Running Unit Tests ==="
cd "$PROJECT_DIR"
mvn test -f services/pom.xml -Djacoco.skip=true -DfailIfNoTests=false -q

echo ""
echo "=== Running Integration Tests ==="
if [[ "$MODE" == "full" ]]; then
    mvn verify -f services/pom.xml -Djacoco.skip=true -DfailIfNoTests=false -Dtest="*IntegrationTest" -q || true
fi

echo ""
echo ""
echo "=== Running k6 Load Test (smoke) ==="
if command -v k6 >/dev/null 2>&1; then
    cd "$PROJECT_DIR/loadtest"
    k6 run --vus 5 --duration 30s -e BASE_URL=http://localhost:8095 peak-5x.js || true
else
    echo "k6 not installed. Install from https://k6.io/docs/getting-started/installation/"
fi

echo ""
echo "=== Test Summary ==="
echo "Services running:"
docker compose -f "$COMPOSE_FILE" ps --format "table {{.Name}}\t{{.Status}}\t{{.Ports}}"

echo ""
echo "To stop: docker compose -f $COMPOSE_FILE down"
echo "To view logs: docker compose -f $COMPOSE_FILE logs -f"