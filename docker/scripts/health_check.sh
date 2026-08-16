#!/bin/bash

# Bhukkad Server Health Check Script
# Usage: ./health_check.sh [host] [port]

HOST=${1:-localhost}
PORT=${2:-8080}
BASE_URL="http://${HOST}:${PORT}/api/v1/health"

echo "========================================="
echo "  Bhukkad Server Health Check"
echo "  Host: ${HOST}:${PORT}"
echo "  Time: $(date '+%Y-%m-%d %H:%M:%S')"
echo "========================================="

echo ""
echo "--- Ping ---"
PING_RESPONSE=$(curl -s -o /dev/null -w "%{http_code}" ${BASE_URL}/ping 2>/dev/null)
if [ "$PING_RESPONSE" = "200" ]; then
    echo "✅ Server is reachable (HTTP $PING_RESPONSE)"
else
    echo "❌ Server is NOT reachable (HTTP $PING_RESPONSE)"
    exit 1
fi

echo ""
echo "--- Application Status ---"
curl -s ${BASE_URL} 2>/dev/null | python3 -m json.tool 2>/dev/null || curl -s ${BASE_URL}

echo ""
echo "--- Database Status ---"
DB_STATUS=$(curl -s ${BASE_URL}/db 2>/dev/null)
DB_UP=$(echo $DB_STATUS | grep -o '"status":"UP"')
if [ -n "$DB_UP" ]; then
    echo "✅ Database is UP"
else
    echo "❌ Database is DOWN"
fi
echo $DB_STATUS | python3 -m json.tool 2>/dev/null || echo $DB_STATUS

echo ""
echo "--- Memory Status ---"
MEM_STATUS=$(curl -s ${BASE_URL}/memory 2>/dev/null)
echo $MEM_STATUS | python3 -m json.tool 2>/dev/null || echo $MEM_STATUS

echo ""
echo "========================================="
echo "  Health Check Complete"
echo "========================================="