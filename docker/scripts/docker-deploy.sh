#!/usr/bin/env bash
# Deploy Bhukkad API container on a single EC2 host (staging or production).
# Expects IMAGE_TAG and application secrets in the environment (see deploy-staging.yml).
set -euo pipefail

IMAGE_TAG="${IMAGE_TAG:?IMAGE_TAG is required}"
APP_IMAGE="ghcr.io/itsvivekghosh/bukkad-server:${IMAGE_TAG}"
CONTAINER_NAME="bhukkad-app"
CONTAINER_PORT="${SERVER_PORT:-8080}"
HOST_PORT="${SERVER_PORT:-8080}"
DOCKER_NETWORK="${DOCKER_NETWORK:-bridge}"

echo "Deploying ${APP_IMAGE} as ${CONTAINER_NAME}..."

# Empty values from deploy-env.txt must not override Spring defaults
DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-3306}"
DB_NAME="${DB_NAME:-bhukkad}"
REDIS_HOST="${REDIS_HOST:-localhost}"
REDIS_PORT="${REDIS_PORT:-6379}"
REDIS_LOCAL="${REDIS_LOCAL:-false}"
REDIS_CONTAINER_NAME="bhukkad-redis"

ensure_local_redis() {
  local name="$1"
  if timeout 2 bash -c "echo >/dev/tcp/127.0.0.1/6379" 2>/dev/null; then
    echo "Redis already listening on 127.0.0.1:6379"
    return 0
  fi
  if docker ps --format '{{.Names}}' | grep -qx "${name}"; then
    echo "Redis container ${name} is already running"
    return 0
  fi
  if docker ps -a --format '{{.Names}}' | grep -qx "${name}"; then
    echo "Starting existing Redis container ${name}..."
    if docker start "${name}" >/dev/null 2>&1; then
      sleep 1
      return 0
    fi
    docker rm -f "${name}" 2>/dev/null || true
  fi
  echo "Creating Redis container ${name} on 127.0.0.1:6379..."
  docker pull redis:7-alpine
  docker run -d --name "${name}" --restart unless-stopped -p 127.0.0.1:6379:6379 redis:7-alpine
  sleep 1
}

if [ "${REDIS_LOCAL}" = "true" ]; then
  ensure_local_redis "${REDIS_CONTAINER_NAME}"
  REDIS_HOST="127.0.0.1"
  REDIS_PORT="6379"
  REDIS_PASSWORD=""
fi

echo "Checking Redis from EC2 host (${REDIS_HOST}:${REDIS_PORT})..."
if ! getent hosts "${REDIS_HOST}" >/dev/null 2>&1; then
  echo "ERROR: Cannot resolve REDIS_HOST=${REDIS_HOST}"
  exit 1
fi
if ! timeout 10 bash -c "echo >/dev/tcp/${REDIS_HOST}/${REDIS_PORT}" 2>/dev/null; then
  echo "ERROR: Cannot reach Redis at ${REDIS_HOST}:${REDIS_PORT} from EC2"
  exit 1
fi
echo "Redis reachable from EC2 host"

if [ -n "${GHCR_TOKEN:-}" ]; then
  echo "Logging in to ghcr.io..."
  echo "${GHCR_TOKEN}" | docker login ghcr.io -u "${GHCR_USERNAME:-itsvivekghosh}" --password-stdin
fi

echo "Stopping existing container..."
docker rm -f "$CONTAINER_NAME" 2>/dev/null || true

echo "Pulling image..."
docker pull "$APP_IMAGE"

NETWORK_ARGS=()
PORT_ARGS=(-p "${HOST_PORT}:${CONTAINER_PORT}")
if [ "${DOCKER_NETWORK}" = "host" ]; then
  NETWORK_ARGS=(--network host)
  PORT_ARGS=()
  echo "Using Docker host network (VPC DNS for Redis/RDS)"
fi

echo "Starting container..."
docker run -d \
  --name "$CONTAINER_NAME" \
  --restart unless-stopped \
  "${NETWORK_ARGS[@]}" \
  "${PORT_ARGS[@]}" \
  -e SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-staging}" \
  -e SERVER_PORT="${SERVER_PORT:-8080}" \
  -e DB_HOST="${DB_HOST}" \
  -e DB_PORT="${DB_PORT}" \
  -e DB_NAME="${DB_NAME}" \
  -e DB_USERNAME="${DB_USERNAME:-}" \
  -e DB_PASSWORD="${DB_PASSWORD:-}" \
  -e REDIS_HOST="${REDIS_HOST}" \
  -e REDIS_PORT="${REDIS_PORT}" \
  -e REDIS_PASSWORD="${REDIS_PASSWORD:-}" \
  -e JWT_SECRET="${JWT_SECRET:-}" \
  -e JWT_EXPIRATION="${JWT_EXPIRATION:-3600000}" \
  -e JWT_REFRESH_EXPIRATION="${JWT_REFRESH_EXPIRATION:-86400000}" \
  -e RAZORPAY_ENABLED="${RAZORPAY_ENABLED:-false}" \
  -e RAZORPAY_KEY_ID="${RAZORPAY_KEY_ID:-}" \
  -e RAZORPAY_KEY_SECRET="${RAZORPAY_KEY_SECRET:-}" \
  -e RAZORPAY_WEBHOOK_SECRET="${RAZORPAY_WEBHOOK_SECRET:-}" \
  -e NOTIFICATION_EMAIL_ENABLED="${NOTIFICATION_EMAIL_ENABLED:-false}" \
  -e NOTIFICATION_EMAIL_FROM="${NOTIFICATION_EMAIL_FROM:-noreply@bhukkad.com}" \
  -e STOMP_BROKER_TYPE="${STOMP_BROKER_TYPE:-simple}" \
  -e RABBITMQ_HOST="${RABBITMQ_HOST:-localhost}" \
  -e RABBITMQ_STOMP_PORT="${RABBITMQ_STOMP_PORT:-61613}" \
  -e RABBITMQ_USERNAME="${RABBITMQ_USERNAME:-guest}" \
  -e RABBITMQ_PASSWORD="${RABBITMQ_PASSWORD:-guest}" \
  -e LIVE_RELAY_ENABLED="${LIVE_RELAY_ENABLED:-true}" \
  -e LIVE_RELAY_CHANNEL="${LIVE_RELAY_CHANNEL:-bhukkad:live:order-updates}" \
  -e DB_REPLICA_ENABLED="${DB_REPLICA_ENABLED:-true}" \
  -e DB_REPLICA_URL="${DB_REPLICA_URL:-}" \
  -e DB_REPLICA_USERNAME="${DB_REPLICA_USERNAME:-}" \
  -e DB_REPLICA_PASSWORD="${DB_REPLICA_PASSWORD:-}" \
  -e PAYMENT_PROVIDER="${PAYMENT_PROVIDER:-simulated}" \
  -e PROMETHEUS_USERNAME="${PROMETHEUS_USERNAME:-}" \
  -e PROMETHEUS_PASSWORD="${PROMETHEUS_PASSWORD:-}" \
  "$APP_IMAGE"

echo "Container started: ${CONTAINER_NAME} with image: ${APP_IMAGE}"
