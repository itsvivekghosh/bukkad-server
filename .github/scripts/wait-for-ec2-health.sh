#!/usr/bin/env bash
# Wait for the app container health endpoint on the EC2 host (localhost from SSH session).
# Streams container logs in real time while polling the health endpoint.
set -euo pipefail

SSH_USER="${1:?SSH user required}"
SSH_HOST="${2:?SSH host required}"
SSH_KEY="${3:?SSH key path required}"
MAX_ATTEMPTS="${4:-60}"
SLEEP_SECONDS="${5:-5}"
CONTAINER_NAME="${6:-bhukkad-app}"
HEALTH_URL="${7:-http://localhost:8080/api/v1/health/ping}"

SSH_OPTS=(-i "$SSH_KEY" -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o ConnectTimeout=30)
REMOTE="${SSH_USER}@${SSH_HOST}"

echo "Waiting for container health on ${REMOTE} (up to $((MAX_ATTEMPTS * SLEEP_SECONDS))s)..."
echo "Streaming logs from ${CONTAINER_NAME} (Ctrl+C in local runs stops the wait only)..."

ssh "${SSH_OPTS[@]}" "$REMOTE" \
  "MAX_ATTEMPTS=${MAX_ATTEMPTS} SLEEP_SECONDS=${SLEEP_SECONDS} CONTAINER_NAME=${CONTAINER_NAME} HEALTH_URL=${HEALTH_URL} bash -s" <<'REMOTE_SCRIPT'
set -euo pipefail

CONSECUTIVE_OK=0

LOG_PID=""
cleanup() {
  if [ -n "${LOG_PID}" ]; then
    kill "${LOG_PID}" 2>/dev/null || true
    wait "${LOG_PID}" 2>/dev/null || true
  fi
}
trap cleanup EXIT

start_log_stream() {
  if [ -n "${LOG_PID}" ]; then
    return
  fi
  if docker inspect "${CONTAINER_NAME}" >/dev/null 2>&1; then
    echo "=== ${CONTAINER_NAME} logs (live) ==="
    docker logs -f --tail 50 "${CONTAINER_NAME}" 2>&1 &
    LOG_PID=$!
  fi
}

for i in $(seq 1 30); do
  if docker inspect "${CONTAINER_NAME}" >/dev/null 2>&1; then
    start_log_stream
    break
  fi
  sleep 1
done

for i in $(seq 1 "${MAX_ATTEMPTS}"); do
  start_log_stream
  if curl -sf "${HEALTH_URL}" -o /dev/null 2>/dev/null; then
    CONSECUTIVE_OK=$((CONSECUTIVE_OK + 1))
    if [ "${CONSECUTIVE_OK}" -ge 3 ]; then
      echo ""
      echo "Container is healthy after ${i} attempts (3 consecutive successes)"
      exit 0
    fi
  else
    CONSECUTIVE_OK=0
  fi
  sleep "${SLEEP_SECONDS}"
done

cleanup
trap - EXIT

echo ""
echo "Container failed to become healthy"
echo "=== Container status ==="
docker ps -a --filter "name=${CONTAINER_NAME}" --format "{{.Image}} {{.Status}}" 2>&1 || true
echo "=== Last 80 log lines ==="
docker logs "${CONTAINER_NAME}" 2>&1 | tail -80 || true
exit 1
REMOTE_SCRIPT
