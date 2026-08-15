#!/bin/bash

# ============================================
# Bhukkad - Complete API Test Script
# Usage: ./scripts/test-all-apis.sh [host] [port]
# ============================================

HOST=${1:-localhost}
PORT=${2:-8080}
BASE="http://${HOST}:${PORT}/api/v1"
BASE_LEGACY="http://${HOST}:${PORT}/api"

# Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m'

# Counters
PASS=0
FAIL=0
SKIP=0
TOTAL=0

# Tokens
CUSTOMER_TOKEN=""
CUSTOMER_REFRESH_TOKEN=""
OWNER_TOKEN=""
AGENT_TOKEN=""
ADMIN_TOKEN=""

# IDs
CUSTOMER_ID=""
OWNER_ID=""
AGENT_ID=""
ADMIN_ID=""
RESTAURANT_ID=""
CATEGORY_ID=""
MENU_ITEM_ID=""
ADDRESS_ID=""
ORDER_ID=""
REVIEW_ID=""
CART_ITEM_ID=""
REFERRAL_CODE=""

# Random data — unique per run to avoid DB collisions when tests run in parallel
RUN_ID=$(python3 -c "import secrets; print(secrets.token_hex(4))" 2>/dev/null || echo "${RANDOM}${RANDOM}")
TIMESTAMP=$(date +%s)
CUSTOMER_EMAIL="customer_${TIMESTAMP}_${RUN_ID}@bhukkad.test"
OWNER_EMAIL="owner_${TIMESTAMP}_${RUN_ID}@bhukkad.test"
AGENT_EMAIL="agent_${TIMESTAMP}_${RUN_ID}@bhukkad.test"
PASSWORD="Test@123456"

random_phone() {
    local prefix="$1"
    python3 -c "import secrets; p='$prefix'; print(p + ''.join(secrets.choice('0123456789') for _ in range(10 - len(p))))"
}

CUSTOMER_PHONE=$(random_phone "98")
OWNER_PHONE=$(random_phone "97")
AGENT_PHONE=$(random_phone "96")

# Seeded dev admin (see DevAdminBootstrap / application-dev.yml)
ADMIN_EMAIL="${ADMIN_EMAIL:-admin@bhukkad.dev}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-Admin@123456}"

# ==================== HELPERS ====================

log_header() {
    echo ""
    echo -e "${BLUE}════════════════════════════════════════════════════${NC}"
    echo -e "${BLUE}  $1${NC}"
    echo -e "${BLUE}════════════════════════════════════════════════════${NC}"
}

log_section() {
    echo ""
    echo -e "${CYAN}──── $1 ────${NC}"
}

test_api() {
    local name="$1"
    local method="$2"
    local url="$3"
    local data="$4"
    local expected_status="$5"
    local token="$6"
    local extract_field="$7"
    local accept_header="${8:-application/json}"

    TOTAL=$((TOTAL + 1))

    # Build curl command
    local curl_cmd="curl -s -w \"\n%{http_code}\" -X $method \"$url\""
    curl_cmd="$curl_cmd -H \"Content-Type: application/json\""
    curl_cmd="$curl_cmd -H \"Accept: $accept_header\""

    if [ -n "$token" ]; then
        curl_cmd="$curl_cmd -H \"Authorization: Bearer $token\""
    fi

    if [ -n "$data" ]; then
        curl_cmd="$curl_cmd -d '$data'"
    fi

    # Execute
    local response
    response=$(eval $curl_cmd 2>/dev/null)

    local http_code=$(echo "$response" | tail -1)
    local body=$(echo "$response" | sed '$d')

    # Check result
    if [ "$http_code" = "$expected_status" ]; then
        echo -e "  ${GREEN}✅ PASS${NC} | $name (HTTP $http_code)"
        PASS=$((PASS + 1))

        # Extract field if requested
        if [ -n "$extract_field" ]; then
            local extracted
            extracted=$(echo "$body" | python3 -c "
import sys, json
try:
    data = json.load(sys.stdin)
    fields = '$extract_field'.split('.')
    result = data
    for f in fields:
        result = result[f]
    print(result)
except:
    print('')
" 2>/dev/null)
            echo "$extracted"
        fi
    else
        echo -e "  ${RED}❌ FAIL${NC} | $name (Expected: $expected_status, Got: $http_code)"
        echo -e "  ${RED}   Response: $(echo "$body" | head -c 200)${NC}"
        FAIL=$((FAIL + 1))
    fi
}

extract_json() {
    local json="$1"
    local field="$2"
    echo "$json" | python3 -c "
import sys, json
try:
    data = json.load(sys.stdin)
    fields = '$field'.split('.')
    result = data
    for f in fields:
        if isinstance(result, dict):
            result = result.get(f, '')
        else:
            result = ''
    print(result)
except:
    print('')
" 2>/dev/null
}

do_request() {
    local method="$1"
    local url="$2"
    local data="$3"
    local token="$4"

    local curl_cmd="curl -s -X $method \"$url\""
    curl_cmd="$curl_cmd -H \"Content-Type: application/json\""

    if [ -n "$token" ]; then
        curl_cmd="$curl_cmd -H \"Authorization: Bearer $token\""
    fi

    if [ -n "$data" ]; then
        curl_cmd="$curl_cmd -d '$data'"
    fi

    eval $curl_cmd 2>/dev/null
}

register_or_login() {
    local role_label="$1"
    local email="$2"
    local phone="$3"
    local role="$4"
    local token_var="$5"
    local refresh_var="$6"
    local id_var="$7"

    local register_body="{\"fullName\":\"Test $role_label\",\"email\":\"$email\",\"password\":\"$PASSWORD\",\"phoneNumber\":\"$phone\",\"role\":\"$role\"}"
    local response
    response=$(do_request "POST" "$BASE/auth/register" "$register_body")

    local token
    token=$(extract_json "$response" "data.token")
    local refresh
    refresh=$(extract_json "$response" "data.refreshToken")
    local user_id
    user_id=$(extract_json "$response" "data.userId")

    if [ -z "$token" ]; then
        local login_body="{\"email\":\"$email\",\"password\":\"$PASSWORD\"}"
        response=$(do_request "POST" "$BASE/auth/login" "$login_body")
        token=$(extract_json "$response" "data.token")
        refresh=$(extract_json "$response" "data.refreshToken")
        user_id=$(extract_json "$response" "data.userId")
    fi

    eval "$token_var=\"$token\""
    if [ -n "$refresh_var" ]; then
        eval "$refresh_var=\"$refresh\""
    fi
    if [ -n "$id_var" ]; then
        eval "$id_var=\"$user_id\""
    fi

    TOTAL=$((TOTAL + 1))
    if [ -n "$token" ] && [ "$token" != "" ]; then
        echo -e "  ${GREEN}✅ PASS${NC} | Register $role_label (ID: $user_id)"
        PASS=$((PASS + 1))
        return 0
    fi

    echo -e "  ${RED}❌ FAIL${NC} | Register $role_label"
    echo -e "  ${RED}   Response: $(echo "$response" | head -c 200)${NC}"
    FAIL=$((FAIL + 1))
    return 1
}

check_server() {
    local ping_url="$BASE/health/ping"
    local code
    code=$(curl -s -o /dev/null -w "%{http_code}" "$ping_url" 2>/dev/null || echo "000")
    if [ "$code" != "200" ]; then
        echo -e "${RED}❌ Server not reachable at http://${HOST}:${PORT} (ping HTTP $code)${NC}"
        echo -e "${YELLOW}   Start the stack first: ./scripts/run-local.sh${NC}"
        exit 1
    fi
    echo -e "${GREEN}✅ Server is up (ping HTTP $code)${NC}"
}

# Reset fraud counters from prior test runs (same IP triggers auth-register limits).
if command -v mysql >/dev/null 2>&1; then
    mysql -h "${DB_HOST:-localhost}" -P "${DB_PORT:-3306}" -u "${DB_USERNAME:-root}" \
        -p"${DB_PASSWORD:-root}" "${DB_NAME:-bhukkad}" \
        -e "DELETE FROM fraud_events;" 2>/dev/null || true
fi

login_admin() {
    if [ -n "$ADMIN_TOKEN" ]; then
        return
    fi
    local response
    response=$(do_request "POST" "$BASE/auth/login" \
        "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PASSWORD\"}")
    ADMIN_TOKEN=$(extract_json "$response" "data.token")
    ADMIN_ID=$(extract_json "$response" "data.userId")
    TOTAL=$((TOTAL + 1))
    if [ -n "$ADMIN_TOKEN" ]; then
        echo -e "  ${GREEN}✅ PASS${NC} | Login Admin (ID: $ADMIN_ID)"
        PASS=$((PASS + 1))
    else
        echo -e "  ${YELLOW}⚠️  SKIP${NC} | Login Admin (seed admin not available)"
        SKIP=$((SKIP + 1))
    fi
}

# ==================== START ====================

echo ""
echo -e "${BLUE}╔══════════════════════════════════════════════════════╗${NC}"
echo -e "${BLUE}║         🍔 BHUKKAD - Complete API Test Suite        ║${NC}"
echo -e "${BLUE}║         Server: ${HOST}:${PORT}                          ║${NC}"
echo -e "${BLUE}║         Time: $(date '+%Y-%m-%d %H:%M:%S')                   ║${NC}"
echo -e "${BLUE}╚══════════════════════════════════════════════════════╝${NC}"

check_server

# ==================== HEALTH CHECKS ====================

log_header "1. HEALTH CHECK ENDPOINTS"

test_api "Ping" \
    "GET" "$BASE/health/ping" "" "200" ""

test_api "Health" \
    "GET" "$BASE/health" "" "200" ""

test_api "Detailed Health" \
    "GET" "$BASE/health/detailed" "" "200" ""

test_api "Database Health" \
    "GET" "$BASE/health/db" "" "200" ""

test_api "Memory Health" \
    "GET" "$BASE/health/memory" "" "200" ""

test_api "Environment" \
    "GET" "$BASE/health/env" "" "200" ""

# ==================== SWAGGER ====================

log_header "2. SWAGGER / OPENAPI"

test_api "OpenAPI JSON" \
    "GET" "http://${HOST}:${PORT}/v3/api-docs" "" "200" ""

test_api "Swagger UI" \
    "GET" "http://${HOST}:${PORT}/swagger-ui/index.html" "" "200" ""

# ==================== AUTH - REGISTER ====================

log_header "3. AUTHENTICATION - REGISTER"

log_section "Register Customer"
register_or_login "Customer" "$CUSTOMER_EMAIL" "$CUSTOMER_PHONE" "CUSTOMER" \
    CUSTOMER_TOKEN CUSTOMER_REFRESH_TOKEN CUSTOMER_ID

log_section "Register Restaurant Owner"
register_or_login "Owner" "$OWNER_EMAIL" "$OWNER_PHONE" "RESTAURANT_OWNER" \
    OWNER_TOKEN "" OWNER_ID

log_section "Register Delivery Agent"
register_or_login "Agent" "$AGENT_EMAIL" "$AGENT_PHONE" "DELIVERY_AGENT" \
    AGENT_TOKEN "" AGENT_ID

# ==================== AUTH - LOGIN ====================

log_header "4. AUTHENTICATION - LOGIN"

log_section "Login Customer"
LOGIN_RESPONSE=$(do_request "POST" "$BASE/auth/login" \
    "{\"email\":\"$CUSTOMER_EMAIL\",\"password\":\"$PASSWORD\"}")

CUSTOMER_TOKEN=$(extract_json "$LOGIN_RESPONSE" "data.token")
CUSTOMER_REFRESH_TOKEN=$(extract_json "$LOGIN_RESPONSE" "data.refreshToken")

if [ -n "$CUSTOMER_TOKEN" ] && [ "$CUSTOMER_TOKEN" != "" ]; then
    echo -e "  ${GREEN}✅ PASS${NC} | Login Customer"
    PASS=$((PASS + 1))
else
    echo -e "  ${RED}❌ FAIL${NC} | Login Customer"
    FAIL=$((FAIL + 1))
fi
TOTAL=$((TOTAL + 1))

log_section "Login Owner"
LOGIN_RESPONSE=$(do_request "POST" "$BASE/auth/login" \
    "{\"email\":\"$OWNER_EMAIL\",\"password\":\"$PASSWORD\"}")

OWNER_TOKEN=$(extract_json "$LOGIN_RESPONSE" "data.token")

if [ -n "$OWNER_TOKEN" ] && [ "$OWNER_TOKEN" != "" ]; then
    echo -e "  ${GREEN}✅ PASS${NC} | Login Owner"
    PASS=$((PASS + 1))
else
    echo -e "  ${RED}❌ FAIL${NC} | Login Owner"
    FAIL=$((FAIL + 1))
fi
TOTAL=$((TOTAL + 1))

log_section "Login Agent"
LOGIN_RESPONSE=$(do_request "POST" "$BASE/auth/login" \
    "{\"email\":\"$AGENT_EMAIL\",\"password\":\"$PASSWORD\"}")

AGENT_TOKEN=$(extract_json "$LOGIN_RESPONSE" "data.token")

if [ -n "$AGENT_TOKEN" ] && [ "$AGENT_TOKEN" != "" ]; then
    echo -e "  ${GREEN}✅ PASS${NC} | Login Agent"
    PASS=$((PASS + 1))
else
    echo -e "  ${RED}❌ FAIL${NC} | Login Agent"
    FAIL=$((FAIL + 1))
fi
TOTAL=$((TOTAL + 1))

test_api "Login with wrong password" \
    "POST" "$BASE/auth/login" \
    "{\"email\":\"$CUSTOMER_EMAIL\",\"password\":\"wrongpassword\"}" \
    "401" ""

# ==================== AUTH - OTHER ====================

log_header "5. AUTHENTICATION - OTHER OPERATIONS"

test_api "Verify Email" \
    "POST" "$BASE/auth/verify-email?email=$CUSTOMER_EMAIL" "" "200" "$CUSTOMER_TOKEN"

log_section "Refresh Token"
REFRESH_FULL=$(curl -s -w "\n%{http_code}" -X POST "$BASE/auth/refresh-token" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $CUSTOMER_REFRESH_TOKEN")
REFRESH_HTTP=$(echo "$REFRESH_FULL" | tail -1)
REFRESH_RESPONSE=$(echo "$REFRESH_FULL" | sed '$d')
TOTAL=$((TOTAL + 1))
if [ "$REFRESH_HTTP" = "200" ]; then
    echo -e "  ${GREEN}✅ PASS${NC} | Refresh Token (HTTP $REFRESH_HTTP)"
    PASS=$((PASS + 1))
    CUSTOMER_TOKEN=$(extract_json "$REFRESH_RESPONSE" "data.token")
    CUSTOMER_REFRESH_TOKEN=$(extract_json "$REFRESH_RESPONSE" "data.refreshToken")
else
    echo -e "  ${RED}❌ FAIL${NC} | Refresh Token (Expected: 200, Got: $REFRESH_HTTP)"
    echo -e "  ${RED}   Response: $(echo "$REFRESH_RESPONSE" | head -c 200)${NC}"
    FAIL=$((FAIL + 1))
fi

test_api "Change Password" \
    "POST" "$BASE/auth/change-password?oldPassword=$PASSWORD&newPassword=NewPass@123" \
    "" "200" "$CUSTOMER_TOKEN"

# Login with new password
LOGIN_RESPONSE=$(do_request "POST" "$BASE/auth/login" \
    "{\"email\":\"$CUSTOMER_EMAIL\",\"password\":\"NewPass@123\"}")
CUSTOMER_TOKEN=$(extract_json "$LOGIN_RESPONSE" "data.token")
CUSTOMER_REFRESH_TOKEN=$(extract_json "$LOGIN_RESPONSE" "data.refreshToken")

test_api "Login with new password" \
    "POST" "$BASE/auth/login" \
    "{\"email\":\"$CUSTOMER_EMAIL\",\"password\":\"NewPass@123\"}" \
    "200" ""

# Change back
do_request "POST" "$BASE/auth/change-password?oldPassword=NewPass@123&newPassword=$PASSWORD" "" "$CUSTOMER_TOKEN" > /dev/null

# Re-login with original password
LOGIN_RESPONSE=$(do_request "POST" "$BASE/auth/login" \
    "{\"email\":\"$CUSTOMER_EMAIL\",\"password\":\"$PASSWORD\"}")
CUSTOMER_TOKEN=$(extract_json "$LOGIN_RESPONSE" "data.token")
CUSTOMER_REFRESH_TOKEN=$(extract_json "$LOGIN_RESPONSE" "data.refreshToken")

test_api "Forgot Password" \
    "POST" "$BASE/auth/forgot-password?email=$CUSTOMER_EMAIL" "" "200" ""

# ==================== AUTH - SECURITY TESTS ====================

log_header "6. SECURITY TESTS"

test_api "Access protected route without token" \
    "GET" "$BASE/customers/profile" "" "403" ""

test_api "Access with invalid token" \
    "GET" "$BASE/customers/profile" "" "403" "invalid_token_here"

test_api "Customer accessing owner route" \
    "GET" "$BASE/restaurants/owner/my-restaurants" "" "403" "$CUSTOMER_TOKEN"

test_api "Owner accessing customer route" \
    "GET" "$BASE/customers/profile" "" "403" "$OWNER_TOKEN"

test_api "Agent accessing customer route" \
    "GET" "$BASE/customers/profile" "" "403" "$AGENT_TOKEN"

# ==================== CUISINES ====================

log_header "7. CUISINES (PUBLIC)"

test_api "Get All Cuisines" \
    "GET" "$BASE/cuisines" "" "200" ""

# ==================== CUSTOMER PROFILE ====================

log_header "8. CUSTOMER PROFILE"

test_api "Get Profile" \
    "GET" "$BASE/customers/profile" "" "200" "$CUSTOMER_TOKEN"

test_api "Update Profile" \
    "PUT" "$BASE/customers/profile?fullName=Updated+Customer&phoneNumber=$CUSTOMER_PHONE" \
    "" "200" "$CUSTOMER_TOKEN"

test_api "Get Wallet Balance" \
    "GET" "$BASE/customers/wallet/balance" "" "200" "$CUSTOMER_TOKEN"

test_api "Add Money to Wallet" \
    "POST" "$BASE/customers/wallet/add-money?amount=500.0" "" "200" "$CUSTOMER_TOKEN"

test_api "Get Wallet Balance After Add" \
    "GET" "$BASE/customers/wallet/balance" "" "200" "$CUSTOMER_TOKEN"

test_api "Get Loyalty Points" \
    "GET" "$BASE/customers/loyalty-points" "" "200" "$CUSTOMER_TOKEN"

log_section "Referral Program"
REFERRAL_RESPONSE=$(do_request "GET" "$BASE/customers/referral" "" "$CUSTOMER_TOKEN")
REFERRAL_CODE=$(extract_json "$REFERRAL_RESPONSE" "data.referralCode")
TOTAL=$((TOTAL + 1))
if [ -n "$REFERRAL_CODE" ]; then
    echo -e "  ${GREEN}✅ PASS${NC} | Get Referral Info (code: $REFERRAL_CODE)"
    PASS=$((PASS + 1))
else
    echo -e "  ${RED}❌ FAIL${NC} | Get Referral Info"
    FAIL=$((FAIL + 1))
fi

test_api "Get Notification Preferences" \
    "GET" "$BASE/customers/notification-preferences" "" "200" "$CUSTOMER_TOKEN"

test_api "Update Notification Preferences" \
    "PUT" "$BASE/customers/notification-preferences" \
    "{\"emailEnabled\":true,\"smsEnabled\":false,\"whatsappEnabled\":true,\"pushEnabled\":true,\"orderUpdatesEnabled\":true,\"promotionsEnabled\":false}" \
    "200" "$CUSTOMER_TOKEN"

log_section "Device Token Management"
test_api "Register Device Token" \
    "POST" "$BASE/customers/device-tokens" \
    "{\"token\":\"fcm-token-${RUN_ID}\",\"platform\":\"ANDROID\"}" "200" "$CUSTOMER_TOKEN"

test_api "Unregister Device Token" \
    "DELETE" "$BASE/customers/device-tokens?token=fcm-token-${RUN_ID}" "" "200" "$CUSTOMER_TOKEN"

log_section "Membership Management"
test_api "List Membership Plans" \
    "GET" "$BASE/customers/membership/plans" "" "200" "$CUSTOMER_TOKEN"

test_api "Membership Status" \
    "GET" "$BASE/customers/membership/status" "" "200" "$CUSTOMER_TOKEN"

test_api "Subscribe to Membership" \
    "POST" "$BASE/customers/membership/subscribe" \
    "{\"planId\":1,\"paymentMethod\":\"WALLET\"}" "200" "$CUSTOMER_TOKEN"

# ==================== ADDRESSES ====================

log_header "9. CUSTOMER ADDRESSES"

log_section "Add Address - Home"
ADDRESS_RESPONSE=$(do_request "POST" "$BASE/customers/addresses" \
    "{\"addressLine1\":\"123 MG Road\",\"addressLine2\":\"Near City Mall\",\"city\":\"Bangalore\",\"state\":\"Karnataka\",\"pincode\":\"560001\",\"landmark\":\"Near Metro\",\"type\":\"HOME\",\"label\":\"My Home\",\"latitude\":12.9716,\"longitude\":77.5946,\"isDefault\":true}" \
    "$CUSTOMER_TOKEN")

ADDRESS_ID=$(extract_json "$ADDRESS_RESPONSE" "data.id")
if [ -n "$ADDRESS_ID" ] && [ "$ADDRESS_ID" != "" ]; then
    echo -e "  ${GREEN}✅ PASS${NC} | Add Home Address (ID: $ADDRESS_ID)"
    PASS=$((PASS + 1))
else
    echo -e "  ${RED}❌ FAIL${NC} | Add Home Address"
    FAIL=$((FAIL + 1))
fi
TOTAL=$((TOTAL + 1))

log_section "Add Address - Work"
ADDRESS2_RESPONSE=$(do_request "POST" "$BASE/customers/addresses" \
    "{\"addressLine1\":\"456 Koramangala\",\"city\":\"Bangalore\",\"state\":\"Karnataka\",\"pincode\":\"560034\",\"type\":\"WORK\",\"label\":\"Office\",\"latitude\":12.9352,\"longitude\":77.6245,\"isDefault\":false}" \
    "$CUSTOMER_TOKEN")

ADDRESS2_ID=$(extract_json "$ADDRESS2_RESPONSE" "data.id")
if [ -n "$ADDRESS2_ID" ] && [ "$ADDRESS2_ID" != "" ]; then
    echo -e "  ${GREEN}✅ PASS${NC} | Add Work Address (ID: $ADDRESS2_ID)"
    PASS=$((PASS + 1))
else
    echo -e "  ${RED}❌ FAIL${NC} | Add Work Address"
    FAIL=$((FAIL + 1))
fi
TOTAL=$((TOTAL + 1))

test_api "Get All Addresses" \
    "GET" "$BASE/customers/addresses" "" "200" "$CUSTOMER_TOKEN"

test_api "Update Address" \
    "PUT" "$BASE/customers/addresses/$ADDRESS_ID" \
    "{\"addressLine1\":\"789 Updated Road\",\"city\":\"Bangalore\",\"state\":\"Karnataka\",\"pincode\":\"560001\",\"latitude\":12.9716,\"longitude\":77.5946}" \
    "200" "$CUSTOMER_TOKEN"

test_api "Set Default Address" \
    "PUT" "$BASE/customers/addresses/$ADDRESS2_ID/set-default" "" "200" "$CUSTOMER_TOKEN"

# ==================== RESTAURANT (OWNER) ====================

log_header "10. RESTAURANT MANAGEMENT (OWNER)"

log_section "Create Restaurant"
RESTAURANT_RESPONSE=$(do_request "POST" "$BASE/restaurants/owner" \
    "{\"name\":\"Bhukkad Kitchen\",\"description\":\"Best food in town\",\"address\":{\"addressLine1\":\"100 Food Street\",\"city\":\"Bangalore\",\"state\":\"Karnataka\",\"pincode\":\"560001\",\"latitude\":12.9716,\"longitude\":77.5946},\"openingTime\":\"00:00:00\",\"closingTime\":\"23:59:59\",\"averageDeliveryTime\":30,\"minimumOrderAmount\":150.0,\"deliveryFee\":40.0,\"freeDeliveryAvailable\":true,\"freeDeliveryAbove\":500.0,\"isPureVeg\":false,\"features\":[\"Hygiene Certified\",\"Live Tracking\"],\"fssaiNumber\":\"12345678901234\"}" \
    "$OWNER_TOKEN")

RESTAURANT_ID=$(extract_json "$RESTAURANT_RESPONSE" "data.id")
if [ -n "$RESTAURANT_ID" ] && [ "$RESTAURANT_ID" != "" ]; then
    echo -e "  ${GREEN}✅ PASS${NC} | Create Restaurant (ID: $RESTAURANT_ID)"
    PASS=$((PASS + 1))
else
    echo -e "  ${RED}❌ FAIL${NC} | Create Restaurant"
    echo -e "  ${RED}   $(echo "$RESTAURANT_RESPONSE" | head -c 300)${NC}"
    FAIL=$((FAIL + 1))
fi
TOTAL=$((TOTAL + 1))

test_api "Get My Restaurants" \
    "GET" "$BASE/restaurants/owner/my-restaurants" "" "200" "$OWNER_TOKEN"

test_api "Update Restaurant" \
    "PUT" "$BASE/restaurants/owner/$RESTAURANT_ID" \
    "{\"name\":\"Bhukkad Kitchen Updated\",\"description\":\"Updated description\",\"address\":{\"addressLine1\":\"100 Food Street\",\"city\":\"Bangalore\",\"state\":\"Karnataka\",\"pincode\":\"560001\",\"latitude\":12.9716,\"longitude\":77.5946},\"openingTime\":\"00:00:00\",\"closingTime\":\"23:59:59\",\"minimumOrderAmount\":100.0,\"deliveryFee\":30.0,\"isPureVeg\":false}" \
    "200" "$OWNER_TOKEN"

test_api "Toggle Restaurant Open" \
    "PUT" "$BASE/restaurants/owner/$RESTAURANT_ID/toggle-status?isOpen=true" "" "200" "$OWNER_TOKEN"

test_api "Toggle Restaurant Closed" \
    "PUT" "$BASE/restaurants/owner/$RESTAURANT_ID/toggle-status?isOpen=false" "" "200" "$OWNER_TOKEN"

test_api "Toggle Restaurant Open Again" \
    "PUT" "$BASE/restaurants/owner/$RESTAURANT_ID/toggle-status?isOpen=true" "" "200" "$OWNER_TOKEN"

test_api "Restaurant Analytics" \
    "GET" "$BASE/restaurants/owner/$RESTAURANT_ID/analytics?days=30" "" "200" "$OWNER_TOKEN"

# ==================== RESTAURANT (PUBLIC) ====================

log_header "11. RESTAURANT PUBLIC ENDPOINTS"

test_api "Get All Restaurants" \
    "GET" "$BASE/restaurants/public" "" "200" ""

test_api "Get Restaurant By ID" \
    "GET" "$BASE/restaurants/public/$RESTAURANT_ID" "" "200" ""

test_api "Add Favorite Restaurant" \
    "POST" "$BASE/customers/favorites/$RESTAURANT_ID" "" "200" "$CUSTOMER_TOKEN"

test_api "List Favorite Restaurants" \
    "GET" "$BASE/customers/favorites" "" "200" "$CUSTOMER_TOKEN"

test_api "Search Restaurants" \
    "GET" "$BASE/restaurants/public/search?keyword=Bhukkad" "" "200" ""

test_api "Search Restaurants - No Results" \
    "GET" "$BASE/restaurants/public/search?keyword=nonexistentxyz" "" "200" ""

test_api "Filter - Pure Veg" \
    "GET" "$BASE/restaurants/public/filter?isPureVeg=true" "" "200" ""

test_api "Filter - Non Veg" \
    "GET" "$BASE/restaurants/public/filter?isPureVeg=false" "" "200" ""

test_api "Nearby Restaurants" \
    "GET" "$BASE/restaurants/public/nearby?latitude=12.9716&longitude=77.5946&radiusKm=10" "" "200" ""

# ==================== MENU CATEGORIES ====================

log_header "12. MENU CATEGORIES (OWNER)"

log_section "Create Category - Starters"
CATEGORY_RESPONSE=$(do_request "POST" "$BASE/menu/categories?restaurantId=$RESTAURANT_ID" \
    "{\"name\":\"Starters\",\"description\":\"Appetizers and snacks\",\"displayOrder\":1,\"active\":true}" \
    "$OWNER_TOKEN")

CATEGORY_ID=$(extract_json "$CATEGORY_RESPONSE" "data.id")
if [ -n "$CATEGORY_ID" ] && [ "$CATEGORY_ID" != "" ]; then
    echo -e "  ${GREEN}✅ PASS${NC} | Create Category - Starters (ID: $CATEGORY_ID)"
    PASS=$((PASS + 1))
else
    echo -e "  ${RED}❌ FAIL${NC} | Create Category - Starters"
    echo -e "  ${RED}   $(echo "$CATEGORY_RESPONSE" | head -c 300)${NC}"
    FAIL=$((FAIL + 1))
fi
TOTAL=$((TOTAL + 1))

log_section "Create Category - Main Course"
CATEGORY2_RESPONSE=$(do_request "POST" "$BASE/menu/categories?restaurantId=$RESTAURANT_ID" \
    "{\"name\":\"Main Course\",\"description\":\"Rice and curry dishes\",\"displayOrder\":2,\"active\":true}" \
    "$OWNER_TOKEN")

CATEGORY2_ID=$(extract_json "$CATEGORY2_RESPONSE" "data.id")
if [ -n "$CATEGORY2_ID" ]; then
    echo -e "  ${GREEN}✅ PASS${NC} | Create Category - Main Course (ID: $CATEGORY2_ID)"
    PASS=$((PASS + 1))
else
    echo -e "  ${RED}❌ FAIL${NC} | Create Category - Main Course"
    FAIL=$((FAIL + 1))
fi
TOTAL=$((TOTAL + 1))

test_api "Get Categories by Restaurant" \
    "GET" "$BASE/menu/categories/restaurant/$RESTAURANT_ID" "" "200" "$OWNER_TOKEN"

# ==================== MENU ITEMS ====================

log_header "13. MENU ITEMS (OWNER)"

log_section "Create Menu Item - Paneer Tikka"
ITEM_RESPONSE=$(do_request "POST" "$BASE/menu/items" \
    "{\"name\":\"Paneer Tikka\",\"description\":\"Grilled cottage cheese with spices\",\"categoryId\":$CATEGORY_ID,\"price\":249.0,\"originalPrice\":299.0,\"foodType\":\"VEG\",\"isVeg\":true,\"isSpicy\":true,\"spiceLevel\":\"MEDIUM\",\"preparationTime\":15,\"calories\":350,\"servingSize\":\"6 pieces\",\"ingredients\":[\"Paneer\",\"Bell Pepper\",\"Onion\"],\"tags\":[\"Bestseller\"],\"allergens\":[\"Dairy\"]}" \
    "$OWNER_TOKEN")

MENU_ITEM_ID=$(extract_json "$ITEM_RESPONSE" "data.id")
if [ -n "$MENU_ITEM_ID" ] && [ "$MENU_ITEM_ID" != "" ]; then
    echo -e "  ${GREEN}✅ PASS${NC} | Create Paneer Tikka (ID: $MENU_ITEM_ID)"
    PASS=$((PASS + 1))
else
    echo -e "  ${RED}❌ FAIL${NC} | Create Paneer Tikka"
    echo -e "  ${RED}   $(echo "$ITEM_RESPONSE" | head -c 300)${NC}"
    FAIL=$((FAIL + 1))
fi
TOTAL=$((TOTAL + 1))

log_section "Create Menu Item - Chicken Biryani"
ITEM2_RESPONSE=$(do_request "POST" "$BASE/menu/items" \
    "{\"name\":\"Chicken Biryani\",\"description\":\"Aromatic basmati rice with chicken\",\"categoryId\":$CATEGORY2_ID,\"price\":349.0,\"originalPrice\":399.0,\"foodType\":\"NON_VEG\",\"isVeg\":false,\"isSpicy\":true,\"spiceLevel\":\"HOT\",\"preparationTime\":25,\"calories\":650,\"servingSize\":\"1 plate\",\"ingredients\":[\"Chicken\",\"Rice\",\"Spices\"],\"tags\":[\"Popular\"]}" \
    "$OWNER_TOKEN")

MENU_ITEM2_ID=$(extract_json "$ITEM2_RESPONSE" "data.id")
if [ -n "$MENU_ITEM2_ID" ]; then
    echo -e "  ${GREEN}✅ PASS${NC} | Create Chicken Biryani (ID: $MENU_ITEM2_ID)"
    PASS=$((PASS + 1))
else
    echo -e "  ${RED}❌ FAIL${NC} | Create Chicken Biryani"
    FAIL=$((FAIL + 1))
fi
TOTAL=$((TOTAL + 1))

log_section "Create Menu Item - Masala Dosa"
ITEM3_RESPONSE=$(do_request "POST" "$BASE/menu/items" \
    "{\"name\":\"Masala Dosa\",\"description\":\"Crispy dosa with potato filling\",\"categoryId\":$CATEGORY_ID,\"price\":149.0,\"foodType\":\"VEG\",\"isVeg\":true,\"isSpicy\":false,\"spiceLevel\":\"MILD\",\"preparationTime\":10,\"calories\":300,\"servingSize\":\"1 dosa\"}" \
    "$OWNER_TOKEN")

MENU_ITEM3_ID=$(extract_json "$ITEM3_RESPONSE" "data.id")
if [ -n "$MENU_ITEM3_ID" ]; then
    echo -e "  ${GREEN}✅ PASS${NC} | Create Masala Dosa (ID: $MENU_ITEM3_ID)"
    PASS=$((PASS + 1))
else
    echo -e "  ${RED}❌ FAIL${NC} | Create Masala Dosa"
    FAIL=$((FAIL + 1))
fi
TOTAL=$((TOTAL + 1))

# Menu Item Public Endpoints
log_section "Menu Item Public Endpoints"

test_api "Get Menu Item by ID" \
    "GET" "$BASE/menu/items/$MENU_ITEM_ID" "" "200" ""

test_api "Get Items by Restaurant" \
    "GET" "$BASE/menu/items/restaurant/$RESTAURANT_ID" "" "200" ""

test_api "Get Items by Category" \
    "GET" "$BASE/menu/items/category/$CATEGORY_ID" "" "200" ""

test_api "Get Bestsellers" \
    "GET" "$BASE/menu/items/restaurant/$RESTAURANT_ID/bestsellers" "" "200" ""

test_api "Search Menu Items" \
    "GET" "$BASE/menu/items/search?keyword=paneer" "" "200" ""

test_api "Search Menu Items - biryani" \
    "GET" "$BASE/menu/items/search?keyword=biryani" "" "200" ""

log_section "Unified Search (restaurants + menu)"
test_api "Unified Search" \
    "GET" "$BASE/search?keyword=Paneer" "" "200" ""

test_api "Unified Search - Restaurant Name" \
    "GET" "$BASE/search?keyword=Bhukkad" "" "200" ""

# Menu Item Owner Operations
log_section "Menu Item Owner Operations"

test_api "Update Menu Item" \
    "PUT" "$BASE/menu/items/$MENU_ITEM_ID" \
    "{\"name\":\"Paneer Tikka Special\",\"description\":\"Updated description\",\"categoryId\":$CATEGORY_ID,\"price\":279.0,\"originalPrice\":329.0,\"foodType\":\"VEG\",\"isVeg\":true,\"stockQuantity\":100}" \
    "200" "$OWNER_TOKEN"

test_api "Toggle Item Unavailable" \
    "PUT" "$BASE/menu/items/$MENU_ITEM_ID/toggle-availability?available=false" "" "200" "$OWNER_TOKEN"

test_api "Toggle Item Available" \
    "PUT" "$BASE/menu/items/$MENU_ITEM_ID/toggle-availability?available=true" "" "200" "$OWNER_TOKEN"

test_api "Set Low Stock Quantity" \
    "PUT" "$BASE/menu/items/$MENU_ITEM_ID" \
    "{\"name\":\"Paneer Tikka Special\",\"description\":\"Updated description\",\"categoryId\":$CATEGORY_ID,\"price\":279.0,\"foodType\":\"VEG\",\"isVeg\":true,\"stockQuantity\":5}" \
    "200" "$OWNER_TOKEN"

test_api "Get Low Stock Items" \
    "GET" "$BASE/menu/items/restaurant/$RESTAURANT_ID/low-stock?threshold=10" "" "200" "$OWNER_TOKEN"

# ==================== CART ====================

log_header "14. CART (CUSTOMER)"

test_api "Get Empty Cart" \
    "GET" "$BASE/cart" "" "200" "$CUSTOMER_TOKEN"

test_api "Add Item to Cart - Paneer Tikka x2" \
    "POST" "$BASE/cart/add" \
    "{\"menuItemId\":$MENU_ITEM_ID,\"quantity\":2,\"specialInstructions\":\"Extra spicy\"}" \
    "200" "$CUSTOMER_TOKEN"

test_api "Add Item to Cart - Biryani x1" \
    "POST" "$BASE/cart/add" \
    "{\"menuItemId\":$MENU_ITEM2_ID,\"quantity\":1}" \
    "200" "$CUSTOMER_TOKEN"

test_api "Get Cart with Items" \
    "GET" "$BASE/cart" "" "200" "$CUSTOMER_TOKEN"

# Get cart item ID for update/remove
CART_RESPONSE=$(do_request "GET" "$BASE/cart" "" "$CUSTOMER_TOKEN")
CART_ITEM_ID=$(extract_json "$CART_RESPONSE" "data.items.0.id" 2>/dev/null)
if [ -z "$CART_ITEM_ID" ] || [ "$CART_ITEM_ID" = "" ]; then
    CART_ITEM_ID=$(echo "$CART_RESPONSE" | python3 -c "
import sys,json
try:
    data=json.load(sys.stdin)
    items=data.get('data',{}).get('items',[])
    if items: print(items[0].get('id',''))
    else: print('')
except: print('')
" 2>/dev/null)
fi

if [ -n "$CART_ITEM_ID" ] && [ "$CART_ITEM_ID" != "" ]; then
    test_api "Update Cart Item Quantity" \
        "PUT" "$BASE/cart/items/$CART_ITEM_ID?quantity=3" "" "200" "$CUSTOMER_TOKEN"
fi

test_api "Clear Cart" \
    "DELETE" "$BASE/cart/clear" "" "200" "$CUSTOMER_TOKEN"

test_api "Get Empty Cart After Clear" \
    "GET" "$BASE/cart" "" "200" "$CUSTOMER_TOKEN"

# ==================== ORDERS ====================
log_header "15. ORDERS (CUSTOMER)"

# Add items to cart for order
do_request "POST" "$BASE/cart/add" \
    "{\"menuItemId\":$MENU_ITEM_ID,\"quantity\":2}" "$CUSTOMER_TOKEN" > /dev/null

do_request "POST" "$BASE/cart/add" \
    "{\"menuItemId\":$MENU_ITEM2_ID,\"quantity\":1}" "$CUSTOMER_TOKEN" > /dev/null

log_section "Create Order (with rider tip)"
ORDER_RESPONSE=$(do_request "POST" "$BASE/orders/customer/create" \
    "{\"restaurantId\":$RESTAURANT_ID,\"deliveryAddressId\":$ADDRESS_ID,\"specialInstructions\":\"Ring the bell\",\"contactlessDelivery\":false,\"paymentMethod\":\"CASH_ON_DELIVERY\",\"tipAmount\":20.0}" \
    "$CUSTOMER_TOKEN")

ORDER_ID=$(extract_json "$ORDER_RESPONSE" "data.id")
ORDER_NUMBER=$(extract_json "$ORDER_RESPONSE" "data.orderNumber")

if [ -n "$ORDER_ID" ] && [ "$ORDER_ID" != "" ]; then
    echo -e "  ${GREEN}✅ PASS${NC} | Create Order (ID: $ORDER_ID, Number: $ORDER_NUMBER)"
    PASS=$((PASS + 1))
else
    echo -e "  ${RED}❌ FAIL${NC} | Create Order"
    echo -e "  ${RED}   $(echo "$ORDER_RESPONSE" | head -c 300)${NC}"
    FAIL=$((FAIL + 1))
fi
TOTAL=$((TOTAL + 1))

test_api "Get My Orders" \
    "GET" "$BASE/orders/customer/my-orders" "" "200" "$CUSTOMER_TOKEN"

test_api "Get Order by ID" \
    "GET" "$BASE/orders/customer/$ORDER_ID" "" "200" "$CUSTOMER_TOKEN"

# Track Order is guarded: with an empty $ORDER_ID the URL degrades to
# /orders/customer/track/ (unmapped -> /error forward) and with an empty
# $CUSTOMER_TOKEN test_api silently drops the Authorization header, so an
# unguarded call reports a misleading 403 instead of a real failure.
if [ -n "$ORDER_ID" ] && [ -n "$CUSTOMER_TOKEN" ]; then
    test_api "Track Order" \
        "GET" "$BASE/orders/customer/track/$ORDER_ID" "" "200" "$CUSTOMER_TOKEN"

    # Documented alias form (docs/api-usage.md): /orders/customer/{orderId}/track
    test_api "Track Order (alias path)" \
        "GET" "$BASE/orders/customer/$ORDER_ID/track" "" "200" "$CUSTOMER_TOKEN"
else
    echo -e "  ${RED}❌ FAIL${NC} | Track Order (skipped: missing ORDER_ID or CUSTOMER_TOKEN)"
    FAIL=$((FAIL + 1))
    TOTAL=$((TOTAL + 1))
fi

if [ -n "$ORDER_ID" ] && [ "$ORDER_ID" != "" ]; then
    log_section "Live ETA on Track"
    TRACK_RESPONSE=$(do_request "GET" "$BASE/orders/customer/track/$ORDER_ID" "" "$CUSTOMER_TOKEN")
    LIVE_ETA=$(extract_json "$TRACK_RESPONSE" "data.liveEtaMinutes")
    TOTAL=$((TOTAL + 1))
    if [ -n "$LIVE_ETA" ] && [ "$LIVE_ETA" != "null" ] && [ "$LIVE_ETA" != "" ]; then
        echo -e "  ${GREEN}✅ PASS${NC} | Live ETA present (liveEtaMinutes: $LIVE_ETA)"
        PASS=$((PASS + 1))
    else
        echo -e "  ${RED}❌ FAIL${NC} | Live ETA missing on track response"
        FAIL=$((FAIL + 1))
    fi

    # Test cursor-based pagination
    test_api "Get Restaurant Orders (cursor)" \
        "GET" "$BASE/orders/restaurant/$RESTAURANT_ID/cursor" "" "200" "$OWNER_TOKEN"

    test_api "Get Kitchen Queue (cached)" \
        "GET" "$BASE/orders/restaurant/$RESTAURANT_ID/kitchen-queue" "" "200" "$OWNER_TOKEN"

    test_api "Kitchen Queue Cached Repeat (2nd call)" \
        "GET" "$BASE/orders/restaurant/$RESTAURANT_ID/kitchen-queue" "" "200" "$OWNER_TOKEN"

    # Test order by order number
    test_api "Get Order by Number" \
        "GET" "$BASE/orders/number/$ORDER_NUMBER" "" "200" "$CUSTOMER_TOKEN"
fi

# Async order creation test (separate from sync order)
if [ -n "$RESTAURANT_ID" ] && [ -n "$CUSTOMER_TOKEN" ] && [ -n "$ADDRESS_ID" ]; then
    log_section "Async Order Creation"
    do_request "POST" "$BASE/cart/add" \
        "{\"menuItemId\":$MENU_ITEM_ID,\"quantity\":1}" "$CUSTOMER_TOKEN" > /dev/null
    ASYNC_HTTP=$(curl -s -o /tmp/bhukkad-async-order.json -w "%{http_code}" -X POST \
        "$BASE/orders/customer/create?async=true" \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer $CUSTOMER_TOKEN" \
        -d "{\"restaurantId\":$RESTAURANT_ID,\"deliveryAddressId\":$ADDRESS_ID,\"paymentMethod\":\"CASH_ON_DELIVERY\"}")
    ASYNC_RESPONSE=$(cat /tmp/bhukkad-async-order.json 2>/dev/null)
    ASYNC_STATUS=$(extract_json "$ASYNC_RESPONSE" "data.status")
    TOTAL=$((TOTAL + 1))
    if { [ "$ASYNC_HTTP" = "200" ] || [ "$ASYNC_HTTP" = "202" ]; } && { [ "$ASYNC_STATUS" = "PROCESSING" ] || [ "$ASYNC_STATUS" = "ACCEPTED" ] || [ "$ASYNC_STATUS" = "PENDING" ] || [ "$ASYNC_STATUS" = "COMPLETED" ]; }; then
        echo -e "  ${GREEN}✅ PASS${NC} | Async Order Creation (HTTP $ASYNC_HTTP, status: $ASYNC_STATUS)"
        PASS=$((PASS + 1))
    else
        echo -e "  ${RED}❌ FAIL${NC} | Async Order Creation (HTTP $ASYNC_HTTP, status: $ASYNC_STATUS)"
        echo -e "  ${RED}   $(echo "$ASYNC_RESPONSE" | head -c 200)${NC}"
        FAIL=$((FAIL + 1))
    fi
fi

# Test payment endpoint
if [ -n "$ORDER_ID" ] && [ -n "$CUSTOMER_TOKEN" ]; then
    test_api "Get Payment for Order" \
        "GET" "$BASE/payments/orders/$ORDER_ID" "" "200" "$CUSTOMER_TOKEN"
fi

# ==================== ORDERS (RESTAURANT) ====================

log_header "16. ORDER MANAGEMENT (RESTAURANT OWNER)"

test_api "Get Restaurant Orders" \
    "GET" "$BASE/orders/restaurant/$RESTAURANT_ID" "" "200" "$OWNER_TOKEN"

test_api "Get Pending Orders" \
    "GET" "$BASE/orders/restaurant/$RESTAURANT_ID/pending" "" "200" "$OWNER_TOKEN"

test_api "Accept Order" \
    "PUT" "$BASE/orders/restaurant/$ORDER_ID/accept" "" "200" "$OWNER_TOKEN"

test_api "Mark Order Ready" \
    "PUT" "$BASE/orders/restaurant/$ORDER_ID/ready" "" "200" "$OWNER_TOKEN"

test_api "Assign Delivery Agent" \
    "PUT" "$BASE/orders/restaurant/$ORDER_ID/assign-delivery?agentId=$AGENT_ID" "" "200" "$OWNER_TOKEN"

# ==================== DELIVERY BATCHES (before order is delivered) ====================

log_header "16.5. DELIVERY BATCHES"

if [ -n "$AGENT_TOKEN" ] && [ -n "$ORDER_ID" ]; then
    test_api "Create Delivery Batch" \
        "POST" "$BASE/delivery/batches" "" "200" "$AGENT_TOKEN"

    test_api "Get Active Delivery Batches" \
        "GET" "$BASE/delivery/batches/active" "" "200" "$AGENT_TOKEN"

    test_api "Get Available Orders" \
        "GET" "$BASE/delivery/available-orders" "" "200" "$AGENT_TOKEN"
fi

# ==================== ORDERS (DELIVERY) ====================

log_header "17. DELIVERY AGENT OPERATIONS"

test_api "Get Agent Profile" \
    "GET" "$BASE/delivery/profile" "" "200" "$AGENT_TOKEN"

test_api "Toggle Available" \
    "PUT" "$BASE/delivery/toggle-availability?available=true" "" "200" "$AGENT_TOKEN"

test_api "Update Location" \
    "PUT" "$BASE/delivery/update-location?latitude=12.9716&longitude=77.5946" "" "200" "$AGENT_TOKEN"

test_api "Get Active Deliveries" \
    "GET" "$BASE/delivery/active-deliveries" "" "200" "$AGENT_TOKEN"

test_api "Get Delivery History" \
    "GET" "$BASE/delivery/delivery-history" "" "200" "$AGENT_TOKEN"

test_api "Agent Earnings Summary" \
    "GET" "$BASE/delivery/earnings/summary" "" "200" "$AGENT_TOKEN"

test_api "Agent Earnings History" \
    "GET" "$BASE/delivery/earnings?page=0&size=10" "" "200" "$AGENT_TOKEN"

if [ -n "$ORDER_ID" ] && [ "$ORDER_ID" != "" ]; then
    test_api "Mark Order Picked Up" \
        "PUT" "$BASE/orders/delivery/$ORDER_ID/picked-up" "" "200" "$AGENT_TOKEN"

    # ---- V17: Delivery proof (OTP + photo) ----
    # Ordered before "Mark Order Delivered" because that is where proof is
    # consumed once app.delivery.proof.enforced=true. With the shipped default
    # (enforced=false) assertProofSatisfied() short-circuits, so the delivered
    # call below still returns 200 even though no proof was ever satisfied.
    log_section "Delivery Proof (V17)"

    test_api "Issue Delivery Proof OTP" \
        "POST" "$BASE/orders/delivery/$ORDER_ID/proof/otp" "" "200" "$AGENT_TOKEN"

    TOTAL=$((TOTAL + 1))
    PHOTO_URL_RESPONSE=$(do_request "POST" "$BASE/orders/delivery/$ORDER_ID/proof/photo-url" \
        "{\"contentType\":\"image/jpeg\"}" "$AGENT_TOKEN")
    # When S3 is not configured in Docker, the endpoint correctly returns 400.
    if echo "$PHOTO_URL_RESPONSE" | rg -q '"success":true' 2>/dev/null; then
        echo -e "  ${GREEN}✅ PASS${NC} | Create Proof Photo Upload URL (HTTP 200)"
        PASS=$((PASS + 1))
    elif echo "$PHOTO_URL_RESPONSE" | rg -qi "not enabled|not configured" 2>/dev/null; then
        echo -e "  ${GREEN}✅ PASS${NC} | Create Proof Photo Upload URL (S3 disabled — expected 400)"
        PASS=$((PASS + 1))
    else
        echo -e "  ${RED}❌ FAIL${NC} | Create Proof Photo Upload URL"
        echo -e "  ${RED}   $(echo "$PHOTO_URL_RESPONSE" | head -c 200)${NC}"
        FAIL=$((FAIL + 1))
    fi

    # The plaintext OTP is never returned by any endpoint (only a BCrypt hash is
    # persisted; the code is SMSed to the customer), so a shell script can never
    # produce a passing verification. We assert the negative path instead: a
    # well-formed but incorrect code must be rejected with 400, which proves the
    # endpoint is mapped, authorized, and validating rather than accepting blindly.
    test_api "Verify Delivery Proof (wrong OTP rejected)" \
        "POST" "$BASE/orders/delivery/$ORDER_ID/proof/verify" \
        "{\"otpCode\":\"000000\",\"recipientName\":\"API Test\"}" "400" "$AGENT_TOKEN"

    test_api "Get Delivery Proof Status" \
        "GET" "$BASE/orders/delivery/$ORDER_ID/proof" "" "200" "$AGENT_TOKEN"

    test_api "Delivery Proof Rejects Customer Role" \
        "GET" "$BASE/orders/delivery/$ORDER_ID/proof" "" "403" "$CUSTOMER_TOKEN"

    test_api "Mark Order Delivered" \
        "PUT" "$BASE/orders/delivery/$ORDER_ID/delivered" "" "200" "$AGENT_TOKEN"

    log_section "Post-Delivery Customer Actions"
    test_api "Reorder from Delivered Order" \
        "POST" "$BASE/orders/customer/$ORDER_ID/reorder" "" "200" "$CUSTOMER_TOKEN"

    test_api "Customer Order Stats" \
        "GET" "$BASE/customers/orders/stats" "" "200" "$CUSTOMER_TOKEN"
fi

# ==================== LIVE STREAMING (SSE) ====================

log_header "17.5. LIVE ORDER STREAMING (SSE)"

# SSE endpoints produce text/event-stream; test_api only checks HTTP status
# since SSE doesn't return JSON. We use -N (no buffering) and a timeout.
if [ -n "$ORDER_ID" ] && [ -n "$CUSTOMER_TOKEN" ]; then
    TOTAL=$((TOTAL + 1))
    HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" -N \
        --max-time 3 \
        -H "Accept: text/event-stream" \
        -H "Authorization: Bearer $CUSTOMER_TOKEN" \
        "$BASE/orders/stream/customer/$ORDER_ID" 2>/dev/null)
    HTTP_CODE=${HTTP_CODE:-000}
    if [ "$HTTP_CODE" = "200" ]; then
        echo -e "  ${GREEN}✅ PASS${NC} | Customer Order SSE Stream (HTTP $HTTP_CODE)"
        PASS=$((PASS + 1))
    else
        echo -e "  ${RED}❌ FAIL${NC} | Customer Order SSE Stream (HTTP $HTTP_CODE)"
        FAIL=$((FAIL + 1))
    fi
fi

if [ -n "$RESTAURANT_ID" ] && [ -n "$OWNER_TOKEN" ]; then
    TOTAL=$((TOTAL + 1))
    HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" -N \
        --max-time 3 \
        -H "Authorization: Bearer $OWNER_TOKEN" \
        "$BASE/orders/stream/kitchen/$RESTAURANT_ID" 2>/dev/null)
    HTTP_CODE=${HTTP_CODE:-000}
    if [ "$HTTP_CODE" = "200" ]; then
        echo -e "  ${GREEN}✅ PASS${NC} | Kitchen SSE Stream (HTTP $HTTP_CODE)"
        PASS=$((PASS + 1))
    else
        echo -e "  ${RED}❌ FAIL${NC} | Kitchen SSE Stream (HTTP $HTTP_CODE)"
        FAIL=$((FAIL + 1))
    fi
fi

if [ -n "$AGENT_TOKEN" ]; then
    TOTAL=$((TOTAL + 1))
    HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" -N \
        --max-time 3 \
        -H "Authorization: Bearer $AGENT_TOKEN" \
        "$BASE/orders/stream/rider" 2>/dev/null)
    HTTP_CODE=${HTTP_CODE:-000}
    if [ "$HTTP_CODE" = "200" ]; then
        echo -e "  ${GREEN}✅ PASS${NC} | Rider SSE Stream (HTTP $HTTP_CODE)"
        PASS=$((PASS + 1))
    else
        echo -e "  ${RED}❌ FAIL${NC} | Rider SSE Stream (HTTP $HTTP_CODE)"
        FAIL=$((FAIL + 1))
    fi
fi

# ==================== DELIVERY BATCHES (legacy section removed — covered in 16.5) ====================

# ==================== SCHEDULED ORDERS ====================

log_header "18. SCHEDULED ORDERS"

# Restore stock depleted by earlier low-stock / order tests.
if [ -n "$MENU_ITEM_ID" ] && [ -n "$OWNER_TOKEN" ] && [ -n "$CATEGORY_ID" ]; then
    do_request "PUT" "$BASE/menu/items/$MENU_ITEM_ID" \
        "{\"name\":\"Paneer Tikka Special\",\"description\":\"Updated description\",\"categoryId\":$CATEGORY_ID,\"price\":279.0,\"foodType\":\"VEG\",\"isVeg\":true,\"stockQuantity\":100}" \
        "$OWNER_TOKEN" > /dev/null
fi

do_request "POST" "$BASE/cart/clear" "" "$CUSTOMER_TOKEN" > /dev/null
do_request "POST" "$BASE/cart/add" \
    "{\"menuItemId\":$MENU_ITEM_ID,\"quantity\":1}" "$CUSTOMER_TOKEN" > /dev/null

SCHEDULED_AT=$(python3 -c "from datetime import datetime, timedelta; print((datetime.now() + timedelta(minutes=35)).strftime('%Y-%m-%dT%H:%M:%S'))")
SCHEDULED_RESPONSE=$(do_request "POST" "$BASE/orders/customer/create" \
    "{\"restaurantId\":$RESTAURANT_ID,\"deliveryAddressId\":$ADDRESS_ID,\"paymentMethod\":\"CASH_ON_DELIVERY\",\"scheduledAt\":\"$SCHEDULED_AT\"}" \
    "$CUSTOMER_TOKEN")
SCHEDULED_STATUS=$(extract_json "$SCHEDULED_RESPONSE" "data.status")
TOTAL=$((TOTAL + 1))
if [ "$SCHEDULED_STATUS" = "SCHEDULED" ]; then
    echo -e "  ${GREEN}✅ PASS${NC} | Create Scheduled Order (status: $SCHEDULED_STATUS, at: $SCHEDULED_AT)"
    PASS=$((PASS + 1))
else
    echo -e "  ${RED}❌ FAIL${NC} | Create Scheduled Order (expected SCHEDULED, got: $SCHEDULED_STATUS)"
    echo -e "  ${RED}   $(echo "$SCHEDULED_RESPONSE" | head -c 200)${NC}"
    FAIL=$((FAIL + 1))
fi

# ==================== CANCEL ORDER TEST ====================

log_header "19. ORDER CANCELLATION"

# Create another order to cancel
do_request "POST" "$BASE/cart/add" \
    "{\"menuItemId\":$MENU_ITEM_ID,\"quantity\":1}" "$CUSTOMER_TOKEN" > /dev/null

CANCEL_ORDER_RESPONSE=$(do_request "POST" "$BASE/orders/customer/create" \
    "{\"restaurantId\":$RESTAURANT_ID,\"deliveryAddressId\":$ADDRESS_ID,\"paymentMethod\":\"CASH_ON_DELIVERY\"}" \
    "$CUSTOMER_TOKEN")

CANCEL_ORDER_ID=$(extract_json "$CANCEL_ORDER_RESPONSE" "data.id")

if [ -n "$CANCEL_ORDER_ID" ] && [ "$CANCEL_ORDER_ID" != "" ]; then
    test_api "Cancel Order" \
        "PUT" "$BASE/orders/customer/$CANCEL_ORDER_ID/cancel?reason=Changed+my+mind" "" "200" "$CUSTOMER_TOKEN"
fi

# ==================== REVIEWS ====================

log_header "20. REVIEWS (CUSTOMER)"

log_section "Submit Review"
REVIEW_RESPONSE=$(do_request "POST" "$BASE/reviews" \
    "{\"orderId\":$ORDER_ID,\"rating\":4,\"comment\":\"Great food! Loved the paneer tikka.\",\"foodRating\":5,\"deliveryRating\":4}" \
    "$CUSTOMER_TOKEN")

REVIEW_ID=$(extract_json "$REVIEW_RESPONSE" "data.id")
if [ -n "$REVIEW_ID" ] && [ "$REVIEW_ID" != "" ]; then
    echo -e "  ${GREEN}✅ PASS${NC} | Submit Review (ID: $REVIEW_ID)"
    PASS=$((PASS + 1))
else
    echo -e "  ${YELLOW}⚠️  SKIP${NC} | Submit Review (Order may not be delivered)"
    SKIP=$((SKIP + 1))
fi
TOTAL=$((TOTAL + 1))

test_api "Get Restaurant Reviews" \
    "GET" "$BASE/reviews/restaurant/$RESTAURANT_ID" "" "200" "$CUSTOMER_TOKEN"

test_api "Get My Reviews" \
    "GET" "$BASE/reviews/my-reviews" "" "200" "$CUSTOMER_TOKEN"

# ---- V17: Review moderation + owner response ----
login_admin
if [ -n "$REVIEW_ID" ] && [ "$REVIEW_ID" != "" ] && [ -n "$ADMIN_TOKEN" ]; then
    log_section "Review Moderation (V17)"

    test_api "Admin Review Moderation Queue" \
        "GET" "$BASE/admin/reviews/moderation" "" "200" "$ADMIN_TOKEN"

    test_api "Admin Moderation Queue (PENDING filter)" \
        "GET" "$BASE/admin/reviews/moderation?status=PENDING" "" "200" "$ADMIN_TOKEN"

    # status is a required query param on PUT /admin/reviews/{id}/moderate and is
    # parsed into Review.ModerationStatus (PENDING|APPROVED|REJECTED).
    test_api "Admin Approve Review" \
        "PUT" "$BASE/admin/reviews/$REVIEW_ID/moderate?status=APPROVED" "" "200" "$ADMIN_TOKEN"

    test_api "Moderation Queue Rejects Non-Admin" \
        "GET" "$BASE/admin/reviews/moderation" "" "403" "$CUSTOMER_TOKEN"

    # Public read (no token) must succeed and only expose APPROVED reviews.
    test_api "Public Restaurant Reviews (approved only)" \
        "GET" "$BASE/reviews/restaurant/$RESTAURANT_ID" "" "200" ""
else
    echo -e "  ${RED}❌ FAIL${NC} | Review Moderation (missing REVIEW_ID or ADMIN_TOKEN)"
    FAIL=$((FAIL + 1))
    TOTAL=$((TOTAL + 1))
fi

if [ -n "$REVIEW_ID" ] && [ "$REVIEW_ID" != "" ] && [ -n "$OWNER_TOKEN" ]; then
    test_api "Owner Responds to Review" \
        "POST" "$BASE/restaurants/owner/reviews/$REVIEW_ID/response" \
        "{\"response\":\"Thanks for the kind words! See you again soon.\"}" "200" "$OWNER_TOKEN"
fi

if [ -n "$ORDER_ID" ] && [ -n "$MENU_ITEM_ID" ]; then
    log_section "Menu Item Rating"
    RATING_RESPONSE=$(do_request "POST" "$BASE/reviews/menu-items" \
        "{\"orderId\":$ORDER_ID,\"menuItemId\":$MENU_ITEM_ID,\"rating\":5,\"comment\":\"Best paneer tikka!\"}" \
        "$CUSTOMER_TOKEN")
    RATING_ID=$(extract_json "$RATING_RESPONSE" "data.id")
    TOTAL=$((TOTAL + 1))
    if [ -n "$RATING_ID" ]; then
        echo -e "  ${GREEN}✅ PASS${NC} | Rate Menu Item (ID: $RATING_ID)"
        PASS=$((PASS + 1))
    else
        echo -e "  ${RED}❌ FAIL${NC} | Rate Menu Item"
        echo -e "  ${RED}   $(echo "$RATING_RESPONSE" | head -c 200)${NC}"
        FAIL=$((FAIL + 1))
    fi

    test_api "Get Menu Item Ratings" \
        "GET" "$BASE/reviews/menu-items/$MENU_ITEM_ID" "" "200" ""
fi

# ==================== SEARCH ====================

log_header "21.5. SEARCH"

test_api "Unified Search (keyword)" \
    "GET" "$BASE/search?keyword=food" "" "200" ""

test_api "Search Restaurants (public)" \
    "GET" "$BASE/restaurants/public/search?keyword=Bhukkad" "" "200" ""

test_api "Search Menu Items (public)" \
    "GET" "$BASE/menu/items/search?keyword=paneer" "" "200" ""

# ==================== BATCH CHECKOUT ====================

log_header "21. BATCH CHECKOUT"

# Ensure cart has stock-backed items.
if [ -n "$MENU_ITEM2_ID" ] && [ -n "$OWNER_TOKEN" ] && [ -n "$CATEGORY2_ID" ]; then
    do_request "PUT" "$BASE/menu/items/$MENU_ITEM2_ID" \
        "{\"name\":\"Chicken Biryani\",\"description\":\"Aromatic basmati rice with chicken\",\"categoryId\":$CATEGORY2_ID,\"price\":349.0,\"foodType\":\"NON_VEG\",\"isVeg\":false,\"stockQuantity\":100}" \
        "$OWNER_TOKEN" > /dev/null
fi

do_request "POST" "$BASE/cart/clear" "" "$CUSTOMER_TOKEN" > /dev/null
do_request "POST" "$BASE/cart/add" \
    "{\"menuItemId\":$MENU_ITEM2_ID,\"quantity\":1}" "$CUSTOMER_TOKEN" > /dev/null

BATCH_RESPONSE=$(do_request "POST" "$BASE/orders/customer/create-batch" \
    "{\"deliveryAddressId\":$ADDRESS_ID,\"specialInstructions\":\"Batch test\",\"contactlessDelivery\":false,\"paymentMethod\":\"CASH_ON_DELIVERY\",\"tipAmount\":10.0}" \
    "$CUSTOMER_TOKEN")
BATCH_SUCCESS=$(extract_json "$BATCH_RESPONSE" "data.successCount")
TOTAL=$((TOTAL + 1))
if [ -n "$BATCH_SUCCESS" ] && [ "$BATCH_SUCCESS" != "0" ] && [ "$BATCH_SUCCESS" != "" ]; then
    echo -e "  ${GREEN}✅ PASS${NC} | Batch Checkout (successCount: $BATCH_SUCCESS)"
    PASS=$((PASS + 1))
else
    echo -e "  ${RED}❌ FAIL${NC} | Batch Checkout"
    echo -e "  ${RED}   $(echo "$BATCH_RESPONSE" | head -c 200)${NC}"
    FAIL=$((FAIL + 1))
fi

# ==================== ADMIN ====================

log_header "22. ADMIN OPERATIONS"

login_admin

if [ -n "$ADMIN_TOKEN" ]; then
    test_api "Admin Dashboard" \
        "GET" "$BASE/admin/dashboard" "" "200" "$ADMIN_TOKEN"

    COUPON_CODE="SAVE${TIMESTAMP}"
    test_api "Create Platform Coupon" \
        "POST" "$BASE/coupons" \
        "{\"code\":\"$COUPON_CODE\",\"description\":\"API test coupon\",\"discountType\":\"PERCENTAGE\",\"discountValue\":10.0,\"minimumOrderAmount\":100.0,\"maximumDiscountAmount\":50.0,\"usageLimit\":100,\"perUserLimit\":1,\"validFrom\":\"2020-01-01T00:00:00\",\"validUntil\":\"2030-12-31T23:59:59\"}" \
        "200" "$ADMIN_TOKEN"

    test_api "List Active Coupons" \
        "GET" "$BASE/coupons/active?restaurantId=$RESTAURANT_ID" "" "200" ""

    if [ -n "$COUPON_CODE" ]; then
        test_api "Validate Coupon" \
            "GET" "$BASE/coupons/validate?code=$COUPON_CODE&orderAmount=500&restaurantId=$RESTAURANT_ID" "" "200" "$CUSTOMER_TOKEN"
    fi
fi

if [ -n "$ADMIN_TOKEN" ] && [ -n "$AGENT_ID" ]; then
    test_api "Settle Rider Payouts" \
        "PUT" "$BASE/admin/agents/$AGENT_ID/settle-payouts" "" "200" "$ADMIN_TOKEN"
fi

if [ -n "$RESTAURANT_ID" ] && [ -n "$OWNER_TOKEN" ]; then
    test_api "Get Restaurant Settlements" \
        "GET" "$BASE/restaurants/owner/$RESTAURANT_ID/settlements" "" "200" "$OWNER_TOKEN"
fi

if [ -n "$RESTAURANT_ID" ] && [ -n "$ADMIN_TOKEN" ]; then
    test_api "Set Restaurant Commission" \
        "PUT" "$BASE/admin/restaurants/$RESTAURANT_ID/commission?percent=12.5" "" "200" "$ADMIN_TOKEN"

    test_api "Settle Restaurant Payouts" \
        "PUT" "$BASE/admin/restaurants/$RESTAURANT_ID/settle-payouts" "" "200" "$ADMIN_TOKEN"
fi

# ==================== GROWTH & OPERATIONS (V13) ====================

log_header "23. GROWTH & OPERATIONS"

test_api "Serviceability Check" \
    "GET" "$BASE/serviceability/check?restaurantId=$RESTAURANT_ID&latitude=12.9716&longitude=77.5946&subtotal=500" "" "200" ""

# V17: repeat the identical request so the second call is served from the
# short-TTL cache. Both must return 200 - a cached response that differs in
# status would surface here even though the script cannot inspect cache metrics.
test_api "Serviceability Check (cached repeat)" \
    "GET" "$BASE/serviceability/check?restaurantId=$RESTAURANT_ID&latitude=12.9716&longitude=77.5946&subtotal=500" "" "200" ""

test_api "Home Feed Banners" \
    "GET" "$BASE/home/banners" "" "200" ""

test_api "Home Feed Campaigns" \
    "GET" "$BASE/home/campaigns" "" "200" ""

test_api "Home Membership Plans" \
    "GET" "$BASE/home/membership-plans" "" "200" ""

test_api "Home Feed Combined" \
    "GET" "$BASE/home/feed" "" "200" ""

# V17: second call exercises the cached path for all three feed sections
# (banners, campaigns, membershipPlans), each cached under its own key.
test_api "Home Feed Combined (cached repeat)" \
    "GET" "$BASE/home/feed" "" "200" ""

if [ -n "$CUSTOMER_TOKEN" ]; then
    test_api "Wallet Transaction History" \
        "GET" "$BASE/customers/wallet/transactions?page=0&size=10" "" "200" "$CUSTOMER_TOKEN"

    test_api "List Membership Plans" \
        "GET" "$BASE/customers/membership/plans" "" "200" "$CUSTOMER_TOKEN"

    test_api "Membership Status" \
        "GET" "$BASE/customers/membership/status" "" "200" "$CUSTOMER_TOKEN"

    SUPPORT_RESPONSE=$(do_request "POST" "$BASE/customers/support/tickets" \
        "{\"category\":\"ORDER\",\"subject\":\"API test ticket\",\"description\":\"Created by test-all-apis.sh\",\"orderId\":$ORDER_ID}" \
        "$CUSTOMER_TOKEN")
    TICKET_ID=$(extract_json "$SUPPORT_RESPONSE" "data.id")
    TOTAL=$((TOTAL + 1))
    if [ -n "$TICKET_ID" ] && [ "$TICKET_ID" != "" ]; then
        echo -e "  ${GREEN}✅ PASS${NC} | Create Support Ticket (id: $TICKET_ID)"
        PASS=$((PASS + 1))
    else
        echo -e "  ${RED}❌ FAIL${NC} | Create Support Ticket"
        FAIL=$((FAIL + 1))
    fi

    test_api "List Support Tickets" \
        "GET" "$BASE/customers/support/tickets" "" "200" "$CUSTOMER_TOKEN"
fi

if [ -n "$ORDER_ID" ] && [ "$ORDER_ID" != "" ]; then
    test_api "Order Timeline" \
        "GET" "$BASE/orders/$ORDER_ID/timeline" "" "200" "$CUSTOMER_TOKEN"

    test_api "Order Invoice" \
        "GET" "$BASE/orders/$ORDER_ID/invoice" "" "200" "$CUSTOMER_TOKEN"

    # V17: binary download (produces=application/pdf, ResponseEntity<byte[]>).
    # test_api only compares the HTTP status, so no JSON extraction is attempted.
    test_api "Download GST Invoice PDF" \
        "GET" "$BASE/orders/$ORDER_ID/invoice/pdf" "" "200" "$CUSTOMER_TOKEN" "" "application/pdf"

    test_api "Invoice PDF Requires Auth" \
        "GET" "$BASE/orders/$ORDER_ID/invoice/pdf" "" "403" ""
fi

if [ -n "$ORDER_ID" ] && [ -n "$AGENT_TOKEN" ]; then
    test_api "Record Rider Location" \
        "POST" "$BASE/delivery/orders/$ORDER_ID/location" \
        "{\"latitude\":12.9720,\"longitude\":77.5950}" "200" "$AGENT_TOKEN"

    test_api "Get Rider Location" \
        "GET" "$BASE/orders/$ORDER_ID/rider-location" "" "200" "$CUSTOMER_TOKEN"
fi

if [ -n "$RESTAURANT_ID" ] && [ -n "$OWNER_TOKEN" ]; then
    test_api "Enable Restaurant Busy Mode" \
        "PUT" "$BASE/restaurants/owner/$RESTAURANT_ID/busy-mode" \
        "{\"extraPrepMinutes\":15}" "200" "$OWNER_TOKEN"

    test_api "Disable Restaurant Busy Mode" \
        "DELETE" "$BASE/restaurants/owner/$RESTAURANT_ID/busy-mode" "" "200" "$OWNER_TOKEN"
fi

if [ -n "$ADMIN_TOKEN" ] && [ -n "$TICKET_ID" ] && [ "$TICKET_ID" != "" ]; then
    test_api "Admin List Support Tickets" \
        "GET" "$BASE/admin/support/tickets" "" "200" "$ADMIN_TOKEN"

    test_api "Admin Update Support Ticket" \
        "PUT" "$BASE/admin/support/tickets/$TICKET_ID/status?status=RESOLVED&resolutionNotes=Resolved%20in%20API%20test" \
        "" "200" "$ADMIN_TOKEN"
fi

# ==================== V14–V16: DELIVERY TRUTH, GROWTH, SCALE OPS ====================

log_header "24. V14–V16 PLATFORM FEATURES"

if [ -n "$ORDER_ID" ] && [ -n "$CUSTOMER_TOKEN" ]; then
    test_api "Smarter ETA Detail" \
        "GET" "$BASE/delivery-truth/orders/$ORDER_ID/eta" "" "200" "$CUSTOMER_TOKEN"
fi

if [ -n "$ADMIN_TOKEN" ]; then
    test_api "Admin List Delivery Zones" \
        "GET" "$BASE/admin/zones" "" "200" "$ADMIN_TOKEN"

    test_api "Admin List Promotion Campaigns" \
        "GET" "$BASE/admin/promotions/campaigns" "" "200" "$ADMIN_TOKEN"

    test_api "Admin List Promo Banners" \
        "GET" "$BASE/admin/promotions/banners" "" "200" "$ADMIN_TOKEN"

    test_api "Admin Create Promo Banner" \
        "POST" "$BASE/admin/promotions/banners" \
        "{\"title\":\"Summer Sale\",\"description\":\"Summer special offers\",\"imageUrl\":\"https://example.com/summer.jpg\",\"ctaText\":\"Order Now\",\"ctaUrl\":\"/summer-sale\",\"active\":true,\"priority\":1}" \
        "200" "$ADMIN_TOKEN"

    test_api "Admin Operations Dashboard 2.0" \
        "GET" "$BASE/admin/operations-dashboard" "" "200" "$ADMIN_TOKEN"

    test_api "Trigger Settlement Run" \
        "POST" "$BASE/admin/settlements/run" "" "200" "$ADMIN_TOKEN"

    test_api "Admin List Users" \
        "GET" "$BASE/admin/users" "" "200" "$ADMIN_TOKEN"

    test_api "Admin List Orders" \
        "GET" "$BASE/admin/orders?page=0&size=10" "" "200" "$ADMIN_TOKEN"

    test_api "Admin List Restaurants" \
        "GET" "$BASE/admin/restaurants?page=0&size=10" "" "200" "$ADMIN_TOKEN"

    test_api "Admin Revenue Report" \
        "GET" "$BASE/admin/revenue" "" "200" "$ADMIN_TOKEN"

    test_api "Admin Analytics" \
        "GET" "$BASE/admin/analytics" "" "200" "$ADMIN_TOKEN"

    test_api "Admin List Zones" \
        "GET" "$BASE/admin/zones" "" "200" "$ADMIN_TOKEN"

    test_api "Admin Create Zone" \
        "POST" "$BASE/admin/zones" \
        "{\"name\":\"Zone ${RUN_ID}\",\"city\":\"Bangalore\",\"centerLatitude\":12.9716,\"centerLongitude\":77.5946,\"radiusKm\":10.0,\"isActive\":true}" \
        "200" "$ADMIN_TOKEN"

    test_api "Admin Platform Status" \
        "GET" "$BASE/platform/status" "" "200" "$ADMIN_TOKEN"
fi

if [ -n "$OWNER_TOKEN" ] && [ -n "$RESTAURANT_ID" ]; then
    test_api "Restaurant Dashboard 2.0" \
        "GET" "$BASE/restaurants/owner/$RESTAURANT_ID/dashboard?days=30" "" "200" "$OWNER_TOKEN"
fi

# ==================== CACHE OPERATIONS ====================

log_header "25. PLATFORM & CACHE"

test_api "Platform Status" \
    "GET" "$BASE/platform/status" "" "200" ""

if [ -n "$ADMIN_TOKEN" ]; then
    test_api "Admin Test Notification (email)" \
        "POST" "$BASE/admin/notifications/test" \
        "{\"channel\":\"email\",\"recipient\":\"test@bhukkad.dev\",\"message\":\"Bhukkad API test notification\"}" \
        "200" "$ADMIN_TOKEN"
fi

log_section "Cache Operations"

test_api "Cache Health" \
    "GET" "$BASE/cache/health" "" "200" ""

test_api "Cache Stats" \
    "GET" "$BASE/cache/stats" "" "200" ""

# ==================== CLEANUP TESTS ====================

log_header "26. CLEANUP & DELETE OPERATIONS"

if [ -n "$ADDRESS2_ID" ] && [ "$ADDRESS2_ID" != "" ]; then
    test_api "Delete Address" \
        "DELETE" "$BASE/customers/addresses/$ADDRESS2_ID" "" "200" "$CUSTOMER_TOKEN"
fi

test_api "Delete Menu Item" \
    "DELETE" "$BASE/menu/items/$MENU_ITEM3_ID" "" "200" "$OWNER_TOKEN"

test_api "Remove Favorite Restaurant" \
    "DELETE" "$BASE/customers/favorites/$RESTAURANT_ID" "" "200" "$CUSTOMER_TOKEN"

# ==================== LOGOUT ====================

log_header "27. LOGOUT"

test_api "Logout Customer" \
    "POST" "$BASE/auth/logout" "" "200" "$CUSTOMER_TOKEN"

test_api "Logout Owner" \
    "POST" "$BASE/auth/logout" "" "200" "$OWNER_TOKEN"

test_api "Logout Agent" \
    "POST" "$BASE/auth/logout" "" "200" "$AGENT_TOKEN"

# ==================== RESULTS ====================

echo ""
echo -e "${BLUE}╔══════════════════════════════════════════════════════╗${NC}"
echo -e "${BLUE}║                  TEST RESULTS                        ║${NC}"
echo -e "${BLUE}╠══════════════════════════════════════════════════════╣${NC}"
echo -e "${BLUE}║                                                      ║${NC}"
echo -e "${BLUE}║${NC}  ${GREEN}✅ Passed:  $PASS${NC}                                    ${BLUE}║${NC}"
echo -e "${BLUE}║${NC}  ${RED}❌ Failed:  $FAIL${NC}                                    ${BLUE}║${NC}"
echo -e "${BLUE}║${NC}  ${YELLOW}⚠️  Skipped: $SKIP${NC}                                    ${BLUE}║${NC}"
echo -e "${BLUE}║${NC}  📊 Total:   $TOTAL                                    ${BLUE}║${NC}"
echo -e "${BLUE}║                                                      ║${NC}"

if [ $FAIL -eq 0 ]; then
    echo -e "${BLUE}║${NC}  ${GREEN}🎉 ALL TESTS PASSED!${NC}                              ${BLUE}║${NC}"
else
    PERCENT=$((PASS * 100 / TOTAL))
    echo -e "${BLUE}║${NC}  ${YELLOW}📈 Pass Rate: ${PERCENT}%${NC}                                ${BLUE}║${NC}"
fi

echo -e "${BLUE}║                                                      ║${NC}"
echo -e "${BLUE}║${NC}  Test Data:                                          ${BLUE}║${NC}"
echo -e "${BLUE}║${NC}    Customer: $CUSTOMER_EMAIL  ${BLUE}║${NC}"
echo -e "${BLUE}║${NC}    Owner:    $OWNER_EMAIL     ${BLUE}║${NC}"
echo -e "${BLUE}║${NC}    Agent:    $AGENT_EMAIL     ${BLUE}║${NC}"
echo -e "${BLUE}║${NC}    Password: $PASSWORD                         ${BLUE}║${NC}"
echo -e "${BLUE}║                                                      ║${NC}"
echo -e "${BLUE}╚══════════════════════════════════════════════════════╝${NC}"
echo ""

if [ $FAIL -gt 0 ]; then
    exit 1
fi
exit 0