#!/usr/bin/env python3
"""Generate Postman collection + environments for Bhukkad API."""
import json
import uuid
from copy import deepcopy
from pathlib import Path

OUT = Path(__file__).parent

COLLECTION_PREREQUEST = """\
// Skip auth for health/actuator probes
const path = pm.request.url.getPath();
if (path.includes('/health') || path.startsWith('/actuator/health')) {
    pm.request.headers.remove('Authorization');
} else {
    const noAuth = pm.variables.get('noAuth');
    if (noAuth !== 'true' && !pm.request.headers.has('Authorization')) {
        const token = pm.environment.get('accessToken');
        if (token) {
            pm.request.headers.upsert({ key: 'Authorization', value: 'Bearer ' + token });
        }
    }
}
pm.variables.set('idempotencyKey', pm.variables.replaceIn('{{$guid}}'));
"""

COLLECTION_TEST = """\
pm.test('Response time < 10s', () => pm.expect(pm.response.responseTime).to.be.below(10000));
if (pm.response.code !== 204 && pm.response.text()) {
    pm.test('JSON content-type', () => {
        const ct = pm.response.headers.get('Content-Type') || '';
        pm.expect(ct).to.include('json');
    });
}
"""

AUTH_SAVE = """\
pm.test('Status 200', () => pm.response.to.have.status(200));
const json = pm.response.json();
pm.test('success=true', () => pm.expect(json.success).to.eql(true));
if (json.data && json.data.token) {
    pm.environment.set('accessToken', json.data.token);
    pm.environment.set('refreshToken', json.data.refreshToken);
    pm.environment.set('userId', String(json.data.userId));
    pm.environment.set('userEmail', json.data.email);
    pm.environment.set('userRole', json.data.role);
    console.log('Saved token for', json.data.role);
}
"""

API_TESTS = """\
pm.test('Status 2xx', () => pm.expect(pm.response.code).to.be.oneOf([200, 201, 202]));
const json = pm.response.json();
pm.test('success envelope', () => {
    pm.expect(json.success).to.eql(true);
    pm.expect(json).to.have.property('data');
});
"""

SAVE_ID = """\
const json = pm.response.json();
if (json.data) {
    if (json.data.id) pm.environment.set('{{var}}', String(json.data.id));
"""


def uid():
    return str(uuid.uuid4())


def req(name, method, path, *, folder_auth=None, body=None, query=None,
        headers=None, auth=False, no_auth=False, tests=None, prerequest=None,
        description="", role=None):
    url_raw = "{{baseUrl}}" + path
    if query:
        qs = "&".join(f"{k}={v}" for k, v in query)
        url_raw += ("&" if "?" in path else "?") + qs
    item = {
        "name": name,
        "request": {
            "method": method,
            "header": headers or [],
            "url": url_raw,
            "description": description,
        },
        "response": [],
    }
    if body is not None:
        item["request"]["header"].append({"key": "Content-Type", "value": "application/json"})
        item["request"]["body"] = {"mode": "raw", "raw": json.dumps(body, indent=2)}
    if auth:
        item["request"]["auth"] = {"type": "bearer", "bearer": [{"key": "token", "value": "{{accessToken}}", "type": "string"}]}
    events = []
    if prerequest:
        events.append({"listen": "prerequest", "script": {"type": "text/javascript", "exec": prerequest.strip().split("\n")}})
    test_script = (tests or API_TESTS).strip().split("\n")
    if no_auth:
        pr = ["pm.variables.set('noAuth', 'true');"]
        events.append({"listen": "prerequest", "script": {"type": "text/javascript", "exec": pr}})
    events.append({"listen": "test", "script": {"type": "text/javascript", "exec": test_script}})
    item["event"] = events
    return item


def folder(name, items, description=""):
    return {"name": name, "description": description, "item": items}


def build_collection():
    items = []

    # --- Health ---
    health = [
        req("Ping", "GET", "/api/v1/health/ping", no_auth=True,
            tests="pm.test('200',()=>pm.response.to.have.status(200));\npm.test('pong',()=>pm.expect(pm.response.json().status).to.eql('pong'));"),
        req("Health", "GET", "/api/v1/health", no_auth=True),
        req("Health Detailed", "GET", "/api/v1/health/detailed", no_auth=True),
        req("Health DB", "GET", "/api/v1/health/db", no_auth=True),
        req("Health Memory", "GET", "/api/v1/health/memory", no_auth=True),
        req("Actuator Health", "GET", "/actuator/health", no_auth=True),
    ]
    items.append(folder("01 - Health", health))

    # --- Auth ---
    reg_customer = {
        "fullName": "Postman Customer",
        "email": "{{customerEmail}}",
        "password": "{{password}}",
        "phoneNumber": "9876543210",
        "role": "CUSTOMER"
    }
    reg_owner = {**reg_customer, "fullName": "Postman Owner", "email": "{{ownerEmail}}", "role": "RESTAURANT_OWNER"}
    reg_agent = {**reg_customer, "fullName": "Postman Agent", "email": "{{agentEmail}}", "role": "DELIVERY_AGENT"}
    reg_admin = {**reg_customer, "fullName": "Postman Admin", "email": "{{adminEmail}}", "role": "ADMIN"}

    auth_items = [
        req("Register Customer", "POST", "/api/v1/auth/register", body=reg_customer, no_auth=True, tests=AUTH_SAVE),
        req("Register Owner", "POST", "/api/v1/auth/register", body=reg_owner, no_auth=True, tests=AUTH_SAVE),
        req("Register Agent", "POST", "/api/v1/auth/register", body=reg_agent, no_auth=True, tests=AUTH_SAVE),
        req("Register Admin", "POST", "/api/v1/auth/register", body=reg_admin, no_auth=True, tests=AUTH_SAVE),
        req("Login Customer", "POST", "/api/v1/auth/login", body={"email": "{{customerEmail}}", "password": "{{password}}"}, no_auth=True, tests=AUTH_SAVE),
        req("Login Owner", "POST", "/api/v1/auth/login", body={"email": "{{ownerEmail}}", "password": "{{password}}"}, no_auth=True, tests=AUTH_SAVE),
        req("Login Agent", "POST", "/api/v1/auth/login", body={"email": "{{agentEmail}}", "password": "{{password}}"}, no_auth=True, tests=AUTH_SAVE),
        req("Login Admin", "POST", "/api/v1/auth/login", body={"email": "{{adminEmail}}", "password": "{{password}}"}, no_auth=True, tests=AUTH_SAVE),
        req("Refresh Token", "POST", "/api/v1/auth/refresh-token", auth=True, tests=AUTH_SAVE),
        req("Verify Email", "POST", "/api/v1/auth/verify-email?email={{customerEmail}}", auth=True),
        req("Forgot Password", "POST", "/api/v1/auth/forgot-password?email={{customerEmail}}", no_auth=True,
            tests="pm.test('200',()=>pm.response.to.have.status(200));"),
        req("Reset Password", "POST", "/api/v1/auth/reset-password?token={{resetToken}}&newPassword={{password}}", no_auth=True),
        req("Change Password", "POST", "/api/v1/auth/change-password?oldPassword={{password}}&newPassword=secret456", auth=True),
        req("Logout", "POST", "/api/v1/auth/logout", auth=True,
            tests="pm.test('200',()=>pm.response.to.have.status(200));"),
    ]
    items.append(folder("02 - Auth", auth_items, "Run Register/Login first. Token auto-saved to environment."))

    # --- Public ---
    public = [
        req("List Restaurants", "GET", "/api/v1/restaurants/public", no_auth=True),
        req("Restaurant By ID", "GET", "/api/v1/restaurants/public/{{restaurantId}}", no_auth=True),
        req("Search Restaurants", "GET", "/api/v1/restaurants/public/search?keyword=pizza", no_auth=True),
        req("Nearby Restaurants", "GET", "/api/v1/restaurants/public/nearby?latitude=12.97&longitude=77.59&radiusKm=5", no_auth=True),
        req("Filter Restaurants", "GET", "/api/v1/restaurants/public/filter?isPureVeg=true", no_auth=True),
        req("List Cuisines", "GET", "/api/v1/cuisines", no_auth=True),
        req("Cuisine By ID", "GET", "/api/v1/cuisines/{{cuisineId}}", no_auth=True),
        req("Menu Item By ID", "GET", "/api/v1/menu/items/{{menuItemId}}", no_auth=True),
        req("Menu By Category", "GET", "/api/v1/menu/items/category/{{categoryId}}", no_auth=True),
        req("Menu By Restaurant", "GET", "/api/v1/menu/items/restaurant/{{restaurantId}}", no_auth=True),
        req("Bestsellers", "GET", "/api/v1/menu/items/restaurant/{{restaurantId}}/bestsellers", no_auth=True),
        req("Recommended", "GET", "/api/v1/menu/items/restaurant/{{restaurantId}}/recommended", no_auth=True),
        req("Search Menu", "GET", "/api/v1/menu/items/search?keyword=biryani&restaurantId={{restaurantId}}", no_auth=True),
        req("Categories By Restaurant", "GET", "/api/v1/menu/categories/restaurant/{{restaurantId}}", no_auth=True),
        req("Active Coupons", "GET", "/api/v1/coupons/active", no_auth=True),
        req("Restaurant Reviews", "GET", "/api/v1/reviews/restaurant/{{restaurantId}}", no_auth=True),
    ]
    items.append(folder("03 - Public", public))

    # --- Customer ---
    address_body = {
        "addressLine1": "123 MG Road",
        "city": "Bangalore",
        "state": "Karnataka",
        "pincode": "560001",
        "latitude": 12.9716,
        "longitude": 77.5946,
        "type": "HOME",
        "isDefault": True
    }
    customer = [
        req("Get Profile", "GET", "/api/v1/customers/profile", auth=True),
        req("Update Profile", "PUT", "/api/v1/customers/profile?fullName=Updated Name", auth=True),
        req("Add Address", "POST", "/api/v1/customers/addresses", auth=True, body=address_body,
            tests=API_TESTS + "\nif(pm.response.json().data&&pm.response.json().data.id)pm.environment.set('addressId',String(pm.response.json().data.id));"),
        req("List Addresses", "GET", "/api/v1/customers/addresses", auth=True),
        req("Set Default Address", "PUT", "/api/v1/customers/addresses/{{addressId}}/set-default", auth=True),
        req("Wallet Balance", "GET", "/api/v1/customers/wallet/balance", auth=True),
        req("Loyalty Points", "GET", "/api/v1/customers/loyalty-points", auth=True),
        req("Wallet Top-Up Initiate", "POST", "/api/v1/customers/wallet/top-up?amount=100", auth=True,
            headers=[{"key": "Idempotency-Key", "value": "{{idempotencyKey}}"}]),
        req("Register Device Token", "POST", "/api/v1/customers/device-tokens", auth=True,
            body={"token": "fcm-device-token-sample", "platform": "ANDROID"}),
        req("Unregister Device Token", "DELETE", "/api/v1/customers/device-tokens?token=fcm-device-token-sample", auth=True),
        req("Update Address", "PUT", "/api/v1/customers/addresses/{{addressId}}", auth=True, body=address_body),
        req("Delete Address", "DELETE", "/api/v1/customers/addresses/{{addressId}}", auth=True),
        req("Delete Account", "DELETE", "/api/v1/customers/account", auth=True),
        req("Get Cart", "GET", "/api/v1/cart", auth=True),
        req("Add To Cart", "POST", "/api/v1/cart/add", auth=True,
            body={"menuItemId": "{{menuItemId}}", "quantity": 2, "specialInstructions": "less spicy"},
            tests=API_TESTS + "\nconst d=pm.response.json().data;if(d&&d.restaurantCarts&&d.restaurantCarts[0]&&d.restaurantCarts[0].items[0])pm.environment.set('cartItemId',String(d.restaurantCarts[0].items[0].id));"),
        req("Update Cart Item Qty", "PUT", "/api/v1/cart/items/{{cartItemId}}?quantity=3", auth=True),
        req("Remove Cart Item", "DELETE", "/api/v1/cart/items/{{cartItemId}}", auth=True),
        req("Clear Cart", "DELETE", "/api/v1/cart/clear", auth=True),
        req("Apply Coupon", "POST", "/api/v1/cart/apply-coupon?couponCode={{couponCode}}", auth=True),
        req("Create Order", "POST", "/api/v1/orders/customer/create", auth=True,
            headers=[{"key": "Idempotency-Key", "value": "{{idempotencyKey}}"}],
            body={"restaurantId": "{{restaurantId}}", "deliveryAddressId": "{{addressId}}",
                  "paymentMethod": "CASH_ON_DELIVERY", "specialInstructions": "Ring doorbell"},
            tests=API_TESTS + "\nif(pm.response.json().data&&pm.response.json().data.id)pm.environment.set('orderId',String(pm.response.json().data.id));"),
        req("Create Order (Split Pay)", "POST", "/api/v1/orders/customer/create", auth=True,
            body={"restaurantId": "{{restaurantId}}", "deliveryAddressId": "{{addressId}}",
                  "paymentMethod": "UPI", "useWallet": True, "walletAmountToUse": 50}),
        req("Create Order Async", "POST", "/api/v1/orders/customer/create?async=true", auth=True,
            headers=[{"key": "Idempotency-Key", "value": "{{idempotencyKey}}"}],
            body={"restaurantId": "{{restaurantId}}", "deliveryAddressId": "{{addressId}}", "paymentMethod": "UPI"},
            tests="pm.test('202 Accepted',()=>pm.expect(pm.response.code).to.eql(202));\nif(pm.response.json().data&&pm.response.json().data.jobId)pm.environment.set('jobId',pm.response.json().data.jobId);"),
        req("Get Create Job", "GET", "/api/v1/orders/customer/create/jobs/{{jobId}}", auth=True),
        req("My Orders", "GET", "/api/v1/orders/customer/my-orders?page=0&size=20", auth=True),
        req("My Orders Cursor", "GET", "/api/v1/orders/customer/my-orders/cursor?size=20", auth=True),
        req("Order By ID", "GET", "/api/v1/orders/customer/{{orderId}}", auth=True),
        req("Track Order", "GET", "/api/v1/orders/customer/track/{{orderId}}", auth=True),
        req("Reorder", "POST", "/api/v1/orders/customer/{{orderId}}/reorder", auth=True),
        req("Cancel Order", "PUT", "/api/v1/orders/customer/{{orderId}}/cancel?reason=Changed mind", auth=True),
        req("Payment For Order", "GET", "/api/v1/payments/orders/{{orderId}}", auth=True),
        req("Validate Coupon", "GET", "/api/v1/coupons/validate?code={{couponCode}}&subtotal=500&restaurantId={{restaurantId}}", auth=True),
        req("Create Review", "POST", "/api/v1/reviews", auth=True,
            body={"orderId": "{{orderId}}", "rating": 5, "comment": "Great food!"}),
        req("My Reviews", "GET", "/api/v1/reviews/my-reviews", auth=True),
        req("Review By Order", "GET", "/api/v1/reviews/order/{{orderId}}", auth=True),
        req("Delete Review", "DELETE", "/api/v1/reviews/{{reviewId}}", auth=True),
        req("Clear Restaurant Cart", "DELETE", "/api/v1/cart/restaurant/{{restaurantId}}", auth=True),
    ]
    items.append(folder("04 - Customer", customer))

    # --- Restaurant Owner ---
    restaurant_body = {
        "name": "Postman Kitchen",
        "description": "Test restaurant",
        "address": address_body,
        "openingTime": "09:00:00",
        "closingTime": "23:00:00",
        "minimumOrderAmount": 100,
        "deliveryFee": 40,
        "isPureVeg": False
    }
    owner = [
        req("Create Restaurant", "POST", "/api/v1/restaurants/owner", auth=True, body=restaurant_body,
            tests=API_TESTS + "\nif(pm.response.json().data&&pm.response.json().data.id)pm.environment.set('restaurantId',String(pm.response.json().data.id));"),
        req("My Restaurants", "GET", "/api/v1/restaurants/owner/my-restaurants", auth=True),
        req("Update Restaurant", "PUT", "/api/v1/restaurants/owner/{{restaurantId}}", auth=True, body=restaurant_body),
        req("Delete Restaurant", "DELETE", "/api/v1/restaurants/owner/{{restaurantId}}", auth=True),
        req("Toggle Open", "PUT", "/api/v1/restaurants/owner/{{restaurantId}}/toggle-status?isOpen=true", auth=True),
        req("Restaurant Analytics", "GET", "/api/v1/restaurants/owner/{{restaurantId}}/analytics?days=30", auth=True),
        req("Create Category", "POST", "/api/v1/menu/categories?restaurantId={{restaurantId}}", auth=True,
            body={"name": "Main Course", "description": "Mains", "displayOrder": 1},
            tests=API_TESTS + "\nif(pm.response.json().data&&pm.response.json().data.id)pm.environment.set('categoryId',String(pm.response.json().data.id));"),
        req("Create Menu Item", "POST", "/api/v1/menu/items", auth=True,
            body={"name": "Chicken Biryani", "categoryId": "{{categoryId}}", "price": 250,
                  "foodType": "NON_VEG", "isVeg": False, "description": "Hyderabadi style"},
            tests=API_TESTS + "\nif(pm.response.json().data&&pm.response.json().data.id)pm.environment.set('menuItemId',String(pm.response.json().data.id));"),
        req("Image Upload URL", "POST", "/api/v1/menu/items/{{menuItemId}}/image/upload-url", auth=True,
            body={"contentType": "image/jpeg", "fileName": "biryani.jpg"}),
        req("Update Menu Item", "PUT", "/api/v1/menu/items/{{menuItemId}}", auth=True,
            body={"name": "Chicken Biryani", "categoryId": "{{categoryId}}", "price": 275,
                  "foodType": "NON_VEG", "isVeg": False}),
        req("Delete Menu Item", "DELETE", "/api/v1/menu/items/{{menuItemId}}", auth=True),
        req("Update Category", "PUT", "/api/v1/menu/categories/{{categoryId}}", auth=True,
            body={"name": "Main Course", "description": "Mains", "displayOrder": 1}),
        req("Delete Category", "DELETE", "/api/v1/menu/categories/{{categoryId}}", auth=True),
        req("Toggle Item Availability", "PUT", "/api/v1/menu/items/{{menuItemId}}/toggle-availability?available=true", auth=True),
        req("Restaurant Orders", "GET", "/api/v1/orders/restaurant/{{restaurantId}}?page=0&size=20", auth=True),
        req("Pending Orders", "GET", "/api/v1/orders/restaurant/{{restaurantId}}/pending", auth=True),
        req("Kitchen Queue", "GET", "/api/v1/orders/restaurant/{{restaurantId}}/kitchen-queue", auth=True),
        req("Accept Order", "PUT", "/api/v1/orders/restaurant/{{orderId}}/accept", auth=True),
        req("Mark Ready", "PUT", "/api/v1/orders/restaurant/{{orderId}}/ready", auth=True),
        req("Assign Delivery Agent", "PUT", "/api/v1/orders/restaurant/{{orderId}}/assign-delivery?agentId={{agentUserId}}", auth=True),
    ]
    items.append(folder("05 - Restaurant Owner", owner))

    # --- Delivery Agent ---
    agent = [
        req("Get Profile", "GET", "/api/v1/delivery/profile", auth=True),
        req("Toggle Available", "PUT", "/api/v1/delivery/toggle-availability?available=true", auth=True),
        req("Update Location", "PUT", "/api/v1/delivery/update-location?latitude=12.97&longitude=77.59", auth=True),
        req("Available Orders", "GET", "/api/v1/delivery/available-orders", auth=True),
        req("Accept Delivery", "POST", "/api/v1/delivery/{{orderId}}/accept", auth=True),
        req("Active Deliveries", "GET", "/api/v1/delivery/active-deliveries", auth=True),
        req("My Deliveries", "GET", "/api/v1/orders/delivery/my-deliveries?page=0&size=20", auth=True),
        req("My Deliveries Cursor", "GET", "/api/v1/orders/delivery/my-deliveries/cursor?size=20", auth=True),
        req("Order By Number", "GET", "/api/v1/orders/number/{{orderNumber}}", auth=True),
        req("Mark Picked Up", "PUT", "/api/v1/orders/delivery/{{orderId}}/picked-up", auth=True),
        req("Mark Delivered", "PUT", "/api/v1/orders/delivery/{{orderId}}/delivered", auth=True),
        req("Earnings Summary", "GET", "/api/v1/delivery/earnings/summary", auth=True),
        req("Earnings History", "GET", "/api/v1/delivery/earnings?page=0&size=20", auth=True),
        req("Delivery History", "GET", "/api/v1/delivery/delivery-history", auth=True),
        req("Reject Delivery", "POST", "/api/v1/delivery/{{orderId}}/reject", auth=True),
    ]
    items.append(folder("06 - Delivery Agent", agent))

    # --- Admin ---
    admin = [
        req("Dashboard", "GET", "/api/v1/admin/dashboard", auth=True),
        req("All Users", "GET", "/api/v1/admin/users?page=0&size=20", auth=True),
        req("All Orders", "GET", "/api/v1/admin/orders?page=0&size=20", auth=True),
        req("All Restaurants", "GET", "/api/v1/admin/restaurants?page=0&size=20", auth=True),
        req("Revenue", "GET", "/api/v1/admin/revenue?days=7", auth=True),
        req("Analytics", "GET", "/api/v1/admin/analytics", auth=True),
        req("Activate User", "PUT", "/api/v1/admin/users/{{userId}}/activate", auth=True),
        req("Deactivate User", "PUT", "/api/v1/admin/users/{{userId}}/deactivate", auth=True),
        req("Verify Owner", "PUT", "/api/v1/admin/owners/{{ownerId}}/verify", auth=True),
        req("Verify Agent", "PUT", "/api/v1/admin/agents/{{agentId}}/verify", auth=True),
        req("Approve Restaurant", "PUT", "/api/v1/admin/restaurants/{{restaurantId}}/approve", auth=True),
        req("Suspend Restaurant", "PUT", "/api/v1/admin/restaurants/{{restaurantId}}/suspend", auth=True),
        req("Create Coupon", "POST", "/api/v1/coupons", auth=True,
            body={"code": "SAVE10", "discountType": "PERCENTAGE", "discountValue": 10,
                  "minOrderAmount": 200, "maxDiscount": 100, "usageLimit": 100}),
        req("Update Coupon", "PUT", "/api/v1/coupons/{{couponId}}", auth=True,
            body={"code": "SAVE10", "discountType": "PERCENTAGE", "discountValue": 15,
                  "minOrderAmount": 200, "maxDiscount": 150, "usageLimit": 100}),
        req("Delete Coupon", "DELETE", "/api/v1/coupons/{{couponId}}", auth=True),
    ]
    items.append(folder("07 - Admin", admin))

    cache_ops = [
        req("Cache Stats", "GET", "/api/v1/cache/stats", auth=True),
        req("Cache Health", "GET", "/api/v1/cache/health", no_auth=True),
        req("Clear All Caches", "DELETE", "/api/v1/cache/clear", auth=True),
        req("Clear Cache Pattern", "DELETE", "/api/v1/cache/clear/restaurants*", auth=True),
    ]
    items.append(folder("08 - Cache", cache_ops))

    # --- Streams & Webhooks ---
    streams = [
        req("SSE Customer Order", "GET", "/api/v1/orders/stream/customer/{{orderId}}", auth=True,
            headers=[{"key": "Accept", "value": "text/event-stream"}],
            tests="pm.test('200 or streaming',()=>pm.expect(pm.response.code).to.be.oneOf([200]));"),
        req("SSE Kitchen", "GET", "/api/v1/orders/stream/kitchen/{{restaurantId}}", auth=True,
            headers=[{"key": "Accept", "value": "text/event-stream"}]),
        req("SSE Rider", "GET", "/api/v1/orders/stream/rider", auth=True,
            headers=[{"key": "Accept", "value": "text/event-stream"}]),
        req("Razorpay Webhook", "POST", "/api/v1/payments/webhooks/razorpay", no_auth=True,
            body={"event": "payment.captured", "payload": {}},
            headers=[{"key": "X-Razorpay-Signature", "value": "{{razorpaySignature}}"}],
            tests="pm.test('signature or processing',()=>pm.expect(pm.response.code).to.be.oneOf([200,400,500]));"),
    ]
    items.append(folder("09 - Streams & Webhooks", streams))

    # --- E2E Flow (ordered) ---
    e2e_tests = """\
pm.test('E2E step OK', () => pm.expect(pm.response.code).to.be.oneOf([200,201,202]));
"""
    e2e = folder("99 - E2E Flow (run in order)", [
        req("1 Login Customer", "POST", "/api/v1/auth/login",
            body={"email": "{{customerEmail}}", "password": "{{password}}"}, no_auth=True, tests=AUTH_SAVE),
        req("2 Browse Restaurants", "GET", "/api/v1/restaurants/public", no_auth=True, tests=e2e_tests),
        req("3 Add Address", "POST", "/api/v1/customers/addresses", auth=True, body=address_body,
            tests=API_TESTS + "\nif(pm.response.json().data&&pm.response.json().data.id)pm.environment.set('addressId',String(pm.response.json().data.id));"),
        req("4 Add Cart Item", "POST", "/api/v1/cart/add", auth=True,
            body={"menuItemId": "{{menuItemId}}", "quantity": 1}, tests=e2e_tests),
        req("5 Place Order", "POST", "/api/v1/orders/customer/create", auth=True,
            body={"restaurantId": "{{restaurantId}}", "deliveryAddressId": "{{addressId}}", "paymentMethod": "CASH_ON_DELIVERY"},
            tests=API_TESTS + "\nif(pm.response.json().data&&pm.response.json().data.id)pm.environment.set('orderId',String(pm.response.json().data.id));"),
        req("6 Track Order", "GET", "/api/v1/orders/customer/track/{{orderId}}", auth=True, tests=e2e_tests),
    ], "Sequential happy-path. Set restaurantId & menuItemId in env first.")
    items.append(e2e)

    return {
        "info": {
            "_postman_id": uid(),
            "name": "Bhukkad API v1",
            "description": "Complete Bhukkad food-delivery backend collection.\n\n**Setup:** Import environment, run `02 - Auth > Login Customer`, then other folders.\n\nTokens auto-save to `accessToken`. Collection pre-request injects Bearer auth.",
            "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"
        },
        "variable": [
            {"key": "baseUrl", "value": "http://localhost:8080"},
        ],
        "event": [
            {"listen": "prerequest", "script": {"type": "text/javascript", "exec": COLLECTION_PREREQUEST.strip().split("\n")}},
            {"listen": "test", "script": {"type": "text/javascript", "exec": COLLECTION_TEST.strip().split("\n")}},
        ],
        "item": items,
    }


def build_environment(name, base_url, suffix=""):
    s = suffix or str(uuid.uuid4())[:8]
    return {
        "id": uid(),
        "name": name,
        "values": [
            {"key": "baseUrl", "value": base_url, "enabled": True},
            {"key": "password", "value": "secret123", "enabled": True},
            {"key": "customerEmail", "value": f"customer.{s}@bhukkad.test", "enabled": True},
            {"key": "ownerEmail", "value": f"owner.{s}@bhukkad.test", "enabled": True},
            {"key": "agentEmail", "value": f"agent.{s}@bhukkad.test", "enabled": True},
            {"key": "adminEmail", "value": f"admin.{s}@bhukkad.test", "enabled": True},
            {"key": "accessToken", "value": "", "enabled": True},
            {"key": "refreshToken", "value": "", "enabled": True},
            {"key": "userId", "value": "", "enabled": True},
            {"key": "userEmail", "value": "", "enabled": True},
            {"key": "userRole", "value": "", "enabled": True},
            {"key": "restaurantId", "value": "1", "enabled": True},
            {"key": "menuItemId", "value": "1", "enabled": True},
            {"key": "categoryId", "value": "1", "enabled": True},
            {"key": "cartItemId", "value": "1", "enabled": True},
            {"key": "addressId", "value": "1", "enabled": True},
            {"key": "orderId", "value": "1", "enabled": True},
            {"key": "orderNumber", "value": "", "enabled": True},
            {"key": "couponCode", "value": "SAVE10", "enabled": True},
            {"key": "cuisineId", "value": "1", "enabled": True},
            {"key": "agentUserId", "value": "1", "enabled": True},
            {"key": "ownerId", "value": "1", "enabled": True},
            {"key": "jobId", "value": "", "enabled": True},
            {"key": "idempotencyKey", "value": "", "enabled": True},
            {"key": "razorpaySignature", "value": "", "enabled": True},
            {"key": "resetToken", "value": "", "enabled": True},
            {"key": "reviewId", "value": "1", "enabled": True},
            {"key": "couponId", "value": "1", "enabled": True},
        ],
        "_postman_variable_scope": "environment",
    }


def build_curl_doc(collection):
  lines = ["# Bhukkad API — cURL Reference\n", "Set variables:\n```bash\nexport BASE=http://localhost:8080\nexport TOKEN=<from login>\n```\n"]
  for folder_item in collection["item"]:
    lines.append(f"\n## {folder_item['name']}\n")
    for r in folder_item.get("item", []):
      if "request" not in r:
        continue
      req_obj = r["request"]
      method = req_obj["method"]
      url = req_obj["url"].replace("{{baseUrl}}", "$BASE").replace("{{", "${").replace("}}", "}")
      lines.append(f"### {r['name']}\n```bash\ncurl -s -X {method} \"$BASE{url.split('$BASE')[-1]}\"")
      if method in ("POST", "PUT", "PATCH") and "body" in req_obj:
        body = req_obj["body"]["raw"].replace("{{", "${").replace("}}", "}")
        lines.append(f" \\\n  -H 'Content-Type: application/json' \\\n  -H 'Authorization: Bearer $TOKEN' \\\n  -d '{body}'")
      else:
        lines.append(" \\\n  -H 'Authorization: Bearer $TOKEN'")
      lines.append("\n```\n")
  return "".join(lines)


if __name__ == "__main__":
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "environments").mkdir(exist_ok=True)

    coll = build_collection()
    (OUT / "Bhukkad-API.postman_collection.json").write_text(json.dumps(coll, indent=2))
    (OUT / "environments" / "Bhukkad-Local.postman_environment.json").write_text(
        json.dumps(build_environment("Bhukkad Local", "http://localhost:8080", "local"), indent=2))
    (OUT / "environments" / "Bhukkad-Docker.postman_environment.json").write_text(
        json.dumps(build_environment("Bhukkad Docker", "http://localhost:8080", "docker"), indent=2))
    (OUT / "environments" / "Bhukkad-K8s.postman_environment.json").write_text(
        json.dumps(build_environment("Bhukkad K8s Port-Forward", "http://localhost:8080", "k8s"), indent=2))
    (OUT / "CURL_REFERENCE.md").write_text(build_curl_doc(coll))
    print("Generated Postman collection + 3 environments + CURL_REFERENCE.md")
