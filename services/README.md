# Bhukkad Services

Maven reactor for the PostgreSQL microservices migration
(`docs/architecture-microservices-postgresql.md` §4).

```
services/
├── pom.xml                  # aggregator (parent: spring-boot-starter-parent BOM)
├── platform-lib/            # platform library (no web, no datasource config)
│   ├── event/               #   PlatformEventMessage envelope (plan §6.3)
│   ├── tracing/             #   TraceContext + W3C traceparent (plan §9)
│   ├── error/               #   ApiError + BusinessException hierarchy
│   ├── outbox/              #   OutboxEvent entity/repo + OutboxClient (plan §6.1)
│   ├── idempotency/         #   IdempotencyRecord entity/repo (first-write-wins)
│   ├── saga/                #   SagaInstance/SagaStep + SagaCoordinator (plan §6.4)
│   └── src/main/resources/db/migration-pg/   # canonical platform PG baseline
└── <service>/               # Spring Boot apps, added in P2–P7
```

## Build & test

```bash
# Whole reactor
./mvnw -f services/pom.xml verify

# One module and its dependencies (CI uses this form)
./mvnw -f services/pom.xml -pl platform-lib -am test
```

The monolith root POM and `src/` have been removed as part of the P8 teardown.
This reactor is the sole build entrypoint.

## Platform rules

- **No web, no datasource config** in `platform-lib` — services own their
  datasources and Flyway locations.
- **No business logic shared** — only platform infrastructure; business code
  crosses service boundaries via events/contracts.
- Every service owns the platform tables in its own PG database via the
  canonical baseline (`platform-lib` jar → `db/migration-pg`), then adds
  service-specific tables in its own `V2+` migrations.
- ArchUnit (`CommonArchTest`) enforces the boundaries.

## Strangler-fig migration phases

| Phase | Scope | Effort | Status |
|-------|-------|--------|--------|
| P1 | Gateway routes for public read paths (restaurants, cuisines, menu) | Low | ✅ |
| P2 | Order service + payment service integration | Medium | ✅ |
| P3 | Restaurant service (menu, reviews) | Medium | ✅ |
| P4 | Delivery service | Medium | ✅ |
| P5 | Notification service (async, low risk) | Low | ✅ |
| P6 | Identity service (JWT validation, sessions) | High | ✅ |
| P7 | Admin-analytics service (read model from events) | Medium | ✅ |
| P8 | Decommission monolith API controllers | Low | ✅ |

## Verification checklist

- [x] Each service has its own DB and Flyway migrations
- [x] Services start independently via `./mvnw -pl <service> spring-boot:run`
- [x] Gateway routes all production traffic via `k8s/ingress.yaml` → `bhukkad-gateway`
- [x] Monolith removed; all traffic routes through microservices gateway
- [x] Distributed tracing shows requests across services (Zipkin, 100% sampling)
- [x] Circuit breakers prevent cascade failures (RetryFilter + CircuitBreakerFilter in platform-lib)
- [x] Each service has independent k8s HPA/PDB (`k8s/<service>/hpa.yaml`, `k8s/<service>/pdb.yaml`)
- [x] CI builds and deploys each service independently (`.github/workflows/services.yml`)
- [x] Service-to-service auth via `ServiceJwtAuthFilter` + `X-Service-Token` header
- [x] Saga coordinator + outbox pattern for data consistency (platform-lib `saga` + `outbox` packages)
- [x] Shared platform library (`platform-lib`) consumed by all services
- [x] Distributed tracing configured (Micrometer + Zipkin)
- [ ] Load tests pass at target RPS
