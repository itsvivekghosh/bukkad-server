# Bhukkad API System - Consolidated Documentation & Reference Guide

## Table of Contents
1. [API Contract Delivery Process](#1-api-contract-delivery-process)
2. [API Architecture Overview](#2-api-architecture-overview)
3. [Functional Area Reference](#3-functional-area-reference)
4. [API Usage Patterns & Best Practices](#4-api-usage-patterns--best-practices)
5. [Existing Documentation References](#5-existing-documentation-references)
6. [Quick Reference Tables](#6-quick-reference-tables)
7. [Glossary of Terms](#7-glossary-of-terms)

---

## 1. API Contract Delivery Process

This section outlines the complete process for defining, reviewing, delivering, and maintaining frontend API contracts in the Bhukkad Food Delivery System.

### 1.1 Contract Definition Process
All API contract changes begin with a clearly defined requirement:
1. **Requirement Submission**: Product, frontend, or backend teams submit API requirements via Jira tickets with `API-Contract` label
2. **Requirement Analysis**: Backend lead reviews for feasibility, security, and architectural alignment
3. **API Design Session**: Collaborative session defining endpoint purpose, HTTP method, schemas, auth, rate limiting, error conditions, and idempotency

### 1.2 Contract Specification
API contracts are defined using:
- **Controllers**: `src/main/java/com/bhukkad/controller/*` with Spring MVC annotations
- **DTOs**: Request (`src/main/java/com/bhukkad/dto/request/*`) and Response (`src/main/java/com/bhukkad/dto/response/*`)
- **Validation**: Jakarta Validation annotations (`@NotNull`, `@Size`, `@Pattern`, etc.)
- **Security**: `@PreAuthorize` for method-level authorization
- **Idempotency**: `Idempotency-Key` header for state-changing operations
- **Documentation**: Spring/OpenAPI annotations (`@Operation`, `@Tag`) for auto-generated docs

### 1.3 Review & Approval Workflow
1. **Self-Review**: Developer checks REST principles, validation, security, idempotency, error handling, documentation
2. **Peer Review**: Backend team lead verifies implementation quality and consistency
3. **Cross-Team Review**: Frontend team reviews for consumability and UI alignment
4. **API Contract Review Board**: Monthly review for significant changes

Approval requires:
- All review checklists satisfied
- Unit test coverage ≥80% for new code
- Backward compatibility (or migration plan)
- Complete and accurate documentation
- No security vulnerabilities
- Acceptable performance impact
- Frontend team confirmation

### 1.4 Delivery & Deployment
1. **Development**: Feature branch `feature/api-{resource}-{description}`
2. **CI Pipeline**: Build, unit tests, contract validation (OpenAPI), security scanning, integration tests
3. **Merge Requirements**: All CI checks pass, 2+ backend approvals (1 tech lead), frontend approval for user-impacting changes
4. **Deployment**: 
   - Staging: Auto-deploy on merge to `main`, smoke tests, contract verification
   - Production: GitHub Actions with blue/green deployment, traffic shifting, health checks
5. **Publication**: OpenAPI at `/v3/api-docs`, Swagger UI at `/swagger-ui.html`, version headers

### 1.5 Contract Lifecycle Management
- **Backward Compatible** (same version): Add endpoints, optional fields, response fields, HTTP methods, enum values
- **Breaking Changes** (version bump): Remove endpoints/fields, change data types/methods/auth, make optional required, change success status codes
- **Deprecation**: 6-month minimum support, `Deprecation` header with sunset date, 410 Gone after sunset
- **Versioning**: Path-based (`/api/v1/{resource}`), future versions use `/api/v2/{resource}`

---

## 2. API Architecture Overview

### 2.1 Core Architectural Principles
- **Layered Architecture**: Controllers → Services → Repositories → Database
- **Dependency Injection**: Constructor injection (Lombok `@RequiredArgsConstructor`)
- **DTO Pattern**: Separate Request/Response objects for clear contracts
- **Standard Response**: `ApiResponse<T>` envelope for consistency
- **Stateless Design**: Enables horizontal scaling
- **Defensive Programming**: Comprehensive validation and error handling

### 2.2 Key Technical Components
- **API Gateway**: Path-based versioning (`/api/v1`) with legacy rewrite filter
- **Authentication**: JWT Bearer tokens with 24h access/7d refresh expiration
- **Authorization**: Spring Security `@PreAuthorize` with role-based access
- **Validation**: Jakarta Bean Validation on all input DTOs
- **Response Handling**: Centralized `@ControllerAdvice` for consistent error responses
- **Caching**: Multi-layer (Caffeine local + Redis distributed) with TTL per bucket
- **Rate Limiting**: Custom Redis-backed implementation with `@RateLimited` annotation
- **Asynchronous Processing**: Thread pools for order processing, background tasks, scheduling
- **Real-time Features**: Server-Sent Events (SSE) for live tracking with replay capability
- **Observability**: Prometheus metrics, structured logging, OpenTelemetry tracing support
- **Security Layers**: WAF, security headers, input sanitization, password hashing (BCrypt)

### 2.3 Architectural Patterns
- **Resource-Oriented Design**: URLs represent resources (nouns), HTTP methods represent actions
- **DTO Pattern**: Prevents overexposure of internal entities, allows versioning
- **Response Wrapper**: Standard envelope ensures consistent client handling
- **Aspect-Oriented Programming**: Cross-cutting concerns (rate limiting, security) via annotations
- **Circuit Breaker**: Resilience4j for external service dependencies (payment gateways, notifications)
- **Outbox Pattern**: Eventual consistency with guaranteed delivery
- **Single-Flight Protection**: Prevents cache stampede during population
- **Pub/Sub Invalidation**: Immediate cache consistency across instances

---

## 3. Functional Area Reference

### 3.1 Customer-Facing APIs

#### Authentication (`/api/v1/auth/*`)
- **POST `/register`** - User registration (role: CUSTOMER/OWNER/AGENT)
- **POST `/login`** - Credential-based authentication
- **POST `/mfa/verify`** - TOTP verification for sensitive roles
- **POST `/verify-email`** - Email verification
- **POST `/forgot-password`** - Password reset initiation
- **POST `/reset-password`** - Password reset completion
- **POST `/change-password`** - Password change (authenticated)
- **POST `/refresh-token`** - Access token renewal
- **POST `/logout`** - Token invalidation

#### Customer Management (`/api/v1/customers/*`)
- **GET `/profile`** - Retrieve customer profile
- **PUT `/profile`** - Update customer profile
- **GET `/addresses`** - List customer addresses
- **POST `/addresses`** - Add new address
- **PUT `/addresses/{id}`** - Update address
- **DELETE `/addresses/{id}`** - Remove address
- **PUT `/addresses/{id}/set-default`** - Set default address
- **GET `/wallet/balance`** - Wallet balance and transaction history
- **POST `/wallet/top-up`** - Fund wallet via payment gateway
- **POST `/wallet/add-money`** - Direct wallet credit (admin/dev)
- **GET `/loyalty-points`** - Loyalty points balance and history
- **GET `/referral`** - Referral code and rewards
- **GET `/favorites`** - Favorite restaurants
- **POST `/favorites/{restaurantId}`** - Add favorite
- **DELETE `/favorites/{restaurantId}`** - Remove favorite
- **GET `/orders/stats`** - Order statistics (count, totals, etc.)
- **GET `/data-export`** - GDPR data export
- **GET `/notification-preferences`** - Notification channel preferences
- **PUT `/notification-preferences`** - Update notification preferences
- **POST `/device-tokens`** - Register push notification token
- **DELETE `/device-tokens`** - Unregister push notification token

#### Cart Management (`/api/v1/cart/*`)
- **GET `/cart`** - Retrieve multi-restaurant cart with grouped items
- **POST `/add`** - Add item to cart (validates availability/stock)
- **PUT `/items/{cartItemId}`** - Update item quantity/customizations
- **DELETE `/items/{cartItemId}`** - Remove item from cart
- **DELETE `/restaurant/{restaurantId}`** - Clear restaurant-specific items
- **DELETE `/clear`** - Empty entire cart
- **POST `/apply-coupon`** - Apply coupon code to cart

#### Order Management (`/api/v1/orders/*`)
**Customer Endpoints:**
- **POST `/customer/create`** - Create order (sync or `?async=true`)
- **GET `/customer/scheduled-orders`** - List scheduled orders (paginated)
- **GET `/customer/scheduled-orders/cursor`** - List scheduled orders (cursor)
- **PUT `/customer/scheduled-orders/{orderId}/cancel`** - Cancel scheduled order
- **POST `/customer/create-batch`** - Create multiple orders from cart
- **GET `/customer/create/jobs/{jobId}`** - Poll async order creation status
- **GET `/customer/my-orders`** - List customer orders (paginated)
- **GET `/customer/my-orders/cursor`** - List customer orders (cursor)
- **GET `/customer/{orderId}`** - Retrieve order details
- **POST `/customer/{orderId}/reorder`** - Rebuild cart from previous order
- **GET `/customer/export/orders`** - Export orders as CSV
- **PUT `/customer/{orderId}/cancel`** - Cancel order (with refund if applicable)
- **GET `/customer/batch`** - Batch fetch multiple orders by ID
- **GET `/number/{orderNumber}`** - Lookup order by human-readable number

**Tracking & Live Updates:**
- **GET `/customer/track/{orderId}`** - Live tracking snapshot (recalculated ETA)
- **GET `/orders/stream/customer/{orderId}`** - SSE for order updates (with replay)
- **POST `/customer/{orderId}/tracking-token`** - Generate guest tracking token
- **GET `/orders/stream/customer-token/{orderId}?token=`** - Public SSE stream with token

**Restaurant Endpoints:**
- **GET `/restaurant/{restaurantId}`** - List restaurant orders (paginated)
- **GET `/restaurant/{restaurantId}/cursor`** - List restaurant orders (cursor)
- **GET `/restaurant/{restaurantId}/pending`** - Pending orders (no pagination)
- **GET `/restaurant/{restaurantId}/kitchen-queue`** - Active kitchen queue (rate-limited)
- **PUT `/restaurant/{orderId}/accept`** - PLACED → CONFIRMED
- **PUT `/restaurant/{orderId}/ready`** - READY_FOR_PICKUP + auto-assign rider
- **PUT `/restaurant/{orderId}/assign-delivery`** - Manual rider assignment
- **GET `/delivery/my-deliveries`** - List assigned deliveries (paginated)
- **GET `/delivery/my-deliveries/cursor`** - List assigned deliveries (cursor)
- **PUT `/delivery/{orderId}/picked-up`** - OUT_FOR_DELIVERY
- **PUT `/delivery/{orderId}/delivered`** - DELIVERED (requires proof if enforced)
- **POST `/delivery/{orderId}/proof/otp`** - Issue/resend handover OTP
- **POST `/delivery/{orderId}/proof/verify`** - Verify OTP + optional photo
- **POST `/delivery/{orderId}/proof/photo-url`** - Presigned upload URL for proof photo
- **GET `/delivery/{orderId}/proof`** - Current proof state

#### Reviews (`/api/v1/reviews/*`)
- **GET `/restaurant/{restaurantId}`** - Public restaurant reviews (read replica)
- **GET `/my-reviews`** - Authenticated user's reviews
- **GET `/order/{orderId}`** - Reviews for specific order
- **DELETE `/{reviewId}`** - Delete own review
- **POST `/menu-items`** - Review menu item
- **GET `/menu-items/{menuItemId}`** - Menu item reviews

#### Referrals (`/api/v1/referrals/*`)
- **POST `/generate`** - Generate referral code
- **POST `/validate`** - Validate referral code
- **GET `/rewards`** - Earned referral rewards

#### Wallet & Payments (`/api/v1/customers/wallet/*` & `/api/v1/payments/*`)
- **GET `/wallet/balance`** - Wallet balance and history
- **POST `/wallet/top-up`** - Fund wallet (returns gatewayOrderId for webhook completion)
- **POST `/wallet/add-money`** - Direct admin/dev wallet credit
- **GET `/payments/orders/{orderId}`** - Payment details for order
- **POST `/payments/webhooks/razorpay`** - Payment gateway webhook (secure signature verification)

#### Additional Customer Features
- **Support Tickets** (`/api/v1/customers/support/tickets/*`): Create, list, update status
- **Membership** (`/api/v1/customers/membership/*`): Plans, status, subscription
- **Surveys** (`/api/v1/surveys/*`): Feedback collection (survey ratings, trending items)
- **Notifications**: Device token management for push notifications
- **Favorites**: Restaurant bookmarking system

### 3.2 Restaurant Owner APIs

#### Restaurant Management (`/api/v1/restaurants/*`)
**Public Endpoints:**
- **GET `/public`** - List restaurants (paginated, read replica)
- **GET `/public/{id}`** - Restaurant detail (read replica)
- **GET `/public/search`** - Text search
- **GET `/public/nearby`** - Geo-search (lat/lng/radius)
- **GET `/public/filter`** - Cuisine/rating/veg filters

**Owner Endpoints (RESTAURANT_OWNER role):**
- **POST `/owner`** - Create restaurant (onboarding)
- **POST `/onboarding/signup`** - Alternative onboarding flow
- **GET `/onboarding/status`** - Onboarding status check
- **GET `/owner/my-restaurants`** - List owned restaurants
- **PUT `/owner/{id}`** - Update restaurant profile
- **DELETE `/owner/{id}`** - Deactivate restaurant
- **GET `/owner/{id}/analytics`** - Performance analytics (date range)
- **PUT `/owner/{id}/toggle-status`** - Active/inactive toggle
- **GET `/owner/{id}/settlements`** - Payout history (paginated/cursor)
- **PUT `/owner/{id}/busy-mode`** - Enable/disable busy mode
- **GET `/owner/{id}/dashboard`** - Operational dashboard
- **POST `/owner/reviews/{reviewId}/response`** - Respond to customer review

#### Menu Management (`/api/v1/menu/*`)
- **POST `/categories`** - Create menu category
- **GET `/categories/restaurant/{restaurantId}`** - List restaurant categories
- **PUT `/categories/{categoryId}`** - Update category
- **DELETE `/categories/{categoryId}`** - Remove category
- **POST `/items`** - Create menu item
- **GET `/items`** - List items (with filtering)
- **GET `/items/{id}`** - Item detail
- **GET `/items/category/{categoryId}`** - Items by category
- **GET `/items/restaurant/{restaurantId}`** - Items by restaurant
- **PUT `/items/{id}`** - Update menu item
- **DELETE `/items/{id}`** - Remove menu item
- **PUT `/items/{id}/toggle-availability`** - Enable/disable item availability
- **GET `/items/restaurant/{restaurantId}/bestsellers`** - Top-selling items
- **GET `/items/restaurant/{restaurantId}/recommended`** - Algorithmically recommended
- **POST `/items/{id}/image/upload-url`** - Presigned URL for menu image upload
- **GET `/items/search`** - Text search within restaurant menu
- **GET `/items/restaurant/{restaurantId}/low-stock`** - Low stock alert items

#### Menu Versioning (`/api/v1/menu/versions/*`)
- **GET `/{id}`** - Retrieve menu version details
- **POST `/{id}/publish`** - Publish menu version (make active)

### 3.3 Delivery Agent APIs

#### Delivery Management (`/api/v1/delivery/*`)
- **GET `/profile`** - Retrieve delivery agent profile
- **PUT `/profile`** - Update delivery agent profile
- **GET `/earnings/summary`** - Earnings summary (totals, averages, etc.)
- **GET `/earnings`** - Earnings history (paginated)
- **GET `/earnings/cursor`** - Earnings history (cursor)
- **PUT `/toggle-availability`** - Enable/disable order acceptance
- **PUT `/update-location`** - Update current GPS location
- **GET `/available-orders`** - Browse available orders for acceptance
- **GET `/active-deliveries`** - List current active deliveries
- **GET `/delivery-history`** - Past delivery history
- **POST `/{orderId}/accept`** - Accept delivery request
- **POST `/{orderId}/reject`** - Reject delivery request
- **POST `/orders/{orderId}/location`** - Update location during delivery
- **POST `/batches`** - Create delivery batch for efficiency
- **GET `/batches/active`** - List active batches
- **PUT `/batches/{batchId}/complete`** - Mark batch as complete

#### Proof of Delivery (`/api/v1/orders/delivery/*/proof/*`)
- **POST `/{orderId}/otp`** - Issue or resend 6-digit handover OTP
- **POST `/{orderId}/verify`** - Verify OTP code + optional photo/recipient data
- **POST `/{orderId}/photo-url`** - Get presigned URL for proof photo upload
- **GET `/{orderId}`** - Retrieve current proof state (no OTP returned)

### 3.4 Administrator APIs

#### Platform Administration (`/api/v1/admin/*`)
- **GET `/dashboard`** - Platform overview dashboard
- **GET `/analytics`** - Revenue, user, order analytics
- **GET `/users`** - User management (paginated)
- **PUT `/users/{userId}/activate`** - Activate user account
- **PUT `/users/{userId}/deactivate`** - Deactivate user account
- **PUT `/owners/{ownerId}/verify`** - Verify restaurant owner
- **PUT `/agents/{agentId}/verify`** - Verify delivery agent
- **GET `/orders`** - Order management (paginated)
- **GET `/restaurants`** - Restaurant management (paginated)
- **PUT `/restaurants/{restaurantId}/approve`** - Approve restaurant application
- **PUT `/restaurants/{restaurantId}/suspend`** - Suspend restaurant operations
- **PUT `/restaurants/{restaurantId}/onboarding`** - Update onboarding status
- **GET `/revenue`** - Revenue tracking and reporting
- **PUT `/agents/{agentId}/settle-payouts`** - Settle delivery agent earnings
- **PUT `/restaurants/{restaurantId}/settle-payouts`** - Settle restaurant payouts
- **PUT `/restaurants/{restaurantId}/commission`** - Update restaurant commission rate
- **POST `/notifications/test`** - Send test notification (email/sms/whatsapp)

#### Specialized Admin Functions
**Growth & Support (`/api/v1/admin/growth/*`):**
- **GET `/support/tickets`** - List support tickets
- **PUT `/support/tickets/{ticketId}/status`** - Update ticket status

**Review Moderation (`/api/v1/admin/reviews/*`):**
- **GET `/moderation`** - List pending/rejected reviews for moderation
- **PUT `/{reviewId}/moderate`** - Approve/reject/pending review

**Scale & Operations (`/api/v1/admin/scale/*`):**
- **Geographic Management**: Cities, zones, promotions, campaigns, banners
- **Operations Dashboard**: System performance and health metrics
- **Settlement Operations**: Manual settlement triggering

**Dead Letter Handling (`/api/v1/admin/dead-letters/*`):**
- **GET `/pending/count`** - Count of failed outbox messages
- **GET `/{id}`** - Retrieve specific dead letter
- **POST `/{id}/requeue`** - Reattempt failed message processing

### 3.5 Platform & Infrastructure APIs

#### Discovery & Search
- **Home Feed** (`/api/v1/home/*`):
  - **GET `/banners`** - Promotional banners
  - **GET `/campaigns`** - Active promotion campaigns
  - **GET `/feed`** - Combined home feed (banners, campaigns, plans, trending)
  - **GET `/membership-plans`** - Available membership plans
- **Search** (`/api/v1/search/*`):
  - **GET `/suggest`** - Search suggestions as user types
- **Cuisine** (`/api/v1/cuisines/*`):
  - **GET `/{id}`** - Cuisine detail
- **Serviceability** (`/api/v1/serviceability/*`):
  - **GET `/check`** - Service area check + delivery fee estimate

#### Health & Monitoring
- **Health** (`/api/v1/health/*`):
  - **GET `/ping`** - Liveness check
  - **GET `/detailed`** - Comprehensive health status
  - **GET `/db`** - Primary database connectivity
  - **GET `/db/replica`** - Replica database connectivity
  - **GET `/memory`** - System memory usage
  - **GET `/env`** - Environment information

#### Platform Information
- **Platform** (`/api/v1/platform/*`):
  - **GET `/cities`** - List of served cities
  - **GET `/tenants/{domain}`** - Tenant information by domain
  - **GET `/status`** - System status (Kafka, cache, GEO, stock, notification flags)

#### Feature Flags
- **Feature Flags** (`/api/v1/feature-flags/*`):
  - **GET `/{key}`** - Get feature flag value
  - **PUT `/{key}`** - Set feature flag value

#### Commission Management
- **Commission Tiers** (`/api/v1/commission/tiers/*`):
  - **GET `/tiers`** - List all commission tiers
  - **GET `/restaurants/{restaurantId}`** - Get specific restaurant's commission

#### Inventory Management
- **Inventory Alerts** (`/api/v1/inventory/alerts/*`):
  - **GET `/restaurants/{restaurantId}`** - Low-stock alerts for restaurant
  - **PUT `/{alertId}/acknowledge`** - Acknowledge inventory alert

#### Order Analytics
- **Order Growth** (`/api/v1/orders/growth/*`):
  - **GET `/{orderId}/timeline`** - Order status change history
  - **GET `/{orderId}/invoice`** - HTML invoice
  - **GET `/{orderId}/invoice/pdf`** - PDF invoice (requires auth)
  - **GET `/{orderId}/rider-location`** - Rider location history during delivery

#### Subscription Management
- **Subscriptions** (`/api/v1/subscriptions/*`):
  - **POST `/{id}/pause`** - Pause subscription
  - **POST `/{id}/resume`** - Resume subscription
  - **POST `/{id}/cancel`** - Cancel subscription
  - **POST `/{id}/skip`** - Skip upcoming delivery

#### Dispute Management
- **Disputes** (`/api/v1/disputes/*`):
  - **POST `/customers/orders/{orderId}/disputes`** - Initiate dispute
  - **GET `/customers/disputes`** - List customer's disputes
  - **GET `/admin/disputes`** - List disputes for admin review
  - **GET `/admin/disputes/{disputeId}`** - Retrieve dispute details
  - **POST `/admin/disputes/{disputeId}/resolve`** - Resolve dispute
  - **POST `/admin/disputes/auto-resolve`** - Trigger automatic dispute resolution

#### Dynamic Pricing
- **Dynamic Pricing** (`/api/v1/pricing/*`):
  - **POST `/restaurants/{restaurantId}/rules`** - Create pricing rule
  - **PUT `/rules/{ruleId}`** - Update pricing rule
  - **DELETE `/rules/{ruleId}`** - Delete pricing rule
  - **GET `/restaurants/{restaurantId}/rules`** - List restaurant's rules
  - **GET `/restaurants/{restaurantId}/happy-hour`** - Happy hour schedule

#### Fraud Management
- **Fraud Dashboard** (`/api/v1/fraud/*`):
  - **GET `/dashboard`** - Fraud event overview
  - **GET `/events`** - List fraud events
  - **GET `/review-queue`** - List events requiring manual review
  - **POST `/review-queue/{eventId}/action`** - Take action on reviewed event

#### Gift Cards
- **Gift Cards** (`/api/v1/gift-cards/*`):
  - **POST `/purchase`** - Purchase gift card
  - **POST `/redeem`** - Redeem gift card into wallet
  - **GET `/my-cards`** - Purchased gift cards
  - **GET `/received`** - Received gift cards
  - **GET `/{code}`** - Lookup gift card by code

#### Group Orders
- **Group Orders** (`/api/v1/group-orders/*`):
  - **POST `/{id}/invite`** - Invite user to group
  - **POST `/{id}/join`** - Join group as member
  - **GET `/{id}`** - Group details
  - **POST `/{id}/split`** - Record expense splitting
  - **POST `/{id}/place`** - Freeze group; host places order

#### Multi-tenancy
- **Tenants** (`/api/v1/tenants/*`):
  - **PUT `/{tenantId}`** - Update tenant configuration
  - **DELETE `/{tenantId}`** - Remove tenant

#### Compliance & Data Protection
- **Compliance** (`/api/v1/compliance/*`):
  - **GET `/consents`** - List data processing consents
  - **PUT `/consents/{purpose}`** - Update consent for specific purpose
  - **POST `/export`** - Initiate data export request
  - **POST `/users/{id}/erase`** - Request user data erasure (GDPR right to be forgotten)

#### Enhanced Delivery ETA
- **Delivery Truth** (`/api/v1/delivery-truth/*`):
  - **GET `/orders/{orderId}/eta`** - Enhanced ETA calculation (OSRM-based when enabled)

#### Payment Webhooks
- **Payment Webhooks** (`/api/v1/payments/webhooks/*`):
  - **POST `/razorpay`** - Secure webhook for payment confirmation

---

## 4. API Usage Patterns & Best Practices

### 4.1 Standard Request Patterns

#### Authentication Flow
```http
# 1. Register
POST /api/v1/auth/register
Content-Type: application/json
{
  "fullName": "Jane Doe",
  "email": "jane@example.com",
  "password": "securePassword123!",
  "phoneNumber": "9876543210",
  "role": "CUSTOMER"
}

# 2. Login
POST /api/v1/auth/login
Content-Type: application/json
{
  "email": "jane@example.com",
  "password": "securePassword123!"
}
# Response: { accessToken: "...", refreshToken: "..." }

# 3. Use Access Token
Authorization: Bearer <accessToken>

# 4. Refresh When Expired
POST /api/v1/auth/refresh-token
Authorization: Bearer <refreshToken>
```

#### Idempotent Operations
```http
POST /api/v1/orders/customer/create
Authorization: Bearer <accessToken>
Idempotency-Key: 550e8400-e29b-41d4-a716-446655440000
Content-Type: application/json
{
  "restaurantId": 12,
  "deliveryAddressId": 5,
  "paymentMethod": "UPI",
  "useWallet": true
}
# Returns same response for duplicate key
```

#### Pagination
```http
# Offset-based
GET /api/v1/customers/my-orders?page=0&size=20
# Response: { items: [...], page: 0, size: 20, totalElements: 152, totalPages: 8, hasNext: true }

# Cursor-based (preferred for infinite scrolling)
GET /api/v1/customers/my-orders/cursor?cursor=abc123&size=20
# Response: { items: [...], nextCursor: "def456", hasNext: true, size: 20 }
```

### 4.2 Error Handling Patterns

#### Validation Errors (400)
```json
{
  "success": false,
  "message": "Validation failed",
  "data": {
    "fieldErrors": [
      {
        "field": "email",
        "message": "must be a valid email address",
        "rejectedValue": "invalid-email"
      },
      {
        "field": "password",
        "message": "must be at least 8 characters",
        "rejectedValue": "123"
      }
    ]
  },
  "timestamp": "2026-08-25T19:13:52Z",
  "traceId": "8060e534c23630e6a1393f746a07029d",
  "spanId": "09c46cd3bd00279e",
  "requestId": "862681db"
}
```

#### Resource Not Found (404)
```json
{
  "success": false,
  "message": "Restaurant not found",
  "data": null,
  "timestamp": "2026-08-25T19:13:52Z",
  "traceId": "8060e534c23630e6a1393f746a07029d",
  "spanId": "09c46cd3bd00279e",
  "requestId": "862681db"
}
```

#### Authorization Errors (403)
```json
{
  "success": false,
  "message": "Insufficient permissions to access this resource",
  "data": null,
  "timestamp": "2026-08-25T19:13:52Z",
  "traceId": "8060e534c23630e6a1393f746a07029d",
  "spanId": "09c46cd3bd00279e",
  "requestId": "862681db"
}
```

#### Rate Limit Exceeded (429)
```http
HTTP/1.1 429 Too Many Requests
Retry-After: 300
Content-Type: application/json

{
  "success": false,
  "message": "Rate limit exceeded",
  "data": null,
  "timestamp": "2026-08-25T19:13:52Z",
  "traceId": "8060e534c23630e6a1393f746a07029d",
  "spanId": "09c46cd3bd00279e",
  "requestId": "862681db"
}
```

### 4.3 Real-time Usage (SSE)

#### Order Tracking
```http
GET /api/v1/orders/stream/customer/12345
Authorization: Bearer <accessToken>
Accept: text/event-stream
Last-Event-ID: 12450  # Optional - for replaying missed events

# Response format:
event: order-update
id: 12451
data: {
  "eventType": "STATUS_CHANGED",
  "status": "OUT_FOR_DELIVERY",
  "orderId": 12345,
  "orderNumber": "ORD-abc123",
  "liveEtaMinutes": 12,
  "liveEtaAt": "2026-08-25T20:00:00Z",
  ...
}

# Heartbeat (every 25s):
:heartbeat
```

#### Guest Tracking
```http
# 1. Generate token
POST /api/v1/orders/stream/customer/12345/tracking-token
Authorization: Bearer <accessToken>
# Response: { trackingToken: "guest-token-xyz" }

# 2. Share token with guest (e.g., via SMS)
# 3. Guest accesses:
GET /api/v1/orders/stream/customer-token/12345?token=guest-token-xyz
Accept: text/event-stream
```

### 4.4 Performance Optimization Tips

#### Efficient Data Fetching
- **Use cursor pagination** for large datasets and infinite scrolling
- **Leverage field selection** when available (`?fields=id,orderNumber,status,totalAmount`)
- **Batch requests** when possible (e.g., `/orders/batch?ids=1&ids=2&ids=3`)
- **Utilize caching** - frequently accessed data (menu items, restaurant lists) is cached
- **Avoid polling** - use SSE for real-time updates instead of frequent GET requests

#### Mobile-Specific Considerations
- **Minimize payload size** - only request needed fields
- **Implement request batching** for related data
- **Use compression** - API automatically gzips responses over 1KB
- **Leverage caching** - take advantage of client-side HTTP caching where appropriate
- **Implement optimistic UI updates** where backed by idempotent APIs

#### Integration Best Practices
- **Always use Idempotency-Key** for POST/PUT/PATCH operations
- **Handle 429 responses** by respecting Retry-After header
- **Implement exponential backoff** for retry logic
- **Validate SSL certificates** in production
- **Use connection pooling** for HTTP clients
- **Implement circuit breaker pattern** for external API dependencies

---

## 5. Existing Documentation References

For detailed information, refer to these existing documentation files:

### 5.1 Core Documentation
- **[API Workflow](docs/api-workflow.md)** - Detailed reference of all public workflows, request/response formats, service steps, and infrastructure touchpoints
- **[API Usage Guide](docs/api-usage.md)** - Comprehensive guide for API consumers with code examples and flow explanations
- **[Getting Started](docs/getting-started.md)** - Setup instructions for local development and deployment
- **[API Contract Process](docs/api-contract-process.md)** - This document: Complete API contract delivery process

### 5.2 API Reference Documentation
Located in `docs/api/` directory:
- **[auth.md](docs/api/auth.md)** - Authentication endpoints (register, login, MFA, password management)
- **[orders.md](docs/api/orders.md)** - Order lifecycle, cart, tracking, group orders, gift cards
- **[payments.md](docs/api/payments.md)** - Payment methods, webhooks, refunds, wallet
- **[delivery.md](docs/api/delivery.md)** - Delivery agent operations, proof of delivery, earnings
- **[discovery.md](docs/api/discovery.md)** - Restaurant/search discovery, home feed, serviceability
- **[reviews.md](docs/api/reviews.md)** - Review and rating system
- **[account.md](docs/api/account.md)** - Customer profile, addresses, notifications, device tokens
- **[admin.md](docs/api/admin.md)** - Administration, analytics, growth, feature flags, compliance

### 5.3 Technical Documentation
- **[Configuration](docs/configuration.md)** - Environment variables, feature flags, rate limits, caching
- **[Deployment](docs/DEPLOYMENT.md)** - Deployment procedures and scripts
- **[Docker](docs/docker.md)** - Docker Compose configurations for local/dev/prod
- **[Kubernetes](docs/kubernetes.md)** - Kubernetes manifests and deployment guides
- **[Operations](docs/operations.md)** - Monitoring, logging, troubleshooting, maintenance
- **[Testing](docs/testing.md)** - Unit and integration testing strategies
- **[Optimization](docs/optimization.md)** - Performance tuning and scaling guidelines
- **[Scaling](docs/scaling.md)** - Horizontal scaling strategies and best practices
- **[Sharding](docs/sharding.md)** - Database sharding approach (when implemented)

### 5.4 Feature-Specific Documentation
- **[Features](docs/features.md)** - Versioned platform features (V10-V17)
- **[Advanced Features](docs/advanced-features.md)** - Referrals, batch checkout, scheduled orders, etc.
- **[Delivery Truth](docs/delivery-truth.md)** - Enhanced ETA calculation with OSRM
- **[Promotions Engine](docs/promotions-engine.md)** - Discount and campaign management
- **[Scale Operations](docs/scale-operations.md)** - Auto-scaling and performance optimization
- **[Trust and Compliance](docs/trust-and-compliance.md)** - GDPR, fraud enforcement, review moderation

### 5.5 Interactive Documentation
- **Swagger UI**: `http://localhost:8080/swagger-ui.html` (development only)
- **OpenAPI JSON**: `http://localhost:8080/v3/api-docs`
- **OpenAPI YAML**: `http://localhost:8080/v3/api-docs.yaml`

---

## 6. Quick Reference Tables

### 6.1 HTTP Status Codes
| Code | Meaning | Typical Use Cases |
|------|---------|-------------------|
| 200 | OK | Successful GET, PUT, PATCH |
| 201 | Created | Successful POST (resource created) |
| 202 | Accepted | Async processing accepted |
| 400 | Bad Request | Validation errors, invalid request |
| 401 | Unauthorized | Missing/invalid/expired token |
| 403 | Forbidden | Insufficient permissions |
| 404 | Not Found | Resource doesn't exist |
| 409 | Conflict | Idempotency key in use with different data |
| 429 | Too Many Requests | Rate limit exceeded |
| 500 | Internal Server Error | Unexpected server error |
| 503 | Service Unavailable | System overload/maintenance |

### 6.2 Common Request Headers
| Header | Description | Example |
|--------|-------------|---------|
| `Authorization` | JWT Bearer token | `Bearer eyJhbGciOiJIUzI1NiIs...` |
| `Idempotency-Key` | Duplicate detection | `550e8400-e29b-41d4-a716-446655440000` |
| `Content-Type` | Request body type | `application/json` |
| `Accept` | Response type preference | `application/json` |
| `Accept-Version` | API version request | `1` (or `0` for latest) |
| `X-Device-Fingerprint` | Fraud detection | Device hash for velocity checks |
| `Last-Event-ID` | SSE replay | `12450` (for event replay) |
| `X-API-Key` | Partner authentication | `bhk_...` (SHA-256 hashed) |

### 6.3 Common Response Headers
| Header | Description | Example |
|--------|-------------|---------|
| `Content-Type` | Response body type | `application/json` |
| `X-API-Version` | API version | `1` |
| `Deprecation` | API deprecation notice | `true; sunset="2026-12-31"` |
| `X-RateLimit-Limit` | Rate limit config | `20` (requests) |
| `X-RateLimit-Remaining` | Current quota | `15` |
| `X-RateLimit-Reset` | Reset timestamp | `1693005600` |
| `Retry-After` | Seconds until retry | `300` |
| `traceId` | Request tracing ID | `8060e534c23630e6a1393f746a07029d` |

### 6.4 Standard Date/Time Format
All timestamps use ISO-8601 UTC format:
- **Example**: `2026-08-25T19:13:52Z`
- **Parsing**: Most languages have built-in ISO-8601 parsers
- **Timezone**: Always UTC (Zulu time) - convert client-side for display

### 6.5 Common Query Parameters
| Parameter | Description | Example |
|-----------|-------------|---------|
| `page` | Page number (0-based) | `0` |
| `size` | Page size | `20` |
| `cursor` | Pagination token | `abc123def456` |
| `fields` | Field selection | `id,orderNumber,status` |
| `sort` | Sort order | `createdAt,desc` |
| `from` | Date range start | `2026-08-01T00:00:00Z` |
| `to` | Date range end | `2026-08-31T23:59:59Z` |
| `lat` | Latitude for geo-search | `12.9716` |
| `lng` | Longitude for geo-search | `77.5946` |
| `radius` | Search radius in meters | `1000` |

### 6.5 Error Response Structure
All error responses follow this format:
```json
{
  "success": false,
  "message": "Human-readable error description",
  "data": null || { /* error details */ },
  "timestamp": "ISO-8601 UTC timestamp",
  "traceId": "Unique request identifier",
  "spanId": "OpenTelemetry span ID (when tracing enabled)",
  "requestId": "Alternative request identifier"
}
```

Validation errors include field details in the `data` field:
```json
{
  "success": false,
  "message": "Validation failed",
  "data": {
    "fieldErrors": [
      {
        "field": "fieldName",
        "message": "Error description",
        "rejectedValue": "value that failed validation"
      }
    ]
  },
  ...
}
```

---

## 7. Glossary of Terms

### 7.1 Technical Terms
- **API**: Application Programming Interface - set of rules for building software applications
- **REST**: Representational State Transfer - architectural style for networked applications
- **DTO**: Data Transfer Object - object used to encapsulate data for transfer
- **JWT**: JSON Web Token - open standard for securely transmitting information
- **Idempotency**: Property whereby multiple identical requests have same effect as single request
- **SSE**: Server-Sent Events - server pushes real-time updates to clients over HTTP
- **Webhook**: HTTP callback triggered by specific events
- **CDN**: Content Delivery Network - geographically distributed server network
- **OSRM**: Open Source Routing Engine - for high-performance routing engines
- **GDPR**: General Data Protection Regulation - EU data privacy regulation
- **PCI DSS**: Payment Card Industry Data Security Standard

### 7.2 Business Terms
- **COD**: Cash On Delivery - payment method where customer pays upon delivery
- **UPI**: Unified Payments Interface - Indian real-time payment system
- **ETA**: Estimated Time of Arrival - predicted delivery time
- **SLA**: Service Level Agreement - commitment between service provider and client
- **KPI**: Key Performance Indicator - measurable value demonstrating business effectiveness
- **LTV**: Lifetime Value - predicted revenue from customer relationship
- **CAC**: Customer Acquisition Cost - cost to acquire a new customer
- **GMV**: Gross Merchandise Value - total value of merchandise sold

### 7.3 Status Codes (Orders)
| Status | Description |
|--------|-------------|
| `SCHEDULED` | Order scheduled for future delivery |
| `PLACED` | Order received but not yet confirmed by restaurant |
| `CONFIRMED` | Restaurant accepted order, preparing food |
| `READY_FOR_PICKUP` | Order ready for delivery agent pickup |
| `OUT_FOR_DELIVERY` | Delivery agent has picked up order |
| `DELIVERED` | Order successfully delivered to customer |
| `CANCELLED` | Order cancelled by customer/restaurant/system |
| `REFUNDED` | Order cancelled and payment refunded |
| `FAILED` | Order processing failed (payment, validation, etc.) |

### 7.4 Payment Methods
| Method | Description |
|--------|-------------|
| `CASH_ON_DELIVERY` | Pay cash to delivery agent |
| `UPI` | Unified Payments Interface (real-time bank transfer) |
| `CREDIT_CARD` | Credit card payment |
| `DEBIT_CARD` | Debit card payment |
| `NET_BANKING` | Online bank transfer |
| `WALLET` | Bhukkad wallet balance |

### 7.5 Roles & Permissions
| Role | Primary Permissions |
|------|---------------------|
| `CUSTOMER` | Browse, cart, orders, wallet, profile, reviews |
| `RESTAURANT_OWNER` | Restaurant management, menu, orders, analytics, settlements |
| `DELIVERY_AGENT` | Delivery acceptance, tracking, earnings, proof of delivery |
| `ADMIN` | Full system access: users, restaurants, analytics, settlements, configurations |

---

## Conclusion

This consolidated documentation provides a comprehensive reference for understanding, using, and extending the Bhukkad API system. It combines:

1. **The API Contract Delivery Process** - How APIs are defined, reviewed, deployed, and maintained
2. **API Architecture Overview** - Technical foundations and design patterns
3. **Functional Area Reference** - Complete endpoint catalog organized by business domain
4. **Usage Patterns & Best Practices** - Guidelines for effective API consumption and integration
5. **Existing Documentation References** - Pointers to deeper dives in specialized documents
6. **Quick Reference Tables** - Essential information at a glance
7. **Glossary of Terms** - Common terminology used throughout the system

Developers and integrators should use this document as their primary reference for API consumption, while referring to the specialized documents in `docs/` for detailed implementation specifics, deployment procedures, and feature-specific guidance.

The Bhukkad API system follows RESTful principles with strong attention to security, scalability, and developer experience. Consistent application of the patterns and practices outlined here will ensure reliable, maintainable, and performant integrations with the platform.

*Last Updated: 2026-08-25*