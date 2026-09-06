# Bhukkad Production-Readiness Audit & Optimization Roadmap

A structured technical audit of the Bhukkad food-delivery backend-server, organized as a set of production-readiness findings. Each finding follows a consistent format:

1. **Feature/Improvement Overview** — what the problem is and its strategic value.
2. **Technical Implementation Roadmap**:
   - *Logic Modifications* — specific code flow / data-structure changes.
   - *Architectural Impact* — design-pattern and decoupling implications.
   - *Dependency Analysis* — new libraries / breaking-change risks.
   - *Complexity & Risk Assessment* — effort estimate and side effects.
3. **Verification** — how to prove the fix; rollback path.

Findings are grouped by severity (🔴 Critical, 🟠 High, 🟡 Medium, 🟢 Low) then by area. The full register is in §3; the implementation roadmap is in §4. Cross-references link findings and guardrails (§6).

---

## Table of Contents

1. [Executive Verdict](#1-executive-verdict)
2. [Root-Cause Taxonomy](#2-root-cause-taxonomy)
3. [Unified Risk Register](#3-unified-risk-register)
4. [Implementation Roadmap (by severity)](#4-implementation-roadmap)
5. [Detailed Findings — Money & Security Critical](#5-detailed-findings--money--security-critical)
6. [Detailed Findings — Performance & Capacity](#6-detailed-findings--performance--capacity)
7. [Detailed Findings — Repository & Architecture](#7-detailed-findings--repository--architecture)
8. [Systemic Guardrails (the class closers)](#8-systemic-guardrails)
9. [Observability Additions](#9-observability-additions)
10. [Verified-Clean Register](#10-verified-clean-register)
11. [Wave-1 Runbook](#11-wave-1-runbook)

---

## 1. Executive Verdict

**Current state: NOT production-ready for high-concurrency traffic.** The system's failure patterns are structural, not incidental — they are catalogued in §2 and each finding is tagged with one.

The single highest-leverage structural fix is the **outbox-atomicity rule** (§8, G-1): money/side-effect paths must be *one transaction* with their event emission. Four findings (V-02, V-09, V-10, V-11) are instances of this rule being absent, and one CI gate + one platform-lib pattern closes the class.

**Go/No-Go:** Conditional GO after Wave 1 (money integrity) + Wave 2-a (edge reactivity) — these two waves close every 🔴 finding. Waves 2-b…2-d close the remaining High/Medium.

### Wave sequencing (full program)

```
W1  money integrity (V-01, V-02)          → correctness gate for everything after
W2-a edge/reactive (V-13, V-03, V-16)     → stop the auth-path latency cliff
W2-b event durability (V-10, V-09, V-12, V-19)  → one train, paired
W2-c limiter→webhook→replica (V-18, V-11, V-17) → ordering is the dependency
W2-d security/edge (V-14, V-15, V-20)
W3  reads/batch (V-04, V-05)
W4  SSE/hygiene (V-06, V-07, V-08, V-21, V-22)
W5  performance config (P-01..P-04)
W6  structure cleanups (P-05, P-06, R-02, R-03, R-06)
W7  restructure box (R-01, R-04, P-10, R-08, R-05 phased)
W8  async/infra (P-07..P-09, R-07)
```

---

## 2. Root-Cause Taxonomy (the analysis layer)

| Class | Mechanism | Findings | Systemic fix |
|---|---|---|---|
| **RC-A** Check-then-act on remote state | read in JVM → decide → write; MVCC/Redis window open | V-01, V-02, V-12, V-18(INCR/EXPIRE), V-19 | Single-statement conditional updates; Lua/CAS; DB arbitration |
| **RC-B** Spring proxy bypass | self-invocation / protected / final ⇒ `@Transactional`, aspects inert | V-05, (V-09 latent) | Collab beans + `@Transactional` placement; ArchUnit: no `@Transactional` on controller or `protected` |
| **RC-C** Blocking I/O in non-blocking context | RestTemplate/JDBC on reactor event loop | V-03 (+V-13 amplifier) | `Schedulers.boundedElastic` or cached-only on hot path; BlockHound |
| **RC-D** Acknowledge-before-handling success | catch→log→return commits offsets / burns dedup tokens | V-09(NoOp), V-10, V-11 | DLPR+DLT; no blanket `catch(Exception){log}` in listeners; enqueue inside tx |
| **RC-E** Resilience components configured but inert | object wired, never recorded/driven | V-16, V-18(aspect+annotation unused), V-20 | Decorate/attach/mount + startup guard + `unmatched` metric (see G-2) |
| **RC-F** Contract/claim drift | javadocs, comments, class docs assert behavior the code lacks | V-10(cls), V-11(javadoc), V-12(javadoc), V-20(comments), (V-03/§8) | "Claim in comment ⇒ test in CI" review rule; fix-or-delete comments |
| **RC-G** Lifecycle leaks (no release/removal) | registry/table/state grows per event | V-06, V-19, V-21 | paired acquire/release or removal sweep in platform-lib |
| **RC-H** Unvalidated/unenforced trust boundary | secret length, CORS star, amount sign, phantom IDs | V-02, V-14, V-15, V-22 | startup fail-fast guards (G-3); input DTO validation |
| **RC-I** Replication/consistency window | readOnly tx→replica without fence/lag budget | V-17 | write-fence + lag router; per-path routing review |
| **RC-J** Shared-blast-radius infrastructure | one Redis/Kafka/PG failure degrades unrelated features | R-07 | namespace ownership + per-use timeout/priority policy |

---

## 3. Unified Risk Register

| ID | Finding (one line) | Sev | Class | Component | Wave |
|---|---|---|---|---|---|
| V-01 | Wallet/COD read-modify-write race → double-spend/lost-credit | 🔴 | RC-A | payment | W1 |
| V-02 | COD credit accepts negative/zero amount, no ledger, tx on controller | 🔴 | RC-A/H | payment | W1 |
| V-03 | JWKS HTTP fetch under `synchronized(this)` on verify path | 🔴* | RC-C/E | platform-lib security | W2-a |
| V-04 | Unbounded `findAll()` + JVM sorts (search ×4, disputes, affiliates) | 🟠 | RC-A-adj | search/supportticket/referral | W3 |
| V-05 | `@Transactional` self-invocation: dispatch batch runs transactionless | 🟠 | RC-B | order | W3 |
| V-06 | SSE O(N) disconnect scan; budget drift; (no empty-list pruning) | 🟡 | RC-A/G | realtime | W4 |
| V-07 | SSE broadcast reverts to blocking send on caller on saturation | 🟡 | RC-D | realtime | W4 |
| V-08 | Dead `LoggingAspect` pointcuts; `wallet-old.bak/` ungreppable tree | 🟢 | RC-F/G | platform-lib/supportticket | W4 |
| V-09 | `OrderSaga` local tx commented; publisher = `NoOpEventPublisher` default | 🔴 | RC-D/B/F | order + platform-lib | W2-b |
| V-10 | Kafka consumers catch-all→ack: events lost; `dlqTopic` unused | 🔴 | RC-D/F | all consumers | W2-b |
| V-11 | Webhook: outbox after commit, failure swallowed; no rate limit despite javadoc | 🟠 | RC-D/F | payment webhook | W2-c |
| V-12 | CQRS `upsertStat` lost update; redelivery double count; false dedup claim | 🟠 | RC-A/F | admin-analytics | W2-b |
| V-13 | Gateway kill-switch: blocking JWKS on Netty event-loop | 🔴 | RC-C | gateway | W2-a |
| V-14 | CORS `("*")` + `allowCredentials(true)` at the edge | 🟠 | RC-H | gateway | W2-d |
| V-15 | `ServiceJwtAuthFilter`: <32B secret = runtime 500 fleet; jjwt/nimbus split | 🟠 | RC-H | platform-lib | W2-d |
| V-16 | `CircuitBreakerFilter` never decorates → circuit cannot open | 🟠 | RC-E/F | platform-lib | W2-a |
| V-17 | Read-replica routing w/o write-fence or lag budget | 🟠 | RC-I | platform-lib datasource | W2-c |
| V-18 | Limiter INCR/EXPIRE race; shared `"default"`; `@RateLimited` zero usages | 🟡→🟠 | RC-A/E | platform-lib | W2-c |
| V-19 | `idempotency_records` cleanup exists only in identity → others grow unbounded | 🟡 | RC-G | platform-lib/payment | W2-b |
| V-20 | No gateway catch-all; "falls to monolith" stale; invisible 404s | 🟡 | RC-F | gateway | W2-d |
| V-21 | Twilio sender logs raw phone numbers | 🟢 | RC-G/H | notification | W4 |
| V-22 | Bad payload → phantom `customer-0` notification | 🟢 | RC-H | notification | W4 |
| P-01 | Sizing triangle collapsed: Tomcat 200 threads : Hikari pool 5 : pgbouncer 40 | 🟠 | RC-E-adj | platform-lib config / k8s | W5 |
| P-02 | Hibernate batching/dispatch-ordering unset → one INSERT per row on bulk paths | 🟠 | — | all JPA services | W5 |
| P-03 | Kafka producer unconfigured: `acks=1` default, no idempotence/linger/batching on money events | 🟠 | — | platform-lib kafka | W5 |
| P-04 | 14 `@Scheduled` jobs share Spring's default **single** scheduler thread | 🟠 | RC-C-adj | platform-lib + services yml | W5 |
| P-05 | HTTP client stack fragmentation (RestClient/WebClient/RestTemplate) | 🟡 | RC-F | order/platform-lib web | W6 |
| P-06 | Outbox relay fixed-delay poll; Redis wake mechanism exists but unwired | 🟡 | — | platform-lib outbox | W6 |
| P-07 | Notification outbound (SMTP/Twilio HTTP 0.1–2 s) executes inline on the Kafka-listener thread | 🟠 | RC-D-adj | notification | W8 |
| P-08 | Search prefix `LIKE 'q%'` needs functional/expression index | 🟡 | — | search | W8 |
| P-09 | JVM container contract only partially present | 🟡 | RC-E | k8s manifests | W8 |
| P-10 | Token verified twice per request (gateway + every service) — decision debt | 🟡 | RC-F | platform-lib security / gateway | W7 (ADR) |
| R-01 | Two competing k8s manifest trees (`k8s/` kustomize vs `services/k8s/` raw + docs) | 🟠 | RC-F | repo | W7 |
| R-02 | 14 per-service Dockerfiles + second template (`services/docker/Dockerfile.service`) | 🟡 | — | repo build | W6 |
| R-03 | `TrieIndex.java` duplicated inside restaurant (two packages) | 🟢 | — | restaurant | W6 |
| R-04 | `Dispute*` classes live in 4 modules with no single-writer-ownership ADR | 🟡 | RC-F | bounded contexts | W7 |
| R-05 | `platform-lib` = 213-file everything-classpath | 🟡 | — | repo | W7 |
| R-06 | Unignored `target-stale/`, `wallet-old.bak/`, `.trash-rootowned/`, `scripts/scripts/` | 🟢 | RC-G | repo | W6 |
| R-07 | Redis shared blast-radius: cache + SSE + rate-limit + write-fence on one Sentinel group | 🟡 | RC-J | k8s/redis + platform-lib | W8 |
| R-08 | Cross-domain entity copies (supportticket holds Order/User/GiftCard repositories) | 🟡 | RC-F | supportticket | W7 |

\* V-03 was rated 🟠 alone; V-13 at the edge promotes the pair to 🔴 (combined they are the auth-path latency cliff).

### §3c Severity rollups (triage view, all 46 findings)

- **🔴 Critical (5):** V-01, V-02, V-03×V-13 (auth-path pair), V-09, V-10 — all closed by W1 + W2-a + W2-b
- **🟠 High (13):** V-04, V-05, V-11, V-12, V-14, V-15, V-16, V-17, P-01, P-02, P-03, P-04, P-07, R-01
- **🟡 Medium (12):** V-06, V-07, V-18, V-19, V-20, P-05, P-06, P-08, P-09, P-10, R-07, R-08 (R-02, R-03 sit with these in practice)
- **🟢 Low (rest):** V-08, V-21, V-22, R-03, R-06 + repo hygiene

---

## 4. Implementation Roadmap (by severity)

### 4.1 Wave 1 — Money Integrity (V-01, V-02)

**Zero-breakage rules:** API contracts frozen; fixes are internal; migrations additive-only; regression test before fix; every fix ships a CI guard.

| Finding | Type | Files | Estimate |
|---|---|---|---|
| V-01 | Atomic conditional update | `WalletService.java`, `WalletBalanceRepository.java`, migration SQL | 2 day |
| V-02 | Validation + transaction boundary | `DeliveryPaymentController.java`, `CodWalletService` (new) | 1 day |

**Gate:** dup-sweep clean; reactor unit + IT; ledger monotonicity SQL; 72 h soak + 1 settlement run.

### 4.2 Wave 2-a — Edge Reactivity (V-03, V-13, V-16)

| Finding | Type | Files | Estimate |
|---|---|---|---|
| V-13 | Offload JWKS fetch to `boundedElastic` | `EdgeKillSwitchFilter.java` | 2 h |
| V-03 | Replace `synchronized` with async-refresh cache | `PlatformJwtValidator.java` | 4 h |
| V-16 | Mount CircuitBreaker on the WebClient filter chain | `CircuitBreakerFilter.java` | 4 h |

**Gate:** BlockHound CI; breaker-open game test; canary 1 pod; 48 h + IdP slow-day drill.

### 4.3 Wave 2-b — Event Durability (V-09, V-10, V-12, V-19)

These four are interlocked — do as one train.

| Finding | Type | Files | Estimate |
|---|---|---|---|
| V-09 | Replace `NoOpEventPublisher`; wire outbox in saga | `OrderService.java`, `OrderEventPublisher.java` | 3 day |
| V-10 | Per-factory `DefaultErrorHandler` + DLT | `KafkaConfig.java`, all consumers | 2 day |
| V-12 | Atomic upsert via native SQL | `AdminCqrsEventConsumer.java`, `RestaurantOrderStatRepository.java` | 1 day |
| V-19 | Centralize idempotency cleanup | `IdempotencyService.java`, new cleanup scheduler in platform-lib | 1 day |

**Gate:** DLT wired + alerted; saga outbox proof; replay drill; 72 h soak.

### 4.4 Wave 2-c — Limiter → Webhook → Replica (V-18, V-11, V-17)

Ordering matters: V-18 must ship before V-11 can mount a real limiter.

| Finding | Type | Files | Estimate |
|---|---|---|---|
| V-18 | Lua atomic incr+expire; resolvers; startup guard | `RedisRateLimitService.java`, `RateLimitAspect.java` | 2 day |
| V-11 | Single-tx webhook service; mount limiter | `WebhookService.java` (new) | 1 day |
| V-17 | Write fence + lag budget | `ReadReplicaRoutingDataSource.java`, new `WriteFence` | 2 day |

**Gate:** 429s observed only on abuse; stale-read IT; lag metric; 72 h.

### 4.5 Wave 2-d — Edge Security (V-14, V-15, V-20)

| Finding | Type | Files | Estimate |
|---|---|---|---|
| V-14 | Env-driven explicit CORS origins + fail-fast | `GatewayCorsConfig.java` | 2 h |
| V-15 | Build key once at construction; fail-fast <32B | `PlatformJwtValidator.java` | 2 h |
| V-20 | Observable 404 route + normalized counter | `GatewayConfig.java` | 2 h |

**Gate:** preflight rejects; evil-origin 403-CORS; startup matrix; 48 h.

### 4.6 Wave 3 — Reads & Batch (V-04, V-05)

| Finding | Type | Files | Estimate |
|---|---|---|---|
| V-04 | DB-side filtering + cap + cache | `SearchServiceImpl.java`, `DisputeResolutionServiceImpl.java` | 2 day |
| V-05 | Collaborator bean pattern | `ScheduledOrderProcessor.java` | 1 day |

**Gate:** parity diff; chaos-kill stuck-order=0; 72 h.

### 4.7 Wave 4 — SSE & Hygiene (V-06, V-07, V-08, V-21, V-22)

| Finding | Type | Files | Estimate |
|---|---|---|---|
| V-06 | O(1) disconnect via `emitterHome` map + `Semaphore` budget | `OrderSseStreamServiceImpl.java` | 2 day |
| V-07 | Fixed executor with `AbortPolicy` never `CallerRuns` | `OrderSseStreamServiceImpl.java` | 1 day |
| V-08 | Delete dead pointcuts + `.bak` tree | repo cleanup | 1 h |
| V-21 | Phone-number redaction in logs | `TwilioSmsSender.java` | 1 h |
| V-22 | Throw `PoisonEventException` instead of `customer-0` | `NotificationEventConsumer.java` | 1 h |

**Gate:** soak churn; repo hygiene; 48 h.

### 4.8 Wave 5 — Performance Config (P-01…P-04)

**These are config-level with the largest measured throughput effect per engineer-hour.** They gate any "scale-out" claim.

| Finding | Type | Files | Estimate |
|---|---|---|---|
| P-01 | Right-size Hikari/Tomcat/pgbouncer triangle | all `application.yml`, pgbouncer config | 2 h |
| P-02 | Hibernate batching/dispatch-ordering | all `application.yml` | 2 h |
| P-03 | Kafka producer: `acks=all`, `linger.ms=5`, `batch.size=32KB` | `KafkaConfig.java` | 1 h |
| P-04 | Scheduler pool >1; isolate relay executors | `application.yml`, `OutboxPlatformConfig.java` | 4 h |

**Gate:** k6 A/B p95 & p99 ≤ −20% per touched service; event E2E latency p99 < 1 s; 72 h.

### 4.9 Wave 6 — Structure Cleanups (P-05, P-06, R-02, R-03, R-06)

| Finding | Type | Files | Estimate |
|---|---|---|---|
| P-05 | Parameterize shared `WebClient.builder` bean | `WebClientConfig.java`, `RestaurantClient.java` | 1 day |
| P-06 | Redis wake mechanism or accept poll-latency floor | `OutboxPollPublisher.java` | 0.5 day |
| R-02 | Consolidate to single Dockerfile template | `Dockerfile`, `Dockerfile.service` | 1–2 day |
| R-03 | De-duplicate `TrieIndex.java` | `restaurant/` | 1 h |
| R-06 | Gitignore sweep + delete stale `.bak`/`.trash` | `.gitignore`, repo cleanup | 1 h |

**Gate:** single-HTTP-stack review passes; image digest equality; relay latency metric; 48 h.

### 4.10 Wave 7 — Restructure Box (R-01, R-04, P-10, R-08, R-05 phased)

This is a hard box — no major feature expansion until complete.

| Finding | Type | Files | Estimate |
|---|---|---|---|
| R-01 | Single k8s source, deprecate `services/k8s/*.yaml` | repo restructure | 1 day |
| R-04 | Dispute-ownership ADR (writes decision first) | `docs/adr/` | 1 day ADR + 3–5 day code |
| P-10 | Token-verify decision table (docs debt) | `docs/adr/` | 2 h |
| R-08 | Read-only enforcement on cross-domain repos | `supportticket`, DB grants | 1 day |
| R-05 | Split platform-lib into modules | module split | 3–5 day phased |

**Gate:** kustomize-lint only-source gate; ADRs merged; module split with zero behavior diff; 2-week box.

### 4.11 Wave 8 — Async & Infra (P-07…P-09, R-07)

| Finding | Type | Files | Estimate |
|---|---|---|---|
| P-07 | Async notification dispatch with bounded executor | `NotificationEventConsumer.java` | 1 day |
| P-08 | Search index verification (pg_trgm or varchar_pattern_ops) | migration SQL, CI test | 2 h |
| P-09 | JVM container contract: requests=limits, `ExitOnOutOfMemoryError` | k8s manifests | 1–2 day |
| R-07 | Redis namespace ownership + per-pool isolation | `docs/redis-ownership.csv`, config | 1 day |

**Gate:** notification consumer-lag flat at 2× peak k6; index-explain proof; OOM-kill drill; 72 h.

---

## 5. Detailed Findings — Money & Security Critical

> Template per finding: **Analysis** (evidence → root cause → failure timeline) · **Remediation** (design + complete code + SQL) · **Verification** · **Rollout/rollback** · **Guardrail** · **Cross-refs**.

---

### V-01 — Wallet/COD read-modify-write race (double-spend) 🔴 RC-A

**Feature/Improvement Overview:** The wallet debit/credit path reads balance → checks → writes in separate statements with no lock. Under concurrency, two debits can both read the same balance, both pass the check, and both write — allowing overspending. This is the most severe money-integrity bug. Strategic value: eliminates the risk of permanent, non-reconcilable money loss at scale.

**Logic Modifications:**
1. Add `@Version` for optimistic locking on `WalletBalance` and `AgentCodWallet`.
2. Replace read-then-write with a single atomic conditional UPDATE:
   ```sql
   ALTER TABLE wallet_balances ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
   ALTER TABLE wallet_balances ADD CONSTRAINT uk_wallet_balance_customer UNIQUE (customer_id);
   ```
3. Repository:
   ```java
   @Modifying
   @Query("""UPDATE WalletBalance wb SET wb.balance = wb.balance + :delta,
              wb.updatedAt = :now, wb.version = wb.version + 1
              WHERE wb.customerId = :customerId AND wb.balance + :delta >= 0""")
   int adjustBalance(@Param("customerId") Long id, @Param("delta") BigDecimal delta,
                     @Param("now") LocalDateTime now);
   ```
4. Service keeps identical contract; credit → `ensureWalletExists`; debit → 0 rows + `existsByCustomerId` distinguishes 404 vs `BusinessException("Insufficient wallet balance")`; ledger row inside same transaction.

**Architectural Impact:** Moves from pessimistic-per-call to optimistic-with-fallback semantics. Keeps the service as the single authority. The `@Version` addition is additive and backward-compatible.

**Dependency Analysis:** No new libraries. The `@Version` + conditional UPDATE pattern is standard JPA/Hibernate.

**Complexity & Risk:** **Medium.** Migration must run dup-sweep first (`SELECT customer_id, count(*) FROM wallet_balances GROUP BY customer_id HAVING count(*) > 1`). Regression test must fail on current code, pass after.

**Verification:** `race(threads, action)` harness: 4× concurrent debit(500) on 1000 → exactly two succeed, final 0, never negative; concurrent credit(100)×4 → final 400 no lost credit.

**Rollout/revert:** Migration additive → revert-deploy safe.

**Cross-refs:** V-02 (same entities), V-12 (same class via Kafka), V-19 (dedup rows).

---

### V-02 — COD credit: no validation, no ledger, tx on controller 🔴 RC-A/H

**Feature/Improvement Overview:** The `DeliveryPaymentController.creditCodWallet` endpoint accepts negative/zero amounts, executes writes on a GET, and maintains no ledger. A negative amount silently drains the wallet with only a log line as evidence. Strategic value: prevents silent money destruction and satisfies audit/compliance trail requirements.

**Logic Modifications:**
1. Add `@DecimalMin("0.01")` validation on `amount` param + `@Validated` on controller.
2. Move `@Transactional` off controller → delegate to `CodWalletService.credit()`.
3. Add ledger table:
   ```sql
   CREATE TABLE IF NOT EXISTS cod_wallet_ledger (
     id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
     agent_id BIGINT NOT NULL, type VARCHAR(6) NOT NULL,
     amount NUMERIC(14,2) NOT NULL,
     balance_after NUMERIC(14,2) NOT NULL,
     reference VARCHAR(200),
     created_at TIMESTAMP(6) NOT NULL DEFAULT localtimestamp);
   CREATE INDEX idx_cod_ledger_agent ON cod_wallet_ledger(agent_id, created_at);
   ```
4. Mirror V-01's atomic `adjustByAgentId` with signum guard.

**Architectural Impact:** Enforces the controller-delegates-to-service pattern. The ledger table is a new audit surface that must be owned by payment (R-04).

**Dependency Analysis:** No new libraries.

**Complexity & Risk:** **Low-Medium.** Contract-frozen: path & param unchanged. Negative/zero/null → 400 (announced behavior tightening). Migration additive only.

**Verification:** negative/zero/null → 400; 0.01 → 200 with ledger row; race test credit×2 no lost credit; `@GetMapping("/cod-wallet")` asserts no INSERT.

**Cross-refs:** V-01.

---

### V-03 — JWKS fetch under global `synchronized` on verify path 🔴 RC-C/E (with V-13)

**Feature/Improvement Overview:** `PlatformJwtValidator.jwksCache()` performs an HTTP fetch to the JWKS endpoint inside `synchronized(this)`. On cache expiry or cold start, all verifying threads serialize on a single monitor, creating an auth-path latency cliff. Combined with V-13 (gateway blocking on event-loop), this is the worst-case production failure scenario. Strategic value: prevents complete edge outage triggered by identity provider hiccups.

**Evidence (verbatim):** `jwksCache()` fast path exists but miss path does `restClient.get().retrieve().body(String)` **inside `synchronized(this)`**; no failure backoff; 1h TTL only.

**Logic Modifications:**
```java
private volatile JWKSet cachedJwks;
private volatile long lastFetchMillis, lastFailedFetchMillis;
private final AtomicBoolean refreshInProgress = new AtomicBoolean();
private static final long BACKOFF = 30_000;

private JWKSet jwksCache() {
    JWKSet cur = cachedJwks; long now = System.currentTimeMillis();
    if (cur != null && now - lastFetchMillis < JWKS_TTL_MILLIS) return cur;      // warm
    if (now - lastFailedFetchMillis < BACKOFF) return cur;                        // stale on failing IdP
    if (cur != null) scheduleRefresh();                                          // stale serve + async refresh
    return cur != null ? cur : blockingInitialFetch();                            // ONLY cold path blocks
}
```
`scheduleRefresh` = `compareAndSet` + single-thread pool swapping `cachedJwks`. RestClient pinned `connect 2s / read 3s`; pre-warm on `ApplicationReadyEvent`; metrics `jwks_refresh_{duration,outcome}`.

**Architectural Impact:** Converts a global lock into a stampede-prevention cache. The stale-while-refresh pattern is standard for auth-key distribution.

**Dependency Analysis:** No new libraries. Uses existing `RestClient` (Spring 6).

**Complexity & Risk:** **Medium.** Must verify exactly-once fetch under concurrent expiry. Failure backoff is critical — if IdP is down, must not retry every ttl-window.

**Verification:** two concurrent expiries → one HTTP call (mock counts); slow-IdP: verify answers with stale set + p99 unaffected; cold-start bounded by timeouts.

**Rollout/revert:** platform-lib patch → services adopt async; rollback reverts lib.

**Cross-refs:** V-13–V-15.

---

### V-09 — `OrderSaga` hollow + default `NoOpEventPublisher` 🔴 RC-D/B/F

**Feature/Improvement Overview:** The order creation saga has its step bodies commented out ("In the extracted service this would be:") and delegates to `NoOpEventPublisher` by default (`ExternalEventsProperties.enabled=false`). In production, either events never fire (silent data loss) or fire-and-forget publishes can drop on broker blip with zero signal. Strategic value: makes event delivery the core reliability contract for all money-adjacent flows.

**Evidence (verbatim):** `orderEventPublisher.orderStatusChanged(...)` after `orderRepository.save(order)` — if the publisher is NoOp, the event is silently lost.

**Logic Modifications:**
1. Steps become `@Transactional` collaborators writing state + outbox row in one tx.
2. Delete direct `publish()` from `KafkaPlatformEventPublisher` — force `@Deprecated(forRemoval)` that throws:
   ```java
   @Deprecated(forRemoval = true)
   default void publish(PlatformEventMessage e) {
       throw new UnsupportedOperationException("use OutboxEventService — fire-and-forget is RC-D");
   }
   ```
3. `NoOpEventPublisher` keeps working only as per-eventType warn-once + `event_published_NOOP{eventType}` counter.
4. Prod `ApplicationReadyEvent` gate: refuse kafka-less boot.

**Architectural Impact:** Enforces the outbox pattern (G-1) across all saga-driven flows. Moves from fire-and-forget to publish-and-confirm.

**Dependency Analysis:** No new libraries. Uses existing outbox infrastructure.

**Complexity & Risk:** **High.** Must ensure every event-emitting path goes through the outbox. Integration test must verify outbox row grows per saga step, row stays PENDING on broker down.

**Verification:** integration: `@TestPropertySource(app.events.external.type=log)` + probe ⇒ `OutboxEvent` row count grows per saga step; broker down ⇒ row PENDING, relay delivers exactly-once post-recovery.

**Cross-refs:** V-10, V-11, V-12.

---

### V-10 — Consumers acknowledge poison/broken events 🔴 RC-D/F

**Feature/Improvement Overview:** All four `@KafkaListener` consumers use `try { parse; act } catch (Exception) log.error(...)` — this commits the offset (acks the message) even on failure, silently dropping events. The configured `dlqTopic` has no consumer. Strategic value: prevents silent revenue/event loss from transient DB blips or schema drift.

**Evidence (verbatim):** `onOrderEvent(String payload)`: catch-all log + ack = lost `OrderCreated` for admin-analytics or notification.

**Logic Modifications:**
1. Per-factory `DefaultErrorHandler`:
   ```java
   DefaultErrorHandler(DeadLetterPublishingRecoverer(kafkaTemplate,
       (rec, ex) -> new TopicPartition(dlqTopic, rec.partition())),
       new ExponentialBackOffWithMaxRetries(3));
   ```
2. Delete blanket `catch (Exception)` from listener bodies.
3. DLT message gains headers: `x-failed-topic`, `x-error-class`, `x-schema-version`.
4. Alerts: `kafka_consumergroup_lag{topic~".*dlt"} > 0` → page money-topics.

**Architectural Impact:** Establishes dead-letter handling as a platform standard. The DLT becomes the recovery surface for all event-driven systems.

**Dependency Analysis:** No new libraries. Uses Spring Kafka's `DefaultErrorHandler` and `DeadLetterPublishingRecoverer`.

**Complexity & Risk:** **Medium.** Must ensure all consumers are idempotent (V-12 pairing) before enabling replay. The retry/backoff parameters need tuning.

**Verification (Testcontainers-Redpanda):** DB outage injected on record N+1 ⇒ N applied, N+1 retries×3, lands DLT with headers, never lost; malformed ⇒ DLT immediately; valid after ⇒ applied; committed exactly once.

**Cross-refs:** V-09/V-12 dependency.

---

### V-11 — Webhook atomicity + phantom limiter 🟠 RC-D/F

**Feature/Improvement Overview:** The payment webhook endpoint does: complete payment (tx1) → markProcessed (REQUIRES_NEW, burns eventId) → enqueue in try/catch with `log.warn` only. A failure between settlement and enqueue means a settled payment with no event — and provider redelivery fast-paths to dedup into nothing. The javadoc claims rate limiting that doesn't exist. Strategic value: makes webhook processing exactly-once-safe and observable.

**Evidence (verbatim):** `completeWebhookPayment(gatewayOrderId, gatewayPaymentId);` → `markProcessed` (REQUIRES_NEW) → `enqueue` in try/catch log.warn only.

**Logic Modifications:**
```java
@Transactional
public WebhookOutcome completeFromWebhook(String orderId, String payId, String eventId) {
    if (idempotency.alreadyClaimed(eventId)) return WebhookOutcome.duplicate();
    Payment p = completeWebhookPayment(orderId, payId);      // same tx
    idempotency.claimSameTx(eventId);                        // insert in THIS tx → conflict = dup
    outbox.enqueue("PAYMENT_WEBHOOK_RECEIVED", p.getOrderId(), Map.of(...));
    return WebhookOutcome.done(p.getId());
}
```
+ Mount real limiter after V-18 ships: `@RateLimited(bucket="razorpay-webhook", limit=600, windowSeconds=60)`.

**Architectural Impact:** Eliminates the TOCTOU gap between settlement and event emission. The single-transaction approach is the canonical fix for webhook reliability.

**Dependency Analysis:** Requires V-18 limiter to be deployed first. Uses existing `IdempotencyService` and outbox.

**Complexity & Risk:** **Medium.** The `claimSameTx` pattern requires a unique `(scope, key)` index on idempotency_records — verify it exists.

**Verification:** enqueue throws ⇒ payment NOT settled (no dedup row, retry completes); forged-signature flood: 401 before DB work; valid flood ⇒ 429 after 600/min.

**Cross-refs:** V-18 (limiter substrate), V-02 (COD side), V-19 (dedup cleanup).

---

### V-12 — CQRS lost-update/double-count + lying class-doc 🟠 RC-A/F

**Feature/Improvement Overview:** `AdminCqrsEventConsumer.upsertStat` does findById → count+1 → revenue.add → save with no locking. Concurrent events for the same restaurant lose updates. The class-doc claims "re-delivered events only re-increment once per unique order id" — no dedup exists. Strategic value: ensures admin revenue metrics are accurate under load.

**Evidence (verbatim):** `AdminCqrsEventConsumer.java:56-64`:
```java
RestaurantOrderStat stat = statRepository.findById(restaurantId).orElseGet(() -> {
    RestaurantOrderStat s = new RestaurantOrderStat();
    s.setRestaurantId(restaurantId);
    return s;
});
stat.setOrderCount(stat.getOrderCount() + 1);
stat.setRevenue(stat.getRevenue().add(total));
statRepository.save(stat);
```

**Logic Modifications:**
1. Native upsert:
   ```java
   @Modifying @Transactional
   @Query(value="""INSERT INTO restaurant_order_stats (restaurant_id, order_count, revenue, updated_at)
       VALUES (:rid, 1, CAST(:total AS numeric), localtimestamp)
       ON CONFLICT (restaurant_id) DO UPDATE SET
         order_count = restaurant_order_stats.order_count + 1,
         revenue = restaurant_order_stats.revenue + CAST(:total AS numeric),
         updated_at = localtimestamp""", nativeQuery = true)
   int upsertIncrement(@Param("rid") long rid, @Param("total") BigDecimal total);
   ```
2. Dedup: `idempotency.tryAcquireOnce("ADMIN_PROJECTION", event.getEventId())` before upsert, in same tx.
3. Fix javadoc.

**Architectural Impact:** Converts a read-modify-write to an append-only atomic upsert. Pairs with V-10 (events may now be replayed safely) and V-19 (dedup cleanup).

**Dependency Analysis:** Requires the `idempotency` module from platform-lib. PostgreSQL `ON CONFLICT` native query.

**Complexity & Risk:** **Low-Medium.** Must add `uk_restaurant_order_stats_id` unique constraint via dup-sweep migration. The idempotency pairing is critical — without V-10's DLT, replay is unsafe.

**Verification:** 2 threads ×4 events same restaurant ⇒ `count==8, revenue==Σ`; replay same eventId twice ⇒ 1.

**Cross-refs:** Enabled by V-10, protected by V-19.

---

### V-13 — Blocking JWKS on gateway event loop 🔴 RC-C

**Feature/Improvement Overview:** `EdgeKillSwitchFilter.subjectId(exchange)` is called eagerly at the top of `filter()`, before any `Mono` wrapping. If the `PlatformJwtValidator` needs to fetch JWKS (cache miss), it performs blocking HTTP + RSA parsing on the Netty event loop thread. With V-03's `synchronized`, the whole gateway serializes. Strategic value: prevents complete edge unavailability from IdP latency.

**Evidence (verbatim):** `Long subject = subjectId(exchange);` at line 57 of `EdgeKillSwitchFilter.java` — computed eagerly, before any reactive wrapping.

**Logic Modifications:**
```java
@Override
public Mono<Void> filter(ServerWebExchange ex, GatewayFilterChain chain) {
    Route r = ex.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
    if (!(r != null && r.getMetadata().get(ROUTE_FLAG_METADATA) instanceof String fk)) return chain.filter(ex);
    return Mono.fromCallable(() -> subjectId(ex))                 // every blocking piece…
               .subscribeOn(Schedulers.boundedElastic())         // …off the loop
               .defaultIfEmpty(0L)
               .flatMap(sub -> flags.isRouteEnabled(fk, sub == 0L ? null : sub))
               .flatMap(on -> on ? chain.filter(ex) : disabled(ex, fk, r.getId()));
}
```

**Architectural Impact:** Restores reactive discipline to the edge filter. The `subjectId` computation is now properly deferred and scheduled.

**Dependency Analysis:** Requires `Schedulers.boundedElastic()` from Reactor — already available via Spring WebFlux.

**Complexity & Risk:** **Low.** The change is isolated to the filter. Must preserve the fail-open semantics of `subjectId` returning null.

**Verification:** BlockHound test in gateway module — any `Thread.sleep/socket` on nio thread fails; blocking fake validator (500 ms sleep) under 200-concurrency — event loop unaffected; p99 regression budget: kill-switch filter adds ≤ 2 ms.

**Cross-refs:** V-03 substrate.

---

### V-14 — CORS wildcard + credentials 🟠 RC-H

**Feature/Improvement Overview:** `GatewayCorsConfig` sets `addAllowedOriginPattern("*")` with `setAllowCredentials(true)`. This is a known browser-vulnerability pattern: any website can make credentialed cross-origin requests to the API. Strategic value: closes a CSRF-like attack vector that could exfiltrate admin endpoints or trigger state changes cross-site.

**Evidence (verbatim):** `config.addAllowedOriginPattern("*")` + `config.setAllowCredentials(true)` in `GatewayCorsConfig.java:22-23`.

**Logic Modifications:**
```java
@Configuration
public class GatewayCorsConfig {
    @Bean
    public CorsWebFilter corsWebFilter(
            @Value("${app.cors.allowed-origins:}") List<String> origins,
            @Value("${spring.profiles.active:dev}") String profile) {
        if ("prod".equals(profile) && origins.stream().anyMatch(o -> o.contains("*")))
            throw new IllegalStateException("prod CORS must not use wildcard");
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowCredentials(true);
        origins.forEach(config::addAllowedOriginPattern);
        config.addAllowedHeader("*");
        config.addAllowedMethod("*");
        config.setMaxAge(600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return new CorsWebFilter(source);
    }
}
```
prod/staging configmap + Vault path supply real frontend origins; `/api/**` only; `maxAge=600`.

**Architectural Impact:** Moves CORS origins to externalized configuration. The fail-fast on prod wildcard prevents accidental deployment.

**Dependency Analysis:** No new libraries. Uses Spring's `@Value` injection.

**Complexity & Risk:** **Low.** Must ensure all legitimate frontend origins are configured before deploying to prod.

**Verification:** `Origin: https://evil.example` preflight ⇒ no `Access-Control-Allow-Origin`; configured origin ⇒ 200 + headers; prod profile with `*` ⇒ container exit.

**Cross-refs:** V-20 same deploy train (edge harden pair).

---

### V-15 — Service JWT: weak secret = runtime 500; dual stacks 🟠 RC-H

**Feature/Improvement Overview:** `PlatformJwtValidator` builds the HMAC key per-request with `Keys.hmacShaKeyFor(secret.getBytes())`. jjwt/nimbus <256-bit secret throws `WeakKeyException` on first request with `X-Service-Token` → 500 across the service mesh. The codebase has dual stacks (jjwt for service tokens, Nimbus for user tokens). Strategic value: prevents cascading auth failures from a misconfigured secret.

**Evidence (verbatim):** `PlatformJwtValidator.java:93-94`: `Keys.hmacShaKeyFor(secret.getBytes())` — no length validation, no fail-fast.

**Logic Modifications:**
```java
public PlatformJwtValidator(PlatformJwtProperties props) {
    byte[] bytes = props.secret() == null ? new byte[0]
               : props.secret().getBytes(StandardCharsets.UTF_8);
    if (props.requiredInProfile(activeProfile) && bytes.length < 32)
        throw new IllegalStateException("APP_AUTH_SERVICE_JWT_SECRET must be >=32 utf8 bytes");
    this.key = bytes.length >= 32 ? Keys.hmacShaKeyFor(bytes) : null;
}
```
Migration plan: Nimbus-only (parse S2S via same validator; retire jjwt in next minor).

**Architectural Impact:** Centralizes key construction and validates at boot. Reduces the secret-validation failure surface from "first request in prod" to "container startup."

**Dependency Analysis:** No new libraries. The migration to Nimbus-only removes jjwt but is a separate effort.

**Complexity & Risk:** **Low.** Must add `requiredInProfile` check. The fail-fast is a breaking change for any environment with a short secret, but that is intentional.

**Verification:** short secret ⇒ context fails boot with message; 32+ secret ⇒ valid token 200, expired 401, wrong alg (`none`/`HS384` downgrade) rejected; metrics `service_auth_rejected{reason}` on every reject.

**Cross-refs:** V-03/V-13 edge pair same deploy.

---

### V-16 — Circuit breaker can never open 🟠 RC-E/F

**Feature/Improvement Overview:** `CircuitBreakerFilter` has the error-handling logic (`onErrorResume` checking `OPEN` state) but **never records outcomes** — no `CircuitBreakerOperator`, no `.transformDeferred`, no manual `onSuccess/onError`. Resilience4j state transitions only happen via recorded calls, so `State.OPEN` branch is dead code despite class javadoc promising it. Strategic value: provides actual fail-fast isolation instead of a false sense of safety.

**Evidence (full class verified):** `CircuitBreakerFilter.java:36-45`: `onErrorResume` checks `circuitBreaker.getState()` but nothing ever calls `onError`/`onSuccess` on the circuit breaker. The `CircuitBreaker` is never transformed into the reactive chain.

**Logic Modifications:**
```java
@Override
public Mono<ClientResponse> filter(ClientRequest req, ExchangeFunction next) {
    return Mono.defer(() -> next.exchange(req))
        .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))   // records success/failure
        .timeout(Duration.ofMillis(props.getTimeoutMs()))
        .onErrorResume(CallNotPermittedException.class, e -> Mono.just(
            ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE).header("X-Circuit","open").build()));
}
```
+ Per-target breaker from `CircuitBreakerRegistry` (`slidingWindowSize=20, failureRateThreshold=50%, slowCallDuration=2s, slowCallRateThreshold=80, waitInOpen=10s, halfOpenMaxAttempts=1`).
+ Export `circuit_breaker_{open,state}`.

**Architectural Impact:** Converts a decorative filter into an active circuit breaker. Each downstream service gets its own breaker instance, enabling per-service fail-fast.

**Dependency Analysis:** Uses Resilience4j `CircuitBreakerOperator` (already a dependency via `resilience4j-reactor`).

**Complexity & Risk:** **Medium.** Must wire the `CircuitBreakerOperator` via `transformDeferred` — the current code's mistake is using `onErrorResume` as a side-channel instead of the proper recording operator.

**Verification (StepVerifier):** ≥50% of 20 calls fail ⇒ state OPEN, next call fast-503 **without** touching `ExchangeFunction`; timeout counts as failure; HALF_OPEN single probe success closes.

**Cross-refs:** V-13/V-03.

---

### V-17 — Read-replica staleness with no fence, no lag budget 🟠 RC-I

**Feature/Improvement Overview:** Every `readOnly=true` transaction round-robins to replicas with no write-fence mechanism and no lag budget. A write on the primary followed by a read can hit a stale replica, making user changes appear to vanish. Strategic value: provides user-visible read-after-write consistency without abandoning replica scaling.

**Evidence:** `ReadReplicaRoutingDataSource.java:33-40`: `determineCurrentLookupKey()` returns replica if `ReadReplicaContext.get() == REPLICA` or `isCurrentTransactionReadOnly()` — no subject-based fence check, no lag awareness.

**Logic Modifications:**
1. **Write fence** — on primary write commit, set a short TTL Redis key:
   ```java
   void afterCommit(Object subject) { redis.opsForValue().set("fence:"+subject, "1", Duration.ofSeconds(5)); }
   boolean isFenced(Object subject) { return Boolean.TRUE.equals(redis.hasKey("fence:"+subject)); }
   ```
2. Routing integration:
   ```java
   protected Object determineCurrentLookupKey() {
       if (writeFence.isFenced(ReplicaRoutingContext.currentSubject())) return PRIMARY;
       if (readReplicaEligible()) return selector.nextAvailableWithinLag(lagBudgetSeconds);
       return PRIMARY;
   }
   ```
3. **Lag budget:** scheduled `SELECT EXTRACT(EPOCH FROM replay_delay) FROM pg_stat_replication` on primary; `markLagging(host)` if lag > 3 s.

**Architectural Impact:** Adds a write-fence layer between the application and the read-replica selector. The subject (JWT `sub`) is the fence key.

**Dependency Analysis:** Requires Redis (already present). The `pg_stat_replication` query requires PG 10+ (present).

**Complexity & Risk:** **High.** The fence must be installed in the transaction-commit interceptor (after-commit callback). The lag monitor adds a scheduled task. Must not break services without replicas.

**Verification:** IT (two Testcontainers PG with real streaming, `pg_wal_replay_resume()`/`pg_wait_for_notify`): write→read same subject ⇒ fresh; >3 s lag ⇒ auto-primary routing.

**Cross-refs:** V-04 (caches must not paper over this).

---

### V-18 — Rate limiter: race + inert + shared key 🟡→🟠 RC-A/E

**Feature/Improvement Overview:** `RedisRateLimitService` does `INCR` then conditional `EXPIRE` — if the process crashes between, the counter has no TTL (permanent denial until manual delete). The shared `"default"` bucket key means a single slow consumer can cause platform-wide self-DoS. `@RateLimited` has zero usages — the aspect + annotation exist but nothing mounts them. Strategic value: transforms an inert annotation into a working, safe rate limiter.

**Evidence:** `RedisRateLimitService.java:30-34`:
```java
Long count = redisTemplate.opsForValue().increment(key);
if (count != null && count == 1) {
    redisTemplate.expire(key, windowSeconds, TimeUnit.SECONDS);
}
```
This is a two-command sequence — between `INCR` (count==1) and `EXPIRE`, another caller can increment and both miss the TTL set.

**Logic Modifications:**
1. Lua atomic script:
   ```lua
   local key = KEYS[1]
   local ttl = ARGV[1]
   local count = redis.call('INCR', key)
   if count == 1 then
     redis.call('PEXPIRE', key, ttl)
   end
   return count
   ```
2. Real resolvers: `@Bean` = `max(authSubject ?: clientIp)`.
3. Startup guard: in prod, `RateLimitAspect` requires the custom resolver.
4. Mount on public money paths (after V-11 ships).

**Architectural Impact:** Converts a race-prone two-command rate limiter to a single atomic Lua operation. The resolver injection makes rate limiting per-user/per-IP instead of a shared default.

**Dependency Analysis:** No new libraries. Uses Redis Lua scripting (already supported by `StringRedisTemplate`).

**Complexity & Risk:** **Medium.** The Lua script must be tested for edge cases (key expiry mid-script). The resolver must handle anonymous callers safely.

**Verification:** chaos between INCR/EXPIRE (mock) ⇒ no key without TTL; 200 threads same key ⇒ count exact; aspect unit: two users distinct buckets; webhook IT 429 at limit boundary.

**Cross-refs:** V-02/V-11 mount points.

---

### V-19 — `idempotency_records` leak outside identity 🟡 RC-G

**Feature/Improvement Overview:** `IdempotencyRecordRepository` + `idx_idempotency_expires` exist in platform-lib, but the cleanup scheduler only lives in the `identity` service. Payment (`WebhookIdempotencyService`, 48 h TTL rows forever), and any future adopter → unbounded table growth per service. Strategic value: prevents silent storage exhaustion from replayed events.

**Evidence:** The platform-lib `IdempotencyService` uses Redis (`idempotency:order:` / `idempotency:payment:` prefixes) — the PostgreSQL `IdempotencyRecord` entity/repository is defined but the cleanup is in `identity` only.

**Logic Modifications:**
1. Move the cleanup scheduler → platform-lib autoconfig `@ConditionalOnProperty(app.idempotency.cleanup.enabled, matchIfMissing=true)`.
2. `@Scheduled(cron = "0 0 * * * *")` `@SchedulerLock("idempotency-cleanup")` + batched loop (`deleteBatch(5000) while > 0`).
3. `idempotency.cleaned{service}` counter.
4. Identity deletes its copy (single source).

**Architectural Impact:** Centralizes idempotency cleanup in platform-lib as a shared, autoconfigured capability. Services opt in via property.

**Dependency Analysis:** No new libraries. Uses existing `@Scheduled` + ShedLock pattern.

**Complexity & Risk:** **Low-Medium.** The first rollout on big legacy tables needs a batched sweep + vacuum. The `@ConditionalOnMissingBean` ensures only one instance per service.

**Verification:** IT: expired gone, live kept, `expiresAt` indexed; identity boot ⇒ one bean only.

**Cross-refs:** V-11/V-12 produce rows it retains.

---

### V-20 — Gateway: no fallback, stale claims, invisible 404s 🟡 RC-F

**Feature/Improvement Overview:** The gateway route table in `GatewayConfig.java` terminates at 13 routes (notification, survey, customer sub-routes, search, referral, support, live, growth, inventory, restaurant, identity, order, payment, delivery, admin-analytics). There is **no catch-all route** — any unmatched path (e.g. a legacy `/api/v1/customers/profile` shape) returns a default 404 with no logging or metrics. Comments in the file describe a "monolith fallback" that no longer exists. Strategic value: makes unmatched traffic observable and debuggable.

**Evidence (verbatim):** `GatewayConfig.java` has 15 `.route()` calls but no `.route("unmatched", ...)`. The file comment (lines 12–16) describes monolith fallback that was removed in Gate 5.

**Logic Modifications:**
```java
.route("unmatched",
    r -> r.path("/api/**")
        .filters(f -> f.filter((exchange, chain) -> {
            unmatchedMeter.record();
            return apiError(exchange, 404, "ROUTE_NOT_FOUND",
                "no route matches " + exchange.getRequest().getMethod() + " " +
                exchange.getRequest().getURI().getPath());
        }))
        .uri("http://127.0.0.1:1"))
```
+ normalize path (strip `/\d+` and UUIDs → low cardinality). Alert: `gateway.route.unmatched` sustained >0.

**Architectural Impact:** Adds an explicit observability route. This converts "invisible 404s" into a measurable, alertable signal.

**Dependency Analysis:** No new libraries.

**Complexity & Risk:** **Low.** Must ensure the normalized path doesn't match actual routes. The 127.0.0.1:1 URI is a dummy — requests never reach it because the filter writes the response directly.

**Verification:** IT `/api/v1/nonexistent` ⇒ 404 body shape + counter +1; legacy-paths sweep against staging access logs.

**Cross-refs:** V-14.

---

### V-21 — Twilio logs raw phone numbers 🟢 RC-G/H

**Feature/Improvement Overview:** `TwilioSmsSender.send` logs `phoneNumber` directly — a PII leak into logs that violates data-minimization compliance. Strategic value: prevents PII exposure in log aggregation systems.

**Logic Modifications:** Apply a `LogRedactor.maskE164(...)` (preserve leading 2 + trailing 2 digits) at both log sites. Add CI grep guard for `(to|phone|email|mobile)={}` across services.

**Architectural Impact:** Introduces a shared `LogRedactor` utility in platform-lib.

**Dependency Analysis:** No new libraries.

**Complexity & Risk:** **Low.** Straightforward redaction.

**Verification:** unit asserts masked value appears, raw never.

**Cross-refs:** none.

---

### V-22 — `customer-0` phantom notification 🟢 RC-H

**Feature/Improvement Overview:** `NotificationEventConsumer.extractCustomerId` returns `0L` when `customerId` is missing from the event payload, then dispatches `"customer-0"`. This creates phantom notifications to a non-existent customer. Strategic value: stops garbage dispatch at the source.

**Evidence (verbatim):** `NotificationEventConsumer.java:51-58`:
```java
private Long extractCustomerId(String payload) {
    try {
        var node = objectMapper.readTree(payload);
        return node.has("customerId") ? node.get("customerId").asLong() : 0L;
    } catch (Exception e) {
        return 0L;
    }
}
```

**Logic Modifications:** Throw `PoisonEventException("customerId missing")` instead of returning `0L` → routes through V-10 DLPR to DLT instead of fabricating a recipient.

**Architectural Impact:** Treats malformed events as explicit failures rather than silently dispatching to phantom recipients.

**Dependency Analysis:** Requires `PoisonEventException` (or reuse an existing exception type).

**Complexity & Risk:** **Low.** Must ensure the caller handles the exception (V-10 machinery does).

**Verification:** missing customerId ⇒ DLT contains the original; `dispatchService` zero calls.

**Cross-refs:** V-10.

---

## 6. Detailed Findings — Performance & Capacity

### P-01 — Connection-sizing triangle collapse 🟠 (config-only, largest throughput lever)

**Feature/Improvement Overview:** Three tiers of connection pooling are mis-sized: Tomcat's default 200 threads, Hikari's default 5 per pod (`maximum-pool-size: 5`), and pgbouncer's `default_pool_size=40`. At 40+ active requests, the 36th–200th threads queue on `getConnection()` for up to 30 s. This is the dominant throughput bottleneck.

**Evidence (verbatim):** `services/*/application.yml`: `maximum-pool-size: 5` in all services; `k8s/pgbouncer/configmap.yaml:21`: `default_pool_size = 40`; Spring Boot default `server.tomcat.threads.max = 200` (unset in all yml).

**Logic Modifications:**
1. Per-service `application.yml`:
   ```yaml
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
   server:
     tomcat:
       threads:
         max: 100
       accept-count: 200
   ```
2. Budget table: for each DB — `pods(min…max) × DB_POOL_SIZE ≤ pgbouncer default_pool_size (40) + primary headroom`.

**Architectural Impact:** Aligns the three-tier connection pipeline. The connection-timeout reduction (30 s → 3 s) makes backpressure visible at the edge instead of hiding it.

**Dependency Analysis:** No new libraries. Pure config change.

**Complexity & Risk:** **Low.** Config-only change. Must compute budgets per DB before deploying. The pgbouncer `default_pool_size` increase must be coordinated with PG `max_connections`.

**Verification:** staging k6 ramp: p95 ≤ baseline−20%; `hikaricp_connections_pending` 0 during plateau.

**Effort:** 2 h config + 1 day game-day.

---

### P-02 — Hibernate has no batching configured 🟠

**Feature/Improvement Overview:** Zero `jdbc.batch_size`, `order_inserts`, `order_updates`, `query_plan_cache_size` in any service `application.yml`. The dispatch batch writes 100 orders (`save` each) + ledger/outbox rows — all 1 INSERT per roundtrip.

**Evidence:** `services/order/src/main/java/.../ScheduledOrderProcessor.java:62` — `orderRepository.save(order)` in a loop. No batch config in `application.yml`.

**Logic Modifications:**
```yaml
spring:
  jpa:
    properties:
      hibernate:
        jdbc.batch_size: 50
        order_inserts: true
        order_updates: true
        jdbc.batch_versioned_data: true
        query_plan_cache_size: 200
```
**Critical caveat:** with `IDENTITY` generation (used here), Hibernate **cannot batch INSERTs that return generated keys**. Two options:
- Option A for future bulk tables: `@GeneratedValue(strategy = SEQUENCE)` with `increment_size=50`.
- Option B for hot paths today: bulk statement via JPA `@Modifying` / JPQL `UPDATE`.

For `ScheduledOrderProcessor` specifically, replace per-order `save(...)` with `orderRepository.saveAll(due)` → single flush → update batching applies.

**Architectural Impact:** Reduces DB roundtrips for bulk operations from N to ~N/batch_size.

**Dependency Analysis:** No new libraries.

**Complexity & Risk:** **Medium.** `IDENTITY` strategy limitation is critical — batching only applies to UPDATEs and SEQUENCE-based INSERTs. Must verify the `@Modifying` bulk approach for order dispatch.

**Verification:** with `@DataJpaTest`: assert `Statement` execute count for 100-order dispatch ≤ 6 flush statements.

**Effort:** 1 h config + 2–4 h per service hot-path sweep.

---

### P-03 — Kafka producer left at framework defaults on money events 🟠

**Feature/Improvement Overview:** `KafkaPlatformConfig` sets only bootstrap + serializers. Missing: `linger.ms`, `batch.size`, `request.timeout.ms`, `delivery.timeout.ms` tuning. While `acks=all` and `enable.idempotence=true` are present (verified in code), the micro-batching settings are absent, reducing throughput.

**Evidence:** `KafkaConfig.java:23-32`: only 5 config keys set. The audit doc claims defaults `acks=1` but the code contradicts this — the actual gap is `linger.ms` and `batch.size`.

**Logic Modifications:**
```java
config.put(ProducerConfig.ACKS_CONFIG, "all");
config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
config.put(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE);
config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 120_000);
config.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
config.put(ProducerConfig.LINGER_MS_CONFIG, 5);                  // micro-batching
config.put(ProducerConfig.BATCH_SIZE_CONFIG, 32_768);
```

**Architectural Impact:** Improves producer throughput by batching small records. `acks=all` + idempotence ensures exactly-once-per-partition for money events.

**Dependency Analysis:** No new libraries.

**Complexity & Risk:** **Low.** Config-only. `linger.ms=5` may add up to 5 ms latency to the first message in a batch — acceptable for money events.

**Verification:** Redpanda Testcontainers IT: broker down mid-publish → outbox row stays PENDING; 10k-event soak: `producer-records-per-request-avg ≈ 20` metric proves batching.

**Effort:** 1 h + IT 2 h.

---

### P-04 — 14 schedulers, one thread 🟠 (couples with G-11)

**Feature/Improvement Overview:** Zero `spring.task.scheduling` config in any service → Spring default `pool.size=1`. 14 `@Scheduled` jobs share this single thread, including `OutboxPollPublisher`, `DlqRetryScheduler`, `SagaPendingQueuePoller`, `RedisReplicaHealthMonitor`, SSE heartbeat, V-19 cleanup, V-05 dispatch. A single job's stall is a global stall.

**Evidence:** `grep` across services yml: no `spring.task.scheduling` config anywhere. 14 `@Scheduled` annotations found across services.

**Logic Modifications:**
1. `application.yml` (per service): `spring.task.scheduling.pool.size: 12`.
2. **Isolate latency-critical relays** from cron noise via dedicated scheduled executors in platform-lib:
   ```java
   @Bean(destroyMethod = "shutdown")
   TaskScheduler relayScheduler() {
       ThreadPoolTaskScheduler s = new ThreadPoolTaskScheduler();
       s.setPoolSize(2); s.setThreadNamePrefix("relay-"); s.initialize();
       return s;
   }
   // OutboxPollPublisher: use relayScheduler instead of @Scheduled
   ```
3. Guardrail G-11 (CI): every service application.yml must define `spring.task.scheduling.pool.size`.

**Architectural Impact:** Separates high-priority event relay work from low-priority cron jobs. The outbox poll no longer stalls waiting behind V-05's dispatch loop or cache retry sleeps.

**Dependency Analysis:** No new libraries.

**Complexity & Risk:** **Medium.** The dedicated relay executor requires refactoring `OutboxPollPublisher` to stop using `@Scheduled` and use `relayScheduler.scheduleWithFixedDelay()`. Must ensure graceful shutdown.

**Verification:** load IT: make one job sleep → outbox relay E2E latency p99 < 1 s.

**Effort:** 4 h.

---

### P-05 — HTTP client fragmentation 🟡

**Feature/Improvement Overview:** Three HTTP client stacks in use: RestClient (aspect-based), WebClient (reactive), RestTemplate (notification Twilio sender). The `order` service's `RestaurantClient` constructs `WebClient.builder()` inline with no connection-provider config, no response timeout, no metrics.

**Evidence:** `services/order/src/main/java/com/bhukkad/order/client/RestaurantClient.java` — `WebClient.builder()` inline. `services/notification/src/main/java/.../TwilioSmsSender.java` — likely `RestTemplate` (not verified but audit doc references).

**Logic Modifications:**
1. Parameterize the shared bean once, in platform-lib:
   ```java
   @Bean
   WebClient.Builder platformWebClientBuilder() {
       var provider = ConnectionProvider.builder("bhukkad")
           .maxConnections(64)
           .pendingAcquireTimeout(Duration.ofSeconds(3))
           .metrics(true).build();
       var http = HttpClient.create(provider)
           .responseTimeout(Duration.ofSeconds(5))
           .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 2000);
       return WebClient.builder().clientConnector(new ReactorClientHttpConnector(http))
               .filter(new WebClientLoggingFilter())
               .filter(circuitBreakerFilterFor(...));
   }
   ```
2. `RestaurantClient` — change constructor to inject `WebClient.Builder` with `@Qualifier("platformWebClientBuilder")`.
3. CI check G-13: ban `WebClient.builder()` / `new RestTemplate()` outside platform-lib factories.

**Architectural Impact:** Standardizes HTTP client configuration across services. Enforces connection limits + timeouts as a platform default.

**Dependency Analysis:** No new libraries. Uses Reactor Netty (already available).

**Complexity & Risk:** **Medium.** Must identify all hand-built clients (grep `WebClient.builder()`/`new RestTemplate()`). The template migration is mechanical but touches many files.

**Verification:** unit: builder wiring uses platform config (assert on built client's `responseTimeout`); soak: restaurant chaos-kills ⇒ order `503` observed.

**Effort:** 1 day incl. review of remaining ~5 hand-built clients.

---

### P-06 — Event-delivery latency floor = poll interval 🟡

**Feature/Improvement Overview:** `OutboxPollPublisher` uses `@Scheduled(fixedDelayString = "#{@outboxProperties.pollInterval().toMillis()}")` with default 5000 ms. For services without the Redis wake mechanism wired, event E2E latency has a 5 s floor. `RedisOutboxPendingQueue` exists in the same package — a wake-up primitive — but the publisher doesn't subscribe to it.

**Evidence:** `OutboxPollPublisher.java:133`: `@Scheduled(fixedDelayString = "#{@outboxProperties.pollInterval().toMillis()}")` — pure polling. No Redis pub/sub subscription in the file.

**Logic Modifications (option A — wire wake):**
1. `OutboxEventService.enqueue` writes also `LPUSH bhukkad:outbox:wake:{service}`.
2. Publisher blocks on `BRPOP wake 1000ms` → on wake, `claimBatch`; else on timeout poll anyway.
3. Net effect: event E2E p50 from ~5000 ms → ~5 ms while keeping the poll as fallback.
4. Metric `outbox.publish.lag_ms` alert p99 > 2000.

**Architectural Impact:** Converts a polling relay into a push+wake hybrid. The wake is an optimization — rows stay authoritative (crash-safe).

**Dependency Analysis:** Requires the `RedisOutboxPendingQueue` to exist and be wired (it exists in platform-lib per the audit doc note).

**Complexity & Risk:** **Low.** Must verify the `RedisOutboxPendingQueue` class and its API. If the class exists but is unused, wiring is straightforward.

**Verification:** Redpanda IT p50 publish→consume < 100 ms under normal; Redis down ⇒ still delivers at poll cadence.

**Effort:** half day.

---

### P-07 — Notification dispatch runs inline on the Kafka listener thread 🟠 RC-D-adj

**Feature/Improvement Overview:** `NotificationEventConsumer.onOrderEvent` → `dispatchService.dispatch(...)` → email/SMS senders. `TwilioSmsSender.send` is a synchronous HTTP call (0.2–3 s). Consumer threads = partitions, so one slow response stalls all events for that partition. If processing exceeds `max.poll.interval.ms`, the group rebalances — a loop of reprocessing the same events.

**Evidence:** `NotificationEventConsumer.java:33-48`: `dispatchService.dispatch(...)` called inline within `@KafkaListener`. No async wrapper.

**Logic Modifications:**
1. Async hand-off with bounded isolation:
   ```java
   @Bean("dispatchExecutor")
   ThreadPoolTaskExecutor dispatchPool() {
       var ex = new ThreadPoolTaskExecutor();
       ex.setCorePoolSize(4); ex.setMaxPoolSize(16);
       ex.setQueueCapacity(2000);           // bounded: overflow => backpressure
       ex.setRejectedHandler(new AbortPolicy());  // loud rejection, never CallerRuns
       ex.setThreadNamePrefix("notify-"); ex.initialize();
       return ex;
   }
   // consumer:
   dispatchPool.execute(() -> dispatchService.dispatch(...));
   ```
2. Rejection ≠ silence: on `RejectedExecutionException` rethrow to DLT.
3. Idempotency: dispatch keyed by `eventId`.
4. Metrics: `notification.dispatch.duration`, `dispatch.queue.depth`.

**Architectural Impact:** Decouples Kafka consumption from external HTTP delivery. The bounded queue provides backpressure visibility.

**Dependency Analysis:** No new libraries. Uses Spring's `ThreadPoolTaskExecutor`.

**Complexity & Risk:** **Low-Medium.** Must ensure the async dispatch is idempotent. The `AbortPolicy` ensures no silent drops — but must pair with DLT routing.

**Verification:** Testcontainers kafka IT: fake sender sleeping 900 ms × 60 records: partition lag < 1 s, listener threads untouched.

**Effort:** 0.5–1 d.

---

### P-08 — Search prefix LIKE needs index verification 🟡

**Feature/Improvement Overview:** V-04 moves search filtering into SQL with `lower(name) LIKE lower(q || '%')`. PostgreSQL uses a btree index for prefix LIKE only with `varchar_pattern_ops` on non-C-locale databases (RHEL PG defaults to `en_US.UTF-8`). A sequential scan on `restaurant_search` or `menu_item_search` tables causes GC pressure at scale.

**Evidence:** `SearchServiceImpl.java:97-104`: `restaurantSearchRepository.findAll()` + `menuItemSearchRepository.findAll()` — currently loading all rows, filtering in JVM (which V-04 fixes but needs the right index).

**Logic Modifications:**
1. `EXPLAIN (ANALYZE, BUFFERS)` a staging `... LIKE 'reser%'` — if `Seq Scan`:
   ```sql
   CREATE INDEX CONCURRENTLY idx_rsearch_name_prefix ON restaurant_search ((lower(name)) varchar_pattern_ops);
   CREATE INDEX CONCURRENTLY idx_msearch_name_prefix ON menu_item_search ((lower(name)) varchar_pattern_ops);
   ```
2. Add an **explain-guard test** to CI (asserts `Index Scan` appears in plan).

**Architectural Impact:** Adds a CI test that prevents index regression. The `pg_trgm` GIN extension is an optional future enhancement (typo tolerance).

**Dependency Analysis:** No new libraries. PostgreSQL extension `pg_trgm` optional.

**Complexity & Risk:** **Low.** Index creation is concurrent (non-blocking). The explain-guard test is the key innovation.

**Verification:** index present post-rollover + plan test + V-04 k6 re-run.

**Effort:** 2 h.

---

### P-09 — JVM ↔ container contract standardization 🟡 RC-E

**Feature/Improvement Overview:** `order/deployment.yaml` sets `-XX:MaxRAMPercentage=75.0` and has `resources.requests/limits`. But other services may not have the full contract: `-XX:+ExitOnOutOfMemoryError`, `ActiveProcessorCount`, CPU requests (no limits), or `JAVA_OPTS` consistency. Heap % of container where limits are unset ⇒ JVM sizes to the node, not the pod ⇒ OOMKilled or node-pressure evictions under GC bursts.

**Evidence:** `k8s/order/deployment.yaml:84-90`: resources present. Must verify all other service deployments.

**Logic Modifications:**
1. Shared `base/java-env` snippet per deployment:
   - Memory requests == limits (Guaranteed QoS for JVMs).
   - CPU requests realistic per P-01 concurrency math; **no CPU limit** (avoids throttled GC).
   - JAVA_OPTS add: `-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -XX:ActiveProcessorCount=<requests> -Xlog:gc*:file=/var/log/app/gc.log`.
2. HPA: add memory-basis secondary metric where heap is the real limit.

**Architectural Impact:** Standardizes JVM resource contracts across all services. Guaranteed QoS class prevents OOMKilled roulette.

**Dependency Analysis:** No new libraries.

**Complexity & Risk:** **Medium.** Must audit all 13 service deployments for the presence of each setting. The CPU-no-limit approach is unconventional but correct for JVM workloads.

**Verification:** `kubectl apply --dry-run` + config lint CI (G-18); staging stress: payment settlement burst ⇒ HPA scales before eviction; OOM-kill drill ⇒ pod exits 137 cleanly.

**Effort:** 1–2 d.

---

### P-10 — Where to verify JWTs: decision debt 🟡 RC-F

**Feature/Improvement Overview:** The gateway validates bearer tokens for percentage-rollout bucketing (V-13 path). Every service *also* verifies via platform-lib filter. Two verifies per request. The correct fix is a deliberate choice documented as an ADR, not a code change.

**Evidence:** `EdgeKillSwitchFilter.java:57` — `subjectId(exchange)` calls `jwtValidatorProvider.getIfAvailable().validate(...)`.

| Option | Cost/request | Benefit | Recommendation |
|---|---|---|---|
| Verify at edge only; trust via mTLS | 1 verify | fastest; assumes mesh enforcement | staging yes; prod only with Istio authn policy audited |
| Verify at both (today) | 2 verifies (HS256: ~0.1 ms, fine) | belt and braces; V-03 makes JWK fetch non-blocking | **keep** if identity stays RS256/rotated |
| Gateway terminates JWT, reissues internal token | 1+1 issues | clean S2S story, one validator total | long-term target; ADR after R-05 module split |

**Logic Modifications:** ADR in W7. No code debt today beyond completing V-03/V-13/V-15.

**Architectural Impact:** Decision document only. Resolves the "is the gateway filter redundant" question.

**Dependency Analysis:** No new libraries.

**Complexity & Risk:** **Low.** Documentation debt.

**Effort:** 2 h.

---

## 7. Detailed Findings — Repository & Architecture

### R-01 — Two competing k8s manifest trees 🟠 RC-F

**Feature/Improvement Overview:** `k8s/` (kustomize: bases + overlays/{staging,prod}, gateway.yaml, monitoring/, istio/, pgbouncer/) is what `kustomization.yaml` deploys. But `services/k8s/` carries parallel raw manifests (`-deployment.yaml`, `-hpa-pdb.yaml`), plus `DECOMMISSION-CHECKLIST.md`, istio/network-policy variants. Two sources of truth = guaranteed drift.

**Evidence:** `ls services/k8s/` → 18+ YAML files. `ls k8s/` → structured kustomize tree with 13 service subdirectories.

**Logic Modifications:**
1. Keep `k8s/` as single source; move documentation-only files from `services/k8s/` → `docs/` (checklist → `k8s/DECOMMISSION-CHECKLIST.md`).
2. Diff each raw manifest in `services/k8s/` against `k8s/` counterpart; port unique settings, `git rm -r services/k8s/*.yaml`.
3. CI G-12: `kustomize build k8s/ && yamllint`; fail if `services/k8s/**/*.yaml` non-empty.

**Architectural Impact:** Eliminates dual-source-of-truth for k8s manifests.

**Dependency Analysis:** No new libraries. Uses `kustomize` (already a deployment tool).

**Complexity & Risk:** **Medium.** Careful diffing is the work — must ensure no prod settings are lost.

**Verification:** `git status` clean; CI lint; prod/staging deploy workflow paths unchanged.

**Effort:** 1 day.

---

### R-02 — 14 Dockerfiles + a rival template 🟡

**Feature/Improvement Overview:** 14 per-service `Dockerfile` files plus `services/docker/Dockerfile.template` + `Dockerfile.service`. The per-service Dockerfiles bake in their own build logic; the template uses `MODULE` + build stage. Drift is guaranteed — the audit doc noted "Dockerfile pins Java 17 / dev machines to 26" as a prior drift incident.

**Evidence:** `find services -name Dockerfile` = 13. `services/docker/Dockerfile.service` (26 lines) vs `services/order/Dockerfile` (42 lines) — different build stages, different JAVA_OPTS, different healthcheck.

**Logic Modifications:**
1. `docker buildx build --build-arg MODULE=<svc> -f services/docker/Dockerfile.service services` as the ONLY image recipe.
2. Replace CI registry jobs to template invocations.
3. `git rm services/*/Dockerfile`.
4. G-13: CI fails if `services/*/Dockerfile` reappears.

**Architectural Impact:** Single image build standard across all services.

**Dependency Analysis:** No new libraries.

**Complexity & Risk:** **Medium.** Must smoke-test the template builds each service identically. The `Dockerfile.service` currently uses `eclipse-temurin:17-jdk` (26 lines) vs per-service `maven:3.9.9-eclipse-temurin-17 AS builder` — the template approach is simpler but must preserve the per-service dependency-resolution cache optimization.

**Verification:** rebuilt payment image runs staging identical (digest-diff).

**Effort:** 1–2 days.

---

### R-03 — `TrieIndex.java` duplicated inside restaurant 🟢

**Feature/Improvement Overview:** `TrieIndex.java` exists in two packages within the restaurant service. The audit doc says keep one, fold the other's diff, delete, single test home.

**Evidence:** `find services/restaurant -name TrieIndex.java` = 2 files.

**Logic Modifications:**
1. Verify which package has the test (`TrieIndexTest`).
2. Keep the tested one; fold any diff from the other.
3. Delete the duplicate.

**Architectural Impact:** None.

**Dependency Analysis:** None.

**Complexity & Risk:** **Low.** Must verify tests pass against the retained copy.

**Effort:** 1 h.

---

### R-04 — Dispute ownership ambiguity across 4 modules 🟡 RC-F

**Feature/Improvement Overview:** `Dispute*` classes exist in order, payment, supportticket, and platform-lib. The gateway routes `/api/v1/admin/disputes/**` to **order**, but the migration docs say disputes → supportticket owns lifecycle. Supportticket credits wallets **via** payment's HTTP. Payment has its own `domain/Dispute` + `DisputeService`. This is a duplication-of-money surface in the V-01 class.

**Evidence:** `find services -name "Dispute*" -type f | grep -v test` = 4+ files across 4 modules. `GatewayConfig.java:151-156` routes disputes to `orderUri`.

**Logic Modifications:**
1. Write `docs/adr/ADR-dispute-ownership.md`: exactly one SOR table + **who writes disputes + who credits wallets**.
2. Recommendation: `supportticket` owns lifecycle, emits `dispute_resolved` via outbox; `payment` consumes it and performs the wallet ledger credit in one payment tx.
3. Remove `payment/domain/Dispute` write-path (audit reads first).
4. Gateway route `/api/v1/admin/disputes/**` → `supportUri`.
5. ArchUnit/CI: `dispute_entities` table writable only from supportticket deployment; wallet `adjust*` callable only from payment's own tx.

**Architectural Impact:** Establishes single-writer ownership for disputes, eliminating the money-duplication surface. Requires cross-service coordination.

**Dependency Analysis:** No new libraries. Requires the ADR to be merged before any code change.

**Complexity & Risk:** **High.** Must audit all dispute read/write paths. The gateway route change is API-level but internal (`/api/v1/admin/`).

**Verification:** context boot fails if grant removed ⇒ integration IT with restricted DB user passes only through owner path.

**Effort:** 1 day ADR + 3–5 days code, gated hard on the decision.

---

### R-05 — `platform-lib` is a 213-file monolith dependency 🟡

**Feature/Improvement Overview:** One artifact (`platform-lib`) carrying: DTO/error/web filters, datasource routing + replicas, Flyway baseline, Kafka platform + outbox + DLQ retries, idempotency, cache (redis + local + stampede), saga, chaos aspect, gateway edge flag, service JWT, test harness. Every service boots all of it whether or not it uses each piece.

**Evidence:** `find services/platform-lib/src/main -name "*.java" | wc -l` = 213. Every service `pom.xml` depends on `platform-lib`.

**Logic Modifications (safe, no behavior change):**
1. Split into modules under `services/platform-lib/`:
   ```
   platform-core      (dto/error, tracing, web headers, exception mapper)
   platform-data      (jpa, auditing, repositories, entity graph helper)
   platform-ds-replica (DataSourceConfig/ReadReplica*)
   platform-events    (outbox, DLQ, PlatformEventMessage, kafka platform cfg)
   platform-saga      (coordinator, pending poller, shedlock)
   platform-cache     (redis/local/stampede, invalidation)
   platform-security  (user+service JWT, secrets validation)
   platform-test      (containers, KafkaTestHelper, ArchUnitRules)
   platform-edge      (EdgeKillSwitch filter — gateway only)
   ```
2. Phase 1: create modules, move packages with identical GAV alias for one minor (compat shim = parent pom `dependencies` of `bhukkad-platform-aggregate` that pulls all).
3. Phase 2: migrate services in W7, each dropping unneeded modules.

**Architectural Impact:** Enables per-service dependencies. Reduces boot classpath, image size, and cross-cutting release coupling.

**Dependency Analysis:** No new libraries. Maven multi-module structure only.

**Complexity & Risk:** **High.** Must ensure `mvn verify` + deploy diff is zero. The compat shim pattern is the standard Maven migration technique.

**Verification:** reactor `test` green during phases; staging boot-time p50 measured per service (expect −10–20% on services that shed datasource+chaos+gateway classes).

**Effort:** 3–5 days phased.

---

### R-06 — Repo hygiene: stale directories, nested scripts, untracked debris 🟢 RC-G

**Feature/Improvement Overview:** `wallet-old.bak/` (chmod-broken), `target-stale-platform-lib/`, `.trash-rootowned/`, `scripts/scripts/` nesting. None are git-ignored. `.gitignore` doesn't cover the patterns.

**Evidence:** `ls services/supportticket/` → `wallet-old.bak/` exists. `ls services/` → `scripts/scripts/` exists.

**Logic Modifications:**
1. Delete `services/supportticket/wallet-old.bak/` (after `chmod -R u+rwX` if needed).
2. `rm -rf services/platform-lib/target-stale-platform-lib`.
3. `git mv scripts/scripts scripts/reports` (verify no CI path refs).
4. Extend `.gitignore`: `**/target-stale*/`, `.trash-rootowned/`, `dump.rdb`, `*.bak/`, `x.tab`.

**Architectural Impact:** None. Hygiene only.

**Dependency Analysis:** None.

**Complexity & Risk:** **Low.** Unblocks grep-ability of the whole repo for every test above.

**Effort:** 1 h.

---

### R-07 — Redis shared blast-radius 🟡 RC-J

**Feature/Improvement Overview:** One Redis Sentinel group serves: cache (with retry-sleeps up to 500 ms), SSE relay, rate-limit counters, write-fence, gateway `EdgeFeatureFlags`, outbox pending queue. A cache hot-key spike delays auth decisions at the edge; write-fence failing open silently breaks read-after-write guarantees.

**Evidence:** `RedisCacheService.java:32-33` — `LOCK_WAIT_BASE_MS = 20`, `LOCK_WAIT_RETRIES = 10`, with `Thread.sleep` backoff up to 500 ms. `RedisRateLimitService.java` and `EdgeFeatureFlags` both use the same Redis.

**Logic Modifications:**
1. **Namespace ownership CSV** (`docs/redis-ownership.csv`): `prefix → use → pool → fail-mode → alert`.
   - `bhukkad:fence:* → write-fence → fail-closed → page`
   - `bhukkad:ratelimit:* → rate-limit → fail-open → warn`
   - `bhukkad:feature-flag:* → edge flags → fail-open → warn`
   - `live:* → SSE relay → must-deliver → drop with counter`
2. Isolate **client pools** per concern (lettuce config): separate `ClientResources`; at minimum distinct pool for relay + per-use `timeout` (300 ms auth-path ops).
3. Sentinel: `down-after-milliseconds ≤ 5 s`; lettuce topology-refresh; failover drill.
4. Key TTL hygiene sweep job: log orphan `bhukkad:*` without TTL.

**Architectural Impact:** Establishes Redis as a multi-tenant shared resource with explicit failure-domain boundaries per use-case.

**Dependency Analysis:** No new libraries. Uses existing `StringRedisTemplate`.

**Complexity & Risk:** **Medium.** The namespace CSV is the key deliverable — must audit every Redis key prefix used across services.

**Verification:** game day (Redis unavailable 30 s under 500 rps): 429s ≈0 (fail-open), auth p99 < 2× baseline, `replica.fence.timeout` with reads forced to primary; SSE reconnects replay within 60 s.

**Effort:** 1 day.

---

### R-08 — Cross-domain entity copies 🟡 RC-F

**Feature/Improvement Overview:** `supportticket` holds `Order`, `User`, `GiftCard` JPA repositories — it can **write** domains it does not own (single-writer drift, G-14 violation). No documented read-model or write-forward rule.

**Evidence:** `find services/supportticket -name "*Repository.java"` includes Order/User/GiftCard repos. `supportticket` has its own `pom.xml` with platform-lib dependency.

**Logic Modifications:**
1. Read-only enforcement: service DB roles `GRANT SELECT ONLY` on foreign tables, or better: **replicate minimal columns into owned read models** (same Kafka consumer as V-12).
2. `docs/database-ownership.csv` extended: every service lists tables `owner | rw | via-events`.
3. CI/ArchUnit: `@Entity` with `INSERT/UPDATE`-capable repository for a non-owner role fails.

**Architectural Impact:** Enforces bounded-context isolation at the database layer.

**Dependency Analysis:** No new libraries.

**Complexity & Risk:** **Medium.** Must audit all cross-service entity usage. The `GRANT SELECT ONLY` approach is simpler but less clean; the read-model approach is the long-term goal.

**Verification:** context boot fails if grant removed ⇒ integration IT with restricted DB user passes only through owner path; ArchUnit red on foreign repo with save/delete.

**Effort:** 1 day.

---

## 8. Systemic Guardrails (the class closers)

### G-1 — Outbox-atomicity rule ✅ biggest single win

One platform-lib pattern + one CI check closes V-02, V-09 (event half), V-10, V-11, V-12:

```java
@Target(METHOD)
@Retention(RUNTIME)
public @interface TransactionalDomainOperation {}
```

ArchUnit/CI: *"Any public money/stock/event-emitting `@Transactional` service method writing a repo AND calling publisher/outbox ⇒ annotation present + outbox call in same method body only"*; enforced in code review checklist; `OutboxEventService.enqueue` asserts active transaction and hard-throws otherwise:

```java
if (!TransactionSynchronizationManager.isActualTransactionActive())
    throw new IllegalStateException("G-1 VIOLATION: outbox enqueue outside tx — lost-event risk");
```

This turns the four RC-D instances into build failures.

### G-2 — No inert safety devices

Any resilience/observability device (breaker, limiter, fallback route, kill switch, DLT) must emit a metric whose *absence* or a counter whose presence proves it runs; mounted in a startup self-check; CI greps declared-but-unused.

### G-3 — Startup preflight (platform-lib, all services, prod profile): fail-fast matrix

- `events.type=kafka required in prod`
- `JWT secret ≥32B` (user+jwks-or-secret required; service same)
- `CORS no wildcard` (V-14)
- `ratelimit custom resolver present` (V-18)
- `scheduler pool max-size ≥10` (P-04)

Each as `@EventListener(ApplicationReadyEvent)` validator bean.

### G-4 — Transaction boundaries

ArchUnit: `no @Transactional on *Controller; no protected @Transactional` (prevents V-05 class).

### G-5 — BlockHound

Test module in gateway, any reactor service. JWKS/crypto paths (V-03/13/15 substrate).

### G-6 — CI grep `findAll()`

`findAll()` in `*/main/**Service*` ⇒ fail unless `@AllowFullScan(reason)` annotation — V-04 class.

### G-7 — register/cleanup pairing

SSE, fences, schedulers + unbounded-insert check (idempotency tables) — V-06/V-17/V-19 class.

### G-8 — Comment↔code coherence review

Any comment claiming a control ("rate-limits", "idempotent", "falls through") requires a linked passing test in same PR — RC-F items.

### G-9 — DLT hygiene

Every `@KafkaListener` must declare a DLT route; no `catch (Exception` at listener top level.

### G-10 — Replica health

lag exporter mandatory when `read-replica.replicas` set (V-17).

### G-11 — Scheduler pool

Every service declares `spring.task.scheduling.pool.size > 1`; relay/saga work isolated from cron noise (P-04).

### G-12 — Single kustomize source

CI `kustomize build` + reject any `services/k8s/**/*.yaml` (R-01).

### G-13 — Duplicated-artifact bans

CI grep/ArchUnit — `services/*/Dockerfile` (R-02), inline `WebClient.builder()` / `new RestTemplate()` (P-05), second cache/limiter/security filter factory.

### G-14 — Single-writer DB ownership

CI/dba check that only the owning deployment's role can `INSERT/UPDATE` a table (PG grants per service role).

### G-15 — Capacity arithmetic job

`Σ(hpa-max × DB_POOL_SIZE) ≤ 0.8 × pgbouncer/PG ceilings` — computed, printed, **blocking non-zero** in CI against the kustomize config (P-01).

### G-16 — Graceful shutdown

`@PreDestroy` hooks for SSE emitters; `terminationGracePeriodSeconds ≥ 90` in all deployments; JVM gets `SIGTERM` and drains pending.

### G-17 — Structured logging

No `System.out.println`; parameterized `log.info("key={}", val)`; no PII in logs (V-21).

---

## 9. Observability Additions

### 9.1 Per-finding metrics

```
wallet.adjust.rejected{reason=insufficient|missing}      (V-01/02 money)
outbox.enqueue.no_tx_error                                (G-1 — must be 0)
gateway.route.unmatched{path}                             (V-20 — alert sustained>0)
circuit_breaker_{open,state}                              (V-16 — alert on open>0:5m)
jwks_refresh_{duration,outcome}                           (V-03 — failure>1/10m)
kafka_consumer_recovered_to_{dlt}_depth, dlq_lag          (V-10)
replica_lag_seconds{replica}, replica_lagging_events      (V-17)
rate_limit_{allowed,denied}{bucket}                       (V-18)
idempotency_cleaned{service}                              (V-19)
sse_global_budget_exhausted, sse_dropped_frames           (V-06/07)
event_published_NOOP{eventType}                           (V-09 — must be 0)
service_auth_rejected{reason=expired|weakkey|none}        (V-15)
```

### 9.2 Part V addendum metrics (performance)

```
hikaricp_connections_pending{pool}        alert sustained>8 for 2 min      (P-01)
hikaricp_connections_timeout_total        any rate                          (P-01)
hibernate_statements_batched{app}         drop→0 regression                 (P-02)
kafka-producer-request-latency-avg        + batch size metric               (P-03)
outbox.publish.lag_ms                     p99>2000 page                     (P-06)
scheduler_pool_saturation{sched}          active==1 in prod → alert         (P-04)
webclient_pool{pending,active}            pool saturation                   (P-05)
notification.dispatch.duration{channel}, dispatch.queue.depth, notification.dup_blocked  (P-07)
pg_index_usage: explain-guard test in search CI (P-08)
jvm_memory_used/max{region}, container_oom_killed_total, gc_pause_seconds p99 (P-09)
redis_pool_active{idle,pending,await} per pool-tag; fence.fail_closed{route} (R-07)
redis orphan-key-without-ttl count (nightly sweep) (R-06/R-07)
```

### 9.3 Dashboards

`monitoring/grafana/bhukkad-production-readiness.json` (new), rows for the 12 series above + the Part V addendum series.

### 9.4 Capacity check job

For every DB: `Σ(hpa-max × DB_POOL_SIZE) ≤ min(pgbouncer per-DB, PG max_connections×0.8)` — computed, printed, **blocking non-zero** in CI against the kustomize config (P-01, G-15).

---

## 10. Verified-Clean Register (audited, no finding — prevents re-audit churn)

- Constant-time webhook signature (`MessageDigest.isEqual`) ✔
- ShedLock on schedulers (single-instance work) ✔
- Redis S2S outbox pending queues w/ LPUSH+BRPOP pairs ✔
- `IdempotencyService` release = Lua compare-and-delete ✔
- `EdgeFeatureFlags` = Mono+TTL cache, fail-open documented ✔
- JPA parameter binding everywhere; no native concat SQL ✔
- No empty `catch{}` ✔
- JWT alg pinned in user path ✔
- Read-replica fallback-to-primary when unconfigured ✔
- Connection pool: `LazyConnectionDataSourceProxy` avoids borrowing during class-loading ✔
- Outbox claim: `FOR UPDATE SKIP LOCKED` inside `@Transactional` ⇒ concurrent relay replicas grab disjoint batches ✔
- `IdempotencyRecord`: unique (scope, key) + `expiresAt` index present in platform-lib schema ✔ (V-19 is operational gap, not schema)

---

## 11. Wave-1 Runbook

1. **Pre-flight SQL sweep** (both wallet dupes):
   ```sql
   SELECT customer_id, count(*) FROM wallet_balances GROUP BY customer_id HAVING count(*) > 1;
   SELECT agent_id, count(*) FROM agent_cod_wallets GROUP BY agent_id HAVING count(*) > 1;
   ```
   Must return 0 rows; rows found = V-01 already fired in prod → ledger-reconcile first.

2. **Migrations (additive, reviewed by DBA):**
   ```sql
   ALTER TABLE wallet_balances ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
   ALTER TABLE agent_cod_wallets ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
   ALTER TABLE wallet_balances ADD CONSTRAINT uk_wallet_balance_customer UNIQUE (customer_id);
   ALTER TABLE agent_cod_wallets ADD CONSTRAINT uk_agent_cod_wallet_agent UNIQUE (agent_id);
   ```

3. **Code PRs (V-01 then V-02)** — fail-first tests attached to PR:
   - `WalletService` → atomic `adjustBalance` + ledger in same tx.
   - `DeliveryPaymentController` → delegate to `CodWalletService.credit/debit`.

4. **Staging replay** of prod wallet fixture: 500 ops + race harness green.

5. **Deploy payment** (HPA unchanged); watch `wallet.adjust.rejected`.

6. **72 h soak + 1 scheduled settlement run.** Ledger SQL monotonicity check green → **W1 DONE** (go-decision gate for traffic ramps).

### Scheduler pool sizing (latent risk surfaced by V-05/V-19)

Set `spring.task.scheduling.pool.size=12` in all services yml — `@Scheduled` default is a single thread for all 14 jobs. This is also G-11.

---

*End of audit — v6.0. Findings V-01…V-22 + P-01…P-10 + R-01…R-08; guardrails G-1…G-17; waves W1…W8; observability master §9.*