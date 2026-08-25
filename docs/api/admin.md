# Admin API — Dashboards, users, orders, restaurants, operations, fraud, feature flags, dead letters

All endpoints require `Authorization: Bearer <accessToken>` with the `ADMIN`
role. Most are under `/api/v1/admin/**`. Dashboards read from the read
replica (`@UseReadReplica`).

---

## Dashboard & platform

### GET /admin/dashboard

**1. Overview** — Platform-level headline numbers (users, restaurants,
orders, revenue, recent orders). The primary "admin app open" call.

**2. Request** — `GET /api/v1/admin/dashboard` · Headers: `Authorization`.

**3. Response — `200 OK`:** `ApiResponse<Map>`

| Key | Type | Description |
|---|---|---|
| `totalUsers` | number | All users |
| `totalCustomers` | number | Customer count |
| `totalOwners` | number | Restaurant owner count |
| `totalAgents` | number | Delivery agent count |
| `totalRestaurants` | number | Total restaurants |
| `activeRestaurants` | number | Currently active |
| `totalOrders` | number | All orders |
| `todayOrders` | number | Orders today |
| `totalRevenue` | number | All-time revenue |
| `todayRevenue` | number | Today's revenue |
| `recentOrders` | array | Last 10 orders: `{id, orderNumber, status, totalAmount, createdAt}` |

### GET /admin/revenue?days=7
Revenue for a window → `ApiResponse<Map>`: `period, totalRevenue,
totalOrders, deliveredOrders, cancelledOrders, averageOrderValue`.

### GET /admin/analytics
Aggregated stats → `ApiResponse<Map>`: `ordersByStatus (Map),
usersByRole (Map), topRestaurants[{id, name, isActive, isOpen,
averageRating, totalReviews, createdAt}]`.

### GET /admin/operations-dashboard
Operations KPIs → `ApiResponse<AdminOperationsDashboardResponse>`:
`totalPendingRestaurantSettlements, totalPendingRiderPayouts,
pendingSettlementCount, pendingPayoutCount, activeDeliveryBatches,
todaySettlementVolume, ordersByStatus, recentSettlementRuns,
etaAccuracy`.

---

## Users

### GET /admin/users?page=0&size=20&role=CUSTOMER&search=Aarav
Paged/filtered user list → `ApiResponse<Map>`: `users[{id, email, fullName,
phoneNumber, role, active, emailVerified, createdAt}], totalElements,
totalPages, currentPage, size`.

### PUT /admin/users/{userId}/activate · PUT /admin/users/{userId}/deactivate
→ `ApiResponse<Void>` (message "User activated" / "User deactivated").

### PUT /admin/owners/{ownerId}/verify · PUT /admin/agents/{agentId}/verify
→ `ApiResponse<Void>`.

---

## Orders

### GET /admin/orders?page=0&size=20&status=PLACED
Paged/filtered orders → `ApiResponse<Map>`: `orders[{id, orderNumber, status,
totalAmount, createdAt}], totalElements, totalPages, currentPage`.

---

## Restaurants

### GET /admin/restaurants?page=0&size=20&active=true
Paged/filtered → `ApiResponse<Map>`: `restaurants[{id, name, isActive,
isOpen, averageRating, totalReviews, createdAt}], totalElements, totalPages,
currentPage, size`.

### PUT /admin/restaurants/{restaurantId}/approve · /suspend
→ `ApiResponse<Void>`.

### PUT /admin/restaurants/{restaurantId}/onboarding
**1. Overview** — Approves or rejects a restaurant's onboarding application.

**2. Request** — `PUT /api/v1/admin/restaurants/{restaurantId}/onboarding`

```json
{ "approved": true, "reason": "All documents verified" }
```

`approved` required boolean; `reason` required when `approved=false`.

**3. Response — `200 OK`:** `ApiResponse<Void>`.

### PUT /admin/restaurants/{restaurantId}/commission?percent=15
Sets commission percentage (0–100) → `ApiResponse<Map>`: `{restaurantId, commissionPercent}`.

### PUT /admin/restaurants/{restaurantId}/settle-payouts
Settles pending restaurant payouts → `ApiResponse<Map>`: `{restaurantId, settledCount}`.

### PUT /admin/agents/{agentId}/settle-payouts
Settles rider payouts → `ApiResponse<Map>`: `{agentId, settledCount}`.

---

## Settlements

### POST /admin/settlements/run
Triggers a settlement run → `ApiResponse<Map>`: `{runId, status(RUNNING|COMPLETED|FAILED),
restaurantsSettled, agentsSettled, totalAmount}`.

---

## Zones & cities

### GET /admin/zones · POST /admin/zones · PUT /admin/zones/{zoneId} · DELETE /admin/zones/{zoneId}
`DeliveryZoneRequest`/`Response`: `id, name, city, centerLatitude,
centerLongitude, radiusKm, baseDeliveryFee, perKmFee, surgeMultiplier,
freeDeliveryAbove, isActive`.

### GET /admin/cities · POST /admin/cities · PUT /admin/cities/{cityId} · DELETE /admin/cities/{cityId}
`CityConfigRequest` (validation: `city` + `displayName` `@NotBlank`);
`CityConfigResponse`: `id, city, displayName, currency, timezone,
supportedPaymentMethods, defaultMinOrderAmount, isServiceable, isActive`.

---

## Promotions

### GET /admin/promotions/campaigns · POST /admin/promotions/campaigns · PUT /admin/promotions/campaigns/{campaignId} · DELETE /admin/promotions/campaigns/{campaignId}
`PromotionCampaignRequest`/`Response`: `id, name, campaignType, description,
discountPercent, flatDiscountAmount, minOrderAmount, maxDiscountAmount,
restaurantId, freeDelivery, priority, usageLimit, perUserLimit, isActive,
startsAt, endsAt, buyQuantity, getQuantity, getDiscountPercent, targetSegment,
applicableMenuItemId`.

### GET /admin/promotions/banners · POST /admin/promotions/banners · PUT /admin/promotions/banners/{bannerId}
`PromoBannerRequest`/`Response`: `id, title, subtitle, imageUrl, actionType,
actionTarget, displayOrder, isActive, startsAt, endsAt`.

---

## Feature flags (kill-switch)

### GET /admin/feature-flags

**1. Overview** — Returns every feature flag and its current effective value
(configured default overlay + runtime override). Front the admin console's
kill-switch panel.

**2. Request** — `GET /api/v1/admin/feature-flags` · Headers: `Authorization` (ADMIN).

**3. Response — `200 OK`:** `ApiResponse<Map<String, Boolean>>`

```json
{
  "success": true,
  "data": {
    "checkout.new": true,
    "search-provider-es": false,
    "chatbot-v2": true,
    "autocomplete-enabled": true
  }
}
```

### GET /admin/feature-flags/{key}
Single flag value → `ApiResponse<Boolean>`. `404` if unknown.

### PUT /admin/feature-flags/{key}

**1. Overview** — Toggles a runtime override. Pass `value=false` to disable
a feature (kill-switch), pass **no param** (`value` absent) to revert to the
configured default. The change propagates to all replicas within the pub/sub
delivery latency (~100ms).

**2. Request** — `PUT /api/v1/admin/feature-flags/{key}?value=false` · `value` optional boolean.

**3. Response — `200 OK`:** `ApiResponse<Boolean>` — the effective value after the toggle.

---

## API keys (partner integrations)

### POST /admin/api-keys
**1. Overview** — Creates a new API key for a partner. The plaintext key is
shown once in the response and never stored (SHA-256 hash is persisted).

**2. Request** — `POST /api/v1/admin/api-keys` (ADMIN)

```json
{ "name": "Zomato Integration", "partnerId": 7, "scopes": "orders:read",
  "expiresAt": "2027-12-31T23:59:59" }
```

**3. Response — `200 OK`:** `ApiResponse<CreatedApiKey>` (message "API key created")

| Field | Type | Description |
|---|---|---|
| `id` | number | Key id |
| `name` | string | Key name |
| `apiKey` | string | **Plaintext — shown once; save it immediately** |
| `scopes` | string | Permission scopes |
| `expiresAt` | string | Expiration |

Format: `bhk_<6-char-prefix>_<24-char-secret>`. Send as `X-API-Key` header.

### GET /admin/api-keys
List all keys → `ApiResponse<ApiKey[]>` (entity: `id, name, keyPrefix,
status, partnerId, scopes, expiresAt, lastUsedAt, createdAt, revokedAt`).

### DELETE /admin/api-keys/{id}
Revokes a key → `ApiResponse<Void>` (message "API key revoked").

### POST /admin/api-keys/{id}/rotate
Rotates a key (new secret, same metadata) → `ApiResponse<CreatedApiKey>`.

---

## Dead-letter queue (DLQ)

### GET /admin/outbox/dlq?limit=20
**1. Overview** — Lists failed outbox events (exhausted retries). Each event
can be inspected and requeued.

**2. Request** — `GET /api/v1/admin/outbox/dlq?limit=20` (ADMIN).

**3. Response — `200 OK`:** `ApiResponse<DeadLetterEventResponse[]>`

| Field | Type | Description |
|---|---|---|
| `id` | number | DLQ event id |
| `eventType` | string | e.g. `PAYMENT_WEBHOOK_RECEIVED` |
| `aggregateType` | string | e.g. `ORDER` |
| `aggregateId` | number | Business id |
| `payload` | string | Raw payload (truncated 2000 chars) |
| `lastError` | string | Last failure message |
| `retryCount` | number | Retry attempts |
| `source` | string | Component |
| `status` | string | `PENDING` \| `REQUEUED` |
| `createdAt` / `requeuedAt` | string | Timestamps |

### GET /admin/outbox/dlq/pending/count
`ApiResponse<number>` — pending DLQ count.

### GET /admin/outbox/dlq/{id}
Single event detail.

### POST /admin/outbox/dlq/{id}/requeue
Re-drives the event back into the outbox → `ApiResponse<DeadLetterEventResponse>`.

---

## Fraud & risk

### GET /admin/fraud/dashboard
`ApiResponse<FraudDashboardResponse>`: `totalEvents, eventsLast24Hours,
eventsLast7Days, eventsLast30Days, pendingReviewCount, eventsByType(Map),
topIPs[{ip, eventCount, firstSeen, lastSeen}], topDevices, recentEvents[]`.

### GET /admin/fraud/events
`ApiResponse<FraudEventResponse[]>`: `id, eventType, ipAddress,
deviceFingerprint, details, createdAt`.

### GET /admin/fraud/review-queue
`ApiResponse<FraudReviewActionResponse[]>` — pending manual reviews.

### POST /admin/fraud/review-queue/{eventId}/action
Body `{"action": "DISMISS", "notes": "False positive"}`. `action` required.
Returns `ApiResponse<FraudReviewActionResponse>`.

---

## Churn

### GET /admin/churn/high-risk
`ApiResponse<ChurnScore[]>`: `id, userId, score(0-100),
riskLevel(LOW|MEDIUM|HIGH), factors, scoredAt, retentionActionTaken`.

### POST /admin/churn/rescore/{userId}
On-demand rescore → `ApiResponse<ChurnScore>`.

---

## Support tickets

### GET /admin/support/tickets
`ApiResponse<SupportTicketResponse[]>`: `id, ticketNumber, customerId, orderId,
category, subject, description, status, priority, resolutionNotes, createdAt,
updatedAt`.

### PUT /admin/support/tickets/{ticketId}/status?status=RESOLVED&resolutionNotes=
Update ticket status → `ApiResponse<SupportTicketResponse>`.

---

## Review moderation

### GET /admin/reviews/moderation?status=PENDING
Moderation queue → `ApiResponse<Review[]>` (oldest first).

### PUT /admin/reviews/{reviewId}/moderate?status=APPROVED
Moderate (APPROVED / REJECTED) → `ApiResponse<Review>` (message "Review moderation updated").

---

## Test notification

### POST /admin/notifications/test
Body `{"channel":"email", "recipient":"admin@test.com", "message":"Test"}`.
Returns `ApiResponse<Map>` (message "Test notification dispatched").

---

## Other admin endpoints

| Endpoint | Purpose |
|---|---|
| `GET /admin/scale` | Capacity/queue metrics |
| `GET /admin/promotions/campaigns` | List campaigns |
| `POST /admin/promotions/campaigns` | Create campaign |
| `PUT/DELETE /admin/promotions/campaigns/{id}` | Update/deactivate |
| `GET /admin/promotions/banners` | List banners |
| `POST /admin/promotions/banners` | Create banner |
| `PUT /admin/promotions/banners/{id}` | Update banner |
| `GET /admin/churn/high-risk` | High-risk churn scores |
| `POST /admin/churn/rescore/{userId}` | Rescore |
| `GET /admin/fraud/dashboard` | Fraud dashboard |
| `GET /admin/fraud/events` | Fraud events list |
| `GET /admin/fraud/review-queue` | Manual review queue |
| `POST /admin/fraud/review-queue/{eventId}/action` | Review event |
| `GET /admin/outbox/dlq` | DLQ list |
| `GET /admin/outbox/dlq/pending/count` | DLQ count |
| `GET /admin/outbox/dlq/{id}` | DLQ detail |
| `POST /admin/outbox/dlq/{id}/requeue` | Requeue DLQ |
| `GET /admin/support/tickets` | List tickets |
| `PUT /admin/support/tickets/{ticketId}/status` | Update ticket |
| `GET /admin/reviews/moderation` | Moderation queue |
| `PUT /admin/reviews/{reviewId}/moderate` | Moderate review |
| `POST /admin/notifications/test` | Test notification |
| `GET /admin/experiments` | Experiment exposures |
| `GET /admin/commission-tiers` | Commission tiers |
| `GET /admin/dynamic-pricing-rules` | Dynamic pricing rules |
| `GET /admin/inventory-alerts` | Inventory alerts |
| `GET /admin/surveys` | Delivery surveys |
| `GET /admin/tenants` | White-label tenants |
| `GET /admin/affiliates` · `POST/PUT/DELETE` | Affiliate code management |
| `GET /admin/compliance` | Consent/export requests |
| `GET /admin/analytics/export` | CSV/JSON export jobs |
| `GET /health` | App health + DB status |
| `GET /admin/cache` | Cache control (debug) |