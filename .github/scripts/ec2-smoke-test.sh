#!/usr/bin/env bash
# Smoke test the API on EC2 via SSH (localhost). Works without a custom domain or public port 8080.
set -euo pipefail

SSH_USER="${1:?SSH user required}"
SSH_HOST="${2:?SSH host required}"
SSH_KEY="${3:?SSH key path required}"
HEALTH_PATH="${4:-/api/v1/health/ping}"
PORT="${5:-8080}"

SSH_OPTS=(-i "$SSH_KEY" -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null)
REMOTE="${SSH_USER}@${SSH_HOST}"

echo "Smoke test: http://localhost:${PORT}${HEALTH_PATH} on ${REMOTE}"
ssh "${SSH_OPTS[@]}" "$REMOTE" \
  "curl -fsS http://localhost:${PORT}${HEALTH_PATH}"

echo "Smoke test passed on ${SSH_HOST}"
