# Bhukkad Architecture Decision Records

Authoritative ADRs for the microservices migration. Each ADR records the
decision, the options considered and rejected, and the conditions that would
reopen it. Source analysis:
[`MIGRATION-GAP-ANALYSIS-AND-TECHNICAL-EXPANSION.md`](./MIGRATION-GAP-ANALYSIS-AND-TECHNICAL-EXPANSION.md)
(§0). Status: Active unless noted. Date: 2026-09-05.

---

## A1 — Outbox for async delivery

**Decision.** Every domain event reaches Kafka (Redpanda `cluster-a`) through the
per-service outbox: event rows commit inside the business transaction;
`OutboxPollPublisher` (platform-lib) relays with `publishForResult` semantics
(row flips PUBLISHED only on broker ack) and claims rows via `claimPending`
(for-update skip locked). At-least-once delivery; consumers are idempotent by
`eventId`.
**Rejected.** Dual-writes (service commits, then posts to Kafka): a crash
between commit and post silently loses events. Kafka transactions: unproven
cost at our scale, couples DB and broker availability.
**Revisit if.** Event volume makes poll latency a constraint (move to
transactional-listener tailing the outbox CDC-style) — not before soak proves it.
**Implementation (W0).** Poll interval is now per-service
`app.outbox.poll-interval-ms` (default 5000 ms); staging baselines p99 relay
latency before this value is tuned.

## A2 — Event schema conventions

**Decision.** Versioned subject per event type (`<Type>V1`, `<Type>V2NewField`),
envelope `platform-lib/common/event` records, `schemaVersion` mandatory on
consume with a reject-and-alert path for unknown versions. The event catalog is
the code-generated authority: `catalog/events.v1.yaml` (docs/event-catalog.md is
its rendered view).
**Rejected.** Unversioned additive-only DTOs (current drift: `OrderCreated` has
three incompatible shapes); schema-registry enforcement (adds infra dependency
today; revisit at GA if drift recurs).
**Revisit if.** Any consumer needs breaking payload change → V2 subject +
N-versions rule (max 2 live versions, consumers migrate within one wave).

## A3 — Saga ownership for order placement

**Decision.** Order owns the place-order saga (`com.bhukkad.order.saga`,
platform-lib saga tables per DB): steps reserve-stock → charge → confirm with
compensation on failure. Payment never calls back into order synchronously.
202/async creation (W2) wraps the same saga — the saga is the single execution
path sync and async share.
**Rejected.** Choreography (status events driving each other): no visibility,
no replay; compensation in the payment webhook path: partial, webhook-dependent.
**Revisit if.** Saga step set grows past ~6 steps or spans >3 services → evaluate
a dedicated orchestrator (Temporal-class).

## A4 — Redis: session, rate-limit, caches — authoritative vs derived

**Decision.** The Redis map in `services/k8s/redis-mongodb-migration-plan.md` is
binding for what may be dropped on restart: session/refresh-state hashes,
rate-limit counters, and `live:events` pub/sub are recoverable or ephemeral by
design. Anything marked authoritative (e.g., `reservedStock:*`) must have a
durable twin — none currently does, so W1/W3 add stock reservations to PG
(`menu_stock_reservations`) and demote Redis to the TTL/expiry cache.
**Rejected.** Treating Redis as durable; migrating reservations to Kafka
compacted topics (over-engineered for the volume).
**Revisit if.** A future feature wants Redis as primary store → it must add a
Redis-persist policy + DR restore procedure *in this ADR* first.

## A5 — JWT claims contract (platform standard)

**Decision.** Identity issues HS256 (dev) / RS256+JWKS (prod, identity signs,
`jwks-url` configured in every service via `app.auth.jwt`). Claims: `sub` =
numeric user id, `uid` (duplicate for tolerance), `email`, `scope` (customer /
rider / admin / restaurant), `roles[]`, `exp` ≥ TTL 30 min / refresh 7 days.
Services verify locally through platform-lib `PlatformJwtValidator`; the gateway
never forwards `X-User-Id`-style identity headers that services would trust —
identity always comes from the verified token.
**Rejected.** Gateway-injected trusted-headers-only model (any pod can spoof);
opaque tokens + introspection per request (latency, identity SPOF).
**Revisit if.** Third-party API consumers appear → move to RS256 everywhere +
audience checks.

## A6 — MongoDB → PostgreSQL completion

**Decision.** Remaining Mongo collections must land in an owned PG schema —
none may die without an owner: chat/compliance-retention → `search` (owns chat
search already); file-metadata → platform-lib `common/storage` metadata model
(admin-analytics hosts the admin surface); notification-preferences →
notification service. W3 ports all three readers/writers and closes the
`MONGO_DB_URI` grep gate.
**Rejected.** Keeping Mongo running "for later" (blocks decommission, two
stateful systems); writing them off as legacy data (compliance retention is a
regulatory blocker).
**Revisit if.** Chat volume makes PG FTS inadequate → dedicated search engine,
tracked as its own ADR.

## A7 — Search/Redis sync: stream-not-cron

**Decision.** The `bhukkad.ordercreated` consumer updates trending/cache
(counters, menu-item aggregates) only via the `MenuItemCacheSyncer` stream
mechanism: Kafka consumer → targeted cache key update, never a full `@Scheduled`
rebuild loop. The 5-minute dish-popularity rebuild cron is retired; popularity
aggregates are computed incrementally and persisted to a PG rollup table.
**Rejected.** Keep the cron: silent drift + 5-min staleness spikes + rebuild
thundering herd; Redis-backed popularity with warm-start: adds a dependency
chain before we've proven the stream path.
**Revisit if.** Event lag >5 min persistently at production volume → reinstate
a *bounded* nightly reconciliation job with metrics + alerts.

## A8 — Scheduler platform: DB-lease `@Scheduled` now, ShedLock when contention appears

**Decision.** All service schedulers follow the platform-lib `DistributedLock`
DB-lease pattern (same contract as `SagaPendingQueuePoller`); a second
`SchedulerLockConfig` variant is *not* introduced — platform-lib exports the
existing lease helper (`common/schedule/ScheduledJobLock`) as the canonical API
and the notification copy folds into it at W1. `@SchedulerLock`/ShedLock is
re-evaluated only when a job shows cross-instance contention under production
cadence (28 jobs are in scope; none demonstrated contention yet — dev is
single-instance so nothing can show up locally: measure on staging).
**Rejected.** Business-logic idempotency only (correct but gives no cadence
control); leader election / external scheduler (Quartz, XXL-Job): infrastructure
out of proportion to current job count.
**Revisit if.** A job needs true cluster-wide cadence guarantees (exactly-once
scheduling), or any single job exceeds 60 s holding its lease.

## A9 — Data migration discipline

**Decision.** Each service's PG schema is versioned in its module
(`src/main/resources/db/migration-pg`); ETL runs through `docs/pgloader/*.load`
pairs + `scripts/migrate-monolith-data.sh`; row-count + sum parity is proven via
extended `scripts/reconcile.sh` per service; the monolith remains the system of
record through the cutover ladder (shadow → canary → full → 30-day fallback).
No table is destroyed — rename/dual-read/drop per Gate 4.
**Rejected.** Big-bang cutover (no fallback); direct ORM dual-writes during
migration (divergence without reconciliation); copying tables without pgloader
mapping files (unauditable).
**Revisit if.** A table proves unmappable (type conflicts) → that table gets its
own transformation-ADR before any load.

## A10 — Redis pub/sub: cache eviction only — plus per-route edge kill switch

**Decision.** Redis pub/sub (`NotificationRedisSubscriber`, `*Subscriber`
pattern, and `live:events`) is *cache-invalidation and local-eviction* only —
never business state, never cross-service commands. Cross-service *events* flow
over Kafka Redpanda with outbox + DLQ. The migration edge flag remains
admin-analytics' `feature_flags` DB (single source of truth, same pattern as rate
limits); W0 adds a gateway-side read of it (flag client in platform-lib) with a
Redis TTL 30 s cache, consulted **before** W2 traffic flip. Rollout percentages
compute identically edge and service (`FeatureFlagHash`).
**Rejected.** Redis pub/sub as an event backbone (no persistence, at-most-once
subscribers); LaunchDarkly-class vendored flags (network SPOF, cost);
per-service DB reads for routing (N copies of a global decision).
**Revisit if.** Kill-switch visibility lags >30 s in drills → drop TTL; or a
second app tier joins → reconsider vendor.

## A11 — Sync protocol: HTTP + Istio mTLS (ratifies execution-plan P0)

**Decision.** Synchronous service→service calls are plain HTTP/1.1 via Spring
`RestTemplate`/`RestClient` behind Istio mTLS (`PeerAuthentication` STRICT +
`AuthorizationPolicy`): caller-side timeouts (connect 1 s; read per op class),
resilience4j retries (0 for GET, 1 idempotency-guarded retry for POST), W3C
`traceparent` propagation, 500 ms total fan-out deadline budget. The gRPC
property in `services/pom.xml` is removed; the monolith `grpc` package is
deletion-track W4.
**Rejected.** Adopting gRPC mid-migration (zero `.proto` exist; buf/codegen
tooling + deadline semantics add no proven value at current hop counts — reopen
only if a measured latency need appears, per execution-plan P0).
**Revisit if.** Any fan-out path exceeds its 500 ms budget in load tests with
HTTP correctly configured → prototype gRPC (streaming or binary) before further
budget pressure; document the prototype result here.
