#!/bin/bash
# One-shot: squash platform-lib baseline + per-service migrations into single V1__baseline.sql
# via scratch databases on the dev postgres container.
set -euo pipefail
cd "$(dirname "$0")/.."

C=bhukkad-postgres-dev
U=bhukkad
PLATFORM_FILE=services/platform-lib/src/main/resources/db/migration-pg/V1__bhukkad_common_pg_baseline.sql

# service_dir -> target db name
# service_dir:target_db name
MAP="identity:identity restaurant:restaurants order:orders payment:payments delivery:delivery notification:notification admin-analytics:admin search:search survey:survey referral:referral supportticket:support growth:growth personalization:personalization realtime:realtime"

for pair in $MAP; do
  svc=${pair%%:*}; clean=${pair##*:}
  if [ "$#" -gt 0 ] && ! printf ' %s ' "$@" | grep -q " $svc "; then continue; fi
  db="squash_$(echo "$svc" | tr '-' '_')"
  dir="services/$svc/src/main/resources/db/migration-pg"
  echo "=== $svc -> scratch $db ==="
  docker exec $C psql -U $U -q -c "DROP DATABASE IF EXISTS $db;" postgres
  docker exec $C psql -U $U -q -c "CREATE DATABASE $db;" postgres

  # apply platform baseline first (skip for services that already start > V1? all do)
  docker exec -i $C psql -U $U -q -v ON_ERROR_STOP=1 -d $db < "$PLATFORM_FILE"

  # If source migrations still exist use them; else the existing baseline is the source (idempotent re-run)
  srcs=$(ls "$dir" | grep '^V[0-9]*__' | grep -v 'V1__baseline.sql' | sed -n 's/^V\([0-9]*\)__.*/\1/p' | sort -n)
  if [ -n "$srcs" ]; then
    for v in $srcs; do
      f=$(ls "$dir"/V${v}__*.sql)
      echo "  apply $f"
      docker exec -i $C psql -U $U -q -v ON_ERROR_STOP=1 -d $db < "$f"
    done
  else
    echo "  re-apply existing baseline"
    docker exec -i $C psql -U $U -q -v ON_ERROR_STOP=1 -d $db < "$dir/V1__baseline.sql"
  fi

  # dump consolidated schema -> baseline file
  out="$dir/V1__baseline.sql"
  {
    echo "-- ${svc} service schema - consolidated baseline (fresh start)."
    echo "-- Derived from squashed platform baseline + service migrations."
    docker exec $C pg_dump -U $U --schema-only --no-owner --no-privileges \
      --exclude-table=flyway_schema_history -d $db \
      | grep -v "^SET \|^SELECT pg_catalog.set_config\|^-- *$\|^$\|^ALTER SCHEMA\|^CREATE SCHEMA\|^\\\\restrict\|^\\\\unrestrict"
  } > "$out.new"

  # move new baseline in place of old files
  rm -f "$dir"/V[0-9]*.sql
  mv "$out.new" "$out"
  echo "  wrote $out ($(wc -l < "$out") lines)"

  docker exec $C psql -U $U -q -c "DROP DATABASE IF EXISTS $db;" postgres
done
echo "ALL DONE"
