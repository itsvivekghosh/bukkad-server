# Delivery API — Rider app endpoints

All endpoints require `Authorization: Bearer <accessToken>` with the
`DELIVERY_AGENT` role. Base path `/api/v1/delivery` (delivery-truth:
`/api/v1/delivery-truth`).

**`DeliveryAgentResponse`** (profile): `id, fullName, email, phoneNumber,
vehicleType, vehicleNumber, available, verified, averageRating,
totalDeliveries, role`.

---

## Earnings

### GET /delivery/earnings/summary

**1. Overview** — The rider's earnings headline numbers.

**2. Request** — `GET /api/v1/delivery/earnings/summary` · Headers: `Authorization`.

**3. Response — `200 OK`:** `ApiResponse<RiderEarningsSummaryResponse>`

| Field | Type | Description |
|---|---|---|
| `pendingAmount` | number | Unpaid earnings |
| `paidAmount` | number | Paid out |
| `perDeliveryFee` | number | Rate per delivery |
| `totalDeliveries` | number | Completed deliveries |

### GET /delivery/earnings · GET /delivery/earnings/cursor
Paged/cursor payout history → `ApiResponse<PagedResponse<RiderPayoutResponse>>`
/ `ApiResponse<CursorPagedResponse<RiderPayoutResponse>>`.

`RiderPayoutResponse`: `id, orderId, orderNumber, amount, status(PENDING|PAID),
createdAt, paidAt`.

---

## Profile & availability

### GET /delivery/profile
`ApiResponse<DeliveryAgentResponse>`.

### PUT /delivery/profile
**1. Overview** — Updates the rider's profile (vehicle, availability,
location, contact fields). Body is a partial `DeliveryAgent` object — only
present fields are written.

**2. Request** — `PUT /api/v1/delivery/profile`

```json
{ "vehicleType": "bike", "vehicleNumber": "KA01AB1234", "available": true }
```

**3. Response — `200 OK`:** `ApiResponse<DeliveryAgentResponse>` (message
"Profile updated successfully").

### PUT /delivery/toggle-availability
`?available=true|false` → `ApiResponse<Void>` (message "Availability updated").

### PUT /delivery/update-location
`?latitude=12.9716&longitude=77.5946` (both required) → `ApiResponse<Void>`
(message "Location updated"). Also pushes the rider's position to customers
tracking an in-flight `OUT_FOR_DELIVERY` order (live ETA recomputed).

---

## Orders

### GET /delivery/available-orders
`ApiResponse<OrderResponse[]>` — orders awaiting a rider (dispatchable).

### GET /delivery/active-deliveries
`ApiResponse<OrderResponse[]>` — the rider's in-progress deliveries.

### GET /delivery/delivery-history
`ApiResponse<OrderResponse[]>` — completed deliveries.

### POST /delivery/{orderId}/accept
Claims a delivery → `ApiResponse<OrderResponse>` (message "Delivery accepted").
`400` if already taken / not eligible.

### POST /delivery/{orderId}/reject
Declines a delivery → `ApiResponse<OrderResponse>` (message "Delivery rejected").

### POST /delivery/orders/{orderId}/location

**1. Overview** — Live GPS ping for an order being delivered: persists the
rider location, recomputes the live ETA, and streams `RIDER_LOCATION` events
to the customer's SSE stream.

**2. Request** — `POST /api/v1/delivery/orders/{orderId}/location`

```json
{ "latitude": 12.9716, "longitude": 77.5946 }
```

Both fields required (non-null).

**3. Response — `200 OK`:** `ApiResponse<RiderLocationResponse>` (message
"Location recorded")

| Field | Type | Description |
|---|---|---|
| `orderId` | number | Order |
| `agentId` | number | Rider |
| `latitude` / `longitude` | number | Recorded GPS |
| `recordedAt` | string | ISO timestamp |

**4. Errors** — `400` missing lat/lng or no agent assigned; `404` order.

---

## Batches

### POST /delivery/batches
**1. Overview** — Creates a delivery batch (multi-order run) for the rider.
**2. Request** — `POST /api/v1/delivery/batches` (no body). **3. Response** —
`ApiResponse<RiderBatchResponse>` (message "Delivery batch created").

`RiderBatchResponse`: `batchId, agentId, status, orders[{orderId, orderNumber,
sequenceNumber, status}], createdAt, completedAt`.

### GET /delivery/batches/active
Returns the rider's active batch → `ApiResponse<RiderBatchResponse>`.

### PUT /delivery/batches/{batchId}/complete
Completes a batch → `ApiResponse<RiderBatchResponse>` (message "Batch completed").

---

## Delivery truth (ETA transparency)

### GET /delivery-truth/orders/{orderId}/eta

**1. Overview** — Detailed, auditable ETA breakdown for an order (traffic,
surge, confidence band, history). Open to all authenticated roles.

**2. Request** — `GET /api/v1/delivery-truth/orders/{orderId}/eta` (any
authenticated role: CUSTOMER / RESTAURANT_OWNER / DELIVERY_AGENT / ADMIN).

**3. Response — `200 OK`:** `ApiResponse<OrderEtaDetailResponse>`

| Field | Type | Description |
|---|---|---|
| `orderId` | number | Order |
| `etaMinutes` | number | Estimated minutes remaining |
| `etaAt` | string | Estimated arrival ISO |
| `confidenceLowMinutes` / `confidenceHighMinutes` | number | Confidence band |
| `trafficFactor` | number | Traffic multiplier |
| `surgeMultiplier` | number | Surge multiplier |
| `factorsSummary` | string | Human-readable factors |
| `history` | array | `{etaMinutes, etaAt, recordedAt}` ETA snapshots |

**4. Errors** — `404` order not found; `403` insufficient role.
