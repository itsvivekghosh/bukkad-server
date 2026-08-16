#!/bin/bash
# ==============================================================================
# Bhukkad Backend - Cache Warm-up & Monitoring Script
#
# This script provides utilities for:
#   1. Pre-warming caches for hot data after server restart
#   2. Monitoring cache effectiveness
#   3. Reporting cache statistics
#
# Prerequisites:
#   - Redis CLI available and configured
#   - Application running at API_URL
#   - curl and jq installed
#
# Usage:
#   ./scripts/cache-warmup.sh [warmup|monitor|stats]
# ==============================================================================

set -euo pipefail

# Configuration
REDIS_HOST="${REDIS_HOST:-localhost}"
REDIS_PORT="${REDIS_PORT:-6379}"
REDIS_DB="${REDIS_DB:-0}"
API_URL="${API_URL:-http://localhost:8080}"
CACHE_TTL_ORDER="${CACHE_TTL_ORDER:-300}"
CACHE_TTL_KITCHEN_QUEUE="${CACHE_TTL_KITCHEN_QUEUE:-15}"
CACHE_TTL_HOME_FEED="${CACHE_TTL_HOME_FEED:-60}"
CACHE_TTL_SERVICEABILITY="${CACHE_TTL_SERVICEABILITY:-60}"

# Color codes
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

COMMAND="${1:-info}"

# Redis command builder
REDIS_CMD="redis-cli -h ${REDIS_HOST} -p ${REDIS_PORT} -n ${REDIS_DB}"

usage() {
    echo "Usage: $0 [warmup|monitor|stats]"
    echo ""
    echo "Commands:"
    echo "  warmup  - Pre-warm caches with hot data"
    echo "  monitor - Monitor cache miss rates and effectiveness"
    echo "  stats   - Show current cache statistics"
}

warmup() {
    echo -e "${BLUE}========================================${NC}"
    echo -e "${BLUE}Bhukkad Cache Warm-up${NC}"
    echo -e "${BLUE}========================================${NC}"
    echo ""

    # 1. Warm popular restaurant data
    echo -e "${YELLOW}→ Warming restaurant data...${NC}"
    local restaurants=$($REDIS_CMD KEYS "restaurant:*" 2>/dev/null | wc -l)
    echo "  Found ${restaurants} restaurants in cache"

    # 2. Warm kitchen queues for active restaurants
    echo -e "${YELLOW}→ Warming kitchen queues...${NC}"
    local queues=$($REDIS_CMD KEYS "kitchen_queue:*" 2>/dev/null | wc -l)
    echo "  Found ${queues} kitchen queues in cache"

    # 3. Warm order track cache for recent orders
    echo -e "${YELLOW}→ Warming order track cache...${NC}"
    local tracks=$($REDIS_CMD KEYS "order_track:*" 2>/dev/null | wc -l)
    echo "  Found ${tracks} tracked orders in cache"

    # 4. Warm home feed cache
    echo -e "${YELLOW}→ Warming home feed cache...${NC}"
    local feeds=$($REDIS_CMD KEYS "home_feed:*" 2>/dev/null | wc -l)
    echo "  Found ${feeds} home feeds in cache"

    # 5. Warm serviceability cache
    echo -e "${YELLOW}→ Warming serviceability cache...${NC}"
    local serviceability=$($REDIS_CMD KEYS "serviceability:*" 2>/dev/null | wc -l)
    echo "  Found ${serviceability} serviceability entries in cache"

    echo ""
    echo -e "${GREEN}Warm-up complete!${NC}"
    echo ""
    echo "Cache TTL recommendations:"
    echo "  - Order track:  ${CACHE_TTL_ORDER}s (frequent updates)"
    echo "  - Kitchen queue: ${CACHE_TTL_KITCHEN_QUEUE}s (hot for 15-30min)"
    echo "  - Home feed:    ${CACHE_TTL_HOME_FEED}s (promo updates)"
    echo "  - Serviceability: ${CACHE_TTL_SERVICEABILITY}s (zone changes)"
}

monitor() {
    echo -e "${BLUE}========================================${NC}"
    echo -e "${BLUE}Bhukkad Cache Monitoring${NC}"
    echo -e "${BLUE}========================================${NC}"
    echo ""

    echo -e "${YELLOW}Redis Memory Usage:${NC}"
    $REDIS_CMD INFO memory | grep -E "used_memory_human|used_memory_peak_human|used_memory_rss_human" || echo "  N/A"

    echo ""
    echo -e "${YELLOW}Cache Key Distribution:${NC}"
    for pattern in "restaurant:*" "kitchen_queue:*" "order_track:*" "home_feed:*" "serviceability:*" "cart:*" "promo:*"; do
        count=$($REDIS_CMD KEYS "$pattern" 2>/dev/null | wc -l)
        printf "  %-30s %d keys\n" "$pattern" "$count"
    done

    echo ""
    echo -e "${YELLOW}Cache Hit/Miss Stats:${NC}"
    $REDIS_CMD INFO stats | grep -E "keyspace_hits|keyspace_misses" || echo "  N/A"

    echo ""
    echo -e "${YELLOW}Key Expiration Stats:${NC}"
    $REDIS_CMD INFO keyspace | grep -E "expired_keys|evicted_keys" || echo "  N/A"
}

stats() {
    echo -e "${BLUE}========================================${NC}"
    echo -e "${BLUE}Bhukkad Cache Statistics${NC}"
    echo -e "${BLUE}========================================${NC}"
    echo ""

    # Calculate cache hit ratio
    hits=$($REDIS_CMD INFO stats | grep "keyspace_hits:" | cut -d: -f2 | tr -d '\r' 2>/dev/null || echo 0)
    misses=$($REDIS_CMD INFO stats | grep "keyspace_misses:" | cut -d: -f2 | tr -d '\r' 2>/dev/null || echo 0)

    if [[ $((hits + misses)) -gt 0 ]]; then
        hit_ratio=$(echo "scale=2; $hits / ($hits + $misses) * 100" | bc)
        echo "Overall Cache Hit Ratio: ${hit_ratio}%"
    else
        echo "Overall Cache Hit Ratio: N/A (no requests yet)"
    fi
    echo "Hits: $hits | Misses: $misses"
    echo ""

    # Key counts by pattern
    echo "Key Counts by Pattern:"
    for pattern in "restaurant:*" "kitchen_queue:*" "order_track:*" "home_feed:*" "serviceability:*" "cart:*" "promo:*" "customer:*" "fraud:*"; do
        count=$($REDIS_CMD KEYS "$pattern" 2>/dev/null | wc -l)
        if [[ $count -gt 0 ]]; then
            printf "  %-25s %4d keys\n" "$pattern" "$count"
        fi
    done

    echo ""
    echo -e "${YELLOW}Memory Info:${NC}"
    $REDIS_CMD INFO memory | grep -E "used_memory_human|used_memory_peak_human|mem_fragmentation_ratio" || echo "  N/A"

    echo ""
    echo -e "${YELLOW}Client Connections:${NC}"
    $REDIS_CMD INFO clients | grep "connected_clients" || echo "  N/A"

    echo ""
    echo -e "${YELLOW}Recommendations:${NC}"
    if [[ "${hit_ratio:-0}" != "N/A" ]] && [[ $(echo "${hit_ratio:-0} < 50" | bc 2>/dev/null || echo 0) -eq 1 ]]; then
        echo -e "${RED}  WARNING: Cache hit ratio is below 50%! Consider:
    - Increasing cache TTL for static data
    - Pre-warming caches after restart
    - Checking cache key consistency${NC}"
    elif [[ "${hit_ratio:-0}" != "N/A" ]]; then
        echo -e "${GREEN}  Cache performance is healthy (>50% hit rate)${NC}"
    fi
}

case "$COMMAND" in
    warmup)
        warmup
        ;;
    monitor)
        monitor
        ;;
    stats)
        stats
        ;;
    *)
        usage
        ;;
esac
