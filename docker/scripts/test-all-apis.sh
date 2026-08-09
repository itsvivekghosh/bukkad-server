#!/bin/bash
# ============================================
# Bhukkad API Test Script
# Usage: ./scripts/test-all-apis.sh [host] [port]
# ============================================

HOST=${1:-localhost}
PORT=${2:-8080}
BASE="http://${HOST}:${PORT}/api"
TOKEN=""
PASS=0
FAIL=0

GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m'

RANDOM_EMAIL="test_$(date +%s)@bhukkad.com"
RANDOM_PHONE="9$(shuf -i 100000000-999999999 -n 1)"

test_api() {
    local name="$1"
    local method="$2"
    local url="$3"
    local data="$4"
    local expected_status="$5"
    local auth="$6"

    echo -e "${YELLOW}Testing: $name${NC}"

    local headers=(-H "Content-Type: application/json")
    [ -n "$auth" ] && headers+=(-H "Authorization: Bearer $TOKEN")

    local response
    if [ "$method" = "GET" ] || [ "$method" = "DELETE" ]; then
        response=$(curl -s -w "\n%{http_code}" -X "$method" "$url" "${headers[@]}" 2>/dev/null)
    else
        response=$(curl -s -w "\n%{http_code}" -X "$method" "$url" "${headers[@]}" -d "$data" 2>/dev/null)
    fi

    local http_code=$(echo "$response" | tail -1)
    local body=$(echo "$response" | sed '$d')

    if [ "$http_code" = "$expected_status" ]; then
        echo -e "  ${GREEN}✅ PASS (HTTP $http_code)${NC}"
        PASS=$((PASS + 1))
    else
        echo -e "  ${RED}❌ FAIL (Expected: $expected_status, Got: $http_code)${NC}"
        echo "  Response: $(echo "$body" | head -c 200)"
        FAIL=$((FAIL + 1))
    fi

    echo "$body"
}

echo ""
echo "========================================"
echo "  🍔 Bhukkad API Test Suite"
echo "  Server: $BASE"
echo "  Time: $(date '+%Y-%m-%d %H:%M:%S')"
echo "========================================"

# Health Checks
echo ""
echo "=== HEALTH CHECKS ==="
test_api "Ping"       "GET" "$BASE/health/ping"     "" "200" ""
test_api "Health"     "GET" "$BASE/health"           "" "200" ""
test_api "DB Health"  "GET" "$BASE/health/db"        "" "200" ""
test_api "Memory"     "GET" "$BASE/health/memory"    "" "200" ""

# Authentication
echo ""
echo "=== AUTHENTICATION ==="
REGISTER_DATA="{\"fullName\":\"Test User\",\"email\":\"$RANDOM_EMAIL\",\"password\":\"password123\",\"phoneNumber\":\"$RANDOM_PHONE\",\"role\":\"CUSTOMER\"}"
test_api "Register Customer" "POST" "$BASE/auth/register" "$REGISTER_DATA" "200" ""

LOGIN_DATA="{\"email\":\"$RANDOM_EMAIL\",\"password\":\"password123\"}"
LOGIN_RESPONSE=$(curl -s -X POST "$BASE/auth/login" -H "Content-Type: application/json" -d "$LOGIN_DATA" 2>/dev/null)
TOKEN=$(echo "$LOGIN_RESPONSE" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('data',{}).get('token',''))" 2>/dev/null)

if [ -n "$TOKEN" ]; then
    echo -e "  ${GREEN}✅ Login - Token obtained${NC}"
    PASS=$((PASS + 1))
else
    echo -e "  ${RED}❌ Login - No token received${NC}"
    FAIL=$((FAIL + 1))
fi

# Customer
echo ""
echo "=== CUSTOMER ==="
test_api "Get Profile"     "GET" "$BASE/customers/profile"         "" "200" "auth"
test_api "Wallet Balance"  "GET" "$BASE/customers/wallet/balance"  "" "200" "auth"
test_api "Loyalty Points"  "GET" "$BASE/customers/loyalty-points"  "" "200" "auth"

ADDRESS_DATA='{"addressLine1":"123 Test St","city":"Bangalore","state":"Karnataka","pincode":"560001","latitude":12.9716,"longitude":77.5946,"type":"HOME","isDefault":true}'
test_api "Add Address"  "POST" "$BASE/customers/addresses" "$ADDRESS_DATA" "200" "auth"
test_api "Get Addresses" "GET" "$BASE/customers/addresses"  "" "200" "auth"

# Restaurants (Public)
echo ""
echo "=== RESTAURANTS (PUBLIC) ==="
test_api "All Restaurants"  "GET" "$BASE/restaurants/public"                     "" "200" ""
test_api "Search"           "GET" "$BASE/restaurants/public/search?keyword=cafe" "" "200" ""
test_api "Filter"           "GET" "$BASE/restaurants/public/filter?isPureVeg=true" "" "200" ""

# Cart
echo ""
echo "=== CART ==="
test_api "Get Cart"   "GET"    "$BASE/cart"                              "" "200" "auth"
test_api "Clear Cart" "DELETE" "$BASE/cart/clear"                        "" "200" "auth"

# Cache
echo ""
echo "=== CACHE ==="
test_api "Cache Health" "GET" "$BASE/cache/health" "" "200" ""
test_api "Cache Stats"  "GET" "$BASE/cache/stats"  "" "200" ""

# Summary
echo ""
echo "========================================"
echo "  Test Results"
echo "  ✅ Passed: $PASS"
echo "  ❌ Failed: $FAIL"
echo "  📊 Total:  $((PASS + FAIL))"
if [ $FAIL -eq 0 ]; then
    echo -e "  ${GREEN}🎉 All tests passed!${NC}"
else
    echo -e "  ${RED}⚠️  Some tests failed!${NC}"
fi
echo "========================================"
echo ""