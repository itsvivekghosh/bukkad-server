# Migration Runbook: Monolith → Multi-Service (Phase 3)

This document describes the ORDER in which tables and code move from the
monolith (`bhukkad-delivery-system`) to the extracted services, so the
single-JAR monolith remains runnable until every service is proven.

## Principles

1. **One service at a time**. Each extraction is a merge commit.
2. **Dual-write / expand-contract** for each table: the monolith writes the old
   schema AND publishes an event; the new service reads the event, writes its
   own schema, and eventually the monolith's reader is removed.
3. **Nightly Regression** runs against the monolith profile (`-Pmonolith`)
   until the last service is extracted.
4. **No breaking API changes**. The gateway aggregates the monolith's and
   service's endpoints; clients see the same URLs and response shapes.

## Table → Service migration order

| Phase | Table(s) | To Service | Strategy |
|---|---|---|---|
| 3.1 | `outbox_events`, `dead_letter_events` | **bukkad-common** (library) | Move the outbox entity + repository + service into `bukkad-common`; monolith depends on the jar. |
| 3.2 | `saga_instances`, `saga_steps` | **bukkad-common** (library) | Same as outbox: the saga coordinator becomes a shared library. |
| 3.3 | `carts`, `cart_items`, `cart_item_customizations` | **order-service** | Dual-write: order-service writes cart events; monolith's CartController reads from order-service via internal REST (or gRPC). |
| 3.4 | `orders`, `order_items`, `order_item_customizations`, `order_eta_snapshots`, `order_invoices`, `order_timeline_events`, `group_orders`, `group_order_members`, `gift_orders`, `gift_cards`, `subscription_plans`, `subscription_deliveries` | **order-service** | The largest migration. Start with READ-ONLY: order-service serves order-detail endpoints; monolith still writes. Then dual-write, then cutover writes. |
| 3.5 | `payments`, `wallet_transactions`, `disputes`, `idempotency_records` | **payment-service** | Payment is the most sensitive — single-writer constraint. Extract last, keep the monolith proxy until the saga is proven. |
| 3.6 | `restaurants`, `menu_items`, `menu_categories`, `menu_versions`, `menu_item_*`, `customization_*`, `inventory_alerts` | **restaurant-service** | Bulk upload + menu versioning are already read-heavy; extract early to reduce monolith load. |
| 3.7 | `customers`, `users`, `addresses`, `device_tokens`, `consent_records`, `customer_memberships`, `membership_plans`, `affiliate_*`, `user_referral_codes`, `favorite_restaurants` | **customer-service** | Self-contained; extract after restaurant-service. |
| 3.8 | `delivery_agents`, `rider_*`, `delivery_surveys`, `order_delivery_proofs`, `agent_cod_wallets`, `agent_shifts`, `delivery_zones`, `zone_surge_rules` | **delivery-service** | Requires geo + route optimization; extract after payment-service. |
| 3.9 | `admin/analytics` tables, `churn_scores`, `fraud_events`, `compliance` tables, `experiment_exposures`, `support_tickets`, `data_export_requests`, `city_configs`, `tenants` | **admin-service** | Pure read-heavy; extract last (never on the hot path). |

## API contract gateway

The monolith's `/mobile/feed` BFF pattern (existing `MobileFeedController`) is
the gateway. Each extracted service exposes its own OpenAPI spec at
`/v3/api-docs`. The gateway:

1. Routes `/api/v1/orders/*` → order-service when the service is extracted.
2. Routes `/api/v1/restaurants/*` → restaurant-service when extracted.
3. Falls back to the monolith for any unextracted domain.
4. Aggregates OpenAPI specs into a single `/v3/api-docs` (springdoc group
   configuration).

## Verifying the cutover

For each extracted table:
1. Run the monolith's Nightly Regression against the monolith profile.
2. Run the extracted service's test suite (per-service CI job).
3. Run the Python API suite against the staging environment (monolith + service
   side by side).
4. Run the `Load Test` workflow to verify throughput/P99 remains within SLO.
5. Mark the migration complete in the table above.

## Rollback

If an extracted service fails in production:
1. Revert the gateway route to the monolith's endpoint.
2. The monolith's fallback profile (`-Pmonolith`) still runs the old code
   (the service's tables are still in the monolith's schema).
3. Fix the service, re-test, re-deploy.