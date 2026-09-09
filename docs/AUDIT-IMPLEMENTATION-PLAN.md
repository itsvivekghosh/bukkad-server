# Bhukkad — Audit Implementation Plan (executed)

## Execution status (2026-09-09)

All five batches committed on their branches (based on `6797edc`) and integrated
onto **`audit/integration-verify`** (scratch worktree
`/var/folders/.../T/kilo/audit-integration`), verified there:

| Batch | Branch | State |
|---|---|---|
| PERF-0 | `perf-0-geometry` | merged; pool-budget guard exits 0 across all DBs |
| PERF-1 | `perf-1-resilience` | merged; breaker/retry/limiter/edge/JWKS/CORS/404 landed with StepVerifier tests |
| PERF-2 | `perf-2-events` | merged; two-phase relay, DLT, preflight, G-1, V-11/V-12/V-19/LogRedactor landed |
| PERF-3 | `perf-3-read-cache` | merged; single-flight cache, feed/menu/batch-endpoint, bounded scans, sweeper, indexes |
| PERF-4 | `perf-4-delivery-rt` | merged; delivery atomicity, SSE budgets, wallet @Version/CHECK, JVM contract |

Integration defects found & fixed on the integration branch (commit `f488145`):
1. Flyway `V8` collisions (PERF-2 outbox migration vs PERF-3/4 own V8s) →
   renumbered to V9 in order/payment/delivery/search.
2. `GatewayConfigTest` still asserted the pre-PERF-0 30 s response-timeout → now 8 s.
3. PERF-0 `minimum-idle: 20` × multiple cached Spring contexts exhausted the
   shared Testcontainers PG (max_connections 100) → all seven
   `Abstract*PostgresTest` harnesses cap the test pool (max 8 / min-idle 2).

**Gate: full 17-module reactor `mvn test` BUILD SUCCESS on the merged tree.**
Main branch `migration/microservices-postgresql` is intentionally untouched —
fast-forward it from the integration branch when ready.

---

# Original plan (reference)

**Generated 2026-09-09 from the consolidated program in
`docs/PERFORMANCE-AUDIT-IMPLEMENTATION-GUIDE.md` (v1.1),
`docs/PRODUCTION-READINESS-AUDIT-GUIDE.md` (v6.0) and
`docs/TECHNICAL-AUDIT-FEATURE-ROADMAP.md` (v2.0), triaged against HEAD `aba0b4e`.**
This is a *re-cross-reference*: every open item was re-verified in the working
tree before listing. Doc §10 (perf guide) triage holds — money integrity
(V-01/V-02 locks, saga basics, search bounding, refresh rotation, SSE bridge, mesh
tokens) is closed; what remains is the throughput/resilience substrate + hardening.

## Status triage (verified this pass)

| Open (implemented by batches below) | Evidence re-checked now |
|---|---|
| B1/V-09 relay self-invoked tx, recoverStale unscheduled, no maxRetries, B2 disabled-publisher blackhole, P-03 knobs, no DLT error handler | `OutboxPollPublisher.java:52,133`, `KafkaPlatformEventPublisher.java:80-82` |
| V-16/V-18/B5/B6/B7/V-14/V-13/V-03/V-15 (breaker never decorates; INCR+EXPIRE; eager subjectId; wildcard CORS; synchronized JWKS) | `CircuitBreakerFilter`, `RedisRateLimitService:33`, `EdgeKillSwitchFilter:57`, `GatewayCorsConfig:35`, `PlatformJwtValidator:168` |
| B3/B4/V-12/RedisCacheService sleep/OrderEventPublisher swallow | `FeedController:63-72`, `AdminCqrsEventConsumer:55-64`, `RedisCacheService:407`, `OrderEventPublisher` catch |
| B10 delivery check-then-act + no UNIQUE(order_id); SSE O(N)/leak/budgets | `DeliveryService:24-35` |
| P-01…P-04 zero config geometry in any service yml | grep: no `task.scheduling`, `connection-timeout`, `batch_size` anywhere |
| Half-open: RetryFilter backoff fixed but still retries any RuntimeException incl. POST | `RetryFilter.java` |

Explicitly deferred per perf guide §3.7 (evidence-gated, not first-queue):
service mesh, gRPC, Avro/schema-registry (roadmap §VI), platform-lib module split
(R-A/W7), dispute-ownership ADR (R-04), CQRS read-model migration, k8s single-tree
consolidation (R-01), PITR/backup (#15), full observability stack bring-up (#14
infra), secrets history purge (#17 — operational, needs maint window).

## Parallel batches (disjoint file ownership; merge order = P0→P1→P2→P3→P4)

### BATCH-0 · PERF-0 — Geometry & hygiene (config-only, 17 yml + k8s + CI guard)
Canonical block in every `services/*/application.yml`:
tomcat 100/200 · `spring.task.scheduling.pool.size=${SCHEDULER_POOL_SIZE:12}` ·
Hikari `${DB_POOL_SIZE:20}`/minIdle 20/conn-timeout 3000/keepalive/max-lifetime ·
Hibernate `jdbc.batch_size=50, order_inserts/order_updates/jdbc.batch_versioned_data, query_plan_cache_size=200` ·
`spring.data.redis.timeout=2000/connect-timeout=1000` — per §6 PERF-0.
pgbouncer configmap per-DB pools (§4.2), gateway `response-timeout: 30s→8s`,
CI arithmetic guard script (§4.2 rule `Σ(hpa×pool) ≤ 0.8×ceiling`).
Owns: all `application*.yml` values, `k8s/pgbouncer/`, `scripts/ci/pool-budget.*`, CI workflow step.

### BATCH-1 · PERF-1 — Re-arm safety devices (platform-lib + gateway Java)
`CircuitBreakerFilter` → `transformDeferred(CircuitBreakerOperator.of(registry breaker))`
+ per-target registry + state gauge; `RetryFilter` → transient/5xx/timeout predicate
**and idempotent methods only**; `RedisRateLimitService` → single atomic Lua
(INCR+PEXPIRE+limit), fail-open w/ counter, real `authSubject|IP` resolvers, prod
startup guard; `EdgeKillSwitchFilter` → `Mono.fromCallable(...).subscribeOn(boundedElastic)`;
`PlatformJwtValidator` → stale-while-refresh async cache (AtomicBoolean single-flight,
30s failure backoff, pre-warm) + build-key-once + ≥32B fail-fast (V-03+V-15);
`GatewayCorsConfig` → env origins + prod-wildcard fail-fast (V-14);
`GatewayConfig` → route-mounted Redis rate limiter + observable 404 `unmatched`
route w/ normalized-path counter (V-18/G, V-20). StepVerifier tests per §6 PERF-1.
Owns: `platform-lib/*/web/client/*`, `ratelimit/*`, `security/PlatformJwtValidator*`, `gateway/**.java`. No yml edits.

### BATCH-2 · PERF-2 — Event backbone durability
Two-phase relay (TransactionTemplate claim-tx → publish outside tx on isolated
`relayScheduler` → batched state-tx via `@Modifying UPDATE … WHERE id IN`);
schedule `recoverStale` at processingTimeout; `retryCount≥maxRetries` →
`dead_letter_events` (+`next_attempt_at` backoff additive migration) + metric;
B2 blackhole fix + single `ExternalEventsProperties` gate + prod boot preflight
refusing disabled events; Kafka producer knobs on the one surviving factory
(acks=all, idempotence, linger 5, batch 32K, lz4) + delete duplicate legacy wiring;
`DefaultErrorHandler`+`DeadLetterPublishingRecoverer` → `<topic>.dlt`; consumers
stop swallowing (`AdminCqrsEventConsumer` catch, `NotificationEventConsumer` inline
SMTP/Twilio → bounded `AbortPolicy` executor → DLT on reject (P-07) + eventId dedupe
(scope KAFKA_CONSUME) + `LogRedactor` for phone/email (V-21/V-22 → PoisonEvent);
`AdminCqrsEventConsumer.upsertStat` → native `ON CONFLICT DO UPDATE` + eventId claim
(V-12); `OrderEventPublisher.enqueue` swallow removed + G-1 guard in
`OutboxClient.enqueue` (audit all existing callers); V-11 webhook single-tx in payment +
V-19 platform-lib `IdempotencyCleanupScheduler` (identity deletes its copy).
Owns: `platform-lib/outbox/*`, `kafka/*`, `idempotency/*`, `config/preflight/*(new)`,
`order/service/OrderEventPublisher`, `payment` webhook files, `admin-analytics` consumer,
`notification` consumers/senders, `identity` cleanup class. No shared yml edits (reads props).

### BATCH-3 · PERF-3 — Hot read paths & cache adoption
`RedisCacheService.waitForValue` → per-key `CompletableFuture` single-flight
(cap 250ms → fall-through), no `Thread.sleep` parking + stampede test;
`FeedController` → `getOrCompute("feed:v1", 45s)` + digest stored inside cached
projection (304 path preserved) + `CacheInvalidationService` on restaurant mutation;
restaurant menu snapshot cached server-side + invalidation on mutations +
new `GET /api/v1/menu/items?ids=` batch endpoint (cap 100, per-id cache 60s) +
`order` mirror cache in `RestaurantPricedItemResolver` (Caffeine max 10k replaces
`clear()` hack, `resolveAll` one call) + `LegacyOrderCompatController` uses snapshot
`restaurantId` — 10-item cart ⇒ ≤1 S2S RTT (B3); cart sweeper wired (@Scheduled+ShedLock,
SQL-predicated batch 500 expire/delete + `(status,updated_at)` index migration) (V-carts);
bounded scans: `AccountProfileService` existsBy, disputes/affiliates/admin lists →
paged (cap 200, SQL ORDER BY); `AutocompleteService` startup build + periodic/incremental
refresh; search expression-index migration (`varchar_pattern_ops`) (§4.3/§6 PERF-3).
Owns: `platform-lib/cache/RedisCacheService`, `restaurant/**` (except TrieIndex), `order/api/*`
resolver/legacy/`RestaurantClient`, `order/service/CartRecoveryService`, `identity/service/AccountProfileService`,
`supportticket/api`, `referral/serviceImpl/AffiliateServiceImpl`, `admin-analytics/api` lists, `search` migrations.

### BATCH-4 · PERF-4 — Delivery/realtime atomicity + money hardening + JVM contract
`assign()` → conditional/INSERT-ON CONFLICT DO NOTHING under new
`UNIQUE(order_id)` additive migration (dup-sweep SQL inside migration guard);
`markDelivered` → conditional UPDATE idempotent (exactly-one `OrderDelivered`),
2-thread concurrency tests (B10); rider ping retention purge (>72h batched);
zone cache via `LocalCacheService.getOrCompute` TTL 300s; SSE: emitter→key reverse
index O(1) removal, per-channel/global budgets from `LiveProperties` surfaced as
`sse_connections`/`sse_capacity_rejected_total`, `AbortPolicy` send executor;
`RedisOrderLiveRelay.localConsumers` removal on stream close + capacity metrics;
`WalletBalance` `@Version` + `CHECK(balance>=0)` + `UNIQUE(customer_id)` additive
migration (dup-sweep) — keep existing FOR UPDATE path (V-01 finish);
k8s: mem requests==limits + no CPU limit + canonical `JAVA_OPTS` (§4.1) + gateway
3 replicas/PDB (P-09); dead-code: `DisputeResolutionServiceImpl.java.bak` delete +
TrieIndex dedup in restaurant (keep the tested copy); P-05 clients → platform `WebClientConfig.Builder`
(rest of clients lands in PERF-5 later).
Owns: `delivery/**`, `realtime/**`, `payment/domain/WalletBalance` + payment migrations,
`k8s/**/deployment.yaml` resources/JAVA_OPTS, `platform-lib/util/LogRedactor` NO (BATCH-2 owns it).

## Sequencing / merge spine
`BATCH-0 → BATCH-1 → BATCH-2` prerequisite for any scale claim; BATCH-3/4 merge after
BATCH-2 only where files touch (none planned — merges are file-disjoint by construction;
Flyway V-number collisions resolve at merge in favour of the earlier batch).

## Gates (standing, every batch)
`./mvnw -f services/pom.xml -pl <touched> -am test` green · finding's own verification
test present (race/StepVerifier/single-winner) · API contracts frozen · additive-only
migrations · no new runtime deps · guardrail + metric per class (G-1…G-17 as in-scope).
