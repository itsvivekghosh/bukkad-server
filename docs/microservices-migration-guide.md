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
1. Implement the **Outbox relay**: a scheduled/transactional `OutboxRelay` that reads unsent `OutboxEvent` rows and publishes to `bhukkad.platform.events`, then marks sent (idempotent, at-least-once).
2. Implement **`SagaCoordinator`** execution: persist `SagaInstance` + `SagaStep`; execute steps; on failure run compensations in reverse; emit saga-state events.
3. Add `resilience4j` default config to `platform-lib` autoconfig (timeouts, circuit-breaker, retry, bulkhead).
4. **Exit:** a unit test publishes via outbox → appears on topic; a saga happy-path + one compensation path pass.

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

> **Correction required:** `docs/pgloader/*.load` currently read `FROM mysql://...`. The monolith is PostgreSQL now, so update the source to `FROM postgresql://${PG_USER}:${PG_PASSWORD}@${PG_HOST}:5432/bhukkad` (the monolith DB) and keep the per-service `INTO postgresql://.../bhukkad_<svc>` target.

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
P0 Foundation/Gateway → P1 platform-lib runtime → P2 data split
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
- **Scale-out:** install `metrics-server` + `prometheus-adapter` (for custom-metric HPA); choose managed Postgres vs in-cluster operator (Patroni/CloudNativePG) for replicas; pick shard key per hot service; **delete stale `k8s/mysql/` (PostgreSQL-only now)**.
