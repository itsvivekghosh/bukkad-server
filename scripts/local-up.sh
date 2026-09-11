#!/usr/bin/env bash
# Run the FULL microservice stack as host (local) JVMs, using the dockerized
# postgres/redis for infrastructure only. This is "test the app locally".
#
# Prereqs: docker compose -f services/docker/docker-compose.dev.yml up -d postgres redis
# Prereq:  ./mvnw -f services/pom.xml package -DskipTests   (fat jars)
set -euo pipefail
cd "$(dirname "$0")/.."

export APP_AUTH_JWT_SECRET="$(grep -m1 '^JWT_SECRET=' services/docker/.env | cut -d= -f2-)"
export APP_AUTH_SERVICE_JWT_SECRET="${APP_AUTH_JWT_SECRET}"
# P0 secret-hygiene: application-local.yml now reads ${JWT_SECRET:}/${SERVICE_JWT_SECRET:}
# (no committed dev secret). identity's JwtProperties hard-fails below 32 chars,
# so the local profile must receive the canonical variable names too.
export JWT_SECRET="${APP_AUTH_JWT_SECRET}"
export SERVICE_JWT_SECRET="${APP_AUTH_SERVICE_JWT_SECRET}"
export SPRING_PROFILES_ACTIVE=local
export TRACING_SAMPLE_PROBABILITY=0.0
export EVENTS_EXTERNAL_ENABLED=false
export REDIS_HOST=127.0.0.1
export SPRING_DATA_REDIS_HOST=127.0.0.1

# identity needs the dev admin bootstrap the e2e suite logs in with
export APP_BOOTSTRAPADMIN_ENABLED=true
export APP_BOOTSTRAPADMIN_EMAIL=admin@bhukkad.dev
export APP_BOOTSTRAPADMIN_PASSWORD='Admin@123456'

# Service-mesh URLs: in Docker the compose file injects container DNS names
# (payment:8080, order:8092, ...). Host JVMs must talk to each other over
# localhost instead — otherwise mesh calls (order→payment charge, survey→order
# ownership oracle, delivery→payment earnings, admin→identity/ops proxies)
# die on UnknownHostException and surface as 500s in the API suite.
export RESTAURANT_SERVICE_URL=http://localhost:8091
export ORDER_SERVICE_URL=http://localhost:8092
export PAYMENT_SERVICE_URL=http://localhost:8093
export DELIVERY_SERVICE_URL=http://localhost:8094
export IDENTITY_SERVICE_URL=http://localhost:8081
export SUPPORTTICKET_SERVICE_URL=http://localhost:8086
export NOTIFICATION_SERVICE_URL=http://localhost:8085
export ADMIN_ANALYTICS_SERVICE_URL=http://localhost:8087
export SEARCH_SERVICE_URL=http://localhost:8082
export SURVEY_SERVICE_URL=http://localhost:8083
export REFERRAL_SERVICE_URL=http://localhost:8084
export REALTIME_SERVICE_URL=http://localhost:8077
export GROWTH_SERVICE_URL=http://localhost:8089
# delivery's PaymentServiceClient reads app.services.payment.url (no
# PAYMENT_SERVICE_URL indirection): env alias for the same target.
export APP_SERVICES_PAYMENT_URL=http://localhost:8093

# "name port" pairs (gateway port overridable via GATEWAY_PORT)
ALL_SERVICES=(
  "identity 8081" "search 8082" "survey 8083" "referral 8084"
  "notification 8085" "supportticket 8086" "admin-analytics 8087"
  "personalization 8088" "growth 8089" "restaurant 8091" "order 8092"
  "payment 8093" "delivery 8094" "realtime 8077" "gateway ${GATEWAY_PORT:-8095}"
)

# Optional positional args restrict the launch to a subset of services
# (canonical ports are kept so mesh URLs still resolve): local-up.sh identity gateway
SERVICES=()
if [ "$#" -gt 0 ]; then
  for entry in "${ALL_SERVICES[@]}"; do
    for want in "$@"; do
      if [ "${entry% *}" = "$want" ]; then
        SERVICES+=("$entry")
      fi
    done
  done
  if [ "${#SERVICES[@]}" -eq 0 ]; then
    echo "no requested service is known: $*"
    echo "available: ${ALL_SERVICES[*]% *}"
    exit 1
  fi
else
  SERVICES=("${ALL_SERVICES[@]}")
fi

LOG_DIR=/tmp/bhukkad-local
mkdir -p "$LOG_DIR"
PIDFILE=.local-stack.pids
: > "$PIDFILE"
# Lean JVM defaults mirror the memory-tight container tuning; C1-only +
# single-processor heuristics triple startup time when 15 JVMs launch at once.
# On a roomy host override for faster boots, e.g.: LOCAL_JVM_OPTS="-Xmx512m"
LEAN_OPTS="${LOCAL_JVM_OPTS:--Xms32m -Xmx224m -XX:MaxMetaspaceSize=160m -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:ActiveProcessorCount=1}"

echo "== launching ${#SERVICES[@]} JVMs (logs: $LOG_DIR/<service>.log) =="
for entry in "${SERVICES[@]}"; do
  name=${entry% *}
  port=${entry##* }
  # Search only the service's own target dir: a repo-wide find dies under
  # `set -e` when it hits unreadable (root-owned) directories like .m2repo.
  jar=$(find "$PWD/services/${name}/target" -maxdepth 1 -name "${name}-1.0.0.jar" 2>/dev/null | head -1 || true)
  [ -n "$jar" ] || { echo "MISSING JAR for $name — run: ./mvnw -f services/pom.xml package -DskipTests"; exit 1; }
  java $LEAN_OPTS "-Dserver.port=$port" -jar "$jar" > "$LOG_DIR/$name.log" 2>&1 &
  echo "$! $name" >> "$PIDFILE"
done

echo "== waiting for services to report healthy =="
# The local profile sets logging.level.com.bhukkad=WARN, which suppresses the
# "Started ... Application" INFO line — readiness is therefore probed on
# /actuator/health (200/UP), not on the log.
deadline=$(( $(date +%s) + 600 ))
count=${#SERVICES[@]}
while true; do
  ok=0
  waiting=""
  for entry in "${SERVICES[@]}"; do
    name=${entry% *}
    port=${entry##* }
    if curl -sf -o /dev/null --max-time 3 "http://localhost:${port}/actuator/health"; then
      ok=$((ok+1))
    else
      pid=$(awk -v n="$name" '$2==n{print $1}' "$PIDFILE")
      if [ -n "$pid" ] && ! kill -0 "$pid" 2>/dev/null; then
        echo "CRASHED: $name — last log lines:"
        tail -15 "$LOG_DIR/$name.log"
        exit 1
      fi
      waiting="$waiting $name"
    fi
  done
  printf '   ready %d/%d\n' "$ok" "$count"
  [ "$ok" -eq "$count" ] && break
  if [ "$(date +%s)" -ge "$deadline" ]; then
    # Do not give up (a 600 s cap on a contended boot killed live JVMs):
    # warn and keep watching, re-reporting every 5 minutes.
    echo "   STILL WAITING:$waiting (tip: LOCAL_JVM_OPTS can raise the dev heap)"
    deadline=$(( $(date +%s) + 300 ))
  fi
  sleep 10
done

echo "== local stack ready =="
echo "   gateway: http://localhost:${GATEWAY_PORT:-8095}"
echo "   logs:    $LOG_DIR/<service>.log"
echo "   staying attached — Ctrl+C stops the stack (or: scripts/local-down.sh)"

# Foreground supervisor: keep this script alive for the lifetime of the JVMs so
# the stack (and whoever tracks this script) owns the whole tree. Signals sent
# to the process group reach the service JVMs directly.
trap '' HUP
for pid_entry in $(awk '{print $1}' "$PIDFILE"); do
  wait "$pid_entry" 2>/dev/null || true
done
rm -f "$PIDFILE"
