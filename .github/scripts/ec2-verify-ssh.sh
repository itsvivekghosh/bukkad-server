#!/usr/bin/env bash
# Verify SSH authentication to EC2 (not just TCP port 22).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=deploy-log.sh
source "${SCRIPT_DIR}/deploy-log.sh"

SSH_USER="${1:?SSH user required}"
SSH_HOST="${2:?SSH host required}"
SSH_KEY="${3:?SSH key path required}"
TIMEOUT="${4:-20}"

log_section "Verify SSH authentication"
RUNNER_IP="$(curl -fsS --max-time 5 https://api.ipify.org 2>/dev/null || echo "unknown")"
log_kv "Runner egress IP" "${RUNNER_IP}"
log_kv "SSH target" "${SSH_USER}@${SSH_HOST}"
log_kv "Timeout" "${TIMEOUT}s"

SSH_OPTS=(
  -i "${SSH_KEY}"
  -o StrictHostKeyChecking=no
  -o UserKnownHostsFile=/dev/null
  -o ConnectTimeout="${TIMEOUT}"
  -o BatchMode=yes
)

if ssh "${SSH_OPTS[@]}" "${SSH_USER}@${SSH_HOST}" 'echo SSH authentication OK'; then
  log_ok "SSH authentication succeeded"
  log_section_end
  exit 0
fi

log_error "SSH authentication failed for ${SSH_USER}@${SSH_HOST}"
log_info "Common causes:"
log_info "  - SSH key secret does not match EC2 authorized_keys"
log_info "  - Instance out of memory (banner timeout) — check EC2 console"
log_info "  - SSH host points to old IP after stop/start — update EC2 host variable"
log_info "Runner IP (changes each run): ${RUNNER_IP}"
log_section_end
exit 1
