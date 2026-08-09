#!/bin/bash
# ============================================
# Wait for MySQL and Redis - Proper connection check
# ============================================

set -e

DB_HOST="${DB_HOST:-mysql}"
DB_PORT="${DB_PORT:-3306}"
DB_USERNAME="${DB_USERNAME:-bhukkad_user}"
DB_PASSWORD="${DB_PASSWORD:-bhukkad_pass}"
DB_NAME="${DB_NAME:-bhukkad}"
REDIS_HOST="${REDIS_HOST:-redis}"
REDIS_PORT="${REDIS_PORT:-6379}"
MAX_WAIT=180
WAIT_INTERVAL=5

GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m'

log()     { echo "[$(date '+%H:%M:%S')] $1"; }
success() { echo "[$(date '+%H:%M:%S')] ${GREEN}✅ $1${NC}"; }
warn()    { echo "[$(date '+%H:%M:%S')] ${YELLOW}⏳ $1${NC}"; }
error()   { echo "[$(date '+%H:%M:%S')] ${RED}❌ $1${NC}"; exit 1; }

# ==================== Wait for MySQL ====================
wait_for_mysql() {
    local elapsed=0
    log "Waiting for MySQL at $DB_HOST:$DB_PORT..."

    while true; do
        if [ $elapsed -ge $MAX_WAIT ]; then
            error "MySQL not ready after ${MAX_WAIT}s. Giving up."
        fi

        # Try actual MySQL connection using mysqladmin
        if mysqladmin ping \
            -h "$DB_HOST" \
            -P "$DB_PORT" \
            -u "$DB_USERNAME" \
            --password="$DB_PASSWORD" \
            --connect-timeout=5 \
            --silent 2>/dev/null; then
            success "MySQL is accepting connections!"
            break
        fi

        warn "MySQL not ready yet. Retrying in ${WAIT_INTERVAL}s... (${elapsed}s elapsed)"
        sleep $WAIT_INTERVAL
        elapsed=$((elapsed + WAIT_INTERVAL))
    done

    # Extra safety wait for MySQL to fully initialize
    log "Waiting 5 more seconds for MySQL to fully initialize..."
    sleep 5
    success "MySQL is fully ready!"
}

# ==================== Wait for Redis ====================
wait_for_redis() {
    local elapsed=0
    log "Waiting for Redis at $REDIS_HOST:$REDIS_PORT..."

    while true; do
        if [ $elapsed -ge $MAX_WAIT ]; then
            error "Redis not ready after ${MAX_WAIT}s. Giving up."
        fi

        # Try actual Redis ping
        REDIS_RESPONSE=$(redis-cli \
            -h "$REDIS_HOST" \
            -p "$REDIS_PORT" \
            ping 2>/dev/null || echo "FAILED")

        if [ "$REDIS_RESPONSE" = "PONG" ]; then
            success "Redis is accepting connections!"
            break
        fi

        warn "Redis not ready yet ($REDIS_RESPONSE). Retrying in ${WAIT_INTERVAL}s... (${elapsed}s elapsed)"
        sleep $WAIT_INTERVAL
        elapsed=$((elapsed + WAIT_INTERVAL))
    done
}

# ==================== Main ====================
echo ""
echo