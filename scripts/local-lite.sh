#!/usr/bin/env bash
# Lightweight local stack: build and start only the essential services
# (postgres, redis, identity, gateway, restaurant, order).
#
# This is the "I want to hack on one feature without burning 6 GB of RAM"
# mode. Total footprint is ~2 containers worth of Java, not 15.
#
# Prereqs:
#   docker compose -f services/docker/docker-compose.lite.yml up -d
#
# Usage:
#   ./scripts/local-lite.sh                # start everything
#   ./scripts/local-lite.sh identity        # start only identity + infra
#   LOCAL_JVM_OPTS="-Xmx256m" ./scripts/local-lite.sh  # override heap
set -euo pipefail
cd "$(dirname "$0")/.."

COMPOSE_FILE="services/docker/docker-compose.lite.yml"

# ---------- secrets ----------
export APP_AUTH_JWT_SECRET="$(grep -m1 '^JWT_SECRET=' services/docker/.env | cut -d= -f2-)"
export APP_AUTH_SERVICE_JWT_SECRET="${APP_AUTH_JWT_SECRET}"
export JWT_SECRET="${APP_AUTH_JWT_SECRET}"
export SERVICE_JWT_SECRET="${APP_AUTH_SERVICE_JWT_SECRET}"

# ---------- optional subset ----------
SERVICES=()
if [ "$#" -gt 0 ]; then
  for svc in "$@"; do
    SERVICES+=("$svc")
  done
fi

# ---------- optional down ----------
if [ "${1:-}" = "--down" ] || [ "${1:-}" = "-d" ]; then
  echo "== stopping lite stack ==" 
  docker compose -f "$COMPOSE_FILE" down
  exit 0
fi

# ---------- build ----------
echo "== building lite services =="
if [ "${#SERVICES[@]}" -gt 0 ]; then
  docker compose -f "$COMPOSE_FILE" build "${SERVICES[@]}"
else
  docker compose -f "$COMPOSE_FILE" build
fi

# ---------- up ----------
echo "== starting lite stack =="
if [ "${#SERVICES[@]}" -gt 0 ]; then
  docker compose -f "$COMPOSE_FILE" up -d "${SERVICES[@]}"
else
  docker compose -f "$COMPOSE_FILE" up -d
fi

# ---------- wait ----------
echo "== waiting for services to report healthy =="
declare -A PORTS=( [postgres]=5432 [redis]=6379 [identity]=8081 [gateway]=8095 [restaurant]=8091 [order]=8092 )
deadline=$(( $(date +%s) + 600 ))
while true; do
  ok=0
  total=0
  for svc in postgres redis identity gateway restaurant order; do
    if [ "${#SERVICES[@]}" -gt 0 ] && [[ ! " ${SERVICES[*]} " =~ " ${svc} " ]]; then
      continue
    fi
    total=$((total+1))
    port="${PORTS[$svc]}"
    if [ "$svc" = "postgres" ]; then
      if pg_isready -h localhost -p "$port" -U app -d core >/dev/null 2>&1; then
        ok=$((ok+1))
      fi
    elif [ "$svc" = "redis" ]; then
      if redis-cli -h localhost -p "$port" ping >/dev/null 2>&1; then
        ok=$((ok+1))
      fi
    else
      if curl -sf -o /dev/null --max-time 3 "http://localhost:${port}/actuator/health"; then
        ok=$((ok+1))
      fi
    fi
  done
  printf '   ready %d/%d\n' "$ok" "$total"
  [ "$ok" -eq "$total" ] && break
  if [ "$(date +%s)" -ge "$deadline" ]; then
    echo "   STILL WAITING — check logs: docker compose -f $COMPOSE_FILE logs -f"
    exit 1
  fi
  sleep 5
done

echo "== lite stack ready =="
echo "   gateway:    http://localhost:8095"
echo "   identity:   http://localhost:8081"
echo "   restaurant: http://localhost:8091"
echo "   order:      http://localhost:8092"
echo "   postgres:   localhost:5432 (app/app_pass)"
echo "   redis:      localhost:6379"
echo ""
echo "   stop with:  $0 --down  or  docker compose -f $COMPOSE_FILE down"
