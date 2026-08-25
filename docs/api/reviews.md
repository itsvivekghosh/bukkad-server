# Reviews API — Order reviews & menu-item ratings

Base path `/api/v1/reviews`. Public reads for restaurant/menu-item listings;
writes require `CUSTOMER`.

---

## POST /reviews

### 1. Endpoint Overview
Submits a review for a delivered order. Feeds the restaurant's average
rating (`restaurant_ratings_summary`). New reviews start in `PENDING`
moderation.

### 2. Request Specification

**Method & URL:** `POST /api/v1/reviews` · Headers: `Authorization` (CUSTOMER) + `Content-Type`.

```json
{
  "orderId": 4242,
  "rating": 4,
  "comment": "Great butter chicken, delivery was a bit slow",
  "foodRating": 5,
  "deliveryRating": 3,
  "images": ["https://cdn.example.com/review/1.jpg"]
}
```

| Field | Type | Required | Constraints | Description |
|---|---|---|---|---|
| `orderId` | number | Yes | — | Delivered order |
| `rating` | number | Yes | 1–5 | Overall rating |
| `comment` | string | No | — | Review text |
| `foodRating` | number | No | 1–5 | Food rating |
| `deliveryRating` | number | No | 1–5 | Delivery rating |
| `images` | string[] | No | — | Image URLs |

### 3. Response Specification

**Success — `200 OK`:** `ApiResponse<Review>` (message "Review submitted successfully").

`Review` (JPA entity serialized): `id, rating, comment, foodRating,
deliveryRating, images[], moderationStatus(PENDING|APPROVED|REJECTED),
ownerResponse, createdAt` (+ nested `customer`, `restaurant`, `order`).

**Errors:**

| Code | Scenario |
|---|---|
| `400` | Rating out of 1–5, missing orderId |
| `400` | Order not delivered / already reviewed / not owned |
| `403` | Not CUSTOMER |
| `404` | Order not found |

### 4. Implementation Example

```js
const res = await fetch(`${BASE}/api/v1/reviews`, {
  method: "POST",
  headers: { "Content-Type": "application/json", Authorization: `Bearer ${accessToken}` },
  body: JSON.stringify({ orderId: 4242, rating: 4, comment: "Great food!" }),
});
```

---

## Public reads

### GET /reviews/restaurant/{restaurantId}
Approved reviews for a restaurant → `ApiResponse<Review[]>` (read replica).

### GET /reviews/menu-items/{menuItemId}
Ratings for a menu item → `ApiResponse<MenuItemRatingResponse[]>` —
`{id, menuItemId, orderId, rating, comment, createdAt}`.

---

## Customer reads & writes

### GET /reviews/my-reviews
The caller's reviews → `ApiResponse<Review[]>`.

### GET /reviews/order/{orderId}
The caller's review for a specific order → `ApiResponse<Review>`.

### DELETE /reviews/{reviewId}
Deletes the caller's review → `ApiResponse<Void>` (message "Review deleted
successfully"). `400` if not the author.

---

## Menu item ratings

### POST /reviews/menu-items

**1. Overview** — Rates a specific menu item from a delivered order.

**2. Request** — `POST /api/v1/reviews/menu-items` (CUSTOMER)

```json
{ "orderId": 4242, "menuItemId": 42, "rating": 5, "comment": "Best butter chicken" }
```

`orderId`, `menuItemId` required; `rating` required 1–5.

**3. Response — `200 OK`:** `ApiResponse<MenuItemRatingResponse>` (message
"Menu item rated successfully") — `{id, menuItemId, orderId, rating, comment,
createdAt}`.

**4. Errors** — `400` invalid rating / item not in order / not delivered.
