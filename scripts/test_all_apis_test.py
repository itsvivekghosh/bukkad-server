#!/usr/bin/env python3
"""
Unit tests for helper functions in scripts/test-all-apis.py.

Run with:
    python3 -m pytest scripts/test_all_apis_test.py -v
or:
    python3 scripts/test_all_apis_test.py
"""

from __future__ import annotations

import importlib.util
import io
import json
import os
import re
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest.mock import MagicMock, patch

# Allow running from repo root or scripts/
_SCRIPTS_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(_SCRIPTS_DIR))

# The main script uses hyphens in its filename, so we import it via importlib.
_spec = importlib.util.spec_from_file_location("test_all_apis", _SCRIPTS_DIR / "test-all-apis.py")
test_all_apis = importlib.util.module_from_spec(_spec)
sys.modules["test_all_apis"] = test_all_apis  # register before exec for dataclass support
_spec.loader.exec_module(test_all_apis)

# Access functions/classes from the dynamically-loaded module.
RunState = test_all_apis.RunState
TestResult = test_all_apis.TestResult
resolve_string = test_all_apis.resolve_string
resolve_value = test_all_apis.resolve_value
extract_json_path = test_all_apis.extract_json_path
truncate = test_all_apis.truncate
pretty_json = test_all_apis.pretty_json
STRING_JSON_KEYS = test_all_apis.STRING_JSON_KEYS
NUMERIC_JSON_KEYS = test_all_apis.NUMERIC_JSON_KEYS
AUTH_MAP = test_all_apis.AUTH_MAP
http_request = test_all_apis.http_request
run_test = test_all_apis.run_test
apply_auth_extract = test_all_apis.apply_auth_extract
check_server_available = test_all_apis.check_server_available
_edge_result = test_all_apis._edge_result
write_markdown_report = test_all_apis.write_markdown_report
write_json_report = test_all_apis.write_json_report
print_result = test_all_apis.print_result
print_header = test_all_apis.print_header
print_section = test_all_apis.print_section
indent_block = test_all_apis.indent_block
reset_database = test_all_apis.reset_database

import api_catalog  # noqa: E402
from api_catalog import API_CATALOG, BODY_TEMPLATES  # noqa: E402


class TestResolveString(unittest.TestCase):
    """Tests for resolve_string — placeholder substitution."""

    def test_substitutes_var(self):
        state = RunState()
        state.vars["customer_email"] = "test@example.com"
        self.assertEqual(resolve_string("{customer_email}", state), "test@example.com")

    def test_substitutes_token(self):
        state = RunState()
        state.tokens["customer_token"] = "jwt.token.here"
        self.assertEqual(resolve_string("{customer_token}", state), "jwt.token.here")

    def test_unknown_placeholder_unchanged(self):
        state = RunState()
        result = resolve_string("{unknown_var}", state)
        self.assertEqual(result, "{unknown_var}")

    def test_multiple_placeholders(self):
        state = RunState()
        state.vars["name"] = "Alice"
        state.vars["id"] = "42"
        result = resolve_string("/{name}/{id}/profile", state)
        self.assertEqual(result, "/Alice/42/profile")

    def test_no_placeholders(self):
        state = RunState()
        self.assertEqual(resolve_string("/api/v1/health", state), "/api/v1/health")

    def test_empty_string(self):
        state = RunState()
        self.assertEqual(resolve_string("", state), "")

    def test_mixed_known_and_unknown(self):
        state = RunState()
        state.vars["known"] = "yes"
        result = resolve_string("/api/v1/{known}/{unknown}", state)
        self.assertEqual(result, "/api/v1/yes/{unknown}")


class TestResolveValue(unittest.TestCase):
    """Tests for resolve_value — placeholder resolution + type coercion."""

    def test_string_no_key_stays_string(self):
        state = RunState()
        state.vars["email"] = "a@b.com"
        result = resolve_value("{email}", state, key=None)
        self.assertEqual(result, "a@b.com")

    def test_string_json_key_keeps_string(self):
        """Keys in STRING_JSON_KEYS must never be coerced to int/float."""
        state = RunState()
        state.vars["phone"] = "9876543210"
        result = resolve_value("{phone}", state, key="phoneNumber")
        self.assertIsInstance(result, str)
        self.assertEqual(result, "9876543210")

    def test_numeric_json_key_converts_to_int(self):
        state = RunState()
        state.vars["qty"] = "5"
        result = resolve_value("{qty}", state, key="quantity")
        self.assertIsInstance(result, int)
        self.assertEqual(result, 5)

    def test_numeric_json_key_converts_to_float(self):
        state = RunState()
        state.vars["price"] = "199.50"
        result = resolve_value("{price}", state, key="price")
        self.assertIsInstance(result, float)
        self.assertAlmostEqual(result, 199.50)

    def test_dict_resolution(self):
        state = RunState()
        state.vars["item_id"] = "42"
        state.vars["phone"] = "9876543210"
        template = {"menuItemId": "{item_id}", "phoneNumber": "{phone}", "quantity": 3}
        result = resolve_value(template, state)
        self.assertEqual(result["menuItemId"], 42)
        self.assertEqual(result["phoneNumber"], "9876543210")
        self.assertEqual(result["quantity"], 3)
        self.assertIsInstance(result["phoneNumber"], str)

    def test_list_resolution(self):
        state = RunState()
        state.vars["id"] = "1"
        template = ["{id}", "literal", 42]
        result = resolve_value(template, state)
        # List items have no key context, so placeholders resolve to str
        self.assertEqual(result[0], "1")
        self.assertEqual(result[1], "literal")
        self.assertEqual(result[2], 42)

    def test_non_string_value_passes_through(self):
        state = RunState()
        result = resolve_value(42, state)
        self.assertEqual(result, 42)

    def test_none_value_passes_through(self):
        state = RunState()
        result = resolve_value(None, state)
        self.assertIsNone(result)

    def test_numeric_string_not_in_key_sets_stays_string(self):
        """Without a known key, numeric-looking strings stay strings."""
        state = RunState()
        state.vars["x"] = "123"
        result = resolve_value("{x}", state, key=None)
        self.assertIsInstance(result, str)
        self.assertEqual(result, "123")

    def test_float_with_multiple_dots_stays_string(self):
        state = RunState()
        state.vars["v"] = "1.2.3"
        result = resolve_value("{v}", state, key="price")
        self.assertIsInstance(result, str)
        self.assertEqual(result, "1.2.3")

    def test_non_digit_numeric_string_with_key_stays_unresolved(self):
        state = RunState()
        state.vars["v"] = "abc"
        result = resolve_value("{v}", state, key="quantity")
        self.assertEqual(result, "abc")


class TestExtractJsonPath(unittest.TestCase):
    """Tests for extract_json_path — dot-notation JSON traversal."""

    def test_simple_key(self):
        self.assertEqual(extract_json_path({"a": 1}, "a"), 1)

    def test_nested_key(self):
        self.assertEqual(extract_json_path({"a": {"b": {"c": 42}}}, "a.b.c"), 42)

    def test_list_index(self):
        self.assertEqual(extract_json_path({"data": [10, 20, 30]}, "data.1"), 20)

    def test_deep_nested_with_list(self):
        data = {"data": [{"id": 5}, {"id": 10}]}
        self.assertEqual(extract_json_path(data, "data.1.id"), 10)

    def test_missing_key_returns_none(self):
        self.assertIsNone(extract_json_path({"a": 1}, "b"))

    def test_missing_nested_key_returns_none(self):
        self.assertIsNone(extract_json_path({"a": {"b": 1}}, "a.c.d"))

    def test_index_out_of_bounds_returns_none(self):
        self.assertIsNone(extract_json_path({"data": [1]}, "data.5"))

    def test_non_digit_part_on_non_dict_returns_none(self):
        self.assertIsNone(extract_json_path([1, 2, 3], "a.b"))

    def test_none_current_returns_none(self):
        self.assertIsNone(extract_json_path(None, "a.b.c"))

    def test_empty_path(self):
        """An empty path returns None (no key to look up)."""
        self.assertIsNone(extract_json_path({"a": 1}, ""))

    def test_array_index_on_dict(self):
        self.assertIsNone(extract_json_path({"a": 1}, "data.0"))

    def test_token_extraction(self):
        data = {"data": {"token": "abc123"}}
        self.assertEqual(extract_json_path(data, "data.token"), "abc123")


class TestTruncate(unittest.TestCase):
    """Tests for truncate — text truncation with suffix annotation."""

    def test_short_text_unchanged(self):
        self.assertEqual(truncate("hello", 2000), "hello")

    def test_exact_limit_unchanged(self):
        text = "x" * 2000
        self.assertEqual(truncate(text, 2000), text)

    def test_long_text_truncated(self):
        text = "x" * 3000
        result = truncate(text, 2000)
        self.assertTrue(result.startswith("x" * 2000))
        self.assertIn("... [1000 more chars]", result)
        self.assertTrue(result.endswith("]"))

    def test_custom_limit(self):
        text = "hello world"
        result = truncate(text, 5)
        self.assertEqual(result, "hello\n... [6 more chars]")

    def test_empty_string(self):
        self.assertEqual(truncate("", 10), "")


class TestPrettyJson(unittest.TestCase):
    """Tests for pretty_json — JSON pretty-printing with fallback."""

    def test_valid_json(self):
        text = '{"a": 1, "b": [2, 3]}'
        result = pretty_json(text)
        self.assertIn('"a": 1', result)
        self.assertIn('"b": [', result)

    def test_invalid_json_returns_original(self):
        text = "not json"
        self.assertEqual(pretty_json(text), text)

    def test_empty_string(self):
        self.assertEqual(pretty_json(""), "")

    def test_none_like_input(self):
        self.assertEqual(pretty_json("null"), "null")
        self.assertEqual(pretty_json("true"), "true")

    def test_unicode_preserved(self):
        text = '{"name": "café"}'
        result = pretty_json(text)
        self.assertIn("café", result)


class TestRunState(unittest.TestCase):
    """Tests for RunState — state management and sentinel values."""

    def test_init_defaults(self):
        state = RunState()
        state.init_defaults("SecretPass123")
        self.assertIn("timestamp", state.vars)
        self.assertIn("run_id", state.vars)
        self.assertEqual(state.vars["password"], "SecretPass123")
        self.assertIn("customer_email", state.vars)
        self.assertIn("owner_email", state.vars)
        self.assertIn("agent_email", state.vars)
        self.assertIn("admin_email", state.vars)
        self.assertIn("idempotency_key", state.vars)
        self.assertTrue(state.vars["customer_email"].endswith("@bhukkad.test"))

    def test_init_defaults_phone_format(self):
        state = RunState()
        state.init_defaults("Test@123456")
        phone = state.vars["customer_phone"]
        self.assertTrue(phone.startswith("98"))
        self.assertEqual(len(phone), 10)

    def test_init_defaults_unique_run_id(self):
        state1 = RunState()
        state1.init_defaults("pw")
        state2 = RunState()
        state2.init_defaults("pw")
        self.assertNotEqual(state1.vars["run_id"], state2.vars["run_id"])

    def test_init_defaults_unique_phone(self):
        state1 = RunState()
        state1.init_defaults("pw")
        state2 = RunState()
        state2.init_defaults("pw")
        self.assertNotEqual(state1.vars["customer_phone"], state2.vars["customer_phone"])

    def test_unique_phone(self):
        state = RunState()
        phone = state._unique_phone("95")
        self.assertTrue(phone.startswith("95"))
        self.assertEqual(len(phone), 10)
        self.assertTrue(phone[2:].isdigit())

    def test_next_webhook_payment_id_incrementing(self):
        state = RunState()
        first = state.next_webhook_payment_id()
        second = state.next_webhook_payment_id()
        third = state.next_webhook_payment_id()
        self.assertEqual(first, "pay_test1")
        self.assertEqual(second, "pay_test2")
        self.assertEqual(third, "pay_test3")

    def test_end_of_time_sentinel(self):
        """Verify RunState stores a sentinel end-of-time correctly, mirroring
        how the Java service layer passes CursorUtils.END_OF_TIME as a
        non-null cursorCreatedAt on the first page."""
        state = RunState()
        state.vars["end_time"] = "9999-12-31T23:59:59"
        self.assertEqual(state.vars["end_time"], "9999-12-31T23:59:59")

    def test_store_token_after_auth(self):
        state = RunState()
        state.tokens["customer_token"] = "jwt.example.token"
        self.assertEqual(state.tokens["customer_token"], "jwt.example.token")


class TestTestResult(unittest.TestCase):
    """Tests for TestResult dataclass — construction and defaults."""

    def test_default_values(self):
        result = TestResult(
            name="Test",
            group="General",
            description="desc",
            method="GET",
            url="/api/v1/test",
            request_headers={},
            request_body=None,
            status_code=200,
            response_body="{}",
            passed=True,
            skipped=False,
        )
        self.assertEqual(result.skip_reason, "")
        self.assertEqual(result.duration_ms, 0)
        self.assertEqual(result.error, "")

    def test_skipped_result_defaults(self):
        result = TestResult(
            name="Skipped",
            group="General",
            description="",
            method="GET",
            url="",
            request_headers={},
            request_body=None,
            status_code=None,
            response_body="",
            passed=False,
            skipped=True,
            skip_reason="No token",
        )
        self.assertTrue(result.skipped)
        self.assertEqual(result.skip_reason, "No token")


class TestApiCatalogIntegrity(unittest.TestCase):
    """Structural sanity checks on the API catalog."""

    def test_catalog_not_empty(self):
        self.assertGreater(len(API_CATALOG), 50)

    def test_every_entry_has_required_keys(self):
        for spec in API_CATALOG:
            self.assertIn("name", spec, f"Missing 'name' in {spec}")
            self.assertIn("method", spec, f"Missing 'method' in {spec}")
            self.assertIn("path", spec, f"Missing 'path' in {spec}")
            self.assertIn("expected", spec, f"Missing 'expected' in {spec}")

    def test_method_is_valid_http(self):
        valid_methods = {"GET", "POST", "PUT", "PATCH", "DELETE"}
        for spec in API_CATALOG:
            self.assertIn(spec["method"].upper(), valid_methods,
                          f"Invalid method '{spec['method']}' in {spec['name']}")

    def test_expected_is_list(self):
        for spec in API_CATALOG:
            self.assertIsInstance(spec["expected"], list,
                                  f"'expected' must be a list in {spec['name']}")

    def test_body_key_templates_exist(self):
        """Every spec that references a body_key must have a matching template."""
        for spec in API_CATALOG:
            bk = spec.get("body_key")
            if bk:
                self.assertIn(bk, BODY_TEMPLATES,
                              f"body_key '{bk}' in '{spec['name']}' has no template")

    def test_placeholder_resolution_in_paths(self):
        """Path placeholders must either appear in a spec's 'requires' list
        (so they are skipped when unavailable) or be resolvable from RunState
        vars (init_defaults), tokens, or be a known var like customer_email."""
        # Build the set of all variables that init_defaults produces.
        state = RunState()
        state.init_defaults("Test@123456")
        runtime_keys = set(state.vars.keys())
        runtime_keys.update(state.tokens.keys())
        # Also allow any placeholder that has a matching 'requires' entry.
        requires_keys = set()
        for spec in API_CATALOG:
            for r in spec.get("requires", []):
                requires_keys.add(r)
        all_known = runtime_keys | requires_keys
        for spec in API_CATALOG:
            path = spec["path"]
            for ph in re.findall(r"\{(\w+)\}", path):
                self.assertIn(ph, all_known,
                              f"Unknown placeholder {{{ph}}} in path '{path}' ({spec['name']})")

    def test_no_duplicate_names(self):
        """Pre-existing catalog has some duplicate names across lifecycle sections
        (e.g. setup + teardown specs with the same action name). We allow a small
        set of known repeats but flag any unexpected ones."""
        names = [s["name"] for s in API_CATALOG]
        from collections import Counter
        counts = Counter(names)
        dups = {n: c for n, c in counts.items() if c > 1}
        # These duplicates are intentional (setup + teardown lifecycle specs).
        allowed = {"Cancel Order", "Batch Checkout", "Accept Order", "Mark Order Ready",
                   "Assign Delivery Agent", "Agent — Mark Picked Up", "Agent — Mark Delivered",
                   "Update Profile"}
        unexpected = dups.keys() - allowed
        self.assertEqual(unexpected, set(),
                         f"Unexpected duplicate spec names: {unexpected}")


class TestEdgeCaseCatalogEntries(unittest.TestCase):
    """Verify the new edge-case entries were added correctly."""

    def _find(self, name):
        return next(s for s in API_CATALOG if s["name"] == name)

    def test_invalid_jwt_edge_case(self):
        spec = self._find("Invalid JWT on Protected Route (edge)")
        self.assertEqual(spec["method"], "GET")
        self.assertEqual(spec["expected"], [401, 403])
        self.assertIn("Authorization", spec["headers"])

    def test_ssti_payload(self):
        spec = self._find("SQL-Injection-Shaped Search Param (edge)")
        self.assertEqual(spec["method"], "GET")
        self.assertIn("keyword=", spec["path"])
        self.assertEqual(spec["expected"], [200, 400])

    def test_batch_checkout_missing_idempotency(self):
        spec = self._find("Batch Checkout — Missing Idempotency-Key (edge)")
        self.assertEqual(spec["method"], "POST")
        self.assertNotIn("Idempotency-Key", spec.get("headers", {}))
        self.assertEqual(spec["expected"], [200, 400, 409])

    def test_refresh_token_invalid_template(self):
        self.assertIn("refresh_token_invalid", BODY_TEMPLATES)
        self.assertEqual(BODY_TEMPLATES["refresh_token_invalid"]["refreshToken"],
                         "garbage.invalid.token")


class TestEdgeCaseCatalogExtended(unittest.TestCase):
    """Verify the additional edge-case entries and their body templates."""

    def _find(self, name):
        return next(s for s in API_CATALOG if s["name"] == name)

    def test_missing_auth_header_edge_case(self):
        spec = self._find("Missing Authorization Header on Protected Route (edge)")
        self.assertEqual(spec["method"], "GET")
        self.assertIsNone(spec.get("auth"))
        self.assertIn(401, spec["expected"])

    def test_negative_tip_edge_case(self):
        spec = self._find("Place Order — Negative Tip Amount (edge)")
        self.assertEqual(spec["method"], "POST")
        self.assertEqual(spec["body_key"], "order_negative_tip")
        self.assertIn(400, spec["expected"])
        self.assertTrue(spec.get("optional"))

    def test_empty_search_keyword_edge_case(self):
        spec = self._find("Search — Empty Keyword (edge)")
        self.assertEqual(spec["method"], "GET")
        self.assertIn("keyword=", spec["path"])
        self.assertIn(200, spec["expected"])

    def test_negative_page_size_edge_case(self):
        spec = self._find("Customer Orders — Negative Page Size (edge)")
        self.assertEqual(spec["method"], "GET")
        self.assertIn("size=-5", spec["path"])
        self.assertIn(spec["auth"], ("customer", None))
        self.assertIn(400, spec["expected"])

    def test_float_quantity_edge_case(self):
        spec = self._find("Add to Cart — Float Quantity (edge)")
        self.assertEqual(spec["method"], "POST")
        self.assertEqual(spec["body_key"], "cart_add_float_qty")
        self.assertIn(spec["auth"], ("customer", None))

    def test_register_sql_injection_email_edge_case(self):
        spec = self._find("Register — SQL Injection in Email (edge)")
        self.assertEqual(spec["method"], "POST")
        self.assertEqual(spec["body_key"], "register_sql_injection_email")
        self.assertIn(400, spec["expected"])

    def test_delete_menu_item_associated_edge_case(self):
        spec = self._find("Delete Menu Item — Associated with Existing Orders (edge)")
        self.assertEqual(spec["method"], "DELETE")
        self.assertIn(400, spec["expected"])
        self.assertIn(409, spec["expected"])

    def test_get_nonexistent_order_edge_case(self):
        spec = self._find("Get Order by ID — Nonexistent Order (edge)")
        self.assertEqual(spec["method"], "GET")
        self.assertIn(404, spec["expected"])

    def test_refresh_missing_field_edge_case(self):
        spec = self._find("Refresh Token — Missing Token Field (edge)")
        self.assertEqual(spec["method"], "POST")
        self.assertEqual(spec["body"], {})
        self.assertIn(400, spec["expected"])

    def test_cart_invalid_item_edge_case(self):
        spec = self._find("Add to Cart — Invalid Menu Item ID (edge)")
        self.assertEqual(spec["method"], "POST")
        self.assertEqual(spec["body_key"], "cart_add_invalid_item")
        self.assertIn(spec["auth"], ("customer", None))

    def test_new_body_templates_exist(self):
        for key in ("order_negative_tip", "cart_add_float_qty",
                     "register_sql_injection_email"):
            with self.subTest(template=key):
                self.assertIn(key, BODY_TEMPLATES,
                              f"Body template '{key}' is referenced by an edge-case spec "
                              f"but not defined in BODY_TEMPLATES")

    def test_new_body_templates_structure(self):
        self.assertEqual(BODY_TEMPLATES["order_negative_tip"]["tipAmount"], -50.0)
        self.assertEqual(BODY_TEMPLATES["cart_add_float_qty"]["quantity"], 2.5)
        self.assertIn("DROP TABLE", BODY_TEMPLATES["register_sql_injection_email"]["email"])

    def test_all_new_edge_cases_in_catalog_integrity(self):
        """Re-run the placeholder/requires integrity check for the full catalog
        so that adding new specs cannot silently introduce unresolved placeholders."""
        state = RunState()
        state.init_defaults("Test@123456")
        runtime_keys = set(state.vars.keys())
        runtime_keys.update(state.tokens.keys())
        requires_keys = set()
        for spec in API_CATALOG:
            for r in spec.get("requires", []):
                requires_keys.add(r)
        all_known = runtime_keys | requires_keys
        for spec in API_CATALOG:
            path = spec["path"]
            for ph in re.findall(r"\{(\w+)\}", path):
                self.assertIn(ph, all_known,
                              f"Unknown placeholder {{{ph}}} in path '{path}' ({spec['name']})")


class TestHttpRequest(unittest.TestCase):
    """Tests for http_request — HTTP execution and error handling."""

    def _mock_response(self, status=200, body=b'{"ok": true}', headers=None):
        resp = MagicMock()
        resp.__enter__ = MagicMock(return_value=resp)
        resp.__exit__ = MagicMock(return_value=False)
        resp.status = status
        resp.read.return_value = body
        resp.headers = headers or {}
        return resp

    @patch("test_all_apis.urlopen")
    def test_success_get(self, mock_urlopen):
        mock_urlopen.return_value = self._mock_response(200, b'{"status":"UP"}')
        status, body, hdrs = http_request("GET", "http://localhost:8080/api/v1/health",
                                         {"Accept": "application/json"}, None, 30)
        self.assertEqual(status, 200)
        self.assertIn("UP", body)

    @patch("test_all_apis.urlopen")
    def test_success_post_with_body(self, mock_urlopen):
        mock_urlopen.return_value = self._mock_response(201, b'{"id":42}')
        status, body, _ = http_request("POST", "http://localhost:8080/api/v1/test",
                                       {"Content-Type": "application/json"}, b'{"x":1}', 30)
        self.assertEqual(status, 201)

    @patch("test_all_apis.urlopen")
    def test_404_returns_status(self, mock_urlopen):
        from urllib.error import HTTPError, URLError
        resp = MagicMock()
        resp.read.return_value = b'{"message":"Not found"}'
        resp.headers = {}
        err = HTTPError(url="http://test", code=404, msg="Not Found", hdrs={}, fp=resp)
        mock_urlopen.side_effect = err
        status, body, hdrs = http_request("GET", "http://localhost:8080/not-found", {}, None, 30)
        self.assertEqual(status, 404)
        self.assertIn("Not found", body)

    @patch("test_all_apis.urlopen")
    def test_500_returns_status(self, mock_urlopen):
        from urllib.error import HTTPError
        resp = MagicMock()
        resp.read.return_value = b'{"message":"Internal Server Error"}'
        resp.headers = {}
        err = HTTPError(url="http://test", code=500, msg="Internal", hdrs={}, fp=resp)
        mock_urlopen.side_effect = err
        status, body, _ = http_request("GET", "http://localhost:8080/crash", {}, None, 30)
        self.assertEqual(status, 500)
        self.assertIn("Internal", body)

    @patch("test_all_apis.urlopen")
    def test_connection_error_raises(self, mock_urlopen):
        from urllib.error import URLError
        mock_urlopen.side_effect = URLError("Connection refused")
        with self.assertRaises(ConnectionError):
            http_request("GET", "http://localhost:9999/test", {}, None, 5)

    @patch("test_all_apis.urlopen")
    def test_default_content_type_set_on_body(self, mock_urlopen):
        mock_urlopen.return_value = self._mock_response(200, b'{}')
        http_request("POST", "http://test/api", {}, b'{"x":1}', 30)
        call_args = mock_urlopen.call_args
        req = call_args[0][0] if call_args[0] else call_args[1].get("req")
        # Content-Type should have been added by http_request
        self.assertEqual(req.get_header("Content-type"), "application/json")

    @patch("test_all_apis.urlopen")
    def test_sse_partial_read_handled(self, mock_urlopen):
        from http.client import IncompleteRead
        resp = MagicMock()
        resp.__enter__ = MagicMock(return_value=resp)
        resp.__exit__ = MagicMock(return_value=False)
        resp.status = 200
        resp.headers = {}
        resp.read.side_effect = IncompleteRead(b'data: ok', 100)
        mock_urlopen.return_value = resp
        status, body, _ = http_request("GET", "http://test/sse",
                                       {"Accept": "text/event-stream"}, None, 5)
        self.assertEqual(status, 200)
        self.assertIn("data: ok", body)


class TestRunTest(unittest.TestCase):
    """Tests for the run_test orchestrator — placeholder resolution, auth,
    skip logic, and status matching."""

    def _mock_response_obj(self, status=200, body=b'{}'):
        resp = MagicMock()
        resp.__enter__ = MagicMock(return_value=resp)
        resp.__exit__ = MagicMock(return_value=False)
        resp.status = status
        resp.read.return_value = body
        resp.headers = {}
        return resp

    @patch("test_all_apis.urlopen")
    def test_pass_on_expected_status(self, mock_urlopen):
        mock_urlopen.return_value = self._mock_response_obj(200, b'{"data":{"id":42}}')
        state = RunState()
        state.init_defaults("pw")
        spec = {"name": "Health", "method": "GET", "path": "/api/v1/health",
                "expected": [200]}
        result = run_test(spec, "http://localhost:8080", state, 30, verbose=False)
        self.assertTrue(result.passed)
        self.assertEqual(result.status_code, 200)
        self.assertFalse(result.skipped)

    @patch("test_all_apis.urlopen")
    def test_fail_on_unexpected_status(self, mock_urlopen):
        mock_urlopen.return_value = self._mock_response_obj(500, b'{"message":"boom"}')
        state = RunState()
        spec = {"name": "Crash", "method": "GET", "path": "/api/v1/crash", "expected": [200]}
        result = run_test(spec, "http://localhost:8080", state, 30, verbose=False)
        self.assertFalse(result.passed)
        self.assertEqual(result.status_code, 500)

    @patch("test_all_apis.urlopen")
    def test_skip_when_required_var_missing(self, mock_urlopen):
        state = RunState()
        state.init_defaults("pw")
        # restaurant_id not set, so this should skip
        spec = {"name": "Needs Restaurant", "method": "GET",
                "path": "/api/v1/restaurants/{restaurant_id}", "expected": [200],
                "requires": ["restaurant_id"]}
        result = run_test(spec, "http://localhost:8080", state, 30, verbose=False)
        self.assertTrue(result.skipped)
        self.assertIn("restaurant_id", result.skip_reason)
        mock_urlopen.assert_not_called()

    @patch("test_all_apis.urlopen")
    def test_skip_when_token_missing(self, mock_urlopen):
        state = RunState()
        state.init_defaults("pw")
        # customer_token not set in state.tokens
        spec = {"name": "Protected", "method": "GET", "path": "/api/v1/profile",
                "expected": [200], "auth": "customer"}
        result = run_test(spec, "http://localhost:8080", state, 30, verbose=False)
        self.assertTrue(result.skipped)
        self.assertIn("token", result.skip_reason)
        mock_urlopen.assert_not_called()

    @patch("test_all_apis.urlopen")
    def test_auth_header_added_when_token_present(self, mock_urlopen):
        mock_urlopen.return_value = self._mock_response_obj(200, b'{}')
        state = RunState()
        state.init_defaults("pw")
        state.tokens["customer_token"] = "jwt.example.token"
        spec = {"name": "Protected", "method": "GET", "path": "/api/v1/profile",
                "expected": [200], "auth": "customer"}
        run_test(spec, "http://localhost:8080", state, 30, verbose=False)
        call_args = mock_urlopen.call_args
        req = call_args[0][0] if call_args[0] else call_args[1].get("req")
        self.assertEqual(req.get_header("Authorization"), "Bearer jwt.example.token")

    @patch("test_all_apis.urlopen")
    def test_placeholder_resolution_in_path(self, mock_urlopen):
        mock_urlopen.return_value = self._mock_response_obj(200, b'{"data":{"id":99}}')
        state = RunState()
        state.init_defaults("pw")
        state.vars["customer_id"] = "99"
        spec = {"name": "Get Customer", "method": "GET", "path": "/api/v1/customers/{customer_id}",
                "expected": [200]}
        result = run_test(spec, "http://localhost:8080", state, 30, verbose=False)
        self.assertTrue(result.passed)
        self.assertIn("/api/v1/customers/99", result.url)

    @patch("test_all_apis.urlopen")
    def test_body_template_resolved(self, mock_urlopen):
        mock_urlopen.return_value = self._mock_response_obj(200, b'{}')
        state = RunState()
        state.init_defaults("pw")
        state.vars["customer_email"] = "test@bhukkad.test"
        state.vars["customer_phone"] = "9876543210"
        spec = {"name": "Register", "method": "POST", "path": "/api/v1/auth/register",
                "body_key": "register_customer", "expected": [200]}
        result = run_test(spec, "http://localhost:8080", state, 30, verbose=False)
        self.assertTrue(result.passed)
        # The request body should have been resolved (no placeholders left)
        self.assertIn("test@bhukkad.test", json.dumps(result.request_body))

    @patch("test_all_apis.urlopen")
    def test_inline_body_resolved(self, mock_urlopen):
        mock_urlopen.return_value = self._mock_response_obj(200, b'{}')
        state = RunState()
        state.init_defaults("pw")
        state.vars["menu_item_id"] = "42"
        spec = {"name": "Inline Body", "method": "POST", "path": "/api/v1/cart/add",
                "body": {"menuItemId": "{menu_item_id}", "quantity": 1}, "expected": [200]}
        result = run_test(spec, "http://localhost:8080", state, 30, verbose=False)
        self.assertTrue(result.passed)
        self.assertEqual(result.request_body["menuItemId"], 42)

    @patch("test_all_apis.urlopen")
    def test_query_params_appended(self, mock_urlopen):
        mock_urlopen.return_value = self._mock_response_obj(200, b'{"data":[]}')
        state = RunState()
        state.init_defaults("pw")
        state.vars["restaurant_id"] = "1"
        spec = {"name": "Search Serviceability", "method": "GET",
                "path": "/api/v1/serviceability/check", "expected": [200],
                "query": {"restaurantId": "{restaurant_id}", "latitude": "12.97"},
                "requires": ["restaurant_id"]}
        result = run_test(spec, "http://localhost:8080", state, 30, verbose=False)
        self.assertTrue(result.passed)
        self.assertIn("restaurantId=1", result.url)
        self.assertIn("latitude=12.97", result.url)

    @patch("test_all_apis.urlopen")
    def test_extract_applies_on_pass(self, mock_urlopen):
        mock_urlopen.return_value = self._mock_response_obj(
            200, b'{"data":{"token":"abc.def.ghi","userId":"42","refreshToken":"refresh123"}}')
        state = RunState()
        state.init_defaults("pw")
        spec = {"name": "Login", "method": "POST", "path": "/api/v1/auth/login",
                "body_key": "login_customer", "expected": [200],
                "extract": {"customer_token": "data.token",
                            "customer_id": "data.userId",
                            "customer_refresh_token": "data.refreshToken"}}
        result = run_test(spec, "http://localhost:8080", state, 30, verbose=False)
        self.assertTrue(result.passed)
        self.assertEqual(state.tokens.get("customer_token"), "abc.def.ghi")
        self.assertEqual(state.vars.get("customer_id"), "42")
        self.assertEqual(state.tokens.get("customer_refresh_token"), "refresh123")

    @patch("test_all_apis.urlopen")
    def test_connection_error_handled(self, mock_urlopen):
        from urllib.error import URLError
        mock_urlopen.side_effect = URLError("Connection refused")
        state = RunState()
        state.init_defaults("pw")
        spec = {"name": "Down", "method": "GET", "path": "/api/v1/health", "expected": [200]}
        result = run_test(spec, "http://localhost:8080", state, 5, verbose=False)
        self.assertFalse(result.passed)
        self.assertFalse(result.skipped)
        self.assertTrue(result.error)

    @patch("test_all_apis.urlopen")
    def test_optional_spec_skipped_on_failure(self, mock_urlopen):
        mock_urlopen.return_value = self._mock_response_obj(500, b'{"message":"error"}')
        state = RunState()
        state.init_defaults("pw")
        state.tokens["customer_token"] = "tok"
        spec = {"name": "Optional Fail", "method": "GET", "path": "/api/v1/flaky",
                "expected": [200], "auth": "customer", "optional": True}
        result = run_test(spec, "http://localhost:8080", state, 30, verbose=False)
        self.assertTrue(result.skipped)
        self.assertFalse(result.passed)


class TestApplyAuthExtract(unittest.TestCase):
    """Tests for apply_auth_extract — token and var extraction from JSON."""

    def test_extract_customer_token(self):
        state = RunState()
        state.init_defaults("pw")
        parsed = {"data": {"token": "abc123", "userId": "42", "refreshToken": "rt456"}}
        apply_auth_extract(state,
                           {"customer_token": "data.token",
                            "customer_id": "data.userId",
                            "customer_refresh_token": "data.refreshToken"},
                           parsed)
        self.assertEqual(state.tokens["customer_token"], "abc123")
        self.assertEqual(state.vars["customer_id"], "42")
        self.assertEqual(state.tokens["customer_refresh_token"], "rt456")

    def test_extract_owner_token(self):
        state = RunState()
        parsed = {"data": {"token": "owner.jwt", "userId": "10"}}
        apply_auth_extract(state,
                           {"owner_token": "data.token", "owner_id": "data.userId"},
                           parsed)
        self.assertEqual(state.tokens["owner_token"], "owner.jwt")

    def test_extract_agent_token(self):
        state = RunState()
        parsed = {"data": {"token": "agent.jwt"}}
        apply_auth_extract(state, {"agent_token": "data.token"}, parsed)
        self.assertEqual(state.tokens["agent_token"], "agent.jwt")

    def test_extract_admin_token(self):
        state = RunState()
        parsed = {"data": {"token": "admin.jwt"}}
        apply_auth_extract(state, {"admin_token": "data.token"}, parsed)
        self.assertEqual(state.tokens["admin_token"], "admin.jwt")

    def test_extract_none_val_skipped(self):
        state = RunState()
        state.init_defaults("pw")
        parsed = {"data": {"token": None}}
        apply_auth_extract(state, {"customer_token": "data.token"}, parsed)
        self.assertNotIn("customer_token", state.tokens)

    def test_extract_empty_string_val_skipped(self):
        state = RunState()
        state.init_defaults("pw")
        parsed = {"data": {"token": ""}}
        apply_auth_extract(state, {"customer_token": "data.token"}, parsed)
        self.assertNotIn("customer_token", state.tokens)

    def test_extract_list_first_element(self):
        state = RunState()
        parsed = {"data": [{"id": 5}, {"id": 10}]}
        apply_auth_extract(state, {"restaurant_id": "data.0.id"}, parsed)
        self.assertEqual(state.vars["restaurant_id"], "5")


class TestCheckServerAvailable(unittest.TestCase):
    """Tests for check_server_available — endpoint probing logic."""

    @patch("test_all_apis.urlopen")
    def test_returns_true_on_ping_200(self, mock_urlopen):
        resp = MagicMock()
        resp.__enter__ = MagicMock(return_value=resp)
        resp.__exit__ = MagicMock(return_value=False)
        resp.status = 200
        resp.read.return_value = b'{"status":"pong"}'
        resp.headers = {}
        mock_urlopen.return_value = resp
        self.assertTrue(check_server_available("http://localhost:8080", 5))

    @patch("test_all_apis.urlopen")
    def test_returns_true_on_health_200(self, mock_urlopen):
        from urllib.error import HTTPError
        resp_404 = MagicMock()
        resp_404.read.return_value = b'{}'
        resp_404.headers = {}
        err = HTTPError("http://x", 404, "Not Found", {}, resp_404)
        resp_200 = MagicMock()
        resp_200.__enter__ = MagicMock(return_value=resp_200)
        resp_200.__exit__ = MagicMock(return_value=False)
        resp_200.status = 200
        resp_200.read.return_value = b'{"status":"UP"}'
        resp_200.headers = {}
        mock_urlopen.side_effect = [err, resp_200]
        self.assertTrue(check_server_available("http://localhost:8080", 5))

    @patch("test_all_apis.urlopen")
    def test_returns_false_when_all_unreachable(self, mock_urlopen):
        from urllib.error import URLError
        mock_urlopen.side_effect = URLError("Connection refused")
        self.assertFalse(check_server_available("http://localhost:8080", 2))

    @patch("test_all_apis.urlopen")
    def test_returns_false_when_all_404(self, mock_urlopen):
        from urllib.error import HTTPError
        resp = MagicMock()
        resp.read.return_value = b'{}'
        resp.headers = {}
        err = HTTPError("http://x", 404, "Not Found", {}, resp)
        mock_urlopen.side_effect = err
        self.assertFalse(check_server_available("http://localhost:8080", 2))


class TestEdgeResult(unittest.TestCase):
    """Tests for the _edge_result helper — lightweight TestResult construction."""

    def test_pass_result(self):
        r = _edge_result("Test", "Edge", "desc", "GET", "http://x/api", 200, "body", True)
        self.assertEqual(r.name, "Test")
        self.assertEqual(r.group, "Edge")
        self.assertTrue(r.passed)
        self.assertFalse(r.skipped)
        self.assertEqual(r.status_code, 200)
        self.assertEqual(r.error, "")

    def test_skip_result(self):
        r = _edge_result("Skipped", "Edge", "desc", "GET", "http://x/api", None, "",
                         False, skipped=True, skip_reason="no server")
        self.assertFalse(r.passed)
        self.assertTrue(r.skipped)
        self.assertEqual(r.skip_reason, "no server")
        self.assertIsNone(r.status_code)


class TestWriteReports(unittest.TestCase):
    """Tests for markdown and JSON report writers."""

    def _sample_results(self):
        return [
            TestResult(name="Pass Test", group="Health", description="ok",
                       method="GET", url="/api/v1/health", request_headers={},
                       request_body=None, status_code=200, response_body='{"ok":true}',
                       passed=True, skipped=False),
            TestResult(name="Fail Test", group="Orders", description="bad",
                       method="POST", url="/api/v1/orders", request_headers={},
                       request_body={"x": 1}, status_code=500, response_body='{"message":"err"}',
                       passed=False, skipped=False),
            TestResult(name="Skip Test", group="Auth", description="no token",
                       method="GET", url="/api/v1/protected", request_headers={},
                       request_body=None, status_code=None, response_body="",
                       passed=False, skipped=True, skip_reason="No token"),
        ]

    def test_markdown_report(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "report.md"
            write_markdown_report(self._sample_results(), path, "http://localhost:8080")
            content = path.read_text()
            self.assertIn("# Bhukkad API Test Report", content)
            self.assertIn("http://localhost:8080", content)
            self.assertIn("## Health", content)
            self.assertIn("**Passed:** 1", content)
            self.assertIn("**Failed:** 1", content)
            self.assertIn("**Skipped:** 1", content)
            self.assertIn("Skip Test", content)
            self.assertIn("Skipped:* No token", content)

    def test_json_report(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "report.json"
            write_json_report(self._sample_results(), path, "http://localhost:8080")
            data = json.loads(path.read_text())
            self.assertEqual(data["baseUrl"], "http://localhost:8080")
            self.assertEqual(data["summary"]["total"], 3)
            self.assertEqual(data["summary"]["passed"], 1)
            self.assertEqual(data["summary"]["failed"], 1)
            self.assertEqual(data["summary"]["skipped"], 1)
            self.assertEqual(len(data["tests"]), 3)
            self.assertEqual(data["tests"][0]["name"], "Pass Test")
            self.assertTrue(data["tests"][0]["passed"])
            self.assertEqual(data["tests"][2]["skipReason"], "No token")

    def test_markdown_report_empty_results(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "empty.md"
            write_markdown_report([], path, "http://localhost:8080")
            content = path.read_text()
            self.assertIn("**Passed:** 0", content)
            self.assertIn("**Total:** 0", content)

    def test_json_report_empty_results(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "empty.json"
            write_json_report([], path, "http://localhost:8080")
            data = json.loads(path.read_text())
            self.assertEqual(data["summary"]["total"], 0)


class TestPrintFunctions(unittest.TestCase):
    """Tests for print_result, print_header, print_section — output formatting."""

    def test_print_header(self):
        buf = io.StringIO()
        with redirect_stdout(buf):
            print_header("My Header")
        out = buf.getvalue()
        self.assertIn("My Header", out)
        self.assertIn("═══", out)

    def test_print_section(self):
        buf = io.StringIO()
        with redirect_stdout(buf):
            print_section("My Group")
        out = buf.getvalue()
        self.assertIn("── My Group ──", out)

    def test_print_result_pass(self):
        buf = io.StringIO()
        with redirect_stdout(buf):
            print_result(TestResult(name="OK", group="G", description="",
                                    method="GET", url="/api/v1/h", request_headers={},
                                    request_body=None, status_code=200, response_body="{}",
                                    passed=True, skipped=False), verbose=False)
        out = buf.getvalue()
        self.assertIn("PASS", out)
        self.assertIn("200", out)
        self.assertIn("OK", out)

    def test_print_result_skip(self):
        buf = io.StringIO()
        with redirect_stdout(buf):
            print_result(TestResult(name="Skipped", group="G", description="",
                                    method="GET", url="", request_headers={},
                                    request_body=None, status_code=None, response_body="",
                                    passed=False, skipped=True, skip_reason="No token"),
                         verbose=False)
        out = buf.getvalue()
        self.assertIn("SKIP", out)
        self.assertIn("No token", out)

    def test_print_result_fail_verbose(self):
        buf = io.StringIO()
        with redirect_stdout(buf):
            print_result(TestResult(name="Boom", group="G", description="",
                                    method="POST", url="/api/v1/crash", request_headers={},
                                    request_body={"x": 1}, status_code=500,
                                    response_body='{"message":"err"}',
                                    passed=False, skipped=False), verbose=True)
        out = buf.getvalue()
        self.assertIn("FAIL", out)
        self.assertIn("500", out)
        self.assertIn("Request:", out)
        self.assertIn("Response:", out)

    def test_print_result_fail_not_verbose(self):
        """Failed tests always print details (URL, request, response) even when
        verbose=False, because diagnostics for failures help debugging."""
        buf = io.StringIO()
        with redirect_stdout(buf):
            print_result(TestResult(name="Boom", group="G", description="",
                                    method="POST", url="/api/v1/crash", request_headers={},
                                    request_body={"x": 1}, status_code=500,
                                    response_body='{"message":"err"}',
                                    passed=False, skipped=False), verbose=False)
        out = buf.getvalue()
        # Failed tests always show summary line
        self.assertIn("FAIL", out)
        self.assertIn("500", out)
        self.assertIn("Boom", out)
        # Failed tests also show details even when not verbose
        self.assertIn("URL:", out)
        self.assertIn("Request:", out)
        self.assertIn("Response:", out)

    def test_print_result_error(self):
        buf = io.StringIO()
        with redirect_stdout(buf):
            print_result(TestResult(name="ConnErr", group="G", description="",
                                    method="GET", url="/api/v1/x", request_headers={},
                                    request_body=None, status_code=None, response_body="",
                                    passed=False, skipped=False, error="Connection refused"),
                         verbose=False)
        out = buf.getvalue()
        self.assertIn("ERR", out)
        self.assertIn("Connection refused", out)

    def test_indent_block(self):
        buf = io.StringIO()
        with redirect_stdout(buf):
            indent_block("line1\nline2\nline3", 4)
        out = buf.getvalue()
        lines = out.rstrip("\n").split("\n")
        self.assertEqual(lines[0], "    line1")
        self.assertEqual(lines[1], "    line2")
        self.assertEqual(lines[2], "    line3")


class TestAuthMapConsistency(unittest.TestCase):
    """Verify AUTH_MAP role keys align with the auth roles used in specs."""

    def test_auth_map_keys(self):
        self.assertIn("customer", AUTH_MAP)
        self.assertIn("owner", AUTH_MAP)
        self.assertIn("agent", AUTH_MAP)
        self.assertIn("admin", AUTH_MAP)
        self.assertIn("customer_refresh", AUTH_MAP)

    def test_auth_map_values_are_token_names(self):
        self.assertTrue(AUTH_MAP["customer"].endswith("_token"))


class TestResolveValueEdgeCases(unittest.TestCase):
    """Additional edge-case tests for resolve_value type coercion."""

    def test_numeric_key_with_float_value(self):
        state = RunState()
        state.vars["price_val"] = "199.99"
        result = resolve_value("{price_val}", state, key="price")
        self.assertIsInstance(result, float)
        self.assertAlmostEqual(result, 199.99)

    def test_numeric_key_with_negative_int(self):
        state = RunState()
        state.vars["id_val"] = "-5"
        result = resolve_value("{id_val}", state, key="restaurantId")
        self.assertIsInstance(result, int)
        self.assertEqual(result, -5)

    def test_string_key_with_numeric_lookalike(self):
        state = RunState()
        state.vars["code_val"] = "123456"
        result = resolve_value("{code_val}", state, key="code")
        self.assertIsInstance(result, str)
        self.assertEqual(result, "123456")

    def test_nested_dict_resolution(self):
        state = RunState()
        state.vars["rid"] = "10"
        state.vars["mid"] = "20"
        template = {"restaurantId": "{rid}", "items": [{"menuItemId": "{mid}", "qty": 2}]}
        result = resolve_value(template, state)
        self.assertEqual(result["restaurantId"], 10)
        self.assertEqual(result["items"][0]["menuItemId"], 20)
        self.assertEqual(result["items"][0]["qty"], 2)

    def test_nested_dict_string_key_in_nested(self):
        state = RunState()
        state.vars["phone"] = "9876543210"
        template = {"contact": {"phoneNumber": "{phone}"}}
        result = resolve_value(template, state)
        self.assertIsInstance(result["contact"]["phoneNumber"], str)
        self.assertEqual(result["contact"]["phoneNumber"], "9876543210")


class TestExtractJsonPathExtended(unittest.TestCase):
    """Additional edge-case tests for extract_json_path."""

    def test_deeply_nested(self):
        data = {"a": {"b": {"c": {"d": {"e": 42}}}}}
        self.assertEqual(extract_json_path(data, "a.b.c.d.e"), 42)

    def test_array_in_nested(self):
        data = {"items": [{"id": 1}, {"id": 2, "meta": {"name": "test"}}]}
        self.assertEqual(extract_json_path(data, "items.1.meta.name"), "test")

    def test_path_into_non_dict(self):
        data = {"a": "string_value"}
        self.assertIsNone(extract_json_path(data, "a.b"))

    def test_path_into_integer(self):
        self.assertIsNone(extract_json_path(42, "a"))

    def test_path_into_string(self):
        self.assertIsNone(extract_json_path("hello", "a.b"))

    def test_empty_path_returns_none(self):
        self.assertIsNone(extract_json_path({"a": 1}, ""))

    def test_trailing_key_on_array(self):
        # Path "0" on a list indexes into it — returns element at index 0
        self.assertEqual(extract_json_path([1, 2], "0"), 1)


class TestTruncateExtended(unittest.TestCase):
    """Additional edge-case tests for truncate."""

    def test_limit_one(self):
        text = "hello"
        result = truncate(text, 1)
        self.assertEqual(result, "h\n... [4 more chars]")

    def test_unicode_length_preserved(self):
        text = "café" * 600  # ~2400 chars
        result = truncate(text, 2000)
        self.assertIn("... [", result)

    def test_multibyte_chars_count_correctly(self):
        text = "🍔" * 3000  # 3000 emoji chars
        result = truncate(text, 10)
        # Each emoji is 1 char in Python 3 str
        self.assertTrue(result.startswith("🍔" * 10))
        self.assertIn("... [", result)


class TestPrettyJsonExtended(unittest.TestCase):
    """Additional edge-case tests for pretty_json."""

    def test_array_input(self):
        text = '[1, 2, 3]'
        result = pretty_json(text)
        self.assertIn("[", result)
        self.assertIn("1", result)

    def test_nested_object(self):
        text = '{"a": {"b": {"c": [1, 2]}}}'
        result = pretty_json(text)
        self.assertIn('"b": {', result)
        self.assertIn('"c": [', result)

    def test_json_with_escape(self):
        text = '{"msg": "hello\\nworld"}'
        result = pretty_json(text)
        self.assertIn("hello", result)
        self.assertIn("world", result)

    def test_single_quotes_invalid(self):
        self.assertEqual(pretty_json("{'a': 1}"), "{'a': 1}")


class TestRunStateExtended(unittest.TestCase):
    """Additional tests for RunState state management."""

    def test_tokens_and_vars_separate(self):
        state = RunState()
        state.tokens["customer_token"] = "tok123"
        state.vars["customer_id"] = "42"
        self.assertIn("customer_token", state.tokens)
        self.assertIn("customer_id", state.vars)
        # tokens should not appear in vars
        self.assertNotIn("customer_token", state.vars)

    def test_results_list_initialized(self):
        state = RunState()
        self.assertIsInstance(state.results, list)
        self.assertEqual(len(state.results), 0)

    def test_webhook_seq_starts_at_zero(self):
        state = RunState()
        self.assertEqual(state._webhook_seq, 0)
        state.next_webhook_payment_id()
        self.assertEqual(state._webhook_seq, 1)

    def test_init_defaults_referred_fields(self):
        state = RunState()
        state.init_defaults("pw")
        self.assertIn("referred_customer_email", state.vars)
        self.assertIn("referred_customer_phone", state.vars)
        self.assertTrue(state.vars["referred_customer_email"].endswith("@bhukkad.test"))


class TestCatalogIntegrityExtended(unittest.TestCase):
    """Extended structural checks on the API catalog."""

    def test_every_body_key_has_template(self):
        """Every spec with body_key must reference an existing BODY_TEMPLATES entry."""
        for spec in API_CATALOG:
            bk = spec.get("body_key")
            if bk:
                self.assertIn(bk, BODY_TEMPLATES,
                              f"Spec '{spec['name']}' references body_key '{bk}' "
                              f"which is not in BODY_TEMPLATES")

    def test_inline_body_no_body_key_conflict(self):
        """A spec should not have both body_key and inline body."""
        for spec in API_CATALOG:
            self.assertFalse(spec.get("body_key") and spec.get("body"),
                             f"Spec '{spec['name']}' has both body_key and inline body")

    def test_auth_role_is_valid_or_none(self):
        """The auth field must be None, a known role, or a custom token name."""
        known_roles = set(AUTH_MAP.keys())
        for spec in API_CATALOG:
            auth = spec.get("auth")
            if auth is not None:
                self.assertTrue(auth in known_roles or auth in AUTH_MAP.values(),
                                f"Spec '{spec['name']}' has unknown auth role '{auth}'")

    def test_phase_is_valid(self):
        """If a phase is specified, it must be one of setup/main/teardown."""
        valid_phases = {"setup", "main", "teardown"}
        for spec in API_CATALOG:
            phase = spec.get("phase")
            if phase:
                self.assertIn(phase, valid_phases,
                              f"Spec '{spec['name']}' has invalid phase '{phase}'")

    def test_new_edge_case_names_unique(self):
        """Verify the new edge-case entries don't collide with existing names."""
        names = [s["name"] for s in API_CATALOG]
        for name in ("Missing Authorization Header on Protected Route (edge)",
                      "Place Order — Negative Tip Amount (edge)",
                      "Search — Empty Keyword (edge)",
                      "Customer Orders — Negative Page Size (edge)",
                      "Add to Cart — Float Quantity (edge)",
                      "Register — SQL Injection in Email (edge)",
                      "Delete Menu Item — Associated with Existing Orders (edge)",
                      "Get Order by ID — Nonexistent Order (edge)",
                      "Refresh Token — Missing Token Field (edge)",
                      "Add to Cart — Invalid Menu Item ID (edge)"):
            self.assertEqual(names.count(name), 1,
                             f"Name '{name}' should appear exactly once")


class TestNewBodyTemplatesStructure(unittest.TestCase):
    """Verify the new body templates have correct structure."""

    def test_order_negative_tip_structure(self):
        t = BODY_TEMPLATES["order_negative_tip"]
        self.assertEqual(t["paymentMethod"], "CASH_ON_DELIVERY")
        self.assertEqual(t["tipAmount"], -50.0)
        self.assertIn("{restaurant_id}", t["restaurantId"])

    def test_cart_add_float_qty_structure(self):
        t = BODY_TEMPLATES["cart_add_float_qty"]
        self.assertEqual(t["quantity"], 2.5)
        self.assertIn("{menu_item_id}", t["menuItemId"])

    def test_register_sql_injection_email_structure(self):
        t = BODY_TEMPLATES["register_sql_injection_email"]
        self.assertIn("DROP TABLE", t["email"])
        self.assertEqual(t["role"], "CUSTOMER")

    def test_refresh_token_invalid_template_structure(self):
        t = BODY_TEMPLATES["refresh_token_invalid"]
        self.assertEqual(t["refreshToken"], "garbage.invalid.token")

    def test_order_oversized_template_structure(self):
        t = BODY_TEMPLATES["order_oversized"]
        self.assertEqual(t["paymentMethod"], "CASH_ON_DELIVERY")
        # specialInstructions should be a very long string
        self.assertGreater(len(t["specialInstructions"]), 99999)


class TestDeleteMenuItemTemplate(unittest.TestCase):
    """The Delete Menu Item edge case should reference the menu_item_id placeholder
    correctly and have the right auth."""

    def test_delete_menu_item_edge_case(self):
        spec = next(s for s in API_CATALOG if s["name"] == "Delete Menu Item — Associated with Existing Orders (edge)")
        self.assertEqual(spec["method"], "DELETE")
        self.assertEqual(spec["auth"], "owner")
        self.assertIn(400, spec["expected"])
        self.assertIn(409, spec["expected"])

    def test_delete_menu_item_requires(self):
        spec = next(s for s in API_CATALOG if s["name"] == "Delete Menu Item — Associated with Existing Orders (edge)")
        self.assertIn("menu_item_id", spec.get("requires", []))


class TestResetDatabase(unittest.TestCase):
    """Tests for reset_database — database truncation and re-seeding."""

    def _list_tables_mock(self, tables="orders\ncustomers\nrestaurants\n"):
        return MagicMock(returncode=0, stdout=tables, stderr="")

    def _truncate_ok(self):
        return MagicMock(returncode=0, stdout="TRUNCATE", stderr="")

    def _admin_ok(self):
        return MagicMock(returncode=0, stdout="INSERT 0 1", stderr="")

    def _seed_ok(self):
        return MagicMock(returncode=0, stdout="INSERT 0 10", stderr="")

    def _seq_fix_ok(self):
        return MagicMock(returncode=0, stdout="DO", stderr="")

    def _seq_fix_ok(self):
        return MagicMock(returncode=0, stdout="DO", stderr="")

    @patch("test_all_apis.subprocess.run")
    def test_successful_reset(self, mock_run):
        """Full reset flow: list → truncate → reseed admin → seed V6 → fix sequences."""
        mock_run.side_effect = [
            self._list_tables_mock(),
            self._truncate_ok(),
            self._admin_ok(),
            self._seed_ok(),
            self._seq_fix_ok(),
        ]
        with patch.dict(os.environ, {"DB_PASSWORD": "secret"}):
            result = reset_database()
        self.assertTrue(result)
        # Should have 5 calls: list, truncate, admin reseed, seed, seq fix
        self.assertEqual(mock_run.call_count, 5)

    @patch("test_all_apis.subprocess.run")
    def test_no_tables_returns_true(self, mock_run):
        """If no tables found, reset succeeds early (no truncate/seed calls)."""
        mock_run.return_value = self._list_tables_mock(tables="")
        with patch.dict(os.environ, {"DB_PASSWORD": "secret"}):
            result = reset_database()
        self.assertTrue(result)
        self.assertEqual(mock_run.call_count, 1)  # only the list call

    @patch("test_all_apis.subprocess.run")
    def test_list_tables_failure_returns_false(self, mock_run):
        mock_run.return_value = MagicMock(returncode=1, stdout="", stderr="connection refused")
        with patch.dict(os.environ, {"DB_PASSWORD": "secret"}):
            result = reset_database()
        self.assertFalse(result)

    @patch("test_all_apis.subprocess.run")
    def test_truncate_failure_returns_false(self, mock_run):
        mock_run.side_effect = [
            self._list_tables_mock(),
            MagicMock(returncode=1, stdout="", stderr="foreign key violation"),
        ]
        with patch.dict(os.environ, {"DB_PASSWORD": "secret"}):
            result = reset_database()
        self.assertFalse(result)

    @patch("test_all_apis.subprocess.run")
    def test_seed_warning_does_not_fail(self, mock_run):
        """If seed re-apply has warnings (e.g. ON CONFLICT skips), still return True."""
        mock_run.side_effect = [
            self._list_tables_mock(),
            self._truncate_ok(),
            self._admin_ok(),
            MagicMock(returncode=1, stdout="", stderr="conflicting key value"),
            self._seq_fix_ok(),
        ]
        with patch.dict(os.environ, {"DB_PASSWORD": "secret"}):
            result = reset_database()
        self.assertTrue(result)

    @patch("test_all_apis.subprocess.run", side_effect=FileNotFoundError)
    def test_psql_not_found(self, mock_run):
        with patch.dict(os.environ, {"DB_PASSWORD": "secret"}):
            result = reset_database()
        self.assertFalse(result)

    @patch("test_all_apis.subprocess.run")
    def test_seed_sql_not_found_returns_true(self, mock_run):
        """If V6 seed SQL doesn't exist, reset still succeeds (data tables were truncated).
        Since seed file is missing, the function returns early before seq fix."""
        mock_run.side_effect = [
            self._list_tables_mock(tables="orders\n"),
            self._truncate_ok(),
            self._admin_ok(),
        ]
        with patch.dict(os.environ, {"DB_PASSWORD": "secret"}):
            with patch.object(test_all_apis.Path, "exists", return_value=False):
                result = reset_database()
        self.assertTrue(result)
        # 3 calls: list + truncate + admin reseed (returns early, no seq fix)
        self.assertEqual(mock_run.call_count, 3)

    @patch("test_all_apis.subprocess.run")
    def test_db_url_parsing(self, mock_run):
        """reset_database should parse postgres:// user:pass@host:port/dburl."""
        mock_run.side_effect = [
            MagicMock(returncode=0, stdout="orders\n", stderr=""),
            self._truncate_ok(),
            self._admin_ok(),
            self._seed_ok(),
            self._seq_fix_ok(),
        ]
        reset_database("postgres://testuser:testpass@localhost:5432/testdb")
        list_call = mock_run.call_args_list[0]
        self.assertIn("-h", list_call[0][0])
        self.assertIn("localhost", list_call[0][0])
        self.assertIn("testuser", list_call[0][0])
        self.assertIn("5432", list_call[0][0])

    @patch("test_all_apis.subprocess.run")
    def test_admin_reseed_uses_correct_sql(self, mock_run):
        """The admin re-seed SQL should insert into both users and admins tables."""
        mock_run.side_effect = [
            self._list_tables_mock(),
            self._truncate_ok(),
            self._admin_ok(),
            self._seed_ok(),
            self._seq_fix_ok(),
        ]
        with patch.dict(os.environ, {"DB_PASSWORD": "secret"}):
            reset_database()
        # Check the admin reseed call contains INSERT INTO users and admins
        admin_call = mock_run.call_args_list[2]
        admin_sql = admin_call[0][0][-1]
        self.assertIn("INSERT INTO users", admin_sql)
        self.assertIn("INSERT INTO admins", admin_sql)

    @patch("test_all_apis.subprocess.run")
    def test_truncate_includes_all_listed_tables(self, mock_run):
        """All non-flyway tables returned by psql should be truncated."""
        mock_run.side_effect = [
            self._list_tables_mock(tables="orders\ncustomers\nrestaurants\nmenus\n"),
            self._truncate_ok(),
            self._admin_ok(),
            self._seed_ok(),
            self._seq_fix_ok(),
        ]
        with patch.dict(os.environ, {"DB_PASSWORD": "secret"}):
            reset_database()
        truncate_call = mock_run.call_args_list[1]
        truncate_sql = truncate_call[0][0][-1]
        self.assertIn("orders", truncate_sql)
        self.assertIn("customers", truncate_sql)
        self.assertIn("restaurants", truncate_sql)
        self.assertIn("menus", truncate_sql)

    @patch("test_all_apis.subprocess.run")
    def test_admin_reseed_failure_is_non_fatal(self, mock_run):
        """If admin re-seed fails, reset should still succeed (returns True)."""
        mock_run.side_effect = [
            self._list_tables_mock(),
            self._truncate_ok(),
            MagicMock(returncode=1, stdout="", stderr="admin reseed failed"),
            self._seed_ok(),
            self._seq_fix_ok(),
        ]
        with patch.dict(os.environ, {"DB_PASSWORD": "secret"}):
            result = reset_database()
        self.assertTrue(result)


class TestRunTestWithReset(unittest.TestCase):
    """Tests for run_test behavior with reset-specific scenarios."""

    def _mock_response_obj(self, status=200, body=b'{}'):
        resp = MagicMock()
        resp.__enter__ = MagicMock(return_value=resp)
        resp.__exit__ = MagicMock(return_value=False)
        resp.status = status
        resp.read.return_value = body
        resp.headers = {}
        return resp

    @patch("test_all_apis.urlopen")
    def test_run_test_with_inline_body(self, mock_urlopen):
        """Specs with inline 'body' (not body_key) should resolve placeholders."""
        mock_urlopen.return_value = self._mock_response_obj(200, b'{"data":{"id":1}}')
        state = RunState()
        state.init_defaults("pw")
        state.vars["order_id"] = "42"
        state.tokens["customer_token"] = "jwt.test"
        spec = {"name": "Inline", "method": "POST", "path": "/api/v1/orders/customer/{order_id}/reorder",
                "body": {"extra": True}, "expected": [200], "auth": "customer"}
        result = run_test(spec, "http://localhost:8080", state, 30, verbose=False)
        self.assertTrue(result.passed)
        self.assertIn("42", result.url)

    @patch("test_all_apis.urlopen")
    def test_run_test_content_type_form(self, mock_urlopen):
        """When content_type is 'form', no Content-Type: application/json should be set."""
        mock_urlopen.return_value = self._mock_response_obj(200, b'{}')
        state = RunState()
        state.tokens["customer_token"] = "jwt.test"
        spec = {"name": "Form", "method": "POST", "path": "/api/v1/test",
                "expected": [200], "auth": "customer", "content_type": "form", "body": {"q": "1"}}
        result = run_test(spec, "http://localhost:8080", state, 30, verbose=False)
        self.assertTrue(result.passed)
        # Content-Type should not be application/json
        ct_header = result.request_headers.get("Content-Type", "")
        self.assertNotEqual(ct_header, "application/json")

    @patch("test_all_apis.urlopen")
    def test_run_test_no_body_for_get(self, mock_urlopen):
        """GET requests should not have a Content-Type: application/json header."""
        mock_urlopen.return_value = self._mock_response_obj(200, b'{}')
        state = RunState()
        spec = {"name": "Get", "method": "GET", "path": "/api/v1/test",
                "expected": [200]}
        result = run_test(spec, "http://localhost:8080", state, 30, verbose=False)
        self.assertTrue(result.passed)
        # GET requests should not have Content-Type set
        ct_header = result.request_headers.get("Content-Type")
        self.assertIsNone(ct_header)

    @patch("test_all_apis.urlopen")
    def test_run_test_skip_if_no_auth_false(self, mock_urlopen):
        """When skip_if_no_auth is explicitly False, a missing token should still
        attempt the request (without Authorization header)."""
        mock_urlopen.return_value = self._mock_response_obj(200, b'{}')
        state = RunState()
        spec = {"name": "NoAuth", "method": "GET", "path": "/api/v1/test",
                "expected": [200], "auth": "customer", "skip_if_no_auth": False}
        result = run_test(spec, "http://localhost:8080", state, 30, verbose=False)
        self.assertTrue(result.passed)
        # The request should have been attempted (not skipped)
        self.assertFalse(result.skipped)
        mock_urlopen.assert_called_once()

    @patch("test_all_apis.urlopen")
    def test_run_test_500_error_diagnostics(self, mock_urlopen):
        """500 responses should be flagged as potential defects in logging."""
        mock_urlopen.return_value = self._mock_response_obj(500, b'{"message":"An unexpected error occurred"}')
        state = RunState()
        spec = {"name": "Crash", "method": "GET", "path": "/api/v1/crash", "expected": [200]}
        """500 responses should be flagged as potential defects in logging."""
        mock_urlopen.return_value = self._mock_response_obj(500, b'{"message":"An unexpected error occurred"}')
        state = RunState()
        spec = {"name": "Crash", "method": "GET", "path": "/api/v1/crash", "expected": [200]}
        with self.assertLogs("test_all_apis", level="WARNING") as log_ctx:
            result = run_test(spec, "http://localhost:8080", state, 30, verbose=False)
        self.assertFalse(result.passed)
        self.assertTrue(any("Potential defect" in msg for msg in log_ctx.output))


class TestCheckServerAvailableExtended(unittest.TestCase):
    """Additional tests for check_server_available."""

    @patch("test_all_apis.urlopen")
    def test_all_endpoints_500_returns_false(self, mock_urlopen):
        from urllib.error import HTTPError
        resp = MagicMock()
        resp.read.return_value = b'{}'
        resp.headers = {}
        err = HTTPError("http://x", 500, "Internal", {}, resp)
        mock_urlopen.side_effect = err
        self.assertFalse(check_server_available("http://localhost:8080", 2))

    @patch("test_all_apis.urlopen")
    def test_stops_on_first_success(self, mock_urlopen):
        """Should return True after the first 200 without trying remaining endpoints."""
        resp = MagicMock()
        resp.__enter__ = MagicMock(return_value=resp)
        resp.__exit__ = MagicMock(return_value=False)
        resp.status = 200
        resp.read.return_value = b'{"status":"pong"}'
        resp.headers = {}
        mock_urlopen.return_value = resp
        check_server_available("http://localhost:8080", 5)
        # Should have only tried the first endpoint (/api/v1/health/ping)
        self.assertEqual(mock_urlopen.call_count, 1)


if __name__ == "__main__":
    unittest.main(verbosity=2)
