# Bhukkad — Technical Audit & Production-Heavy Readiness Roadmap

**Version 2.0 (restructured 2026-09-06).** Part I carries the 18 feature-level
recommendations (unchanged numbering, now tagged with severity and delivery
phase); Part II adds a **performance engineering playbook** (P-01…P-10) with
measured impact models and step-by-step implementation snippets; Part III adds
an **architectural restructuring playbook** (R-A…R-G); Part IV sets the
**code-coverage & SonarQube quality-gate** program using instruction coverage
measured on the current tree.

**Purpose.** This document is a feature-level technical audit of the Bhukkad
food-delivery backend (`backend-server`), produced from a full working-tree
inspection of all seventeen microservices, the shared `platform-lib`, the API
gateway, and the deployment/CI stack. It is the companion *recommendation*
document to the verification-level `docs/PRODUCTION-READINESS-AUDIT-GUIDE.md`
(which carries individually-verified findings and remediation SQL); the two
documents overlap intentionally — this file collapses those findings into
prioritized, implementable **features** and **playbooks**, each with the
implementation structure required to take it to production.

**Verification basis.**
- `docs/PRODUCTION-READINESS-AUDIT-GUIDE.md` (findings `V-01…V-22`,
  `P-01…P-10`, `R-01…R-08`) + Direct source inspection (line references
  prefixed `// verified:` were read from the working tree this cycle).
- **Test baseline:** all 17 reactor modules green on 2026-09-06
  (`./mvnw -f services/pom.xml test` → BUILD SUCCESS, 14:52 min wall,
  Testcontainers PG/Kafka suites included). Bugs fixed en route: gateway
  route-table test drift (17→23 routes after the home/BFF cache/platform
  additions), identity WIP compile break (publisher moved to
  `com.bhukkad.identity.event` vs `service`), `JwtService.issue` overload for
  scoped tokens, misplaced `OVERRIDING SYSTEM VALUE` in the new
  `V2__auth_resets` migration (hard Flyway failure — every identity boot after
  V1 would have failed), `CacheOpsController` placed in `common.cache`
  violating `CommonArchTest` (moved to `common.web`), dead monolith V6-seed
  reference inside `scripts/test-all-apis.py` (removed).
- **Config identity pass:** DB/user naming migrated off the legacy `bhukkad`
  prefix — dev/shared DB is now `core`, roles `app`/`app_owner`/`app_reader`,
  dev passwords `app_pass` across docker, k8s, scripts and
  `services/*/src/main/resources/application*.yml` defaults. Per-service
  databases already use bare domain names (`orders`, `payments`, `identity`,
  …) and table sets are clean — *no remaining `bhukkad_`-prefixed tables.*
- Out-of-scope-but-noted: two stale root-owned directories
  (`services/platform-lib/target-stale-platform-lib`,
  `services/supportticket/target-stale-supportticket`, plus
  `wallet-old.bak/` remnants and the root-owned `.gitignore`) require one
  `sudo rm -rf` / `sudo chown` pass by the repo owner — flagged in Part IV.

**How to read.**
```
Part I   §1–18   feature specs (money/auth/event/edge/ops/repo)
Part II  P-01…P-10  performance playbook: impact model → steps → code → verify
Part III R-A…R-G    restructuring playbook: module/k8s/schema/ownership decisions
Part IV  coverage & quality-gate program (measured baseline, ramp, config)
Order    dependency graph + phase table (what unblocks what)
```

**Severity vocabulary (used in all parts).** `S0` = ship-blocker — direct
money/integrity/auth loss at plausible production input. `S1` = major —
measured latency/throughput, silent divergence, self-DoS. `S2` = moderate —
degrades only under scale/failure. `S3` = hygiene/latent. Engineering risk is
stated separately per item as Low/Medium/High.

---

## Index

| # | Recommendation | Severity | Owner | Phase |
|---|---|---|---|---|
| 1 | Idempotent payment processing with a real PSP adapter | S0 | payment | P0 |
| 2 | Wallet money-integrity (atomic balances, ledger, BigDecimal) | S0 | payment,identity,order | P0 |
| 3 | Server-side pricing + real order saga outbox orchestration | S0/S1 | order | P1 |
| 4 | Loyalty & referral durable ledger + abuse controls | S0 | growth,referral | P1 |
| 5 | Auth hardening (JWT fail-fast, refresh-token rotation, lockout, MFA) | S1 | identity,platform-lib | P1 |
| 6 | Authorization model: IDOR fixes + service-to-service auth | S1 | all | P1 |
| 7 | Event backbone durability (outbox two-phase, idempotent delivery) | S0 | platform-lib,order,payment | P1 |
| 8 | Circuit-breakers that actually open + sane retry discipline | S1 | platform-lib | P0 |
| 9 | Rate-limiting discipline + edge throttling | S1 | platform-lib,gateway | P1 |
| 10 | Caching at scale (menu snapshot cache + invalidation) | S1 | restaurant | P2 |
| 11 | Search: event-driven indexing + geo (decision + scope) | S1/S2 | search,restaurant | P3 |
| 12 | Delivery intelligence (GEO matching, ETA, OSRM, load cap) | S1 | delivery | P2 |
| 13 | Realtime fan-out (Redis pub/sub bridge + stream auth) | S0 | realtime | P1 |
| 14 | Observability stack (Prometheus/alerts, tracing, logs, PII) | S1 | infra | P0‖P1 |
| 15 | Backup & DR (per-service backups, PITR) | S1 | infra | P4 |
| 16 | CI/CD hardening (image scan/SBOM, digest pins, GitOps, gates) | S1 | infra | P4 |
| 17 | Secrets remediation (committed TLS key, fail-fast secrets) | S0 | infra,gateway | P0 |
| 18 | Repository & platform-lib structure (de-dup, dead code, modules) | S2 | repo | P5 |

**Part II / III items are cross-indexed below (§→P/R refs):** #2↔V-01/02,
#7↔V-09/10/11 & P-03/P-06/P-05, #9↔V-18, #10↔P-06 (cache part),
#11↔P-06/P-08, #13↔V-06/07, #14↔§7 obs, #18↔R-A…R-G; the sizing/batching/
scheduler trio is #14-adjacent capacity work → **P-01…P-04**.

## Severity classification map (all parts, triage view)

| Sev | Items |
|---|---|
| **S0** (money/auth/data-loss today) | #1 fake-settled payments + wallet credit · #2 wallet/COD read-modify-write races (`WalletService.credit/debit:26-58` verified: no lock, no atomic UPDATE) · #4 loyalty/referral double-grant + no abuse ceilings · #7 `PROCESSING` rows stranded (recoverStale never scheduled), no maxRetries→DLQ, prod ships `enabled=false` so `outbox_events` grows forever · #13 SSE fan-out is pod-local (Redis bridge absent) · #17 TLS private key committed + secret fail-fast absent · V-03/V-13 pair: blocking JWKS under `synchronized` on gateway event loop = whole-edge stall on one IdP hiccup · `DeliveryPaymentController.creditCodWallet` (`:45-69` verified) unvalidated `BigDecimal` amount, no ledger, `@Transactional` on the controller, GET mints wallet rows |
| **S1** (material perf / durability / silent divergence) | P-01 connection-triangle (pool 5 vs 200 req threads vs pgbouncer 40) · P-02 no Hibernate batching on bulk writes · P-03 platform-Kafka producer knobs missing + *dual competing `@KafkaListener` config wiring* · P-04 one scheduler thread serving 14 `@Scheduled` jobs incl. outbox relay · P-05 5 s poll latency floor + serial publish holding tx · P-06 four unbounded `findAll()` sites incl. double full-table scan per search request · P-07 notification I/O inline on consumer thread · #5 auth hardening · #6 IDOR · #8 breaker that never opens (`CircuitBreakerFilter:36-45` verified) · #9 limiter INCR/EXPIRE race, zero `@RateLimited` mounts (verified: none in any service main source) · #10/#12/#14 as listed · R-A platform-lib monolith, R-D two k8s trees (both verified present), R-E dispute/user ownership blur |
| **S2** | P-08 expression indexes · P-09 JVM/container contract drift · P-10 double token verify (decision debt) · V-06/07 SSE registry O(N) + blocking fallback · R-B event-schema governance · R-C 14+2 Dockerfile drift (count verified) · R-F missing outbox tx-guard · #11 geo decision |
| **S3** | root-owned build debris + `.gitignore` ownership (Part IV) · dead monolith-V6 references (fixed this cycle) · test drift class (3 fixed this cycle) |

## Implementation roadmap (phases & gates)

| Phase | Content | Exit gate |
|---|---|---|
| **P0 — stop-the-bleeding (1–2 wk)** | #17 secrets, #2 wallet atomicity + ledger (V-01/V-02), P-01 + P-02 config geometry, #8 breaker decorates + edge offload (V-13/V-03 code in Part II), P-04 scheduler pool | wallet race harness green; gateway p99 −IdP-drill; k6 pool plateau; all 17 modules still BUILD SUCCESS |
| **P1 — integrity backbone (3–5 wk)** | #7 event backbone (two-phase relay + maxRetries→DLQ + prod `enabled=true` overlay), #3 saga completion, #5/#6 authn/authz, #9 limiter mount, #13 SSE Redis bridge, P-03 producer + wiring consolidation, P-05 wake/parallel drain | DLT page drill; replay no double-count; SSE reconnect cross-pod test; event E2E p99 < 2 s |
| **P2 — scale read paths (2–3 wk)** | #10 menu snapshot cache, #12 geo matching, P-06 SQL-side filters + caps + parity harness, P-08 indexes, P-10 decision ADR | search/disputes/affiliates bounded; explain-guard in CI; p95 targets met at 10× catalog |
| **P3 — domain truth (2–4 wk)** | #4 loyalty ledger, #11 search/geo scope decision | abuse ceilings proven; one SOR per domain |
| **P4 — ops resilience (2 wk)** | #14 observability wiring, #15 backup/PITR, #16 CI/CD, P-07 async dispatch, P-09 JVM contract | alerts fire on game-drill; restore drill < 30 min |
| **P5 — restructure (4–6 wk)** | R-A module split, R-D k8s single-source, R-C Dockerfile consolidation, R-E ownership ADRs, #18 repo hygiene, Part IV coverage ramp (first increment) | behavior-diff = 0, `mvn verify` green throughout, coverage gate step-1 passing |

Dependencies that gate sequencing (not independent): #7 must precede #3/#11/
#13 consumers; #5 gates #6/#17; P-04's pool bump should ship *with* P-05's
relay isolation so cron work can't starve the relay; #2 (atomic wallet) ships
before #1 wires the PSP adapter so credit paths are already race-safe.

---

# PART I — Feature specifications (#1 … #18)

Each item follows: *Feature/Improvement Overview → Technical Implementation
Roadmap (Logic Modifications · Architectural Impact · Dependency Analysis ·
Complexity & Risk) → Verification/Cross-refs*. Severity/phase per the Index.

## 1. Idempotent payment processing with a real PSP adapter

**Feature/Improvement Overview.**
The payment service does not integrate *any* payment provider. `PaymentService.processPayment`
(`// verified: services/payment/.../service/PaymentService.java:31-87`) saves a
`PENDING` row, immediately flips it to `SETTLED`, fabricates `providerRef`, and
**credits the customer wallet** with the order amount as if payment succeeded.
The `PaymentGateway` port and `SimulatedPaymentGateway` adapter exist but are
unused; no Razorpay adapter exists despite the webhook verifier being wired.
Razorpay's webhook secret defaults to `dev-webhook-secret`
(`// verified: services/payment/src/main/resources/application.yml:65`), so a
missing env var accepts forged webhooks signed with a known key.

This is the single largest money-integrity risk: every order "settles"
without moving money, while wallets are inflated. It solves:
- correctness (money only moves on real authorization),
- auditability (provider reference is real),
- fraud resistance (webhook signature verification with a real secret),
- reconciliation grounding (later P-07/VI enhancement).

Strategic value: **trust + compliance**. A food-delivery platform cannot reach
"production heavy" without a real, auditable payment path; everything downstream
(wallet, settlement, disputes, chargebacks) depends on it.

**Technical Implementation Roadmap.**

*Logic Modifications.*
- Introduce `RazorpayPaymentGateway implements PaymentGateway`: create `Order`
  on the PSP, poll for `charged`, capture, and `refund(amt)` on a distinct
  event. All calls behind the existing Resilience4j `@CircuitBreaker`/retry
  (`platform-lib` web-client filters).
- `PaymentService.processPayment`: insert the `idempotency_records` row first
  (`IN_PROGRESS`, catch `DataIntegrityViolationException` → fetch stored
  payload → return it), then call `gateway.charge(...)`, then persist
  `providerRef` + status inside a transaction that **also** enqueues the
  `payment_settled` outbox event in the *same* DB transaction
  (`OutboxClient.enqueue(...)`; matches the outbox-atomicity rule, audit-guide
  §6.1 / V-02). Credit wallet **only after** settlement is persisted.
- `completeWebhookPayment`: add a legal-transition map
  (`PENDING→SETTLED`, `SETTLED→REFUNDED`…) via conditional
  `UPDATE … WHERE status = …`, keyed by the **Razorpay event id**
  (not the payment id — current code collides `payment.captured`+`refunded`).
- Make webhook verification **fail closed**: refuse to boot if
  `RAZORPAY_WEBHOOK_SECRET` is unset/empty in prod profile; constant-time
  compare already present (`// verified: RazorpayWebhookVerifier.java:40`).

*Architectural Impact.*
Establishes the **money path** as a real, externalized, auditable flow and
removes the "fake it till you make it" coupling between order and payment.
Introduces strategy selection (`PaymentGateway` per provider) and makes wallet
credit a *consequence* of a persisted settled payment rather than a parallel
action — restoring single-source-of-truth for funds.

*Dependency Analysis.*
- New: Razorpay Java SDK (or raw HTTP behind `WebClient`) — prefer raw HTTP to
  keep the dependency surface small and avoid SDK version drift.
- Breaking: order service's `RestaurantClient`/saga must tolerate
  `payment_pending`/`payment_failed` order states previously never produced.
  Add new event `PaymentSettled` consumers downstream (notification,
  delivery). No API contract break for external callers.

*Complexity & Risk.*
**Risk: Medium.** Highest-leverage correctness fix; the danger is ordering:
wallet must **not** be credited before the settled row + outbox event commit, or
double-counting returns. Mitigate with a regression test that replays two
identical requests and asserts one wallet credit + one `payment_settled` event.
Estimated: 2-4 dev-weeks incl. testcontainers webhook replay.

Cross-ref: audit-guide V-01, V-02, V-09, V-10, V-11; infra P-03 (Kafka producer).

---

## 2. Wallet money-integrity (atomic balances, ledger, BigDecimal everywhere)

**Feature/Improvement Overview.**
`WalletService.credit/debit`
(`// verified: services/payment/.../service/WalletService.java:26-58`) is
read-modify-write with **no `@Version` and no pessimistic lock** on
`wallet_balances`; concurrent debits both pass the balance check → double-spend,
plus lost updates. `WalletBalance` entity has no `@Version`
(`// verified: services/payment/.../domain/WalletBalance.java` — 0 hits for
`Version`). Order service stores `subtotal/deliveryFee/taxAmount/…` as
`Double` (`// verified: order/.../domain/Order.java:72-87`); identity's wallet
mirror is `Double` (`// verified: identity/.../domain/Customer.java:74`).
No `CHECK (balance >= 0)` constraint exists. Ledger rows
(`WalletTransaction`) are written but the *authoritative* customer balance can
diverge from the ledger under concurrency.

Solves: fund-loss via double-spend, balance/ledger drift,
floating-point rounding on money (`Double` → `BigDecimal/NUMERIC(12,2)`).

Strategic value: **money correctness is non-negotiable for scale**; the
existing audit guide proves the pattern is already known to the codebase
(`PaymentRepository` uses `PESSIMISTIC_WRITE`, `CouponRepository` uses
`incrementUsedCountIfWithinLimit`).

**Technical Implementation Roadmap.**

*Logic Modifications.*
- Add `@Version private Long version;` to `WalletBalance`; map
  `OptimisticLockingFailureException` → retry-with-backoff (or translate to
  a conditional `BusinessException` so callers retry).
- Better: replace RMW with a **single conditional UPDATE**:
  `UPDATE wallet_balances SET balance = balance + :delta, version = version+1
   WHERE customer_id=:id AND balance + :delta >= 0`; 0 rows ⇒ insufficient /
   missing wallet. Matches `CouponRepository` proven pattern.
- Add `ALTER TABLE wallet_balances ADD CONSTRAINT balance_nonneg CHECK (balance >= 0)`.
- Persist `WalletTransaction` rows in the **same** transaction as the balance
  change (already done in `PaymentService`; verify order stays).
- Migrate `identity.Customer.walletBalance`/`CustomerWalletSyncAdapter` and
  `Order` money columns from `Double`→`BigDecimal` + DB `NUMERIC(12,2)`.

*Architectural Impact.*
Moves wallet from a *shared mutable singleton read into memory* to an
**atomic, auditable, single-writer** balance — the canonical "money path"
pattern. Removes the `Double`-money anti-pattern repo-wide, aligning identity's
mirror with payment's source of truth.

*Dependency Analysis.*
- New: none (JPA/Hibernate features only).
- Breaking: `Customer.walletBalance` type change is a JPA migration; add
  Flyway `ALTER COLUMN TYPE`. No API field changes if DTOs already expose as
  `BigDecimal`.

*Complexity & Risk.*
**Risk: High** (money). Requires a dup-sweep + reconciliation run (audit-guide
V-01 §9 runbook: `SELECT … GROUP BY … HAVING count(*)>1`); backfill
`version=0`, add unique constraint `(customer_id)`. Regression test: 500
concurrent debits against 800 → at most 1 success, balance never negative.
Estimated: 1-2 dev-weeks + a staging fixture replay.

Cross-ref: audit-guide V-01, V-02; platform P-01, P-02.

---

## 3. Server-side pricing + real (asynchronous) order saga outbox orchestration

**Feature/Improvement Overview.**
`OrderSaga.createOrder`
(`// verified: services/order/.../service/OrderService.java:54-75`) takes
`unitPrice` straight from the client request and computes totals from it — no
server-side validity check against the menu. `OrderSaga`
(`// verified: services/order/.../saga/OrderSaga.java`) is a **hollow stub**:
the reserve-stock/charge-payment steps return JSON, and compensations
`log.info` only. Worse, `SagaCoordinator.executeSaga` runs synchronously inside
the *same* `@Transactional` DB tx as order creation
(`// verified: services/order/.../service/OrderService.java:45` +
`SagaCoordinator.java:33`) — a rolled-back order tx erases saga state, and
synchronous external calls hold DB connections. The default
`PlatformEventPublisher` is `NoOpEventPublisher`
(`// verified: services/platform-lib/.../event/EventPlatformConfig.java:23`),
so even OrderSaga's events never reach Kafka unless `app.events.external.enabled=true`.

Solves: price tampering / revenue leakage, fake order confirmation, DB
connection exhaustion, lost saga audit trails.

Strategic value: **transactional integrity under concurrency**; the order is the
domain's most critical business object.

**Technical Implementation Roadmap.**

*Logic Modifications.*
- `OrderService.createOrder`: fetch menu snapshot via `RestaurantClient.getMenu`
  **before** opening the write tx (avoids `subscriptionService` blocking-call
  pattern audit-guide flagged — `SubscriptionService.java:174`); re-price
  server-side; reject items that are unavailable/disabled.
- Rewrite `OrderSaga` steps as **outbox-driven asynchronous sagas**: each step
  enqueues an event (`order_stock_reserved`, `payment_charged`) *in the
  ordering service's own tx*; the *next* step executes on consumer side via the
  saga coordinator's `SagaInstance/SageStep` records (platform-lib already has
  these entities + `SagaCoordinator` machinery). Compensation runs as reverse
  outbox events.
- Remove `this.executeSaga(...)` self-invocation; call via a **separate
  `@Component` (collab bean)** so `@Transactional` is not proxy-bypassed
  (audit-guide V-05 / RC-B).
- `OrderEventPublisher.enqueue` failures must **propagate** to roll back the
  business tx (not swallow-and-log — `// verified: OrderEventPublisher.java:40-42`).

*Architectural Impact.*
Converts a synchronous, in-transaction, no-op saga into a **durable,
observable, compensating** distributed workflow. Couple order → payment via
events, not blocking calls. Enables future horizontal order scaling without
coordinator state in the request thread.

*Dependency Analysis.*
- New: none (reuses platform-lib outbox + saga entities).
- Breaking: order-create latency becomes "accepted" (202) until orchestration
  reaches `CONFIRMED`. Add `GET /orders/{id}` to poll status. Introduce
  `OrderStatus.DELETED_PENDING_PAYMENT`. No money moves until saga completes.

*Complexity & Risk.*
**Risk: High.** Changes a core domain flow. Mitigation: keep a synchronous
fallback path for the existing `processOrder` (legacy) during rollout; add a
saga-replay integration test. 2-3 dev-weeks.

Cross-ref: audit-guide V-01, V-05, V-09, V-12.

---

## 4. Loyalty & referral durable ledger + abuse controls

**Feature/Improvement Overview.**
Growth's `LoyaltyServiceImpl`
(`// verified: services/growth/.../serviceImpl/LoyaltyServiceImpl.java:51-55`)
increments **bare Redis counters** — a Redis flush/eviction silently destroys all
customer loyalty balances with **no DB ledger** to rebuild from. The
`loyalty_points_ledger` table exists in Flyway but has no entity/repo. The
`POST /customers/{id}/loyalty/credit?points=N` endpoint
(`// verified: services/growth/.../api/GrowthController.java:35-42`) has **no
auth/role check** (growth has `spring-boot-starter-security` but no
`SecurityFilterChain` — grep found none in growth) and **no idempotency key**.
Redemption (`LoyaltyServiceImpl:61-84`) is a TOCTOU check-then-decrement →
overspend. Referral `ReferralServiceImpl.applyReferral` overwrites
`referredBy` on every call (double-claim) with no unique constraint.

Solves: catastrophic balance loss, referral fraud, point inflation.

Strategic value: **program integrity** — loyalty budgets fund discounts; a
Redis-only ledger is a write-off waiting to happen at scale.

**Technical Implementation Roadmap.**

*Logic Modifications.*
- Build JPA entities/repos for `loyalty_points_ledger` (append-only,
  `customer_id, type(CREDIT|DEBIT), points, order_id, created_at`) +
  `promotion_campaigns`, `referral_records`.
- Replace `INCRBY` with: append a ledger row (single tx) then
  `UPDATE customers SET points = points + :delta WHERE customer_id=:id`.
  Recompute counters from the ledger nightly as an audit/reconciliation job.
- Redemption: Lua script `EVAL … IF balance-points < amt THEN abort ELSE
  SADD referred… INCRBY … RETURN ok` (atomic check-decrement). Or DB:
  `UPDATE customers SET points = points - :amt WHERE id=:cid AND points >= :amt`.
- `credit` endpoint: enforce service-JWT auth (`ServiceJwtAuthFilter`,
  platform-lib) + idempotency key on `IdempotencyRecord` (scope
  `LOYALTY_CREDIT`).
- Referral apply: `if (newCustomer.referredBy != null) return;` (reject) +
  partial unique index `UNIQUE(referred_by) WHERE referred_by IS NOT NULL`.

*Architectural Impact.*
Moves loyalty from an ephemeral cache to the same **ledger-based** model as
wallets — single pattern across all money-adjacent state. Adds real authn to a
critical surface.

*Dependency Analysis.*
- New: none (JPA + Redis atomic scripts; reuse platform-lib idempotency).
- Breaking: loyalty counter type may change (int→bigint); add Flyway migration.

*Complexity & Risk.*
**Risk: Medium.** Requires a backfill of existing Redis balances into the
ledger (one-time reconciliation). Regression: concurrent redemptions can't
overspend. 1-2 dev-weeks.

Cross-ref: audit-guide R-02, RC-A/RC-H.

---

## 5. Auth hardening (JWT fail-fast, refresh-token rotation, lockout, MFA)

**Feature/Improvement Overview.**
JWT signing secret defaults to a committed dev value everywhere
(`// verified: services/identity/.../application.yml:42`, and order/payment/etc.
validating-side defaults at `application.yml:59`/`73`). Access tokens live 24h
with no `jti`/`iss`/`aud` and no server-side revocation; "refresh" just re-issues
from a still-valid access token (`// verified: IdentityService.java:78-88`).
No lockout/velocity check on `/api/v1/auth/**` (rate-limit only on referrals).
MFA is stubbed: `users.totp_enabled` column exists but no enrollment/verification
flow; OTP controllers have no service impl. `ServiceJwtAuthFilter` lets
requests without `X-Service-Token` through untouched
(`// verified: platform-lib/ServiceJwtAuthFilter.java:41-51`).

Solves: forged-token auth-bypass, credential-stuffing/brute-force, session
hijack persistence, no second factor, internal-API impersonation.

Strategic value: **auth is the perimeter**; HS256 single shared secret across
all services is a repo-wide single point of compromise.

**Technical Implementation Roadmap.**

*Logic Modifications.*
- Fail-fast at startup: `SecretValidationConfig` (exists) must validate
  `app.auth.jwt.secret` (align the property-name mismatch: it currently checks
  `app.jwt.secret` — `// verified: SecretValidationConfig.java:45`) and refuse
  to boot if the value is the default/empty in `prod` profile.
- Issue JWTs with `jti`, `iss`, `aud` (per-service audience), `iat`, `exp`;
  role claims (not single `scope`). Rotate to **RS256 + JWKS** (platform-lib
  already anticipates this in `JwtSecretRotationService`).
- Implement a refresh-token store: hashed, rotating, revocable per device;
  detect reuse of a rotated token → invalidate the whole family (audit-guide §5.2
  pattern).
- Add Redis-backed failed-login counters per `(email, IP)` + exponential
  backoff/`@PreAuthorize` lockout window on `POST /auth/login`.
- Wire TOTP: enroll (QR + secret), verify on login (`TOTPGenerator` exists in
  platform-lib), persist `totp_confirmed_at`.
- `ServiceJwtAuthFilter`: require (and verify) `X-Service-Token` on
  `/internal/**`; reject (401) when absent — never pass-through.
- Map `RateLimitAspect` onto `/auth/login`, `/auth/register`; move
  `InMemoryRateLimitService` → `RedisRateLimitService` (atomic Lua script —
  current `INCR`+`EXPIRE` is racy, audit-guide V-18/RC-A).

*Architectural Impact.*
Moves a shared-HS256, 24h-stale-token, no-revocation model to **per-service
audience RS256 + revocable refresh tokens + rate-limited brute-force
protection** — the standard for heavy-traffic platforms.

*Dependency Analysis.*
- New: Nimbus JOSE + JWKS-auth0 + a small TOTP lib (or use platform-lib
  `TOTPGenerator`). No new major infra (Redis already present).
- Breaking: existing tokens signed with the old HS256 key are invalid →
  coordinated cutover / dual-key grace window.

*Complexity & Risk.*
**Risk: Medium.** Token cutover requires all services to verify new key set;
test auth end-to-end with forged/expired/reused tokens. ~2-3 dev-weeks.

Cross-ref: audit-guide V-02, V-03, V-13, V-14, V-15.

---

## 6. Authorization model: IDOR fixes + service-to-service auth

**Feature/Improvement Overview.**
Multiple services trust client-supplied path/header identifiers without
binding them to the authenticated principal: `CustomerController` trusts
`{customerId}` (`// verified: identity/.../api/CustomerController.java:26-48`);
`WalletController` trusts path param / `X-Customer-Id` header
(`// verified: payment/.../api/WalletController.java:27-31,45`);
`RecommendationController` reads `X-Customer-Id` raw header
(`// verified: personalization/.../api/RecommendationController.java:22-52`).
`/internal/**` is `permitAll`
(`// verified: identity/SecurityConfig.java:50`), so the token-introspection
endpoint is an unauthenticated oracle. Restaurant `POST /restaurants` / toggle
busy mode have no ownership checks (`// verified: RestaurantController.java:37-41,61-75`).

Solves: horizontal privilege escalation (BOLA), impersonation, support-ticket
forgery, restaurant takeover.

Strategic value: **identity != authorization** — fixing this is the cheapest
way to close the widest attack surface.

**Technical Implementation Roadmap.**

*Logic Modifications.*
- Introduce a `UserId` accessor on the principal (`SecurityUtils.currentUserId()`),
  and replace every `request.path("/customers/{id}")` with a guard:
  `if (!currentId.equals(pathId)) throw 403`.
- `/internal/**` everywhere → `antMatcher(...).hasRole("SERVICE")` gated by
  `ServiceJwtAuthFilter` (see #5).
- Add `@PreAuthorize("hasRole('ADMIN')")` on admin mutation surfaces
  (restaurant create/toggle, growth credit, admin-analytics exports).
- `RecommendationController`: derive customer from `TokenPrincipal`, remove
  `X-Customer-Id` header.

*Architectural Impact.*
Enforces a **principal-bound access-control** layer consistently. No new
service boundaries — reuses platform-lib filters.

*Dependency Analysis.*
- New: `@PreAuthorize` (already on classpath via Spring Security).
- Breaking: previously-permissive endpoints now 403 for unauthorized callers;
  document the new principal derivation (JWT `sub`).

*Complexity & Risk.*
**Risk: Low-Medium.** Mechanical; regression risk is authorization regressions
— add tests for cross-customer access. ~1-2 dev-week.

Cross-ref: audit-guide RC-H (V-15), R-08.

---

## 7. Event backbone durability (outbox two-phase, idempotent delivery)

**Feature/Improvement Overview.**
`OutboxPollPublisher` (`// verified: platform-lib/.../outbox/OutboxPollPublisher.java:52-91`)
claims PENDING rows with `FOR UPDATE SKIP LOCKED` and publishes to Kafka
**synchronously inside the same `@Transactional`** — up to ~17 min worst-case
lock/connection hold for a 100-row batch (audit-guide V-11/RC-J). But
`drainBatch` is the only scheduled job — `recoverStale()`
(`// verified: OutboxPollPublisher.java`) is invoked **only from tests**, so a
broker blip strands `PROCESSING` rows permanently (audit-guide V-09). The
retry counter is bumped but **never compared to a max** — no auto-DLQ write
to `dead_letter_events` (audit-guide V-10). Kafka producer is unhardened in
the **active** platform factory (`// verified: common/kafka/KafkaPlatformConfig.java:56-61`
sets only bootstrap + serializers → `acks=1` broker default, no idempotence,
no linger/batch), and a second, legacy wiring
(`// verified: common/config/KafkaConfig.java:28-31`) *does* set
`acks=all`/idempotence but is gated by `app.events.external.type=kafka` —
when both gates ever pass, the two `@Configuration` classes collide on bean
names (`producerFactory`, `kafkaListenerContainerFactory`). The three services ship
with `app.events.external.enabled=false, type=log`
(`// verified: identity/order/payment application.yml`) → the poller bean
never even materializes in prod, so `outbox_events` accumulate forever.

Solves: event loss, duplicate events, unbounded backlog, phantom
"delivered" markers, reconciliation impossibility.

Strategic value: **event-driven integrity** — every money/ownership change is
a durable, ordered, acknowledged event. The backbone is the nervous system.

**Technical Implementation Roadmap.**

*Logic Modifications.*
- **Two-phase relay:** `drainBatch` becomes (1) claim-only tx
  (`FOR UPDATE SKIP LOCKED`, flip PENDING→PROCESSING, commit), (2) publish to
  Kafka outside any tx, (3) state-update tx (PUBLISHED | bumpRetry | FAILED).
- Schedule `recoverStale()` at `fixedDelay = processingTimeout`
  (`// verified: OutboxPollPublisher.java:133` only schedules `poll`).
- Enforce `maxRetries` (wiring the dead `OutboxProperties.maxRetries`) → after
  N, call `DeadLetterEventService.record(...)`; wire `OutboxMetrics` alert.
- Add `next_attempt_at` column + exponential backoff instead of immediate
  re-claim on failure.
- Parallelize: partition claimed batch by `aggregate_id % N` and drain
  partitions concurrently (preserves per-aggregate ordering).
- Enable `app.events.external.enabled=true` in prod overlays; wire a
  `DefaultErrorHandler` + `DeadLetterPublishingRecoverer` to the configured
  `.dlt` topics on the listener container factory
  (`// verified: KafkaPlatformConfig.java:93-100` has none).
- Producer config: `acks=all`, `enable.idempotence=true`, `retries=Integer.MAX`,
  `linger.ms=5`, `compression=producer`.
- Consumers: scope dedupe by `eventId` via `idempotency_records`
  (`IdempotencyRecord.java:64` defines a `KAFKA_CONSUME` enum never used) —
  audit-guide V-10 root-cause fix.

*Architectural Impact.*
Transforms the event path from "best-effort inline publish" to a
**durable, acknowledged, dedupable, replayable** backbone. Decouples business
tx from broker I/O, eliminating connection-exhaustion under broker pressure.

*Dependency Analysis.*
- New: none (Debezium/Connect is the Phase-2 streaming option — `docs/` mention
  it as a future enhancement; not required for the two-phase fix).
- Breaking: consumers must tolerate redelivery (idempotency now mandatory, not
  "should be").

*Complexity & Risk.*
**Risk: Medium.** The two-phase claim/publish/commit is the classic correct
pattern but changes ordering semantics (events may briefly reorder by
aggregate under parallel drain — mitigated by partition-by-aggregateId).
Regression test: crash the publisher mid-batch, assert `recoverStale` requeues.
2-3 dev-weeks.

Cross-ref: audit-guide V-01, V-09, V-10, V-11, P-03, P-07.

---

## 8. Circuit-breakers that actually open + sane retry discipline

**Feature/Improvement Overview.**
`CircuitBreakerFilter` (`// verified: platform-lib/.../web/client/CircuitBreakerFilter.java:36-45`)
constructs a Resilience4j breaker but **reads its state in `onErrorResume`** —
it never *decorates* the exchange, so the breaker never transitions to OPEN;
callers (`RestaurantClient`) believe they have protection they don't
(audit-guide V-16/RC-E). `RetryFilter` uses
`backoff.toMillisPart()` (the **millisecond** component — e.g. 5s→0ms first
retry) and retries on **any** `RuntimeException` including 4xx/idempotency
violations
(`// verified: RetryFilter.java:31-45`). `WebClientConfig.loadBalancedWebClient`
applies `@LoadBalanced` for a discovery client this architecture explicitly
doesn't use, while real callers build their own `WebClient` **without** the
filters.

Solves: cascading failures from unprotected downstream flaps, retrying
non-idempotent POSTs (double charges).

Strategic value: **resilience is only real if it opens** — a breaker that
cannot open is a `try/catch`.

**Technical Implementation Roadmap.**

*Logic Modifications.*
- Decorate the exchange with a registry-backed breaker using the Resilience4j
  `reactor` operator (`CircuitBreakerOperator.of(breaker)` applied around the
  downstream `Mono`); register `CircuitBreaker` in a `CircuitBreakerRegistry`
  for `cb.state()` metrics.
- `RetryFilter`: fix backoff to `Duration.ofMillis(...)`; restrict retry
  predicate to 5xx + timeouts + idempotent methods (GET/PUT/DELETE); add
  `Retry` registry + `attempts` metric. Drop `toMillisPart` bug.
- `WebClientConfig`: drop `@LoadBalanced` (no discovery client); expose a
  `WebClient.builder()` that composes the real filters **with explicit
  connect/read timeouts** (currently hardcodes 5s docs-claim / code differs).
- Make `CircuitBreakerFilter`/`RetryFilter` **decorate the same `WebClient`**
  that `RestaurantClient`/other callers actually use (audit-guide P-05: HTTP
  client stack fragmentation).

*Architectural Impact.*
Unifies HTTP resilience into a single, *correct* `WebClient` factory bean in
platform-lib — callers stop building unarmored clients.

*Dependency Analysis.*
- New: `resilience4j-reactor` (already pulled transitively by
  platform-lib's starter; confirm version).
- Breaking: retry semantics (now only idempotent ops) — safe default.

*Complexity & Risk.*
**Risk: Low-Medium.** The logic is small; the regression risk is "breaker
finally opens during a flap" which is desirable. Add a test that forces N
consecutive failures and asserts state transitions OPEN→HALF_OPEN. ~1 dev-week.

Cross-ref: audit-guide V-16, P-05, RC-E.

---

## 9. Rate-limiting discipline + edge throttling

**Feature/Improvement Overview.**
`RedisRateLimitService.check()` (`// verified`) does `INCR` then `EXPIRE` as two
**non-atomic** Redis calls → a crash between them leaves the bucket key with no
TTL → that identifier is 429'd forever (audit-guide V-18/RC-A). A Redis outage
propagates as a 500 storm (fail-closed, no bypass). `InMemoryRateLimitService`
side-maps are unbounded (slow leak). The **gateway has no edge
rate-limiter** at all
(`// verified: GatewayConfig/GatewaySecurityHeadersConfig` — grep found none),
so all abuse pressure reaches every service's 5 Hikari connections each.

Solves: self-DoS via Redis key leak, fleet 500 storms, connection-pool
exhaustion, SMS/email pumping.

Strategic value: **cheapest protection with the best ROI** — rate-limit at the
edge before any backend connection is consumed.

**Technical Implementation Roadmap.**

*Logic Modifications.*
- Replace `INCR`+`EXPIRE` with a single **Lua script**:
  `EVAL "local c=redis.call('INCR',KEYS[1]); if c==1 then redis.call('EXPIRE',KEYS[1],ARGS[1]) end; return c" 1 key ttl`.
- Add Redis-exception handling: fail **open** when `app.rate-limit.fail-open=false`
  (configurable), emit a `ratelimit.bypass.redis_error` counter.
- Bound `InMemoryRateLimitService`'s maps (e.g., `Map.computeIfAbsent` + a
  periodic sweep; or drop in favor of Redis always for prod).
- Wire `spring.cloud.gateway.redis-rate-limiter` (or Bucket4j) per route on the
  **gateway** as the first defense — audit-guide V-10.
- Add decision metrics (`ratelimit.allowed`/`ratelimit.denied`) + `Retry-After`.

*Architectural Impact.*
Pushes the abuse boundary to the edge; services then rate-limit only on the
"real" business semantics (e.g., orders-created/min) rather than surviving
connection-floods.

*Dependency Analysis.*
- New: `spring-boot-starter-data-redis-reactive` in gateway (already pulled?
  confirm); possibly Bucket4j.
- Breaking: none to API contracts; 429s become more accurate (fewer false 429
  from leaked TTLs).

*Complexity & Risk.*
**Risk: Low.** Lua atomicity is a 1-day change; edge wiring is ~3 days.
Regression test: assert key has TTL after a cold `INCR`, assert fail-open sets
counter.

Cross-ref: audit-guide V-18, V-20, P-02.

---

## 10. Caching at scale (menu snapshot cache + invalidation)

**Feature/Improvement Overview.**
The battle-tested `RedisCacheService` (Caffeine L1 + Redis L2, single-flight
locks, TTL jitter, probabilistic early expiry, distributed invalidation pub/sub)
exists in platform-lib but is **unused by the hottest read paths**. Restaurant
menu snapshots are fetched per-order via `/internal/menu/snapshot`
(`// verified: restaurant/.../api/InternalMenuController.java:21-24`) and hit
Postgres every time. Two in-flight cache implementations drift
(`RedisCacheService` vs `LocalCacheService`), and `RedisCacheService.waitForValue`
busy-waits up to 3.5s on the request thread then **every waiter recomputes the
supplier** (thundering herd, audit-guide V-06/RC-G).

Solves: redundant DB hits on the single highest-traffic read (checkout pricing),
cache stampedes, inconsistent read models.

Strategic value: **the difference between "works dev" and "handles 1k RPS."**

**Technical Implementation Roadmap.**

*Logic Modifications.*
- Wrap `RestaurantQueryService.menuSnapshot` with
  `RedisCacheService.getOrCompute("menu:restaurant:{id}", ttl=300s, () -> …)`.
- On menu mutation (`RestaurantAdminService.updateAvailability`,
  `MenuItemService.create/update/delete`), publish
  `RestaurantEvents.MenuItemUpdated` (outbox, audit-guide V-11) → invalidate
  `menu:restaurant:{id}` via `CacheInvalidationService`.
- Fix `waitForValue`: replace busy-wait with `Caffeine AsyncCache`/single
  `CompletableFuture` per key in-JVM (single-flight); cap wait to a fraction
  of TTL.
- Unify invalidation: one channel (`bhukkad:cache:invalidate`), L1+L2 eviction,
  SCAN-based bulk delete reused (audit-guide V-06/RC-G).

*Architectural Impact.*
Establishes the **single, correct cache-aside pattern** with coherent
invalidation — the repo already has the implementation; the gap is adoption.

*Dependency Analysis.*
- New: none (platform-lib already has it).
- Breaking: cached snapshots introduce bounded staleness (≤300s) — acceptable
  for menus; document it.

*Complexity & Risk.*
**Risk: Low.** Library already written; adoption is wiring. Regression: cache
miss storm → assert single recompute under concurrent reads. ~1-2 dev-weeks.

Cross-ref: audit-guide V-06, RC-A-adj, R-01.

---

## 11. Search: event-driven indexing + geo (decision + scope)

**Feature/Improvement Overview.**
Search has **no Elasticsearch/Opensearch dependency** at all
(`// verified: services/search/pom.xml` — grep found none). `SearchServiceImpl`
does `findAll()` on both restaurant + menu tables per request and filters with
Java `.contains()` (`// verified: SearchServiceImpl.java:97-108,167-178`) —
no pagination, no scoring, no index. Restaurant entities are never indexed (no
save path), and no service calls the push endpoints, so search **always returns
zero restaurants**. No event-driven sync, no delete propagation, no geo.

Solves: search returns nothing / full-table scans under load.

Strategic value: **discoverability conversion**; broken search kills conversion.

**Technical Implementation Roadmap.**

*Logic Modifications.*
- Decision: adopt **Elasticsearch/Opensearch** (the `ARCHITECTURE.md` stack
  already names it) OR double down on **PostgreSQL `tsvector` + `pg_trgm`**
  (restaurant service already has a superior `MenuSearchService` with
  `tsvector` — `// verified: restaurant/.../MenuSearchService.java:29-38`).
  Recommendation: ES for geo/fuzzy/scoring at scale (P0), with the PG path as
  the fallback decision if ES is deferred.
- If ES: add the client dep; add a search-service Kafka consumer on
  `RestaurantEvents.MenuItemUpdated`/`RestaurantCreated`/`MenuItemDeleted`;
  bulk-upsert into ES indices; periodic reconciliation job; geo_point fields
  for "near me"; fuzzy matching.
- If PG path: create a unified `restaurant_search`/`menu_search` denormalized
  table, `tsvector` index, `pg_trgm` for fuzzy; populate via the same event
  consumer (or outbox → search).
- Add a `DELETE /api/v1/search/internal/menu-items/{id}` for delete
  propagation; add a bulk reindex endpoint.

*Architectural Impact.*
Converts search from "in-memory `contains()`" to a **queryable, synced,
geospatial** index — and closes the broken push contract by event-driven sync.

*Dependency Analysis.*
- New (ES): `org.springframework.boot:spring-boot-starter-data-elasticsearch` +
  an ES cluster.
- Breaking: none if endpoints keep their response shape; the *results* change
  (now return restaurants). If choosing the PG path: new index DDL + a
  migration.

*Complexity & Risk.*
**Risk: Medium.** ES path: 3-4 dev-weeks + infra. PG path: 2 dev-weeks, lower
risk. Regression: search returns the restaurant that was invisible before.

Cross-ref: audit-guide V-04, R-01.

---

## 12. Delivery intelligence (GEO matching, ETA, OSRM, load cap)

**Feature/Improvement Overview.**
Agent matching is `findFirstByIsActiveTrue` — no proximity, no zone, no load
(`// verified: DeliveryAgentRepository.java:8` / `DeliveryService.java:28`).
The unique constraint on `order_id` is missing (non-unique index) → duplicate
assignments under concurrent `POST /assign`. The OSRM road-distance client is
**dead code** (never called, `enabled=false`, `// verified: RoadDistanceService.java`,
`OsrmClient.java`, `RoadDistanceProperties.java`). `EtaService` is non-Spring
(no wiring, `// verified: EtaService.java:11` has no `@Component`) and live ETA
always empty. Serviceability is a `.equalsIgnoreCase` over `findAll()` — no
geospatial model, no PostGIS. Rider pings are unbounded inserts with unbounded
reads; `markDelivered` is a read-modify-write with no version → duplicate
`OrderDelivered` events.

Solves: inefficient/noisy matching, duplicate deliveries, fake ETA, service
misses.

Strategic value: **operational cost** — a dumb matcher inflates delivery
distance and rider churn at scale.

**Technical Implementation Roadmap.**

*Logic Modifications.*
- Matching: `Redis GEOSEARCH` over `geo:rider:locations` (key already exists,
  `// verified: RiderLocationTrackingService.java:19`) within a radius, score
  by (distance + activeLoad + rating), pick lowest; atomically assign via
  `INSERT … ON CONFLICT (order_id) DO NOTHING` (or `SELECT…FOR UPDATE` +
  unique constraint).
- Load cap: conditional update
  `UPDATE agents SET active_load = active_load+1 WHERE active_load < cap`.
- Wire OSRM `RoadDistanceService` into ETA + assignment (it exists; enable and
  call). Fix `@TimeLimiter` misuse — make the RestTemplate call async or drop
  TimeLimiter (it can't interrupt a sync thread).
- Serviceability: add PostGIS geometry + GiST index, point-in-polygon check
  vs the string name match; or at minimum lat/lng + radius.
- `markDelivered`: `UPDATE deliveries SET status='DELIVERED' WHERE id=:id AND
  status != 'DELIVERED'` — make it idempotent; version column.
- Rider ping retention: time-window query + scheduled purge/partitioning on
  `rider_location_updates`.

*Architectural Impact.*
Moves from "assign anyone" to **proximity-aware, load-balanced, idempotent**
assignment with real ETAs — the core delivery SLA.

*Dependency Analysis.*
- New: PostGIS extension (Postgres already used); optionally the OSRM client
  is already built (just wire it).
- Breaking: none to external API; assignment semantics change (fewer misroutes).

*Complexity & Risk.*
**Risk: Medium.** GEO logic + DB migration (PostGIS GiST). Regression: no
double-assignment under concurrent assign requests. 2-3 dev-weeks.

Cross-ref: audit-guide V-01 (assignment race), RC-A.

---

## 13. Realtime fan-out (Redis pub/sub bridge + stream auth)

**Feature/Improvement Overview.**
`RedisOrderLiveRelay` in realtime publishes to `live:channel:*` but **nothing
subscribes** (`// verified: realtime/.../RedisOrderLiveRelay.java:42-60,75-90`
— no `RedisMessageListenerContainer` registered anywhere); one subscriber
per channel, `localConsumers` grows unbounded (P2/RC-G leak). Per-instance SSE
emitters with a 5-minute hardcoded timeout, no ownership check on
subscriptions (any user can subscribe to any order live stream), and the
`/live/*/broadcast` endpoints accept arbitrary `OrderLiveUpdate` from *any*
authenticated principal — **any user can spoof live status**. Deliver's twin
SSE impl is a near-duplicate of realtime's (RC-F drift).

Solves: broken live tracking end-to-end, spoofed status updates, connection
state leaks, multi-instance dead-end.

Strategic value: **the customer-visible live-update promise** — if it doesn't
fan out across pods or can be spoofed, it's not a feature, it's a fraud vector.

**Technical Implementation Roadmap.**

*Logic Modifications.*
- On boot, register a single `RedisMessageListenerContainer` per service
  instance subscribing to a `PatternTopic("live:channel:*")`; on message,
  deserialize and fan out to the in-memory `SseEmitter` set for that channel.
  This replaces per-channel subscriber creation (the leak in #43).
- Enforce **ownership/auth** per subscription: require the tracking token
  (`RiderLocationTrackingService` already mints signed tokens) or verify the
  caller's role/claims against the `orderId`/`agentId`/`restaurantId`.
- `broadcast` endpoints → require a `SERVICE`-role JWT (`ServiceJwtAuthFilter`).
- Streamline the per-instance emitter set: configurable timeout, heartbeat,
  eviction on disconnect; remove the duplicate impl (extract to platform-lib
  SSE helper).
- Pipeline the replay-store Redis ops (ZADD+ZCARD+ZREMRANGEBYSCORE+EXPIRE)
  and trim by score, not count.

*Architectural Impact.*
Makes real-time updates **multi-instance-safe** without sticky sessions/ingress
affinity, and closes the spoofing hole by binding streams to caller identity.

*Dependency Analysis.*
- New: none (Redis pub/sub + Spring Web SSE already used).
- Breaking: `/live/*/broadcast` now requires service auth (internal only).

*Complexity & Risk.*
**Risk: Low-Medium.** Wiring is mechanical; the authn fix is the important
correctness change. Regression: multi-instance fan-out delivers to the right
single client only. ~1-2 dev-weeks.

Cross-ref: audit-guide V-06, V-07, RC-D.

---

## 14. Observability stack (Prometheus/alerts, tracing, logs, PII)

**Feature/Improvement Overview.**
**No Prometheus server or scrape config exists** — pods carry scrape
annotations but nothing scrapes them; **zero alert rules** → every SLO in
`monitoring/README.md:29-34` is unenforced. Tracing points Zipkin at
`http://localhost:9411` everywhere
(`// verified: order/.../application.yml:33-34` + same in gateway/survey/
notification/search/restaurant/payment/delivery) — no backend deployed, spans
go nowhere (platform-lib has the OTel bridge already, `micrometer-tracing-bridge-otel`).
Log aggregation is a half-manifest: `fluent-bit-configmap.yaml` references
`${LOKI_HOST}` but no DaemonSet/RBAC runs it, no Loki deployed. The PII-masking
`PiiMaskingConverter` exists (`// verified: platform-lib/.../logging/PiiMaskingConverter.java:29-35`)
but no service has a `logback-spring.xml` to register it;
`TwilioSmsSender`/`ResilientEmailSender` log raw phone numbers/emails
(`// verified` in notification audit).

Solves: blind ops, no alerting on money/error paths, PII leakage, no tracing
for latency triage.

Strategic value: **you cannot run what you cannot see** — observability is a
production-readiness gate, not a nice-to-have.

**Technical Implementation Roadmap.**

*Logic Modifications.*
- Deploy Prometheus (kube-prometheus-stack or equivalent) + ServiceMonitors
  per service; import the existing Grafana SLO dashboard
  (`monitoring/grafana/bhukkad-slo-sli.json`).
- Ship a `PrometheusRule` with **actionable** alerts:
  `outbox_events > 1000 for 5m`, `saga_compensation_failed`, `wallet.adjust.rejected
  > 0`, `payment_settled.error_rate > 1%`, `kafka_consumer_lag > 1000`,
  `hpa at maxReplicas > 5m`, `p95 latency vs SLO 1s`.
- Wire FluentBit DaemonSet + Loki (or a hosted log backend); set
  `${LOKI_HOST}` via ConfigMap/env.
- Register `PiiMaskingAppender` in each service's `logback-spring.xml`:
  `<encoder><pattern>%d … %msg%n</pattern><pii/></encoder>`.
- Make tracing backend configurable per profile (`MANAGEMENT_ZIPKIN_TRACING_ENDPOINT`
  → prod points at a real Tempo/Jaeger; `TRACING_SAMPLE_PROBABILITY=0.1` in prod
  vs 1.0 in dev).

*Architectural Impact.*
Completes the **signals-3** triangle (metrics/logs/traces) and enforces a
baseline alert policy — without it, no SLO is real.

*Dependency Analysis.*
- New: Prometheus Operator (or Helm chart), Loki/Grafana stack, FluentBit
  DaemonSet. No Java deps (platform-lib already has the OTel/Micrometer libs).
- Breaking: alert-firing may surface existing latent bugs (desirable).

*Complexity & Risk.*
**Risk: Low-Medium.** Pure infra; risk is alert-noise (start permissive:
page only on RED metrics + business SLO). ~2-3 dev-weeks infra + alert-tuning.

Cross-ref: audit-guide §7, R-07 (Redis blast-radius shared by cache+SSE+rate-limit).

---

## 15. Backup & DR (per-service backups, PITR, restore drills)

**Feature/Improvement Overview.**
Backups cover **one** database (the legacy `bhukkad` DB) but the platform is
database-per-service (15+ Postgres DBs) (`// verified: k8s/backup-scripts.yaml:25-29`
dumps a single `DB_NAME`). No WAL archiving → **no PITR, RPO=24h**. S3 upload
is optional (`if [ -n "${S3_BUCKET}" ]`) and never set in the CronJob →
backups live on a single in-cluster PVC. No restore drill, no backup-success
alert.

Solves: unrecoverable loss across 14/15 service databases, 24h RPO on money
data.

Strategic value: **data is the only thing you can't code your way out
of** — a single `rm -rf` or ransomware wipes months of orders/wallet history.

**Technical Implementation Roadmap.**

*Logic Modifications.*
- Rewrite the backup script to loop over **all** service DBs (or `pg_dumpall`
  with per-DB `--file`), using the secrets already defined in
  `k8s/secrets.yaml`.
- Enable WAL archiving (`archive_command` to S3-compatible, or use
  `pgBackRest`/`wal-g`) → enable PITR/RPO < 5min.
- Set `S3_BUCKET` in the CronJob (`k8s/backup-cronjob.yaml:30-53`,
  `backup-pvc.yaml`) — unbreak the off-site copy.
- Add a **restore drill** script + a monthly CI job that restores to a
  throwaway namespace.
- Add a backup-success alert (`backup_job_failed` / `pg_basebackup_lag_minutes`).

*Architectural Impact.*
Shifts from "hope the PVC survives" to **RPO/RTO-bounded** recovery per the
repo's own database-per-service model.

*Dependency Analysis.*
- New: `wal-g` or `pgBackRest` container image; S3 credentials (already mapped
  via Vault `external-secret.yaml`).
- Breaking: none; larger backup footprint.

*Complexity & Risk.*
**Risk: Low.** Script change + a tool image. Regression: a weekly restore
drill test. ~1-2 dev-weeks.

Cross-ref: audit-guide P-01 (capacity triangle), infra backup bullets.

---

## 16. CI/CD hardening (image scan/SBOM, digest pins, GitOps, gates)

**Feature/Improvement Overview.**
No container scanning, SBOM, or signing — images build and push with zero
gates (`// verified: .github/workflows/production.yml:50-60`). Images pin to
mutable `:latest` (`// verified: k8s/order/deployment.yaml:39`
`ghcr.io/bhukkad/order:latest`) while CI pushes `:${{ github.sha }}` tags
nothing consumes. The CI coverage step *says* "85%" but `services/pom.xml`
enforces 50% line / 20% branch
(`// verified: services/pom.xml:125-139` vs `ci.yml:105-106`). Smoke tests
end with `curl … || true` (always pass
(`// verified: production.yml:140`). `survey/referral/supportticket` are
**not** in the root kustomization → never deployed
(`// verified: k8s/kustomization.yaml`). `k8s/secrets.yaml` and
`external-secret.yaml` **both** materialize `bhukkad-secrets`, racing and
clobbering real Vault values with `CHANGE_ME_*` placeholders
(`// verified: k8s/kustomization.yaml:9,32`). `kustomization.yaml:33` has
broken 3-space indentation → `kubectl apply -k k8s/` likely can't build.

Solves: unscanned vulnerabilities, deploy of stale/unsigned images, missing-
service deployments, secret clobbering on every deploy.

Strategic value: **supply-chain + deploy integrity** — the last gate before
prod.

**Technical Implementation Roadmap.**

*Logic Modifications.*
- Add Trivy scan (fail on HIGH/CRITICAL), Syft SBOM, cosign sign to the
  build-and-push job; consume digest-pinned refs via `kustomize images:`.
- Fix the coverage label vs threshold mismatch (raise pom to the repo's
  stated 85%, or fix the label); archive reports per module
  (`services/*/target/site/jacoco/`).
- Drop `|| true` on smoke tests; add retries + JSON-body assertions.
- Fix `k8s/kustomization.yaml:33` indentation; add survey/referral/
  supportticket to the base list; resolve the secrets-vs-ExternalSecret race
  (`creationPolicy: Merge` or drop `secrets.yaml` from base).
- Adopt **GitOps** (ArgoCD/Flux): render kustomize in CI to an artifact, let
  the controller reconcile → drift detection + removes the "kubeconfig on the
  runner" assumption.

*Architectural Impact.*
Moves deployment from "imperative kubectl from CI" to **declarative GitOps**
with verifiable, signed, scanned artifacts.

*Dependency Analysis.*
- New: Trivy/Syft/cosign GitHub Actions; an ArgoCD/Flux Helm chart.
- Breaking: none (GitOps is additive; the kustomization fix is a correctness
  repair).

*Complexity & Risk.*
**Risk: Low-Medium.** Mostly workflow YAML. Regression: a fresh
`kubectl apply -k k8s/` must build and a sample deploy must succeed.
~2-3 dev-weeks.

Cross-ref: audit-guide R-01, R-05; infra CI/CD bullets.

---

## 17. Secrets remediation (committed TLS private key, fail-fast secrets)

**Feature/Improvement Overview.**
A **real TLS private key is committed to git** at
`docker/nginx/ssl/privkey.pem` (fullchain too)
(`// verified: git ls-files` confirms both) — a P0; rotate immediately + purge
from history. JWT secrets default to committed dev values repo-wide (#5), and
`SecretValidationConfig` validates the **wrong property** name (`app.jwt.secret`
vs the `app.auth.jwt.secret` that services actually bind — V-15/RC-H).
`ServiceJwtAuthFilter` passes through requests with no `X-Service-Token`
(V-15). DB password defaults (`bhukkad_pass`) appear in every service yml.

Solves: credential exposure, forged-token acceptance, internal-API
impersonation.

Strategic value: **stop the bleeding before hardening** — a committed key is
an active incident.

**Technical Implementation Roadmap.**

*Logic Modifications.*
- `git filter-repo` (or `BFG`) to purge `docker/nginx/ssl/privkey.pem` +
  `fullchain.pem` from history; `.gitignore` add `docker/nginx/ssl/*.pem`;
  rotate the key (cert-manager, referenced in `k8s/ingress.yaml:9`, or a
  mounted secret); remove nginx SSL termination (let ingress terminate TLS).
- Align `SecretValidationConfig` to validate `app.auth.jwt.secret` (and
  `app.auth.jwt.jwks-url`); fail startup if unset/weak in `prod`.
- `ServiceJwtAuthFilter`: require + verify `X-Service-Token` on
  `/internal/**`; 401 when absent.
- Remove DB-password defaults; fail fast on missing `DB_PASSWORD`/`DB_USERNAME`
  in prod. Add `.env*` to `.gitignore` (already there — V-19) but rotate the
  shared secret in `.env`/`.env.dev` which both contain the same
  `404E63…5970` key.
- Add **secret-scanning** to CI (gitleaks/trufflehog) to catch regression.

*Architectural Impact.*
Makes **secrets external and verified at startup** — no secure default can
be a known value.

*Dependency Analysis.*
- New: `gitleaks` or `trufflehog` GitHub Action; cert-manager.
- Breaking: deploy rotates TLS and JWT keys (coordinated cutover; services
  must accept both during grace).

*Complexity & Risk.*
**Risk: Medium-High.** The git-history purge is a one-time operational cost.
The key rotation needs a maintenance window. Regression: a prod boot with
missing secrets must refuse to start (not fall to defaults).

Cross-ref: audit-guide V-13, V-14, V-15, V-22, P-03.

---

## 18. Repository & platform-lib structure (de-dup, dead code, modules)

**Feature/Improvement Overview.**
`platform-lib` is a 213-file "everything-classpath"
(`// verified: platform-lib/src/main/java/com/bhukkad/common/` has 33 package
dirs: datasource, kafka, chaos, gateway-filters, testkits, …) with no module
boundary (audit-guide R-05). 14 per-service `Dockerfile`s duplicate ~30 lines
each (R-02). `Dispute*` classes live in 4 modules with no single-writer ADR
(R-04). Dead code is checked in: `RiderLocationTrackingService.java:61-88`
returns a token after logging a Redis failure (test asserts this — `// verified:
RiderLocationTrackingServiceTest.java:130-136`), `RoadDistanceService`/`OsrmClient`
are unwired stubs, `OrderSaga` is a stub, `GrowthCampaignServiceImpl` has
"In real implementation…", `.bak/` wallet packages compile into the artifact
(`// verified: supportticket/.../wallet-old.bak/` — permission-broken), two
competing k8s trees (`k8s/kustomize` vs `services/k8s/`, R-01), a `TrieIndex`
duplicated in two restaurant packages (R-03).

Solves: drift, accidental dead-code compilation, ambiguous ownership,
difficult future change.

Strategic value: **reduce the tax on every future change** — a clean module
graph and single-owner-per-domain makes fixes landable in days, not weeks.

**Technical Implementation Roadmap.**

*Logic Modifications.*
- Delete dead code now: `wallet-old.bak/`, OSRM stubs (or wire them),
  `OrderSaga` stub (or replace with the real async saga from #3), growth
  campaign stub (or implement + ship).
- Extract `platform-lib` into **cohesive sub-modules** (platform-security,
  platform-messaging, platform-web, platform-observability, platform-cache);
  services depend on the slice they need. This is the W7 "platform split".
- Adopt a **single k8s manifest tree** with overlays (dev/staging/prod);
  archive the dead `services/k8s/` and `blue-green/` monolith manifests.
- Consolidate the 14 Dockerfiles into a single heredoc-templated Dockerfile
  (or a shared base image with per-service `ENTRYPOINT` env).
- Write an ADR per cross-domain concept (`Dispute` ownership → order; `Wallet`
  → payment; `Refund` → payment) (R-04, R-08).

*Architectural Impact.*
Enforces **bounded contexts** and a clean dependency direction
(service → platform-lib slice, never platform-lib → service); removes the
"everything can see everything" coupling that hides bugs.

*Dependency Analysis.*
- New: none (build restructure only).
- Breaking: none to runtime behavior; Maven multi-module refactor must
  preserve the `com.bhukkad:platform-lib` coordinates so service POMs
  unchanged.

*Complexity & Risk.*
**Risk: Medium.** The platform-lib split touches 16 POMs; do it module-by-
module (security first) with a zero-diff `mvn verify` gate.
~2-4 dev-weeks.

Cross-ref: audit-guide R-01…R-09.

---

# PART II — Performance engineering playbook (P-01 … P-10)

Every item: *Evidence (verified) → Impact model → Step-by-step (with code) →
Verification → Effort/rollback*. Config-only items (P-01, P-02, P-04, P-03)
carry the highest throughput-per-engineer-hour and should ship in Phase P0.

### P-01 — Connection-sizing triangle (S1, config-only) — *largest lever*

**Evidence (verified this cycle).** `// identity/order/payment/restaurant
application.yml`: `maximum-pool-size: ${X_DB_POOL_SIZE:5}`; several services
pin literal `5`/`8`/`10` in `application-prod.yml`. Tomcat
`server.tomcat.threads.max` is set **nowhere** → Spring default **200**.
`k8s/pgbouncer/configmap.yaml`: `pool_mode=transaction`,
`default_pool_size = 40`. No `hikari.connection-timeout` override → 30 s
borrow timeout. **200 request threads : 5 JDBC connections : 40 pooled
server-side** — a textbook triangle collapse.

**Impact model.** At ≥6 concurrent queries, threads 6…200 queue inside
`HikariDataSource.getConnection()` for up to 30 s each. Under a DB blip the
failure mode is not 503s — it is **thread pool exhaustion** (every HTTP thread
parked on a borrow), so the readiness probe still passes (it doesn't use the
DB) for up to `20s × failureThreshold` while the pod serves nothing.
Pgbouncer then caps every *scaled* pod beyond 40/replicas shared server
connections: at the order HPA max (12 replicas) × even 10 connections = 120
> 40 — the platform is capped before the app is.

**Steps.**
1. Per-service `application.yml` — geometry + fail-fast (single canonical block):
```yaml
spring:
  task:
    scheduling:
      pool:
        size: ${SCHEDULER_POOL_SIZE:12}        # pairs with P-04
  datasource:
    hikari:
      maximum-pool-size: ${DB_POOL_SIZE:20}    # per-pod; re-pin pgbouncer alongside
      minimum-idle: 20
      connection-timeout: 3000                 # backpressure visible at 3 s, not 30 s
      keepalive-time: 300000
      max-lifetime: 900000                     # < pgbouncer server_lifetime (3600 s)
server:
  tomcat:
    threads: { max: 100 }                      # stop queuing hopeless work at the edge
    accept-count: 200
```
2. Capacity arithmetic (block CI on it, G-15): for each DB,
   `pods_max × DB_POOL_SIZE ≤ pgbouncer default_pool_size + headroom`. Order's
   HPA max 12 × 20 = 240 → raise `default_pool_size` on the pgbouncer
   ConfigMap (transaction mode: server-side sockets, so 280 is PG-viable with
   `max_connections ≈ 320`) **or** cap per-DB via
   `{dbname} = … pool_size=…` entries. Encode both limits in
   `k8s/configmap.yaml` overlays so staging/prod diverge intentionally.
3. Expose + alert: `hikaricp_connections_pending` gauge alert sustained > 8
   for 2 min; `hikaricp_connections_timeout_total` any-rate page.

**Verify.** staging k6 (`loadtest/` or `scripts/e2e-smoke.sh` ramp): p95 ≤
baseline−20%; a DB-outage game-day shows 503s in ~3 s instead of 30 s hangs.
**Effort:** 0.5 day + game-day; **rollback:** revert values (no code).

### P-02 — Hibernate batching unconfigured (S1, config-only)

**Evidence (verified).** `jdbc.batch_size` / `order_inserts` /
`order_updates` exist in **zero** service yamls (grep this cycle: no match).
Bulk write sites: scheduled-order dispatch (per-row save loop,
`ScheduledOrderProcessor.dispatchNextBatch:54-71` `// verified`), outbox
`claimBatch` (already `saveAll`), settlement batch writers, V-03-era
`users` join inserts per registration.

**Impact model.** N-row batch = N network round-trips (0.4–2 ms in VPC) →
1 grouped statement-set. On a 100-order tick the dispatch batch goes from
~100 round-trips to ~2; at pgbouncer transaction mode every extra round-trip
also holds a *server* connection longer — multiplicative with P-01.

**Steps.**
1. Shared block (append to each service's `jpa.properties`):
```yaml
spring:
  jpa:
    properties:
      hibernate:
        jdbc.batch_size: 50
        order_inserts: true
        order_updates: true
        jdbc.batch_versioned_data: true
        query_plan_cache_size: 200       # PG prepared-statement cache
```
2. **IDENTITY caveat** (document in the PR): with
   `GenerationType.IDENTITY` Hibernate *cannot* batch INSERTs (generated keys
   force one statement each). Batching therefore applies to UPDATEs and to
   tables using SEQUENCES. For hot bulk paths today: use
   `repository.saveAll(entities)` (single flush → update batching applies) and
   JPQL `@Modifying` statements for set-updates; for future bulk-heavy tables
   prefer `@GeneratedValue(strategy = SEQUENCE, generator = …)` with
   `allocationSize`/`increment_size` aligned to the batch (50).
3. Sweep loops that save per item inside `for` blocks (start with
   `ScheduledOrderProcessor` — collect, `saveAll`, then publish events).

**Verify.** with p6spy or a `StatementInspector`: 100-row dispatch batch ≤ 6
DB round-trips; k6 bulk path p50 −40%+. **Effort:** 1 h config + 1 sweep day.

### P-03 — Kafka producer hardening + single authoritative wiring (S1)

**Evidence (verified).** `KafkaPlatformConfig.kafkaProducerFactory` = bootstrap
+ serializers only (⇒ `acks=1`, no idempotence, linger 0, batch 16 KB). A
parallel `common/config/KafkaConfig` already sets `acks=all`,
`enable.idempotence=true`, `retries=3`, `max.in.flight=5` — **but** its
`@ConditionalOnExpression` (`enabled==true && type==kafka`) and
`KafkaPlatformConfig`'s `@ConditionalOnProperty` can both pass (a profile with
`type: kafka` + `enabled: true`), creating duplicate `producerFactory` /
`kafkaListenerContainerFactory` bean definitions whose coexistence depends on
registration order in Boot 3.2 (override disabled ⇒ boot failure risk).

**Impact.** `acks=1` + relay marking rows PUBLISHED after leader-ack = silent
loss window for every money event when a leader dies mid-replication (this is
the durability half of V-09/V-10/V-11). Missing `linger` + batch 16 KB = tiny
per-event sends under the outbox relay (throughput half). And the dual-wiring
landmine turns "just enable Kafka" into a failed rollout.

**Steps.**
1. **Consolidate to one wiring path**: delete `common/config/KafkaConfig`'s
   duplicate producer/template beans (or gate it exclusive:
   `KafkaPlatformConfig` gains `@ConditionalOnMissingBean(ExternalEventsProperties)`
   style guards per bean). One `ConcurrentKafkaListenerContainerFactory` — see
   Part III R-B for the DLQ/`DefaultErrorHandler` on it.
2. Complete the hardening on the surviving factory:
```java
config.put(ProducerConfig.ACKS_CONFIG, "all");
config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
config.put(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE);
config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 120_000);
config.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
config.put(ProducerConfig.LINGER_MS_CONFIG, 5);
config.put(ProducerConfig.BATCH_SIZE_CONFIG, 32_768);
config.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");
```
3. Startup self-check (G-2 family): at boot with `enabled=true`, publish a
   `<topic>.__probe__` and require ack < 2 s; log/monitor
   `kafka.probe.latency_ms`. A prod profile booting with
   `app.events.external.enabled=false` **fails fast** (extends §6.3 preflight)
   instead of letting `outbox_events` silently grow (feature #7's "accumulate
   forever").
4. Outbox→Kafka parity test (Redpanda Testcontainers): stop broker mid-publish
   ⇒ row stays PENDING (or PROCESSING→recoverStale→PENDING after P-05 fix),
   `acks=all` config asserted via `producer.properties` metric dump.

**Effort:** 1 day. **Rollback:** revert map entries.

### P-04 — 14 `@Scheduled` jobs share ONE scheduler thread (S1)

**Evidence (verified).** `grep "task:"` in every service main yml: **zero**
matches ⇒ `spring.task.scheduling.pool.size=1`. `@Scheduled` count repo-wide:
**14** — incl. the per-service outbox relay (`OutboxPollPublisher.poll`), DLQ
retry, saga pending-queue poller, SSE heartbeat, cleanup sweeps,
`ScheduledOrderProcessor.dispatchDueOrders` (which itself busy-loops with
`Thread.sleep(50)` between batches and can hold the tick for minutes on a big
backlog) and a 30 s stale-recover pass that is *not even scheduled* (feature #7).

**Impact model.** Spring's default scheduler is a single thread per service:
the dispatch busy-loop delays the outbox relay behind it; outbox relay delays
every event behind cron jobs — event latency becomes the *sum* of the
scheduler's tail, worst precisely under the load spikes where relay
timeliness matters. This is also why `recoverStale()` must be scheduled (P-05).

**Steps.**
1. The `spring.task.scheduling.pool.size: 12` block (P-01 step 1) + env
   overlay `${SCHEDULAR_POOL_SIZE:12}`.
2. Isolate latency-critical relays from cron noise — platform-lib:
```java
@Bean(destroyMethod = "shutdown")
TaskScheduler relayScheduler() {                      // events only
    ThreadPoolTaskScheduler s = new ThreadPoolTaskScheduler();
    s.setPoolSize(2); s.setThreadNamePrefix("relay-"); s.initialize();
    return s;
}
```
   Replace `@Scheduled` on `OutboxPollPublisher.poll()`/DLQ pollers with
   `relayScheduler.scheduleWithFixedDelay(...)` registered via an
   `ApplicationReadyEvent` listener gated on the existing
   `isEnabled()` property (preserves the off-by-default gate).
3. Bound the dispatch tick instead of busy-looping with sleeps: cap orders per
   tick (the `MAX_DISPATCH_PER_TICK` pattern, feature #3) so one job cannot
   occupy a relay thread for minutes anyway.
4. CI (G-11): every service yml defines the pool size; WARN-at-boot if 1 in
   non-dev profiles.

**Verify.** test: make `DlqRetryScheduler` sleep 5 s under load ⇒ outbox
end-to-end p99 stays < 1 s. **Effort:** half day + IT day.

### P-05 — Event latency floor (5 s poll) + serial publish holding the txn (S1/S2)

**Evidence (verified).** ADR-A1: poll interval now per-service
`app.outbox.poll-interval-ms` default **5000 ms** ⇒ +0…5 s E2E floor for all
events. `OutboxPollPublisher.drainBatch:52-91` runs **inside one
`@Transactional`** and publishes **serially**, each with a blocking
`future.get(sendTimeout=10s)` ⇒ worst case a dead broker pins one
connection+scheduler thread for `batchSize × 10 s` = **16.7 min at batch 100**
(cross-ref feature #7's two-phase fix). `RedisOutboxPendingQueue` does **not**
exist despite the older guide text — there is no wake path today at all.

**Steps (ordered; step 1 must land before steps 2–3 to avoid compounding).**
1. Two-phase relay from feature #7: claim-tx → publish outside tx (bounded
   pool) → state-tx, with `next_attempt_at` backoff and maxRetries →
   `DeadLetterEventService`.
2. Keep the poll as the correctness fallback, lower it to 1 s now that
   publishing no longer blocks the tx.
3. Add the wake channel (push path):
```java
// OutboxClient.enqueue(...) tail, same commit hook as G-1 guard:
stringRedis.convertAndSend("bhukkad-outbox-wake", event.getId().toString());
// relays: @Scheduled(1s poll) + a RedisMessageListener container BRPOP-style:
// on message → drainBatch() immediately (dedupe via claim, rows authoritative).
```
   Pub/sub is lossy by design here — it is a *latency optimization*; the 1 s
   poll guarantees delivery. (This matches ADR A10's "pub/sub for eviction/wake
   only".)
4. Metric `outbox_publish_lag_ms = now − created_at` sampled in the relay,
   alert p99 > 2000.

**Verify.** Redpanda IT: normal p50 publish→consume < 100 ms; Redis killed ⇒
delivery continues at 1 s cadence (never stalls); broker blip ⇒ rows stay
PENDING with `next_attempt_at`, no stranded PROCESSING after
`processingTimeout` (ties in the scheduled `recoverStale`). **Effort:** 2–3 days.

### P-06 — Unbounded `findAll()` + JVM-side filtering/sorting (S1)

**Evidence (verified this cycle, line-exact).**
- `search/SearchServiceImpl.java:97,104,128,144` — each request loads the
  *entire* `restaurant_search` **and** `menu_item_search` tables **twice**
  (unified search + per-type search) and filters in JVM.
- `supportticket/DisputeResolutionServiceImpl.java:59` — `findAll().stream()
  .sorted(...)` full history, growing forever.
- `referral/AffiliateServiceImpl.java:40` — `findAll()` all affiliate codes.
- `restaurant/AutocompleteService.java:27` — rebuilds the trie from
  `menuItemRepository.findAll()` **on subscribe/refresh**.
- admin-analytics `AdminQueryService.java:35/40` + growth controller
  `statRepository.findAll()`.

**Impact model.** Search is O(all rows) per request: at 50 RPS × 10 k menu
items ≈ 500k entity materializations/s → Young-GC pressure → p99 collapse
long before CPU/IO saturates; dispute list degrades linearly with age.

**Steps.**
1. **Parity harness first** (audit rule): capture 100 staging responses vs
   the old impl with explicit tie-break, diff = 0 before swapping.
2. SQL-side predicates + caps:
```java
@Query("SELECT r FROM RestaurantSearchEntity r WHERE lower(r.name) LIKE :p OR lower(r.cuisine) LIKE :p ORDER BY r.rating DESC NULLS LAST, r.id")
List<RestaurantSearchEntity> search(@Param("p") String pattern, Pageable page);
// service: pattern = lower(trim(q)) + '%'; PAGE_SIZE cap 50; reject > 200 with 400 (P-06 mount)
```
   Disputes: `findAllByOrderByCreatedAtDesc(PageRequest.of(clamp(page), min(size,200)))`;
   affiliates: projection + page. Autocomplete: build the trie once at startup
   and update incrementally from a `menuItemChanged` consumer /
   cache-invalidation event instead of `findAll()` on every subscribe.
3. Guardrail **G-6** (CI): `findAll()` under `src/main/**Service*` fails
   unless annotated `@AllowFullScan(reason)` — turns the class into build
   noise when reintroduced.
4. Hot-prefix result cache (45 s, `bhukkad:search:` keys, invalidation via the
   existing `CacheInvalidationService`) after P-08 indexes exist.

**Verify.** SQL-collector asserts bounded rows-per-endpoint; k6 p95 < 150 ms
@ 300 RPS on 10× seeded catalog. **Effort:** 3–4 days incl. parity work.

### P-07 — Notification I/O inline on the Kafka-listener thread (S1)

**Evidence.** `NotificationEventConsumer.onOrderEvent:31-49` (verified: parse
→ `dispatchService.dispatch` synchronously, catch-all swallow) → SMTP/Twilio
HTTP 0.2–3 s; consumer threads == partitions. Slow provider ⇒ group lag ⇒
if per-poll cost crosses `max.poll.interval.ms` the group **rebalances and
redelivers** — notification spam or starvation (feature #13/#14 adjacent,
audit-guide VI.1).

**Steps.**
1. Hand-off with bounded isolation inside the listener (Kafka semantics kept:
   ack only after the *enqueue*):**
```java
@Bean("dispatchExecutor")
ThreadPoolTaskExecutor dispatchPool() {
    var ex = new ThreadPoolTaskExecutor();
    ex.setCorePoolSize(4); ex.setMaxPoolSize(16);
    ex.setQueueCapacity(2000); ex.setRejectedHandler(new ThreadPoolExecutor.AbortPolicy());
    ex.setThreadNamePrefix("notify-"); ex.initialize(); return ex;
}
// consumer:
try { dispatchPool.execute(() -> dispatchService.dispatch(event)); }
catch (RejectedExecutionException re) { throw re; }   // → DefaultErrorHandler → DLT (R-B)
```
2. Dedup by `eventId` (`idempotency_records`, scope `KAFKA_CONSUME` — the enum
   already defined but unused, feature #7) so rebalance redelivery never
   double-sends.
3. Metrics `notification_dispatch_duration{channel}`,
   `notification_dispatch_queue_depth`, `notification_sent` /
   `notification_failed{err}`.
4. Only if burst queue > provider drain: promote to a PG
   `notification_requests` outbox-style poller (keep as an option — *do not
   start there*).

**Verify.** kafka IT: fake sender sleeping 900 ms × 60 records ⇒ partition lag
< 1 s, listener thread untouched; rejection ⇒ DLT non-lost; restart ⇒ no dup.
**Effort:** 1 day.

### P-08 — Expression indexes for the new SQL search (S2)

PG uses a btree for prefix `LIKE` only with `varchar_pattern_ops` (or C
locale) — default RHEL/Alpine `en_US.UTF-8` clusters ignore plain
`lower(name)` btrees for LIKE. Steps: 1) `EXPLAIN (ANALYZE, BUFFERS)` staging
queries (`... LIKE 'reser%'`); 2) where absent:
```sql
CREATE INDEX CONCURRENTLY idx_rsearch_name_prefix ON restaurant_search ((lower(name)) varchar_pattern_ops);
CREATE INDEX CONCURRENTLY idx_msearch_name_prefix ON menu_item_search  ((lower(name)) varchar_pattern_ops);
CREATE INDEX CONCURRENTLY idx_rsearch_cuisine_prefix ON restaurant_search ((lower(cuisine)) varchar_pattern_ops);
```
3) ship an **explain-guard** regression test (asserts `Index Scan` in the plan
of a fixture query) in the search module's test scope — prevents silent index
loss on future migration edits; 4) `pg_trgm` remains an explicit product
decision (typo tolerance), gated in #11. 5) **Geo (feature #12 caveat,
verified):** locations are `latitude/longitude double precision` columns
(`// verified: delivery/V1__baseline.sql:390-391`) and **PostGIS is not
installed** — a `ST_GeogFromText(...) geography` expression index would fail.
Use either a PostGIS adoption ADR (new extension + geometry columns) or a
no-new-dependency bounding-box prefilter on an indexed `point(lng, lat)`
expression / composite `(lat, lng)` btree (fine for city-scale serviceability;
PostGIS for true geodesic). Don't write the geospatial index until the #12
decision picks a lane. **Effort:** 2 h (prefix indexes) + decision.

### P-09 — JVM ↔ container contract standardization (S2)

**Evidence.** Runtime `JAVA_OPTS` in `services/order/Dockerfile:26` includes
`MaxRAMPercentage=75`, G1, `MaxGCPauseMillis=200`, `ExitOnOutOfMemoryError`;
`services/docker/Dockerfile.service:25` (compose/CI template) carries only
`MaxRAMPercentage` — no GC choice, OOM exit, or string dedup (drift class,
R-C). `k8s/order/deployment.yaml`: requests `250m/512Mi` limits
`1000m/1024Mi` ⇒ **Burstable** QoS with heap-sized-to-limit: under GC bursts
the pod is eviction-prone; JVM `ActiveProcessorCount` floats with node
parallelism vs the 250m guaranteed (GC thread oversubscription → throttled
GC, long pauses).

**Steps (one PR, manifests+templates).**
1. Memory `requests == limits` for JVM pods (heap predictable, QoS
   Guaranteed); CPU requests sized from the P-01/P-04 thread math
   (e.g. `750m`), **no CPU limit** (throttled GC is worse than noisy
   neighbors for latency SLOs).
2. Uniform `JAVA_OPTS`:
```
-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -XX:MaxGCPauseMillis=200
-XX:+ExitOnOutOfMemoryError -XX:+UseStringDeduplication
-Xlog:gc*:file=/tmp/logs/bhukkad/gc.log:time,uptime:filecount=5,filesize=20M
```
3. Add `JAVA_OPTS` to the consolidated canonical Dockerfile (R-C) so template
   and per-service paths converge.
4. CI (G-18): grep deploy overlays — every Deployment defines
   `MaxRAMPercentage` + memory limits equal → fail otherwise.
5. HPA: keep CPU-65; add memory-basis only where heap pressure is the real
   limiter (payment settlement, search after P-06).

**Verify.** OOM-kill drill (cgroup pressure): pod exits non-zero on OOM
immediately, restarts, alarm fires once; settlement burst scales before
eviction. **Effort:** 1–2 days.

### P-10 — Edge CPU budget: gateway blocking + double token verification (S1/S2, with code)

**Evidence.** `EdgeKillSwitchFilter:51-62` calls `subjectId(exchange)`
**eagerly** in the reactive chain's *assembly* point — `PlatformJwtValidator
→ jwksCache()` can do blocking HTTP + RSA parsing on a Netty event-loop thread;
combined with the `synchronized` fetch under cache miss
(`PlatformJwtValidator:115-123`) an IdP hiccup serializes the **entire
gateway**. Separately each request is verified at the gateway *and* again per
service (defense-in-depth un-decided — audit P-10).

**Steps.**
1. Offload the blocking call (V-13 fix, ship with P0):
```java
return Mono.fromCallable(() -> subjectId(ex))
        .subscribeOn(Schedulers.boundedElastic())      // every blocking piece off the loop
        .defaultIfEmpty(0L)
        .flatMap(sub -> flags.isRouteEnabled(fk, sub == 0L ? null : sub))
        .flatMap(on -> on ? chain.filter(ex) : disabled(exchange, fk, routeId));
```
2. Harden the shared validator substrate with the async-refresh cache from
   feature #5 (§5 step 1–2) — stale-serve + backoff + single-flight.
3. Decide verification (ADR, R-E-adj): keep two verifies for RS256/rotated
   keys (identity = source of truth for scope), drop service verify once Istio
   `RequestAuthentication` re-issues a short internal token — document in
   `docs/adr/`; do not half-implement either.
4. BlockHound in the `gateway` module build (throwing config, reactor test
   only) — CI guard G-5 prevents re-introduction.

**Verify.** blocking-fake-validator p99 test + `reactor.netty` busy-cap metric
proves event loops idle. **Effort:** 2 days.

---

# PART III — Architectural restructuring playbook (R-A … R-G)

### R-A — `platform-lib` module split (S2 → enables S0 velocity)

**Why now (verified).** One artifact carries datasource+replica routing, Kafka,
outbox, DLQ retry, saga, idempotency, cache (redis/local/stampede), chaos
aspect, gateway edge filter, service JWT, testkits. Consequences measured:
every boot pays a 14-job scheduler tax that includes machinery services never
call (P-04); classpath-conflict/Bean-name collisions (P-03 dual wiring); the
V-09 `NoOpEventPublisher` "default" was a platform default (RC-D class).

**Steps (no behavior change, two releases).**
```
platform-core   dto/error, tracing, web headers, exception mapper   ← everyone
platform-data   jpa, auditing, repositories helpers                 ← services with DB
platform-events outbox, DLQ, PlatformEventMessage, kafka wiring     ← event services
platform-data-replica DataSourceConfig/ReadReplica*                 ← replica services only
platform-saga   coordinator, pending poller, shedlock                ← order
platform-cache  redis/local/stampede, invalidation (+ CacheOpsController)
platform-security user+service JWT, secrets validation
platform-edge   gateway-only filters
platform-test   Testcontainers helpers, ArchUnitRules                ← test scope
```
1. Phase 1: create modules; move packages verbatim; publish
   `bhukkad-platform-aggregate` = thin pom depending on all, so **no service
   build breaks**; bump parent version.
2. Phase 2 (this cycle's cleanup already applied one instance): each service
   adopts only the modules it uses — validate with
   `mvn dependency:tree -Dincludes=com.bhukkad*` diffs per service; boot
   before/after timing report (expect −10–20 % boot on lean services).
3. `CommonArchTest` evolves to *inter-module* forbids (e.g. core ⇏ events) —
   prevents re-fusion. **Effort:** 4–6 days phased, gated on phase-1 green.

### R-B — Event schema & consumer wiring governance (S1)

**Decision + steps.** (a) **One** Kafka wiring path (P-03 step 1).
(b) The container factory gains `DefaultErrorHandler(DeadLetterPublishingRecoverer(kafkaTemplate, (rec,ex) → new TopicPartition(rec.topic()+".dlt", rec.partition())), ExponentialBackOffWithMaxRetries(3))`; DLQ topics follow the `<topic>.dlt` convention already declared in service yml (`order.events.v1.dlt` etc. `// verified` in `ExternalEventsProperties`/`application.yml`). (c) Consumers stop swallowing: remove
blanket `try/catch(Exception){log}` (verified pattern in
`NotificationEventConsumer:46-48`, `AdminCqrsEventConsumer:50-52`) → let the
error handler route poison to DLT; keep *deliberate* skips only for
unknown-event-type. (d) Consumer idempotency: `IdempotencyRecord` scope
`KAFKA_CONSUME` + `eventId` claim inside the projection tx (features #7/#1
pairing — replay-safety of projections is what makes the DLT re-drive cheap).
(e) Governance: `schemaVersion` mandatory on consume with reject+alert on
unknown; `docs/event-catalog.md` generated from `catalog/events.v1.yaml`
(ADR A2); `OrderCreated` payload drift (three shapes today) is frozen onto
one envelope per the ADR before any new consumer lands. **(f) CQRS upsert
fix (V-12):** replace read-modify-write in
`AdminCqrsEventConsumer.upsertStat:55-65` (`// verified` findById→+1→save)
with the single-statement `INSERT … ON CONFLICT (restaurant_id) DO UPDATE SET
order_count = order_count + 1, revenue = revenue + CAST(:total AS numeric)`
upsert + `(ADMIN_PROJECTION, eventId)` dedupe — 2-threads-same-restaurant
concurrency test as the fail-first harness. **Effort:** 4–5 days.

### R-C — Dockerfile consolidation: 14 + 2 → 1 (S2)

**Evidence (verified).** `find services -maxdepth 2 -name Dockerfile*` = **14**
plus `Dockerfile.template` + `Dockerfile.service` with *different* build
stages, JAVA_OPTS and user IDs (the `Dockerfile.service`/compose template path
also differs from the per-service files' `E2E` healthcheck/cache-mount layers —
the "pins 17 vs 26"-class drift).

**Steps.**
1. Canonicalize on the richest recipe: `ARG MODULE` multi-stage with
   `dependency:go-offline` cache mount + Spring layered jar copy, runtime
   `JAVA_OPTS` frozen in one place (P-09 list), non-root `1001`, healthcheck.
2. Registry/CI jobs invoke it per module (`build-arg MODULE=…`); after two
   digests match the old per-service image for payment/order (config-diff +
   startup smoke), `git rm services/*/Dockerfile`.
3. G-13 CI: fail if a second service Dockerfile or a `WebClient.builder()` /
   `new RestTemplate()` factory outside platform-lib reappears. Local compose
   may keep `Dockerfile.service` only if it literally *is* the canonical file
   (one file, `dockerfile:` referenced by both). **Effort:** 1–2 days.

### R-D — Single k8s source of truth (S1 → structural)

**Evidence (verified).** Both `k8s/order/{deployment,hpa,pdb,service}.yaml`
(kustomize base, what `kustomization.yaml` composes — incl. the previously
flagged `rabbitmq/deployment.yaml` entry, now tracked: it is listed but no
RabbitMQ is used by services → remove, it's dead blast-radius/CVE surface)
and `services/k8s/*-deployment.yaml` raw manifests (incl. per-service raws
with **different** env conventions — the `bhukkad-secrets` keys) exist in
parallel. Two truths = the audit guide R-01 drift class, and it already ate the
gateway route table once (§7 verification basis).

**Steps.**
1. Diff each raw manifest against its kustomize counterpart; port any
   unique setting (image pull policy, probes) into `k8s/<svc>/`.
2. `git rm -r services/k8s/*.yaml`; move only *docs*
   (`DECOMMISSION-CHECKLIST.md`) to `k8s/` or `docs/`.
3. CI G-12: `kustomize build k8s/overlays/prod` as the sole deploy source;
   PR guard rejects `services/k8s/**/*.yaml`.
4. Delete unused `k8s/rabbitmq/` reference; verify
   `docs/microservices-migration-guide.md` paths after.

**Effort:** 1 day (careful diffing is the work).
### R-E — Ownership & boundaries (S1, decision-heavy/code-light)

**Disputes (S1 / latent S0 money).** Verified via gateway route table:
`/api/v1/admin/disputes/**` → **order** while the ownership matrix says
supportticket, payment hosts its own `DisputeService`, and supportticket can
credit *other domains'* wallets through them. One SOR each, in `docs/adr/`:
- disputes lifecycle: `supportticket` (state machine + `dispute_resolved`
  outbox event only writer); `payment` **consumes** for the ledger credit
  (never two crediting paths for one case — wallet-atomicity V-01/V-02 lesson);
- orders write-path in order: read-model via `order.events.v1` consumer (R-Bf
  idempotent projection), never a repository with save();
- loyalty: single durable ledger (growth) — verified: `loyalty_points_ledger`
  **exists** in `growth/V1__baseline.sql:81` but `LoyaltyServiceImpl.creditPoints:45-57`
  writes only Redis `increment("loyalty:points:<id>")` — points are volatile
  (flush = money-adjacent erase, no audit trail); make the Redis key a cache
  over append-only ledger rows (feature #4). Note two **parallel referral-code
  writers** (`// verified: identity/referral/ReferralService.java:134` **and**
  `referral/serviceImpl/ReferralServiceImpl.java:127`, each `"BK" + id +
  System.currentTimeMillis() % 10000` — collision-prone modulo + ownership
  violation): one code generator (retry-on-duplicate or uuid-tail), one owner.

Steps: write ADRs (half day each) → gateway route flip (staging, edge kill
flag already exists: `edge-flag` metadata `// verified: GatewayConfig`) →
restrict DB roles (`GRANT SELECT ONLY` for borrowers; owner-only for writers —
G-14 CI/dba check) → drop foreign repositories/entities (ArchUnit red first,
code after). **Effort:** 2 days ADR+roles, 3–5 days code.

### R-F — Platform contracts that must become *runtime-enforced* (S1)

Three classes of "documented-but-inert" already bit this repo (breaker R-class
V-16, limiter V-18, outbox NoOp V-09). Encode the contracts so they cannot
drift back:
```java
// OutboxClient.enqueue (platform-lib):
if (!TransactionSynchronizationManager.isActualTransactionActive())
    throw new IllegalStateException("G-1 violation: enqueue outside a transaction");
// RateLimitStartupGuard (prod profile): require >=1 @RateLimited usage + custom
// resolver bean, else fail boot; in prod, in-memory limiter impl must refuse >1 instance.
// CircuitBreaker self-check: after registration, a unit game-test asserts OPEN
// (StepVerifier), and a startup log line prints the mounted breakers list.
```
Plus the audit-guide G-list (G-2…G-10) as CI stages. **Effort:** 2–3 days, 1
day per class.

### R-G — Realtime: Redis fan-out bridge + SSE registry hardening (S0 feature #13, mechanics here)

`OrderSseStreamServiceImpl:36-38` keeps **three pod-local
`ConcurrentHashMap<Long, CopyOnWriteArrayList<SseEmitter>>`** — a broadcast
landed on pod A cannot reach an SSE client attached to pod B (the kitchen is
now a *separate deployment* behind scaling, so this is not theoretical). Plus
`O(N)` disconnect scan (`remove:192-196` walks `entrySet()` per emitter).
Steps: (1) Redis pub/sub bridge: `onMessage` → local broadcast (same
`broadcast()` body), publish side at the consumer; (2) emitter-index map for
O(1) removal: `Map<SseEmitter, StreamKey>`; (3) per-channel + global budget via
the existing `LiveProperties` (`maxTotalEmitters`/`maxEmittersPerStream`
verified wired) surfaced as
`sse_connections{channel}`, `sse_capacity_rejected_total`; (4) replay store is
already pod-local — put it on Redis too or pin streams by gateway hash
(A10: pub/sub for delivery only, state in Redis/PG). **Effort:** 3–4 days.

---

# PART IV — Code coverage & SonarQube quality-gate program

**Measured baseline (jacoco instruction coverage, full-reactor run
2026-09-06, `mvn test jacoco:report`, Testcontainers suites included):**

| Module | Coverage | Module | Coverage |
|---|---|---|---|
| gateway | **84.9 %** | order | 45.7 % |
| delivery | **84.7 %** | realtime | 43.1 % |
| admin-analytics | 71.4 % | restaurant | 41.0 % |
| identity | 70.2 % | notification | 32.7 % |
| payment | 53.4 % | survey, referral, search, supportticket, growth, personalization | **0.0 %** |
| platform-lib | 53.6 % | | |

**The 100 % requirement, stated honestly.** Whole-repository line coverage of
100 % is not a credible engineering target for a running platform (generated
equals/hashCode, defensive catch-blocks, DI scaffolding). `sonar-project.properties`
already encodes the *right* target — **Coverage on New Code = 100 %** against
`main` (`sonar.newCode.referenceBranch`), with sensible
`sonar.coverage.exclusions` for entity/dto/mapper/config. That gate is
enforceable from day 1 with `sonar.qualitygate.wait=true` + PR pipeline
(required check) — every increment written from Parts I–III provably covered.

**Legacy ramp (where Sonar "Overall Coverage" is reported):**

| Step | Gate (overall, per module) | Unlocks | Effort |
|---|---|---|---|
| 1 (now) | publish reports; no min | visibility (done — table above) | 0 |
| 2 (P0 fixes ship) | ≥ 60 % for gateway/delivery/admin-analytics/identity; ≥ 45 % rest | proves P0 work didn't erode | 2 d |
| 3 (P1) | ≥ 65 % all; 0 % modules must exit zero (below) + Part II items carry tests | backbone items | 5–7 d |
| 4 (P3–P5) | ≥ 80 %; branch coverage of money paths (wallet, idempotency, outbox relay) ≥ 70 % | restructuring confidence | ongoing |

**0 % modules — the exits, ordered by cheapness.** (a) gateway of the reactor
already runs their *smoke* context tests? — no: six services have classes but
no unit scope at all (growth: `LoyaltyService`/`CampaignService` — Part I #4
delivers their test-first harnesses anyway; search/`SearchServiceImpl` test
comes with P-06; notification test lands with P-07's consumer refactor; survey
/ referral / supportticket / personalization: one controller-slice
`@WebMvcTest`+one service-unit per feature class — each is ≤ 1 day, all are
pre-conditions of their respective items).

**Config to flip the gate on (already present, needs the CI step):**
```yaml
# .github/workflows/services.yml additions:
mvn -f services/pom.xml -q verify jacoco:report site
# then: sonar-scanner -Dsonar.login=$SONAR_TOKEN   (quality gate holds PRs)
```
Note `scripts/` currently ships a parallel python API suite
(1642 + 1668 lines, now incl. a ~20-probe edge battery with per-probe unit
tests) — it complements rather than replaces unit scope; wire it as nightly
`test-all-apis --base-url staging` + `python3 -m pytest scripts/` into CI so
the HTTP-contract tests run where the JVM unit tests can't reach.

### Known repo-hygiene debts to clear alongside (this cycle's discoveries)

- **Root-owned build debris**: `services/platform-lib/target-stale-platform-lib`,
  `services/supportticket/target-stale-supportticket`,
  `services/supportticket/.../wallet-old.bak`, `.gitignore` itself — created
  by a container running as root; delete/chown once with
  `sudo rm -rf … && sudo chown $(whoami) .gitignore`, then land the
  `.gitignore` append lines (patterns already drafted: `target-stale-*/`,
  `*.bak/`, `.trash-rootowned/`, `dump.rdb`).
- Gateway `application.yml` route URIs default to `bhukkad-*.bhukkad.svc`
  DNS names — service discovery naming is *deployed* identity (fine), but the
  two k8s trees use divergent key spellings (`bhukkad-secrets-*` key names in
  `services/k8s/*/deployment.yaml`) — R-D removes the divergence, it should
  *not* rename the namespace (blast radius: every secret ref).

---

## Cross-cutting implementation ordering

The recommendations are **not independent** — a dependency graph (audit-guide
§10) should gate sequencing:

1. **W1 (money first).** #1 Idempotent payments → #2 Wallet integrity → #4
   Loyalty ledger. Close every 🔴 finding before any scaling work.
2. **W2-a/W2-b (backbone).** #7 Event backbone (outbox two-phase + DLQ) must
   precede #3 (real order saga) and #11/#13 (search/realtime consumers).
   #5 Auth hardening unblocks #6, #17.
3. **W2-c/W2-d (edge).** #9 Rate limiting + #5/#6 auth + #17 secrets → #8
   resilience + #14 observability are visible.
4. **W3–W6 (scale).** #10 Caching → #11 Search → #12 Delivery → #13 Realtime.
5. **W7–W8 (ops).** #15 Backup → #16 CI/CD → #18 Structure.

Each feature ships with a regression test that fails on current code and passes
after (audit-guide rule: "regression test before fix").

**Mapping Parts II–IV onto the waves above:**

| Wave | Feature items | Playbook items |
|---|---|---|
| W1 | #1 #2 #4 | P-01 P-02 (config geometry is *free* at W1 sizes; fixes the pool cliff the wallet fix is discovered through under load) |
| W2-a/b | #7 #8 #13 | P-03 P-04 P-05, R-B (one Kafka path before the two-phase relay), R-G |
| W2-c/d | #5 #6 #9 #17 | P-10 (edge offload + BlockHound) |
| W3 | #10 #11 #12 | P-06 P-08 (P-08 indexes gate P-06's SQL switch) |
| W4+ | #3 #14 | P-07 (async dispatch) |
| W5–W6 | — | P-09 JVM contract |
| W7–W8 | #15 #16 #18 | R-A…R-F restructure box, Part IV ramp step-2/3 |

**Status at publication (2026-09-06).** All S0/S1 code fixes in Parts I–III
remain **open** against the working tree; verified this cycle: wallet
read-modify-write (`WalletService.credit/debit`), COD endpoint validation,
inert circuit breaker, non-atomic limiter, `synchronized` JWKS fetch, blocking
edge call, catch-all consumers, `PROCESSING`-stranding poller, SSE pod-local
maps + O(N) removal, both k8s trees, dual referral-code writers, loyalty
Redis-only points. Completed as side-effects of the test/identity cycle: the
`OVERRIDING SYSTEM VALUE` migration bug (**would have bricked every identity
boot**), gateway/roadmap test drift, dead seed reference, controller
mis-placement, and the full config identity rename (`app`/`core`/`app_pass`).

## Appendices

- **A.** Existing, verified finding register → see `docs/PRODUCTION-READINESS-AUDIT-GUIDE.md`
  §3 (V-01…V-22, P-01…P-10, R-01…R-08) with per-finding remediation SQL and
  rollout runbooks.
- **B.** Event & API catalogs → `docs/event-catalog.md`, `docs/api/auth.md`,
  `docs/api/orders.md`, `docs/api/payments.md`.
- **C.** Architecture decision records → `docs/adr/`.
- **D.** Ownership matrix (which service owns which domain table) → required
  before starting #3/#4/#8 (the dispute/gift-card split is still unresolved,
  audit-guide R-04).

*This document is living infrastructure. New findings should be filed as
numbered `AUDIT-` issues and folded in on the next audit cycle.