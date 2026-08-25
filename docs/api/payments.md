# Payments API — Status, webhooks, refunds, disputes

Most payment actions happen **inside order creation** (see `orders.md`).
Frontend engineers interact with payments in three places:

1. `GET /payments/orders/{orderId}` — fetch the `gatewayOrderId` to drive the
   payment SDK.
2. `POST /payments/webhooks/razorpay` — **server-to-server** (Razorpay calls
   this; never call from the app).
3. `POST /customers/orders/{orderId}/disputes` — file a dispute.

Payment statuses: `PENDING, COMPLETED, FAILED, REFUNDED`. Methods:
`CASH_ON_DELIVERY, CREDIT_CARD, DEBIT_CARD, UPI, WALLET, NET_BANKING`.

---

## GET /payments/orders/{orderId}

### 1. Endpoint Overview
Returns the payment record for an order — including `gatewayOrderId`, which
you pass to the Razorpay Checkout SDK to collect the payment.

### 2. Request Specification

**Method & URL:** `GET /api/v1/payments/orders/{orderId}` · Headers:
`Authorization` (CUSTOMER).

| Param | Type | Required | Description |
|---|---|---|---|
| `orderId` (path) | number | Yes | Order id |

### 3. Response Specification

**Success — `200 OK`:** `ApiResponse<PaymentResponse>`

| Field | Type | Description |
|---|---|---|
| `id` | number | Payment record id |
| `orderId` | number | Order id |
| `paymentMethod` | string | e.g. `UPI` |
| `status` | string | `PENDING` \| `COMPLETED` \| `FAILED` \| `REFUNDED` |
| `amount` | number | Total amount |
| `gatewayOrderId` | string | Razorpay order id → pass to the SDK |
| `gatewayPaymentId` | string \| null | Set after capture |
| `transactionId` | string \| null | Set after completion |
| `purpose` | string | `ORDER` \| `WALLET_TOP_UP` |

```json
{
  "success": true,
  "data": {
    "id": 9001, "orderId": 4242, "paymentMethod": "UPI", "status": "PENDING",
    "amount": 733.8, "gatewayOrderId": "order_Oxyz",
    "gatewayPaymentId": null, "transactionId": null, "purpose": "ORDER"
  }
}
```

**4. Errors** — `401`/`403` not CUSTOMER; `404` payment/order not found.

### SDK flow for gateway orders

```
1. POST /orders/customer/create (paymentMethod: UPI) → 200 (payment PENDING)
2. GET  /payments/orders/{orderId}   → gatewayOrderId
3. Open Razorpay Checkout with { key: <your key>, order_id: gatewayOrderId }
4. Razorpay's webhook marks the payment COMPLETED (status change is server-side)
5. GET  /payments/orders/{orderId}/payment-status  → confirm COMPLETED
```

---

## GET /payments/orders/{orderId}/payment-status

**1. Overview** — Convenience status check for the order's payment. **2.
Request** — `GET /api/v1/payments/orders/{orderId}/payment-status` (CUSTOMER).
**3. Response — `200 OK`:** `ApiResponse<Map>` with keys like `orderId`,
`paymentStatus`, `paymentMethod`, `amount`, `gatewayOrderId`. **4. Errors** —
`404` unknown order.

---

## POST /admin/payments/refund/{orderId}

### 1. Endpoint Overview
Admin-triggered refund using the cancellation refund policy (percent + target
per reason). Idempotent per (order, reason) — a second call returns the same
result without a second refund.

### 2. Request Specification

**Method & URL:** `POST /api/v1/admin/payments/refund/{orderId}` ·
Headers: `Authorization` (ADMIN).

| Param | Type | Required | Description |
|---|---|---|---|
| `orderId` (path) | number | Yes | Order to refund |

Optional body: `{"reason": "CUSTOMER_CANCELLED"}`.

### 3. Response Specification

**Success — `200 OK`:** `ApiResponse<Map>`

```json
{ "success": true, "data": { "orderId": 4242, "reason": "CUSTOMER_CANCELLED", "refunded": true } }
```

**Errors** — `403` not ADMIN; `404` order not found; `400` no policy applies.

---

## POST /payments/webhooks/razorpay (server-to-server)

### 1. Endpoint Overview
Razorpay's webhook target for `payment.captured` events. Marks the payment
COMPLETED, credits the wallet for top-ups, and records the event. Public by
design — **security comes from the HMAC signature**, not auth.

### 2. Request Specification

**Method & URL:** `POST /api/v1/payments/webhooks/razorpay`

**Headers:**

| Header | Required | Value |
|---|---|---|
| `Content-Type` | Yes | `application/json` |
| `X-Razorpay-Signature` | Yes | Razorpay signature (HMAC-SHA256 of raw body with the webhook secret) |

**Body:** raw Razorpay event JSON:

```json
{
  "event": "payment.captured",
  "payload": { "payment": { "entity": { "id": "pay_Lxyz", "order_id": "order_Oxyz", "status": "captured" } } }
}
```

### 3. Response Specification

**Success — `200 OK`:** `ApiResponse<BlankResponse>` (acknowledge every
delivery; duplicates are deduped and also acked).

**Errors:**

| Code | Scenario |
|---|---|
| `400` | Invalid signature / malformed payload / non-`payment.captured` event |
| `429` | Rate limited (`webhook` bucket, per client IP) |

### 4. Implementation Notes
- Verify the signature with the `RAZORPAY_WEBHOOK_SECRET`.
- Re-deliveries are idempotent (unique event id guard) — always ack `200`.
- Only `payment.captured` triggers state changes; other events are ignored.

---

## Disputes (CUSTOMER + ADMIN)

### POST /customers/orders/{orderId}/disputes

**1. Overview** — Files a dispute against an order (wrong item, delivery
issue, charge dispute).

**2. Request** — `POST /api/v1/customers/orders/{orderId}/disputes` (CUSTOMER)

```json
{ "type": "WRONG_ITEM", "customerEvidence": "Photo attached of incorrect item" }
```

`type` required; `customerEvidence` required (non-blank).

**3. Response — `200 OK`:** `ApiResponse<DisputeResponse>` (message "Dispute filed").

`DisputeResponse`: `id, orderId, orderNumber, type, status, customerEvidence,
riderEvidence, restaurantEvidence, resolutionNotes, resolution, refundAmount,
resolvedBy, resolvedAt, createdAt`.

### GET /customers/disputes
The caller's disputes → `ApiResponse<DisputeResponse[]>`.

### GET /admin/disputes · GET /admin/disputes/{disputeId}
Admin list/detail.

### POST /admin/disputes/{disputeId}/resolve
Admin resolves — body `{resolution, refundAmount?, notes?}` where
`resolution` is e.g. `FULL_REFUND | PARTIAL_REFUND | DISMISSED`.

### POST /admin/disputes/auto-resolve
Admin triggers auto-resolution of eligible disputes → `ApiResponse<Map>` with
`resolved` (count).
