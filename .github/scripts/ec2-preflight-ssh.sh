#!/usr/bin/env bash
# Pre-flight check: verify GitHub Actions runner can reach EC2 SSH before deploy.
set -euo pipefail

SSH_HOST="${1:?SSH host required}"
SSH_PORT="${2:-22}"
TIMEOUT="${3:-10}"

RUNNER_IP="$(curl -fsS --max-time 5 https://api.ipify.org 2>/dev/null || echo "unknown")"
echo "GitHub Actions runner egress IP: ${RUNNER_IP}"
echo "Testing TCP ${SSH_PORT} on ${SSH_HOST} (timeout ${TIMEOUT}s)..."

port_reachable() {
  # Prefer bash /dev/tcp — works on GitHub Actions ubuntu-latest (Linux).
  if timeout "${TIMEOUT}" bash -c "echo >/dev/tcp/${SSH_HOST}/${SSH_PORT}" 2>/dev/null; then
    return 0
  fi
  # Fallback: OpenBSD/GNU netcat on Linux uses -w only (no -G; -G is macOS-only).
  if command -v nc >/dev/null 2>&1; then
    if nc -z -w "${TIMEOUT}" "${SSH_HOST}" "${SSH_PORT}" 2>/dev/null; then
      return 0
    fi
  fi
  return 1
}

if port_reachable; then
  echo "SSH port ${SSH_PORT} is reachable from this runner."
  exit 0
fi

cat <<EOF
ERROR: Cannot reach ${SSH_HOST}:${SSH_PORT} from GitHub Actions.

If you recently opened the security group, confirm inbound SSH (22) allows 0.0.0.0/0
and STAGING_SSH_HOST is the instance public DNS (not a private IP).

AWS Console → EC2 → Security Groups → Inbound rules:
  Type=SSH, Port=22, Source=0.0.0.0/0

Runner IP for reference (changes each run): ${RUNNER_IP}
EOF
exit 1
