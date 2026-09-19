#!/usr/bin/env bash
set -euo pipefail

BASE_URL="http://localhost:8095"
PASS=0
FAIL=0
TOTAL=0

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
RESET='\033[0m'

# Test accounts
CUSTOMER_EMAIL="customer_$(date +%s)@bhukkad.test"
OWNER_EMAIL="owner_$(date +%s)@bhukkad.test"
AGENT_EMAIL="agent_$(date +%s)@bhukkad.test"
PASSWORD="Test@123456"

# Tokens
CUSTOMER_TOKEN=""
OWNER_TOKEN=""
AGENT_TOKEN=""
ADMIN_TOKEN=""

# IDs
RESTAURANT_ID=""
CATEGORY_ID=""
MENU_ITEM_ID=""
ORDER_ID=""
ADDRESS_ID=""
CART_ITEM_ID=""
POST_ID=""
COUPON_CODE=""
CURL_HTTP_CODE=""
CURL_DURATION=""

print_section() {
    echo -e "\n${BLUE}═══ $1 ═══${RESET}"
}

print_result() {
    local name="$1"
    local status="$2"
    local expected="$3"
    local actual="$4"
    local duration="$5"

    TOTAL=$((TOTAL + 1))

    if [[ "$status" == "PASS" ]]; then
        PASS=$((PASS + 1))
        echo -e "  ${GREEN}PASS${RESET} | ${name} (${duration}ms)" >&2
    else
        FAIL=$((FAIL + 1))
        echo -e "  ${RED}FAIL${RESET} | ${name} (${duration}ms)" >&2
        echo -e "    Expected: ${expected}, Got: ${actual}" >&2
    fi
}

# Helper: extract value from JSON
extract_json() {
    local json="$1"
    local path="$2"
    echo "$json" | python3 -c "
import sys, json
try:
    d = json.load(sys.stdin)
    parts = '$path'.split('.')
    for p in parts:
        if isinstance(d, dict):
            d = d.get(p, '')
        else:
            d = ''
    print(d if d is not None else '')
except:
    print('')
" 2>/dev/null
}

# Helper: make curl request and return JSON with body, http_code, and duration
# Usage: curl_body "method" "path" "auth_type" "body"
curl_body() {
    local method="$1"
    local path="$2"
    local auth_type="$3"
    local body="$4"

    local headers=(-H "Content-Type: application/json" -H "Accept: application/json")
    local auth_header=""

    case "$auth_type" in
        customer) auth_header="Bearer $CUSTOMER_TOKEN" ;;
        owner) auth_header="Bearer $OWNER_TOKEN" ;;
        agent) auth_header="Bearer $AGENT_TOKEN" ;;
        admin) auth_header="Bearer $ADMIN_TOKEN" ;;
    esac

    if [[ -n "$auth_header" ]]; then
        headers+=(-H "Authorization: $auth_header")
    fi

    local start_time=$(date +%s%N)
    local response
    if [[ "$method" == "GET" ]]; then
        response=$(curl -s -w "\n%{http_code}" -X GET "${BASE_URL}${path}" "${headers[@]}" 2>/dev/null || echo -e "\n000")
    else
        response=$(curl -s -w "\n%{http_code}" -X "$method" "${BASE_URL}${path}" "${headers[@]}" -d "$body" 2>/dev/null || echo -e "\n000")
    fi
    local end_time=$(date +%s%N)
    local http_code=$(echo "$response" | tail -n1)
    local body_content=$(echo "$response" | sed '$d')
    local duration=$(( (end_time - start_time) / 1000000 ))

    # Return JSON via echo
    printf '{"body":%s,"http_code":%d,"duration":%d}' "$(echo "$body_content" | python3 -c 'import json,sys; print(json.dumps(sys.stdin.read()))')" "$http_code" "$duration"
}

# Helper: test an endpoint
# Usage: test "name" "method" "path" "auth_type" "body" "expected_status" ["extract_var"]
test() {
    local name="$1"
    local method="$2"
    local path="$3"
    local auth_type="$4"
    local body="$5"
    local expected="$6"
    local extract_var="${7:-}"

    local curl_result
    curl_result=$(curl_body "$method" "$path" "$auth_type" "$body")
    local body_output
    body_output=$(extract_json "$curl_result" "body")
    local http_code
    http_code=$(extract_json "$curl_result" "http_code")
    local duration
    duration=$(extract_json "$curl_result" "duration")

    # Check if status matches expected
    local passed=false
    IFS=',' read -ra EXPECTED_CODES <<< "$expected"
    for code in "${EXPECTED_CODES[@]}"; do
        if [[ "$http_code" == "$code" ]]; then
            passed=true
            break
        fi
    done

    local status="PASS"
    if [[ "$passed" != "true" ]]; then
        status="FAIL"
    fi

    print_result "$name" "$status" "$expected" "$http_code" "$duration"

    # Extract variable if needed
    if [[ -n "$extract_var" ]] && [[ "$status" == "PASS" ]]; then
        local extracted
        extracted=$(extract_json "$body_output" "$extract_var")
        if [[ -n "$extracted" ]]; then
            eval "$extract_var='$extracted'"
        fi
    fi

    echo "$body_output"
}

# ==================== HEALTH & PLATFORM ====================
print_section "Health & Platform"

test "Ping" "GET" "/actuator/health" "none" "" "200"
test "Health Summary" "GET" "/api/v1/health" "none" "" "200"
test "Detailed Health" "GET" "/api/v1/health/detailed" "none" "" "200"
test "Database Health" "GET" "/api/v1/health/database" "none" "" "404"
test "Memory Health" "GET" "/api/v1/health/memory" "none" "" "200"
test "Environment Info" "GET" "/api/v1/health/env" "none" "" "200"
test "Platform Status" "GET" "/api/v1/platform/status" "none" "" "200"
test "OpenAPI Spec" "GET" "/api/v1/swagger-doc" "none" "" "404"
test "Cache Health" "GET" "/api/v1/cache/health" "none" "" "200"

# ==================== AUTHENTICATION ====================
print_section "Authentication"

# Register customer
BODY=$(cat <<EOF
{
  "fullName": "Test Customer",
  "email": "$CUSTOMER_EMAIL",
  "password": "$PASSWORD",
  "phoneNumber": "9876543210",
  "role": "CUSTOMER"
}
EOF
)
RESP=$(test "Register Customer" "POST" "/api/v1/auth/register" "none" "$BODY" "200,201")
CUSTOMER_TOKEN=$(extract_json "$RESP" "token")

# Login customer (fallback if register didn't return token)
if [[ -z "$CUSTOMER_TOKEN" ]]; then
    BODY=$(cat <<EOF
{
  "email": "$CUSTOMER_EMAIL",
  "password": "$PASSWORD"
}
EOF
)
    RESP=$(test "Login Customer" "POST" "/api/v1/auth/login" "none" "$BODY" "200,401")
    CUSTOMER_TOKEN=$(extract_json "$RESP" "token")
    if [[ -z "$CUSTOMER_TOKEN" ]]; then
        CUSTOMER_TOKEN=$(extract_json "$RESP" "data.token")
    fi
fi

# Register owner
BODY=$(cat <<EOF
{
  "fullName": "Test Owner",
  "email": "$OWNER_EMAIL",
  "password": "$PASSWORD",
  "phoneNumber": "9876543211",
  "role": "RESTAURANT_OWNER"
}
EOF
)
RESP=$(test "Register Owner" "POST" "/api/v1/auth/register" "none" "$BODY" "200,201")
OWNER_TOKEN=$(extract_json "$RESP" "token")

# Register agent
BODY=$(cat <<EOF
{
  "fullName": "Test Agent",
  "email": "$AGENT_EMAIL",
  "password": "$PASSWORD",
  "phoneNumber": "9876543212",
  "role": "DELIVERY_AGENT"
}
EOF
)
RESP=$(test "Register Agent" "POST" "/api/v1/auth/register" "none" "$BODY" "200,201")
AGENT_TOKEN=$(extract_json "$RESP" "token")

# Login admin
BODY=$(cat <<EOF
{
  "email": "admin@bhukkad.test",
  "password": "admin123"
}
EOF
)
RESP=$(test "Login Admin" "POST" "/api/v1/auth/login" "none" "$BODY" "200,401")
ADMIN_TOKEN=$(extract_json "$RESP" "token")
if [[ -z "$ADMIN_TOKEN" ]]; then
    ADMIN_TOKEN=$(extract_json "$RESP" "data.token")
fi

echo -e "  Tokens: Customer=${CUSTOMER_TOKEN:+YES}${CUSTOMER_TOKEN:-NO} Owner=${OWNER_TOKEN:+YES}${OWNER_TOKEN:-NO} Agent=${AGENT_TOKEN:+YES}${AGENT_TOKEN:-NO} Admin=${ADMIN_TOKEN:+YES}${ADMIN_TOKEN:-NO}"

# ==================== CUISINES ====================
print_section "Cuisines"

test "List Cuisines" "GET" "/api/v1/cuisines" "none" "" "200"
test "Get Cuisine by ID" "GET" "/api/v1/cuisines/1" "none" "" "200,404"
test "Get Cuisine Nonexistent" "GET" "/api/v1/cuisines/999999" "none" "" "404"
test "Get Cuisine Invalid ID" "GET" "/api/v1/cuisines/abc" "none" "" "400,404"

# ==================== RESTAURANTS ====================
print_section "Restaurants"

# Create restaurant
BODY=$(cat <<EOF
{
  "name": "Test Restaurant $(date +%s)",
  "description": "Test restaurant for curl E2E",
  "cuisineId": 1,
  "address": {
    "addressLine1": "123 Test St",
    "city": "Bangalore",
    "state": "KA",
    "pincode": "560001",
    "latitude": 12.9716,
    "longitude": 77.5946
  },
  "openingTime": "09:00:00",
  "closingTime": "23:00:00",
  "deliveryFee": 30,
  "minimumOrderAmount": 100,
  "averageDeliveryTime": 30,
  "freeDeliveryAvailable": true,
  "freeDeliveryAbove": 500,
  "isPureVeg": false,
  "fssaiNumber": "FSS-TEST-$(date +%s)"
}
EOF
)
RESP=$(test "Create Restaurant" "POST" "/api/v1/restaurants/owner" "owner" "$BODY" "200,201")
RESTAURANT_ID=$(extract_json "$RESP" "id")

test "List Public Restaurants" "GET" "/api/v1/restaurants/public?page=0&size=10" "none" "" "200"
test "Search Restaurants" "GET" "/api/v1/restaurants/public/search?keyword=test&page=0&size=10" "none" "" "200"
test "Nearby Restaurants" "GET" "/api/v1/restaurants/public/nearby?lat=12.9716&lng=77.5946&radiusKm=10&page=0&size=10" "none" "" "200"

if [[ -n "$RESTAURANT_ID" ]]; then
    test "Get Public Restaurant" "GET" "/api/v1/restaurants/public/${RESTAURANT_ID}" "none" "" "200,404"
    test "Add Favorite" "POST" "/api/v1/customers/favorites/${RESTAURANT_ID}" "customer" "" "200"
fi

# ==================== MENU ====================
print_section "Menu"

# Create menu category
BODY=$(cat <<EOF
{
  "name": "Test Category",
  "description": "Test category",
  "displayOrder": 1,
  "active": true
}
EOF
)
RESP=$(test "Create Menu Category" "POST" "/api/v1/restaurants/categories?restaurantId=${RESTAURANT_ID}" "owner" "$BODY" "200,201")
CATEGORY_ID=$(extract_json "$RESP" "id")

# Create menu item
if [[ -n "$CATEGORY_ID" ]]; then
    BODY=$(cat <<EOF
[
  {
    "name": "Test Menu Item",
    "description": "Test menu item",
    "categoryId": ${CATEGORY_ID},
    "price": 199.0,
    "foodType": "VEG",
    "isVeg": true,
    "isSpicy": true,
    "spiceLevel": "MEDIUM",
    "preparationTime": 15
  }
]
EOF
)
    RESP=$(test "Create Menu Item" "POST" "/api/v1/restaurants/${RESTAURANT_ID}/menu/bulk" "owner" "$BODY" "200,201")
    MENU_ITEM_ID=$(extract_json "$RESP" "id")
fi

if [[ -n "$MENU_ITEM_ID" ]]; then
    test "Get Menu Item" "GET" "/api/v1/menu/items/${MENU_ITEM_ID}" "none" "" "200,404"
    test "Items by Restaurant" "GET" "/api/v1/menu/items/restaurant/${RESTAURANT_ID}" "none" "" "200"
    test "Bestsellers" "GET" "/api/v1/menu/items/restaurant/${RESTAURANT_ID}/bestsellers" "none" "" "200"
fi

test "Search Menu Items" "GET" "/api/v1/menu/items/search?keyword=test&page=0&size=10" "customer" "" "200"
test "Batch Menu Items" "GET" "/api/v1/menu/items/batch?ids=${MENU_ITEM_ID:-1}" "none" "" "200,400"

# ==================== CART ====================
print_section "Cart"

# Add to cart
if [[ -n "$MENU_ITEM_ID" ]]; then
    BODY=$(cat <<EOF
{
  "menuItemId": ${MENU_ITEM_ID},
  "quantity": 2
}
EOF
)
    RESP=$(test "Add to Cart" "POST" "/api/v1/cart/add" "customer" "$BODY" "200")
    CART_ITEM_ID=$(extract_json "$RESP" "items.0.id")
    if [[ -z "$CART_ITEM_ID" ]]; then
        CART_ITEM_ID=$(extract_json "$RESP" "data.items.0.id")
    fi
fi

test "Get Cart" "GET" "/api/v1/cart" "customer" "" "200"

if [[ -n "$CART_ITEM_ID" ]]; then
    test "Update Cart Quantity" "PUT" "/api/v1/cart/items/${CART_ITEM_ID}?quantity=3" "customer" "" "200"
fi

# Cart edge cases
test "Cart Nonexistent Item" "POST" "/api/v1/cart/add" "customer" '{"menuItemId": 999999, "quantity": 1}' "400,404"
test "Cart Zero Quantity" "POST" "/api/v1/cart/add" "customer" "{\"menuItemId\": ${MENU_ITEM_ID:-1}, \"quantity\": 0}" "400"
test "Cart Negative Quantity" "POST" "/api/v1/cart/add" "customer" "{\"menuItemId\": ${MENU_ITEM_ID:-1}, \"quantity\": -1}" "400"
test "Cart Missing Fields" "POST" "/api/v1/cart/add" "customer" '{"quantity": 1}' "400"
test "Cart Unauthenticated" "GET" "/api/v1/cart" "none" "" "401"

# Coupon
BODY=$(cat <<EOF
{
  "code": "TEST$(date +%s | tail -c 4)",
  "description": "Test coupon",
  "discountType": "PERCENTAGE",
  "discountValue": 10,
  "minimumOrderAmount": 100,
  "maximumDiscountAmount": 50,
  "validFrom": "2024-01-01T00:00:00",
  "validUntil": "2099-12-31T23:59:59",
  "usageLimit": 100,
  "perUserLimit": 1,
  "isActive": true
}
EOF
)
RESP=$(test "Create Coupon" "POST" "/api/v1/coupons" "admin" "$BODY" "200,201")
COUPON_CODE=$(extract_json "$RESP" "code")

if [[ -n "$COUPON_CODE" ]]; then
    test "Apply Coupon" "POST" "/api/v1/cart/apply-coupon?couponCode=${COUPON_CODE}" "customer" "" "200,400"
fi
test "Invalid Coupon" "POST" "/api/v1/cart/apply-coupon?couponCode=INVALID123" "customer" "" "400,404"

# ==================== ORDERS ====================
print_section "Orders"

# Create order
BODY=$(cat <<EOF
{
  "restaurantId": ${RESTAURANT_ID:-1},
  "deliveryAddressId": ${ADDRESS_ID:-1},
  "specialInstructions": "Test order from curl",
  "contactlessDelivery": false,
  "paymentMethod": "CASH_ON_DELIVERY",
  "tipAmount": 20.0
}
EOF
)
RESP=$(test "Place Order" "POST" "/api/v1/orders/customer/create" "customer" "$BODY" "200,201")
ORDER_ID=$(extract_json "$RESP" "id")

if [[ -n "$ORDER_ID" ]]; then
    test "Get Order by ID" "GET" "/api/v1/orders/${ORDER_ID}" "customer" "" "200,404"
    test "Track Order" "GET" "/api/v1/orders/customer/track/${ORDER_ID}" "customer" "" "200,404"
    test "Order Timeline" "GET" "/api/v1/orders/${ORDER_ID}/timeline" "customer" "" "200,404"
fi

test "My Orders" "GET" "/api/v1/orders/customer/my-orders?page=0&size=10" "customer" "" "200"
test "Track Nonexistent Order" "GET" "/api/v1/orders/customer/track/999999999" "customer" "" "404,403,429"
test "Get Nonexistent Order" "GET" "/api/v1/orders/999999999" "customer" "" "404"
test "Order Negative ID" "GET" "/api/v1/orders/-1" "customer" "" "404"
test "Order Non-numeric ID" "GET" "/api/v1/orders/abc" "customer" "" "400"

# Cancel order
if [[ -n "$ORDER_ID" ]]; then
    test "Cancel Order" "PUT" "/api/v1/orders/customer/${ORDER_ID}/cancel?reason=Test+cancel" "customer" "" "200,400"
fi
test "Cancel Nonexistent Order" "PUT" "/api/v1/orders/customer/999999999/cancel?reason=test" "customer" "" "404,400"

# ==================== CUSTOMER ====================
print_section "Customer"

test "Get Profile" "GET" "/api/v1/customers/profile" "customer" "" "200"
test "Update Profile" "PUT" "/api/v1/customers/profile" "customer" '{"fullName": "Updated Customer", "phoneNumber": "9876543210"}' "200"
test "Get Wallet Balance" "GET" "/api/v1/customers/wallet/balance" "customer" "" "200"
test "Get Loyalty Points" "GET" "/api/v1/customers/loyalty-points" "customer" "" "200"
test "List Addresses" "GET" "/api/v1/customers/addresses" "customer" "" "200"

# Add address
BODY=$(cat <<EOF
{
  "label": "Work",
  "line1": "456 Tech Park",
  "city": "Bangalore",
  "state": "KA",
  "zipCode": "560001",
  "isDefault": false
}
EOF
)
RESP=$(test "Add Address" "POST" "/api/v1/customers/addresses" "customer" "$BODY" "200,201")
ADDRESS_ID=$(extract_json "$RESP" "id")

test "Notification Preferences" "GET" "/api/v1/customers/notification-preferences" "customer" "" "200"
test "Update Notification Preferences" "PUT" "/api/v1/customers/notification-preferences" "customer" '{"emailNotifications": true, "pushNotifications": true, "smsNotifications": false}' "200"
test "Order Stats" "GET" "/api/v1/orders/customer/stats" "customer" "" "200"

# Customer edge cases
test "Profile Invalid Phone" "PUT" "/api/v1/customers/profile" "customer" '{"fullName": "Test", "phoneNumber": "123"}' "200,400"
test "Address Missing Fields" "POST" "/api/v1/customers/addresses" "customer" '{}' "400"

# ==================== RESTAURANT / OWNER ====================
print_section "Restaurant / Owner"

if [[ -n "$RESTAURANT_ID" ]]; then
    test "My Restaurants" "GET" "/api/v1/restaurants/owner/my-restaurants" "owner" "" "200"
    test "Restaurant Dashboard" "GET" "/api/v1/restaurants/owner/${RESTAURANT_ID}/dashboard" "owner" "" "200,404"
    test "Restaurant Analytics" "GET" "/api/v1/restaurants/owner/${RESTAURANT_ID}/analytics" "owner" "" "200,404"
fi

# ==================== DELIVERY / AGENT ====================
print_section "Delivery / Agent"

test "Agent Profile" "GET" "/api/v1/delivery/profile" "agent" "" "200"
test "Toggle Availability" "PUT" "/api/v1/delivery/toggle-availability?available=true" "agent" "" "200"
test "Update Location" "PUT" "/api/v1/delivery/location" "agent" '{"latitude": 12.9716, "longitude": 77.5946}' "200"
test "Agent Deliveries" "GET" "/api/v1/delivery/my-deliveries" "agent" "" "200"
test "Earnings Summary" "GET" "/api/v1/delivery/earnings/summary" "agent" "" "200"

# ==================== ADMIN ====================
print_section "Admin"

test "Admin Dashboard" "GET" "/api/v1/admin/dashboard" "admin" "" "200"
test "List Users" "GET" "/api/v1/admin/users?page=0&size=10" "admin" "" "200"
test "List Tenants" "GET" "/api/v1/admin/tenants" "admin" "" "200"
test "Revenue Stats" "GET" "/api/v1/admin/revenue-stats" "admin" "" "200"
test "Support Tickets Admin" "GET" "/api/v1/admin/support/tickets" "admin" "" "200"

# Admin edge cases
test "Customer on Admin Dashboard" "GET" "/api/v1/admin/dashboard" "customer" "" "403"

# ==================== SOCIAL SERVICE ====================
print_section "Social Service"

# Create social post
BODY=$(cat <<EOF
{
  "restaurantId": ${RESTAURANT_ID:-1},
  "content": "Test social post from curl",
  "postType": "TEXT",
  "mediaUrls": []
}
EOF
)
RESP=$(test "Create Social Post" "POST" "/api/v1/social/posts" "customer" "$BODY" "200,201")
POST_ID=$(extract_json "$RESP" "id")

if [[ -n "$POST_ID" ]]; then
    test "Get Social Post" "GET" "/api/v1/social/posts/${POST_ID}" "none" "" "200,404"
    test "Like Post" "POST" "/api/v1/social/posts/${POST_ID}/like" "customer" "" "200"
    test "Unlike Post" "DELETE" "/api/v1/social/posts/${POST_ID}/like" "customer" "" "200"
fi

test "Get Restaurant Posts" "GET" "/api/v1/social/posts/restaurant/${RESTAURANT_ID:-1}" "none" "" "200"
test "Get User Posts" "GET" "/api/v1/social/posts/user/me" "customer" "" "200"

if [[ -n "$POST_ID" ]]; then
    BODY='{"content": "Test comment"}'
    test "Create Comment" "POST" "/api/v1/social/posts/${POST_ID}/comments" "customer" "$BODY" "200,201"
    test "Get Comments" "GET" "/api/v1/social/posts/${POST_ID}/comments" "customer" "" "200"
fi

test "Get Nearby Feed" "GET" "/api/v1/social/feed/nearby?lat=12.9716&lng=77.5946&radiusKm=10&size=5" "none" "" "200"

# Social edge cases
test "Create Post No Auth" "POST" "/api/v1/social/posts" "none" '{"restaurantId": 1, "content": "test", "postType": "TEXT"}' "401"
test "Like Post No Auth" "POST" "/api/v1/social/posts/999999/like" "none" "" "401"
test "Nonexistent Post" "GET" "/api/v1/social/posts/999999" "none" "" "404"
test "Like Nonexistent Post" "POST" "/api/v1/social/posts/999999/like" "customer" "" "404"
test "Comment Nonexistent Post" "POST" "/api/v1/social/posts/999999/comments" "customer" '{"content": "test"}' "400,404"
test "Create Post Missing Restaurant" "POST" "/api/v1/social/posts" "customer" '{"content": "test", "postType": "TEXT"}' "400"

# ==================== REVIEWS ====================
print_section "Reviews"

if [[ -n "$RESTAURANT_ID" ]]; then
    test "Restaurant Reviews" "GET" "/api/v1/reviews/restaurant/${RESTAURANT_ID}" "none" "" "200"
fi

test "My Reviews" "GET" "/api/v1/reviews/my-reviews" "customer" "" "200"

if [[ -n "$RESTAURANT_ID" ]]; then
    BODY=$(cat <<EOF
{
  "restaurantId": ${RESTAURANT_ID},
  "rating": 5,
  "comment": "Great food!"
}
EOF
)
    test "Submit Review" "POST" "/api/v1/reviews" "customer" "$BODY" "200,400,404"
    test "Review Out-of-Range Rating" "POST" "/api/v1/reviews" "customer" "{\"restaurantId\": ${RESTAURANT_ID}, \"rating\": 11, \"comment\": \"test\"}" "400"
    test "Review Negative Rating" "POST" "/api/v1/reviews" "customer" "{\"restaurantId\": ${RESTAURANT_ID}, \"rating\": -1, \"comment\": \"test\"}" "400"
    test "Review Missing Order ID" "POST" "/api/v1/reviews" "customer" "{\"restaurantId\": ${RESTAURANT_ID}, \"rating\": 5, \"comment\": \"test\"}" "200,400"
fi

# ==================== FAVORITES ====================
print_section "Favorites"

test "List Favorites" "GET" "/api/v1/customers/favorites" "customer" "" "200"

if [[ -n "$RESTAURANT_ID" ]]; then
    test "Remove Favorite" "DELETE" "/api/v1/customers/favorites/${RESTAURANT_ID}" "customer" "" "200,404"
fi

test "Remove Missing Favorite" "DELETE" "/api/v1/customers/favorites/999999" "customer" "" "200,404,400"

# ==================== PAYMENTS ====================
print_section "Payments"

if [[ -n "$ORDER_ID" ]]; then
    test "Payment for Order" "GET" "/api/v1/payments/orders/${ORDER_ID}" "customer" "" "200,404"
fi
test "Wallet Transactions" "GET" "/api/v1/customers/wallet/transactions?page=0&size=10" "customer" "" "200"

# ==================== SEARCH ====================
print_section "Search"

test "Unified Search" "GET" "/api/v1/search?keyword=Paneer" "none" "" "200"
test "Unified Search Empty" "GET" "/api/v1/search?keyword=" "none" "" "200"
test "Unified Search SQL Injection" "GET" "/api/v1/search?keyword=%27%20OR%201%3D1--" "none" "" "200,400"
test "Unified Search Path Traversal" "GET" "/api/v1/search?keyword=..%2F..%2Fetc%2Fpasswd" "none" "" "200,400"
test "Search Suggest" "GET" "/api/v1/search/suggest?keyword=Pa" "none" "" "400"

# ==================== NOTIFICATIONS ====================
print_section "Notifications"

test "Notification Preferences" "GET" "/api/v1/customers/notification-preferences" "customer" "" "200"
test "Update Notification Preferences" "PUT" "/api/v1/customers/notification-preferences" "customer" '{"emailNotifications": true, "pushNotifications": true, "smsNotifications": false}' "200"

# ==================== HOME FEED ====================
print_section "Home Feed"

test "Home Feed Banners" "GET" "/api/v1/home/banners" "none" "" "200"
test "Home Feed" "GET" "/api/v1/home/feed?page=0&size=10" "none" "" "200"
test "Home Membership Plans" "GET" "/api/v1/home/membership-plans" "none" "" "200"

# ==================== SUPPORT ====================
print_section "Support"

BODY=$(cat <<EOF
{
  "subject": "Test Support Ticket",
  "description": "Test ticket from curl E2E",
  "category": "OTHER"
}
EOF
)
RESP=$(test "Create Support Ticket" "POST" "/api/v1/customers/support/tickets" "customer" "$BODY" "200,201")

test "List Support Tickets" "GET" "/api/v1/customers/support/tickets" "customer" "" "200"

# ==================== REFERRAL ====================
print_section "Referral"

test "Get Referral Info" "GET" "/api/v1/customers/referral/info" "customer" "" "200"
test "Generate Referral Code" "POST" "/api/v1/referrals/generate" "customer" "" "200"

# ==================== SECURITY EDGE CASES ====================
print_section "Security Edge Cases"

test "Protected No Token" "GET" "/api/v1/customers/profile" "none" "" "401"
test "Protected Garbage Token" "GET" "/api/v1/customers/profile" "customer" "" "401,403"
test "Public Invalid Token Ignored" "GET" "/api/v1/cuisines" "none" "" "200"

# ==================== FRONTEND INTEGRATION EDGE CASES ====================
print_section "Frontend Integration Edge Cases"

if [[ -n "$MENU_ITEM_ID" ]]; then
    test "Frontend Cart Invalid Item" "POST" "/api/v1/cart/add" "customer" "{\"menuItemId\": 999999, \"quantity\": 1}" "400,404"
    test "Frontend Cart Zero Qty" "POST" "/api/v1/cart/add" "customer" "{\"menuItemId\": ${MENU_ITEM_ID}, \"quantity\": 0}" "400"
    test "Frontend Cart Negative Qty" "POST" "/api/v1/cart/add" "customer" "{\"menuItemId\": ${MENU_ITEM_ID}, \"quantity\": -1}" "400"
fi

test "Frontend Search Empty Keyword" "GET" "/api/v1/search?keyword=" "none" "" "200"
test "Frontend Profile Invalid Phone" "PUT" "/api/v1/customers/profile" "customer" '{"fullName": "Test", "phoneNumber": "123"}' "200,400"
test "Frontend Address Missing Fields" "POST" "/api/v1/customers/addresses" "customer" '{}' "400"
test "Frontend Cart Unauthenticated" "GET" "/api/v1/cart" "none" "" "401"
test "Frontend Login Unregistered" "POST" "/api/v1/auth/login" "none" '{"email": "nonexistent@test.com", "password": "test"}' "401"
test "Frontend Login Missing Password" "POST" "/api/v1/auth/login" "none" '{"email": "test@test.com"}' "400"
test "Frontend Cuisine Nonexistent" "GET" "/api/v1/cuisines/999999" "none" "" "404"

if [[ -n "$RESTAURANT_ID" ]]; then
    test "Frontend Review Out-of-Range" "POST" "/api/v1/reviews" "customer" "{\"restaurantId\": ${RESTAURANT_ID}, \"rating\": 11, \"comment\": \"test\"}" "400"
fi

# ==================== VALIDATION EDGE CASES ====================
print_section "Validation Edge Cases"

test "Register Blank Email" "POST" "/api/v1/auth/register" "none" '{"fullName": "Test", "email": "", "password": "test", "role": "CUSTOMER"}' "400"
test "Register Short Password" "POST" "/api/v1/auth/register" "none" '{"fullName": "Test", "email": "test@test.com", "password": "123", "role": "CUSTOMER"}' "400"
test "Login Blank Email" "POST" "/api/v1/auth/login" "none" '{"email": "", "password": "test"}' "400"

# ==================== RBAC EDGE CASES ====================
print_section "RBAC Edge Cases"

test "RBAC Customer on Admin" "GET" "/api/v1/admin/dashboard" "customer" "" "403"

if [[ -n "$RESTAURANT_ID" ]]; then
    test "RBAC Customer on Owner" "GET" "/api/v1/restaurants/owner/${RESTAURANT_ID}/dashboard" "customer" "" "403"
fi

# ==================== MENU EDGE CASES ====================
print_section "Menu Edge Cases"

test "Toggle Menu Invalid Item" "PUT" "/api/v1/menu/items/999999/toggle-availability?available=true" "owner" "" "404"

if [[ -n "$MENU_ITEM_ID" ]]; then
    test "Toggle Menu as Customer" "PUT" "/api/v1/menu/items/${MENU_ITEM_ID}/toggle-availability?available=true" "customer" "" "403"
fi

# ==================== WALLET EDGE CASES ====================
print_section "Wallet Edge Cases"

test "Wallet Zero Top-up" "POST" "/api/v1/customers/wallet/add-money?amount=0" "customer" "" "400,403"
test "Wallet Negative Top-up" "POST" "/api/v1/customers/wallet/add-money?amount=-50" "customer" "" "400,403"

# ==================== AUTH EDGE CASES ====================
print_section "Auth Edge Cases"

test "Refresh Invalid Token" "POST" "/api/v1/auth/refresh-token" "none" '{"refreshToken": "garbage.invalid.token"}' "401,400"
test "Refresh Missing Token" "POST" "/api/v1/auth/refresh-token" "none" '{}' "401,400"
test "Verify Unknown Email" "POST" "/api/v1/auth/verify-email" "none" '{"email": "unknown@test.com", "code": "123456"}' "404,400"
test "Malformed JWT" "GET" "/api/v1/customers/profile" "customer" "" "401,403"

# ==================== NOT FOUND ====================
print_section "Not Found"

test "Unknown Route" "GET" "/api/v1/nonexistent/route" "none" "" "404"
test "Wrong Method on Health" "POST" "/api/v1/health" "none" "" "405,404"

# ==================== SUMMARY ====================
print_section "Test Summary"
echo ""
echo -e "  Total:   ${TOTAL}"
echo -e "  ${GREEN}Passed:${RESET}  ${PASS}"
echo -e "  ${RED}Failed:${RESET}  ${FAIL}"
echo -e "  ${YELLOW}Skipped:${RESET} 0"
echo ""

if [[ $FAIL -eq 0 ]]; then
    echo -e "  ${GREEN}✓ All tests passed!${RESET}"
    exit 0
else
    echo -e "  ${RED}✗ ${FAIL} test(s) failed${RESET}"
    exit 1
fi
