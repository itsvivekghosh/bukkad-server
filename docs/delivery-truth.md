# V14: Delivery Truth

Smarter ETA, zone surge rules, and live rider map enhancements.

## Smarter ETA (V14)

ETA now factors in:

| Factor | Effect |
|--------|--------|
| Order status | Per-stage time estimates |
| Restaurant busy mode | Adds `extraPrepMinutes` |
| Traffic heuristic | Lunch (+15%), dinner (+25%), morning (+10%) |
| Zone surge | Peak-hour surge rules per zone |
| Rider GPS | Haversine distance at ~36 km/h when out for delivery |

Each ETA includes a **confidence band** (default ±5 minutes) and is persisted as a snapshot for accuracy tracking.

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | `/api/v1/delivery-truth/orders/{orderId}/eta` | Customer/Owner/Agent/Admin | ETA detail + history |

Rider location updates (`POST /delivery/orders/{id}/location`) now sync agent coordinates and refresh live ETA automatically.

## Dynamic zones & surge (V14)

Zone fees use:

- Base fee + per-km distance × **effective surge** (max of zone base surge and time-of-day rules)
- `freeDeliveryAbove` subtotal threshold per zone
- Peak-hour rules in `zone_surge_rules` (seeded: lunch 12–14, dinner 19–22 for Bangalore)

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | `/api/v1/serviceability/check` | Public | Serviceability + fee estimate |
| GET | `/api/v1/admin/zones` | Admin | List zones |
| POST | `/api/v1/admin/zones` | Admin | Create zone |
| PUT | `/api/v1/admin/zones/{id}` | Admin | Update zone |
| DELETE | `/api/v1/admin/zones/{id}` | Admin | Delete zone |

## Configuration

```yaml
app:
  delivery-truth:
    avg-speed-km-per-min: 0.6
    pickup-buffer-minutes: 8
    confidence-band-minutes: 5
    record-snapshots: true
```

## Database

`V14__delivery_truth.sql` — `order_eta_snapshots`, `zone_surge_rules`, `delivery_zones.free_delivery_above`

## Related

- [Growth Features](./growth-features.md) — V13 foundation
- [Scale Operations](./scale-operations.md) — V16 settlement & batching
