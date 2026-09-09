# Bhukkad — Microservices Migration Guide

**Status:** ⚠️ Historical record (Phases 1–8 complete as of 2026-07) — **now superseded by the full-migration plan** for remaining scope: [MICROSERVICES-MIGRATION-EXECUTION-PLAN.md](MICROSERVICES-MIGRATION-EXECUTION-PLAN.md); gaps in this guide are tracked in [MIGRATION-GAP-ANALYSIS-AND-TECHNICAL-EXPANSION.md](MIGRATION-GAP-ANALYSIS-AND-TECHNICAL-EXPANSION.md).
**Audience:** Engineers maintaining the distributed system

This document chronicles the strangler-fig migration from the original Spring Boot monolith (`bhukkad-delivery-system`) to 7 independently deployable PostgreSQL microservices wired via Spring Cloud Gateway (path routing + edge headers), synchronous JWT-authenticated HTTP (gRPC is NOT used in this codebase — see MIGRATION-GAP-ANALYSIS §B4), Redpanda/Kafka async via the transactional outbox, Saga for distributed transactions, and resilience4j for fault tolerance.

---

## 1. What Was Migrated

### 1.1 Source monolith (partially decommissioned — holdout domains remain, see [services/k8s/DECOMMISSION-CHECKLIST.md](../services/k8s/DECOMMISSION-CHECKLIST.md))
- Single deployable: `src/main/java/com/bhukkad/BackendServerApplication.java`, packaged as `bhukkad-delivery-system-1.0.0.jar`.
- One PostgreSQL database (`bhukkad`), migrated from MySQL (Flyway location `db/migration-pg`, V1–V5).
- Flat package layout with ~66 entities, ~73 repos, ~45 controllers.

### 1.2 Target services (production)
| Service | Database | Domain |
|---------|----------|--------|
| `gateway` | — | Spring Cloud Gateway edge routing |
| `identity` | `identity` | auth, users, JWT validation |
| `restaurant` | `restaurants` | restaurants, menu, cuisines, reviews |
| `order` | `orders` | orders, cart, coupons, disputes |
| `payment` | `payments` | payments, wallet |
| `delivery` | `delivery` | delivery tracking, serviceability |
| `notification` | `notification` | async notifications (event consumer) |
| `admin-analytics` | `admin` | read-only analytics projections |

---

## 2. Target Architecture

```
                          ┌────────────────────────────┐
    Clients (web/mobile)  │      API Gateway           │  Spring Cloud Gateway
         │                │  auth passthrough, route,  │
         └────────────────┤  rate-limit, CB            │
                          └─────────┬──────────────────┘
                                    │ route by path
         ┌──────────────┬───────────┼───────────┬───────────────┐
         ▼              ▼           ▼           ▼               ▼
    identity       restaurant    order      payment        delivery
    (own PG)       (own PG)    (own PG)    (own PG)        (own PG)
         │              │           │           │               │
         │   gRPC (sync reads)      │  Saga     │               │
         └──────────────┴───────────┴───────────┴───────────────┘
                                    │
               Redpanda/Kafka  ◄────┴────►  Outbox relay  (bhukkad.platform.events)
                                    │
                             notification (consumer)   admin-analytics (CQRS read models)
```

---

## 3. Ownership Matrix (final)

Derived from `docs/pgloader/*.load`. Each service owns these tables and **only** these.

| Service | Target DB | Owned table regex |
|---|---|---|
| `identity` | `identity` | `users`, `customers`, `restaurant_owners`, `delivery_agents`, `admins`, `addresses`, `device_tokens`, `consent*`, `user_referral` |
| `restaurant` | `restaurants` | `restaurant*`, `menu_*`, `cuisine`, `inventory*`, `trending*`, `promo_*` |
| `order` | `orders` | `orders*`, `order_*`, `cart*`, `subscription*`, `group_order*`, `gift*` |
| `payment` | `payments` | `payments`, `wallet_*`, `disputes` |
| `delivery` | `delivery` | `delivery_*`, `rider_*`, `agent_*`, `zone_*` |
| `notification` | `notification` | `customer_notification_preferences` |
| `admin-analytics` | `admin` | `audit_*`, `fraud_*`, `churn*`, `experiment*`, `api_keys`, `data_export*`, `support_tickets`, `settlement*` (read models) |

Cross-domain reads are satisfied by **projected read models** maintained from events.

---

## 4. Building Blocks → Existing Code

| Concern | Mechanism | Location |
|---|---|---|
| Service template | Spring Boot app per module | `services/*/.../*ServiceApplication.java` |
| Shared infra jar | `platform-lib` (no web/datasource) | `com/bhukkad/common/{outbox,event,saga,idempotency,tracing,error,kafka,ratelimit,cache,security,web,metrics,logging,datasource}` |
| Reliable events | Outbox → Kafka relay | `common/outbox` + `common/kafka`; topic `bhukkad.platform.events` |
| Distributed txn | Saga orchestration | `common/saga` (`SagaCoordinator`) |
| Sync IPC | HTTP (RestTemplate/RestClient) + Istio mTLS — ratified per A11 (docs/adr/); gRPC is legacy monolith code only | monolith `grpc` pkg is deletion-track (W4); services call each other via HTTP behind the gateway/service DNS |
| Resilience | CB / retry / bulkhead / rate-limit | `resilience4j-spring-boot3` |
| AuthN/Z | JWT HS512, RBAC | `security/` → replicated verifier per service |
| Metrics | Micrometer / Prometheus | actuator + micrometer |
| Tracing | W3C `traceparent` | `common/tracing` (`TraceContext`) |
| Gateway | Spring Cloud Gateway | `services/gateway` |

---

## 5. Extraction Phases (completed)

| Phase | Scope | Status |
|-------|-------|--------|
| P0 | Gateway routes for public read paths | ✅ |
| P1 | platform-lib runtime (outbox relay + saga) | ✅ |
| P2 | Per-service DB split (pgloader corrected) | ✅ |
| P3 | Extract `identity` | ✅ |
| P4 | Extract `restaurant` | ✅ |
| P5 | Extract `order` + saga orchestrator | ✅ |
| P6 | Extract `payment`, `delivery`, `notification` | ✅ |
| P7 | `admin-analytics` + monolith teardown | 🚧 extraction done; **monolith partially decommissioned** — teardown gates tracked in `services/k8s/DECOMMISSION-CHECKLIST.md` |

---

## 6. Reference Configurations

### 6.1 Service `application.yml` (template)
```yaml
spring:
  datasource:
    url: jdbc:postgresql://${DB_HOST}:5432/${DB_NAME}   # bhukkad_<svc>
    username: ${DB_USER}      # from Vault
    password: ${DB_PASSWORD}  # from Vault
  jpa:
    hibernate:
      ddl-auto: validate
  flyway:
    locations: classpath:db/migration-pg   # platform-lib baseline + service V2+
    baseline-on-migrate: true
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:redpanda:9092}
    producer:
      retries: 5
app:
  events:
    platform-topic: bhukkad.platform.events
    dlq-topic: bhukkad.platform.events.dlt
  security:
    jwt-secret: ${JWT_SECRET}   # Vault; HS512, >=512 bits
resilience4j:
  circuitbreaker:
    instances:
      restaurantClient:
        sliding-window-size: 20
        failure-rate-threshold: 50
        wait-duration-in-open-state: 5s
  retry:
    instances:
      restaurantClient:
        max-attempts: 3
        wait-duration: 500ms
```

### 6.2 Outbox usage (pattern)
```java
@Transactional
public Order placed(OrderCmd cmd) {
    Order o = orderRepo.save(Order.create(cmd));
    outboxClient.queue(new PlatformEventMessage(
        "order.created", "order", o.getId(), traceContext, payload(o)));
    return o;   // relay publishes to Kafka after commit
}
```

### 6.3 Saga step (pattern)
```java
sagaCoordinator.start("create-order", List.of(
    step("reserve-inventory", () -> restaurantClient.reserve(id),
         () -> restaurantClient.release(id)),
    step("charge",             () -> paymentClient.charge(id),
         () -> paymentClient.refund(id)),
    step("dispatch",           () -> deliveryClient.dispatch(id),
         () -> deliveryClient.cancel(id))
));
```

### 6.4 Gateway route flip (rollback primitive)
Traffic moves by editing gateway predicates only — no service redeploy.

---

## 7. Data Migration Runbook (per service)

Steps:
1. Snapshot monolith DB.
2. Run corrected `pgloader/<svc>.load` → copies owned tables into `bhukkad_<svc>` (idempotent, re-runnable).
3. Service applies its Flyway `V2+` (adds columns/constraints absent in the raw copy).
4. **Dual-write:** during cutover the live writer emits outbox events; the target consumes → convergence.
5. **Backfill** read models from events.
6. **Shadow + reconcile:** run service beside monolith; diff responses; fix drift.
7. **Flip** gateway route; keep dual-write one bake period; then stop monolith writes for that domain.

---

## 8. Cross-Cutting Enablement
- **Gateway:** Spring Cloud Gateway (rate-limit, auth passthrough, path routes).
- **Discovery:** k8s Service DNS (headless Services for gRPC); no Eureka needed.
- **Config/Secrets:** Vault for JWT secret + per-service DB creds; `platform-lib` autoconfig binds them.
- **Tracing:** inject/propagate `TraceContext` (W3C) on every gRPC call and Kafka message.
- **CI/CD:** per-module build in `services/pom.xml`; per-service k8s manifests under `k8s/`; independent Deployments.
- **Autoscaling:** one `HorizontalPodAutoscaler` per service Deployment.

---

## 9. Testing & Contracts
- **Per service:** `./mvnw -f services/pom.xml -pl <svc> -am verify`.
- **ArchUnit:** `CommonArchTest` — no business logic in `platform-lib`; no cross-service DB access.
- **Contracts:** `pact` — consumer/provider pacts for gateway↔service and service↔service.
- **Saga:** happy path + each compensation branch.
- **Resilience:** inject latency/fault → verify CB/retry.

---

## 10. Observability
- Actuator health/readiness on every service; Prometheus scrape.
- Trace correlation via `traceparent`; structured JSON logs with `service.name`, `trace_id`, `span_id`.
- Alerts: error rate, p99 latency, CB open, outbox backlog, saga failure rate, consumer lag.

---

## 11. Risk Register & Rollback

| Risk | Mitigation | Rollback |
|---|---|---|
| Cross-domain join breaks | Read models via events | Re-enable monolith route |
| Dual-write divergence | Outbox + reconcile job + shadow diff | Route flip to monolith |
| Saga partial failure | Compensating txns; idempotent steps | Auto-compensate; alert |
| Auth inconsistency | Shared Vault JWT secret; local verify | N/A (stateless) |
| Sync latency | Cache read models; bulkheads; CB | Route flip |
| Cutover data loss | Snapshot + re-runnable pgloader + reconcile | Re-seed + re-flip |
| Deploy blast radius | Independent Deploys + kill-switches | Route flip |

---

## 12. Definition of Done (per service)
- [x] Own DB + Flyway green; data seeded via corrected pgloader.
- [x] Boots standalone; health/readiness UP.
- [x] Outbox publishes; consumes/produces events correctly.
- [x] Cross-service calls use gRPC + resilience4j.
- [x] JWT verified locally via Vault secret.
- [x] Gateway route active.
- [x] Contract (Pact) + ArchUnit + saga/compensation tests green.
- [x] Monolith code for the domain deleted (teardown complete).

---

## Appendix A — Command Cheat-Sheet
```bash
# Build all services
./mvnw -f services/pom.xml verify

# Build one service + its deps
./mvnw -f services/pom.xml -pl restaurant -am verify

# Run infra (postgres, redis, redpanda)
docker compose -f docker/docker-compose.dev.yml up -d postgres redis redpanda

# Seed a service DB from monolith DB
pgloader docs/pgloader/restaurants.load

# Run platform tests
./mvnw -f services/pom.xml -pl platform-lib -am test
```

## Appendix B — Bugs Found & Fixed (migration-phase audit)
| # | Component | Defect | Fix |
|---|---|---|---|
| C1 | `SagaCoordinator.executeSaga` | On step `execute()` failure, the failed `SagaStep` row was left `PENDING` (no status, no error) and `SagaInstance` mislabeled `STEP_COMPLETED` for a step that never completed. | Fetch the step before execute; on `RuntimeException`, `markFailed(msg)` + persist before unwinding. |
| C2 | `docs/pgloader/*.load` | All 7 read `FROM mysql://...` but the monolith is PostgreSQL; MySQL-specific `CAST type ...` blocks invalid for PG→PG; `restaurant_owners` claimed by both `restaurants.load` and `identity.load`. | Rewritten `FROM postgresql://$MONOLITH_PG_*`; removed CAST block; `restaurants.load` enumerates tables explicitly to exclude `restaurant_owners`. |
| C3 | `k8s/scripts/deploy.sh` | Deployed `deployment/bhukkad-mysql` and shipped stale MySQL k8s manifests. | Replaced `bhukkad-mysql` wait with `bhukkad-postgresql`; deleted `k8s/mysql/`. |
| C4 | `docker/.env.*` + `application-dev.yml` default | `JWT_SECRET` was 64 base64 chars = **384 bits** (< HS512 minimum of 512 bits). | `.env.dev` now ships a real 512-bit base64 secret; added `SecretValidationConfigTest.devProfileJwtSecret_mustDecodeToAtLeast512Bits` regression guard. |
| C5 | `KafkaPlatformEventPublisher.publish` | Fire-and-forget: the send `Future` was discarded and failures only logged. | Added `publishForResult()` (sync acked send) used by `OutboxPollPublisher`. |

---

## Appendix C — Monolith Teardown (P8)

All monolith artifacts removed after P7 extraction completed:
- Deleted root `pom.xml` (`bhukkad-delivery-system`)
- Deleted `src/main/java/com/bhukkad/BackendServerApplication.java` and all monolith source
- Deleted `k8s/app/` (monolith Deployment, Service, HPA, PDB)
- Deleted `k8s/nginx/` (standalone nginx ingress controller)
- Deleted `k8s/blue-green/` (monolith blue-green deployment)
- Deleted `k8s/overlays/custom-metrics-hpa/` (monolith HPA overlay)
- Updated `k8s/overlays/minikube/kustomization.yaml` to remove monolith resources/patches
- Removed `app` and `nginx` services from `docker/docker-compose.dev.yml` and `docker/docker-compose.prod.yml`
- Removed EC2 deploy scripts (`.github/scripts/ec2.sh`, `docker/scripts/deploy.sh`, `docker/scripts/docker-deploy.sh`)
- Updated `.github/workflows/{ci,staging,production}.yml` to deploy via `kubectl apply -k k8s/`
- Removed monolith routes from `GatewayConfig` and `application.yml`
- Deleted `sonar-project.properties` (monolith-only Sonar config)

The repository now builds exclusively via `./mvnw -f services/pom.xml`.

---

## 16. Final Pre-Flight Checklist (Cutover Validation)

> Run this checklist in a staging environment that mirrors production. **Do not proceed to production cutover until all checks pass.**

### 6.1 Schema & Data Integrity
| Step | Command / Action | Expected |
|------|-----------------|----------|
| 1 | `./mvnw -f services/pom.xml -pl platform-lib -am verify` | All platform tests pass |
| 2 | `pgloader docs/pgloader/<service>.load` for each extracted service | Exit 0, `total events` matches row counts in monolith |
| 3 | Compare row counts: `SELECT COUNT(*) FROM <table>` in monolith vs service DB | Within 0.1% tolerance |
| 4 | Verify foreign-key relationships: `SELECT * FROM <table> WHERE <fk_col> NOT IN (SELECT id FROM <ref_table>)` | Zero orphan rows |
| 5 | `psql -d <service_db> -c "\d <table>"` — confirm indexes match monolith | All critical indexes present |

### 6.2 API Contract Verification
| Step | Action | Expected |
|------|--------|----------|
| 6 | Run `./scripts/test-all-apis.py --reset-data` against each new service | ≥95% pass rate (baseline before cutover) |
| 7 | Verify all API contracts match monolith responses (diff JSON response shapes) | No structural differences |
| 8 | Run consumer-driven contract tests if any exist | All pass |
| 9 | Verify auth tokens from monolith gateway work for the new service | 200 on protected endpoints |

### 6.3 Infrastructure Readiness
| Step | Action | Expected |
|------|--------|----------|
| 10 | `kubectl get pods -n <svc-namespace>` | All pods Ready |
| 11 | `kubectl get svc,ingress -n <svc-namespace>` | Service + gateway route exist |
| 12 | Verify Kafka topics exist: `rpk topic list` | All required topics present (`bhukkad.platform.events`, `.dlt`) |
| 13 | Verify Redis connection from service | `redis-cli ping` returns PONG |
| 14 | Verify DB connection pool: `SELECT count(*) FROM pg_stat_activity WHERE datname='<svc_db>'` | Connections below `max_connections` threshold |
| 15 | `kubectl describe hpa -n <svc-namespace>` | HPA configured for target service |

### 6.4 Observability & Alerting
| Step | Action | Expected |
|------|--------|----------|
| 16 | Verify Prometheus can scrape service metrics (`curl http://<svc>:8080/actuator/prometheus`) | Metrics endpoint returns 200 |
| 17 | Verify Grafana dashboards exist for service | Dashboards importable |
| 18 | Verify alert rules exist in `k8s/prometheus/` or alert manager config | Alerts defined for latency, error rate, CPU, memory |
| 19 | Verify log aggregation: `kubectl logs -n <svc-namespace> -l app=<svc> --tail=100` | Logs structured as JSON |
| 20 | Verify distributed tracing: end-to-end trace appears in Jaeger/Tempo | Trace shows monolith → service call |

### 6.5 Smoke Test in Staging
| Step | Action | Expected |
|------|--------|----------|
| 21 | Deploy service alongside monolith (dual-write enabled) | Both monolith and service respond correctly |
| 22 | Route 1% of traffic to new service via gateway header/weight | No errors, metrics look healthy |
| 23 | Monitor `outbox_pending` lag for event-driven services | Lag stable, < 5 seconds |
| 24 | Run full integration test suite against staging | All tests pass |
| 25 | Verify rollback: flip gateway flag back to monolith | Traffic returns to monolith, no data loss |

**If any check above fails:** revert the gateway flag and investigate. Do not proceed with wider rollout.

---

## 17. Rollback Verification Steps

> Use these exact commands to reverse a service extraction if issues are detected in production.

### 17.1 Gateway-Level Rollback (Instant)
```bash
# Flip the gateway route back to the monolith for a given service
kubectl set env -n gateway deploy/gateway \
  PLATFORM_<SERVICE>_ROUTE=monolith

# Verify
kubectl get deploy gateway -o jsonpath='{.spec.template.spec.containers[?(@name=="gateway")].env}'
```
**Expected:** Traffic immediately returns to the monolith; the extracted service receives no new requests.

### 17.2 Database Rollback
```bash
# Stop writes to the service DB by removing the route first (see 17.1)
# Then drop the service database (data was replicated from monolith)
psql -h $MONOLITH_PG_HOST -U postgres -d postgres -c \
  "DROP DATABASE IF EXISTS bhukkad_<service>;"

# Recreate empty DB for future deployments
psql -h $MONOLITH_PG_HOST -U postgres -d postgres -c \
  "CREATE DATABASE bhukkad_<service>;"
```
**Note:** Monolith retains all original data — no data is lost by dropping the service DB.

### 17.3 Kafka Topic Rollback
```bash
# If the service published events, replay from the monolith-side DLQ
# First, stop the service so it stops consuming
kubectl scale deployment -n <svc-namespace> <svc> --replicas=0

# Optionally compact/retain the topic if debugging is needed
rpk topic describe bhukkad.platform.events
```

### 17.4 K8s Resources Rollback
```bash
# Delete the service namespace
kubectl delete namespace <svc-namespace>

# Remove HPA
kubectl delete hpa -n <svc-namespace> <svc>-hpa
# (namespace deletion handles this if HPA is in the same namespace)

# Remove gateway routes
kubectl set env -n gateway deploy/gateway \
  PLATFORM_<SERVICE>_ROUTE=monolith
```

### 17.5 Post-Rollback Verification
```bash
# Confirm monolith is handling <service> traffic
curl -s https://api.bhukkad.dev/<service-endpoint> | jq '.source'  # should indicate monolith

# Confirm no orphaned consumers
rpk consumer-groups list

# Confirm DB was cleaned up
psql -h $MONOLITH_PG_HOST -U postgres -d postgres -c \
  "\l"  # should not list bhukkad_<service>
```

---

## 18. Post-Migration Metrics Monitoring (SLOs Per Phase)

> Define these alerts before deploying each service. Review daily during rollout.

### 18.1 Per-Service SLOs

#### identity
| Metric | SLO | Alert Threshold |
|--------|-----|----------------|
| `http_server_requests_seconds_count_identity{status=~"5.."}` / `http_server_requests_seconds_count_identity` | Error rate < 0.1% | > 0.2% for 5 min |
| `http_server_requests_seconds_sum_identity` / `http_server_requests_seconds_count_identity` (99th pct) | Latency < 200ms | > 500ms for 2 min |
| `jvm_memory_used_bytes_identity` / `jvm_memory_max_bytes_identity` | Memory < 85% | > 95% for 5 min |
| `pg_database_size_bytes{dbname="identity"}` | Growth rate < 5%/day | > 20%/day |

#### restaurant
| Metric | SLO | Alert Threshold |
|--------|-----|----------------|
| Error rate | < 0.1% | > 0.2% for 5 min |
| Latency P99 | < 300ms | > 1s for 2 min |
| `db_connections_active_restaurant` / `db_connections_max_restaurant` | < 70% | > 90% for 5 min |
| `http_server_requests_seconds_count_restaurant{method="GET",uri="/restaurants/menu/*"}` | Cache hit ratio > 95% | < 90% for 10 min |

#### order
| Metric | SLO | Alert Threshold |
|--------|-----|----------------|
| Error rate | < 0.1% | > 0.2% for 5 min |
| Latency P99 | < 500ms | > 2s for 2 min |
| Saga completion rate | > 99.9% | < 99.5% for 10 min |
| `saga_instance_status{status="FAILED"}` | < 0.01% of total sagas | > 0.1% for 5 min |

#### payment
| Metric | SLO | Alert Threshold |
|--------|-----|----------------|
| Error rate | < 0.01% | > 0.1% for 5 min |
| Latency P99 | < 1s | > 5s for 2 min |
| Idempotency replay error rate | < 0.01% | > 0.1% for 5 min |
| `payment_gateway_latency_seconds` | < 3s | > 10s for 2 min |

#### delivery
| Metric | SLO | Alert Threshold |
|--------|-----|----------------|
| Error rate | < 0.1% | > 0.2% for 5 min |
| Latency P99 | < 200ms | > 1s for 2 min |
| `active_sse_connections_delivery` | < 10k per instance | > 15k for 5 min |
| Rider assignment latency | < 5s | > 30s for 2 min |

#### notification
| Metric | SLO | Alert Threshold |
|--------|-----|----------------|
| Error rate | < 0.1% | > 0.2% for 5 min |
| Latency P99 | < 200ms | > 1s for 2 min |
| Message delivery success | > 99.9% | < 99% for 5 min |
| `outbox_pending_notification` | < 100 | > 1000 for 5 min |

#### admin-analytics
| Metric | SLO | Alert Threshold |
|--------|-----|----------------|
| Query latency | < 5s | > 30s for 5 min |
| `http_server_requests_seconds_count_admin_analytics{status="200"}` rate | > 99% of expected | < 90% for 10 min |
| `jvm_gc_pause_seconds_admin_analytics` | < 100ms | > 500ms for 2 min |
| `pg_table_size{table="analytics_events"}` growth | < 10GB/day | > 50GB/day |

### 18.2 Shared Metrics (All Services)
| Metric | SLO | Alert Threshold |
|--------|-----|----------------|
| `outbox_pending` lag | < 1000 events | > 5000 for 5 min |
| `pg_replication_lag_seconds` | < 1s | > 5s for 5 min |
| `kafka_consumer_lag{topic="bhukkad.platform.events"}` | < 1000 per group | > 10000 for 5 min |
| CPU utilization | < 70% avg | > 85% for 10 min |
| Memory utilization | < 80% avg | > 95% for 5 min |

---

## 19. Team Handoff Notes (Per-Service Ownership)

> Last updated: 2026-09-01

### 19.1 Service Ownership Matrix

| Service | Primary Owner | Secondary Owner | Key Contacts | Slack Channel |
|---------|---------------|-----------------|--------------|---------------|
| platform-lib | Platform Team | — | @platform-team | `#platform-lib` |
| identity | Auth Team | Platform Team | @auth-team, @platform-team | `#identity-service` |
| restaurant | Menu Team | Platform Team | @menu-team, @platform-team | `#restaurant-service` |
| order | Order Team | Platform Team | @order-team, @platform-team | `#order-service` |
| payment | Payments Team | Order Team | @payments-team, @order-team | `#payment-service` |
| delivery | Logistics Team | Platform Team | @logistics-team, @platform-team | `#delivery-service` |
| notification | Comms Team | Platform Team | @comms-team, @platform-team | `#notification-service` |
| admin-analytics | Analytics Team | Platform Team | @analytics-team, @platform-team | `#admin-analytics` |

### 19.2 Documentation & Resources Per Service

#### identity
- **Repository path:** `services/identity/`
- **Run locally:** `./mvnw -pl identity -am spring-boot:run -Dspring-boot.run.profiles=dev`
- **Dockerfile:** `services/identity/Dockerfile`
- **K8s manifests:** `k8s/identity/`
- **Terraform:** `infra/terraform/modules/identity/`
- **Key env vars:** `JWT_SECRET`, `DB_URL`, `REDIS_URL`, `KAFKA_BOOTSTRAP_SERVERS`
- **Critical endpoints:** `/auth/login`, `/auth/refresh`, `/auth/verify-token`, `/users/me`
- **Migration script:** `docs/pgloader/identity.load`

#### restaurant
- **Repository path:** `services/restaurant/`
- **Run locally:** `./mvnw -pl restaurant -am spring-boot:run -Dspring-boot.run.profiles=dev`
- **Dockerfile:** `services/restaurant/Dockerfile`
- **K8s manifests:** `k8s/restaurant/`
- **Terraform:** `infra/terraform/modules/restaurant/`
- **Key env vars:** `RESTAURANT_DB_URL`, `MENU_CACHE_TTL_SECONDS`, `KAFKA_BOOTSTRAP_SERVERS`
- **Critical endpoints:** `GET /restaurants`, `GET /restaurants/{id}/menu`, `GET /restaurants/{id}/timing`
- **Migration script:** `docs/pgloader/restaurants.load`
- **Special notes:** Read-heavy service; ensure menu cache warming completes before traffic shift.

#### order
- **Repository path:** `services/order/`
- **Run locally:** `./mvnw -pl order -am spring-boot:run -Dspring-boot.run.profiles=dev`
- **Dockerfile:** `services/order/Dockerfile`
- **K8s manifests:** `k8s/order/`
- **Terraform:** `infra/terraform/modules/order/`
- **Key env vars:** `ORDER_DB_URL`, `SAGA_COORDINATOR_ENABLED`, `KAFKA_BOOTSTRAP_SERVERS`, `DELIVERY_SERVICE_URL`, `PAYMENT_SERVICE_URL`, `RESTAURANT_SERVICE_URL`
- **Critical endpoints:** `POST /orders`, `GET /orders/{id}`, `GET /orders/tracking/{id}`
- **Migration script:** `docs/pgloader/orders.load`
- **Special notes:** Saga orchestrator — critical path. Monitor saga completion rate and step failure rates.

#### payment
- **Repository path:** `services/payment/`
- **Run locally:** `./mvnw -pl payment -am spring-boot:run -Dspring-boot.run.profiles=dev`
- **Dockerfile:** `services/payment/Dockerfile`
- **K8s manifests:** `k8s/payment/`
- **Terraform:** `infra/terraform/modules/payment/`
- **Key env vars:** `PAYMENT_DB_URL`, `PAYMENT_GATEWAY_API_KEY` (Vault), `IDEMPOTENCY_TTL_SECONDS`, `KAFKA_BOOTSTRAP_SERVERS`
- **Critical endpoints:** `POST /payments`, `POST /payments/refund`, `GET /payments/{id}`
- **Migration script:** `docs/pgloader/payment.load`
- **Special notes:** Idempotency is critical; verify replay behavior after migration.

#### delivery
- **Repository path:** `services/delivery/`
- **Run locally:** `./mvnw -pl delivery -am spring-boot:run -Dspring-boot.run.profiles=dev`
- **Dockerfile:** `services/delivery/Dockerfile`
- **K8s manifests:** `k8s/delivery/`
- **Terraform:** `infra/terraform/modules/delivery/`
- **Key env vars:** `DELIVERY_DB_URL`, `RIDER_GEO_POSITIONAL_CHANNEL_PREFIX`, `KAFKA_BOOTSTRAP_SERVERS`, `NOTIFICATION_SERVICE_URL`
- **Critical endpoints:** `GET /tracking/{orderId}`, `POST /riders/assign`, `GET /riders/{id}/earnings`
- **Migration script:** `docs/pgloader/delivery.load`
- **Special notes:** Long-lived SSE connections; monitor `active_sse_connections` metric.

#### notification
- **Repository path:** `services/notification/`
- **Run locally:** `./mvnw -pl notification -am spring-boot:run -Dspring-boot.run.profiles=dev`
- **Dockerfile:** `services/notification/Dockerfile`
- **K8s manifests:** `k8s/notification/`
- **Terraform:** `infra/terraform/modules/notification/`
- **Key env vars:** `NOTIFICATION_DB_URL`, `SMS_PROVIDER_API_KEY` (Vault), `KAFKA_BOOTSTRAP_SERVERS`
- **Critical endpoints:** `POST /notifications`, `POST /notifications/send`, `GET /notifications/history`
- **Migration script:** `docs/pgloader/notification.load`
- **Special notes:** Event-driven; monitor outbox lag and message delivery success rate.

#### admin-analytics
- **Repository path:** `services/admin-analytics/`
- **Run locally:** `./mvnw -pl admin-analytics -am spring-boot:run -Dspring-boot.run.profiles=dev`
- **Dockerfile:** `services/admin-analytics/Dockerfile`
- **K8s manifests:** `k8s/admin-analytics/`
- **Terraform:** `infra/terraform/modules/admin-analytics/`
- **Key env vars:** `ANALYTICS_DB_URL`, `READ_REPLICA_URL`, `KAFKA_BOOTSTRAP_SERVERS`
- **Critical endpoints:** `GET /admin/analytics/orders`, `GET /admin/analytics/restaurants`, `GET /admin/analytics/riders`
- **Migration script:** None (read-only views on monolith DB)
- **Special notes:** Read-only service with its own read replica; no data migration required.

### 19.3 Escalation Contacts

| Severity | Contact | Response Time |
|----------|---------|---------------|
| P0 — Service Down | Platform Team Lead (@platform-team) | 15 min |
| P1 — Data Inconsistency | Service Owner + Platform Team | 30 min |
| P2 — Performance Degradation | Service Owner + Platform Team | 1 hour |
| P3 — Minor Bug / Enhancement | Service Owner | 1 business day |

### 19.4 CI/CD Ownership

All services use the same GitHub Actions workflow defined in `.github/workflows/ci.yml`. Each service team is responsible for:
1. Monitoring their service's CI pipeline status
2. Adding alerts for their service's build failures
3. Maintaining deployment automation in `k8s/scripts/deploy.sh`
4. Reviewing DB migration scripts in `docs/pgloader/` during code review

---

## 20. Sign-Off & Approval

### 20.1 Checklist for Final Approval

Before marking a service as fully migrated, confirm all of the following:

| Item | Owner | Status |
|------|-------|--------|
| All pre-flight checklist items pass | Platform Team | ✅ / ❌ |
| All SLOs monitored and alertable | Service Owner | ✅ / ❌ |
| Rollback verification tested in staging | Platform Team | ✅ / ❌ |
| CI/CD pipeline green for 72 hours | Service Owner | ✅ / ❌ |
| Runbook and escalation contacts published | Service Owner | ✅ / ❌ |
| Security review completed (Vault, secret rotation) | Security Team | ✅ / ❌ |
| Cost analysis approved (DB, Kafka, infrastructure) | Finance Team | ✅ / ❌ |
| Stakeholder sign-off | Product Lead | ✅ / ❌ |

### 20.2 Approval Record

| Service | Migration Date | Approved By | Notes |
|---------|----------------|-------------|-------|
| identity | — | — | Pending |
| restaurant | — | — | Pending |
| order | — | — | Pending |
| payment | — | — | Pending |
| delivery | — | — | Pending |
| notification | — | — | Pending |
| admin-analytics | — | — | Pending |

### 20.3 Post-Migration Retrospective

Schedule a 60-minute retro within 1 week of each service's production migration. Cover:

1. **What went well:** Capture successful patterns for reuse
2. **Pain points:** Identify friction in the process for the next service
3. **Metrics:** Review SLO compliance and alert noise
4. **Action items:** Convert to issues in the migration tracker repo

**Template:** `docs/templates/migration-retro.md`
