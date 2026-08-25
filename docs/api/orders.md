# Orders API — Cart, order lifecycle, live tracking, group orders, gift cards

This is the core purchase workflow. Order creation is **synchronous**: after
`POST /orders/customer/create` returns 200, the order is placed **and** paid
(non-COD). Responses wrap `OrderResponse` (full) or `OrderSummaryResponse`
(lightweight lists).

---

## Shared response shapes

**`OrderResponse`** (order detail / create / lifecycle responses):

```
id, orderNumber, customerId, customerName, restaurantId, restaurantName,
items[ {id, menuItemName, quantity, price, customizations[], specialInstructions} ],
deliveryAddress(AddressResponse), status, subtotal, deliveryFee, taxAmount,
discountAmount, totalAmount, tipAmount, paymentMethod, paymentStatus,
specialInstructions, contactlessDelivery, estimatedDeliveryTime,
estimatedDeliveryAt, scheduledAt, liveEtaMinutes, liveEtaAt, deliveredAt,
createdAt, deliveryAgent( {id, fullName, email, phoneNumber, vehicleType,
vehicleNumber, available, verified, averageRating, totalDeliveries, role} )
```

**Order statuses:** `SCHEDULED, PLACED, CONFIRMED, PREPARING,
READY_FOR_PICKUP, OUT_FOR_DELIVERY, DELIVERED, CANCELLED, REFUNDED`

**`OrderSummaryResponse`** (lists/kitchen queue): `id, orderNumber, customerId,
customerName, restaurantId, restaurantName, status, totalAmount,
specialInstructions, createdAt, estimatedDeliveryAt`.

**`PagedResponse<T>`**: `{items[], page, size, totalElements, totalPages, hasNext}`.
**`CursorPagedResponse<T>`**: `{items[], nextCursor, hasNext, size}`.

---

## Cart (CUSTOMER)

### GET /cart

**1. Overview** — Returns the caller's cart grouped per restaurant.

**2. Request** — `GET /api/v1/cart` · Headers: `Authorization`. No params.

**3. Response — `200 OK`:** `ApiResponse<CartResponse>`

| Field | Type | Description |
|---|---|---|
| `id` | number | Cart id |
| `restaurantId` / `restaurantName` / `items` | deprecated | Legacy single-restaurant fields |
| `restaurantCarts` | array\<group\> | Per-restaurant groups: `{restaurantId, restaurantName, items[], subtotal, itemCount}` |
| `subtotal` | number | Total subtotal |
| `itemCount` | number | Total items |

`CartItemResponse`: `{id, menuItemId, menuItemName, price, quantity,
customizations[], totalPrice, specialInstructions}`.

**4. Errors** — `401`/`403` not authenticated as CUSTOMER.

### POST /cart/add

**1. Overview** — Adds an item to the cart (validates availability + stock).

**2. Request** — `POST /api/v1/cart/add` · rate-limited `cart-mutation`

```json
{
  "menuItemId": 42,
  "quantity": 2,
  "customizationChoiceIds": [10, 11],
  "specialInstructions": "Less spicy"
}
```

| Field | Type | Required | Description |
|---|---|---|---|
| `menuItemId` | number | Yes | Menu item to add |
| `quantity` | number | Yes | > 0 |
| `customizationChoiceIds` | number[] | No | Selected customization choice ids |
| `specialInstructions` | string | No | Kitchen note |

**3. Response — `200 OK`:** `ApiResponse<CartResponse>` (`message: "Item added to cart"`).
**4. Errors** — `400` item unavailable / insufficient stock / not same restaurant as existing cart items; `429`.

### PUT /cart/items/{cartItemId}
`?quantity=` (integer, **required**). Rate-limited. Returns updated `CartResponse`.

### DELETE /cart/items/{cartItemId}
Rate-limited. Returns updated `CartResponse`.

### DELETE /cart/restaurant/{restaurantId}
Clears one restaurant's lines. Returns updated `CartResponse`.

### DELETE /cart/clear
Clears the whole cart. Returns `ApiResponse<Void>`.

### POST /cart/apply-coupon
`?couponCode=` (**required**). Applies the coupon to the cart. Returns
`CartResponse` (message "Coupon applied"). `400` on invalid coupon.

---

## Create order — POST /orders/customer/create

### 1. Endpoint Overview
Places an order from the customer's cart for the given restaurant. Runs
pricing, stock reservation, wallet/coupon/loyalty application, payment
(unless COD), cache invalidation, and outbox events. Idempotent via
`Idempotency-Key`. Can run async via `?async=true` (returns `202` + a job id).

### 2. Request Specification

**Method & URL:** `POST /api/v1/orders/customer/create[?async=false]`

**Headers:**

| Header | Required | Value |
|---|---|---|
| `Authorization` | Yes | `Bearer <accessToken>` (CUSTOMER) |
| `Content-Type` | Yes | `application/json` |
| `Idempotency-Key` | No (recommended) | Your UUID — replay returns the original order |

**Query params:**

| Param | Type | Default | Description |
|---|---|---|---|
| `async` | boolean | `false` | `true` → `202` + `OrderCreateJobResponse`; `false` → `200` + `OrderResponse` |

**Request Body:**

```json
{
  "restaurantId": 12,
  "deliveryAddressId": 5,
  "specialInstructions": "No onions please",
  "contactlessDelivery": true,
  "couponCode": "WELCOME50",
  "paymentMethod": "UPI",
  "loyaltyPointsToRedeem": 20,
  "walletAmountToUse": null,
  "useWallet": true,
  "tipAmount": 15.0,
  "scheduledAt": null
}
```

| Field | Type | Required | Description |
|---|---|---|---|
| `restaurantId` | number | Yes | Target restaurant |
| `deliveryAddressId` | number | Yes | A saved address of the caller |
| `specialInstructions` | string | No | Kitchen note |
| `contactlessDelivery` | boolean | No | Default `false` |
| `couponCode` | string | No | Coupon to apply |
| `paymentMethod` | string | Yes | `CASH_ON_DELIVERY` \| `CREDIT_CARD` \| `DEBIT_CARD` \| `UPI` \| `WALLET` \| `NET_BANKING` |
| `loyaltyPointsToRedeem` | number | No | Points to redeem for discount |
| `walletAmountToUse` | number | No | Explicit wallet amount (split pay) |
| `useWallet` | boolean | No | Apply wallet balance up to total |
| `tipAmount` | number | No | Rider tip (added to total) |
| `scheduledAt` | string | No | ISO-8601 datetime for scheduled delivery |

> There is **no `items` field** — the order is built from the customer's cart
> for `restaurantId`. Add items to the cart first.

### 3. Response Specification

**Success — `200 OK` (sync):** `ApiResponse<OrderResponse>` (message "Order placed successfully").

```json
{
  "success": true,
  "message": "Order placed successfully",
  "data": {
    "id": 4242, "orderNumber": "ORD-3f8a21c4", "customerId": 101,
    "customerName": "Aarav Sharma", "restaurantId": 12,
    "restaurantName": "Spice Route",
    "items": [ { "id": 1, "menuItemName": "Butter Chicken", "quantity": 2,
                 "price": 320.0, "customizations": [], "specialInstructions": null } ],
    "deliveryAddress": { "id": 5, "addressLine1": "12 MG Road", "city": "Bengaluru", "state": "Karnataka", "pincode": "560001", "latitude": 12.9716, "longitude": 77.5946, "isDefault": true },
    "status": "PLACED", "subtotal": 640.0, "deliveryFee": 35.0, "taxAmount": 108.8,
    "discountAmount": 50.0, "totalAmount": 733.8, "tipAmount": 15.0,
    "paymentMethod": "UPI", "paymentStatus": "COMPLETED",
    "specialInstructions": "No onions please", "contactlessDelivery": true,
    "estimatedDeliveryTime": 40, "estimatedDeliveryAt": "2026-08-24T21:40:00",
    "scheduledAt": null, "liveEtaMinutes": 40, "liveEtaAt": "2026-08-24T21:40:00",
    "deliveredAt": null, "createdAt": "2026-08-24T21:00:00", "deliveryAgent": null
  }
}
```

**Success — `202 Accepted` (async):** `ApiResponse<OrderCreateJobResponse>`

```json
{
  "success": true,
  "message": "Order accepted for processing",
  "data": { "jobId": "ord-abc-123", "status": "PROCESSING", "order": null,
            "message": null, "pollUrl": "/api/v1/orders/customer/create/jobs/ord-abc-123" }
}
```

Poll `GET /orders/customer/create/jobs/{jobId}` until `status` is
`COMPLETED` (then `order` is populated) or `FAILED`.

**Error Responses:**

| Code | Scenario |
|---|---|
| `400` | Restaurant closed / busy / not active; insufficient stock; invalid payment method; invalid scheduled time |
| `400` | `"Payment failed"` (gateway rejected) |
| `404` | Customer / restaurant / address not found |
| `409` | `"Duplicate order request is already being processed"` (same `Idempotency-Key` in flight) |
| `429` | Fraud velocity block (`Retry-After: 300`) |

### 4. Implementation Example

```js
const res = await fetch(`${BASE}/api/v1/orders/customer/create`, {
  method: "POST",
  headers: { "Content-Type": "application/json", "Idempotency-Key": crypto.randomUUID(),
             Authorization: `Bearer ${accessToken}` },
  body: JSON.stringify({ restaurantId: 12, deliveryAddressId: 5,
                         paymentMethod: "UPI", useWallet: true, couponCode: "WELCOME50" }),
});
const body = await res.json();
const order = body.data;                 // status PLACED, paymentStatus COMPLETED
```

---

## Customer order reads & actions

### GET /orders/customer/my-orders
`?page=0&size=20` → `ApiResponse<PagedResponse<OrderSummaryResponse>>`.

### GET /orders/customer/my-orders/cursor
`?cursor=&size=20` → `ApiResponse<CursorPagedResponse<OrderSummaryResponse>>`.

### GET /orders/customer/{orderId}
Full detail, ownership-checked. Optional `?fields=id,orderNumber,status,totalAmount`.
Returns projected `ApiResponse<OrderResponse>`.

### GET /orders/customer/track/{orderId} (alias: /orders/customer/{orderId}/track)
Live tracking snapshot: `OrderResponse` with **recalculated** `liveEtaMinutes`
/ `liveEtaAt` from status + rider location. Rate-limited `order-track`.
Ownership enforced.

### POST /orders/customer/{orderId}/reorder
Rebuilds the cart from a past order. Returns `ApiResponse<ReorderResponse>` —
`{cart(CartResponse), skippedItems[{menuItemId, menuItemName, reason}]}`.
`404` if order not owned.

### GET /orders/customer/export/orders
`?page=0&size=50` → **CSV download** (`text/csv`, `Content-Disposition:
attachment; filename=bhukkad-orders-<date>.csv`). Columns: `Order Number,
Customer, Restaurant, Status, Total, Date`.

### PUT /orders/customer/{orderId}/cancel?reason=
Cancels (if allowed) and refunds a COMPLETED payment. Returns
`ApiResponse<OrderResponse>` (message "Order cancelled successfully").
`400` if not cancellable.

### GET /orders/customer/scheduled-orders (+ /cursor)
Scheduled (future) orders, paged/cursor.

### PUT /orders/customer/scheduled-orders/{orderId}/cancel?reason=
Cancels a scheduled order + refunds if paid.

### GET /orders/customer/batch?ids=1&ids=2
Batch fetch owned orders (max 100 ids). Returns
`ApiResponse<Map<Long, OrderResponse>>` — keyed by order id; foreign/missing
ids silently dropped.

### POST /orders/customer/create-batch
Body `BatchOrderRequest` — `{deliveryAddressId, specialInstructions?,
contactlessDelivery?, paymentMethod, tipAmount?}` — places orders for each
restaurant currently in the cart. Returns `ApiResponse<BatchOrderResponse>` —
`{orders[], successCount, failureCount, errors[]}`. Supports `Idempotency-Key`.

### GET /orders/customer/create/jobs/{jobId}
Polls an async create job (see above).

### GET /orders/number/{orderNumber}
Public lookup by human-readable order number. Returns `ApiResponse<OrderResponse>`.

---

## Restaurant operations (RESTAURANT_OWNER)

| Endpoint | Description |
|---|---|
| `GET /orders/restaurant/{restaurantId}?page&size` | Orders for the restaurant |
| `GET /orders/restaurant/{restaurantId}/cursor?cursor&size` | Cursor variant |
| `GET /orders/restaurant/{restaurantId}/pending?limit=50` | Pending orders (no pagination) |
| `GET /orders/restaurant/{restaurantId}/kitchen-queue?limit=50` | Active kitchen queue (rate-limited `kitchen-queue`) |
| `PUT /orders/restaurant/{orderId}/accept` | `PLACED → CONFIRMED` |
| `PUT /orders/restaurant/{orderId}/ready` | `READY_FOR_PICKUP` + **auto-assign nearest rider** |
| `PUT /orders/restaurant/{orderId}/assign-delivery?agentId=` | Manually assign a rider |

Each lifecycle endpoint returns `ApiResponse<OrderResponse>` with a status
message. Errors: `400` invalid transition / wrong restaurant owner; `404`.

---

## Delivery agent actions (DELIVERY_AGENT)

| Endpoint | Description |
|---|---|
| `GET /orders/delivery/my-deliveries?page&size` (+ `/cursor`) | Assigned deliveries |
| `PUT /orders/delivery/{orderId}/picked-up` | `OUT_FOR_DELIVERY` |
| `PUT /orders/delivery/{orderId}/delivered` | `DELIVERED` (requires satisfied proof if enforced) |
| `POST /orders/delivery/{orderId}/proof/otp` | Issue/re-issue handover OTP (SMS only, never in response) |
| `POST /orders/delivery/{orderId}/proof/verify` | Verify OTP (+ optional photo/recipient) |
| `POST /orders/delivery/{orderId}/proof/photo-url` | Mint presigned photo upload URL |
| `GET /orders/delivery/{orderId}/proof` | Proof state (resume after restart) |

**`POST .../proof/verify` body:**

```json
{
  "otpCode": "123456",
  "photoKey": "delivery-proof/4242/ab12.jpg",
  "recipientName": "Aarav",
  "captureLatitude": 12.9716,
  "captureLongitude": 77.5946,
  "notes": "Left with security guard"
}
```

`otpCode` required (`^\d{6}$`); `photoKey` max 512; `recipientName` max 120;
`notes` max 1000. Wrong code → `400` and consumes one attempt (anti-brute-force).

**`POST .../proof/photo-url` body:** `{"contentType": "image/jpeg"}` →
`ApiResponse<DeliveryProofPhotoUploadResponse>` `{uploadUrl, photoKey}` — PUT
the photo bytes to `uploadUrl` (presigned, short-lived), then include
`photoKey` on verify.

**`DeliveryProofResponse`** (otp / verify / get): `id, orderId, orderNumber,
proofType(OTP|PHOTO|OTP_AND_PHOTO|SKIPPED), status(PENDING|VERIFIED|FAILED|SKIPPED),
otpIssuedAt, otpExpiresAt, otpAttemptsRemaining, verifiedAt, photoAvailable,
photoUrl, recipientName, notes, satisfied(boolean — READ THIS, not status),
enforced(boolean), createdAt`. **The OTP code itself is never returned.**

---

## Live streams (SSE)

| Endpoint | Role | Purpose |
|---|---|---|
| `GET /orders/stream/kitchen/{restaurantId}` | OWNER | Kitchen queue live |
| `GET /orders/stream/rider` | AGENT | Agent's deliveries live |
| `GET /orders/stream/customer/{orderId}` | CUSTOMER | Customer tracking live (rate-limited `order-track`) |
| `POST /orders/stream/customer/{orderId}/tracking-token` | CUSTOMER | Issue guest token → `{trackingToken}` |
| `GET /orders/stream/customer-token/{orderId}?token=` | public | Guest SSE stream (token-gated) |

All SSE endpoints accept optional `Last-Event-ID`. See the integration guide
§8 for the wire format and resume semantics.

---

## Group orders (CUSTOMER) — base /customers/group-orders

| Endpoint | Description |
|---|---|
| `POST /customers/group-orders` | Create — body `{"title": "Office lunch"}` (max 100 chars) → **201** |
| `POST /customers/group-orders/{id}/invite` | Invite — body `{"phone": "9876543210"}` |
| `POST /customers/group-orders/{id}/join` | Join as a member |
| `GET /customers/group-orders/{id}` | Detail |
| `POST /customers/group-orders/{id}/split` | Record split — body `{"shares": {"<userId>": 250.5}}` |
| `POST /customers/group-orders/{id}/place` | Freeze the group; host then places the order via the standard create flow |

`GroupOrderResponse`: `id, hostUserId, title, status, createdAt, placedAt,
members[{id, userId, invitePhone, status, amountContribution, paid, joinedAt}]`.

---

## Gift cards (CUSTOMER / public) — base /gift-cards

| Endpoint | Role | Description |
|---|---|---|
| `POST /gift-cards/purchase` | CUSTOMER | Body `{amount(>=100), recipientEmail, recipientName, message?, expiresAt}` |
| `POST /gift-cards/redeem` | CUSTOMER | Body `{"code": "GC-XXXX"}` — redeems into wallet |
| `GET /gift-cards/my-cards` | CUSTOMER | Cards the caller purchased |
| `GET /gift-cards/received` | CUSTOMER | Cards purchased for the caller |
| `GET /gift-cards/{code}` | public | Lookup by code |

`GiftCardResponse`: `id, code, amount, balance, status, recipientEmail,
recipientName, message, expiresAt, createdAt, redeemedAt`.

---

## Order assist (CUSTOMER)

| Endpoint | Description |
|---|---|
| `POST /customers/orders/{orderId}/rebook` | Rebuilds cart from a cancelled/refunded order → `ApiResponse<ReorderResponse>` |
| `GET /customers/surprise-me?restaurantId=` | Curated pick → `ApiResponse<MenuItemResponse>` (404 if nothing available) |
