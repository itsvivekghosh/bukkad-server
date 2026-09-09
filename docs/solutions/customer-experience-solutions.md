# Customer Experience Solutions

**Audience:** Frontend engineers, backend engineers, AI coding agents  
**Source of truth:** Verified against `src/main/java/com/bhukkad/**`  
**Date:** 2026-08-25

---

## Table of Contents

1. [Multi-Restaurant Simultaneous Ordering](#1-multi-restaurant-simultaneous-ordering)
2. [Precision Geolocation Addressing](#2-precision-geolocation-addressing)
3. [Phone-First Registration with OTP Verification](#3-phone-first-registration-with-otp-verification)

---

## 1. Multi-Restaurant Simultaneous Ordering

### 1.1 Problem Statement

Customers want to order from multiple restaurants in a single checkout. The standard `POST /api/v1/orders/customer/create` endpoint accepts only one `restaurantId`, creating the perception that simultaneous multi-restaurant ordering is impossible.

### 1.2 Backend Architecture Deep Dive

#### 1.2.1 Multi-Restaurant Cart

The cart natively supports multiple restaurants. `GET /api/v1/cart` returns `CartResponse`:

```java
public class CartResponse {
    private Long id;
    @Deprecated private Long restaurantId;
    @Deprecated private List<CartItemResponse> items;
    private List<RestaurantCartGroup> restaurantCarts; // PRIMARY STRUCTURE
    private Double subtotal;
    private Integer itemCount;
}
```

`RestaurantCartGroup`:

```java
public class RestaurantCartGroup {
    private Long restaurantId;
    private String restaurantName;
    private List<CartItemResponse> items;
    private Double subtotal;
    private Integer itemCount;
}
```

Each `CartItemResponse` contains `id, menuItemId, menuItemName, price, quantity, customizations, totalPrice, specialInstructions`.

When a customer adds items from Restaurant B while Restaurant A items are already in the cart, the backend automatically groups them into separate `RestaurantCartGroup` entries. No frontend orchestration is needed for grouping.

#### 1.2.2 Batch Order Creation

`POST /api/v1/orders/customer/create-batch` is the intended mechanism for simultaneous multi-restaurant orders.

**Request contract** (`BatchOrderRequest`):

```java
public class BatchOrderRequest {
    @NotNull private Long deliveryAddressId;
    private String specialInstructions;
    private Boolean contactlessDelivery = false;
    @NotNull private String paymentMethod;
    private Double tipAmount;
}
```

**Critical design detail:** The batch endpoint does NOT accept items or restaurantIds in the request body. It reads the current cart state server-side, splits it by restaurant using `restaurantCarts`, and creates one `Order` per group. This means:

- Cart MUST be hydrated before calling the batch endpoint.
- All orders share one `deliveryAddressId`.
- All orders share one `paymentMethod` and `tipAmount`.

**Response contract** (`BatchOrderResponse`):

```java
public class BatchOrderResponse {
    private List<OrderResponse> orders;
    private int successCount;
    private int failureCount;
    private List<String> errors;
}
```

Partial failure is first-class. If Restaurant A fails validation (e.g., item out of stock), Restaurant B and C can still succeed.

**Abuse defense:** Like single order creation, the batch endpoint requires:
- `Idempotency-Key: <UUID v4>` header.
- `fraudDetectionService.checkAndBlock(customerId, ORDER_CREATE)` runs before processing.

#### 1.2.3 Identified Constraints

| Constraint | Impact | Workaround |
|---|---|---|
| Single `deliveryAddressId` for entire batch | Cannot send Restaurant A to Address X and Restaurant B to Address Y in one batch | Place separate single-restaurant orders using `POST /orders/customer/create` |
| Items must already be in cart | Cannot pass items inline | Ensure cart hydration before checkout |
| Shared `paymentMethod` | All orders use same payment method | Acceptable for most use cases; separate orders for mixed payment |
| `tipAmount` is global | Tip applies to entire batch, not per restaurant | Document in UI |

### 1.3 Solution: Checkout Routing + Batch Flow

#### 1.3.1 Checkout Routing Logic

The cart screen must branch based on restaurant count:

```
Cart → "Checkout" tapped
    ├── restaurantCarts.length === 1 → Single Checkout
    │       └── POST /api/v1/orders/customer/create
    └── restaurantCarts.length > 1 → Batch Checkout
            └── POST /api/v1/orders/customer/create-batch
```

**Detection rule:** `cart.restaurantCarts.length > 1` triggers batch mode.

#### 1.3.2 Batch Checkout Screen Flow

1. **Confirmation step** — Show all participating restaurants with item count and subtotal. Make explicit: "This creates {N} separate orders. One delivery fee per restaurant. Single payment."

2. **Address selection** — Single address selector. If user needs different addresses, offer an escape hatch: "Order restaurants separately" (returns to cart for individual checkout).

3. **Payment & tip** — Global payment method and tip applied to all orders.

4. **Placement** — Generate a single `Idempotency-Key` for the batch. Call the batch endpoint.

5. **Result handling** — Inspect `BatchOrderResponse`:
   - `failureCount === 0` → Full success. Show all order numbers. Navigate to batch summary.
   - `failureCount > 0` → Partial failure. Show success/failure split with error messages. Offer retry for failed restaurants.

#### 1.3.3 Partial Failure Recovery

When `BatchOrderResponse.failureCount > 0`:

1. Show split card UI: ✅ Successful orders (list), ❌ Failed orders (list with error).
2. For failures, explain the error (e.g., "Restaurant X is currently unavailable", "Item Y is out of stock").
3. Offer actions:
   - **Retry failed only:** Remove failed restaurants from cart, re-initiate batch.
   - **Place individually:** Navigate to failed restaurant's menu for single checkout.

### 1.4 Implementation Blueprint

```
Build a "Batch Checkout" flow that activates when the cart contains items from more than one restaurant.

Context:
- Backend endpoint: POST /api/v1/orders/customer/create-batch
- Request body: BatchOrderRequest { deliveryAddressId, specialInstructions, contactlessDelivery, paymentMethod, tipAmount }
- Required header: Idempotency-Key: <UUID v4>
- Response: BatchOrderResponse { orders: OrderResponse[], successCount, failureCount, errors: String[] }
- Backend reads cart items server-side; do NOT send items in request body.
- All orders in batch share one deliveryAddressId and one paymentMethod.

Required files/components:
1. hooks/useCart.ts — fetches GET /api/v1/cart, returns { restaurantCarts, subtotal, itemCount, isLoading, error, refetch }

2. components/BatchCheckoutSummary.tsx — renders one RestaurantOrderCard per restaurantCarts entry showing restaurantName, itemCount, subtotal, and item list.

3. screens/BatchCheckoutScreen.tsx:
   a. On mount: call useCart(). If restaurantCarts.length <= 1, redirect to SingleCheckoutScreen.
   b. Show BatchCheckoutSummary.
   c. Show address selector (hydrated from GET /api/v1/customers/addresses).
   d. Show payment method selector (CASH_ON_DELIVERY, UPI, WALLET, CARD).
   e. Show tip input.
   f. "Place All Orders" button:
      - Generate UUID v4 for Idempotency-Key.
      - POST /api/v1/orders/customer/create-batch with { deliveryAddressId, specialInstructions, contactlessDelivery, paymentMethod, tipAmount } and header Idempotency-Key.
      - On 200: inspect BatchOrderResponse.
        * If failureCount === 0: navigate to BatchConfirmationScreen passing orders.
        * If failureCount > 0: navigate to BatchPartialFailureScreen passing the response.
      - On 429: show "Too many requests. Please wait.", disable button for Retry-After seconds.
      - On 400/409: show ApiResponse.message as toast.
   g. Escape hatch: "Order restaurants separately" link that returns to cart for individual checkout.

4. screens/BatchConfirmationScreen.tsx — shows successful orders. Each card: orderNumber, restaurantName, totalAmount, status. Provide "Track Order" deep link per order.

5. screens/BatchPartialFailureScreen.tsx — shows success and failure lists. Failed entries show error string. Actions:
   - "Retry Failed" → remove failed restaurants from cart, re-initiate batch checkout.
   - "Place Remaining Individually" → navigate to cart filtered to failed restaurants.

6. Cart screen modification:
   - In "Checkout" handler, inspect restaurantCarts.length.
   - If > 1: navigate to BatchCheckoutScreen.
   - If === 1: navigate to SingleCheckoutScreen (existing flow using POST /orders/customer/create).

7. API client update:
   - Ensure API wrapper sends Idempotency-Key as header (not body).
   - Generate UUID at click time, not at component mount.

Edge cases:
- Empty cart: disable Checkout button.
- Network failure: show "Network error. Please check your connection and try again." Do NOT silently retry (idempotency key prevents duplicates, but user should confirm).
- WALLET with insufficient balance: check GET /api/v1/customers/wallet/balance before calling batch endpoint. If insufficient, show warning and offer top-up.
```

---

## 2. Precision Geolocation Addressing

### 2.1 Problem Statement

A customer's GPS coordinate is useless for delivery without an accurate street address. The backend requires `addressLine1`, `addressLine2`, `city`, `state`, `pincode` plus `latitude` and `longitude` (`@NotNull` on both coordinates). The solution must acquire GPS, convert to street text, allow manual refinement, validate serviceability, and save the address.

### 2.2 Backend Architecture Deep Dive

#### 2.2.1 Address Entity Contract

`POST /api/v1/customers/addresses` accepts `AddressRequest`:

```java
public class AddressRequest {
    @NotBlank private String addressLine1;   // primary street address
    private String addressLine2;             // apartment, floor, building
    @NotBlank private String city;
    @NotBlank private String state;
    @NotBlank private String pincode;
    private String landmark;                 // nearby landmark
    private Address.AddressType type;        // HOME, WORK, OTHER
    private String label;                    // custom label
    @NotNull private Double latitude;        // REQUIRED
    @NotNull private Double longitude;       // REQUIRED
    private Boolean isDefault = false;
}
```

`latitude` and `longitude` are `@NotNull`. Backend uses them for:
- Serviceability zone lookup (`GET /api/v1/serviceability/check`).
- Rider assignment and ETA calculation.
- Nearby restaurant search (`GET /api/v1/restaurants/public/nearby`).

**Address management endpoints:**
- `GET /api/v1/customers/addresses` — list
- `POST /api/v1/customers/addresses` — create
- `PUT /api/v1/customers/addresses/{id}` — update
- `DELETE /api/v1/customers/addresses/{id}` — delete
- `PUT /api/v1/customers/addresses/{id}/set-default` — set default

#### 2.2.2 Serviceability Response

`GET /api/v1/serviceability/check` returns:

```java
public class ServiceabilityResponse {
    private boolean serviceable;
    private Long zoneId;
    private String zoneName;
    private Double estimatedDeliveryFee;
    private Double distanceKm;
    private Double surgeMultiplier;
}
```

Called after address save to confirm deliverability.

#### 2.2.3 Checkout Integration

During order creation, `deliveryAddressId` is passed to `POST /api/v1/orders/customer/create`. The backend resolves coordinates from the address to determine delivery zone and fee.

### 2.3 Solution: GPS-Anchored Address Capture

#### 2.3.1 Geolocation Acquisition

1. User taps "Add Address" → opens address form.
2. Prominent "Use Current Location" button.
3. On tap: call `navigator.geolocation.getCurrentPosition(success, error, { enableHighAccuracy: true, timeout: 10000, maximumAge: 0 })`.
4. If denied: show modal explaining why location is needed, offer manual entry fallback.
5. If timeout: "Unable to get precise location. Please enter address manually."

#### 2.3.2 Reverse Geocoding

Convert `{ latitude, longitude }` to street address components.

**Recommended providers:**
- **OpenStreetMap Nominatim** (free, rate-limited): `https://nominatim.openstreetmap.org/reverse?format=json&lat={lat}&lon={lng}`
- **Google Maps Geocoding API** (production, paid): `https://maps.googleapis.com/maps/api/geocode/json?latlng={lat},{lng}&key=...`

Parse response to extract:
- `addressLine1`: house number + street (e.g., "42, MG Road")
- `addressLine2`: area/locality
- `city`: city or town
- `state`: state
- `pincode`: postal code (critical for Indian delivery)
- `landmark`: if available

#### 2.3.3 Address Refinement

1. Show map with pin at exact coordinate. Allow pin drag. On drag end, re-fetch reverse geocode.
2. Pre-fill all text fields. Allow user to edit:
   - Add apartment/flat to `addressLine1` or `addressLine2`.
   - Add building/society to `addressLine2`.
   - Fill `landmark` (e.g., "Near City Mall").
   - Confirm/correct `pincode`.
3. Show "Verify on Map" mini-preview.

#### 2.3.4 Serviceability Validation

1. Save address via `POST /api/v1/customers/addresses` with complete `AddressRequest`.
2. On success, call `GET /api/v1/serviceability/check?addressId={newAddressId}`.
3. If `serviceable === false`: show "We don't deliver to this exact location yet. Please try a nearby address." Keep address saved but disable checkout selection.
4. If `serviceable === true`: show "Delivery available! Estimated fee: ₹{estimatedDeliveryFee}. Distance: {distanceKm} km."

### 2.4 Implementation Blueprint

```
Precision Geolocation Address Implementation Prompt:

Build an address capture flow that combines GPS coordinates with editable street address text.

Context:
- Backend requires AddressRequest with latitude (@NotNull) and longitude (@NotNull).
- Address is used for serviceability check, order delivery, and nearby restaurant search.
- Reverse geocoding converts raw GPS into human-readable street components.

Required files/components:
1. hooks/useGeolocation.ts — wraps navigator.geolocation.getCurrentPosition:
   - Returns { latitude, longitude, error, isLoading }.
   - Options: { enableHighAccuracy: true, timeout: 10000, maximumAge: 0 }.

2. services/geocoding.ts — reverse geocode service:
   - Function reverseGeocode(lat, lng): Promise<{ addressLine1, addressLine2, city, state, pincode, landmark }>
   - Default: OpenStreetMap Nominatim with 1-second in-memory cache.
   - Parse: address.road + address.house_number → addressLine1; address.suburb → addressLine2; address.city → city; address.state → state; address.postcode → pincode.
   - Production swap: replace with Google Geocoding API or Mapbox by changing one function body.

3. components/AddressForm.tsx:
   - "Use Current Location" button at top.
   - On tap: call useGeolocation() → get lat/lng → call reverseGeocode() → pre-fill all fields.
   - Fields: addressLine1, addressLine2, city, state, pincode, landmark, label (HOME/WORK/OTHER), isDefault checkbox.
   - Mini map preview showing pin at {latitude, longitude}.
   - "Pin is incorrect" button: re-opens geolocation or lets user drag pin.

4. hooks/useCreateAddress.ts — mutation:
   - Input: AddressRequest fields + latitude + longitude.
   - Calls POST /api/v1/customers/addresses.
   - On success: call GET /api/v1/serviceability/check?addressId={id}.
   - Returns { address, serviceable, estimatedDeliveryFee, distanceKm }.

5. screens/AddAddressScreen.tsx:
   a. If "Use Current Location" tapped:
      - Show loading: "Getting your location..."
      - On success: show map pin + pre-filled form.
      - On failure: show fallback with "Enter manually" option.
   b. Form validation: addressLine1, city, state, pincode required. latitude/longitude auto-filled from GPS; if manual, show expandable lat/lng fields.
   c. On submit:
      - Validate pincode format (6 digits for India).
      - Call useCreateAddress().
      - If serviceable: show "Address saved. Delivery available!" Navigate back.
      - If not serviceable: show warning "We don't deliver here yet." Offer "Save anyway" or "Edit address".
   d. First-address flow: if user has no addresses, auto-set as default and navigate to restaurant discovery.

6. Checkout integration:
   - Address selector pulls from GET /api/v1/customers/addresses.
   - Each card shows: label, addressLine1, city, pincode, map pin icon.
   - Default pre-selected.
   - On address change: call GET /api/v1/serviceability/check?addressId={newId}, update delivery fee + ETA.
   - "Add new address" CTA opens AddAddressScreen.

7. Error handling:
   - PERMISSION_DENIED: "Location permission is required for precise delivery. Please enable it in settings." + fallback manual form.
   - TIMEOUT: "Location took too long. Please enter address manually."
   - Geocoding failure: "Could not resolve address from location. Please fill in details manually." Pre-fill only lat/lng.
   - Serviceability failure: "We currently don't deliver to this pincode. Try a nearby area." Do not prevent saving.

8. UX refinements:
   - Auto-detect pincode from reverse geocode and validate it matches city.
   - Show "Last used" timestamp on address cards.
   - Allow reordering addresses with default always at top.
   - If GPS coordinates but no pincode, show warning: "Pincode helps us find nearby restaurants faster."
```

---

## 3. Phone-First Registration with OTP Verification

### 3.1 Problem Statement

The current registration flow (`POST /api/v1/auth/register`) requires `fullName`, `email`, `password`, and `phoneNumber` simultaneously. This creates friction for users who want to start with just a phone number and add email/profile details later.

**Current constraints discovered in codebase:**
1. `User.email` is `@Column(nullable = false, unique = true)` — cannot be null without migration.
2. `User` has no `phoneVerified` field.
3. No phone OTP verification endpoints exist in `AuthController`.
4. Existing OTP infrastructure (`OTPGenerator`, Redis-backed `GuestCheckoutService`, `SmsSender`, `WhatsAppSender`) is scoped to guest checkout only.

### 3.2 Backend Architecture Deep Dive

#### 3.2.1 Current Registration Flow

`AuthServiceImpl.register(RegisterRequest)`:
1. Checks `existsByEmail` and `existsByPhoneNumber`.
2. Creates `Customer`/`RestaurantOwner`/`DeliveryAgent` with email, password (BCrypt/Argon2id), fullName, phoneNumber, role.
3. Issues JWT pair immediately via `issueTokenPair(user)`.
4. Returns `AuthResponse { accessToken, refreshToken, user }`.

There is no intermediate "unverified" state. The user is fully authenticated the moment they register.

#### 3.2.2 Existing OTP Infrastructure

**`GuestCheckoutService`** (production-ready pattern):
- Generates 6-digit OTP via `OTPGenerator.generateOTP()`.
- Stores SHA-256 hash in Redis: `guest:otp:{phone}` with TTL (`Constants.OTP_EXPIRY_MINUTES`).
- Sends via SMS: `notificationService.sendTestNotification("sms", phone, "Your Bhukkad OTP is " + code + "...")`.
- Verification: compares input code's SHA-256 hash against stored hash. On success, consumes OTP and returns a guest token.

**`NotificationService`** (already supports multiple channels):
```java
void sendTestNotification(String channel, String recipient, String message);
// channel: "email", "sms", "whatsapp"
```

**`WhatsAppSender`** (Twilio + Log implementations):
- `TwilioWhatsAppSender` — production via Twilio REST API with circuit breaker.
- `LogWhatsAppSender` — dev fallback.

**`SmsSender`**:
- `TwilioSmsSender` — production.
- `LogSmsSender` — dev fallback.

#### 3.2.3 Current Email Verification

`POST /api/v1/auth/verify-email?email=...` requires `Authorization` header. The token is a JWT. This means a user must already be logged in to verify their email — unusual but the current contract.

### 3.3 Proposed Solution

The solution requires **three backend changes** and **one frontend flow**:

#### 3.3.1 Backend Changes

**A. Database Migration (V2 migration)**

```sql
-- Make email nullable for phone-first registration
ALTER TABLE users ALTER COLUMN email DROP NOT NULL;

-- Add phone verification tracking
ALTER TABLE users ADD COLUMN phone_verified BOOLEAN DEFAULT FALSE;
ALTER TABLE users ADD COLUMN phone_verified_at TIMESTAMP;

-- Add profile completion tracking
ALTER TABLE users ADD COLUMN profile_completed BOOLEAN DEFAULT FALSE;
```

**B. New DTOs**

```java
// Phone-only registration request
public class PhoneRegisterRequest {
    @NotBlank(message = "Phone number is required")
    @Pattern(regexp = "^[0-9]{10}$", message = "Phone number must be 10 digits")
    private String phoneNumber;

    private User.UserRole role; // default CUSTOMER

    private String password; // optional at step 1
}

// OTP verification request
public class OtpVerifyRequest {
    @NotBlank private String phoneNumber;
    @NotBlank private String code;
}

// Profile completion (add email + details later)
public class CompleteProfileRequest {
    @NotBlank @Email private String email;
    @NotBlank @Size(min = 2) private String fullName;
    private String password; // if not set at step 1
}
```

**C. New Endpoints**

| Method | Path | Purpose |
|--------|------|---------|
| POST | `/api/v1/auth/register/phone` | Step 1: Create placeholder account, send OTP |
| POST | `/api/v1/auth/register/phone/resend` | Resend OTP |
| POST | `/api/v1/auth/verify-phone` | Step 2: Verify OTP, issue tokens |
| PUT | `/api/v1/customers/profile/complete` | Step 3: Add email + profile details |
| POST | `/api/v1/auth/verify-email` | *(extend existing)* Verify email with OTP or JWT |

**D. New/Updated Services**

Create `PhoneVerificationService` (extract OTP logic from `GuestCheckoutService` into a reusable component):

```java
@Service
public class PhoneVerificationService {
    // Reuses: OTPGenerator, StringRedisTemplate, NotificationService
    
    public String sendOtp(String phone, String channel) // channel: "sms" | "whatsapp"
    public boolean verifyOtp(String phone, String code)
    public void resendOtp(String phone, String channel)
}
```

Update `AuthService` interface and `AuthServiceImpl`:
- `registerPhone(PhoneRegisterRequest)` — creates user with placeholder email, sends OTP, returns `{ phoneNumber, otpExpiryMinutes }`.
- `verifyPhone(OtpVerifyRequest)` — validates OTP, marks `phoneVerified = true`, issues tokens.
- `completeProfile(Long userId, CompleteProfileRequest)` — updates email, fullName, password, marks `profileCompleted = true`, sends email verification.

**E. Registration State Machine**

```
[Phone Entered] 
    ↓
[OTP Sent via SMS/WhatsApp]
    ↓
[OTP Verified] → Account active, tokens issued, phoneVerified = true
    ↓
[Profile Completion] → Email + fullName + password added, profileCompleted = true
    ↓
[Email Verification] → emailVerified = true (optional but recommended)
```

#### 3.3.2 Frontend Flow

**Step 1: Phone Entry**
- Screen: "Enter your phone number" (10 digits).
- On submit: `POST /api/v1/auth/register/phone { phoneNumber, role: "CUSTOMER" }`.
- Backend: creates user with `email = "phone_{hashedPhone}@temp.bhukkad.local"`, `active = true` (or `active = false` until phone verified — choose based on policy), `phoneVerified = false`. Sends OTP via SMS/WhatsApp.
- Frontend: show OTP entry screen. Show channel selection: "Send via SMS" / "Send via WhatsApp".

**Step 2: OTP Verification**
- Screen: 6-digit OTP input.
- On complete: `POST /api/v1/auth/verify-phone { phoneNumber, code }`.
- Backend: validates OTP hash, marks `phoneVerified = true`, issues JWT pair.
- Frontend: store tokens, navigate to Home. Account is now usable.

**Step 3: Profile Completion (lazy)**
- Show a persistent banner: "Add your email to recover your account" with a "Complete Profile" CTA.
- On tap: open profile completion form.
- `PUT /api/v1/customers/profile/complete { email, fullName, password? }`.
- Backend: updates user, sends email verification via `POST /api/v1/auth/verify-email` (extend to support email-only verification without requiring existing auth, or use a JWT token sent to the new email).
- Frontend: show "Verify your email" interstitial. User can skip and continue using the app.

### 3.4 Implementation Blueprint

```
Phone-First Registration with OTP Verification — Implementation Prompt:

Build a 3-step phone-first registration flow where users sign up with just a phone number, verify via SMS/WhatsApp OTP, and optionally add email/profile details later.

Context:
- Current backend requires email + fullName + password + phoneNumber in a single POST /auth/register call.
- Backend already has OTP generation (OTPGenerator), Redis storage pattern (GuestCheckoutService), SMS sender (SmsSender), WhatsApp sender (WhatsAppSender), and NotificationService.sendTestNotification(channel, recipient, message).
- User entity currently has: email (nullable=false), phoneNumber, emailVerified, fullName, password.
- Missing: phoneVerified field, placeholder email support, OTP endpoints for registration.

Backend changes required (specify to backend team):
1. Database migration:
   - ALTER TABLE users ALTER COLUMN email DROP NOT NULL;
   - ADD COLUMN phone_verified BOOLEAN DEFAULT FALSE;
   - ADD COLUMN phone_verified_at TIMESTAMP;
   - ADD COLUMN profile_completed BOOLEAN DEFAULT FALSE;

2. New DTOs in dto/request/:
   - PhoneRegisterRequest { phoneNumber, role, password? }
   - OtpVerifyRequest { phoneNumber, code }
   - CompleteProfileRequest { email, fullName, password? }

3. New endpoints in AuthController:
   - POST /api/v1/auth/register/phone
     * Body: PhoneRegisterRequest
     * Creates User with placeholder email (e.g., "phone_{SHA256(phoneNumber)}@temp.bhukkad.local"), password if provided, role, active=true, phoneVerified=false.
     * Calls PhoneVerificationService.sendOtp(phone, channel).
     * Returns 200: { phoneNumber, otpExpiryMinutes, message: "OTP sent" }.
     * Rate limit: auth-register.
   
   - POST /api/v1/auth/register/phone/resend
     * Body: { phoneNumber, channel: "sms" | "whatsapp" }
     * Rate limit: auth-register.
   
   - POST /api/v1/auth/verify-phone
     * Body: OtpVerifyRequest { phoneNumber, code }
     * Validates OTP via PhoneVerificationService.verifyOtp(phone, code).
     * Sets phoneVerified = true, phoneVerifiedAt = now.
     * Issues JWT pair via issueTokenPair(user).
     * Returns AuthResponse.
   
   - PUT /api/v1/customers/profile/complete (extend CustomerController or add to AuthController)
     * Requires auth.
     * Body: CompleteProfileRequest { email, fullName, password? }
     * Updates user: setEmail, setFullName, setPassword (if provided), setProfileCompleted(true).
     * Checks email uniqueness.
     * Triggers email verification flow (send verification email with JWT token).
     * Returns updated CustomerProfileResponse.

4. New service: PhoneVerificationService (extract OTP logic from GuestCheckoutService):
   - sendOtp(phone, channel): generates OTP, stores SHA-256 hash in Redis key phone:otp:{phone} with TTL, sends via notificationService.sendTestNotification(channel, phone, "Your Bhukkad OTP is {code}..."), returns "sent".
   - verifyOtp(phone, code): retrieves hash from Redis, compares with hashOtp(code), deletes key on success, returns true/false.
   - resendOtp(phone, channel): invalidates previous OTP (delete key), calls sendOtp.
   - hashOtp(code): SHA-256 hex digest (static method, reuse from GuestCheckoutService).

5. Extend AuthService interface:
   - AuthResponse registerPhone(PhoneRegisterRequest request);
   - AuthResponse verifyPhone(OtpVerifyRequest request);
   - void resendPhoneOtp(String phone, String channel);
   - CustomerProfileResponse completeProfile(Long userId, CompleteProfileRequest request);

6. Update AuthServiceImpl:
   - registerPhone: create user with placeholder email, no tokens, send OTP.
   - verifyPhone: validate OTP, mark phoneVerified, issue tokens.
   - completeProfile: update fields, trigger email verification.

Frontend implementation:
1. screens/PhoneRegisterScreen.tsx:
   - Form: phoneNumber input (10 digits, auto-format), role selector (default CUSTOMER, hidden on customer app).
   - Channel selector: "Send OTP via SMS" / "Send OTP via WhatsApp".
   - On submit: POST /api/v1/auth/register/phone { phoneNumber, role }.
   - On success: navigate to OtpVerificationScreen with phoneNumber and selected channel.
   - Show "Already have an account? Login" link.

2. screens/OtpVerificationScreen.tsx:
   - Show masked phone: "****XX{last 2 digits}".
   - 6-digit OTP input with auto-focus.
   - "Resend OTP" link (60s cooldown timer).
   - On complete: POST /api/v1/auth/verify-phone { phoneNumber, code }.
   - On success: store AuthResponse tokens, navigate to Home.
   - On failure: "Invalid or expired OTP. Please try again."
   - If backend returns 404: "No registration found for this number. Please sign up again."

3. components/ProfileCompletionBanner.tsx:
   - Persistent banner on Home/Profile: "Add your email to secure your account."
   - On tap: open CompleteProfileScreen.
   - Dismissible but re-shows after 3 days.

4. screens/CompleteProfileScreen.tsx:
   - Form: email, fullName, optional password (if not set at step 1), confirmPassword.
   - On submit: PUT /api/v1/customers/profile/complete with auth header.
   - On success: show "Profile updated! We sent a verification email to {email}."
   - Trigger email verification flow: POST /api/v1/auth/verify-email?email=... (backend should send verification email).
   - User can skip this step and continue using the app.

5. Auth state management:
   - Store phoneNumber in auth state for OTP resend.
   - Track registration step: 'PHONE_ENTERED' | 'PHONE_VERIFIED' | 'PROFILE_COMPLETE'.
   - On app launch: if tokens exist and profileCompleted === false, show ProfileCompletionBanner.

6. Error handling:
   - Phone already exists: "This phone number is already registered. Login instead?" with link to Login.
   - OTP expired: "OTP expired. Please resend."
   - Max attempts exceeded: "Too many failed attempts. Please request a new OTP."
   - Email already exists during profile completion: "This email is already linked to another account."

7. WhatsApp channel specifics:
   - WhatsApp OTP uses TwilioWhatsAppSender. Message format: "Your Bhukkad verification code is {code}. Valid for {minutes} minutes. Do not share this code."
   - WhatsApp has higher delivery cost but better open rates in India. Offer as default with SMS fallback.
```

---

## Summary

| Problem | Root Cause | Solution | Key APIs |
|---|---|---|---|
| Multi-restaurant ordering | Frontend unaware of batch endpoint + cart grouping | Detect `restaurantCarts.length > 1`, route to batch checkout with `POST /orders/customer/create-batch` | `GET /cart`, `POST /orders/customer/create-batch` |
| Different addresses per restaurant | Batch endpoint accepts single `deliveryAddressId` | Accept as known constraint; fall back to individual single-restaurant orders for mixed-address scenarios | `POST /orders/customer/create` (fallback) |
| GPS without street precision | Backend requires structured address + lat/lng | Reverse geocode GPS → pre-fill form → manual refinement → save + verify serviceability | `POST /customers/addresses`, `GET /serviceability/check` |
| Phone-first registration | Current `RegisterRequest` mandates email + fullName + password simultaneously; `User.email` is NOT NULL; no phone OTP endpoints | DB migration (email nullable + phone_verified column) + new phone OTP endpoints + reuse existing `OTPGenerator`/`GuestCheckoutService` Redis pattern + `SmsSender`/`WhatsAppSender` | New: `/auth/register/phone`, `/auth/verify-phone`, `/auth/register/phone/resend`, `PUT /customers/profile/complete` |
| Email-later profile completion | No endpoint to update email/profile on existing account | New `PUT /customers/profile/complete` endpoint + extend email verification to support post-registration flow | `PUT /customers/profile/complete`, `POST /auth/verify-email` |
