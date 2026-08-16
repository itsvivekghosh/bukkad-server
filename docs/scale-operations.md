# Scale Operations (V16)

Settlement automation, rider delivery batching, and dashboard 2.0 for admin and restaurant owners.

## Settlement automation

Automated daily settlement runs settle pending restaurant payouts and rider earnings when pending amount exceeds the configured minimum.

| Setting | Default | Description |
|---------|---------|-------------|
| `app.settlement.auto-settle-enabled` | `true` | Enable cron job |
| `app.settlement.min-pending-amount` | `100.0` | Min ₹ before auto-settle |
| `app.settlement.auto-settle-cron` | `0 0 2 * * *` | Daily at 2 AM |

Each run is recorded in `settlement_runs` with restaurants/agents settled and total amount.

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/api/v1/admin/settlements/run` | Admin | Trigger manual settlement run |
| GET | `/api/v1/admin/operations-dashboard` | Admin | Ops dashboard 2.0 |

## Rider delivery batching

Groups up to 3 nearby active orders into a multi-stop batch for efficient delivery.

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/api/v1/delivery/batches` | Agent | Create batch from active orders |
| GET | `/api/v1/delivery/batches/active` | Agent | Get current active batch |
| PUT | `/api/v1/delivery/batches/{id}/complete` | Agent | Mark batch complete |

Batching selects orders within 2 km of the anchor restaurant, max 3 stops.

## Admin operations dashboard 2.0

`GET /api/v1/admin/operations-dashboard` returns:

- Pending restaurant settlements (total ₹ + count)
- Pending rider payouts (total ₹ + count)
- Active delivery batches
- Orders by status
- Recent settlement runs
- ETA accuracy (snapshots last 24h, avg ETA minutes; V17 added measured/on-time counts, on-time rate, and avg late minutes — see [trust-and-compliance.md](./trust-and-compliance.md))

## Restaurant dashboard 2.0

`GET /api/v1/restaurants/owner/{id}/dashboard?days=30` combines:

- Revenue, orders, AOV (from analytics)
- Pending settlement amount
- Busy mode status + extra prep minutes
- Top menu items and daily revenue

## Database

`V16__scale_operations.sql` — `settlement_runs`, `rider_delivery_batches`, `rider_delivery_batch_orders`

## Related

- [Advanced Features](./advanced-features.md) — manual settlements (V10)
- [Delivery Truth](./delivery-truth.md) — V14 ETA & zones
- [Operations](./operations.md) — monitoring & alerting
