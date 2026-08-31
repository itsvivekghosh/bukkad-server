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

The monolith root POM remains the standalone "before" build until the teardown
phase; this reactor is intentionally separate.

## Platform rules

- **No web, no datasource config** in `platform-lib` — services own their
  datasources and Flyway locations.
- **No business logic shared** — only platform infrastructure; business code
  crosses service boundaries via events/contracts.
- Every service owns the platform tables in its own PG database via the
  canonical baseline (`platform-lib` jar → `db/migration-pg`), then adds
  service-specific tables in its own `V2+` migrations.
- ArchUnit (`CommonArchTest`) enforces the boundaries.
