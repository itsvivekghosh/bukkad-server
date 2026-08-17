#!/usr/bin/env bash
# Unified EC2 deploy helpers for GitHub Actions.
# Usage: ec2.sh <command> [args...]
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=deploy-log.sh
source "${SCRIPT_DIR}/deploy-log.sh"

runner_ip() {
  curl -fsS --max-time 5 https://api.ipify.org 2>/dev/null || echo "unknown"
}

ssh_opts() {
  local key="$1"
  local timeout="${2:-30}"
  echo -i "$key" -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o ConnectTimeout="$timeout"
}

cmd_preflight_port() {
  local host="${1:?SSH host required}"
  local port="${2:-22}"
  local timeout="${3:-10}"
  local ip
  ip="$(runner_ip)"

  log_section "Pre-flight: SSH port"
  log_kv "Runner IP" "$ip"
  log_kv "Target" "${host}:${port}"

  if timeout "${timeout}" bash -c "echo >/dev/tcp/${host}/${port}" 2>/dev/null \
    || { command -v nc >/dev/null && nc -z -w "${timeout}" "${host}" "${port}" 2>/dev/null; }; then
    log_ok "Port ${port} is reachable"
    log_section_end
    return 0
  fi

  log_error "Cannot reach ${host}:${port}"
  log_info "Open EC2 security group: SSH port 22 from 0.0.0.0/0"
  log_info "Runner IP: ${ip}"
  log_section_end
  return 1
}

cmd_verify_ssh() {
  local user="${1:?SSH user required}"
  local host="${2:?SSH host required}"
  local key="${3:?SSH key path required}"
  local timeout="${4:-20}"
  local ip
  ip="$(runner_ip)"

  log_section "Verify SSH authentication"
  log_kv "Runner IP" "$ip"
  log_kv "Target" "${user}@${host}"

  # shellcheck disable=SC2046
  if ssh $(ssh_opts "$key" "$timeout") -o BatchMode=yes "${user}@${host}" 'echo SSH authentication OK'; then
    log_ok "SSH authentication succeeded"
    log_section_end
    return 0
  fi

  log_error "SSH authentication failed"
  log_info "Check SSH key secret, instance memory, and EC2 host variable"
  log_section_end
  return 1
}

cmd_deploy() {
  local user="${1:?SSH user required}"
  local host="${2:?SSH host required}"
  local key="${3:?SSH key path required}"
  local script="${4:?Deploy script path required}"
  local env_file="${5:?Env file path required}"
  local remote="${user}@${host}"

  log_section "Deploy to EC2"
  log_kv "Target" "$remote"
  # shellcheck disable=SC2046
  scp $(ssh_opts "$key") "$script" "${remote}:/tmp/docker-deploy.sh"
  # shellcheck disable=SC2046
  base64 "$env_file" | ssh $(ssh_opts "$key") "$remote" \
    "base64 -d > /tmp/deploy-env.txt && chmod 600 /tmp/deploy-env.txt"
  # shellcheck disable=SC2046
  ssh $(ssh_opts "$key") "$remote" \
    "chmod 755 /tmp/docker-deploy.sh && set -a && source /tmp/deploy-env.txt && set +a && bash /tmp/docker-deploy.sh"
  log_ok "Deployment finished on EC2"
  log_section_end
}

cmd_wait_health() {
  local user="${1:?SSH user required}"
  local host="${2:?SSH host required}"
  local key="${3:?SSH key path required}"
  local max="${4:-60}"
  local sleep_s="${5:-5}"
  local container="${6:-bhukkad-app}"
  local url="${7:-http://localhost:8080/api/v1/health/ping}"
  local remote="${user}@${host}"
  local ok=0 i

  log_section "Wait for application health"
  log_kv "Target" "$remote"
  log_kv "Health URL" "$url"
  log_kv "Max wait" "$((max * sleep_s))s"

  for i in $(seq 1 "$max"); do
    # shellcheck disable=SC2046
    if ssh $(ssh_opts "$key") -o ServerAliveInterval=15 -o ServerAliveCountMax=8 "$remote" \
      "CONTAINER_NAME=${container} HEALTH_URL=${url} bash -s" <<'REMOTE'
set -euo pipefail
curl -sf "${HEALTH_URL}" -o /dev/null 2>/dev/null
REMOTE
    then
      ok=$((ok + 1))
      log_info "Attempt ${i}/${max}: healthy (${ok}/3)"
      if [ "$ok" -ge 3 ]; then
        log_ok "Application healthy"
        log_section_end
        return 0
      fi
    else
      ok=0
      if [ $((i % 6)) -eq 0 ] || [ "$i" -eq 1 ]; then
        # shellcheck disable=SC2046
        ssh $(ssh_opts "$key") "$remote" "docker logs --tail 5 ${container} 2>&1" 2>/dev/null | sed 's/^/  /' || true
      fi
    fi
    sleep "$sleep_s"
  done

  log_error "Health check timed out"
  # shellcheck disable=SC2046
  ssh $(ssh_opts "$key") "$remote" "docker logs --tail 40 ${container} 2>&1" || true
  log_section_end
  return 1
}

cmd_redis_preflight() {
  local user="${1:?SSH user required}"
  local host="${2:?SSH host required}"
  local key="${3:?SSH key path required}"
  local env_file="${4:?Env file path required}"
  local remote="${user}@${host}"

  log_section "Redis preflight"
  # shellcheck disable=SC2046
  base64 "$env_file" | ssh $(ssh_opts "$key") "$remote" \
    "base64 -d > /tmp/redis-preflight-env.txt && chmod 600 /tmp/redis-preflight-env.txt"
  # shellcheck disable=SC2046
  ssh $(ssh_opts "$key") "$remote" "bash -s" <<'REMOTE'
set -euo pipefail
set -a && source /tmp/redis-preflight-env.txt && set +a
rm -f /tmp/redis-preflight-env.txt
[ "${REDIS_LOCAL:-false}" = "true" ] && { echo "REDIS_LOCAL=true — skip"; exit 0; }
[ -n "${REDIS_HOST:-}" ] || { echo "::error::REDIS_HOST empty"; exit 1; }
getent hosts "${REDIS_HOST}" >/dev/null || { echo "::error::Cannot resolve REDIS_HOST"; exit 1; }
timeout 10 bash -c "echo >/dev/tcp/${REDIS_HOST}/${REDIS_PORT:-6379}" || { echo "::error::Redis unreachable"; exit 1; }
echo "Redis reachable"
REMOTE
  log_ok "Redis preflight passed"
  log_section_end
}

cmd_flyway_repair() {
  local user="${1:?SSH user required}"
  local host="${2:?SSH host required}"
  local key="${3:?SSH key path required}"
  local migrations="${4:?Migrations dir required}"
  local env_file="${5:?Env file path required}"
  local remote="${user}@${host}"

  [ -d "$migrations" ] || { log_error "Migrations not found: $migrations"; exit 1; }
  log_section "Flyway repair"
  # shellcheck disable=SC2046
  ssh $(ssh_opts "$key") "$remote" "rm -rf /tmp/flyway-sql && mkdir -p /tmp/flyway-sql"
  # shellcheck disable=SC2046
  scp -r $(ssh_opts "$key") "${migrations}/." "${remote}:/tmp/flyway-sql/"
  # shellcheck disable=SC2046
  base64 "$env_file" | ssh $(ssh_opts "$key") "$remote" \
    "base64 -d > /tmp/flyway-env.txt && chmod 600 /tmp/flyway-env.txt"
  # shellcheck disable=SC2046
  ssh $(ssh_opts "$key") "$remote" "bash -s" <<'REMOTE'
set -euo pipefail
set -a && source /tmp/flyway-env.txt && set +a
DB_URL="${DB_URL:-jdbc:mysql://${DB_HOST}:${DB_PORT:-3306}/${DB_NAME}?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true}"
docker run --rm -v /tmp/flyway-sql:/flyway/sql:ro flyway/flyway:10-alpine \
  -url="${DB_URL}" -user="${DB_USERNAME}" -password="${DB_PASSWORD}" \
  -locations="filesystem:/flyway/sql" repair
rm -rf /tmp/flyway-sql /tmp/flyway-env.txt
REMOTE
  log_ok "Flyway repair completed"
  log_section_end
}

usage() {
  cat <<EOF
Usage: ec2.sh <command> [args...]

Commands:
  preflight-port <host> [port] [timeout]
  verify-ssh     <user> <host> <key> [timeout]
  deploy         <user> <host> <key> <script> <env-file>
  wait-health    <user> <host> <key> [max] [sleep] [container] [url]
  redis-preflight <user> <host> <key> <env-file>
  flyway-repair  <user> <host> <key> <migrations-dir> <env-file>
EOF
  exit 1
}

CMD="${1:-}"
shift || usage

case "$CMD" in
  preflight-port)   cmd_preflight_port "$@" ;;
  verify-ssh)       cmd_verify_ssh "$@" ;;
  deploy)           cmd_deploy "$@" ;;
  wait-health)      cmd_wait_health "$@" ;;
  redis-preflight)  cmd_redis_preflight "$@" ;;
  flyway-repair)    cmd_flyway_repair "$@" ;;
  *)                usage ;;
esac
