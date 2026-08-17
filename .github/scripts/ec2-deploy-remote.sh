#!/usr/bin/env bash
# Copy docker-deploy.sh + env file to EC2 and run the deployment.
# Used by GitHub Actions deploy workflows.
set -euo pipefail

SSH_USER="${1:?SSH user required}"
SSH_HOST="${2:?SSH host required}"
SSH_KEY="${3:?SSH key path required}"
DEPLOY_SCRIPT="${4:?Deploy script path required}"
ENV_FILE="${5:?Env file path required}"

SSH_OPTS=(-i "$SSH_KEY" -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o ConnectTimeout=30)
REMOTE="${SSH_USER}@${SSH_HOST}"

echo "Deploying to ${REMOTE}..."
scp "${SSH_OPTS[@]}" "$DEPLOY_SCRIPT" "${REMOTE}:/tmp/docker-deploy.sh"
base64 "$ENV_FILE" | ssh "${SSH_OPTS[@]}" "$REMOTE" "base64 -d > /tmp/deploy-env.txt && chmod 600 /tmp/deploy-env.txt"
ssh "${SSH_OPTS[@]}" "$REMOTE" \
  "chmod 755 /tmp/docker-deploy.sh && set -a && source /tmp/deploy-env.txt && set +a && bash /tmp/docker-deploy.sh"
