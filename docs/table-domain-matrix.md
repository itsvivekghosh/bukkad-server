# Table→Domain Matrix (Phase 2 — Vertical Partitioning)

Every table in the `bhukkad` database is owned by exactly one domain. Cross-domain
data sharing happens through events (outbox → Redpanda), not SQL joins. The ArchUnit
test `NoCrossDomainJoinArchTest` enforces this.

## Ownership

| Table | Owning Domain | Notes |
|---|---|---|
| `orders`, `order_items`, `order_item_customizations` | **ORDER** | Core order lifecycle |
| `order_eta_snapshots`, `order_invoices` | **ORDER** | ETA tracking + invoice generation |
| `order_timeline_events` | **ORDER** | Audit trail per order |
| `group_orders`, `group_order_members`, `group_order_participants` | **ORDER** | Group ordering |
| `gift_orders`, `gift_cards` | **ORDER** | Gift orders |
| `subscription_plans`, `subscription_deliveries` | **ORDER** | Subscriptions |
| `carts`, `cart_items`, `cart_item_customizations` | **ORDER** | Cart (pre-order) |
| `payments`, `wallet_transactions` | **PAYMENT** | Transaction records |
| `disputes` | **PAYMENT** | Payment disputes |
| `idempotency_records` | **PAYMENT** | Idempotency for payment/order creation |
| `restaurants`, `restaurant_owners` | **RESTAURANT/MENU** | Restaurant profile & ownership |
| `restaurant_cuisines`, `restaurant_features`, `restaurant_food_types`, `restaurant_gallery` | **RESTAURANT/MENU** | Restaurant metadata |
| `menu_items`, `menu_categories`, `menu_versions` | **RESTAURANT/MENU** | Menu catalog |
| `menu_item_allergens`, `menu_item_images`, `menu_item_ingredients`, `menu_item_tags` | **RESTAURANT/MENU** | Menu item metadata |
| `menu_item_ratings` | **RESTAURANT/MENU** | Per-item ratings |
| `customization_options`, `customization_choices` | **RESTAURANT/MENU** | Menu customization |
| `inventory_alerts` | **RESTAURANT/MENU** | Ingredient stock alerts |
| `customers`, `users`, `addresses` | **CUSTOMER** | Core customer profile |
| `device_tokens`, `consent_records` | **CUSTOMER** | Push tokens & GDPR consent |
| `customer_memberships`, `membership_plans` | **CUSTOMER** | Loyalty/membership |
| `customer_notification_preferences` | **CUSTOMER** | Notification opt-in/out |
| `affiliate_codes`, `affiliate_referrals`, `user_referral_codes` | **CUSTOMER** | Referral program |
| `favorite_restaurants` | **CUSTOMER** | Customer favourites |
| `delivery_agents`, `agent_cod_wallets`, `agent_shifts` | **DELIVERY** | Rider profile & COD |
| `rider_delivery_batches`, `rider_delivery_batch_orders` | **DELIVERY** | Batch dispatch |
| `rider_earnings` | **DELIVERY** | Rider payout |
| `rider_location_updates` | **DELIVERY** | GPS tracking |
| `delivery_surveys`, `order_delivery_proofs` | **DELIVERY** | Delivery quality |
| `delivery_zones`, `zone_surge_rules` | **DELIVERY** | Geo-fencing & surge |
| `reviews`, `review_images` | **REVIEW** | Customer reviews |
| `coupons`, `coupon_usages` | **PROMOTION/MARKETING** | Discounts |
| `promo_banners`, `promotion_campaigns`, `campaign_usages` | **PROMOTION/MARKETING** | Campaigns |
| `dynamic_pricing_rules` | **PROMOTION/MARKETING** | Dynamic pricing |
| `settlement_runs`, `restaurant_settlements` | **SETTLEMENT** | Payout runs |
| `restaurant_order_stats`, `restaurant_ratings_summary` | **ADMIN/ANALYTICS** | Materialized views |
| `churn_scores`, `fraud_events`, `fraud_review_queue` | **ADMIN/ANALYTICS** | Risk & churn |
| `data_export_requests` | **ADMIN/ANALYTICS** | GDPR exports |
| `experiment_exposures` | **ADMIN/ANALYTICS** | A/B testing |
| `support_tickets` | **ADMIN/ANALYTICS** | Customer support |
| `api_keys`, `audit_events` | **ADMIN/ANALYTICS** | Admin access |
| `cities`, `city_configs` | **ADMIN/ANALYTICS** | Geography config |
| `tenants` | **ADMIN/ANALYTICS** | Multi-tenancy |
| `outbox_events`, `dead_letter_events` | **PLATFORM** | Shared outbox infrastructure |
| `saga_instances`, `saga_steps` | **PLATFORM** | Saga orchestration |

## Allowlisted cross-domain queries (pre-existing, documented in ArchUnit)

See `NoCrossDomainJoinArchTest` for the exact method-level allowlist. These are
being replaced by event-driven materialization in Phase 2 + Phase 3.