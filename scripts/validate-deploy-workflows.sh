#!/usr/bin/env bash
# Validate CI/CD workflows and helper scripts locally.
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
  .github/scripts/ec2.sh \
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
for path in sorted(root.glob("*.yml")):
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

echo ""
echo "=== Workflow inventory ==="
for wf in ci.yml pull-request.yml feature-ci.yml staging.yml production.yml nightly-regression.yml; do
  [ -f ".github/workflows/${wf}" ] && ok "workflow present: ${wf}" || bad "missing workflow: ${wf}"
done
for wf in docker.yml deploy-staging.yml deploy-production.yml; do
  [ -f ".github/workflows/${wf}" ] && bad "obsolete workflow still present: ${wf}" || ok "obsolete workflow removed: ${wf}"
done

echo ""
echo "=== Triggers ==="
grep -q 'on:\s*$\|on:' .github/workflows/pull-request.yml && grep -q 'pull_request:' .github/workflows/pull-request.yml \
  && ok "PR workflow triggers on pull_request" \
  || bad "PR workflow missing pull_request trigger"
grep -q "'feature/\*\*'" .github/workflows/feature-ci.yml \
  && ok "feature CI triggers on feature/** pushes" \
  || bad "feature CI missing feature/** push trigger"
grep -q 'branches: \[ deploy \]' .github/workflows/staging.yml \
  && ok "staging workflow triggers on deploy branch" \
  || bad "staging workflow missing deploy branch trigger"
grep -q 'branches: \[ main \]' .github/workflows/production.yml \
  && ok "production workflow triggers on main branch" \
  || bad "production workflow missing main branch trigger"

echo ""
echo "=== Reusable CI wiring ==="
grep -q 'workflow_call:' .github/workflows/ci.yml \
  && ok "ci.yml is reusable (workflow_call)" \
  || bad "ci.yml missing workflow_call trigger"
for wf in pull-request.yml feature-ci.yml staging.yml production.yml; do
  grep -q 'uses: ./.github/workflows/ci.yml' ".github/workflows/${wf}" \
    && ok "${wf} calls reusable ci.yml" \
    || bad "${wf} does not call reusable ci.yml"
done
grep -q '^\s*build:\s*$\|^  build:' .github/workflows/ci.yml && grep -q '^\s*test:\s*$\|^  test:' .github/workflows/ci.yml \
  && ok "ci.yml contains build and test jobs" \
  || bad "ci.yml missing build or test job"

echo ""
echo "=== Deploy gates (deploy only after CI passes) ==="
grep -q 'needs: \[ci, build-and-push\]' .github/workflows/staging.yml \
  && ok "staging deploy depends on ci + build-and-push" \
  || bad "staging deploy gate missing"
grep -q 'needs: \[ci, build-and-push\]' .github/workflows/production.yml \
  && ok "production deploy depends on ci + build-and-push" \
  || bad "production deploy gate missing"
grep -q 'needs: ci' .github/workflows/staging.yml \
  && ok "staging build-and-push depends on ci" \
  || bad "staging build-and-push gate missing"
grep -q 'needs: ci' .github/workflows/production.yml \
  && ok "production build-and-push depends on ci" \
  || bad "production build-and-push gate missing"

echo ""
echo "=== Job outputs (deploy consumes pushed image) ==="
for wf in staging production; do
  grep -q 'outputs:' ".github/workflows/${wf}.yml" \
    && ok "${wf} build-and-push declares outputs" \
    || bad "${wf} build-and-push missing outputs"
  grep -q 'needs.build-and-push.outputs.image' ".github/workflows/${wf}.yml" \
    && grep -q 'needs.build-and-push.outputs.tag' ".github/workflows/${wf}.yml" \
    && ok "${wf} deploy consumes build-and-push outputs" \
    || bad "${wf} deploy does not consume build-and-push outputs"
done

echo ""
echo "=== No deploy from PR / feature workflows ==="
if grep -qE 'environment:|ec2\.sh deploy|docker-build' .github/workflows/pull-request.yml .github/workflows/feature-ci.yml; then
  bad "PR or feature workflow contains deploy logic"
else
  ok "PR and feature workflows contain no deploy logic"
fi

echo ""
echo "=== Variable and secret reuse ==="
grep -q 'vars.STAGING_EC2_HOST' .github/workflows/staging.yml \
  && ok "staging workflow uses STAGING_EC2_HOST variable" \
  || bad "staging workflow missing STAGING_EC2_HOST variable"
grep -q 'vars.PROD_EC2_HOST' .github/workflows/production.yml \
  && ok "production workflow uses PROD_EC2_HOST variable" \
  || bad "production workflow missing PROD_EC2_HOST variable"
grep -q 'vars.GHCR_IMAGE' .github/workflows/staging.yml \
  && ok "staging workflow uses GHCR_IMAGE variable" \
  || bad "staging workflow missing GHCR_IMAGE variable"
grep -q 'vars.GHCR_IMAGE' .github/workflows/production.yml \
  && ok "production workflow uses GHCR_IMAGE variable" \
  || bad "production workflow missing GHCR_IMAGE variable"
grep -q 'GHCR_READ_TOKEN' .github/workflows/staging.yml \
  && ok "staging reuses GHCR_READ_TOKEN" \
  || bad "staging missing GHCR_READ_TOKEN"
grep -q 'GHCR_READ_TOKEN' .github/workflows/production.yml \
  && ok "production reuses GHCR_READ_TOKEN" \
  || bad "production missing GHCR_READ_TOKEN"
grep -q 'STAGING_IMAGE_SHA' .github/workflows/staging.yml \
  && ok "staging preserves STAGING_IMAGE_SHA variable update" \
  || bad "staging missing STAGING_IMAGE_SHA update"
if ! grep -E 'ec2-[0-9-]+\.ap-south-1\.compute\.amazonaws\.com' .github/workflows/*.yml >/dev/null 2>&1; then
  ok "no hardcoded EC2 DNS in workflows"
else
  bad "workflows still contain hardcoded EC2 DNS"
fi

echo ""
echo "=== Workflow expression safety ==="
for wf in .github/workflows/staging.yml .github/workflows/production.yml; do
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
done
grep -q '^name: Production Deploy' .github/workflows/production.yml \
  && ok "production workflow name is Production Deploy" \
  || bad "production workflow name incorrect"
grep -q '^name: Staging Deploy' .github/workflows/staging.yml \
  && ok "staging workflow name is Staging Deploy" \
  || bad "staging workflow name incorrect"

echo ""
echo "=== Required deployment implementation ==="
grep -q 'cp docker/scripts/docker-deploy.sh' .github/workflows/staging.yml \
  && ok "staging copies deploy script from repo" \
  || bad "staging deploy script copy missing"
grep -q 'cp docker/scripts/docker-deploy.sh' .github/workflows/production.yml \
  && ok "production copies deploy script from repo" \
  || bad "production deploy script copy missing"
grep -q 'ec2.sh verify-ssh' .github/workflows/staging.yml \
  && ok "staging verifies SSH authentication" \
  || bad "staging missing SSH auth verification"
grep -q 'ec2.sh verify-ssh' .github/workflows/production.yml \
  && ok "production verifies SSH authentication" \
  || bad "production missing SSH auth verification"
grep -q 'Set deployment defaults' .github/workflows/staging.yml \
  && ok "staging sets deployment defaults" \
  || bad "staging missing deployment defaults step"

echo ""
echo "=== Concurrency ==="
grep -q 'group: staging-deploy' .github/workflows/staging.yml && grep -q 'cancel-in-progress: false' .github/workflows/staging.yml \
  && ok "staging serializes deployments without canceling in-progress runs" \
  || bad "staging concurrency misconfigured"
grep -q 'group: production-deploy' .github/workflows/production.yml && grep -q 'cancel-in-progress: false' .github/workflows/production.yml \
  && ok "production serializes deployments without canceling in-progress runs" \
  || bad "production concurrency misconfigured"
grep -q 'group: pr-ci-\${{ github.event.pull_request.number }}' .github/workflows/pull-request.yml \
  && ok "PR CI uses per-PR concurrency group" \
  || bad "PR CI concurrency group missing"

echo ""
echo "=== Summary ==="
echo "Passed checks: ${PASS}"
echo "Failed checks: ${FAIL}"
if [ "$FAIL" -gt 0 ]; then
  exit 1
fi
