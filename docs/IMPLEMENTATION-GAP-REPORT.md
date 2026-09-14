# Bhukkad — Implementation Gap Report: 1M+ TPS / 1 000 Orders/sec

**Generated:** 2026-09-14
**Scope:** Current-state gap analysis for production-ready heavy traffic on Bhukkad backend.
**Audience:** Engineers prioritizing remaining work; each gap includes Priority, Evidence, Impact, Fix, and Test.

---

## Executive Summary

As of 2026-09-14, the following major infrastructure and correctness items are **fully implemented** and **removed from this gap report**:

| Category | Item | Status |
|----------|------|--------|
| **Observability** | Outbox wake channel enabled in all 14 services | ✅ Done |
| **Observability** | Tracing endpoint configured (Tempo/Jaeger) in all prod overlays | ✅ Done |
| **Gateway** | HPA 10→50 replicas with CPU/memory metrics | ✅ Done |
| **Gateway** | Connection pool: 2000 max-connections, 8s acquire-timeout | ✅ Done |
| **Gateway** | Rate-limit buckets: login, order-create, customer-wallet, payment, notification, webhook | ✅ Done |
| **Gateway** | L7 LoadBalancer service with externalTrafficPolicy: Local | ✅ Done |
| **Gateway** | Topology spread constraints + zone labels | ✅ Done |
| **Redis** | Cluster StatefulSet: 6 replicas (3 masters + 3 replicas) | ✅ Done |
| **Redis** | Lettuce pool: max-active=64, min-idle=8, max-idle=32, max-wait=5s (all services) | ✅ Done |
| **Kafka** | Topic partitions expanded: 24/12 partitions per topic | ✅ Done |
| **Kafka** | Consumer concurrency: listener-concurrency=3 in all services | ✅ Done |
| **Kafka** | Parallel outbox relay: relayThreads=4 with partition_id-based polling | ✅ Done |
| **Order Path** | Async saga enabled in prod overlay (202 Accepted + outbox-driven) | ✅ Done |
| **Order Path** | Sequence CACHE 100 (V13 migration) | ✅ Done |
| **Order Path** | Outbox partition_id column + idx_outbox_partition index (V12) | ✅ Done |
| **Order Path** | Dual-write trigger + shard schemas/tables (V14/V15) | ✅ Done |
| **DB** | Missing indexes added: Order(deliveryAgentId), OrderItem(orderId,menuItemId), Cart(customerId,status), RefreshToken(customerId,expiresAt), LoyaltyPointsLedger(customerId,createdAt), OrderInvoice(orderId) | ✅ Done |
| **DB** | Pagination on order listing endpoints (customer, restaurant, delivery, admin) | ✅ Done |
| **DB** | N+1 fix in AdminOrderInternalController via batch item loading | ✅ Done |
| **Config** | Response compression enabled in all services | ✅ Done |
| **Config** | Request size limits: max-swallow-size=2MB, max-request-size=10MB | ✅ Done |
| **Config** | HikariCP metrics-tracker-class for Prometheus observability | ✅ Done |
| **Config** | Kafka RELEASE_RPC_TIMEOUT reduced from 20s to 5s | ✅ Done |
| **Tests** | Python API test suite: 342 tests covering auth, edge cases, URL encoding | ✅ Done |
| **Tests** | Java controller security tests: OrderAdjunct, PromotionEvaluate, ExperimentAdmin, CityRegistry, InventoryAlert | ✅ Done |

---

## Remaining Gaps (Detailed Analysis)

### P0 — Critical Data-Correctness Gaps (fix before first heavy-traffic launch)

#### C1. `ShardAspect` — session `search_path` leaks across pooled connections
**Evidence:** `platform-lib/src/main/java/com/bhukkad/common/datasource/ShardAspect.java:62-71`
registers `TransactionSynchronization.beforeCommit` to execute `SET search_path TO shard_N, public`.
`k8s/pgbouncer/configmap.yaml:33` uses `pool_mode = transaction` with no `server_reset_query`.
**Impact at heavy traffic:** Under PgBouncer transaction pooling, consecutive transactions may land on
different server connections. The `SET search_path` executed at `beforeCommit` runs *after* all
statements in the transaction have already executed on the inherited schema. JPA flush also happens
in beforeCommit — ordering between this sync and the JPA flush is undefined. The hot write path is
therefore *guaranteed* to use the wrong schema. At 50 pods × Hikari pool 30 = 1500 physical
connections, the leak rate scales with pool churn.
**Fix (preferred):**
```java
// Replace ShardAspect with per-shard routing datasource
// platform-lib/src/main/java/com/bhukkad/common/datasource/ShardRoutingDataSource.java
public class ShardRoutingDataSource extends AbstractRoutingDataSource {
    @Override protected Object determineCurrentLookupKey() {
        return ShardRouter.shardFor(SecurityUtils.currentUserIdOrNull());
    }
}
// pgbouncer configmap.yaml — add as second line of defense
[pgbouncer]
server_reset_query_always = DISCARD ALL
```
**Alternative (if shard-pool redesign is phased):** use `SET LOCAL search_path` *inside the current
transaction* on the tx-bound connection (not at `beforeCommit`), plus the
`server_reset_query_always` above.
**Test:** integration test with pool size 1, 2 concurrent customers → interleave
`findByCustomerId(A)` / `findByCustomerId(B)` thousands of times → assert zero cross-shard rows
via `pg_stat_activity` + `search_path` inspection.

#### C2. `@Shard(key=...)` is decorative — aspect keys off `args[0]`
**Evidence:** `common/datasource/Shard.java` javadoc: "used for audit/logging only";
`ShardAspect.applyShard:52-59` (`extractLong(args[0])`); `order/.../repository/OrderRepository.java:25-26`
`@Shard(key = "customerId") String`-first method `findByOrderNumber(String orderNumber)` → returns
null silently; default JpaRepository methods (`findById`, `save`, `countByRestaurantId`,
`countByStatus`) have no `@Shard` at all and run schema-unscoped.
**Impact:** order lookups by orderNumber silently miss/return wrong rows; under load the error rate
scales with pool reuse — the classic heisenbug that only shows at production concurrency.
**Fix:**
```java
// ShardAspect.java — resolve by parameter name, not position
@Before("@within(org.springframework.data.jpa.repository.JpaRepository) && execution(@com.bhukkad.common.datasource.Shard * *(..))")
public void applyShard(JoinPoint jp) throws SQLException {
    Method method = ((MethodSignature) jp.getSignature()).getMethod();
    Shard shard = AnnotationUtils.findAnnotation(method, Shard.class);
    if (shard == null) return;

    // Resolve by declared parameter name, not position
    Long userId = resolveUserId(jp, method, shard.key());
    if (userId == null) {
        throw new IllegalStateException("Cannot resolve shard key '" + shard.key()
            + "' for " + method.getName() + " — annotate the parameter with @Param");
    }
    // ... apply immediately (see C3)
}
```
**Test:** unit test `ShardAspectTest` with `@Shard(key="customerId")` on a `String`-first method:
assert it throws rather than silently skipping.

#### C3. Sharded DML inside transactions sets schema *at commit time*
**Evidence:** `ShardAspect.applyShard:62-68` registers `TransactionSynchronization.beforeCommit`.
**Impact:** every statement in that transaction already executed on the wrong schema before the `SET`
runs; JPA flush also happens in beforeCommit — ordering between this sync and the JPA flush is
undefined. The hot write path is therefore *guaranteed* to use the inherited schema.
**Fix:** drop the `beforeCommit` hook; apply immediately on the tx-bound connection:
```java
if (TransactionSynchronizationManager.isSynchronizationActive()) {
    // Already bound — set now, not at commit
    Connection connection = DataSourceUtils.getConnection(dataSource);
    applySchema(schema, dataSource); // uses the same tx-bound connection
} else {
    applySchema(schema, dataSource);
}
```

#### C4. CI guard for HTTP-client discipline is broken open
**Evidence:** `scripts/ci/check-http-clients.py` exits 2 on this tree:
`services/supportticket/src/main/java/com/bhukkad/support/wallet-old.bak/WalletCreditClient.java`
(Permission denied). Allowlist entry `services/delivery/.../delivery/OsrmHttpConfig.java` is stale
(file moved to `delivery/config/`). Detector regexes miss `RestClient.builder()` entirely.
**Impact:** the only automated enforcement against hand-built HTTP clients (hidden timeouts, retries)
is silently not running; regressions land exactly where heavy traffic bites.
**Fix:**
```bash
# 1. Remove the .bak directory from src/main/java
rm -rf services/supportticket/src/main/java/com/bhukkad/support/wallet-old.bak
# 2. Update allowlist path
sed -i 's|delivery/OsrmHttpConfig|delivery/config/OsrmHttpConfig|' scripts/ci/webclient-allowlist.txt
# 3. Add RestClient.builder() to detector regex in check-http-clients.py
# 4. Make the guard a hard CI required check (not just a script)
```
**Test:** `python3 scripts/ci/check-http-clients.py` must exit 0 on clean tree.

---

### P1 — High-Stability Gaps (fix during scale-out, before 5× peak load)

#### H2. Platform HTTP client factory missing — hidden timeouts/retries
**Evidence:** `services/search/.../SearchSourceClient.java` uses raw `RestClient.builder()` with no
configured timeouts; `delivery/.../config/OsrmHttpConfig.java:38-42` uses
`HttpClients.custom()` without `disableAutomaticRetries()` — HTTP 5xx responses are silently
retried by the Apache HC5 default retry handler.
**Impact at heavy traffic:** a 503 from restaurant/OSRM is amplified by 2× via client retry
spirals; search sweep threads hang indefinitely on slow restaurant responses. Under 1M TPS, a
single slow upstream cascades into thread-pool exhaustion.
**Fix (platform-lib — single fix, all services benefit):**
```java
// services/platform-lib/src/main/java/com/bhukkad/common/web/client/PlatformHttpClientFactory.java
@Bean
public RestClient.Builder platformRestClientBuilder(
        @Value("${app.http.connect-timeout-ms:2000}") long connectTimeout,
        @Value("${app.http.read-timeout-ms:8000}") long readTimeout) {
    return RestClient.builder()
            .requestFactory(new JdkClientHttpRequestFactory(
                HttpClient.newHttpClient()
                    .connectTimeout(Duration.ofSeconds(connectTimeout / 1000))))
            .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
}
```
**Fix (OSRM — immediate):**
```java
// delivery/.../config/OsrmHttpConfig.java
CloseableHttpClient httpClient = HttpClients.custom()
        .setConnectionManager(connectionManager)
        .setDefaultRequestConfig(requestConfig)
        .evictIdleConnections(Timeout.ofSeconds(30))
        .disableAutomaticRetries()   // <-- add this
        .build();
```
**Test:** synthetic 503 on restaurant-service → verify search/OSRM clients do not double request
volume (count upstream hits); verify `RestClient.builder()` clients surface timeout exceptions
within configured budget, never hang.

#### H3. `SearchSourceClient` N+1 fan-out per sweep tick
**Evidence:** `SearchSourceClient` javadoc + implementation: one `/restaurants/public` page then
**one menu snapshot GET per restaurant**, serial, per batch tick.
**Impact:** sweep latency = `pageSize × restaurantServiceP99`. At 100 restaurants × 300 ms = 30 s
batch cycles — index freshness silently lags, loop can overtake its own tick, and backpressure
behavior is unknown.
**Fix (two parts):**
```java
// 1. Source-side bulk endpoint (restaurant-service)
@GetMapping("/api/v1/restaurants/public/menus")
public Map<Long, MenuSnapshot> menus(@RequestParam List<Long> restaurantIds) { ... }

// 2. Bounded-parallel fan-out in SearchSourceClient (if bulk endpoint phased)
public List<SourceMenu> menusBounded(List<Long> ids, int concurrency) {
    try (var executor = Executors.newFixedThreadPool(concurrency)) {
        return ids.stream()
            .map(id -> executor.submit(() -> menu(id)))
            .toList().stream()
            .map(CompletableFuture::join)
            .toList();
    }
}
```
**Test:** sweep 100 restaurants with 300 ms artificial delay on menu endpoint → measure sweep wall
time; assert < tick period; assert in-flight never exceeds concurrency cap.

#### H4. Cache-invalidation namespace collision with security keys
**Evidence:** `common/cache/CacheInvalidationSubscriber.onMessage:69-71` — every event expands
under the **same `bhukkad:` prefix** used by security state (`bhukkad:auth:lockout:*`,
`bhukkad:ratelimit:*`). A broad pattern event (e.g. `auth:` or a prefix typo) executes
`SCAN` + delete over the entire security keyspace cluster-wide — wiping active login locks /
rate-limit budgets. Additionally each pattern event calls `localCacheService.clearAll()` (every pod
drops L1 → brief DB/Redis spike at pattern-event bursts).
**Impact at heavy traffic:** one misconfigured cache mutation during a product launch invalidates
all locks → brute-force window opens cluster-wide. L1 clearAll at pattern-event bursts causes
DB thundering herd.
**Fix:**
```yaml
# application-prod.yml — segregate security keyspace
spring:
  data:
    redis:
      database: ${REDIS_SECURITY_DATABASE:1}   # separate logical DB for security state
```
```java
// CacheInvalidationSubscriber — add denylist
private static final Set<String> DENYLIST = Set.of(
    "bhukkad:auth:lockout", "bhukkad:ratelimit", "bhukkad:idempotency"
);
// in onMessage: if (DENYLIST.stream().anyMatch(fullKey::startsWith)) { log.warn(...); return; }
```
**Test:** publish a `CacheInvalidatedEvent(cacheName="auth", key="*", pattern=true)` → assert
`bhukkad:auth:lockout:*` keys still exist after subscriber runs.

#### H5. `LOGIN_RATE_LIMIT = 1000` per 5 min vs documented "30"
**Evidence:** `identity/.../IdentityController.java:108-119` — javadoc: "Conservative limits: 30
register/login attempts and 5 password-reset"; actual constants: `LOGIN_RATE_LIMIT=1000`,
`PASSWORD_RESET_RATE_LIMIT=5`, `REFRESH_RATE_LIMIT=100`, window `300s`.
**Impact:** login/register buckets are effectively no coarse pre-filter (~300 logins/s per caller
bucket). BCrypt cost ~100 ms per attempt → 1 000 × 5 min ≈ 3 CPU-core seconds per bucket per
window; sustained credential-stuffing across many buckets burns service CPU. The (email,IP) lockout
is the real guard but doesn't help a bot cycling many emails/IPs.
**Fix:** reconcile doc and code; either:
```java
// Option A: tighten bucket to match doc intent
private static final int LOGIN_RATE_LIMIT = 30;  // per 5 min per caller bucket
private static final int REGISTER_RATE_LIMIT = 30;
// Option B: leave 1000 and update the javadoc to explain lockout is the real guard
```
Add metric `auth_login_bucket_used` (counter on `@RateLimited` aspect) and alert when > 30 per
bucket per 5 min.
**Test:** 31 rapid logins from same IP within 5 min → 429 on the 31st; lockout still works
independently on the (email,IP) pair.

---

### P2 — Medium Resilience Gaps (fix during hardening phase)

#### M1. PgBouncer `server_reset_query` missing + pool budget unguarded
**Evidence:** `k8s/pgbouncer/configmap.yaml` — no `server_reset_query`; `default_pool_size=150`.
Parallel session's `scripts/ci/pool-budget-check.py` edits are uncommitted.
**Impact:** without `DISCARD ALL` on release, session state (like the shard `SET` in C1) leaks
between server connections; pool budget math is not enforced in CI → Hikari pool sizes can silently
outgrow pgbouncer capacity.
**Fix:** add `server_reset_query_always = DISCARD ALL` to pgbouncer config; promote
`pool-budget-check.py` to a hard CI required check; document per-service budget targets in this
file's Phase 5 section.

#### M2. Redis availability coupling — crash-loop on Redis restart
**Evidence:** `AbstractIdentityPostgresTest.java:33-37` comment: "identity boots a Redis
cache-invalidation listener that fails the context without a live Redis"; production inherits the
same listener bean.
**Impact at heavy traffic:** Redis maintenance window or failover → all identity pods crash-loop
simultaneously → full auth outage; even if Lettuce reconnects, verify the listener resubscribes
after a blip (stale-L1 across pods = silent wrong reads until next invalidation).
**Fix:**
```java
// Startup: degraded-mode boot — listener retries with backoff, health reports DOWN,
// but the service keeps serving reads from L1 + DB (short TTL fallback).
@Bean
public RedisMessageListenerContainer container(RedisConnectionFactory factory,
        CacheInvalidationSubscriber subscriber) {
    var container = new RedisMessageListenerContainer();
    container.setConnectionFactory(factory);
    container.addMessageListener(subscriber, new PatternTopic("bhukkad:cache:invalidation"));
    container.setErrorHandler(t -> log.error("REDIS_LISTENER_ERROR", t));
    return container;
}
```
**Test:** chaos drill: `kubectl delete pod redis-*` → verify identity pods stay running, health goes
DOWN, come back UP within 10 s of Redis recovery.

#### M3. Test Redis DB index bleed
**Evidence:** `AbstractIdentityPostgresTest` uses static Testcontainers Redis; all identity test
classes share DB **0**. This session's lockout debug noise partly came from shared limiter buckets
across test classes.
**Fix:** add `@DynamicPropertySource` override for `spring.data.redis.database` per test class (or
per-method via `@TestPropertySource`); use unique key prefix per class.

#### M4. Config validation gap — duplicate YAML keys only surface at boot
**Evidence:** this session's concurrent uncommitted edit added a second top-level `spring:` key to
`identity/application.yml`; SnakeYAML throws `DuplicateKeyException` only at context start.
**Impact:** a config typo in the wrong profile only surfaces in staging/prod at 3 AM.
**Fix:** add CI step:
```bash
# scripts/ci/validate-yaml.py
for f in services/*/src/main/resources/application*.yml; do
  python3 -c "import yaml,sys; yaml.safe_load(open('$f'))" || exit 1
  # duplicate-key check
done
```

#### M5. Readiness flaps fleet on Redis micro-hiccups
**Evidence:** `identity/application.yml` `include: readinessState` + custom readiness bean depends
on Redis.
**Impact at heavy traffic:** 200 ms Redis blip → readiness flips → HPA scales down → scale back up
→ request routing churn (pod restarts mid-flight).
**Fix:** add a 5–10 s failure threshold to the readiness group before reporting NOT READY.

#### M6. Dead code + untested prod-only aspect
**Evidence:** `UserTierResolver` has zero production callers; `ShardAspect` is `@Profile("prod")`
with **zero tests** (verified via `grep -rln ShardAspect src/test`).
**Fix:** delete or wire `UserTierResolver`; add `ShardAspectTest` per C1.

#### L1. Observability gaps before load test
**Evidence:** no Micrometer binders for Hikari pending-threads, lettuce pool acquire time, OSRM/mesh
client queue times.
**Impact at heavy traffic:** you cannot diagnose pool exhaustion or upstream brownouts without these
histograms during chaos drills.
**Fix:**
```java
// services/*/src/main/java/com/bhukkad/common/observability/MetricsConfig.java
@Bean
public HikariCPMetrics hikariMetrics(HikariDataSource ds) {
    return new HikariCPMetrics(ds, "hikaricp");
}
// lettuce command latency — already exposed via spring-boot-starter-data-redis;
// add explicit pool metrics:
@Bean
public LettucePoolMetrics lettuceMetrics(GenericApplicationContext ctx) {
    return new LettucePoolMetrics(ctx.getBean(GenericApplicationContext.class));
}
```

#### L3. HTTP request header size 8 KB tight for future claims
**Evidence:** `identity/application.yml:13` `max-http-request-header-size: 8KB`.
Bearer JWTs here are ~1.5 KB today; future claims growth + cookies can exceed 8 KB.
**Fix:** `max-http-request-header-size: 16KB`; measure real p99 header size in load test.

---

### P3 — Optimization & 100% Production Readiness (finish the last 10%)

These are the items that separate "passes load test once" from "operates at 1M TPS for 6 months
without firefighting."

#### O1. JWT validation latency under load
**Evidence:** `PlatformJwtValidator` is stale-while-revalidate, but verify per-request cost:
RS256 verify on hot path with a 2 048-bit key ≈ 1–2 ms per token. At 1M TPS ≈ 1–2 CPU cores.
**Fix:** pre-warm JWKS on `ApplicationReadyEvent` (already done); add a `Caffeine` L1 cache
around `JwkSet` retrieval with 30-second TTL so the *first* request per kid rotation doesn't
serialize on HTTP; verify the async refresh path is bounded by `refreshInProgress` future.

#### O2. Idempotency table write amplification
**Evidence:** `platform-lib/.../idempotency/IdempotencyRecord.java` — JPA entity, repository is
`JpaRepository`. Every idempotent POST hits this table → extra DB write per order/money operation.
**Impact at 1 000 orders/sec:** 1 000 extra INSERTs/sec into `idempotency_records` with a unique
index — measurable write amplification on the primary.
**Fix:** TTL-based eviction (`ON DELETE CASCADE` via DB job or `@SQLDelete`); partition by month;
or move to Redis SET with TTL (lighter, given the lockout infrastructure already exists).

#### O3. BCrypt CPU budget at login scale
**Evidence:** BCrypt default strength = 10 → ~100 ms on a single core. At `LOGIN_RATE_LIMIT=1000`
per 5 min / bucket × many buckets ≈ sustained CPU burn on credential-stuffing attacks.
**Fix (defense in depth):** reject obvious bots at the edge (reCAPTCHA/v3 on login after 3
failures); consider `ARGON2` if JVM supports it (faster on modern CPUs); offload to a sidecar or
delegate to the identity service's existing `PasswordEncoder` with configurable strength.

#### O4. Gateway reactor thread starvation guard
**Evidence:** `gateway/application-prod.yml` — Netty `worker-count: 0` (auto = CPU cores). With
`max-wait` now 5s across all services (H1 fixed), a Redis latency spike could still pin all
reactor workers waiting on Lettuce pool. Verify the gateway has a `BoundedElasticScheduler` for
blocking calls and that the `WebClient` pool (Netty) is sized separately from the event-loop count.
**Fix:** confirm `spring.codec.max-in-memory-size` is bounded; add `reactor.netty.ioWorkerCount`
explicit if auto-detection is wrong on the platform.

#### O5. Real-time SSE memory per connection
**Evidence:** `SseCapacityExceededException` exists, but confirm per-connection memory budget:
each SSE holds a `Flux<ServerSentEvent>` + backpressure buffer. At 10 k connections × 64 KB buffer
= 640 MB per pod.
**Fix:** set `spring.webflux.session.cookie.max-size` + per-connection buffer cap; verify with
`jmap -histo` during the 10k SSE load test.

#### O6. Kafka producer durability
**Evidence:** grep for `acks` in `application*.yml` returned empty.
**Impact:** default `acks=1` (leader only) → broker crash between write and fsync → silent event
loss during the outbox → Kafka path.
**Fix:**
```yaml
spring:
  kafka:
    producer:
      acks: all
      retries: 3
      max-block-ms: 5000
```

#### O7. Connection budget CI enforcement
**Evidence:** `scripts/ci/pool-budget-check.py` exists but is uncommitted / not a hard gate.
**Impact at scale:** a single service can silently exceed pgbouncer capacity and take down the
entire DB tier.
**Fix:** add to CI pipeline as a required check; fail merge if `Σ(hpa-max × DB_POOL_SIZE) > 0.8 ×
pgbouncer_pool` for any database.

#### O8. Load-test SLO enforcement in CI
**Evidence:** no k6/vegeta load test is wired into the CI pipeline today.
**Impact:** performance regressions land undetected between releases.
**Fix:** add a `k6 run --vus 50 --duration 30s` smoke test for the login + order-create paths to
CI; gate on p99 < SLO and error rate < 0.1 %.

---

## Implementation Order (Remaining Work)

| Priority | Item | Why first | Effort |
|---|---|---|---|
| P0 | C1, C2, C3 — ShardAspect redesign | Data-correctness; gates shard rollout | M (2–3 days) |
| P0 | C4 — Restore CI guard | Prevents new regressions while fixing P0 | S (hours) |
| P1 | H2 — Platform HTTP factory + OSRM retry fix | Prevents 503/429 amplification storms | M (1–2 days) |
| P1 | H4 — Cache namespace segregation | Prevents security-state wipe | S (hours) |
| P1 | H3 — Search sweep batching | Prevents index lag at peak | M (1 day) |
| P1 | H5 — Reconcile rate-limit constants | Closes documented abuse window | S (hours) |
| P2 | M1 — PgBouncer `server_reset_query` + CI budget gate | Complements C1 | S (hours) |
| P2 | M2 — Redis degraded-mode boot | Prevents crash-loop fleet | M (1–2 days) |
| P2 | M4 — YAML CI validation | Prevents next `DuplicateKeyException` | S (hours) |
| P2 | M6 — Add ShardAspectTest + ShardRouterTest | Zero test coverage on prod-only aspect | M (1 day) |
| P3 | O1–O8 — Optimization pass | Last 10 % to "operates at 1M TPS for 6 months" | L (1–2 weeks) |

**After all P0 + P1 items are implemented and load gates pass at 3–5× peak TPS with chaos
drills, the system is ready for production heavy traffic.** P2/P3 are the difference between "it
worked in the load test" and "it stayed up during the first real Diwali sale."

---

*End of gap report. This document reflects the current state as of 2026-09-14.*
