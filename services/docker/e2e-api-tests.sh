#!/bin/bash
# E2E API test suite for Bhukkad platform via gateway
# Usage: ./e2e-api-tests.sh
GATEWAY="http://localhost:8095"
PASS=0; FAIL=0; FAILED_TESTS=()

check() {
  local name="$1"; local expected="$2"; local actual="$3"
  if [ "$expected" = "$actual" ]; then
    PASS=$((PASS+1)); echo "PASS: $name (HTTP $actual)"
  else
    FAIL=$((FAIL+1)); FAILED_TESTS+=("$name: expected $expected, got $actual"); echo "FAIL: $name — expected HTTP $expected, got HTTP $actual"
  fi
}

check_body() {
  # Like check, but also captures the response body for diagnosis.
  local name="$1"; local expected="$2"; local body_file="$3"
  local actual
  actual=$(curl -s -o "$body_file" -w "%{http_code}" "${@:4}")
  if [ "$expected" = "$actual" ]; then
    PASS=$((PASS+1)); echo "PASS: $name (HTTP $actual)"
  else
    FAIL=$((FAIL+1)); FAILED_TESTS+=("$name: expected $expected, got $actual"); echo "FAIL: $name — expected HTTP $expected, got HTTP $actual"; echo "  BODY: $(head -c 300 "$body_file")"
  fi
}

http_code() { curl -s -o /dev/null -w "%{http_code}" "$@"; }

echo "========== E2E API TEST SUITE =========="
echo "Gateway: $GATEWAY"
echo ""

# ============ SETUP: seed test data ============
echo "--- SETUP: seeding test data ---"
# Fresh users (unique emails so re-runs work)
TS=$(date +%s)
CUST_EMAIL="e2e-cust-$TS@test.com"
OWNER_EMAIL="e2e-owner-$TS@test.com"
ADDR='{"addressLine1":"123 Test St","city":"Bangalore","state":"Karnataka","pincode":"560001"}'

REG=$(curl -s -X POST $GATEWAY/api/v1/auth/register -H "Content-Type: application/json" \
  -d "{\"email\":\"$CUST_EMAIL\",\"password\":\"Test@123\",\"fullName\":\"E2E Customer\"}")
CUST_TOKEN=$(echo "$REG" | jq -r .token 2>/dev/null)
CUST_ID=$(echo "$REG" | jq -r .customerId 2>/dev/null)
echo "Customer registered: id=$CUST_ID"

REG2=$(curl -s -X POST $GATEWAY/api/v1/auth/register -H "Content-Type: application/json" \
  -d "{\"email\":\"$OWNER_EMAIL\",\"password\":\"Owner@123\",\"fullName\":\"E2E Owner\",\"role\":\"RESTAURANT_OWNER\"}")
OWNER_TOKEN=$(echo "$REG2" | jq -r .token 2>/dev/null)
echo "Owner registered"

# Restaurant
REST=$(curl -s -X POST $GATEWAY/api/v1/restaurants/owner -H "Authorization: Bearer $OWNER_TOKEN" -H "Content-Type: application/json" \
  -d "{\"name\":\"E2E Test Restaurant $TS\",\"description\":\"E2E fixture\",\"cuisineId\":1,\"address\":$ADDR,\"openingTime\":\"10:00\",\"closingTime\":\"22:00\",\"deliveryFee\":30,\"minimumOrderAmount\":100,\"averageDeliveryTime\":30,\"freeDeliveryAvailable\":true,\"freeDeliveryAbove\":500,\"isPureVeg\":false,\"phone\":\"+91987654321$((TS%10))\",\"fssaiNumber\":\"12345678$((TS%100))\"}")
REST_ID=$(echo "$REST" | jq -r '.data.id // .id // empty' 2>/dev/null)
echo "Restaurant created: id=$REST_ID"

# Menu category + item (extract the real category id — hardcoding 1 breaks
# from the second run onward because each run auto-increments categories)
CAT=$(curl -s -X POST "$GATEWAY/api/v1/menu/categories?restaurantId=$REST_ID" -H "Authorization: Bearer $OWNER_TOKEN" -H "Content-Type: application/json" \
  -d '{"name":"Mains","description":"Main course","displayOrder":1}')
CAT_ID=$(echo "$CAT" | jq -r '.id // .data.id // empty' 2>/dev/null)
MENU_ITEM=$(curl -s -X POST $GATEWAY/api/v1/menu/items -H "Authorization: Bearer $OWNER_TOKEN" -H "Content-Type: application/json" \
  -d "{\"categoryId\":$CAT_ID,\"name\":\"E2E Burger\",\"description\":\"Test burger\",\"price\":199,\"isVeg\":false,\"isActive\":true,\"spiceLevel\":\"MILD\",\"preparationTime\":15}")
ITEM_ID=$(echo "$MENU_ITEM" | jq -r '.id // .data.id // empty' 2>/dev/null)
echo "Menu category id=$CAT_ID, item id=$ITEM_ID"
echo ""

# ============ 1. IDENTITY: AUTH EDGE CASES ============
echo "=== 1. IDENTITY — AUTH EDGE CASES ==="
check_body "register duplicate email → 409" 409 /tmp/e2e-dup.json -X POST $GATEWAY/api/v1/auth/register -H 'Content-Type: application/json' -d "{\"email\":\"$CUST_EMAIL\",\"password\":\"Test@123\",\"fullName\":\"Dup\"}"
check "register invalid email → 400" 400 "$(http_code -X POST $GATEWAY/api/v1/auth/register -H 'Content-Type: application/json' -d '{"email":"not-an-email","password":"Test@123","fullName":"X"}')"
check "register short password → 400" 400 "$(http_code -X POST $GATEWAY/api/v1/auth/register -H 'Content-Type: application/json' -d '{"email":"shortpw@test.com","password":"123","fullName":"X"}')"
check "register missing fullName → 400" 400 "$(http_code -X POST $GATEWAY/api/v1/auth/register -H 'Content-Type: application/json' -d "{\"email\":\"nofn-$TS@test.com\",\"password\":\"Test@123\"}")"
check_body "login wrong password → 401" 401 /tmp/e2e-login.json -X POST $GATEWAY/api/v1/auth/login -H 'Content-Type: application/json' -d "{\"email\":\"$CUST_EMAIL\",\"password\":\"WrongPass@1\"}"
check "login unknown user → 401" 401 "$(http_code -X POST $GATEWAY/api/v1/auth/login -H 'Content-Type: application/json' -d '{"email":"ghost@nowhere.test","password":"Test@123"}')"
check "login empty body → 400" 400 "$(http_code -X POST $GATEWAY/api/v1/auth/login -H 'Content-Type: application/json' -d '{}')"
echo ""

# ============ 2. IDENTITY: TOKEN EDGE CASES ============
echo "=== 2. IDENTITY — TOKEN EDGE CASES ==="
check "profile no token → 401" 401 "$(http_code $GATEWAY/api/v1/customers/profile)"
check "profile garbage token → 401" 401 "$(http_code $GATEWAY/api/v1/customers/profile -H 'Authorization: Bearer garbage.token.value')"
check "profile malformed bearer → 401" 401 "$(http_code $GATEWAY/api/v1/customers/profile -H 'Authorization: notbearer xyz')"
check "profile valid token → 200" 200 "$(http_code $GATEWAY/api/v1/customers/profile -H "Authorization: Bearer $CUST_TOKEN")"
echo ""

# ============ 3. IDENTITY: CUSTOMER SELF-SERVICE ============
echo "=== 3. IDENTITY — CUSTOMER SELF-SERVICE ==="
check "get notification prefs → 200" 200 "$(http_code $GATEWAY/api/v1/customers/notification-preferences -H "Authorization: Bearer $CUST_TOKEN")"
check "update notification prefs → 200" 200 "$(http_code -X PUT $GATEWAY/api/v1/customers/notification-preferences -H "Authorization: Bearer $CUST_TOKEN" -H 'Content-Type: application/json' -d '{"emailEnabled":false,"smsEnabled":true,"whatsappEnabled":false,"pushEnabled":true,"orderUpdatesEnabled":true,"promotionsEnabled":false}')"
check "membership status → 200" 200 "$(http_code $GATEWAY/api/v1/customers/membership/status -H "Authorization: Bearer $CUST_TOKEN")"
check "compliance consents → 200" 200 "$(http_code $GATEWAY/api/v1/compliance/consents -H "Authorization: Bearer $CUST_TOKEN")"
check "loyalty points → 200" 200 "$(http_code $GATEWAY/api/v1/customers/loyalty-points -H "Authorization: Bearer $CUST_TOKEN")"
check "data-export → 200" 200 "$(http_code $GATEWAY/api/v1/customers/data-export -H "Authorization: Bearer $CUST_TOKEN")"
check "referral validate nonexistent → 200" 200 "$(http_code "$GATEWAY/api/v1/referrals/validate/NOEXIST$TS" -H "Authorization: Bearer $CUST_TOKEN")"
echo ""

# ============ 4. CATALOG ============
echo "=== 4. CATALOG ==="
check "cuisines list → 200" 200 "$(http_code $GATEWAY/api/v1/cuisines -H "Authorization: Bearer $CUST_TOKEN")"
check "restaurants list → 200" 200 "$(http_code $GATEWAY/api/v1/restaurants -H "Authorization: Bearer $CUST_TOKEN")"
check "restaurants public → 200" 200 "$(http_code $GATEWAY/api/v1/restaurants/public)"
check "search keyword → 200" 200 "$(http_code "$GATEWAY/api/v1/search?keyword=burger" -H "Authorization: Bearer $CUST_TOKEN")"
check "search requires keyword → 400" 400 "$(http_code "$GATEWAY/api/v1/search" -H "Authorization: Bearer $CUST_TOKEN")"
check "menu items by restaurant → 200" 200 "$(http_code "$GATEWAY/api/v1/menu/items/restaurant/$REST_ID" -H "Authorization: Bearer $CUST_TOKEN")"
echo ""

# ============ 5. COMMERCE ============
echo "=== 5. COMMERCE ==="
check "create order no body → 400" 400 "$(http_code -X POST $GATEWAY/api/v1/orders -H "Authorization: Bearer $CUST_TOKEN" -H 'Content-Type: application/json' -d '{}')"
check "create order no restaurantId → 400" 400 "$(http_code -X POST $GATEWAY/api/v1/orders -H "Authorization: Bearer $CUST_TOKEN" -H 'Content-Type: application/json' -d "{\"customerId\":$CUST_ID,\"items\":[]}")"
check "create order no auth → 401" 401 "$(http_code -X POST $GATEWAY/api/v1/orders -H 'Content-Type: application/json' -d '{}')"
echo ""

# ============ 6. ENGAGEMENT ============
echo "=== 6. ENGAGEMENT ==="
check "loyalty points e2e → 200" 200 "$(http_code $GATEWAY/api/v1/customers/loyalty-points -H "Authorization: Bearer $CUST_TOKEN")"
check "referral stats self → 200" 200 "$(http_code "$GATEWAY/api/v1/customers/$CUST_ID/referral/stats" -H "Authorization: Bearer $CUST_TOKEN")"
check "referral stats other customer → 403" 403 "$(http_code "$GATEWAY/api/v1/customers/999999/referral/stats" -H "Authorization: Bearer $CUST_TOKEN")"
echo ""

# ============ 7. ADMIN ============
echo "=== 7. ADMIN ==="
ADMIN_TOKEN=$(curl -s -X POST $GATEWAY/api/v1/auth/login -H 'Content-Type: application/json' -d '{"email":"admin@bhukkad.dev","password":"Admin@123456"}' | jq -r .token 2>/dev/null)
if [ -z "$ADMIN_TOKEN" ] || [ "$ADMIN_TOKEN" = "null" ]; then
  # bootstrap admin may be disabled; create via bootstrap env? report
  echo "NOTE: admin login failed — admin token unavailable"
fi
check "admin endpoint with customer token → 403" 403 "$(http_code $GATEWAY/api/v1/admin/users -H "Authorization: Bearer $CUST_TOKEN")"
check "admin users list (admin) → 200" 200 "$(http_code $GATEWAY/api/v1/admin/users -H "Authorization: Bearer $ADMIN_TOKEN")"
check "admin analytics export orders → 200" 200 "$(http_code $GATEWAY/api/v1/analytics/export/orders -H "Authorization: Bearer $ADMIN_TOKEN")"
echo ""

# ============ 8. PLATFORM ============
echo "=== 8. PLATFORM ==="
check "platform health → 200" 200 "$(http_code $GATEWAY/api/v1/health -H "Authorization: Bearer $CUST_TOKEN")"
check "platform cities → 200" 200 "$(http_code $GATEWAY/api/v1/platform/cities -H "Authorization: Bearer $CUST_TOKEN")"
echo ""

# ============ 9. SECURITY EDGE CASES ============
echo "=== 9. SECURITY EDGE CASES ==="
# SQL injection attempt in search keyword: safe handling means either a 400
# (validation rejects it) or 200 (parameterized query treats it as a literal
# string and simply finds nothing). Both prove no injection occurred.
SQLI_CODE=$(http_code "$GATEWAY/api/v1/search?keyword=';DROP%20TABLE%20restaurants;--" -H "Authorization: Bearer $CUST_TOKEN")
if [ "$SQLI_CODE" = "200" ] || [ "$SQLI_CODE" = "400" ]; then
  check "search SQL-injection keyword safely handled → 200/400" "$SQLI_CODE" "$SQLI_CODE"
else
  check "search SQL-injection keyword safely handled → 200/400" "200-or-400" "$SQLI_CODE"
fi
# Unauthenticated access to protected endpoints
check "orders list no auth → 401" 401 "$(http_code $GATEWAY/api/v1/orders)"
check "cart no auth → 401" 401 "$(http_code $GATEWAY/api/v1/cart)"
# Gateway headers present
HDRS=$(curl -s -D - -o /dev/null $GATEWAY/api/v1/health -H "Authorization: Bearer $CUST_TOKEN" | grep -ci "x-correlation-id")
check "gateway adds X-Correlation-Id → 1" 1 "$HDRS"
echo ""

echo "========== RESULTS =========="
echo "PASS: $PASS | FAIL: $FAIL"
if [ $FAIL -gt 0 ]; then
  printf '%s\n' "${FAILED_TESTS[@]}"
  exit 1
fi
echo "ALL E2E TESTS PASSED"
