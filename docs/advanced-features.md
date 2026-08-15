# Advanced Platform Features

This guide covers V9/V10 capabilities beyond the core order flow: referrals, favorites, batch checkout, scheduled orders, live ETA, settlements, unified search, and external event hooks.

## Unified search

Single endpoint combining restaurant and menu item full-text search (MySQL FULLTEXT):

```http
GET /api/v1/search?keyword=Paneer
```

Public — no auth required. Response includes `restaurants`, `menuItems`, and counts.

## Scheduled orders

Customers can schedule delivery at least **30 minutes** ahead (configurable), up to **7 days**:

```http
POST /api/v1/orders/customer/create
Content-Type: application/json

{
  "restaurantId": 1,
  "deliveryAddressId": 1,
  "paymentMethod": "CASH_ON_DELIVERY",
  "scheduledAt": "2026-08-15T14:30:00"
}
```

- Order status is **`SCHEDULED`** until the dispatch job moves it to **`PLACED`**.
- Background processor runs every 60s (`app.scheduled-orders.dispatch-interval-ms`).
- Kitchen queues exclude `SCHEDULED` orders until dispatched.

## Live ETA

Orders expose dynamic ETA fields on responses and SSE updates:

| Field | Description |
|-------|-------------|
| `liveEtaMinutes` | Estimated minutes until delivery |
| `liveEtaAt` | ISO timestamp of estimated arrival |

ETA is recalculated by order status and rider GPS when available. Track endpoint:

```http
GET /api/v1/orders/customer/track/{orderId}
```

SSE `OrderLiveUpdate` events also include `liveEtaMinutes` and `liveEtaAt`.

## Restaurant settlements

When an order is marked **delivered**, a settlement row is created:

- `orderAmount` — restaurant subtotal
- `commissionAmount` — platform commission (default 15%)
- `netAmount` — amount owed to restaurant
- `status` — `PENDING` or `SETTLED`

### Owner: view ledger

```http
GET /api/v1/restaurants/owner/{restaurantId}/settlements?page=0&size=20
Authorization: Bearer <owner-token>
```

### Admin: mark settled

```http
PUT /api/v1/admin/restaurants/{restaurantId}/settle-payouts
Authorization: Bearer <admin-token>
```

## Referrals, favorites, tips, batch checkout

| Feature | Endpoint |
|---------|----------|
| Referral code & stats | `GET /api/v1/customers/referral` |
| Add/remove favorite | `POST/DELETE /api/v1/customers/favorites/{restaurantId}` |
| List favorites | `GET /api/v1/customers/favorites` |
| Rider tip on order | `tipAmount` in `POST /orders/customer/create` |
| Multi-restaurant checkout | `POST /api/v1/orders/customer/create-batch` |
| Menu item ratings | `POST /api/v1/reviews/menu-items` |

## External events (outbox bridge)

When `app.events.external.enabled=true`, outbox events are forwarded to `ExternalEventBridge` (default: structured log sink; extend for Kafka/Rabbit).

```yaml
app:
  events:
    external:
      enabled: true
      type: log   # plug in kafka/rabbitmq publisher
```

## Staging profile

Use `SPRING_PROFILES_ACTIVE=staging` for production-like settings:

- Read replica enabled by default (`DB_REPLICA_ENABLED=true`)
- Hibernate SQL logging at WARN
- Prometheus scrape requires auth
- File logging to `logs/bhukkad-staging.log`

See [configuration.md](./configuration.md) for all properties.

## Testing

```bash
# Full bash integration suite (Docker stack)
bash docker/scripts/test-all-apis.sh

# Python catalog runner
python3 scripts/test-all-apis.py
```

Both suites cover unified search, scheduled orders, live ETA, and restaurant settlements.

## V11 platform enhancements

### Coupon per-user limits

`perUserLimit` on coupons is now enforced via `coupon_usages` tracking. Validation checks how many times the current customer has used a code.

```http
GET /api/v1/coupons/validate?code=SAVE10&orderAmount=500&restaurantId=1
Authorization: Bearer <customer-token>
```

### Low-stock alerts (owner)

```http
GET /api/v1/menu/items/restaurant/{restaurantId}/low-stock?threshold=10
Authorization: Bearer <owner-token>
```

Returns items with `stockQuantity` at or below the threshold (default from `app.inventory.low-stock-threshold`).

### Per-restaurant commission

Admins can override the global settlement commission per restaurant:

```http
PUT /api/v1/admin/restaurants/{restaurantId}/commission?percent=12.5
```

Settlement ledger uses restaurant override when set, otherwise `app.settlement.commission-percent`.

### Notification preferences

Customers control channels for order updates:

```http
GET  /api/v1/customers/notification-preferences
PUT  /api/v1/customers/notification-preferences
```

### Customer order stats

```http
GET /api/v1/customers/orders/stats
```

Returns `totalOrders`, `deliveredOrders`, `cancelledOrders`, `totalSpent`, and `loyaltyPoints`.

