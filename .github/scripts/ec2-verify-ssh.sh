#!/usr/bin/env bash
# Verify SSH authentication to EC2 (not just TCP port 22).
set -euo pipefail

SSH_USER="${1:?SSH user required}"
SSH_HOST="${2:?SSH host required}"
SSH_KEY="${3:?SSH key path required}"
TIMEOUT="${4:-20}"

RUNNER_IP="$(curl -fsS --max-time 5 https://api.ipify.org 2>/dev/null || echo "unknown")"
echo "GitHub Actions runner egress IP: ${RUNNER_IP}"
echo "Verifying SSH authentication to ${SSH_USER}@${SSH_HOST} (timeout ${TIMEOUT}s)..."

SSH_OPTS=(
  -i "${SSH_KEY}"
  -o StrictHostKeyChecking=no
  -o UserKnownHostsFile=/dev/null
  -o ConnectTimeout="${TIMEOUT}"
  -o BatchMode=yes
)

if ssh "${SSH_OPTS[@]}" "${SSH_USER}@${SSH_HOST}" 'echo SSH authentication OK'; then
  echo "SSH authentication succeeded."
  exit 0
fi

cat <<EOF
ERROR: SSH authentication failed for ${SSH_USER}@${SSH_HOST}.

Common causes:
- STAGING_SSH_KEY / PROD_SSH_KEY does not match the EC2 authorized_keys
- Instance is out of memory (SSH banner timeout) — check EC2 console / reboot
- STAGING_SSH_HOST / PROD_SSH_HOST points to an old IP after instance stop/start

Runner IP for reference (changes each run): ${RUNNER_IP}
EOF
exit 1
