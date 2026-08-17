#!/usr/bin/env bash
set -euo pipefail

SSH_USER="${1:?SSH user required}"
SSH_HOST="${2:?SSH host required}"
SSH_KEY="${3:?SSH key path required}"
MAX_ATTEMPTS="${4:-60}"
SLEEP_SECONDS="${5:-5}"

SSH_OPTS=(-i "$SSH_KEY" -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o ConnectTimeout=30)
REMOTE="${SSH_USER}@${SSH_HOST}"

ssh "${SSH_OPTS[@]}" "$REMOTE" \
  "MAX_ATTEMPTS=${MAX_ATTEMPTS} SLEEP_SECONDS=${SLEEP_SECONDS} bash -s" <<'REMOTE_SCRIPT'
set -euo pipefail
for i in $(seq 1 "${MAX_ATTEMPTS}"); do
  if curl -sf http://localhost:8080/api/v1/health/ping -o /dev/null; then
    echo "Container is healthy after ${i} attempts"
    exit 0
  fi
  echo "Attempt ${i}: container not ready yet..."
  sleep "${SLEEP_SECONDS}"
done
docker ps -a --filter name=bhukkad-app --format "{{.Image}} {{.Status}}" 2>&1 || true
docker logs bhukkad-app 2>&1 | tail -80 || true
exit 1
REMOTE_SCRIPT
