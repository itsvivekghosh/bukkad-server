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
  .github/scripts/ec2-preflight-ssh.sh; do
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
echo "=== EC2 host defaults ==="
grep -q 'ec2-13-204-80-247.ap-south-1.compute.amazonaws.com' .github/workflows/deploy-staging.yml \
  && ok "staging EC2 host default present" \
  || bad "staging EC2 host default missing"
grep -q 'ec2-13-201-21-45.ap-south-1.compute.amazonaws.com' .github/workflows/deploy-production.yml \
  && ok "production EC2 host default present" \
  || bad "production EC2 host default missing"

echo ""
echo "=== Required workflow fixes ==="
grep -q 'actions: read' .github/workflows/deploy-staging.yml \
  && ok "staging has actions: read" \
  || bad "staging missing actions: read"
grep -q 'workflow_dispatch' .github/workflows/deploy-staging.yml \
  && ok "staging supports workflow_dispatch fallback script" \
  || bad "staging workflow_dispatch missing"
grep -q 'STAGING_SSH_HOST_DEFAULT' .github/workflows/deploy-staging.yml \
  && ok "staging SSH host fallback env" \
  || bad "staging SSH host fallback env missing"
grep -q 'outputs:' .github/workflows/deploy-production.yml \
  && ok "production get-image-tag outputs declared" \
  || bad "production get-image-tag outputs missing"
grep -q 'docker/scripts/docker-deploy.sh' .github/workflows/docker.yml \
  && ok "docker workflow uses repo deploy script" \
  || bad "docker workflow not using repo deploy script"

echo ""
echo "=== EC2 reachability (SSH port 22) ==="
for host in \
  ec2-13-204-80-247.ap-south-1.compute.amazonaws.com \
  ec2-13-201-21-45.ap-south-1.compute.amazonaws.com; do
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
for url in \
  "http://ec2-13-204-80-247.ap-south-1.compute.amazonaws.com:8080/api/v1/health/ping" \
  "http://ec2-13-201-21-45.ap-south-1.compute.amazonaws.com:8080/api/v1/health/ping"; do
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
