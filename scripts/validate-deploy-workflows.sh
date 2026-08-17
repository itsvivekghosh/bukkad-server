#!/usr/bin/env bash
# Validate EC2 deploy workflows and helper scripts locally.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

PASS=0
FAIL=0

ok() { echo "  ✅ $1"; PASS=$((PASS + 1)); }
bad() { echo "  ❌ $1"; FAIL=$((FAIL + 1)); }

echo "=== Shell script syntax ==="
for script in \
  docker/scripts/docker-deploy.sh \
  .github/scripts/ec2-deploy-remote.sh \
  .github/scripts/wait-for-ec2-health.sh \
  .github/scripts/ec2-smoke-test.sh \
  .github/scripts/ec2-ghcr-login.sh \
  .github/scripts/ec2-preflight-ssh.sh \
  .github/scripts/ec2-flyway-repair.sh \
  .github/scripts/ec2-redis-preflight.sh \
  .github/scripts/ec2-verify-ssh.sh \
  .github/scripts/deploy-log.sh; do
  if bash -n "$script"; then
    ok "bash -n $script"
  else
    bad "bash -n $script"
  fi
  if [ -x "$script" ] || chmod +x "$script"; then
    ok "executable $script"
  fi
done

echo ""
echo "=== Workflow YAML parse ==="
if command -v python3 >/dev/null 2>&1; then
  python3 - <<'PY'
import sys
from pathlib import Path
try:
    import yaml
except ImportError:
    sys.exit(2)
root = Path(".github/workflows")
for path in sorted(root.glob("deploy-*.yml")):
    with path.open() as f:
        yaml.safe_load(f)
    print(f"  ✅ yaml parse {path}")
PY
  YAML_RC=$?
  if [ "$YAML_RC" -eq 2 ]; then
    echo "  ⚠️  PyYAML not installed; skipping YAML parse"
  elif [ "$YAML_RC" -ne 0 ]; then
    bad "workflow YAML parse failed"
  fi
else
  echo "  ⚠️  python3 not found; skipping YAML parse"
fi

echo ""
echo "=== Deploy configuration ==="
if [ -f .github/deploy-config.env ]; then
  # shellcheck source=/dev/null
  source .github/deploy-config.env
  ok "deploy-config.env present"
elif [ -f .github/deploy-hosts.env ]; then
  # shellcheck source=/dev/null
  source .github/deploy-hosts.env
  ok "deploy-hosts.env present (legacy)"
else
  bad "missing .github/deploy-config.env"
fi
grep -q 'vars.STAGING_EC2_HOST' .github/workflows/deploy-staging.yml \
  && ok "staging workflow uses STAGING_EC2_HOST variable" \
  || bad "staging workflow missing STAGING_EC2_HOST variable"
grep -q 'vars.PROD_EC2_HOST' .github/workflows/deploy-production.yml \
  && ok "production workflow uses PROD_EC2_HOST variable" \
  || bad "production workflow missing PROD_EC2_HOST variable"
grep -q 'vars.GHCR_IMAGE' .github/workflows/deploy-staging.yml \
  && ok "staging workflow uses GHCR_IMAGE variable" \
  || bad "staging workflow missing GHCR_IMAGE variable"
grep -q 'vars.GHCR_IMAGE' .github/workflows/docker.yml \
  && ok "docker workflow uses GHCR_IMAGE variable" \
  || bad "docker workflow missing GHCR_IMAGE variable"
if ! grep -E 'ec2-[0-9-]+\.ap-south-1\.compute\.amazonaws\.com' .github/workflows/deploy-staging.yml .github/workflows/deploy-production.yml >/dev/null 2>&1; then
  ok "no hardcoded EC2 DNS in deploy workflows"
else
  bad "deploy workflows still contain hardcoded EC2 DNS"
fi

echo ""
echo "=== Workflow expression safety ==="
for wf in .github/workflows/deploy-staging.yml .github/workflows/deploy-production.yml; do
  if grep -E '^env:' -A5 "$wf" | grep -q 'secrets\.'; then
    bad "secrets used in workflow-level env in ${wf}"
  else
    ok "no secrets in workflow-level env (${wf})"
  fi
  if grep -A3 'environment:' "$wf" | grep -q 'secrets\.'; then
    bad "secrets used in environment url in ${wf}"
  else
    ok "no secrets in environment url (${wf})"
  fi
  if grep -q '^name: Deploy to Production' "$wf" 2>/dev/null || [ "$wf" != ".github/workflows/deploy-production.yml" ]; then
    :
  fi
done
grep -q '^name: Deploy to Production' .github/workflows/deploy-production.yml \
  && ok "production workflow name is Deploy to Production" \
  || bad "production workflow name incorrect"
grep -q '^name: Deploy to Staging' .github/workflows/deploy-staging.yml \
  && ok "staging workflow name is Deploy to Staging" \
  || bad "staging workflow name incorrect"

echo ""
echo "=== Required workflow fixes ==="
grep -q 'cp docker/scripts/docker-deploy.sh' .github/workflows/deploy-staging.yml \
  && ok "staging copies deploy script from repo" \
  || bad "staging deploy script copy missing"
grep -q 'cp docker/scripts/docker-deploy.sh' .github/workflows/deploy-production.yml \
  && ok "production copies deploy script from repo" \
  || bad "production deploy script copy missing"
grep -q 'branches: \[ main, deploy \]' .github/workflows/docker.yml \
  && ok "docker workflow triggers on main and deploy" \
  || bad "docker workflow trigger branches missing"
grep -q 'GHCR_READ_TOKEN' .github/workflows/deploy-production.yml \
  && ok "production uses GHCR_READ_TOKEN" \
  || bad "production missing GHCR_READ_TOKEN"
grep -q 'ec2-verify-ssh.sh' .github/workflows/deploy-staging.yml \
  && ok "staging verifies SSH authentication" \
  || bad "staging missing SSH auth verification"
grep -q 'ec2-verify-ssh.sh' .github/workflows/deploy-production.yml \
  && ok "production verifies SSH authentication" \
  || bad "production missing SSH auth verification"

echo ""
echo "=== EC2 reachability (SSH port 22) ==="
for host in "${STAGING_EC2_HOST:-}" "${PROD_EC2_HOST:-}"; do
  [ -n "${host}" ] || continue
  if timeout 3 bash -c "echo >/dev/tcp/${host}/22" 2>/dev/null; then
    ok "TCP 22 open on ${host} (bash /dev/tcp)"
  elif nc -z -w 3 "$host" 22 >/dev/null 2>&1; then
    ok "TCP 22 open on ${host} (nc)"
  else
    echo "  ⚠️  cannot verify TCP 22 on ${host}"
  fi
done

echo ""
echo "=== Public health endpoints (optional, requires SG port 8080) ==="
for host in "${STAGING_EC2_HOST:-}" "${PROD_EC2_HOST:-}"; do
  [ -n "${host}" ] || continue
  url="http://${host}:${APP_PORT:-8080}${HEALTH_PATH:-/api/v1/health/ping}"
  code=$(curl -s -o /dev/null -w "%{http_code}" --connect-timeout 5 "$url" || echo "000")
  if [ "$code" = "200" ]; then
    ok "${url} -> HTTP ${code}"
  else
    echo "  ⚠️  ${url} -> HTTP ${code} (DNS/TLS/app may not be ready yet)"
  fi
done

echo ""
echo "=== Summary ==="
echo "Passed checks: ${PASS}"
echo "Failed checks: ${FAIL}"
if [ "$FAIL" -gt 0 ]; then
  exit 1
fi
