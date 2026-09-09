# Bhukkad — Performance Audit & Unified Implementation Guide
## Latency, Contention & Scalability + Consolidated Program Under High Traffic

**Version 1.1 (2026-09-08).** This file **consolidates and replaces**
`docs/UNIFIED-IMPLEMENTATION-BLUEPRINT.md` (v1.0, now removed): the blueprint's
program content — program-status triage, target architecture, traceability
matrix, full-program tracks, dependency spine, SPOF inventory and acceptance
rules — is migrated into §10–§16. The two source audits remain the evidence
base and are unchanged: `docs/TECHNICAL-AUDIT-FEATURE-ROADMAP.md` (features
#1–#18, playbooks P/R, Parts V/VI) and `docs/PRODUCTION-READINESS-AUDIT-GUIDE.md`
(findings V/P/R, guardrails G-1…G-17, waves W1–W8). Every performance finding
below carries `file:line` evidence verified against the working tree (batch
`854fe43`); items that could not be re-verified are explicitly listed in §9 —
they are not asserted.

**Reading order:** §0–§2 evidence → §3–§5 strategy → §6–§8 execution →
§10 program status (why sequencing is re-cut) → §11–§16 target architecture,
traceability, full-program tracks, risks, acceptance.

**Stack context (verified):** 17 Spring Boot 3.2.x / Java 17 services behind a
WebFlux gateway, PostgreSQL per service via pgbouncer, Redis (cache/flags/limits),
Kafka/Redpanda (dev only), k8s HPA `maxReplicas: 12` on hot services.

---

## 0. Headline verdict

The platform is **architecturally sound (microservices, outbox, CQRS, caches,
breakers, limits all exist as code)** but effectively **unarmed under load**:

1. The **event backbone is off by default** (`events.external.enabled: false`
   in every service's base `application.yml`) and the relay has **three
   correctness bugs** (proxy-bypassed transaction, never-scheduled recovery,
   disabled-publisher marks rows PUBLISHED) — at traffic the backbone either
   silences or blackholes events.
2. The **hottest read paths have zero caching** — `RedisCacheService`
   (L1+L2, single-flight, jitter — the good implementation) has **zero
   production adoption**; the home feed materializes the whole restaurant
   table + SHA-256 per request; checkout pricing makes **one blocking S2S call
   per cart item**.
3. The **safety devices are inert or broken**: breaker never records
   (cannot open), retry backs off `toMillisPart()` ≈ 0 ms on any exception,
   limiter `INCR`+`EXPIRE` is non-atomic (permanent-429 risk), and the edge
   filter does a **blocking JWKS fetch on the Netty event loop**.
4. **Concurrency geometry is collapsed**: 14 `@Scheduled` jobs on Spring's
   default **single** scheduler thread (including the outbox relay), Tomcat's
   default 200 threads against Hikari pools of **5**, no Hibernate batching,
   no connection-timeout, no Redis timeouts.

Nothing here requires new infrastructure. Nearly every fix is config or a
contained code change in `platform-lib` + 4 hot services (order, restaurant,
realtime, delivery).

---

## 1. Top-10 bottleneck register (impact × effort)

| # | Bottleneck (evidence §2) | Dimension | Impact under load | Effort | Phase |
|---|---|---|---|---|---|
| B1 | Relay `@Transactional` self-invoked → claim not atomic; duplicates possible; `recoverStale` never scheduled; `maxRetries` never checked | Concurrency/DB | duplicate events, stranded PROCESSING rows, no replay | 3–4 d | PERF-2 |
| B2 | `publishForResult` returns **true when publisher disabled** → relay marks rows PUBLISHED with nothing sent (base yml ships disabled) | I/O | **silent event loss** in any env with relay on / Kafka off | 0.5 d | PERF-2 |
| B3 | Checkout pricing: per-item blocking `getMenuItem` (`.block(4s)`) + **uncached** per-item `resolveRestaurantId` loop (`.block(5s)`) | S2S latency | +N×RTT serial per order (≈45–500 ms for 1–10 items), fan-out amplification | 2–3 d | PERF-3 |
| B4 | Home feed: `restaurantRepository.findAll()` + full JSON serialize + SHA-256 **per request** | Algorithm/DB | O(N) rows & bytes per request at highest-RPS path | 1 d | PERF-3 |
| B5 | Breaker never decorates (`CircuitBreakerFilter:39-40`) + retry `toMillisPart()` bug, retries any RuntimeException (`RetryFilter:34`) | Concurrency | cascading failure, retry storms amplify outages | 1 d | PERF-1 |
| B6 | Edge kill-switch calls `subjectId()` **eagerly on the event loop**; JWKS miss = synchronous HTTP fetch on Netty thread (`EdgeKillSwitchFilter:57`, `PlatformJwtValidator:65-68`) | I/O | whole-edge stall on IdP hiccup | 0.5 d | PERF-1 |
| B7 | Limiter `INCR` then `EXPIRE` non-atomic (`RedisRateLimitService:30-34`); no gateway edge limiter | Concurrency | permanent 429 on crash-between-commands; abuse reaches DB pools | 1 d | PERF-1 |
| B8 | 14 `@Scheduled` jobs, default pool size 1 → relay head-of-line-blocks all cron; `Thread.sleep(50)` inside dispatch loop (`ScheduledOrderProcessor:62`) | Concurrency | event latency = sum of all cron tails | 0.5 d | PERF-0 |
| B9 | Pool geometry: Hikari 5 (several prod overlays pin 5) vs Tomcat 200 default vs pgbouncer 40; no `connection-timeout`, no Hibernate batching, no Redis/kafka timeouts | DB/Resource | 30 s borrow queues, thread-pileup, row-by-row writes | 0.5 d + re-pin | PERF-0 |
| B10 | Delivery: `assign()` check-then-act + **no UNIQUE(order_id)**; `findFirstByIsActiveTrue` = one agent gets everything; `markDelivered` TOCTOU double-events; rider pings never purged | Concurrency/DB | duplicate assignments/events; unbounded table | 2–3 d | PERF-4 |

Also fixed en route in PERF-2/3: `OrderEventPublisher.enqueue` **swallows**
failures (`OrderEventPublisher:40-42`); `AdminCqrsEventConsumer.upsertStat`
RMW lost-update + swallow (`AdminCqrsEventConsumer:50-65`); SSE pod-local maps
+ O(N) disconnect scan + unbounded `localConsumers`
(`OrderSseStreamServiceImpl:36-38,192-196`, `RedisOrderLiveRelay:40,98`).

---

## 2. Findings by requested dimension (verified evidence)

### 2.1 Algorithmic efficiency & computational complexity

| Location | Problem | Complexity today | Should be |
|---|---|---|---|
| `restaurant/api/FeedController.loadFeed:63-74` | home feed materializes **all** restaurants per request; ETag = SHA-256 over full JSON per request | O(restaurants) rows + full marshal/hash per hit | cached projection (30–60 s) + precomputed digest; O(1) amortized |
| `order/api/LegacyOrderCompatController:223,279-290` | per-cart-item S2S resolve inside loop, uncached | O(items × HTTP) serial | one batched lookup per cart |
| `order/api/RestaurantPricedItemResolver:67-69` | `cache.clear()` when >5 000 entries → wholesale eviction | O(1) then stampede | LRU/segmented eviction (Caffeine maximumSize) |
| `identity/service/AccountProfileService:25` | `userRepository.findAll().stream().anyMatch(...)` | O(users) per call — grows with customer base | keyed query (`existsBy...`) |
| `supportticket/.../DisputeResolutionServiceImpl:63` | `findAll().stream().sorted()` full history per list call | O(disputes log disputes) + full materialization | paged SQL `ORDER BY created_at DESC` |
| `referral/.../AffiliateServiceImpl:40` and duplicate `identity/.../AffiliateService:38` | full affiliate table per list; **two owners** for the same concept | O(affiliates) | paged + single owner |
| `admin-analytics AdminQueryService:35,40`, `ApiKeyService:46`, `AdminGrowthController:29,34` | unbounded tables read whole (fraud, stats, api keys, growth) | grows forever | paged / projection |
| `order/service/CartRecoveryService:27` | `cartRepository.findAll().stream().filter(stale)` | O(carts) — and **`expireStale` is never called from main** (grep: tests only) → carts never expire | scheduled sweeper + SQL-side predicate (`WHERE status='ACTIVE' AND updated_at < :cutoff`) + retention |
| `restaurant/service/AutocompleteService:33-37` | trie rebuilt from `findAll()` at startup only; never refreshed on menu change | O(menu) at boot; stale after mutations | startup build + incremental updates on menu events |
| `order/service/SubscriptionService.buildOrder:245-258` | `menuItemIds.contains()` inside stream; per-plan blocking `getMenu` | O(items×menu) small; latency = 1 RT per plan | Set lookup; batch materialization |
| `realtime OrderSseStreamServiceImpl.remove:192-196` | disconnect walks **every** stream map entry | O(streams × emitters) per disconnect | emitter→key reverse index, O(1) |
| `search` module | — bounded `LIKE`-escaped queries landed (batch D); **no `findAll()` remains** | — | keep + expression indexes (§4.3) |

### 2.2 Database interaction patterns

- **Connection pooling (verified):** `maximum-pool-size: ${X_DB_POOL_SIZE:5}`
  default repo-wide; several `application-prod.yml` pin literal `5`
  (search/notification/admin-analytics/delivery/restaurant), referral and
  supportticket pin 10, order prod overlay 30. No service sets
  `connection-timeout` (Hikari default 30 s), `keepalive`, or `max-lifetime`.
  pgbouncer runs transaction-mode with `default_pool_size = 40` (k8s configmap)
  → at HPA max 12 replicas × 5–20 connections the **server-side cap is
  exceeded before the app scales**. No `server.tomcat.threads.max` anywhere →
  Tomcat default 200 request threads queue on ~5 connections.
- **No Hibernate batching:** `jdbc.batch_size` / `order_inserts` /
  `order_updates` appear in **zero** service ymls. Bulk writes are per-row:
  outbox relay `repository.save(event)` inside the per-event `finally`
  (`OutboxPollPublisher:87`), `CartRecoveryService:31-35` delete+save per cart,
  settlement writers. IDENTITY id generation makes INSERT batching inert —
  hot bulk paths need `saveAll` + `@Modifying` set-updates.
- **Index/constraint gaps (verified in `delivery/V1__baseline.sql`):**
  `delivery_assignments` has PK(id) only — **no UNIQUE(order_id)** (lines
  160-177, 544-548) so the service-level check is the only guard;
  `rider_location_updates` is well-indexed `(agent_id, recorded_at)` (line 753)
  but has **no retention/purge** (grep: none) → unbounded.
- **Row-by-row status writes:** outbox relay flips one row per `save`
  (autocommit, see §2.4) — 100 saves per batch instead of one batched
  `UPDATE ... WHERE id IN (...)`.
- **Missing unique/dup sweeps:** wallet path is fixed with
  `findByCustomerIdForUpdate` + same-tx ledger (verified) but `WalletBalance`
  still has **no `@Version`** and there is **no `CHECK (balance >= 0)`** —
  defense-in-depth absent on the money table.

### 2.3 Memory management & leaks

- `RedisOrderLiveRelay.localConsumers` (`:40`): `ConcurrentHashMap<topic,
  CopyOnWriteArrayList<Consumer>>` grown via `computeIfAbsent` (`:98`) —
  **no removal path found** → per-topic consumer lists accumulate for the pod
  lifetime.
- `OrderSseStreamServiceImpl` keeps three pod-local emitter maps
  (`:36-38`); removal is O(N) scan (`:192-196`) — correct but CPU-costly under
  churn; no per-channel/global capacity budget surfaced as metrics →
  connection memory grows with zombie sockets if heartbeats lag.
- `RestaurantPricedItemResolver` cache is bounded (5 000) but clears
  wholesale (`:67-69`) — not a leak, a stampede generator.
- **Unbounded tables = unbounded heap/IO**: carts (sweeper unwired),
  `rider_location_updates` (no purge), `idempotency_records` (cleanup
  scheduler only exists in identity — audit V-19), outbox rows (see §2.4),
  dispute/affiliate/stat lists read whole (§2.1).
- `LocalCacheService` (Caffeine) is properly bounded (`maximumSize` +
  `expireAfterWrite`, `LocalCacheService:29-33`) with jittered early expiry —
  the *only* cache actually in production (survey trending,
  `TrendingDishServiceImpl:53`).
- `DisputeResolutionServiceImpl.java.bak` still sits in `src/main` (noise,
  not compiled) — delete.

### 2.4 Concurrency models & thread safety

- **Outbox relay transaction is a no-op (highest-severity concurrency bug).**
  `drainBatch()` is `@Transactional` (`OutboxPollPublisher:52`) but is
  **self-invoked** from `poll()` (`:136`) → Spring proxy bypass → runs
  **without any transaction**: the `FOR UPDATE SKIP LOCKED` claim
  (`claimBatch:144-156`) commits statement-by-statement, so (a) two relay
  replicas can claim the same PENDING rows → **duplicate publishes**, and (b)
  each `repository.save` is its own implicit tx (100 round-trips).
- **Failed publishes strand forever:** `bumpRetry` keeps status PROCESSING
  (`:158-163`); the claim query selects only PENDING; `recoverStale()`
  (`:99-121`) is invoked **only from tests** (grep) → any broker blip
  permanently strands rows. `retryCount` is incremented but **never compared**
  to a max → no DLQ transition.
- **Publisher-disabled blackhole:** `KafkaPlatformEventPublisher
  .publishForResult` returns `true` when `properties.enabled()` is false
  (`:80-83`) → with the relay bean active and `events.external.enabled=false`
  (the **shipped default in every base `application.yml`** — verified in
  order/payment/identity/restaurant/delivery/notification/realtime/
  admin-analytics), rows flip to PUBLISHED with nothing sent. Two independent
  gates (relay `@ConditionalOnProperty` vs publisher flag) make this state
  reachable.
- **Money/state check-then-act:** delivery `assign()` (`DeliveryService:25`
  check → `:35` insert; no DB unique), `markDelivered()` status check
  (`:45-47`) then save → double `OrderDelivered` events; growth/admin
  `upsertStat` findById→+1→save (`AdminCqrsEventConsumer:55-65`).
- **Rate limiter race:** `INCR` then `EXPIRE` as two commands
  (`RedisRateLimitService:30-34`) — crash between them leaves a TTL-less key
  → permanent 429 for that identity. Javadoc now claims "atomic" (RC-F drift).
- **Single scheduler thread:** no `spring.task.scheduling.pool.size` in any
  yml (grep) with 14 `@Scheduled` jobs — the relay shares one thread with
  everything else; `ScheduledOrderProcessor.dispatchDueOrders` busy-loops with
  `Thread.sleep(50)` (`:62`) and can hold the thread for a full backlog.
- **Blocking on the reactive edge:** `EdgeKillSwitchFilter.filter` calls
  `subjectId(exchange)` eagerly (`:57`) — JWT parse + (on cache miss)
  synchronous JWKS HTTP via `SimpleClientHttpRequestFactory`
  (`PlatformJwtValidator:65-68`) on the Netty event loop; no
  `Schedulers.boundedElastic()`, no stale-serve backoff.
- **Inert resilience:** `CircuitBreakerFilter` reads state in
  `onErrorResume` and never decorates (`:39-40`) — breakers cannot open even
  though `RestaurantClient` mounts the filter (`RestaurantClient:33-34`);
  `RetryFilter` uses `backoff.toMillisPart()` (≈0 ms) and retries **any**
  `RuntimeException` (`RetryFilter:34`) including non-idempotent POSTs —
  `stockReservation` POSTs are retry-eligible → duplicate reserve risk under
  flaps.
- **Good patterns already present** (keep, don't regress): wallet
  pessimistic lock + same-tx ledger; `ScheduledOrderProcessor`
  ShedLock + per-order `TransactionTemplate` + status guard; outbox claim
  intent (`SKIP LOCKED`); `LocalCacheService` jitter/early-expiry; survey
  trending native `ON CONFLICT` upsert; service JWT
  `enforce-internal-paths: true` default in every yml.

### 2.5 I/O operations & network latency

- **Serial acknowledged Kafka publish:** `publishForResult` blocks
  `future.get(sendTimeout)` per event (`KafkaPlatformEventPublisher:86-89`);
  batch of 100 = up to 100 sequential acks on one scheduler thread.
  Producer has **no** `acks`/idempotence/linger/batch knobs
  (`KafkaPlatformConfig` — grep: absent) → `acks=1` broker default on money
  events.
- **Blocking per-item HTTP on checkout:** `RestaurantPricedItemResolver
  .resolve` does `.timeout(3s).block(4s)` per item (`:43-45`);
  `LegacyOrderCompatController.resolveRestaurantId` another `.block(5s)` per
  item, **uncached** (`:279-290`); subscription materialization blocks per
  plan (`SubscriptionService:240-242`). All on servlet threads.
- **Event-loop blocking:** §2.4 edge filter.
- **Cache wait parking request threads:** `RedisCacheService.waitForValue`
  sleeps up to `LOCK_WAIT_RETRIES=10` × (20 ms exponential, cap 500 ms) ≈
  **3.5 s of `Thread.sleep` on the caller thread** (`RedisCacheService:30,
  379-407`) — currently harmless because unused; would become a p99 cliff the
  day it is adopted as-is. Fix before adoption.
- **Timeout posture (verified):** `RestaurantClient` sets per-call response
  timeouts (3 s reads, 5 s stock POST) — good; but **no connect timeout /
  connection-pool provider** on any built client; survey/realtime
  `OrderOwnershipClient` use `SimpleClientHttpRequestFactory` 2 s/3 s;
  gateway `connect-timeout: 5000`, `response-timeout: 30s`
  (`gateway/application.yml:11-13`; the gateway now runs a **36-route**
  programmatic table per its own config comment — the audits still say 23) —
  30 s response ceiling is far above any
  sensible API SLO and lets stuck upstreams pin gateway connections;
  **no Redis command timeouts** configured anywhere (Lettuce defaults);
  no Kafka consumer `max.poll.*`/concurrency tuning.
- **Event flags:** base configs ship `events.external.enabled: false,
  type: log` everywhere (verified, §2.4) — dev overlays flip to true; survey
  defaults true. The prod posture must be an explicit overlay decision, not a
  silent default.

### 2.6 Per-service communication latencies

- **order → restaurant is the hot chord**: menu snapshot per order
  (`getMenu`), item price per cart line (`getMenuItem` ×N), restaurant id per
  legacy cart line (×N uncached), stock reserve/release per order (5 s cap).
  Under 1k RPS with 5-item carts this is **~6k extra HTTP calls/min to
  restaurant**, all serial with the request.
- **Client construction is fragmented**: order builds 3 private
  `WebClient.builder()` instances (`RestaurantClient:31`,
  `PaymentServiceClient:45`, `SupportTicketDisputeClient:27`); platform-lib
  exposes a `@LoadBalanced` bean nobody injects (`WebClientConfig:36-43`) —
  no shared `ConnectionProvider` (per-client default pools), no uniform
  metrics, breaker config duplicated per client.
- **Silent failure conversion:** `RestaurantClient` reads end
  `onErrorResume(e -> Mono.empty())` (`:52,69,85,105`) — downstream errors
  become "not found" and flow into business logic as empty menus/restaurants;
  acceptable for the kill-switch bucketing, dangerous for pricing paths
  (resolver throws BusinessException — correct — but the legacy path returns
  empty → callers must handle).
- **Same-topic latency floor:** outbox relay poll interval default 5 s
  (`OutboxProperties.defaults():36-38`, batchSize 100, processingTimeout 30 s,
  sendTimeout ~10 s) → every event carries a 0–5 s delivery floor even when
  everything is healthy; no wake channel.
- **Realtime fan-out:** cross-pod Redis bridge exists
  (`RedisOrderLiveRelay` publishes + dispatches to `localConsumers:105`) but
  the delivery service's SSE twin is a near-duplicate implementation, and
  neither bounds connections or exposes capacity metrics.

---

## 3. Architectural redesign recommendations

These are the six structural moves the audit justifies (all reuse in-repo
machinery; no new infrastructure):

1. **Make the event backbone real, then make it the default.** Two-phase
   relay (claim-tx → publish-outside-tx → state-tx), scheduled
   `recoverStale`, `maxRetries → dead_letter_events`, producer knobs
   (`acks=all`, idempotence, linger 5 ms, batch 32 KB, lz4), one wiring path
   (delete the legacy/duplicate Kafka config before enabling), prod overlays
   `enabled=true` with a **boot gate that refuses a prod profile with events
   disabled**. Everything downstream (search sync, trending, realtime,
   notifications, analytics projections) inherits durability.
2. **Read-model + cache tier for the top of the funnel.** Adopt
   `RedisCacheService` (already built: L1 Caffeine + L2 Redis + single-flight
   lock + TTL jitter + pub/sub invalidation via `CacheInvalidationService`)
   for: home feed projection (30–60 s), menu snapshot per restaurant (60–300 s,
   invalidated on menu mutation events), trending (upgrade survey's
   local-only cache to L1+L2), serviceability zones. Fix `waitForValue`
   busy-wait → `CompletableFuture` single-flight per key **before** adoption.
3. **Batch the checkout chord.** One `GET /api/v1/menu/items?ids=…` batch
   endpoint on restaurant; order's resolver takes the item-id set per cart and
   resolves **one** call (cached 60 s per restaurant snapshot). Eliminates the
   N×RTT serial chain and the uncached `resolveRestaurantId` loop. Longer
   term the menu snapshot already carries restaurantId — prefer snapshot-based
   grouping so legacy compat stops resolving per item at all.
4. **Concurrency geometry by config.** Tomcat 100 threads, Hikari 20/pod +
   `connection-timeout: 3000ms` (backpressure at the edge, not 30 s queues),
   `spring.task.scheduling.pool.size: 12` + dedicated 2-thread `relay-`
   scheduler for the outbox, Hibernate batching block, Redis command timeouts,
   Kafka listener concurrency = partitions, pgbouncer re-pin per DB
   (`Σ(HPA-max × pool) ≤ 0.8 × ceiling`) enforced by a CI arithmetic check.
5. **Single-writer / atomic-mutation discipline for state machines.**
   `delivery_assignments` UNIQUE(order_id) + `INSERT … ON CONFLICT DO
   NOTHING`; `markDelivered` as conditional
   `UPDATE … WHERE status <> 'DELIVERED'` (idempotent, no double events);
   `upsertStat` as native `ON CONFLICT DO UPDATE`; limiter Lua script;
   conditional updates for any future money/status transition — the codebase
   already proves the pattern (coupon repo, survey trending).
6. **Completion of the CQRS read side.** The analytics projection, trending,
   and search read-models are the correct long-term replacement for the
   remaining `findAll()` admin/list surfaces: page them now (cap 200),
   migrate hot ones to projections fed by the (fixed) backbone.

Explicitly **deferred** (evidence-gated): service mesh (mTLS/retries at L7),
gRPC for hot chords, Avro/schema-registry — revisit only after PERF-0…4
metrics show transport as the residual p99 contributor.

### 3.7 Resolved doc-vs-code conflicts (adopted positions)

Where the two source audits contradict each other or the working tree, these
positions are adopted (migrated from the blueprint, inspection-verified):

1. **Wallet lock strategy (V-01):** the audit guide prescribes conditional
   single-statement UPDATE + `@Version`; the tree uses `SELECT … FOR UPDATE`.
   **Adopted:** keep the verified lock path, add `@Version` + `CHECK
   (balance >= 0)` as defense-in-depth (§4.3); standardize on
   conditional-UPDATE for the *next* money surfaces (COD ledger, loyalty —
   the coupon-repository pattern already proves it in-repo).
2. **Outbox wake channel:** the audit guide says `RedisOutboxPendingQueue`
   "exists, unwired"; the roadmap says it does not exist. **Resolved by
   inspection:** it does not exist (grep: zero hits) — the plan assumes
   build-the-wake (PERF-2), not wire-the-wake.
3. **Kafka `acks`:** guide vs roadmap disagree on whether `acks=all` is set.
   **Resolved:** the active `KafkaPlatformConfig` factory has **no** producer
   knobs; the hardened legacy config is the one holding `acks=all` — the
   dual-wiring collision is the root cause; consolidate first (PERF-2
   step 3), then the knobs land once.
4. **Service mesh & gRPC (roadmap §VI):** both are the roadmap's own
   "Not Verified" items (§VI.8) and conflict with the audit's
   zero-new-dependency discipline. **Adopted:** defer both (see the deferred
   note above); scope-limit to permissive-mode mTLS as hardening and gRPC for
   the two measured hottest S2S pairs, only if the REST-pool work
   (P-01/P-05) fails its targets — evidence over protocol churn.

---

## 4. Resource management strategy

### 4.1 JVM & containers
- Memory **requests == limits** (Guaranteed QoS), `MaxRAMPercentage=75`,
  `ExitOnOutOfMemoryError`, G1 with `MaxGCPauseMillis=200`; **no CPU limit**
  (requests only) so GC threads aren't throttled; uniform `JAVA_OPTS` in one
  canonical Dockerfile (14 per-service Dockerfiles drift today).
- Add GC log rotation; alert on `gc_pause_seconds p99` and
  `container_oom_killed_total`.

### 4.2 Connection & pool budget (the arithmetic that must pass CI)

| DB (hot) | HPA max | pool/pod | server need | pgbouncer per-DB pool |
|---|---|---|---|---|
| orders | 12 | 20 | 240 | 280 (max_connections ≈ 320) |
| payments | 6 | 20 | 120 | 140 |
| restaurants | 12 | 20 | 240 | 280 |
| identity | 6 | 15 | 90 | 100 |
| others (small) | 3–6 | 10 | 30–60 | 80 |

Rule: `Σ(hpa-max × DB_POOL_SIZE) ≤ 0.8 × min(pgbouncer per-DB pool, PG
max_connections)`; block merge otherwise (script + CI job).

### 4.3 Index plan (verified gaps only)
- `delivery_assignments`: `UNIQUE(order_id)` (also fixes B10).
- `restaurant_search` / `menu_item_search`: `((lower(name))
  varchar_pattern_ops)` prefix indexes + **explain-guard test** in CI
  (search queries are bounded but must stay index-backed).
- `carts`: composite `(status, updated_at)` for the (to-be-wired) sweeper.
- `rider_location_updates`: keep `(agent_id, recorded_at)`; add scheduled
  purge (e.g. keep 72 h) or monthly partition drop.
- Wallet hardening: `@Version` column + `CHECK (balance >= 0)` +
  `UNIQUE(customer_id)` (dup-sweep first).

### 4.4 Load balancing & edge
- Gateway: ≥3 replicas + PDB; **response-timeout 30s → 8–10s**; per-route
  Redis rate limiter (bucket per `authSubject|IP`), fail-open with counter;
  exact-origin CORS; unmatched-route 404 counter.
- Client side: keep DNS Service LB at current pod counts; after PERF-2,
  consider zone-aware `spring-cloud-loadbalancer` only on the order→restaurant
  chord if cross-zone RTT shows in traces.

---

## 5. Concrete concurrency optimization program

1. **Transaction discipline** — replace every self-invoked `@Transactional`
   with an injected collaborator or `TransactionTemplate`
   (`ScheduledOrderProcessor` is the in-repo reference pattern). Audit list:
   `OutboxPollPublisher.drainBatch` (B1).
2. **Atomic claims**: relay claim = `TransactionTemplate` tx wrapping
   `findPendingForProcessing … SKIP LOCKED` + status flip to PROCESSING +
   commit; publish outside any tx on a bounded `relay-` pool; state flip in a
   second short tx; `recoverStale` scheduled every `processingTimeout`; after
   `maxRetries` → `dead_letter_events` + alert metric.
3. **Bulkheads**: `spring.task.scheduling.pool.size: 12`; dedicated
   `relayScheduler` (2 threads) registered on `ApplicationReadyEvent` gated by
   the existing enabled property; notification dispatch executor (core 4 /
   max 16 / queue 2000 / **AbortPolicy** → rethrow → DLT — never
   CallerRuns); SSE send executor with AbortPolicy + capacity budgets.
4. **Atomic state mutations** (replace check-then-act): conditional UPDATEs /
   `ON CONFLICT` for assign, markDelivered, upsertStat, loyalty redeem,
   limiter (Lua). Each ships with a concurrency test that fails on current
   code (e.g. 2 threads × markDelivered → exactly one `OrderDelivered`).
5. **Non-blocking edge**: wrap `subjectId` in `Mono.fromCallable(...)
   .subscribeOn(boundedElastic)`; JWKS stale-while-refresh cache with failure
   backoff + pre-warm; BlockHound test in gateway CI to keep the loop clean.
6. **Single-flight cache loads**: `RedisCacheService.waitForValue` →
   per-key `CompletableFuture` map (compute once, others await ≤ 250 ms then
   fall through to L2/DB); remove `Thread.sleep` parking.
7. **Retry/breaker correctness**: `CircuitBreakerOperator.of(breaker)` via
   `transformDeferred` + registry + metrics; `Retry.fixedDelay` with
   predicate = 5xx/408/timeouts **and idempotent methods only**; exponential
   backoff `Duration.toMillis()` (fix `toMillisPart`).

---

## 6. Granular, step-by-step implementation guide

Ordered by phase; each step lists files, exact change, and verification.
Config steps are safe same-day deploys (rollback = revert values).

### PERF-0 — Geometry & hygiene (day 1–2, config-only)

1. **Per-service `application.yml`** (canonical block; env-overridable):
```yaml
server:
  tomcat:
    threads: { max: 100 }
    accept-count: 200
spring:
  task:
    scheduling:
      pool:
        size: ${SCHEDULER_POOL_SIZE:12}
  datasource:
    hikari:
      maximum-pool-size: ${DB_POOL_SIZE:20}
      minimum-idle: 20
      connection-timeout: 3000
      keepalive-time: 300000
      max-lifetime: 900000
  jpa:
    properties:
      hibernate:
        jdbc.batch_size: 50
        order_inserts: true
        order_updates: true
        jdbc.batch_versioned_data: true
        query_plan_cache_size: 200
  data:
    redis:
      timeout: 2000        # command timeout
      connect-timeout: 1000
```
   (IDENTITY caveat: batching applies to UPDATEs; convert hot per-row loops to
   `saveAll`/`@Modifying` — see PERF-3 step 5.)
2. **pgbouncer** (`k8s/pgbouncer/configmap.yaml`): `default_pool_size` per
   §4.2 table (or per-DB `pool_size` entries); keep transaction mode; document
   `max_connections ≈ 320`.
3. **Gateway** `application.yml:13`: `response-timeout: 30s → 8s`; keep
   connect 5s.
4. **K8s resources** (all Deployment overlays): memory requests==limits,
   CPU request 750m no limit, `JAVA_OPTS` standard block (§4.1).
5. **CI arithmetic guard**: script computing §4.2 inequality from the
   kustomize tree; fail the PR on violation.
   **Verify:** staging k6 ramp — `hikaricp_connections_pending` ≈ 0 at
   previous saturation point; p95 −20 % on order/restaurant; boot logs show
   scheduler pool 12.

### PERF-1 — Re-arm the safety devices (week 1, ~2 dev-days)

1. **`CircuitBreakerFilter`** (platform-lib):
```java
return Mono.defer(() -> next.exchange(req))
    .transformDeferred(CircuitBreakerOperator.of(breaker))
    .timeout(props.timeout())
    .onErrorResume(CallNotPermittedException.class, e ->
        Mono.just(ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE)
            .header("X-Circuit", "open").build()));
```
   per-target breakers from a `CircuitBreakerRegistry`; export state gauge;
   StepVerifier test: 20 calls ≥50 % fail → OPEN, next call fails fast
   without touching the exchange.
2. **`RetryFilter`**: `.retryWhen(Retry.backoff(max, Duration.ofMillis(200))
   .filter(t -> isTransient(t) && isIdempotent(req.method())))`; fix
   `toMillisPart → toMillis`; unit: 4xx → 0 retries; POST → 0 retries.
3. **`RedisRateLimitService`**: single Lua
   (`INCR; if c==1 then PEXPIRE end; c>limit → 0`) + fail-open on Redis error
   with counter + real resolvers (`authSubject|IP`); mount on gateway routes
   (Redis rate-limiter filter) and on `/auth/login`, order-create, webhook.
4. **`EdgeKillSwitchFilter`**:
```java
return Mono.fromCallable(() -> subjectId(exchange))
    .subscribeOn(Schedulers.boundedElastic())
    .defaultIfEmpty(0L)
    .flatMap(sub -> flags.isRouteEnabled(flagKey, sub == 0L ? null : sub))
    .flatMap(on -> on ? chain.filter(exchange)
                      : disabled(exchange, flagKey, route.getId()));
```
   Plus `PlatformJwtValidator`: stale-serve + async refresh + 30 s failure
   backoff + pre-warm on `ApplicationReadyEvent`.
   **Verify:** BlockHound green in gateway; slow-IdP drill: p99 flat,
   verify serves stale; breaker game-day: order sees fast 503 from client
   filter instead of pileup.

### PERF-2 — Event backbone (week 1–2, ~4 dev-days)

1. Fix the blackhole: `publishForResult` returns `false` (not `true`) when
   disabled, **or** relay and publisher share one gate. Choose: one gate
   (`ExternalEventsProperties`), `@ConditionalOnProperty` on the relay bean
   aligned to it; prod overlay sets `enabled: true, type: kafka`; add boot
   preflight refusing prod+disabled.
2. Two-phase `drainBatch` (per §5.2) with `TransactionTemplate`; schedule
   `recoverStale` at `processingTimeout`; `retryCount ≥ maxRetries` →
   `dead_letter_events` row + `outbox_dlq_total` metric; keep `SKIP LOCKED`.
3. Producer knobs on the single surviving factory (delete duplicate
   `KafkaConfig` beans first): `acks=all`, idempotence, retries MAX,
   `linger.ms=5`, `batch.size=32768`, lz4; `DefaultErrorHandler` +
   `DeadLetterPublishingRecoverer` → `<topic>.dlt`; consumers stop
   swallowing (`AdminCqrsEventConsumer:50-52`, notification consumer);
   consumer dedupe via `idempotency_records` scope `KAFKA_CONSUME`.
4. `AdminCqrsEventConsumer.upsertStat` → native
   `INSERT … ON CONFLICT (restaurant_id) DO UPDATE … +1/+=` + eventId claim.
5. `OrderEventPublisher.enqueue` (`:40-42`): remove the swallow — let the
   exception roll back the business tx (G-1 rule); add
   `outbox_enqueue_no_tx` guard throwing when no active tx.
6. Deploy Kafka/Redpanda to the cluster (currently dev-compose only) — hard
   prerequisite; topics from `docs/event-catalog.md`, DLT partitions
   pre-created.
   **Verify (Testcontainers + staging):** broker-kill mid-batch → rows stay
   PENDING/PROCESSING with `next_attempt_at`, recovered after timeout, no
   duplicates across two relay replicas (fixed claim tx); replay drill = no
   double-count; event E2E p99 < 2 s.

### PERF-3 — Hot read paths & S2S chord (week 2–3, ~4 dev-days)

1. **Pre-fix `RedisCacheService`**: replace `waitForValue` sleep loop with
   per-key `CompletableFuture` single-flight (cap wait 250 ms → fall through
   to supplier); keep jitter/early-expiry. Tests: concurrent miss → exactly
   one supplier execution; Redis down → supplier path, no 3.5 s parking.
2. **Feed** (`FeedController`): `loadFeed` →
   `cacheService.getOrCompute("feed:v1", 30–60 s, …)`; ETag digest stored
   **inside** the cached projection (compute once per TTL); add
   `CacheInvalidationService.publish` on restaurant activation changes.
   Target: origin work per feed hit → O(1) cached read; keep 304 path.
3. **Menu snapshot**: cache `GET /api/v1/restaurants/{id}/menu` server-side
   in restaurant (`menu:restaurant:{id}`, 300 s) with invalidation on menu
   mutations (outbox event → subscriber). Order's `getMenu` then hits cache
   95 %+ of the time.
4. **Batch item resolution**: new `GET /api/v1/menu/items?ids=1,2,3` on
   restaurant (cap 100 ids, cached per id 60 s); `RestaurantPricedItemResolver
   .resolveAll(Collection<Long>)` = one call; Caffeine `maximumSize(10_000)`
   replaces the `clear()` hack; `LegacyOrderCompatController:223` uses the
   snapshot's `restaurantId` (already in `MenuSnapshot`) instead of per-item
   `resolveRestaurantId`. **Verify:** 10-item cart checkout = ≤1 additional
   S2S round-trip; k6 checkout p95 −40 %+.
5. **Bulk-write sweeps**: outbox state flip → batched
   `UPDATE outbox_events SET status=… WHERE id IN (:ids)`; `CartRecoveryService`
   → SQL-predicated delete+update in one tx per batch of 500.
6. **Bound the remaining scans** (§2.1 list): identity
   `AccountProfileService:25` → `existsBy…`; disputes/affiliates/admin lists
   → `PageRequest.of(page, min(size,200))` + `ORDER BY` in SQL; wire the cart
   sweeper (`@Scheduled` hourly + ShedLock) and add retention to
   `rider_location_updates` (purge >72 h, batched).
   **Verify:** SQL-collector test asserts row caps per endpoint;
   `findAll()` CI grep with `@AllowFullScan` opt-in.

### PERF-4 — Delivery & realtime (week 3–4, ~3 dev-days)

1. Migration: `ALTER TABLE delivery_assignments ADD CONSTRAINT
   uq_delivery_assignments_order UNIQUE (order_id);` (dup-sweep first).
   `assign()` → `INSERT … ON CONFLICT (order_id) DO NOTHING` semantics;
   matching = Redis `GEOSEARCH` over existing `geo:rider:locations` + load
   cap conditional update; keep `findFirstByIsActiveTrue` as fallback.
2. `markDelivered` → `UPDATE … SET status='DELIVERED', delivered_at=…
   WHERE order_id=:o AND status<>'DELIVERED'` (1 row → publish event; 0 rows
   → idempotent no-op). Concurrency test: 2 threads → exactly one event.
3. SSE: emitter→streamKey reverse map for O(1) removal; per-channel/global
   budgets from existing `LiveProperties` surfaced as
   `sse_connections`/`sse_capacity_rejected_total`; `localConsumers`
   removal on stream close; unify delivery's duplicate SSE impl onto the
   platform helper.
4. Zones: `ServiceabilityCheckController` cache refresh →
   `LocalCacheService.getOrCompute` (zones are small; TTL 300 s) instead of
   per-miss `findAll()`.
   **Verify:** concurrent assign race test; SSE reconnect cross-pod test;
   ping-table size flat over 7-day simulation.

### PERF-5 — Structural (weeks 4–6, behavior-frozen)
- One platform `WebClient` factory (connect/response timeouts, shared
  `ConnectionProvider`, breaker+retry+metrics) — migrate the 3 order clients
  + survey/realtime ownership clients; CI ban on new `WebClient.builder()`
  outside platform-lib.
- Relay/cron scheduler isolation (§5.3) if not already landed in PERF-0.
- Delete dead code touching perf surfaces: `DisputeResolutionServiceImpl
  .java.bak`, duplicate `AffiliateService` (identity vs referral — pick one
  owner), OSRM stubs (wire or remove).
- Sonar new-code gate 100 % on every PERF PR (config items exempt).

---

## 7. Prioritization matrix (impact vs effort)

```
        Low effort                      Medium effort                 High effort
High   PERF-0 geometry (B8,B9)         B1/B2 outbox fix (PERF-2)     backbone bring-up+k8s Kafka
       B5 breaker/retry (PERF-1)       B3 checkout batch (PERF-3)    read-model migration
       B6 edge offload (PERF-1)        B4 feed cache (PERF-3)
       B7 limiter Lua (PERF-1)
Med    gateway timeout 8s              B10 delivery atomicity        GEO matching
       wallet @Version+CHECK           SSE budgets/index             CQRS for admin lists
       idempotency cleanup central     zones cache
Low    delete .bak/dead code           trie incremental refresh      mesh/gRPC (deferred)
```

Sequencing rule: **PERF-0 → PERF-1 → PERF-2** are prerequisites for claiming
any scale number; PERF-3/4 can proceed in parallel once PERF-0 lands; PERF-5
runs behind, never concurrent with money-path changes.

---

## 8. Verification & load-test plan

- **Baseline capture before PERF-0** (staging, k6 in `loadtest/`):
  checkout, feed, search, order-create, SSE connect — record p50/p95/p99,
  RPS at error onset, `hikaricp_connections_pending`, GC pause p99.
- **Per-phase gates:**
  - PERF-0: pool plateau removed (pending=0 at 2× prior RPS); p95 −20 %
    on touched services.
  - PERF-1: IdP-slow drill p99 flat; breaker opens in game-day; 429 storm
    impossible (TTL present on every cold key).
  - PERF-2: broker-kill drill = zero loss, zero duplicates; DLT drill pages;
    event E2E p99 < 2 s; consumer lag flat at 2× peak.
  - PERF-3: checkout S2S calls per order ≤ 2; feed origin O(1) cached;
    bounded-scan CI green; p95 targets at 10× seeded catalog.
  - PERF-4: assign race test green; single `OrderDelivered` per order;
    SSE budgets reject cleanly at 2× expected connections.
- **Continuous:** nightly `scripts/test-all-apis.py --base-url staging` +
  edge battery; §9.1/§9.2 metric set from the audit guide scraped and
  alert-backed before each phase's prod apply.

---

## 9. Not verified (explicitly not asserted)

- Notification senders' HTTP stack/timeouts (client dir grep empty — layout
  differs; inspect before claiming).
- `InMemoryRateLimitService` map bounds (audit-guide claim, not re-checked).
- OSRM client wiring state beyond config existence.
- Read-replica routing configuration per service.
- Exact pgbouncer runtime values in the cluster (config default 40 per k8s
  tree; cluster may drift).
- Whether any environment currently runs with relay enabled + publisher
  disabled simultaneously (the B2 blackhole is code-verified reachable;
  prevalence unknown).
- Live load measurements — no load tests were executed in this audit turn;
  §8 defines the baseline procedure.

---

# PART II — Consolidated Program (migrated from the removed blueprint)

The sections below carry over the blueprint's program content, re-cut against
Part I's code-truth findings. Cross-references to the old blueprint now point
here.

## 10. Program status triage (the one fact that changes sequencing)

Both source audits agree on ~90 % of content — but both lag the working tree.
Batches A–E (committed at/before `854fe43`) already closed or partially closed
several S0/🔴 items the audits still list as open. Executing the audits as
written would redo closed work and trust stale severity ratings. Triage:

| Item | Audit claim | Working-tree state (verified) |
|---|---|---|
| V-01/#2 wallet race | "no lock, no atomic UPDATE" | **PARTIAL→CLOSED**: `WalletService.credit/debit` uses `findByCustomerIdForUpdate` + same-tx ledger. `@Version`/`CHECK` still absent — finish as hardening (Part I §4.3), not S0. |
| V-02/COD endpoint | "@Transactional on controller, GET mints rows" | **CLOSED (moved)**: flow now `RiderWalletController` → `PaymentClient` (batch A/D). Re-validate `@DecimalMin` + ledger on the payment-side path. |
| #3 OrderSaga | "hollow stub, sync in-tx" | **PARTIAL-CLOSED**: batch A real saga (reserve/charge via internal APIs) + per-order tx dispatch. Remaining: async outbox-driven steps, V-05 self-invocation, publish-failure propagation. |
| V-04 search `findAll()` | "4 unbounded sites" | **CLOSED for search** (batch D bounded LIKE-escaped queries; docs corrected in `854fe43`). **OPEN** for disputes/affiliates/autocomplete/admin lists (Part I §2.1). |
| #5 refresh tokens | "re-issue from access token" | **PARTIAL-CLOSED**: batch E rotation + revocation (identity yml: 15-min access TTL, 30-day refresh horizon verified). Remaining: RS256+JWKS cutover, lockout, TOTP, `jti/iss/aud`. |
| #6 S2S auth | "clients never send X-Service-Token" | **PARTIAL-CLOSED**: batch D mesh clients send the token; every yml now defaults `enforce-internal-paths: true`. Remaining: filter must **reject** absent token on `/internal/**` (pass-through today), IDOR principal-binding sweep. |
| #13 SSE fan-out | "pod-local, no subscriber" | **PARTIAL-CLOSED**: cross-pod Redis relay bridge exists (`RedisOrderLiveRelay`); remaining: O(1) removal, budgets/metrics, broadcast service-auth, dedupe delivery's twin. |
| V-16/#8 breaker | "never decorates" | **OPEN** (re-verified: `CircuitBreakerFilter:39-40`). |
| #8 retry bug | "`toMillisPart()`" | **OPEN** (re-verified: `RetryFilter:34`). |
| V-18/#9 limiter | "INCR+EXPIRE non-atomic" | **OPEN** (re-verified; javadoc now falsely claims atomic — live RC-F). |
| #9 edge rate limit | "gateway has none" | **OPEN** (no `RequestRateLimiter` in gateway). |
| P-03 producer knobs | "missing on active factory" | **OPEN** (no ACKS/IDEMPOTENCE keys). |
| #7/P-05 relay | "publish inside tx, serial" | **OPEN** — actual mechanism differs (Part I §2.4): tx never applies (self-invocation); recoverStale unscheduled; disabled-publisher blackhole (B2). |
| P-01 triangle | "pool 5 vs 200 vs 40" | **OPEN** (re-verified defaults/overlays; HPA `maxReplicas: 12`). |
| P-04 scheduler | "no pool config anywhere" | **OPEN** (zero `spring.task.scheduling` keys; 14 jobs). |
| #10 menu cache | "RedisCacheService unused" | **OPEN** — stronger: **zero L2 cache adoption in production** (Part I §2.3). |

**Consequence:** the money-integrity objective that gated audit Wave 1 is
*materially done*. The binding constraint for heavy traffic is the
throughput/resilience substrate — which is exactly what Part I's PERF-0…5
phases deliver first.

## 11. Target architecture under heavy traffic

```
                        ┌─────────────────────────────────────────────────┐
 clients ──TLS──▶ INGRESS (cert-manager; nginx TLS termination removed — #17)
                        │  Gateway (WebFlux/Netty, ≥3 replicas, HPA, PDB) │
                        │  • Redis bucket rate-limiter per route (#9)     │
                        │  • exact-origin CORS (V-14) • observable 404    │
                        │  • kill-switch non-blocking (V-13, PERF-1.4)    │
                        │  • response-timeout 8–10 s (PERF-0.3)           │
                        └───────┬───────────────────────────┬─────────────┘
                                │ REST (perimeter)           │ SSE (streamed)
                    ┌───────────▼───────────────────────────▼─────────────┐
                    │ SERVICE TIER — 16 services, stateless pods          │
                    │ Tomcat 100 threads · Hikari 20/pod · conn-to 3 s    │  PERF-0
                    │ scheduler pool 12 + relay- pool 2 (isolated)        │  PERF-0/2
                    │ ONE platform WebClient: pooled, timeouts, breaker   │  PERF-1/5
                    │   that OPENS, retry on 5xx/idempotent-only          │
                    │ money paths: idempotency_records + conditional SQL  │  #1/#2/V-01/02
                    │ /internal/**: SERVICE-role JWT REQUIRED (V-15,#6)   │
                    │ (deferred: Istio mTLS permissive→strict; gRPC for   │
                    │  order↔restaurant/payment only if measured — §3.7)  │
                    └───────┬───────────────┬───────────────┬─────────────┘
              L1 Caffeine   │               │               │  bounded executors
              + L2 Redis    │               │               │  (AbortPolicy→DLT) for ext I/O
        ┌───────────────────▼──┐     ┌──────▼──────────┐    ▼
        │ REDIS (Sentinel)     │     │ KAFKA/Redpanda  │  SMTP/Twilio/PSP
        │ namespace-ownership  │     │ acks=all idem   │  (bulkheads)
        │ + per-use pools +    │     │ linger 5 batch  │
        │ timeouts (R-07)      │     │ 32K lz4 (PERF-2)│
        │ wake+invalidate      │     │ .dlt + Default  │
        │ pub/sub only         │     │ ErrorHandler    │
        └──────────┬───────────┘     └──────▲──────────┘
        ┌──────────▼────────────────────────┴───────────┐
        │ DATA PLANE                                    │
        │ PG primary + replica per domain (15 DBs)      │
        │ pgbouncer per-DB pool budget (Part I §4.2)    │
        │ write-fence + lag-budget replicas (V-17)      │
        │ Hibernate batching 50 + saveAll sweeps        │
        │ expression indexes + explain-guard CI         │
        │ single-writer grants per table (G-14, R-08)   │
        │ OUTBOX two-phase relay + recoverStale + DLQ   │  PERF-2
        │ PITR backups per DB, WAL→S3 (#15)             │
        └────────────────────────────────────────────────┘
        OBSERVABILITY: Prometheus+Alertmanager, Tempo/Jaeger (0.15 sampling),
        Loki+FluentBit, logback PII masking, SLO alert set (#14 + audit §9).
```

## 12. Feature → audit-standard traceability

| Deliverable | Audit finding(s) | Class closer / guardrail | Proof it is real |
|---|---|---|---|
| #1 PSP adapter + idempotent payments | V-11, V-09, G-1 | RC-D | replay test: 2 identical requests → 1 wallet credit + 1 `payment_settled` |
| #2 wallet integrity (finish) | V-01/V-02 (lock landed) | RC-A | 500 concurrent debits → never negative; `CHECK` migration |
| #3 async saga completion | V-05, V-09, V-12 | RC-B, G-4 | chaos-kill mid-saga → stuck-order = 0 |
| #4 loyalty ledger | RC-A (R-E loyalty note) | G-14 | refill-after-flush = true; redemption can't overspend |
| #5/#6 authn/authz | V-03, V-13, V-14, V-15, G-3 | RC-C/H | forged/expired/reused token matrix green; `/internal/**` 401 absent-token |
| #7 event backbone | V-09…V-12, V-19, P-03, P-06 | G-1, G-9 | broker-kill IT: rows PENDING w/ backoff; E2E p99 < 2 s (PERF-2 gates) |
| #8 breakers + retry | V-16, P-05, RC-E | G-2, G-13 | StepVerifier OPEN→HALF_OPEN; 4xx retry count = 0 (PERF-1) |
| #9 limiting at scale | V-18 | RC-A, G-2 | Redis-chaos: fail-open counter; TTL present after cold incr (PERF-1) |
| #10 menu snapshot cache | V-06 cache part | RC-G | stampede test → supplier executed once (PERF-3) |
| #11 search engine | V-04, P-08 | G-6 | p95 < 150 ms @300 RPS on 10× catalog |
| #12 geo delivery | V-01-class dup assign | RC-A | concurrent assign → single winner (PERF-4) |
| #13 realtime fan-out (finish) | V-06, V-07, R-G | RC-G, G-7 | cross-pod SSE reconnect; budgets → 503 + metric (PERF-4) |
| #14 observability | audit §7, §9 | — | every §9 metric scrapable; game-day alert fires |
| #15/#16 backup / CI-CD | R-01, G-12, G-15 | RC-F | restore drill < 30 min; digest-pinned; `kubectl apply -k` builds |
| #17 secrets | V-13/V-14/V-15/V-22 | G-3 | prod boot with missing secret = container exit |
| #18 repo structure | R-01…R-08, G-12/13/14 | RC-F | zero-diff `mvn verify`; single k8s/Dockerfile sources |

## 13. Full-program tracks (beyond the PERF scope)

Part I's PERF-0…5 covers the performance substrate. These audit/roadmap items
run as parallel tracks and are unaffected by the consolidation:

- **Security track (U0/U2 items):** TLS-key rotation + history purge +
  `secrets.yaml`/ExternalSecret collision fix + gitleaks (day-1 incident);
  RS256+JWKS dual-key cutover; login lockout/velocity; TOTP; IDOR sweep;
  `/internal/**` reject-absent-token.
- **Domain-truth track:** loyalty/referral durable ledger + abuse ceilings +
  single referral-code generator; PostGIS-vs-bounding-box ADR + OSRM
  wire-or-delete; search engine decision ADR (PG tsvector default, ES on
  measured need).
- **Ops track (#14–#16, U3/U4):** Prometheus/alerts live, Loki/FluentBit +
  PII masking, tracing endpoint + sampling; per-DB backups + WAL→S3 PITR +
  monthly restore drill (<30 min); Trivy/SBOM/cosign, digest pins,
  GitOps, kustomization fixes (missing services, indent, secrets race);
  Sonar ramp steps 2–4.
- **Restructure box (U5, behavior-frozen):** platform-lib module split
  (aggregate shim, two phases); event-schema governance (envelope + CI
  compatibility checks + generated catalog); ownership ADRs + `GRANT SELECT
  ONLY` enforcement; dead-code sweep; single k8s tree + canonical Dockerfile.

### Master dependency spine

```
PERF-0 geometry ─▶ PERF-1 edge/resilience ─▶ Kafka in cluster ─▶ one wiring ─▶
PERF-2 two-phase relay ─▶ DLT + idempotent consumers ─▶ webhook tx (V-11) ─▶
saga events (#3) ─▶ search sync (#11) / SSE replay (#13) ─▶ PERF-3/4 caches &
atomicity ─▶ security cutover ─▶ ops trust (U3/U4) ─▶ restructure box (U5)
P-04 pool bump ──ships with── relay isolation (never alone)
V-18 limiter ──before── V-11 mount ──before── PSP adapter (#1)
R-E/#4 ADRs ──before── #3/#4 code    │    #17 rotation = incident (day 1)
```

## 14. SPOF / bottleneck inventory & program risks

| # | Single point / bottleneck | Blast radius | Mitigation (track) |
|---|---|---|---|
| 1 | pgbouncer 40 vs HPA 12 × pool 20 | every DB request at scale | §4.2 re-pin + CI arithmetic (PERF-0) |
| 2 | single scheduler thread incl. relay | all events + cron | relay- isolation (PERF-0/2) |
| 3 | HS256 shared JWT secret fleet-wide | one leak = platform forgery | RS256+JWKS cutover, dual-key grace (security track) |
| 4 | committed TLS key + `dev-webhook-secret` + secrets.yaml clobbering | active incident / prod secret overwrite | rotation + purge + fail-fast G-3 (day 1) |
| 5 | one Redis group: cache+limits+SSE+fences+flags (R-07) | cache spike slows auth | namespace CSV + per-use pools + declared fail modes (ops track) |
| 6 | gateway = only ingress; two k8s trees drift (R-D/R-01); no catch-all (V-20) | whole edge | single kustomize source + 404 counter + ≥3 replicas + PDB (ops/PERF-0) |
| 7 | `outbox_events` unbounded when relay disabled | silent event loss + table blowup | prod fail-fast boot gate + `enabled=true` overlay + backlog alerts (PERF-2) |
| 8 | Kafka in no k8s manifest (dev-compose only) | backbone can't run in prod | cluster bring-up = hard PERF-2 prerequisite |
| 9 | backups cover 1/15 DBs, RPO=24h, single PVC | total data loss | per-DB dumps + WAL→S3 PITR + monthly restore drill (ops track) |
| 10 | pod-local SSE + `localConsumers` leak + O(N) removal | live tracking dies >1 pod | PERF-4.3 |
| 11 | `RetryFilter` retries POSTs at ≈0 ms backoff | double-charge storms | PERF-1.2 |
| 12 | `:latest` tags + smoke `|| true` + coverage label 50 %≠85 % + broken kustomization indent | undetectable bad deploys | digest pins, Trivy/SBOM/cosign, real gates (ops track) |
| 13 | zero alert rules / no Prometheus scrapes / tracing to localhost | outages found by users | U4 alert set + game-days (ops track) |
| 14 | dual Kafka `@Configuration` bean collision | "enable Kafka" → boot failure | consolidation precedes enablement (PERF-2.3) |

**Program-execution risks** (carried from the blueprint): sequencing
dependencies above are not optional; HS256→RS256 + TLS rotation need
coordinated dual-key grace windows and maintenance windows; behavior
tightening (401/403/429 on previously-permissive paths) requires API-contract
freeze + the `scripts/test-all-apis.py` 401-matrix battery; breakers "finally
opening" read as new 503s — ship permissive thresholds, game-day, then
tighten; doc↔code drift is proven (§10) — re-validate triage at every
gate, never re-fix without evidence of regression; zero-breakage rules
(additive-only migrations, dup-sweep before constraints, regression test
before fix, revert = previous digest < 2 min, edge kill flag per surface).

## 15. Standing acceptance gates (every rollout)

1. **Audit-standard proof** — the finding's own Verification section passes
   (race harness, StepVerifier, DLT IT, k6 A/B −20 % p95, game-day).
2. **Guardrail landed** — the G-n class closer is in CI so the bug cannot be
   re-introduced.
3. **Signal exists** — the audit §9 metric is scraped and alert-backed.

Standing gates: 17-module reactor green · `kubectl apply -k
k8s/overlays/<env>` builds · Sonar new-code 100 % · digest-pinned rollback
< 2 min · edge kill flag per touched surface · per-component dependency
checklist (audit Part V.4) evidenced before each prod apply.

## 16. Caveats

- Part I's drift table reflects spot-verification on batch `854fe43`; items
  marked CLOSED should each get a re-runnable proof test (wallet/COD
  hardening per Part I §4.3 + concurrency tests per Part I §5.4).
- Traffic/pool numbers are auditor arithmetic — re-run the §4.2 CI guard
  against real replica counts before PERF-0 sign-off.
- Istio/gRPC/Avro stay explicitly deferred (§3.7); adopting roadmap §VI
  programs as first-queue work would violate the zero-new-dependency posture.
- `./mvnw verify` and load tests were not executed in either audit turn;
  Part I §8 defines the baseline procedure and per-phase gates.
- Consolidation note: `docs/UNIFIED-IMPLEMENTATION-BLUEPRINT.md` (v1.0,
  2026-09-07) was fully migrated into this file and removed; no other file
  referenced it (verified by grep across `docs/`).
