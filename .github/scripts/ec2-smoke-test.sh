#!/usr/bin/env bash
set -euo pipefail

SSH_USER="${1:?SSH user required}"
SSH_HOST="${2:?SSH host required}"
SSH_KEY="${3:?SSH key path required}"
HEALTH_PATH="${4:-/api/v1/health/ping}"
PORT="${5:-8080}"

SSH_OPTS=(-i "$SSH_KEY" -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o ConnectTimeout=30)
REMOTE="${SSH_USER}@${SSH_HOST}"

ssh "${SSH_OPTS[@]}" "$REMOTE" "curl -fsS http://localhost:${PORT}${HEALTH_PATH}"
echo "Smoke test passed on ${SSH_HOST}"
