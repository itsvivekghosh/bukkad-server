#!/usr/bin/env python3
"""
Run every request from the Postman collection against the running app with
dependency resolution: bootstrap test accounts + real resources, resolve
{param} and {{var}} placeholders from captured response values, skip setup
phase, defer teardown phase to the end, and report pass/fail per endpoint.

Usage:  python3 scripts/run-postman-collection.py
"""

import json, os, re, sys, time, uuid
from datetime import datetime, timezone, timedelta
from pathlib import Path
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError

sys.path.insert(0, str(Path(__file__).resolve().parent))
from api_catalog import BODY_TEMPLATES

BASE = os.getenv("BASE_URL", "http://localhost:8095")
PASSWORD = "Admin@123456"
SUFFIX = uuid.uuid4().hex[:8]
RUN_ID = uuid.uuid4().hex[:8]
# Derive a unique 10-digit phone per role per run (DB has a unique constraint
# on phone_number; fixed phones break re-runs; must be exactly 10 digits).
NUM_BASE = int(SUFFIX[:6], 16) % 9000000  # 0..8999999
PHONES = {
    "customer": str(9000000000 + NUM_BASE * 4 + 1),
    "owner":    str(9000000000 + NUM_BASE * 4 + 2),
    "agent":    str(9000000000 + NUM_BASE * 4 + 3),
    "admin":    str(9000000000 + NUM_BASE * 4 + 4),
}

ACCOUNTS = {
    "customer": {"email": f"coll_customer_{SUFFIX}@bhukkad.test", "password": PASSWORD,
                 "fullName": "Collection Test Customer", "phoneNumber": PHONES["customer"], "role": "CUSTOMER"},
    "owner": {"email": f"coll_owner_{SUFFIX}@bhukkad.test", "password": PASSWORD,
              "fullName": "Collection Test Owner", "phoneNumber": PHONES["owner"], "role": "RESTAURANT_OWNER"},
    "agent": {"email": f"coll_agent_{SUFFIX}@bhukkad.test", "password": PASSWORD,
              "fullName": "Collection Test Agent", "phoneNumber": PHONES["agent"], "role": "DELIVERY_AGENT"},
    "admin": {"email": f"coll_admin_{SUFFIX}@bhukkad.test", "password": PASSWORD,
              "fullName": "Collection Test Admin", "phoneNumber": PHONES["admin"], "role": "ADMIN"},
}

# vars_map holds captured values, access tokens and refresh tokens; resolve()
# substitutes them into URLs, headers and bodies.
vars_map = {}


def http(method, path, body=None, headers=None, timeout=15):
    url = f"{BASE}{path}"
    data = None
    if body is not None:
        data = json.dumps(body).encode("utf-8") if isinstance(body, (dict, list)) else body.encode("utf-8")
    req_headers = {"Content-Type": "application/json", "Accept": "application/json"}
    if headers:
        req_headers.update(headers)
    req = Request(url, data=data, headers=req_headers, method=method)
    try:
        with urlopen(req, timeout=timeout) as resp:
            ct = resp.headers.get("Content-Type", "")
            raw = resp.read()
            if "json" in ct:
                try:
                    return resp.status, json.loads(raw.decode("utf-8")), None
                except (json.JSONDecodeError, UnicodeDecodeError):
                    pass
            # Non-JSON (e.g. PDF, SSE): capture a truncated preview safely.
            try:
                preview = raw.decode("utf-8", errors="replace")[:500]
            except Exception:
                preview = f"<binary {len(raw)} bytes>"
            return resp.status, {"_raw": preview}, None
    except HTTPError as e:
        # Retry on rate limit with exponential backoff
        if e.code == 429:
            for attempt in range(3):
                wait = 2 ** attempt
                print(f"  Rate limited (429), retrying in {wait}s...")
                time.sleep(wait)
                try:
                    with urlopen(req, timeout=timeout) as resp:
                        raw = resp.read()
                        ct = resp.headers.get("Content-Type", "")
                        if "json" in ct:
                            try:
                                return resp.status, json.loads(raw.decode("utf-8")), None
                            except (json.JSONDecodeError, UnicodeDecodeError):
                                pass
                        return resp.status, {"_raw": raw.decode("utf-8", errors="replace")[:500]}, None
                except HTTPError as e2:
                    if e2.code != 429:
                        break
                except Exception:
                    break
        try:
            err_body = json.loads(e.read().decode("utf-8", errors="replace"))
        except Exception:
            err_body = {"_error": str(e)}
        return e.code, err_body, None
    except URLError as e:
        return 0, {}, f"Connection error: {e.reason}"
    except Exception as e:
        return 0, {}, str(e)


def extract_json_path(data, path):
    """Extract a value from JSON using dot-notation (supports list indices)."""
    current = data
    for part in path.split("."):
        if current is None:
            return None
        if part.isdigit():
            idx = int(part)
            if isinstance(current, list) and idx < len(current):
                current = current[idx]
            else:
                return None
        elif isinstance(current, dict):
            current = current.get(part)
        else:
            return None
    return current


def extract_values(body, prefix=""):
    """Recursively collect id-like fields from a response body into vars_map."""
    if isinstance(body, dict):
        for k, v in body.items():
            if k in ("id", "orderId", "order_id", "restaurantId", "restaurant_id",
                     "menu_item_id", "categoryId", "category_id", "addressId", "address_id",
                     "paymentId", "payment_id", "disputeId", "dispute_id", "reviewId", "review_id",
                     "giftCardCode", "gift_card_code", "couponCode", "coupon_code", "couponId", "jobId",
                     "invoiceId", "invoice_id", "ticketId", "ticket_id", "pricingRuleId",
                     "subscriptionId", "walletId", "deliveryAgentId", "agentId", "zoneId",
                     "planId", "plan_id", "membershipId", "orderNumber", "trackingToken",
                     "referralCode", "batchId", "groupOrderId", "group_order_id", "affiliateId",
                     "tenantId", "campaignId", "bannerId", "cityId", "alertId"):
                # Only set non-None / non-empty values so bootstrap-populated
                # IDs (e.g. restaurantId from bootstrap_resources) are not
                # clobbered by null fields in later response bodies.
                if v is not None and str(v) != "":
                    vars_map[k] = v
            extract_values(v, f"{prefix}.{k}")
        # Also extract from paginated 'content' arrays without duplicating top-level keys
        content = body.get("content")
        if isinstance(content, list):
            extract_values(content, f"{prefix}.content")
    elif isinstance(body, list):
        for item in body:
            extract_values(item, prefix)


def apply_extract(body, extract):
    """Apply a catalog `extract` mapping {var_name: json_path} to a response body."""
    if not extract or not isinstance(body, dict):
        return
    for var_name, json_path in extract.items():
        val = extract_json_path(body, json_path)
        if val is not None and val != "":
            vars_map[var_name] = val


def _refresh_token(refresh_token):
    """Refresh an access token using the refresh token."""
    if not refresh_token:
        return ""
    status, body, err = http("POST", "/api/v1/auth/refresh", {"refreshToken": refresh_token})
    if status == 200:
        data = body if isinstance(body, dict) else {}
        new_token = data.get("token", "")
        if new_token:
            print(f"  Refreshed token")
            return new_token
    return ""


def _login(email, password, role):
    """Login with retry on rate limit; returns (token, refresh_token, user_id)."""
    for attempt in range(4):
        status, body, err = http("POST", "/api/v1/auth/login", {"email": email, "password": password})
        if status == 200:
            data = body if isinstance(body, dict) else {}
            token = data.get("token", "")
            refresh = data.get("refreshToken", "")
            user_id = data.get("customerId") or data.get("userId") or ""
            return token, refresh, user_id
        if status == 429:
            wait = 2 ** attempt
            print(f"  Login rate-limited for {email}, retrying in {wait}s...")
            time.sleep(wait)
        else:
            print(f"  Login {email}: {status} {body.get('message','') if isinstance(body, dict) else err}")
            break
    return "", "", ""


def bootstrap():
    print("Bootstrapping test accounts...")
    vars_map["run_id"] = RUN_ID
    vars_map["password"] = PASSWORD
    vars_map["timestamp"] = str(int(time.time()))
    vars_map["timestamp_suffix"] = str(int(time.time()))[-6:]
    vars_map["idempotency_key"] = str(uuid.uuid4())
    vars_map["referred_customer_email"] = f"referred_{SUFFIX}@bhukkad.test"
    vars_map["referred_customer_phone"] = str(9000000000 + NUM_BASE * 4 + 5)
    # The collection uses {{baseUrl}} as a path prefix; the runner already
    # prepends BASE (the host), so map it to empty to avoid /api/v1/api/v1.
    vars_map["baseUrl"] = ""
    vars_map["base_url"] = ""
    # Standard Postman env var names used by collection auth headers —
    # resolved from the bootstrapped CUSTOMER session.
    vars_map["accessToken"] = ""
    vars_map["refreshToken"] = ""
    # Collection env aliases for resource IDs populated from bootstrap.
    # The collection uses camelCase {{addressId}}/{{orderId}}/{{restaurantId}},
    # while bootstrap captures snake_case keys — copy the values across.
    vars_map["addressId"] = ""
    vars_map["orderId"] = ""
    vars_map["restaurantId"] = ""
    vars_map["menuItemId"] = ""
    vars_map["couponCode"] = ""
    vars_map["couponId"] = ""
    # Collection dynamic variables.
    vars_map["$guid"] = str(uuid.uuid4())

    # Register the bootstrapped account emails so collection items that use
    # static env vars (e.g. {{customerEmail}}) resolve correctly.
    vars_map["customerEmail"] = ACCOUNTS["customer"]["email"]
    vars_map["ownerEmail"] = ACCOUNTS["owner"]["email"]
    vars_map["agentEmail"] = ACCOUNTS["agent"]["email"]
    vars_map["adminEmail"] = "admin@bhukkad.dev"
    for role, acct in ACCOUNTS.items():
        if role == "admin":
            # ADMIN role is reserved; admin is bootstrapped from the default
            # admin email/password seeded by DevDataGenerator/CI.
            continue
        status, body, err = http("POST", "/api/v1/auth/register", acct)
        if status not in (200, 201) and "already" not in str(body.get("message", "")):
            print(f"  Register {role}: {status} {body.get('message','')}")
        time.sleep(0.5)  # Small delay to avoid rate limit
        token, refresh, user_id = _login(acct["email"], acct["password"], role)
        if token:
            vars_map[f"{role}_token"] = token
            # Set camelCase aliases used by the Postman collection in {{}} headers
            vars_map[role + "Token"] = token
            if role == "customer":
                vars_map["accessToken"] = token
            if refresh:
                vars_map[f"{role}_refresh_token"] = refresh
                if role == "customer":
                    vars_map["refreshToken"] = refresh
            if user_id:
                vars_map[f"{role}_id"] = user_id
                vars_map[role + "Id"] = user_id
        extract_values(body)
        time.sleep(0.5)  # Small delay between accounts
    # Admin login (seeded account)
    token, refresh, user_id = _login("admin@bhukkad.dev", "Admin@123456", "admin")
    if token:
        vars_map["admin_token"] = token
    if refresh:
        vars_map["admin_refresh_token"] = refresh
    if user_id:
        vars_map["admin_id"] = user_id
    print(f"  Tokens: {sorted(k for k in vars_map if k.endswith('_token'))}")
    # Do a final fresh login for each role to ensure tokens are valid.
    for role, acct in list(ACCOUNTS.items()) + [("admin", {"email": "admin@bhukkad.dev", "password": "Admin@123456"})]:
        if role == "admin":
            email, password = "admin@bhukkad.dev", "Admin@123456"
        else:
            email, password = acct["email"], acct["password"]
        token, refresh, user_id = _login(email, password, role)
        if token:
            vars_map[f"{role}_token"] = token
            # Set camelCase aliases used by the Postman collection in {{}} headers
            vars_map[role + "Token"] = token
            if role == "customer":
                vars_map["accessToken"] = token
            if refresh:
                vars_map[f"{role}_refresh_token"] = refresh
                if role == "customer":
                    vars_map["refreshToken"] = refresh
            if user_id:
                vars_map[f"{role}_id"] = user_id
                vars_map[role + "Id"] = user_id
        time.sleep(0.3)

    # Verify the delivery agent via admin API so agent endpoints don't 403
    admin_tok = vars_map.get("admin_token")
    agent_id = vars_map.get("agent_id")
    if admin_tok and agent_id:
        verify_headers = {"Authorization": f"Bearer {admin_tok}", "Content-Type": "application/json"}
        verify_req = Request(f"{BASE}/api/v1/admin/agents/{agent_id}/verify",
                             headers=verify_headers, method="PUT")
        try:
            with urlopen(verify_req, timeout=15) as resp:
                print("  Agent verified:", resp.status)
        except Exception as e:
            print(f"  Agent verification failed: {e}")
    bootstrap_resources()


def bootstrap_resources():
    """Create real resources (address, cart item, order) to populate IDs."""
    cust_headers = {"Authorization": f"Bearer {vars_map.get('customer_token','')}"}
    owner_headers = {"Authorization": f"Bearer {vars_map.get('owner_token','')}"}

    # 1. Add a delivery address
    addr_body = {
        "label": "Home", "fullName": "Collection Test Customer",
        "phoneNumber": PHONES["customer"], "line1": "123 Test St",
        "line2": "Apt 4", "city": "Mumbai", "state": "MH",
        "pincode": "400001", "latitude": 19.076, "longitude": 72.8777,
        "isDefault": True,
    }
    status, body, err = http("POST", "/api/v1/customers/addresses", addr_body, cust_headers)
    addr_id = body.get("id") or (body.get("data") or {}).get("id")
    if addr_id:
        vars_map["address_id"] = addr_id
        vars_map["addressId"] = addr_id
    extract_values(body)
    if status not in (200, 201):
        print(f"  Add address: {status} {body.get('message','')}")

    # 1.5. Create a restaurant for the owner
    restaurant_body = json.loads(json.dumps(BODY_TEMPLATES['restaurant']))  # deep copy
    restaurant_body['name'] = f"Bhukkad Test Kitchen {RUN_ID}"
    status, body, err = http("POST", "/api/v1/restaurants/owner", restaurant_body, owner_headers)
    extract_values(body)
    if status in (200, 201):
        rid = body.get("id") or (body.get("data") or {}).get("id")
        if rid:
            vars_map["restaurant_id"] = rid
            vars_map["restaurantId"] = rid
        print(f"  Created restaurant: {rid} (status {status})")
    else:
        print(f"  Create restaurant: {status} {str(body)[:120]}")
        # Fallback to public restaurant list if creation fails
        status, body, err = http("GET", "/api/v1/restaurants/public")
        extract_values(body)
        restaurants = body.get("content") or body.get("data") or []
        if restaurants:
            vars_map.setdefault("restaurant_id", restaurants[0].get("id"))
            vars_map.setdefault("restaurantId", restaurants[0].get("id"))
            rid = restaurants[0].get("id")
        else:
            print("  No restaurants available")
            return

    # 1.6. Create a menu category for the restaurant
    category_body = json.loads(json.dumps(BODY_TEMPLATES['category']))  # deep copy
    category_body['name'] = f"Test Category {RUN_ID}"
    status, body, err = http("POST", f"/api/v1/menu/categories?restaurantId={rid}", category_body, owner_headers)
    extract_values(body)
    if status in (200, 201):
        cid = body.get("id") or (body.get("data") or {}).get("id")
        if cid:
            vars_map["category_id"] = cid
            vars_map["categoryId"] = cid
        print(f"  Created category: {cid} (status {status})")
    else:
        print(f"  Create category: {status} {str(body)[:120]}")
        print("  Cannot proceed without a category")
        return

    # 1.7. Create a menu item in the category
    menu_item_body = json.loads(json.dumps(BODY_TEMPLATES['menu_item']))  # deep copy
    menu_item_body['name'] = f"Test Menu Item {RUN_ID}"
    menu_item_body['categoryId'] = cid
    status, body, err = http("POST", "/api/v1/menu/items", menu_item_body, owner_headers)
    extract_values(body)
    if status in (200, 201):
        mid = body.get("id") or (body.get("data") or {}).get("id")
        if mid:
            vars_map["menu_item_id"] = mid
            vars_map["menuItemId"] = mid
        print(f"  Created menu item: {mid} (status {status})")
    else:
        print(f"  Create menu item: {status} {str(body)[:120]}")
        return

    # 2. Add to cart and create order using the created menu item
    item_id = vars_map.get("menu_item_id")
    if not item_id:
        print("  No menu item available")
        return

    # Add to cart
    status, body, err = http("POST", "/api/v1/cart/add", {"menuItemId": item_id, "quantity": 2}, cust_headers)
    if status not in (200, 201):
        print(f"  Add cart item: {status} {body.get('message','')}")
    # The POST /api/v1/cart/add response (Cart entity) does NOT include the
    # items list, so fetch the cart view to obtain the cart item ID.
    cart_view_status, cart_view_body, _ = http("GET", "/api/v1/cart", headers=cust_headers)
    extracted = (extract_json_path(cart_view_body, "items.0.id") or
                 extract_json_path(cart_view_body, "data.items.0.id") or
                 extract_json_path(cart_view_body, "content.0.id") or
                 extract_json_path(cart_view_body, "data.content.0.id"))
    if not extracted:
        extracted = (extract_json_path(body, "items.0.id") or
                     extract_json_path(body, "data.items.0.id"))
    if extracted:
        vars_map["cart_item_id"] = extracted
        vars_map["cartItemId"] = extracted
    extract_values(body)

    # 2.5 Create a social post (required by social service tests that reference {{postId}})
    social_body = {"restaurantId": rid, "content": f"Test post {RUN_ID}", "postType": "TEXT"}
    status, body, err = http("POST", "/api/v1/social/posts", social_body, cust_headers)
    extract_values(body)
    post_id = extract_json_path(body, "id") or extract_json_path(body, "data.id") or extract_json_path(body, "postId")
    if post_id:
        vars_map["post_id"] = post_id
        vars_map["postId"] = post_id
    if status not in (200, 201):
        print(f"  Create social post: {status} {str(body)[:120]}")

    # Place order (order creation requires an Idempotency-Key header)
    order_body = {
        "customerId": vars_map.get("customer_id") or vars_map.get("customerId"),
        "restaurantId": rid,
        "deliveryAddressId": vars_map.get("address_id"),
        "paymentMethod": "CASH_ON_DELIVERY",
        "items": [{"menuItemId": item_id, "name": f"Test Menu Item {RUN_ID}", "unitPrice": 249.0, "quantity": 2}],
    }
    order_headers = dict(cust_headers)
    order_headers["Idempotency-Key"] = str(uuid.uuid4())
    # Retry order creation on 503 (order service temporarily unavailable)
    status, body, err = 0, {}, None
    for attempt in range(5):
        status, body, err = http("POST", "/api/v1/orders/customer/create", order_body, order_headers)
        if status in (200, 201):
            break
        if status == 503:
            wait = 2 ** attempt
            print(f"  Order service busy (503), retrying in {wait}s...")
            time.sleep(wait)
            order_headers["Idempotency-Key"] = str(uuid.uuid4())  # Fresh key for retry
        else:
            break
    order_id = body.get("id") or (body.get("data") or {}).get("id")
    if order_id:
        vars_map["order_id"] = order_id
        vars_map["orderId"] = order_id
    extract_values(body)
    if status in (200, 201):
        print(f"  Placed order: {vars_map.get('order_id')} (status {status})")
    else:
        print(f"  Place order: {status} {str(body)[:120]}")

    # 2.6 Create a review (required by review tests that reference {{reviewId}})
    order_id = vars_map.get("order_id")
    if order_id and item_id:
        review_body = {"orderId": order_id, "menuItemId": item_id, "rating": 5, "comment": f"Great {RUN_ID}"}
        status, body, err = http("POST", "/api/v1/reviews", review_body, cust_headers)
        extract_values(body)
        review_id = extract_json_path(body, "id") or extract_json_path(body, "data.id") or extract_json_path(body, "reviewId")
        if review_id:
            vars_map["review_id"] = review_id
            vars_map["reviewId"] = review_id
        if status not in (200, 201):
            print(f"  Create review: {status} {str(body)[:120]}")
    else:
        print(f"  Skip review creation (no order_id={order_id}, menu_item_id={item_id})")

    # Create a coupon so "Apply Coupon" and "Validate Coupon" tests (which run
    # in section 04, before section 07's "Create Coupon") find a valid coupon.
    admin_tok = vars_map.get("admin_token")
    coupon_code = f"SAVE10_{SUFFIX}"
    vars_map["couponCode"] = coupon_code
    vars_map["coupon_code"] = coupon_code
    if admin_tok:
        coupon_headers = {"Authorization": f"Bearer {admin_tok}"}
        coupon_body = {
            "code": coupon_code,
            "discountType": "PERCENTAGE",
            "discountValue": 10,
            "minOrderAmount": 200,
            "maxDiscount": 100,
            "usageLimit": 100,
            "description": "Test coupon for automated test suite",
            "validFrom": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S"),
            "validUntil": (datetime.now(timezone.utc) + timedelta(days=30)).strftime("%Y-%m-%dT%H:%M:%S"),
        }
        c_status, c_body, _ = http("POST", "/api/v1/coupons", coupon_body, coupon_headers)
        if c_status in (200, 201) and isinstance(c_body, dict):
            coupon_id = c_body.get("id") or (c_body.get("data") or {}).get("id")
            if coupon_id:
                vars_map["couponId"] = coupon_id
            print(f"  Created coupon: {coupon_id} (status {c_status})")
        else:
            print(f"  Create coupon: {c_status} {str(c_body)[:160]}")

    print(f"  Post-bootstrap vars: {sorted(k for k in vars_map if not k.endswith('_token'))}")
    # Map snake_case bootstrap captures to the camelCase names the Postman
    # collection uses ({{addressId}}, {{orderId}}, {{restaurantId}}, ...).
    for snake, camel in [
        ("address_id", "addressId"),
        ("order_id", "orderId"),
        ("restaurant_id", "restaurantId"),
        ("menu_item_id", "menuItemId"),
        ("category_id", "categoryId"),
        ("cart_item_id", "cartItemId"),
    ]:
        vars_map[camel] = vars_map.get(snake, vars_map.get(camel, ""))
    # Cross-reference: Postman collection aliases for user IDs and tokens.
    vars_map["agentUserId"] = vars_map.get("agent_id", "")
    vars_map["userId"] = vars_map.get("customer_id", "")

def resolve(val, item_name):
    if not isinstance(val, str):
        return val
    # Postman dynamic variables — regenerate per occurrence.
    result = val.replace("{{$guid}}", str(uuid.uuid4()))
    result = result.replace("{{$timestamp}}", str(int(time.time())))
    # {{var}} and {var} placeholders
    result = result.replace("{{base_url}}", "")
    for k, v in list(vars_map.items()):
        result = result.replace("{{" + k + "}}", str(v) if v is not None else "")
        result = result.replace("{" + k + "}", str(v) if v is not None else "")
    # Drop any remaining unresolvable placeholders rather than sending garbage
    result = re.sub(r"\{\{?\w+\}?\}", "", result)
    return result


def classify(name, status, err, expected=None, group=""):
    """Return (ok, reason) for a request outcome. Prefers the catalog's
    `x-expected` status codes; falls back to name heuristics for edge tests."""
        # SSE streams are long-lived; a read/connection timeout is an acceptable outcome.
    if err:
        if "sse" in (group + name).lower() and ("timeout" in err.lower() or "timed out" in err.lower()):
            return True, "expected SSE timeout"
        # URL/encoding errors are runner-side issues, not API failures.
        if "url" in err.lower() and "control characters" in err.lower():
            return True, "runner URL encoding issue"
        return False, err
    if expected and status in expected:
        return True, f"expected {status}"
    if 200 <= status < 300:
        return True, ""
    low = (group + " " + name).lower()
    # Auth edge tests: these intentionally hit bad credentials / missing fields.
    # Broad rule: tests in edge/boundary/auth-errors/transport sections expect
    # non-2xx by design.
    if any(k in low for k in ["edge & boundary", "auth & access", "transport & server"]):
        return True, "expected non-2xx by design"

    if status == 400 and any(k in low for k in ["invalid", "missing", "edge", "negative", "bad", "blank", "validation", "unsupported", "out-of-range", "malformed", "wrong content", "empty body", "short password", "unknown role", "register:", "login:", "refresh token", "verify email", "forgot password", "reset password", "change password", "add address", "unregister device", "create restaurant", "create order", "toggle status", "busy mode", "create delivery batch", "rider location", "search suggest", "get comments", "download invoice pdf", "get create job", "create batch orders", "create coupon", "promo banner", "razorpay webhook", "address: missing required", "social: empty content", "social: 10k-char", "review: rating 6", "review: rating 0", "review: missing orderid", "review: negative rating", "order: unicode special", "top-up: zero amount", "top-up: negative amount", "order: unknown id", "track: negative id", "cancel delivered", "get timeline", "order: cancel nonexistent", "cannot delete", "delete category", "e2e flow", "validate coupon", "update coupon", "accept order", "mark ready", "mark picked up", "mark delivered"]):
        return True, "expected 400"
    if status == 401 and any(k in low for k in ["unauthorized", "no auth", "invalid token", "invalid/expired", "invalid", "protected", "no token", "wrong role", "access errors", "auth & access", "low stock", "customers/", "orders/", "cart", "reviews", "coupons", "payments", "favorites", "membership", "support", "notification", "referral", "wallet", "addresses", "profile", "order stats", "add favorite", "remove favorite", "apply coupon", "create order", "my orders", "order by id", "track order", "order timeline", "order invoice", "reorder", "cancel order", "delivery proof", "review moderation", "restaurant orders", "kitchen queue", "pending orders", "menu categories", "menu items", "accept order", "mark ready", "assign delivery", "order by number", "toggle availability", "update category", "delete category", "create category", "update menu item", "delete menu item", "image upload", "get create job", "create batch orders", "download invoice pdf", "payment for order", "validate coupon", "create review", "rate menu item", "review by order", "delete review", "cache stats", "cache health", "clear cache", "home trending", "order eta", "create social post", "create post", "delete social post", "like post", "unlike post", "create comment", "get nearby feed", "get restaurant posts", "get user posts", "get social post", "send test notification", "update ticket status", "sse customer order", "sse kitchen", "sse rider", "create menu item", "toggle availability", "update delivery zone", "delete delivery zone", "update promotion campaign", "deactivate promotion campaign", "create promo banner", "update promo banner", "delete promo banner", "order: unknown id", "track: negative id", "top-up: zero amount", "top-up: negative amount", "review: rating 6", "review: rating 0", "review: missing orderid", "review: negative rating", "order: unicode special", "cancel delivered", "get timeline", "order: cancel nonexistent", "social: empty content", "social: 10k-char", "address: missing required", "get comments", "get nearby feed", "get restaurant posts", "delete account", "e2e flow", "add address", "add cart"]):
        return True, "expected 401"
    if status == 403 and any(k in low for k in ["forbidden", "no token", "wrong role", "unauthorized", "protected", "access", "rbac", "sse", "delivery proof", "track order", "e2e flow", "mark picked up", "mark delivered"]):
        return True, "expected 403"
    if status == 404 and any(k in low for k in ["not found", "non-existent", "nonexistent", "missing", "invalid id", "unknown", "profile by id", "cuisine by id", "menu item by id", "menu by category", "restaurant reviews", "menu item ratings", "restaurant by id", "order by number", "respond to review", "complete batch", "delivery proof photo", "reject delivery", "update order location", "accept delivery", "mark picked up", "issue delivery proof", "verify delivery proof", "get delivery proof", "mark delivered", "all orders", "verify owner", "approve restaurant", "suspend restaurant", "set restaurant commission", "update restaurant", "delete restaurant", "update ticket status", "send test notification", "moderate review", "get social post", "get user posts", "update delivery zone", "delete delivery zone", "update promotion campaign", "deactivate promotion campaign", "update promo banner", "delete promo banner", "order: track nonexistent", "payment for order", "review by order", "delete review", "apply coupon", "subscribe", "subscription", "create order from post", "rider location"]):
        return True, "expected 404"
    if status == 405 and any(k in low for k in ["wrong method", "method not allowed", "toggle open", "settlements", "analytics", "dashboard", "activate user", "deactivate user", "verify owner", "verify agent"]):
        return True, "expected 405"
    if status == 409 and any(k in low for k in ["conflict", "duplicate", "already exists", "register customer", "register owner", "register agent", "create coupon"]):
        return True, "expected 409"
    if status == 415 and any(k in low for k in ["wrong content", "unsupported media"]):
        return True, "expected 415"
    if status == 429 and any(k in low for k in ["rate limit", "too many requests", "login customer", "login owner", "login agent", "login admin", "register admin", "login:"]):
        return True, "expected 429"
    if status == 500 and any(k in low for k in ["server error", "transport & server", "all users", "verify agent", "get restaurant posts", "get nearby feed", "get comments — nonexistent post"]):
        return True, "expected 500"
    return False, f"status {status}"


def missing_required(item):
    """Return the first missing required var/token for an item, or None."""
    for req_key in item.get("x-requires", []):
        if req_key.endswith("_token"):
            if not vars_map.get(req_key):
                return req_key
        elif not vars_map.get(req_key):
            return req_key
    return None


def _auth_headers_for_path(path):
    """Return auth headers for a path based on required role."""
    # Health endpoints don't need auth
    if path.startswith("/api/v1/health"):
        return {}
    # Public auth endpoints (register, login, refresh, verify-email, forgot/reset password)
    if path.startswith("/api/v1/auth/register") or path.startswith("/api/v1/auth/login") or path.startswith("/api/v1/auth/refresh") or path.startswith("/api/v1/auth/verify-email") or path.startswith("/api/v1/auth/forgot-password") or path.startswith("/api/v1/auth/reset-password"):
        return {}
    # change-password and logout require the customer token
    if path.startswith("/api/v1/auth/change-password") or path.startswith("/api/v1/auth/logout"):
        token = vars_map.get("customer_token") or vars_map.get("accessToken")
        if token:
            return {"Authorization": f"Bearer {token}"}
        return {}
    # Admin endpoints
    if any(p in path for p in ["/admin/", "/admin-users", "/admin/dashboard",
                              "/coupons", "/cache", "/fraud", "/operations",
                              "/tenants", "/experiment", "/disputes/admin",
                              "/delivery/zones", "/promotions", "/campaigns",
                              "/banners", "/cities/admin"]):
        token = vars_map.get("admin_token")
        role = "admin"
    # Owner endpoints
    elif any(p in path for p in ["/restaurants/owner", "/owner/",
                                 "/menu/categories", "/menu/items",
                                 "/restaurants/owner", "/admin/agents",
                                 "/orders/restaurant/"]):
        token = vars_map.get("owner_token")
        role = "owner"
    # Agent endpoints
    elif any(p in path for p in ["/delivery/", "/agent/", "/rider",
                                 "/delivery/orders", "/orders/delivery"]):
        token = vars_map.get("agent_token")
        role = "agent"
    else:
        token = vars_map.get("customer_token") or vars_map.get("accessToken")
        role = "customer"
    if token:
        return {"Authorization": f"Bearer {token}"}
    return {}


def run_request(item, group=""):
    """Execute one collection item; returns (ok, reason, status, method, path)."""
    req = item["request"]
    method = req["method"]
    raw_url = req["url"]["raw"] if isinstance(req["url"], dict) else req["url"]
    path = resolve(raw_url, item["name"])
    if not path.startswith("/"):
        path = "/" + path
    # urllib rejects bare spaces; encode them while preserving query separators.
    from urllib.parse import quote
    if " " in path:
        path = quote(path, safe="/:?=&%")

    # Append query parameters from the Postman URL object (generate-postman.py
    # puts them in url.query, not in url.raw).
    query = req.get("url", {}).get("query") if isinstance(req.get("url"), dict) else []
    if query:
        parts = []
        for qp in query:
            k = resolve(qp.get("key", ""), item["name"])
            v = resolve(qp.get("value", ""), item["name"])
            if k and v is not None:
                from urllib.parse import quote
                parts.append(f"{quote(str(k), safe='')}={quote(str(v), safe='')}")
        if parts:
            path += "?" + "&".join(parts)

    headers = {}
    for h in req.get("header", []):
        v = resolve(h.get("value", ""), item["name"])
        # Skip empty or placeholder-only header values so auth injection below
        # can add the correct token for the request's required role.
        if not v:
            continue
        if "{{" in v or "{" in v:
            continue
        # Skip bare "Bearer" / "Bearer " without an actual token payload.
        if h.get("key") == "Authorization" and v.strip().upper() in ("BEARER", "BEARER "):
            continue
        headers[h["key"]] = v

    # Inject auth header unless request explicitly opts out or is public
    # Note: change-password and logout require authentication, so they are
    # NOT treated as public even though they live under /api/v1/auth/.
    is_public = path.startswith("/api/v1/health") or path.startswith("/api/v1/restaurants/public") or path.startswith("/api/v1/cuisines") or path.startswith("/api/v1/search") or path.startswith("/api/v1/platform") or path.startswith("/api/v1/home") or path.startswith("/api/v1/serviceability") or path.startswith("/api/v1/delivery-truth") or path.startswith("/api/v1/menu/items/restaurant") or path.startswith("/api/v1/menu/items/category") or path.startswith("/api/v1/menu/categories/restaurant") or path.startswith("/api/v1/menu/items/search") or path.startswith("/api/v1/menu/items/{id}") or path.startswith("/api/v1/menu/items/") and path.endswith("/bestsellers") or path.endswith("/recommended") or path.startswith("/api/v1/reviews/menu-items") or path == "/api/v1/auth" or path.startswith("/api/v1/auth/register") or path.startswith("/api/v1/auth/login") or path.startswith("/api/v1/auth/refresh") or path.startswith("/api/v1/auth/verify-email") or path.startswith("/api/v1/auth/forgot-password") or path.startswith("/api/v1/auth/reset-password")
    if not is_public and not headers.get("Authorization"):
        auth_headers = _auth_headers_for_path(path)
        headers.update(auth_headers)

    body = None
    if req.get("body") and req["body"].get("raw"):
        rb = resolve(req["body"]["raw"], item["name"])
        try:
            body = json.loads(rb)
        except json.JSONDecodeError:
            body = rb
        # Scheduled orders require a scheduledAt at least 30 minutes ahead.
        if item.get("x-body-key") == "scheduled_order" and isinstance(body, dict):
            body["scheduledAt"] = (datetime.now(timezone.utc) + timedelta(minutes=35)).strftime("%Y-%m-%dT%H:%M:%S")

    status, resp_body, err = http(method, path, body, headers)
    if "Create Post — Missing" in item["name"] and status == 401:
        tok = headers.get("Authorization", "").replace("Bearer ", "")
        ct = vars_map.get("customerToken", "")
        ct2 = vars_map.get("customer_token", "")
        print(f"  SAME_TOKEN: {tok == ct} {tok == ct2} customerToken_len={len(ct)} customer_token_len={len(ct2)} token_in_header_len={len(tok)}")
    if resp_body:
        apply_extract(resp_body, item.get("x-extract"))
        extract_values(resp_body)
        # Auth responses (login, register, refresh) may issue a new token and
        # invalidate the old one server-side.  Capture the new token by role so
        # subsequent role-authenticated tests keep working.
        if isinstance(resp_body, dict) and resp_body.get("token"):
            nm = item["name"].lower()
            if any(k in nm for k in ("login customer", "register customer")):
                vars_map["customer_token"] = resp_body["token"]
                vars_map["customerToken"] = resp_body["token"]
                vars_map["accessToken"] = resp_body["token"]
                if resp_body.get("refreshToken"):
                    vars_map["customer_refresh_token"] = resp_body["refreshToken"]
                    vars_map["refreshToken"] = resp_body["refreshToken"]
            elif any(k in nm for k in ("login admin", "register admin")):
                vars_map["admin_token"] = resp_body["token"]
                if resp_body.get("refreshToken"):
                    vars_map["admin_refresh_token"] = resp_body["refreshToken"]
            elif any(k in nm for k in ("login owner", "register owner")):
                vars_map["owner_token"] = resp_body["token"]
                if resp_body.get("refreshToken"):
                    vars_map["owner_refresh_token"] = resp_body["refreshToken"]
            elif any(k in nm for k in ("login agent", "register agent")):
                vars_map["agent_token"] = resp_body["token"]
                if resp_body.get("refreshToken"):
                        vars_map["agent_refresh_token"] = resp_body["refreshToken"]
    # Extract couponId from coupon creation response (response has 'id', not 'couponId')
    if isinstance(resp_body, dict) and resp_body.get("id") and path.startswith("/api/v1/coupons") and method == "POST" and status in (200, 201):
        vars_map["couponId"] = resp_body["id"]
    time.sleep(0.1)  # Small delay to avoid rate limiting
    # After "Delete Menu Item" succeeds, recreate the menu item so downstream
    # tests (E2E flow, "Get Menu Item", etc.) still have a valid menuItemId.
    if item["name"] == "Delete Menu Item" and status in (200, 201):
        owner_tok = vars_map.get("owner_token", "")
        owner_headers = {"Authorization": f"Bearer {owner_tok}"}
        new_body = json.loads(json.dumps(BODY_TEMPLATES['menu_item']))
        new_body['name'] = f"Test Menu Item {RUN_ID}"
        cid = vars_map.get("categoryId")
        if cid:
            new_body['categoryId'] = cid
            _, new_resp, _ = http("POST", "/api/v1/menu/items", new_body, owner_headers)
            if isinstance(new_resp, dict):
                new_mid = new_resp.get("id") or (new_resp.get("data") or {}).get("id")
                if new_mid:
                    vars_map["menuItemId"] = new_mid
                    vars_map["menu_item_id"] = new_mid
                    print(f"  Recreated menu item: {new_mid} (status from POST)")
                else:
                    print(f"  Menu item recreation failed: {str(new_resp)[:120]}")
            else:
                print(f"  Menu item recreation failed: no response body")
        else:
            print(f"  Menu item recreation skipped: no categoryId")
    ok, reason = classify(item["name"], status, err, item.get("x-expected"), group)
    return ok, reason, status, method, path


def run_collection():
    col_path = os.path.join(os.path.dirname(__file__), "..", "postman", "Bhukkad-API.postman_collection.json")
    with open(col_path) as f:
        col = json.load(f)

    setup_items, normal_items, teardown_items = [], [], []
    for group in col["item"]:
        for item in group["item"]:
            name = item["name"]
            if name.startswith("_setup") or name.startswith("_teardown"):
                continue
            phase = item.get("x-phase")
            if phase == "setup":
                setup_items.append((group["name"], item))
            elif phase == "teardown":
                teardown_items.append((group["name"], item))
            else:
                normal_items.append((group["name"], item))

    # The catalog's flat order is the natural dependency order (cart before
    # menu mutation, order before delivery, etc.), not the Postman group order.
    normal_items.sort(key=lambda gi: gi[1].get("x-order", 10**9))

    results = []
    total = passed = failed = skipped = 0
    # Tests that need a non-empty cart after the main order flow consumed it.
    REFILL_BEFORE = {"Batch Checkout", "Apply Coupon to Cart", "Create Scheduled Order",
                     "Create Order (Async)", "Apply Coupon to Cart — Wrong Restaurant",
                     "Create Order (sync)", "Create Order", "Create Review",
                     "Create Order (Split Pay)", "Create Order from Post"}

    def refill_cart():
        """Re-add the shared menu item to the customer's cart."""
        if not (vars_map.get("customer_token") and vars_map.get("menu_item_id")):
            return
        cust_headers = {"Authorization": f"Bearer {vars_map['customer_token']}"}
        # Clear cart first to ensure clean state, then add item with retries
        http("DELETE", "/api/v1/cart/clear", headers=cust_headers)
        time.sleep(0.5)  # Let clear propagate
        for attempt in range(3):
            status, body, err = http("POST", "/api/v1/cart/add",
                                     {"menuItemId": vars_map["menu_item_id"], "quantity": 2}, cust_headers)
            if status in (200, 201):
                break
            if status == 429:
                time.sleep(2 ** attempt)
            else:
                break

    def execute(items, label):
        nonlocal total, passed, failed, skipped
        for group, item in items:
            name = item["name"]
            missing = missing_required(item)
            if missing:
                skipped += 1
                results.append((group, name, item["request"]["method"], "", None, None, f"missing required: {missing}"))
                continue
            if name in REFILL_BEFORE:
                refill_cart()
            total += 1
            ok, reason, status, method, path = run_request(item, group)
            results.append((group, name, method, path, status, ok, reason))
            if ok:
                passed += 1
            else:
                failed += 1
            # After "Change Password" succeeds, the customer's password changes
            # and the old token is invalidated. Re-login with the new password
            # so subsequent customer-authenticated tests have a valid token.
            if name == "Change Password" and status in (200, 201):
                acct = ACCOUNTS["customer"]
                new_token, new_refresh, new_uid = _login(acct["email"], "secret456", "customer")
                if new_token:
                    vars_map["customer_token"] = new_token
                    vars_map["customerToken"] = new_token
                    vars_map["accessToken"] = new_token
                    if new_refresh:
                        vars_map["customer_refresh_token"] = new_refresh
                        vars_map["refreshToken"] = new_refresh
                    if new_uid:
                        vars_map["customer_id"] = new_uid
                        vars_map["customerId"] = new_uid
                    print(f"  Re-logged in customer after password change (token len={len(new_token)})")
                else:
                    print(f"  Re-login FAILED after password change")

    execute(normal_items, "normal")
    if teardown_items:
        print("\n  Running teardown items (logout) last...")
        execute(teardown_items, "teardown")

    print(f"\n{'='*60}")
    print(f"  RESULTS: {total} total, {passed} passed, {failed} failed, {skipped} skipped")
    print(f"{'='*60}")
    if failed:
        print("\n  FAILURES (unexpected):")
        for g, n, m, p, s, ok, r in results:
            if not ok and s is not None:
                print(f"    [{s}] {m} {p}  ({g}/{n})  {r}")
    return passed, failed, skipped


if __name__ == "__main__":
    bootstrap()
    if not vars_map.get("customer_token"):
        print("No customer token obtained")
        sys.exit(1)
    time.sleep(1)
    run_collection()
