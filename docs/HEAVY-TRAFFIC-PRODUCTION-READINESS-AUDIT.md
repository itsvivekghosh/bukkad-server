# Heavy-Traffic Production Readiness Audit

Date: 2026-09-14
System under review: bhukkad microservice monorepo (12 services + gateway + platform-lib)
Method: static review of hot-path components (datasource/sharding, Redis client + cache, HTTP clients, edge/gateway, messaging, auth chain), executed tests, and measured JaCoCo output. Evidence cited file:line against this revision.
Companion docs: `PRODUCTION-READINESS-AUDIT-GUIDE.md`, `PERFORMANCE-AUDIT-IMPLEMENTATION-GUIDE.md`, `TECHNICAL-AUDIT-FEATURE-ROADMAP.md`, `IMPLEMENTATION-GAP-REPORT.md`.
Note: several k8s/HPA/connection-budget files carry in-flight edits from a parallel tuning session (uncommitted) — cross-check before fixing overlaps.

---

## 0. Verdict (read this first)

**After fixing the items here, is the code ready for production heavy traffic?**
No — fixing the CRITICAL items is **necessary but not sufficient**. This audit shows where the system *will* fail (or worse: silently misroute data) under load. Proof of readiness only comes from the load gates in §5 passing at your target scale and surviving the chaos drills. The system already has substantial hardening (§1); the three CRITICAL shard-connection findings (§2) are data-correctness bugs, not just performance issues, and must gate any shard rollout and any heavy-traffic launch.

---

## 1. Verified healthy (evidence reviewed — do not re-open without cause)

| Area | Evidence | Why it holds under load |
|---|---|---|
| Cache stampede protection | `platform-lib/.../cache/RedisCacheService.java` L1+L2, in-JVM singleflight with 250 ms join cap, Redis SETNX compute lock (token-safe Lua release), 10 % TTL jitter, probabilistic early refresh | Cold-key p99 cliff removed; no thundering herd per expiry; cross-pod coalescing |
| Outbox relay | `common/outbox/OutboxPollPublisher.java` | `FOR UPDATE SKIP LOCKED` inside a real claim tx (two-replica duplicate-claim fixed); publish outside tx; retry ceiling + max-retries quarantine |
| Kafka consumers | `common/kafka/KafkaPlatformConfig.java` | MANUAL_IMMEDIATE acks, error handler + DLT routing for poison payloads (no consumer-thread death loops) |
| Login lockout | `identity/.../LoginLockoutService.java` + 100 %-covered tests | One atomic EVAL (no INCR/SET race, no TTL-less keys), fail-**open** with a *counted* `auth_lockout_active{outcome=failopen}` metric (alertable), email/IP normalization, IPv6-safe keys |
| JWKS handling | `common/security/PlatformJwtValidator.java` | Stale-while-revalidate cache, pre-warmed on ApplicationReady, single-flight async refresh, unknown-`kid` rotation path; **verify() never blocks on HTTP once warm** |
| Edge rate limiting | `gateway/.../EdgeRateLimitFilter.java`, Redis bucket per caller | Fast rejection before services see load |
| Logging hot path | `common/logging/LoggingAspect.java` | All ENTER/EXIT JSON serialization gated behind `debugMode`; only slow-WARN/ERROR paths pay cost in prod; PII regexes are pre-compiled statics |
| Redis key scanning | `RedisCacheService`/`CacheInvalidationSubscriber` | Bounded `SCAN`, never `KEYS` (no blocking single-threaded Redis under pattern deletes) |
| SSE capacity | `realtime/.../RealtimeExceptionHandler.java`, `SseCapacityExceededException` | Rejection + metrics when capacity exceeded (not silent drop) |
| Dev-only bootstrap | `identity/.../config/DevAdminBootstrap.java` | `@ConditionalOnProperty(...enabled=true)` — inert in prod unless explicitly turned on |
| JVM heap | `k8s/identity/deployment.yaml` | `-XX:MaxRAMPercentage=75.0` pinned; leaves 25 % headroom for metaspace/native |
| HPA baseline | `k8s/identity/hpa.yaml` | CPU 65 % + memory + custom Pods metrics; sane starting point for horizontal scaling |

---

## 2. CRITICAL findings (data correctness under load — gate the launch)

### C1. Shard schema `SET search_path` leaks across pooled connections
**Where:** `platform-lib/src/main/java/com/bhukkad/common/datasource/ShardAspect.java:74-88` (`applySchema`), `applyShard` L52-71.
**Evidence:** Aspect executes `SET search_path TO shard_N, public` (session-scope) on a **borrowed
Hikari connection** and releases it back (`finally { DataSourceUtils.releaseConnection(...) }` :87)
**without ever resetting** the search_path. `k8s/pgbouncer/configmap.yaml` has `pool_mode = transaction` (:33) and no `server_reset_query` configured.
**Impact at heavy traffic:**
- Next borrower of that pooled connection — potentially an *un*-sharded query, the admin service,
  or a different tenant — inherits `shard_N` as its default schema. Results: wrong-tenant reads,
  "relation does not exist", or silently reading the `public` mirror when both exist.
- Under PgBouncer transaction pooling, **session `SET` is undefined behavior**: consecutive
  transactions may land on different server connections; the setting may apply to some, none, or
  leak to another client's transaction sharing a server.
**Shipped state today:** `@Profile("prod")` (L33) with **zero tests** — this code has never
executed anywhere. The first prod traffic is its first execution.
**Fix (pick one, verify under PgBouncer):**
1. Preferred for correctness: route per-shard via `AbstractRoutingDataSource`/per-shard pools
   (no session state at all); or
2. Use `SET LOCAL search_path ...` executed **inside the current transaction** immediately on the
   tx-bound connection (not at `beforeCommit` — see C3), plus add
   `server_reset_query = DISCARD ALL` to pgbouncer as a second line of defense *and* a Hikari
   `connectionCustomizer`/`RESET search_path` on return.
**Test plan:** integration test with 2 concurrent customers mapped to different shards sharing one
connection (pool size 1, fixed `search_path=public` server default): interleave
`findByCustomerId(A)` / `findByCustomerId(B)` thousands of times; assert zero cross-shard reads.

### C2. `@Shard(key=...)` is decorative; aspect keys off argument position
**Where:** `common/datasource/Shard.java` (javadoc admits "used for audit/logging only"),
`ShardAspect.applyShard:52-59` (`args[0]` → `extractLong`),
vs `order/.../repository/OrderRepository.java:19,22,25,47,54,57`.
**Evidence:** `@Shard(key = "customerId")` on `findByOrderNumber(String orderNumber)` (:25-26)
→ `extractLong` returns null → aspect **returns silently**, query runs on whatever search_path
the connection holds (see C1). Conversely any method whose *first* argument happens to be a Long
(e.g. an `orderId`) would be treated as the shard key.
`findById`, `save`, `countByRestaurantId`, `countByStatus` — unannotated → also unscoped.
**Impact:** orders look "missing"/wrong per connection roulette; under load the error rate scales
with pool reuse — the classic heisenbug that only shows at production concurrency.
**Fix:** resolve the named parameter (`MethodSignature` + `@Param`/parameter names); throw on
unresolvable instead of silent return; annotate/document the JpaRepository default-method surface
(one bulk custom impl or per-query scoping) — no repository call may run schema-unscoped on sharded
tables.

### C3. Sharded DML inside transactions sets the schema *at commit time*
**Where:** `ShardAspect.applyShard:62-68` — when a transaction is active, the `SET` is registered in
`TransactionSynchronization.beforeCommit`.
**Evidence/impact:** every statement of that transaction has already executed *before* the `SET`
runs (JPA flush also happens in beforeCommit — ordering between this sync and the JPA flush is
undefined). Reads and writes in `@Transactional` service methods therefore run on the previous
inherited schema. This inverts the whole point of the aspect exactly on the hot write path.
**Fix:** bind the connection immediately (`DataSourceUtils.getConnection` inside the tx returns the
tx-bound connection) and `SET LOCAL` there; drop the beforeCommit hook.

### C4. CI guard for HTTP-client discipline is currently broken open
**Where:** `scripts/ci/check-http-clients.py` → exit code 2 on this tree:
`services/supportticket/src/main/java/com/bhukkad/support/wallet-old.bak/WalletCreditClient.java` (13
Permission denied). Also the allowlist entry
`services/delivery/.../delivery/OsrmHttpConfig.java` (file was moved to `delivery/config/`) is
stale, and the detector regexes miss `RestClient.builder()` entirely (see H2).
**Impact:** the one automated mechanism that keeps hand-built HTTP clients (with hidden or missing
timeouts/retries) out of the services is silently not enforcing; regressions will land unnoticed
exactly where heavy traffic bites.
**Fix:** delete the `.bak` directory from `src/main/java` (keep the archive outside compile roots);
update the allowlist path; add `RestClient.builder()` to the detector; restore CI.

---

## 3. HIGH findings (stability at high concurrency)

### H1. Unbounded Redis pool waits on the ingress path
**Where:** `services/gateway/src/main/resources/application-prod.yml:13` and
`services/admin-analytics/.../application-prod.yml:13`: `lettuce.pool.max-wait: -1`.
**Evidence/impact:** Lettuce pool exhaustion (Redis latency spike/failover under load) makes every
caller thread **wait forever** for a connection. On the gateway this converts a Redis hiccup into a
full ingress freeze (reactor worker starvation + request pile-up). `delivery` already uses
`max-wait: 5s`, proving the intended convention.
**Fix:** set `max-wait: 2-5s` everywhere; fail fast with 503 (counted); alert on
`lettuce_pool_exhausted` equivalents.

### H2. Service-to-service clients without timeouts (and outside the guard)
**Where:** `search/.../infrastructure/client/SearchSourceClient.java:28-33` —
`RestClient.builder().baseUrl(...).build()`; no connect/response timeout, default request factory
(HC5 auto-detected when on classpath → **retries 429/503 responses even for POST** — the exact
class of bug that masked the login-lockout test this session; see `identity` test javadoc
`LoginLockoutRedisIntegrationTest`).
Also OSRM: `delivery/.../config/OsrmHttpConfig.java:38-42` — `HttpClients.custom()` without
`disableAutomaticRetries()`/custom `setRetryStrategy`: HC5's default strategy retries `429/503`
GETs and IOExceptions, **doubling request load against OSRM exactly when OSRM is unhealthy**
(amplification spiral during the upstream brownout window; bounded by `maxRetries=1` only).
**Fix:** route all blocking mesh clients through one platform factory bean with explicit
connect/response/acquire timeouts and `disableAutomaticRetries()` (or an
`HttpRequestRetryStrategy` that never retries non-idempotent calls); extend the G-13 detector per C4.

### H3. Search sweep N+1 fan-out
**Where:** `SearchSourceClient` javadoc + implementation loop: one `/restaurants/public` page then
**one menu snapshot GET per restaurant**, serial, per batch tick.
**Impact:** sweep latency grows linearly with page size × restaurant-service p99; at 100 restaurants
× 300 ms = 30 s batch cycles, index freshness silently lags and the loop can overtake its own tick
(overlap/backpressure unknown without seeing the scheduler call site).
**Fix:** batch or (bounded, parallel) the menu fetches; source-side bulk endpoint
(`menu?restaurantIds=...`); cap in-flight with `ExecutorService` + per-call timeouts.

### H4. Cache-invalidation namespace can touch security keys
**Where:** `common/cache/CacheInvalidationSubscriber.onMessage:69-71` — every event key is expanded
under the **same `bhukkad:` prefix** used by non-cache security state
(`bhukkad:auth:lockout:*`, limiter buckets). A pattern event like `auth:` → `deletePattern`
(`SCAN` + delete) would wipe in-flight login locks / rate-limit budgets cluster-wide — a
*correctness* event on a *security* keyset. Additionally each pattern event executes
`localCacheService.clearAll()` — every pod drops its L1 → brief DB/Redis load spike (bounded but
real at pattern-event bursts).
**Fix:** segregate namespaces (`sec:*` or a separate logical Redis DB for security state), and/or
have the subscriber refuse patterns matching a denylist prefix (`bhukkad:auth:lockout`,
`bhukkad:ratelimit`).

### H5. `LOGIN_RATE_LIMIT = 1000` per 5 min vs documented "30"
**Where:** `identity/.../IdentityController.java:108-119` (javadoc says "Conservative limits: 30
register/login attempts and 5 password-reset"; constants: 1000/1000/5, refresh 100).
**Impact:** the login/register buckets are effectively no-abuse-brake as a coarse pre-filter: ~300
logins/s tolerated per caller bucket. The (email,IP) lockout is the real guard (per-pair), but
one bot cycling many emails against many IPs still spends DB + BCrypt CPU. At BCrypt cost ~100 ms
(≈1 CPU-second per attempt), a 1 000 × 5-min bucket ≈ 3 cores·s/5min per bucket — sustained
credential-stuffing across buckets burns the service's CPU budget.
**Fix:** reconcile: either lower (per IP without pairing) or gate document intent (login bucket
exists to shed gross abuse, lockout handles targeted). Add metric
`auth_login_bucket_used`, alert on > documented threshold; consider adaptive (IP-reputation) tiers.

---

## 4. MEDIUM / LOW findings

| # | Where | Issue | Fix/verify |
|---|---|---|---|
| M1 | PgBouncer (`k8s/pgbouncer/configmap.yaml`) | No `server_reset_query`; `default_pool_size=150` vs (sum services × Hikari pool) budget — parallel session's `scripts/ci/pool-budget-check.py` edits are **uncommitted** | Adopt `server_reset_query_always=DISCARD ALL`; make pool-budget-check a hard CI gate once the tuning lands; document per-service budgets in this file |
| M2 | Redis availability coupling | identity boot **fails without live Redis** (cache-invalidation listener) → Redis maintenance window = crash-loop fleet (startup storm). Lettuce reconnect default is on, but *verify* listener resubscribe after a blip (stale-L1 across pods = silent wrong reads) | Startup: degraded-mode boot (retry-listener + health DOWN, keep serving reads with L1-only TTL); chaos drill §5 proves recovery |
| M3 | `AbstractIdentityPostgresTest` | Static containers shared by all identity test classes; per-class random ports shared Redis DB **0** — cross-test key bleed possible (this session's debug was partly noise from that: shared limiter buckets) | Per-class DB index or unique key prefix; document the 30-bucket collision noted in lockout test |
| M4 | `application.yml` (identity) | A concurrently added duplicate top-level `spring:` key booted `SnakeYAML DuplicateKeyException` only at context start (this session). Config errors are invisible until boot | CI step: `spring-boot-properties-loader` smoke or plain `yaml.safe_load` + key-duplicate check across all `application*.yml` |
| M5 | Health/readiness semantics | Readiness includes redis/db (`include: readinessState` + custom) — verify readiness doesn't flap the fleet on Redis micro-hiccups (stormy scale-down/up cycles at high QPS) | Add a 5-10 s failure threshold on the readiness group (or circuit-weighted readiness) |
| M6 | `ShardAspect`, `UserTierResolver`, dev-only profiles | `UserTierResolver` has **no production caller** (dead code); `ShardAspect` untested, prod-only | Delete or wire resolver; test aspect per C1 |
| L1 | Observability gaps | No histogram for lettuce pool acquire time, Hikari pending-threads, OSRM/mesh client queue times | Add Micrometer binders (`HikariCPMetrics`, lettuce command latency) before the §5 runs — the audit relies on these gates |
| L2 | `k8s/*/hpa.yaml`, gateway deployment | Parallel-session uncommitted edits (HPA custom metrics, etc.) — merge-review required; two agents editing the same k8s tree is a silent-clobber risk | Reconcile `git diff` before commit; single owner per file set |
| L3 | `max-http-request-header-size: 8KB` (identity) | With bearer JWTs (~1.5 KB claims observed here) + cookies, 8 KB is tight for future growth; oversized headers = 400s at the edge of valid traffic | 16 KB, measure real p99 header size in load test §5 |

---

## 5. Load-test acceptance gates ("how you know you're ready")

Definition: sustained target ≈ **peak-hour TPS × 3** with 30-min soak, +100% spike for 60 s,
p99 within SLO, error rate < 0.1 %, and **all alarms below silent**.

1. **Credential path soak** — `/auth/login` mix (80 % success / 20 % bad) at target TPS:
   assert lockout only fires on (email,IP) abuse, BCrypt CPU < 30 % of pod CPU (offload or
   `LOGIN_RATE_LIMIT` decision H5), 429s all carry `Retry-After`: verify clients do NOT
   blind-retry (H2) via edge access-log re-POST correlation.
2. **Order-write storm** — sharded path with ≥ 2 concurrent shards under C1 probe (pool=1):
   zero cross-shard rows; pgbouncer under `transaction` mode: `DISCARD ALL` on release proven.
3. **Cache expiry stampede** — warm 1 k hot keys, expire simultaneously: DB qps spike < 3× steady;
   L1 singleflight holds (this exists — prove it).
4. **Redis degradation** — 300 ms latency injection, then SIGKILL redis, then restart:
   gateway returns degraded-served or fast-503 within `max-wait` (H1), no request pile-up threads
   (jstack snapshot), fleet does not crash-loop (M2), metrics show counted fail-open (§1 lockout).
5. **Upstream brownout** — restaurant service 503-storm: OSRM/mesh clients must NOT double the
   request volume (§H2 check via upstream side request counters), circuit/fallback engages.
6. **Search sweep contention** — largest restaurant page during order storm: sweep finishes < tick
   period, index lag bounded, restaurant-service QPS from sweep < 10 % its capacity (H3).
7. **Chaos combos** — 4 + 5 simultaneously + node drain (PDB), measure ingress error budget and
   autoscaler behavior against the in-flight HPA edits (L2).

Suggested SLOs (tune to real volumes): login p99 < 500 ms @ 5× ; order confirm p99 < 900 ms @ 5×;
read p99 < 150 ms cached / < 400 ms cold; availability 99.9 % sustained; data-misrouting incidents: **0** (C1-C3).

**Blocking order:** C4 (restore CI guard) → C1/C2/C3 (one shard-datasource redesign, with §5.2 test)
→ H1 (one-line config) → H2 → H4/H5 → H3/M findings → run §5 gates.

---

## 6. Coverage snapshot at audit time (JaCoCo, measured 2026-09-14)

- identity module: **line 63.3 %** (931/1 470), branch 46 %+, 194/194 tests pass.
- Auth-chain classes driven to **100 % line + branch** this session: `LoginLockoutService`,
  `LoginLockoutProperties`, `UserTierResolver`.
- Remaining module floor check: CI `jacoco:check` gate (0.34 line); ramp target (≥60 % for
  identity) is now met. "100 % system-wide" would require targeted work on the §2/§3 files
  (largest zero-coverage gaps: `CustomerAccountController` 0/83, `ComplianceController` 0/29,
  `ConsentService` 0/18, `TenantController` 0/11, `DevAdminBootstrap` 0/28, `ShardAspect` n/a)
   — prioritize *risk-weighted* (auth, money, shard), not percentage chasing.

---

## 7. Implementation Gap Analysis

### 7.1 Critical Functional Gaps (block deployment)

| ID | Gap | Evidence | Impact |
|---|---|---|---|
| **G1** | `ShardAspect` leaks `search_path` across pooled connections | `platform-lib/.../ShardAspect.java:74-88` executes `SET search_path TO shard_N, public` then releases via `DataSourceUtils.releaseConnection` at L87 without reset; `k8s/pgbouncer/configmap.yaml:33` uses `pool_mode = transaction` with no `server_reset_query` | Next borrower inherits another tenant's shard schema → wrong-tenant reads, "relation does not exist", or silent `public` mirror reads. Under PgBouncer transaction pooling, session `SET` is undefined behavior — consecutive transactions may land on different server connections; the setting may leak to another client's transaction. |
| **G2** | `@Shard(key=...)` is decorative; aspect keys off `args[0]` | `Shard.java` javadoc: "used for audit/logging only"; `ShardAspect.applyShard:52-59` uses `extractLong(args[0])`; `order/.../repository/OrderRepository.java:25-26` has `@Shard(key="customerId")` on `findByOrderNumber(String orderNumber)` → returns null silently. Default JpaRepository methods (`findById`, `save`, `countByRestaurantId`, `countByStatus`) have no `@Shard` at all. | `findByOrderNumber` silently runs unsharded; default repo methods run schema-unscoped. Classic heisenbug that only manifests at production concurrency when pool reuse is high. |
| **G3** | Sharded DML inside transactions sets schema *at commit time* | `ShardAspect.applyShard:62-68` registers `TransactionSynchronization.beforeCommit`. | Every statement in that transaction already executed on the wrong schema before the `SET` runs; JPA flush also happens in beforeCommit — ordering between this sync and the JPA flush is undefined. The hot write path is *guaranteed* to use the inherited schema. |
| **G4** | CI guard for HTTP-client discipline is broken open | `scripts/ci/check-http-clients.py` exits 2 on this tree: `services/supportticket/src/main/java/com/bhukkad/support/wallet-old.bak/WalletCreditClient.java` (Permission denied). Allowlist entry `services/delivery/.../delivery/OsrmHttpConfig.java` is stale (file moved to `delivery/config/`). Detector regexes miss `RestClient.builder()` entirely. | The only automated enforcement against hand-built HTTP clients (with hidden or missing timeouts/retries) is silently not running; regressions land exactly where heavy traffic bites. |
| **G5** | `SearchSourceClient` RestClient has no timeouts + undetected by CI guard | `search/.../infrastructure/client/SearchSourceClient.java:28-33` uses `RestClient.builder().baseUrl(...).build()` — no connect/response timeout. With HC5 on classpath, the default factory auto-retries 429/503 POSTs (proven in `LoginLockoutRedisIntegrationTest` javadoc this session). | Slow restaurant-service → search sweep threads hang forever; 503 from upstream is amplified via blind retry. |
| **G6** | OSRM HC5 client uses default retry strategy | `delivery/.../config/OsrmHttpConfig.java:38-42` `HttpClients.custom()` without `disableAutomaticRetries()`/custom `setRetryStrategy`: HC5 default retries 429/503 GETs and IOExceptions. | Doubling request load against OSRM exactly when OSRM is unhealthy — amplification spiral during brownout. |
| **G7** | Gateway Lettuce pool `max-wait: -1` | `services/gateway/src/main/resources/application-prod.yml:13` and `services/admin-analytics/.../application-prod.yml:13` | Lettuce pool exhaustion makes every caller thread wait **forever** for a connection. Gateway converts a Redis hiccup into a full ingress freeze. `delivery` already uses `max-wait: 5s`. |
| **G8** | Cache-invalidation namespace collides with security keys | `common/cache/CacheInvalidationSubscriber.onMessage:69-71` expands events under the **same `bhukkad:` prefix** as security state (`bhukkad:auth:lockout:*`, `bhukkad:ratelimit:*`). Pattern event `auth:*` wipes active locks/rate-limit budgets cluster-wide. Each pattern event calls `localCacheService.clearAll()` → L1 stampede. | One misconfigured cache mutation during launch invalidates all locks → brute-force window opens. L1 clearAll at pattern bursts causes DB thundering herd. |
| **G9** | `LOGIN_RATE_LIMIT = 1000` per 5 min vs documented "30" | `identity/.../IdentityController.java:108-119` javadoc says "Conservative limits: 30 register/login"; constants are 1000/1000/5. | Login/register buckets are effectively no coarse pre-filter (~300 logins/s per caller bucket). BCrypt CPU burn under credential-stuffing. |
| **G10** | Redis availability coupling — crash-loop on Redis restart | `AbstractIdentityPostgresTest.java:33-37` comment: identity boots a Redis cache-invalidation listener that fails the context without live Redis. Production inherits the same listener bean. | Redis maintenance window → all identity pods crash-loop simultaneously → full auth outage. |
| **G11** | Test Redis DB index bleed | `AbstractIdentityPostgresTest` uses static Testcontainers Redis; all identity test classes share DB **0**. | Cross-test key bleed; this session's lockout debug noise partly came from shared limiter buckets across test classes. |
| **G12** | Config validation gap | Concurrent uncommitted edit added duplicate top-level `spring:` key to `identity/application.yml`; SnakeYAML throws `DuplicateKeyException` only at context start. | Config typo in wrong profile surfaces in staging/prod at 3 AM. |
| **G13** | Readiness flaps fleet on Redis micro-hiccups | `identity/application.yml` `include: readinessState` + custom readiness bean depends on Redis. | 200 ms Redis blip → readiness flips → HPA scales down → scale back up → request routing churn. |
| **G14** | Dead code + untested prod-only aspect | `UserTierResolver` has zero production callers; `ShardAspect` is `@Profile("prod")` with zero tests (`grep -rln ShardAspect src/test` → none). | Dead code increases attack surface; untested prod-only aspect = first prod traffic is its first execution. |
| **G15** | No circuit breakers on gateway outbound mesh calls | `gateway/.../EdgeRateLimitFilter.java` exists, but grep for `@CircuitBreaker` in gateway sources returned empty. Delivery/notification have Resilience4j on their outbound clients, but gateway→service calls lack them. | Upstream service 503 → gateway threads pile up waiting for response → ingress freeze. |

### 7.2 Architectural Omissions

| Omission | Evidence | Consequence |
|---|---|---|
| **No per-shard routing datasource** | `ShardAspect` mutates session state on a shared pool instead of routing at the datasource level. | Session `SET` leaks; under PgBouncer transaction pooling, behavior is undefined. |
| **No platform-wide HTTP client factory** | `SearchSourceClient` builds its own `RestClient`; `OsrmHttpConfig` builds its own HC5 client; no central `PlatformHttpClientFactory`. | Timeouts, retries, and circuit breakers are inconsistent across services. |
| **No Kafka producer durability config** | grep for `acks` in `application*.yml` returned empty. | Default `acks=1` (leader only) → broker crash between write and fsync → silent event loss. |
| **No load-test SLO enforcement in CI** | No k6/vegeta load test wired into CI pipeline. | Performance regressions land undetected between releases. |
| **No connection budget CI gate** | `scripts/ci/pool-budget-check.py` exists but is uncommitted / not a hard gate. | A single service can silently exceed pgbouncer capacity and take down the entire DB tier. |

### 7.3 Edge Cases / Error Handling Gaps

| Gap | Evidence | Fix Needed |
|---|---|---|
| `ShardAspect.extractLong` returns null on unresolvable key → silent return | `ShardAspect.java:91-102` | Throw `IllegalStateException` with method name + expected key |
| `CacheInvalidationSubscriber.onMessage` catches `Exception` broadly | L75 — swallows all errors, logs at WARN | Distinguish JSON parse errors (skip) from Redis errors (alert) |
| `LoginLockoutService.recordFailure` fail-open on `RedisSystemException` | L142 — returns 0 (no lock) | Already has metric `auth_lockout_active{failopen}` ✓ — verify alerting on it |
| `IdentityController.login` catch only `UnauthorizedException` | L162 — other auth failures (e.g., TOTP required) bypass `recordFailure` | Ensure all credential-failure paths call `recordFailure` |
| `OutboxPollPublisher` claim tx uses `TransactionTemplate` — verify rollback on publish failure | `OutboxPollPublisher.java:103` — if publish throws, state tx must roll back | Confirm via integration test |

### 7.4 Missing Dependencies / Configuration

| Missing | Where | Required |
|---|---|---|
| `server_reset_query_always = DISCARD ALL` | `k8s/pgbouncer/configmap.yaml` | Second line of defense against session state leaks |
| `spring.data.redis.cluster.enabled` + cluster nodes | All `application-prod.yml` | For Redis cluster migration (Phase 2) |
| `app.http.connect-timeout-ms` / `app.http.read-timeout-ms` | Platform properties | For `PlatformHttpClientFactory` |
| `Resilience4j` circuit breaker configs for gateway outbound | `gateway/application-prod.yml` | `resilience4j.circuitbreaker.configs.<service>` |
| `management.metrics.enable.lettuce` | All services | Expose lettuce pool metrics |
| `logging.level.com.bhukkad=INFO` in prod | All `application-prod.yml` | Confirm not overridden to DEBUG/TRACE |

---

## 8. High-Traffic Optimization Strategy

### 8.1 Concurrency Management

| Optimization | Where | Implementation |
|---|---|---|
| **Shard isolation via per-shard pools** | `ShardAspect` → replace with `ShardRoutingDataSource` | Each shard gets its own `HikariDataSource` (16 shards × pool 5 = 80 connections vs 1 shared pool). Eliminates session `SET` entirely. |
| **Gateway WebClient bounded elastic scheduler** | `gateway/application.yml` | `spring.webflux.netty.worker-count: 0` (auto) ✓; add `spring.codec.max-in-memory-size: 256KB`; verify blocking calls use `publishOn(Schedulers.boundedElastic())`. |
| **Outbox parallel relay** | `OutboxPollPublisher` + `OutboxProperties` | `relayThreads=4` (matching partition count); each claims a partition range via `poll(partitionId)` with `FOR UPDATE SKIP LOCKED`. |
| **Search sweep bounded parallelism** | `SearchSourceClient` | `ExecutorService` with `Semaphore(concurrency=10)` around menu fetches; per-call timeout 2s. |
| **Connection budget enforcement** | `scripts/ci/pool-budget-check.py` → CI gate | Formula: `Σ(hpa-max × DB_POOL_SIZE) ≤ 0.8 × pgbouncer_pool_size` AND `max_client_conn ≥ Σ(pgbouncer_pool_size)`. |

### 8.2 Caching Strategies

| Layer | Current State | Optimization |
|---|---|---|
| **L1 (Caffeine)** | Present with jittered TTL + probabilistic early refresh | Add maximum size bound (e.g., 10k entries / 100 MB) to prevent OOM under key-space growth. |
| **L2 (Redis)** | Singleflight + SETNX compute lock + 10% jitter | Add per-key `CacheInvalidatedEvent` version prefix to prevent namespace collision (G8). |
| **JWT/JWKS** | Stale-while-revalidate + pre-warm | Add Caffeine L1 around `JwkSet` retrieval (30s TTL) so first request per kid rotation doesn't serialize on HTTP. |
| **Rate limit buckets** | Redis-backed with Lua atomicity | Add local Caffeine L1 with 1s TTL for hot buckets → reduces Redis round-trips by ~90% for repeated callers. |
| **Menu/feed** | Not pre-warmed | Phase 7: `@EventListener(ApplicationReadyEvent)` pre-warm top 100 restaurants. |

### 8.3 Database Indexing / Query Optimization

| Finding | Evidence | Fix |
|---|---|---|
| `orders_id_seq` CACHE missing | `IMPLEMENTATION-GAP-REPORT.md` Phase 4.2 | `ALTER SEQUENCE orders_id_seq CACHE 100;` (1k for 1M TPS validation) |
| `idempotency_records` write amplification | `IdempotencyRecord.java` JPA entity | Partition by month; TTL eviction; or migrate to Redis SET with TTL. |
| Shard table queries need per-shard indexes | `ShardAspect` + `OrderRepository` | Each shard schema needs its own `idx_orders_customer_id`, `idx_orders_restaurant_id`, `idx_orders_status_created_at`. |
| `outbox_events` partition_id index | `OutboxPollPublisher` query | `CREATE INDEX idx_outbox_partition ON outbox_events(partition_id, status, created_at)` (Phase 4.5). |
| `refresh_tokens` composite index | `RefreshToken.java` L27 — `idx_refresh_tokens_customer_expires` added in uncommitted edit ✓ | Verify `customer_id, expires_at` covers the `findByCustomerIdAndExpiresAtAfter` query pattern. |

### 8.4 Resource Scaling

| Component | Current | Target / Recommendation |
|---|---|---|
| **Gateway HPA** | CPU 65%, memory, Pods (in-flight edits) | Min 10 → 30+ replicas; max-connections 2000/pod. |
| **Identity HPA** | CPU 65% | Min 5 → 20 replicas; Hikari pool 15/pod → pgbouncer pool 300. |
| **Order HPA** | CPU 65% | Min 10 → 50 replicas; Hikari pool 30/pod → pgbouncer pool 1200. |
| **PgBouncer** | `default_pool_size=150`, no `server_reset_query` | Add `server_reset_query_always=DISCARD ALL`; per-DB pool sizes matching HPA max × Hikari pool. |
| **Redis** | Single node | 3 masters + 3 replicas + Redis Cluster for 500k+ ops/sec, p99 < 5ms. |
| **Lettuce pool** | max-active 64, min-idle 8, max-idle 32 | Gateway/admin-analytics: change `max-wait: -1` → `5s`. |
| **JVM heap** | `-XX:MaxRAMPercentage=75.0` ✓ | Leave 25% for metaspace + native; monitor with `jmap -histo` during SSE test. |

### 8.5 Latency Reduction

| Optimization | Where | Expected Gain |
|---|---|---|
| **BCrypt offload / adaptive strength** | `IdentityService` password encoder | Reduce login p99 from ~100ms to ~30ms; move expensive ops to bounded thread pool. |
| **Edge rate limiting** | `EdgeRateLimitFilter` ✓ already present | Ensure buckets cover wallet/payment/notification endpoints (Phase 1.5). |
| **Gateway connection pool** | `gateway/application.yml` — already configured | connect 5s, response 8s, acquire 8s, max-connections 500 → 2000. |
| **Search sweep batching** | `SearchSourceClient` | Reduce sweep wall time from 30s to < 2s for 100 restaurants. |
| **Outbox wake channel** | `OutboxRelayBootstrap` | Poll interval 5s → sub-2s via wake channel (Phase 0). |
| **JWKS pre-warm + Caffeine L1** | `PlatformJwtValidator` | Eliminate first-request latency spike on kid rotation. |

---

## 9. Production Readiness Checklist

### 9.1 Stability

| Requirement | Status | Verification |
|---|---|---|
| All P0 critical data-correctness gaps fixed (C1–C4) | ❌ Not done | `ShardAspectTest` + interleave test pass; `python3 scripts/ci/check-http-clients.py` exits 0 |
| All P1 high-stability gaps fixed (H1–H5) | ❌ Not done | Lettuce `max-wait` ≤ 5s; HC5 retries disabled; search sweep < tick period; cache namespace segregated; rate-limit constants reconciled |
| Circuit breakers on all outbound mesh calls | ⚠️ Partial | Delivery (OSRM) + notification have Resilience4j; gateway→service calls missing |
| Outbox relay with SKIP LOCKED + DLT | ✅ Done | `OutboxPollPublisher` verified |
| Kafka consumers with MANUAL_IMMEDIATE + DLT | ✅ Done | `KafkaPlatformConfig` verified |
| Login lockout atomic + fail-open observable | ✅ Done | `LoginLockoutService` 100% covered; metric `auth_lockout_active` present |
| SSE capacity bounded | ✅ Done | `SseCapacityExceededException` + metric present |
| Dev-only bootstrap gated | ✅ Done | `DevAdminBootstrap` `@ConditionalOnProperty(enabled=true)` |
| Connection budget CI gate | ❌ Not done | `pool-budget-check.py` promoted to required CI check |
| Config validation in CI | ❌ Not done | YAML parse + duplicate-key check in CI |

### 9.2 Observability

| Requirement | Status | Verification |
|---|---|---|
| Prometheus scrape targets up for all 14 services | ❌ Not done | Phase 0.2 — deploy rules + verify |
| Outbox wake channel subscribed | ❌ Not done | `OUTBOX_WAKE_SUBSCRIBED` in all service logs |
| Loki ingests logs from all pods within 30s | ❌ Not done | Phase 0.3 |
| Tempo receives traces from synthetic request | ❌ Not done | Phase 0.4 |
| Hikari pending-connections metric | ❌ Not done | `HikariCPMetrics` bean |
| Lettuce pool acquire-time histogram | ❌ Not done | `lettuce.pool.acquire` Micrometer binding |
| OSRM/mesh client queue-time histogram | ❌ Not done | Custom timer around `RestClient` calls |
| SSE capacity rejected metric | ✅ Done | `sse_capacity_rejected_total` present |
| Rate-limit bucket usage metric | ❌ Not done | `auth_login_bucket_used` counter on `@RateLimited` aspect |
| p99 SLO enforcement in CI | ❌ Not done | k6 smoke test (login + order-create) wired into CI |

### 9.3 Security

| Requirement | Status | Verification |
|---|---|---|
| Login lockout enforced on (email, IP) before credential check | ✅ Done | `IdentityController.login:156` calls `assertAllowed`; 429 + Retry-After |
| Edge rate limiting on all auth endpoints | ✅ Done | `EdgeRateLimitFilter` + `@RateLimited` on controllers |
| JWT RS256 with stale-while-revalidate JWKS | ✅ Done | `PlatformJwtValidator` pre-warmed + single-flight refresh |
| PII masking in logs | ✅ Done | `PiiMaskingConverter` with pre-compiled regexes |
| Security keys namespace isolated from cache | ❌ Not done | `bhukkad:auth:lockout:*` shares `bhukkad:` prefix with cache events |
| Idempotency records TTL / partition | ❌ Not done | Write amplification on `idempotency_records` table |
| Redis cluster ACL / authentication | ❌ Not done | Phase 2 — Redis cluster with `auth_type=md5` |
| PgBouncer `auth_type=md5` + userlist | ❌ Not done | Verify `userlist.txt` mounted and rotated |

### 9.4 Peak Load Handling

| Requirement | Status | Verification |
|---|---|---|
| 3–5× peak TPS soak (30 min) | ❌ Not done | k6/vegeta load test at target scale |
| 100% spike for 60s | ❌ Not done | Burst test with 2× sustained rate |
| p99 within SLO (login < 500ms, order < 900ms, read < 150ms cached / 400ms cold) | ❌ Not done | Load test measurement |
| Error rate < 0.1% | ❌ Not done | Load test assertion |
| Redis kill + restart chaos | ❌ Not done | Gateway returns fast-503 within `max-wait`; no crash-loop |
| Upstream 503 storm (OSRM/restaurant) | ❌ Not done | Clients do NOT double request volume |
| Shard interleave correctness test | ❌ Not done | Zero cross-shard rows with pool=1, 2 concurrent customers |
| Search sweep < tick period under load | ❌ Not done | Bounded parallelism + bulk endpoint |
| HPA scales to 50 pods under CPU pressure | ❌ Not done | Order/gateway/realtime HPA verified at load |
| PgBouncer saturation test | ❌ Not done | `cl_active < pool_size`, `cl_waiting = 0` under peak |

---

## 10. Summary and Go/No-Go Criteria

### 10.1 What the system already does well
The system has solid foundational components: atomic Lua lockout with fail-open observability, cache singleflight + jitter + probabilistic early refresh, outbox relay with `FOR UPDATE SKIP LOCKED`, Kafka consumers with MANUAL_IMMEDIATE + DLT, stale-while-revalidate JWKS, edge rate limiting, bounded `SCAN` (no `KEYS`), SSE capacity guard, and PII-masked logging with zero hot-path serialization cost in prod.

### 10.2 What blocks go-live
The **P0/Critical** items are data-correctness bugs, not performance preferences:
- **C1–C3 (ShardAspect):** session `search_path` leaks across pooled connections; `@Shard(key=...)` is decorative; schema is set at `beforeCommit` (too late). Under PgBouncer `pool_mode=transaction`, this is undefined behavior. The aspect is `@Profile("prod")` with **zero tests** — first prod traffic is its first execution.
- **C4 (CI guard):** broken open — regressions land unnoticed.

### 10.3 What separates "works at 10% peak" from "survives 5× burst"
The **P1/High** items: unbounded Lettuce `max-wait:-1` turns a Redis hiccup into an ingress freeze; HC5 default retry amplifies 503/429 storms; search sweep N+1 causes index lag; cache namespace collision can wipe security state; rate-limit constants are misaligned with documentation.

### 10.4 Direct answer: after implementing all these points, is it ready?

**No — implementing the gaps is necessary but not sufficient.** Here is the precise meaning:

- **P0 must be fixed and verified before any shard rollout or heavy-traffic launch.** The three ShardAspect findings (C1–C3) are data-correctness bugs. Until they are fixed and the interleave test (§5.2) passes, a heavy-traffic launch can silently cross-tenant order reads.
- **P1 must be fixed before 5× peak load.** H1 (`max-wait:-1`) and H2 (HC5 retry) are the difference between "survives a Redis blip" and "full ingress freeze / upstream amplification spiral."
- **P2/P3 close the resilience gap** so the system *recovers* from failures rather than just tolerating them.
- **The actual gate is the §5 load-test + chaos suite at 3–5× peak TPS.** The system's primitives (§1) are genuinely solid — the risk is concentrated in the shard path, mesh client discipline, and the broken CI guard. Implementing the gaps gets you to "safe to launch with load-test validation." Passing the §5 gates at target scale with chaos drills is what makes it **ready for production heavy traffic**.

**Bottom line:** implement P0 → P1 in order, run the §5 load gates (soak, spike, Redis kill, 503 storm, shard interleave), and only then is the system ready for production heavy traffic. P2/P3 are the difference between "it worked in the load test" and "it stayed up during the first real sale day."

