# syntax=docker/dockerfile:1.7

# ============================================
# Build + Spring Boot layers (Maven cache mount)
# ============================================
FROM --platform=$BUILDPLATFORM maven:3.9.9-eclipse-temurin-17 AS builder
WORKDIR /build
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    mvn -B -q dependency:go-offline -DskipTests
COPY src ./src
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    mvn -B -q clean package -DskipTests

# ============================================
# Minimal runtime
# ============================================
FROM eclipse-temurin:17-jre-jammy AS runtime

ENV TZ=Asia/Kolkata \
    LANG=C.UTF-8 \
    SPRING_PROFILES_ACTIVE=dev \
    SERVER_PORT=8080 \
    HEALTH_PATH=/api/v1/health/ping \
    WAIT_FOR_SERVICES=false \
    JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=50.0 -XX:+UseG1GC -XX:+UseStringDeduplication -XX:MaxGCPauseMillis=200 -Djava.security.egd=file:/dev/./urandom"

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates bash \
    && rm -rf /var/lib/apt/lists/* \
    && ln -snf /usr/share/zoneinfo/$TZ /etc/localtime \
    && echo "$TZ" > /etc/timezone \
    && groupadd -r -g 1001 bhukkad \
    && useradd -r -u 1001 -g bhukkad -d /app -s /usr/sbin/nologin bhukkad \
    && mkdir -p /app/logs /app/scripts \
    && chown -R bhukkad:bhukkad /app

WORKDIR /app

COPY --from=builder --chown=bhukkad:bhukkad /build/target/bhukkad-delivery-system-*.jar /app/app.jar

COPY --chown=bhukkad:bhukkad docker/scripts/entrypoint.sh /app/scripts/entrypoint.sh
RUN chmod +x /app/scripts/entrypoint.sh

USER bhukkad

EXPOSE 8080

HEALTHCHECK --interval=20s --timeout=5s --start-period=90s --retries=5 \
  CMD curl -fsS "http://127.0.0.1:${SERVER_PORT}${HEALTH_PATH}" >/dev/null || exit 1

ENTRYPOINT ["/app/scripts/entrypoint.sh"]
