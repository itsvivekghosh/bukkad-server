# ============================================
# Stage 1: Build
# ============================================
FROM --platform=$BUILDPLATFORM maven:3.9.6-eclipse-temurin-17 AS builder
WORKDIR /build
COPY pom.xml .
RUN mvn dependency:go-offline --no-transfer-progress -B
COPY src ./src
RUN mvn clean package -DskipTests --no-transfer-progress -B

# ============================================
# Stage 2: Runtime
# ============================================
FROM eclipse-temurin:17-jre AS runtime

RUN apt-get update && apt-get install -y --no-install-recommends \
    wget curl default-mysql-client redis-tools \
    && rm -rf /var/lib/apt/lists/*

ENV TZ=Asia/Kolkata
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

RUN groupadd -r -g 1001 bhukkad \
    && useradd -r -u 1001 -g bhukkad -m bhukkad

RUN mkdir -p /app/logs /app/scripts \
    && chown -R bhukkad:bhukkad /app

WORKDIR /app

COPY --from=builder --chown=bhukkad:bhukkad /build/target/*.jar app.jar

# Inline wait-for-services script
RUN cat > /app/scripts/start.sh << 'STARTSCRIPT'
#!/bin/bash
set -e

DB_HOST="${DB_HOST:-mysql}"
DB_PORT="${DB_PORT:-3306}"
DB_USERNAME="${DB_USERNAME:-bhukkad_user}"
DB_PASSWORD="${DB_PASSWORD:-bhukkad_pass}"
REDIS_HOST="${REDIS_HOST:-redis}"
REDIS_PORT="${REDIS_PORT:-6379}"
MAX_WAIT=180
INTERVAL=5

echo ""
echo "================================================"
echo "  Bhukkad Server - Waiting for services"
echo "================================================"

# Wait for MySQL
echo "[$(date '+%H:%M:%S')] Waiting for MySQL ($DB_HOST:$DB_PORT)..."
elapsed=0
while true; do
    if [ $elapsed -ge $MAX_WAIT ]; then
        echo "[$(date '+%H:%M:%S')] ERROR: MySQL not ready after ${MAX_WAIT}s"
        exit 1
    fi
    if mysqladmin ping -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USERNAME" \
        --password="$DB_PASSWORD" --connect-timeout=5 --silent 2>/dev/null; then
        echo "[$(date '+%H:%M:%S')] MySQL is ready!"
        break
    fi
    echo "[$(date '+%H:%M:%S')] MySQL not ready... retrying (${elapsed}s/${MAX_WAIT}s)"
    sleep $INTERVAL
    elapsed=$((elapsed + INTERVAL))
done

# Extra wait for MySQL init scripts
echo "[$(date '+%H:%M:%S')] Waiting 10s for MySQL to fully initialize..."
sleep 10

# Wait for Redis
echo "[$(date '+%H:%M:%S')] Waiting for Redis ($REDIS_HOST:$REDIS_PORT)..."
elapsed=0
while true; do
    if [ $elapsed -ge $MAX_WAIT ]; then
        echo "[$(date '+%H:%M:%S')] ERROR: Redis not ready after ${MAX_WAIT}s"
        exit 1
    fi
    RESP=$(redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" ping 2>/dev/null || echo "FAIL")
    if [ "$RESP" = "PONG" ]; then
        echo "[$(date '+%H:%M:%S')] Redis is ready!"
        break
    fi
    echo "[$(date '+%H:%M:%S')] Redis not ready... retrying (${elapsed}s/${MAX_WAIT}s)"
    sleep $INTERVAL
    elapsed=$((elapsed + INTERVAL))
done

echo ""
echo "[$(date '+%H:%M:%S')] All services ready! Starting app..."
echo "================================================"
echo ""

exec java $JAVA_OPTS \
    -Dspring.profiles.active="${SPRING_PROFILES_ACTIVE}" \
    -Dserver.port="${SERVER_PORT}" \
    -jar /app/app.jar
STARTSCRIPT

RUN chmod +x /app/scripts/start.sh

USER bhukkad

ENV SPRING_PROFILES_ACTIVE=dev \
    SERVER_PORT=8080 \
    DB_HOST=mysql \
    DB_PORT=3306 \
    DB_USERNAME=bhukkad_user \
    DB_PASSWORD=bhukkad_pass \
    REDIS_HOST=redis \
    REDIS_PORT=6379 \
    JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseG1GC -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -Djava.security.egd=file:/dev/./urandom"

EXPOSE 8080

HEALTHCHECK \
    --interval=30s \
    --timeout=10s \
    --retries=5 \
    --start-period=120s \
    CMD wget -qO- http://localhost:${SERVER_PORT}/api/health/ping || exit 1

ENTRYPOINT ["/app/scripts/start.sh"]