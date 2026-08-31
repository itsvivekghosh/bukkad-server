#!/usr/bin/env bash
#
# Newman runner for the Bhukkad API Postman collection.
# Executes the reconstructed collection (postman/Bhukkad-API.postman_collection.json)
# against a running instance and asserts status codes / response schemas.
#
# Usage:
#   ./scripts/run-postman.sh            # uses BASE_URL env or default localhost:8080
#   BASE_URL=https://staging.example.com ./scripts/run-postman.sh
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
COLLECTION="$ROOT_DIR/postman/Bhukkad-API.postman_collection.json"
ENV_FILE="$ROOT_DIR/postman/Bhukkad-API.postman_environment.json"
BASE_URL="${BASE_URL:-http://localhost:8080/api/v1}"

if ! command -v newman >/dev/null 2>&1; then
  echo "ERROR: 'newman' is not installed. Install with: npm install -g newman" >&2
  exit 1
fi

if [ ! -f "$COLLECTION" ]; then
  echo "ERROR: collection not found at $COLLECTION" >&2
  exit 1
fi

echo "Running Postman collection against $BASE_URL"

if [ -f "$ENV_FILE" ]; then
  newman run "$COLLECTION" --environment "$ENV_FILE" \
    --env-var "baseUrl=$BASE_URL" \
    --reporters cli,json --reporter-json-export "$ROOT_DIR/target/postman-report.json" \
    --color on
else
  newman run "$COLLECTION" \
    --env-var "baseUrl=$BASE_URL" \
    --reporters cli,json --reporter-json-export "$ROOT_DIR/target/postman-report.json" \
    --color on
fi
