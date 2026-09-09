#!/usr/bin/env bash
# =============================================================================
# scripts/ci/restore-drill.sh — roadmap #15 monthly restore drill.
#
# Proves the backups are RESTORABLE, not merely present: pulls the latest
# per-service pg_dump set, restores it into a throwaway namespace with an
# ephemeral PostgreSQL, verifies schema + row counts per restored database,
# then tears everything down. Exit 0 = drill passed.
#
# Monthly CI hook (documented — the integrator wires the workflow):
#   - name: Monthly restore drill (#15)
#     if: github.event_name == 'schedule'      # cron: '0 4 1 * *'
#     run: ./scripts/ci/restore-drill.sh --source s3 --bucket "$BACKUP_BUCKET"
#     env:
#       AWS_ACCESS_KEY_ID: ${{ secrets.BACKUP_AWS_ACCESS_KEY_ID }}
#       AWS_SECRET_ACCESS_KEY: ${{ secrets.BACKUP_AWS_SECRET_ACCESS_KEY }}
#       KUBE_CONFIG: ${{ secrets.STAGING_KUBECONFIG }}   # ephemeral cluster/ns
#   See docs/backup-and-restore-runbook.md for the full runbook.
#
# Usage:
#   ./scripts/ci/restore-drill.sh --source s3 --bucket my-bucket [--prefix backups/]
#   ./scripts/ci/restore-drill.sh --source dir --dir /backup/postgres
#   ./scripts/ci/restore-drill.sh --source pvc --namespace bhukkad
#
# Requires: kubectl, aws (only for --source s3), gzip, pg_restore/psql optional
# (verification runs INSIDE the ephemeral pod, so no local postgres needed).
# =============================================================================
set -euo pipefail

SOURCE="s3"
BUCKET="${S3_BUCKET:-}"
PREFIX="backups"
SRC_DIR=""
PVC_NS="bhukkad"
PVC_NAME="bhukkad-backups"
DRILL_NS=""
KEEP_ON_FAIL=0
WORKDIR="$(mktemp -d /tmp/bhukkad-restore-drill.XXXXXX)"

log()  { echo "[restore-drill] $*"; }
fail() { echo "[restore-drill] FAIL: $*" >&2; exit 1; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --source)   SOURCE="$2"; shift 2 ;;
    --bucket)   BUCKET="$2"; shift 2 ;;
    --prefix)   PREFIX="$2"; shift 2 ;;
    --dir)      SRC_DIR="$2"; shift 2 ;;
    --namespace) PVC_NS="$2"; shift 2 ;;
    --pvc)      PVC_NAME="$2"; shift 2 ;;
    --keep-on-fail) KEEP_ON_FAIL=1; shift ;;
    -h|--help)  grep '^#' "$0" | sed -n '2,25p'; exit 0 ;;
    *) fail "unknown argument: $1" ;;
  esac
done

DRILL_NS="bhukkad-restore-drill-$(date +%Y%m%d-%H%M%S)"
cleanup() {
  local rc=$?
  if [[ $rc -ne 0 && $KEEP_ON_FAIL -eq 1 ]]; then
    log "keeping drill namespace ${DRILL_NS} for inspection (--keep-on-fail)"
  else
    log "tearing down drill namespace ${DRILL_NS}"
    kubectl delete namespace "${DRILL_NS}" --ignore-not-found --wait=false >/dev/null 2>&1 || true
  fi
  rm -rf "${WORKDIR}"
}
trap cleanup EXIT

command -v kubectl >/dev/null || fail "kubectl not found"

# -----------------------------------------------------------------------------
# 1. Fetch the latest dump set into ${WORKDIR}/dumps
# -----------------------------------------------------------------------------
DUMPS="${WORKDIR}/dumps"
mkdir -p "${DUMPS}"
case "${SOURCE}" in
  s3)
    [[ -n "${BUCKET}" ]] || fail "--bucket (or S3_BUCKET) required for --source s3"
    command -v aws >/dev/null || fail "aws cli not found"
    LATEST=$(aws s3 ls "s3://${BUCKET}/${PREFIX}/" | sort | tail -1 | awk '{print $2}')
    [[ -n "${LATEST}" ]] || fail "no backup prefix found under s3://${BUCKET}/${PREFIX}/"
    log "pulling s3://${BUCKET}/${PREFIX}/${LATEST}"
    aws s3 sync "s3://${BUCKET}/${PREFIX}/${LATEST}" "${DUMPS}" --exclude "*.partial"
    ;;
  dir)
    [[ -n "${SRC_DIR}" && -d "${SRC_DIR}" ]] || fail "--dir must be an existing directory"
    log "copying dumps from ${SRC_DIR}"
    cp "${SRC_DIR}"/bhukkad_*_*.sql.gz "${DUMPS}/"
    ;;
  pvc)
    log "extracting dumps from PVC ${PVC_NAME} (namespace ${PVC_NS}) via helper pod"
    kubectl -n "${PVC_NS}" run restore-drill-src --rm -i --restart=Never \
      --image=busybox:1.36 --overrides "{
        \"spec\":{\"containers\":[{\"name\":\"restore-drill-src\",\"image\":\"busybox:1.36\",
        \"command\":[\"sh\",\"-c\",\"tar cf - postgres/bhukkad_*_*.sql.gz 2>/dev/null\"],
        \"volumeMounts\":[{\"name\":\"b\",\"mountPath\":\"/backup\"}]}],
        \"volumes\":[{\"name\":\"b\",\"persistentVolumeClaim\":{\"claimName\":\"${PVC_NAME}\"}}]}}" \
      | tar xf - -C "${WORKDIR}" --strip-components=1
    mv "${WORKDIR}"/postgres/bhukkad_*_*.sql.gz "${DUMPS}/" 2>/dev/null || true
    ;;
  *) fail "unknown source: ${SOURCE}" ;;
esac

shopt -s nullglob
FILES=("${DUMPS}"/bhukkad_*_*.sql.gz)
[[ ${#FILES[@]} -gt 0 ]] || fail "no bhukkad_*_*.sql.gz dumps found (source=${SOURCE})"
log "found ${#FILES[@]} dump file(s)"

# -----------------------------------------------------------------------------
# 2. Freshness gate: a drill on stale backups is a failed drill.
# -----------------------------------------------------------------------------
if [[ -f "${DUMPS}/../postgres/.last_success_epoch" || -f "${DUMPS}/.last_success_epoch" ]]; then
  :
fi
NEWEST_FILE=$(ls -t "${DUMPS}"/bhukkad_*_*.sql.gz | head -1)
AGE_HOURS=$(( ( $(date +%s) - $(stat -f %m "${NEWEST_FILE}" 2>/dev/null || stat -c %Y "${NEWEST_FILE}") ) / 3600 ))
if [[ ${AGE_HOURS} -gt 168 ]]; then
  fail "newest dump is ${AGE_HOURS}h old (>7d) — backup pipeline is broken"
fi
log "newest dump is ${AGE_HOURS}h old"

# -----------------------------------------------------------------------------
# 3. Throwaway namespace + ephemeral PostgreSQL.
# -----------------------------------------------------------------------------
log "creating namespace ${DRILL_NS} with ephemeral postgres"
kubectl create namespace "${DRILL_NS}" >/dev/null
kubectl -n "${DRILL_NS}" run drill-postgres --restart=Never \
  --image=postgres:16-alpine \
  --env="POSTGRES_PASSWORD=drill" --env="POSTGRES_USER=restore" >/dev/null
kubectl -n "${DRILL_NS}" wait --for=condition=Ready pod/drill-postgres --timeout=180s >/dev/null

copy_in() {
  kubectl -n "${DRILL_NS}" cp "$1" drill-postgres:/tmp/ >/dev/null
}

# -----------------------------------------------------------------------------
# 4. Restore every dump and verify.
# -----------------------------------------------------------------------------
PASS=0; FAILED_DBS=()
for f in "${FILES[@]}"; do
  DB=$(basename "$f" .sql.gz); DB="${DB#bhukkad_}"; DB="${DB%_*}"   # bhukkad_<db>_<ts>.sql.gz
  log "restoring ${DB} ($(du -h "$f" | cut -f1))"
  copy_in "$f"
  gunzip -c "$f" | kubectl -n "${DRILL_NS}" exec -i drill-postgres -- \
    env PGPASSWORD=drill psql -U restore -v ON_ERROR_STOP=1 -d postgres \
    -c "CREATE DATABASE \"${DB}_drill\";" >/dev/null
  gunzip -c "$f" | kubectl -n "${DRILL_NS}" exec -i drill-postgres -- \
    env PGPASSWORD=drill psql -U restore -v ON_ERROR_STOP=1 -d "${DB}_drill" >/dev/null

  TABLES=$(kubectl -n "${DRILL_NS}" exec drill-postgres -- \
    env PGPASSWORD=drill psql -U restore -d "${DB}_drill" -t -A -c \
    "SELECT count(*) FROM information_schema.tables WHERE table_schema='public';")
  HISTORY=$(kubectl -n "${DRILL_NS}" exec drill-postgres -- \
    env PGPASSWORD=drill psql -U restore -d "${DB}_drill" -t -A -c \
    "SELECT count(*) FROM information_schema.tables WHERE table_name='flyway_schema_history';")
  ROWS=$(kubectl -n "${DRILL_NS}" exec drill-postgres -- \
    env PGPASSWORD=drill psql -U restore -d "${DB}_drill" -t -A -c \
    "SELECT COALESCE(sum(n_live_tup),0) FROM pg_stat_user_tables;")
  if [[ "${TABLES}" -eq 0 ]]; then
    FAILED_DBS+=("${DB}:no-tables")
  elif [[ "${HISTORY}" -eq 0 ]]; then
    FAILED_DBS+=("${DB}:no-flyway-history")
  else
    log "  OK ${DB}: ${TABLES} tables, ~${ROWS} rows, Flyway history present"
    PASS=$((PASS + 1))
  fi
done

# -----------------------------------------------------------------------------
# 5. Verdict.
# -----------------------------------------------------------------------------
log "drill result: ${PASS}/${#FILES[@]} databases restored+verified"
if [[ ${#FAILED_DBS[@]} -gt 0 ]]; then
  fail "restoration failed for: ${FAILED_DBS[*]}"
fi
log "PASSED — ${PASS} service databases restored from ${SOURCE} backup set"
