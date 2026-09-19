# Bhukkad Observability Stack

> Fleet-wide observability standard: structured logging, distributed tracing, metrics, circuit breakers, audit, and alerting.

## 1. Structured Logging

### 1.1 Logback Configuration
- **Shared fragment**: `services/platform-lib/src/main/resources/logback-bhukkad.xml`
  - Console appender with PII masking (`%pii` conversion word)
  - JSON appender (`CONSOLE_JSON`) using Logstash encoder for prod
- **Per-service override**: `logback-spring.xml` switches between `CONSOLE` (dev) and `CONSOLE_JSON` (prod)

### 1.2 PII Masking
- `PiiMaskingConverter` masks emails (`***@***`) and bare phone digits (`**********`)
- Stack traces are NOT masked (security audit: exception context must remain readable)

### 1.3 Correlation / MDC
- `TraceContext` seeds MDC keys: `traceId`, `spanId`, `correlationId`, `service`
- `CorrelationIdFilter` (platform-lib) ensures every request carries a correlation ID
- `TraceIdResolver` extracts trace IDs from incoming headers for cross-service joins

### 1.4 Request/Response Logging
| Component | Profile | Behavior |
|-----------|---------|----------|
| `RequestResponseLoggingFilter` | `dev` only | Logs full headers + body via `HTTP_BODY` logger |
| `ProductionRequestLoggingFilter` | default | Sampled summary line (`HTTP` logger) — no bodies |

### 1.5 Domain Loggers (platform-lib)
- `AuditLogger` — auth, consent, and admin actions
- `SecurityEventLogger` — login success/failure, token refresh, RBAC denials
- `KafkaEventLogger` — produced/consumed event shapes + DLQ routing
- `KafkaLagLogger` — consumer lag per topic/partition
- `CircuitBreakerLogger` — state transitions (CLOSED/OPEN/HALF_OPEN)
- `DependencyHealthLogger` — upstream health probe outcomes
- `StartupLoggingListener` — emits service version, profile, DB, and Kafka bootstrap state on boot
- `LoggingAspect` — `@LogExecution` / `@LogBusinessEvent` AOP around service methods

### 1.6 Log Separation
- **Dev**: human-readable console, verbose body logging when `app.debug=true`
- **Prod**: sampled JSON to stdout, collected by Fluent Bit → Loki / OpenSearch

---

## 2. Distributed Tracing

- **Micrometer Tracing** (Bridge: `TracingBridge`, `TraceContext`)
- Sampling probability configurable via `management.tracing.sampling.probability`
- Zipkin exporter configured in `application.yml` (`management.zipkin.tracing.endpoint`)
- Gateway propagates `traceparent` / `tracestate` headers on every proxied request

---

## 3. Metrics

### 3.1 Micrometer
- `BusinessMetrics` — order lifecycle, payment outcomes, delivery status transitions
- `EndpointSloMetrics` — per-endpoint latency/error SLO tracking
- `MetricsConfig` — shared `MeterRegistry` configuration

### 3.2 Gateway Metrics
- `gateway_route_unmatched` — counter tagged by normalized path
- `edge_hedge_requests_total` / `edge_hedge_won_total` — request hedging
- `edge_cache_miss` / `edge_kill_switch_fallback_served` — edge resilience
- `edge_request_size_*` / `edge_response_size_*` — size-limit guard
- Reactor Netty metrics enabled (`management.metrics.enable.reactor.netty: true`)

### 3.3 Export
- Prometheus endpoint exposed: `management.endpoints.web.exposure.include=health,info,metrics`
- Grafana dashboards provisioned via k8s ConfigMap (`k8s/monitoring/grafana-dashboards.yaml`)

---

## 4. Circuit Breakers

- **Resilience4j** instances per backend (`identity`, `order`, `payment`, `delivery`, `restaurant`, `search`, `social`, `notification`, `referral`, `support`, `growth`, `personalization`, `realtime`, `survey`, `admin-analytics`)
- Timeout: 8s; failure-rate threshold: 40%; slow-call rate: 30%
- Gateway routes annotate circuit breakers per slice:
  ```java
  .filters(f -> f.circuitBreaker(c -> c.setName("order")))
  ```
- `CircuitBreakerFilter` (platform-lib) wraps outbound `WebClient` calls with Resilience4j + fallback
- `CircuitBreakerLogger` emits state transition events

---

## 5. Error Handling

### 5.1 Global Exception Handler
- `GlobalExceptionHandler` (platform-lib) centralizes:
  - `MethodArgumentNotValidException` → 400 with field-level errors
  - `AccessDeniedException` → 403
  - `ResponseStatusException` passthrough
  - Unhandled exceptions → 500 envelope (no stack trace leakage)

### 5.2 Gateway Error Surfaces
- `FallbackController` — circuit-breaker fallback endpoints (`/fallback/*`)
- `UpstreamUnavailableHandler` — maps upstream 5xx / timeouts to 503 with `Retry-After`
- `EdgeApiErrors` — structured JSON error envelope (`code`, `message`, `path`, `timestamp`)

---

## 6. Alerting

- **PrometheusRule** (`k8s/monitoring/prometheus-rules.yaml`) covers:
  - Circuit breaker open state
  - Kafka outbox/DLT lag
  - HikariCP pending connections
  - Gateway unmatched routes
  - SSE budget exhaustion
  - JWKS fetch failures
  - Backup job success/failure
- Alert categories in `platform-lib`: `AlertSeverity`, `AlertCategory`

---

## 7. Kafka Observability

- `KafkaEventLogger` — logs produced/consumed event shapes with DLQ routing metadata
- `KafkaLagLogger` — emits per-topic/partition consumer lag metrics
- `KafkaPlatformConfig` / `KafkaPlatformProperties` — topic parity validation + concurrency guards

---

## 8. Audit Logging

- `AuditLogger` (platform-lib) captures:
  - Login / logout / lockout events
  - Consent grants and revocations
  - Admin actions (user verify, tenant activate, dispute status changes)
- Audit log lines include: `actorId`, `action`, `targetType`, `targetId`, `traceId`

---

## 9. Security Event Logging

- `SecurityEventLogger` records:
  - Authentication success/failure with reason codes
  - Token refresh and re-issue events
  - RBAC denials (`requiredRole`, `missingAuthority`)
- Sensitive values (passwords, OTPs, tokens) are never logged

---

## 10. Observability Checklist for New Services

When adding a new microservice:

1. **Logging**
   - `logback-spring.xml` includes `logback-bhukkad.xml` and selects `CONSOLE` / `CONSOLE_JSON`
   - `application.yml` sets `logging.level.com.bhukkad: INFO`
2. **Tracing**
   - `management.tracing.sampling.probability` configured
   - Outbound `WebClient` built via `PlatformWebClientBuilderFactory`
3. **Metrics**
   - `management.metrics.enable.reactor.netty: true`
   - Custom business metrics wired through `BusinessMetrics`
4. **Resilience**
   - Circuit breaker instance declared in `application.yml`
   - Outbound clients use `CircuitBreakerFilter`
5. **Error Handling**
   - Extend `GlobalExceptionHandler` with service-specific mappings
   - Gateway route (if applicable) gets `circuitBreaker` filter + fallback URI

---

## 11. Known Gaps / Pre-existing Failures

- `GatewayRoutingTest.authPathIsServedByIdentityBackend` — pre-existing WebClient/SSE routing failure
- Social service `SocialChaosTest`, `FeedInvalidationHandlerTest`, `OrderFromPostPerformanceTest` — pre-existing PostGIS / Testcontainers issues
