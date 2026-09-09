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
# The imported dataclass is not a test case: keep pytest from trying to
# collect it (silences PytestCollectionWarning "cannot collect test class").
TestResult.__test__ = False  # type: ignore[attr-defined]
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
        # Mandatory spec: transport failure stays a FAIL (not a skip).
        self.assertFalse(result.skipped)
        self.assertTrue(result.error)

    @patch("test_all_apis.urlopen")
    def test_optional_spec_connection_error_marks_skipped(self, mock_urlopen):
        """Optional spec + transport failure → SKIP (environment signal), but
        an optional spec failing an HTTP assertion still FAILs."""
        from urllib.error import URLError
        mock_urlopen.side_effect = URLError("Connection refused")
        state = RunState()
        state.init_defaults("pw")
        spec = {"name": "OptDown", "method": "GET", "path": "/api/v1/x",
                "expected": [200], "optional": True}
        result = run_test(spec, "http://localhost:8080", state, 5, verbose=False)
        self.assertFalse(result.passed)
        self.assertTrue(result.skipped)
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

    def _seq_fix_ok(self):
        return MagicMock(returncode=0, stdout="DO", stderr="")

    @patch("test_all_apis.subprocess.run")
    def test_successful_reset(self, mock_run):
        """Full reset flow: list → truncate → reseed admin → fix sequences."""
        mock_run.side_effect = [
            self._list_tables_mock(),
            self._truncate_ok(),
            self._admin_ok(),
            self._seq_fix_ok(),
        ]
        with patch.dict(os.environ, {"DB_PASSWORD": "secret"}):
            result = reset_database()
        self.assertTrue(result)
        # Should have 4 calls: list, truncate, admin reseed, seq fix
        self.assertEqual(mock_run.call_count, 4)

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
    def test_seq_fix_warning_does_not_fail(self, mock_run):
        """If the sequence fix reports a warning, reset still returns True."""
        mock_run.side_effect = [
            self._list_tables_mock(),
            self._truncate_ok(),
            self._admin_ok(),
            MagicMock(returncode=1, stdout="", stderr="sequence warning"),
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
    def test_admin_reseed_failure_still_runs_seq_fix(self, mock_run):
        """Admin reseed failure is non-fatal and must not skip the seq fix."""
        mock_run.side_effect = [
            self._list_tables_mock(tables="orders\n"),
            self._truncate_ok(),
            MagicMock(returncode=1, stdout="", stderr="admin reseed failed"),
            self._seq_fix_ok(),
        ]
        with patch.dict(os.environ, {"DB_PASSWORD": "secret"}):
            result = reset_database()
        self.assertTrue(result)
        # 4 calls: list + truncate + admin reseed (failed, non-fatal) + seq fix
        self.assertEqual(mock_run.call_count, 4)

    @patch("test_all_apis.subprocess.run")
    def test_db_url_parsing(self, mock_run):
        """reset_database should parse postgres:// user:pass@host:port/dburl."""
        mock_run.side_effect = [
            MagicMock(returncode=0, stdout="orders\n", stderr=""),
            self._truncate_ok(),
            self._admin_ok(),
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


class TestEdgeBattery(unittest.TestCase):
    """Unit tests for the comprehensive edge-case battery added to the runner."""

    def _make_state(self) -> RunState:
        state = RunState()
        state.init_defaults("Test@123456")
        return state

    def _wire_battery(self, state: RunState) -> None:
        test_all_apis._EDGE_STATE["base_url"] = "http://test"
        test_all_apis._EDGE_STATE["timeout"] = 2
        test_all_apis._register_edge_battery(state)

    # ---------- battery_auth_edges ----------

    @patch.object(test_all_apis, "_probe")
    def test_auth_battery_duplicate_registration(self, mock_probe):
        """Duplicate registration probe: second register must be rejected
        (400/409) for the battery to pass."""
        # probe sequence: dup#1(200), dup#2(409), wrong-type(400), injection(400),
        # oversized(413), unicode(200), role(400), register(200), login(401)
        mock_probe.side_effect = [
            (200, '{"data":{"token":"t"}}'), (409, "duplicate"),
            (400, "bad"), (400, "bad"), (413, "too large"),
            (200, '{"data":{"token":"t"}}'), (400, "bad"),
            (200, '{"data":{"token":"t"}}'), (401, "invalid credentials"),
        ]
        state = self._make_state()
        self._wire_battery(state)
        test_all_apis.battery_auth_edges("http://test", state, 2)
        dup = next(r for r in state.results if "duplicate registration" in r.name)
        self.assertTrue(dup.passed)
        self.assertEqual(dup.status_code, 409)

    @patch.object(test_all_apis, "_probe")
    def test_auth_battery_unknown_role_rejected(self, mock_probe):
        """Unknown role must be rejected with 400."""
        mock_probe.return_value = (400, "bad request")
        state = self._make_state()
        self._wire_battery(state)
        test_all_apis.battery_auth_edges("http://test", state, 2)
        role = next(r for r in state.results if "unknown role" in r.name)
        self.assertTrue(role.passed)
        self.assertEqual(role.status_code, 400)

    @patch.object(test_all_apis, "http_request")
    def test_auth_battery_malformed_json_400(self, mock_http):
        """Malformed JSON body must yield 400 for a pass."""
        mock_http.return_value = (400, "bad json", {})
        state = self._make_state()
        self._wire_battery(state)
        test_all_apis.battery_auth_edges("http://test", state, 2)
        mal = next(r for r in state.results if "malformed JSON" in r.name)
        self.assertTrue(mal.passed)

    @patch.object(test_all_apis, "http_request")
    def test_auth_battery_malformed_json_500_fails(self, mock_http):
        """A 500 on malformed JSON is the bug the battery hunts — must fail."""
        mock_http.return_value = (500, "Internal error", {})
        state = self._make_state()
        self._wire_battery(state)
        test_all_apis.battery_auth_edges("http://test", state, 2)
        mal = next(r for r in state.results if "malformed JSON" in r.name)
        self.assertFalse(mal.passed)

    @patch.object(test_all_apis, "_probe")
    def test_auth_battery_unicode_accepted(self, mock_probe):
        """Unicode/emoji name should register (200)."""
        mock_probe.return_value = (200, '{"data":{"token":"t"}}')
        state = self._make_state()
        self._wire_battery(state)
        test_all_apis.battery_auth_edges("http://test", state, 2)
        uni = next(r for r in state.results if "unicode/emoji" in r.name)
        self.assertTrue(uni.passed)

    @patch.object(test_all_apis, "_probe")
    def test_auth_battery_wrong_password_401_no_stack_leak(self, mock_probe):
        """Wrong password → 401 and response must not leak stack frames."""
        mock_probe.return_value = (401, '{"code":"INVALID_CREDENTIALS"}')
        state = self._make_state()
        self._wire_battery(state)
        test_all_apis.battery_auth_edges("http://test", state, 2)
        wp = next(r for r in state.results if "wrong password" in r.name)
        self.assertTrue(wp.passed)

    @patch.object(test_all_apis, "_probe")
    def test_auth_battery_wrong_password_stack_leak_fails(self, mock_probe):
        """A 401 whose body contains stack frames must fail the battery."""
        mock_probe.return_value = (401, "java.lang.NullPointerException\n\tat com.bhukkad.x")
        state = self._make_state()
        self._wire_battery(state)
        test_all_apis.battery_auth_edges("http://test", state, 2)
        wp = next(r for r in state.results if "wrong password" in r.name)
        self.assertFalse(wp.passed)

    # ---------- battery_authz_edges ----------

    @patch.object(test_all_apis, "_probe")
    def test_authz_battery_no_token_rejected(self, mock_probe):
        mock_probe.return_value = (401, "unauthorized")
        state = self._make_state()
        self._wire_battery(state)
        test_all_apis.battery_authz_edges("http://test", state, 2)
        res = next(r for r in state.results if "without token" in r.name)
        self.assertTrue(res.passed)

    @patch.object(test_all_apis, "_probe")
    def test_authz_battery_tampered_jwt_rejected(self, mock_probe):
        mock_probe.return_value = (401, "invalid signature")
        state = self._make_state()
        self._wire_battery(state)
        test_all_apis.battery_authz_edges("http://test", state, 2)
        res = next(r for r in state.results if "tampered JWT" in r.name)
        self.assertTrue(res.passed)

    @patch.object(test_all_apis, "_fresh_customer", return_value=("tok", "e@x.test"))
    @patch.object(test_all_apis, "_probe")
    def test_authz_battery_customer_blocked_from_admin(self, mock_probe, _mock_fresh):
        mock_probe.return_value = (403, "forbidden")
        state = self._make_state()
        self._wire_battery(state)
        test_all_apis.battery_authz_edges("http://test", state, 2)
        blocked = [r for r in state.results if "customer blocked from" in r.name]
        self.assertEqual(len(blocked), 2)
        self.assertTrue(all(r.passed for r in blocked))

    @patch.object(test_all_apis, "_fresh_customer", return_value=(None, "e@x.test"))
    @patch.object(test_all_apis, "_probe")
    def test_authz_battery_unknown_path_structured_404(self, mock_probe, _mock_fresh):
        mock_probe.return_value = (404, '{"code":"ROUTE_NOT_FOUND"}')
        state = self._make_state()
        self._wire_battery(state)
        test_all_apis.battery_authz_edges("http://test", state, 2)
        res = next(r for r in state.results if "unknown path" in r.name)
        self.assertTrue(res.passed)

    # ---------- battery_pagination_edges ----------

    @patch.object(test_all_apis, "_fresh_customer", return_value=("tok", "e@x.test"))
    @patch.object(test_all_apis, "_probe")
    def test_pagination_battery_negative_page_clamps(self, mock_probe, _mock_fresh):
        """Negative page clamped to 200 (or rejected with 400) — both pass."""
        mock_probe.return_value = (200, '{"data":{"items":[]}}')
        state = self._make_state()
        self._wire_battery(state)
        test_all_apis.battery_pagination_edges("http://test", state, 2)
        neg = next(r for r in state.results if "page" in r.name and "-5" in r.response_body)
        self.assertTrue(neg.passed)

    @patch.object(test_all_apis, "_fresh_customer", return_value=("tok", "e@x.test"))
    @patch.object(test_all_apis, "_probe")
    def test_pagination_battery_all_six_cases_recorded(self, mock_probe, _mock_fresh):
        mock_probe.return_value = (200, '{"data":{"items":[]}}')
        state = self._make_state()
        self._wire_battery(state)
        test_all_apis.battery_pagination_edges("http://test", state, 2)
        pag = [r for r in state.results if "Pagination —" in r.name]
        self.assertEqual(len(pag), 6)
        self.assertTrue(all(r.passed for r in pag))

    @patch.object(test_all_apis, "_fresh_customer", return_value=(None, "e@x.test"))
    @patch.object(test_all_apis, "_probe")
    def test_pagination_battery_skips_without_token(self, mock_probe, _mock_fresh):
        """No token anywhere → battery records nothing (graceful skip)."""
        state = self._make_state()
        self._wire_battery(state)
        test_all_apis.battery_pagination_edges("http://test", state, 2)
        self.assertFalse(any("Pagination —" in r.name for r in state.results))
        mock_probe.assert_not_called()

    # ---------- battery_resource_edges ----------

    @patch.object(test_all_apis, "_probe")
    def test_resource_battery_unknown_id_404(self, mock_probe):
        mock_probe.return_value = (404, "not found")
        state = self._make_state()
        state.tokens["customer_token"] = "tok"
        self._wire_battery(state)
        test_all_apis.battery_resource_edges("http://test", state, 2)
        res = [r for r in state.results if "unknown id" in r.name]
        self.assertEqual(len(res), 4)
        self.assertTrue(all(r.passed for r in res))

    @patch.object(test_all_apis, "_probe")
    def test_resource_battery_fabricated_wallet_is_failure(self, mock_probe):
        """A 200 with a balance for a nonexistent wallet id is the V-class bug
        the battery must flag."""
        mock_probe.side_effect = [
            (200, '{"data":{"customerId":99999999,"balance":100}}'),
        ] + [(404, "nf")] * 4
        state = self._make_state()
        state.tokens["customer_token"] = "tok"
        self._wire_battery(state)
        test_all_apis.battery_resource_edges("http://test", state, 2)
        wallet = next(r for r in state.results if "99999999" in r.name and "wallet" in r.url)
        self.assertFalse(wallet.passed)
        self.assertIn("fabricated_data=True", wallet.response_body)

    @patch.object(test_all_apis, "_probe")
    def test_resource_battery_negative_id(self, mock_probe):
        mock_probe.return_value = (400, "bad id")
        state = self._make_state()
        state.tokens["customer_token"] = "tok"
        self._wire_battery(state)
        test_all_apis.battery_resource_edges("http://test", state, 2)
        neg = next(r for r in state.results if "negative id" in r.name)
        self.assertTrue(neg.passed)

    # ---------- battery_money_edges ----------

    @patch.object(test_all_apis, "_probe")
    def test_money_battery_negative_amount_rejected(self, mock_probe):
        mock_probe.return_value = (400, "amount must be positive")
        state = self._make_state()
        state.tokens["admin_token"] = "adm"
        self._wire_battery(state)
        test_all_apis.battery_money_edges("http://test", state, 2)
        money = [r for r in state.results if "COD credit" in r.name]
        self.assertEqual(len(money), 5)
        self.assertTrue(all(r.passed for r in money))

    @patch.object(test_all_apis, "_probe")
    def test_money_battery_negative_amount_accepted_is_bug(self, mock_probe):
        """200 on a negative COD credit = V-02 regression — must fail."""
        mock_probe.return_value = (200, '{"balance":-500}')
        state = self._make_state()
        state.tokens["admin_token"] = "adm"
        self._wire_battery(state)
        test_all_apis.battery_money_edges("http://test", state, 2)
        neg = next(r for r in state.results if "negative amount" in r.name)
        self.assertFalse(neg.passed)

    @patch.object(test_all_apis, "_probe")
    def test_money_battery_unauthenticated_rejection_ok(self, mock_probe):
        """401/403 on the internal endpoint also counts as rejected."""
        mock_probe.return_value = (401, "unauthorized")
        state = self._make_state()
        state.tokens["admin_token"] = "adm"
        self._wire_battery(state)
        test_all_apis.battery_money_edges("http://test", state, 2)
        money = [r for r in state.results if "COD credit" in r.name]
        self.assertTrue(all(r.passed for r in money))

    # ---------- battery_webhook_edges ----------

    @patch.object(test_all_apis, "_probe")
    def test_webhook_battery_forged_signature_rejected(self, mock_probe):
        mock_probe.return_value = (401, "invalid signature")
        state = self._make_state()
        self._wire_battery(state)
        test_all_apis.battery_webhook_edges("http://test", state, 2)
        wh = next(r for r in state.results if "invalid signature body" in r.name)
        self.assertTrue(wh.passed)

    @patch.object(test_all_apis, "_probe")
    def test_webhook_battery_500_on_forged_signature_is_bug(self, mock_probe):
        mock_probe.return_value = (500, "NPE")
        state = self._make_state()
        self._wire_battery(state)
        test_all_apis.battery_webhook_edges("http://test", state, 2)
        wh = next(r for r in state.results if "invalid signature body" in r.name)
        self.assertFalse(wh.passed)

    # ---------- run_edge_battery isolation ----------

    @patch.object(test_all_apis, "battery_webhook_edges", side_effect=RuntimeError("boom"))
    @patch.object(test_all_apis, "battery_money_edges")
    @patch.object(test_all_apis, "battery_resource_edges")
    @patch.object(test_all_apis, "battery_pagination_edges")
    @patch.object(test_all_apis, "battery_authz_edges")
    @patch.object(test_all_apis, "battery_auth_edges")
    def test_run_edge_battery_isolates_crashes(self, m_auth, m_authz, m_pag,
                                               m_res, m_money, m_webhook):
        """One crashing battery must not prevent the others from running."""
        state = self._make_state()
        test_all_apis.run_edge_battery("http://test", state, 2)
        crashed = next(r for r in state.results if "crashed" in r.name)
        self.assertFalse(crashed.passed)
        self.assertIn("boom", crashed.response_body)
        m_auth.assert_called_once()
        m_authz.assert_called_once()
        m_pag.assert_called_once()
        m_res.assert_called_once()
        m_money.assert_called_once()


class TestOrchestrationEdgeCases(unittest.TestCase):
    """Edge-case tests for the untested orchestration helpers in test-all-apis.py.

    All HTTP/flow functions are exercised with mocked run_test/http_request so
    they run deterministically without a live server. Assertions document the
    *actual* current behavior so regressions (including accidental fixes that
    change semantics) surface in CI.
    """

    @staticmethod
    def _result(passed=True, status_code=200, skipped=False, skip_reason=""):
        return test_all_apis.TestResult(
            name="mock", group="g", description="d", method="POST", url="http://u",
            request_headers={}, request_body=None, status_code=status_code,
            response_body="{}", passed=passed, skipped=skipped,
            skip_reason=skip_reason)

    # ── extract_json_path envelope stripping ────────────────────────────────

    def test_flat_response_data_prefix_stripped(self):
        flat = {"token": "t1", "customerId": 5}
        self.assertEqual(extract_json_path(flat, "data.token"), "t1")
        self.assertEqual(extract_json_path(flat, "data.customerId"), 5)

    def test_enveloped_response_literal_wins(self):
        env = {"data": {"token": "t2"}}
        self.assertEqual(extract_json_path(env, "data.token"), "t2")

    def test_envelope_list_index_on_both_shapes(self):
        env = {"data": [{"id": 7}]}
        flat = [{"id": 7}]
        self.assertEqual(extract_json_path(env, "data.0.id"), 7)
        self.assertEqual(extract_json_path(flat, "data.0.id"), 7)

    def test_non_data_path_never_stripped(self):
        flat = {"token": "t"}
        self.assertEqual(extract_json_path(flat, "token"), "t")
        self.assertIsNone(extract_json_path(flat, "dat.token"))

    def test_bare_data_path_on_flat_object(self):
        self.assertIsNone(extract_json_path({"a": 1}, "data"))

    # ── resolve_value collection semantics ──────────────────────────────────

    def test_bool_and_number_passthrough(self):
        state = RunState()
        self.assertIs(resolve_value(True, state), True)
        self.assertIs(resolve_value(False, state), False)
        self.assertEqual(resolve_value(3.14, state), 3.14)
        self.assertIsNone(resolve_value(None, state))

    def test_list_items_resolve_without_numeric_conversion(self):
        state = RunState()
        # List items are resolved with key=None → numeric strings stay strings.
        out = resolve_value(["2", "3.5"], state)
        self.assertEqual(out, ["2", "3.5"])
        self.assertIsInstance(out[0], str)

    def test_nested_dict_inside_list_resolves_keys(self):
        state = RunState()
        out = resolve_value([{"quantity": "4"}], state)
        self.assertEqual(out, [{"quantity": 4}])

    def test_numeric_key_with_non_numeric_string_stays_string(self):
        state = RunState()
        out = resolve_value("abc", state, key="quantity")
        self.assertEqual(out, "abc")

    # ── truncate boundary behavior ──────────────────────────────────────────

    def test_zero_limit_truncates_to_marker(self):
        out = truncate("abcdefghij", 0)
        self.assertTrue(out.startswith(""))
        self.assertIn("... [10 more chars]", out)

    def test_negative_limit_current_behavior(self):
        # Documents current behavior: negative limit slices from the end.
        out = truncate("abc", -1)
        self.assertEqual(out, "ab\n... [4 more chars]")

    def test_empty_string_zero_limit_unchanged(self):
        self.assertEqual(truncate("", 0), "")

    # ── _edge_battery_result hook ───────────────────────────────────────────

    def test_battery_result_appends_and_truncates_detail(self):
        state = RunState()
        test_all_apis._register_edge_battery(state)
        test_all_apis._edge_battery_result(
            "Money — NaN rejected", True, 400, "x" * 1000, "http://u", "POST")
        r = state.results[-1]
        self.assertTrue(r.passed)
        self.assertEqual(r.group, "Edge Cases & Boundaries")
        self.assertEqual(len(r.response_body), 400)
        self.assertEqual(r.method, "POST")
        self.assertEqual(r.url, "http://u")

    def test_battery_result_before_registration_raises_clearly(self):
        # results list not wired → _EDGE_STATE["results"] is None → AttributeError.
        # Save/restore global battery state so this test cannot poison later ones.
        saved = test_all_apis._EDGE_STATE.get("results")
        try:
            test_all_apis._EDGE_STATE["results"] = None
            with self.assertRaises(AttributeError):
                test_all_apis._edge_battery_result("n", True, 400, "d")
        finally:
            test_all_apis._EDGE_STATE["results"] = saved

    # ── _probe helper ───────────────────────────────────────────────────────

    def setUp_probe_state(self):
        test_all_apis._EDGE_STATE["base_url"] = "http://probe"
        test_all_apis._EDGE_STATE["timeout"] = 3

    @patch("test_all_apis.http_request")
    def test_probe_success_returns_status_and_text(self, mock_http):
        self.setUp_probe_state()
        mock_http.return_value = (200, "ok", {})
        status, text = test_all_apis._probe("GET", "/ping")
        self.assertEqual(status, 200)
        self.assertEqual(text, "ok")

    @patch("test_all_apis.http_request")
    def test_probe_connection_error_returns_none(self, mock_http):
        self.setUp_probe_state()
        mock_http.side_effect = ConnectionError("down")
        status, text = test_all_apis._probe("GET", "/ping")
        self.assertIsNone(status)
        self.assertIn("down", text)

    @patch("test_all_apis.http_request")
    def test_probe_sets_auth_and_content_type(self, mock_http):
        self.setUp_probe_state()
        mock_http.return_value = (200, "{}", {})
        test_all_apis._probe("POST", "/x", token="tk", body={"a": 1})
        args, _ = mock_http.call_args
        headers = args[2]
        self.assertEqual(headers["Authorization"], "Bearer tk")
        self.assertEqual(headers["Content-Type"], "application/json")
        self.assertEqual(args[3], b'{"a": 1}')

    @patch("test_all_apis.http_request")
    def test_probe_no_token_no_auth_header(self, mock_http):
        self.setUp_probe_state()
        mock_http.return_value = (200, "{}", {})
        test_all_apis._probe("GET", "/x")
        args, _ = mock_http.call_args
        self.assertNotIn("Authorization", args[2])

    # ── _fresh_customer ─────────────────────────────────────────────────────

    @patch("test_all_apis._probe")
    def test_fresh_customer_success(self, mock_probe):
        mock_probe.return_value = (200, json.dumps({"token": "tk9"}))
        state = RunState()
        token, email = test_all_apis._fresh_customer(state, "dup")
        self.assertEqual(token, "tk9")
        self.assertIn("@bhukkad.test", email)
        self.assertIn("edge_dup_", email)

    @patch("test_all_apis._probe")
    def test_fresh_customer_registration_failure(self, mock_probe):
        mock_probe.return_value = (500, "err")
        state = RunState()
        token, email = test_all_apis._fresh_customer(state, "x")
        self.assertIsNone(token)
        self.assertIn("@bhukkad.test", email)

    @patch("test_all_apis._probe")
    def test_fresh_customer_malformed_json(self, mock_probe):
        mock_probe.return_value = (200, "not-json{")
        state = RunState()
        token, _ = test_all_apis._fresh_customer(state, "x")
        self.assertIsNone(token)

    # ── register_or_login ───────────────────────────────────────────────────

    @patch("test_all_apis.run_test")
    def test_register_success_single_call(self, mock_run):
        mock_run.return_value = self._result(passed=True)
        state = RunState()
        ok = test_all_apis.register_or_login(
            "customer", "register_customer", "login_customer", "http://x", state, 5)
        self.assertTrue(ok)
        self.assertEqual(mock_run.call_count, 1)
        self.assertEqual(mock_run.call_args[0][0]["path"], "/api/v1/auth/register")

    @patch("test_all_apis.run_test")
    def test_register_duplicate_falls_back_to_login(self, mock_run):
        mock_run.side_effect = [self._result(passed=False), self._result(passed=True)]
        state = RunState()
        ok = test_all_apis.register_or_login(
            "owner", "register_owner", "login_owner", "http://x", state, 5)
        self.assertTrue(ok)
        self.assertEqual(mock_run.call_count, 2)
        self.assertEqual(mock_run.call_args[0][0]["path"], "/api/v1/auth/login")

    @patch("test_all_apis.run_test")
    def test_register_and_login_both_fail(self, mock_run):
        mock_run.side_effect = [self._result(passed=False), self._result(passed=False)]
        state = RunState()
        ok = test_all_apis.register_or_login(
            "agent", "register_agent", "login_agent", "http://x", state, 5)
        self.assertFalse(ok)

    # ── bootstrap_restaurant_id ─────────────────────────────────────────────

    @patch("test_all_apis.run_test")
    def test_bootstrap_restaurant_short_circuits(self, mock_run):
        state = RunState()
        state.vars["restaurant_id"] = "9"
        test_all_apis.bootstrap_restaurant_id("http://x", state, 5)
        mock_run.assert_not_called()

    @patch("test_all_apis.run_test")
    def test_bootstrap_restaurant_extracts_first_id(self, mock_run):
        mock_run.return_value = self._result(passed=True)
        state = RunState()
        test_all_apis.bootstrap_restaurant_id("http://x", state, 5)
        spec = mock_run.call_args[0][0]
        self.assertEqual(spec["extract"], {"restaurant_id": "data.0.id"})
        self.assertIsNone(spec.get("auth"))

    # ── bootstrap_accounts ──────────────────────────────────────────────────

    @patch("test_all_apis.run_test")
    def test_bootstrap_accounts_three_roles_no_admin(self, mock_run):
        mock_run.return_value = self._result(passed=True)
        state = RunState()
        test_all_apis.bootstrap_accounts("http://x", state, 5, None, None)
        self.assertEqual(mock_run.call_count, 3)
        self.assertNotIn("bootstrap_admin_email", state.vars)

    @patch("test_all_apis.run_test")
    def test_bootstrap_accounts_with_admin(self, mock_run):
        mock_run.return_value = self._result(passed=True)
        state = RunState()
        test_all_apis.bootstrap_accounts("http://x", state, 5, "a@b.c", "pw")
        self.assertEqual(mock_run.call_count, 4)
        self.assertEqual(state.vars["bootstrap_admin_email"], "a@b.c")
        admin_spec = mock_run.call_args[0][0]
        self.assertEqual(admin_spec["body_key"], "login_bootstrap_admin")

    # ── refill_cart_for_order_tests ─────────────────────────────────────────

    @patch("test_all_apis.run_test")
    def test_refill_cart_noop_without_menu_item(self, mock_run):
        state = RunState()
        test_all_apis.refill_cart_for_order_tests("http://x", state, 5)
        mock_run.assert_not_called()

    @patch("test_all_apis.run_test")
    def test_refill_cart_calls_cart_add(self, mock_run):
        mock_run.return_value = self._result(passed=True)
        state = RunState()
        state.vars["menu_item_id"] = "1"
        test_all_apis.refill_cart_for_order_tests("http://x", state, 5)
        spec = mock_run.call_args[0][0]
        self.assertEqual(spec["path"], "/api/v1/cart/add")
        self.assertEqual(spec["auth"], "customer")

    # ── create_cancel_order ─────────────────────────────────────────────────

    @patch("test_all_apis.run_test")
    def test_cancel_order_noop_without_prereqs(self, mock_run):
        state = RunState()
        test_all_apis.create_cancel_order("http://x", state, 5)
        mock_run.assert_not_called()

    @patch("test_all_apis.run_test")
    def test_cancel_order_sends_idempotency_key(self, mock_run):
        mock_run.return_value = self._result(passed=True)
        state = RunState()
        state.vars.update({"menu_item_id": "1", "address_id": "2"})
        test_all_apis.create_cancel_order("http://x", state, 5)
        self.assertEqual(mock_run.call_count, 2)
        order_spec = mock_run.call_args[0][0]
        self.assertIn("Idempotency-Key", order_spec["headers"])
        self.assertEqual(order_spec["path"], "/api/v1/orders/customer/create")

    # ── test_order_idempotency_replay ───────────────────────────────────────

    def _replay_state(self):
        state = RunState()
        state.vars.update({"menu_item_id": "1", "restaurant_id": "2", "address_id": "3"})
        return state

    @patch("test_all_apis.refill_cart_for_order_tests")
    @patch("test_all_apis.run_test")
    def test_replay_same_id_passes(self, mock_run, _mock_refill):
        def side_effect(spec, base_url, st, timeout, verbose):
            if "replay_order_id" in spec.get("extract", {}):
                st.vars["replay_order_id"] = "777"
            if "replay_order_id_2" in spec.get("extract", {}):
                st.vars["replay_order_id_2"] = "777"
            return self._result(passed=True)
        mock_run.side_effect = side_effect
        state = self._replay_state()
        test_all_apis.test_order_idempotency_replay("http://x", state, 5)
        self.assertTrue(state.results[-1].passed)
        self.assertIn("first_id=777", state.results[-1].response_body)

    @patch("test_all_apis.refill_cart_for_order_tests")
    @patch("test_all_apis.run_test")
    def test_replay_different_ids_fails(self, mock_run, _mock_refill):
        def side_effect(spec, base_url, st, timeout, verbose):
            if "replay_order_id" in spec.get("extract", {}):
                st.vars["replay_order_id"] = "111"
            if "replay_order_id_2" in spec.get("extract", {}):
                st.vars["replay_order_id_2"] = "222"
            return self._result(passed=True)
        mock_run.side_effect = side_effect
        state = self._replay_state()
        test_all_apis.test_order_idempotency_replay("http://x", state, 5)
        self.assertFalse(state.results[-1].passed)

    @patch("test_all_apis.refill_cart_for_order_tests")
    @patch("test_all_apis.run_test")
    def test_replay_creation_failure_reports_skip(self, mock_run, _mock_refill):
        mock_run.return_value = self._result(passed=False, status_code=500)
        state = self._replay_state()
        test_all_apis.test_order_idempotency_replay("http://x", state, 5)
        r = state.results[-1]
        self.assertTrue(r.skipped)
        self.assertFalse(r.passed)
        self.assertIn("Order creation unavailable", r.skip_reason)

    def test_replay_no_prereqs_no_result(self):
        state = RunState()
        test_all_apis.test_order_idempotency_replay("http://x", state, 5)
        self.assertEqual(state.results, [])

    # ── test_rate_limit_order_track ─────────────────────────────────────────

    @patch("test_all_apis.http_request")
    def test_rate_limit_429_seen_passes(self, mock_http):
        mock_http.return_value = (429, "too many", {})
        state = RunState()
        state.vars["order_id"] = "5"
        state.tokens["customer_token"] = "tok"
        test_all_apis.test_rate_limit_order_track("http://x", state, 5)
        self.assertTrue(state.results[-1].passed)
        self.assertEqual(state.results[-1].status_code, 429)
        mock_http.assert_called_once()

    @patch("test_all_apis.http_request")
    def test_rate_limit_never_429_fails_after_45(self, mock_http):
        mock_http.return_value = (200, "ok", {})
        state = RunState()
        state.vars["order_id"] = "5"
        state.tokens["customer_token"] = "tok"
        test_all_apis.test_rate_limit_order_track("http://x", state, 5)
        self.assertFalse(state.results[-1].passed)
        self.assertEqual(mock_http.call_count, 45)

    def test_rate_limit_no_prereqs_no_result(self):
        state = RunState()
        test_all_apis.test_rate_limit_order_track("http://x", state, 5)
        self.assertEqual(state.results, [])

    # ── test_order_empty_cart_400 ───────────────────────────────────────────

    @patch("test_all_apis.http_request")
    def test_empty_cart_400_passes(self, mock_http):
        state = RunState()
        state.vars.update({"restaurant_id": "2", "address_id": "3"})
        reg = (200, json.dumps({"token": "t1"}), {})
        order = (400, '{"message":"Cart is empty"}', {})
        mock_http.side_effect = [reg, order]
        test_all_apis.test_order_empty_cart_400("http://x", state, 5)
        r = state.results[-1]
        self.assertTrue(r.passed)
        self.assertEqual(r.status_code, 400)

    @patch("test_all_apis.http_request")
    def test_empty_cart_registration_failure_skips(self, mock_http):
        mock_http.return_value = (500, "boom", {})
        state = RunState()
        state.vars.update({"restaurant_id": "2", "address_id": "3"})
        test_all_apis.test_order_empty_cart_400("http://x", state, 5)
        r = state.results[-1]
        self.assertTrue(r.skipped)
        self.assertIn("Registration", r.skip_reason)

    @patch("test_all_apis.http_request")
    def test_empty_cart_registration_without_token_skips(self, mock_http):
        mock_http.return_value = (200, '{"unexpected": 1}', {})
        state = RunState()
        state.vars.update({"restaurant_id": "2", "address_id": "3"})
        test_all_apis.test_order_empty_cart_400("http://x", state, 5)
        r = state.results[-1]
        self.assertTrue(r.skipped)
        self.assertIn("no token", r.skip_reason)

    @patch("test_all_apis.http_request")
    def test_empty_cart_500_is_failure_not_skip(self, mock_http):
        state = RunState()
        state.vars.update({"restaurant_id": "2", "address_id": "3"})
        reg = (200, json.dumps({"token": "t1"}), {})
        order = (500, '{"message":"boom"}', {})
        mock_http.side_effect = [reg, order]
        test_all_apis.test_order_empty_cart_400("http://x", state, 5)
        r = state.results[-1]
        self.assertFalse(r.passed)
        self.assertFalse(r.skipped)

    # ── setup_review_for_moderation ─────────────────────────────────────────

    @patch("test_all_apis.run_test")
    def test_review_setup_skips_when_review_exists(self, mock_run):
        state = RunState()
        state.vars["review_id"] = "9"
        test_all_apis.setup_review_for_moderation("http://x", state, 5)
        mock_run.assert_not_called()

    @patch("test_all_apis.run_test")
    def test_review_setup_skips_without_order(self, mock_run):
        state = RunState()
        state.vars["menu_item_id"] = "1"
        test_all_apis.setup_review_for_moderation("http://x", state, 5)
        mock_run.assert_not_called()

    @patch("test_all_apis.run_test")
    def test_review_setup_restores_original_order_id(self, mock_run):
        mock_run.return_value = self._result(passed=True)
        state = RunState()
        state.vars.update({"menu_item_id": "1", "order_id": "orig"})
        test_all_apis.setup_review_for_moderation("http://x", state, 5, main_order_id="77")
        self.assertEqual(state.vars["order_id"], "orig")
        self.assertEqual(mock_run.call_count, 1)

    @patch("test_all_apis.run_test")
    def test_review_setup_keeps_main_order_when_no_original(self, mock_run):
        mock_run.return_value = self._result(passed=True)
        state = RunState()
        state.vars["menu_item_id"] = "1"
        test_all_apis.setup_review_for_moderation("http://x", state, 5, main_order_id="77")
        self.assertEqual(state.vars["order_id"], "77")

    # ── setup_invoice_pdf_order ─────────────────────────────────────────────

    def test_invoice_setup_points_order_id(self):
        state = RunState()
        test_all_apis.setup_invoice_pdf_order("http://x", state, 5, main_order_id="42")
        self.assertEqual(state.vars["order_id"], "42")

    def test_invoice_setup_noop_without_order(self):
        state = RunState()
        test_all_apis.setup_invoice_pdf_order("http://x", state, 5)
        self.assertNotIn("order_id", state.vars)


# ═══════════════════════════════════════════════════════════════════════════
# Newly added edge-case coverage (repaired + extended suite)
# ═══════════════════════════════════════════════════════════════════════════


class TestResolveStringMoreEdgeCases(unittest.TestCase):
    """Adversarial placeholder templates and value types for resolve_string."""

    def test_numeric_var_value_stringified(self):
        state = RunState()
        state.vars["n"] = 5
        self.assertEqual(resolve_string("/items/{n}", state), "/items/5")

    def test_vars_take_precedence_over_tokens(self):
        state = RunState()
        state.vars["dup"] = "from_vars"
        state.tokens["dup"] = "from_tokens"
        self.assertEqual(resolve_string("{dup}", state), "from_vars")

    def test_backslash_digits_not_treated_as_regex_backrefs(self):
        """re.sub with a function replacer must return the literal value,
        not expand \\1-style group references."""
        state = RunState()
        state.vars["x"] = "\\1"
        self.assertEqual(resolve_string("{x}", state), "\\1")

    def test_braces_with_spaces_not_substituted(self):
        state = RunState()
        state.vars["a"] = "A"
        self.assertEqual(resolve_string("{ a }", state), "{ a }")

    def test_unmatched_brace_left_untouched(self):
        state = RunState()
        state.vars["a"] = "A"
        self.assertEqual(resolve_string("{a", state), "{a")

    def test_empty_braces_left_untouched(self):
        state = RunState()
        self.assertEqual(resolve_string("{}", state), "{}")

    def test_nested_braces_substitute_inner_only(self):
        state = RunState()
        state.vars["b"] = "B"
        # "{a{b}}" — \w+ cannot span "{a", so only the inner "{b}" matches.
        self.assertEqual(resolve_string("{a{b}}", state), "{aB}")

    def test_placeholder_with_underscore_and_digits(self):
        state = RunState()
        state.vars["order_id_2"] = "77"
        self.assertEqual(resolve_string("/o/{order_id_2}", state), "/o/77")

    def test_adjacent_placeholders(self):
        state = RunState()
        state.vars["a"] = "1"
        state.vars["b"] = "2"
        self.assertEqual(resolve_string("{a}{b}", state), "12")


class TestResolveValueMoreEdgeCases(unittest.TestCase):
    """Coercion boundaries: partial placeholders, exotic numeric formats."""

    def test_partial_placeholder_under_numeric_key_stays_string(self):
        state = RunState()
        state.vars["n"] = "5"
        result = resolve_value("{n} items", state, key="quantity")
        self.assertEqual(result, "5 items")
        self.assertIsInstance(result, str)

    def test_unresolved_placeholder_under_numeric_key_stays_template(self):
        state = RunState()
        result = resolve_value("{never_set}", state, key="quantity")
        self.assertEqual(result, "{never_set}")

    def test_numeric_key_with_surrounding_whitespace_coerces(self):
        """Documents current Python int() semantics: whitespace is tolerated."""
        state = RunState()
        state.vars["v"] = " 42 "
        self.assertEqual(resolve_value("{v}", state, key="quantity"), 42)

    def test_trailing_dot_coerces_to_float(self):
        state = RunState()
        state.vars["v"] = "42."
        result = resolve_value("{v}", state, key="price")
        self.assertIsInstance(result, float)
        self.assertEqual(result, 42.0)

    def test_scientific_notation_coerces_to_float(self):
        state = RunState()
        state.vars["v"] = "1e5"
        result = resolve_value("{v}", state, key="price")
        self.assertIsInstance(result, float)
        self.assertAlmostEqual(result, 100000.0)

    def test_bare_numeric_literal_without_placeholder_stays_string(self):
        state = RunState()
        # No placeholder at all; coercion only applies after substitution —
        # a raw literal "42" under key=None must stay a string.
        self.assertEqual(resolve_value("42", state), "42")

    def test_tuple_passthrough_untouched(self):
        state = RunState()
        tpl = (1, "a")
        self.assertIs(resolve_value(tpl, state), tpl)

    def test_bool_values_inside_dict_preserved(self):
        state = RunState()
        result = resolve_value({"active": True, "deleted": False}, state)
        self.assertIs(result["active"], True)
        self.assertIs(result["deleted"], False)

    def test_empty_containers_resolve_to_empty(self):
        state = RunState()
        self.assertEqual(resolve_value({}, state), {})
        self.assertEqual(resolve_value([], state), [])

    def test_unknown_key_never_coerces(self):
        state = RunState()
        state.vars["v"] = "199"
        result = resolve_value("{v}", state, key="totallyUnknownKey")
        self.assertEqual(result, "199")
        self.assertIsInstance(result, str)


class TestExtractJsonPathMoreEdgeCases(unittest.TestCase):
    """Falsy values, exotic dict shapes, and envelope-fallback boundaries."""

    def test_falsy_values_returned_verbatim(self):
        self.assertEqual(extract_json_path({"a": 0}, "a"), 0)
        self.assertIs(extract_json_path({"a": False}, "a"), False)
        self.assertEqual(extract_json_path({"a": ""}, "a"), "")

    def test_digit_key_on_dict_returns_none(self):
        """Documented quirk: a digit path segment only indexes LISTS, so a
        dict with a string "0" key is not reachable by "0"."""
        self.assertIsNone(extract_json_path({"0": "zero"}, "0"))

    def test_dotted_key_not_traversable(self):
        self.assertIsNone(extract_json_path({"a.b": 1}, "a.b"))

    def test_double_data_envelope_walks(self):
        data = {"data": {"data": {"id": 9}}}
        self.assertEqual(extract_json_path(data, "data.data.id"), 9)

    def test_flat_double_data_path_not_rescued(self):
        """Only ONE leading data. is stripped; data.data.token on a flat body
        (whose root has no data) must not be resurrected."""
        self.assertIsNone(extract_json_path({"token": "x"}, "data.data.token"))

    def test_bare_data_path_returns_envelope_when_present(self):
        data = {"data": {"token": "t"}}
        self.assertEqual(extract_json_path(data, "data"), {"token": "t"})


class TestHttpRequestMoreEdgeCases(unittest.TestCase):
    """Request mutation details: method case, header precedence, timeouts."""

    def _mock_response(self, status=200, body=b'{}', headers=None):
        resp = MagicMock()
        resp.__enter__ = MagicMock(return_value=resp)
        resp.__exit__ = MagicMock(return_value=False)
        resp.status = status
        resp.read.return_value = body
        resp.headers = headers if headers is not None else {}
        return resp

    @patch("test_all_apis.urlopen")
    def test_method_is_uppercased_on_wire(self, mock_urlopen):
        mock_urlopen.return_value = self._mock_response()
        http_request("post", "http://x/a", {}, b"{}", 5)
        req = mock_urlopen.call_args[0][0]
        self.assertEqual(req.get_method(), "POST")

    def test_explicit_content_type_not_overwritten(self):
        with patch("test_all_apis.urlopen") as m:
            m.return_value = self._mock_response()
            http_request("POST", "http://x/a", {"Content-Type": "text/plain"}, b"x", 5)
            req = m.call_args[0][0]
            self.assertEqual(req.get_header("Content-type"), "text/plain")

    def test_get_without_body_has_no_content_type(self):
        with patch("test_all_apis.urlopen") as m:
            m.return_value = self._mock_response()
            http_request("GET", "http://x/a", {}, None, 5)
            req = m.call_args[0][0]
            self.assertIsNone(req.data)
            self.assertIsNone(req.get_header("Content-type"))

    def test_sse_accept_overrides_timeout_to_five(self):
        with patch("test_all_apis.urlopen") as m:
            m.return_value = self._mock_response()
            http_request("GET", "http://x/sse", {"Accept": "text/event-stream"}, None, 30)
            self.assertEqual(m.call_args.kwargs["timeout"], 5)

    def test_non_sse_uses_given_timeout(self):
        with patch("test_all_apis.urlopen") as m:
            m.return_value = self._mock_response()
            http_request("GET", "http://x/a", {}, None, 17)
            self.assertEqual(m.call_args.kwargs["timeout"], 17)

    def test_response_headers_converted_to_plain_dict(self):
        with patch("test_all_apis.urlopen") as m:
            m.return_value = self._mock_response(headers={"X-Total-Count": "5"})
            _, _, hdrs = http_request("GET", "http://x/a", {}, None, 5)
            self.assertIsInstance(hdrs, dict)
            self.assertEqual(hdrs.get("X-Total-Count"), "5")


class TestRunTestMoreEdgeCases(unittest.TestCase):
    """Coverage for run_test paths that had no tests: skip gating, header and
    query handling, template specials (scheduled orders, webhook replay ids)."""

    def _mock_response_obj(self, status=200, body=b'{}'):
        resp = MagicMock()
        resp.__enter__ = MagicMock(return_value=resp)
        resp.__exit__ = MagicMock(return_value=False)
        resp.status = status
        resp.read.return_value = body
        resp.headers = {}
        return resp

    def test_requires_empty_string_var_skips(self):
        state = RunState()
        state.vars["shop_id"] = ""
        spec = {"name": "NeedShop", "method": "GET", "path": "/api/v1/shop/{shop_id}",
                "expected": [200], "requires": ["shop_id"]}
        result = run_test(spec, "http://x", state, 5, verbose=False)
        self.assertTrue(result.skipped)
        self.assertIn("shop_id", result.skip_reason)

    def test_requires_empty_token_skips(self):
        state = RunState()
        state.tokens["customer_token"] = ""
        spec = {"name": "NeedTok", "method": "GET", "path": "/api/v1/x",
                "expected": [200], "requires": ["customer_token"]}
        result = run_test(spec, "http://x", state, 5, verbose=False)
        self.assertTrue(result.skipped)
        self.assertIn("Missing required token", result.skip_reason)

    def test_auth_accepts_raw_token_key_not_in_auth_map(self):
        """auth values absent from AUTH_MAP are treated as token names directly."""
        with patch("test_all_apis.urlopen") as m:
            m.return_value = self._mock_response_obj()
            state = RunState()
            state.tokens["owner_token"] = "raw.key"
            spec = {"name": "OwnerRaw", "method": "GET", "path": "/api/v1/owner",
                    "expected": [200], "auth": "owner_token"}
            result = run_test(spec, "http://x", state, 5, verbose=False)
            self.assertTrue(result.passed)
            req = m.call_args[0][0]
            self.assertEqual(req.get_header("Authorization"), "Bearer raw.key")

    def test_unknown_custom_auth_skips_with_role_name(self):
        state = RunState()
        spec = {"name": "Weird", "method": "GET", "path": "/api/v1/w",
                "expected": [200], "auth": "weird_token"}
        result = run_test(spec, "http://x", state, 5, verbose=False)
        self.assertTrue(result.skipped)
        self.assertIn("weird_token", result.skip_reason)

    def test_custom_headers_resolved_and_authorization_stripped_from_report(self):
        state = RunState()
        state.init_defaults("pw")
        state.tokens["customer_token"] = "jwt.secret"
        spec = {"name": "Hdrs", "method": "POST", "path": "/api/v1/h",
                "expected": [200], "auth": "customer",
                "headers": {"X-Idem": "{idempotency_key}"}}
        with patch("test_all_apis.urlopen") as m:
            m.return_value = self._mock_response_obj()
            result = run_test(spec, "http://x", state, 5, verbose=False)
            req = m.call_args[0][0]
            # On the wire: full auth + resolved custom header
            self.assertEqual(req.get_header("Authorization"), "Bearer jwt.secret")
            self.assertEqual(req.get_header("X-idem"), state.vars["idempotency_key"])
            # In the report: Authorization redacted, custom header resolved
            self.assertNotIn("Authorization", result.request_headers)
            self.assertEqual(result.request_headers["X-Idem"], state.vars["idempotency_key"])

    def test_query_none_and_empty_values_dropped(self):
        state = RunState()
        state.init_defaults("pw")
        state.vars["blank"] = ""
        spec = {"name": "Q", "method": "GET", "path": "/api/v1/z", "expected": [200],
                "query": {"a": None, "b": "{blank}", "c": "1"}}
        with patch("test_all_apis.urlopen") as m:
            m.return_value = self._mock_response_obj()
            result = run_test(spec, "http://x", state, 5, verbose=False)
            self.assertEqual(result.url, "http://x/api/v1/z?c=1")

    def test_base_url_trailing_slash_normalized(self):
        state = RunState()
        with patch("test_all_apis.urlopen") as m:
            m.return_value = self._mock_response_obj()
            result = run_test({"name": "S", "method": "GET", "path": "/api/v1/z",
                               "expected": [200]}, "http://x/", state, 5, verbose=False)
            self.assertEqual(result.url, "http://x/api/v1/z")

    def test_unknown_body_key_sends_no_body(self):
        """A body_key missing from BODY_TEMPLATES must degrade to no body,
        not crash and not send an empty dict."""
        state = RunState()
        with patch("test_all_apis.urlopen") as m:
            m.return_value = self._mock_response_obj()
            result = run_test({"name": "NB", "method": "POST", "path": "/p",
                               "body_key": "no_such_template_xyz",
                               "expected": [200]}, "http://x", state, 5, verbose=False)
            req = m.call_args[0][0]
            self.assertIsNone(req.data)
            self.assertIsNone(result.request_body)

    def test_scheduled_order_gets_future_scheduled_at(self):
        """The scheduled_order template receives scheduledAt = now + 35 min."""
        from datetime import datetime
        state = RunState()
        state.init_defaults("pw")
        spec = {"name": "Sched", "method": "POST", "path": "/api/v1/orders/schedule",
                "body_key": "scheduled_order", "expected": [200]}
        with patch("test_all_apis.urlopen") as m:
            m.return_value = self._mock_response_obj()
            result = run_test(spec, "http://x", state, 5, verbose=False)
            scheduled = datetime.strptime(result.request_body["scheduledAt"],
                                          "%Y-%m-%dT%H:%M:%S")
            delta_min = (scheduled - datetime.now()).total_seconds() / 60
            self.assertGreater(delta_min, 30)
            self.assertLess(delta_min, 40)

    def test_razorpay_webhook_payment_id_unique_per_invocation(self):
        """Replay protection: each webhook spec run must mint a fresh paymentId."""
        state = RunState()
        spec = {"name": "WH", "method": "POST", "path": "/api/v1/payments/webhook",
                "body_key": "razorpay_webhook", "expected": [200, 404]}
        with patch("test_all_apis.urlopen") as m:
            m.return_value = self._mock_response_obj()
            first = run_test(spec, "http://x", state, 5, verbose=False)
            second = run_test(spec, "http://x", state, 5, verbose=False)
        self.assertEqual(first.request_body["paymentId"], "pay_test1")
        self.assertEqual(second.request_body["paymentId"], "pay_test2")

    def test_extract_tolerates_malformed_response_json(self):
        state = RunState()
        spec = {"name": "Bad", "method": "GET", "path": "/p", "expected": [200],
                "extract": {"shop_id": "data.id"}}
        with patch("test_all_apis.urlopen") as m:
            m.return_value = self._mock_response_obj(200, b"this is not json{{{")
            result = run_test(spec, "http://x", state, 5, verbose=False)
            self.assertTrue(result.passed)
            self.assertNotIn("shop_id", state.vars)

    def test_400_logs_warning_with_response_message(self):
        state = RunState()
        spec = {"name": "V400", "method": "POST", "path": "/p", "expected": [200]}
        with patch("test_all_apis.urlopen") as m:
            m.return_value = self._mock_response_obj(
                400, b'{"message":"must not be blank"}')
            with self.assertLogs("test_all_apis", level="WARNING") as ctx:
                run_test(spec, "http://x", state, 5, verbose=False)
        self.assertTrue(any("returned 400" in line and "must not be blank" in line
                            for line in ctx.output))

    def test_passes_when_status_matches_any_expected(self):
        state = RunState()
        spec = {"name": "Multi", "method": "POST", "path": "/p",
                "expected": [200, 201, 204]}
        with patch("test_all_apis.urlopen") as m:
            m.return_value = self._mock_response_obj(201)
            result = run_test(spec, "http://x", state, 5, verbose=False)
        self.assertTrue(result.passed)
        self.assertEqual(result.status_code, 201)

    def test_group_and_description_copied_into_result(self):
        state = RunState()
        with patch("test_all_apis.urlopen") as m:
            m.return_value = self._mock_response_obj()
            result = run_test({"name": "G", "group": "My Group",
                               "description": "desc!", "method": "GET",
                               "path": "/p", "expected": [200]},
                              "http://x", state, 5, verbose=False)
        self.assertEqual(result.group, "My Group")
        self.assertEqual(result.description, "desc!")
        self.assertIsInstance(result.duration_ms, int)
        self.assertGreaterEqual(result.duration_ms, 0)


class TestApplyAuthExtractMoreEdgeCases(unittest.TestCase):

    def test_integer_value_stringified(self):
        state = RunState()
        state.init_defaults("pw")
        apply_auth_extract(state, {"customer_id": "customerId"}, {"customerId": 42})
        self.assertEqual(state.vars["customer_id"], "42")
        self.assertIsInstance(state.vars["customer_id"], str)

    def test_token_varals_populate_plain_vars(self):
        state = RunState()
        apply_auth_extract(state, {"customer_token": "token"}, {"token": "abc"})
        self.assertEqual(state.vars["customer_token"], "abc")
        self.assertEqual(state.tokens["customer_token"], "abc")

    def test_refresh_token_lands_in_tokens_via_suffix_rule(self):
        state = RunState()
        apply_auth_extract(state, {"customer_refresh_token": "refreshToken"},
                           {"refreshToken": "rt"})
        self.assertEqual(state.tokens["customer_refresh_token"], "rt")

    def test_zero_valued_extraction_still_stored(self):
        """0 is falsy but not None/empty — var extraction must store it."""
        state = RunState()
        state.init_defaults("pw")
        apply_auth_extract(state, {"page_count": "count"}, {"count": 0})
        self.assertEqual(state.vars["page_count"], "0")


class TestProbeAndFreshCustomerEdgeCases(unittest.TestCase):
    """Edge behavior of the low-level battery probe + throwaway accounts."""

    def setUp(self):
        self._saved = dict(test_all_apis._EDGE_STATE)
        test_all_apis._EDGE_STATE["base_url"] = "http://probe"
        test_all_apis._EDGE_STATE["timeout"] = 3

    def tearDown(self):
        test_all_apis._EDGE_STATE.clear()
        test_all_apis._EDGE_STATE.update(self._saved)

    @patch.object(test_all_apis, "http_request")
    def test_probe_custom_headers_override_defaults(self, mock_http):
        mock_http.return_value = (200, "{}", {})
        test_all_apis._probe("GET", "/x", headers={"Accept": "text/csv"})
        headers = mock_http.call_args[0][2]
        self.assertEqual(headers["Accept"], "text/csv")

    @patch.object(test_all_apis, "http_request")
    def test_probe_without_body_omits_content_type(self, mock_http):
        mock_http.return_value = (200, "{}", {})
        test_all_apis._probe("GET", "/x")
        headers = mock_http.call_args[0][2]
        self.assertNotIn("Content-Type", headers)
        self.assertIsNone(mock_http.call_args[0][3])

    @patch.object(test_all_apis, "http_request")
    def test_probe_passes_4xx_5xx_through_unchanged(self, mock_http):
        mock_http.return_value = (418, "teapot", {})
        status, text = test_all_apis._probe("GET", "/x")
        self.assertEqual(status, 418)
        self.assertEqual(text, "teapot")

    @patch.object(test_all_apis, "_probe")
    def test_fresh_customer_uses_state_password_and_97_phone(self, mock_probe):
        mock_probe.return_value = (200, '{"token":"tk"}')
        state = RunState()
        state.init_defaults("Sup3rSecret!")
        token, email = test_all_apis._fresh_customer(state, "pwcheck")
        self.assertEqual(token, "tk")
        body = mock_probe.call_args.kwargs["body"]
        self.assertEqual(body["password"], "Sup3rSecret!")
        self.assertTrue(body["phoneNumber"].startswith("97"))
        self.assertEqual(len(body["phoneNumber"]), 10)
        self.assertIn("edge_pwcheck_", email)

    @patch.object(test_all_apis, "_probe")
    def test_fresh_customer_defaults_password_when_state_empty(self, mock_probe):
        mock_probe.return_value = (200, '{"token":"tk"}')
        state = RunState()
        test_all_apis._fresh_customer(state, "empty")
        body = mock_probe.call_args.kwargs["body"]
        self.assertEqual(body["password"], "Test@123456")

    @patch.object(test_all_apis, "_probe")
    def test_fresh_customer_does_not_unwrap_data_envelope(self, mock_probe):
        """Documents current flat-body assumption: an enveloped register
        response yields no token (batteries skip gracefully)."""
        mock_probe.return_value = (200, '{"data":{"token":"tk"}}')
        state = RunState()
        token, email = test_all_apis._fresh_customer(state, "env")
        self.assertIsNone(token)
        self.assertIn("@bhukkad.test", email)


class TestResetDatabaseMoreEdgeCases(unittest.TestCase):

    @patch("test_all_apis.subprocess.run")
    def test_psql_timeout_while_listing_returns_false(self, mock_run):
        import subprocess as sp
        mock_run.side_effect = sp.TimeoutExpired("psql", 30)
        with patch.dict(os.environ, {"DB_PASSWORD": "secret"}):
            self.assertFalse(reset_database())

    @patch("test_all_apis.subprocess.run")
    def test_unparseable_db_url_falls_back_to_env_defaults(self, mock_run):
        mock_run.side_effect = [
            MagicMock(returncode=0, stdout="orders\n", stderr=""),
            MagicMock(returncode=0, stdout="TRUNCATE", stderr=""),
            MagicMock(returncode=0, stdout="INSERT 0 1", stderr=""),
            MagicMock(returncode=0, stdout="DO", stderr=""),
        ]
        env = {"DB_HOST": "envhost", "DB_PORT": "5555", "DB_NAME": "envdb",
               "DB_USERNAME": "envuser", "DB_PASSWORD": "envsecret"}
        with patch.dict(os.environ, env):
            reset_database("jdbc:postgresql://not/parsable")
        argv = mock_run.call_args_list[0][0][0]
        self.assertEqual(argv[argv.index("-h") + 1], "envhost")
        self.assertEqual(argv[argv.index("-p") + 1], "5555")
        self.assertEqual(argv[argv.index("-U") + 1], "envuser")
        self.assertEqual(argv[argv.index("-d") + 1], "envdb")

    @patch("test_all_apis.subprocess.run")
    def test_pgpassword_exported_from_db_url_password(self, mock_run):
        mock_run.side_effect = [
            MagicMock(returncode=0, stdout="orders\n", stderr=""),
            MagicMock(returncode=0, stdout="TRUNCATE", stderr=""),
            MagicMock(returncode=0, stdout="INSERT 0 1", stderr=""),
            MagicMock(returncode=0, stdout="DO", stderr=""),
        ]
        with patch.dict(os.environ, {}, clear=False):
            reset_database("postgres://u:sup3r@h:6543/d")
            self.assertEqual(os.environ["PGPASSWORD"], "sup3r")
        argv = mock_run.call_args_list[0][0][0]
        self.assertEqual(argv[argv.index("-h") + 1], "h")
        self.assertEqual(argv[argv.index("-p") + 1], "6543")
        self.assertEqual(argv[argv.index("-d") + 1], "d")


class TestReportWritersMoreEdgeCases(unittest.TestCase):

    def test_markdown_error_result_rendered(self):
        results = [TestResult(name="X", group="Err", description="", method="GET",
                              url="/x", request_headers={}, request_body=None,
                              status_code=None, response_body="", passed=False,
                              skipped=False, error="timed out")]
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "r.md"
            write_markdown_report(results, path, "http://x")
            content = path.read_text()
            self.assertIn("**Error:** timed out", content)

    def test_markdown_repeats_group_header_on_non_contiguous_groups(self):
        """Groups are emitted on every *change* from the previous result; a
        non-contiguous group appears in more than one section (documented)."""
        results = [
            TestResult(name="a", group="A", description="", method="GET", url="",
                       request_headers={}, request_body=None, status_code=200,
                       response_body="{}", passed=True, skipped=False),
            TestResult(name="b", group="B", description="", method="GET", url="",
                       request_headers={}, request_body=None, status_code=200,
                       response_body="{}", passed=True, skipped=False),
            TestResult(name="c", group="A", description="", method="GET", url="",
                       request_headers={}, request_body=None, status_code=200,
                       response_body="{}", passed=True, skipped=False),
        ]
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "r.md"
            write_markdown_report(results, path, "http://x")
            content = path.read_text()
            self.assertEqual(content.count("## A"), 2)

    def test_markdown_long_response_truncated_at_four_k(self):
        results = [TestResult(name="Big", group="G", description="", method="GET",
                              url="", request_headers={}, request_body=None,
                              status_code=200, response_body="x" * 4096,
                              passed=True, skipped=False)]
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "r.md"
            write_markdown_report(results, path, "http://x")
            content = path.read_text()
            # "xxxx…" is not valid JSON → pretty_json passes it through
            # unchanged, then the markdown writer truncates at 4000 chars.
            self.assertIn("... [96 more chars]", content)

    def test_json_report_body_truncated_at_eight_k(self):
        results = [TestResult(name="Big", group="G", description="", method="GET",
                              url="", request_headers={"A": "b"},
                              request_body={"k": "v"}, status_code=200,
                              response_body="x" * 9000, passed=True, skipped=False)]
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "r.json"
            write_json_report(results, path, "http://x")
            data = json.loads(path.read_text())
            body = data["tests"][0]["responseBody"]
            self.assertIn("... [1000 more chars]", body)
            self.assertEqual(data["tests"][0]["requestHeaders"], {"A": "b"})
            self.assertEqual(data["tests"][0]["requestBody"], {"k": "v"})

    def test_json_report_has_iso_generated_at(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "r.json"
            write_json_report([], path, "http://x")
            data = json.loads(path.read_text())
            # Parses without error on any valid ISO-8601 timestamp.
            from datetime import datetime as dt
            dt.fromisoformat(data["generatedAt"])


class TestEdgeBatteryWiringMoreEdgeCases(unittest.TestCase):

    def test_run_edge_battery_primes_global_state(self):
        """run_edge_battery must inject base_url/timeout and the results sink
        before batteries execute (batteries read _EDGE_STATE, not RunState)."""
        saved = dict(test_all_apis._EDGE_STATE)
        try:
            with patch.object(test_all_apis, "battery_auth_edges"), \
                 patch.object(test_all_apis, "battery_authz_edges"), \
                 patch.object(test_all_apis, "battery_pagination_edges"), \
                 patch.object(test_all_apis, "battery_resource_edges"), \
                 patch.object(test_all_apis, "battery_money_edges"), \
                 patch.object(test_all_apis, "battery_webhook_edges"):
                state = RunState()
                state.init_defaults("pw")
                test_all_apis.run_edge_battery("http://wired", state, 9)
            self.assertEqual(test_all_apis._EDGE_STATE["base_url"], "http://wired")
            self.assertEqual(test_all_apis._EDGE_STATE["timeout"], 9)
            self.assertIs(test_all_apis._EDGE_STATE["results"], state.results)
        finally:
            test_all_apis._EDGE_STATE.clear()
            test_all_apis._EDGE_STATE.update(saved)

    def test_battery_webhook_edges_accepts_404_and_rejects_200(self):
        """404 on a forged webhook = no side effect (pass); 200 = the bug."""
        saved = dict(test_all_apis._EDGE_STATE)
        try:
            for status, expect_pass, calls in ((404, True, []), (200, False, ["side effect"])):
                state = RunState()
                state.init_defaults("pw")
                test_all_apis._EDGE_STATE["base_url"] = "http://t"
                test_all_apis._EDGE_STATE["timeout"] = 2
                test_all_apis._register_edge_battery(state)
                with patch.object(test_all_apis, "_probe", return_value=(status, "x")):
                    test_all_apis.battery_webhook_edges("http://t", state, 2)
                res = next(r for r in state.results if "invalid signature body" in r.name)
                self.assertIs(res.passed, expect_pass,
                              f"status {status}: passed={res.passed}")
        finally:
            test_all_apis._EDGE_STATE.clear()
            test_all_apis._EDGE_STATE.update(saved)

    def test_pagination_battery_accepts_clamp_or_reject(self):
        """Both clamp-to-empty (200) and hard-reject (400) designs pass."""
        saved = dict(test_all_apis._EDGE_STATE)
        try:
            for status in (200, 400):
                state = RunState()
                state.init_defaults("pw")
                state.tokens["customer_token"] = "tok"
                test_all_apis._EDGE_STATE["base_url"] = "http://t"
                test_all_apis._EDGE_STATE["timeout"] = 2
                test_all_apis._register_edge_battery(state)
                with patch.object(test_all_apis, "_fresh_customer",
                                  return_value=(None, "x@y.test")), \
                     patch.object(test_all_apis, "_probe",
                                  return_value=(status, "{}")):
                    test_all_apis.battery_pagination_edges("http://t", state, 2)
                pag = [r for r in state.results if "Pagination —" in r.name]
                self.assertEqual(len(pag), 6)
                self.assertTrue(all(r.passed for r in pag), f"status {status}")
        finally:
            test_all_apis._EDGE_STATE.clear()
            test_all_apis._EDGE_STATE.update(saved)

    def test_resource_battery_flags_fabricated_wallet_data(self):
        """A 200 for an unknown wallet id that echoes the id AND a balance is
        fabricated data — battery_resource_edges must flag it as a failure,
        while the 404-correct siblings still pass."""
        saved = dict(test_all_apis._EDGE_STATE)
        try:
            state = RunState()
            state.init_defaults("pw")
            state.tokens["customer_token"] = "tok"
            test_all_apis._EDGE_STATE["base_url"] = "http://t"
            test_all_apis._EDGE_STATE["timeout"] = 2
            test_all_apis._register_edge_battery(state)

            def probe(method, path, token=None, body=None, headers=None):
                if "wallet" in path:
                    return 200, '{"customerId":99999999,"balance":42}'
                return 404, "nf"

            with patch.object(test_all_apis, "_probe", side_effect=probe):
                test_all_apis.battery_resource_edges("http://t", state, 2)
            wallet = next(r for r in state.results if "wallet" in r.url)
            self.assertFalse(wallet.passed)
            self.assertIn("fabricated_data=True", wallet.response_body)
            others = [r for r in state.results
                      if "unknown id" in r.name and "wallet" not in r.url]
            self.assertTrue(all(r.passed for r in others))
        finally:
            test_all_apis._EDGE_STATE.clear()
            test_all_apis._EDGE_STATE.update(saved)

    def test_probe_connection_error_marked_failed_not_skipped(self):
        """Probe returning None status (connection error) fails the case."""
        saved = dict(test_all_apis._EDGE_STATE)
        try:
            state = RunState()
            state.init_defaults("pw")
            test_all_apis._EDGE_STATE["base_url"] = "http://t"
            test_all_apis._EDGE_STATE["timeout"] = 2
            test_all_apis._register_edge_battery(state)
            with patch.object(test_all_apis, "_probe",
                              return_value=(None, "connection refused")):
                test_all_apis.battery_webhook_edges("http://t", state, 2)
            res = next(r for r in state.results if "invalid signature body" in r.name)
            self.assertFalse(res.passed)
            self.assertIsNone(res.status_code)
            # response detail carries the connection error excerpt
            self.assertIn("connection refused", res.response_body)
        finally:
            test_all_apis._EDGE_STATE.clear()
            test_all_apis._EDGE_STATE.update(saved)

    def test_detail_truncated_to_four_hundred_chars(self):
        saved = dict(test_all_apis._EDGE_STATE)
        try:
            state = RunState()
            test_all_apis._register_edge_battery(state)
            test_all_apis._edge_battery_result("T", True, 200, "d" * 900)
            self.assertEqual(len(state.results[-1].response_body), 400)
        finally:
            test_all_apis._EDGE_STATE.clear()
            test_all_apis._EDGE_STATE.update(saved)


class TestCatalogIntegrityMoreChecks(unittest.TestCase):
    """Deeper structural invariants over the shared API catalog."""

    def test_all_paths_absolute(self):
        for spec in API_CATALOG:
            self.assertTrue(spec["path"].startswith("/"),
                            f"path must start with '/': {spec['name']}")

    def test_method_is_uppercase(self):
        for spec in API_CATALOG:
            self.assertEqual(spec["method"], spec["method"].upper(),
                             f"method must be uppercase: {spec['name']}")

    def test_expected_codes_are_valid_http_range(self):
        for spec in API_CATALOG:
            for code in spec["expected"]:
                self.assertIsInstance(code, int, spec["name"])
                self.assertTrue(100 <= code < 600,
                                f"invalid status expectation {code} in {spec['name']}")

    def test_names_are_nonempty_strings(self):
        for spec in API_CATALOG:
            self.assertIsInstance(spec.get("name"), str)
            self.assertTrue(spec["name"].strip(), f"empty name in {spec}")

    def test_header_values_are_strings(self):
        for spec in API_CATALOG:
            for k, v in (spec.get("headers") or {}).items():
                self.assertIsInstance(k, str, spec["name"])
                self.assertIsInstance(v, str,
                                       f"header {k} in {spec['name']} not a string")

    def test_extract_paths_are_strings(self):
        for spec in API_CATALOG:
            for var, path in (spec.get("extract") or {}).items():
                self.assertIsInstance(var, str)
                self.assertIsInstance(path, str,
                                       f"extract {var} in {spec['name']} not a string")

    def test_requires_entries_are_reachable(self):
        """Every requires entry must be satisfiable at runtime: a var produced
        by init_defaults, a *_token name, an extract target of any catalog
        spec, or a key produced by an orchestration helper in the runner
        script (its inline specs extract into RunState.vars)."""
        state = RunState()
        state.init_defaults("Test@123456")
        runtime_keys = set(state.vars.keys())
        produced = set()
        for spec in API_CATALOG:
            produced.update((spec.get("extract") or {}).keys())
        # Scan the runner source for helper-produced vars: inline extract maps
        # and direct state.vars["x"] = assignments.
        runner_src = test_all_apis.__file__
        src = Path(runner_src).read_text()
        for m in re.finditer(r'["\'](\w+)["\']\s*:\s*"data', src):
            produced.add(m.group(1))
        for m in re.finditer(r'state\.vars\["(\w+)"\]\s*=', src):
            produced.add(m.group(1))
        for spec in API_CATALOG:
            for req in spec.get("requires", []):
                ok = req in runtime_keys or req.endswith("_token") or req in produced
                self.assertTrue(ok,
                                f"requirement '{req}' of {spec['name']} is unreachable "
                                f"(never in vars, tokens, or any extract target)")

    def test_json_serializable_body_templates(self):
        for key, tpl in BODY_TEMPLATES.items():
            try:
                json.dumps(resolve_value(tpl, RunState()))
            except TypeError as e:  # pragma: no cover - regression guard
                self.fail(f"template {key} not JSON-serializable: {e}")


class TestCheckServerAvailableMoreEdgeCases(unittest.TestCase):
    """Health-probing edge behavior."""

    def _resp(self, status):
        resp = MagicMock()
        resp.__enter__ = MagicMock(return_value=resp)
        resp.__exit__ = MagicMock(return_value=False)
        resp.status = status
        resp.read.return_value = b'{}'
        resp.headers = {}
        return resp

    @patch("test_all_apis.urlopen")
    def test_falls_through_non_200_until_actuator_ok(self, mock_urlopen):
        mock_urlopen.side_effect = [self._resp(204), self._resp(503), self._resp(200)]
        self.assertTrue(check_server_available("http://x", 2))
        self.assertEqual(mock_urlopen.call_count, 3)

    @patch("test_all_apis.urlopen")
    def test_connection_then_recovery_on_second_endpoint(self, mock_urlopen):
        from urllib.error import URLError
        mock_urlopen.side_effect = [URLError("refused"), self._resp(200)]
        self.assertTrue(check_server_available("http://x", 2))


class TestPrintResultMoreEdgeCases(unittest.TestCase):
    """Output-formatting corners: N/A status, request body on verbose pass."""

    def test_pass_verbose_shows_request_and_response(self):
        buf = io.StringIO()
        r = TestResult(name="OK", group="G", description="", method="POST",
                       url="/x", request_headers={}, request_body={"a": 1},
                       status_code=201, response_body='{"b":2}', passed=True,
                       skipped=False)
        with redirect_stdout(buf):
            print_result(r, verbose=True)
        out = buf.getvalue()
        self.assertIn("Request:", out)
        self.assertIn("Response:", out)

    def test_short_body_no_truncation_marker(self):
        buf = io.StringIO()
        r = TestResult(name="OK", group="G", description="", method="GET",
                       url="/x", request_headers={}, request_body=None,
                       status_code=200, response_body='{"tiny":1}', passed=True,
                       skipped=False)
        with redirect_stdout(buf):
            print_result(r, verbose=True)
        self.assertNotIn("more chars", buf.getvalue())


if __name__ == "__main__":
    unittest.main(verbosity=2)
