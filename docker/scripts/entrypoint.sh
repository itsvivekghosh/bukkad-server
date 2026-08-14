#!/bin/bash
set -eu

WAIT_FOR_SERVICES="${WAIT_FOR_SERVICES:-false}"
MAX_WAIT="${MAX_WAIT:-120}"
INTERVAL="${INTERVAL:-2}"

log() {
  printf '[%s] %s\n' "$(date '+%H:%M:%S')" "$1"
}

wait_for_tcp() {
  host="$1"
  port="$2"
  name="$3"
  elapsed=0

  log "Waiting for ${name} (${host}:${port})..."
  while [ "$elapsed" -lt "$MAX_WAIT" ]; do
    if (echo >"/dev/tcp/${host}/${port}") >/dev/null 2>&1; then
      log "${name} is reachable"
      return 0
    fi
    sleep "$INTERVAL"
    elapsed=$((elapsed + INTERVAL))
  done

  log "ERROR: ${name} not reachable after ${MAX_WAIT}s"
  return 1
}

if [ "$WAIT_FOR_SERVICES" = "true" ]; then
  wait_for_tcp "${DB_HOST:-mysql}" "${DB_PORT:-3306}" "MySQL"
  wait_for_tcp "${REDIS_HOST:-redis}" "${REDIS_PORT:-6379}" "Redis"
fi

exec java ${JAVA_OPTS:-} \
  -Dspring.profiles.active="${SPRING_PROFILES_ACTIVE:-dev}" \
  -Dserver.port="${SERVER_PORT:-8080}" \
  -jar /app/app.jar
