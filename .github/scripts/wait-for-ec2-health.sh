#!/usr/bin/env bash
# Wait for the app container health endpoint on the EC2 host (localhost from SSH session).
# Uses short SSH sessions per poll so long waits do not hit broken-pipe disconnects.
set -euo pipefail

SSH_USER="${1:?SSH user required}"
SSH_HOST="${2:?SSH host required}"
SSH_KEY="${3:?SSH key path required}"
MAX_ATTEMPTS="${4:-60}"
SLEEP_SECONDS="${5:-5}"
CONTAINER_NAME="${6:-bhukkad-app}"
HEALTH_URL="${7:-http://localhost:8080/api/v1/health/ping}"

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

echo "Waiting for container health on ${REMOTE} (up to $((MAX_ATTEMPTS * SLEEP_SECONDS))s)..."
echo "Polling with fresh logs each attempt (container: ${CONTAINER_NAME})..."

CONSECUTIVE_OK=0

for i in $(seq 1 "${MAX_ATTEMPTS}"); do
  echo ""
  echo "=== Health check ${i}/${MAX_ATTEMPTS} ==="

  if ssh "${SSH_OPTS[@]}" "$REMOTE" \
    "CONTAINER_NAME=${CONTAINER_NAME} HEALTH_URL=${HEALTH_URL} bash -s" <<'REMOTE_SCRIPT'
set -euo pipefail

if docker inspect "${CONTAINER_NAME}" >/dev/null 2>&1; then
  docker logs --tail 40 "${CONTAINER_NAME}" 2>&1 || true
else
  echo "(container ${CONTAINER_NAME} not found yet)"
fi

if curl -sf "${HEALTH_URL}" -o /dev/null 2>/dev/null; then
  exit 0
fi
exit 1
REMOTE_SCRIPT
  then
    CONSECUTIVE_OK=$((CONSECUTIVE_OK + 1))
    echo "Health OK (${CONSECUTIVE_OK}/3 consecutive)"
    if [ "${CONSECUTIVE_OK}" -ge 3 ]; then
      echo "Container is healthy after ${i} attempts (3 consecutive successes)"
      exit 0
    fi
  else
    CONSECUTIVE_OK=0
    echo "Health not ready yet"
  fi

  sleep "${SLEEP_SECONDS}"
done

echo ""
echo "Container failed to become healthy"
ssh "${SSH_OPTS[@]}" "$REMOTE" \
  "CONTAINER_NAME=${CONTAINER_NAME} bash -s" <<'REMOTE_SCRIPT'
set -euo pipefail
echo "=== Container status ==="
docker ps -a --filter "name=${CONTAINER_NAME}" --format "{{.Image}} {{.Status}}" 2>&1 || true
echo "=== Last 80 log lines ==="
docker logs "${CONTAINER_NAME}" 2>&1 | tail -80 || true
REMOTE_SCRIPT
exit 1
