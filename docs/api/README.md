# Bhukkad API — Frontend Integration Guide

This is the single source of truth for frontend engineers integrating with the
Bhukkad platform. Every endpoint is documented with its exact request and
response shapes in the files below. Read this guide first — it defines the
conventions every endpoint follows.

**Base URL:** `https://<host>/api/v1`

| File | Content |
|---|---|
| [`auth.md`](./auth.md) | Registration, login, MFA, tokens, passwords |
| [`account.md`](./account.md) | Profile, addresses, wallet, loyalty, referrals, favorites, subscriptions |
| [`discovery.md`](./discovery.md) | Home feed, search, restaurants, menu, cuisines, coupons |
| [`orders.md`](./orders.md) | Cart, order create/track/cancel, restaurant & delivery actions, proof, group orders, gift cards, SSE live streams |
| [`payments.md`](./payments.md) | Payment status, webhooks (server-to-server), refunds, disputes |
| [`delivery.md`](./delivery.md) | Delivery agent: earnings, availability, location, accept/reject, batches |
| [`reviews.md`](./reviews.md) | Order reviews, menu-item ratings |
| [`admin.md`](./admin.md) | Admin: dashboards, users, restaurants, settlements, feature flags, fraud, DLQ, etc. |

---

## 1. Authentication model

- **Access token** (JWT, HS256): valid **24 hours**. Send as
  `Authorization: Bearer <token>`.
- **Refresh token** (JWT): valid **7 days**. **Rotation**: every
  `POST /auth/refresh-token` invalidates the old refresh token and issues a
  new pair. Store both; never store in localStorage if you can avoid it
  (use HttpOnly cookies or a secure storage).
- **Roles**: `CUSTOMER`, `RESTAURANT_OWNER`, `DELIVERY_AGENT`, `ADMIN`
  (and `PARTNER` for API keys). Roles are loaded server-side per request —
  a role change or account deactivation takes effect immediately.
- **MFA**: `ADMIN` / `RESTAURANT_OWNER` accounts with TOTP enabled get
  `mfaRequired: true` on login and must call `POST /auth/mfa/verify` before
  receiving tokens.

### Token refresh pattern

```js
// Auto-refresh when the API returns 401 and the access token is expired.
async function authenticatedFetch(path, options = {}) {
  const res = await fetch(`${BASE}/v1${path}`, {
    ...options,
    headers: { Authorization: `Bearer ${accessToken}`, ...(options.headers || {}) },
  });
  if (res.status === 401) {
    const ok = await refreshSession();           // POST /auth/refresh-token
    if (ok) return authenticatedFetch(path, options);
  }
  return res;
}
```

---

## 2. Response envelope (every endpoint)

```json
{
  "success": true,
  "message": "Order placed successfully",   // optional; null on plain success
  "data": { "...": "endpoint payload" },
  "timestamp": "2026-08-24T21:00:00",
  "traceId": "8060e534c23630e6a1393f746a07029d",
  "spanId": "09c46cd3bd00279e",
  "requestId": "862681db"
}
```

- Read `success` before `data`. `data` may be `null` on `success=false`.
- On errors, `message` is safe to display to users (no internals are leaked).

---

## 3. Headers

| Header | Required | Format / Purpose |
|---|---|---|
| `Authorization` | For authenticated endpoints | `Bearer <accessToken>` |
| `Content-Type` | When sending a body | `application/json` |
| `Idempotency-Key` | Order create / batch / wallet top-up | Your client-generated UUID. **Replaying the same key returns the stored response** (safe retry after network failure). |
| `Accept-Version` / `X-API-Version` | Optional | Defaults to `1`. `0` → `400`. Deprecated versions get a `Warning` header. Response always carries `X-API-Version`. |
| `X-Device-Fingerprint` | Optional (recommended for auth/order) | Stable device id used by fraud velocity checks. |
| `Last-Event-ID` | SSE streams | Resume missed events after a reconnect. |
| `X-API-Key` | Partner/integration only | `bhk_<6-char-prefix>_<24-char-secret>`. |
| `If-None-Match` / `If-Modified-Since` | Public GET (restaurants, menu, categories) | HTTP conditional caching; server returns `304 Not Modified`. |

---

## 4. Error handling

### 4.1 Status codes

| Code | Meaning | Frontend action |
|---|---|---|
| `200` / `201` / `202` | Success / created / async accepted | Render payload |
| `400` | Validation error, WAF block, unsupported version, bad OTP | Show `message`; fix input |
| `401` | Missing/expired/revoked token, bad credentials | Run refresh flow → if that fails, force login |
| `403` | Authenticated but wrong role, or foreign resource | Show "not permitted"; route by role |
| `404` | Resource not found | Show not-found state |
| `409` | Conflict (duplicate in-flight order) | Surface "already being processed"; poll or fetch order |
| `429` | Rate limited or fraud-blocked | Read `Retry-After` header (seconds); back off |
| `500` | Server error | Show generic error; auto-retry with backoff for GET |

### 4.2 Error body

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

### 4.3 Common errors per domain

| Scenario | Endpoint | Code + message |
|---|---|---|
| Wrong credentials | `POST /auth/login` | `401` "Invalid email or password" |
| Email taken | `POST /auth/register` | `400` "Email already exists" |
| Expired refresh | `POST /auth/refresh-token` | `401` "Refresh token revoked or expired" |
| Anonymous register flood | `POST /auth/register` | `429` + `Retry-After` |
| Restaurant closed/busy | `POST /orders/customer/create` | `400` "Restaurant is not accepting orders" |
| Insufficient stock | `POST /orders/customer/create` | `400` "Insufficient stock" |
| Duplicate in-flight | `POST /orders/customer/create` | `409` "Duplicate order request is already being processed" |
| Payment failed | `POST /orders/customer/create` | `400` "Payment failed" |
| Foreign order access | `GET /orders/customer/{id}` | `403`/`404` |
| Bad OTP | `POST /orders/delivery/{id}/proof/verify` | `400` (consumes one attempt) |
| Invalid tracking token | `GET /orders/stream/customer-token/{id}` | `401` |

---

## 5. Rate limits (client guidance)

Bursts above these get `429` + `Retry-After`. Treat them as hard ceilings.

| Bucket | Limit | Used by |
|---|---|---|
| `auth-register` | 10 / 60s | `POST /auth/register` |
| `auth-login` | 10 / 60s | `POST /auth/login`, `POST /auth/mfa/verify` |
| `cart-mutation` | 40 / 60s | All cart write endpoints |
| `order-track` | 20 / 60s | Track, customer SSE, tracking tokens |
| `kitchen-queue` | 30 / 60s | `GET /orders/restaurant/{id}/kitchen-queue` |
| `search` | 30 / 60s | Search, suggest, restaurant search/nearby, menu search |
| `mobile-feed` | 30 / 60s | `GET /mobile/feed` |
| `webhook` | 120 / 60s | Razorpay webhook (server-side) |

---

## 6. Pagination

Two styles; never mix them on one endpoint.

**Offset:** query `page` (0-based) + `size` (default 20, max 100).

```json
{
  "items": [ "...array of items..." ],
  "page": 0,
  "size": 20,
  "totalElements": 134,
  "totalPages": 7,
  "hasNext": true
}
```

**Cursor:** query `cursor` (opaque; omit for first page) + `size`.

```json
{
  "items": [ "..." ],
  "nextCursor": "eyJvZmZzZXQiOjIwfQ",
  "hasNext": true,
  "size": 20
}
```

> Prefer cursor endpoints for order lists, earnings, and settlements.

---

## 7. Field projection

Order detail endpoints accept `?fields=id,orderNumber,status,totalAmount`
(comma-separated). Unknown fields are ignored; blank = full payload. The
response is still wrapped in `ApiResponse`; only `data` is filtered.

---

## 8. Server-Sent Events (live order tracking)

```js
const es = new EventSource(
  `${BASE}/api/v1/orders/stream/customer/${orderId}`,
  { headers: { Authorization: `Bearer ${accessToken}` } }
);
```

| Event name | Data |
|---|---|
| `connected` | `{"channel":"order:4242","id":0}` — connection established |
| `order-update` | An `OrderLiveUpdate` (see below) |
| `order-snapshot` | Full `OrderResponse` sent once on connect |
| comment lines `:heartbeat` | Keep-alive (25s interval) |

```json
{
  "eventType": "STATUS_CHANGED",          // ORDER_CREATED | STATUS_CHANGED | AGENT_ASSIGNED | RIDER_LOCATION
  "eventId": 88231,
  "orderId": 4242,
  "orderNumber": "ORD-3f8a21c4",
  "customerId": 101,
  "restaurantId": 12,
  "deliveryAgentId": 77,
  "previousStatus": "READY_FOR_PICKUP",
  "status": "OUT_FOR_DELIVERY",
  "changedAt": "2026-08-24T21:31:00",
  "liveEtaMinutes": 12,
  "liveEtaAt": "2026-08-24T21:43:00",
  "latitude": 12.9716,
  "longitude": 77.5946
}
```

**Reconnect/resume:** send `Last-Event-ID: <lastEventId>` — the server replays
missed `order-update` events. Anonymous tracking: `POST
/orders/stream/customer/{orderId}/tracking-token` (as the customer) then
`GET /orders/stream/customer-token/{orderId}?token=<token>` (no auth header).

---

## 9. Idempotency (safe retries)

For `POST /orders/customer/create`, `POST /orders/customer/create-batch` and
`POST /customers/wallet/top-up`, generate one `Idempotency-Key` per logical
operation **before** the first attempt and reuse it on retries:

```js
async function placeOrder(payload) {
  const key = crypto.randomUUID();                 // create ONCE
  for (let attempt = 0; attempt < 3; attempt++) {
    const res = await fetch(`${BASE}/api/v1/orders/customer/create`, {
      method: "POST",
      headers: { "Content-Type": "application/json",
                 "Idempotency-Key": key,
                 Authorization: `Bearer ${accessToken}` },
      body: JSON.stringify(payload),
    });
    if (res.status < 500 && res.status !== 429) return res;  // terminal
    await sleep(2 ** attempt * 250);
  }
}
```

A replayed key returns the **original** order (200), never a duplicate.

---

## 10. Quick-start flows

### Customer app
```
POST /auth/login                         → tokens
GET  /home/feed                          → banners, campaigns, plans, trending
GET  /restaurants/public/nearby?lat&lng  → restaurants
GET  /menu/items/restaurant/{id}         → menu
POST /cart/add                           → add item
POST /orders/customer/create             → place order (+ Idempotency-Key)
GET  /orders/stream/customer/{id}        → live tracking (SSE)
PUT  /orders/customer/{id}/cancel        → cancel (+ refund)
POST /reviews                            → review after delivery
```

### Restaurant owner app
```
POST /auth/login → GET /restaurants/owner/my-restaurants
GET  /orders/restaurant/{id}/kitchen-queue
PUT  /orders/restaurant/{orderId}/accept
PUT  /orders/restaurant/{orderId}/ready
GET  /orders/stream/kitchen/{restaurantId}   (SSE)
PUT  /restaurants/owner/{id}/toggle-status?isOpen=true
```

### Delivery agent app
```
POST /auth/login → PUT /delivery/toggle-availability?available=true
PUT  /delivery/update-location?latitude&longitude
GET  /delivery/available-orders
POST /delivery/{orderId}/accept
POST /delivery/orders/{orderId}/location      (GPS pings)
POST /orders/delivery/{orderId}/proof/otp
POST /orders/delivery/{orderId}/proof/verify  {otpCode}
PUT  /orders/delivery/{orderId}/delivered
GET  /orders/stream/rider                     (SSE)
```

### Admin console
```
POST /auth/login (+ MFA) → GET /admin/dashboard
GET  /admin/users?role=&search= → PUT /admin/users/{id}/deactivate
GET  /admin/orders?status= → GET /admin/revenue?days=30
GET  /admin/outbox/dlq → POST /admin/outbox/dlq/{id}/requeue
PUT  /admin/feature-flags/{key}?value=false   (kill-switch)
```
