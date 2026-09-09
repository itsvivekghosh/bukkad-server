# Bhukkad Product API Guide

This document explains the Bhukkad Food Delivery System APIs from a product perspective. It is intended for product managers, business analysts, and integration partners who need to understand the business flows and capabilities of the platform.

## Table of Contents

- [Platform Overview](#platform-overview)
- [Customer Journey](#customer-journey)
- [Restaurant Owner Journey](#restaurant-owner-journey)
- [Delivery Agent Journey](#delivery-agent-journey)
- [Admin Journey](#admin-journey)
- [Key Business Flows](#key-business-flows)
- [Feature Matrix](#feature-matrix)
- [API Capabilities](#api-capabilities)

---

## Platform Overview

Bhukkad is a full-stack food delivery platform connecting three primary user types:

1. **Customers** — Browse restaurants, place orders, track deliveries, and leave reviews
2. **Restaurant Owners** — Manage menus, process orders, and update availability
3. **Delivery Agents** — Accept deliveries, update order status, and earn income
4. **Admins** — Oversee platform operations, verify agents, and view analytics

The backend provides 400+ REST API endpoints across 8 service domains, supporting the complete order lifecycle from discovery to delivery.

---

## Customer Journey

### 1. Discovery & Browsing

**What the customer sees:**
- Home screen with featured restaurants and banners
- Search bar for restaurants and dishes
- Filter by cuisine, veg/non-veg, rating, and delivery time
- Restaurant cards with ratings, delivery time, and minimum order

**APIs Involved:**
- `GET /restaurants/public` — Browse all active restaurants
- `GET /restaurants/public/search` — Search by keyword
- `GET /restaurants/public/filter` — Advanced filtering
- `GET /restaurants/public/{id}` — Restaurant details with menu
- `GET /home-feed` — Curated home screen data

**Business Logic:**
- Only active restaurants are shown
- Delivery radius is calculated from customer's location
- Restaurants are sorted by relevance, rating, and distance
- Cache TTL: 5-30 minutes depending on endpoint

### 2. Menu Exploration

**What the customer sees:**
- Restaurant menu organized by categories
- Item details with price, description, images
- Veg/Non-veg indicators, spice levels, customization options
- Item availability status

**APIs Involved:**
- `GET /menu/categories/restaurant/{restaurantId}` — Menu categories
- `GET /menu/items/restaurant/{restaurantId}` — All items
- `GET /menu/items/{id}` — Item details
- `GET /menu/search` — Search menu items

**Business Logic:**
- Unavailable items are hidden or marked
- Prices are locked at time of adding to cart
- Customization options are item-specific

### 3. Cart Management

**What the customer sees:**
- Cart with items from a single restaurant
- Quantity adjustment
- Coupon code application
- Bill breakdown (subtotal, taxes, delivery fee, discount, total)

**APIs Involved:**
- `GET /cart` — View cart
- `POST /cart/add` — Add item
- `PUT /cart/items/{cartItemId}` — Update quantity
- `DELETE /cart/items/{cartItemId}` — Remove item
- `POST /cart/apply-coupon` — Apply coupon
- `DELETE /cart/coupon` — Remove coupon

**Business Logic:**
- Cart is cleared when switching restaurants
- Coupon validation checks minimum order, expiry, and usage limits
- Delivery fee is calculated based on distance and restaurant settings
- Free delivery thresholds are applied

### 4. Checkout & Order Placement

**What the customer sees:**
- Delivery address selection
- Payment method selection (COD, Online, Wallet)
- Order summary with ETA
- Place order button

**APIs Involved:**
- `POST /orders/customer/create` — Place order (with Idempotency-Key)
- `GET /customers/addresses` — Select delivery address
- `POST /customers/addresses` — Add new address

**Business Logic:**
- Order creation is idempotent (duplicate requests return same order)
- Fraud detection runs before order confirmation
- Inventory is reserved at order placement
- ETA is calculated based on restaurant prep time and delivery distance
- Order number format: `ORD-{YYYY}-{sequential}`

### 5. Order Tracking

**What the customer sees:**
- Real-time order status (PLACED → ACCEPTED → PREPARING → READY → PICKED_UP → DELIVERED)
- Estimated delivery time
- Delivery agent details and location (live tracking)
- Order timeline

**APIs Involved:**
- `GET /orders/customer/track/{orderId}` — Track order
- `GET /orders/customer/my-orders` — Order history
- `GET /orders/stream/customer-token/{orderId}` — SSE live updates
- `GET /delivery/agents/{orderId}/location` — Agent location

**Business Logic:**
- Status transitions are validated (no skipping states)
- Live tracking updates every 30 seconds via Redis pub/sub
- SSE stream authenticates via a separate customer token
- Customers receive push notifications at key milestones

### 6. Reordering

**What the customer sees:**
- "Reorder" button on past orders
- Quick re-add of all items to cart

**APIs Involved:**
- `POST /orders/customer/{orderId}/reorder` — Reorder past order

**Business Logic:**
- Items that are no longer available are skipped with a warning
- Cart is cleared before reordering
- New order is placed with current prices

### 7. Reviews & Ratings

**What the customer sees:**
- Review form after delivery
- Star rating and text comment
- Photo upload option
- Restaurant review summary

**APIs Involved:**
- `POST /reviews` — Submit review
- `GET /restaurants/{restaurantId}/reviews` — View reviews

**Business Logic:**
- Reviews can only be submitted for delivered orders
- One review per order
- Ratings affect restaurant average (weighted calculation)
- Reviews can be moderated by admin

---

## Restaurant Owner Journey

### 1. Restaurant Management

**What the owner sees:**
- Restaurant profile (name, description, images, hours)
- Cuisine tags and features (COD, Online Pay, etc.)
- Toggle open/closed status
- Operating hours

**APIs Involved:**
- `POST /restaurants/owner` — Create restaurant
- `PUT /restaurants/owner/{restaurantId}` — Update restaurant
- `PUT /restaurants/owner/{restaurantId}/toggle-status` — Toggle open/closed
- `PUT /restaurants/owner/{restaurantId}/availability` — Update availability

**Business Logic:**
- Restaurant must be active to receive orders
- Status changes take effect immediately
- Operating hours determine auto-open/close

### 2. Menu Management

**What the owner sees:**
- Menu categories (Starters, Main Course, etc.)
- Menu items with images, prices, descriptions
- Item availability toggle
- Bestseller and recommendation flags

**APIs Involved:**
- `POST /menu/categories` — Create category
- `PUT /menu/categories/{categoryId}` — Update category
- `DELETE /menu/categories/{categoryId}` — Delete category
- `POST /menu/items` — Create menu item
- `PUT /menu/items/{itemId}` — Update item
- `DELETE /menu/items/{itemId}` — Delete item

**Business Logic:**
- Menu changes reflect in cache within 5 minutes
- Unavailable items are hidden from customers
- Price changes apply to new orders only
- Image uploads are signed URL-based (S3/CloudFlare R2)

### 3. Order Processing

**What the owner sees:**
- Incoming order notifications
- Order details with items and customer info
- Accept/reject/time estimate actions
- Real-time order status updates

**APIs Involved:**
- `PUT /orders/restaurant/{orderId}/accept` — Accept order
- `PUT /orders/restaurant/{orderId}/reject` — Reject order
- `PUT /orders/restaurant/{orderId}/ready` — Mark ready for pickup
- `GET /orders/restaurant/incoming` — Incoming orders

**Business Logic:**
- Orders auto-reject if not accepted within 5 minutes
- Rejection requires a reason
- Ready status triggers delivery agent assignment
- Kitchen display system (KDS) integration supported

### 4. Analytics & Insights

**What the owner sees:**
- Daily/weekly/monthly sales
- Top selling items
- Peak hours
- Customer demographics

**APIs Involved:**
- `GET /restaurants/owner/analytics/sales` — Sales analytics
- `GET /restaurants/owner/analytics/popular-items` — Popular items
- `GET /analytics/export/restaurants` — CSV export (admin)

**Business Logic:**
- Analytics are calculated daily at midnight
- Data is cached for 1 hour
- Commission and settlement details are included

---

## Delivery Agent Journey

### 1. Registration & Verification

**What the agent sees:**
- Registration form with vehicle details
- ID verification (Aadhaar/Driving License)
- Admin approval status

**APIs Involved:**
- `POST /auth/register` — Register as delivery agent
- `POST /delivery/profile` — Complete profile
- `GET /delivery/profile` — View profile

**Business Logic:**
- Agents must be verified by admin before accepting deliveries
- Vehicle type determines delivery capacity (bike, scooter, car)
- Background verification is required (integration with third-party)

### 2. Availability & Job Acceptance

**What the agent sees:**
- Toggle online/offline
- List of available delivery jobs
- Job details (pickup, drop, distance, earning)

**APIs Involved:**
- `PUT /delivery/toggle-availability` — Go online/offline
- `GET /delivery/available-orders` — View available orders
- `POST /delivery/{orderId}/accept` — Accept delivery
- `POST /delivery/{orderId}/reject` — Reject delivery

**Business Logic:**
- Agents can only accept orders in their service zone
- Rejection rate is tracked (high rejection affects ranking)
- First-come-first-served for order assignment
- Earnings include delivery fee + tips

### 3. Delivery Execution

**What the agent sees:**
- Pickup instructions and OTP
- Customer location and contact
- Proof of delivery (photo + OTP)
- Earning summary

**APIs Involved:**
- `POST /delivery/{orderId}/proof/otp` — Request delivery OTP
- `PUT /delivery/{orderId}/picked-up` — Mark picked up
- `PUT /delivery/{orderId}/delivered` — Mark delivered
- `POST /delivery/{orderId}/proof/photo` — Upload proof photo

**Business Logic:**
- OTP is sent to customer for verification
- Photo proof is optional but recommended
- Delivery time is tracked for agent rating
- Agents can contact customer via in-app call/message

---

## Admin Journey

### 1. Platform Oversight

**What the admin sees:**
- Dashboard with key metrics (orders, GMV, active users)
- Restaurant approval queue
- Agent verification queue
- Dispute resolution

**APIs Involved:**
- `GET /admin/dashboard` — Platform metrics
- `GET /admin/restaurants` — All restaurants
- `PUT /admin/restaurants/{id}/toggle-status` — Toggle restaurant
- `GET /admin/agents` — All agents
- `PUT /admin/agents/{id}/verify` — Verify agent

**Business Logic:**
- Admins have full visibility across the platform
- Restaurant suspension is immediate
- Agent verification requires document review

### 2. Analytics & Reporting

**What the admin sees:**
- Platform-wide sales trends
- Top restaurants and agents
- User growth metrics
- Revenue reports

**APIs Involved:**
- `GET /analytics/export/orders` — Orders CSV
- `GET /analytics/export/restaurants` — Restaurants CSV
- `GET /analytics/export/riders` — Agents CSV
- `GET /analytics/export/payments` — Payments CSV

**Business Logic:**
- Exports include filters for date range and status
- CSV exports are streamed for large datasets
- Data is refreshed every 15 minutes

### 3. Trust & Compliance

**APIs Involved:**
- `POST /compliance/users/{userId}/erase` — GDPR data erasure
- `GET /compliance/audit-log` — Audit trail
- `POST /admin/disputes/{disputeId}/resolve` — Resolve disputes

**Business Logic:**
- Data erasure anonymizes PII while preserving order history
- Audit logs are immutable and retained for 7 years
- Disputes are auto-escalated after 48 hours

---

## Key Business Flows

### Order Lifecycle

```
PLACED → ACCEPTED → PREPARING → READY → PICKED_UP → DELIVERED
   ↓         ↓          ↓         ↓          ↓
REJECTED  CANCELLED  CANCELLED  CANCELLED  CANCELLED
```

### Payment Flow

1. Customer places order → Payment initiated
2. Razorpay creates payment link
3. Customer completes payment
4. Webhook confirms payment → Order confirmed
5. If payment fails → Order cancelled automatically

### Delivery Assignment Flow

1. Restaurant marks order READY
2. System searches for available agents in zone
3. Agent receives notification
4. Agent accepts → OTP shared with customer
5. Agent picks up → Status updates
6. Agent delivers → Proof collected

### Commission & Settlement

1. Order delivered → Commission calculated
2. Restaurant wallet credited (order amount - commission)
3. Agent wallet credited (delivery fee + tip)
4. Weekly settlement to bank accounts

---

## Feature Matrix

| Feature | Customer | Owner | Agent | Admin |
|---------|----------|-------|-------|-------|
| Browse restaurants | ✓ | ✓ | ✓ | ✓ |
| Place orders | ✓ | ✗ | ✗ | ✗ |
| Track orders | ✓ | ✓ | ✓ | ✓ |
| Menu management | ✗ | ✓ | ✗ | ✗ |
| Order management | ✗ | ✓ | ✓ | ✗ |
| Delivery execution | ✗ | ✗ | ✓ | ✗ |
| Analytics | ✗ | ✓ | ✓ | ✓ |
| Platform settings | ✗ | ✗ | ✗ | ✓ |
| Verification | ✗ | ✗ | ✓ | ✓ |

---

## API Capabilities

### Batch Operations

- `GET /menu/items?ids=1,2,3` — Batch fetch menu items (max 100)
- `GET /restaurants/public?ids=1,2,3` — Batch fetch restaurants
- `POST /orders/batch` — Batch order creation (enterprise feature)

### Real-time Updates

- Server-Sent Events (SSE) for order tracking
- Redis pub/sub for delivery location updates
- WebSocket support for in-app chat

### Offline Support

- Idempotency keys for order retry
- Graceful degradation when Redis is unavailable
- Optimistic locking for concurrent updates

### Internationalization

- Multi-language support (i18n)
- Currency formatting per region
- Timezone-aware timestamps

### Security

- JWT-based authentication
- Rate limiting per endpoint
- WAF (Web Application Firewall) rules
- API key pepper for sensitive operations
- MFA support for admin accounts

---

## Support & Integration

For API integration support:
- Review the [Developer API Reference](DEVELOPER_API.md)
- Check the [Postman Collection](../../postman/Bhukkad-API.postman_collection.json)
- Run the test suite: `python scripts/test-all-apis.py`
