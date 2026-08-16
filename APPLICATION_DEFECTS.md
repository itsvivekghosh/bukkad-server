# Application Defects - Status Report

## Test Results Summary
- **Passed:** 228
- **Failed:** 0
- **Skipped:** 13 (optional tests)
- **Total:** 241

## Resolved Defects

### Defect 1: Export Orders - Content Negotiation Failure ✅ RESOLVED
- **Status:** Fixed
- **Fix:** Controller endpoint updated to return raw CSV data instead of `ApiResponse` wrapper

### Defect 2: Update Profile - Lazy Initialization Exception ✅ RESOLVED
- **Status:** Fixed
- **Fix:** Added `@Transactional` to `CustomerServiceImpl.updateProfile()` and proper DTO serialization handling

### Defect 3: Cancel Scheduled Order - Unexpected Server Error ✅ RESOLVED
- **Status:** Fixed
- **Fix:** Added proper error handling in scheduled order cancellation flow

### Defect 4: Apply Coupon to Cart - Unexpected Server Error ✅ RESOLVED
- **Status:** Fixed
- **Fix:** Added null checks and division-by-zero protection in `CouponServiceImpl.calculateDiscount()`

### Defect 5: Toggle Menu Item Availability - Unexpected Server Error ✅ RESOLVED
- **Status:** Fixed
- **Fix:** Added proper error handling and cache invalidation in menu item availability toggle

### Defect 6: Kitchen SSE Stream - Server Error ✅ RESOLVED
- **Status:** Fixed
- **Fix:** Fixed SSE timeout handling and graceful shutdown in `OrderSseStreamService`
- **Test Fix:** Updated test script to handle `SocketTimeoutError` for SSE streams

### Defect 7: Foreign Key Constraint on Delete Operations ✅ RESOLVED
- **Status:** Fixed
- **Fix:** Updated delete operations in `CustomerServiceImpl`, `MenuServiceImpl` to handle FK constraints gracefully

### Defect 8: Razorpay Webhook - Processing Failure ✅ RESOLVED
- **Status:** Fixed
- **Fix:** Added exception handling and proper validation in `PaymentWebhookController`

## Test Script Issues (Not Application Defects)

### Issue 1: Missing Required Fields in Test Payloads ✅ FIXED
- **Update Menu Item**: Added `isVeg`, `foodType`, `categoryId` to body template
- **Create Support Ticket**: Added `category` to body template
- **Create Promotion Campaign**: Changed `campaign_type` → `campaignType`
- **Purchase Gift Card**: Added `recipientName`, `expiresAt` to body template
- **Create Pricing Rule**: Added `name`, `startTime`, `endTime`, `type` to body template

### Issue 2: Feature Flags / Configuration
- **Image Uploads**: Returns 400 "Image uploads are not enabled" - Expected when feature flag is disabled
- **Online Wallet Top-Up**: Returns 400 "requires Razorpay to be enabled" - Expected when payment gateway is disabled

### Issue 3: Expected Business Rule Violations
- **Cancel Order**: Returns 400 "Order cannot be cancelled in its current status" - Correct business rule enforcement
- **Accept Delivery**: Returns 400 "Agent account is not verified" - Correct security check
- **Update Delivery Location**: Returns 400 "No delivery agent assigned to this order" - Correct state validation
- **Create Delivery Batch**: Returns 400 "No active orders available for batching" - Correct pre-condition check

### Issue 4: Authorization Failures
- **Update Coupon, Delete Coupon, Happy Hour Pricing**: Returns 403 - These are owner-only endpoints, correctly rejecting customer tokens

### Issue 5: Empty State / Not Found
- **Remove Cart Item, Active Batches, Get Review by Order, Respond to Review**: Returns 404 - Correct for non-existent resources
