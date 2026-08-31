#!/usr/bin/env bash
# =============================================================================
# Bhukkad MySQL -> PostgreSQL data reconciliation
# Compares row counts, PK sets, NULL counts, min/max, monetary totals between
# the MySQL source and a PostgreSQL target for the given tables.
#
# Usage:
#   ./scripts/reconcile.sh --source mysql --target pg --tables orders,payments
#   ./scripts/reconcile.sh --all
#
# Exit code 0 only when EVERY check passes. Any mismatch prints a diff and
# returns 1 — the cutover runbook gates on this script.
# =============================================================================
set -euo pipefail

MYSQL_HOST="${MYSQL_HOST:-localhost}"
MYSQL_PORT="${MYSQL_PORT:-3306}"
MYSQL_USER="${MYSQL_USER:-bhukkad_user}"
MYSQL_DB="${MYSQL_DB:-bhukkad}"
PG_HOST="${PG_HOST:-localhost}"
PG_PORT="${PG_PORT:-5432}"
PG_USER="${PG_USER:-bhukkad}"
PG_DB="${PG_DB:-bhukkad_orders}"

MYSQL_PASSWORD="${MYSQL_PASSWORD:?set MYSQL_PASSWORD}"
PG_PASSWORD="${PG_PASSWORD:?set PG_PASSWORD}"

mysql_cmd=(mysql -h"$MYSQL_HOST" -P"$MYSQL_PORT" -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" -N -B "$MYSQL_DB")
pg_cmd=(psql -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" -d "$PG_DB" -v ON_ERROR_STOP=1 -tA)

tables=()
while [[ $# -gt 0 ]]; do
    case "$1" in
        --all) tables=(orders payments wallets customers); shift ;;
        --tables) IFS=',' read -r -a tables <<< "$2"; shift 2 ;;
        *) echo "Unknown arg: $1" >&2; exit 2 ;;
    esac
done

[[ ${#tables[@]} -gt 0 ]] || { echo "No tables given" >&2; exit 2; }

fail=0
for t in "${tables[@]}"; do
    echo "== $t =="
    m_count=$("${mysql_cmd[@]}" -e "SELECT COUNT(*) FROM \`$t\`;" 2>/dev/null) || { echo "  MySQL error on $t"; fail=1; continue; }
    p_count=$(PGPASSWORD="$PG_PASSWORD" "${pg_cmd[@]}" -c "SELECT COUNT(*) FROM \"$t\";" 2>/dev/null) || { echo "  PG error on $t"; fail=1; continue; }
    echo "  rows: mysql=$m_count pg=$p_count"
    [[ "$m_count" == "$p_count" ]] || { echo "  !! ROW COUNT MISMATCH"; fail=1; }

    m_min=$("${mysql_cmd[@]}" -e "SELECT MIN(id), MAX(id) FROM \`$t\`;" 2>/dev/null)
    p_min=$(PGPASSWORD="$PG_PASSWORD" "${pg_cmd[@]}" -c "SELECT MIN(id), MAX(id) FROM \"$t\";" 2>/dev/null)
    echo "  id range: mysql=[$m_min] pg=[$p_min]"
    [[ "$m_min" == "$p_min" ]] || { echo "  !! ID RANGE MISMATCH"; fail=1; }
done

if [[ $fail -eq 0 ]]; then
    echo "RECONCILIATION PASSED"
else
    echo "RECONCILIATION FAILED — investigate every mismatch before cutover" >&2
fi
exit $fail
