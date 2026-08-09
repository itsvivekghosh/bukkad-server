#!/bin/bash
# ============================================
# Bhukkad Health Check Script
# Usage: ./scripts/health-check.sh [host] [port]
# ============================================

HOST=${1:-localhost}
PORT=${2:-8080}
BASE="http://${HOST}:${PORT}/api"

GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

check() {
    local name="$1"
    local url="$2"
    local status
    status=$(curl -s -o /dev/null -w "%{http_code}" --max-time 10 "$url" 2>/dev/null)
    if [ "$status" = "200" ]; then
        echo -e "  ${GREEN}✅ $name ${NC}(HTTP $status)"
        return 0
    else
        echo -e "  ${RED}❌ $name ${NC}(HTTP $status)"
        return 1
    fi
}

echo ""
echo -e "${BLUE}========================================="
echo "  🍔 Bhukkad Health Check"
echo "  Host: ${HOST}:${PORT}"
echo "  Time: $(date '+%Y-%m-%d %H:%M:%S')"
echo -e "=========================================${NC}"

echo ""
echo "--- Endpoints ---"
check "Ping"           "$BASE/health/ping"
check "Health"         "$BASE/health"
check "Database"       "$BASE/health/db"
check "Memory"         "$BASE/health/memory"
check "Detailed"       "$BASE/health/detailed"
check "Environment"    "$BASE/health/env"

echo ""
echo "--- Detailed Report ---"
DETAIL=$(curl -s --max-time 10 "$BASE/health/detailed" 2>/dev/null)
if command -v python3 &>/dev/null; then
    echo "$DETAIL" | python3 -m json.tool 2>/dev/null || echo "$DETAIL"
else
    echo "$DETAIL"
fi

echo ""
echo -e "${BLUE}=========================================${NC}"