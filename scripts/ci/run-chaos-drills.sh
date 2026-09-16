#!/usr/bin/env bash
# run-chaos-drills.sh — master runner for all chaos drills.
# Usage:
#   ./scripts/ci/run-chaos-drills.sh --base-url http://staging.bhukkad.com --namespace bhukkad
#   ./scripts/ci/run-chaos-drills.sh --drill redis

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BASE_URL="${CHAOS_BASE_URL:-http://localhost:8080}"
NAMESPACE="${CHAOS_NAMESPACE:-bhukkad}"
DRILL="${CHAOS_DRILL:-all}"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --base-url) BASE_URL="$2"; shift 2 ;;
        --namespace) NAMESPACE="$2"; shift 2 ;;
        --drill) DRILL="$2"; shift 2 ;;
        *) echo "Unknown arg: $1"; exit 1 ;;
    esac
done

echo "=== Chaos Drills ==="
echo "  base_url: $BASE_URL"
echo "  namespace: $NAMESPACE"
echo "  drill: $DRILL"

python3 "${SCRIPT_DIR}/chaos-drills.py" \
    --base-url "$BASE_URL" \
    --namespace "$NAMESPACE" \
    --drill "$DRILL"
