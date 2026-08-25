# Orders Sharding Strategy (Phase 2)

## Shard key: `city_id` (with `customer_id` fallback)

`orders` is the hottest table. When it must be split across physical
databases, use **`city_id`** as the primary shard key:

- `orders` already carries `restaurant_id`; the restaurant's `city_id` is the
  natural locality anchor (orders are placed and delivered within a city).
- Reads are city-scoped: the home feed, restaurant menus, delivery dispatch,
  surge pricing and admin dashboards all query by city. City-local sharding
  keeps those reads single-shard.
- `customer_id` is the fallback key for the customer's order-history queries
  (profile pages, reorder). These are cross-city by nature and are served via
  a customer→shard index (Redis or a lookup table), never a scatter-gather.

### Hash function

```
shard = CRC32(city_id) % NUM_SHARDS
```

- Deterministic, stable across restarts, no rehashing on rebalance if
  `NUM_SHARDS` is a power of two and doubling is used.
- Documented in the migration runbook: `customer_id` variant is
  `CRC32(customer_id) % NUM_SHARDS` for the customer-orders index.

## Range partition by `created_at` (retention/archival)

Independent of sharding, `orders.created_at` is range-partitioned in
`orders_archive` (V55, quarterly partitions + `p_future`) for retention:
old orders are moved by `OrderArchiveService` (ShedLock-guarded, bounded
batch) and the oldest partitions are dropped with `ALTER TABLE ... DROP
PARTITION` — no bulk DELETE. `OrderArchivePartitionsTest` verifies the
boundaries (a row on the first day of a quarter belongs to the *next*
partition under `VALUES LESS THAN`).

## Constraints

- MySQL 8 partitioned InnoDB tables cannot have foreign keys — archived rows
  are copied to `orders_archive` and the source row deleted (application-level
  integrity, matching the domain-ownership split).
- Sharding is a Phase 3+ migration for `order-service`; the strategy is
  documented and the partition scaffolding is in place so the split does not
  require a schema redesign.