#!/usr/bin/env bash
#
# Run the Bhukkad LOCAL stack (microservices on host JVMs + Dockerized infra).
#
# The legacy monolith was removed in 49c63b4 ("Restructure to microservices") —
# there is no root POM anymore, so every invocation brings up the microservices
# stack instead:
#
#   1. Infrastructure: PostgreSQL (services/docker/docker-compose.dev.yml) and
#      Redis (an already-running instance on :6379 is reused; otherwise Redis
#      is started in docker too). Per-service databases are created by the
#      compose initdb on first postgres boot.
#   2. Build: `./mvnw -f services/pom.xml package -DskipTests` when any
#      requested service's fat jar is missing.
#   3. Launch: scripts/local-up.sh starts the host JVMs with the mesh URLs,
#      JWT secrets and profile settings the services expect, waits for every
#      service to report UP on /actuator/health (logs under
#      /tmp/bhukkad-local) and then stays attached until the stack is stopped
#      (Ctrl+C from a terminal).
#
# Usage examples:
#   ./scripts/run-local.sh                                # core stack + gateway
#   ./scripts/run-local.sh identity restaurant gateway    # subset (canonical ports kept)
#   ./scripts/run-local.sh identity search survey referral notification supportticket \
#       admin-analytics personalization growth restaurant order payment delivery realtime gateway
#                                                         # full 15-JVM stack
#   ./scripts/run-local.sh --create-databases             # init DBs on an already-running PG
#   ./scripts/run-local.sh --microservices ...            # legacy flag, accepted as no-op
#
# Stop: Ctrl+C (stops the JVMs with it) or ./scripts/local-down.sh from another shell.
# Tear down infra: docker compose -f services/docker/docker-compose.dev.yml down
#
# Prerequisites: Java 17, Docker; the full 15-JVM stack fits a ~4 GB heap budget
# (lean JVM options are set in local-up.sh).
# This script uses bash arrays. If invoked via a plain POSIX sh
# (`sh scripts/run-local.sh` on systems where sh != bash), re-exec under bash.
if [ -z "${BASH_VERSION:-}" ]; then
    exec bash "$0" "$@"
fi
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

COMPOSE_FILE="services/docker/docker-compose.dev.yml"

port_open() { nc -z -G 2 localhost "$1" >/dev/null 2>&1; }

# ── Database setup (optional; the compose initdb does this on first boot) ──
create_pg_databases() {
    echo "Creating PostgreSQL databases for each service..."
    local psql_cmd
    if command -v psql &>/dev/null; then
        psql_cmd="psql"
    elif [ -x /opt/homebrew/bin/psql ]; then
        psql_cmd="/opt/homebrew/bin/psql"
    elif [ -x /usr/local/bin/psql ]; then
        psql_cmd="/usr/local/bin/psql"
    else
        echo "ERROR: psql not found. Start the compose stack instead (it creates the DBs itself)."
        exit 1
    fi

    export PGPASSWORD="${PGPASSWORD:-app_pass}"   # matches compose POSTGRES_PASSWORD
    local db_user="${PGUSER:-app}"
    local db_host="${PGHOST:-localhost}"
    local db_port="${PGPORT:-5432}"

    local dbs=(
        identity restaurants orders payments delivery notification admin
        search survey referral support realtime personalization growth
    )

    for db in "${dbs[@]}"; do
        echo "  Creating database: $db"
        "$psql_cmd" -h "$db_host" -p "$db_port" -U "$db_user" \
            -c "SELECT 'CREATE DATABASE $db' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = '$db')\gexec" \
            postgres 2>/dev/null || echo "  ($db may already exist — skipping)"
    done
    echo "Database setup complete."
}

# ── Argument parsing ─────────────────────────────────────────────────
CREATE_DBS=false
SERVICE_NAMES=()

while [[ $# -gt 0 ]]; do
    case "$1" in
        --microservices|--monolith)
            # Flags kept for backward compatibility. The monolith no longer
            # exists; the local path is the microservices stack either way.
            shift
            ;;
        --create-databases)
            CREATE_DBS=true
            shift
            ;;
        --help|-h)
            sed -n '2,36p' "$0"
            exit 0
        ;;
        *)
            SERVICE_NAMES+=("$1")
            shift
            ;;
    esac
done

# ── 1. Infrastructure ────────────────────────────────────────────────
if port_open 5432; then
    echo "== PostgreSQL already listening on :5432 =="
else
    echo "== Starting PostgreSQL via docker compose =="
    docker compose -f "$COMPOSE_FILE" up -d postgres
    for _ in $(seq 1 60); do
        status="$(docker inspect --format='{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' backend-postgres 2>/dev/null || echo none)"
        [ "$status" = "healthy" ] && break
        sleep 2
    done
    if port_open 5432; then
        echo "== PostgreSQL listening on :5432 =="
    else
        echo "ERROR: PostgreSQL did not come up on :5432 (docker inspect status: ${status:-unknown})"
        exit 1
    fi
fi

if port_open 6379; then
    echo "== Redis already listening on :6379 (reusing it) =="
else
    echo "== Starting Redis via docker compose =="
    docker compose -f "$COMPOSE_FILE" up -d redis
fi

if $CREATE_DBS; then
    create_pg_databases
fi

# ── 2. Ensure service jars exist ─────────────────────────────────────
# Default = the core commerce slice this script has always documented
# (identity restaurant order payment delivery notification admin-analytics)
# plus the gateway, so it boots on an 8 GB laptop. Name services explicitly
# (or use scripts/local-up.sh) for the full 15-JVM stack.
CORE_MODULES=(identity restaurant order payment delivery notification
              admin-analytics gateway)
if [ "${#SERVICE_NAMES[@]}" -gt 0 ]; then
    MODULES=("${SERVICE_NAMES[@]}")
else
    MODULES=("${CORE_MODULES[@]}")
fi

missing=false
for m in "${MODULES[@]}"; do
    if [ ! -f "services/$m/target/$m-1.0.0.jar" ]; then
        echo "  missing jar: $m"
        missing=true
    fi
done
if [ "$missing" = true ]; then
    echo "== Building service jars (./mvnw -f services/pom.xml package -DskipTests) =="
    ./mvnw -f services/pom.xml package -DskipTests -q
fi

# ── 3. Launch the host stack (delegates to scripts/local-up.sh) ──────
if [ ! -f scripts/local-up.sh ]; then
    echo "ERROR: scripts/local-up.sh is missing — cannot launch the stack."
    exit 1
fi
if [ "${#SERVICE_NAMES[@]}" -gt 0 ]; then
    exec bash scripts/local-up.sh "${SERVICE_NAMES[@]}"
else
    exec bash scripts/local-up.sh "${CORE_MODULES[@]}"
fi
