#!/usr/bin/env bash
# Friendly, structured logs for GitHub Actions deploy workflows.
# Source from workflow steps: source .github/scripts/deploy-log.sh

deploy_banner() {
  local environment="$1"
  local commit_sha="${2:-unknown}"
  local image_tag="${3:-deploy}"
  local host="${4:-}"

  echo ""
  echo "══════════════════════════════════════════════════════════════"
  echo "  Bhukkad Deploy — ${environment}"
  echo "══════════════════════════════════════════════════════════════"
  echo "  Commit     : ${commit_sha}"
  echo "  Image tag  : ${image_tag}"
  if [ -n "${host}" ]; then
    echo "  EC2 host   : ${host}"
  fi
  echo "  Started    : $(date -u '+%Y-%m-%d %H:%M:%S UTC')"
  echo "══════════════════════════════════════════════════════════════"
  echo ""
}

log_section() {
  echo ""
  echo "::group::${1}"
}

log_section_end() {
  echo "::endgroup::"
}

log_info() {
  echo "[INFO] $*"
}

log_ok() {
  echo "[OK]   $*"
  echo "::notice title=Deploy::$*"
}

log_warn() {
  echo "[WARN] $*"
  echo "::warning::$*"
}

log_error() {
  echo "[ERROR] $*"
  echo "::error::$*"
}

log_kv() {
  printf "  %-18s %s\n" "$1:" "$2"
}

deploy_summary() {
  local environment="$1"
  local host="$2"
  local image_ref="$3"
  local health_url="$4"

  echo ""
  echo "══════════════════════════════════════════════════════════════"
  echo "  Deployment complete — ${environment}"
  echo "══════════════════════════════════════════════════════════════"
  log_kv "Environment" "${environment}"
  log_kv "EC2 host" "${host}"
  log_kv "Docker image" "${image_ref}"
  log_kv "Health check" "${health_url}"
  log_kv "Finished" "$(date -u '+%Y-%m-%d %H:%M:%S UTC')"
  echo "══════════════════════════════════════════════════════════════"
  echo ""
}

check_required_secret() {
  local name="$1"
  local value="$2"
  if [ -z "${value}" ]; then
    log_error "Missing required secret: ${name}"
    exit 1
  fi
  log_ok "Secret present: ${name}"
}

check_optional_var() {
  local name="$1"
  local value="$2"
  local default="$3"
  if [ -z "${value}" ]; then
    log_warn "${name} not set — using default: ${default}"
  else
    log_ok "${name} = ${value}"
  fi
}
