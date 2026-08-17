#!/usr/bin/env bash
# Copy docker-deploy.sh + env file to EC2 and run the deployment.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=deploy-log.sh
source "${SCRIPT_DIR}/deploy-log.sh"

SSH_USER="${1:?SSH user required}"
SSH_HOST="${2:?SSH host required}"
SSH_KEY="${3:?SSH key path required}"
DEPLOY_SCRIPT="${4:?Deploy script path required}"
ENV_FILE="${5:?Env file path required}"

SSH_OPTS=(-i "$SSH_KEY" -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o ConnectTimeout=30)
REMOTE="${SSH_USER}@${SSH_HOST}"

log_section "Copy deploy assets to EC2"
log_info "Target: ${REMOTE}"
scp "${SSH_OPTS[@]}" "$DEPLOY_SCRIPT" "${REMOTE}:/tmp/docker-deploy.sh"
log_ok "Uploaded docker-deploy.sh"

log_info "Uploading environment file (secrets redacted in logs)"
base64 "$ENV_FILE" | ssh "${SSH_OPTS[@]}" "$REMOTE" "base64 -d > /tmp/deploy-env.txt && chmod 600 /tmp/deploy-env.txt"
log_ok "Environment file uploaded"
log_section_end

log_section "Run deployment on EC2"
ssh "${SSH_OPTS[@]}" "$REMOTE" \
  "chmod 755 /tmp/docker-deploy.sh && set -a && source /tmp/deploy-env.txt && set +a && bash /tmp/docker-deploy.sh"
log_ok "Remote deploy script finished"
log_section_end
