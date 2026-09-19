# Bhukkad Observability & Debugging Runbook

## Table of Contents
1. [Logging Architecture](#logging-architecture)
2. [How to Debug a Service Locally](#how-to-debug-a-service-locally)
3. [Key Log Patterns to Search For](#key-log-patterns-to-search-for)
4. [Loki Queries by Use Case](#loki-queries-by-use-case)
5. [Common Issues & Fixes](#common-issues--fixes)
6. [What Changed (2026-09-18)](#what-changed-2026-09-18)

---

## Logging Architecture

```
Spring Boot App (stdout/stderr)
    │
    ├── RequestLoggingFilter          → HTTP summary line (INFO)
    ├── CorrelationIdFilter           → X-Correlation-Id (INFO)
    ├── RequestResponseLoggingFilter  → body/headers (DEBUG, dev only)
    ├── LoggingAspect                 → controller/service entry/exit + slow WARN
    ├── StartupLoggingListener        → SERVICE_STARTED banner
    ├── DependencyHealthLogger        → HEALTH_CHECK DB/Redis/Kafka
    ├── AuditLogger                   → AUDIT payment/auth/admin/data
    ├── CircuitBreakerLogger          → CIRCUIT_BREAKER state changes
    └── KafkaEventLogger              → KAFKA_EVENT_CONSUMED/PRODUCED
    │
    ▼
Fluent Bit DaemonSet (enriches with k8s labels)
    │
    ▼
Loki (labels: service, environment, namespace_name, container_name)
    │
    ▼
Grafana / logcli
```

### Log Line Format

```
2026-09-18 17:30:00.123 INFO  [http-nio-8095-exec-1] [traceId=abc123, spanId=span456, correlationId=corr789, service=social] com.bhukkad.common.logging.RequestLoggingFilter - HTTP | method=POST | path=/api/v1/posts | status=201 | durationMs=125 | traceId=abc123
```

Fields:
- `traceId` / `spanId` — distributed tracing correlation
- `correlationId` — client-supplied or generated request correlation
- `service` — `spring.application.name`
- `event` — structured event tag (e.g. `SERVICE_SLOW`, `AUDIT`, `CIRCUIT_BREAKER`)

---

## How to Debug a Service Locally

### 1. Start Dependencies

```bash
# PostgreSQL
docker run -d --name postgres -p 5432:5432 -e POSTGRES_DB=_db -e POSTGRES_PASSWORD=app_pass postgres:16

# Redis
docker run -d --name redis -p 6379:6379 redis:7-alpine

# (Optional) Kafka
docker compose -f scripts/docker-compose.kafka.yml up -d
```

### 2. Start the Service

```bash
# Use dev profile for full DEBUG logging
./mvnw spring-boot:run -pl services/social \
  -Dspring-boot.run.profiles=dev \
  -Dspring-boot.run.arguments="--app.debug=true"
```

Or with the JAR:
```bash
java -jar services/social/target/social-*.jar \
  --spring.profiles.active=dev \
  --app.debug=true \
  --server.port=8095
```

### 3. Verify Startup Logs

You should see:
```
SERVICE_STARTED | name=social | port=8095 | environment=development | version=1.0.0 | host=macbook | activeProfiles=dev
HEALTH_CHECK | database=OK | redis=OK | kafka=OK
```

### 4. Enable HTTP Body Logging (Dev Only)

`RequestResponseLoggingFilter` is active automatically when:
- `app.debug=true` OR
- `spring.profiles.active=dev`

It logs at DEBUG under logger `HTTP_BODY`.

### 5. Enable Spring HTTP Logging

Already configured in `application-dev.yml`:
```yaml
logging:
  level:
    org.springframework.web: DEBUG
```

This prints request/response headers and body (for small payloads).

---

## Key Log Patterns to Search For

| Pattern | Meaning | Example |
|---------|---------|---------|
| `HTTP \|` | Request summary line | `HTTP \| method=POST \| path=/api/v1/orders \| status=201 \| durationMs=45` |
| `SERVICE_STARTED` | Service boot complete | `SERVICE_STARTED \| name=order \| port=8092` |
| `HEALTH_CHECK` | Dependency probe result | `HEALTH_CHECK \| database=OK \| redis=OK` |
| `SERVICE_SLOW` | Service call >500ms | `SERVICE_SLOW \| class=OrderService \| method=create` |
| `SLOW_QUERY` | DB query >200ms | `SLOW_QUERY \| class=PostRepository \| method=findByUser` |
| `CONTROLLER_ENTER` | Controller entry (DEBUG) | `CONTROLLER_ENTER \| class=PostController \| method=createPost` |
| `AUDIT \|` | Sensitive operation | `AUDIT \| type=PAYMENT \| event=PAYMENT_SUCCESS` |
| `CIRCUIT_BREAKER` | Resilience state change | `CIRCUIT_BREAKER \| name=payment-api \| from=CLOSED \| to=OPEN` |
| `KAFKA_EVENT_CONSUMED` | Message consumed | `KAFKA_EVENT_CONSUMED \| topic=order-events` |
| `RATE_LIMIT_EXCEEDED` | Throttled | `RATE_LIMIT_EXCEEDED` |
| `UNHANDLED_EXCEPTION` | Unexpected error | `UNHANDLED_EXCEPTION \| class=OrderService \| method=create` |
| `DATA_INTEGRITY_VIOLATION` | Constraint violation | `DATA_INTEGRITY_VIOLATION \| type=UniqueConstraintViolation` |
| `HttpMessageNotReadable` | Bad JSON body | `HttpMessageNotReadable \| Malformed or unreadable request body` |

---

## Loki Queries by Use Case

```logql
# All logs from a service
{service="social"}

# Errors only
{service="social"} |= "ERROR"

# HTTP requests to a specific endpoint
{service="social"} |~ "HTTP \\| path=/api/v1/posts"

# Trace a single request across services
{service=~"social|order|payment"} |~ "traceId=abc123"

# Slow service calls
{service="social"} |~ "SERVICE_SLOW"

# Circuit breaker opened
{service="order"} |~ "CIRCUIT_BREAKER.*to=OPEN"

# Payment audit trail
{service=~"order|payment"} |~ "AUDIT.*type=PAYMENT"

# Kafka events for a topic
{service="order"} |~ "KAFKA_EVENT_CONSUMED.*topic=order-events"

# Startup banner (verify deployment)
{service="social"} |~ "SERVICE_STARTED"

# Health check failures
{service=~".+"} |~ "HEALTH_CHECK.*FAIL"

# Last 10 minutes
{service="social"}[10m]
```

---

## Common Issues & Fixes

### Issue: No logs appearing in Loki

**Check:**
```bash
# 1. Loki is running
curl http://localhost:3100/ready

# 2. Fluent Bit is running
kubectl get pods -n bhukkad -l app=bhukkad,component=logging

# 3. LOKI_HOST is set in configmap
kubectl get configmap bhukkad-config -n bhukkad -o yaml | grep LOKI_HOST
```

**Fix:** Set `LOKI_HOST` in `k8s/configmap.yaml` and redeploy Fluent Bit.

### Issue: Logs don't have service label

**Check:** The logback pattern must include `%X{service:-}`.
```bash
grep -r "logback-bhukkad.xml" services/
```

**Fix:** Each service must either:
- Have `logback-spring.xml` that includes `logback-bhukkad.xml`, OR
- Use Spring Boot default with a custom pattern that includes `%X{service:-}`

### Issue: Can't trace a request across services

**Check:**
1. Client sent `X-Correlation-Id` header
2. `CorrelationIdFilter` added it to MDC
3. Log line contains `correlationId=...`

**Fix:** Ensure all services have `CorrelationIdFilter` on the classpath (it's in `platform-lib`).

### Issue: Sensitive data in logs

**Check:** `PiiMaskingConverter` is registered in `logback-bhukkad.xml`.

**Fix:** If emails/phones are leaking, verify:
```xml
<conversionRule conversionWord="pii"
                converterClass="com.bhukkad.common.logging.PiiMaskingConverter"/>
```

### Issue: Request bodies are empty in logs

**Check:** `RequestResponseLoggingFilter` only runs when `app.debug=true` or `dev` profile active.

**Fix:** Start service with `--spring.profiles.active=dev --app.debug=true`.

### Issue: Dependency health check crashes service

**Cause:** `DependencyHealthLogger` now uses optional autowiring. If a service crashes on startup with `NoSuchBeanDefinitionException`, it means the bean is still being required somewhere.

**Fix:** Ensure all constructor parameters in `DependencyHealthLogger` are marked `@Autowired(required = false)`.

---

## What Changed (2026-09-18)

| Component | Change |
|-----------|--------|
| `logback-bhukkad.xml` | Added `correlationId` and `service` to MDC pattern |
| `RequestLoggingFilter` | Puts `service` name into MDC per request |
| `RequestResponseLoggingFilter` | New — logs full HTTP body/headers in dev mode |
| `CorrelationIdFilter` | New — generates/propagates `X-Correlation-Id` |
| `StartupLoggingListener` | New — logs `SERVICE_STARTED` banner with metadata |
| `DependencyHealthLogger` | New — probes DB/Redis/Kafka on startup (optional beans) |
| `AuditLogger` | New — centralized payment/auth/admin/data audit events |
| `CircuitBreakerLogger` | New — logs Resilience4j state transitions |
| `KafkaEventLogger` | New — logs Kafka events, scoped to `dev` profile |
| `SecurityEventLogger` | Extended — rate limit, auth failure, account lockout, access denied |
| `LoggingSampler` | New — probabilistic sampling for HTTP/service logs |
| `LogMetadataEnricher` | New — enriches MDC with env/version/region |
| `application-dev.yml` (all services) | Added `org.springframework.web: DEBUG`, `app.debug: true` |
| `application.yml` (order, social) | Added `app.version`, `app.region`, `logging.sampling.*` |
| `application-dev.yml` (social) | Created missing dev profile |

---

## Alerting Rules (Loki / Alertmanager)

```yaml
# loki/alerting-rules.yaml
groups:
  - name: bhukkad-logs
    rules:
      - alert: HighErrorRate
        expr: sum(rate({service=~"social|order|payment"} |= "ERROR" [5m])) > 10
        for: 2m
        labels:
          severity: critical
        annotations:
          summary: "High error rate in {{ $labels.service }}"
          
      - alert: CircuitBreakerOpen
        expr: {service=~".+"} |~ "CIRCUIT_BREAKER.*to=OPEN"
        for: 1m
        labels:
          severity: warning
        annotations:
          summary: "Circuit breaker OPEN in {{ $labels.service }}"

      - alert: ServiceDown
        expr: absent({service=~".+"} |~ "SERVICE_STARTED")
        for: 5m
        labels:
          severity: critical
        annotations:
          summary: "Service {{ $labels.service }} not reporting"
```

---

## Environment Variables Reference

| Variable | Purpose | Default |
|----------|---------|---------|
| `APP_DEBUG` | Enable debug logging | `false` |
| `ENVIRONMENT` | Environment tag (prod/staging/dev) | `production` |
| `APP_VERSION` | Service version (CI injected) | `unknown` |
| `REGION` | Deployment region | `unknown` |
| `LOGGING_SAMPLING_HTTP` | Fraction of HTTP requests to log | `1.0` (100%) |
| `LOGGING_SAMPLING_SERVICE` | Fraction of service calls to log | `1.0` (100%) |
| `LOKI_HOST` | Loki endpoint for Fluent Bit | `""` (disabled) |
| `MANAGEMENT_ZIPKIN_TRACING_ENDPOINT` | Zipkin collector | `""` (disabled) |
| `TRACING_SAMPLE_PROBABILITY` | Head-based sampling | `1.0` (100%) |

---

## Further Improvements (Roadmap)

1. **OpenSearch / Elasticsearch** — add output plugin to Fluent Bit for per-service indices
2. **Structured JSON output** — switch from text to JSON for all logs in prod
3. **Sensitive data scanner** — regex-based redaction in addition to PII masker
4. **Sampled request/response logging in prod** — use `LoggingSampler` to enable full body logging for a subset of requests
5. **Metrics ↔ Logs ↔ Traces correlation** — embed metric tags in log lines automatically
