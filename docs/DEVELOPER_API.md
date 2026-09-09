# Bhukkad Developer API Reference

This document provides a comprehensive reference for the Bhukkad Food Delivery System REST APIs. It is intended for frontend developers, mobile app developers, and third-party integrators.

## Table of Contents

- [Overview](#overview)
- [Authentication](#authentication)
- [Base URL](#base-url)
- [Common Headers](#common-headers)
- [Error Handling](#error-handling)
- [Rate Limiting](#rate-limiting)
- [API Endpoints](#api-endpoints)
  - [Health & Status](#health--status)
  - [Authentication](#authentication-endpoints)
  - [Customer APIs](#customer-apis)
  - [Restaurant APIs](#restaurant-apis)
  - [Order APIs](#order-apis)
  - [Cart APIs](#cart-apis)
  - [Menu APIs](#menu-apis)
  - [Payment APIs](#payment-apis)
  - [Delivery APIs](#delivery-apis)
  - [Review APIs](#review-apis)
  - [Admin APIs](#admin-apis)
  - [Analytics APIs](#analytics-apis)

---

## Overview

The Bhukkad API is a RESTful API that follows standard HTTP semantics. All requests and responses use JSON unless otherwise specified. The API version is included in the URL path: `/api/v1/...`

## Base URL

```
http://localhost:8080/api/v1
```

For production deployments, replace `localhost:8080` with the appropriate domain.

## Common Headers

| Header | Required | Description |
|--------|----------|-------------|
| `Content-Type` | Yes (POST/PUT) | `application/json` |
| `Authorization` | Yes (protected endpoints) | `Bearer <JWT_TOKEN>` |
| `Accept` | No | `application/json` |
| `Idempotency-Key` | Yes (order creation) | UUID to prevent duplicate orders |

## Authentication

Most endpoints require a JWT token obtained via the login or register endpoints. Include the token in the `Authorization` header:

```
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
```

### Token Lifecycle

- **Access Token**: Valid for 24 hours
- **Refresh Token**: Valid for 7 days
- Use the refresh token to obtain a new access token without re-authenticating

### Roles

| Role | Description |
|------|-------------|
| `CUSTOMER` | End-user ordering food |
| `RESTAURANT_OWNER` | Restaurant manager |
| `DELIVERY_AGENT` | Delivery personnel |
| `ADMIN` | Platform administrator |

## Error Handling

All errors follow a consistent structure:

```json
{
  "success": false,
  "message": "Error description",
  "data": null,
  "timestamp": "2026-08-30T10:00:00.000Z",
  "traceId": "abc123",
  "spanId": "def456"
}
```

### HTTP Status Codes

| Code | Meaning |
|------|---------|
| 200 | Success |
| 201 | Created |
| 204 | No Content |
| 400 | Bad Request — validation error |
| 401 | Unauthorized — missing or invalid token |
| 403 | Forbidden — insufficient permissions |
| 404 | Not Found |
| 409 | Conflict — duplicate resource |
| 429 | Too Many Requests — rate limited |
| 500 | Internal Server Error |

## Rate Limiting

The API implements rate limiting on certain endpoints:

| Endpoint Pattern | Limit | Window |
|------------------|-------|--------|
| `/auth/register` | 100 requests | 60 seconds |
| `/cart/*` | 50 requests | 60 seconds |
| `/orders/customer/create` | 20 requests | 60 seconds |
| `/orders/customer/track/*` | 20 requests | 60 seconds |

Rate limit headers are included in responses:
```
X-RateLimit-Limit: 20
X-RateLimit-Remaining: 19
X-RateLimit-Reset: 60
```

---

## API Endpoints

### Health & Status

#### GET /health/ping
Simple health check endpoint.

**Response:**
```json
{
  "status": "UP",
  "timestamp": "2026-08-30T10:00:00.000Z"
}
```

#### GET /actuator/health
Detailed health check with component status.

**Response:**
```json
{
  "status": "UP",
  "components": {
    "db": { "status": "UP" },
    "redis": { "status": "UP" },
    "diskSpace": { "status": "UP" }
  }
}
```

---

### Authentication Endpoints

#### POST /auth/register
Register a new user account.

**Request Body:**
```json
{
  "fullName": "John Doe",
  "email": "john@example.com",
  "password": "SecurePass123!",
  "phoneNumber": "9876543210",
  "role": "CUSTOMER"
}
```

**Response (201):**
```json
{
  "success": true,
  "message": "Registration successful",
  "data": {
    "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
    "refreshToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
    "tokenType": "Bearer",
    "userId": 123,
    "email": "john@example.com",
    "role": "CUSTOMER",
    "mfaRequired": false
  }
}
```

#### POST /auth/login
Authenticate and receive access/refresh tokens.

**Request Body:**
```json
{
  "email": "john@example.com",
  "password": "SecurePass123!"
}
```

**Response (200):**
```json
{
  "success": true,
  "message": "Login successful",
  "data": {
    "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
    "refreshToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
    "tokenType": "Bearer",
    "userId": 123,
    "email": "john@example.com",
    "role": "CUSTOMER"
  }
}
```

#### POST /auth/refresh
Refresh an expired access token using a refresh token.

**Request Body:**
```json
{
  "refreshToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
}
```

**Response (200):**
```json
{
  "success": true,
  "data": {
    "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
    "refreshToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
    "tokenType": "Bearer"
  }
}
```

#### POST /auth/logout
Invalidate the current access token.

**Headers:**
```
Authorization: Bearer <token>
```

**Response (200):**
```json
{
  "success": true,
  "message": "Logout successful"
}
```

#### POST /auth/change-password
Change the authenticated user's password.

**Headers:**
```
Authorization: Bearer <token>
```

**Request Body:**
```json
{
  "oldPassword": "OldPass123!",
  "newPassword": "NewPass456!"
}
```

**Response (200):**
```json
{
  "success": true,
  "message": "Password changed successfully"
}
```

#### POST /auth/forgot-password
Initiate password reset flow.

**Request Body:**
```json
{
  "email": "john@example.com"
}
```

**Response (200):**
```json
{
  "success": true,
  "message": "Password reset email sent"
}
```

---

### Customer APIs

#### GET /customers/profile
Get the authenticated customer's profile.

**Headers:**
```
Authorization: Bearer <customer_token>
```

**Response (200):**
```json
{
  "success": true,
  "data": {
    "id": 123,
    "fullName": "John Doe",
    "email": "john@example.com",
    "phoneNumber": "9876543210",
    "profileImageUrl": "https://example.com/profile.jpg",
    "defaultAddressId": 456
  }
}
```

#### PUT /customers/profile
Update customer profile information.

**Headers:**
```
Authorization: Bearer <customer_token>
```

**Request Body:**
```json
{
  "fullName": "John Doe Updated",
  "phoneNumber": "9876543210",
  "profileImageUrl": "https://example.com/new-profile.jpg"
}
```

**Response (200):**
```json
{
  "success": true,
  "message": "Profile updated successfully",
  "data": { /* updated profile */ }
}
```

#### GET /customers/addresses
List all addresses for the authenticated customer.

**Headers:**
```
Authorization: Bearer <customer_token>
```

**Response (200):**
```json
{
  "success": true,
  "data": [
    {
      "id": 456,
      "addressLine1": "123 Main St",
      "city": "Bangalore",
      "state": "KA",
      "pincode": "560001",
      "latitude": 12.9716,
      "longitude": 77.5946,
      "type": "HOME",
      "isDefault": true
    }
  ]
}
```

#### POST /customers/addresses
Add a new delivery address.

**Headers:**
```
Authorization: Bearer <customer_token>
```

**Request Body:**
```json
{
  "addressLine1": "456 Oak Avenue",
  "addressLine2": "Apt 5B",
  "city": "Bangalore",
  "state": "KA",
  "pincode": "560002",
  "latitude": 12.9850,
  "longitude": 77.6100,
  "type": "WORK",
  "isDefault": false
}
```

**Response (201):**
```json
{
  "success": true,
  "message": "Address added successfully",
  "data": { /* created address */ }
}
```

#### GET /customers/my-orders
Get paginated list of customer's orders.

**Headers:**
```
Authorization: Bearer <customer_token>
```

**Query Parameters:**
- `page` (optional): Page number (default: 0)
- `size` (optional): Page size (default: 20, max: 100)

**Response (200):**
```json
{
  "success": true,
  "data": {
    "items": [
      {
        "id": 789,
        "orderNumber": "ORD-2024-001",
        "restaurantName": "Spice Route",
        "status": "DELIVERED",
        "totalAmount": 450.00,
        "createdAt": "2026-08-30T10:00:00.000Z"
      }
    ],
    "totalElements": 1,
    "totalPages": 1,
    "currentPage": 0
  }
}
```

---

### Restaurant APIs

#### GET /restaurants/public
List all active restaurants with optional filtering.

**Response (200):**
```json
{
  "success": true,
  "data": [
    {
      "id": 1,
      "name": "Spice Route",
      "description": "Authentic North Indian cuisine",
      "cuisines": ["North Indian", "Mughlai"],
      "imageUrl": "https://example.com/restaurant.jpg",
      "averageRating": 4.5,
      "totalReviews": 230,
      "deliveryFee": 30.00,
      "minimumOrderAmount": 100.00,
      "isPureVeg": false,
      "features": ["COD", "ONLINE_PAY"],
      "openingTime": "09:00:00",
      "closingTime": "23:00:00",
      "isOpen": true
    }
  ]
}
```

**Query Parameters:**
- `ids` (optional): Comma-separated restaurant IDs for batch fetch
- `latitude` (optional): User latitude for distance calculation
- `longitude` (optional): User longitude for distance calculation
- `radiusKm` (optional): Search radius in kilometers

#### GET /restaurants/public/{id}
Get detailed information about a specific restaurant.

**Response (200):**
```json
{
  "success": true,
  "data": {
    "id": 1,
    "name": "Spice Route",
    "description": "Authentic North Indian cuisine",
    "address": {
      "addressLine1": "123 Food Street",
      "city": "Bangalore",
      "state": "KA",
      "pincode": "560001"
    },
    "cuisines": ["North Indian", "Mughlai"],
    "features": ["COD", "ONLINE_PAY"],
    "menu": [ /* menu items */ ]
  }
}
```

#### GET /restaurants/public/search
Search restaurants by keyword.

**Query Parameters:**
- `keyword` (required): Search term

**Response (200):**
```json
{
  "success": true,
  "data": [
    {
      "id": 1,
      "name": "Spice Route",
      "description": "Authentic North Indian cuisine",
      "matchScore": 0.95
    }
  ]
}
```

---

### Order APIs

#### POST /orders/customer/create
Place a new order.

**Headers:**
```
Authorization: Bearer <customer_token>
Idempotency-Key: <unique-uuid>
```

**Request Body:**
```json
{
  "restaurantId": 1,
  "deliveryAddressId": 456,
  "paymentMethod": "CASH_ON_DELIVERY",
  "tipAmount": 20.00,
  "specialInstructions": "Please ring the bell twice",
  "scheduledAt": "2026-08-30T12:30:00"
}
```

**Response (201):**
```json
{
  "success": true,
  "message": "Order placed successfully",
  "data": {
    "id": 789,
    "orderNumber": "ORD-2024-001",
    "status": "PLACED",
    "subtotal": 400.00,
    "deliveryFee": 30.00,
    "taxAmount": 36.00,
    "totalAmount": 466.00,
    "estimatedDeliveryAt": "2026-08-30T11:30:00.000Z",
    "createdAt": "2026-08-30T10:00:00.000Z"
  }
}
```

#### GET /orders/customer/{orderId}
Get order details.

**Headers:**
```
Authorization: Bearer <customer_token>
```

**Response (200):**
```json
{
  "success": true,
  "data": {
    "id": 789,
    "orderNumber": "ORD-2024-001",
    "status": "PLACED",
    "items": [
      {
        "id": 1,
        "menuItemId": 10,
        "name": "Paneer Tikka",
        "quantity": 2,
        "price": 200.00,
        "totalPrice": 400.00
      }
    ],
    "subtotal": 400.00,
    "deliveryFee": 30.00,
    "taxAmount": 36.00,
    "totalAmount": 466.00
  }
}
```

#### PUT /orders/restaurant/{orderId}/accept
Accept an order (restaurant owner).

**Headers:**
```
Authorization: Bearer <owner_token>
```

**Response (200):**
```json
{
  "success": true,
  "message": "Order accepted",
  "data": { /* updated order */ }
}
```

#### PUT /orders/delivery/{orderId}/picked-up
Mark order as picked up (delivery agent).

**Headers:**
```
Authorization: Bearer <agent_token>
```

**Response (200):**
```json
{
  "success": true,
  "message": "Order marked as picked up"
}
```

#### PUT /orders/delivery/{orderId}/delivered
Mark order as delivered (delivery agent).

**Headers:**
```
Authorization: Bearer <agent_token>
```

**Response (200):**
```json
{
  "success": true,
  "message": "Order marked as delivered"
}
```

---

### Cart APIs

#### GET /cart
Get the current user's cart.

**Headers:**
```
Authorization: Bearer <customer_token>
```

**Response (200):**
```json
{
  "success": true,
  "data": {
    "id": 1,
    "restaurantId": 1,
    "restaurantName": "Spice Route",
    "items": [
      {
        "id": 1,
        "menuItemId": 10,
        "name": "Paneer Tikka",
        "quantity": 2,
        "price": 200.00,
        "totalPrice": 400.00
      }
    ],
    "subtotal": 400.00,
    "itemCount": 2
  }
}
```

#### POST /cart/add
Add item to cart.

**Headers:**
```
Authorization: Bearer <customer_token>
```

**Request Body:**
```json
{
  "menuItemId": 10,
  "quantity": 2
}
```

**Response (200):**
```json
{
  "success": true,
  "message": "Item added to cart",
  "data": { /* updated cart */ }
}
```

#### PUT /cart/items/{cartItemId}
Update item quantity.

**Headers:**
```
Authorization: Bearer <customer_token>
```

**Query Parameters:**
- `quantity` (required): New quantity

**Response (200):**
```json
{
  "success": true,
  "message": "Cart updated",
  "data": { /* updated cart */ }
}
```

#### DELETE /cart/items/{cartItemId}
Remove item from cart.

**Headers:**
```
Authorization: Bearer <customer_token>
```

**Response (200):**
```json
{
  "success": true,
  "message": "Item removed from cart",
  "data": { /* updated cart */ }
}
```

---

### Menu APIs

#### GET /menu/categories/restaurant/{restaurantId}
Get menu categories for a restaurant.

**Response (200):**
```json
{
  "success": true,
  "data": [
    {
      "id": 1,
      "name": "Starters",
      "description": "Appetizers and snacks",
      "displayOrder": 1,
      "active": true
    }
  ]
}
```

#### GET /menu/items/restaurant/{restaurantId}
Get all menu items for a restaurant.

**Response (200):**
```json
{
  "success": true,
  "data": [
    {
      "id": 10,
      "name": "Paneer Tikka",
      "description": "Grilled cottage cheese",
      "price": 200.00,
      "foodType": "VEG",
      "isVeg": true,
      "isSpicy": true,
      "spiceLevel": "MEDIUM",
      "preparationTime": 15,
      "imageUrl": "https://example.com/paneer.jpg",
      "categoryName": "Starters",
      "isAvailable": true
    }
  ]
}
```

#### GET /menu/items/{id}
Get details of a specific menu item.

**Response (200):**
```json
{
  "success": true,
  "data": {
    "id": 10,
    "name": "Paneer Tikka",
    "description": "Grilled cottage cheese with spices",
    "price": 200.00,
    "foodType": "VEG",
    "isVeg": true,
    "isSpicy": true,
    "spiceLevel": "MEDIUM",
    "preparationTime": 15,
    "imageUrl": "https://example.com/paneer.jpg",
    "categoryName": "Starters",
    "tags": ["bestseller", "chef-special"],
    "allergens": ["dairy"],
    "ingredients": ["paneer", "spices", "butter"],
    "customizationOptions": [ /* options */ ],
    "isAvailable": true
  }
}
```

---

### Payment APIs

#### POST /payments/initiate
Initiate a payment for an order.

**Headers:**
```
Authorization: Bearer <customer_token>
```

**Request Body:**
```json
{
  "orderId": 789,
  "paymentMethod": "RAZORPAY",
  "amount": 466.00
}
```

**Response (200):**
```json
{
  "success": true,
  "data": {
    "paymentId": 123,
    "orderId": 789,
    "amount": 466.00,
    "currency": "INR",
    "status": "PENDING",
    "razorpayOrderId": "order_ABC123"
  }
}
```

#### POST /payments/webhook/razorpay
Razorpay webhook endpoint (server-to-server).

**Headers:**
```
X-Razorpay-Signature: <webhook_signature>
```

**Response (200):**
```json
{
  "success": true,
  "message": "Webhook processed"
}
```

---

### Delivery APIs

#### PUT /delivery/toggle-availability
Toggle delivery agent availability.

**Headers:**
```
Authorization: Bearer <agent_token>
```

**Query Parameters:**
- `available` (required): `true` or `false`

**Response (200):**
```json
{
  "success": true,
  "message": "Availability updated",
  "data": {
    "available": true
  }
}
```

#### GET /delivery/available-orders
Get list of orders available for delivery.

**Headers:**
```
Authorization: Bearer <agent_token>
```

**Response (200):**
```json
{
  "success": true,
  "data": [
    {
      "orderId": 789,
      "restaurantName": "Spice Route",
      "customerName": "John Doe",
      "deliveryAddress": "123 Main St, Bangalore",
      "totalAmount": 466.00,
      "estimatedDistance": 3.5
    }
  ]
}
```

#### POST /delivery/{orderId}/accept
Accept a delivery order.

**Headers:**
```
Authorization: Bearer <agent_token>
```

**Response (200):**
```json
{
  "success": true,
  "message": "Delivery accepted",
  "data": { /* order details */ }
}
```

---

### Review APIs

#### POST /reviews
Submit a review for a delivered order.

**Headers:**
```
Authorization: Bearer <customer_token>
```

**Request Body:**
```json
{
  "orderId": 789,
  "rating": 5,
  "comment": "Excellent food and quick delivery!",
  "images": ["https://example.com/review1.jpg"]
}
```

**Response (201):**
```json
{
  "success": true,
  "message": "Review submitted successfully",
  "data": {
    "id": 1,
    "orderId": 789,
    "rating": 5,
    "comment": "Excellent food and quick delivery!",
    "createdAt": "2026-08-30T10:00:00.000Z"
  }
}
```

#### GET /restaurants/{restaurantId}/reviews
Get reviews for a restaurant.

**Response (200):**
```json
{
  "success": true,
  "data": {
    "items": [
      {
        "id": 1,
        "customerName": "John Doe",
        "rating": 5,
        "comment": "Excellent food!",
        "createdAt": "2026-08-30T10:00:00.000Z"
      }
    ],
    "averageRating": 4.5,
    "totalReviews": 230
  }
}
```

---

### Admin APIs

#### GET /admin/restaurants
List all restaurants (admin view).

**Headers:**
```
Authorization: Bearer <admin_token>
```

**Query Parameters:**
- `page` (optional): Page number
- `size` (optional): Page size
- `status` (optional): Filter by active status

**Response (200):**
```json
{
  "success": true,
  "data": {
    "items": [ /* restaurants */ ],
    "totalElements": 100,
    "totalPages": 5
  }
}
```

#### PUT /admin/restaurants/{restaurantId}/toggle-status
Toggle restaurant active status.

**Headers:**
```
Authorization: Bearer <admin_token>
```

**Query Parameters:**
- `isActive` (required): `true` or `false`

**Response (200):**
```json
{
  "success": true,
  "message": "Restaurant status updated",
  "data": { /* updated restaurant */ }
}
```

#### GET /admin/agents
List all delivery agents.

**Headers:**
```
Authorization: Bearer <admin_token>
```

**Response (200):**
```json
{
  "success": true,
  "data": [ /* delivery agents */ ]
}
```

#### PUT /admin/agents/{agentId}/verify
Verify a delivery agent's identity.

**Headers:**
```
Authorization: Bearer <admin_token>
```

**Response (200):**
```json
{
  "success": true,
  "message": "Agent verified successfully",
  "data": { /* updated agent */ }
}
```

---

### Analytics APIs

#### GET /analytics/export/orders
Stream orders CSV export.

**Headers:**
```
Authorization: Bearer <admin_token>
```

**Query Parameters:**
- `fromDate` (optional): Start date (YYYY-MM-DD)
- `toDate` (optional): End date (YYYY-MM-DD)

**Response (200):**
```
Content-Type: text/csv
Content-Disposition: attachment; filename=orders_export_20260830_1000.csv

order_number,customer_name,restaurant_name,status,total_amount,created_at
ORD-2024-001,John Doe,Spice Route,DELIVERED,466.00,2026-08-30T10:00:00.000Z
...
```

#### GET /analytics/export/restaurants
Stream restaurants CSV export.

**Headers:**
```
Authorization: Bearer <admin_token>
```

**Query Parameters:**
- `city` (optional): Filter by city

**Response (200):**
```
Content-Type: text/csv
Content-Disposition: attachment; filename=restaurants_export_20260830_1000.csv

id,name,city,status,average_rating,total_reviews
1,Spice Route,Bangalore,ACTIVE,4.5,230
...
```

---

## Pagination

List endpoints support pagination via query parameters:

- `page` (default: 0) — zero-based page index
- `size` (default: 20) — page size

Response includes:
```json
{
  "items": [ /* resources */ ],
  "totalElements": 100,
  "totalPages": 5,
  "currentPage": 0,
  "hasNext": true,
  "hasPrevious": false
}
```

## Filtering & Search

Many list endpoints support filtering:

- `keyword` — full-text search
- `status` — filter by status
- `fromDate` / `toDate` — date range filtering
- `city` — geographic filtering

## Caching

The API uses HTTP caching for public endpoints:

- `ETag` header for conditional requests
- `Cache-Control` headers for client-side caching
- `If-None-Match` support for 304 responses

## Webhooks

Webhook endpoints are server-to-server only. Verify the `X-Razorpay-Signature` header for payment webhooks.

---

## Changelog

- **v1.0.0** — Initial API version
- **v1.1.0** — Added scheduled orders, gift cards
- **v1.2.0** — Added delivery proof, batch operations
- **v1.3.0** — Added analytics exports, recommendations
