# Account API — Customer profile, wallet, addresses, loyalty, referrals, subscriptions

All endpoints require `Authorization: Bearer <accessToken>` and the
`CUSTOMER` role unless noted. Base path `/api/v1/customers` (referrals:
`/api/v1/referrals`, subscriptions: `/api/v1/customers/subscriptions`,
affiliates: `/api/v1/admin/affiliates`).

---

## Profile

### GET /customers/profile

**1. Overview** — Returns the authenticated customer's full profile including
saved addresses and order count.

**2. Request** — `GET /api/v1/customers/profile` · Headers: `Authorization`.
No params, no body.

**3. Response — `200 OK`:** `ApiResponse<CustomerProfileResponse>`

| Field | Type | Description |
|---|---|---|
| `id` | number | Customer id |
| `email` | string | Email |
| `fullName` | string | Full name |
| `phoneNumber` | string | Phone |
| `profileImageUrl` | string | Avatar URL |
| `active` | boolean | Account active |
| `emailVerified` | boolean | Email verified |
| `loyaltyPoints` | number | Loyalty balance |
| `walletBalance` | number | Wallet balance |
| `role` | string | `CUSTOMER` |
| `createdAt` | string | Creation timestamp |
| `addresses` | array\<AddressResponse\> | Saved addresses |
| `totalOrders` | number | Order count |

**4. Example**

```json
{
  "success": true,
  "data": {
    "id": 101, "email": "aarav@example.com", "fullName": "Aarav Sharma",
    "phoneNumber": "9876543210", "profileImageUrl": null, "active": true,
    "emailVerified": true, "loyaltyPoints": 120, "walletBalance": 250.0,
    "role": "CUSTOMER", "createdAt": "2026-01-01T10:00:00",
    "addresses": [], "totalOrders": 14
  }
}
```

### GET /customers/profile/{profileId}

**1. Overview** — Returns another customer's profile by id (used for social
features). **2. Request** — `GET /api/v1/customers/profile/{profileId}` ·
Path: `profileId` (number, required, must be positive). **3. Response** —
`ApiResponse<CustomerProfileResponse>`. **4. Errors** — `400` invalid id,
`404` not found.

### PUT /customers/profile

**1. Overview** — Updates the caller's profile fields. **2. Request** —
`PUT /api/v1/customers/profile` · Query params (all optional): `fullName`
(string), `phoneNumber` (string), `profileImageUrl` (string). No body.
**3. Response — `200 OK`:** `ApiResponse<CustomerResponse>` (same shape as
profile minus `totalOrders`). **4. Errors** — `400` validation, `404` user.

### DELETE /customers/account

**1. Overview** — Deactivates the caller's account (soft delete). **2.
Request** — `DELETE /api/v1/customers/account`. **3. Response** — `200 OK`,
`data: null`. **4. Errors** — `404` user.

---

## Addresses

### POST /customers/addresses

**1. Overview** — Creates a delivery address for the caller.

**2. Request** — `POST /api/v1/customers/addresses` · `Content-Type: application/json`

```json
{
  "addressLine1": "12, MG Road",
  "addressLine2": "3rd Floor",
  "city": "Bengaluru",
  "state": "Karnataka",
  "pincode": "560001",
  "landmark": "Near Metro",
  "type": "HOME",
  "label": "Home",
  "latitude": 12.9716,
  "longitude": 77.5946,
  "isDefault": true
}
```

| Field | Type | Required | Description |
|---|---|---|---|
| `addressLine1` | string | Yes | Street line 1 |
| `addressLine2` | string | No | Street line 2 |
| `city` | string | Yes | City |
| `state` | string | Yes | State |
| `pincode` | string | Yes | Postal code |
| `landmark` | string | No | Landmark |
| `type` | string | No | `HOME` \| `WORK` \| `OTHER` |
| `label` | string | No | Custom label |
| `latitude` | number | Yes | GPS latitude |
| `longitude` | number | Yes | GPS longitude |
| `isDefault` | boolean | No | Set as default (default `false`) |

**3. Response — `200 OK`:** `ApiResponse<AddressResponse>` — `id` (number),
then the request fields + `isDefault`.

**4. Errors** — `400` validation (blank city/state/pincode, null lat/lng).

### GET /customers/addresses
Returns `ApiResponse<AddressResponse[]>` for the caller. No params.

### PUT /customers/addresses/{addressId}
Body = same `AddressRequest` schema as POST. Returns updated
`ApiResponse<AddressResponse>`. `400` if not owned by caller / `404`.

### DELETE /customers/addresses/{addressId}
Returns `ApiResponse<Void>`.

### PUT /customers/addresses/{addressId}/set-default
Promotes an address to default; returns `ApiResponse<AddressResponse>`.

---

## Wallet

### GET /customers/wallet/balance
**1. Overview** — Returns the wallet balance. **2. Request** —
`GET /api/v1/customers/wallet/balance`. **3. Response — `200 OK`:**
`ApiResponse<number>` — `data` is the balance, e.g. `250.0`.

### POST /customers/wallet/top-up

**1. Overview** — Initiates a wallet top-up via the payment gateway (Razorpay
when enabled). Returns a `PaymentResponse` with a `gatewayOrderId` you pass to
the payment SDK; the wallet is credited when the gateway webhook confirms.

**2. Request** — `POST /api/v1/customers/wallet/top-up?amount=500` ·
Headers: `Authorization`; optional `Idempotency-Key: <uuid>`.

| Param | Type | Required | Description |
|---|---|---|---|
| `amount` | number | Yes | Top-up amount, positive |

**3. Response — `200 OK`:** `ApiResponse<PaymentResponse>`

| Field | Type | Description |
|---|---|---|
| `id` | number | Payment record id |
| `orderId` | number \| null | Order id (null for top-up) |
| `paymentMethod` | string | e.g. `UPI` |
| `status` | string | `PENDING` until webhook confirms |
| `amount` | number | Top-up amount |
| `gatewayOrderId` | string | Gateway order id for the payment SDK |
| `gatewayPaymentId` | string \| null | Set after capture |
| `transactionId` | string \| null | Set after completion |
| `purpose` | string | `WALLET_TOP_UP` |

**4. Errors** — `400` validation / gateway disabled; `409` duplicate
`Idempotency-Key` in flight.

### POST /customers/wallet/add-money
**1. Overview** — Directly credits the wallet (dev/admin; gated by
`app.wallet.allow-direct-top-up`). **2. Request** —
`POST /api/v1/customers/wallet/add-money?amount=100`. **3. Response** —
`ApiResponse<Void>`. **4. Errors** — `403` when direct top-up disabled.

---

## Loyalty, referral & favorites

### GET /customers/loyalty-points
`ApiResponse<number>` — current loyalty points.

### GET /customers/referral
`ApiResponse<ReferralInfoResponse>` — `referralCode` (string),
`referralsCount` (number), `referralBonusEarned` (number).

### POST /referrals/generate
Authenticated (any role). Creates/returns the caller's referral code.
`ApiResponse<string>` — `data` = the code. Service rate-limit per user.

### POST /referrals/validate
Body `{"referralCode": "ARAV50"}`. Returns `ApiResponse<boolean>` — `true`
if the code belongs to an active customer.

### GET /referrals/rewards
`ApiResponse<ReferralInfoResponse>` — the caller's rewards summary.

### GET /customers/favorites
`ApiResponse<FavoriteRestaurantResponse[]>` — each: `restaurantId`,
`restaurantName`, `imageUrl`, `averageRating`, `isOpen`.

### POST /customers/favorites/{restaurantId}
Adds a favorite. Path: `restaurantId` (number). Returns
`ApiResponse<FavoriteRestaurantResponse>`.

### DELETE /customers/favorites/{restaurantId}
Removes a favorite. Returns `ApiResponse<Void>`.

### GET /customers/orders/stats
`ApiResponse<CustomerOrderStatsResponse>` — `totalOrders` (number),
`deliveredOrders` (number), `cancelledOrders` (number), `totalSpent`
(number), `loyaltyPoints` (number).

### GET /customers/data-export
`ApiResponse<Map>` — DPDP/GDPR-style export of profile, addresses, wallet,
loyalty, and order history.

---

## Notification preferences & devices

### GET /customers/notification-preferences
`ApiResponse<NotificationPreferenceResponse>` — booleans: `emailEnabled`,
`smsEnabled`, `pushEnabled`, `whatsappEnabled`, `orderUpdatesEnabled`,
`promotionsEnabled`.

### PUT /customers/notification-preferences
Body: same six booleans. Returns `ApiResponse<NotificationPreferenceResponse>`.
(Note: this endpoint does not apply `@Valid` — send all fields.)

### POST /customers/device-tokens
Body `{"token": "<push-token>", "platform": "ANDROID"}` (`token` non-blank,
`platform` required). Returns `ApiResponse<DeviceTokenResponse>` — `id`,
`platform`, `active`.

### DELETE /customers/device-tokens
Body `{"token": "<push-token>"}`. Returns `ApiResponse<Void>`.

---

## Subscriptions (recurring orders)

Base `/api/v1/customers/subscriptions` (CUSTOMER).

### POST /customers/subscriptions — create plan

```json
{
  "restaurantId": 12,
  "title": "Weekday lunch",
  "weekday": "MON",
  "deliveryTime": "13:00:00",
  "deliveryAddressId": 5,
  "paymentMethod": "WALLET",
  "startDate": "2026-09-01",
  "items": [ { "menuItemId": 42, "quantity": 2 } ]
}
```

| Field | Type | Required | Description |
|---|---|---|---|
| `restaurantId` | number | Yes | Restaurant |
| `title` | string | No | max 100 chars |
| `weekday` | string | Yes | `MON`..`SUN` (case-insensitive) |
| `deliveryTime` | string | Yes | LocalTime `HH:mm:ss` |
| `deliveryAddressId` | number | Yes | Saved address |
| `paymentMethod` | string | Yes | Payment method |
| `startDate` | string | Yes | `yyyy-MM-dd` |
| `items` | array | Yes, min 1 | `{menuItemId, quantity>0}` |

**Response — `200 OK`:** `ApiResponse<SubscriptionPlanResponse>` — `id`,
`restaurantId`, `title`, `weekday`, `deliveryTime`, `deliveryAddressId`,
`paymentMethod`, `status` (`ACTIVE|PAUSED|CANCELLED`), `startDate`,
`nextDeliveryDate`, `deliveries` (`[{id, orderId, scheduledDate, status}]`
with status `PENDING|PLACED|SKIPPED|FAILED`).

### GET /customers/subscriptions
`ApiResponse<SubscriptionPlanResponse[]>` — the caller's plans.

### POST /customers/subscriptions/{id}/pause · /resume · /cancel · /skip
Path: `id` (number). Each returns `ApiResponse<SubscriptionPlanResponse>`
with the updated `status` (skip bumps `nextDeliveryDate` past the next
delivery).

---

## Affiliates (admin)

Base `/api/v1/admin/affiliates` (ADMIN only).

| Endpoint | Purpose |
|---|---|
| `GET /admin/affiliates` | List all affiliate codes (`AffiliateCodeResponse[]`: `id, code, name, channel, rewardAmount, isActive, createdAt`) |
| `POST /admin/affiliates` | Create — body `{code, name, channel?, rewardAmount, isActive?}` |
| `PUT /admin/affiliates/{affiliateId}` | Update — same body |
| `DELETE /admin/affiliates/{affiliateId}` | Deactivate |
| `GET /admin/affiliates/{affiliateId}/stats` | `{affiliateCodeId, code, name, totalReferrals, paidReferrals, totalReward, recentReferrals[]}` |
