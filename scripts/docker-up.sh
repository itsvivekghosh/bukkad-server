#!/usr/bin/env bash
# Bring the dev stack up in staged waves so the 4 GB Docker VM's 8 cores are
# not thrashed by 15 simultaneous JVM boots. Infra first, then services in
# dependency order. Waits for each service's "Started *Application" marker
# before starting the next wave so health endpoints are reachable.
set -euo pipefail

cd "$(dirname "$0")/../services/docker"
COMPOSE="docker-compose -f docker-compose.dev.yml"
# docker-compose auto-loads JWT_SECRET from services/docker/.env (see
# .env.example); pass it in the environment to override.

echo "== wave 0: infra (postgres/redis) =="
$COMPOSE up -d postgres redis

echo "== wave 1: identity + gateway =="
$COMPOSE up -d identity gateway
wait_for() { # container_name pattern
  local name="$1"
  for _ in $(seq 1 100); do
    if docker logs "$name" 2>&1 | grep -q "Started .*Application"; then
      echo "  up: $name"
      return 0
    fi
    if ! docker ps --format '{{.Names}}' | grep -qx "$name"; then
      echo "  FAILED: $name is not running"
      return 1
    fi
    sleep 3
  done
  echo "  TIMEOUT waiting for $name"
  return 1
}
wait_for backend-identity
wait_for backend-gateway

echo "== wave 2: domain services =="
$COMPOSE up -d restaurant order payment delivery search survey referral notification
for c in backend-restaurant backend-order backend-payment backend-delivery backend-search backend-survey backend-referral backend-notification; do
  wait_for "$c" || true
done

echo "== wave 3: platform services =="
$COMPOSE up -d supportticket realtime growth personalization admin-analytics
for c in backend-supportticket backend-realtime backend-growth backend-personalization backend-admin-analytics; do
  wait_for "$c" || true
done

echo "== all waves complete =="
$COMPOSE ps --format '{{.Name}} {{.Status}}' 2>/dev/null || $COMPOSE ps
