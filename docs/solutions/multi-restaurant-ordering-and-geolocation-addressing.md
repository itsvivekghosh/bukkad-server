# Solution: Multi-Restaurant Simultaneous Ordering & Precision Geolocation Addressing

**Audience:** Frontend engineers, AI coding agents  
**Source of truth:** Verified against `src/main/java/com/bhukkad/dto/`, `controller/`, and `docs/api/customer-technical-manual.md`  
**Date:** 2026-08-25

---

## Part 1 — Multi-Restaurant Simultaneous Ordering

### 1.1 Problem Statement

A customer should be able to add items from Restaurant A and Restaurant B into a single checkout flow and place both orders simultaneously. The perceived limitation is that the standard order-creation endpoint (`POST /api/v1/orders/customer/create`) accepts only a single `restaurantId`. Placing orders from multiple restaurants requires either:

- Multiple manual checkouts (poor UX), or
- A batch mechanism that processes all restaurants in one shot.

**Backend reality:** The platform already supports multi-restaurant carts and a batch-order endpoint. The cart natively groups items by restaurant, and the batch endpoint consumes that grouped cart to create one order per restaurant. The limitation is not missing backend capability — it is missing frontend orchestration to use it correctly.

### 1.2 Backend Architecture Analysis

#### 1.2.1 Cart as Multi-Restaurant Container

`GET /api/v1/cart` returns `CartResponse`:

```java
public class CartResponse {
    private Long id;
    @Deprecated private Long restaurantId;
    @Deprecated private List<CartItemResponse> items;
    private List<RestaurantCartGroup> restaurantCarts; // primary structure
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

Each `CartItemResponse` carries `id, menuItemId, menuItemName, price, quantity, customizations, totalPrice, specialInstructions`.

The deprecated flat fields (`restaurantId`, `items`) exist for backward compatibility. All new development must use `restaurantCarts`.

#### 1.2.2 Batch Order Creation

`POST /api/v1/orders/customer/create-batch` accepts:

```java
public class BatchOrderRequest {
    @NotNull private Long deliveryAddressId;
    private String specialInstructions;
    private Boolean contactlessDelivery = false;
    @NotNull private String paymentMethod;
    private Double tipAmount;
}
```

**Critical contract detail:** The batch endpoint does NOT accept items or restaurantIds in the request body. It reads the current cart state server-side, splits it by restaurant using the `restaurantCarts` groups, and creates one `Order` per group. This means:

- The cart MUST contain items before the batch endpoint is called.
- All orders in the batch share the same `deliveryAddressId`.
- All orders share the same `paymentMethod` and `tipAmount`.

Response: `BatchOrderResponse`:

```java
public class BatchOrderResponse {
    private List<OrderResponse> orders;
    private int successCount;
    private int failureCount;
    private List<String> errors;
}
```

Partial failure is a first-class outcome. One restaurant failing validation does not roll back the others.

#### 1.2.3 Idempotency & Abuse Defense

Like single order creation, the batch endpoint requires:

- `Idempotency-Key: <UUID v4>` header (required).
- Fraud detection: `fraudDetectionService.checkAndBlock(customerId, ORDER_CREATE)` runs before processing.

#### 1.2.4 Identified Constraints

| Constraint | Impact | Workaround |
|---|---|---|
| Single `deliveryAddressId` for entire batch | Cannot send Restaurant A to Address X and Restaurant B to Address Y in one batch | Place separate single-restaurant orders |
| Items must already be in cart | Cannot pass items inline in the batch request | Ensure cart hydration before checkout |
| Shared `paymentMethod` | All orders use same payment method | Acceptable for most use cases; separate orders for mixed payment |
| `tipAmount` is global | Tip applies to entire batch, not per restaurant | Document this behavior in UI |

### 1.3 Solution Design

#### 1.3.1 Checkout Routing Logic

The checkout flow must branch based on cart composition:

```
Cart screen → "Checkout" tapped
    ├── cart has items from 1 restaurant → Single Checkout
    │       └── POST /api/v1/orders/customer/create
    └── cart has items from N restaurants → Batch Checkout
            └── POST /api/v1/orders/customer/create-batch
```

**Detection rule:** `cart.restaurantCarts.length > 1` triggers batch mode.

#### 1.3.2 Batch Checkout Screen Flow

1. **Confirmation step** — Before placing the batch, show a summary of all participating restaurants with their item count and subtotal. Make it explicit that this creates N separate orders with one delivery fee per restaurant (if applicable) and a single payment.

2. **Address selection** — Single address selector. All orders in the batch ship here. If the user needs different addresses, offer a "Place orders separately" escape hatch that takes them back to the cart so they can checkout one restaurant at a time.

3. **Payment & tip** — Global payment method and tip amount applied to all orders.

4. **Placement** — Generate a single `Idempotency-Key` for the entire batch. Call the batch endpoint.

5. **Result handling** — Inspect `BatchOrderResponse`:
   - `successCount === restaurantCarts.length` and `failureCount === 0` → Full success. Show all order numbers. Navigate to a batch summary screen listing each order with its own tracking link.
   - `failureCount > 0` → Partial failure. Show which restaurants succeeded and which failed, with error messages. Offer retry for failed restaurants (clear failed items from cart, re-add, retry batch or place individually).

#### 1.3.3 Cart State Management

- The cart must be hydrated before checkout. On cart screen mount, call `GET /api/v1/cart` and render `restaurantCarts`.
- If the user adds items from Restaurant B while already having Restaurant A items, the cart automatically groups them (backend handles this on `POST /cart/add`).
- The "Checkout" CTA must be disabled if the cart is empty (`itemCount === 0`).

#### 1.3.4 Partial Failure Recovery

When `BatchOrderResponse.failureCount > 0`:

1. Show a split card UI: ✅ Successful orders (list), ❌ Failed orders (list with error).
2. For failed orders, explain the error (e.g., "Restaurant X is currently unavailable", "Item Y is out of stock").
3. Offer two actions:
   - **Retry failed only:** Remove failed restaurant items from cart, place a new batch with only the remaining restaurants.
   - **Place individually:** Navigate to the failed restaurant's menu, add items, and use single checkout.

### 1.4 Implementation Blueprint

```
Batch Checkout Implementation Prompt:

Build a "Batch Checkout" flow that activates when the cart contains items from more than one restaurant.

Context:
- Backend endpoint: POST /api/v1/orders/customer/create-batch
- Request body: BatchOrderRequest { deliveryAddressId, specialInstructions, contactlessDelivery, paymentMethod, tipAmount }
- Required header: Idempotency-Key: <UUID v4>
- Response: BatchOrderResponse { orders: OrderResponse[], successCount, failureCount, errors: String[] }
- The backend reads cart items server-side; do NOT send items in the request body.
- All orders in a batch share one deliveryAddressId and one paymentMethod.

Required files/components:
1. hooks/useCart.ts — fetches GET /api/v1/cart, returns { restaurantCarts, subtotal, itemCount, isLoading, error, refetch }
2. components/BatchCheckoutSummary.tsx — renders one RestaurantOrderCard per restaurantCarts entry showing restaurantName, itemCount, subtotal, and item list.
3. screens/BatchCheckoutScreen.tsx — orchestrates the flow:
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
      - On 400/409: show ApiResponse.message as a toast.
   g. Include an escape hatch link: "Order restaurants separately" that clears the current cart view and lets user checkout one restaurant at a time.

4. screens/BatchConfirmationScreen.tsx — shows a list of successful orders. Each order card shows orderNumber, restaurantName, totalAmount, status. Provide "Track Order" deep link per order.

5. screens/BatchPartialFailureScreen.tsx — shows two lists (success/failure). Failed entries show the error string. Actions:
   - "Retry Failed" → remove failed restaurants from cart, re-initiate batch checkout.
   - "Place Remaining Individually" → navigate to cart filtered to failed restaurants.

6. Cart screen modifications:
   - In the "Checkout" button handler, inspect restaurantCarts.length.
   - If > 1, navigate to BatchCheckoutScreen.
   - If === 1, navigate to SingleCheckoutScreen (existing flow using POST /api/v1/orders/customer/create).

7. API client update:
   - Ensure the API wrapper sends Idempotency-Key as a header (not body).
   - For batch, generate the UUID at click time, not at component mount, to avoid stale keys.

Edge cases to handle:
- Empty cart: disable Checkout button.
- Cart with 0 items in one restaurant group after mutations: backend may ignore empty groups; verify and clean up UI.
- Network failure during batch placement: show "Network error. Please check your connection and try again." Do NOT silently retry (idempotency key prevents duplicates, but user should confirm).
- Payment method WALLET with insufficient balance: check GET /api/v1/customers/wallet/balance before calling batch endpoint. If insufficient, show warning and offer top-up.
```

---

### 1.5 Workarounds for Unsupported Scenarios

| Scenario | Limitation | Workaround |
|---|---|---|
| Different delivery addresses per restaurant | Batch endpoint accepts single `deliveryAddressId` | Place separate single-restaurant orders. Add a UI hint: "Want different addresses? Order restaurants one at a time." |
| Different payment methods per restaurant | Batch endpoint accepts single `paymentMethod` | Same as above — separate orders. |
| Mixed scheduled + immediate orders | Scheduled orders are a separate list; batch endpoint does not expose `scheduledAt` per restaurant | If scheduling is required for any restaurant, place all orders individually. |
| Coupon per restaurant vs global | `POST /cart/apply-coupon` applies to the whole cart; batch does not accept per-restaurant coupons | Validate coupon at cart level. If user needs per-restaurant coupons, checkout restaurants separately. |

---

## Part 2 — Precision Geolocation Addressing

### 2.1 Problem Statement

A customer's GPS coordinate is useless for delivery without an accurate street address. The backend requires precise address text (`addressLine1`, `addressLine2`, `city`, `state`, `pincode`) plus `latitude` and `longitude` (`@NotNull` on both). The solution must:

1. Acquire the user's current geographic position.
2. Convert coordinates into a human-readable street address.
3. Allow the user to refine and complete the address.
4. Validate serviceability before saving.
5. Store the address with coordinates for future checkout and ETA calculations.

### 2.2 Backend Architecture Analysis

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

`latitude` and `longitude` are `@NotNull`. The backend stores them and uses them for:
- Serviceability zone lookup (`GET /api/v1/serviceability/check`).
- Rider assignment and ETA calculation.
- Nearby restaurant search (`GET /api/v1/restaurants/public/nearby`).

`PUT /api/v1/customers/addresses/{id}/set-default` promotes an address to default.

`GET /api/v1/customers/addresses` returns `AddressResponse` with all fields including `latitude` and `longitude`.

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

This must be called with an addressId (or lat/lng) after the address is saved, to confirm the platform can deliver to that coordinate.

#### 2.2.3 Address Usage in Checkout

When a customer creates an order (`POST /api/v1/orders/customer/create`), they pass `deliveryAddressId`. The backend resolves the address (including its coordinates) to determine the delivery zone and fee.

### 2.3 Solution Design

#### 2.3.1 Geolocation Permission Flow

1. User taps "Add Address" → opens address form.
2. Show a prominent "Use Current Location" button.
3. On tap:
   - Call `navigator.geolocation.getCurrentPosition(success, error, { enableHighAccuracy: true, timeout: 10000, maximumAge: 0 })`.
   - If denied: show a modal explaining why location is needed and offer to open browser settings. Fall back to manual entry.
   - If timeout/error: show "Unable to get precise location. Please enter address manually."

#### 2.3.2 Reverse Geocoding

Once `{ latitude, longitude }` is acquired, convert to a street address using a reverse geocoding service.

**Recommended stack:**
- **Free / open:** OpenStreetMap Nominatim (`https://nominatim.openstreetmap.org/reverse?format=json&lat={lat}&lon={lng}`). Rate-limited; cache results. No API key required.
- **Production / paid:** Google Maps Geocoding API (`https://maps.googleapis.com/maps/api/geocode/json?latlng={lat},{lng}&key=...`). Higher rate limits, better address parsing in India.
- **Alternative:** Mapbox Geocoding API.

Parse the response to extract:
- `addressLine1`: house number + street name (e.g., "42, MG Road").
- `addressLine2`: area or locality if available.
- `city`: city or town.
- `state`: state.
- `pincode`: postal code (critical for Indian delivery; Nominatim often returns it in `address.postcode`).
- `landmark`: if available from geocode, or leave blank for user to fill.

#### 2.3.3 Address Refinement UI

After reverse geocoding populates the form:

1. Show the map with a pin at the exact coordinate. Let the user drag the pin to refine location. On drag end, re-fetch reverse geocode for the new coordinate.
2. Pre-fill all text fields from geocode. Allow user to edit:
   - Add apartment/flat number to `addressLine1` or `addressLine2`.
   - Add building/society name to `addressLine2`.
   - Fill `landmark` (e.g., "Near City Mall").
   - Confirm or correct `pincode` (common source of delivery failure).
3. Show a "Verify on Map" mini-preview so the user can confirm the pin is at their doorstep.

#### 2.3.4 Serviceability Validation

After the user confirms the address text and coordinates:

1. Save the address via `POST /api/v1/customers/addresses` with the complete `AddressRequest` (including `latitude`, `longitude`).
2. On success, call `GET /api/v1/serviceability/check?addressId={newAddressId}` (or lat/lng params if supported).
3. If `serviceable === false`: show "We don't deliver to this exact location yet. Please try a nearby address." Keep the address saved (for future platform expansion) but disable checkout selection.
4. If `serviceable === true`: show "Delivery available! Estimated fee: ₹{estimatedDeliveryFee}. Distance: {distanceKm} km." and mark the address as selectable in checkout.

#### 2.3.5 Default Address Strategy

- If this is the user's first address, auto-set `isDefault = true`.
- If user has existing addresses, show a "Set as default" toggle.
- On checkout, pre-select the default address but allow switching.

### 2.4 Implementation Blueprint

```
Precision Geolocation Address Implementation Prompt:

Build an address capture flow that combines GPS coordinates with editable street address text to ensure delivery precision.

Context:
- Backend requires AddressRequest with latitude (@NotNull) and longitude (@NotNull) for every address.
- Address is used for serviceability check (GET /api/v1/serviceability/check), order delivery, and nearby restaurant search.
- Reverse geocoding converts raw GPS into human-readable street components.

Required files/components:
1. hooks/useGeolocation.ts — wraps navigator.geolocation.getCurrentPosition:
   - Returns { latitude, longitude, error, isLoading }.
   - Options: { enableHighAccuracy: true, timeout: 10000, maximumAge: 0 }.
   - On error: return a typed error (PERMISSION_DENIED, POSITION_UNAVAILABLE, TIMEOUT).

2. services/geocoding.ts — reverse geocode service:
   - Function reverseGeocode(lat, lng): Promise<{ addressLine1, addressLine2, city, state, pincode, landmark }>
   - Default: use OpenStreetMap Nominatim with a 1-second cache (in-memory or localStorage) to avoid rate limits.
   - Parse Nominatim address: address.road + address.house_number → addressLine1; address.suburb → addressLine2; address.city → city; address.state → state; address.postcode → pincode.
   - Production swap: replace with Google Geocoding API or Mapbox by changing one function body.

3. components/AddressForm.tsx — form with fields:
   - "Use Current Location" button at top.
   - On tap: call useGeolocation() → get lat/lng → call reverseGeocode() → pre-fill all fields.
   - Fields: addressLine1, addressLine2, city, state, pincode, landmark, label (HOME/WORK/OTHER), isDefault checkbox.
   - Mini map preview (use a static map image or lightweight map library) showing a pin at {latitude, longitude}.
   - "Pin is incorrect" button: re-opens geolocation or lets user drag pin (if using an interactive map).

4. hooks/useCreateAddress.ts — mutation:
   - Input: AddressRequest fields + latitude + longitude.
   - Calls POST /api/v1/customers/addresses.
   - On success: call GET /api/v1/serviceability/check?addressId={id} to verify deliverability.
   - Returns { address, serviceable, estimatedDeliveryFee, distanceKm }.

5. screens/AddAddressScreen.tsx:
   a. If user tapped "Use Current Location":
      - Show loading state: "Getting your location..."
      - On success: show map pin + pre-filled form.
      - On failure: show fallback message with "Enter manually" option.
   b. Form validation:
      - addressLine1, city, state, pincode: required.
      - latitude, longitude: auto-filled from GPS; if manual entry, show lat/lng input fields (hidden by default, expandable for power users).
   c. On submit:
      - Validate pincode format (6 digits for India).
      - Call useCreateAddress().
      - If serviceable === true: show success toast "Address saved. Delivery available!" Navigate back.
      - If serviceable === false: show warning "We don't deliver here yet." Offer "Save anyway" or "Edit address".
   d. First-address flow: if user has no addresses, after successful creation, auto-set as default and navigate directly to restaurant discovery.

6. Checkout integration (CheckoutScreen.tsx):
   - Address selector pulls from GET /api/v1/customers/addresses.
   - Each address card shows: label, addressLine1, city, pincode, and a small map pin icon.
   - Default address is pre-selected.
   - On address change: call GET /api/v1/serviceability/check?addressId={newId} and update delivery fee + ETA display.
   - "Add new address" CTA opens AddAddressScreen.

7. Error handling:
   - PERMISSION_DENIED: show "Location permission is required for precise delivery. Please enable it in settings." + fallback manual form.
   - TIMEOUT: "Location took too long. Please enter address manually."
   - Geocoding failure: "Could not resolve address from location. Please fill in the details manually." Pre-fill only lat/lng, leave text fields blank.
   - Serviceability failure: "We currently don't deliver to this pincode. Try a nearby area." Do not prevent saving; user may want to save for future use.

8. UX refinements:
   - When GPS is used, auto-detect pincode from reverse geocode and validate it matches the city.
   - Show "Last used" timestamp on address cards.
   - Allow reordering addresses (drag-to-reorder) with the default always at top.
   - If user has GPS coordinates but no pincode, show a warning: "Pincode helps us find nearby restaurants faster."
```

---

## Part 3 — Combined Feature: Smart Checkout from Geolocated Address

### 3.1 Unified Flow

The two solutions combine at checkout:

1. Customer opens app.
2. App detects no saved addresses.
3. "Add Delivery Address" screen opens with "Use Current Location" prominent.
4. Customer taps it → GPS acquired → reverse geocoded → form pre-filled → customer confirms and saves.
5. Serviceability check runs automatically.
6. If serviceable, customer proceeds to browse restaurants.
7. Customer adds items from Restaurant A and Restaurant B to cart.
8. Cart shows 2 restaurant groups. Customer taps "Checkout".
9. App detects multi-restaurant cart → routes to Batch Checkout.
10. Batch Checkout shows both restaurants, single address (from step 4), total.
11. Customer selects payment, adds tip, places batch.
12. Two orders are created simultaneously. Customer sees confirmation with both order numbers.

### 3.2 Edge Case Matrix

| Scenario | Solution |
|---|---|
| GPS denied | Manual address entry with lat/lng fields |
| Reverse geocode returns incomplete address | Pre-fill what we have, highlight blank fields for user |
| Serviceability returns false | Save address anyway, show warning in checkout |
| Cart has 1 restaurant | Route to single checkout (`POST /create`) |
| Cart has N restaurants | Route to batch checkout (`POST /create-batch`) |
| Batch partially fails | Show split success/failure, offer retry or individual placement |
| User wants different addresses per restaurant | Fall back to individual single-restaurant checkouts |

---

## Summary

| Problem | Root Cause | Solution | Key API |
|---|---|---|---|
| Multi-restaurant ordering | Standard `create` endpoint is single-restaurant only | Use cart's native `restaurantCarts` grouping + `POST /orders/customer/create-batch` | `GET /cart`, `POST /orders/customer/create-batch` |
| Single address constraint in batch | `BatchOrderRequest` has one `deliveryAddressId` | Accept constraint for simultaneous orders; fall back to individual orders for per-restaurant addresses | `POST /orders/customer/create` (fallback) |
| GPS coordinates without street text | Backend requires `addressLine1..state + lat/lng` | Reverse geocode GPS → pre-fill form → let user refine → save + verify serviceability | `POST /customers/addresses`, `GET /serviceability/check` |
| Delivery precision | Coordinates alone don't guide riders | Combine lat/lng with structured address + landmark + pincode + map pin confirmation | `AddressRequest` |
