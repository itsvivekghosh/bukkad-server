# Trust & Compliance (V17)

GST invoice PDFs, fraud enforcement, review moderation, and delivery proof of handover — the four subsystems a customer, a tax auditor, or a dispute agent needs before this platform can run in India.

All of V17 ships behind configuration, and two of the four features are deliberately **not** enforcing by default. Read [Rollout order](#rollout-order) before enabling anything in production.

## GST invoice PDF

An invoice row is created the first time an order reaches `DELIVERED`. It is a tax document, so it is treated as immutable once issued: `OrderInvoiceService` writes the tax figures once and afterwards only updates the PDF object key. Marking delivery twice returns the existing invoice rather than issuing a second one — `order_invoices.order_id` carries a unique constraint, so a duplicate would fail at the database anyway.

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | `/api/v1/orders/{orderId}/invoice` | Customer/Owner/Admin | Invoice as JSON |
| GET | `/api/v1/orders/{orderId}/invoice/pdf` | Customer/Owner/Admin | Rendered PDF (`application/pdf`) |

The PDF is rendered with OpenPDF, stored under `order_invoices.pdf_storage_key`, and emailed to the customer as an attachment. The recipient address is snapshotted into `order_invoices.email_recipient` at send time, because the customer may change their email later and the audit trail must show where the document actually went.

There is no delete endpoint. Cancelling an issued invoice is a credit note, not a row removal — that is deliberate and remains backlog.

## Fraud enforcement

`fraud_events` is an append-only evidence trail. `FraudDetectionService` writes a row **before** the guarded operation proceeds, so rejected attempts are recorded too. A block is a velocity decision: count the rows matching this `event_type` plus one actor identifier (IP or device fingerprint) inside the sliding window, and if the count reaches the threshold, raise `FraudBlockedException`.

Guarded operations: **register**, **login**, **order create**.

Counting is scoped per event type, so heavy login traffic can never trip the registration threshold.

| Setting | Default | Description |
|---------|---------|-------------|
| `app.fraud.enabled` | `true` | Master switch; when off, no rows are written and no checks run |
| `app.fraud.blocking-enabled` | `true` | When off, rows are still written but nothing is rejected — use this to pick thresholds from real data |
| `app.fraud.window-minutes` | `60` | Sliding window applied to every threshold |
| `app.fraud.retry-after-seconds` | `300` | Value sent in the `Retry-After` header |
| `app.fraud.default-threshold` | `20` | Applied to any event type with no explicit entry |

Per-type defaults (a `0` disables that dimension for that type):

| Event type | Per IP | Per device |
|------------|--------|------------|
| `AUTH_REGISTER` | 10 | 5 |
| `AUTH_LOGIN` | 40 | 25 | 
| `ORDER_CREATE` | 25 | 15 |

Registration is tight because a genuine user registers once; login is loose because a whole apartment block or office can share one NAT'd IP. `FraudProperties.thresholdFor(...)` falls back to the built-in default and then to `default-threshold`, so adding a new event type can never NPE.

### Response contract

A blocked request returns **HTTP 429** with a `Retry-After` header. Clients must treat 429 as "back off and retry later", not as a validation error. Note that `RateLimitExceededException` also maps to 429 — the two are distinguished by the response body, not the status.

Only `event_type` and `created_at` are guaranteed non-null on a fraud row. There is no customer before authentication, and either network identifier may be missing; a check skips an absent identifier rather than matching on `null`. The device fingerprint is spoofable by design — it is a correlation hint, never an authentication factor.

## Review moderation

`reviews.moderation_status` is `PENDING`, `APPROVED`, or `REJECTED`. Both `PENDING` and `REJECTED` are hidden from public reads **and** excluded from aggregate restaurant ratings, so a review cannot move the star average before a human has seen it.

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | `/api/v1/admin/reviews/moderation` | Admin | Moderation queue; optional `?status=` filter |
| PUT | `/api/v1/admin/reviews/{reviewId}/moderate?status=` | Admin | Approve or reject (`status` required) |
| POST | `/api/v1/restaurants/owner/reviews/{reviewId}/response` | Owner | Public reply, body `{"response":"..."}` |
| GET | `/api/v1/reviews/restaurant/{restaurantId}` | Public | Approved reviews only |

An owner replying to a review that belongs to a different restaurant gets a **400**, not a 403 — the check is a business rule inside the service, not a security matcher.

## Delivery proof of handover

Before an order is marked `DELIVERED`, the rider captures evidence: a 6-digit OTP the customer reads out, optionally plus a photo. This backs the "my order never arrived" dispute flow.

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/api/v1/orders/delivery/{orderId}/proof/otp` | Agent | Issue/resend OTP (sent to customer by SMS) |
| POST | `/api/v1/orders/delivery/{orderId}/proof/verify` | Agent | Verify OTP, optionally attach photo/recipient/GPS |
| POST | `/api/v1/orders/delivery/{orderId}/proof/photo-url` | Agent | Presigned upload URL for the handover photo |
| GET | `/api/v1/orders/delivery/{orderId}/proof` | Agent | Current proof state |

**The OTP plaintext is never returned by any endpoint.** Only a BCrypt hash is persisted in `order_delivery_proofs.otp_code_hash`; the plaintext leaves the system only over SMS. `DeliveryProofResponse` has no OTP field. This is why `docker/scripts/test-all-apis.sh` asserts a **400** on `proof/verify` — a script has no way to learn a valid code, and that is the point.

| Setting | Default | Description |
|---------|---------|-------------|
| `app.delivery.proof.enabled` | `true` | Master switch; when off the proof endpoints reject and no rows are written |
| `app.delivery.proof.enforced` | **`false`** | When on, a verified proof becomes mandatory for the `DELIVERED` transition |
| `app.delivery.proof.otp-expiry-minutes` | `10` | Long enough to survive a lift with no signal |
| `app.delivery.proof.max-otp-attempts` | `5` | Exhausting attempts marks the proof `FAILED`; only a fresh code restores the allowance |
| `app.delivery.proof.otp-resend-cooldown-seconds` | `60` | Stops a resend tap from billing us for an SMS burst |

Verification fails **closed** — but only when `enforced` is on. With the shipped default, `assertProofSatisfied` returns early and delivery is never blocked. Enforcement is off at ship time because turning it on would strand every order already out for delivery with a rider on an older build.

Proof lives in its own table rather than on `orders` so the hot orders row stays narrow and the OTP hash can be dropped independently for data retention.

## ETA accuracy metric

`GET /api/v1/admin/operations-dashboard` (Admin) gained an `etaAccuracy` block. Snapshot volume and average quoted ETA come from `order_eta_snapshots`; accuracy is derived from `orders.estimated_delivery_at` versus `orders.delivered_at`, because snapshots hold no actual delivery timestamp.

| Field | Meaning |
|-------|---------|
| `snapshotsLast24h` | Promises made in the window |
| `avgEtaMinutes` | Average quoted ETA across those snapshots |
| `measuredDeliveriesLast24h` | Deliveries with both a promised and an actual timestamp |
| `onTimeDeliveriesLast24h` | Subset that met or beat the promise |
| `onTimeRatePercent` | 0–100, two decimals; `0` when nothing was measurable |
| `avgLateMinutes` | Average overshoot across late deliveries **only** |

`avgLateMinutes` excludes on-time deliveries on purpose: it answers "when we miss, by how much?" rather than diluting the number with early arrivals. All fields report `0` rather than `null` for an empty window.

## Read-path caching

Two read-heavy public endpoints are now cached with short TTLs.

| Endpoint | Cache | TTL |
|----------|-------|-----|
| `GET /api/v1/home/feed` | `home-feed` | `cache.ttl.home-feed`, 60s |
| `GET /api/v1/serviceability/check` | `serviceability` | `cache.ttl.serviceability`, 60s |

`/home/feed` composes three independently cached sections (`banners`, `campaigns`, `membershipPlans`), so a miss on one does not recompute the others. Sixty seconds is chosen so a promo or zone edit becomes visible within a minute without a manual eviction.

## Track-order fix

`GET /api/v1/orders/customer/{orderId}/track` now works alongside the pre-existing `/customer/track/{orderId}` form; both map to the same handler. The 403 that previously masked this was a servlet ERROR-dispatch artifact — an unmapped path forwards to `/error`, which Spring Security re-authorized with an empty context. `/error` is now `permitAll`, so the real status surfaces.

Separately, `trackOrder` gained an ownership check, closing an IDOR where any authenticated customer could track any order. A non-owner now gets **401** (`UnauthorizedException`), not 403.

## Rollout order

1. Deploy with the shipped defaults. Fraud rows accumulate, invoices and PDFs are issued, proof is captured but not required.
2. Watch `fraud_events` for a few days and tune `app.fraud.thresholds` against real traffic before trusting the defaults.
3. Confirm riders are on a build that captures proof, then flip `app.delivery.proof.enforced` to `true` in a low-traffic window.
4. Staff the moderation queue before it becomes the reason reviews stop appearing.

## Database

`V17__trust_and_compliance.sql`:

| Section | Change |
|---------|--------|
| 1 | `order_invoices` PDF + email audit columns, `idx_invoice_email_pending` |
| 2 | New table `order_delivery_proofs` (unique on `order_id`) |
| 3 | `orders` index `idx_order_eta_accuracy` |
| 4 | `reviews` indexes `idx_review_restaurant_moderation`, `idx_review_moderation_queue` |
| 5 | `fraud_events` composite indexes `idx_fraud_ip_type_created`, `idx_fraud_fingerprint_type_created` |

Section 5 matters more than it looks: V13 indexed only `customer_id` and `event_type`, so every enforcement check on the register/login/order path was a full table scan. The new indexes put both equality predicates first and `created_at` last, making the sliding window an index range scan. Their names are referenced from the `FraudEventRepository` Javadoc — keep them in sync.

`order_invoices`, `reviews.moderation_status`, `reviews.owner_response`, `orders.estimated_delivery_at`, and `orders.delivered_at` all pre-date V17; no invoice or review data is rewritten by this migration.

## Related

- [Growth Features](./growth-features.md) — V13 invoices, review columns
- [Delivery Truth](./delivery-truth.md) — V14 ETA snapshots this metric reads
- [Scale Operations](./scale-operations.md) — V16 operations dashboard this metric extends
- [Configuration](./configuration.md) — full environment variable reference
- [API Usage](./api-usage.md) — auth flow and curl examples
