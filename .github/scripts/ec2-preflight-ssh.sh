#!/usr/bin/env bash
# Pre-flight check: verify GitHub Actions runner can reach EC2 SSH before deploy.
set -euo pipefail

SSH_HOST="${1:?SSH host required}"
SSH_PORT="${2:-22}"
TIMEOUT="${3:-10}"

RUNNER_IP="$(curl -fsS --max-time 5 https://api.ipify.org 2>/dev/null || echo "unknown")"
echo "GitHub Actions runner egress IP: ${RUNNER_IP}"
echo "Testing TCP ${SSH_PORT} on ${SSH_HOST} (timeout ${TIMEOUT}s)..."

if command -v nc >/dev/null 2>&1; then
  if nc -z -G "${TIMEOUT}" -w "${TIMEOUT}" "${SSH_HOST}" "${SSH_PORT}" 2>/dev/null; then
    echo "SSH port ${SSH_PORT} is reachable from this runner."
    exit 0
  fi
else
  if timeout "${TIMEOUT}" bash -c "echo >/dev/tcp/${SSH_HOST}/${SSH_PORT}" 2>/dev/null; then
    echo "SSH port ${SSH_PORT} is reachable from this runner."
    exit 0
  fi
fi

cat <<EOF
ERROR: Cannot reach ${SSH_HOST}:${SSH_PORT} from GitHub Actions (connection timed out).

This is almost always an AWS Security Group issue. GitHub-hosted runners use
dynamic IPs — your EC2 SG likely only allows your personal IP, not GitHub's.

Fix in AWS Console → EC2 → Security Groups → Inbound rules:
  1. Add rule: Type=SSH, Port=22, Source=0.0.0.0/0  (quick test)
     OR add GitHub Actions IP ranges from: https://api.github.com/meta (actions key)
  2. Ensure the instance is running and has a public IP/DNS matching STAGING_SSH_HOST
  3. Re-run the workflow

Runner IP for reference (may change each run): ${RUNNER_IP}
EOF
exit 1
