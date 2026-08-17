#!/usr/bin/env bash
# Wait for the app health endpoint on EC2 (polled via SSH to localhost).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=deploy-log.sh
source "${SCRIPT_DIR}/deploy-log.sh"

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
MAX_WAIT=$((MAX_ATTEMPTS * SLEEP_SECONDS))

log_section "Wait for application health"
log_kv "Target" "${REMOTE}"
log_kv "Container" "${CONTAINER_NAME}"
log_kv "Health URL" "${HEALTH_URL}"
log_kv "Max wait" "${MAX_WAIT}s (${MAX_ATTEMPTS} attempts × ${SLEEP_SECONDS}s)"
log_kv "Success rule" "3 consecutive healthy responses"

CONSECUTIVE_OK=0

for i in $(seq 1 "${MAX_ATTEMPTS}"); do
  if ssh "${SSH_OPTS[@]}" "$REMOTE" \
    "CONTAINER_NAME=${CONTAINER_NAME} HEALTH_URL=${HEALTH_URL} bash -s" <<'REMOTE_SCRIPT'
set -euo pipefail
if curl -sf "${HEALTH_URL}" -o /dev/null 2>/dev/null; then
  exit 0
fi
exit 1
REMOTE_SCRIPT
  then
    CONSECUTIVE_OK=$((CONSECUTIVE_OK + 1))
    log_info "Attempt ${i}/${MAX_ATTEMPTS}: healthy (${CONSECUTIVE_OK}/3 consecutive)"
    if [ "${CONSECUTIVE_OK}" -ge 3 ]; then
      log_ok "Application is healthy after ${i} attempts"
      log_section_end
      exit 0
    fi
  else
    CONSECUTIVE_OK=0
    if [ $((i % 6)) -eq 0 ] || [ "${i}" -eq 1 ]; then
      log_info "Attempt ${i}/${MAX_ATTEMPTS}: not ready yet — checking container status"
      ssh "${SSH_OPTS[@]}" "$REMOTE" \
        "CONTAINER_NAME=${CONTAINER_NAME} bash -s" <<'REMOTE_SCRIPT' || true
set -euo pipefail
if docker inspect "${CONTAINER_NAME}" >/dev/null 2>&1; then
  docker ps --filter "name=${CONTAINER_NAME}" --format "  Status: {{.Status}}"
  docker logs --tail 8 "${CONTAINER_NAME}" 2>&1 | sed 's/^/  /' || true
else
  echo "  Container not found yet"
fi
REMOTE_SCRIPT
    else
      log_info "Attempt ${i}/${MAX_ATTEMPTS}: not ready yet"
    fi
  fi

  sleep "${SLEEP_SECONDS}"
done

log_error "Application did not become healthy within ${MAX_WAIT}s"
log_info "Fetching diagnostic logs from EC2..."
ssh "${SSH_OPTS[@]}" "$REMOTE" \
  "CONTAINER_NAME=${CONTAINER_NAME} bash -s" <<'REMOTE_SCRIPT' || true
set -euo pipefail
echo "--- Container status ---"
docker ps -a --filter "name=${CONTAINER_NAME}" --format "Image: {{.Image}} | Status: {{.Status}}" 2>&1 || true
echo "--- Last 40 log lines ---"
docker logs "${CONTAINER_NAME}" 2>&1 | tail -40 || true
REMOTE_SCRIPT
log_section_end
exit 1
