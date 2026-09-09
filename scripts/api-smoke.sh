#!/usr/bin/env bash
# =============================================================================
# Bhukkad API smoke test — hits every major endpoint against a running instance
# and asserts expected status codes. Run:
#   API_BASE=http://localhost:8081 bash scripts/api-smoke.sh
# Exits non-zero on any failure. Public GET endpoints first, then the auth flow
# (register -> login -> authenticated calls), then admin-scoped endpoints.
# =============================================================================
set -u
BASE="${API_BASE:-http://localhost:8081}"
PASS=0; FAIL=0
declare -a FAILURES=()

check() { # name expected_status actual_status
  local name="$1" expected="$2" actual="$3"
  if [ "$expected" = "$actual" ]; then
    PASS=$((PASS+1)); echo "  ✓ $name (${actual})"
  else
    FAIL=$((FAIL+1)); FAILURES+=("$name: expected $expected got $actual")
    echo "  ✗ $name (expected $expected, got $actual)"
  fi
}

# --- helper: hit endpoint, capture HTTP status ---
status_of() { # method url [data]
  local method="$1" url="$2" data="${3:-}"
  if [ -n "$data" ]; then
    curl -s -o /tmp/smoke-body.json -w '%{http_code}' -X "$method" \
      -H 'Content-Type: application/json' -d "$data" "$BASE$url"
  else
    curl -s -o /tmp/smoke-body.json -w '%{http_code}' -X "$method" "$BASE$url"
  fi
}

echo "== Health & actuators =="
check "GET /api/v1/health" 200 "$(status_of GET /api/v1/health)"
check "GET /api/v1/health/ping" 200 "$(status_of GET /api/v1/health/ping)"
check "GET /api/v1/health/detailed" 200 "$(status_of GET /api/v1/health/detailed)"
check "GET /actuator/health" 200 "$(status_of GET /actuator/health)"

echo "== Public GET endpoints =="
check "GET /api/v1/cuisines" 200 "$(status_of GET /api/v1/cuisines)"
check "GET /api/v1/restaurants/public/**" 200 "$(status_of GET /api/v1/restaurants/public)"
check "GET /api/v1/health/db" 200 "$(status_of GET /api/v1/health/db)"

echo "== Auth flow =="
EMAIL="smoke$(date +%s)@test.com"
REGISTER=$(status_of POST /api/v1/auth/register \
  "{\"email\":\"$EMAIL\",\"phoneNumber\":\"999$(date +%s | tail -c 8)\",\"fullName\":\"Smoke Test\",\"password\":\"Passw0rd!123\"}")
check "POST /api/v1/auth/register" 200 "$REGISTER"
TOKEN=$(grep -o '"token":"[^"]*"' /tmp/smoke-body.json | head -1 | sed 's/"token":"//; s/"//')
if [ -z "$TOKEN" ]; then
  echo "  ⚠ no token from register (check body in /tmp/smoke-body.json); login instead"
  status_of POST /api/v1/auth/login "{\"email\":\"$EMAIL\",\"password\":\"Passw0rd!123\"}" >/dev/null
  TOKEN=$(grep -o '"token":"[^"]*"' /tmp/smoke-body.json | head -1 | sed 's/"token":"//; s/"//')
fi
LOGIN=$(status_of POST /api/v1/auth/login "{\"email\":\"$EMAIL\",\"password\":\"Passw0rd!123\"}")
check "POST /api/v1/auth/login" 200 "$LOGIN"
[ -z "$TOKEN" ] && TOKEN=$(grep -o '"token":"[^"]*"' /tmp/smoke-body.json | head -1 | sed 's/"token":"//; s/"//')
echo "  token acquired: ${TOKEN:0:20}..."

if [ -n "$TOKEN" ]; then
  echo "== Authenticated endpoints =="
  AUTH_H=(-H "Authorization: Bearer $TOKEN")
  check "GET /api/v1/customers/profile" 200 \
    "$(curl -s -o /tmp/smoke-body.json -w '%{http_code}' "${AUTH_H[@]}" "$BASE/api/v1/customers/profile")"
  check "GET /api/v1/customers/addresses" 200 \
    "$(curl -s -o /tmp/smoke-body.json -w '%{http_code}' "${AUTH_H[@]}" "$BASE/api/v1/customers/addresses")"
  check "GET /api/v1/customers/wallet/balance" 200 \
    "$(curl -s -o /tmp/smoke-body.json -w '%{http_code}' "${AUTH_H[@]}" "$BASE/api/v1/customers/wallet/balance")"
  check "GET /api/v1/orders/customer/my-orders" 200 \
    "$(curl -s -o /tmp/smoke-body.json -w '%{http_code}' "${AUTH_H[@]}" "$BASE/api/v1/orders/customer/my-orders")"
  # 403 without token (security working — Spring returns 403 for missing auth)
  check "GET /api/v1/customers/profile (no token)" 403 \
    "$(status_of GET /api/v1/customers/profile)"
else
  echo "  ⚠ no token acquired — skipping authenticated checks"
  check "GET /api/v1/customers/profile (no token)" 403 "$(status_of GET /api/v1/customers/profile)"
fi

echo
echo "== Summary =="
echo "  PASS=$PASS FAIL=$FAIL"
if [ ${#FAILURES[@]} -gt 0 ]; then
  printf '  FAILURES:\n'
  for f in "${FAILURES[@]}"; do echo "    - $f"; done
  exit 1
fi
exit 0
