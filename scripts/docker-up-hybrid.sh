#!/usr/bin/env bash
# Hybrid bring-up for a small Docker VM (≤ ~4.5 GB): 13 services in Docker +
# 2 dependency-free read models (notification, personalization) as host JVMs.
set -euo pipefail
cd "$(dirname "$0")/.."

export JWT_SECRET="$(grep -m1 '^JWT_SECRET=' services/docker/.env | cut -d= -f2-)"
COMPOSE="docker-compose -f services/docker/docker-compose.dev.yml -f services/docker/docker-compose.hybrid.yml"

DOCKER_SVCS="postgres redis identity gateway restaurant order payment delivery search survey referral supportticket admin-analytics realtime growth"
HOST_SVCS=("notification 8085" "personalization 8088")

echo "== docker wave 0: infra =="
$COMPOSE up -d postgres redis

echo "== docker wave 1: identity + gateway =="
$COMPOSE up -d identity gateway

wait_started() { # container name
  for _ in $(seq 1 120); do
    docker logs "$1" 2>&1 | grep -q "Started .*Application" && { echo "  up(docker): $1"; return 0; }
    docker ps --format '{{.Names}}' | grep -qx "$1" || { echo "  CRASHED: $1"; docker logs "$1" --tail 20; return 1; }
    sleep 3
  done
  echo "  TIMEOUT: $1"; docker logs "$1" --tail 15; return 1
}
wait_started backend-identity
wait_started backend-gateway

echo "== docker wave 2: domain + platform =="
$COMPOSE up -d restaurant order payment delivery search survey referral
$COMPOSE up -d supportticket admin-analytics realtime growth
for c in backend-restaurant backend-order backend-payment backend-delivery backend-search backend-survey backend-referral backend-supportticket backend-admin-analytics backend-realtime backend-growth; do
  wait_started "$c" || true
done

echo "== host wave: notification + personalization (logs: /tmp/bhukkad-local) =="
export SERVICE_JWT_SECRET="$JWT_SECRET"
export TRACING_SAMPLE_PROBABILITY=0.0
export EVENTS_EXTERNAL_ENABLED=false
export REDIS_HOST=localhost
export SPRING_DATA_REDIS_HOST=localhost
mkdir -p /tmp/bhukkad-local
: > .hybrid-host.pids
LEAN="-Xms32m -Xmx224m -XX:MaxMetaspaceSize=160m -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:ActiveProcessorCount=1"
for entry in "${HOST_SVCS[@]}"; do
  name=${entry% *}
  port=${entry##* }
  jar=$(find "$PWD/services/$name/target" -name "$name-1.0.0.jar" | head -1)
  [ -n "$jar" ] || { echo "MISSING JAR $jar — run: ./mvnw -f services/pom.xml package -DskipTests"; exit 1; }
  java $LEAN "-Dserver.port=$port" -jar "$jar" > "/tmp/bhukkad-local/$name.log" 2>&1 &
  echo "$! $name" >> .hybrid-host.pids
done
for entry in "${HOST_SVCS[@]}"; do
  name=${entry% *}
  ok=0
  for _ in $(seq 1 80); do
    grep -q "Started .*Application" "/tmp/bhukkad-local/$name.log" 2>/dev/null && { ok=1; echo "  up(host): $name"; break; }
    sleep 3
  done
  [ "$ok" -eq 1 ] || { echo "TIMEOUT host $name"; tail -10 "/tmp/bhukkad-local/$name.log"; exit 1; }
done

echo "== hybrid stack ready: gateway on http://localhost:8095 =="
