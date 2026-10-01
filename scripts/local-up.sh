#!/usr/bin/env bash
# Run the FULL microservice stack as host (local) JVMs, using the dockerized
# postgres/redis for infrastructure only. This is "test the app locally".
#
# Prereqs: docker compose -f services/docker/docker-compose.dev.yml up -d postgres redis
# Prereq:  ./mvnw -f services/pom.xml clean package -DskipTests   (fat jars;
# `clean` matters: maven never removes stale target/classes from a worktree
# whose merge deleted a resource (e.g. a renamed Flyway V11), and the orphan
# silently lands in the jar — duplicated migration versions then hard-fail
# boot.)
set -euo pipefail
cd "$(dirname "$0")/.."

export APP_AUTH_JWT_SECRET="$(grep -m1 '^JWT_SECRET=' services/docker/.env | cut -d= -f2-)"
export APP_AUTH_SERVICE_JWT_SECRET="${APP_AUTH_JWT_SECRET}"
# P0 secret-hygiene: application-local.yml now reads ${JWT_SECRET:}/${SERVICE_JWT_SECRET:}
# (no committed dev secret). identity's JwtProperties hard-fails below 32 chars,
# so the local profile must receive the canonical variable names too.
export JWT_SECRET="${APP_AUTH_JWT_SECRET}"
export SERVICE_JWT_SECRET="${APP_AUTH_SERVICE_JWT_SECRET}"
export JWKS_URL="http://localhost:8081/.well-known/jwks.json"
export IDENTITY_JWKS_URL="http://localhost:8081/.well-known/jwks.json"
export SPRING_PROFILES_ACTIVE=local
export TRACING_SAMPLE_PROBABILITY=0.0
export EVENTS_EXTERNAL_ENABLED=false
export REDIS_HOST=127.0.0.1
export SPRING_DATA_REDIS_HOST=127.0.0.1

# identity needs the dev admin bootstrap the e2e suite logs in with
export APP_BOOTSTRAPADMIN_ENABLED=true
export APP_BOOTSTRAPADMIN_EMAIL=admin@bhukkad.dev
export APP_BOOTSTRAPADMIN_PASSWORD='Admin@12345678'

# Service-mesh URLs: consolidated services expose multiple domains on single ports
export IDENTITY_SERVICE_URL=http://localhost:8081
export CATALOG_SERVICE_URL=http://localhost:8082
export COMMERCE_SERVICE_URL=http://localhost:8091
export ENGAGEMENT_SERVICE_URL=http://localhost:8083
export ADMIN_SERVICE_URL=http://localhost:8087
export GATEWAY_URL=http://localhost:8095

# Mesh URLs for gateway routing (matching k8s ConfigMap)
export IDENTITY_BASE_URL=http://localhost:8081
export CATALOG_BASE_URL=http://localhost:8082
export COMMERCE_BASE_URL=http://localhost:8091
export ENGAGEMENT_BASE_URL=http://localhost:8083
export ADMIN_BASE_URL=http://localhost:8087
export GATEWAY_BASE_URL=http://localhost:8095

# Legacy mesh URLs for backward compatibility (point to consolidated services)
export RESTAURANT_SERVICE_URL=http://localhost:8082
export ORDER_SERVICE_URL=http://localhost:8091
export PAYMENT_SERVICE_URL=http://localhost:8091
export DELIVERY_SERVICE_URL=http://localhost:8091
export SEARCH_SERVICE_URL=http://localhost:8082
export PERSONALIZATION_SERVICE_URL=http://localhost:8082
export SURVEY_SERVICE_URL=http://localhost:8083
export REFERRAL_SERVICE_URL=http://localhost:8083
export NOTIFICATION_SERVICE_URL=http://localhost:8083
export REALTIME_SERVICE_URL=http://localhost:8083
export GROWTH_SERVICE_URL=http://localhost:8083
export SOCIAL_SERVICE_URL=http://localhost:8083
export SUPPORTTICKET_SERVICE_URL=http://localhost:8087
export ADMIN_ANALYTICS_SERVICE_URL=http://localhost:8087

# Local stack canonical mesh URLs
export IDENTITY_BASE_URL=http://localhost:8081
export CATALOG_BASE_URL=http://localhost:8082
export COMMERCE_BASE_URL=http://localhost:8091
export ENGAGEMENT_BASE_URL=http://localhost:8083
export ADMIN_BASE_URL=http://localhost:8087
export GATEWAY_BASE_URL=http://localhost:8095

# Delivery's PaymentServiceClient reads app.services.payment.url
export APP_SERVICES_PAYMENT_URL=http://localhost:8091
# Supportticket's OrderServiceClient reads app.services.order.url
export APP_SERVICES_ORDER_URL=http://localhost:8091

# Consolidated services: "name port" pairs (gateway port overridable via GATEWAY_PORT)
ALL_SERVICES=(
  "identity 8081"
  "catalog 8082"
  "commerce 8091"
  "engagement 8083"
  "admin 8087"
  "gateway ${GATEWAY_PORT:-8095}"
)

# Optional positional args restrict the launch to a subset of services
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

# Use JDK 21 to avoid ARM64 C1 JIT crash on OpenJDK 17
export JAVA_HOME="/Users/vivekghosh/Library/Java/JavaVirtualMachines/ms-21.0.12/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"

LOG_DIR=/tmp/bhukkad-local
mkdir -p "$LOG_DIR"
PIDFILE=.local-stack.pids
: > "$PIDFILE"
# Lean JVM defaults mirror the memory-tight container tuning.
LEAN_OPTS="${LOCAL_JVM_OPTS:--Xms128m -Xmx512m -XX:MaxMetaspaceSize=256m -XX:+UseSerialGC}"

echo "== launching ${#SERVICES[@]} JVMs (logs: $LOG_DIR/<service>.log) =="
for entry in "${SERVICES[@]}"; do
  name=${entry% *}
  port=${entry##* }
  jar=$(find "$PWD/services/${name}/target" -maxdepth 1 -name "${name}-1.0.0-exec.jar" 2>/dev/null | head -1 || true)
  if [ -z "$jar" ]; then
    jar=$(find "$PWD/services/${name}/target" -maxdepth 1 -name "${name}-1.0.0.jar" 2>/dev/null | head -1 || true)
  fi
  [ -n "$jar" ] || { echo "MISSING JAR for $name — run: ./mvnw -f services/pom.xml package -DskipTests"; exit 1; }
java $LEAN_OPTS "-Dserver.port=$port" -Dspring.main.allow-bean-definition-overriding=true \
    -Dapp.bootstrap-admin.enabled=true \
    -Dapp.bootstrap-admin.email=admin@bhukkad.dev \
    -Dapp.bootstrap-admin.password=Admin@12345678 \
    -Dapp.bootstrap-admin.full-name="Platform Admin" \
    -jar "$jar" > "$LOG_DIR/$name.log" 2>&1 &
  echo "$! $name" >> "$PIDFILE"
  # Stagger: 15 JVMs racing docker-proxy/Redis simultaneously cause connect
  # refusions at boot (observed as Lettuce 'Unable to connect' + hard-fail).
  sleep 10
done

echo "== waiting for services to report healthy =="
deadline=$(( $(date +%s) + 1800 ))
count=${#SERVICES[@]}
RESTARTED=" "
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
        if case "$RESTARTED" in *" $name "*) false;; *) true;; esac; then
          echo "   $name died at boot — restarting once"
          RESTARTED="$RESTARTED$name "
          jar=$(find "$PWD/services/${name}/target" -maxdepth 1 -name "${name}-1.0.0-exec.jar" 2>/dev/null | head -1 || true)
          if [ -z "$jar" ]; then
            jar=$(find "$PWD/services/${name}/target" -maxdepth 1 -name "${name}-1.0.0.jar" 2>/dev/null | head -1 || true)
          fi
          if [ -n "$jar" ]; then
            java $LEAN_OPTS "-Dserver.port=$port" -Dspring.main.allow-bean-definition-overriding=true \
                -Dapp.bootstrap-admin.enabled=true \
                -Dapp.bootstrap-admin.email=admin@bhukkad.dev \
                -Dapp.bootstrap-admin.password=Admin@12345678 \
                -Dapp.bootstrap-admin.full-name="Platform Admin" \
                -jar "$jar" >> "$LOG_DIR/$name.log" 2>&1 &
            newpid=$!
            grep -v " $name\$" "$PIDFILE" > "$PIDFILE.tmp" && mv "$PIDFILE.tmp" "$PIDFILE"
            echo "$newpid $name" >> "$PIDFILE"
          fi
        else
          echo "CRASHED twice: $name — last log lines:"
          tail -15 "$LOG_DIR/$name.log"
          exit 1
        fi
      fi
      waiting="$waiting $name"
    fi
  done
  printf '   ready %d/%d\n' "$ok" "$count"
  [ "$ok" -eq "$count" ] && break
  if [ "$(date +%s)" -ge "$deadline" ]; then
    echo "   STILL WAITING:$waiting (tip: LOCAL_JVM_OPTS can raise the dev heap)"
    deadline=$(( $(date +%s) + 300 ))
  fi
  sleep 10
done

echo "== local stack ready =="
echo "   gateway: http://localhost:${GATEWAY_PORT:-8095}"
echo "   identity: http://localhost:8081"
echo "   catalog:  http://localhost:8082"
echo "   commerce: http://localhost:8091"
echo "   engagement: http://localhost:8083"
echo "   admin:    http://localhost:8087"
echo "   logs:     $LOG_DIR/<service>.log"
echo "   staying attached — Ctrl+C stops the stack (or: scripts/local-down.sh)"

trap '' HUP
for pid_entry in $(awk '{print $1}' "$PIDFILE"); do
  wait "$pid_entry" 2>/dev/null || true
done
rm -f "$PIDFILE"
