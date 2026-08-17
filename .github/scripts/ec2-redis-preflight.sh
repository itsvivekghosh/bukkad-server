#!/usr/bin/env bash
# Verify Redis is reachable from the EC2 host before deploy (VPC DNS + security groups).
set -euo pipefail

SSH_USER="${1:?SSH user required}"
SSH_HOST="${2:?SSH host required}"
SSH_KEY="${3:?SSH key path required}"
ENV_FILE="${4:?Env file path required}"

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

echo "Checking Redis connectivity from ${REMOTE}..."
base64 "$ENV_FILE" | ssh "${SSH_OPTS[@]}" "$REMOTE" "base64 -d > /tmp/redis-preflight-env.txt && chmod 600 /tmp/redis-preflight-env.txt"

ssh "${SSH_OPTS[@]}" "$REMOTE" "bash -s" <<'REMOTE_SCRIPT'
set -euo pipefail
set -a && source /tmp/redis-preflight-env.txt && set +a
rm -f /tmp/redis-preflight-env.txt

REDIS_HOST="${REDIS_HOST:-}"
REDIS_PORT="${REDIS_PORT:-6379}"

if [ -z "${REDIS_HOST}" ]; then
  echo "::error::REDIS_HOST is empty — set STAGING_REDIS_HOST in GitHub secrets"
  exit 1
fi

case "${REDIS_HOST}" in
  redis://*|rediss://*)
    echo "::error::REDIS_HOST must be a hostname only (not a URL). Use STAGING_REDIS_HOST=my-cache.amazonaws.com and STAGING_REDIS_PORT=6379"
    exit 1
    ;;
esac

if [[ "${REDIS_HOST}" == *:* && "${REDIS_HOST}" != \[*\] ]]; then
  echo "::error::REDIS_HOST must not include a port — set STAGING_REDIS_PORT separately (current host: ${REDIS_HOST})"
  exit 1
fi

if ! [[ "${REDIS_PORT}" =~ ^[0-9]+$ ]]; then
  echo "::error::REDIS_PORT must be numeric (got: '${REDIS_PORT}'). Set STAGING_REDIS_PORT=6379"
  exit 1
fi

echo "Resolving ${REDIS_HOST}..."
if ! getent hosts "${REDIS_HOST}" >/dev/null 2>&1; then
  echo "::error::Cannot resolve REDIS_HOST '${REDIS_HOST}' on EC2 — check VPC DNS or use the ElastiCache primary endpoint"
  exit 1
fi
getent hosts "${REDIS_HOST}"

echo "Testing TCP ${REDIS_HOST}:${REDIS_PORT}..."
if ! timeout 10 bash -c "echo >/dev/tcp/${REDIS_HOST}/${REDIS_PORT}" 2>/dev/null; then
  echo "::error::Cannot reach Redis at ${REDIS_HOST}:${REDIS_PORT} from EC2 — open the Redis security group to this instance"
  exit 1
fi

echo "Redis is reachable from EC2"
REMOTE_SCRIPT
