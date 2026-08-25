# Discovery API — Home feed, search, restaurants, menu, coupons

These endpoints power the anonymous browsing experience. Almost all are
public `GET`s served from cache/read-replica; the mutating menu/restaurant
endpoints require `RESTAURANT_OWNER`.

---

## Home feed (public)

### GET /home/feed

**1. Overview** — The app's landing payload: active banners, promotion
campaigns, membership plans, and trending dishes, assembled and cached
server-side. The primary "app open" call.

**2. Request** — `GET /api/v1/home/feed`. No auth, no params, no body.

**3. Response — `200 OK`:** `ApiResponse<Map>` with keys:

| Key | Type | Description |
|---|---|---|
| `banners` | array\<PromoBannerResponse\> | Carousel banners |
| `campaigns` | array\<PromotionCampaignResponse\> | Active promotions |
| `membershipPlans` | array\<MembershipPlanResponse\> | Buyable membership plans |
| `trendingDishes` | array\<TrendingDishResponse\> | `{id, name, orderItemCount}` |

`PromoBannerResponse`: `id, title, subtitle, imageUrl, actionType, actionTarget, displayOrder`.

`MembershipPlanResponse`: `id, name, description, pricePerMonth, freeDelivery, discountPercent, tierLevel, maxDiscountPercent, referralBonusPercent, referralMaxPerMonth`.

`PromotionCampaignResponse` (also on `/home/campaigns`): `id, name, campaignType, description, discountPercent, flatDiscountAmount, minOrderAmount, maxDiscountAmount, restaurantId, freeDelivery, priority, usageLimit, perUserLimit, isActive, startsAt, endsAt, buyQuantity, getQuantity, getDiscountPercent, targetSegment, applicableMenuItemId`.

**4. Example**

```json
{
  "success": true,
  "data": {
    "banners": [ { "id": 1, "title": "Monsoon sale", "subtitle": "30% off",
                   "imageUrl": "https://cdn.example.com/b1.jpg", "actionType": "DEEP_LINK", "actionTarget": "bhukkad://promo/1", "displayOrder": 1 } ],
    "campaigns": [ { "id": 5, "name": "FLAT100", "campaignType": "DISCOUNT",
                     "description": "Flat ₹100 off", "discountPercent": null,
                     "flatDiscountAmount": 100.0, "minOrderAmount": 499.0,
                     "maxDiscountAmount": 100.0, "restaurantId": null,
                     "freeDelivery": false, "priority": 1, "usageLimit": 10000,
                     "perUserLimit": 1, "isActive": true, "startsAt": "2026-08-01T00:00:00",
                     "endsAt": "2026-08-31T23:59:59", "buyQuantity": null,
                     "getQuantity": null, "getDiscountPercent": null,
                     "targetSegment": "ALL", "applicableMenuItemId": null } ],
    "membershipPlans": [ { "id": 1, "name": "Bhukkad Plus", "description": "Free delivery",
                           "pricePerMonth": 199.0, "freeDelivery": true, "discountPercent": 5.0,
                           "tierLevel": 2, "maxDiscountPercent": 10.0, "referralBonusPercent": 5.0,
                           "referralMaxPerMonth": 500 } ]
  }
}
```

**4. Errors** — `429` if rate limited (`mobile-feed` on `/mobile/feed`); `500` on cache/db failure (feed degrades to empty lists).

### GET /home/banners · GET /home/campaigns · GET /home/membership-plans
Individual slices of the feed: `ApiResponse<PromoBannerResponse[]>`,
`ApiResponse<PromotionCampaignResponse[]>`,
`ApiResponse<MembershipPlanResponse[]>`.

### GET /mobile/feed
Same composite payload as `/home/feed`, shaped for the mobile app. Rate
limited: `mobile-feed` (30/60s).

---

## Search (public, rate-limited `search` 30/60s)

### GET /search

**1. Overview** — Unified search across restaurants and menu items.

**2. Request** — `GET /api/v1/search?keyword=pizza`

| Param | Type | Required | Description |
|---|---|---|---|
| `keyword` | string | Yes | Search text |

**3. Response — `200 OK`:** `ApiResponse<UnifiedSearchResponse>` —

| Field | Type | Description |
|---|---|---|
| `restaurants` | array\<RestaurantResponse\> | Matching restaurants |
| `menuItems` | array\<MenuItemResponse\> | Matching menu items |
| `restaurantCount` | number | Count |
| `menuItemCount` | number | Count |

**4. Errors** — `429`; `400` missing keyword.

### GET /search/suggest
Authenticated (any role). `GET /api/v1/search/suggest?q=piz&limit=8` —
`limit` optional. Returns `ApiResponse<AutocompleteSuggestion[]>` — each
`{text, type}` where `type` is `RESTAURANT` or `MENU_ITEM`.

---

## Restaurants

`RestaurantResponse` (shared shape) — full field list:

```
id, name, description, address(AddressResponse), cuisines[], imageUrl,
galleryImages[], openingTime, closingTime, isOpen, isActive, averageRating,
totalReviews, averageDeliveryTime, minimumOrderAmount, deliveryFee,
freeDeliveryAvailable, freeDeliveryAbove, isPureVeg, foodTypes[], features[],
virtualBrandName, onboardingStatus, tenantId
```

### GET /restaurants/public — list / batch
**1. Overview** — All active restaurants, or a batch by ids. Supports HTTP
conditional caching.

**2. Request** — `GET /api/v1/restaurants/public`
Headers: optional `If-None-Match`, `If-Modified-Since`, `X-Tenant-Id`.

| Param | Type | Required | Description |
|---|---|---|---|
| `ids` | repeated number | No | Batch by ids (max 100) |

**3. Response** — `200 OK` `ApiResponse<RestaurantResponse[]>` with ETag
headers, or `304 Not Modified`.

### GET /restaurants/public/{id} — detail
`ApiResponse<RestaurantResponse>`. `404` when missing.

### GET /restaurants/public/nearby — geo
`GET /api/v1/restaurants/public/nearby?latitude=12.97&longitude=77.59&radiusKm=5&limit=20`
Params: `latitude`, `longitude` (required numbers), `radiusKm` (default 5),
`limit` (default 20). Rate-limited `search`. Returns
`ApiResponse<RestaurantResponse[]>`.

### GET /restaurants/public/search — text
`?keyword=pizza` (required). Rate-limited `search`.

### GET /restaurants/public/filter
`?cuisineId=` (optional) & `?isPureVeg=` (optional boolean).

### Owner endpoints (RESTAURANT_OWNER, base `/restaurants/owner`)

| Endpoint | Purpose |
|---|---|
| `POST /restaurants/owner` | Create restaurant — `RestaurantRequest` body |
| `POST /restaurants/onboarding/signup` | Dark-kitchen onboarding (starts `PENDING_VERIFICATION`) |
| `GET /restaurants/onboarding/status` | `{restaurants:[{restaurantId, name, onboardingStatus, rejectionReason, isActive}]}` |
| `GET /restaurants/owner/my-restaurants` | `ApiResponse<RestaurantResponse[]>` |
| `PUT /restaurants/owner/{id}` | Update — `RestaurantRequest` |
| `DELETE /restaurants/owner/{id}` | Deactivate |
| `PUT /restaurants/owner/{id}/toggle-status?isOpen=true` | Open/close |
| `PUT /restaurants/owner/{id}/busy-mode` | Body `{busyUntil, extraPrepMinutes}` — reject orders while busy |
| `DELETE /restaurants/owner/{id}/busy-mode` | Clear busy mode |
| `GET /restaurants/owner/{id}/analytics?days=30` | Revenue/orders/top items/daily+hourly stats |
| `GET /restaurants/owner/{id}/dashboard?days=30` | Dashboard incl. pending settlements + live ETA |
| `GET /restaurants/owner/{id}/settlements?page&size` | Paged settlements |
| `GET /restaurants/owner/{id}/settlements/cursor?cursor&size` | Cursor settlements |
| `POST /restaurants/owner/reviews/{reviewId}/response` | Body `{"response":"reply"}` (max 2000 chars) |

**`RestaurantRequest` body (create/update):**

```json
{
  "name": "Spice Route",
  "description": "North Indian",
  "address": { "addressLine1": "12 MG Road", "city": "Bengaluru", "state": "Karnataka",
               "pincode": "560001", "latitude": 12.9716, "longitude": 77.5946 },
  "cuisineIds": [1, 2],
  "imageUrl": "https://cdn.example.com/r.jpg",
  "openingTime": "10:00:00",
  "closingTime": "23:00:00",
  "averageDeliveryTime": 35,
  "minimumOrderAmount": 149.0,
  "deliveryFee": 35.0,
  "freeDeliveryAbove": 499.0,
  "isPureVeg": false,
  "foodTypes": ["INDIAN"],
  "features": ["Parking"],
  "licenseNumber": "FSSAI-123",
  "fssaiNumber": "FSSAI-123"
}
```

Required: `name`, `address` (with non-blank `addressLine1/city/state/pincode`
+ non-null lat/lng), `openingTime`, `closingTime`. Everything else optional.

**Errors:** `400` validation / `404` restaurant not owned; `403` wrong role.

---

## Menu

`MenuItemResponse` (shared shape) — full field list:

```
id, name, description, categoryName, price, originalPrice, discountPercentage,
available, foodType(VEG|NON_VEG|VEGAN|EGGETARIAN), isVeg, isSpicy,
spiceLevel(MILD|MEDIUM|HOT|EXTRA_HOT), allergens[], imageUrl, additionalImages[],
preparationTime, bestseller, recommended, calories, servingSize, ingredients[],
averageRating, totalRatings, customizationOptions[{id, name, required,
multipleSelection, minSelection, maxSelection, choices[{id, name, additionalPrice, available}]}],
tags[]
```

### Public reads (no auth)

| Endpoint | Notes |
|---|---|
| `GET /menu/items` | `?ids=` repeated, max 100 |
| `GET /menu/items/{id}` | ETag-cacheable |
| `GET /menu/items/category/{categoryId}` | ETag-cacheable |
| `GET /menu/items/restaurant/{restaurantId}` | The full menu for a restaurant |
| `GET /menu/items/restaurant/{restaurantId}/bestsellers` | Best sellers |
| `GET /menu/items/restaurant/{restaurantId}/recommended` | Recommended |
| `GET /menu/items/search?keyword=` | Rate-limited `search` |
| `GET /menu/categories/restaurant/{restaurantId}` | Categories with `itemCount` |
| `GET /cuisines` · `GET /cuisines/{id}` | Cuisine list |

### Owner writes (RESTAURANT_OWNER)

| Endpoint | Purpose |
|---|---|
| `POST /menu/categories?restaurantId=` | Body `{name, description?, displayOrder?, active?}` |
| `PUT /menu/categories/{categoryId}` | Update category |
| `DELETE /menu/categories/{categoryId}` | Delete category |
| `POST /menu/items` | Create item (schema below) |
| `PUT /menu/items/{id}` | Update item |
| `DELETE /menu/items/{id}` | Delete item |
| `PUT /menu/items/{id}/toggle-availability?available=false` | Hide/show |
| `POST /menu/items/{id}/image/upload-url` | Body `{"contentType":"image/jpeg"}` → `{uploadUrl, imageKey, expiresInSeconds}` (presigned S3 PUT; put the image, then set `imageKey` on the item) |
| `GET /menu/items/restaurant/{restaurantId}/low-stock?threshold=` | Low-stock list |

**`POST /menu/items` body (create/update):**

```json
{
  "name": "Butter Chicken",
  "description": "Creamy tomato gravy",
  "categoryId": 3,
  "price": 320.0,
  "originalPrice": 360.0,
  "foodType": "NON_VEG",
  "isVeg": false,
  "isSpicy": true,
  "spiceLevel": "MEDIUM",
  "allergens": ["MILK", "NUTS"],
  "imageUrl": null,
  "imageKey": "menu-items/12/butter-chicken.jpg",
  "preparationTime": 15,
  "calories": 450,
  "servingSize": "1 bowl",
  "ingredients": ["chicken", "butter", "cream"],
  "tags": ["bestseller"],
  "stockQuantity": 50,
  "customizationOptions": [
    { "name": "Add-ons", "required": false, "multipleSelection": true,
      "choices": [ { "name": "Extra butter", "additionalPrice": 30.0, "available": true } ] }
  ]
}
```

Required: `name`, `categoryId`, `price` (>0), `foodType`, `isVeg`.

**Errors:** `400` validation; `403` owner doesn't own the category/restaurant;
`404` missing.

---

## Coupons

### GET /coupons/active (public)
`GET /api/v1/coupons/active?restaurantId=` — `restaurantId` optional filter.
Returns `ApiResponse<CouponResponse[]>`.

`CouponResponse`: `id, code, description, discountType(PERCENTAGE|FIXED_AMOUNT),
discountValue, minimumOrderAmount, maximumDiscountAmount, validFrom, validUntil`.

### GET /coupons/validate (CUSTOMER)
`GET /api/v1/coupons/validate?code=WELCOME50&orderAmount=640&restaurantId=12`
Returns `ApiResponse<CouponResponse>` if valid, `400` otherwise.

### POST /coupons (ADMIN or RESTAURANT_OWNER)

```json
{
  "code": "WELCOME50",
  "description": "50% off up to ₹100",
  "discountType": "PERCENTAGE",
  "discountValue": 50.0,
  "minimumOrderAmount": 199.0,
  "maximumDiscountAmount": 100.0,
  "validFrom": "2026-09-01T00:00:00",
  "validUntil": "2026-09-30T23:59:59",
  "usageLimit": 5000,
  "perUserLimit": 1,
  "restaurantId": null
}
```

### PUT /coupons/{couponId} (ADMIN) · DELETE /coupons/{couponId} (ADMIN)
Update with the same body / delete.

---

## Serviceability, platform & recommendations

### GET /serviceability/**
Public — check delivery serviceability by pincode/city/address.

### GET /platform/status · GET /platform/cities · GET /platform/tenants/**
Public — platform status, supported cities, tenant (white-label) info.

### GET /customers/me/recommendations
Authenticated CUSTOMER — personalized restaurant/menu recommendations.
