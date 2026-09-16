#!/usr/bin/env bash
# run-load-tests.sh — master runner for k6 load-test scenarios.
#
# Usage:
#   ./scripts/ci/run-load-tests.sh --scenario browse --base-url http://staging.bhukkad.com
#   ./scripts/ci/run-load-tests.sh --scenario peak-5x
#
# Exits non-zero when k6 thresholds fail.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BASE_URL="${LOAD_TEST_BASE_URL:-http://localhost:8080}"
SCENARIO="${LOAD_TEST_SCENARIO:-browse}"
TEST_EMAIL="${LOAD_TEST_EMAIL:-loadtest@example.com}"
TEST_PASSWORD="${LOAD_TEST_PASSWORD:-LoadTest@123456}"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --scenario) SCENARIO="$2"; shift 2 ;;
        --base-url) BASE_URL="$2"; shift 2 ;;
        --test-email) TEST_EMAIL="$2"; shift 2 ;;
        --test-password) TEST_PASSWORD="$2"; shift 2 ;;
        *) echo "Unknown arg: $1"; exit 1 ;;
    esac
done

echo "=== Load Test ==="
echo "  scenario: $SCENARIO"
echo "  base_url: $BASE_URL"

# Resolve the k6 script path. Root loadtest/ has the main scenarios;
# scripts/loadtest/ has legacy smoke/comprehensive scripts.
SCRIPT=""
if [[ -f "loadtest/${SCENARIO}.js" ]]; then
    SCRIPT="loadtest/${SCENARIO}.js"
elif [[ -f "scripts/loadtest/${SCENARIO}.js" ]]; then
    SCRIPT="scripts/loadtest/${SCENARIO}.js"
else
    echo "ERROR: scenario script not found: ${SCENARIO}.js"
    echo "Checked loadtest/ and scripts/loadtest/"
    exit 1
fi

echo "  script: $SCRIPT"

if ! command -v k6 &> /dev/null; then
    echo "ERROR: k6 is not installed. Install from https://k6.io/docs/getting-started/installation/"
    exit 1
fi

k6 run "$SCRIPT" \
    -e BASE_URL="$BASE_URL" \
    -e TEST_EMAIL="$TEST_EMAIL" \
    -e TEST_PASSWORD="$TEST_PASSWORD"
