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
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError

BASE = os.getenv("BASE_URL", "http://localhost:8080")
PASSWORD = "Test@123456"
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
            if k in ("id", "orderId", "order_id", "restaurantId", "restaurant_id", "menuItemId",
                     "menu_item_id", "categoryId", "category_id", "addressId", "address_id",
                     "paymentId", "payment_id", "disputeId", "dispute_id", "reviewId", "review_id",
                     "giftCardCode", "gift_card_code", "couponCode", "coupon_code", "jobId",
                     "invoiceId", "invoice_id", "ticketId", "ticket_id", "pricingRuleId",
                     "subscriptionId", "walletId", "deliveryAgentId", "agentId", "zoneId",
                     "planId", "plan_id", "membershipId", "orderNumber", "trackingToken",
                     "referralCode", "batchId", "groupOrderId", "group_order_id", "affiliateId",
                     "tenantId", "campaignId", "bannerId", "cityId", "alertId"):
                vars_map[k] = v
            extract_values(v, f"{prefix}.{k}")
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
    # prepends BASE (the host), so map it to the versioned API prefix.
    vars_map["baseUrl"] = "/api/v1"
    vars_map["base_url"] = "/api/v1"
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
    vars_map["couponCode"] = "SAVE10"
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
        status, body, err = http("POST", "/api/v1/auth/login", {"email": acct["email"], "password": acct["password"]})
        if status == 200:
            data = body.get("data") or {}
            if data.get("token"):
                vars_map[f"{role}_token"] = data["token"]
                if role == "customer":
                    vars_map["accessToken"] = data["token"]
            if data.get("refreshToken"):
                vars_map[f"{role}_refresh_token"] = data["refreshToken"]
                if role == "customer":
                    vars_map["refreshToken"] = data["refreshToken"]
            if data.get("userId"):
                vars_map[f"{role}_id"] = data["userId"]
        extract_values(body)
    # Admin login (seeded account)
    status, body, err = http("POST", "/api/v1/auth/login", {"email": "admin@bhukkad.dev", "password": "Admin@123456"})
    if status == 200:
        data = body.get("data") or {}
        if data.get("token"):
            vars_map["admin_token"] = data["token"]
        if data.get("refreshToken"):
            vars_map["admin_refresh_token"] = data["refreshToken"]
        if data.get("userId"):
            vars_map["admin_id"] = data["userId"]
    print(f"  Tokens: {sorted(k for k in vars_map if k.endswith('_token'))}")
    bootstrap_resources()


def bootstrap_resources():
    """Create real resources (address, cart item, order) to populate IDs."""
    cust_headers = {"Authorization": f"Bearer {vars_map.get('customer_token','')}"}

    # 1. Add a delivery address
    addr_body = {
        "label": "Home", "fullName": "Collection Test Customer",
        "phoneNumber": PHONES["customer"], "addressLine1": "123 Test St",
        "addressLine2": "Apt 4", "city": "Mumbai", "state": "MH",
        "pincode": "400001", "latitude": 19.076, "longitude": 72.8777,
        "isDefault": True,
    }
    status, body, err = http("POST", "/api/v1/customers/addresses", addr_body, cust_headers)
    addr_id = (body.get("data") or {}).get("id")
    if addr_id:
        vars_map["address_id"] = addr_id
    extract_values(body)
    if status not in (200, 201):
        print(f"  Add address: {status} {body.get('message','')}")

    # 2. Get a restaurant + menu item to build a cart
    status, body, err = http("GET", "/api/v1/restaurants/public")
    extract_values(body)
    restaurants = body.get("data") or []
    if restaurants:
        vars_map.setdefault("restaurant_id", restaurants[0].get("id"))
        rid = restaurants[0].get("id")
        status, body, err = http("GET", f"/api/v1/menu/items/restaurant/{rid}")
        extract_values(body)
        items = body.get("data") or []
        if items:
            vars_map.setdefault("menu_item_id", items[0].get("id"))
            item_id = items[0].get("id")
            # Add to cart
            status, body, err = http("POST", "/api/v1/cart/add", {"menuItemId": item_id, "quantity": 2}, cust_headers)
            cart_item = extract_json_path(body, "data.items.0.id")
            if cart_item:
                vars_map["cart_item_id"] = cart_item
            extract_values(body)
            if status not in (200, 201):
                print(f"  Add cart item: {status} {body.get('message','')}")
            # Place order (order creation requires an Idempotency-Key header)
            order_body = {
                "restaurantId": rid, "deliveryAddressId": vars_map.get("address_id"),
                "paymentMethod": "CASH_ON_DELIVERY",
                "items": [{"menuItemId": item_id, "quantity": 2}],
            }
            order_headers = dict(cust_headers)
            order_headers["Idempotency-Key"] = str(uuid.uuid4())
            status, body, err = http("POST", "/api/v1/orders/customer/create", order_body, order_headers)
            order_id = (body.get("data") or {}).get("id")
            if order_id:
                vars_map["order_id"] = order_id
                vars_map["orderId"] = order_id
            extract_values(body)
            if status in (200, 201):
                print(f"  Placed order: {vars_map.get('order_id')} (status {status})")
            else:
                print(f"  Place order: {status} {str(body)[:120]}")
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


def classify(name, status, err, expected=None):
    """Return (ok, reason) for a request outcome. Prefers the catalog's
    `x-expected` status codes; falls back to name heuristics for edge tests."""
    if err:
        # SSE streams are long-lived; a read/connection timeout is an acceptable outcome.
        if "sse stream" in name.lower() and ("timeout" in err.lower() or "timed out" in err.lower()):
            return True, "expected SSE timeout"
        return False, err
    if expected and status in expected:
        return True, f"expected {status}"
    if 200 <= status < 300:
        return True, ""
    low = name.lower()
    if status == 400 and any(k in low for k in ["invalid", "missing", "edge", "negative", "bad", "blank", "validation", "unsupported", "out-of-range"]):
        return True, "expected 400"
    if status == 401 and any(k in low for k in ["unauthorized", "no auth", "invalid token", "invalid/expired", "invalid"]):
        return True, "expected 401"
    if status == 403 and any(k in low for k in ["forbidden", "no token", "wrong role", "unauthorized", "protected", "access"]):
        return True, "expected 403"
    if status == 404 and any(k in low for k in ["not found", "non-existent", "nonexistent", "missing", "invalid id", "invalid id", "unknown"]):
        return True, "expected 404"
    if status == 409 and any(k in low for k in ["conflict", "duplicate", "already exists"]):
        return True, "expected 409"
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


def run_request(item):
    """Execute one collection item; returns (ok, reason, status, method, path)."""
    req = item["request"]
    method = req["method"]
    raw_url = req["url"]["raw"] if isinstance(req["url"], dict) else req["url"]
    path = resolve(raw_url, item["name"])
    if not path.startswith("/"):
        path = "/" + path

    # Append query parameters from the Postman URL object (generate-postman.py
    # puts them in url.query, not in url.raw).
    query = req.get("url", {}).get("query") if isinstance(req.get("url"), dict) else []
    if query:
        parts = []
        for qp in query:
            k = resolve(qp.get("key", ""), item["name"])
            v = resolve(qp.get("value", ""), item["name"])
            if k and v:
                parts.append(f"{k}={v}")
        if parts:
            path += "?" + "&".join(parts)

    headers = {}
    for h in req.get("header", []):
        v = resolve(h.get("value", ""), item["name"])
        if v:
            headers[h["key"]] = v

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
    if resp_body:
        apply_extract(resp_body, item.get("x-extract"))
        extract_values(resp_body)
    ok, reason = classify(item["name"], status, err, item.get("x-expected"))
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
                     "Create Order (sync)"}

    def refill_cart():
        """Re-add the shared menu item to the customer's cart."""
        if not (vars_map.get("customer_token") and vars_map.get("menu_item_id")):
            return
        cust_headers = {"Authorization": f"Bearer {vars_map['customer_token']}"}
        http("POST", "/api/v1/cart/add",
             {"menuItemId": vars_map["menu_item_id"], "quantity": 2}, cust_headers)

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
            ok, reason, status, method, path = run_request(item)
            results.append((group, name, method, path, status, ok, reason))
            if ok:
                passed += 1
            else:
                failed += 1

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
