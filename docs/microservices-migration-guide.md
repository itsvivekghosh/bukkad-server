# Bhukkad — Monolith to Distributed Microservices: Analysis & Implementation Guide

**Status:** Planning / execution-ready
**Audience:** Senior engineers performing the decomposition
**Scope:** Convert the single Spring Boot monolith (`bhukkad-delivery-system`, one JAR, one PostgreSQL DB) into 7 independently deployable, database-per-service Spring Boot applications wired as a distributed system (gRPC sync + Redpanda/Kafka async, Saga for distributed transactions, resilience4j for fault tolerance).

> This guide is grounded in the actual repository state: the `services/` Maven reactor, `platform-lib`, the scaffolded service apps, the `docs/pgloader/*.load` ownership files, and the dependency/class infra already present in the monolith (`spring-kafka`, gRPC, `resilience4j-spring-boot3`, actuator, Redpanda).

---

## 1. Current-State Analysis

### 1.1 What the monolith is today
- Single deployable: `src/main/java/com/bhukkad/BackendServerApplication.java`, packaged as `bhukkad-delivery-system-1.0.0.jar`.
- One PostgreSQL database (`bhukkad`), migrated from MySQL (Flyway location `db/migration-pg`, V1–V5).
- Flat package layout, **not** domain-modularized:
  - `entity/` (66 entities), `repository/` (73 repos), `controller/` (45), `service/` + `serviceImpl/` (~54), `dto/` (140).
  - Domain boundaries exist only by table/entity relationships, not by package. This is why the first migration step is an explicit **ownership matrix** (§3), which the `pgloader` files already anticipate.
- Cross-cutting: `security/` (JWT HS512 RBAC), `cache/`, `outbox/`, `event/`, `grpc/` (`OrderInternalServiceGrpc`), `ratelimit/`, `audit/`, `chaos/`.

### 1.2 What already exists for the target (reuse, don't rebuild)
| Asset | Location | Use |
|---|---|---|
| Maven reactor for services | `services/pom.xml` (`bhukkad-microservices`, `packaging: pom`) | Builds all services + platform-lib |
| Platform library | `services/platform-lib` | Shared infra jar (no web/datasource) |
| Service apps (scaffold) | `services/{identity,restaurant,order,payment,delivery,notification,admin-analytics}` | Each has `*ServiceApplication.java` |
| Restaurant service code | `services/restaurant/...` (57 files: entities, controllers, services) | Most complete; best first slice |
| DB-per-domain split | `docs/pgloader/{restaurants,orders,delivery,identity,payments,notification,admin}.load` | Exact table ownership regexes |
| Messaging | `spring-kafka` + Redpanda in `docker/docker-compose.dev.yml` | Topics: `bhukkad.platform.events` (+ `.dlt`) |
| Sync IPC | gRPC server starter + `grpc` proto | Internal service-to-service |
| Resilience | `resilience4j-spring-boot3` + micrometer | CB/retry/bulkhead/ratelimit |
| Observability | actuator + micrometer + Prometheus | Per-service health/metrics |
| Secrets / mesh | `docs/vault-setup.md`, `docs/istio-install.md` | Vault + Istio intended |

### 1.3 Gap analysis (what is missing)
- **API Gateway** — none; must be added (Spring Cloud Gateway).
- **Service discovery** — not needed if on k8s DNS; otherwise add.
- **Outbox→Kafka relay runtime** — `platform-lib` has the `OutboxEvent` model + `OutboxClient`; the relay scheduler/consumer must be finalized.
- **Saga runtime** — `SagaInstance/SagaStep/SagaCoordinator` present; the orchestration DSL + compensation execution must be wired per flow.
- **Per-service DBs + Flyway locations** — only `restaurant` has `db/migration-pg` (V2–V4) + `application.yml`; others need their own.
- **pgloader source** — files say `FROM mysql://`; monolith is Postgres now → update to `FROM postgresql://` (§7).
- **Contract tests (Pact)** — dep present; consumer/provider pacts not yet authored.
- **Vault integration** — documented, not yet wired into service config.

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
                                   │
                        Vault (JWT secret, DB creds)   Istio (mTLS, east-west)
                        Prometheus/Grafana + tracing (W3C traceparent)
```

**Principles**
1. Strangler-Fig: route one domain at a time; monolith stays fallback.
2. Database-per-service: no shared DB, no cross-DB joins. Replicate read models via events.
3. Events over sync coupling where consistency can be eventual.
4. Saga (not 2PC) for multi-service writes.
5. Outbox for every publish (write domain + event in one local tx).
6. No business logic in `platform-lib` (enforced by `CommonArchTest`).
7. A service may take external traffic only after it boots standalone + passes contract + arch tests.

---

## 3. Ownership Matrix (source of truth)

Derived from `docs/pgloader/*.load`. Each service owns these tables and **only** these.

| Service | Target DB | Owned table regex (from pgloader) |
|---|---|---|
| `identity` | `bhukkad_identity` | `users`, `customers`, `restaurant_owners`, `delivery_agents`, `admins`, `addresses`, `device_tokens`, `consent*`, `user_referral` |
| `restaurant` | `bhukkad_restaurants` | `restaurant*`, `menu_*`, `cuisine`, `inventory*`, `trending*`, `promo_*` |
| `order` | `bhukkad_orders` | `orders*`, `order_*`, `cart*`, `subscription*`, `group_order*`, `gift*` |
| `payment` | `bhukkad_payments` | `payments`, `wallet_*`, `disputes` |
| `delivery` | `bhukkad_delivery` | `delivery_*`, `rider_*`, `agent_*`, `zone_*` |
| `notification` | `bhukkad_notification` | `customer_notification_preferences` |
| `admin-analytics` | `bhukkad_admin` | `audit_*`, `fraud_*`, `churn*`, `experiment*`, `api_keys`, `data_export*`, `support_tickets`, `settlement*` (read models) |

Cross-domain reads are satisfied by **projected read models** maintained from events — never by querying another service's DB.

---

## 4. Distributed-System Building Blocks → Existing Code

| Concern | Mechanism | Grounded location |
|---|---|---|
| Service template | Spring Boot app per module | `services/*/.../*ServiceApplication.java` |
| Shared infra jar | `platform-lib` (no web/datasource) | `com/bhukkad/common/{outbox,event,saga,idempotency,tracing,error,kafka,ratelimit,cache,security,web,metrics,logging,datasource}` |
| Reliable events | Outbox → Kafka relay | `common/outbox` + `common/kafka`; topic `bhukkad.platform.events` |
| Distributed txn | Saga orchestration | `common/saga` (`SagaCoordinator`) |
| Sync IPC | gRPC | monolith `grpc` pkg (`OrderInternalServiceGrpc`) |
| Resilience | CB / retry / bulkhead / rate-limit | `resilience4j-spring-boot3` |
| AuthN/Z | JWT HS512, RBAC | `security/` → replicated verifier per service |
| Metrics | Micrometer / Prometheus | actuator + micrometer |
| Tracing | W3C `traceparent` | `common/tracing` (`TraceContext`) |
| Secrets | Vault | `docs/vault-setup.md` |
| Mesh / mTLS | Istio | `docs/istio-install.md` |
| Gateway | Spring Cloud Gateway | **to add** |
| Discovery | k8s Service DNS | **to add** (headless for gRPC) |

---

## 5. Implementation Roadmap (P0–P7)

### P0 — Foundation & Edge (1–2 weeks)
**Goal:** gateway in front of monolith with zero behavior change.
1. Add `spring-cloud-starter-gateway` to a new `services/gateway` module (or root-level edge service). Configure routes:
   ```yaml
   spring:
     cloud:
       gateway:
         routes:
           - id: monolith
             uri: lb://bhukkad-monolith
             predicates: [ Path=/api/** ]
           - id: auth-legacy
             uri: lb://bhukkad-monolith
             predicates: [ Path=/auth/**, /users/** ]
   ```
2. Wire Vault for the shared JWT secret; monolith + future services read `${JWT_SECRET}` from Vault.
3. CI: add `./mvnw -f services/pom.xml verify` as a required PR check; keep `CommonArchTest` (ArchUnit) as a gate.
4. **Exit:** 100% traffic → monolith via gateway; metrics/traces flowing.

### P1 — Finalize `platform-lib` Runtime (1 week)
**Goal:** outbox relay + saga runtime are real and tested.
1. Implement the **Outbox relay**: `OutboxPollPublisher` claims PENDING rows via `findPendingForProcessing` (`FOR UPDATE SKIP LOCKED`, horizontally safe), publishes via `KafkaPlatformEventPublisher` to `bhukkad.platform.events`, and flips rows to `PUBLISHED` on ack or keeps them `PROCESSING` (re-queueable by stale-recovery) on failure — with `retryCount`/`lastError` tracking.
2. Implement **`SagaCoordinator`** execution: persist `SagaInstance` + `SagaStep`; execute steps; on failure mark the failing step `FAILED` + error before unwinding; on success mark `COMPLETED`; compensations in reverse; replay of terminal sagas is a no-op.
3. Add `resilience4j` default config to `platform-lib` autoconfig (timeouts, circuit-breaker, retry, bulkhead).
4. Wire `OutboxPlatformConfig` (conditional on `app.events.external.enabled=true`).
5. **Exit:** a unit test publishes via outbox → appears on topic; a saga happy-path + one compensation path pass; **end-to-end Testcontainers** (`OutboxPollPublisherIntegrationTest` against Redpanda + Postgres) verifies claim→publish→`PUBLISHED` and the failure path.
6. **Status:** ✅ P1 complete and tested. `OutboxPollPublisher` (claims PENDING→PROCESSING via `FOR UPDATE SKIP LOCKED`, publishes via `publishForResult`, flips to `PUBLISHED` on ack / keeps `PROCESSING` re-queueable on failure, re-queues stale rows) + `KafkaPlatformEventPublisher.publishForResult` (sync acked send) + `OutboxProperties`/`OutboxPlatformConfig` (gated on `app.events.external.enabled`). 15 new tests (9 unit + 2 Testcontainers Redpanda+Postgres integration + 4 publisher). Full platform-lib = 124 tests, whole services reactor = 501 tests, monolith = 3319 tests — all green.

### P2 — Per-Service Data Split (DB-per-service) (2–3 weeks)
For **every** service:
1. Create DB `bhukkad_<svc>` + a Flyway location `db/migration-pg` that imports the `platform-lib` baseline (platform tables) then service `V2+` migrations. `restaurant` already does this (V2–V4).
2. Run the corrected `pgloader` (§7) to copy owned tables from the monolith Postgres DB into the service DB.
3. Define **read-model** tables for data owned by other services that this service needs.
4. **Exit:** each service DB contains exactly its owned data + platform tables; monolith DB untouched.

### P3 — Extract `identity` First (1–2 weeks)
**Why first:** auth is cross-cutting; every service validates JWTs locally.
1. `identity` already scaffolded → give it `bhukkad_identity` + Flyway.
2. Expose login/register/refresh + a `/internal/verify` (gRPC or REST) for token verification.
3. Add JWT verifier (HS512) to `platform-lib` `common/security`; every service uses it with the Vault secret.
4. Gateway routes `/auth/**`, `/users/**` → `identity`; everything else → monolith.
5. **Exit:** auth served by `identity`; monolith unchanged; rollback = route flip.

### P4 — Extract `restaurant` (most complete) (2 weeks)
1. `restaurant` has entities/controllers/services + `db/migration-pg` V2–V4 + `application.yml`. Give it `bhukkad_restaurants` (pgloader `restaurants.load`).
2. Gateway routes `/restaurants/**`, `/menu/**`, `/cuisine/**`, `/reviews/**` → `restaurant`.
3. **Dual-write/shadow:** while cutting over, the still-living writer emits outbox events; the other side consumes to stay converged. Run a reconciliation diff (monolith vs service responses).
4. Add `resilience4j` + gRPC client in monolith for any remaining sync reads of restaurant data.
5. **Exit:** restaurant traffic fully on service; contract tests green; rollback = route flip.

### P5 — Extract `order` + Saga (orchestrator) (3 weeks)
**Why after dependents:** `order` reads `restaurant` and saga-orchestrates `payment` + `delivery`.
1. Give `order` `bhukkad_orders` (pgloader `orders.load`); cart/order_items become its tables.
2. Implement **order saga** via `SagaCoordinator`:
   - `CreateOrder` → reserve inventory (`restaurant`) → charge (`payment`, saga step) → dispatch (`delivery`, saga step).
   - Compensations: refund on payment failure; release inventory on dispatch failure.
3. Steps are **idempotent**; saga state persisted in `order` DB.
4. Gateway routes `/orders/**`, `/cart/**` → `order`.
5. **Exit:** end-to-end order via saga with compensation tested; monolith order paths behind a kill-switch.

### P6 — Extract `payment`, `delivery`, `notification` (3–4 weeks)
- `payment`: `bhukkad_payments` (pgloader `payments.load`); consume order events; publish payment-result; own wallet/settlement/invoice.
- `delivery`: `bhukkad_delivery` (pgloader `delivery.load`); consume dispatch; publish agent location/status; own zone/geo.
- `notification`: `bhukkad_notification` (pgloader `notification.load`); pure event consumer; templates/prefs.
- All wired as saga participants + `resilience4j` on every cross-service call.
- **Exit:** these domains served by services; monolith equivalents behind flags.

### P7 — `admin-analytics` (CQRS) + Monolith Teardown (2–3 weeks)
1. `admin-analytics`: **read-only** projections built from event subscriptions (no writes to core domains). Target `bhukkad_admin`.
2. For each domain now fully on a service + contract-tested: **delete its code from the monolith** (the teardown phase per `services/README.md`).
3. Repeat until the monolith is empty → retire it; gateway talks only to services.
4. **Exit:** 7 services in prod; zero monolith code; full observability + alerts.

---

## 6. Reference Configurations (grounded)

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
Traffic moves by editing gateway predicates only — no service redeploy. Keep the monolith deployed until each domain is confirmed stable.

---

## 7. Data Migration Runbook (per service)

> **Done (P2 tooling corrected):** `docs/pgloader/*.load` now read `FROM postgresql://${MONOLITH_PG_USER}:${MONOLITH_PG_PASSWORD}@${MONOLITH_PG_HOST}:5432/bhukkad` (the monolith's actual PostgreSQL DB) — the old `FROM mysql://...` was a stale artifact and has been rewritten. The MySQL-specific `CAST type ...` blocks were removed (invalid/irrelevant for PG→PG), and an explicit `-- Usage:` block with PG env vars was added to every file.

**Ownership collision fixed:** `restaurant_owners` (a user/auth table holding `password_hash`/`totp`) was claimed by **both** `identity.load` (`~/^restaurant_owners$/`) and `restaurants.load` (`/^restaurant/` matches `restaurant_owners`). `restaurants.load` now enumerates its tables explicitly and **excludes** `restaurant_owners`; `identity.load` keeps sole ownership. This is the database-per-service invariant (no shared data).

Steps:
1. Snapshot monolith DB.
2. Run corrected `pgloader/<svc>.load` → copies owned tables into `bhukkad_<svc>` (idempotent, re-runnable). `pgloader` itself is not installed locally; run in CI/ops container.
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
- **CI/CD:** per-module build in `services/pom.xml`; per-service k8s manifests under `k8s/` (already Postgres-oriented); independent Deployments.
- **Autoscaling:** one `HorizontalPodAutoscaler` (autoscaling/v2) per service Deployment; base HPA + custom-metrics overlay already exist for the monolith (`k8s/app/hpa.yaml`, `k8s/overlays/custom-metrics-hpa/`) — clone per service (§15.1). Requires `metrics-server` + `prometheus-adapter`.
- **Data scale-out:** per-service PostgreSQL **read replicas** + read/write split + PgBouncer once a service is hot; **database sharding** only when a single primary's write IOPS saturates (§15.2–§15.3).
- **mTLS:** Istio (`docs/istio-install.md`) for east-west traffic post-P3.

---

## 9. Testing & Contracts
- **Per service:** `./mvnw -f services/pom.xml -pl <svc> -am verify`.
- **ArchUnit:** `CommonArchTest` — no business logic in `platform-lib`; no cross-service DB access.
- **Contracts:** `pact` (dep present) — consumer/provider pacts for gateway↔service and service↔service (gRPC + REST).
- **Saga:** happy path + each compensation branch.
- **Resilience:** inject latency/fault (reuse monolith `chaos` patterns) → verify CB/retry.
- **Shadow reconciliation:** automated response diff monolith vs service pre-flip.

---

## 10. Observability
- Actuator health/readiness on every service; Prometheus scrape; Grafana per-service dashboards.
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
| Replica lag / stale reads | Read/write split + lag alert + primary fallback | Route read to primary |
| Shard hotspot / resharding | High-cardinality key + shard-map service | Dual-write + cutover |

---

## 12. Sequencing Summary
```
P0 Foundation/Gateway → P1 platform-lib runtime (DONE: outbox relay + saga fix)
  → P2 data split (pgloader corrected; ownership collision fixed)
  → P3 identity → P4 restaurant → P6 payment/delivery/notification (parallelizable)
  → P5 order (after its dependents) → P7 admin-analytics + monolith teardown
  → P8 Scale-Out (per service, progressive): HPA → read replicas → sharding (hot only)
```
Extraction order: **identity → restaurant → notification → payment → delivery → order → admin-analytics → teardown.**
Scale-out order (per service, as traffic justifies): **HPA → read replica + read/write split + PgBouncer → sharding (write-saturated services only).**

---

## 13. Definition of Done (per service)
- [ ] Own DB + Flyway green; data seeded via corrected pgloader.
- [ ] Boots standalone; health/readiness UP.
- [ ] Outbox publishes; consumes/produces events correctly.
- [ ] Cross-service calls use gRPC + resilience4j.
- [ ] JWT verified locally via Vault secret.
- [ ] Gateway route active; monolith route is the rollback.
- [ ] Contract (Pact) + ArchUnit + saga/compensation tests green.
- [ ] Dashboards/alerts live; shadow reconciliation passed.
- [ ] Monolith code for the domain deleted (teardown).

---

## 14. First Concrete Slice (do this to prove the pattern)
1. Update `docs/pgloader/restaurants.load` source to `postgresql://`.
2. Create `bhukkad_restaurants`; run the load.
3. Add Spring Cloud Gateway routing `/restaurants/**` → `restaurant` service.
4. `./mvnw -f services/pom.xml -pl restaurant -am verify` + one Pact test against the gateway.
5. Flip route; keep monolith as fallback.
Once green, replicate P3–P7 with the same template.

---

## 15. Production Scale-Out: HPA, Read Replicas, Database Sharding

The decomposition in P0–P7 makes the system *horizontally partitionable*. This section covers scaling each partition for true heavy-traffic (5k+ RPS) load. These are **cross-cutting** and applied progressively per service once it is stable in production (treat as **P8** after P7, or in parallel for hot services).

### 15.1 Horizontal Pod Autoscaling (HPA)
Grounded in existing manifests:
- `k8s/app/hpa.yaml` — `HorizontalPodAutoscaler` (autoscaling/v2), `minReplicas: 10`, `maxReplicas: 20`, CPU `averageUtilization: 65`, scale-up 100%/30s + 2 pods/30s (Max), scale-down stabilized 300s at 25%/60s.
- `k8s/overlays/custom-metrics-hpa/` — replaces the base HPA (same name `bhukkad-app-hpa`) adding **prometheus-adapter** metrics: `http_server_requests_seconds_count_orders` (avg 30) and `sse_active_connections` (avg 200). Requires `metrics-server` (CPU) + `prometheus-adapter` (custom) installed.

Per-service changes:
1. **One HPA per service Deployment** (e.g. `restaurant-hpa` → `restaurant` Deployment). Reuse the same `autoscaling/v2` shape; tune `min/max` and `averageUtilization` per service SLO (order/payment need higher min than admin-analytics).
2. **Scale on the right signal**: CPU for stateless services; custom metrics (request rate, active SSE/connections, `outbox_pending`, `tomcat_threads_busy`) where CPU is a lagging indicator. Keep CPU as a floor metric; add business-rate metrics via the custom-metrics overlay pattern.
3. **Readiness gates**: only ready pods receive traffic; HPA + rolling deploy + `PodDisruptionBudget` (`k8s/app/pdb.yaml`) keep quorum during scale/rollout. Multi-AZ `topologySpreadConstraints` (already in `k8s/app/deployment.yaml`) prevent AZ-correlated scaling.
4. **Scale-down safety**: stabilization window ≥300s and percent-based policy avoid flapping; for stateful-ish services (outbox lag) prefer slower scale-down.

### 15.2 Read Replicas (PostgreSQL)
`k8s/postgres/` today is a **single** instance — no replica. Add per hot service:
1. **Streaming replication**: one or more async (or sync for money-path) read replicas per service DB (`bhukkad_<svc>`). Use managed Postgres (CloudSQL/RDS/Aurora) or Patroni/CloudNativePG in-cluster.
2. **Read/write split in the service**: route writes to the primary JDBC URL; route reads (listings, feeds, search) to a **replica URL**. Either via a second `DataSource` + `@ReadOnly` transaction routing, or a connection pooler that understands roles. Keep strongly-consistent reads (balances, order state) on the primary.
3. **Connection pooling**: add **PgBouncer** (or the pooler built into the operator) in `transaction`/`session` mode to protect replicas from connection storms; size pools per HPA maxReplicas.
4. **Stale-data tolerance**: define acceptable replication lag per query; alert on `pg_stat_replication` lag; downgrade to primary on lag breach.
5. **Cleanup**: remove the stale `k8s/mysql/` directory (MySQL-era replica config) — it is dead after the PostgreSQL migration and must not be applied.

### 15.3 Database Sharding
The per-service DB is **shard boundary #1** (a service never shares a DB). For hot services (orders, payments, notifications, restaurant reads), add **shard boundary #2**:
1. **Shard key**: choose a high-cardinality, write-spread key — `tenant_id` (multi-tenant), `region`/`city_id` (geo-local delivery), or `user_id`/`order_id` hash. Avoid keys that create hotspots (e.g. a single global admin tenant).
2. **Routing layer**: a `ShardRouter` (in `platform-lib` or a sidecar) maps shard key → physical DB via consistent hashing or a shard map. Each shard is an independent Postgres DB (`bhukkad_orders_00..NN`).
3. **Cross-shard queries**: forbid cross-shard joins. Aggregate in the service (scatter-gather) or, preferably, in **`admin-analytics`** which already consumes events and holds consolidated read models (CQRS) — this is the natural cross-shard query surface.
4. **Resharding**: pick a key that allows range/modulo splits; keep a shard-map service so resharding does not require app redeploys. Dual-write to old+new shard during migration, then cut over.
5. **Saga/Outbox with shards**: outbox + saga must live **per shard** (events keyed by shard) so compensation stays local; the relay partitions by shard.
6. **When to shard**: only after HPA + read replicas saturate a single primary's write IOPS. Sharding is the most expensive step — defer until justified by metrics.

### 15.4 Scale-Out Sequencing
```
P0–P7 (decompose) → P8 Scale-Out (per service, progressive):
   HPA (CPU + custom metrics) → Read replica + read/write split + PgBouncer
   → Sharding only for write-saturated hot services (orders/payments)
```
Apply to the highest-traffic services first (order, payment, restaurant reads). `admin-analytics` scales mostly via HPA (read-only). `identity` scales via HPA + read replica (token verify is read-heavy).

### 15.5 Metrics That Must Drive Scaling
- `cpu` (floor), `http_server_requests_seconds_count_<svc>` (rate), `sse_active_connections`, `outbox_pending` (lag), `pg_replication_lag_seconds` (replica), `tomcat_threads_busy`, and per-shard `write_iops`. Expose all via Micrometer → Prometheus → (prometheus-adapter for HPA).

---

## Appendix A — Command Cheat-Sheet
```bash
# Build all services
./mvnw -f services/pom.xml verify

# Build one service + its deps
./mvnw -f services/pom.xml -pl restaurant -am verify

# Run infra (postgres, redis, redpanda)
docker compose -f docker/docker-compose.dev.yml up -d postgres redis redpanda

# Seed a service DB from monolith DB (after editing pgloader source)
pgloader docs/pgloader/restaurants.load

# Run platform tests
./mvnw -f services/pom.xml -pl platform-lib -am test
```

## Appendix B — Open Items to Confirm Before Execution
- Decide gateway module location (`services/gateway` vs edge repo).
- Confirm Vault path/secret names for JWT + DB creds.
- Confirm whether `admin-analytics` needs write access or purely read models.
- Decide final topic taxonomy (currently `bhukkad.platform.events` + `.dlt`); consider per-domain topics for isolation.
- Confirm Istio adoption timing (post-P3 east-west mTLS).
- **Scale-out:** install `metrics-server` + `prometheus-adapter` (for custom-metric HPA); choose managed Postgres vs in-cluster operator (Patroni/CloudNativePG) for replicas; pick shard key per hot service; **delete stale `k8s/mysql/` (done)** and **`docker/mysql/` (done)**.

## Appendix C — Bugs Found & Fixed (migration-phase audit)
| # | Component | Defect | Fix |
|---|---|---|---|
| C1 | `SagaCoordinator.executeSaga` | On step `execute()` failure, the failed `SagaStep` row was left `PENDING` (no status, no error) and `SagaInstance` mislabeled `STEP_COMPLETED` for a step that never completed — losing failure attribution and corrupting saga state. | Fetch the step before execute; on `RuntimeException`, `markFailed(msg)` + persist before unwinding; failing step is not compensated (matches intent). Regression test `SagaCoordinatorTest.executeSaga_stepFailure_marksFailedStepFailedWithErrorMessage`. |
| C2 | `docs/pgloader/*.load` | All 7 read `FROM mysql://...` but the monolith is PostgreSQL; MySQL-specific `CAST type ...` blocks invalid for PG→PG; `restaurant_owners` claimed by both `restaurants.load` (`/^restaurant/`) and `identity.load`. | Rewritten `FROM postgresql://$MONOLITH_PG_*` with `-- Usage:`; removed CAST block; `restaurants.load` enumerates tables explicitly to exclude `restaurant_owners`. |
| C3 | `k8s/scripts/deploy.sh` / `k8s/mysql/` | Deployed `deployment/bhukkad-mysql` and shipped stale MySQL k8s manifests + `docker/mysql/` cfg + `.env.dev`/`.env.prod` MySQL env vars — all left over from the pre-PostgreSQL migration. | Replaced `bhukkad-mysql` wait with `bhukkad-postgresql`; deleted `k8s/mysql/` and `docker/mysql/`; rewrote `.env.dev`/`.env.prod` PG-consistent with 512-bit JWT secret. |
| C4 | `docker/.env.*` + `application-dev.yml` default | `JWT_SECRET` was 64 base64 chars = **384 bits** (< HS512 minimum of 512 bits); HS512 rejects it with `WeakKeyException` at token-signing time. `.env.prod` shipped a `CHANGE_ME_256_BIT` placeholder. | `.env.dev` now ships a real 512-bit base64 secret; `.env.prod` requires a generated 512-bit secret; added `SecretValidationConfigTest.devProfileJwtSecret_mustDecodeToAtLeast512Bits` regression guard. (In-code `application-dev.yml` default was already 516-bit.) |
| C5 | `KafkaPlatformEventPublisher.publish` | Fire-and-forget: the send `Future` was discarded and failures only logged — the documented "outbox retry path" it referenced did not exist, so events could be lost from the outbox's perspective. | Added `publishForResult()` (sync acked send) used by `OutboxPollPublisher`; `publish()` preserved for backward compatibility.

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
| `pg_database_size_bytes{dbname="bhukkad_identity"}` | Growth rate < 5%/day | > 20%/day |

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
