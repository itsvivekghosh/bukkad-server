# Bhukkad Backend — Production Readiness & Mobile Scalability Analysis

Date: 2026-08-28 · Scope: `backend-server` (706 Java sources, 330 tests, Flyway migrations, 4 config profiles)

Verdict: the codebase is architecturally ambitious and mostly sound (observability stack, outbox
pattern, resilience4j, SSE capacity management, idempotent order creation). The gaps below are
concentrated in **money/coupon integrity**, **a few dangerous permitAll paths**, **scheduler
concurrency across replicas**, and **mobile payload efficiency**.

---

## 1. Implementation Gaps

### 1.1 Blocking for production

| # | Gap | Evidence | Fix |
|---|-----|----------|-----|
| G1 | **Simulated payment gateway is the default in every profile.** `app.payment.provider=simulated`, `razorpay.enabled=false`; prod yml never overrides. If env vars aren't set in prod, payments silently "succeed" without a gateway. | `application.yml:377-383`, `SimulatedPaymentGateway.java:40-42` | Make provider env-driven with **fail-fast** when production config is missing; never ship `simulated` as a default. |
| G2 | **Global rate-limit bucket for registration.** The aspect routes `auth-register` to the default identifier → one shared `"user:anonymous"` bucket for `/auth/register`, `/register/phone`, `/resend`. Legit users 429 platform-wide; attackers effectively unthrottled. | `ratelimit/RateLimitAspect.buildIdentifier` | Key registration buckets on submitted phone/email + client IP. |
| G3 | **No auth entry point.** Filter-chain auth failures bypass `GlobalExceptionHandler` → non-JSON 403 instead of the standard `ApiResponse` 401 shape mobile expects. | `config/SecurityConfig.java` | Add `AuthenticationEntryPoint` + `AccessDeniedHandler` emitting the envelope; also map `405`, `ConstraintViolationException`, `MaxUploadSizeExceededException`, `MissingPathVariableException` (currently fall through to generic 500 + false critical alerts). |
| G4 | **Schedulers run on every replica without ShedLock.** Settlement cron flips PENDING→SETTLED/PAID via read-modify-write without atomic `WHERE status='PENDING'`; materialized-view refresh fires 7 aggregate queries per restaurant inside one long tx; inventory alerts same. Only the outbox uses `@SchedulerLock`. | `SettlementAutomationScheduler.java:36-38`, `RestaurantSettlementService.java:89-101`, `MaterializedViewRefreshService.java:52-68` | `@SchedulerLock` everywhere; batch the matview refresh into grouped `INSERT…SELECT`; atomic status flips. |
| G5 | **Order status machine is unguarded.** Restaurant role can set *any* status: `DELIVERED` skips loyalty/settlement/invoice; `CANCELLED` skips refunds/stock restore. `markOrderDelivered` has no already-delivered guard → sequential retries re-award loyalty. | `OrderStatusService.java:159-163, 227-273` | Allowed-transition matrix + terminal-state guards (idempotent side effects). |
| G6 | **Idempotency missing on money-adjacent mutations.** Order create + batch orders are enforced (good), but `/wallet/top-up` makes the key optional, `/wallet/add-money`, `/cart/add`, `/membership/subscribe` have none. Network retries can double-charge / double-add. Top-up webhook path has a double-credit race (unlocked read + in-memory status check). | `CustomerController.java:127`, `PaymentServiceImpl.java:286-305`, `WalletTopUpService.java:33-99` | Require `Idempotency-Key` on all mutating money flows; add `PESSIMISTIC_WRITE` on the payment row in webhook completion. |
| G7 | **Cancellation swallows refund failures.** `cancelScheduledOrder` catches refund exceptions and nothing re-queues → silent money leak (dunning covers captures only). | `OrderStatusService.java:110-117` | Enqueue failed refunds for retry/alert. |
| G8 | **I18n dead code.** `MessageSource` + 6 locale files configured, but `I18nService` has zero call sites — every user-facing string is hard-coded English despite multi-language properties existing. | `i18n/I18nService.java` | Route `ApiResponse`/validation messages through it or delete the plumbing. |
| G9 | **Missing components.** `monitoring/` package empty (no Kafka consumer lag/DLQ gauges); OTP verify lacks per-key attempt counters (only coarse limits, non-constant-time compare); JWT secret rotation is per-instance random (breaks multi-pod validation). | `PhoneVerificationServiceImpl.java:84-106`, `JwtSecretRotationService.java:27-58` | Add OTP attempt lockout; shared keystore for rotation; consumer-lag metrics. |
| G10 | **CORS pinned to localhost.** Prod origins absent; WebSocket handshake uses `setAllowedOriginPatterns("*")` and accepts `?token=` in URL (leaks into proxy logs). | `WebConfig.java:13`, `WebSocketConfig.java:44-51` | Env-driven origin lists; short-lived WS tickets instead of URL tokens. |

### 1.2 Secondary gaps (medium/low)

- Request bodies logged at INFO on every endpoint (large PII surface); `populateUserContext()` runs a `UserRepository` lookup **twice per request** — cache in request attributes or derive from JWT.
- PII masking covers only emails/bare 10-digit numbers; misses +91 phones, UPI IDs; also mis-masks order IDs.
- `server.error.include-message: always` in base config — restrict `include-stacktrace`/`include-message` in prod.
- Validation gaps: missing `@Valid` (AdminScaleController, CustomerGrowthController, CustomerController:143/210, RestaurantController:234, AdminController:160); `DeliveryController:68` binds the **entity directly**, and phone-number updates skip the uniqueness check (collides with phone-based OTP login).
- API-key `scopes` stored but never enforced — every key gets full PARTNER.
- Feature-flag keys share one Redis namespace across environments → staging toggles can hit prod.
- `IdempotencyService.releaseLock` deletes without ownership check (should be Lua compare-and-delete).
- No stable machine-readable `errorCode` in error payloads — mobile retry logic string-matches messages.
- GraphQL introspection + GraphiQL public (fine in dev, disable in prod).

### 1.3 Already production-grade (preserve)

- `GlobalExceptionHandler` maps 15+ exception families, no stack leakage, includes traceId, fires alerts.
- Outbox: `FOR UPDATE SKIP LOCKED` claiming, DLQ, stale-PROCESSING recovery, ShedLock.
- Stock decrement: atomic conditional UPDATE, Redis pre-check fails open.
- Order creation: short tx, gateway capture post-commit, idempotency keys, compensation path.
- Resilience4j circuit breakers on Razorpay/Twilio/FCM/OSRM; webhook HMAC + eventId dedup.
- Feature flags with %rollout + pub/sub invalidation; SLO burn-rate monitoring + synthetic health checks.
- `ddl-auto: none`, `open-in-view: false`, Flyway migrations present across v1..v52+.

---

## 2. Performance & Scalability Optimizations

### 2.1 Latency & payload efficiency (mobile-critical)

| Priority | Issue | Recommendation |
|----------|-------|----------------|
| **HIGH** | `GET /restaurants/public` returns **all** active restaurants as the full 23-field DTO (nested address, gallery, onboarding status) with no pagination — multi-hundred-KB launch payload. The mobile Home screen hits this on every load. | Ship a slim `RestaurantCardResponse` (id, name, image, rating, cuisines[], eta, minOrder, freeDelivery, offer) + **cursor pagination** (limit 20). Biggest single bandwidth win. |
| **HIGH** | Public search/filter LIKE fallbacks unbounded; `GET /menu/items/restaurant/{id}` unbounded with nested customizations/ingredients/allergens; menu needs 2 round-trips. | Cap LIKE queries (LIMIT 50); combined categories+items endpoint or per-category lazy load; move customization details to item-detail only. |
| **HIGH** | No HTTP caching on `/home/feed` & `/mobile/feed` (handed out on every app launch; 60s TTL server-side). The two endpoints are byte-identical duplicates. | Consolidate to one; emit `Cache-Control: public, max-age=60, stale-while-revalidate` + version ETag; honor `If-None-Match` **before** building body (current ETag = 32-bit `String.hashCode()` computed *after* serialization, and `If-Modified-Since` is accepted but never evaluated). |
| MED | 4 `OrderRepository.findBy*WithDetails` queries return unbounded `List` with 5–8 JOIN FETCHes. Unbounded fetches also in DataExport/Customer/Delivery services. | Migrate callers to the existing `OrderSummaryResponse`/cursor variants. |
| MED | SSE (`OrderSseStreamService`): blocking `SseEmitter.send` (slow client stalls the stream loop), 60s emitter timeout forces reconnects, snapshot event ships full `OrderResponse`. | Non-blocking send path, longer timeout w/ existing 25s heartbeat, slim snapshot DTO. Last-Event-ID replay store is good — keep it. |
| MED | Rider dispatch loads **all** available agents and calls OSRM twice per agent *inside* the markOrderReady transaction (2N HTTP calls). | Precomputed/cached routes; move dispatch outside the tx; lock on rider assignment. |
| LOW | `LocalDateTime` fields force clients to assume UTC — countdown UIs break on TZ drift. | Epoch-millis or ISO-offset for ETAs/expiries. |
| LOW | GraphQL `homeFeed` query already exists and would solve field over-fetching — but the app doesn't use it. | Either adopt for feeds or prune REST DTOs. |

Already fine: gzip on (≥1KB, brotli via nginx per config notes), HTTP/2 in prod, cursor variants for orders/wallet, batch caps (100 ids), recommendations capped at 10, feed L1 Caffeine + L2 Redis stampede protection.

### 2.2 Concurrency & database performance

- **Coupons unsound.** `recordCouponUsage` is read-modify-write with no lock/atomic update; limit checks are check-then-act; no unique index on `coupon_usages(coupon_id, customer_id)`. Concurrent checkouts exceed limits and lose increments. → atomic guarded UPDATE (`used_count=used_count+1 WHERE used_count<usage_limit`) + unique usage index.
- **Wallet stale read after lock.** `findByIdWithLock` relies on Hibernate's identity map after the customer was already loaded earlier in the same tx → `FOR UPDATE` returns the cached row → overspend window under concurrent orders. → `entityManager.refresh()` post-lock, or do balance math in the UPDATE.
- **Long transactions spanning external I/O.** `refundPayment` holds a pessimistic lock + tx across gateway refund + synchronous notify; `initiateTopUp` calls gateway create inside tx. → external calls post-commit (order placement already does this correctly — replicate the pattern).
- **Loyalty points** read-modify-write on a `Customer` without `@Version` (multiple call sites). → version field or atomic UPDATE.
- **Missing index:** `payments.gateway_order_id` drives every webhook lookup but is unindexed (migrations). → add it; table will grow forever.
- **Materialized view refresh** = per-replica 5-min N+1 storm (7 queries × all restaurants, one long tx). → grouped aggregate upsert + ShedLock.
- Connection pool is well-tuned for prod (Hikari 50, max-lifetime < MySQL wait_timeout, leak detection, read-replica routing) — but base `application.yml` ships `show-sql: true` + Hibernate DEBUG; confirm override in every env. `allow-circular-references: true` (PaymentServiceImpl↔DunningService) should be refactored out.
- **Verdicts per hotspot:** stock ✅ atomic · orders ✅ optimistic `@Version` (needs transition guards) · wallet ⚠️ correct intent, stale-read bug · coupons ❌ · settlements ❌.

---

## 3. Security Recommendations

### 3.1 Critical — fix before any real traffic

1. **Unauthenticated analytics export.** `/api/v1/analytics/export/**` is `permitAll`; the service concatenates `fromDate`/`toDate` raw into SQL and streams customer/rider/restaurant PII + gateway txn IDs. → require ADMIN, bind parameters, strip PII columns.
2. **Forged payment webhooks accepted.** `SimulatedPaymentGateway.verifyWebhookSignature` returns `true` unconditionally **and** is the active gateway (razorpay disabled everywhere, `/payments/webhooks/**` permitAll). Anyone can POST `payment.captured` and mark orders paid. → real HMAC verification mandatory; refuse webhook processing when verification is unavailable.
3. **Privileged-role self-registration.** Public `/auth/register` accepts client-supplied `role` and creates RESTAURANT_OWNER (verified+active) or DELIVERY_AGENT instantly. → public registration restricted to CUSTOMER; other roles behind admin approval.
4. **JWT secret fallback committed to VCS** (64-byte HS512 default in `application.yml:221`); access-token TTL 24h; rotation off. → remove default/fail fast; **15-min access + refresh rotation**; shared-secret distribution for multi-pod.
5. **Secrets in logs.** `RequestLoggingFilter` logs all *query params* unsanitized — `oldPassword/newPassword`, reset `token`, `mfaToken+code` all travel as query params and land in prod INFO logs; OTP plaintext logged when Redis is down. → move secrets to bodies, sanitize params, drop OTP log.
6. **WAF bypass.** Values >2000 chars are treated as *safe* (skip, not block); JSON bodies never scanned. → block oversized values; scan bodies.

### 3.2 Strong recommendations (auth/authz hardening)

- **Token hygiene after credential events:** password reset/change revokes refresh tokens only; access tokens live ≤24h. → add a `tokenVersion` claim (bump on password change) or jti blacklist covering access tokens.
- **Token-type confusion:** refresh/MFA tokens are accepted where access tokens are expected (`type` claim never checked in `changePassword`/`verifyEmail`/WS handshake). → enforce `tokenType`.
- **Client-IP spoofing:** raw `X-Forwarded-For` trusted for rate-limit keys, fraud thresholds, IP logs. → honor only rightmost entry from trusted proxy list.
- **OTP hardening:** per-key attempt counter + lockout (delivery-proof OTPs show this pattern exists — generalize it), constant-time hash compare.
- **Actuator surface:** `/actuator/metrics` falls to `anyRequest().authenticated()` — any customer JWT reads metrics; public `/api/v1/health/**` leaks env/instance detail. → ADMIN/ops-only, trim health payload.
- **Request binding:** entity binding on `DeliveryController` allows phone-number change without uniqueness validation — collides with phone-based login keys. → DTOs + `@Valid` + uniqueness checks.
- **API keys:** enforce stored scopes; remove pepper default from config.
- **OAuth2 flow:** CSRF disabled is fine for stateless Bearer, but keep redirect URIs strictly allow-listed.

### 3.3 Lower priority

- `/auth/forgot-password` user enumeration ("User not found") → generic response.
- Non-constant-time comparisons: `PrometheusAuthFilter`, OTP hash, webhook eventId lookup.
- `spring.jwt.secret` dead namespace in dev yml (real key is `app.jwt.secret`) — remove to prevent confusion.
- Log injection: client-supplied `X-Trace-Id` accepted unsanitized → hex/alnum whitelist.

---

## Suggested rollout order

1. **Week 1 (security-blocking):** G-3.1 items 1–6, CORS/actuator lock-down, registration-role restriction.
2. **Week 2 (integrity):** coupon atomicity + index, wallet refresh-after-lock, refund/top-up tx boundaries, scheduler ShedLock batch, `payments.gateway_order_id` index, idempotency on top-up/cart/membership, order transition matrix.
3. **Week 3 (mobile perf):** RestaurantCardResponse + cursor pagination, menu payload trim, feed Cache-Control/ETag + endpoint consolidation, LIKE caps.
4. **Week 4 (operability):** rate-limit identifier fix, auth entry point JSON, error codes, OTP lockout, masking rules, i18n wiring (or removal), monitoring-package metrics.
