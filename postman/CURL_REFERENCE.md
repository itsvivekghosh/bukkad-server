# Bhukkad API — cURL Reference
Set variables:
```bash
export BASE=http://localhost:8080
export TOKEN=<from login>
```

## 01 - Health
### Ping
```bash
curl -s -X GET "$BASE/api/v1/health/ping" \
  -H 'Authorization: Bearer $TOKEN'
```
### Health
```bash
curl -s -X GET "$BASE/api/v1/health" \
  -H 'Authorization: Bearer $TOKEN'
```
### Health Detailed
```bash
curl -s -X GET "$BASE/api/v1/health/detailed" \
  -H 'Authorization: Bearer $TOKEN'
```
### Health DB
```bash
curl -s -X GET "$BASE/api/v1/health/db" \
  -H 'Authorization: Bearer $TOKEN'
```
### Health Memory
```bash
curl -s -X GET "$BASE/api/v1/health/memory" \
  -H 'Authorization: Bearer $TOKEN'
```
### Actuator Health
```bash
curl -s -X GET "$BASE/actuator/health" \
  -H 'Authorization: Bearer $TOKEN'
```

## 02 - Auth
### Register Customer
```bash
curl -s -X POST "$BASE/api/v1/auth/register" \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer $TOKEN' \
  -d '{
  "fullName": "Postman Customer",
  "email": "${customerEmail}",
  "password": "${password}",
  "phoneNumber": "9876543210",
  "role": "CUSTOMER"
}'
```
### Register Owner
```bash
curl -s -X POST "$BASE/api/v1/auth/register" \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer $TOKEN' \
  -d '{
  "fullName": "Postman Owner",
  "email": "${ownerEmail}",
  "password": "${password}",
  "phoneNumber": "9876543210",
  "role": "RESTAURANT_OWNER"
}'
```
### Register Agent
```bash
curl -s -X POST "$BASE/api/v1/auth/register" \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer $TOKEN' \
  -d '{
  "fullName": "Postman Agent",
  "email": "${agentEmail}",
  "password": "${password}",
  "phoneNumber": "9876543210",
  "role": "DELIVERY_AGENT"
}'
```
### Register Admin
```bash
curl -s -X POST "$BASE/api/v1/auth/register" \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer $TOKEN' \
  -d '{
  "fullName": "Postman Admin",
  "email": "${adminEmail}",
  "password": "${password}",
  "phoneNumber": "9876543210",
  "role": "ADMIN"
}'
```
### Login Customer
```bash
curl -s -X POST "$BASE/api/v1/auth/login" \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer $TOKEN' \
  -d '{
  "email": "${customerEmail}",
  "password": "${password}"
}'
```
### Login Owner
```bash
curl -s -X POST "$BASE/api/v1/auth/login" \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer $TOKEN' \
  -d '{
  "email": "${ownerEmail}",
  "password": "${password}"
}'
```
### Login Agent
```bash
curl -s -X POST "$BASE/api/v1/auth/login" \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer $TOKEN' \
  -d '{
  "email": "${agentEmail}",
  "password": "${password}"
}'
```
### Login Admin
```bash
curl -s -X POST "$BASE/api/v1/auth/login" \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer $TOKEN' \
  -d '{
  "email": "${adminEmail}",
  "password": "${password}"
}'
```
### Refresh Token
```bash
curl -s -X POST "$BASE/api/v1/auth/refresh-token" \
  -H 'Authorization: Bearer $TOKEN'
```
### Verify Email
```bash
curl -s -X POST "$BASE/api/v1/auth/verify-email?email=${customerEmail}" \
  -H 'Authorization: Bearer $TOKEN'
```
### Forgot Password
```bash
curl -s -X POST "$BASE/api/v1/auth/forgot-password?email=${customerEmail}" \
  -H 'Authorization: Bearer $TOKEN'
```
### Reset Password
```bash
curl -s -X POST "$BASE/api/v1/auth/reset-password?token=${resetToken}&newPassword=${password}" \
  -H 'Authorization: Bearer $TOKEN'
```
### Change Password
```bash
curl -s -X POST "$BASE/api/v1/auth/change-password?oldPassword=${password}&newPassword=secret456" \
  -H 'Authorization: Bearer $TOKEN'
```
### Logout
```bash
curl -s -X POST "$BASE/api/v1/auth/logout" \
  -H 'Authorization: Bearer $TOKEN'
```

## 03 - Public
### List Restaurants
```bash
curl -s -X GET "$BASE/api/v1/restaurants/public" \
  -H 'Authorization: Bearer $TOKEN'
```
### Restaurant By ID
```bash
curl -s -X GET "$BASE/api/v1/restaurants/public/${restaurantId}" \
  -H 'Authorization: Bearer $TOKEN'
```
### Search Restaurants
```bash
curl -s -X GET "$BASE/api/v1/restaurants/public/search?keyword=pizza" \
  -H 'Authorization: Bearer $TOKEN'
```
### Nearby Restaurants
```bash
curl -s -X GET "$BASE/api/v1/restaurants/public/nearby?latitude=12.97&longitude=77.59&radiusKm=5" \
  -H 'Authorization: Bearer $TOKEN'
```
### Filter Restaurants
```bash
curl -s -X GET "$BASE/api/v1/restaurants/public/filter?isPureVeg=true" \
  -H 'Authorization: Bearer $TOKEN'
```
### List Cuisines
```bash
curl -s -X GET "$BASE/api/v1/cuisines" \
  -H 'Authorization: Bearer $TOKEN'
```
### Cuisine By ID
```bash
curl -s -X GET "$BASE/api/v1/cuisines/${cuisineId}" \
  -H 'Authorization: Bearer $TOKEN'
```
### Menu Item By ID
```bash
curl -s -X GET "$BASE/api/v1/menu/items/${menuItemId}" \
  -H 'Authorization: Bearer $TOKEN'
```
### Menu By Category
```bash
curl -s -X GET "$BASE/api/v1/menu/items/category/${categoryId}" \
  -H 'Authorization: Bearer $TOKEN'
```
### Menu By Restaurant
```bash
curl -s -X GET "$BASE/api/v1/menu/items/restaurant/${restaurantId}" \
  -H 'Authorization: Bearer $TOKEN'
```
### Bestsellers
```bash
curl -s -X GET "$BASE/api/v1/menu/items/restaurant/${restaurantId}/bestsellers" \
  -H 'Authorization: Bearer $TOKEN'
```
### Recommended
```bash
curl -s -X GET "$BASE/api/v1/menu/items/restaurant/${restaurantId}/recommended" \
  -H 'Authorization: Bearer $TOKEN'
```
### Search Menu
```bash
curl -s -X GET "$BASE/api/v1/menu/items/search?keyword=biryani&restaurantId=${restaurantId}" \
  -H 'Authorization: Bearer $TOKEN'
```
### Categories By Restaurant
```bash
curl -s -X GET "$BASE/api/v1/menu/categories/restaurant/${restaurantId}" \
  -H 'Authorization: Bearer $TOKEN'
```
### Low Stock Items
```bash
curl -s -X GET "$BASE/api/v1/menu/items/restaurant/${restaurantId}/low-stock" \
  -H 'Authorization: Bearer $TOKEN'
```
### Active Coupons
```bash
curl -s -X GET "$BASE/api/v1/coupons/active" \
  -H 'Authorization: Bearer $TOKEN'
```
### Restaurant Reviews
```bash
curl -s -X GET "$BASE/api/v1/reviews/restaurant/${restaurantId}" \
  -H 'Authorization: Bearer $TOKEN'
```
### Menu Item Ratings
```bash
curl -s -X GET "$BASE/api/v1/reviews/menu-items/${menuItemId}" \
  -H 'Authorization: Bearer $TOKEN'
```

## 04 - Customer
### Get Profile
```bash
curl -s -X GET "$BASE/api/v1/customers/profile" \
  -H 'Authorization: Bearer $TOKEN'
```
### Get Profile By ID
```bash
curl -s -X GET "$BASE/api/v1/customers/profile/${userId}" \
  -H 'Authorization: Bearer $TOKEN'
```
### Update Profile
```bash
curl -s -X PUT "$BASE/api/v1/customers/profile?fullName=Updated Name" \
  -H 'Authorization: Bearer $TOKEN'
```
### Add Address
```bash
curl -s -X POST "$BASE/api/v1/customers/addresses" \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer $TOKEN' \
  -d '{
  "addressLine1": "123 MG Road",
  "city": "Bangalore",
  "state": "Karnataka",
  "pincode": "560001",
  "latitude": 12.9716,
  "longitude": 77.5946,
  "type": "HOME",
  "isDefault": true
}'
```
### List Addresses
```bash
curl -s -X GET "$BASE/api/v1/customers/addresses" \
  -H 'Authorization: Bearer $TOKEN'
```
### Set Default Address
```bash
curl -s -X PUT "$BASE/api/v1/customers/addresses/${addressId}/set-default" \
  -H 'Authorization: Bearer $TOKEN'
```
### Wallet Balance
```bash
curl -s -X GET "$BASE/api/v1/customers/wallet/balance" \
  -H 'Authorization: Bearer $TOKEN'
```
### Wallet Transactions
```bash
curl -s -X GET "$BASE/api/v1/customers/wallet/transactions?page=0&size=20" \
  -H 'Authorization: Bearer $TOKEN'
```
### Loyalty Points
```bash
curl -s -X GET "$BASE/api/v1/customers/loyalty-points" \
  -H 'Authorization: Bearer $TOKEN'
```
### Wallet Top-Up Initiate
```bash
curl -s -X POST "$BASE/api/v1/customers/wallet/top-up?amount=100" \
  -H 'Authorization: Bearer $TOKEN'
```
### Add Money to Wallet
```bash
curl -s -X POST "$BASE/api/v1/customers/wallet/add-money?amount=500" \
  -H 'Authorization: Bearer $TOKEN'
```
### Register Device Token
```bash
curl -s -X POST "$BASE/api/v1/customers/device-tokens" \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer $TOKEN' \
  -d '{
  "token": "fcm-device-token-sample",
  "platform": "ANDROID"
}'
```
### Unregister Device Token
```bash
curl -s -X DELETE "$BASE/api/v1/customers/device-tokens?token=fcm-device-token-sample" \
  -H 'Authorization: Bearer $TOKEN'
```
### Update Address
```bash
curl -s -X PUT "$BASE/api/v1/customers/addresses/${addressId}" \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer $TOKEN' \
  -d '{
  "addressLine1": "123 MG Road",
  "city": "Bangalore",
  "state": "Karnataka",
  "pincode": "560001",
  "latitude": 12.9716,
  "longitude": 77.5946,
  "type": "HOME",
  "isDefault": true
}'
```
### Delete Address
```bash
curl -s -X DELETE "$BASE/api/v1/customers/addresses/${addressId}" \
  -H 'Authorization: Bearer $TOKEN'
```
### Delete Account
```bash
curl -s -X DELETE "$BASE/api/v1/customers/account" \
  -H 'Authorization: Bearer $TOKEN'
```
### Get Referral Info
```bash
curl -s -X GET "$BASE/api/v1/customers/referral" \
  -H 'Authorization: Bearer $TOKEN'
```
### List Favorites
```bash
curl -s -X GET "$BASE/api/v1/customers/favorites" \
  -H 'Authorization: Bearer $TOKEN'
```
### Add Favorite
```bash
curl -s -X POST "$BASE/api/v1/customers/favorites/${restaurantId}" \
  -H 'Authorization: Bearer $TOKEN'
```
### Remove Favorite
```bash
curl -s -X DELETE "$BASE/api/v1/customers/favorites/${restaurantId}" \
  -H 'Authorization: Bearer $TOKEN'
```
### Order Stats
```bash
curl -s -X GET "$BASE/api/v1/customers/orders/stats" \
  -H 'Authorization: Bearer $TOKEN'
```
### Get Notification Preferences
```bash
curl -s -X GET "$BASE/api/v1/customers/notification-preferences" \
  -H 'Authorization: Bearer $TOKEN'
```
### Update Notification Preferences
```bash
curl -s -X PUT "$BASE/api/v1/customers/notification-preferences" \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer $TOKEN' \
  -d '{
  "emailEnabled": true,
  "smsEnabled": true,
  "pushEnabled": true,
  "whatsappEnabled": false,
  "orderUpdates": true,
  "promotions": true,
  "recommendations": true
}'
```
### Create Support Ticket
```bash
curl -s -X POST "$BASE/api/v1/customers/support/tickets" \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer $TOKEN' \
  -d '{
  "subject": "Order issue",
  "description": "My order was delayed",
  "category": "DELIVERY"
}'
```
### List Support Tickets
```bash
curl -s -X GET "$BASE/api/v1/customers/support/tickets" \
  -H 'Authorization: Bearer $TOKEN'
```
### List Membership Plans
```bash
curl -s -X GET "$BASE/api/v1/customers/membership/plans" \
  -H 'Authorization: Bearer $TOKEN'
```
### Get Membership Status
```bash
curl -s -X GET "$BASE/api/v1/customers/membership/status" \
  -H 'Authorization: Bearer $TOKEN'
```
### Subscribe Membership
```bash
curl -s -X POST "$BASE/api/v1/customers/membership/subscribe" \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer $TOKEN' \
  -d '{
  "planId": 1
}'
```
### Get Cart
```bash
curl -s -X GET "$BASE/api/v1/cart" \
  -H 'Authorization: Bearer $TOKEN'
```
### Add To Cart
```bash
curl -s -X POST "$BASE/api/v1/cart/add" \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer $TOKEN' \
  -d '{
  "menuItemId": "${menuItemId}",
  "quantity": 2,
  "specialInstructions": "less spicy"
}'
```
### Update Cart Item Qty
```bash
curl -s -X PUT "$BASE/api/v1/cart/items/${cartItemId}?quantity=3" \
  -H 'Authorization: Bearer $TOKEN'
```
### Remove Cart Item
```bash
curl -s -X DELETE "$BASE/api/v1/cart/items/${cartItemId}" \
  -H 'Authorization: Bearer $TOKEN'
```
### Clear Cart
```bash
curl -s -X DELETE "$BASE/api/v1/cart/clear" \
  -H 'Authorization: Bearer $TOKEN'
```
### Apply Coupon
```bash
curl -s -X POST "$BASE/api/v1/cart/apply-coupon?couponCode=${couponCode}" \
  -H 'Authorization: Bearer $TOKEN'
```
### Create Order
```bash
curl -s -X POST "$BASE/api/v1/orders/customer/create" \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer $TOKEN' \
  -d '{
  "restaurantId": "${restaurantId}",
  "deliveryAddressId": "${addressId}",
  "paymentMethod": "CASH_ON_DELIVERY",
  "specialInstructions": "Ring doorbell"
}'
```
### Create Order (Split Pay)
```bash
curl -s -X POST "$BASE/api/v1/orders/customer/create" \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer $TOKEN' \
  -d '{
  "restaurantId": "${restaurantId}",
  "deliveryAddressId": "${addressId}",
  "paymentMethod": "UPI",
  "useWallet": true,
  "walletAmountToUse": 50
}'
```
### Create Order Async
```bash
curl -s -X POST "$BASE/api/v1/orders/customer/create?async=true" \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer $TOKEN' \
  -d '{
  "restaurantId": "${restaurantId}",
  "deliveryAddressId": "${addressId}",
  "paymentMethod": "UPI"
}'
```
### Get Create Job
```bash
curl -s -X GET "$BASE/api/v1/orders/customer/create/jobs/${jobId}" \
  -H 'Authorization: Bearer $TOKEN'
```
### Create Batch Orders
```bash
curl -s -X POST "$BASE/api/v1/orders/customer/create-batch" \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer $TOKEN' \
  -d '{
  "orders": [
    {
      "restaurantId": "${restaurantId}",
      "deliveryAddressId": "${addressId}",
      "paymentMethod": "CASH_ON_DELIVERY",
      "items": [
        {
          "menuItemId": "${menuItemId}",
          "quantity": 1
        }
      ]
    }
  ]
}'
```
### My Orders
```bash
curl -s -X GET "$BASE/api/v1/orders/customer/my-orders?page=0&size=20" \
  -H 'Authorization: Bearer $TOKEN'
```
### My Orders Cursor
```bash
curl -s -X GET "$BASE/api/v1/orders/customer/my-orders/cursor?size=20" \
  -H 'Authorization: Bearer $TOKEN'
```
### Order By ID
```bash
curl -s -X GET "$BASE/api/v1/orders/customer/${orderId}" \
  -H 'Authorization: Bearer $TOKEN'
```
### Track Order
```bash
curl -s -X GET "$BASE/api/v1/orders/customer/track/${orderId}" \
  -H 'Authorization: Bearer $TOKEN'
```
### Rider Location
```bash
curl -s -X GET "$BASE/api/v1/orders/${orderId}/rider-location" \
  -H 'Authorization: Bearer $TOKEN'
```
### Order Timeline
```bash
curl -s -X GET "$BASE/api/v1/orders/${orderId}/timeline" \
  -H 'Authorization: Bearer $TOKEN'
```
### Order Invoice
```bash
curl -s -X GET "$BASE/api/v1/orders/${orderId}/invoice" \
  -H 'Authorization: Bearer $TOKEN'
```
### Download Invoice PDF
```bash
curl -s -X GET "$BASE/api/v1/orders/${orderId}/invoice/pdf" \
  -H 'Authorization: Bearer $TOKEN'
```
### Reorder
```bash
curl -s -X POST "$BASE/api/v1/orders/customer/${orderId}/reorder" \
  -H 'Authorization: Bearer $TOKEN'
```
### Cancel Order
```bash
curl -s -X PUT "$BASE/api/v1/orders/customer/${orderId}/cancel?reason=Changed mind" \
  -H 'Authorization: Bearer $TOKEN'
```
### Payment For Order
```bash
curl -s -X GET "$BASE/api/v1/payments/orders/${orderId}" \
  -H 'Authorization: Bearer $TOKEN'
```
### Validate Coupon
```bash
curl -s -X GET "$BASE/api/v1/coupons/validate?code=${couponCode}&subtotal=500&restaurantId=${restaurantId}" \
  -H 'Authorization: Bearer $TOKEN'
```
### Create Review
```bash
curl -s -X POST "$BASE/api/v1/reviews" \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer $TOKEN' \
  -d '{
  "orderId": "${orderId}",
  "rating": 5,
  "comment": "Great food!"
}'
```
### Rate Menu Item
```bash
curl -s -X POST "$BASE/api/v1/reviews/menu-items" \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer $TOKEN' \
  -d '{
  "menuItemId": "${menuItemId}",
  "rating": 5,
  "comment": "Delicious!"
}'
```
### My Reviews
```bash
curl -s -X GET "$BASE/api/v1/reviews/my-reviews" \
  -H 'Authorization: Bearer $TOKEN'
```
### Review By Order
```bash
curl -s -X GET "$BASE/api/v1/reviews/order/${orderId}" \
  -H 'Authorization: Bearer $TOKEN'
```
### Delete Review
```bash
curl -s -X DELETE "$BASE/api/v1/reviews/${reviewId}" \
  -H 'Authorization: Bearer $TOKEN'
```
### Clear Restaurant Cart
```bash
curl -s -X DELETE "$BASE/api/v1/cart/restaurant/${restaurantId}" \
  -H 'Authorization: Bearer $TOKEN'
```

## 05 - Restaurant Owner
### Create Restaurant
```bash
curl -s -X POST "$BASE/api/v1/restaurants/owner" \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer $TOKEN' \
  -d '{
  "name": "Postman Kitchen",
  "description": "Test restaurant",
  "address": {
    "addressLine1": "123 MG Road",
    "city": "Bangalore",
    "state": "Karnataka",
    "pincode": "560001",
    "latitude": 12.9716,
    "longitude": 77.5946,
    "type": "HOME",
    "isDefault": true
  },
  "openingTime": "09:00:00",
  "closingTime": "23:00:00",
  "minimumOrderAmount": 100,
  "deliveryFee": 40,
  "isPureVeg": false
}'
```
### My Restaurants
```bash
curl -s -X GET "$BASE/api/v1/restaurants/owner/my-restaurants" \
  -H 'Authorization: Bearer $TOKEN'
```
### Update Restaurant
```bash
curl -s -X PUT "$BASE/api/v1/restaurants/owner/${restaurantId}" \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer $TOKEN' \
  -d '{
  "name": "Postman Kitchen",
  "description": "Test restaurant",
  "address": {
    "addressLine1": "123 MG Road",
    "city": "Bangalore",
    "state": "Karnataka",
    "pincode": "560001",
    "latitude": 12.9716,
    "longitude": 77.5946,
    "type": "HOME",
    "isDefault": true
  },
  "openingTime": "09:00:00",
  "closingTime": "23:00:00",
  "minimumOrderAmount": 100,
  "deliveryFee": 40,
  "isPureVeg": false
}'
```
### Delete Restaurant
```bash
curl -s -X DELETE "$BASE/api/v1/restaurants/owner/${restaurantId}" \
  -H 'Authorization: Bearer $TOKEN'
```
### Toggle Open
```bash
curl -s -X PUT "$BASE/api/v1/restaurants/owner/${restaurantId}/toggle-status?isOpen=true" \
  -H 'Authorization: Bearer $TOKEN'
```
### Restaurant Analytics
```bash
curl -s -X GET "$BASE/api/v1/restaurants/owner/${restaurantId}/analytics?days=30" \
  -H 'Authorization: Bearer $TOKEN'
```
### Restaurant Settlements
```bash
curl -s -X GET "$BASE/api/v1/restaurants/owner/${restaurantId}/settlements?page=0&size=20" \
  -H 'Authorization: Bearer $TOKEN'
```
### Enable Busy Mode
```bash
curl -s -X PUT "$BASE/api/v1/restaurants/owner/${restaurantId}/busy-mode" \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer $TOKEN' \
  -d '{
  "enabled": true,
  "message": "High order volume",
  "estimatedDelayMinutes": 30
}'
```
### Disable Busy Mode
```bash
curl -s -X DELETE "$BASE/api/v1/restaurants/owner/${restaurantId}/busy-mode" \
  -H 'Authorization: Bearer $TOKEN'
```
### Restaurant Dashboard
```bash
curl -s -X GET "$BASE/api/v1/restaurants/owner/${restaurantId}/dashboard?days=30" \
  -H 'Authorization: Bearer $TOKEN'
```
### Respond to Review
```bash
curl -s -X POST "$BASE/api/v1/restaurants/owner/reviews/${reviewId}/response" \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer $TOKEN' \
  -d '{
  "response": "Thank you for your feedback!"
}'
```
### Create Category
```bash
curl -s -X POST "$BASE/api/v1/menu/categories?restaurantId=${restaurantId}" \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer $TOKEN' \
  -d '{
  "name": "Main Course",
  "description": "Mains",
  "displayOrder": 1
}'
```
### Create Menu Item
```bash
curl -s -X POST "$BASE/api/v1/menu/items" \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer $TOKEN' \
  -d '{
  "name": "Chicken Biryani",
  "categoryId": "${categoryId}",
  "price": 250,
  "foodType": "NON_VEG",
  "isVeg": false,
  "description": "Hyderabadi style"
}'
```
### Image Upload URL
```bash
curl -s -X POST "$BASE/api/v1/menu/items/${menuItemId}/image/upload-url" \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer $TOKEN' \
  -d '{
  "contentType": "image/jpeg",
  "fileName": "biryani.jpg"
}'
```
### Update Menu Item
```bash
curl -s -X PUT "$BASE/api/v1/menu/items/${menuItemId}" \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer $TOKEN' \
  -d '{
  "name": "Chicken Biryani",
  "categoryId": "${categoryId}",
  "price": 275,
  "foodType": "NON_VEG",
  "isVeg": false
}'
```
### Delete Menu Item
```bash
curl -s -X DELETE "$BASE/api/v1/menu/items/${menuItemId}" \
  -H 'Authorization: Bearer $TOKEN'
```
### Update Category
```bash
curl -s -X PUT "$BASE/api/v1/menu/categories/${categoryId}" \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer $TOKEN' \
  -d '{
  "name": "Main Course",
  "description": "Mains",
  "displayOrder": 1
}'
```
### Delete Category
```bash
curl -s -X DELETE "$BASE/api/v1/menu/categories/${categoryId}" \
  -H 'Authorization: Bearer $TOKEN'
```
### Toggle Item Availability
```bash
curl -s -X PUT "$BASE/api/v1/menu/items/${menuItemId}/toggle-availability?available=true" \
  -H 'Authorization: Bearer $TOKEN'
```
### Restaurant Orders
```bash
curl -s -X GET "$BASE/api/v1/orders/restaurant/${restaurantId}?page=0&size=20" \
  -H 'Authorization: Bearer $TOKEN'
```
### Pending Orders
```bash
curl -s -X GET "$BASE/api/v1/orders/restaurant/${restaurantId}/pending" \
  -H 'Authorization: Bearer $TOKEN'
```
### Kitchen Queue
```bash
curl -s -X GET "$BASE/api/v1/orders/restaurant/${restaurantId}/kitchen-queue" \
  -H 'Authorization: Bearer $TOKEN'
```
### Accept Order
```bash
curl -s -X PUT "$BASE/api/v1/orders/restaurant/${orderId}/accept" \
  -H 'Authorization: Bearer $TOKEN'
```
### Mark Ready
```bash
curl -s -X PUT "$BASE/api/v1/orders/restaurant/${orderId}/ready" \
  -H 'Authorization: Bearer $TOKEN'
```
### Assign Delivery Agent
```bash
curl -s -X PUT "$BASE/api/v1/orders/restaurant/${orderId}/assign-delivery?agentId=${agentUserId}" \
  -H 'Authorization: Bearer $TOKEN'
```

## 06 - Delivery Agent
### Get Profile
```bash
curl -s -X GET "$BASE/api/v1/delivery/profile" \
  -H 'Authorization: Bearer $TOKEN'
```
### Toggle Available
```bash
curl -s -X PUT "$BASE/api/v1/delivery/toggle-availability?available=true" \
  -H 'Authorization: Bearer $TOKEN'
```
### Update Location
```bash
curl -s -X PUT "$BASE/api/v1/delivery/update-location?latitude=12.97&longitude=77.59" \
  -H 'Authorization: Bearer $TOKEN'
```
### Available Orders
```bash
curl -s -X GET "$BASE/api/v1/delivery/available-orders" \
  -H 'Authorization: Bearer $TOKEN'
```
### Accept Delivery
```bash
curl -s -X POST "$BASE/api/v1/delivery/${orderId}/accept" \
  -H 'Authorization: Bearer $TOKEN'
```
### Active Deliveries
```bash
curl -s -X GET "$BASE/api/v1/delivery/active-deliveries" \
  -H 'Authorization: Bearer $TOKEN'
```
### My Deliveries
```bash
curl -s -X GET "$BASE/api/v1/orders/delivery/my-deliveries?page=0&size=20" \
  -H 'Authorization: Bearer $TOKEN'
```
### My Deliveries Cursor
```bash
curl -s -X GET "$BASE/api/v1/orders/delivery/my-deliveries/cursor?size=20" \
  -H 'Authorization: Bearer $TOKEN'
```
### Order By Number
```bash
curl -s -X GET "$BASE/api/v1/orders/number/${orderNumber}" \
  -H 'Authorization: Bearer $TOKEN'
```
### Mark Picked Up
```bash
curl -s -X PUT "$BASE/api/v1/orders/delivery/${orderId}/picked-up" \
  -H 'Authorization: Bearer $TOKEN'
```
### Issue Delivery Proof OTP
```bash
curl -s -X POST "$BASE/api/v1/orders/delivery/${orderId}/proof/otp" \
  -H 'Authorization: Bearer $TOKEN'
```
### Delivery Proof Photo URL
```bash
curl -s -X POST "$BASE/api/v1/orders/delivery/${orderId}/proof/photo-url" \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer $TOKEN' \
  -d '{
  "contentType": "image/jpeg"
}'
```
### Verify Delivery Proof
```bash
curl -s -X POST "$BASE/api/v1/orders/delivery/${orderId}/proof/verify" \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer $TOKEN' \
  -d '{
  "otpCode": "123456",
  "photoKey": "${deliveryProofPhotoKey}"
}'
```
### Get Delivery Proof
```bash
curl -s -X GET "$BASE/api/v1/orders/delivery/${orderId}/proof" \
  -H 'Authorization: Bearer $TOKEN'
```
### Mark Delivered
```bash
curl -s -X PUT "$BASE/api/v1/orders/delivery/${orderId}/delivered" \
  -H 'Authorization: Bearer $TOKEN'
```
### Earnings Summary
```bash
curl -s -X GET "$BASE/api/v1/delivery/earnings/summary" \
  -H 'Authorization: Bearer $TOKEN'
```
### Earnings History
```bash
curl -s -X GET "$BASE/api/v1/delivery/earnings?page=0&size=20" \
  -H 'Authorization: Bearer $TOKEN'
```
### Delivery History
```bash
curl -s -X GET "$BASE/api/v1/delivery/delivery-history" \
  -H 'Authorization: Bearer $TOKEN'
```
### Reject Delivery
```bash
curl -s -X POST "$BASE/api/v1/delivery/${orderId}/reject" \
  -H 'Authorization: Bearer $TOKEN'
```
### Update Order Location
```bash
curl -s -X POST "$BASE/api/v1/delivery/orders/${orderId}/location" \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer $TOKEN' \
  -d '{
  "latitude": 12.97,
  "longitude": 77.59
}'
```
### Create Delivery Batch
```bash
curl -s -X POST "$BASE/api/v1/delivery/batches" \
  -H 'Authorization: Bearer $TOKEN'
```
### Get Active Batch
```bash
curl -s -X GET "$BASE/api/v1/delivery/batches/active" \
  -H 'Authorization: Bearer $TOKEN'
```
### Complete Batch
```bash
curl -s -X PUT "$BASE/api/v1/delivery/batches/${batchId}/complete" \
  -H 'Authorization: Bearer $TOKEN'
```

## 07 - Admin
### Dashboard
```bash
curl -s -X GET "$BASE/api/v1/admin/dashboard" \
  -H 'Authorization: Bearer $TOKEN'
```
### All Users
```bash
curl -s -X GET "$BASE/api/v1/admin/users?page=0&size=20" \
  -H 'Authorization: Bearer $TOKEN'
```
### All Orders
```bash
curl -s -X GET "$BASE/api/v1/admin/orders?page=0&size=20" \
  -H 'Authorization: Bearer $TOKEN'
```
### All Restaurants
```bash
curl -s -X GET "$BASE/api/v1/admin/restaurants?page=0&size=20" \
  -H 'Authorization: Bearer $TOKEN'
```
### Revenue
```bash
curl -s -X GET "$BASE/api/v1/admin/revenue?days=7" \
  -H 'Authorization: Bearer $TOKEN'
```
### Analytics
```bash
curl -s -X GET "$BASE/api/v1/admin/analytics" \
  -H 'Authorization: Bearer $TOKEN'
```
### Activate User
```bash
curl -s -X PUT "$BASE/api/v1/admin/users/${userId}/activate" \
  -H 'Authorization: Bearer $TOKEN'
```
### Deactivate User
```bash
curl -s -X PUT "$BASE/api/v1/admin/users/${userId}/deactivate" \
  -H 'Authorization: Bearer $TOKEN'
```
### Verify Owner
```bash
curl -s -X PUT "$BASE/api/v1/admin/owners/${ownerId}/verify" \
  -H 'Authorization: Bearer $TOKEN'
```
### Verify Agent
```bash
curl -s -X PUT "$BASE/api/v1/admin/agents/${agentId}/verify" \
  -H 'Authorization: Bearer $TOKEN'
```
### Approve Restaurant
```bash
curl -s -X PUT "$BASE/api/v1/admin/restaurants/${restaurantId}/approve" \
  -H 'Authorization: Bearer $TOKEN'
```
### Suspend Restaurant
```bash
curl -s -X PUT "$BASE/api/v1/admin/restaurants/${restaurantId}/suspend" \
  -H 'Authorization: Bearer $TOKEN'
```
### Set Restaurant Commission
```bash
curl -s -X PUT "$BASE/api/v1/admin/restaurants/${restaurantId}/commission?percent=20" \
  -H 'Authorization: Bearer $TOKEN'
```
### Create Coupon
```bash
curl -s -X POST "$BASE/api/v1/coupons" \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer $TOKEN' \
  -d '{
  "code": "SAVE10",
  "discountType": "PERCENTAGE",
  "discountValue": 10,
  "minOrderAmount": 200,
  "maxDiscount": 100,
  "usageLimit": 100
}'
```
### Update Coupon
```bash
curl -s -X PUT "$BASE/api/v1/coupons/${couponId}" \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer $TOKEN' \
  -d '{
  "code": "SAVE10",
  "discountType": "PERCENTAGE",
  "discountValue": 15,
  "minOrderAmount": 200,
  "maxDiscount": 150,
  "usageLimit": 100
}'
```
### Delete Coupon
```bash
curl -s -X DELETE "$BASE/api/v1/coupons/${couponId}" \
  -H 'Authorization: Bearer $TOKEN'
```
### List All Support Tickets
```bash
curl -s -X GET "$BASE/api/v1/admin/support/tickets" \
  -H 'Authorization: Bearer $TOKEN'
```
### Update Ticket Status
```bash
curl -s -X PUT "$BASE/api/v1/admin/support/tickets/${ticketId}/status?status=RESOLVED&resolutionNotes=Issue resolved" \
  -H 'Authorization: Bearer $TOKEN'
```
### Send Test Notification
```bash
curl -s -X POST "$BASE/api/v1/admin/notifications/test" \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer $TOKEN' \
  -d '{
  "channel": "EMAIL",
  "recipient": "test@example.com",
  "message": "Test notification"
}'
```
### Review Moderation Queue
```bash
curl -s -X GET "$BASE/api/v1/admin/reviews/moderation" \
  -H 'Authorization: Bearer $TOKEN'
```
### Review Moderation Pending
```bash
curl -s -X GET "$BASE/api/v1/admin/reviews/moderation?status=PENDING" \
  -H 'Authorization: Bearer $TOKEN'
```
### Moderate Review Approve
```bash
curl -s -X PUT "$BASE/api/v1/admin/reviews/${reviewId}/moderate?status=APPROVED" \
  -H 'Authorization: Bearer $TOKEN'
```
### Moderate Review Reject
```bash
curl -s -X PUT "$BASE/api/v1/admin/reviews/${reviewId}/moderate?status=REJECTED" \
  -H 'Authorization: Bearer $TOKEN'
```

## 08 - Cache
### Cache Stats
```bash
curl -s -X GET "$BASE/api/v1/cache/stats" \
  -H 'Authorization: Bearer $TOKEN'
```
### Cache Health
```bash
curl -s -X GET "$BASE/api/v1/cache/health" \
  -H 'Authorization: Bearer $TOKEN'
```
### Clear All Caches
```bash
curl -s -X DELETE "$BASE/api/v1/cache/clear" \
  -H 'Authorization: Bearer $TOKEN'
```
### Clear Cache Pattern
```bash
curl -s -X DELETE "$BASE/api/v1/cache/clear/restaurants*" \
  -H 'Authorization: Bearer $TOKEN'
```

## 09 - Home Feed (Public)
### Get Banners
```bash
curl -s -X GET "$BASE/api/v1/home/banners" \
  -H 'Authorization: Bearer $TOKEN'
```
### Get Campaigns
```bash
curl -s -X GET "$BASE/api/v1/home/campaigns" \
  -H 'Authorization: Bearer $TOKEN'
```
### Get Home Feed (Composed)
```bash
curl -s -X GET "$BASE/api/v1/home/feed" \
  -H 'Authorization: Bearer $TOKEN'
```
### Get Membership Plans
```bash
curl -s -X GET "$BASE/api/v1/home/membership-plans" \
  -H 'Authorization: Bearer $TOKEN'
```

## 10 - Search (Public)
### Unified Search
```bash
curl -s -X GET "$BASE/api/v1/search?keyword=biryani" \
  -H 'Authorization: Bearer $TOKEN'
```

## 11 - Serviceability (Public)
### Check Serviceability
```bash
curl -s -X GET "$BASE/api/v1/serviceability/check?restaurantId=${restaurantId}&latitude=12.97&longitude=77.59&subtotal=500" \
  -H 'Authorization: Bearer $TOKEN'
```

## 12 - Platform (Info)
### Platform Status
```bash
curl -s -X GET "$BASE/api/v1/platform/status" \
  -H 'Authorization: Bearer $TOKEN'
```

## 13 - Delivery Truth (ETA)
### Order ETA Detail
```bash
curl -s -X GET "$BASE/api/v1/delivery-truth/orders/${orderId}/eta" \
  -H 'Authorization: Bearer $TOKEN'
```

## 14 - Streams & Webhooks
### SSE Customer Order
```bash
curl -s -X GET "$BASE/api/v1/orders/stream/customer/${orderId}" \
  -H 'Authorization: Bearer $TOKEN'
```
### SSE Kitchen
```bash
curl -s -X GET "$BASE/api/v1/orders/stream/kitchen/${restaurantId}" \
  -H 'Authorization: Bearer $TOKEN'
```
### SSE Rider
```bash
curl -s -X GET "$BASE/api/v1/orders/stream/rider" \
  -H 'Authorization: Bearer $TOKEN'
```
### Razorpay Webhook
```bash
curl -s -X POST "$BASE/api/v1/payments/webhooks/razorpay" \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer $TOKEN' \
  -d '{
  "event": "payment.captured",
  "payload": {}
}'
```

## 15 - Trust & Compliance (V17)
### Issue Delivery Proof OTP
```bash
curl -s -X POST "$BASE/api/v1/orders/delivery/${orderId}/proof/otp" \
  -H 'Authorization: Bearer $TOKEN'
```
### Delivery Proof Photo URL
```bash
curl -s -X POST "$BASE/api/v1/orders/delivery/${orderId}/proof/photo-url" \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer $TOKEN' \
  -d '{
  "contentType": "image/jpeg"
}'
```
### Verify Delivery Proof
```bash
curl -s -X POST "$BASE/api/v1/orders/delivery/${orderId}/proof/verify" \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer $TOKEN' \
  -d '{
  "otpCode": "123456"
}'
```
### Get Delivery Proof
```bash
curl -s -X GET "$BASE/api/v1/orders/delivery/${orderId}/proof" \
  -H 'Authorization: Bearer $TOKEN'
```
### Download Invoice PDF
```bash
curl -s -X GET "$BASE/api/v1/orders/${orderId}/invoice/pdf" \
  -H 'Authorization: Bearer $TOKEN'
```
### Review Moderation Queue
```bash
curl -s -X GET "$BASE/api/v1/admin/reviews/moderation" \
  -H 'Authorization: Bearer $TOKEN'
```
### Moderate Review
```bash
curl -s -X PUT "$BASE/api/v1/admin/reviews/${reviewId}/moderate?status=APPROVED" \
  -H 'Authorization: Bearer $TOKEN'
```

## 16 - Admin Scale (V14-V16)
### List Delivery Zones
```bash
curl -s -X GET "$BASE/api/v1/admin/zones" \
  -H 'Authorization: Bearer $TOKEN'
```
### Create Delivery Zone
```bash
curl -s -X POST "$BASE/api/v1/admin/zones" \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer $TOKEN' \
  -d '{
  "name": "Zone 1",
  "polygon": {
    "type": "Polygon",
    "coordinates": [
      [
        [
          77.5,
          12.9
        ],
        [
          77.6,
          12.9
        ],
        [
          77.6,
          13.0
        ],
        [
          77.5,
          13.0
        ],
        [
          77.5,
          12.9
        ]
      ]
    ]
  },
  "deliveryFee": 30,
  "minOrderAmount": 100,
  "isActive": true
}'
```
### Update Delivery Zone
```bash
curl -s -X PUT "$BASE/api/v1/admin/zones/${zoneId}" \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer $TOKEN' \
  -d '{
  "name": "Zone 1 Updated",
  "polygon": {
    "type": "Polygon",
    "coordinates": [
      [
        [
          77.5,
          12.9
        ],
        [
          77.6,
          12.9
        ],
        [
          77.6,
          13.0
        ],
        [
          77.5,
          13.0
        ],
        [
          77.5,
          12.9
        ]
      ]
    ]
  },
  "deliveryFee": 40,
  "minOrderAmount": 150,
  "isActive": true
}'
```
### Delete Delivery Zone
```bash
curl -s -X DELETE "$BASE/api/v1/admin/zones/${zoneId}" \
  -H 'Authorization: Bearer $TOKEN'
```
### List Promotion Campaigns
```bash
curl -s -X GET "$BASE/api/v1/admin/promotions/campaigns" \
  -H 'Authorization: Bearer $TOKEN'
```
### Create Promotion Campaign
```bash
curl -s -X POST "$BASE/api/v1/admin/promotions/campaigns" \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer $TOKEN' \
  -d '{
  "name": "Summer Sale",
  "description": "Summer discount",
  "discountType": "PERCENTAGE",
  "discountValue": 20,
  "minOrderAmount": 200,
  "maxDiscount": 100,
  "startsAt": "2026-06-01T00:00:00",
  "endsAt": "2026-08-31T23:59:59",
  "usageLimit": 1000,
  "applicableRestaurantIds": [
    1
  ]
}'
```
### Update Promotion Campaign
```bash
curl -s -X PUT "$BASE/api/v1/admin/promotions/campaigns/${campaignId}" \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer $TOKEN' \
  -d '{
  "name": "Summer Sale",
  "description": "Summer discount",
  "discountType": "PERCENTAGE",
  "discountValue": 25,
  "minOrderAmount": 200,
  "maxDiscount": 150,
  "startsAt": "2026-06-01T00:00:00",
  "endsAt": "2026-08-31T23:59:59",
  "usageLimit": 1000,
  "applicableRestaurantIds": [
    1
  ]
}'
```
### Deactivate Promotion Campaign
```bash
curl -s -X DELETE "$BASE/api/v1/admin/promotions/campaigns/${campaignId}" \
  -H 'Authorization: Bearer $TOKEN'
```
### List Promo Banners
```bash
curl -s -X GET "$BASE/api/v1/admin/promotions/banners" \
  -H 'Authorization: Bearer $TOKEN'
```
### Create Promo Banner
```bash
curl -s -X POST "$BASE/api/v1/admin/promotions/banners" \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer $TOKEN' \
  -d '{
  "imageUrl": "https://example.com/banner.jpg",
  "targetUrl": "https://example.com",
  "displayOrder": 1,
  "startsAt": "2026-06-01T00:00:00",
  "endsAt": "2026-08-31T23:59:59",
  "isActive": true
}'
```
### Update Promo Banner
```bash
curl -s -X PUT "$BASE/api/v1/admin/promotions/banners/${bannerId}" \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer $TOKEN' \
  -d '{
  "imageUrl": "https://example.com/banner2.jpg",
  "targetUrl": "https://example.com",
  "displayOrder": 1,
  "startsAt": "2026-06-01T00:00:00",
  "endsAt": "2026-08-31T23:59:59",
  "isActive": true
}'
```
### Operations Dashboard
```bash
curl -s -X GET "$BASE/api/v1/admin/operations-dashboard" \
  -H 'Authorization: Bearer $TOKEN'
```
### Trigger Settlement Run
```bash
curl -s -X POST "$BASE/api/v1/admin/settlements/run" \
  -H 'Authorization: Bearer $TOKEN'
```

## 99 - E2E Flow (run in order)
### 1 Login Customer
```bash
curl -s -X POST "$BASE/api/v1/auth/login" \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer $TOKEN' \
  -d '{
  "email": "${customerEmail}",
  "password": "${password}"
}'
```
### 2 Browse Restaurants
```bash
curl -s -X GET "$BASE/api/v1/restaurants/public" \
  -H 'Authorization: Bearer $TOKEN'
```
### 3 Add Address
```bash
curl -s -X POST "$BASE/api/v1/customers/addresses" \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer $TOKEN' \
  -d '{
  "addressLine1": "123 MG Road",
  "city": "Bangalore",
  "state": "Karnataka",
  "pincode": "560001",
  "latitude": 12.9716,
  "longitude": 77.5946,
  "type": "HOME",
  "isDefault": true
}'
```
### 4 Add Cart Item
```bash
curl -s -X POST "$BASE/api/v1/cart/add" \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer $TOKEN' \
  -d '{
  "menuItemId": "${menuItemId}",
  "quantity": 1
}'
```
### 5 Place Order
```bash
curl -s -X POST "$BASE/api/v1/orders/customer/create" \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer $TOKEN' \
  -d '{
  "restaurantId": "${restaurantId}",
  "deliveryAddressId": "${addressId}",
  "paymentMethod": "CASH_ON_DELIVERY"
}'
```
### 6 Track Order
```bash
curl -s -X GET "$BASE/api/v1/orders/customer/track/${orderId}" \
  -H 'Authorization: Bearer $TOKEN'
```
