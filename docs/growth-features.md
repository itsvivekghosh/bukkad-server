# Growth & Operations Features (V13)

V13 adds delivery zones, home feed, membership, support tickets, order timeline, GST invoices, rider live map, restaurant busy mode, and wallet history.

## Delivery zones & serviceability

Zone-aware delivery fees use haversine distance from restaurant to delivery address. Seeded zone: **Bangalore Central** (15 km radius).

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | `/api/v1/serviceability/check` | Public | Check if restaurant delivers to lat/lng |

Query params: `restaurantId`, `latitude`, `longitude`, `subtotal` (optional).

```bash
curl "http://localhost:8080/api/v1/serviceability/check?restaurantId=1&latitude=12.9716&longitude=77.5946&subtotal=500"
```

Order pricing automatically uses the delivery address coordinates when placing an order.

## Home feed

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | `/api/v1/home/banners` | Public | Active promo banners |
| GET | `/api/v1/home/campaigns` | Public | Active promotion campaigns |
| GET | `/api/v1/home/membership-plans` | Public | Available membership plans |
| GET | `/api/v1/home/feed` | Public | Combined feed (banners + campaigns + plans) |

## Membership (Bhukkad One)

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | `/api/v1/customers/membership/plans` | Customer | List plans |
| GET | `/api/v1/customers/membership/status` | Customer | Active membership |
| POST | `/api/v1/customers/membership/subscribe` | Customer | Subscribe to a plan |

Membership benefits (free delivery, % discount) are applied automatically during order pricing.

## Support tickets

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/api/v1/customers/support/tickets` | Customer | Create ticket |
| GET | `/api/v1/customers/support/tickets` | Customer | List own tickets |
| GET | `/api/v1/admin/support/tickets` | Admin | List all tickets |
| PUT | `/api/v1/admin/support/tickets/{id}/status` | Admin | Update status |

## Order timeline & invoice

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | `/api/v1/orders/{orderId}/timeline` | Customer/Owner/Agent/Admin | Status history |
| GET | `/api/v1/orders/{orderId}/invoice` | Customer/Owner/Admin | GST invoice (after delivery) |

Timeline events are recorded on order placement, status changes, and delivery. Invoices are generated automatically when an order is marked delivered.

## Rider live map

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/api/v1/delivery/orders/{orderId}/location` | Delivery agent | Record GPS |
| GET | `/api/v1/orders/{orderId}/rider-location` | Customer/Owner/Admin | Latest GPS |

## Restaurant busy mode

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| PUT | `/api/v1/restaurants/owner/{id}/busy-mode` | Owner | Enable busy mode |
| DELETE | `/api/v1/restaurants/owner/{id}/busy-mode` | Owner | Disable busy mode |

When busy mode is active, new orders are rejected until `busyUntil` expires or busy mode is cleared.

## Wallet history

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | `/api/v1/customers/wallet/transactions` | Customer | Paginated wallet ledger |

## Database migration

Schema changes are in `V13__growth_operations.sql` (zones, tickets, invoices, banners, membership, timeline, rider locations, campaigns, fraud events, busy mode, cancellation metadata).

## Related docs

- [API Usage](./api-usage.md) — curl examples and auth
- [Advanced Features](./advanced-features.md) — V9–V12 platform features
- [Optimizations](./optimizations.md) — Kafka, cache, GEO index
