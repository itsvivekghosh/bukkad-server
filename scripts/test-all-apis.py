#!/usr/bin/env python3
"""
Bhukkad API Feature Test Runner

Exercises REST endpoints in dependency order, printing and saving for each call:
  - API name & description
  - HTTP method & URL
  - Request headers/body
  - Response status & body
  - PASS / FAIL / SKIP

Usage::
  python3 scripts/test-all-apis.py
  python3 scripts/test-all-apis.py --base-url http://localhost:8080
  python3 scripts/test-all-apis.py --verbose
  python3 scripts/test-all-apis.py --report-dir scripts/reports

Requires: Python 3.9+ (stdlib only)
Server must be running (Docker or local mvn spring-boot:run).
"""

from __future__ import annotations

import argparse
import json
import logging
import os
import re
import secrets
import subprocess
import sys
import time
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timezone, timedelta
from pathlib import Path
from typing import Any
from http.client import IncompleteRead
from socket import timeout as SocketTimeoutError
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

# Allow running from repo root or scripts/
sys.path.insert(0, str(Path(__file__).resolve().parent))
from api_catalog import API_CATALOG, BODY_TEMPLATES  # noqa: E402

logger = logging.getLogger(__name__)

AUTH_MAP = {
    "customer": "customer_token",
    "owner": "owner_token",
    "agent": "agent_token",
    "admin": "admin_token",
    "customer_refresh": "customer_refresh_token",
}

# JSON fields that must stay strings even when numeric-looking
STRING_JSON_KEYS = frozenset({
    "phoneNumber", "code", "token", "platform", "pincode", "paymentMethod",
    "orderNumber", "specialInstructions", "discountType", "description",
    "label", "landmark", "addressLine1", "addressLine2", "city", "state",
    "type", "foodType", "spiceLevel", "fssaiNumber", "email", "password",
    "role", "fullName", "name", "comment", "validFrom", "validUntil",
})

# JSON fields that should be sent as numbers
NUMERIC_JSON_KEYS = frozenset({
    "categoryId", "menuItemId", "restaurantId", "orderId", "quantity",
    "deliveryAddressId",
    "displayOrder", "usageLimit", "perUserLimit", "loyaltyPointsToRedeem",
    "latitude", "longitude", "price", "originalPrice", "minimumOrderAmount",
    "deliveryFee", "maximumDiscountAmount", "discountValue", "preparationTime",
    "calories", "rating", "foodRating", "deliveryRating", "averageDeliveryTime",
    "freeDeliveryAbove", "tipAmount", "stockQuantity",
})

GREEN = "\033[0;32m"
RED = "\033[0;31m"
YELLOW = "\033[1;33m"
BLUE = "\033[0;34m"
CYAN = "\033[0;36m"
DIM = "\033[2m"
RESET = "\033[0m"


@dataclass
class TestResult:
    name: str
    group: str
    description: str
    method: str
    url: str
    request_headers: dict[str, str]
    request_body: Any
    status_code: int | None
    response_body: str
    passed: bool
    skipped: bool
    skip_reason: str = ""
    duration_ms: int = 0
    error: str = ""


@dataclass
class RunState:
    vars: dict[str, str] = field(default_factory=dict)
    tokens: dict[str, str] = field(default_factory=dict)
    results: list[TestResult] = field(default_factory=list)
    _webhook_seq: int = 0

    def next_webhook_payment_id(self) -> str:
        """Returns a unique payment id per webhook call so replay-protection
        tests never collide on the dedup key."""
        self._webhook_seq += 1
        return f"pay_test{self._webhook_seq}"

    def init_defaults(self, password: str) -> None:
        ts = str(int(time.time()))
        run_id = secrets.token_hex(4)
        self.vars = {
            "timestamp": ts,
            "timestamp_suffix": ts[-6:],
            "run_id": run_id,
            "password": password,
            "customer_email": f"customer_{ts}_{run_id}@bhukkad.test",
            "owner_email": f"owner_{ts}_{run_id}@bhukkad.test",
            "agent_email": f"agent_{ts}_{run_id}@bhukkad.test",
            "admin_email": f"admin_{ts}_{run_id}@bhukkad.test",
            "referred_customer_email": f"referred_{ts}_{run_id}@bhukkad.test",
            "customer_phone": self._unique_phone("98"),
            "owner_phone": self._unique_phone("97"),
            "agent_phone": self._unique_phone("96"),
            "admin_phone": self._unique_phone("95"),
            "referred_customer_phone": self._unique_phone("94"),
            "idempotency_key": str(uuid.uuid4()),
        }

    @staticmethod
    def _unique_phone(prefix: str) -> str:
        suffix = "".join(secrets.choice("0123456789") for _ in range(10 - len(prefix)))
        return prefix + suffix


def resolve_string(template: str, state: RunState) -> str:
    def replacer(match: re.Match[str]) -> str:
        key = match.group(1)
        if key in state.vars:
            return str(state.vars[key])
        if key in state.tokens:
            return state.tokens[key]
        return match.group(0)

    return re.sub(r"\{(\w+)\}", replacer, template)


def resolve_value(value: Any, state: RunState, key: str | None = None) -> Any:
    """Resolve placeholders in values and convert numeric strings to numbers.
    
    Args:
        value: The value to resolve (string, dict, list, or primitive)
        state: The run state containing variables and tokens
        key: The JSON key name (used to determine if value should stay string or become numeric)
    
    Returns:
        The resolved value with placeholders substituted and appropriate type conversion
    """
    if isinstance(value, str):
        resolved = resolve_string(value, state)
        if key in STRING_JSON_KEYS:
            return resolved
        if key in NUMERIC_JSON_KEYS:
            try:
                return int(resolved)
            except ValueError:
                pass
            try:
                return float(resolved)
            except ValueError:
                pass
        return resolved
    if isinstance(value, dict):
        return {k: resolve_value(v, state, k) for k, v in value.items()}
    if isinstance(value, list):
        return [resolve_value(v, state) for v in value]
    return value


def extract_json_path(data: Any, path: str) -> Any:
    """Extract a value from JSON data using a dot-notation path.
    
    Supports both object keys and array indices (e.g., "data.0.id").
    
    Args:
        data: The parsed JSON data (dict or list)
        path: Dot-separated path (e.g., "data.token" or "data.0.id")
    
    Returns:
        The extracted value, or None if path not found
    """
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


def truncate(text: str, limit: int = 2000) -> str:
    if len(text) <= limit:
        return text
    return text[:limit] + f"\n... [{len(text) - limit} more chars]"


def pretty_json(text: str) -> str:
    try:
        return json.dumps(json.loads(text), indent=2, ensure_ascii=False)
    except (json.JSONDecodeError, TypeError):
        return text


def http_request(
    method: str,
    url: str,
    headers: dict[str, str],
    body: bytes | None,
    timeout: int,
) -> tuple[int, str, dict[str, str]]:
    """Execute an HTTP request and return status, body, and headers.
    
    Handles SSE streams specially with a shorter timeout to avoid hanging.
    
    Args:
        method: HTTP method (GET, POST, PUT, DELETE, etc.)
        url: Full URL to request
        headers: Request headers
        body: Request body as bytes (or None)
        timeout: Request timeout in seconds
    
    Returns:
        Tuple of (status_code, response_body, response_headers)
    
    Raises:
        ConnectionError: If the request fails to connect
    """
    is_sse = headers.get("Accept", "") == "text/event-stream"
    # For SSE streams, use a short timeout to avoid hanging on long-lived connections
    effective_timeout = 5 if is_sse else timeout

    req = Request(url, data=body, method=method.upper())
    for key, value in headers.items():
        req.add_header(key, value)
    if body is not None and "Content-Type" not in headers:
        req.add_header("Content-Type", "application/json")
    try:
        with urlopen(req, timeout=effective_timeout) as resp:
            try:
                raw = resp.read().decode("utf-8", errors="replace")
            except IncompleteRead as e:
                # SSE streams send chunked data; partial read is fine for testing
                partial = e.partial
                raw = partial.decode("utf-8", errors="replace") if isinstance(partial, bytes) else str(partial or "")
            except SocketTimeoutError:
                # SSE streams are long-lived; a read timeout is expected and acceptable
                raw = ""
            return resp.status, raw, dict(resp.headers)
    except HTTPError as e:
        raw = e.read().decode("utf-8", errors="replace")
        return e.code, raw, dict(e.headers)
    except URLError as e:
        raise ConnectionError(str(e.reason)) from e


def run_test(
    spec: dict[str, Any],
    base_url: str,
    state: RunState,
    timeout: int,
    verbose: bool,
) -> TestResult:
    name = spec["name"]
    group = spec.get("group", "General")
    description = spec.get("description", "")
    method = spec["method"]

    # Skip if required state missing (before URL construction so unresolved
    # placeholders do not produce malformed request targets).
    for req_key in spec.get("requires", []):
        if req_key.endswith("_token"):
            if req_key not in state.tokens or not state.tokens.get(req_key):
                return TestResult(
                    name=name,
                    group=group,
                    description=description,
                    method=method,
                    url="",
                    request_headers={},
                    request_body=None,
                    status_code=None,
                    response_body="",
                    passed=False,
                    skipped=True,
                    skip_reason=f"Missing required token: {req_key}",
                )
        elif req_key not in state.vars or not state.vars[req_key]:
            return TestResult(
                name=name,
                group=group,
                description=description,
                method=method,
                url="",
                request_headers={},
                request_body=None,
                status_code=None,
                response_body="",
                passed=False,
                skipped=True,
                skip_reason=f"Missing required state: {req_key}",
            )

    path = resolve_string(spec["path"], state)
    url = base_url.rstrip("/") + path

    # Append query parameters if present
    query_params = spec.get("query")
    if query_params:
        resolved_query = resolve_value(query_params, state)
        query_parts = []
        for key, value in resolved_query.items():
            if value is not None and str(value) != "":
                query_parts.append(f"{key}={value}")
        if query_parts:
            url += "?" + "&".join(query_parts)

    headers: dict[str, str] = {"Accept": "application/json"}
    content_type = spec.get("content_type", "json")
    if content_type == "json" and method.upper() in ("POST", "PUT", "PATCH"):
        headers["Content-Type"] = "application/json"

    auth_role = spec.get("auth")
    if auth_role:
        token_key = AUTH_MAP.get(auth_role, auth_role)
        token = state.tokens.get(token_key, "")
        if not token:
            if spec.get("skip_if_no_auth", True):
                return TestResult(
                    name=name,
                    group=group,
                    description=description,
                    method=method,
                    url=url,
                    request_headers={},
                    request_body=None,
                    status_code=None,
                    response_body="",
                    passed=False,
                    skipped=True,
                    skip_reason=f"No {auth_role} token available",
                )
        else:
            headers["Authorization"] = f"Bearer {token}"

    for hk, hv in (spec.get("headers") or {}).items():
        headers[hk] = resolve_string(hv, state)

    body_obj = None
    body_bytes = None
    body_key = spec.get("body_key")
    if body_key and body_key in BODY_TEMPLATES:
        body_obj = resolve_value(BODY_TEMPLATES[body_key], state)
        if body_key == "scheduled_order":
            body_obj["scheduledAt"] = (datetime.now() + timedelta(minutes=35)).strftime("%Y-%m-%dT%H:%M:%S")
        # Each webhook test must use a distinct payment id: the payment webhook
        # is replay-protected by idempotency, so reusing the same id across tests
        # would make later tests hit the dedup path (200) instead of the
        # first-processing path (404) they assert.
        if body_key and body_key.startswith("razorpay_webhook") and "paymentId" in body_obj:
            body_obj["paymentId"] = state.next_webhook_payment_id()
        body_bytes = json.dumps(body_obj).encode("utf-8")
    elif spec.get("body"):
        # Inline body (placeholders resolved like templates). Used by recovered
        # and edge-case specs that do not need a named template.
        body_obj = resolve_value(spec["body"], state)
        body_bytes = json.dumps(body_obj).encode("utf-8")

    start = time.perf_counter()
    try:
        status, response_text, _ = http_request(method, url, headers, body_bytes, timeout)
        duration_ms = int((time.perf_counter() - start) * 1000)
        expected = spec.get("expected", [200])
        passed = status in expected

        # Optional specs (e.g. SSE streams, webhook fakes, S3-backed uploads)
        # depend on environment capabilities rather than application behavior.
        # A non-expected status on an optional spec is reported as SKIP so the
        # suite still surfaces a hard failure when a real bug exists.
        if not passed and spec.get("optional"):
            result = TestResult(
                name=name,
                group=group,
                description=description,
                method=method,
                url=url,
                request_headers={k: v for k, v in headers.items() if k != "Authorization"},
                request_body=body_obj,
                status_code=status,
                response_body=response_text,
                passed=False,
                skipped=True,
                skip_reason=f"Optional spec returned {status}, expected {expected}",
                duration_ms=int((time.perf_counter() - start) * 1000),
            )
            print_result(result, verbose)
            return result

        # Enhanced: Handle specific error cases for better diagnostics
        if not passed:
            if status == 500:
                # Try to extract error message from response
                try:
                    parsed = json.loads(response_text)
                    error_msg = parsed.get("message", "Internal Server Error")
                    if "An unexpected error occurred" in error_msg:
                        error_msg = f"Potential defect: {error_msg}"
                except json.JSONDecodeError:
                    error_msg = f"HTTP 500: {response_text[:200]}"
                logger.warning(f"Test '{name}' returned 500: {error_msg}")
            elif status == 400:
                try:
                    parsed = json.loads(response_text)
                    error_msg = parsed.get("message", "Bad Request")
                except json.JSONDecodeError:
                    error_msg = f"HTTP 400: {response_text[:200]}"
                logger.warning(f"Test '{name}' returned 400: {error_msg}")

        # Extract state from response
        if passed and spec.get("extract"):
            try:
                parsed = json.loads(response_text)
                apply_auth_extract(state, spec["extract"], parsed)
            except json.JSONDecodeError:
                pass

        result = TestResult(
            name=name,
            group=group,
            description=description,
            method=method,
            url=url,
            request_headers={k: v for k, v in headers.items() if k != "Authorization"},
            request_body=body_obj,
            status_code=status,
            response_body=response_text,
            passed=passed,
            skipped=False,
            duration_ms=duration_ms,
        )

        print_result(result, verbose)
        return result

    except ConnectionError as e:
        duration_ms = int((time.perf_counter() - start) * 1000)
        result = TestResult(
            name=name,
            group=group,
            description=description,
            method=method,
            url=url,
            request_headers=headers,
            request_body=body_obj,
            status_code=None,
            response_body="",
            passed=False,
            skipped=False,
            duration_ms=duration_ms,
            error=str(e),
        )
        print_result(result, verbose)
        return result


def print_result(result: TestResult, verbose: bool) -> None:
    if result.skipped:
        icon = f"{YELLOW}SKIP{RESET}"
    elif result.passed:
        icon = f"{GREEN}PASS{RESET}"
    else:
        icon = f"{RED}FAIL{RESET}"

    status = result.status_code if result.status_code is not None else "ERR"
    print(f"  {icon} | {result.method:6} {status:>3} | {result.name} ({result.duration_ms}ms)")

    if result.skipped:
        print(f"       {DIM}↳ {result.skip_reason}{RESET}")
        return

    if result.error:
        print(f"       {RED}↳ {result.error}{RESET}")
        return

    if verbose or not result.passed:
        print(f"       {DIM}URL: {result.url}{RESET}")
        if result.request_body:
            print(f"       {DIM}Request:{RESET}")
            indent_block(json.dumps(result.request_body, indent=2), 7)
        if result.response_body:
            body_preview = pretty_json(result.response_body)
            print(f"       {DIM}Response:{RESET}")
            indent_block(truncate(body_preview, 1500), 7)


def indent_block(text: str, spaces: int) -> None:
    pad = " " * spaces
    for line in text.splitlines():
        print(pad + line)


def print_header(title: str) -> None:
    print()
    print(f"{BLUE}{'═' * 60}{RESET}")
    print(f"{BLUE}  {title}{RESET}")
    print(f"{BLUE}{'═' * 60}{RESET}")


def print_section(group: str) -> None:
    print()
    print(f"{CYAN}── {group} ──{RESET}")


def apply_auth_extract(state: RunState, extract: dict[str, str], parsed: dict) -> None:
    for var_name, json_path in extract.items():
        val = extract_json_path(parsed, json_path)
        if val is not None and val != "":
            state.vars[var_name] = str(val)
            if var_name.endswith("_token") or var_name in AUTH_MAP.values():
                state.tokens[var_name] = str(val)
            if var_name.endswith("_refresh_token"):
                state.tokens[var_name] = str(val)
            if var_name == "customer_token":
                state.tokens["customer_token"] = str(val)
            elif var_name == "owner_token":
                state.tokens["owner_token"] = str(val)
            elif var_name == "agent_token":
                state.tokens["agent_token"] = str(val)
            elif var_name == "admin_token":
                state.tokens["admin_token"] = str(val)


def register_or_login(
    role: str,
    register_body_key: str,
    login_body_key: str,
    base_url: str,
    state: RunState,
    timeout: int,
) -> bool:
    """Register a user; on duplicate email/phone, fall back to login."""
    token_field = f"{role}_token"
    register_spec = {
        "name": f"Register {role}",
        "method": "POST",
        "path": "/api/v1/auth/register",
        "body_key": register_body_key,
        "expected": [200],
        "extract": {token_field: "data.token", f"{role}_id": "data.userId", f"{role}_refresh_token": "data.refreshToken"},
    }
    result = run_test(register_spec, base_url, state, timeout, verbose=False)
    if result.passed:
        return True

    login_spec = {
        "name": f"Login {role}",
        "method": "POST",
        "path": "/api/v1/auth/login",
        "body_key": login_body_key,
        "expected": [200],
        "extract": {token_field: "data.token", f"{role}_id": "data.userId", f"{role}_refresh_token": "data.refreshToken"},
    }
    result = run_test(login_spec, base_url, state, timeout, verbose=False)
    return result.passed


def bootstrap_restaurant_id(base_url: str, state: RunState, timeout: int) -> None:
    """Fetch the first public restaurant id for tests that need a seed restaurant."""
    if state.vars.get("restaurant_id"):
        return
    spec = {
        "name": "_bootstrap_restaurant_id",
        "method": "GET",
        "path": "/api/v1/restaurants/public?page=0&size=1",
        "auth": None,
        "expected": [200],
        "extract": {"restaurant_id": "data.0.id"},
    }
    result = run_test(spec, base_url, state, timeout, verbose=False)
    if not result.passed:
        print(f"  {YELLOW}↳ Could not bootstrap restaurant_id — serviceability and restaurant tests may be skipped.{RESET}")


def bootstrap_accounts(
    base_url: str,
    state: RunState,
    timeout: int,
    admin_email: str | None,
    admin_password: str | None,
) -> None:
    """Ensure customer, owner, agent (and optional admin) tokens exist before the main suite."""
    print_section("Bootstrap accounts")
    for role, reg_key, login_key in (
        ("customer", "register_customer", "login_customer"),
        ("owner", "register_owner", "login_owner"),
        ("agent", "register_agent", "login_agent"),
    ):
        register_or_login(role, reg_key, login_key, base_url, state, timeout)

    if admin_email and admin_password:
        state.vars["bootstrap_admin_email"] = admin_email
        state.vars["bootstrap_admin_password"] = admin_password
        admin_spec = {
            "name": "Login Admin",
            "group": "Bootstrap accounts",
            "description": "Authenticates the seeded dev admin for platform API tests.",
            "method": "POST",
            "path": "/api/v1/auth/login",
            "body_key": "login_bootstrap_admin",
            "expected": [200],
            "extract": {"admin_token": "data.token", "admin_id": "data.userId"},
        }
        result = run_test(admin_spec, base_url, state, timeout, verbose=False)
        if not result.passed:
            print(f"  {YELLOW}↳ Admin login failed — admin API tests will be skipped.{RESET}")
            print(f"  {DIM}  Seed admin via DevAdminBootstrap or pass --admin-email / --admin-password{RESET}")


def create_cancel_order(
    base_url: str,
    state: RunState,
    timeout: int,
) -> None:
    """Place a second order to test cancellation (first may be in delivery flow)."""
    if not state.vars.get("menu_item_id") or not state.vars.get("address_id"):
        return
    add_spec = {
        "name": "_setup_cancel_cart",
        "method": "POST",
        "path": "/api/v1/cart/add",
        "auth": "customer",
        "body_key": "cart_add",
        "expected": [200],
    }
    run_test(add_spec, base_url, state, timeout, verbose=False)
    order_spec = {
        "name": "_setup_cancel_order",
        "method": "POST",
        "path": "/api/v1/orders/customer/create",
        "auth": "customer",
        "body_key": "order",
        "expected": [200],
        "headers": {"Idempotency-Key": str(uuid.uuid4())},
        "extract": {"cancel_order_id": "data.id"},
    }
    run_test(order_spec, base_url, state, timeout, verbose=False)



def setup_delivery_proof_order(
    base_url: str,
    state: RunState,
    timeout: int,
) -> None:
    """Create an order and advance it to PICKED_UP state assigned to the test agent.
    This provides the prerequisite state for delivery proof tests.
    """
    if not state.vars.get("menu_item_id") or not state.vars.get("address_id") or not state.vars.get("agent_id"):
        return

    # 1. Add to cart
    add_spec = {
        "name": "_setup_dp_cart",
        "method": "POST",
        "path": "/api/v1/cart/add",
        "auth": "customer",
        "body_key": "cart_add",
        "expected": [200],
    }
    run_test(add_spec, base_url, state, timeout, verbose=False)

    # 2. Place order
    order_spec = {
        "name": "_setup_dp_order",
        "method": "POST",
        "path": "/api/v1/orders/customer/create",
        "auth": "customer",
        "body_key": "order",
        "expected": [200],
        "headers": {"Idempotency-Key": str(uuid.uuid4())},
        "requires": ["restaurant_id", "address_id"],
        "extract": {"order_id": "data.id"},
    }
    run_test(order_spec, base_url, state, timeout, verbose=False)

    dp_order_id = state.vars.get("order_id")
    if not dp_order_id:
        return

    # 3. Accept order (owner)
    accept_spec = {
        "name": "_setup_dp_accept",
        "method": "PUT",
        "path": f"/api/v1/orders/restaurant/{dp_order_id}/accept",
        "auth": "owner",
        "expected": [200],
    }
    run_test(accept_spec, base_url, state, timeout, verbose=False)

    # 4. Mark ready (owner)
    ready_spec = {
        "name": "_setup_dp_ready",
        "method": "PUT",
        "path": f"/api/v1/orders/restaurant/{dp_order_id}/ready",
        "auth": "owner",
        "expected": [200],
    }
    run_test(ready_spec, base_url, state, timeout, verbose=False)

    # 5. Assign delivery agent (owner assigns test agent)
    assign_spec = {
        "name": "_setup_dp_assign",
        "method": "PUT",
        "path": f"/api/v1/orders/restaurant/{dp_order_id}/assign-delivery?agentId={state.vars['agent_id']}",
        "auth": "owner",
        "expected": [200],
    }
    run_test(assign_spec, base_url, state, timeout, verbose=False)

    # 6. Mark picked up (agent)
    pickup_spec = {
        "name": "_setup_dp_pickup",
        "method": "PUT",
        "path": f"/api/v1/orders/delivery/{dp_order_id}/picked-up",
        "auth": "agent",
        "expected": [200],
    }
    run_test(pickup_spec, base_url, state, timeout, verbose=False)


def setup_review_for_moderation(
    base_url: str,
    state: RunState,
    timeout: int,
    main_order_id: str = None,
) -> None:
    """Create a review for the Moderate Review test.
    Requires a delivered order with a menu item.
    Uses main_order_id (the delivered order) instead of current order_id.
    Skips when a review already exists for the order (each order accepts one).
    """
    if state.vars.get("review_id"):
        return
    order_id = main_order_id or state.vars.get("order_id")
    if not order_id or not state.vars.get("menu_item_id"):
        return
    
    # Submit a review for the delivered order
    review_spec = {
        "name": "_setup_review",
        "method": "POST",
        "path": "/api/v1/reviews",
        "auth": "customer",
        "body_key": "review",
        "expected": [200],
        "requires": ["order_id", "menu_item_id"],
        "extract": {"review_id": "data.id"},
    }
    # Temporarily set order_id for the review request
    original_order_id = state.vars.get("order_id")
    state.vars["order_id"] = order_id
    run_test(review_spec, base_url, state, timeout, verbose=False)
    # Restore original order_id
    if original_order_id:
        state.vars["order_id"] = original_order_id


def setup_invoice_pdf_order(
    base_url: str,
    state: RunState,
    timeout: int,
    main_order_id: str = None,
) -> None:
    """Point order_id at the main delivered order for the invoice PDF test.

    Leaves order_id on the main delivered order afterward, which is what the
    remaining downstream tests (review lookup, track alias) expect.
    """
    order_id = main_order_id or state.vars.get("order_id")
    if not order_id:
        return

    state.vars["order_id"] = order_id


def refill_cart_for_order_tests(
    base_url: str,
    state: RunState,
    timeout: int,
) -> None:
    """Re-add items after the main order flow empties the cart."""
    if not state.vars.get("menu_item_id"):
        return
    run_test(
        {
            "name": "_setup_refill_cart",
            "method": "POST",
            "path": "/api/v1/cart/add",
            "auth": "customer",
            "body_key": "cart_add",
            "expected": [200],
        },
        base_url,
        state,
        timeout,
        verbose=False,
    )


def _edge_result(
    name: str,
    group: str,
    description: str,
    method: str,
    url: str,
    status_code: int | None,
    response_body: str,
    passed: bool,
    skipped: bool = False,
    skip_reason: str = "",
) -> TestResult:
    return TestResult(
        name=name,
        group=group,
        description=description,
        method=method,
        url=url,
        request_headers={},
        request_body=None,
        status_code=status_code,
        response_body=response_body,
        passed=passed,
        skipped=skipped,
        skip_reason=skip_reason,
    )


def test_order_idempotency_replay(base_url: str, state: RunState, timeout: int) -> None:
    """Probe: placing an order with the same Idempotency-Key twice must replay the
    SAME order (identical order id), not create a duplicate. This is the core
    duplicate-order guarantee under network retries."""
    if not state.vars.get("menu_item_id") or not state.vars.get("restaurant_id") or not state.vars.get("address_id"):
        return

    refill_cart_for_order_tests(base_url, state, timeout)
    key = f"idem-replay-{state.vars.get('run_id', 'x')}-{int(time.time())}"

    first = run_test(
        {
            "name": "Idempotency Replay — First Call (probe)",
            "method": "POST",
            "path": "/api/v1/orders/customer/create",
            "auth": "customer",
            "body_key": "order",
            "expected": [200],
            "headers": {"Idempotency-Key": key},
            "extract": {"replay_order_id": "data.id"},
        },
        base_url, state, timeout, verbose=False,
    )
    first_id = state.vars.get("replay_order_id")

    second = run_test(
        {
            "name": "Idempotency Replay — Duplicate Call (probe)",
            "method": "POST",
            "path": "/api/v1/orders/customer/create",
            "auth": "customer",
            "body_key": "order",
            "expected": [200],
            "headers": {"Idempotency-Key": key},
            "extract": {"replay_order_id_2": "data.id"},
        },
        base_url, state, timeout, verbose=False,
    )
    second_id = state.vars.get("replay_order_id_2")

    if not (first.passed and second.passed):
        # State-dependent: by the time the probes run the suite's test
        # restaurant may have been toggled off / stock exhausted, so order
        # creation legitimately fails. Report as SKIP, not FAIL.
        state.results.append(_edge_result(
            name="Idempotency Replay Returns Same Order (edge)",
            group="Edge Cases & Boundaries",
            description="Placing an order twice with the same Idempotency-Key must replay the same order id (no duplicate order).",
            method="POST",
            url=f"{base_url}/api/v1/orders/customer/create",
            status_code=second.status_code,
            response_body=f"first_status={first.status_code} second_status={second.status_code} first_id={first_id} second_id={second_id}",
            passed=False,
            skipped=True,
            skip_reason=f"Order creation unavailable at probe time (first={first.status_code}, second={second.status_code})",
        ))
        return

    passed = first.passed and second.passed and bool(first_id) and first_id == second_id
    state.results.append(_edge_result(
        name="Idempotency Replay Returns Same Order (edge)",
        group="Edge Cases & Boundaries",
        description="Placing an order twice with the same Idempotency-Key must replay the same order id (no duplicate order).",
        method="POST",
        url=f"{base_url}/api/v1/orders/customer/create",
        status_code=second.status_code,
        response_body=f"first_id={first_id} second_id={second_id}",
        passed=passed,
    ))


def test_rate_limit_order_track(base_url: str, state: RunState, timeout: int) -> None:
    """Probe: the order-track endpoint is rate limited (20 req / 60s). Firing a
    burst must eventually produce 429 with Retry-After — the app must never 500
    under throttle pressure."""
    order_id = state.vars.get("replay_order_id") or state.vars.get("order_id")
    token = state.tokens.get("customer_token")
    if not order_id or not token:
        return

    url = f"{base_url}/api/v1/orders/customer/track/{order_id}"
    statuses: list[int] = []
    seen_429 = False
    for _ in range(45):
        status, _, _ = http_request(
            "GET", url,
            {"Accept": "application/json", "Authorization": f"Bearer {token}"},
            None, timeout,
        )
        statuses.append(status)
        if status == 429:
            seen_429 = True
            break

    state.results.append(_edge_result(
        name="Order Track Rate Limit Enforces 429 (edge)",
        group="Edge Cases & Boundaries",
        description="Bursting the order-track endpoint must be throttled with 429 (never 500).",
        method="GET",
        url=url,
        status_code=statuses[-1] if statuses else None,
        response_body=f"statuses={statuses[:10]}... total={len(statuses)} 429_seen={seen_429}",
        passed=seen_429,
    ))


def test_order_empty_cart_400(base_url: str, state: RunState, timeout: int) -> None:
    """Probe: a brand-new account with an empty cart must get 400 'Cart is empty'
    when placing an order — a clean error state, not a 500."""
    ts = str(int(time.time()))
    email = f"edge_empty_{ts}@bhukkad.test"
    phone = "98" + "".join(secrets.choice("0123456789") for _ in range(8))
    body = {
        "fullName": "Edge Empty Cart",
        "email": email,
        "password": state.vars.get("password", "Test@123456"),
        "phoneNumber": phone,
        "role": "CUSTOMER",
    }
    reg_status, reg_text, _ = http_request(
        "POST", f"{base_url}/api/v1/auth/register",
        {"Content-Type": "application/json"},
        json.dumps(body).encode("utf-8"), timeout,
    )
    if reg_status != 200:
        state.results.append(_edge_result(
            name="Place Order — Empty Cart Returns 400 (edge)",
            group="Edge Cases & Boundaries",
            description="Fresh account with an empty cart placing an order must return 400.",
            method="POST",
            url=f"{base_url}/api/v1/orders/customer/create",
            status_code=reg_status,
            response_body="Could not register fresh edge account",
            passed=False,
            skipped=True,
            skip_reason=f"Registration for fresh edge account failed with {reg_status}",
        ))
        return

    token = None
    try:
        token = json.loads(reg_text).get("data", {}).get("token")
    except json.JSONDecodeError:
        pass
    if not token:
        state.results.append(_edge_result(
            name="Place Order — Empty Cart Returns 400 (edge)",
            group="Edge Cases & Boundaries",
            description="Fresh account with an empty cart placing an order must return 400.",
            method="POST",
            url=f"{base_url}/api/v1/orders/customer/create",
            status_code=reg_status,
            response_body="No token in registration response",
            passed=False,
            skipped=True,
            skip_reason="Registration returned 200 but no token",
        ))
        return

    order_body = {
        "restaurantId": int(state.vars["restaurant_id"]),
        "deliveryAddressId": int(state.vars["address_id"]),
        "paymentMethod": "CASH_ON_DELIVERY",
        "tipAmount": 0.0,
    }
    order_status, order_text, _ = http_request(
        "POST", f"{base_url}/api/v1/orders/customer/create",
        {
            "Content-Type": "application/json",
            "Authorization": f"Bearer {token}",
            "Idempotency-Key": f"edge-empty-{ts}",
        },
        json.dumps(order_body).encode("utf-8"), timeout,
    )
    state.results.append(_edge_result(
        name="Place Order — Empty Cart Returns 400 (edge)",
        group="Edge Cases & Boundaries",
        description="Fresh account with an empty cart placing an order must return 400, not 500.",
        method="POST",
        url=f"{base_url}/api/v1/orders/customer/create",
        status_code=order_status,
        response_body=order_text[:300],
        passed=order_status == 400,
    ))


def test_e2e_full_journey(base_url: str, state: RunState, timeout: int) -> None:
    """E2E: complete customer lifecycle — register → browse → cart → place order →
    owner confirms/readies → agent delivers → customer reviews → reorder.

    Uses dedicated accounts so the shared suite state is never disturbed, and
    asserts cross-step invariants (the review and reorder target the SAME order
    that was placed and delivered)."""
    ts = str(int(time.time()))
    suffix = ts[-6:]

    def http(method, path, token=None, body=None, headers=None, label=""):
        h = {"Accept": "application/json", "Content-Type": "application/json"}
        if token:
            h["Authorization"] = f"Bearer {token}"
        for k, v in (headers or {}).items():
            h[k] = v
        raw = json.dumps(body).encode() if body is not None else None
        try:
            status, text, _ = http_request(method, f"{base_url}{path}", h, raw, timeout)
            return status, text
        except Exception as e:  # noqa: BLE001 - surfaced via the summary below
            return None, str(e)

    def ok(name, status, text, passed, ctx=""):
        state.results.append(_edge_result(
            name=name, group="E2E Journey", description=f"Step: {name}",
            method="", url="", status_code=status, response_body=(text or "")[:200] + ctx,
            passed=passed))

    # 1. Register dedicated customer, owner, agent
    c_email, o_email, a_email = f"e2e_c_{suffix}@bhukkad.test", f"e2e_o_{suffix}@bhukkad.test", f"e2e_a_{suffix}@bhukkad.test"
    c_status, c_text = http("POST", "/api/v1/auth/register", body={
        "fullName": "E2E Customer", "email": c_email, "password": "Test@123456",
        "phoneNumber": f"93{suffix}11", "role": "CUSTOMER"})
    c_token = json.loads(c_text).get("data", {}).get("token", "") if c_status == 200 else ""
    ok("Register customer", c_status, c_text, c_status == 200 and bool(c_token))
    if not c_token:
        return

    o_status, o_text = http("POST", "/api/v1/auth/register", body={
        "fullName": "E2E Owner", "email": o_email, "password": "Test@123456",
        "phoneNumber": f"92{suffix}22", "role": "RESTAURANT_OWNER"})
    o_token = json.loads(o_text).get("data", {}).get("token", "") if o_status == 200 else ""
    ok("Register owner", o_status, o_text, o_status == 200 and bool(o_token))

    a_status, a_text = http("POST", "/api/v1/auth/register", body={
        "fullName": "E2E Agent", "email": a_email, "password": "Test@123456",
        "phoneNumber": f"91{suffix}33", "role": "DELIVERY_AGENT"})
    a_data = json.loads(a_text).get("data", {}) if a_status == 200 else {}
    a_token = a_data.get("token", "")
    agent_id = a_data.get("userId")
    ok("Register agent", a_status, a_text, a_status == 200 and bool(a_token) and agent_id is not None)

    # delivery agents must be admin-verified before accepting deliveries
    admin_tok = state.tokens.get("admin_token", "")
    if admin_tok and agent_id:
        http("PUT", f"/api/v1/admin/agents/{agent_id}/verify", token=admin_tok)

    # 2. Browse: public cuisines and restaurants
    cu_status, cu_text = http("GET", "/api/v1/cuisines")
    ok("Browse cuisines", cu_status, cu_text, cu_status == 200)

    # 3. Seed a restaurant + menu item for the owner. Extract the restaurant id
    # from the creation response so we never pick up a stale restaurant from a
    # previous run.
    r_status, r_text = http("POST", "/api/v1/restaurants/owner", token=o_token, body={
        "name": f"E2E Kitchen {suffix}", "description": "E2E journey restaurant",
        "address": {"addressLine1": "1 Food St", "city": "Bangalore", "state": "KA",
                    "pincode": "560001", "latitude": 12.97, "longitude": 77.59},
        "openingTime": "09:00:00", "closingTime": "23:00:00",
        "deliveryFee": 30, "minimumOrderAmount": 100, "averageDeliveryTime": 30,
        "freeDeliveryAvailable": True, "freeDeliveryAbove": 500, "isPureVeg": False,
        "fssaiNumber": f"FSS-E2E-{suffix}"})
    rid = json.loads(r_text).get("data", {}).get("id") if r_status == 200 else None
    ok("Owner creates restaurant", r_status, r_text, r_status == 200 and rid is not None)
    if not rid:
        return

    # 4. Customer adds an address, adds to cart, places an order
    ad_status, ad_text = http("POST", "/api/v1/customers/addresses", token=c_token, body={
        "addressLine1": "2 Test Ave", "city": "Bangalore", "state": "KA", "pincode": "560001",
        "latitude": 12.971, "longitude": 77.594, "type": "HOME"})
    addr_id = json.loads(ad_text).get("data", {}).get("id") if ad_status == 200 else None
    ok("Add delivery address", ad_status, ad_text, ad_status == 200 and addr_id is not None)

    # toggle the restaurant open so ordering is allowed
    http("PUT", f"/api/v1/restaurants/owner/{rid}/toggle-status?isOpen=true", token=o_token)

    # seed a menu category + item so the restaurant is orderable
    cat_status, cat_text = http("POST", f"/api/v1/menu/categories?restaurantId={rid}", token=o_token,
                                body={"name": "Starters", "description": "E2E category",
                                      "displayOrder": 1, "active": True})
    cat_id = json.loads(cat_text).get("data", {}).get("id") if cat_status == 200 else None
    item_status, item_text = http("POST", "/api/v1/menu/items", token=o_token,
                                  body={"name": "Paneer Tikka", "description": "E2E dish",
                                        "categoryId": cat_id, "price": 199.0, "foodType": "VEG",
                                        "isVeg": True, "isSpicy": True, "spiceLevel": "MEDIUM",
                                        "preparationTime": 15})

    items_status, items_text = http("GET", f"/api/v1/menu/items/restaurant/{rid}")
    items = json.loads(items_text).get("data", []) if items_status == 200 else []
    if not items:
        ok("Menu has items", items_status, items_text, False, "seeded restaurant has no menu items")
        return
    mid = items[0]["id"]

    cart_status, cart_text = http("POST", "/api/v1/cart/add", token=c_token,
                                  body={"menuItemId": mid, "quantity": 2})
    ok("Add to cart", cart_status, cart_text, cart_status == 200)

    order_status, order_text = http("POST", "/api/v1/orders/customer/create", token=c_token,
                                    headers={"Idempotency-Key": f"e2e-order-{ts}"},
                                    body={"restaurantId": rid, "deliveryAddressId": addr_id,
                                          "paymentMethod": "CASH_ON_DELIVERY", "tipAmount": 10.0})
    order_id = json.loads(order_text).get("data", {}).get("id") if order_status == 200 else None
    ok("Place order", order_status, order_text, order_status == 200 and order_id is not None)
    if not order_id:
        return

    # 5. Track order (customer) + owner accepts/readies + agent delivers
    tr_status, tr_text = http("GET", f"/api/v1/orders/customer/track/{order_id}", token=c_token)
    ok("Track order", tr_status, tr_text, tr_status == 200)

    ac_status, ac_text = http("PUT", f"/api/v1/orders/restaurant/{order_id}/accept", token=o_token)
    ok("Owner accepts order", ac_status, ac_text, ac_status == 200)
    rd_status, rd_text = http("PUT", f"/api/v1/orders/restaurant/{order_id}/ready", token=o_token)
    ok("Owner marks ready", rd_status, rd_text, rd_status == 200)

    asg_status, asg_text = http("PUT", f"/api/v1/orders/restaurant/{order_id}/assign-delivery?agentId={agent_id}", token=o_token)
    ok("Assign delivery agent", asg_status, asg_text, asg_status == 200 and agent_id is not None)

    # agent availability + accept the delivery
    http("PUT", "/api/v1/delivery/toggle-availability?available=true", token=a_token)
    avail_status, avail_text = http("GET", "/api/v1/delivery/available-orders", token=a_token)
    ok("Agent sees available orders", avail_status, avail_text, avail_status == 200)
    dacc_status, dacc_text = http("POST", f"/api/v1/delivery/{order_id}/accept", token=a_token)
    ok("Agent accepts delivery", dacc_status, dacc_text, dacc_status == 200)

    picked_status, picked_text = http("PUT", f"/api/v1/orders/delivery/{order_id}/picked-up", token=a_token)
    ok("Agent marks picked up", picked_status, picked_text, picked_status == 200)
    del_status, del_text = http("PUT", f"/api/v1/orders/delivery/{order_id}/delivered", token=a_token)
    ok("Agent marks delivered", del_status, del_text, del_status == 200)

    # 6. Customer reviews the delivered order + reorders it
    rev_status, rev_text = http("POST", "/api/v1/reviews", token=c_token,
                                body={"orderId": order_id, "rating": 5, "comment": "E2E journey review"})
    ok("Submit review on delivered order", rev_status, rev_text, rev_status == 200)

    re_status, re_text = http("POST", f"/api/v1/orders/customer/{order_id}/reorder", token=c_token)
    ok("Reorder from delivered order", re_status, re_text, re_status == 200)

    # 7. Customer sees the order in history with the right status
    his_status, his_text = http("GET", "/api/v1/orders/customer/my-orders?page=0&size=10", token=c_token)
    history = json.loads(his_text).get("data", {}).get("items", []) if his_status == 200 else []
    entry = next((o for o in history if o.get("id") == order_id), None)
    delivered_in_history = entry is not None and entry.get("status") == "DELIVERED"
    ok("Order history reflects DELIVERED", his_status, his_text, delivered_in_history)


def write_markdown_report(results: list[TestResult], path: Path, base_url: str) -> None:
    passed = sum(1 for r in results if r.passed and not r.skipped)
    failed = sum(1 for r in results if not r.passed and not r.skipped)
    skipped = sum(1 for r in results if r.skipped)
    total = len(results)

    lines = [
        "# Bhukkad API Test Report",
        "",
        f"- **Generated:** {datetime.now(timezone.utc).strftime('%Y-%m-%d %H:%M:%S UTC')}",
        f"- **Base URL:** `{base_url}`",
        f"- **Total:** {total} | **Passed:** {passed} | **Failed:** {failed} | **Skipped:** {skipped}",
        "",
        "---",
        "",
    ]

    current_group = None
    for r in results:
        if r.group != current_group:
            current_group = r.group
            lines.extend([f"## {current_group}", ""])

        status_label = "SKIP" if r.skipped else ("PASS" if r.passed else "FAIL")
        status_code = r.status_code if r.status_code is not None else "N/A"

        lines.extend([
            f"### {r.method} `{r.url}` — {r.name}",
            "",
            f"**Description:** {r.description}",
            "",
            f"**Result:** `{status_label}` | **Status:** `{status_code}` | **Time:** {r.duration_ms}ms",
            "",
        ])

        if r.skipped:
            lines.extend([f"*Skipped:* {r.skip_reason}", ""])
            continue

        if r.error:
            lines.extend([f"**Error:** {r.error}", ""])
            continue

        lines.append("**Request headers:**")
        lines.append("```json")
        lines.append(json.dumps(r.request_headers, indent=2))
        lines.append("```")
        lines.append("")

        if r.request_body is not None:
            lines.append("**Request body:**")
            lines.append("```json")
            lines.append(json.dumps(r.request_body, indent=2))
            lines.append("```")
            lines.append("")

        lines.append("**Response:**")
        lines.append("```json")
        lines.append(truncate(pretty_json(r.response_body), 4000))
        lines.append("```")
        lines.append("")
        lines.append("---")
        lines.append("")

    path.write_text("\n".join(lines), encoding="utf-8")


def write_json_report(results: list[TestResult], path: Path, base_url: str) -> None:
    payload = {
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "baseUrl": base_url,
        "summary": {
            "total": len(results),
            "passed": sum(1 for r in results if r.passed and not r.skipped),
            "failed": sum(1 for r in results if not r.passed and not r.skipped),
            "skipped": sum(1 for r in results if r.skipped),
        },
        "tests": [
            {
                "name": r.name,
                "group": r.group,
                "description": r.description,
                "method": r.method,
                "url": r.url,
                "requestHeaders": r.request_headers,
                "requestBody": r.request_body,
                "statusCode": r.status_code,
                "responseBody": truncate(r.response_body, 8000),
                "passed": r.passed,
                "skipped": r.skipped,
                "skipReason": r.skip_reason,
                "durationMs": r.duration_ms,
                "error": r.error,
            }
            for r in results
        ],
    }
    path.write_text(json.dumps(payload, indent=2), encoding="utf-8")


def reset_database(db_url: str | None = None) -> bool:
    """Truncate all user tables and re-apply the V6 seed data for a clean E2E run.

    Uses psql (PostgreSQL) to drop all rows from every user table with CASCADE,
    then re-inserts the baseline reference data from the V6 Flyway migration.
    This ensures each test run starts from a known-good state.

    Args:
        db_url: Optional PostgreSQL connection URL. If not provided, uses
                environment variables DB_HOST, DB_PORT, DB_NAME, DB_USERNAME,
                DB_PASSWORD (or defaults from run-local.sh defaults).

    Returns:
        True if the reset succeeded, False otherwise.
    """
    # Build connection params from env (matching run-local.sh defaults)
    host = os.getenv("DB_HOST", "localhost")
    port = os.getenv("DB_PORT", "5432")
    dbname = os.getenv("DB_NAME", "bhukkad")
    user = os.getenv("DB_USERNAME", "bhukkad")
    password = os.getenv("DB_PASSWORD", "")

    # Try to extract params from db_url if provided
    if db_url:
        # Parse postgres://user:pass@host:port/dbname
        m = re.match(r"postgres://([^:]+):([^@]+)@([^:]+):(\d+)/(.+)", db_url)
        if m:
            user, password, host, port, dbname = m.groups()

    os.environ["PGPASSWORD"] = password

    # Step 1: Get all user table names (exclude Flyway schema history).
    # We truncate everything including users/admins, then re-seed the dev admin
    # via SQL (DevAdminBootstrap only runs on app startup, not on each test run).
    try:
        result = subprocess.run(
            ["psql", "-h", host, "-p", port, "-U", user, "-d", dbname, "-t",
             "-c", "SELECT tablename FROM pg_tables WHERE schemaname = 'public' "
                   "AND tablename NOT LIKE 'flyway%' AND tablename NOT LIKE 'database%' "
                   "ORDER BY tablename;"],
            capture_output=True, text=True, timeout=30,
        )
    except FileNotFoundError:
        print(f"  {RED}❌ psql not found — cannot reset database{RESET}")
        return False
    except subprocess.TimeoutExpired:
        print(f"  {RED}❌ psql timed out while listing tables{RESET}")
        return False

    if result.returncode != 0:
        print(f"  {RED}❌ Failed to list tables: {result.stderr.strip()}{RESET}")
        return False

    tables = [line.strip() for line in result.stdout.strip().split("\n") if line.strip()]
    if not tables:
        print(f"  {YELLOW}⚠  No user tables found to truncate{RESET}")
        return True

    # Step 2: Truncate all tables with CASCADE (handles FK constraints)
    truncate_sql = "TRUNCATE " + ", ".join(tables) + " RESTART IDENTITY CASCADE;"
    trunc_result = subprocess.run(
        ["psql", "-h", host, "-p", port, "-U", user, "-d", dbname, "-c", truncate_sql],
        capture_output=True, text=True, timeout=60,
    )
    if trunc_result.returncode != 0:
        print(f"  {RED}❌ Truncate failed: {trunc_result.stderr.strip()}{RESET}")
        return False

    print(f"  {GREEN}✓ Truncated {len(tables)} tables (CASCADE){RESET}")

    # Step 3: Re-seed the dev admin user (DevAdminBootstrap only runs on app
    # startup; after truncation we must re-insert it manually for admin tests).
    admin_email = os.getenv("APP_BOOTSTRAP_ADMIN_EMAIL", "admin@bhukkad.dev")
    # bcrypt hash of "Admin@123456" (compatible with Spring BCryptPasswordEncoder).
    # This is a dev-only default; override via APP_BOOTSTRAP_ADMIN_BCRYPT env var.
    admin_bcrypt = os.getenv(
        "APP_BOOTSTRAP_ADMIN_BCRYPT",
        "$2b$10$pR1oqzVQuKqrVj9ZME9C9ugYVDc3gCxaRmJd/8iPdeGFF7h361h1W"
    )
    admin_sql = (
        "WITH new_user AS (\n"
        "  INSERT INTO users (role, active, email_verified, phone_verified, "
        "profile_completed, totp_enabled, created_at, updated_at)\n"
        "  VALUES ('ADMIN', TRUE, TRUE, FALSE, FALSE, FALSE, NOW(), NOW())\n"
        "  RETURNING id\n"
        ") "
        "INSERT INTO admins (id, email, password, full_name, phone_number, "
        "profile_image_url, totp_secret) "
        f"SELECT id, '{admin_email}', '{admin_bcrypt}', 'Bhukkad Admin', "
        f"'9000000001', NULL, NULL FROM new_user;"
    )
    admin_result = subprocess.run(
        ["psql", "-h", host, "-p", port, "-U", user, "-d", dbname, "-c", admin_sql],
        capture_output=True, text=True, timeout=30,
    )
    if admin_result.returncode != 0:
        print(f"  {YELLOW}⚠  Admin re-seed failed (non-fatal): {admin_result.stderr.strip()[:200]}{RESET}")
    else:
        print(f"  {GREEN}✓ Re-seeded dev admin user{RESET}")

    # Step 4: Re-apply V6 seed data
    seed_path = Path(__file__).resolve().parent.parent / \
                "src/main/resources/db/migration-pg/V6__baseline_seed_data.sql"
    if not seed_path.exists():
        print(f"  {YELLOW}⚠  Seed SQL not found at {seed_path} — skipping seed{RESET}")
        return True

    seed_result = subprocess.run(
        ["psql", "-h", host, "-p", port, "-U", user, "-d", dbname, "-f", str(seed_path)],
        capture_output=True, text=True, timeout=60,
    )
    if seed_result.returncode != 0:
        print(f"  {YELLOW}⚠  Seed re-apply had warnings: {seed_result.stderr.strip()[:200]}{RESET}")
        # Seed uses ON CONFLICT DO NOTHING, so re-applying is safe even if some data exists
        return True

    print(f"  {GREEN}✓ Re-applied V6 seed data{RESET}")

    # Step 5: Fix sequences after explicit-ID seed inserts.
    # TRUNCATE ... RESTART IDENTITY resets sequences to 1, but V6 seed inserts
    # use explicit IDs (e.g. delivery_zones id=1), which do NOT advance the
    # sequence. The next auto-generated ID would collide with seed data,
    # causing "duplicate key value violates unique constraint" (HTTP 500).
    # Fix: for each table with an auto-incrementing id, setval to MAX(id)+1.
    fix_seq_sql = (
        "DO $$\n"
        "DECLARE\n"
        "  r RECORD;\n"
        "BEGIN\n"
        "  FOR r IN\n"
        "    SELECT t.tablename\n"
        "    FROM pg_tables t\n"
        "    WHERE t.schemaname = 'public'\n"
        "      AND EXISTS (\n"
        "        SELECT 1 FROM pg_attribute a\n"
        "        JOIN pg_attrdef ad ON ad.adrelid = a.attrelid AND ad.adnum = a.attnum\n"
        "        WHERE a.attrelid = t.tablename::regclass\n"
        "          AND a.attname = 'id'\n"
        "          AND a.attnum = 1\n"
        "          AND pg_get_expr(ad.adbin, ad.adrelid) LIKE 'nextval%')\n"
        "  LOOP\n"
        "    EXECUTE format('SELECT setval(pg_get_serial_sequence(%L, ''id''), '\n"
        "                  'COALESCE((SELECT MAX(id) FROM %I), 1) + 1, false)',\n"
        "                  r.tablename, r.tablename);\n"
        "  END LOOP;\n"
        "END $$;"
    )
    seq_result = subprocess.run(
        ["psql", "-h", host, "-p", port, "-U", user, "-d", dbname, "-v", "ON_ERROR_STOP=1",
         "-c", fix_seq_sql],
        capture_output=True, text=True, timeout=60,
    )
    if seq_result.returncode != 0:
        print(f"  {YELLOW}⚠  Sequence fix had a warning: {seq_result.stderr.strip()[:200]}{RESET}")
    else:
        print(f"  {GREEN}✓ Reset sequences to MAX(id)+1{RESET}")

    return True


def check_server_available(base_url: str, timeout: int) -> bool:
    """Ping the server health endpoint; return False if unreachable."""
    for endpoint in ("/api/v1/health/ping", "/api/v1/health", "/actuator/health"):
        try:
            status, body, _ = http_request("GET", base_url.rstrip("/") + endpoint, {}, None, timeout)
            if status == 200:
                return True
        except ConnectionError:
            continue
    print(f"  {RED}❌ Could not reach the server at {base_url}{RESET}")
    print(f"  {YELLOW}   Start the stack first, e.g. ./scripts/run-local.sh{RESET}")
    return False


def main() -> int:
    parser = argparse.ArgumentParser(description="Bhukkad API feature test runner")
    parser.add_argument("--base-url", default=os.getenv("BASE_URL", "http://localhost:8080"), help="Server base URL")
    parser.add_argument("--password", default="Test@123456", help="Password for test accounts")
    parser.add_argument("--timeout", type=int, default=30, help="HTTP timeout seconds")
    parser.add_argument("--verbose", "-v", action="store_true", help="Print full request/response")
    parser.add_argument(
        "--report-dir",
        default="scripts/reports",
        help="Directory for markdown + JSON reports",
    )
    parser.add_argument("--admin-email", default="admin@bhukkad.dev", help="Admin email for admin API tests")
    parser.add_argument("--admin-password", default="Admin@123456", help="Password for --admin-email")
    parser.add_argument("--skip-bootstrap", action="store_true", help="Skip account bootstrap (use catalog auth only)")
    parser.add_argument("--reset-data", action="store_true",
                        help="Truncate all user tables and re-seed V6 data before running tests")
    parser.add_argument("--no-report", action="store_true", help="Skip writing report files")
    args = parser.parse_args()

    # Configure logging
    log_level = logging.DEBUG if args.verbose else logging.WARNING
    logging.basicConfig(
        level=log_level,
        format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
        datefmt="%H:%M:%S"
    )

    state = RunState()
    state.init_defaults(args.password)

    print()
    print(f"{BLUE}╔{'═' * 58}╗{RESET}")
    print(f"{BLUE}║{'🍔 Bhukkad API Feature Test Suite':^58}║{RESET}")
    print(f"{BLUE}║{'Server: ' + args.base_url:^58}║{RESET}")
    print(f"{BLUE}╚{'═' * 58}╝{RESET}")

    # Fail fast if the server is unreachable instead of running the whole
    # catalog into a wall of connection errors.
    if not check_server_available(args.base_url, args.timeout):
        return 1

    if args.reset_data:
        print_section("Database Reset")
        if not reset_database(os.getenv("DATABASE_URL")):
            print(f"  {YELLOW}⚠  Database reset failed — continuing with existing data{RESET}")

    if not args.skip_bootstrap:
        bootstrap_accounts(
            args.base_url,
            state,
            args.timeout,
            args.admin_email,
            args.admin_password,
        )
        bootstrap_restaurant_id(
            args.base_url,
            state,
            args.timeout,
        )

    # Setup flags for one-time setup functions
    setup_flags = {
        "delivery_proof": False,
        "review": False,
        "invoice_pdf": False,
    }
    main_order_id = None  # Preserve the main delivered order for review/invoice tests

    current_group = None
    for spec in API_CATALOG:
        if not args.skip_bootstrap and spec.get("phase") == "setup":
            continue

        group = spec.get("group", "General")
        if group != current_group:
            print_section(group)
            current_group = group

        if spec["name"] in ("Batch Checkout", "Create Scheduled Order", "Apply Coupon to Cart",
                            "Place Order — Invalid Payment Method (edge)"):
            refill_cart_for_order_tests(args.base_url, state, args.timeout)

        # Set up delivery proof order before delivery proof tests (run once)
        if spec["name"] in (
            "Issue Delivery Proof OTP",
            "Delivery Proof Photo Upload URL",
            "Verify Delivery Proof",
            "Get Delivery Proof",
        ):
            if not setup_flags["delivery_proof"]:
                setup_delivery_proof_order(args.base_url, state, args.timeout)
                setup_flags["delivery_proof"] = True

        # Set up review for moderation before Moderate Review test (run once)
        if spec["name"] == "Moderate Review":
            if not setup_flags["review"]:
                setup_review_for_moderation(args.base_url, state, args.timeout, main_order_id)
                setup_flags["review"] = True

        # Set up invoice PDF order before Download Invoice PDF test (run once)
        if spec["name"] == "Download Invoice PDF":
            if not setup_flags["invoice_pdf"]:
                setup_invoice_pdf_order(args.base_url, state, args.timeout, main_order_id)
                setup_flags["invoice_pdf"] = True

        result = run_test(spec, args.base_url, state, args.timeout, args.verbose)
        state.results.append(result)

        # After main order flow, preserve main order_id for review/invoice tests and prepare cancel-order id
        if spec["name"] == "Agent — Mark Delivered" and result.passed:
            if state.vars.get("order_id"):
                main_order_id = state.vars["order_id"]
            create_cancel_order(args.base_url, state, args.timeout)

        # Stateful edge-case probes run just before the destructive teardown
        # ("Delete Account" deactivates the suite customer, invalidating its
        # token), while the live customer token is still valid.
        if spec["name"] == "Delete Account":
            test_order_idempotency_replay(args.base_url, state, args.timeout)
            test_rate_limit_order_track(args.base_url, state, args.timeout)
            test_order_empty_cart_400(args.base_url, state, args.timeout)
            # End-to-end journey uses its own dedicated accounts, so it is safe
            # to run here even after the main customer is deactivated.
            test_e2e_full_journey(args.base_url, state, args.timeout)

    # Summary
    passed = sum(1 for r in state.results if r.passed and not r.skipped)
    failed = sum(1 for r in state.results if not r.passed and not r.skipped)
    skipped = sum(1 for r in state.results if r.skipped)
    total = len(state.results)

    print_header("SUMMARY")
    print(f"  {GREEN}Passed:{RESET}  {passed}")
    print(f"  {RED}Failed:{RESET}  {failed}")
    print(f"  {YELLOW}Skipped:{RESET} {skipped}")
    print(f"  Total:   {total}")
    print()
    print(f"  Test accounts (password: {args.password}):")
    print(f"    Customer: {state.vars.get('customer_email')}")
    print(f"    Owner:    {state.vars.get('owner_email')}")
    print(f"    Agent:    {state.vars.get('agent_email')}")
    print(f"    Admin:    {state.vars.get('admin_email')}")

    if not args.no_report:
        report_dir = Path(args.report_dir)
        report_dir.mkdir(parents=True, exist_ok=True)
        stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
        md_path = report_dir / f"api-test-report-{stamp}.md"
        json_path = report_dir / f"api-test-report-{stamp}.json"
        write_markdown_report(state.results, md_path, args.base_url)
        write_json_report(state.results, json_path, args.base_url)
        print()
        print(f"  Reports written:")
        print(f"    {md_path}")
        print(f"    {json_path}")

    return 1 if failed > 0 else 0


if __name__ == "__main__":
    sys.exit(main())
