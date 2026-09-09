# syntax=docker/dockerfile:1.7
ARG SERVICE
FROM --platform=$BUILDPLATFORM maven:3.9.9-eclipse-temurin-17 AS builder
ARG SERVICE
WORKDIR /build
# Cache-optimized dependency resolution: copy pom files first, resolve deps,
# then copy source so unchanged source doesn't invalidate the dep cache.
COPY pom.xml .
COPY lombok.config . 2>/dev/null || true
COPY platform-lib/pom.xml ./platform-lib/pom.xml
COPY ${SERVICE}/pom.xml ./${SERVICE}/pom.xml
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    mvn -B -q dependency:go-offline -DskipTests -pl ${SERVICE} -am
# Copy source directories (platform-lib + service) and build.
COPY platform-lib ./platform-lib
COPY ${SERVICE} ./${SERVICE}
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    mvn -B -q clean package -DskipTests -pl ${SERVICE} -am

FROM eclipse-temurin:17-jre-jammy AS runtime
ARG SERVICE
ENV TZ=Asia/Kolkata \
    LANG=C.UTF-8 \
    SPRING_PROFILES_ACTIVE=prod \
    SERVER_PORT=8080 \
    JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -XX:+UseStringDeduplication -XX:MaxGCPauseMillis=200 -Djava.security.egd=file:/dev/./urandom -XX:+ExitOnOutOfMemoryError"
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && ln -snf /usr/share/zoneinfo/$TZ /etc/localtime \
    && echo "$TZ" > /etc/timezone \
    && groupadd -r -g 1001 bhukkad \
    && useradd -r -u 1001 -g bhukkad -d /app -s /usr/sbin/nologin bhukkad \
    && mkdir -p /tmp/logs/bhukkad /app \
    && chown -R bhukkad:bhukkad /app
WORKDIR /app
COPY --from=builder --chown=bhukkad:bhukkad /build/${SERVICE}/target/*.jar /app/app.jar
USER bhukkad
EXPOSE 8080
HEALTHCHECK --interval=20s --timeout=5s --start-period=90s --retries=5 \
  CMD curl -fsS "http://127.0.0.1:${SERVER_PORT}/actuator/health/readiness" >/dev/null || exit 1
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]