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
| POST | `/api/v1/auth/register` | Register |
| POST | `/api/v1/auth/login` | Login |
| GET | `/api/v1/restaurants/public` | List restaurants |
| GET | `/api/v1/restaurants/public/{id}` | Restaurant detail |
| GET | `/api/v1/restaurants/public/search` | Search |
| GET | `/api/v1/restaurants/public/nearby` | Geo search |
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
  "contactlessDelivery": false
}
```

**Payment methods:** `CASH_ON_DELIVERY`, `UPI`, `CREDIT_CARD`, `DEBIT_CARD`, `WALLET`, `NET_BANKING` (also `COD` → COD).

**Split pay:** Set `walletAmountToUse` or `useWallet: true` with `UPI`/`CARD` to pay remainder via gateway.

**Async create:** `POST .../create?async=true` returns `202` with job ID; poll job status endpoint.

### 4. Track order

```http
GET /api/v1/orders/customer/{orderId}
GET /api/v1/orders/customer/{orderId}/track
```

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

GET  /api/v1/delivery/earnings/summary
GET  /api/v1/delivery/earnings?page=0&size=20

GET  /api/v1/orders/stream/rider   # SSE
```

## Admin

```http
GET /api/v1/admin/dashboard
GET /api/v1/admin/analytics
# Cache management (ADMIN only, or public if app.debug=true)
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
