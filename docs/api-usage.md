# API Usage Guide

REST API reference for integrators. All paths use prefix **`/api/v1`**.

Interactive docs: **Swagger UI** at `/swagger-ui.html` (dev only).

## Response format

Successful responses wrap data in `ApiResponse`:

```json
{
  "success": true,
  "message": "Login successful",
  "data": { ... },
  "timestamp": "2026-08-14T18:00:00"
}
```

Errors return appropriate HTTP status with `success: false` and a message.

## Authentication

### Register

```http
POST /api/v1/auth/register
Content-Type: application/json

{
  "fullName": "Jane Doe",
  "email": "jane@example.com",
  "password": "secret123",
  "phoneNumber": "9876543210",
  "role": "CUSTOMER"
}
```

Roles: `CUSTOMER`, `RESTAURANT_OWNER`, `DELIVERY_AGENT`, `ADMIN`.

### Login

```http
POST /api/v1/auth/login
Content-Type: application/json

{
  "email": "jane@example.com",
  "password": "secret123"
}
```

Response includes `accessToken` and `refreshToken`.

### Using the token

```http
Authorization: Bearer <accessToken>
```

### Refresh

```http
POST /api/v1/auth/refresh-token
Authorization: Bearer <refreshToken>
```

### Logout

```http
POST /api/v1/auth/logout
Authorization: Bearer <accessToken>
```

## Public endpoints (no auth)

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/v1/health/ping` | Liveness ping |
| GET | `/api/v1/health` | Basic health |
| GET | `/api/v1/platform/status` | Kafka, cache, GEO, stock, notification flags |
| GET | `/api/v1/serviceability/check` | Zone serviceability + delivery fee estimate |
| GET | `/api/v1/home/banners` | Home feed promo banners |
| GET | `/api/v1/home/campaigns` | Active promotion campaigns |
| GET | `/api/v1/home/membership-plans` | Membership plans |
| GET | `/api/v1/home/feed` | Combined home feed |
| POST | `/api/v1/auth/register` | Register |
| POST | `/api/v1/auth/login` | Login |
| GET | `/api/v1/restaurants/public` | List restaurants |
| GET | `/api/v1/restaurants/public/{id}` | Restaurant detail |
| GET | `/api/v1/restaurants/public/search` | Search |
| GET | `/api/v1/restaurants/public/nearby` | Geo search |
| GET | `/api/v1/search` | Unified restaurant + menu search |
| GET | `/api/v1/menu/items/**` | Menu (GET only) |
| GET | `/api/v1/cuisines/**` | Cuisines |
| GET | `/api/v1/reviews/restaurant/{id}` | Restaurant reviews |
| GET | `/api/v1/coupons/active` | Active coupons |

Legacy `/api/**` URLs are rewritten to `/api/v1/**`.

## Customer flow

### 1. Profile & addresses

```http
GET  /api/v1/customers/profile
PUT  /api/v1/customers/profile?fullName=...
GET  /api/v1/customers/orders/stats
GET  /api/v1/customers/notification-preferences
PUT  /api/v1/customers/notification-preferences   # email, sms, whatsapp, push, orderUpdates, promotions
POST /api/v1/customers/addresses
GET  /api/v1/customers/addresses
```

### 2. Cart (multi-restaurant supported)

```http
GET    /api/v1/cart
POST   /api/v1/cart/add
PUT    /api/v1/cart/items/{cartItemId}?quantity=2
DELETE /api/v1/cart/items/{cartItemId}
DELETE /api/v1/cart/restaurant/{restaurantId}
DELETE /api/v1/cart/clear
```

`CartResponse` includes `restaurantCarts[]` grouped by restaurant.

### 3. Create order

```http
POST /api/v1/orders/customer/create
Authorization: Bearer <token>
Idempotency-Key: <optional-uuid>
Content-Type: application/json

{
  "restaurantId": 1,
  "deliveryAddressId": 1,
  "paymentMethod": "UPI",
  "couponCode": "SAVE10",
  "loyaltyPointsToRedeem": 100,
  "walletAmountToUse": 50,
  "useWallet": true,
  "specialInstructions": "No onions",
  "contactlessDelivery": false,
  "tipAmount": 20.0,
  "scheduledAt": "2026-08-15T14:30:00"
}
```

**Scheduled orders:** Set `scheduledAt` (ISO-8601) at least 30 minutes ahead. Order starts in `SCHEDULED` status until auto-dispatched.

**Rider tip:** Optional `tipAmount` is added to the order total.

**Payment methods:** `CASH_ON_DELIVERY`, `UPI`, `CREDIT_CARD`, `DEBIT_CARD`, `WALLET`, `NET_BANKING` (also `COD` → COD).

**Split pay:** Set `walletAmountToUse` or `useWallet: true` with `UPI`/`CARD` to pay remainder via gateway.

**Async create:** `POST .../create?async=true` returns `202` with job ID; poll job status endpoint.

### 4. Track order

```http
GET /api/v1/orders/customer/{orderId}
GET /api/v1/orders/customer/track/{orderId}
GET /api/v1/orders/customer/{orderId}/track
```

`track` returns `liveEtaMinutes` and `liveEtaAt` recalculated from order status and rider location.
Both track URI forms are mapped to the same handler; `/customer/track/{orderId}` is canonical and
`/customer/{orderId}/track` is kept as a compatibility alias.

Ownership is enforced server-side and the tracking cache entry is scoped to the requesting customer,
so tracking another customer's order returns `401` rather than that customer's data. Track is rate
limited (bucket `order-track`); exceeding it returns `429` with `Retry-After`.

### 5. Live SSE (customer)

```http
GET /api/v1/orders/stream/customer/{orderId}
Accept: text/event-stream
Authorization: Bearer <token>
Last-Event-ID: <optional-for-replay>
```

### 6. Reorder

```http
POST /api/v1/orders/customer/{orderId}/reorder
```

### 7. Wallet

```http
GET  /api/v1/customers/wallet/balance
POST /api/v1/customers/wallet/top-up?amount=500
     # Returns Razorpay gatewayOrderId when Razorpay enabled
GET  /api/v1/payments/orders/{orderId}
```

### 8. Push notifications

```http
POST   /api/v1/customers/device-tokens
DELETE /api/v1/customers/device-tokens?token=...
```

Body: `{ "token": "...", "platform": "ANDROID" }` — platforms: `ANDROID`, `IOS`, `WEB`.

## Restaurant owner flow

```http
POST /api/v1/restaurants/owner
GET  /api/v1/restaurants/owner/my-restaurants
PUT  /api/v1/restaurants/owner/{id}
GET  /api/v1/restaurants/owner/{id}/analytics?days=30
GET  /api/v1/restaurants/owner/{id}/settlements?page=0&size=20

POST /api/v1/menu/categories
POST /api/v1/menu/items
POST /api/v1/menu/items/{id}/image/upload-url

GET  /api/v1/orders/restaurant/{restaurantId}
PUT  /api/v1/orders/restaurant/{orderId}/status?status=CONFIRMED

GET  /api/v1/orders/stream/kitchen/{restaurantId}   # SSE
```

## Delivery agent flow

```http
PUT  /api/v1/delivery/toggle-availability?available=true
PUT  /api/v1/delivery/update-location?latitude=12.97&longitude=77.59
GET  /api/v1/delivery/available-orders
POST /api/v1/delivery/{orderId}/accept
POST /api/v1/delivery/{orderId}/reject
PUT  /api/v1/orders/delivery/{orderId}/status?status=OUT_FOR_DELIVERY
PUT  /api/v1/orders/delivery/{orderId}/delivered

# Delivery proof of handover (V17). The OTP plaintext is sent to the customer by SMS
# and is never returned by any endpoint, so proof/verify needs the code from the customer.
POST /api/v1/orders/delivery/{orderId}/proof/otp         # issue or resend the handover OTP
POST /api/v1/orders/delivery/{orderId}/proof/verify      # { "otpCode": "123456", "recipientName": "...", "photoKey?": "...", "captureLatitude?": 0.0, "captureLongitude?": 0.0, "notes?": "..." }
POST /api/v1/orders/delivery/{orderId}/proof/photo-url   # presigned upload target for the handover photo
GET  /api/v1/orders/delivery/{orderId}/proof             # current proof state (no OTP field)

GET  /api/v1/delivery/earnings/summary
GET  /api/v1/delivery/earnings?page=0&size=20

GET  /api/v1/orders/stream/rider   # SSE
```

## Admin

```http
GET /api/v1/admin/dashboard
GET /api/v1/admin/analytics
PUT /api/v1/admin/agents/{agentId}/settle-payouts
PUT /api/v1/admin/restaurants/{restaurantId}/settle-payouts
PUT /api/v1/admin/restaurants/{restaurantId}/commission?percent=12.5
POST /api/v1/admin/notifications/test   # body: { "channel": "email|sms|whatsapp", "recipient": "...", "message": "..." }
# Review moderation (V17). PENDING and REJECTED reviews are hidden from public reads
# and excluded from aggregate restaurant ratings.
GET /api/v1/admin/reviews/moderation?status=PENDING          # status is optional
PUT /api/v1/admin/reviews/{reviewId}/moderate?status=APPROVED # status is required: APPROVED | REJECTED | PENDING
# Cache management (ADMIN only, or public if app.debug=true)
```

See [advanced-features.md](./advanced-features.md) for referrals, batch checkout, scheduled orders, and settlements.

See [growth-features.md](./growth-features.md) for V13: serviceability, membership, support tickets, order timeline, invoices, and busy mode.

See [delivery-truth.md](./delivery-truth.md), [promotions-engine.md](./promotions-engine.md), and [scale-operations.md](./scale-operations.md) for V14–V16 features.

See [trust-and-compliance.md](./trust-and-compliance.md) for V17: GST invoice PDFs, fraud enforcement, review moderation, delivery proof, ETA accuracy, and read-path caching.

### Growth endpoints (customer)

```http
GET  /api/v1/customers/wallet/transactions?page=0&size=20
GET  /api/v1/customers/membership/plans
GET  /api/v1/customers/membership/status
POST /api/v1/customers/membership/subscribe   # { "planId": 1 }
POST /api/v1/customers/support/tickets        # { "category", "subject", "description", "orderId?" }
GET  /api/v1/customers/support/tickets
GET  /api/v1/orders/{orderId}/timeline
GET  /api/v1/orders/{orderId}/invoice
GET  /api/v1/orders/{orderId}/invoice/pdf     # application/pdf; CUSTOMER, RESTAURANT_OWNER, or ADMIN
GET  /api/v1/orders/{orderId}/rider-location
```

## Payments webhook (Razorpay)

```http
POST /api/v1/payments/webhooks/razorpay
X-Razorpay-Signature: <signature>
```

Handles `payment.captured` for orders and wallet top-ups.

## Idempotency

Pass header on order create and wallet top-up:

```http
Idempotency-Key: 550e8400-e29b-41d4-a716-446655440000
```

Duplicate requests return the original result.

## Pagination

List endpoints support:

- **Offset:** `?page=0&size=20`
- **Cursor:** `?cursor=<token>&size=20` (orders)

## Rate limiting

Exceeded limits return `429 Too Many Requests`. Affected endpoints include login, search, cart mutations, and order tracking (see [configuration.md](./configuration.md)).

## WebSocket (optional)

Native WebSocket + STOMP available at `/ws` and `/ws-native` with JWT in handshake. Configure `app.stomp.broker.type=rabbitmq` for multi-instance relay.

## Example: end-to-end curl script

```bash
BASE=http://localhost:8080/api/v1

# Register
TOKEN=$(curl -s -X POST $BASE/auth/register -H "Content-Type: application/json" \
  -d '{"fullName":"Test","email":"t@ex.com","password":"secret123","phoneNumber":"9876543210","role":"CUSTOMER"}' \
  | jq -r '.data.accessToken')

# Browse
curl -s $BASE/restaurants/public | jq '.data[0].id'

# Health
curl -s $BASE/health/ping | jq .
```

More test scripts: `docker/scripts/swagger-test.sh`, `docker/scripts/test-all-apis.sh`.
