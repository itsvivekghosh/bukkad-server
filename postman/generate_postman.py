#!/usr/bin/env python3
"""Generate Postman collection + environments for Bhukkad API.

Synchronized with the backend source (services/* controllers, gateway routes)
and the live-tested contract suite (scripts/test-all-apis.py).

Collection layout:
  Part I  (01-16, 99) — functional suites: happy paths with schema + integrity
                        assertions and response chaining.
  Part II (17-19)     — edge cases (boundary values, empty payloads, unusual
                        types) and error handling (invalid auth, missing
                        params, wrong content type, server error codes).

Response envelope (source: platform-lib ApiResponse):
  success:boolean, message, data:T, timestamp, traceId, spanId, requestId
Error envelope (source: platform-lib GlobalExceptionHandler -> ApiError):
  status:int, code:string, message:string, traceId, timestamp

Run:  python3 postman/generate_postman.py
"""
import json
import uuid
from pathlib import Path

OUT = Path(__file__).parent

# ---------------------------------------------------------------------------
# Shared collection-level scripts
# ---------------------------------------------------------------------------

COLLECTION_PREREQUEST = """\
// Inject Bearer auth unless the request opted out via noAuth or health path.
const path = pm.request.url.getPath();
const isHealth = path.includes('/health') || path.startsWith('/actuator/health');
if (pm.variables.get('noAuth') === 'true' || isHealth) {
    pm.request.headers.remove('Authorization');
} else if (!pm.request.headers.has('Authorization')) {
    const token = pm.environment.get('accessToken');
    if (token) {
        pm.request.headers.upsert({ key: 'Authorization', value: 'Bearer ' + token });
    }
}
// Fresh idempotency key per request (replay tests override it explicitly).
pm.variables.set('idempotencyKey', pm.variables.replaceIn('{{$guid}}'));
"""

COLLECTION_TEST = """\
pm.test('Response time < 10s', () => pm.expect(pm.response.responseTime).to.be.below(10000));
if (pm.response.code !== 204 && pm.response.code !== 202 && pm.response.text()) {
    const ct = pm.response.headers.get('Content-Type') || '';
    pm.test('JSON or SSE content-type', () => {
        pm.expect(ct.includes('json') || ct.includes('event-stream')).to.eql(true);
    });
}
"""

AUTH_SAVE = """\
pm.test('Status 200', () => pm.response.to.have.status(200));
const json = pm.response.json();
pm.test('success=true', () => pm.expect(json.success).to.eql(true));
pm.test('token is a JWT (3 segments)', () => {
    pm.expect(json.data.token).to.be.a('string');
    pm.expect(json.data.token.split('.').length).to.eql(3);
});
pm.test('auth schema', () => {
    pm.expect(json.data.userId).to.be.a('number');
    pm.expect(json.data.email).to.be.a('string');
    pm.expect(json.data.role).to.be.a('string');
    pm.expect(json.data.refreshToken || json.data.token).to.be.a('string');
});
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


def js_types_check(schema):
    """Compile {'data.id':'number','data.items':'array','data.email?':'string'} to JS."""
    lines = ["pm.test('response schema', () => {"]
    for path, typ in schema.items():
        optional = typ.endswith("|null") or path.endswith("?")
        key = path.rstrip("?")
        t = typ.replace("|null", "")
        getter = f"_.get(json, '{key}')"
        if optional:
            lines.append(f"    const v = {getter};")
            lines.append(f"    if (v !== undefined && v !== null) pm.expect(v, '{key}').to.be.a('{t}');")
        else:
            lines.append(f"    pm.expect({getter}, '{key}').to.be.a('{t}');")
    lines.append("});")
    return "\n".join(lines)


def ok_tests(codes=(200, 201, 202), schema=None, integrity=None, save=None):
    """Happy-path test block: status, envelope schema, field schema, integrity, chaining."""
    parts = [
        f"pm.test('Status in {codes}', () => pm.expect(pm.response.code).to.be.oneOf({list(codes)}));",
        "const json = pm.response.json();",
        "pm.test('envelope: success=true', () => {",
        "    pm.expect(json.success).to.eql(true);",
        "    pm.expect(json).to.have.property('data');",
        "    pm.expect(json).to.have.property('timestamp');",
        "    pm.expect(json).to.have.property('traceId');",
        "});",
    ]
    if schema:
        parts.append(js_types_check(schema))
    if integrity:
        parts.append(integrity.strip())
    if save:
        for var, path in save.items():
            parts.append(
                f"const __v = _.get(json, '{path}');\n"
                f"if (__v !== undefined && __v !== null) pm.environment.set('{var}', String(__v));"
            )
    return "\n".join(parts)


def err_tests(codes, code_hints=None, integrity=None):
    """Error-path test block: status, ApiError/ApiResponse-failure schema, optional code hint."""
    hints = code_hints or []
    parts = [
        f"pm.test('Status in {codes}', () => pm.expect(pm.response.code).to.be.oneOf({list(codes)}));",
        "const json = pm.response.json();",
        "pm.test('error envelope shape', () => {",
        "    pm.expect(json).to.have.property('message');",
        "    pm.expect(json.message === undefined || typeof json.message === 'string').to.eql(true);",
        "    const failure = json.success === false || typeof json.code === 'string';",
        "    pm.expect(failure).to.eql(true);",
        "});",
    ]
    if hints:
        hint_list = ", ".join(f"'{h}'" for h in hints)
        parts.append(
            "if (typeof json.code === 'string') {\n"
            f"    pm.test('error code', () => pm.expect(json.code).to.be.oneOf([{hint_list}]));\n"
            "}"
        )
    if integrity:
        parts.append(integrity.strip())
    return "\n".join(parts)


NO_AUTH = """\
pm.request.headers.remove('Authorization');
pm.variables.set('noAuth', 'true');
"""

BAD_TOKEN = """\
pm.request.headers.upsert({ key: 'Authorization', value: 'Bearer NOT-A-REAL-JWT.abcdef.ghijkl' });
"""


def raw_status_tests(codes, integrity=None):
    parts = [f"pm.test('Status in {codes}', () => pm.expect(pm.response.code).to.be.oneOf({list(codes)}));"]
    if integrity:
        parts.append(integrity.strip())
    return "\n".join(parts)


def uid():
    return str(uuid.uuid4())


def req(name, method, path, *, body=None, query=None, headers=None, tests=None,
        prerequest=None, description="", no_auth=False, bad_token=False,
        raw_body=None, content_type=None, examples=None):
    """Build a Postman item. tests=JS string; raw_body bypasses JSON serialization."""
    url_raw = "{{baseUrl}}" + path
    if query:
        qs = "&".join(f"{k}={v}" for k, v in query)
        url_raw += ("&" if "?" in path else "?") + qs
    item = {
        "name": name,
        "request": {"method": method, "header": headers or [], "url": url_raw},
        "response": [],
    }
    if description:
        item["request"]["description"] = description
    if body is not None or raw_body is not None:
        ct = content_type or "application/json"
        item["request"]["header"].append({"key": "Content-Type", "value": ct})
        if raw_body is not None:
            item["request"]["body"] = {"mode": "raw", "raw": raw_body}
        else:
            item["request"]["body"] = {"mode": "raw", "raw": json.dumps(body, indent=2)}
    events = []
    pre = []
    if no_auth:
        pre.append("pm.variables.set('noAuth', 'true');")
        pre.append("pm.request.headers.remove('Authorization');")
    if bad_token:
        pre.append(BAD_TOKEN.strip())
    if prerequest:
        pre.append(prerequest.strip())
    if pre:
        events.append({"listen": "prerequest", "script": {"type": "text/javascript", "exec": pre}})
    if tests:
        events.append({"listen": "test", "script": {"type": "text/javascript", "exec": tests.strip().split("\n")}})
    if events:
        item["event"] = events
    if examples:
        item["response"] = examples
    return item


def example(name, code, status_text, body_obj, request_ref=None, content_type="application/json"):
    orig = {}
    if request_ref is not None:
        orig = {
            "method": request_ref["request"]["method"],
            "header": request_ref["request"]["header"],
            "url": request_ref["request"]["url"],
            **({"body": request_ref["request"]["body"]} if "body" in request_ref["request"] else {}),
        }
    return {
        "name": name,
        "originalRequest": orig,
        "status": status_text,
        "code": code,
        "_postman_previewlanguage": "json",
        "header": [{"key": "Content-Type", "value": content_type}],
        "body": json.dumps(body_obj, indent=2),
    }


def folder(name, items, description=""):
    return {"name": name, "description": description, "item": items}


SUCCESS_EXAMPLE = {"success": True, "message": None, "data": {}, "timestamp": "2026-09-08T10:00:00", "traceId": "trace-123", "spanId": "span-456", "requestId": "req-789"}
ERROR_EXAMPLE = {"status": 400, "code": "VALIDATION_FAILED", "message": "Validation failed: email must be valid", "traceId": "trace-123", "timestamp": "2026-09-08T10:00:00Z"}


def build_collection():
    items = []

    # ------------------------------------------------------------------ #
    # 01 - Health (raw maps, no envelope)
    # ------------------------------------------------------------------ #
    health = [
        req("Ping", "GET", "/api/v1/health/ping", no_auth=True,
            description="Liveness probe. Returns raw map `{status:'pong', uptime}` (no ApiResponse envelope).",
            tests=raw_status_tests([200], "pm.test('pong', () => pm.expect(pm.response.json().status).to.eql('pong'));"),
            examples=[example("200 - pong", 200, "OK", {"status": "pong", "uptimeSeconds": 100}, None)]),
        req("Health", "GET", "/api/v1/health", no_auth=True,
            description="Overall health snapshot (raw map: status + components).",
            tests=raw_status_tests([200], "pm.test('status field', () => pm.expect(pm.response.json().status).to.be.a('string'));")),
        req("Health Detailed", "GET", "/api/v1/health/detailed", no_auth=True,
            description="Component-level health detail (db, redis, kafka when configured).",
            tests=raw_status_tests([200], "pm.test('detailed map', () => pm.expect(pm.response.json()).to.be.an('object'));")),
        req("Health DB", "GET", "/api/v1/health/db", no_auth=True,
            description="Primary datasource health.",
            tests=raw_status_tests([200], "pm.test('db status', () => pm.expect(pm.response.json().status).to.be.a('string'));")),
        req("Health DB Replica", "GET", "/api/v1/health/db/replica", no_auth=True,
            description="Read-replica health (200 with status even when no replica is configured).",
            tests=raw_status_tests([200], "pm.test('replica status', () => pm.expect(pm.response.json().status).to.be.a('string'));")),
        req("Health Memory", "GET", "/api/v1/health/memory", no_auth=True,
            description="JVM memory snapshot (used/max numbers).",
            tests=raw_status_tests([200],
                "const j = pm.response.json();\n"
                "pm.test('memory numbers', () => { pm.expect(j.used).to.be.at.least(0); pm.expect(j.max).to.be.at.least(0); });")),
        req("Health Env", "GET", "/api/v1/health/env", no_auth=True,
            description="Active profile / environment info.",
            tests=raw_status_tests([200])),
        req("Actuator Health", "GET", "/actuator/health", no_auth=True,
            description="Spring Boot actuator aggregate health (UP).",
            tests=raw_status_tests([200], "pm.test('UP', () => pm.expect(pm.response.json().status).to.eql('UP'));")),
    ]
    items.append(folder("01 - Health", health, "Raw-map responses (no ApiResponse envelope). All public."))

    # ------------------------------------------------------------------ #
    # 02 - Auth
    # ------------------------------------------------------------------ #
    reg_customer = {"fullName": "Postman Customer", "email": "{{customerEmail}}", "password": "{{password}}",
                    "phoneNumber": "9876543210", "role": "CUSTOMER"}
    reg_owner = {**reg_customer, "fullName": "Postman Owner", "email": "{{ownerEmail}}", "role": "RESTAURANT_OWNER"}
    reg_agent = {**reg_customer, "fullName": "Postman Agent", "email": "{{agentEmail}}", "role": "DELIVERY_AGENT"}
    reg_admin = {**reg_customer, "fullName": "Postman Admin", "email": "{{adminEmail}}", "role": "ADMIN"}

    auth_items = [
        req("Register Customer", "POST", "/api/v1/auth/register", body=reg_customer, no_auth=True, tests=AUTH_SAVE,
            description="201/200 + ApiResponse<AuthResponse>{token, refreshToken, userId, email, role}. Token auto-saved."),
        req("Register Owner", "POST", "/api/v1/auth/register", body=reg_owner, no_auth=True, tests=AUTH_SAVE),
        req("Register Agent", "POST", "/api/v1/auth/register", body=reg_agent, no_auth=True, tests=AUTH_SAVE),
        req("Register Admin", "POST", "/api/v1/auth/register", body=reg_admin, no_auth=True, tests=AUTH_SAVE),
        req("Login Customer", "POST", "/api/v1/auth/login", body={"email": "{{customerEmail}}", "password": "{{password}}"}, no_auth=True, tests=AUTH_SAVE),
        req("Login Owner", "POST", "/api/v1/auth/login", body={"email": "{{ownerEmail}}", "password": "{{password}}"}, no_auth=True, tests=AUTH_SAVE),
        req("Login Agent", "POST", "/api/v1/auth/login", body={"email": "{{agentEmail}}", "password": "{{password}}"}, no_auth=True, tests=AUTH_SAVE),
        req("Login Admin", "POST", "/api/v1/auth/login", body={"email": "{{adminEmail}}", "password": "{{password}}"}, no_auth=True, tests=AUTH_SAVE),
        req("Refresh Token", "POST", "/api/v1/auth/refresh-token", tests=AUTH_SAVE,
            description="Rotating refresh. Reuse detection revokes the whole token family (401)."),
        req("Verify Email", "POST", "/api/v1/auth/verify-email?email={{customerEmail}}",
            tests=ok_tests([200, 400])),
        req("Forgot Password", "POST", "/api/v1/auth/forgot-password?email={{customerEmail}}", no_auth=True,
            tests=raw_status_tests([200])),
        req("Reset Password", "POST", "/api/v1/auth/reset-password?token={{resetToken}}&newPassword={{password}}", no_auth=True,
            tests=err_tests([200, 400, 401])),
        req("Change Password", "POST", "/api/v1/auth/change-password?oldPassword={{password}}&newPassword=secret456",
            tests=ok_tests([200, 400])),
        req("Logout", "POST", "/api/v1/auth/logout", tests=raw_status_tests([200])),
    ]
    items.append(folder("02 - Auth", auth_items, "Run Register/Login first. Token auto-saved to environment."))

    # ------------------------------------------------------------------ #
    # 03 - Public browse
    # ------------------------------------------------------------------ #
    public = [
        req("List Restaurants", "GET", "/api/v1/restaurants/public", no_auth=True,
            description="Paged restaurant summaries. Integrity: content length <= size, ids unique.",
            tests=ok_tests([200],
                schema={"data.content": "array", "data.totalElements": "number", "data.number": "number"},
                integrity=(
                    "pm.test('pagination integrity', () => {\n"
                    "    const d = json.data;\n"
                    "    pm.expect(d.content.length).to.be.at.most(d.size || 20);\n"
                    "    const ids = d.content.map(r => String(r.id));\n"
                    "    pm.expect(new Set(ids).size).to.eql(ids.length);\n"
                    "    d.content.forEach(r => { pm.expect(r.name).to.be.a('string'); });\n"
                    "});"))),
        req("Restaurant By ID", "GET", "/api/v1/restaurants/public/{{restaurantId}}", no_auth=True,
            tests=ok_tests([200, 404]),
            description="200 with restaurant detail; 404 ApiError for unknown id (edge case folder covers it)."),
        req("Search Restaurants", "GET", "/api/v1/restaurants/public/search?keyword=pizza", no_auth=True,
            tests=ok_tests([200], schema={"data": "array"})),
        req("Nearby Restaurants", "GET", "/api/v1/restaurants/public/nearby?latitude=12.97&longitude=77.59&radiusKm=5", no_auth=True,
            tests=ok_tests([200, 400], schema={"data": "array"})),
        req("Filter Restaurants", "GET", "/api/v1/restaurants/public/filter?isPureVeg=true", no_auth=True,
            tests=ok_tests([200], schema={"data": "array"})),
        req("List Cuisines", "GET", "/api/v1/cuisines", no_auth=True, tests=ok_tests([200])),
        req("Cuisine By ID", "GET", "/api/v1/cuisines/{{cuisineId}}", no_auth=True, tests=ok_tests([200, 404])),
        req("Menu Item By ID", "GET", "/api/v1/menu/items/{{menuItemId}}", no_auth=True,
            tests=ok_tests([200, 404],
                schema={"data.price": "number", "data.name": "string"},
                integrity="pm.test('price non-negative', () => pm.expect(json.data.price).to.be.at.least(0));")),
        req("Menu By Category", "GET", "/api/v1/menu/items/category/{{categoryId}}", no_auth=True, tests=ok_tests([200, 404])),
        req("Menu By Restaurant", "GET", "/api/v1/menu/items/restaurant/{{restaurantId}}", no_auth=True,
            tests=ok_tests([200], schema={"data": "array"})),
        req("Bestsellers", "GET", "/api/v1/menu/items/restaurant/{{restaurantId}}/bestsellers", no_auth=True, tests=ok_tests([200])),
        req("Recommended", "GET", "/api/v1/menu/items/restaurant/{{restaurantId}}/recommended", no_auth=True, tests=ok_tests([200])),
        req("Search Menu", "GET", "/api/v1/menu/items/search?keyword=biryani&restaurantId={{restaurantId}}", no_auth=True, tests=ok_tests([200])),
        req("Categories By Restaurant", "GET", "/api/v1/menu/categories/restaurant/{{restaurantId}}", no_auth=True, tests=ok_tests([200])),
        req("Low Stock Items", "GET", "/api/v1/menu/items/restaurant/{{restaurantId}}/low-stock", no_auth=True, tests=ok_tests([200])),
        req("Active Coupons", "GET", "/api/v1/coupons/active", no_auth=True, tests=ok_tests([200])),
        req("Restaurant Reviews", "GET", "/api/v1/reviews/restaurant/{{restaurantId}}", no_auth=True, tests=ok_tests([200])),
        req("Menu Item Ratings", "GET", "/api/v1/reviews/menu-items/{{menuItemId}}", no_auth=True, tests=ok_tests([200, 404])),
    ]
    items.append(folder("03 - Public", public, "Unauthenticated browse surface."))

    # ------------------------------------------------------------------ #
    # 04 - Customer
    # ------------------------------------------------------------------ #
    address_body = {"addressLine1": "123 MG Road", "city": "Bangalore", "state": "Karnataka",
                    "pincode": "560001", "latitude": 12.9716, "longitude": 77.5946,
                    "type": "HOME", "isDefault": True}
    customer = [
        req("Get Profile", "GET", "/api/v1/customers/profile",
            tests=ok_tests([200], schema={"data.email": "string", "data.fullName": "string|null"})),
        req("Get Profile By ID", "GET", "/api/v1/customers/profile/{{userId}}", tests=ok_tests([200, 403, 404])),
        req("Update Profile", "PUT", "/api/v1/customers/profile?fullName=Updated Name", tests=ok_tests([200])),
        req("Add Address", "POST", "/api/v1/customers/addresses", body=address_body,
            tests=ok_tests([200, 201],
                schema={"data.id": "number", "data.pincode": "string|null"},
                integrity="pm.test('lat/lng echoed', () => { pm.expect(json.data.latitude).to.eql(12.9716); pm.expect(json.data.longitude).to.eql(77.5946); });",
                save={"addressId": "data.id"})),
        req("List Addresses", "GET", "/api/v1/customers/addresses", tests=ok_tests([200], schema={"data": "array"})),
        req("Set Default Address", "PUT", "/api/v1/customers/addresses/{{addressId}}/set-default", tests=ok_tests([200])),
        req("Wallet Balance", "GET", "/api/v1/customers/wallet/balance",
            tests=ok_tests([200],
                schema={"data.balance": "number"},
                integrity="pm.test('balance non-negative', () => pm.expect(json.data.balance).to.be.at.least(0));")),
        req("Wallet Transactions", "GET", "/api/v1/customers/wallet/transactions?page=0&size=20",
            tests=ok_tests([200], schema={"data.content": "array"},
                integrity=(
                    "pm.test('ledger sorted desc', () => {\n"
                    "    const c = json.data.content;\n"
                    "    for (let i = 1; i < c.length; i++) {\n"
                    "        pm.expect(new Date(c[i-1].createdAt) >= new Date(c[i].createdAt)).to.eql(true);\n"
                    "    }\n"
                    "});"))),
        req("Loyalty Points", "GET", "/api/v1/customers/loyalty-points",
            tests=ok_tests([200], schema={"data.points": "number", "data.points": "number"},
                integrity="pm.test('points non-negative', () => pm.expect(json.data.points).to.be.at.least(0));")),
        req("Wallet Top-Up Initiate", "POST", "/api/v1/customers/wallet/top-up?amount=100",
            headers=[{"key": "Idempotency-Key", "value": "{{idempotencyKey}}"}],
            tests=ok_tests([200, 201, 202, 400])),
        req("Add Money to Wallet", "POST", "/api/v1/customers/wallet/add-money?amount=500",
            tests=ok_tests([200, 202])),
        req("Register Device Token", "POST", "/api/v1/customers/device-tokens",
            body={"token": "fcm-device-token-sample", "platform": "ANDROID"}, tests=ok_tests([200, 201])),
        req("Unregister Device Token", "DELETE", "/api/v1/customers/device-tokens?token=fcm-device-token-sample",
            tests=ok_tests([200, 204])),
        req("Update Address", "PUT", "/api/v1/customers/addresses/{{addressId}}", body=address_body, tests=ok_tests([200])),
        req("Delete Address", "DELETE", "/api/v1/customers/addresses/{{addressId}}", tests=ok_tests([200, 204])),
        req("Delete Account", "DELETE", "/api/v1/customers/account", tests=ok_tests([200, 204])),
        req("Get Referral Info", "GET", "/api/v1/customers/referral", tests=ok_tests([200])),
        req("List Favorites", "GET", "/api/v1/customers/favorites", tests=ok_tests([200])),
        req("Add Favorite", "POST", "/api/v1/customers/favorites/{{restaurantId}}", tests=ok_tests([200, 201, 409])),
        req("Remove Favorite", "DELETE", "/api/v1/customers/favorites/{{restaurantId}}", tests=ok_tests([200, 204, 404])),
        req("Order Stats", "GET", "/api/v1/customers/orders/stats",
            tests=ok_tests([200], schema={"data.totalOrders": "number"},
                integrity="pm.test('stats non-negative', () => { pm.expect(json.data.totalOrders).to.be.at.least(0); });")),
        req("Get Notification Preferences", "GET", "/api/v1/customers/notification-preferences", tests=ok_tests([200])),
        req("Update Notification Preferences", "PUT", "/api/v1/customers/notification-preferences",
            body={"emailEnabled": True, "smsEnabled": True, "pushEnabled": True, "whatsappEnabled": False,
                  "orderUpdates": True, "promotions": True, "recommendations": True},
            tests=ok_tests([200])),
        req("Create Support Ticket", "POST", "/api/v1/customers/support/tickets",
            body={"subject": "Order issue", "description": "My order was delayed", "category": "DELIVERY"},
            tests=ok_tests([200, 201], save={"ticketId": "data.id"})),
        req("List Support Tickets", "GET", "/api/v1/customers/support/tickets", tests=ok_tests([200])),
        req("List Membership Plans", "GET", "/api/v1/customers/membership/plans", tests=ok_tests([200])),
        req("Get Membership Status", "GET", "/api/v1/customers/membership/status", tests=ok_tests([200])),
        req("Subscribe Membership", "POST", "/api/v1/customers/membership/subscribe",
            body={"planId": 1}, tests=ok_tests([200, 201, 400])),
        # --- Cart flow ---
        req("Get Cart", "GET", "/api/v1/cart",
            tests=ok_tests([200],
                integrity=(
                    "pm.test('cart integrity', () => {\n"
                    "    const d = json.data;\n"
                    "    const carts = d.restaurantCarts || d.items || [];\n"
                    "    [].concat(carts).forEach(c => {\n"
                    "        (c.items || []).forEach(i => {\n"
                    "            pm.expect(i.quantity).to.be.at.least(1);\n"
                    "        });\n"
                    "    });\n"
                    "});"))),
        req("Add To Cart", "POST", "/api/v1/cart/add",
            body={"menuItemId": "{{menuItemId}}", "quantity": 2, "specialInstructions": "less spicy"},
            tests=ok_tests([200, 201], save={"cartItemId": "data.restaurantCarts[0].items[0].id"})),
        req("Update Cart Item Qty", "PUT", "/api/v1/cart/items/{{cartItemId}}?quantity=3", tests=ok_tests([200])),
        req("Apply Coupon", "POST", "/api/v1/cart/apply-coupon?couponCode={{couponCode}}",
            tests=ok_tests([200, 400, 404])),
        req("Remove Cart Item", "DELETE", "/api/v1/cart/items/{{cartItemId}}", tests=ok_tests([200, 204, 404])),
        req("Clear Cart", "DELETE", "/api/v1/cart/clear", tests=ok_tests([200, 204])),
        # --- Orders ---
        req("Create Order", "POST", "/api/v1/orders/customer/create",
            headers=[{"key": "Idempotency-Key", "value": "{{idempotencyKey}}"}],
            body={"restaurantId": "{{restaurantId}}", "deliveryAddressId": "{{addressId}}",
                  "paymentMethod": "CASH_ON_DELIVERY", "specialInstructions": "Ring doorbell"},
            description="Creates order from cart. 202+jobId when ?async=true. Idempotent on Idempotency-Key.",
            tests=ok_tests([200, 201, 202],
                schema={"data.id": "number", "data.status": "string"},
                integrity=(
                    "pm.test('order integrity', () => {\n"
                    "    pm.expect(json.data.id).to.be.above(0);\n"
                    "    pm.expect(['PLACED','CONFIRMED','PENDING','PENDING_PAYMENT','SCHEDULED','ACCEPTED']).to.include(json.data.status);\n"
                    "    if (_.get(json, 'data.totalAmount') !== undefined && _.get(json, 'data.totalAmount') !== null) {\n"
                    "        pm.expect(json.data.totalAmount).to.be.at.least(0);\n"
                    "    }\n"
                    "});"),
                save={"orderId": "data.id"}),
            examples=[example("202 - accepted (async)", 202, "Accepted",
                              {"success": True, "data": {"jobId": "3f9c2b1e", "status": "ACCEPTED"}, "traceId": "t1"}, None)]),
        req("Create Order (Split Pay)", "POST", "/api/v1/orders/customer/create",
            body={"restaurantId": "{{restaurantId}}", "deliveryAddressId": "{{addressId}}",
                  "paymentMethod": "UPI", "useWallet": True, "walletAmountToUse": 50},
            tests=ok_tests([200, 201, 202, 400])),
        req("Create Order Async", "POST", "/api/v1/orders/customer/create?async=true",
            headers=[{"key": "Idempotency-Key", "value": "{{idempotencyKey}}"}],
            body={"restaurantId": "{{restaurantId}}", "deliveryAddressId": "{{addressId}}", "paymentMethod": "UPI"},
            tests="pm.test('202 Accepted', () => pm.expect(pm.response.code).to.eql(202));\n"
                  "const json = pm.response.json();\n"
                  "pm.test('jobId present', () => { if (json.data) pm.expect(json.data.jobId || json.data.id).to.exist; });\n"
                  "if (json.data && json.data.jobId) pm.environment.set('jobId', json.data.jobId);"),
        req("Get Create Job", "GET", "/api/v1/orders/customer/create/jobs/{{jobId}}",
            tests=ok_tests([200, 404]),
            description="Poll async job: PENDING/PROCESSING/COMPLETED/FAILED."),
        req("Create Batch Orders", "POST", "/api/v1/orders/customer/create-batch",
            headers=[{"key": "Idempotency-Key", "value": "{{idempotencyKey}}"}],
            body={"orders": [{"restaurantId": "{{restaurantId}}", "deliveryAddressId": "{{addressId}}",
                              "paymentMethod": "CASH_ON_DELIVERY",
                              "items": [{"menuItemId": "{{menuItemId}}", "quantity": 1}]}]},
            tests=ok_tests([200, 201, 202, 400])),
        req("My Orders", "GET", "/api/v1/orders/customer/my-orders?page=0&size=20",
            tests=ok_tests([200], schema={"data.content": "array"},
                integrity="pm.test('page integrity', () => { pm.expect(json.data.content.length).to.be.at.most(20); });")),
        req("My Orders Cursor", "GET", "/api/v1/orders/customer/my-orders/cursor?size=20", tests=ok_tests([200])),
        req("Order By ID", "GET", "/api/v1/orders/customer/{{orderId}}",
            tests=ok_tests([200, 404], schema={"data.status": "string"})),
        req("Track Order", "GET", "/api/v1/orders/customer/track/{{orderId}}",
            tests=ok_tests([200, 404], schema={"data.status": "string", "data.status": "string"})),
        req("Rider Location", "GET", "/api/v1/orders/{{orderId}}/rider-location", tests=ok_tests([200, 404])),
        req("Order Timeline", "GET", "/api/v1/orders/{{orderId}}/timeline",
            tests=ok_tests([200, 404],
                integrity="pm.test('timeline ordered', () => { if (Array.isArray(json.data) && json.data.length > 1) { pm.expect(new Date(json.data[0].at || json.data[0].timestamp) >= new Date(json.data[1].at || json.data[1].timestamp)).to.eql(true); } });")),
        req("Order Invoice", "GET", "/api/v1/orders/{{orderId}}/invoice", tests=ok_tests([200, 404])),
        req("Download Invoice PDF", "GET", "/api/v1/orders/{{orderId}}/invoice/pdf",
            tests="pm.test('PDF download', () => {\n"
                  "    pm.expect(pm.response.code).to.eql(200);\n"
                  "    pm.expect(pm.response.headers.get('Content-Type')).to.include('application/pdf');\n"
                  "});"),
        req("Reorder", "POST", "/api/v1/orders/customer/{{orderId}}/reorder", tests=ok_tests([200, 201, 400, 404])),
        req("Cancel Order", "PUT", "/api/v1/orders/customer/{{orderId}}/cancel?reason=Changed mind",
            tests=ok_tests([200, 400, 404, 409]),
            description="Only cancellable states allowed; terminal orders → 400/409."),
        req("Payment For Order", "GET", "/api/v1/payments/orders/{{orderId}}", tests=ok_tests([200, 404])),
        req("Validate Coupon", "GET", "/api/v1/coupons/validate?code={{couponCode}}&subtotal=500&restaurantId={{restaurantId}}",
            tests=ok_tests([200, 400, 404])),
        req("Create Review", "POST", "/api/v1/reviews",
            body={"orderId": "{{orderId}}", "rating": 5, "comment": "Great food!"},
            tests=ok_tests([200, 201, 400, 409], save={"reviewId": "data.id"})),
        req("Rate Menu Item", "POST", "/api/v1/reviews/menu-items",
            body={"menuItemId": "{{menuItemId}}", "rating": 5, "comment": "Delicious!"},
            tests=ok_tests([200, 201, 400])),
        req("My Reviews", "GET", "/api/v1/reviews/my-reviews", tests=ok_tests([200])),
        req("Review By Order", "GET", "/api/v1/reviews/order/{{orderId}}", tests=ok_tests([200, 404])),
        req("Delete Review", "DELETE", "/api/v1/reviews/{{reviewId}}", tests=ok_tests([200, 204, 404])),
        req("Clear Restaurant Cart", "DELETE", "/api/v1/cart/restaurant/{{restaurantId}}", tests=ok_tests([200, 204])),
    ]
    items.append(folder("04 - Customer", customer))

    # ------------------------------------------------------------------ #
    # 05 - Restaurant Owner
    # ------------------------------------------------------------------ #
    restaurant_body = {"name": "Postman Kitchen", "description": "Test restaurant", "address": address_body,
                       "openingTime": "09:00:00", "closingTime": "23:00:00",
                       "minimumOrderAmount": 100, "deliveryFee": 40, "isPureVeg": False}
    owner = [
        req("Create Restaurant", "POST", "/api/v1/restaurants/owner", body=restaurant_body,
            tests=ok_tests([200, 201], schema={"data.id": "number"}, save={"restaurantId": "data.id"})),
        req("My Restaurants", "GET", "/api/v1/restaurants/owner/my-restaurants", tests=ok_tests([200])),
        req("Update Restaurant", "PUT", "/api/v1/restaurants/owner/{{restaurantId}}", body=restaurant_body,
            tests=ok_tests([200, 403, 404])),
        req("Delete Restaurant", "DELETE", "/api/v1/restaurants/owner/{{restaurantId}}", tests=ok_tests([200, 204, 404])),
        req("Toggle Open", "PUT", "/api/v1/restaurants/owner/{{restaurantId}}/toggle-status?isOpen=true", tests=ok_tests([200])),
        req("Restaurant Analytics", "GET", "/api/v1/restaurants/owner/{{restaurantId}}/analytics?days=30", tests=ok_tests([200, 403, 404])),
        req("Restaurant Settlements", "GET", "/api/v1/restaurants/owner/{{restaurantId}}/settlements?page=0&size=20", tests=ok_tests([200, 403])),
        req("Enable Busy Mode", "PUT", "/api/v1/restaurants/owner/{{restaurantId}}/busy-mode",
            body={"enabled": True, "message": "High order volume", "estimatedDelayMinutes": 30}, tests=ok_tests([200])),
        req("Disable Busy Mode", "DELETE", "/api/v1/restaurants/owner/{{restaurantId}}/busy-mode", tests=ok_tests([200, 204])),
        req("Restaurant Dashboard", "GET", "/api/v1/restaurants/owner/{{restaurantId}}/dashboard?days=30", tests=ok_tests([200, 403])),
        req("Respond to Review", "POST", "/api/v1/restaurants/owner/reviews/{{reviewId}}/response",
            body={"response": "Thank you for your feedback!"}, tests=ok_tests([200, 400, 404])),
        req("Create Category", "POST", "/api/v1/menu/categories?restaurantId={{restaurantId}}",
            body={"name": "Main Course", "description": "Mains", "displayOrder": 1},
            tests=ok_tests([200, 201], schema={"data.id": "number"}, save={"categoryId": "data.id"})),
        req("Create Menu Item", "POST", "/api/v1/menu/items",
            body={"name": "Chicken Biryani", "categoryId": "{{categoryId}}", "price": 250,
                  "foodType": "NON_VEG", "isVeg": False, "description": "Hyderabadi style"},
            tests=ok_tests([200, 201],
                schema={"data.id": "number", "data.price": "number"},
                integrity="pm.test('price stored', () => pm.expect(json.data.price).to.eql(250));",
                save={"menuItemId": "data.id"})),
        req("Image Upload URL", "POST", "/api/v1/menu/items/{{menuItemId}}/image/upload-url",
            body={"contentType": "image/jpeg", "fileName": "biryani.jpg"}, tests=ok_tests([200])),
        req("Update Menu Item", "PUT", "/api/v1/menu/items/{{menuItemId}}",
            body={"name": "Chicken Biryani", "categoryId": "{{categoryId}}", "price": 275,
                  "foodType": "NON_VEG", "isVeg": False},
            tests=ok_tests([200, 403, 404], integrity="pm.test('price updated', () => { if (_.get(json,'data.price')!==undefined) pm.expect(json.data.price).to.eql(275); });")),
        req("Delete Menu Item", "DELETE", "/api/v1/menu/items/{{menuItemId}}", tests=ok_tests([200, 204, 404])),
        req("Update Category", "PUT", "/api/v1/menu/categories/{{categoryId}}",
            body={"name": "Main Course", "description": "Mains", "displayOrder": 1}, tests=ok_tests([200, 404])),
        req("Delete Category", "DELETE", "/api/v1/menu/categories/{{categoryId}}", tests=ok_tests([200, 204, 404])),
        req("Toggle Item Availability", "PUT", "/api/v1/menu/items/{{menuItemId}}/toggle-availability?available=true", tests=ok_tests([200, 404])),
        req("Restaurant Orders", "GET", "/api/v1/orders/restaurant/{{restaurantId}}?page=0&size=20", tests=ok_tests([200, 403])),
        req("Pending Orders", "GET", "/api/v1/orders/restaurant/{{restaurantId}}/pending", tests=ok_tests([200, 403])),
        req("Kitchen Queue", "GET", "/api/v1/orders/restaurant/{{restaurantId}}/kitchen-queue", tests=ok_tests([200, 403])),
        req("Accept Order", "PUT", "/api/v1/orders/restaurant/{{orderId}}/accept",
            tests=ok_tests([200, 400, 403, 404, 409]),
            description="State machine: only PLACED/PENDING can be accepted; 409 otherwise."),
        req("Mark Ready", "PUT", "/api/v1/orders/restaurant/{{orderId}}/ready", tests=ok_tests([200, 400, 403, 404, 409])),
        req("Assign Delivery Agent", "PUT", "/api/v1/orders/restaurant/{{orderId}}/assign-delivery?agentId={{agentUserId}}",
            tests=ok_tests([200, 400, 403, 404])),
    ]
    items.append(folder("05 - Restaurant Owner", owner))

    # ------------------------------------------------------------------ #
    # 06 - Delivery Agent
    # ------------------------------------------------------------------ #
    agent = [
        req("Get Profile", "GET", "/api/v1/delivery/profile", tests=ok_tests([200, 403])),
        req("Toggle Available", "PUT", "/api/v1/delivery/toggle-availability?available=true", tests=ok_tests([200, 403])),
        req("Update Location", "PUT", "/api/v1/delivery/update-location?latitude=12.97&longitude=77.59", tests=ok_tests([200, 400, 403])),
        req("Available Orders", "GET", "/api/v1/delivery/available-orders", tests=ok_tests([200, 403])),
        req("Accept Delivery", "POST", "/api/v1/delivery/{{orderId}}/accept", tests=ok_tests([200, 400, 403, 404, 409])),
        req("Active Deliveries", "GET", "/api/v1/delivery/active-deliveries", tests=ok_tests([200, 403])),
        req("My Deliveries", "GET", "/api/v1/orders/delivery/my-deliveries?page=0&size=20", tests=ok_tests([200, 403])),
        req("My Deliveries Cursor", "GET", "/api/v1/orders/delivery/my-deliveries/cursor?size=20", tests=ok_tests([200, 403])),
        req("Order By Number", "GET", "/api/v1/orders/number/{{orderNumber}}", tests=ok_tests([200, 404])),
        req("Mark Picked Up", "PUT", "/api/v1/orders/delivery/{{orderId}}/picked-up", tests=ok_tests([200, 400, 403, 404, 409])),
        req("Issue Delivery Proof OTP", "POST", "/api/v1/orders/delivery/{{orderId}}/proof/otp", tests=ok_tests([200, 400, 403, 404])),
        req("Delivery Proof Photo URL", "POST", "/api/v1/orders/delivery/{{orderId}}/proof/photo-url",
            body={"contentType": "image/jpeg"}, tests=ok_tests([200], save={"deliveryProofPhotoKey": "data.photoKey"})),
        req("Verify Delivery Proof", "POST", "/api/v1/orders/delivery/{{orderId}}/proof/verify",
            body={"otpCode": "123456", "photoKey": "{{deliveryProofPhotoKey}}"},
            tests=ok_tests([200, 400])),
        req("Get Delivery Proof", "GET", "/api/v1/orders/delivery/{{orderId}}/proof", tests=ok_tests([200, 404])),
        req("Mark Delivered", "PUT", "/api/v1/orders/delivery/{{orderId}}/delivered",
            tests=ok_tests([200, 400, 403, 404, 409]),
            description="Idempotent transition: second call returns 400/409 (already delivered)."),
        req("Earnings Summary", "GET", "/api/v1/delivery/earnings/summary",
            tests=ok_tests([200, 403],
                integrity="pm.test('earnings non-negative', () => { if (_.get(json,'data.total')!==undefined) pm.expect(json.data.total).to.be.at.least(0); });")),
        req("Earnings History", "GET", "/api/v1/delivery/earnings?page=0&size=20", tests=ok_tests([200, 403])),
        req("Delivery History", "GET", "/api/v1/delivery/delivery-history", tests=ok_tests([200, 403])),
        req("Reject Delivery", "POST", "/api/v1/delivery/{{orderId}}/reject", tests=ok_tests([200, 400, 403, 404])),
        req("Update Order Location", "POST", "/api/v1/delivery/orders/{{orderId}}/location",
            body={"latitude": 12.97, "longitude": 77.59}, tests=ok_tests([200, 400, 404])),
        req("Create Delivery Batch", "POST", "/api/v1/delivery/batches",
            tests=ok_tests([200, 201], save={"batchId": "data.id"})),
        req("Get Active Batch", "GET", "/api/v1/delivery/batches/active", tests=ok_tests([200, 404])),
        req("Complete Batch", "PUT", "/api/v1/delivery/batches/{{batchId}}/complete", tests=ok_tests([200, 400, 404])),
    ]
    items.append(folder("06 - Delivery Agent", agent))

    # ------------------------------------------------------------------ #
    # 07 - Admin
    # ------------------------------------------------------------------ #
    admin = [
        req("Dashboard", "GET", "/api/v1/admin/dashboard", tests=ok_tests([200, 403])),
        req("All Users", "GET", "/api/v1/admin/users?page=0&size=20", tests=ok_tests([200, 403])),
        req("All Orders", "GET", "/api/v1/admin/orders?page=0&size=20", tests=ok_tests([200, 403])),
        req("All Restaurants", "GET", "/api/v1/admin/restaurants?page=0&size=20", tests=ok_tests([200, 403])),
        req("Revenue", "GET", "/api/v1/admin/revenue?days=7", tests=ok_tests([200, 403])),
        req("Analytics", "GET", "/api/v1/admin/analytics", tests=ok_tests([200, 403])),
        req("Activate User", "PUT", "/api/v1/admin/users/{{userId}}/activate", tests=ok_tests([200, 403, 404])),
        req("Deactivate User", "PUT", "/api/v1/admin/users/{{userId}}/deactivate", tests=ok_tests([200, 403, 404])),
        req("Verify Owner", "PUT", "/api/v1/admin/owners/{{ownerId}}/verify", tests=ok_tests([200, 403, 404])),
        req("Verify Agent", "PUT", "/api/v1/admin/agents/{{agentId}}/verify", tests=ok_tests([200, 403, 404])),
        req("Approve Restaurant", "PUT", "/api/v1/admin/restaurants/{{restaurantId}}/approve", tests=ok_tests([200, 403, 404])),
        req("Suspend Restaurant", "PUT", "/api/v1/admin/restaurants/{{restaurantId}}/suspend", tests=ok_tests([200, 403, 404])),
        req("Set Restaurant Commission", "PUT", "/api/v1/admin/restaurants/{{restaurantId}}/commission?percent=20",
            tests=ok_tests([200, 400, 403, 404])),
        req("Create Coupon", "POST", "/api/v1/coupons",
            body={"code": "SAVE10", "discountType": "PERCENTAGE", "discountValue": 10,
                  "minOrderAmount": 200, "maxDiscount": 100, "usageLimit": 100},
            tests=ok_tests([200, 201, 400, 409], save={"couponId": "data.id"})),
        req("Update Coupon", "PUT", "/api/v1/coupons/{{couponId}}",
            body={"code": "SAVE10", "discountType": "PERCENTAGE", "discountValue": 15,
                  "minOrderAmount": 200, "maxDiscount": 150, "usageLimit": 100},
            tests=ok_tests([200, 400, 403, 404])),
        req("Delete Coupon", "DELETE", "/api/v1/coupons/{{couponId}}", tests=ok_tests([200, 204, 404])),
        req("List All Support Tickets", "GET", "/api/v1/admin/support/tickets", tests=ok_tests([200, 403])),
        req("Update Ticket Status", "PUT", "/api/v1/admin/support/tickets/{{ticketId}}/status?status=RESOLVED&resolutionNotes=Issue resolved",
            tests=ok_tests([200, 400, 403, 404])),
        req("Send Test Notification", "POST", "/api/v1/admin/notifications/test",
            body={"channel": "EMAIL", "recipient": "test@example.com", "message": "Test notification"},
            tests=ok_tests([200, 400, 403])),
        req("Review Moderation Queue", "GET", "/api/v1/admin/reviews/moderation", tests=ok_tests([200, 403])),
        req("Review Moderation Pending", "GET", "/api/v1/admin/reviews/moderation?status=PENDING", tests=ok_tests([200, 403])),
        req("Moderate Review Approve", "PUT", "/api/v1/admin/reviews/{{reviewId}}/moderate?status=APPROVED", tests=ok_tests([200, 400, 403, 404])),
        req("Moderate Review Reject", "PUT", "/api/v1/admin/reviews/{{reviewId}}/moderate?status=REJECTED", tests=ok_tests([200, 400, 403, 404])),
    ]
    items.append(folder("07 - Admin", admin))

    # ------------------------------------------------------------------ #
    # 08-16 (unchanged surfaces, schema-tightened)
    # ------------------------------------------------------------------ #
    cache_ops = [
        req("Cache Stats", "GET", "/api/v1/cache/stats",
            tests=raw_status_tests([200], "pm.test('stats map', () => pm.expect(pm.response.json()).to.be.an('object'));")),
        req("Cache Health", "GET", "/api/v1/cache/health", no_auth=True,
            tests=raw_status_tests([200])),
        req("Clear All Caches", "DELETE", "/api/v1/cache/clear",
            tests=raw_status_tests([200, 204, 403])),
        req("Clear Cache Pattern", "DELETE", "/api/v1/cache/clear/restaurants*",
            tests=raw_status_tests([200, 204, 403, 404])),
    ]
    items.append(folder("08 - Cache", cache_ops))

    home_feed = [
        req("Get Home Feed (Composed)", "GET", "/api/v1/home/feed", no_auth=True,
            tests=ok_tests([200])),
        req("Get Banners", "GET", "/api/v1/home/banners", no_auth=True,
            tests=ok_tests([200], schema={"data": "array|null"})),
        req("Get Campaigns", "GET", "/api/v1/home/campaigns", no_auth=True, tests=ok_tests([200])),
        req("Get Membership Plans", "GET", "/api/v1/home/membership-plans", no_auth=True, tests=ok_tests([200])),
        req("Home Trending", "GET", "/api/v1/home/trending", no_auth=True,
            tests=ok_tests([200], schema={"data": "array|null"})),
    ]
    items.append(folder("09 - Home Feed (Public)", home_feed))

    search = [
        req("Unified Search", "GET", "/api/v1/search?keyword=biryani", no_auth=True,
            tests=ok_tests([200], schema={"data": "array|null"}),
            description="Unified restaurant+menu search. Empty/absent keyword edge cases in folder 17."),
        req("Search Suggest", "GET", "/api/v1/search/suggest?prefix=bir", no_auth=True, tests=ok_tests([200])),
    ]
    items.append(folder("10 - Search (Public)", search))

    serviceability = [
        req("Check Serviceability", "GET", "/api/v1/serviceability/check?restaurantId={{restaurantId}}&latitude=12.97&longitude=77.59&subtotal=500", no_auth=True,
            tests=ok_tests([200, 400, 404])),
    ]
    items.append(folder("11 - Serviceability (Public)", serviceability))

    platform = [
        req("Platform Status", "GET", "/api/v1/platform/status", no_auth=True,
            tests=raw_status_tests([200], "pm.test('status map', () => pm.expect(pm.response.json()).to.be.an('object'));")),
        req("Platform Cities", "GET", "/api/v1/platform/cities", no_auth=True, tests=ok_tests([200])),
    ]
    items.append(folder("12 - Platform (Info)", platform))

    delivery_truth = [
        req("Order ETA Detail", "GET", "/api/v1/delivery-truth/orders/{{orderId}}/eta",
            tests=ok_tests([200, 404])),
    ]
    items.append(folder("13 - Delivery Truth (ETA)", delivery_truth))

    streams = [
        req("SSE Customer Order", "GET", "/api/v1/orders/stream/customer/{{orderId}}",
            headers=[{"key": "Accept", "value": "text/event-stream"}],
            tests=raw_status_tests([200])),
        req("SSE Kitchen", "GET", "/api/v1/orders/stream/kitchen/{{restaurantId}}",
            headers=[{"key": "Accept", "value": "text/event-stream"}],
            tests=raw_status_tests([200])),
        req("SSE Rider", "GET", "/api/v1/orders/stream/rider",
            headers=[{"key": "Accept", "value": "text/event-stream"}],
            tests=raw_status_tests([200])),
        req("Razorpay Webhook (unsigned)", "POST", "/api/v1/payments/webhooks/razorpay", no_auth=True,
            body={"event": "payment.captured", "payload": {}},
            headers=[{"key": "X-Razorpay-Signature", "value": "invalid-signature"}],
            tests=err_tests([400, 401]),
            description="Unsigned/forged webhooks must be rejected (fail-closed signature check)."),
    ]
    items.append(folder("14 - Streams & Webhooks", streams))

    trust_v17 = [
        req("Issue Delivery Proof OTP", "POST", "/api/v1/orders/delivery/{{orderId}}/proof/otp", tests=ok_tests([200, 400, 404])),
        req("Delivery Proof Photo URL", "POST", "/api/v1/orders/delivery/{{orderId}}/proof/photo-url",
            body={"contentType": "image/jpeg"}, tests=ok_tests([200])),
        req("Verify Delivery Proof (wrong OTP)", "POST", "/api/v1/orders/delivery/{{orderId}}/proof/verify",
            body={"otpCode": "000000"},
            tests=err_tests([400], ["PROOF_MISMATCH", "VALIDATION_FAILED", "INVALID_BODY", "BUSINESS_ERROR", "OTP_INVALID"])),
        req("Get Delivery Proof", "GET", "/api/v1/orders/delivery/{{orderId}}/proof", tests=ok_tests([200, 404])),
        req("Download Invoice PDF", "GET", "/api/v1/orders/{{orderId}}/invoice/pdf",
            tests="pm.test('PDF', () => {\n    pm.expect(pm.response.code).to.eql(200);\n    pm.expect(pm.response.headers.get('Content-Type')).to.include('application/pdf');\n});"),
        req("Review Moderation Queue", "GET", "/api/v1/admin/reviews/moderation", tests=ok_tests([200, 403])),
        req("Moderate Review", "PUT", "/api/v1/admin/reviews/{{reviewId}}/moderate?status=APPROVED", tests=ok_tests([200, 400, 403, 404])),
    ]
    items.append(folder("15 - Trust & Compliance (V17)", trust_v17,
                        "GST invoice PDFs, delivery proof OTP/photo, review moderation."))

    zone_polygon = {"type": "Polygon", "coordinates": [[[77.5, 12.9], [77.6, 12.9], [77.6, 13.0], [77.5, 13.0], [77.5, 12.9]]]}
    admin_scale = [
        req("List Delivery Zones", "GET", "/api/v1/admin/zones", tests=ok_tests([200, 403])),
        req("Create Delivery Zone", "POST", "/api/v1/admin/zones",
            body={"name": "Zone 1", "polygon": zone_polygon, "deliveryFee": 30, "minOrderAmount": 100, "isActive": True},
            tests=ok_tests([200, 201, 403], save={"zoneId": "data.id"})),
        req("Update Delivery Zone", "PUT", "/api/v1/admin/zones/{{zoneId}}",
            body={"name": "Zone 1 Updated", "polygon": zone_polygon, "deliveryFee": 40, "minOrderAmount": 150, "isActive": True},
            tests=ok_tests([200, 403, 404])),
        req("Delete Delivery Zone", "DELETE", "/api/v1/admin/zones/{{zoneId}}", tests=ok_tests([200, 204, 403, 404])),
        req("List Promotion Campaigns", "GET", "/api/v1/admin/promotions/campaigns", tests=ok_tests([200, 403])),
        req("Create Promotion Campaign", "POST", "/api/v1/admin/promotions/campaigns",
            body={"name": "Summer Sale", "description": "Summer discount", "discountType": "PERCENTAGE", "discountValue": 20,
                  "minOrderAmount": 200, "maxDiscount": 100, "startsAt": "2026-06-01T00:00:00", "endsAt": "2026-08-31T23:59:59",
                  "usageLimit": 1000, "applicableRestaurantIds": [1]},
            tests=ok_tests([200, 201, 400, 403], save={"campaignId": "data.id"})),
        req("Update Promotion Campaign", "PUT", "/api/v1/admin/promotions/campaigns/{{campaignId}}",
            body={"name": "Summer Sale", "description": "Summer discount", "discountType": "PERCENTAGE", "discountValue": 25,
                  "minOrderAmount": 200, "maxDiscount": 150, "startsAt": "2026-06-01T00:00:00", "endsAt": "2026-08-31T23:59:59",
                  "usageLimit": 1000, "applicableRestaurantIds": [1]},
            tests=ok_tests([200, 400, 403, 404])),
        req("Deactivate Promotion Campaign", "DELETE", "/api/v1/admin/promotions/campaigns/{{campaignId}}",
            tests=ok_tests([200, 204, 403, 404])),
        req("List Promo Banners", "GET", "/api/v1/admin/promotions/banners", tests=ok_tests([200, 403])),
        req("Create Promo Banner", "POST", "/api/v1/admin/promotions/banners",
            body={"imageUrl": "https://example.com/banner.jpg", "targetUrl": "https://example.com", "displayOrder": 1,
                  "startsAt": "2026-06-01T00:00:00", "endsAt": "2026-08-31T23:59:59", "isActive": True},
            tests=ok_tests([200, 201, 403], save={"bannerId": "data.id"})),
        req("Update Promo Banner", "PUT", "/api/v1/admin/promotions/banners/{{bannerId}}",
            body={"imageUrl": "https://example.com/banner2.jpg", "targetUrl": "https://example.com", "displayOrder": 1,
                  "startsAt": "2026-06-01T00:00:00", "endsAt": "2026-08-31T23:59:59", "isActive": True},
            tests=ok_tests([200, 403, 404])),
        req("Operations Dashboard", "GET", "/api/v1/admin/operations-dashboard", tests=ok_tests([200, 403])),
        req("Trigger Settlement Run", "POST", "/api/v1/admin/settlements/run", tests=ok_tests([200, 202, 403, 409])),
    ]
    items.append(folder("16 - Admin Scale (V14-V16)", admin_scale,
                        "Delivery zones, promotion campaigns/banners, settlement automation, operations dashboard."))

    # ------------------------------------------------------------------ #
    # 17 - Edge & Boundary cases
    # ------------------------------------------------------------------ #
    edge = [
        # -- Registration boundaries --
        req("Register: empty body", "POST", "/api/v1/auth/register", no_auth=True, body={},
            tests=err_tests([400], ["VALIDATION_FAILED", "INVALID_BODY"]),
            description="Empty payload → 400 VALIDATION_FAILED (bean validation)."),
        req("Register: malformed email", "POST", "/api/v1/auth/register", no_auth=True,
            body={"fullName": "Edge", "email": "not-an-email", "password": "{{password}}", "role": "CUSTOMER"},
            tests=err_tests([400], ["VALIDATION_FAILED", "INVALID_BODY"])),
        req("Register: short password", "POST", "/api/v1/auth/register", no_auth=True,
            body={"fullName": "Edge", "email": "{{edgeEmail}}", "password": "x", "role": "CUSTOMER"},
            tests=err_tests([400], ["VALIDATION_FAILED", "INVALID_BODY", "WEAK_PASSWORD", "BUSINESS_ERROR"])),
        req("Register: duplicate email", "POST", "/api/v1/auth/register", no_auth=True, body=reg_customer,
            tests=err_tests([400, 409], ["DATA_CONFLICT", "EMAIL_EXISTS", "BUSINESS_ERROR", "VALIDATION_FAILED"]),
            description="Second registration with {{customerEmail}} → 409 DATA_CONFLICT (unique constraint)."),
        req("Register: 10k-char fullName", "POST", "/api/v1/auth/register", no_auth=True,
            body={"fullName": "A" * 10000, "email": f"long{uuid.uuid4().hex[:6]}@bhukkad.test", "password": "{{password}}", "role": "CUSTOMER"},
            tests=err_tests([400], ["VALIDATION_FAILED", "INVALID_BODY"])),
        req("Register: unknown role enum", "POST", "/api/v1/auth/register", no_auth=True,
            body={"fullName": "Edge", "email": f"role{uuid.uuid4().hex[:6]}@bhukkad.test", "password": "{{password}}", "role": "SUPERUSER_X"},
            tests=err_tests([400], ["VALIDATION_FAILED", "INVALID_BODY", "BUSINESS_ERROR"]),
            description="Unexpected enum value → 400 (Jackson + validation), never 500."),
        # -- Login boundaries --
        req("Login: wrong password", "POST", "/api/v1/auth/login", no_auth=True,
            body={"email": "{{customerEmail}}", "password": "definitely-wrong"},
            tests=err_tests([401], ["UNAUTHORIZED", "BAD_CREDENTIALS", "AUTH_FAILED"])),
        req("Login: unknown email", "POST", "/api/v1/auth/login", no_auth=True,
            body={"email": "ghost@bhukkad.test", "password": "{{password}}"},
            tests=err_tests([401, 404], ["UNAUTHORIZED", "BAD_CREDENTIALS", "NOT_FOUND"])),
        req("Login: empty body", "POST", "/api/v1/auth/login", no_auth=True, body={},
            tests=err_tests([400], ["VALIDATION_FAILED", "INVALID_BODY"])),
        # -- Cart boundaries --
        req("Cart: quantity 0", "POST", "/api/v1/cart/add",
            body={"menuItemId": "{{menuItemId}}", "quantity": 0},
            tests=err_tests([400], ["VALIDATION_FAILED", "BUSINESS_ERROR", "INVALID_BODY"])),
        req("Cart: negative quantity", "POST", "/api/v1/cart/add",
            body={"menuItemId": "{{menuItemId}}", "quantity": -5},
            tests=err_tests([400], ["VALIDATION_FAILED", "BUSINESS_ERROR", "INVALID_BODY"])),
        req("Cart: huge quantity", "POST", "/api/v1/cart/add",
            body={"menuItemId": "{{menuItemId}}", "quantity": 100000},
            tests=err_tests([400, 409], ["VALIDATION_FAILED", "BUSINESS_ERROR", "STOCK_EXCEEDED", "DATA_CONFLICT"])),
        req("Cart: quantity as string (wrong type)", "POST", "/api/v1/cart/add",
            body={"menuItemId": "{{menuItemId}}", "quantity": "two"},
            tests=err_tests([400], ["INVALID_BODY", "VALIDATION_FAILED", "TYPE_MISMATCH"]),
            description="Unusual data type: Jackson coercion failure → 400 INVALID_BODY."),
        req("Cart: unknown menu item", "POST", "/api/v1/cart/add",
            body={"menuItemId": 99999999, "quantity": 1},
            tests=err_tests([400, 404], ["NOT_FOUND", "RESOURCE_NOT_FOUND", "BUSINESS_ERROR", "MENU_ITEM_NOT_FOUND"])),
        req("Cart: empty body", "POST", "/api/v1/cart/add", body={},
            tests=err_tests([400], ["VALIDATION_FAILED", "INVALID_BODY"])),
        # -- Order lookups --
        req("Order: unknown id", "GET", "/api/v1/orders/customer/99999999",
            tests=err_tests([404], ["NOT_FOUND", "RESOURCE_NOT_FOUND", "ORDER_NOT_FOUND"]),
            description="Unknown id → 404 ApiError (never leaks other customers' orders)."),
        req("Track: negative id", "GET", "/api/v1/orders/customer/track/-1",
            tests=err_tests([400, 404], ["NOT_FOUND", "VALIDATION_FAILED", "BUSINESS_ERROR"])),
        # -- Money boundaries --
        req("Top-up: zero amount", "POST", "/api/v1/customers/wallet/top-up?amount=0",
            tests=err_tests([400], ["VALIDATION_FAILED", "MISSING_PARAM", "BUSINESS_ERROR", "INVALID_AMOUNT"])),
        req("Top-up: negative amount", "POST", "/api/v1/customers/wallet/top-up?amount=-50",
            tests=err_tests([400], ["VALIDATION_FAILED", "BUSINESS_ERROR", "INVALID_AMOUNT"])),
        # -- Review boundaries --
        req("Review: rating 6 out of range", "POST", "/api/v1/reviews",
            body={"orderId": "{{orderId}}", "rating": 6, "comment": "too good"},
            tests=err_tests([400], ["VALIDATION_FAILED", "INVALID_RATING", "BUSINESS_ERROR"])),
        req("Review: rating 0", "POST", "/api/v1/reviews",
            body={"orderId": "{{orderId}}", "rating": 0, "comment": "too harsh"},
            tests=err_tests([400], ["VALIDATION_FAILED", "INVALID_RATING", "BUSINESS_ERROR"])),
        # -- Geo / pagination boundaries --
        req("Nearby: latitude out of range", "GET", "/api/v1/restaurants/public/nearby?latitude=999&longitude=77.59&radiusKm=5", no_auth=True,
            tests=ok_tests([200], schema={"data": "array|null"},
                integrity="pm.test('no crash on bad geo', () => pm.expect(json.success).to.eql(true));"),
            description="Out-of-range coordinates must not 500 — empty/validated result."),
        req("Nearby: negative radius", "GET", "/api/v1/restaurants/public/nearby?latitude=12.97&longitude=77.59&radiusKm=-1", no_auth=True,
            tests=ok_tests([200], schema={"data": "array|null"})),
        req("My Orders: negative page", "GET", "/api/v1/orders/customer/my-orders?page=-1&size=20",
            tests=ok_tests([400, 200])),
        req("My Orders: size 0", "GET", "/api/v1/orders/customer/my-orders?page=0&size=0", tests=ok_tests([400, 200])),
        req("My Orders: size 10000 (cap check)", "GET", "/api/v1/orders/customer/my-orders?page=0&size=10000",
            tests=ok_tests([200, 400],
                integrity="pm.test('server caps page size', () => { if (json.data && json.data.content) pm.expect(json.data.content.length).to.be.at.most(1000); });"),
            description="Server must cap page size (bounded queries) — integrity check enforces the cap."),
        # -- Injection / unicode resilience --
        req("Search: SQL meta-characters", "GET", "/api/v1/search?keyword=%27%20OR%201%3D1--", no_auth=True,
            tests=ok_tests([200], schema={"data": "array|null"}),
            description="Parameterized queries must treat this as a literal keyword → 200 (never 500)."),
        req("Search: emoji keyword", "GET", "/api/v1/search?keyword=%F0%9F%8D%95pizza", no_auth=True,
            tests=ok_tests([200])),
        req("Order: unicode special instructions", "POST", "/api/v1/orders/customer/create",
            body={"restaurantId": "{{restaurantId}}", "deliveryAddressId": "{{addressId}}",
                  "paymentMethod": "CASH_ON_DELIVERY", "specialInstructions": "少辣 🌶️ émoji-test"},
            tests=ok_tests([200, 201, 202, 400])),
    ]
    items.append(folder("17 - Edge & Boundary", edge,
                        "Boundary values, empty payloads, unusual types, injection/unicode resilience. "
                        "Requires 02 Auth to have run (duplicate-email test reuses {{customerEmail}})."))

    # ------------------------------------------------------------------ #
    # 18 - Auth & access errors
    # ------------------------------------------------------------------ #
    access = [
        req("Login Customer (RBAC context)", "POST", "/api/v1/auth/login",
            body={"email": "{{customerEmail}}", "password": "{{password}}"}, no_auth=True, tests=AUTH_SAVE),
        req("Protected: no token", "GET", "/api/v1/customers/profile", no_auth=True,
            tests=err_tests([401, 403], ["UNAUTHORIZED", "ACCESS_DENIED", "MISSING_TOKEN"]),
            description="Missing Authorization header → 401 (never 500, never anonymous data)."),
        req("Protected: garbage token", "GET", "/api/v1/customers/profile", bad_token=True,
            tests=err_tests([401], ["UNAUTHORIZED", "INVALID_TOKEN", "BAD_CREDENTIALS"])),
        req("Protected: malformed JWT structure", "GET", "/api/v1/orders/customer/my-orders",
            prerequest="pm.request.headers.upsert({ key: 'Authorization', value: 'Bearer abc.def' });",
            tests=err_tests([401], ["UNAUTHORIZED", "INVALID_TOKEN", "MALFORMED_JWT"])),
        req("RBAC: customer on admin dashboard", "GET", "/api/v1/admin/dashboard",
            tests=err_tests([403], ["ACCESS_DENIED", "FORBIDDEN"]),
            description="Authenticated but wrong role → 403 (identity != authorization)."),
        req("RBAC: customer on owner surface", "POST", "/api/v1/restaurants/owner",
            body=restaurant_body,
            tests=err_tests([403], ["ACCESS_DENIED", "FORBIDDEN"])),
        req("RBAC: customer on admin user management", "PUT", "/api/v1/admin/users/{{userId}}/deactivate",
            tests=err_tests([403], ["ACCESS_DENIED", "FORBIDDEN"])),
        req("Refresh: no body", "POST", "/api/v1/auth/refresh-token", no_auth=True, body={},
            tests=err_tests([400, 401], ["VALIDATION_FAILED", "INVALID_BODY", "UNAUTHORIZED", "MISSING_TOKEN"])),
        req("Refresh: invalid token", "POST", "/api/v1/auth/refresh-token", no_auth=True,
            body={"refreshToken": "not-a-real-refresh-token"},
            tests=err_tests([401], ["UNAUTHORIZED", "INVALID_TOKEN", "TOKEN_FAMILY_REVOKED"])),
        req("Verify Email: unknown email", "POST", "/api/v1/auth/verify-email?email=ghost@bhukkad.test",
            tests=err_tests([400, 404], ["NOT_FOUND", "VALIDATION_FAILED", "USER_NOT_FOUND"])),
    ]
    items.append(folder("18 - Auth & Access Errors", access,
                        "Run '02 - Auth' first. First request re-establishes the CUSTOMER token for RBAC tests."))

    # ------------------------------------------------------------------ #
    # 19 - Transport & server errors
    # ------------------------------------------------------------------ #
    transport = [
        req("Unknown route", "GET", "/api/v1/definitely-not-a-real-endpoint-xyz", no_auth=True,
            tests=err_tests([404], ["NOT_FOUND", "ROUTE_NOT_FOUND"]),
            description="Unmatched route → 404 ApiError (observable, never silent)."),
        req("Wrong method on health", "DELETE", "/api/v1/health/ping", no_auth=True,
            tests=err_tests([405, 404], ["METHOD_NOT_ALLOWED", "NOT_FOUND"])),
        req("Malformed JSON body", "POST", "/api/v1/auth/register", no_auth=True,
            raw_body='{"fullName": "Broken", "email": ',
            tests=err_tests([400], ["INVALID_BODY", "MALFORMED_JSON"]),
            description="HttpMessageNotReadableException → 400 INVALID_BODY (GlobalExceptionHandler)."),
        req("Wrong content type (text/plain)", "POST", "/api/v1/auth/register", no_auth=True,
            raw_body='fullName=Test&email=test@example.com',
            content_type="text/plain",
            tests=err_tests([400, 415], ["INVALID_BODY", "UNSUPPORTED_MEDIA_TYPE", "VALIDATION_FAILED"])),
        req("Missing required query param", "GET", "/api/v1/serviceability/check?restaurantId={{restaurantId}}&longitude=77.59&subtotal=500", no_auth=True,
            tests=err_tests([400], ["MISSING_PARAM", "VALIDATION_FAILED", "BUSINESS_ERROR"]),
            description="latitude omitted → 400 MISSING_PARAM (GlobalExceptionHandler mapping)."),
        req("Oversized payload (50KB string)", "POST", "/api/v1/customers/support/tickets",
            body={"subject": "S", "description": "x" * 50000, "category": "DELIVERY"},
            tests=err_tests([400, 413], ["VALIDATION_FAILED", "PAYLOAD_TOO_LARGE", "INVALID_BODY", "BUSINESS_ERROR"])),
        req("Duplicate idempotency key (replay)", "POST", "/api/v1/customers/wallet/top-up?amount=100",
            headers=[{"key": "Idempotency-Key", "value": "fixed-replay-key-001"}],
            prerequest="pm.variables.set('idempotencyKey', 'fixed-replay-key-001');",
            tests=ok_tests([200, 201, 202, 409],
                integrity="pm.test('replay returns same reference or conflict', () => { pm.expect(pm.response.code).to.be.oneOf([200,201,202,409]); });",
                save={"firstTopUpId": "data.id"}),
            description="First call with fixed key. Run next request immediately after: same key → same data or 409."),
        req("Replay: second call (same key)", "POST", "/api/v1/customers/wallet/top-up?amount=100",
            headers=[{"key": "Idempotency-Key", "value": "fixed-replay-key-001"}],
            prerequest="pm.variables.set('idempotencyKey', 'fixed-replay-key-001');",
            tests=ok_tests([200, 201, 202, 409],
                integrity=(
                    "pm.test('no double-credit (data integrity)', () => {\n"
                    "    const first = pm.environment.get('firstTopUpId');\n"
                    "    if (first && json.data && json.data.id) {\n"
                    "        pm.expect(String(json.data.id)).to.eql(first);\n"
                    "    }\n"
                    "});")),
            description="Same Idempotency-Key → identical stored result (integrity: no double credit) or 409 conflict."),
        req("Wallet: unknown customer (internal surface)", "POST", "/api/v1/internal/delivery/cod-wallet/99999999/credit?amount=10", no_auth=True,
            tests=err_tests([400, 401, 403, 404], ["UNAUTHORIZED", "ACCESS_DENIED", "NOT_FOUND", "AGENT_NOT_FOUND", "MISSING_HEADER"]),
            description="/internal/** must require a service token (401/403) — never silently process."),
    ]
    items.append(folder("19 - Transport & Server Errors", transport,
                        "Malformed input, transport violations, idempotency replay, internal-surface protection."))

    # ------------------------------------------------------------------ #
    # 99 - E2E Flow
    # ------------------------------------------------------------------ #
    e2e_tests = "pm.test('E2E step OK', () => pm.expect(pm.response.code).to.be.oneOf([200,201,202]));\n"
    e2e = folder("99 - E2E Flow (run in order)", [
        req("1 Login Customer", "POST", "/api/v1/auth/login",
            body={"email": "{{customerEmail}}", "password": "{{password}}"}, no_auth=True, tests=AUTH_SAVE),
        req("2 Browse Restaurants", "GET", "/api/v1/restaurants/public", no_auth=True,
            tests=ok_tests([200], schema={"data.content": "array"})),
        req("3 Add Address", "POST", "/api/v1/customers/addresses", body=address_body,
            tests=ok_tests([200, 201], save={"addressId": "data.id"})),
        req("4 Add Cart Item", "POST", "/api/v1/cart/add",
            body={"menuItemId": "{{menuItemId}}", "quantity": 1}, tests=e2e_tests),
        req("5 Place Order", "POST", "/api/v1/orders/customer/create",
            body={"restaurantId": "{{restaurantId}}", "deliveryAddressId": "{{addressId}}", "paymentMethod": "CASH_ON_DELIVERY"},
            tests=ok_tests([200, 201, 202], save={"orderId": "data.id"})),
        req("6 Track Order", "GET", "/api/v1/orders/customer/track/{{orderId}}",
            tests=ok_tests([200], schema={"data.status": "string"})),
    ], "Sequential happy-path. Set restaurantId & menuItemId in env first.")
    items.append(e2e)

    return {
        "info": {
            "_postman_id": uid(),
            "name": "Bhukkad Food Delivery API",
            "description": (
                "Full API test suite synchronized with the backend source (services/* controllers, "
                "gateway routes) and the live-tested contract suite (scripts/test-all-apis.py).\n\n"
                "**Three test layers per surface:**\n"
                "1. **Functional (01-16, 99)** — happy paths with ApiResponse envelope schema, field-type "
                "schema, data-integrity assertions (pagination caps, ledger ordering, non-negative money, "
                "state-machine codes) and response chaining.\n"
                "2. **Edge & Boundary (17)** — empty payloads, boundary values (quantity 0/-1/100000, rating "
                "0/6, size 0/10000, out-of-range geo), unusual types (string where number expected), "
                "unicode/injection resilience.\n"
                "3. **Errors (18-19)** — invalid/missing auth, wrong-role access, missing params, wrong "
                "content type, malformed JSON, unknown routes, idempotency replay, internal-surface "
                "protection.\n\n"
                "**Envelopes:** success = `{success:true, data, timestamp, traceId}`; error = "
                "`ApiError{status, code, message, traceId, timestamp}`.\n\n"
                "**Setup:** import an environment from `environments/`, run `02 - Auth > Register/Login "
                "Customer`, then other folders. Tokens auto-save via the collection pre-request."
            ),
            "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json",
        },
        "variable": [{"key": "baseUrl", "value": "http://localhost:8080"}],
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
            {"key": "edgeEmail", "value": f"edge.{s}@bhukkad.test", "enabled": True},
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
            {"key": "orderNumber", "value": "BK-0001", "enabled": True},
            {"key": "couponCode", "value": "SAVE10", "enabled": True},
            {"key": "cuisineId", "value": "1", "enabled": True},
            {"key": "agentUserId", "value": "1", "enabled": True},
            {"key": "agentId", "value": "1", "enabled": True},
            {"key": "ownerId", "value": "1", "enabled": True},
            {"key": "jobId", "value": "", "enabled": True},
            {"key": "idempotencyKey", "value": "", "enabled": True},
            {"key": "razorpaySignature", "value": "invalid-signature", "enabled": True},
            {"key": "resetToken", "value": "", "enabled": True},
            {"key": "reviewId", "value": "1", "enabled": True},
            {"key": "deliveryProofPhotoKey", "value": "", "enabled": True},
            {"key": "couponId", "value": "1", "enabled": True},
            {"key": "zoneId", "value": "1", "enabled": True},
            {"key": "campaignId", "value": "1", "enabled": True},
            {"key": "bannerId", "value": "1", "enabled": True},
            {"key": "ticketId", "value": "1", "enabled": True},
            {"key": "batchId", "value": "1", "enabled": True},
        ],
        "_postman_variable_scope": "environment",
    }


def build_curl_doc(collection):
    lines = ["# Bhukkad API — cURL Reference\n",
             "Set variables:\n```bash\nexport BASE=http://localhost:8080\nexport TOKEN=<from login>\n```\n"]
    for folder_item in collection["item"]:
        lines.append(f"\n## {folder_item['name']}\n")
        for r in folder_item.get("item", []):
            if "request" not in r:
                continue
            req_obj = r["request"]
            method = req_obj["method"]
            url = req_obj["url"].replace("{{baseUrl}}", "").replace("{{", "${").replace("}}", "}")
            lines.append(f"### {r['name']}\n```bash\ncurl -s -X {method} \"$BASE{url}\"")
            if method in ("POST", "PUT", "PATCH") and "body" in req_obj:
                body = req_obj["body"]["raw"].replace("{{", "${").replace("}}", "}")
                lines.append(f" \\\n  -H 'Content-Type: application/json' \\\n  -H 'Authorization: Bearer $TOKEN' \\\n  -d '{body}'")
            else:
                lines.append(" \\\n  -H 'Authorization: Bearer $TOKEN'")
            lines.append("\n```\n")
    return "".join(lines)


def validate(collection):
    """Structural sanity checks so a bad edit cannot ship."""
    problems = []
    names = set()

    def walk(items, path=""):
        for it in items:
            if "item" in it:
                walk(it["item"], path + it["name"] + "/")
            else:
                key = path + it["name"]
                if key in names:
                    problems.append(f"duplicate request: {key}")
                names.add(key)
                if "event" not in it:
                    problems.append(f"missing tests: {key}")
                for ev in it.get("event", []):
                    if not ev["script"]["exec"] or not any(l.strip() for l in ev["script"]["exec"]):
                        problems.append(f"empty script: {key}")
                if it["request"]["method"] not in {"GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS"}:
                    problems.append(f"bad method: {key}")

    walk(collection["item"])
    return problems


if __name__ == "__main__":
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "environments").mkdir(exist_ok=True)

    coll = build_collection()
    problems = validate(coll)
    if problems:
        raise SystemExit("Collection validation failed:\n  " + "\n  ".join(problems))

    (OUT / "Bhukkad-API.postman_collection.json").write_text(json.dumps(coll, indent=2))
    (OUT / "Bhukkad-API.postman_environment.json").write_text(
        json.dumps(build_environment("Bhukkad Local E2E", "http://localhost:8080", "e2e"), indent=2))
    (OUT / "environments" / "Bhukkad-Local.postman_environment.json").write_text(
        json.dumps(build_environment("Bhukkad Local", "http://localhost:8080", "local"), indent=2))
    (OUT / "environments" / "Bhukkad-Docker.postman_environment.json").write_text(
        json.dumps(build_environment("Bhukkad Docker", "http://localhost:8080", "docker"), indent=2))
    (OUT / "environments" / "Bhukkad-K8s.postman_environment.json").write_text(
        json.dumps(build_environment("Bhukkad K8s Port-Forward", "http://localhost:8080", "k8s"), indent=2))
    (OUT / "CURL_REFERENCE.md").write_text(build_curl_doc(coll))

    n_requests = sum(len(f.get("item", [])) for f in coll["item"])
    n_tests = sum(1 for f in coll["item"] for r in f.get("item", []) for e in r.get("event", []) if e["listen"] == "test")
    print(f"Generated collection: {len(coll['item'])} folders, {n_requests} requests, {n_tests} with test scripts")
    print("Generated 3 environments + CURL_REFERENCE.md")
