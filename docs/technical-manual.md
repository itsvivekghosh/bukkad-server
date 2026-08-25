# Bhukkad Platform — Technical API Manual

**Single consolidated reference for all integrations.** Base URL:
`https://<host>/api/v1` (local: `http://localhost:8080/api/v1`).

Every endpoint in this manual includes: (1) a functional description, (2) a
request-parameter table with types/constraints, (3) success and error response
schemas with examples, and (4) a behavioral analysis of state changes, side
effects, and edge cases. All content is verified against the codebase.

> Consolidated from `docs/api/*` (which remain as a quick-reference index).
> This manual is the authoritative version.

---

## Contents

- **Part I — Conventions** (envelope, auth, headers, errors, rate limits, pagination, SSE, idempotency)
- **Part II — Authentication**
- **Part III — Account & Customer** (profile, addresses, wallet, loyalty, referrals, favorites, subscriptions)
- **Part IV — Discovery** (home feed, search, restaurants, menu, coupons)
- **Part V — Cart & Orders** (cart, order lifecycle, live streams, group orders, gift cards)
- **Part VI — Payments** (status, webhooks, refunds, disputes)
- **Part VII — Delivery** (rider app, ETA truth)
- **Part VIII — Reviews**
- **Part IX — Admin** (dashboards, users, restaurants, ops, flags, DLQ, fraud)
- **Part X — Cross-cutting behavior** (outbox, saga, routing, cache, metrics)

---

# PART I — CONVENTIONS

## I.1 Response envelope

Every endpoint returns the same envelope. **Always read `success` before
`data`.**

```json
{
  "success": true,
  "message": "Order placed successfully",
  "data": { "...": "payload" },
  "timestamp": "2026-08-24T21:00:00",
  "traceId": "8060e534c23630e6a1393f746a07029d",
  "spanId": "09c46cd3bd00279e",
  "requestId": "862681db"
}
```

| Field | Type | Notes |
|---|---|---|
| `success` | boolean | `false` for all errors |
| `message` | string | Human-readable; `null` on plain `success(data)` |
| `data` | any | Payload; `null` on errors |
| `timestamp` | string | ISO-8601 server time |
| `traceId` / `spanId` / `requestId` | string | Correlation ids — include them in bug reports |

## I.2 Headers

| Header | Required for | Format / purpose |
|---|---|---|
| `Authorization` | All authenticated endpoints | `Bearer <accessToken>` (JWT, 24 h) |
| `Content-Type` | Endpoints with a body | `application/json` |
| `Idempotency-Key` | `POST /orders/customer/create`, `/orders/customer/create-batch`, `/customers/wallet/top-up` | Your UUID; replay returns the stored response |
| `Accept-Version` / `X-API-Version` | Optional | Default `1`; `0` → 400; deprecated versions emit `Warning` header; response always echoes `X-API-Version` |
| `X-Device-Fingerprint` | Optional (auth/orders) | Stable device id for fraud velocity |
| `Last-Event-ID` | SSE streams | Resume missed events |
| `X-API-Key` | Partner integrations | `bhk_<6-char-prefix>_<24-char-secret>` |
| `If-None-Match` / `If-Modified-Since` | Public GET (restaurants/menu/categories) | Conditional caching → `304` |

## I.3 Authentication model

- **Access token** (JWT HS256) — 24 h. Roles are **loaded from the DB per
  request**, so deactivation/role changes apply immediately.
- **Refresh token** (JWT) — 7 d. **Rotation**: each `POST /auth/refresh-token`
  revokes the old refresh token and issues a new pair.
- **MFA**: `ADMIN`/`RESTAURANT_OWNER` with TOTP enabled get
  `mfaRequired: true` + a 5-min `mfaToken` on login, then must call
  `POST /auth/mfa/verify`.
- Roles: `CUSTOMER`, `RESTAURANT_OWNER`, `DELIVERY_AGENT`, `ADMIN`,
  `PARTNER` (API keys).

## I.4 Error handling

| Code | Meaning | Frontend action |
|---|---|---|
| 200 / 201 / 202 | Success / created / async accepted | Render |
| 400 | Validation, WAF block, bad OTP, invalid transition | Show `message`; fix input |
| 401 | Missing/expired/revoked token, bad credentials | Refresh flow → force login |
| 403 | Wrong role or foreign resource | Role-aware routing |
| 404 | Not found | Not-found state |
| 409 | Duplicate in-flight (idempotency) | "Already processing" |
| 429 | Rate limited / fraud blocked | Back off per `Retry-After` |
| 500 | Server error | Generic + retry with backoff |

Error body:

```json
{
  "success": false,
  "message": "Duplicate order request is already being processed",
  "data": null,
  "timestamp": "2026-08-24T21:00:01",
  "traceId": "8060e534c23630e6a1393f746a07029d",
  "spanId": "09c46cd3bd00279e",
  "requestId": "862681db"
}
```

## I.5 Rate limits (hard ceilings → 429 + `Retry-After`)

| Bucket | Limit | Endpoints |
|---|---|---|
| `auth-register` | 10/60 s | `POST /auth/register` |
| `auth-login` | 10/60 s | `POST /auth/login`, `/auth/mfa/verify` |
| `cart-mutation` | 40/60 s | All cart writes |
| `order-track` | 20/60 s | Track, customer SSE, tracking tokens |
| `kitchen-queue` | 30/60 s | `GET /orders/restaurant/{id}/kitchen-queue` |
| `search` | 30/60 s | Search, suggest, restaurant search/nearby, menu search |
| `mobile-feed` | 30/60 s | `GET /mobile/feed` |
| `webhook` | 120/60 s | Razorpay webhook (per IP) |

Rate limiting is Redis-backed (`INCR`+`EXPIRE` Lua) and **fails open** on Redis
outage. Additionally, auth/order endpoints run a per-IP/per-device fraud
velocity check (60-min window) that can return `429` with `Retry-After: 300`.

## I.6 Pagination

- **Offset**: `?page=0&size=20` (default 20, max 100) →
  `PagedResponse<T>` = `{items[], page, size, totalElements, totalPages, hasNext}`.
- **Cursor**: `?cursor=<opaque>&size=20` →
  `CursorPagedResponse<T>` = `{items[], nextCursor, hasNext, size}`.
  Prefer cursor endpoints for order lists, earnings, settlements.

## I.7 Field projection

Order-detail endpoints accept `?fields=id,orderNumber,status,totalAmount`
(comma-separated, unknown names ignored, blank = full). The response stays
wrapped in `ApiResponse`; only `data` is filtered.

## I.8 Server-Sent Events (live tracking)

```js
const es = new EventSource(`${BASE}/api/v1/orders/stream/customer/${orderId}`,
  { headers: { Authorization: `Bearer ${token}` } });
```

| Event | Data |
|---|---|
| `connected` | `{"channel":"order:4242","id":0}` |
| `order-update` | `OrderLiveUpdate` (below) |
| `order-snapshot` | Full `OrderResponse` once on connect |
| `:heartbeat` | Keep-alive comment (25 s) |

```json
{
  "eventType": "STATUS_CHANGED",
  "eventId": 88231, "orderId": 4242, "orderNumber": "ORD-3f8a21c4",
  "customerId": 101, "restaurantId": 12, "deliveryAgentId": 77,
  "previousStatus": "READY_FOR_PICKUP", "status": "OUT_FOR_DELIVERY",
  "changedAt": "2026-08-24T21:31:00", "liveEtaMinutes": 12,
  "liveEtaAt": "2026-08-24T21:43:00", "latitude": 12.9716, "longitude": 77.5946
}
```

`eventType`: `ORDER_CREATED | STATUS_CHANGED | AGENT_ASSIGNED | RIDER_LOCATION`.
Resume with `Last-Event-ID: <lastEventId>` (Redis replay store). Anonymous
tracking: `POST /orders/stream/customer/{orderId}/tracking-token` (as
customer) → `GET /orders/stream/customer-token/{orderId}?token=<token>`.

## I.9 Idempotency

Generate one `Idempotency-Key` per logical operation before the first attempt;
reuse it on retries. A replayed key returns the **original** response (no
duplicate side effects). In-flight duplicates → `409`. Uniqueness enforced by
DB unique `(scope, idempotencyKey)` + Redis fast-path cache (TTL 24 h).

---

# PART II — AUTHENTICATION

## II.1 POST /auth/register

**Purpose:** creates a `CUSTOMER`, `RESTAURANT_OWNER`, or `DELIVERY_AGENT`
account, wires referral/affiliate hooks for customers, and returns a token
pair. Entry point of the entire platform — every other role-gated call needs
the tokens from this response.

**Request**

| Header | Required | Value |
|---|---|---|
| `Content-Type` | Yes | `application/json` |

```json
{
  "fullName": "Aarav Sharma",
  "email": "aarav@example.com",
  "password": "Test@123456",
  "phoneNumber": "9876543210",
  "role": "CUSTOMER",
  "referralCode": "ARAV50",
  "affiliateCode": null
}
```

| Field | Type | Required | Constraints | Description |
|---|---|---|---|---|
| `fullName` | string | Yes | non-blank | Full name |
| `email` | string | Yes | valid email | Login email (unique) |
| `password` | string | Yes | min 6 chars | Password |
| `phoneNumber` | string | No | `^[0-9]{10}$` | 10-digit phone |
| `role` | string | No | `CUSTOMER\|RESTAURANT_OWNER\|DELIVERY_AGENT` | Role (default CUSTOMER; ADMIN cannot self-register) |
| `referralCode` | string | No | — | Referral code (CUSTOMER only) |
| `affiliateCode` | string | No | — | Affiliate code (CUSTOMER only) |

**Response — 200 OK**

```json
{
  "success": true, "message": "Registration successful",
  "data": { "token": "eyJ...", "refreshToken": "eyJ...", "tokenType": "Bearer",
            "userId": 101, "email": "aarav@example.com", "fullName": "Aarav Sharma",
            "role": "CUSTOMER", "mfaRequired": false, "mfaToken": null },
  "timestamp": "2026-08-24T21:00:00", "traceId": "...", "spanId": "...", "requestId": "..."
}
```

| Field | Type | Description |
|---|---|---|
| `token` / `refreshToken` | string | Access (24 h) / refresh (7 d) JWT |
| `tokenType` | string | `Bearer` |
| `userId` | number | Account id |
| `email` / `fullName` / `role` | string | Profile snapshot |
| `mfaRequired` | boolean | Always `false` for new accounts |
| `mfaToken` | string \| null | Only when MFA required |

**Errors**

| Code | Scenario | Message |
|---|---|---|
| 400 | Invalid email/short password/bad phone | Validation text |
| 400 | Duplicate email/phone | `Email already exists` / `Phone number already registered` |
| 400 | Disallowed role | `Invalid user role` |
| 429 | Rate limit / fraud block | `Retry-After` |

**Behavioral analysis**
- **State changes:** creates `users` row (+ `customers`/`restaurant_owners`/
  `delivery_agents` JOINED subclass) with a BCrypt hash; `emailVerified=false`
  until `POST /auth/verify-email`. Customer registration runs referral
  initialization and affiliate signup hooks (side effects: referral credit
  records).
- **Side effects:** refresh token persisted to Redis (`auth:refresh:{uid}:{token}`)
  + index set; security-audit log entry.
- **Edge cases:** role omitted → CUSTOMER; referral code invalid → ignored
  (registration still succeeds); phone empty → skipped uniqueness check;
  `auth-register` bucket is global (all anonymous users share the 10/60 s
  counter) — the fraud check (per-IP/per-device) is the real per-source guard.

## II.2 POST /auth/login

**Purpose:** authenticates email+password and returns tokens, or steps into
MFA for privileged accounts.

**Request** — `POST /api/v1/auth/login` · `Content-Type: application/json`

```json
{ "email": "aarav@example.com", "password": "Test@123456" }
```

| Field | Type | Required | Constraints |
|---|---|---|---|
| `email` | string | Yes | valid email |
| `password` | string | Yes | non-blank |

**Response — 200 OK (no MFA):** `AuthResponse` (as II.1).
**Response — 200 OK (MFA gate):** `token:null`, `refreshToken:null`,
`mfaRequired:true`, `mfaToken:"eyJ..."`.

**Errors:** `401` `Invalid email or password`; `400` `Account is deactivated`;
`429` rate-limited/fraud-blocked.

**Behavioral analysis**
- **Flow:** BCrypt verify → if `totpEnabled` and role ∈ {ADMIN, OWNER} → issue
  a 5-min MFA token only (no access token) → else issue token pair.
- **Side effects:** refresh token stored in Redis; security login-success/
  login-failure audit events.
- **Edge cases:** wrong password increments the per-email rate-limit counter
  and the fraud velocity counter; a deactivated account always returns 400
  even with correct credentials (no enumeration beyond that).

## II.3 POST /auth/mfa/verify

**Purpose:** completes TOTP second factor with the `mfaToken` from login;
returns the real token pair.

**Request** — `POST /api/v1/auth/mfa/verify?mfaToken=...&code=...`

| Param | Type | Required | Constraints |
|---|---|---|---|
| `mfaToken` | string | Yes | from login response |
| `code` | string | Yes | 6-digit TOTP |

**Response — 200 OK:** full `AuthResponse`. **Errors:** `400` `MFA token
expired or invalid` / bad code; `429` rate limit.

**Behavioral analysis:** token must have `type=mfa` and be unexpired; TOTP
verified with ±1 step window (90 s tolerance). No state persists on failure.

## II.4 POST /auth/refresh-token

**Purpose:** rotate a valid refresh token into a new access+refresh pair.

**Request** — `POST /api/v1/auth/refresh-token` · Header
`Authorization: Bearer <refreshToken>` (required).

**Response — 200 OK:** full `AuthResponse` with new pair.
**Errors:** `401` `Refresh token revoked or expired`; `400` account deactivated.

**Behavioral analysis**
- **State changes:** old refresh token **revoked** (Redis `DEL` + `SREM`
  index) → new pair issued and stored. Reusing a rotated token → 401.
- **Edge cases:** refresh tokens are rejected by the access-token filter if
  sent to other endpoints; logout of an already-rotated token is a no-op.

## II.5 POST /auth/logout

**Purpose:** end the session server-side.

**Request** — `POST /api/v1/auth/logout` · Header `Authorization: Bearer <token>`.

**Response — 200 OK:** `data:"Logger out successfully"`.

**Behavioral analysis:** refresh token → revoked from Redis; access token →
blacklisted (`auth:blacklist:{token}`, TTL = remaining life) so the filter
rejects it. Best-effort — never throws; always 200 unless the header is
malformed (400).

## II.6 POST /auth/verify-email

**Purpose:** verify the account email with the emailed JWT.
**Request** — `POST /api/v1/auth/verify-email?email=...` · Header
`Authorization: Bearer <verificationToken>` (required).
**Response:** 200 `ApiResponse<Void>`.
**Errors:** 400 `User not found` / `Email already verified`; 401 bad header.
**Behavior:** sets `emailVerified=true`; idempotent.

## II.7 POST /auth/forgot-password

**Purpose:** start password reset (30-min token + email).
**Request** — `?email=` (required). **Response:** 200 `ApiResponse<Void>`.
**Errors:** 400 `User not found`.
**Behavior:** stores `auth:reset:{uuid}` = email with 30-min TTL; sends email.
Edge case: rate is not annotation-limited (none configured).

## II.8 POST /auth/reset-password

**Purpose:** consume reset token, set new password, revoke all refresh tokens.
**Request** — `?token=` (required) & `?newPassword=` (required, min 6).
**Response:** 200 `ApiResponse<Void>`. **Errors:** 400 `Invalid or expired
reset token`.

## II.9 POST /auth/change-password

**Purpose:** change password for the authenticated user.
**Request** — `?oldPassword=` & `?newPassword=` (min 6, must differ).
Header `Authorization` required. **Response:** 200 `ApiResponse<Void>`.
**Errors:** 400 wrong old password / weak new password; 401 bad token.
**Behavior:** revokes all refresh tokens after the change.

---

# PART III — ACCOUNT & CUSTOMER

All endpoints require `Authorization: Bearer <accessToken>` (CUSTOMER role)
unless noted. Base: `/api/v1/customers`; referrals `/api/v1/referrals`;
subscriptions `/api/v1/customers/subscriptions`; affiliates
`/api/v1/admin/affiliates`.

## III.1 Profile

### GET /customers/profile
**Purpose:** the caller's full profile (addresses, wallet, loyalty, order
count) — powers the "My Account" screen.
**Request:** `GET /api/v1/customers/profile` (no params).
**Response — 200:** `ApiResponse<CustomerProfileResponse>`

| Field | Type | Description |
|---|---|---|
| `id` | number | Customer id |
| `email` / `fullName` / `phoneNumber` / `profileImageUrl` | string | Profile |
| `active` / `emailVerified` | boolean | Account flags |
| `loyaltyPoints` | number | Balance |
| `walletBalance` | number | Balance |
| `role` | string | `CUSTOMER` |
| `createdAt` | string | ISO |
| `addresses` | array | `AddressResponse[]` |
| `totalOrders` | number | Count |

**Errors:** 401/403 unauthenticated/non-customer; 404 user.
**Behavior:** resolves the user from the JWT subject; addresses joined; no
side effects. Read replica used.

### GET /customers/profile/{profileId}
**Purpose:** another customer's profile (social features).
**Request:** path `profileId` (number, required, must be positive).
**Response:** `ApiResponse<CustomerProfileResponse>`.
**Errors:** 400 non-positive; 404 not found.

### PUT /customers/profile
**Purpose:** update profile fields.
**Request:** `?fullName=&phoneNumber=&profileImageUrl=` (all optional query
params, no body).
**Response:** `ApiResponse<CustomerResponse>` (profile minus `totalOrders`).
**Behavior:** only provided fields are written.

### DELETE /customers/account
**Purpose:** deactivate the caller's account (soft delete).
**Request:** none. **Response:** `ApiResponse<Void>`.
**Behavior:** sets `active=false`; tokens remain valid until expiry unless the
user logs out (auth checks `active` per request and rejects deactivated users).

## III.2 Addresses

`AddressRequest` (shared create/update body):

```json
{
  "addressLine1": "12, MG Road", "addressLine2": "3rd Floor", "city": "Bengaluru",
  "state": "Karnataka", "pincode": "560001", "landmark": "Near Metro",
  "type": "HOME", "label": "Home", "latitude": 12.9716, "longitude": 77.5946,
  "isDefault": true
}
```

| Field | Type | Required | Constraints |
|---|---|---|---|
| `addressLine1` | string | Yes | non-blank |
| `addressLine2` | string | No | — |
| `city` | string | Yes | non-blank |
| `state` | string | Yes | non-blank |
| `pincode` | string | Yes | non-blank |
| `landmark` | string | No | — |
| `type` | string | No | `HOME\|WORK\|OTHER` |
| `label` | string | No | — |
| `latitude` / `longitude` | number | Yes | non-null |
| `isDefault` | boolean | No | default `false` |

| Endpoint | Purpose | Response |
|---|---|---|
| `POST /customers/addresses` | Create | `ApiResponse<AddressResponse>` |
| `GET /customers/addresses` | List | `ApiResponse<AddressResponse[]>` |
| `PUT /customers/addresses/{addressId}` | Update | `ApiResponse<AddressResponse>` |
| `DELETE /customers/addresses/{addressId}` | Delete | `ApiResponse<Void>` |
| `PUT /customers/addresses/{addressId}/set-default` | Promote default | `ApiResponse<AddressResponse>` |

`AddressResponse` = request fields + server-assigned `id` + `type` string.

**Errors:** 400 validation (blank city/state/pincode, null lat/lng, foreign
address id); 404 not found.
**Behavior:** all address operations are owner-scoped — mutating another
customer's address returns 400/404 (no data leak). `isDefault` demotes other
defaults.

## III.3 Wallet

### GET /customers/wallet/balance
**Purpose:** current wallet balance. **Request:** none.
**Response — 200:** `ApiResponse<number>` (`data` = balance).

### POST /customers/wallet/top-up
**Purpose:** initiate a gateway top-up (Razorpay when enabled). Returns a
`PaymentResponse` carrying `gatewayOrderId` for the payment SDK; the wallet is
credited **only after** the gateway webhook confirms.
**Request:** `POST /api/v1/customers/wallet/top-up?amount=500` · optional
`Idempotency-Key`.
| Param | Type | Required | Constraints |
|---|---|---|---|
| `amount` | number | Yes | positive |

**Response — 200:** `ApiResponse<PaymentResponse>` — `id, orderId(null),
paymentMethod(UPI), status(PENDING), amount, gatewayOrderId, gatewayPaymentId,
transactionId, purpose(WALLET_TOP_UP)`.

**Errors:** 400 validation / gateway disabled; 409 duplicate key in flight.
**Behavioral analysis**
- **State changes:** creates a `Payment` row with `purpose=WALLET_TOP_UP`,
  `status=PENDING`; creates a gateway order. No wallet mutation yet.
- **Side effects / completion:** the Razorpay `payment.captured` webhook →
  `completeTopUp` → payment `COMPLETED` + `walletService.credit(TOP_UP)`.
- **Edge cases:** replay of the same `Idempotency-Key` returns the original
  `PaymentResponse`; payment stays PENDING forever if the webhook never fires
  (no auto-cancel in this flow).

### POST /customers/wallet/add-money
**Purpose:** direct wallet credit (dev/admin; gated by
`app.wallet.allow-direct-top-up`).
**Request:** `?amount=` (positive, required). **Response:** `ApiResponse<Void>`.
**Errors:** 403 when the feature flag is off.

## III.4 Loyalty, referral, favorites

| Endpoint | Purpose | Response |
|---|---|---|
| `GET /customers/loyalty-points` | Balance | `ApiResponse<number>` |
| `GET /customers/referral` | Referral info | `ApiResponse<ReferralInfoResponse>` |
| `POST /referrals/generate` | Create/return code | `ApiResponse<string>` |
| `POST /referrals/validate` | Check code | `ApiResponse<boolean>` |
| `GET /referrals/rewards` | Rewards summary | `ApiResponse<ReferralInfoResponse>` |
| `GET /customers/favorites` | List | `ApiResponse<FavoriteRestaurantResponse[]>` |
| `POST /customers/favorites/{restaurantId}` | Add | `ApiResponse<FavoriteRestaurantResponse>` |
| `DELETE /customers/favorites/{restaurantId}` | Remove | `ApiResponse<Void>` |
| `GET /customers/orders/stats` | Order stats | `ApiResponse<CustomerOrderStatsResponse>` |
| `GET /customers/data-export` | DPDP/GDPR export | `ApiResponse<Map>` |

- `ReferralInfoResponse`: `referralCode` (string), `referralsCount` (number),
  `referralBonusEarned` (number).
- `POST /referrals/validate` body: `{"referralCode": "ARAV50"}` (`@NotBlank`).
- `CustomerOrderStatsResponse`: `totalOrders, deliveredOrders, cancelledOrders,
  totalSpent, loyaltyPoints`.
- `FavoriteRestaurantResponse`: `restaurantId, restaurantName, imageUrl,
  averageRating, isOpen`.
- Referral endpoints are rate-limited **service-side** per user
  (`referral:generate:{uid}`, `referral:validate:{uid}`) — 429 on abuse.
- **Behavior:** loyalty points are **earned on delivery**, not on order
  creation; they're redeemed at checkout (`loyaltyPointsToRedeem` in the order
  request).

## III.5 Notifications & devices

| Endpoint | Purpose |
|---|---|
| `GET /customers/notification-preferences` | 6 booleans: `emailEnabled, smsEnabled, pushEnabled, whatsappEnabled, orderUpdatesEnabled, promotionsEnabled` |
| `PUT /customers/notification-preferences` | Same 6 booleans body → updated response (note: no `@Valid` — send all fields) |
| `POST /customers/device-tokens` | Body `{"token":"<push>","platform":"ANDROID"}` → `{id, platform, active}` |
| `DELETE /customers/device-tokens` | Body `{"token":"<push>"}` → `ApiResponse<Void>` |

## III.6 Subscriptions (recurring orders)

Base `/api/v1/customers/subscriptions` (CUSTOMER).

| Endpoint | Purpose |
|---|---|
| `POST /customers/subscriptions` | Create plan (body below) |
| `GET /customers/subscriptions` | List caller's plans |
| `POST /customers/subscriptions/{id}/pause` | Pause |
| `POST /customers/subscriptions/{id}/resume` | Resume |
| `POST /customers/subscriptions/{id}/cancel` | Cancel |
| `POST /customers/subscriptions/{id}/skip` | Skip next delivery |

**Create body:**

```json
{
  "restaurantId": 12, "title": "Weekday lunch", "weekday": "MON",
  "deliveryTime": "13:00:00", "deliveryAddressId": 5, "paymentMethod": "WALLET",
  "startDate": "2026-09-01", "items": [ { "menuItemId": 42, "quantity": 2 } ]
}
```

| Field | Type | Required | Constraints |
|---|---|---|---|
| `restaurantId` | number | Yes | — |
| `title` | string | No | max 100 |
| `weekday` | string | Yes | `MON`..`SUN` case-insensitive |
| `deliveryTime` | string | Yes | `HH:mm:ss` |
| `deliveryAddressId` | number | Yes | saved address |
| `paymentMethod` | string | Yes | — |
| `startDate` | string | Yes | `yyyy-MM-dd` |
| `items` | array | Yes | min 1; `{menuItemId, quantity>0}` |

**Response — 200:** `ApiResponse<SubscriptionPlanResponse>` — `id,
restaurantId, title, weekday, deliveryTime, deliveryAddressId, paymentMethod,
status(ACTIVE|PAUSED|CANCELLED), startDate, nextDeliveryDate,
deliveries[{id, orderId, scheduledDate, status(PENDING|PLACED|SKIPPED|FAILED)}]`.

**Behavior:** deliveries are generated per weekday from `startDate`; `skip`
advances `nextDeliveryDate` past the next occurrence; cancel stops generation.

## III.7 Affiliates (ADMIN) — /api/v1/admin/affiliates

| Endpoint | Purpose |
|---|---|
| `GET /admin/affiliates` | List codes |
| `POST /admin/affiliates` | Create — `{code, name, channel?, rewardAmount, isActive?}` |
| `PUT /admin/affiliates/{affiliateId}` | Update (same body) |
| `DELETE /admin/affiliates/{affiliateId}` | Deactivate |
| `GET /admin/affiliates/{affiliateId}/stats` | `{affiliateCodeId, code, name, totalReferrals, paidReferrals, totalReward, recentReferrals[]}` |

`AffiliateCodeRequest`: `code` `@NotBlank`, `name` `@NotBlank`,
`rewardAmount` `@NotNull`. `AffiliateCodeResponse` adds `id, isActive,
createdAt`. Note: `affiliateId` path params have no `@Positive` constraint —
send valid ids.

---

# PART IV — DISCOVERY

Public reads served from cache/read-replica; writes are owner-scoped.

## IV.1 Home feed (public)

### GET /home/feed (and /mobile/feed)
**Purpose:** the app's landing payload — banners, campaigns, membership
plans, trending dishes. `/mobile/feed` is the BFF variant (rate-limited
`mobile-feed` 30/60 s).
**Request:** `GET /api/v1/home/feed` (no auth/params).
**Response — 200:** `ApiResponse<Map>` with keys `banners`, `campaigns`,
`membershipPlans`, `trendingDishes`.

- `PromoBannerResponse`: `id, title, subtitle, imageUrl, actionType,
  actionTarget, displayOrder`.
- `MembershipPlanResponse`: `id, name, description, pricePerMonth,
  freeDelivery, discountPercent, tierLevel, maxDiscountPercent,
  referralBonusPercent, referralMaxPerMonth`.
- `PromotionCampaignResponse`: `id, name, campaignType, description,
  discountPercent, flatDiscountAmount, minOrderAmount, maxDiscountAmount,
  restaurantId, freeDelivery, priority, usageLimit, perUserLimit, isActive,
  startsAt, endsAt, buyQuantity, getQuantity, getDiscountPercent,
  targetSegment, applicableMenuItemId`.

```json
{
  "success": true,
  "data": {
    "banners": [ { "id": 1, "title": "Monsoon sale", "subtitle": "30% off",
                   "imageUrl": "https://cdn.example.com/b1.jpg", "actionType": "DEEP_LINK",
                   "actionTarget": "bhukkad://promo/1", "displayOrder": 1 } ],
    "campaigns": [ { "id": 5, "name": "FLAT100", "campaignType": "DISCOUNT", "flatDiscountAmount": 100.0, "minOrderAmount": 499.0 } ],
    "membershipPlans": [ { "id": 1, "name": "Bhukkad Plus", "pricePerMonth": 199.0, "freeDelivery": true } ],
    "trendingDishes": [ { "id": 42, "name": "Butter Chicken", "orderItemCount": 87 } ]
  }
}
```

**Errors:** 429 (`/mobile/feed`); 500 on cache/db failure — feed **degrades to
empty lists**, never 5xx on the hot path.
**Behavior:** assembled by `HomeFeedCacheService` (in-process 60 s + Redis,
`@UseReadReplica`); trending dishes come from the **materialized
`trending_dishes` table** fed by the `ORDER_ITEMS_SNAPSHOT` outbox event
(no cross-domain SQL). Side effects: none (read-only).

### GET /home/banners · /home/campaigns · /home/membership-plans
Individual slices: `ApiResponse<PromoBannerResponse[]>`,
`ApiResponse<PromotionCampaignResponse[]>`,
`ApiResponse<MembershipPlanResponse[]>`.

## IV.2 Search (public; rate-limited `search`)

### GET /search
**Purpose:** unified search across restaurants + menu items.
**Request:** `GET /api/v1/search?keyword=pizza` (`keyword` required).
**Response — 200:** `ApiResponse<UnifiedSearchResponse>` — `restaurants[]`,
`menuItems[]`, `restaurantCount`, `menuItemCount`.
**Errors:** 400 missing keyword; 429.
**Behavior:** `@UseReadReplica`; suggestion caches in Redis; no writes.

### GET /search/suggest
Authenticated (any role). `?q=piz&limit=8` → `ApiResponse<AutocompleteSuggestion[]>`:
`{text, type(RESTAURANT|MENU_ITEM)}`.

## IV.3 Restaurants

**`RestaurantResponse`** (shared): `id, name, description,
address(AddressResponse), cuisines[], imageUrl, galleryImages[], openingTime,
closingTime, isOpen, isActive, averageRating, totalReviews,
averageDeliveryTime, minimumOrderAmount, deliveryFee, freeDeliveryAvailable,
freeDeliveryAbove, isPureVeg, foodTypes[], features[], virtualBrandName,
onboardingStatus, tenantId`.

### Public reads

| Endpoint | Purpose | Notes |
|---|---|---|
| `GET /restaurants/public` | List / batch | `?ids=` repeated (max 100); ETag cacheable; `X-Tenant-Id` optional |
| `GET /restaurants/public/{id}` | Detail | ETag cacheable; 404 missing |
| `GET /restaurants/public/nearby` | Geo | `?latitude&longitude` (req), `radiusKm` (def 5), `limit` (def 20); rate-limited `search` |
| `GET /restaurants/public/search` | Text | `?keyword=` (req); rate-limited `search` |
| `GET /restaurants/public/filter` | Filter | `?cuisineId=` & `?isPureVeg=` optional |

**Errors:** 400 missing params; 404 not found; 429 on search/nearby; 304 on
ETag match.
**Behavior:** read replica + Redis cache with pub/sub invalidation on owner
writes; `isOpen` computed from opening/closing hours server-side.

### Owner endpoints (RESTAURANT_OWNER)

**`RestaurantRequest`** (create/update):

```json
{
  "name": "Spice Route", "description": "North Indian",
  "address": { "addressLine1": "12 MG Road", "city": "Bengaluru", "state": "Karnataka",
               "pincode": "560001", "latitude": 12.9716, "longitude": 77.5946 },
  "cuisineIds": [1, 2], "imageUrl": "https://cdn.example.com/r.jpg",
  "openingTime": "10:00:00", "closingTime": "23:00:00",
  "averageDeliveryTime": 35, "minimumOrderAmount": 149.0, "deliveryFee": 35.0,
  "freeDeliveryAbove": 499.0, "isPureVeg": false, "foodTypes": ["INDIAN"],
  "features": ["Parking"], "licenseNumber": "FSSAI-123", "fssaiNumber": "FSSAI-123"
}
```

Required: `name`, `address` (non-blank `addressLine1/city/state/pincode`,
non-null lat/lng), `openingTime`, `closingTime`. `AddressRequest` schema as
III.2.

| Endpoint | Purpose | Response |
|---|---|---|
| `POST /restaurants/owner` | Create | `ApiResponse<RestaurantResponse>` |
| `POST /restaurants/onboarding/signup` | Dark-kitchen onboarding (`PENDING_VERIFICATION`) | `ApiResponse<RestaurantResponse>` |
| `GET /restaurants/onboarding/status` | Onboarding status | `{restaurants:[{restaurantId, name, onboardingStatus, rejectionReason, isActive}]}` |
| `GET /restaurants/owner/my-restaurants` | List mine | `ApiResponse<RestaurantResponse[]>` |
| `PUT /restaurants/owner/{id}` | Update | `ApiResponse<RestaurantResponse>` |
| `DELETE /restaurants/owner/{id}` | Deactivate | `ApiResponse<Void>` |
| `PUT /restaurants/owner/{id}/toggle-status?isOpen=` | Open/close | `ApiResponse<Void>` |
| `PUT /restaurants/owner/{id}/busy-mode` | Set busy | body `{busyUntil, extraPrepMinutes}` → `ApiResponse<Void>` |
| `DELETE /restaurants/owner/{id}/busy-mode` | Clear busy | `ApiResponse<Void>` |
| `GET /restaurants/owner/{id}/analytics?days=30` | Analytics | `RestaurantAnalyticsResponse` |
| `GET /restaurants/owner/{id}/dashboard?days=30` | Dashboard | `RestaurantDashboardResponse` |
| `GET /restaurants/owner/{id}/settlements?page&size` | Paged settlements | `PagedResponse<RestaurantSettlementResponse>` |
| `GET /restaurants/owner/{id}/settlements/cursor?cursor&size` | Cursor settlements | `CursorPagedResponse<...>` |
| `POST /restaurants/owner/reviews/{reviewId}/response` | Reply to review | body `{"response":"..."}` (max 2000) → `ApiResponse<Review>` |

**Behavioral analysis (busy mode):** setting `busy-mode` makes
`OrderPlacementService` reject new orders until `busyUntil` (auto-clears when
the timestamp passes); `extraPrepMinutes` inflates live ETA. Analytics/dashboard
aggregate orders + settlements + top items + hourly/daily volume from the read
replica. All owner mutations invalidate the restaurant/menu Redis cache via
pub/sub.

**Errors:** 400 validation / non-owner; 403 wrong role; 404 not owned.

## IV.4 Menu

**`MenuItemResponse`** (shared): `id, name, description, categoryName, price,
originalPrice, discountPercentage, available, foodType(VEG|NON_VEG|VEGAN|EGGETARIAN),
isVeg, isSpicy, spiceLevel(MILD|MEDIUM|HOT|EXTRA_HOT), allergens[],
imageUrl, additionalImages[], preparationTime, bestseller, recommended,
calories, servingSize, ingredients[], averageRating, totalRatings,
customizationOptions[{id, name, required, multipleSelection, minSelection,
maxSelection, choices[{id, name, additionalPrice, available}]}], tags[]`.

### Public reads

| Endpoint | Notes |
|---|---|
| `GET /menu/items` | `?ids=` repeated, max 100 |
| `GET /menu/items/{id}` | ETag-cacheable |
| `GET /menu/items/category/{categoryId}` | ETag-cacheable |
| `GET /menu/items/restaurant/{restaurantId}` | Full menu for a restaurant |
| `GET /menu/items/restaurant/{restaurantId}/bestsellers` | Best sellers |
| `GET /menu/items/restaurant/{restaurantId}/recommended` | Recommended |
| `GET /menu/items/search?keyword=` | Rate-limited `search` |
| `GET /menu/categories/restaurant/{restaurantId}` | Categories with `itemCount` |
| `GET /cuisines` · `GET /cuisines/{id}` | Cuisine list |

### Owner writes (RESTAURANT_OWNER)

| Endpoint | Purpose |
|---|---|
| `POST /menu/categories?restaurantId=` | Create — `{name, description?, displayOrder?, active?}` |
| `PUT /menu/categories/{categoryId}` · `DELETE /menu/categories/{categoryId}` | Update/delete |
| `POST /menu/items` | Create item (schema below) |
| `PUT /menu/items/{id}` · `DELETE /menu/items/{id}` | Update/delete |
| `PUT /menu/items/{id}/toggle-availability?available=` | Hide/show |
| `POST /menu/items/{id}/image/upload-url` | `{"contentType":"image/jpeg"}` → `{uploadUrl, imageKey, expiresInSeconds}` (presigned S3 PUT; then set `imageKey` on the item) |
| `GET /menu/items/restaurant/{restaurantId}/low-stock?threshold=` | Low-stock list |

**`POST /menu/items` body:**

```json
{
  "name": "Butter Chicken", "description": "Creamy tomato gravy", "categoryId": 3,
  "price": 320.0, "originalPrice": 360.0, "foodType": "NON_VEG", "isVeg": false,
  "isSpicy": true, "spiceLevel": "MEDIUM", "allergens": ["MILK", "NUTS"],
  "imageKey": "menu-items/12/butter-chicken.jpg", "preparationTime": 15,
  "calories": 450, "servingSize": "1 bowl", "ingredients": ["chicken", "butter"],
  "tags": ["bestseller"], "stockQuantity": 50,
  "customizationOptions": [ { "name": "Add-ons", "required": false, "multipleSelection": true,
      "choices": [ { "name": "Extra butter", "additionalPrice": 30.0, "available": true } ] } ]
}
```

Required: `name`, `categoryId`, `price` (>0), `foodType`, `isVeg`.

**Behavior:** image URLs are resolved through `MenuImageService` (CloudFront
CDN when configured, else presigned S3). Writes invalidate menu caches; stock
updates sync to the Redis reservation keys used by order placement.

**Errors:** 400 validation; 403 wrong owner; 404 missing.

## IV.5 Coupons

### GET /coupons/active (public)
`?restaurantId=` optional → `ApiResponse<CouponResponse[]>`.
`CouponResponse`: `id, code, description, discountType(PERCENTAGE|FIXED_AMOUNT),
discountValue, minimumOrderAmount, maximumDiscountAmount, validFrom, validUntil`.

### GET /coupons/validate (CUSTOMER)
`?code=WELCOME50&orderAmount=640&restaurantId=12` → `ApiResponse<CouponResponse>`
if valid, else `400`.
**Behavior:** validates active window, usage limits (global + per-user), min
order, restaurant scope. No side effects.

### POST /coupons (ADMIN or OWNER)
Body: `code` `@NotBlank`, `description` `@NotBlank`, `discountType`
`@NotNull`, `discountValue` `@NotNull @Positive`, `minimumOrderAmount?`,
`maximumDiscountAmount?`, `validFrom`/`validUntil` `@NotNull`, `usageLimit?`,
`perUserLimit?`, `restaurantId?`.

### PUT /coupons/{couponId} (ADMIN) · DELETE /coupons/{couponId} (ADMIN)
Update (same body) / delete.

## IV.6 Serviceability, platform, recommendations

| Endpoint | Role | Purpose |
|---|---|---|
| `GET /serviceability/**` | public | Delivery serviceability by pincode/city |
| `GET /platform/status` · `/platform/cities` · `/platform/tenants/**` | public | Status, cities, tenant info |
| `GET /customers/me/recommendations` | CUSTOMER | Personalized recommendations |

---

# PART V — CART & ORDERS

## V.1 Shared shapes

**`OrderResponse`** (detail/create/lifecycle): `id, orderNumber, customerId,
customerName, restaurantId, restaurantName, items[{id, menuItemName,
quantity, price, customizations[], specialInstructions}],
deliveryAddress(AddressResponse), status, subtotal, deliveryFee, taxAmount,
discountAmount, totalAmount, tipAmount, paymentMethod, paymentStatus,
specialInstructions, contactlessDelivery, estimatedDeliveryTime,
estimatedDeliveryAt, scheduledAt, liveEtaMinutes, liveEtaAt, deliveredAt,
createdAt, deliveryAgent({id, fullName, email, phoneNumber, vehicleType,
vehicleNumber, available, verified, averageRating, totalDeliveries, role})`.

**Order statuses:** `SCHEDULED, PLACED, CONFIRMED, PREPARING,
READY_FOR_PICKUP, OUT_FOR_DELIVERY, DELIVERED, CANCELLED, REFUNDED`.

**`OrderSummaryResponse`** (lists/kitchen queue): `id, orderNumber,
customerId, customerName, restaurantId, restaurantName, status, totalAmount,
specialInstructions, createdAt, estimatedDeliveryAt`.

**Pagination wrappers:** `PagedResponse<T>` / `CursorPagedResponse<T>` (I.6).

## V.2 Cart (CUSTOMER)

`CartResponse`: `id, restaurantId(deprecated), restaurantName(deprecated),
items(deprecated), restaurantCarts[{restaurantId, restaurantName, items[],
subtotal, itemCount}], subtotal, itemCount`. `CartItemResponse`: `id,
menuItemId, menuItemName, price, quantity, customizations[], totalPrice,
specialInstructions`.

| Endpoint | Purpose | Notes |
|---|---|---|
| `GET /cart` | Get cart | read replica |
| `POST /cart/add` | Add item | rate-limited `cart-mutation` |
| `PUT /cart/items/{cartItemId}` | Update quantity | `?quantity=` required integer; rate-limited |
| `DELETE /cart/items/{cartItemId}` | Remove line | rate-limited |
| `DELETE /cart/restaurant/{restaurantId}` | Clear one restaurant | rate-limited |
| `DELETE /cart/clear` | Clear all | rate-limited; `ApiResponse<Void>` |
| `POST /cart/apply-coupon` | Apply coupon | `?couponCode=` required; rate-limited |

**`POST /cart/add` body:**

```json
{ "menuItemId": 42, "quantity": 2, "customizationChoiceIds": [10, 11], "specialInstructions": "Less spicy" }
```

| Field | Type | Required | Constraints |
|---|---|---|---|
| `menuItemId` | number | Yes | — |
| `quantity` | number | Yes | `@Positive` (> 0) |
| `customizationChoiceIds` | number[] | No | choice ids |
| `specialInstructions` | string | No | — |

**Errors:** 400 item unavailable / insufficient stock / mixing restaurants;
429.
**Behavior:** validates item availability + stock server-side; carts are
**multi-restaurant** (groups per restaurant); the cart for a restaurant is
consumed by order creation. Cache invalidation on every mutation.

## V.3 POST /orders/customer/create — the core write path

### Purpose
Places an order from the customer's cart for one restaurant. Runs the full
pipeline: fraud check, idempotency, stock reservation, pricing (fee/tax/
coupon/promotion/loyalty/wallet), order persistence, payment (unless COD),
cart cleanup, cache invalidation, and outbox events. Sync (`200`) or async
(`202` + job poll).

### Request

**Method & URL:** `POST /api/v1/orders/customer/create[?async=false]`

| Header | Required | Value |
|---|---|---|
| `Authorization` | Yes | `Bearer <token>` (CUSTOMER) |
| `Content-Type` | Yes | `application/json` |
| `Idempotency-Key` | No (recommended) | UUID |

| Param | Type | Default | Description |
|---|---|---|---|
| `async` | boolean | `false` | `true` → 202 + job; `false` → 200 + order |

**Body:**

```json
{
  "restaurantId": 12, "deliveryAddressId": 5, "specialInstructions": "No onions please",
  "contactlessDelivery": true, "couponCode": "WELCOME50", "paymentMethod": "UPI",
  "loyaltyPointsToRedeem": 20, "walletAmountToUse": null, "useWallet": true,
  "tipAmount": 15.0, "scheduledAt": null
}
```

| Field | Type | Required | Constraints | Description |
|---|---|---|---|---|
| `restaurantId` | number | Yes | — | Target restaurant |
| `deliveryAddressId` | number | Yes | owned address | Saved address |
| `specialInstructions` | string | No | — | Kitchen note |
| `contactlessDelivery` | boolean | No | — | default false |
| `couponCode` | string | No | — | Coupon |
| `paymentMethod` | string | Yes | `CASH_ON_DELIVERY\|CREDIT_CARD\|DEBIT_CARD\|UPI\|WALLET\|NET_BANKING` | Method |
| `loyaltyPointsToRedeem` | number | No | ≥ 0 | Points → discount |
| `walletAmountToUse` | number | No | ≥ 0 | Explicit wallet split |
| `useWallet` | boolean | No | — | Apply wallet up to total |
| `tipAmount` | number | No | ≥ 0 | Rider tip (added to total) |
| `scheduledAt` | string | No | ISO-8601 | Future delivery |

> **No `items` field** — the order is assembled from the customer's cart for
> `restaurantId`.

### Response

**200 OK (sync):**

```json
{
  "success": true, "message": "Order placed successfully",
  "data": {
    "id": 4242, "orderNumber": "ORD-3f8a21c4", "customerId": 101, "restaurantId": 12,
    "items": [ { "id": 1, "menuItemName": "Butter Chicken", "quantity": 2, "price": 320.0 } ],
    "status": "PLACED", "subtotal": 640.0, "deliveryFee": 35.0, "taxAmount": 108.8,
    "discountAmount": 50.0, "totalAmount": 733.8, "tipAmount": 15.0,
    "paymentMethod": "UPI", "paymentStatus": "COMPLETED",
    "estimatedDeliveryTime": 40, "estimatedDeliveryAt": "2026-08-24T21:40:00",
    "liveEtaMinutes": 40, "liveEtaAt": "2026-08-24T21:40:00",
    "createdAt": "2026-08-24T21:00:00", "deliveryAgent": null
  }
}
```

**202 Accepted (async):**

```json
{ "success": true, "message": "Order accepted for processing",
  "data": { "jobId": "ord-abc-123", "status": "PROCESSING", "order": null,
            "message": null, "pollUrl": "/api/v1/orders/customer/create/jobs/ord-abc-123" } }
```

### Errors

| Code | Scenario | Message |
|---|---|---|
| 400 | Restaurant closed / busy / inactive | varies |
| 400 | Insufficient stock / invalid items | `Insufficient stock` |
| 400 | Invalid payment method / invalid total | varies |
| 400 | Payment gateway rejected | `Payment failed` |
| 404 | Customer / restaurant / address missing | `Order not found` etc. |
| 409 | Same `Idempotency-Key` in flight | `Duplicate order request is already being processed` |
| 429 | Fraud velocity block | `Retry-After: 300` |

### Behavioral analysis (the full pipeline)

1. **Fraud gate first** — `checkAndBlock(customerId, ORDER_CREATE)` counts
   per customer/device/IP (60-min window); 429 on breach. Runs before both
   branches so the async executor thread (no request context) is never the
   check point.
2. **Idempotency** — Redis fast-path replay of a completed key returns the
   stored order; else DB `IN_PROGRESS` row (unique `(scope, key)`) guards
   concurrency → 409 for duplicates.
3. **Guards** — restaurant active/open + `busy-mode` check + scheduled-order
   window (min lead, max days ahead) + open-hours for non-scheduled.
4. **Stock reservation (Redis)** — per item: `SETNX stock:item:{id}` (from DB
   stock) then atomic `DECR`; negative → `INCR` back + reject. `stockReserved`
   flag drives rollback compensation.
5. **Pricing** — subtotal → min-order → delivery fee (membership free
   delivery → promo free delivery → free-above threshold → zone-based → flat)
   → tax → coupon → membership discount → best campaign → loyalty redemption →
   wallet split (`WALLET` method requires full balance).
6. **Persistence** — order saved with `@Version` optimistic lock
   (`OptimisticLockingFailureException` → 400 retry); timeline
   `ORDER_PLACED`; DB stock decrement + Redis sync; loyalty deduction; wallet
   debit (`ORDER_DEBIT` transaction); coupon usage record.
7. **Payment** — `createPayment` + `processPayment` (non-COD) — see Part VI.
8. **Cleanup & notify** — clear the restaurant's cart lines; invalidate
   order/track/kitchen caches; outbox `ORDER_CREATED` + `ORDER_ITEMS_SNAPSHOT`;
   metrics (`orders.created`, funnel).
9. **Failure compensation** — any RuntimeException → release Redis stock
   reservations, mark idempotency FAILED (rolled back with the tx), rethrow.
10. **Edge cases** — COD stays `paymentStatus: PENDING`; full-wallet orders
    complete inline (`WALLET-<orderNumber>`); scheduled orders start
    `SCHEDULED` and are promoted by a 60 s poller; a cancelled/refunded
    replayed key returns the original response.

## V.4 Customer order reads & actions

| Endpoint | Purpose | Response |
|---|---|---|
| `GET /orders/customer/my-orders?page&size` | My orders | `PagedResponse<OrderSummaryResponse>` |
| `GET /orders/customer/my-orders/cursor?cursor&size` | Cursor variant | `CursorPagedResponse<...>` |
| `GET /orders/customer/{orderId}` | Detail (ownership-checked) | `ApiResponse<OrderResponse>` + `?fields=` |
| `GET /orders/customer/track/{orderId}` (alias `/{orderId}/track`) | Live ETA snapshot | `ApiResponse<OrderResponse>` (liveEta recalculated); rate-limited `order-track` |
| `POST /orders/customer/{orderId}/reorder` | Rebuild cart from order | `ApiResponse<ReorderResponse>` `{cart, skippedItems[]}` |
| `GET /orders/customer/export/orders?page&size` | CSV download | `text/csv` attachment |
| `PUT /orders/customer/{orderId}/cancel?reason=` | Cancel + refund | `ApiResponse<OrderResponse>` |
| `GET /orders/customer/scheduled-orders` (+ `/cursor`) | Future orders | paged/cursor |
| `PUT /orders/customer/scheduled-orders/{orderId}/cancel?reason=` | Cancel scheduled | `ApiResponse<OrderResponse>` |
| `GET /orders/customer/batch?ids=` | Batch fetch (max 100) | `ApiResponse<Map<Long, OrderResponse>>` |
| `POST /orders/customer/create-batch` | Order for each cart restaurant | body `BatchOrderRequest`; `Idempotency-Key`; `ApiResponse<BatchOrderResponse>` |
| `GET /orders/customer/create/jobs/{jobId}` | Poll async job | `OrderCreateJobResponse` |
| `GET /orders/number/{orderNumber}` | Public lookup | `ApiResponse<OrderResponse>` |

**Errors:** 400 invalid transition/ownership; 404; 403 foreign order;
429 (`track`).

**Behavioral analysis (cancel):** `cancelOrder` requires a cancellable
status; sets `CANCELLED`, restores redeemed loyalty points, and — if the
payment was `COMPLETED` — runs `refundPayment` (wallet portion → wallet
credit, gateway portion → Razorpay refund), emits `ORDER_STATUS_CHANGED` +
SSE, and records `ORDER_REFUNDED` timeline + refund notification. Scheduled
cancels behave identically. Edge case: already-cancelled → 400; refund
failure logged but cancellation still completes.

## V.5 Restaurant operations (RESTAURANT_OWNER)

| Endpoint | Description |
|---|---|
| `GET /orders/restaurant/{restaurantId}?page&size` · `/cursor` | Orders list |
| `GET /orders/restaurant/{restaurantId}/pending?limit=50` | Pending |
| `GET /orders/restaurant/{restaurantId}/kitchen-queue?limit=50` | KDS queue (rate-limited `kitchen-queue`) |
| `PUT /orders/restaurant/{orderId}/accept` | `PLACED → CONFIRMED` |
| `PUT /orders/restaurant/{orderId}/ready` | `READY_FOR_PICKUP` + **auto-assign nearest rider** |
| `PUT /orders/restaurant/{orderId}/assign-delivery?agentId=` | Manual assign |

**Behavioral analysis (lifecycle):** each transition runs
`saveWithStatusChange`: capture previous status → set new → recompute live ETA
→ save (optimistic-lock guarded) → timeline `STATUS_CHANGE` → outbox
`ORDER_STATUS_CHANGED` + cache invalidation → SSE fan-out (status appears on
customer/kitchen/rider streams within the 2 s outbox poll). `ready` triggers
`RiderDispatchService` (nearest available agent by road distance) and emits
`ORDER_AGENT_ASSIGNED` (+ notification). Invalid transitions → 400; wrong
owner → 403/404.

## V.6 Delivery agent actions (DELIVERY_AGENT)

| Endpoint | Description |
|---|---|
| `GET /orders/delivery/my-deliveries?page&size` (+ `/cursor`) | Assigned deliveries |
| `PUT /orders/delivery/{orderId}/picked-up` | `OUT_FOR_DELIVERY` |
| `PUT /orders/delivery/{orderId}/delivered` | `DELIVERED` (proof gate) |
| `POST /orders/delivery/{orderId}/proof/otp` | Issue OTP (SMS only) |
| `POST /orders/delivery/{orderId}/proof/verify` | Verify OTP (+ photo/recipient) |
| `POST /orders/delivery/{orderId}/proof/photo-url` | Presigned photo upload URL |
| `GET /orders/delivery/{orderId}/proof` | Proof state |

**`proof/verify` body:** `otpCode` (required, `^\d{6}$`), `photoKey` (max
512), `recipientName` (max 120), `captureLatitude`, `captureLongitude`,
`notes` (max 1000).
**`proof/photo-url` body:** `{"contentType":"image/jpeg"}` → `{uploadUrl,
photoKey}`.

**`DeliveryProofResponse`:** `id, orderId, orderNumber, proofType(OTP|PHOTO|
OTP_AND_PHOTO|SKIPPED), status(PENDING|VERIFIED|FAILED|SKIPPED), otpIssuedAt,
otpExpiresAt, otpAttemptsRemaining, verifiedAt, photoAvailable, photoUrl,
recipientName, notes, satisfied(boolean — read this, not status),
enforced(boolean), createdAt`. **The OTP is never in the response.**

**Behavioral analysis (delivery proof):**
- All proof endpoints re-check the order is **assigned to the caller** (role
  alone is insufficient).
- `otp` reuses the proof row and replaces the code with a resend cooldown;
  400 if already verified.
- `verify`: wrong code → 400 and **permanently consumes one attempt**
  (anti-brute-force, max attempts → locked `FAILED`); re-verifying an already
  verified order returns the existing state (safe on flaky networks); photo/
  GPS are optional supporting context and feed geo-fraud checks.
- `delivered` requires `satisfied` when `enforced` is true (`SKIPPED` also
  satisfies the gate). On `DELIVERED`: loyalty points earned, rider earnings
  recorded, restaurant settlement created, invoice generated, timeline +
  metrics.

## V.7 Live streams (SSE)

| Endpoint | Role | Purpose |
|---|---|---|
| `GET /orders/stream/kitchen/{restaurantId}` | OWNER | Kitchen queue live |
| `GET /orders/stream/rider` | AGENT | Rider's deliveries live |
| `GET /orders/stream/customer/{orderId}` | CUSTOMER | Tracking (rate-limited) |
| `POST /orders/stream/customer/{orderId}/tracking-token` | CUSTOMER | Guest token → `{trackingToken}` |
| `GET /orders/stream/customer-token/{orderId}?token=` | public | Guest stream (token-gated) |

Wire format + resume semantics in Part I.8. **Behavior:** every event gets a
monotonic `eventId` (Redis `INCR`), is appended to replay ZSETs
(`live:replay:kitchen|order|rider:*`), and is relayed over Redis pub/sub so
**every replica** delivers it to its local SSE/STOMP subscribers — no sticky
sessions needed. Reconnect with `Last-Event-ID` replays missed events.

## V.8 Group orders (CUSTOMER) — /customers/group-orders

| Endpoint | Purpose |
|---|---|
| `POST /customers/group-orders` | Create — `{"title":"Office lunch"}` (max 100) → **201** |
| `POST /customers/group-orders/{id}/invite` | Invite — `{"phone":"9876543210"}` |
| `POST /customers/group-orders/{id}/join` | Join |
| `GET /customers/group-orders/{id}` | Detail |
| `POST /customers/group-orders/{id}/split` | Record split — `{"shares":{"<userId>":250.5}}` |
| `POST /customers/group-orders/{id}/place` | Freeze group |

`GroupOrderResponse`: `id, hostUserId, title, status, createdAt, placedAt,
members[{id, userId, invitePhone, status, amountContribution, paid,
joinedAt}]`.

**Behavior:** group orders are a **bill-splitting layer** — the host's order
is placed through the standard create flow (the shared cart), then
`placeGroupOrder` freezes the group + splits. Split mismatch vs cart subtotal
is logged, not enforced.

## V.9 Gift cards — /gift-cards

| Endpoint | Role | Purpose |
|---|---|---|
| `POST /gift-cards/purchase` | CUSTOMER | `{amount(≥100), recipientEmail, recipientName, message?, expiresAt}` |
| `POST /gift-cards/redeem` | CUSTOMER | `{"code":"GC-XXXX"}` → wallet credit |
| `GET /gift-cards/my-cards` | CUSTOMER | Purchased |
| `GET /gift-cards/received` | CUSTOMER | For me |
| `GET /gift-cards/{code}` | public | Lookup |

`GiftCardResponse`: `id, code, amount, balance, status, recipientEmail,
recipientName, message, expiresAt, createdAt, redeemedAt`.

## V.10 Order assist (CUSTOMER)

| Endpoint | Purpose |
|---|---|
| `POST /customers/orders/{orderId}/rebook` | Rebuild cart from cancelled/refunded order → `ReorderResponse` |
| `GET /customers/surprise-me?restaurantId=` | Curated pick → `MenuItemResponse` (404 if none) |

---

# PART VI — PAYMENTS

Payment statuses: `PENDING, COMPLETED, FAILED, REFUNDED`. Methods:
`CASH_ON_DELIVERY, CREDIT_CARD, DEBIT_CARD, UPI, WALLET, NET_BANKING`.

## VI.1 GET /payments/orders/{orderId}

**Purpose:** payment record for an order — especially `gatewayOrderId`, which
you pass to the Razorpay Checkout SDK to collect the payment.
**Request:** `GET /api/v1/payments/orders/{orderId}` (CUSTOMER).
**Response — 200:** `ApiResponse<PaymentResponse>`

| Field | Type | Description |
|---|---|---|
| `id` | number | Payment id |
| `orderId` | number | Order |
| `paymentMethod` | string | Method |
| `status` | string | Lifecycle state |
| `amount` | number | Total |
| `gatewayOrderId` | string | → payment SDK |
| `gatewayPaymentId` | string \| null | Set after capture |
| `transactionId` | string \| null | Set after completion |
| `purpose` | string | `ORDER` \| `WALLET_TOP_UP` |

**Errors:** 401/403; 404.
**Behavior:** read-only. The **SDK flow** is: create order → fetch
`gatewayOrderId` → open Razorpay Checkout → webhook marks COMPLETED → poll
`payment-status`.

## VI.2 GET /payments/orders/{orderId}/payment-status

**Purpose:** quick status check. **Request:** (CUSTOMER).
**Response — 200:** `ApiResponse<Map>` — `orderId, paymentStatus,
paymentMethod, amount, gatewayOrderId`. **Errors:** 404.

## VI.3 POST /admin/payments/refund/{orderId}

**Purpose:** admin refund via the cancellation refund policy (percent + target
per reason). Idempotent per (order, reason).
**Request:** `POST /api/v1/admin/payments/refund/{orderId}` (ADMIN); optional
body `{"reason":"CUSTOMER_CANCELLED"}`.
**Response — 200:** `ApiResponse<Map>` `{orderId, reason, refunded}`.
**Errors:** 403; 404; 400 no policy applies.
**Behavioral analysis:** policy resolution → Redis `SETNX` double-refund
guard (`bhukkad:autorefund:refund:{orderId}:{reason}`, 24 h) → wallet credit
or gateway refund → payment `REFUNDED` + timeline + notification. A replayed
call returns the same result without a second refund; Redis outage falls back
to a local claim map.

## VI.4 POST /payments/webhooks/razorpay (server-to-server)

**Purpose:** Razorpay's `payment.captured` webhook target: marks the payment
COMPLETED, credits the wallet for top-ups. Public by design — **security is
the HMAC signature**.

**Headers:** `Content-Type: application/json`; `X-Razorpay-Signature`
(required — HMAC-SHA256 of the raw body with the webhook secret, compared
constant-time).

**Body:**

```json
{ "event": "payment.captured",
  "payload": { "payment": { "entity": { "id": "pay_Lxyz", "order_id": "order_Oxyz", "status": "captured" } } } }
```

**Response — 200:** `ApiResponse<BlankResponse>`.
**Errors:** 400 invalid signature/malformed/non-captured event; 429 rate
limit (per IP).

**Behavioral analysis:**
1. Rate limit (`webhook` bucket, per IP, fail-open).
2. Verify signature → 400 + alert on mismatch.
3. Ignore all events except `payment.captured`.
4. **Deduplicate** via unique webhook-event id (`RAZORPAY_WEBHOOK` scope) —
   a redelivery is acked 200 with no side effects.
5. `completeWebhookPayment` — if `purpose=WALLET_TOP_UP` → wallet credit
   `TOP_UP`; else payment → `COMPLETED` + `transactionId`.
6. Enqueue `PAYMENT_WEBHOOK_RECEIVED` outbox record (best-effort). **Known
   gap:** the outbox processor routes only `ORDER_*` event types, so this
   record dead-letters — the money path is already applied transactionally, so
   this is safe but noisy in the DLQ.

## VI.5 Disputes

| Endpoint | Role | Purpose |
|---|---|---|
| `POST /customers/orders/{orderId}/disputes` | CUSTOMER | File — `{type, customerEvidence}` (both required) |
| `GET /customers/disputes` | CUSTOMER | My disputes |
| `GET /admin/disputes` · `GET /admin/disputes/{id}` | ADMIN | List/detail |
| `POST /admin/disputes/{disputeId}/resolve` | ADMIN | `{resolution(FULL_REFUND\|PARTIAL_REFUND\|DISMISSED), refundAmount?, notes?}` |
| `POST /admin/disputes/auto-resolve` | ADMIN | Auto-resolve eligible → `{resolved: count}` |

`DisputeResponse`: `id, orderId, orderNumber, type, status, customerEvidence,
riderEvidence, restaurantEvidence, resolutionNotes, resolution, refundAmount,
resolvedBy, resolvedAt, createdAt`.

---

# PART VII — DELIVERY

All endpoints require `DELIVERY_AGENT` (base `/api/v1/delivery`) unless noted.
`DeliveryAgentResponse`: `id, fullName, email, phoneNumber, vehicleType,
vehicleNumber, available, verified, averageRating, totalDeliveries, role`.

## VII.1 Earnings

| Endpoint | Purpose | Response |
|---|---|---|
| `GET /delivery/earnings/summary` | Headline numbers | `RiderEarningsSummaryResponse` — `pendingAmount, paidAmount, perDeliveryFee, totalDeliveries` |
| `GET /delivery/earnings?page&size` | Paged history | `PagedResponse<RiderPayoutResponse>` |
| `GET /delivery/earnings/cursor?cursor&size` | Cursor history | `CursorPagedResponse<RiderPayoutResponse>` |

`RiderPayoutResponse`: `id, orderId, orderNumber, amount, status(PENDING|PAID),
createdAt, paidAt`.

## VII.2 Profile & availability

| Endpoint | Purpose |
|---|---|
| `GET /delivery/profile` | `ApiResponse<DeliveryAgentResponse>` |
| `PUT /delivery/profile` | Partial update body (vehicleType, vehicleNumber, available, coords, contact fields) → `ApiResponse<DeliveryAgentResponse>` |
| `PUT /delivery/toggle-availability?available=` | `ApiResponse<Void>` |
| `PUT /delivery/update-location?latitude=&longitude=` | `ApiResponse<Void>`; also pushes position to customers tracking an in-flight order (live ETA recompute + SSE) |

## VII.3 Orders

| Endpoint | Purpose |
|---|---|
| `GET /delivery/available-orders` | `ApiResponse<OrderResponse[]>` |
| `GET /delivery/active-deliveries` | `ApiResponse<OrderResponse[]>` |
| `GET /delivery/delivery-history` | `ApiResponse<OrderResponse[]>` |
| `POST /delivery/{orderId}/accept` | Claim → `ApiResponse<OrderResponse>` |
| `POST /delivery/{orderId}/reject` | Decline → `ApiResponse<OrderResponse>` |
| `POST /delivery/orders/{orderId}/location` | GPS ping — body `{latitude, longitude}` (both required) → `RiderLocationResponse` `{orderId, agentId, latitude, longitude, recordedAt}` |

**Behavior (location ping):** persists the fix, recomputes live ETA
(status + traffic factor by hour + zone surge + busy-mode prep), and streams
`RIDER_LOCATION` to the customer's SSE stream via the Redis relay.

## VII.4 Batches

| Endpoint | Purpose |
|---|---|
| `POST /delivery/batches` | Create batch → `RiderBatchResponse` |
| `GET /delivery/batches/active` | Active batch |
| `PUT /delivery/batches/{batchId}/complete` | Complete |

`RiderBatchResponse`: `batchId, agentId, status, orders[{orderId,
orderNumber, sequenceNumber, status}], createdAt, completedAt`.

## VII.5 Delivery truth (ETA transparency)

### GET /delivery-truth/orders/{orderId}/eta
**Purpose:** auditable ETA breakdown (traffic, surge, confidence band,
history). Any authenticated role.
**Request:** `GET /api/v1/delivery-truth/orders/{orderId}/eta`.
**Response — 200:** `ApiResponse<OrderEtaDetailResponse>` — `orderId,
etaMinutes, etaAt, confidenceLowMinutes, confidenceHighMinutes,
trafficFactor, surgeMultiplier, factorsSummary, history[{etaMinutes, etaAt,
recordedAt}]`.
**Errors:** 404; 403.

---

# PART VIII — REVIEWS

Base `/api/v1/reviews`. Public reads; writes require CUSTOMER.

## VIII.1 POST /reviews

**Purpose:** review a delivered order; feeds the restaurant's average rating.
New reviews start `PENDING` moderation.

**Request:**

```json
{ "orderId": 4242, "rating": 4, "comment": "Great butter chicken",
  "foodRating": 5, "deliveryRating": 3, "images": ["https://cdn.example.com/review/1.jpg"] }
```

| Field | Type | Required | Constraints |
|---|---|---|---|
| `orderId` | number | Yes | delivered order |
| `rating` | number | Yes | 1–5 |
| `comment` | string | No | — |
| `foodRating` | number | No | 1–5 |
| `deliveryRating` | number | No | 1–5 |
| `images` | string[] | No | URLs |

**Response — 200:** `ApiResponse<Review>` — `id, rating, comment,
foodRating, deliveryRating, images[], moderationStatus(PENDING|APPROVED|REJECTED),
ownerResponse, createdAt` (+ nested customer/restaurant/order).
**Errors:** 400 rating out of range / order not delivered / already reviewed /
not owned; 403; 404.
**Behavior:** single review per (customer, order); the review's rating drives
`restaurant_ratings_summary` (materialized, 5-min refresh) and
`menu_item_ratings`.

## VIII.2 Reads

| Endpoint | Role | Response |
|---|---|---|
| `GET /reviews/restaurant/{restaurantId}` | public | `ApiResponse<Review[]>` (approved) |
| `GET /reviews/menu-items/{menuItemId}` | public | `ApiResponse<MenuItemRatingResponse[]>` |
| `GET /reviews/my-reviews` | CUSTOMER | `ApiResponse<Review[]>` |
| `GET /reviews/order/{orderId}` | CUSTOMER | `ApiResponse<Review>` |
| `DELETE /reviews/{reviewId}` | CUSTOMER (author) | `ApiResponse<Void>` |

## VIII.3 POST /reviews/menu-items

**Purpose:** rate a specific menu item from a delivered order.
**Request:** `{orderId, menuItemId, rating(1–5), comment?}` (orderId,
menuItemId, rating required).
**Response — 200:** `ApiResponse<MenuItemRatingResponse>` — `{id, menuItemId,
orderId, rating, comment, createdAt}`.
**Errors:** 400 item not in order / not delivered / invalid rating.

---

# PART IX — ADMIN

All endpoints require `ADMIN`. Dashboards read from the read replica.

## IX.1 Dashboard & platform

### GET /admin/dashboard
**Purpose:** platform headline numbers — the "admin app open" call.
**Response — 200:** `ApiResponse<Map>`: `totalUsers, totalCustomers,
totalOwners, totalAgents, totalRestaurants, activeRestaurants, totalOrders,
todayOrders, totalRevenue, todayRevenue, recentOrders[{id, orderNumber,
status, totalAmount, createdAt}]`.

### GET /admin/revenue?days=7
`ApiResponse<Map>`: `period, totalRevenue, totalOrders, deliveredOrders,
cancelledOrders, averageOrderValue`.

### GET /admin/analytics
`ApiResponse<Map>`: `ordersByStatus(Map), usersByRole(Map),
topRestaurants[{id, name, isActive, isOpen, averageRating, totalReviews,
createdAt}]`.

### GET /admin/operations-dashboard
`AdminOperationsDashboardResponse`: `totalPendingRestaurantSettlements,
totalPendingRiderPayouts, pendingSettlementCount, pendingPayoutCount,
activeDeliveryBatches, todaySettlementVolume, ordersByStatus,
recentSettlementRuns, etaAccuracy`.

## IX.2 Users

| Endpoint | Purpose |
|---|---|
| `GET /admin/users?page&size&role&search` | Paged/filtered — `{users[], totalElements, totalPages, currentPage, size}` |
| `PUT /admin/users/{userId}/activate` · `/deactivate` | Toggle |
| `PUT /admin/owners/{ownerId}/verify` · `PUT /admin/agents/{agentId}/verify` | Verify identity |

**Behavior:** deactivation takes effect immediately (roles are DB-loaded per
request); activation restores access.

## IX.3 Orders & restaurants

| Endpoint | Purpose |
|---|---|
| `GET /admin/orders?page&size&status` | Paged/filtered — `{orders[], totalElements, totalPages, currentPage}` |
| `GET /admin/restaurants?page&size&active` | Paged/filtered |
| `PUT /admin/restaurants/{id}/approve` · `/suspend` | Toggle |
| `PUT /admin/restaurants/{id}/onboarding` | `{approved, reason?}` (reason required when rejecting) |
| `PUT /admin/restaurants/{id}/commission?percent=` | 0–100 |
| `PUT /admin/restaurants/{id}/settle-payouts` | `{restaurantId, settledCount}` |
| `PUT /admin/agents/{agentId}/settle-payouts` | `{agentId, settledCount}` |

## IX.4 Settlements, zones, cities, promotions

| Endpoint | Purpose |
|---|---|
| `POST /admin/settlements/run` | `{runId, status, restaurantsSettled, agentsSettled, totalAmount}` |
| `GET/POST /admin/zones` · `PUT/DELETE /admin/zones/{id}` | Delivery zones (radiusKm, fees, surge) |
| `GET/POST /admin/cities` · `PUT/DELETE /admin/cities/{id}` | City config (currency, timezone, serviceable) |
| `GET/POST /admin/promotions/campaigns` · `PUT/DELETE .../{id}` | Campaigns |
| `GET/POST /admin/promotions/banners` · `PUT .../{id}` | Banners |

## IX.5 Feature flags (kill-switch)

### GET /admin/feature-flags
**Purpose:** all flags + effective values (config default ⊕ runtime override).
**Response — 200:** `ApiResponse<Map<String, Boolean>>`

```json
{ "success": true, "data": { "checkout.new": true, "search-provider-es": false } }
```

### GET /admin/feature-flags/{key}
Single value → `ApiResponse<Boolean>`; 404 unknown.

### PUT /admin/feature-flags/{key}?value=false
**Purpose:** toggle a runtime override (`value` omitted → revert to default).
**Response — 200:** `ApiResponse<Boolean>` (new effective value).
**Behavioral analysis:** writes `bhukkad:feature-flag:overrides` Redis hash,
then **pub/sub invalidates** every replica's local cache — the flip is
effective cluster-wide within ~100 ms. This is the production kill-switch.

## IX.6 API keys (partners)

| Endpoint | Purpose |
|---|---|
| `POST /admin/api-keys` | Create — `{name, partnerId, scopes, expiresAt}` → `{id, name, apiKey(plaintext once), scopes, expiresAt}` |
| `GET /admin/api-keys` | List (`keyPrefix` only, never the secret) |
| `DELETE /admin/api-keys/{id}` | Revoke |
| `POST /admin/api-keys/{id}/rotate` | New secret, same metadata |

**Behavior:** only the SHA-256 hash of the key is stored; the plaintext
(`bhk_<6-char-prefix>_<24-char-secret>`) is returned exactly once. Clients
send it as `X-API-Key`; `ApiKeyFilter` authenticates as `ROLE_PARTNER`.

## IX.7 Dead-letter queue (outbox DLQ)

| Endpoint | Purpose |
|---|---|
| `GET /admin/outbox/dlq?limit=20` | `DeadLetterEventResponse[]` |
| `GET /admin/outbox/dlq/pending/count` | `ApiResponse<number>` |
| `GET /admin/outbox/dlq/{id}` | Detail |
| `POST /admin/outbox/dlq/{id}/requeue` | Re-drive into outbox |

`DeadLetterEventResponse`: `id, eventType, aggregateType, aggregateId,
payload(truncated 2000), lastError, retryCount, source, status,
createdAt, requeuedAt`.

## IX.8 Fraud, churn, support, moderation

| Endpoint | Purpose |
|---|---|
| `GET /admin/fraud/dashboard` | `FraudDashboardResponse` (counts, eventsByType, topIPs, topDevices, recentEvents) |
| `GET /admin/fraud/events` | `FraudEventResponse[]` |
| `GET /admin/fraud/review-queue` | Manual reviews |
| `POST /admin/fraud/review-queue/{eventId}/action` | `{action, notes?}` |
| `GET /admin/churn/high-risk` | `ChurnScore[]` |
| `POST /admin/churn/rescore/{userId}` | On-demand rescore |
| `GET /admin/support/tickets` · `PUT .../{id}/status?status=&resolutionNotes=` | Support tickets |
| `GET /admin/reviews/moderation?status=` · `PUT /admin/reviews/{id}/moderate?status=` | Review moderation |
| `POST /admin/notifications/test` | `{channel, recipient, message}` |
| `GET /admin/experiments` · `/commission-tiers` · `/dynamic-pricing-rules` · `/inventory-alerts` · `/surveys` · `/tenants` · `/compliance` · `/analytics/export` · `/scale` · `/cache` | Operational/admin utilities |

---

# PART X — CROSS-CUTTING BEHAVIOR

## X.1 Outbox → events (eventual consistency)

All significant writes (order placed, status changed, agent assigned, items
snapshot) insert an `outbox_events` row **in the same DB transaction**.
`OutboxEventProcessor` polls every 2 s (batch 50), republishes each event as a
Spring event (→ SSE/notifications), and forwards to Kafka/Redpanda when
`app.events.external.enabled` (with W3C `traceparent` header). Failures retry
3× then move to the DLQ (`dead_letter_events`), which admins can requeue.
**Known gap:** `PAYMENT_WEBHOOK_RECEIVED` is not routable by the processor —
it dead-letters (the money path is already applied, so this is safe).

## X.2 Saga (Phase 3 scaffolding)

`saga_instances` / `saga_steps` + `SagaCoordinator`: forward steps with
per-step compensation executed in **reverse order** on failure; terminal-state
replay is a no-op; an in-flight saga with the same id is rejected. This is the
replacement for distributed transactions (no 2PC).

## X.3 Read/write routing

`@UseReadReplica` or `@Transactional(readOnly = true)` → connection from a
round-robin-selected read replica (N replicas, Phase 1); read-write → primary.
Hot reads (home feed, menu, search, restaurant list, admin dashboards,
reviews, earnings) are replica-routed; writes always hit the primary.

## X.4 Caching

Cache-aside with TTL jitter ±10% + probabilistic early-expiry (stampede
protection); Redis `SETNX` single-flight lock across replicas; pub/sub
invalidation on writes; per-domain keyspaces (`bhukkad:orders:*`,
`bhukkad:restaurant:*`, `bhukkad:search:*`).

## X.5 Async tiers

`orderTaskExecutor` (core 4 / max 16 / queue 200) for order-path fan-out;
`lowPriorityTaskExecutor` (core 2 / max 4 / queue 100) for analytics
materialization (e.g. trending dishes). Both propagate MDC trace ids.

## X.6 Metrics & SLO

`EndpointSloMetrics` writes `bhukkad.http.requests` (timer) +
`bhukkad.http.errors` (counter) → `SloMonitorService` computes per-replica p95
+ error-budget burn rate and raises Redis-deduped alerts (a per-replica window
+ cluster-wide dedup). Prometheus at `/actuator/prometheus`; Grafana
dashboards group by `application`.

## X.7 Resilience

Resilience4j circuit breaker + retry on the payment gateway (3 attempts,
exponential backoff + jitter, 50% failure threshold, 30 s open). Graceful
degradation: Redis down → caches fall back, rate limiting fails open, BNPL
fails closed, tracking tokens fail closed. Outbox and dunning schedulers are
ShedLock-guarded (single-replica execution).

---

*End of manual. Verified against the codebase; where the implementation
diverges from an ideal design (unwired BNPL strategy, unroutable webhook
outbox event, overlapping scheduled-order pollers, validation gaps on some
admin/affiliate path params), the divergence is documented inline.*





