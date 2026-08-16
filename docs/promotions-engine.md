# Promotions Engine (V15)

Full promotions engine with campaign rules, usage limits, admin CRUD, and checkout integration.

## Campaign types

| Type | Field | Behavior |
|------|-------|----------|
| `PERCENT_OFF` | `discountPercent` | % off subtotal, capped by `maxDiscountAmount` |
| `FLAT_OFF` | `flatDiscountAmount` | Fixed ₹ discount |
| `FREE_DELIVERY` | `freeDelivery: true` | Waives delivery fee |

## Engine rules

The `PromotionEngineService` evaluates all active campaigns and selects the **best** discount (highest ₹ value):

- **Eligibility**: min order amount, restaurant scope, global usage limit, per-user limit
- **Stacking**: Best campaign + membership discount + coupon (coupon applied separately)
- **Usage tracking**: `campaign_usages` table records redemptions

## Public / customer APIs

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/v1/home/campaigns` | Active campaigns for home feed |
| GET | `/api/v1/home/feed` | Banners + campaigns + membership plans |

Campaign discounts are applied automatically at checkout via `OrderPricingServiceImpl`.

## Admin APIs

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/v1/admin/promotions/campaigns` | List all campaigns |
| POST | `/api/v1/admin/promotions/campaigns` | Create campaign |
| PUT | `/api/v1/admin/promotions/campaigns/{id}` | Update campaign |
| DELETE | `/api/v1/admin/promotions/campaigns/{id}` | Deactivate campaign |
| GET | `/api/v1/admin/promotions/banners` | List banners |
| POST | `/api/v1/admin/promotions/banners` | Create banner |
| PUT | `/api/v1/admin/promotions/banners/{id}` | Update banner |

### Create campaign example

```json
POST /api/v1/admin/promotions/campaigns
{
  "name": "Weekend Feast",
  "campaignType": "PERCENT_OFF",
  "description": "15% off orders above ₹300",
  "discountPercent": 15.0,
  "minOrderAmount": 300.0,
  "maxDiscountAmount": 100.0,
  "priority": 20,
  "usageLimit": 1000,
  "perUserLimit": 3,
  "isActive": true
}
```

## Database

`V15__promotions_engine.sql` — extends `promotion_campaigns`, adds `campaign_usages`

## Related

- [Growth Features](./growth-features.md) — membership & home feed
- [API Usage](./api-usage.md) — checkout flow
