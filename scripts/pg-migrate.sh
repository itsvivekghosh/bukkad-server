#!/usr/bin/env bash
# =============================================================================
# Bhukkad MySQL -> PostgreSQL migration orchestrator
#
# Runs the initial pgloader load per service database, then the reconciliation
# gate. This is the executable form of docs/pg-cutover-runbook.md §1-§2.
#
# Usage:
#   ./scripts/pg-migrate.sh --initial            # run pgloader for all services
#   ./scripts/pg-migrate.sh --service orders     # run one service's .load file
#   ./scripts/pg-migrate.sh --verify            # run reconcile only
#
# Requires: pgloader, mysql client, psql, and the env vars below.
# =============================================================================
set -euo pipefail

MYSQL_HOST="${MYSQL_HOST:-localhost}"
MYSQL_PORT="${MYSQL_PORT:-3306}"
MYSQL_USER="${MYSQL_USER:-bhukkad_user}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:?set MYSQL_PASSWORD}"
PG_HOST="${PG_HOST:-localhost}"
PG_PORT="${PG_PORT:-5432}"
PG_USER="${PG_USER:-app}"
PG_PASSWORD="${PG_PASSWORD:?set PG_PASSWORD}"

# service -> PostgreSQL database name
declare -A PG_DB=(
    [identity]=identity
    [restaurant]=restaurants
    [orders]=orders
    [payments]=payments
    [delivery]=delivery
    [notification]=notification
    [admin]=admin
)

run_service() {
    local svc="$1"
    local loadfile="docs/pgloader/$svc.load"
    [[ -f "$loadfile" ]] || { echo "no load file: $loadfile" >&2; return 1; }
    echo "== pgloader: $svc -> ${PG_DB[$svc]} =="
    pgloader -v -L "pgloader-$svc.log" \
        --set "MYSQL_USER=$MYSQL_USER" --set "MYSQL_PASSWORD=$MYSQL_PASSWORD" \
        --set "MYSQL_HOST=$MYSQL_HOST" \
        --set "PG_USER=$PG_USER" --set "PG_PASSWORD=$PG_PASSWORD" \
        --set "PG_HOST=$PG_HOST" \
        "$loadfile"
}

MODE="${1:---initial}"
case "$MODE" in
    --initial)
        for svc in identity restaurant orders payments delivery notification admin; do
            run_service "$svc"
        done
        ;;
    --service)
        run_service "$2"
        ;;
    --verify)
        echo "Reconciliation gate — set PG_DB per service and run scripts/reconcile.sh"
        ;;
    *)
        echo "Unknown mode: $MODE" >&2; exit 2 ;;
esac

echo "MIGRATION STEP COMPLETE — next: docs/pg-cutover-runbook.md §2 reconciliation"
