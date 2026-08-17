#!/usr/bin/env bash
# Log in to GitHub Container Registry on EC2 so docker pull works for private packages.
set -euo pipefail

SSH_USER="${1:?SSH user required}"
SSH_HOST="${2:?SSH host required}"
SSH_KEY="${3:?SSH key path required}"
GHCR_USERNAME="${4:?GHCR username required}"
GHCR_TOKEN="${5:?GHCR token required}"

if [ -z "${GHCR_TOKEN}" ]; then
  echo "ERROR: GHCR_READ_TOKEN secret is empty."
  echo "Add a GitHub PAT with read:packages scope as repo secret GHCR_READ_TOKEN"
  echo "Create at: https://github.com/settings/tokens"
  exit 1
fi

SSH_OPTS=(-i "$SSH_KEY" -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o ConnectTimeout=30)
REMOTE="${SSH_USER}@${SSH_HOST}"

echo "Logging in to ghcr.io on ${REMOTE} as ${GHCR_USERNAME}..."

# Pass token via stdin to remote docker login (never echo the token).
printf '%s' "${GHCR_TOKEN}" | ssh "${SSH_OPTS[@]}" "$REMOTE" \
  "docker login ghcr.io -u '${GHCR_USERNAME}' --password-stdin"

echo "GHCR login successful on ${REMOTE}"
