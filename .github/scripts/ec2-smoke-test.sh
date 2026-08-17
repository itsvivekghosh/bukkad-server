#!/usr/bin/env bash
# Smoke test the API on EC2 via SSH (localhost). Works without a custom domain or public port 8080.
set -euo pipefail

SSH_USER="${1:?SSH user required}"
SSH_HOST="${2:?SSH host required}"
SSH_KEY="${3:?SSH key path required}"
HEALTH_PATH="${4:-/api/v1/health/ping}"
PORT="${5:-8080}"
MAX_ATTEMPTS="${6:-10}"
SLEEP_SECONDS="${7:-3}"

SSH_OPTS=(
  -i "$SSH_KEY"
  -o StrictHostKeyChecking=no
  -o UserKnownHostsFile=/dev/null
  -o ConnectTimeout=30
  -o ServerAliveInterval=15
  -o ServerAliveCountMax=8
  -o TCPKeepAlive=yes
)
REMOTE="${SSH_USER}@${SSH_HOST}"

echo "Smoke test: http://localhost:${PORT}${HEALTH_PATH} on ${REMOTE} (up to ${MAX_ATTEMPTS} attempts)..."

ssh "${SSH_OPTS[@]}" "$REMOTE" \
  "MAX_ATTEMPTS=${MAX_ATTEMPTS} SLEEP_SECONDS=${SLEEP_SECONDS} PORT=${PORT} HEALTH_PATH=${HEALTH_PATH} bash -s" <<'REMOTE_SCRIPT'
set -euo pipefail

for i in $(seq 1 "${MAX_ATTEMPTS}"); do
  if response=$(curl -fsS --connect-timeout 5 --max-time 15 "http://localhost:${PORT}${HEALTH_PATH}" 2>&1); then
    echo "${response}"
    echo "Smoke test passed on attempt ${i}"
    exit 0
  fi
  echo "Smoke test attempt ${i}/${MAX_ATTEMPTS} failed: ${response:-connection error}"
  sleep "${SLEEP_SECONDS}"
done

echo "Smoke test failed after ${MAX_ATTEMPTS} attempts"
docker ps -a --filter name=bhukkad-app --format "{{.Image}} {{.Status}}" 2>&1 || true
exit 1
REMOTE_SCRIPT

echo "Smoke test passed on ${SSH_HOST}"
