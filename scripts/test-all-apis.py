#!/usr/bin/env python3
"""
Bhukkad API Feature Test Runner

Exercises REST endpoints in dependency order, printing and saving for each call:
  - API name & description
  - HTTP method & URL
  - Request headers/body
  - Response status & body
  - PASS / FAIL / SKIP

Script Usage:
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
import re
import secrets
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
            "customer_phone": self._unique_phone("98"),
            "owner_phone": self._unique_phone("97"),
            "agent_phone": self._unique_phone("96"),
            "admin_phone": self._unique_phone("95"),
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
    if isinstance(value, str):
        resolved = resolve_string(value, state)
        if key in STRING_JSON_KEYS:
            return resolved
        if key in NUMERIC_JSON_KEYS:
            if resolved.isdigit():
                return int(resolved)
            try:
                if "." in resolved and resolved.replace(".", "", 1).isdigit():
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
        body_bytes = json.dumps(body_obj).encode("utf-8")

    start = time.perf_counter()
    try:
        status, response_text, _ = http_request(method, url, headers, body_bytes, timeout)
        duration_ms = int((time.perf_counter() - start) * 1000)
        expected = spec.get("expected", [200])
        passed = status in expected

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


def main() -> int:
    parser = argparse.ArgumentParser(description="Bhukkad API feature test runner")
    parser.add_argument("--base-url", default="http://localhost:8080", help="Server base URL")
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

    current_group = None
    for spec in API_CATALOG:
        if not args.skip_bootstrap and spec.get("phase") == "setup":
            continue

        group = spec.get("group", "General")
        if group != current_group:
            print_section(group)
            current_group = group

        if spec["name"] in ("Batch Checkout", "Create Scheduled Order", "Apply Coupon to Cart"):
            refill_cart_for_order_tests(args.base_url, state, args.timeout)

        result = run_test(spec, args.base_url, state, args.timeout, args.verbose)
        state.results.append(result)

        # After main order flow, prepare cancel-order id
        if spec["name"] == "Agent — Mark Delivered" and result.passed:
            create_cancel_order(args.base_url, state, args.timeout)

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
