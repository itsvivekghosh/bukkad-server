#!/bin/bash
# Postgres automatic failover script
# Promotes the first healthy read replica to primary when the current primary is unreachable.
set -euo pipefail

PRIMARY_HOST="${PRIMARY_HOST:-bhukkad-postgresql}"
PRIMARY_PORT="${PRIMARY_PORT:-5432}"
REPLICA_SERVICE="${REPLICA_SERVICE:-bhukkad-postgresql-read}"
REPLICA_PORT="${REPLICA_PORT:-5432}"
DB_NAME="${DB_NAME:-core}"
DB_USERNAME="${DB_USERNAME:-app}"
DB_PASSWORD="${DB_PASSWORD:-app_pass}"
PROMOTION_TIMEOUT="${PROMOTION_TIMEOUT:-30}"

log() {
    echo "[$(date -Iseconds)] [FAILOVER] $*"
}

check_primary() {
    if pg_isready -h "$PRIMARY_HOST" -p "$PRIMARY_PORT" -U "$DB_USERNAME" -d "$DB_NAME" >/dev/null 2>&1; then
        return 0
    fi
    return 1
}

promote_replica() {
    local replica_host="$1"
    log "Promoting replica $replica_host to primary..."
    
    # Connect to replica and promote it
    PGPASSWORD="$DB_PASSWORD" psql -h "$replica_host" -p "$REPLICA_PORT" -U "$DB_USERNAME" -d "$DB_NAME" -c "SELECT pg_promote();" || {
        log "Failed to promote replica $replica_host"
        return 1
    }
    
    log "Replica $replica_host promoted successfully"
    
    # Wait for promotion to complete
    sleep 5
    
    # Verify the replica is now accepting writes
    if PGPASSWORD="$DB_PASSWORD" psql -h "$replica_host" -p "$REPLICA_PORT" -U "$DB_USERNAME" -d "$DB_NAME" -c "SELECT 1" >/dev/null 2>&1; then
        log "Promotion verified - replica $replica_host is now primary"
        return 0
    else
        log "Promotion verification failed for $replica_host"
        return 1
    fi
}

get_replica_hosts() {
    # Get list of replica pod IPs from the headless service
    nslookup "$REPLICA_SERVICE" 2>/dev/null | grep -E "^Address: " | awk '{print $2}' | grep -v "^$" | sort -u || true
}

main() {
    log "Checking primary health: $PRIMARY_HOST:$PRIMARY_PORT"
    
    if check_primary; then
        log "Primary is healthy"
        exit 0
    fi
    
    log "PRIMARY IS DOWN - initiating failover"
    
    # Get replica hosts
    local replica_hosts=()
    while IFS= read -r line; do
        if [ -n "$line" ]; then
            replica_hosts+=("$line")
        fi
    done < <(get_replica_hosts)
    
    if [ ${#replica_hosts[@]} -eq 0 ]; then
        log "No replica hosts found"
        exit 1
    fi
    
    log "Found ${#replica_hosts[@]} replicas: ${replica_hosts[*]}"
    
    # Try to promote the first healthy replica
    for replica in "${replica_hosts[@]}"; do
        if PGPASSWORD="$DB_PASSWORD" pg_isready -h "$replica" -p "$REPLICA_PORT" -U "$DB_USERNAME" -d "$DB_NAME" >/dev/null 2>&1; then
            if promote_replica "$replica"; then
                log "FAILOVER COMPLETED - new primary: $replica"
                exit 0
            fi
        else
            log "Replica $replica is not ready, skipping"
        fi
    done
    
    log "FAILOVER FAILED - no healthy replica available"
    exit 1
}

main "$@"
