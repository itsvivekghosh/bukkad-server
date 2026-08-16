#!/usr/bin/env bash
# Wrapper for the Python API test runner (see scripts/test-all-apis.py)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
exec python3 scripts/test-all-apis.py "$@"
