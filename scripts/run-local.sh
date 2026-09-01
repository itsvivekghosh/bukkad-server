#!/usr/bin/env bash
#
# Run Bhukkad services locally without Docker/Kubernetes.
#
# Two modes:
#
#   1. Monolith (default):
#      ./scripts/run-local.sh
#      Runs the single Spring Boot app against MySQL + Redis on localhost.
#
#   2. Microservices:
#      ./scripts/run-local.sh --microservices [service1 service2 ...]
#      Runs the named services from services/ against PostgreSQL on localhost.
#      If no service names are given, all are started.
#
# Prerequisites for microservices mode:
#   - PostgreSQL 15+ running on localhost:5432
#   - One database per service (see --create-databases flag below)
#   - Java 17
#
# Usage examples:
#   ./scripts/run-local.sh                                    # monolith
#   ./scripts/run-local.sh --microservices                    # all services
#   ./scripts/run-local.sh --microservices identity restaurant payment  # subset
#   ./scripts/run-local.sh --microservices --create-databases  # init DBs then start
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# ── Database setup for microservices mode ────────────────────────────
create_pg_databases() {
    echo "Creating PostgreSQL databases for each service..."
    local psql_cmd
    if command -v psql &>/dev/null; then
        psql_cmd="psql"
    elif [ -x /usr/local/bin/psql ]; then
        psql_cmd="/usr/local/bin/psql"
    elif [ -x /opt/homebrew/bin/psql ]; then
        psql_cmd="/opt/homebrew/bin/psql"
    else
        echo "ERROR: psql not found. Install PostgreSQL or create databases manually."
        exit 1
    fi

    export PGPASSWORD="${PGPASSWORD:-bhukkad_dev}"
    local db_user="${PGUSER:-bhukkad}"
    local db_host="${PGHOST:-localhost}"
    local db_port="${PGPORT:-5432}"

    local dbs=(
        bhukkad_restaurants
        bhukkad_identity
        bhukkad_orders
        bhukkad_payments
        bhukkad_delivery
        bhukkad_notification
        bhukkad_admin
    )

    for db in "${dbs[@]}"; do
        echo "  Creating database: $db"
        "$psql_cmd" -h "$db_host" -p "$db_port" -U "$db_user" \
            -c "SELECT 'CREATE DATABASE $db' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = '$db')\gexec" \
            postgres 2>/dev/null || echo "  ($db may already exist — skipping)"
    done
    echo "Database setup complete."
}

# ── Microservices mode ─────────────────────────────────────────────────
run_microservice() {
    local module="$1"
    local port_offset="$2"
    local base_port=8090
    local port=$((base_port + port_offset))

    echo ""
    echo "=== Starting ${module} on port ${port} ==="
    local svc_name=""
    case "$module" in
        identity)       svc_name="bhukkad_identity" ;;
        restaurant)     svc_name="bhukkad_restaurants" ;;
        order)          svc_name="bhukkad_orders" ;;
        payment)        svc_name="bhukkad_payments" ;;
        delivery)       svc_name="bhukkad_delivery" ;;
        notification)   svc_name="bhukkad_notification" ;;
        admin-analytics) svc_name="bhukkad_admin" ;;
    esac
    echo "  Database: ${svc_name} (PostgreSQL)"

    IDENTITY_DB_URL="jdbc:postgresql://localhost:5432/bhukkad_identity" \
    IDENTITY_DB_USERNAME="bhukkad" \
    IDENTITY_DB_PASSWORD="bhukkad_dev" \
    RESTAURANT_DB_URL="jdbc:postgresql://localhost:5432/bhukkad_restaurants" \
    RESTAURANT_DB_USERNAME="bhukkad" \
    RESTAURANT_DB_PASSWORD="bhukkad_dev" \
    ORDER_DB_URL="jdbc:postgresql://localhost:5432/bhukkad_orders" \
    ORDER_DB_USERNAME="bhukkad" \
    ORDER_DB_PASSWORD="bhukkad_dev" \
    PAYMENT_DB_URL="jdbc:postgresql://localhost:5432/bhukkad_payments" \
    PAYMENT_DB_USERNAME="bhukkad" \
    PAYMENT_DB_PASSWORD="bhukkad_dev" \
    DELIVERY_DB_URL="jdbc:postgresql://localhost:5432/bhukkad_delivery" \
    DELIVERY_DB_USERNAME="bhukkad" \
    DELIVERY_DB_PASSWORD="bhukkad_dev" \
    NOTIFICATION_DB_URL="jdbc:postgresql://localhost:5432/bhukkad_notification" \
    NOTIFICATION_DB_USERNAME="bhukkad" \
    NOTIFICATION_DB_PASSWORD="bhukkad_dev" \
    ADMIN_DB_URL="jdbc:postgresql://localhost:5432/bhukkad_admin" \
    ADMIN_DB_USERNAME="bhukkad" \
    ADMIN_DB_PASSWORD="bhukkad_dev" \
    JWT_SECRET="dev-secret-change-me-0123456789abcdef0123456789abcdef" \
    SERVER_PORT="$port" \
    ./mvnw -f services/pom.xml -pl "$module" -am spring-boot:run -DskipTests &
    echo "  ${module} PID: $!"
}

# ── Argument parsing ─────────────────────────────────────────────────
MICROSERVICES_MODE=false
CREATE_DBS=false
SERVICE_NAMES=()

while [[ $# -gt 0 ]]; do
    case "$1" in
        --microservices)
            MICROSERVICES_MODE=true
            shift
            ;;
        --create-databases)
            CREATE_DBS=true
            shift
            ;;
        --help|-h)
            head -30 "$0"
            exit 0
            ;;
        *)
            SERVICE_NAMES+=("$1")
            shift
            ;;
    esac
done

# ── Main ─────────────────────────────────────────────────────────────
if [ "$MICROSERVICES_MODE" = true ]; then
    if [ "$CREATE_DBS" = true ]; then
        create_pg_databases
    fi

    # Default: start all services
    if [ ${#SERVICE_NAMES[@]} -eq 0 ]; then
        SERVICE_NAMES=(identity restaurant order payment delivery notification admin-analytics)
    fi

    echo "Starting ${#SERVICE_NAMES[@]} microservice(s)..."
    echo "Each service runs its own PostgreSQL database on localhost:5432"
    echo "nginx reverse proxy exposed at http://localhost:8088 (-> each service on :8080 internally)"
    echo ""

    # Port offsets: identity=0, restaurant=1, order=2, ...
    port_offset=0
    for svc in "${SERVICE_NAMES[@]}"; do
        run_microservice "$svc" "$port_offset"
        ((port_offset++))
    done

    echo ""
    echo "All services started. Press Ctrl+C to stop."
    echo "Ports: identity=8090, restaurant=8091, order=8092, payment=8093, delivery=8094, notification=8095, admin-analytics=8096"

    # Wait for all background PIDs
    wait
else
    # ── Monolith mode (original behavior) ───────────────────────────────
    export SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-dev}"
    export DB_HOST="${DB_HOST:-localhost}"
    export DB_PORT="${DB_PORT:-5432}"
    export DB_NAME="${DB_NAME:-bhukkad}"
    export DB_USERNAME="${DB_USERNAME:-bhukkad}"
    export DB_PASSWORD="${DB_PASSWORD:-bhukkad_dev}"
    export REDIS_HOST="${REDIS_HOST:-localhost}"
    export REDIS_PORT="${REDIS_PORT:-6379}"
    export SERVER_PORT="${SERVER_PORT:-8080}"
    export FRAUD_BLOCKING_ENABLED="${FRAUD_BLOCKING_ENABLED:-false}"

    echo "Starting Bhukkad monolith on http://localhost:${SERVER_PORT} (profile=${SPRING_PROFILES_ACTIVE})"
    echo "PostgreSQL: ${DB_USERNAME}@${DB_HOST}:${DB_PORT}/${DB_NAME}"
    echo "Redis: ${REDIS_HOST}:${REDIS_PORT}"
    echo ""

    exec ./mvnw spring-boot:run -DskipTests "$@"
fi
