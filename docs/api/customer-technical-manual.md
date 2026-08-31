# Bhukkad Customer-Facing Application — Technical Manual

**Version:** 1.0  
**Last updated:** 2026-08-25  
**Audience:** Frontend engineers, AI coding agents, technical stakeholders  
**Source of truth:** Verified against `src/main/java/com/bhukkad/**` controllers, DTOs, and `docs/bhukkad-api-consolidated-reference.md`

---

## Table of Contents

1. [Platform Context](#1-platform-context)
2. [Module M1 — Authentication & Session](#2-module-m1--authentication--session)
   - 2.1 Registration & Role Selection
   - 2.2 Login & JWT Issuance
   - 2.3 MFA Verification
   - 2.4 Password Management
   - 2.5 Token Refresh & Logout
3. [Module M2 — User Profile & Preferences](#3-module-m2--user-profile--preferences)
   - 3.1 Profile Management
   - 3.2 Address Book
   - 3.3 Notification Preferences & Push Registration
   - 3.4 GDPR Compliance & Data Portability
4. [Module M3 — Discovery & Browsing](#4-module-m3--discovery--browsing)
   - 4.1 Home Feed
   - 4.2 Search & Suggestions
   - 4.3 Restaurant Discovery & Filtering
   - 4.4 Menu Browsing
   - 4.5 Reviews & Ratings (Public Read)
   - 4.6 Serviceability Check
5. [Module M4 — Cart & Checkout](#5-module-m4--cart--checkout)
   - 5.1 Multi-Restaurant Cart
   - 5.2 Coupon Application
   - 5.3 Single-Restaurant Order Creation
   - 5.4 Multi-Restaurant Batch Checkout
   - 5.5 Payment Method Selection & Status
6. [Module M5 — Order Lifecycle & Tracking](#6-module-m5--order-lifecycle--tracking)
   - 6.1 Order History & Reorder
   - 6.2 Live Order Tracking (SSE)
   - 6.3 Guest Tracking (Shareable Link)
   - 6.4 Order Detail, Timeline & Invoices
   - 6.5 Scheduled Orders
   - 6.6 Order CSV Export
7. [Module M6 — Payments & Wallet](#7-module-m6--payments--wallet)
   - 7.1 Wallet Overview & Transactions
   - 7.2 Wallet Top-Up via Gateway
   - 7.3 Gift Cards
8. [Module M7 — Engagement & Loyalty](#8-module-m7--engagement--loyalty)
   - 8.1 Loyalty Points
   - 8.2 Referral Program
   - 8.3 Favorites
   - 8.4 Membership & Subscriptions
   - 8.5 Group Orders
   - 8.6 Recommendations & "Surprise Me"
9. [Module M8 — Feedback & Support](#9-module-m8--feedback--support)
   - 9.1 Post-Delivery Reviews & Ratings
   - 9.2 Survey Submission
   - 9.3 Support Tickets
   - 9.4 Disputes
10. [Cross-Cutting Concerns](#10-cross-cutting-concerns)
11. [Quick-Reference: Endpoint Inventory by Feature](#11-quick-reference-endpoint-inventory-by-feature)

---

## 1. Platform Context

Bhukkad is a multi-role food delivery platform. The customer-facing application serves the `CUSTOMER` role and is the primary revenue-generating surface. It operates over a versioned REST API (`/api/v1`) with an optional GraphQL surface (`/graphql`) for optimized reads. All endpoints return a standard `ApiResponse<T>` envelope and support JWT Bearer authentication with role-based authorization.

**Roles:** `CUSTOMER`, `RESTAURANT_OWNER`, `DELIVERY_AGENT`, `ADMIN`  
**Auth:** JWT access token (24h) + refresh token (7d)  
**Real-time:** Server-Sent Events (SSE) for live order tracking, kitchen queue, and rider stream  
**Pagination:** Offset-based (`?page=&size=`) and cursor-based (`/cursor?cursor=&size=`)  
**Idempotency:** `Idempotency-Key` header required for all order-creation mutations  

---

## 2. Module M1 — Authentication & Session

### 2.1 Registration & Role Selection

**Functional Overview**  
Solves the onboarding problem: new users must create an account with a verified email and phone, choose their platform role, and agree to the platform's terms before accessing any protected feature.

**Technical Deep Dive**  
- **Endpoint:** `POST /api/v1/auth/register`
- **Request:** `RegisterRequest { fullName, email, password, phoneNumber, role }`
- **Abuse defence:** Two independent layers run *before* credential verification:
  1. `@RateLimited("auth-register")` — caps per-account hammering.
  2. `FraudDetectionService.checkAndBlock(null, AUTH_REGISTER)` — attributes by source IP and device fingerprint, catching bulk signup abuse where each email is distinct.
- **Response:** `AuthResponse { accessToken, refreshToken, user }`
- **State change:** Creates a `User` row with `emailVerified = false`. A verification email dispatch is triggered asynchronously.
- **Downstream effect:** Successful registration immediately issues a JWT pair, allowing the client to skip the login step and proceed directly to the home screen.

**Implementation Blueprint**  
```
Build a Customer Registration screen in React Native (or Next.js web) with:

1. Form fields: fullName, email, password, confirmPassword, phoneNumber (E.164), role selector (CUSTOMER only; others hidden on customer app).
2. On submit:
   - POST /api/v1/auth/register with validated body.
   - If 200: persist AuthResponse.accessToken in secure storage, persist refreshToken, navigate to Home.
   - If 429 (rate limit): show "Too many attempts. Please try again in {Retry-After} seconds."
   - If 400 (validation): display field-level errors from ApiResponse.data.fieldErrors.
3. Immediately after registration, fire-and-forget POST /api/v1/auth/verify-email?email=... (no UI required; silent).
4. Store the user's role in auth state. Gate protected routes with a CustomerShell that checks role === 'CUSTOMER'.
5. For web: use httpOnly refresh-token cookie; for mobile: store tokens in Keychain/Keystore.
```

---

### 2.2 Login & JWT Issuance

**Functional Overview**  
Allows returning users to authenticate with email/password and receive a short-lived access token plus a long-lived refresh token.

**Technical Deep Dive**  
- **Endpoint:** `POST /api/v1/auth/login`
- **Request:** `LoginRequest { email, password }`
- **Abuse defence:** Same dual-layer defence as registration (`@RateLimited("auth-login")` + `FraudDetectionService.checkAndBlock(null, AUTH_LOGIN)`). Crucially, fraud detection counts *every* attempt regardless of outcome, so a successful login from a burst source is itself a signal.
- **Response:** `AuthResponse { accessToken, refreshToken, user }`
- **MFA flow:** If the account has TOTP enabled, the response returns `mfaToken` (not a full access token). The client must then call `POST /api/v1/auth/mfa/verify?mfaToken=...&code=123456` to complete authentication.
- **State:** BCrypt comparison happens inside `AuthService.login`; passwords are never returned or logged.

**Implementation Blueprint**  
```
Build a Login screen with:

1. Fields: email, password, "Remember me" toggle (controls whether you persist refresh token).
2. On submit:
   - POST /api/v1/auth/login.
   - Success: store tokens, navigate to Home.
   - Response shape: if data.mfaToken is present, navigate to MfaScreen instead.
3. MfaScreen: 6-digit TOTP input, auto-focus on next field. On complete:
   - POST /api/v1/auth/mfa/verify?mfaToken={mfaToken from login response}&code={otp}.
   - Store resulting accessToken/refreshToken, navigate to Home.
4. Error handling:
   - 401: "Invalid email or password" (do not reveal which field was wrong).
   - 429: rate-limited, show retry timer.
5. On app cold-start: check if refresh token exists. If so, call POST /api/v1/auth/refresh-token with { refreshToken } body to restore session silently.
```

---

### 2.3 MFA Verification

*(See 2.2 for the primary flow. This section covers the explicit verify endpoint when triggered out-of-band.)*

**Functional Overview**  
Completes multi-factor authentication for privileged accounts (e.g., restaurant owners, delivery agents) when the initial login response indicates MFA is required.

**Technical Deep Dive**  
- **Endpoint:** `POST /api/v1/auth/mfa/verify`
- **Params:** `mfaToken` (query param from login response), `code` (TOTP 6-digit code).
- **Response:** Full `AuthResponse` with `accessToken` and `refreshToken` only on successful verification.

**Implementation Blueprint**  
```
The MFA flow is embedded in Login (see 2.2). If you need a standalone "verify MFA" screen (e.g., returning user whose session expired but MFA was previously enabled):

1. User enters email+password → POST /auth/login → receives { mfaToken }.
2. Show TOTP input, call POST /api/v1/auth/mfa/verify?mfaToken={mfaToken}&code={otp}.
3. On success: store tokens, navigate to Home.
4. On failure: "Invalid code. Please try again." Do not reveal whether the TOTP was malformed vs. expired.
5. Provide a "Use backup code" fallback only if your backend supports it (not in current controller — verify before adding UI).
```

---

### 2.4 Password Management

**Functional Overview**  
Covers forgot-password (out-of-band reset), reset-password (token-based), and change-password (authenticated, requires old password).

**Technical Deep Dive**  
- **Forgot password:** `POST /api/v1/auth/forgot-password?email=...` — always returns 200 to prevent email enumeration. Backend sends a time-limited reset link.
- **Reset password:** `POST /api/v1/auth/reset-password?token=...&newPassword=...` — the `token` comes from the email link.
- **Change password:** `POST /api/v1/auth/change-password` — requires `Authorization: Bearer <accessToken>`; body params `oldPassword`, `newPassword`.

**Implementation Blueprint**  
```
Forgot-Password screen:
1. User enters email. Call POST /api/v1/auth/forgot-password?email=... (query param, no body).
2. Always show "If an account exists, a reset link has been sent." regardless of outcome.
3. Navigate to a "Check your email" interstitial.

Reset-Password screen (deep-linked from email):
1. Extract token from URL query params (?token=...).
2. Form: newPassword, confirmPassword.
3. On submit: POST /api/v1/auth/reset-password?token={urlToken}&newPassword={pw}.
4. On success: navigate to Login with a success toast.

Change-Password (Settings screen):
1. Fields: currentPassword, newPassword, confirmNewPassword.
2. On submit: POST /api/v1/auth/change-password with Authorization header + body params oldPassword + newPassword.
3. On success: show toast, clear form.
4. On failure: "Current password is incorrect" (generic; do not reveal if new password failed policy).
```

---

### 2.5 Token Refresh & Logout

**Functional Overview**  
Keeps sessions alive without re-authenticating and allows explicit session termination.

**Technical Deep Dive**  
- **Refresh:** `POST /api/v1/auth/refresh-token` — body `{ refreshToken }`; returns new `AuthResponse`.
- **Logout:** `POST /api/v1/auth/logout` — requires `Authorization: Bearer <accessToken>`; invalidates the current refresh token server-side.

**Implementation Blueprint**  
```
Auth interceptor / Axios wrapper:
1. Attach Authorization: Bearer {accessToken} to every request.
2. On 401 Unauthorized:
   - If a refresh token is stored, call POST /api/v1/auth/refresh-token with { refreshToken }.
   - On refresh success: update stored tokens, retry original request once.
   - On refresh failure (401/403 from refresh endpoint): clear tokens, redirect to Login.
   - Use single-flight: prevent concurrent refresh calls (queue the original request).
3. On explicit logout:
   - Call POST /api/v1/auth/logout with current accessToken.
   - Clear secure storage (accessToken, refreshToken, user).
   - Navigate to Login.
4. On app background/foreground transitions: if token is near expiry, proactively refresh.
```

---

## 3. Module M2 — User Profile & Preferences

### 3.1 Profile Management

**Functional Overview**  
Allows customers to view and edit their personal information (name, phone, avatar metadata) and to permanently delete their account.

**Technical Deep Dive**  
- **Get profile:** `GET /api/v1/customers/profile` — returns the authenticated customer's profile.
- **Get by ID:** `GET /api/v1/customers/profile/{profileId}` — allows fetching another user's public profile (used for owner/agent public views; customer app rarely needs this).
- **Update:** `PUT /api/v1/customers/profile` — partial update; body contains fields to change.
- **Delete account:** `DELETE /api/v1/customers/account` — triggers GDPR erasure workflow (see Module M8 §9.4); returns 200 on request acceptance, not on completion (erasure is asynchronous).

**Implementation Blueprint**  
```
Profile screen:
1. On mount: GET /api/v1/customers/profile, populate form fields.
2. Edit mode: allow changing fullName, phoneNumber. On save:
   - PUT /api/v1/customers/profile with changed fields only (do not send unchanged fields; backend treats body as partial).
   - On 200: update local user state, show "Profile updated".
3. Account deletion (danger zone):
   - Show a confirmation modal: "This will permanently delete your account and all associated data. This action cannot be undone."
   - On confirm: DELETE /api/v1/customers/account.
   - On success: clear all local state, navigate to Welcome/Login with a "Your account has been deleted" message.
   - Do not allow a "undo" — erasure is irreversible.
```

---

### 3.2 Address Book

**Functional Overview**  
Customers maintain a list of delivery addresses (home, work, etc.) and can set a default. The address book is the primary input for checkout.

**Technical Deep Dive**  
- **List:** `GET /api/v1/customers/addresses`
- **Create:** `POST /api/v1/customers/addresses`
- **Update:** `PUT /api/v1/customers/addresses/{addressId}`
- **Delete:** `DELETE /api/v1/customers/addresses/{addressId}`
- **Set default:** `PUT /api/v1/customers/addresses/{addressId}/set-default`

All endpoints require `CUSTOMER` role. Setting a new default automatically unsets the previous default.

**Implementation Blueprint**  
```
Address Book screen (AddressList):
1. On mount: GET /api/v1/customers/addresses, render list.
2. Each address card shows: label (Home/Work/Custom), full address, default badge, edit/delete actions.
3. Add Address:
   - Form with: label, address line 1, line 2, city, pincode, latitude (optional), longitude (optional).
   - Optional: "Use current location" button that calls browser Geolocation API, then reverse-geocodes (or uses a geocoding service) to pre-fill lat/lng.
   - POST /api/v1/customers/addresses on submit.
4. Edit Address: pre-fill form, PUT /api/v1/customers/addresses/{id}.
5. Set Default: PUT /api/v1/customers/addresses/{id}/set-default. Refresh list.
6. Delete: confirmation dialog, DELETE /api/v1/customers/addresses/{id}.
7. In Checkout: show a condensed address selector pre-populated with the default address, with an "Add new" inline action.
```

---

### 3.3 Notification Preferences & Push Registration

**Functional Overview**  
Lets customers control which notification channels (push, SMS, email) they receive, and registers device tokens for push delivery.

**Technical Deep Dive**  
- **Preferences:** `GET /api/v1/customers/notification-preferences` and `PUT /api/v1/customers/notification-preferences`
- **Device tokens:** `POST /api/v1/customers/device-tokens` (register), `DELETE /api/v1/customers/device-tokens` (unregister)

**Implementation Blueprint**  
```
Notifications Settings screen:
1. On mount: GET /api/v1/customers/notification-preferences, render toggles for each channel (order updates, promotions, surveys, etc.).
2. On toggle change: PUT /api/v1/customers/notification-preferences with updated preferences object.
3. Push registration (app lifecycle, not settings):
   - On first launch after login, request browser notification permission (or mobile APNs/FCM permission).
   - On permission grant: POST /api/v1/customers/device-tokens with { token, platform: 'WEB'|'IOS'|'ANDROID' }.
   - On permission revoke or logout: DELETE /api/v1/customers/device-tokens.
   - On token refresh (FCM): re-register silently.
4. If PUT /notification-preferences returns 400 with field errors, surface the specific channel error.
```

---

### 3.4 GDPR Compliance & Data Portability

**Functional Overview**  
Provides customer-facing GDPR controls: view and update data-processing consents, request a full personal data export, and request account/data erasure.

**Technical Deep Dive**  
- **Consents:** `GET /api/v1/compliance/consents` (list), `PUT /api/v1/compliance/consents/{purpose}` (update one purpose)
- **Data export:** `POST /api/v1/compliance/export` (initiate), `GET /api/v1/compliance/export` (retrieve/download)
- **Erasure:** `POST /api/v1/compliance/users/{id}/erase` — triggers async right-to-be-forgotten workflow.

**Implementation Blueprint**  
```
Privacy & Data screen:
1. On mount: GET /api/v1/compliance/consents. Render a list of purposes (marketing, analytics, etc.) with toggle switches.
2. On toggle change: PUT /api/v1/compliance/consents/{purpose} with { accepted: boolean }.
3. Data Export:
   - Button "Download my data". On click: POST /api/v1/compliance/export.
   - Show "Your export is being prepared. You will be notified when ready." (backend typically sends email; UI can poll GET /api/v1/compliance/export to check status if a job ID is returned).
   - When ready: trigger browser download of the returned file/blob.
4. Delete My Data:
   - Show a destructive confirmation: "This will permanently delete your account, orders, and personal data. You will receive a confirmation email."
   - On confirm: POST /api/v1/compliance/users/{id}/erase.
   - After success: clear all local state, navigate to Login with a "Deletion request submitted. You will receive a confirmation email." message.
5. Do NOT implement a "undo delete" flow — erasure is irreversible per GDPR.
```

---

## 4. Module M3 — Discovery & Browsing

### 4.1 Home Feed

**Functional Overview**  
The primary landing experience after login (or for unauthenticated users via `/mobile/feed`). Surfaces promotional banners, active campaigns, membership plans, and trending dishes in a scrollable feed.

**Technical Deep Dive**  
- **REST endpoints:**
  - `GET /api/v1/home/banners` — active promo banners
  - `GET /api/v1/home/campaigns` — active promotion campaigns
  - `GET /api/v1/home/feed` — combined feed (banners + campaigns + plans + trending)
  - `GET /api/v1/home/membership-plans` — available membership tiers
  - `GET /api/v1/home/trending` — trending dishes (global or localized)
- **GraphQL:** `POST /graphql` with query `{ homeFeed { banners { ... } campaigns { ... } membershipPlans { ... } } }` — single round-trip with field selection.
- **Mobile-specific:** `GET /api/v1/mobile/feed` — analogous to home feed but versioned under `/mobile` for future mobile-only tailoring.
- **Caching:** `HomeFeedCacheService` wraps each section independently; a cache miss on one section does not invalidate others.

**Implementation Blueprint**  
```
Home screen (React / Next.js / React Native):
1. On mount (or on app foreground):
   - Preferred: POST /graphql with query { homeFeed { banners { id title subtitle imageUrl actionUrl } campaigns { id name description imageUrl } membershipPlans { id name price benefits } } }.
   - Fallback (if GraphQL not available): fire parallel GET /home/banners, /home/campaigns, /home/membership-plans, /home/trending. Use Promise.allSettled so one failing section does not block the others.
2. Render:
   - Horizontal scrolling banner carousel (auto-advance every 4s, swipeable, dots indicator).
   - "Active Campaigns" horizontal list (card per campaign with image + title).
   - "Trending Near You" horizontal list of dish cards.
   - "Membership Plans" section (horizontal or stacked cards with price + CTA).
3. Cache:
   - Cache the feed for 60s in memory (React Query / SWR / TanStack Query with staleTime: 60_000).
   - Show skeletons on first load; show cached data instantly on subsequent visits.
4. Error handling: if any section fails, show an empty state ("No banners right now") and a retry button. Do not fail the whole screen.
5. For web: GraphQL endpoint is typically POST /graphql; for mobile, use the REST /home/feed as fallback.
```

---

### 4.2 Search & Suggestions

**Functional Overview**  
Provides type-ahead search suggestions and full-text search across restaurants and menu items as the user types.

**Technical Deep Dive**  
- **Suggestions:** `GET /api/v1/search/suggest` — returns suggestions as the user types (debounced client-side).
- **Restaurant search:** `GET /api/v1/restaurants/public/search?keyword=...`
- **Menu item search:** `GET /api/v1/menu/items/search?keyword=...`

**Implementation Blueprint**  
```
Search experience:
1. Search bar at top of Home/Discover screen with debounce of 250ms.
2. On input change (after debounce):
   - Call GET /api/v1/search/suggest?keyword={query}.
   - Render a dropdown with categorized results: Restaurants (name + cuisine), Menu Items (name + price + restaurant).
   - Keyboard navigation (arrow keys + enter).
3. On submit or "See all results":
   - Navigate to a full Search Results screen.
   - If source was restaurant search: GET /api/v1/restaurants/public/search?keyword={query}.
   - If source was menu search: GET /api/v1/menu/items/search?keyword={query}.
4. Combine results if both endpoints are needed.
5. Persist recent searches in localStorage (last 5 queries). Show them when the search bar is focused and empty.
6. If API returns empty: show "No results found for '{query}'" with a "Clear search" CTA.
```

---

### 4.3 Restaurant Discovery & Filtering

**Functional Overview**  
Lets users browse all restaurants, find nearby options by geo-coordinates, and filter by cuisine, rating, veg preference, etc.

**Technical Deep Dive**  
- **List all:** `GET /api/v1/restaurants/public` — paginated, read-replica.
- **Detail:** `GET /api/v1/restaurants/public/{id}`
- **Search:** `GET /api/v1/restaurants/public/search?keyword=...`
- **Nearby:** `GET /api/v1/restaurants/public/nearby?lat=...&lng=...&radiusKm=...`
- **Filter:** `GET /api/v1/restaurants/public/filter?cuisineIds=1,2&minRating=4&isVegOnly=true`
- **Cuisines:** `GET /api/v1/cuisines/{id}` (detail), and implicitly a cuisines list is needed for filter chips.

**Implementation Blueprint**  
```
Restaurant Listing screen:
1. Query params in URL (for shareability): ?cuisine=, ?rating=, ?vegOnly=, ?lat=, ?lng=, ?q=.
2. On mount:
   - If geo permission granted: GET /api/v1/restaurants/public/nearby?lat={lat}&lng={lng}&radiusKm=5 (default 5km).
   - Else: GET /api/v1/restaurants/public (global list, sorted by relevance/rating).
   - If filter params present: GET /api/v1/restaurants/public/filter?cuisineIds=...&minRating=...&isVegOnly=...
3. UI:
   - Top: horizontal cuisine chips (fetched from GET /api/v1/cuisines, or inline with restaurant list).
   - Sort dropdown: "Relevance", "Rating: High to Low", "Delivery Time", "Cost: Low to High".
   - Veg toggle chip.
   - Restaurant cards: image, name, cuisine tags, rating, delivery time estimate, "FREE DELIVERY" badge, min order.
4. Infinite scroll: use cursor pagination if supported by the endpoint (the docs indicate read-replica paginated; check if cursor variant exists; if not, use offset).
5. Pull-to-refresh: re-fetch with current filters.
6. On restaurant card tap: navigate to Restaurant Detail.
```

---

### 4.4 Menu Browsing

**Functional Overview**  
Drill into a restaurant to see categories, items, pricing, veg/non-veg indicators, dietary filters, and bestseller/recommended highlights.

**Technical Deep Dive**  
- **Categories:** `GET /api/v1/menu/categories/restaurant/{restaurantId}`
- **All items:** `GET /api/v1/menu/items/restaurant/{restaurantId}`
- **By category:** `GET /api/v1/menu/items/category/{categoryId}`
- **Item detail:** `GET /api/v1/menu/items/{id}`
- **Bestsellers:** `GET /api/v1/menu/items/restaurant/{restaurantId}/bestsellers`
- **Recommended:** `GET /api/v1/menu/items/restaurant/{restaurantId}/recommended`
- **Low stock:** `GET /api/v1/menu/items/restaurant/{restaurantId}/low-stock` (informational)
- **Diet filter:** `GET /api/v1/menu/items/restaurant/{restaurantId}?diet=VEG` — server-side diet filter.
- **Search within menu:** `GET /api/v1/menu/items/search?keyword=...`
- **Image upload (owner):** `POST /api/v1/menu/items/{id}/image/upload-url` — returns presigned URL for direct S3 upload.

**Implementation Blueprint**  
```
Restaurant Detail screen:
1. On mount: GET /api/v1/restaurants/public/{restaurantId} for header (name, image, rating, cuisines, delivery time, min order).
2. In parallel: GET /api/v1/menu/categories/restaurant/{restaurantId}.
3. Render header, then a vertical list of categories. Each category expands to show items.
4. Item card: name, description, price, veg/non-veg indicator, "BESTSELLER" badge, "RECOMMENDED" badge, add-to-cart button with quantity stepper.
5. "Bestsellers" / "Recommended" sections render as horizontal lists above the categorized menu.
6. Search within menu: a filter bar that calls GET /api/v1/menu/items/search?keyword={query} and overlays results.
7. Diet filter toggle: when toggled, re-fetch GET /api/v1/menu/items/restaurant/{id}?diet=VEG and filter the rendered list.
8. On add-to-cart: POST /api/v1/cart/add with { menuItemId, quantity, customizations? }.
9. If item is low-stock (backend returns isAvailable=false or stock info), show "Sold Out" badge and disable add-to-cart.
```

---

### 4.5 Reviews & Ratings (Public Read)

**Functional Overview**  
Displays aggregated and individual reviews for a restaurant and for specific menu items to inform purchase decisions.

**Technical Deep Dive**  
- **Restaurant reviews:** `GET /api/v1/reviews/restaurant/{restaurantId}` — public, read-replica.
- **Survey ratings:** `GET /api/v1/surveys/restaurants/public/{restaurantId}/survey-ratings` — average delivery, food, speed ratings.
- **Menu item reviews:** `GET /api/v1/reviews/menu-items/{menuItemId}`

**Implementation Blueprint**  
```
Restaurant Detail — Reviews tab:
1. On tab focus: GET /api/v1/reviews/restaurant/{restaurantId}?page=0&size=20.
2. Show aggregate rating (average stars) at top, with rating distribution bars (5★, 4★, etc.).
3. Also fetch GET /api/v1/surveys/restaurants/public/{restaurantId}/survey-ratings to show Delivery/Food/Speed sub-ratings.
4. Render review list: reviewer name (anonymized to first name + initial if privacy policy requires), rating, comment, date, any photos.
5. Pagination: offset-based or cursor-based depending on API response shape.
6. Menu Item Reviews (secondary): on menu item detail, GET /api/v1/reviews/menu-items/{menuItemId} and render similar review cards.
7. Empty state: "No reviews yet. Be the first to order and review!"
```

---

### 4.6 Serviceability Check

**Functional Overview**  
Before checkout, validates whether delivery is possible to the customer's selected address and estimates the delivery fee.

**Technical Deep Dive**  
- **Endpoint:** `GET /api/v1/serviceability/check`
- **Params:** addressId (or lat/lng as query params — verify exact param names from backend; docs indicate geo + fee estimate).

**Implementation Blueprint**  
```
Checkout flow — Step 1 (Delivery):
1. When customer selects an address in checkout, call GET /api/v1/serviceability/check?addressId={id} (or lat/lng).
2. Response indicates: isServiceable (boolean), estimatedDeliveryMinutes, deliveryFee.
3. If not serviceable: show "We don't deliver to this address yet" and disable "Proceed to Payment". Offer "Change address" CTA.
4. If serviceable: display "Estimated delivery: {minutes} mins" and "Delivery fee: ₹{fee}" (or free).
5. Cache this result for the session so repeated toggles do not re-check.
```

---

## 5. Module M4 — Cart & Checkout

### 5.1 Multi-Restaurant Cart

**Functional Overview**  
Customers can add items from multiple restaurants into a single cart. The cart groups items by restaurant, computes subtotals per restaurant, and supports mixed cart operations (add, update quantity, remove item, clear restaurant, clear all).

**Technical Deep Dive**  
- **Get cart:** `GET /api/v1/cart`
- **Add item:** `POST /api/v1/cart/add` — body `CartItemRequest { menuItemId, quantity, customizations? }`
- **Update quantity:** `PUT /api/v1/cart/items/{cartItemId}?quantity=...`
- **Remove item:** `DELETE /api/v1/cart/items/{cartItemId}`
- **Clear restaurant:** `DELETE /api/v1/cart/restaurant/{restaurantId}`
- **Clear all:** `DELETE /api/v1/cart/clear`
- **Rate limiting:** Mutations are annotated `@RateLimited("cart-mutation")`.

**Implementation Blueprint**  
```
Cart screen (full-page or bottom-sheet):
1. On mount: GET /api/v1/cart. Render grouped list: one RestaurantCard per restaurant, with nested CartItemRows.
2. Each CartItemRow: image, name, price, quantity stepper (- / count / +), remove (X) button.
3. RestaurantCard header: restaurant name, image, "Clear all from {restaurant}" link.
4. Sticky footer / bottom sheet: "Proceed to Checkout" button with total amount. If cart empty: show empty state with "Browse Restaurants" CTA.
5. Quantity stepper:
   - On +: PUT /api/v1/cart/items/{cartItemId}?quantity={newQty}. Optimistically update UI; on failure, revert and show toast.
   - On -: same endpoint. If qty reaches 0, remove item.
   - On remove (X): DELETE /api/v1/cart/items/{cartItemId}.
   - On "Clear restaurant": DELETE /api/v1/cart/restaurant/{restaurantId}. Confirm before action.
   - On "Clear all": DELETE /api/v1/cart/clear. Confirm before action.
6. Cart summary section (above footer): item count, subtotal, delivery fee estimate (if serviceability known), discount (if coupon applied), total.
7. Coupon link: navigate to CouponInput overlay/screen.
8. Rate-limit handling: if backend returns 429, show "Too many changes. Please wait a moment." and disable mutations for 5s.
```

---

### 5.2 Coupon Application

**Functional Overview**  
Allows customers to apply a coupon code to their cart, receiving a discount if valid.

**Technical Deep Dive**  
- **Validate:** `GET /api/v1/coupons/validate?code=...&orderAmount=...&restaurantId=...` — requires `CUSTOMER` role. Returns the coupon with discount details if valid.
- **Apply to cart:** `POST /api/v1/cart/apply-coupon?couponCode=...`
- **Active coupons (for browsing):** `GET /api/v1/coupons/active?restaurantId=...` — public or customer-accessible.

**Implementation Blueprint**  
```
Coupon input in Cart:
1. Text input + "Apply" button. On submit:
   - Call POST /api/v1/cart/apply-coupon?couponCode={code}.
   - On success: re-fetch GET /api/v1/cart, show applied coupon with discount amount and code in summary.
   - On failure (400/404): "Coupon is not valid or has expired." Clear input.
2. Show an "Available coupons" link that calls GET /api/v1/coupons/active?restaurantId={currentRestaurantId} and presents a list. On tap, auto-apply.
3. Allow removing an applied coupon: call POST /api/v1/cart/apply-coupon with empty or a "remove" endpoint if available (if not, clear cart's coupon by re-setting; check backend for explicit remove endpoint).
4. Validation rules (display only if backend returns them): min order amount, max discount, expiry.
```

---

### 5.3 Single-Restaurant Order Creation

**Functional Overview**  
The primary checkout flow. Converts the cart into a paid order with a selected address, payment method, tip, and optional scheduling.

**Technical Deep Dive**  
- **Endpoint:** `POST /api/v1/orders/customer/create`
- **Headers:** `Idempotency-Key: <uuid>` (required, no retries without a new key).
- **Query param:** `?async=false` (default) or `?async=true` for background processing.
- **Request:** `OrderRequest { restaurantId, deliveryAddressId, paymentMethod, tipAmount?, scheduledAt?, useWallet?, items? }`
- **Abuse defence:** `fraudDetectionService.checkAndBlock(customerId, ORDER_CREATE)` runs before any branch.
- **Sync path:** Returns `OrderResponse` directly (200 OK).
- **Async path:** Returns `OrderCreateJobResponse` with `jobId` (202 Accepted). Client polls `GET /api/v1/orders/customer/create/jobs/{jobId}` for completion.
- **State machine:** `CREATED → PLACED → CONFIRMED → READY_FOR_PICKUP → OUT_FOR_DELIVERY → DELIVERED` (with `CANCELLED` and `SCHEDULED` variants).

**Implementation Blueprint**  
```
Checkout screen (3 steps):
Step 1 — Delivery:
  - Address selector (from saved addresses).
  - Serviceability call: GET /api/v1/serviceability/check?addressId={id}. Show ETA + fee.
  - Time slot selector (if scheduling is supported): show slots starting from now + 30 min, up to 7 days.

Step 2 — Payment:
  - Payment method selector: CASH_ON_DELIVERY, UPI, WALLET, CARD (razorpay/gateway).
  - If WALLET: show current balance (from GET /customers/wallet/balance) and "Insufficient balance" warning.
  - Tip input: presets (₹10, ₹20, ₹50, ₹100) + custom.
  - Order summary: items, subtotal, delivery fee, discount (coupon), tip, total.

Step 3 — Review & Place:
  - Show full summary.
  - "Place Order" button. On tap:
    a. Generate UUID v4 for Idempotency-Key.
    b. POST /api/v1/orders/customer/create?async=false with body + header Idempotency-Key.
    c. On 200: navigate to Order Tracking screen with orderId.
    d. On 202 (async): show "Order is being processed...", poll GET /api/v1/orders/customer/create/jobs/{jobId} every 2s. On completion, navigate to tracking.
    e. On 429: "Too many orders. Please wait." (Retry-After header if present).
    f. On 400/409: show validation or business error from ApiResponse.message.

Post-placement:
  - Clear cart: DELETE /api/v1/cart/clear (optimistic, fire-and-forget).
  - Start SSE stream for live tracking.
  - Trigger haptic / push notification permission prompt if not already granted.
```

---

### 5.4 Multi-Restaurant Batch Checkout

**Functional Overview**  
Enables ordering from multiple restaurants in a single checkout flow, with the backend orchestrating multiple order creations and an async job for progress tracking.

**Technical Deep Dive**  
- **Endpoint:** `POST /api/v1/orders/customer/create-batch`
- **Headers:** `Idempotency-Key` (required).
- **Request:** `BatchOrderRequest { restaurantOrders: [{ restaurantId, deliveryAddressId, paymentMethod, items, tipAmount, scheduledAt? }, ...] }`
- **Response:** `BatchOrderResponse` with per-restaurant order IDs and overall status.
- **Polling:** `GET /api/v1/orders/customer/create/jobs/{jobId}` for async batch jobs (shared with async single order).

**Implementation Blueprint**  
```
Batch Checkout screen:
1. Triggered from Cart when items from >1 restaurant are present and user taps "Checkout".
2. Show a confirmation: "You are ordering from {count} restaurants. Each restaurant will create a separate order. Delivery fees and minimums apply per restaurant."
3. Show a per-restaurant breakdown: items, subtotal, delivery fee, total.
4. On "Place All Orders":
   - Build BatchOrderRequest from cart items grouped by restaurantId.
   - Use a single Idempotency-Key for the entire batch.
   - POST /api/v1/orders/customer/create-batch.
5. On success: show a Batch Confirmation screen listing all created orders with individual order numbers and status.
6. Allow navigating to tracking for any individual order.
7. If any restaurant fails validation: show which restaurant failed and why, offer to "Remove {restaurant} and place remaining orders".
```

---

### 5.5 Payment Method Selection & Status

**Functional Overview**  
Lets customers choose how to pay (wallet, UPI, COD, card via gateway) and check the payment status of an existing order.

**Technical Deep Dive**  
- **Payment details:** `GET /api/v1/payments/orders/{orderId}` — returns `PaymentResponse` (e.g., Razorpay gateway order ID for mobile SDK).
- **Payment status:** `GET /api/v1/payments/orders/{orderId}/payment-status`
- **Wallet top-up:** `POST /api/v1/customers/wallet/top-up` — returns gateway order ID for webhook completion.
- **Webhooks:** `POST /api/v1/payments/webhooks/razorpay` — server-to-server, not called by frontend.

**Implementation Blueprint**  
```
Payment method selector in Checkout:
1. Render options: UPI, WALLET, CASH_ON_DELIVERY, CARD.
2. If WALLET selected:
   - Call GET /api/v1/customers/wallet/balance to show current balance.
   - If balance >= order total: auto-select wallet.
   - If balance < order total: show "Insufficient wallet balance. Top up now?" with CTA to Wallet Top-Up.
3. If CARD/UPI selected:
   - Call GET /api/v1/payments/orders/{orderId} to get gateway order details (e.g., Razorpay order ID).
   - Launch payment SDK (Razorpay/PhonePe/etc.) with the returned order ID.
   - Handle success/cancel/failure callbacks from the SDK.
   - On success: backend is notified via webhook; frontend can optimistically navigate to tracking but should poll payment status if unsure.
4. If COD selected: no extra call needed; order proceeds.
5. Post-order payment status check (optional, for payment-pending screen):
   - GET /api/v1/payments/orders/{orderId}/payment-status.
   - Show spinner if PENDING, success screen if PAID, retry CTA if FAILED.
```

---

## 6. Module M5 — Order Lifecycle & Tracking

### 6.1 Order History & Reorder

**Functional Overview**  
Displays past and current orders, allows searching by order number, supports paginated and infinite-scroll lists, and enables one-tap reorder.

**Technical Deep Dive**  
- **My orders (offset):** `GET /api/v1/orders/customer/my-orders?page=0&size=20`
- **My orders (cursor):** `GET /api/v1/orders/customer/my-orders/cursor?cursor=...&size=20`
- **Order by ID:** `GET /api/v1/orders/customer/{orderId}` — supports field projection via `?fields=id,status,totalAmount,...` for bandwidth optimization.
- **Batch lookup:** `GET /api/v1/orders/customer/batch` — body contains multiple IDs.
- **Lookup by number:** `GET /api/v1/orders/number/{orderNumber}`
- **Reorder:** `POST /api/v1/orders/customer/{orderId}/reorder` — rebuilds cart from a previous order's items.

**Implementation Blueprint**  
```
Orders screen (My Orders):
1. On mount: GET /api/v1/orders/customer/my-orders/cursor?size=20. Render a vertical list of OrderSummary cards.
2. OrderSummary card: order number, date, restaurant name + image, item count, total, status badge (DELIVERED, OUT_FOR_DELIVERY, etc.), "Reorder" and "Track" buttons.
3. Infinite scroll: when scrolled to bottom and hasNext is true, call the cursor endpoint with the nextCursor.
4. Pull-to-refresh: re-fetch from cursor=null.
5. Search by order number: search bar at top, calls GET /api/v1/orders/number/{orderNumber} on submit, navigates to Order Detail.
6. Reorder: on "Reorder" tap, POST /api/v1/orders/customer/{orderId}/reorder. On success: navigate to Cart with a toast "Items added to cart".
7. Order Detail: GET /api/v1/orders/customer/{orderId}?fields=id,orderNumber,status,totalAmount,items,... (request only needed fields). Render full detail with item list, pricing breakdown, and action buttons (Track, Cancel, Reorder).
```

---

### 6.2 Live Order Tracking (SSE)

**Functional Overview**  
Provides a real-time, auto-updating tracking experience with live ETA, rider location, and order status changes — the primary differentiator of the customer experience.

**Technical Deep Dive**  
- **Tracking snapshot:** `GET /api/v1/orders/customer/track/{orderId}` — returns enriched `OrderResponse` with `liveEtaMinutes` and `liveEtaAt` recalculated from order status + rider GPS.
- **SSE stream:** `GET /api/v1/orders/stream/customer/{orderId}` — `text/event-stream`. Supports `Last-Event-ID` header for replay on reconnect.
- **Rider location history:** `GET /api/v1/orders/{orderId}/rider-location` — historical GPS breadcrumbs.
- **Enhanced ETA:** `GET /api/v1/delivery-truth/orders/{orderId}/eta` — OSRM-based ETA when enabled.
- **Order timeline:** `GET /api/v1/orders/{orderId}/timeline` — status change history.
- **Rate limiting:** `@RateLimited("order-track")` on both track snapshot and SSE.

**Implementation Blueprint**  
```
Order Tracking screen (full-screen map + status sheet):
1. On screen mount:
   a. GET /api/v1/orders/customer/track/{orderId} for initial snapshot (status, liveEtaMinutes, liveEtaAt, riderLocation if assigned).
   b. Open SSE connection: GET /api/v1/orders/stream/customer/{orderId} with Authorization header + Last-Event-ID (if reconnecting).
2. SSE event handling:
   - On each event: parse event type and data. Update local order state.
   - Event types (contract per backend; typical): ORDER_STATUS_UPDATE, RIDER_LOCATION_UPDATE, ETA_UPDATE.
   - If connection drops: exponential backoff reconnect (1s, 2s, 4s, max 30s). On reconnect, send Last-Event-ID from the last received event. If server returns 404 (order no longer trackable), navigate to Order Detail.
3. Map rendering:
   - Show rider marker (if assigned) at riderLocation.lat/lng. Animate marker movement on RIDER_LOCATION_UPDATE.
   - Show destination pin at restaurant or delivery address.
   - Draw polyline if route geometry is provided in events (or compute client-side from rider location breadcrumbs).
   - Show ETA banner: "Arriving in {liveEtaMinutes} min" with countdown.
4. Status sheet (bottom sheet):
   - Vertical timeline: Order Placed → Confirmed → Ready → Out for Delivery → Delivered.
   - Highlight current step. Show timestamp for each completed step from timeline data.
   - Show rider name + phone (if assigned) with a "Call Rider" action (tel: link).
5. Rider location fallback:
   - If SSE is unavailable or closed: poll GET /api/v1/orders/customer/track/{orderId} every 10s as fallback.
   - Show "Live tracking paused. Retrying..." banner when SSE is disconnected.
6. Back button: keep SSE connection alive in background for 30s (in case user returns quickly). If user leaves screen permanently, close emitter.
```

---

### 6.3 Guest Tracking (Shareable Link)

**Functional Overview**  
Allows customers to share a link with a guest (no login required) so the guest can view live delivery progress.

**Technical Deep Dive**  
- **Issue token:** `POST /api/v1/orders/customer/{orderId}/tracking-token` — returns `{ trackingToken }`.
- **Guest SSE:** `GET /api/v1/orders/stream/customer-token/{orderId}?token={trackingToken}` — public, rate-limited, returns `text/event-stream` or `application/json`.
- **Security:** Token is validated server-side; if invalid or expired, returns 401.

**Implementation Blueprint**  
```
Guest Tracking screen and share flow:
1. In Order Tracking screen, add a "Share delivery" button.
2. On tap:
   - Call POST /api/v1/orders/customer/{orderId}/tracking-token.
   - Construct shareable URL: {appBaseUrl}/track/{orderId}?token={trackingToken}.
   - Use Web Share API (mobile) or clipboard + toast (web): "Link copied. Share it to let others track this delivery."
3. Guest Tracking route (/track/[orderId]?token=...):
   - This route is public (no auth required).
   - On mount: read token from URL query params. Open SSE to GET /api/v1/orders/stream/customer-token/{orderId}?token={token}.
   - Render a simplified tracking view (map + ETA + status). No actions like cancel or reorder.
   - If token is invalid: show "This link is invalid or has expired."
   - If order is not in a deliverable state: show "Tracking not yet active."
4. SSE handling is identical to the authenticated customer tracking screen (see 6.2), but without auth headers.
5. Rate-limit handling: guest endpoint is rate-limited; if 429, show "Too many tracking requests. Please try again later."
```

---

### 6.4 Order Detail, Timeline & Invoices

**Functional Overview**  
Provides a complete post-delivery record: itemized order details, a vertical status timeline, an HTML invoice, and a PDF download.

**Technical Deep Dive**  
- **Detail:** `GET /api/v1/orders/customer/{orderId}` — full order with items, pricing, delivery address, payment, rider info.
- **Timeline:** `GET /api/v1/orders/{orderId}/timeline` — status change history (ordered → confirmed → ...).
- **Invoice HTML:** `GET /api/v1/orders/{orderId}/invoice` — returns HTML.
- **Invoice PDF:** `GET /api/v1/orders/{orderId}/invoice/pdf` — returns PDF binary (requires auth).
- **Growth endpoints (also customer-accessible):** `GET /api/v1/orders/{orderId}/timeline`, `/invoice`, `/invoice/pdf`, `/rider-location`.

**Implementation Blueprint**  
```
Order Detail screen:
1. On mount: GET /api/v1/orders/customer/{orderId}?fields=id,orderNumber,status,totalAmount,subtotal,deliveryFee,taxAmount,tipAmount,items,restaurant,deliveryAddress,createdAt,paidAt,deliveredAt.
2. Render:
   - Header: order number, date, status badge.
   - Restaurant info: name, image.
   - Item list: name, quantity, price, customizations.
   - Pricing breakdown: subtotal, delivery fee, tax, tip, discount, total.
   - Delivery address.
   - Payment method + transaction ID (if available).
3. Action buttons:
   - "Track" (if active delivery): navigate to Tracking screen.
   - "Reorder": POST /api/v1/orders/customer/{orderId}/reorder, then navigate to Cart.
   - "Cancel" (if cancellable): PUT /api/v1/orders/customer/{orderId}/cancel?reason=... (show reason input first).
   - "Download Invoice": GET /api/v1/orders/{orderId}/invoice/pdf, trigger browser download with filename `invoice-{orderNumber}.pdf`.
   - "View Invoice": GET /api/v1/orders/{orderId}/invoice, render sanitized HTML in an iframe or dedicated screen.
4. Timeline tab:
   - GET /api/v1/orders/{orderId}/timeline. Render a vertical stepper with status steps and timestamps.
5. Rider location tab (optional):
   - GET /api/v1/orders/{orderId}/rider-location. Show a static map snapshot with rider path polyline (historical).
```

---

### 6.5 Scheduled Orders

**Functional Overview**  
Customers can schedule orders for future delivery (minimum 30 minutes ahead, up to 7 days). Orders remain in `SCHEDULED` status until a background dispatcher promotes them to `PLACED`.

**Technical Deep Dive**  
- **List scheduled (offset):** `GET /api/v1/orders/customer/scheduled-orders?page=0&size=20`
- **List scheduled (cursor):** `GET /api/v1/orders/customer/scheduled-orders/cursor?cursor=...&size=20`
- **Cancel scheduled:** `PUT /api/v1/orders/customer/scheduled-orders/{orderId}/cancel?reason=...`

Scheduled orders appear in the normal order list only after they are dispatched. Kitchen queues exclude `SCHEDULED` orders.

**Implementation Blueprint**  
```
Scheduled Orders screen (accessible from Orders tab):
1. On mount: GET /api/v1/orders/customer/scheduled-orders/cursor?size=20.
2. Render list of future orders: scheduled time, restaurant, items preview, total.
3. If empty: "No scheduled orders. Schedule one from the restaurant menu!" with CTA to Discover.
4. Cancel scheduled order:
   - Tap order → Order Detail (read-only, no tracking).
   - "Cancel" button: prompt for reason (optional dropdown: "Plan changed", "Ordered by mistake", "Other").
   - PUT /api/v1/orders/customer/scheduled-orders/{orderId}/cancel?reason=...
   - On success: remove from list, show "Scheduled order cancelled".
5. In the main "My Orders" screen, add a filter/tab for "Upcoming" (scheduled) vs "Past" (delivered/cancelled).
```

---

### 6.6 Order CSV Export

**Functional Overview**  
Allows customers to download their complete order history as a CSV file for personal records.

**Technical Deep Dive**  
- **Endpoint:** `GET /api/v1/orders/customer/export/orders?page=0&size=50`
- **Response:** `text/csv;charset=UTF-8` with `Content-Disposition: attachment; filename=bhukkad-orders-{date}.csv`
- **Format:** `Order Number, Customer, Restaurant, Status, Total, Date`

**Implementation Blueprint**  
```
Export Orders feature (in Orders screen):
1. Add an "Export" button in the Orders screen toolbar or overflow menu.
2. On tap:
   - Call GET /api/v1/orders/customer/export/orders?page=0&size=50 (use size=50 to get a reasonable batch; confirm with backend if larger is allowed).
   - If the response is a blob with content-type text/csv:
     - Web: create a Blob, generate object URL, trigger anchor click with download attribute.
     - Mobile: use platform-specific file download API (e.g., expo-file-system or react-native-fs).
   - Show a success toast: "Orders exported to CSV."
3. Error handling:
   - If 400/500: show "Export failed. Please try again later."
   - If order history is empty: return an empty CSV with headers only (backend should handle this).
4. Do not attempt client-side CSV generation — use the backend endpoint exclusively to ensure consistent format.
```

---

## 7. Module M6 — Payments & Wallet

### 7.1 Wallet Overview & Transactions

**Functional Overview**  
Shows wallet balance, transaction history with infinite scroll, and supports top-up via payment gateway.

**Technical Deep Dive**  
- **Balance:** `GET /api/v1/customers/wallet/balance`
- **Top-up:** `POST /api/v1/customers/wallet/top-up` — returns gateway order ID for webhook completion.
- **Direct credit:** `POST /api/v1/customers/wallet/add-money` — for admin/dev use; not exposed in customer UI.
- **Transactions (offset):** `GET /api/v1/customers/wallet/transactions?page=0&size=20`
- **Transactions (cursor):** `GET /api/v1/customers/wallet/transactions/cursor?cursor=...&size=20`

**Implementation Blueprint**  
```
Wallet screen:
1. On mount:
   - GET /api/v1/customers/wallet/balance. Show large balance display: "₹1,250.00".
   - GET /api/v1/customers/wallet/transactions/cursor?size=20 for initial list.
2. Render:
   - "Add Money" button (primary CTA).
   - Transaction list: type (CREDIT/DEBIT), description, amount (+/-), date, balance after.
   - Infinite scroll on transaction list using cursor pagination.
3. Add Money flow:
   - Show amount presets: ₹100, ₹200, ₹500, ₹1000, custom.
   - On amount select: POST /api/v1/customers/wallet/top-up with { amount }.
   - On success: response contains gatewayOrderId. Launch payment SDK (Razorpay/etc.) with this order ID.
   - On payment success via SDK callback: backend is notified via webhook. Optimistically add credit to local balance; verify by re-fetching GET /api/v1/customers/wallet/balance on screen focus.
   - On payment failure: show error, allow retry.
4. Empty state: "No transactions yet."
```

---

### 7.2 Wallet Top-Up via Gateway

*(See 7.1 for the primary flow. This section formalizes the gateway handshake.)*

**Technical Deep Dive**  
- The `POST /api/v1/customers/wallet/top-up` response includes a `gatewayOrderId` (or equivalent) that the frontend passes to the payment SDK (e.g., Razorpay Checkout).
- Backend listens for `POST /api/v1/payments/webhooks/razorpay` (server-to-server) to confirm payment and credit the wallet.
- Frontend should treat the wallet balance as eventually consistent: optimistically update, then reconcile on next balance fetch.

**Implementation Blueprint**  
```
Top-up CTA handler (specifics):
1. User selects amount → POST /api/v1/customers/wallet/top-up { amount }.
2. Response: { gatewayOrderId, amount, currency }.
3. Open payment SDK:
   - Razorpay example: RazorpayCheckout.open({ key, order_id: gatewayOrderId, amount, currency, handler: onSuccess, theme }).
4. onSuccess (payment captured):
   - Show "Payment successful! Adding money to your wallet..."
   - Re-fetch GET /api/v1/customers/wallet/balance to confirm.
   - If balance did not update after 5s, show "Payment received. Balance will update shortly." and continue polling every 5s (max 3 attempts).
5. onDismissal / onFailure:
   - Show "Payment cancelled or failed. Please try again."
   - Do NOT cancel the gateway order — allow user to retry (idempotency on backend handles duplicate captures gracefully if the SDK re-opens the same order).
6. Do not call the webhook endpoint from frontend — it is server-to-server only.
```

---

### 7.3 Gift Cards

**Functional Overview**  
Allows customers to purchase gift cards, redeem them into their wallet, view purchased and received cards, and look up a card by code.

**Technical Deep Dive**  
- **Purchase:** `POST /api/v1/gift-cards/purchase`
- **Redeem:** `POST /api/v1/gift-cards/redeem` — credits wallet.
- **My cards:** `GET /api/v1/gift-cards/my-cards`
- **Received:** `GET /api/v1/gift-cards/received`
- **Lookup:** `GET /api/v1/gift-cards/{code}` — public-ish (verify auth requirements; likely customer-only).

**Implementation Blueprint**  
```
Gift Cards screen (under Wallet or standalone):
1. Tabs: "My Cards", "Received", "Redeem".
2. My Cards tab: GET /api/v1/gift-cards/my-cards. Render list of purchased cards with code, balance, expiry, status.
3. Received tab: GET /api/v1/gift-cards/received. Render list of gifts received from others.
4. Redeem tab:
   - Input: gift card code.
   - On submit: POST /api/v1/gift-cards/redeem { code }.
   - On success: show "Gift card redeemed! ₹{amount} added to your wallet." Navigate to Wallet to show updated balance.
   - On failure: "Invalid or expired gift card code."
5. Purchase flow (if exposed to customers):
   - Form: amount, recipient phone/email, message.
   - POST /api/v1/gift-cards/purchase with payment flow identical to wallet top-up (gateway handshake).
```

---

## 8. Module M7 — Engagement & Loyalty

### 8.1 Loyalty Points

**Functional Overview**  
Displays the customer's accumulated loyalty points balance and transaction history.

**Technical Deep Dive**  
- **Endpoint:** `GET /api/v1/customers/loyalty-points`

**Implementation Blueprint**  
```
Loyalty section (in Profile or dedicated "Rewards" tab):
1. On mount: GET /api/v1/customers/loyalty-points.
2. Render: large points balance, "Redeem" CTA (if redemption endpoint exists; current API only shows balance/history — confirm with backend if redeem endpoint is available before building redeem UI).
3. If response includes history: render transaction list (date, description, points +/-).
4. Empty state: "No loyalty points yet. Keep ordering to earn rewards!"
5. Show conversion hint if available: "100 points = ₹10 off your next order."
```

---

### 8.2 Referral Program

**Functional Overview**  
Enables customers to generate a unique referral code, share it, validate others' codes, and view earned rewards.

**Technical Deep Dive**  
- **My referral:** `GET /api/v1/customers/referral` — returns customer's own referral code + stats.
- **Generate:** `POST /api/v1/referrals/generate`
- **Validate:** `POST /api/v1/referrals/validate` — checks if a code is valid.
- **Rewards:** `GET /api/v1/referrals/rewards` — earned rewards from referrals.

**Implementation Blueprint**  
```
Referral screen:
1. On mount: GET /api/v1/customers/referral.
2. If no referral code yet: POST /api/v1/referrals/generate, then re-fetch.
3. Render:
   - "Your Referral Code" card: large code display, "Copy" button (clipboard API), share icons (WhatsApp, SMS, Twitter, etc.).
   - "How it works" explanation: "Give your code to friends. They get ₹X off their first order. You get ₹Y when they place their first order."
   - Stats: "Friends invited: {count}", "Successful referrals: {count}", "Total earned: ₹{amount}".
4. Rewards list: GET /api/v1/referrals/rewards. Render list of reward transactions (date, friend name/phone (anonymized), reward amount, status).
5. "Validate a code" (optional, for UX): input field + "Check" button. Calls POST /api/v1/referrals/validate { code }. Shows "This code is valid! You will get ₹X off." or "Invalid code."
```

---

### 8.3 Favorites

**Functional Overview**  
Allows customers to bookmark favorite restaurants for quick access.

**Technical Deep Dive**  
- **List:** `GET /api/v1/customers/favorites`
- **Add:** `POST /api/v1/customers/favorites/{restaurantId}`
- **Remove:** `DELETE /api/v1/customers/favorites/{restaurantId}`

**Implementation Blueprint****
```
Favorites screen:
1. On mount: GET /api/v1/customers/favorites. Render restaurant cards (same card component as Discover).
2. Empty state: "No favorites yet. Heart your favorite restaurants to see them here." with CTA to "Discover Restaurants".
3. Heart toggle on Restaurant Detail card:
   - Check if restaurant is in favorites list (hydrate from GET /customers/favorites on app start, or check on demand).
   - Tapping heart: POST /api/v1/customers/favorites/{restaurantId} (add) or DELETE (remove).
   - Optimistically toggle heart icon; on failure, revert and show toast.
4. Also expose favorites in the Restaurant Detail screen as a heart icon in the header.
```

---

### 8.4 Membership & Subscriptions

**Functional Overview**  
Membership: customers can view plans, subscribe to a membership, and check status. Subscriptions: recurring meal plans that can be paused, resumed, cancelled, or skipped.

**Technical Deep Dive**  
**Membership:**
- **Plans:** `GET /api/v1/customers/membership/plans`
- **Status:** `GET /api/v1/customers/membership/status`
- **Subscribe:** `POST /api/v1/customers/membership/subscribe`

**Subscriptions:**
- **List:** `GET /api/v1/customers/subscriptions`
- **Create:** `POST /api/v1/customers/subscriptions`
- **Pause:** `POST /api/v1/customers/subscriptions/{id}/pause`
- **Resume:** `POST /api/v1/customers/subscriptions/{id}/resume`
- **Cancel:** `POST /api/v1/customers/subscriptions/{id}/cancel`
- **Skip:** `POST /api/v1/customers/subscriptions/{id}/skip`

**Implementation Blueprint**  
```
Membership Hub screen:
1. On mount: GET /api/v1/customers/membership/plans and GET /api/v1/customers/membership/status in parallel.
2. If active membership: show current plan (name, benefits, renewal date, status). Render action: Pause / Cancel (if allowed).
3. If no active membership: render plan cards (name, price, benefits list, "Subscribe" CTA).
4. On "Subscribe": POST /api/v1/customers/membership/subscribe { planId }. On success: refresh status, show "Membership activated!".

Subscriptions screen (under "My Subscriptions"):
1. On mount: GET /api/v1/customers/subscriptions. Render list of active/paused plans.
2. Each subscription card: plan name, next delivery date, frequency, status (ACTIVE/PAUSED/CANCELLED).
3. Actions:
   - Pause: POST /api/v1/customers/subscriptions/{id}/pause.
   - Resume: POST /api/v1/customers/subscriptions/{id}/resume.
   - Cancel: POST /api/v1/customers/subscriptions/{id}/cancel (with confirmation).
   - Skip: POST /api/v1/customers/subscriptions/{id}/skip (skip only the next delivery; subsequent deliveries remain).
4. Show a visual calendar or timeline indicating the next 4 delivery dates, with skipped dates crossed out.
```

---

### 8.5 Group Orders

**Functional Overview**  
Enables a host customer to create a group order, invite members by phone, let members join and pick their items, split the bill, and place the consolidated order.

**Technical Deep Dive**  
- **Create:** `POST /api/v1/customers/group-orders` — body `GroupOrderCreateRequest { title }`. Returns `GroupOrderResponse` with shareable group ID.
- **Get detail:** `GET /api/v1/customers/group-orders/{id}`
- **Invite:** `POST /api/v1/customers/group-orders/{id}/invite` — body `GroupOrderInviteRequest { phone }`.
- **Join:** `POST /api/v1/customers/group-orders/{id}/join`
- **Split:** `POST /api/v1/customers/group-orders/{id}/split` — body `GroupOrderSplitRequest { shares: [{ userId, amount }] }`.
- **Place:** `POST /api/v1/customers/group-orders/{id}/place` — host finalizes and places the consolidated order.

**Important gap:** No dedicated SSE or push endpoint exists for group-order presence. The frontend must poll `GET /group-orders/{id}` to detect new members/items.

**Implementation Blueprint**  
```
Group Order Room screen:
1. Create:
   - Host taps "Start Group Order", enters a title (e.g., "Friday Team Lunch").
   - POST /api/v1/customers/group-orders { title }.
   - Show the group detail screen with a shareable link/code.
2. Invite:
   - "Invite" button opens a bottom sheet: phone input + "Send Invite".
   - POST /api/v1/customers/group-orders/{id}/invite { phone }.
   - Backend sends SMS with deep link to join.
3. Join:
   - Deep link route /group-orders/join/{id}. On open (user is authenticated):
   - POST /api/v1/customers/group-orders/{id}/join.
   - Navigate to Group Order Room.
4. Room UI:
   - Header: group title, member avatars, "Invite" button.
   - Tabs: "Menu", "My Items", "Bill Split".
   - Menu tab: restaurant menu (fetched from public menu endpoints). Member adds items to their personal list (stored in group order context on backend).
   - My Items tab: shows items I added, quantity stepper, remove button.
   - Bill Split tab: shows total, per-member share. Host can adjust shares and call POST /api/v1/customers/group-orders/{id}/split { shares }.
5. Polling:
   - Set up a 5-second interval to GET /api/v1/customers/group-orders/{id} to refresh member list and item list.
   - Show "X is adding items..." indicator when polling detects changes.
6. Place Order:
   - Only visible to host. When all members have confirmed or host decides to finalize:
   - POST /api/v1/customers/group-orders/{id}/place.
   - On success: show confirmation with consolidated order number for each sub-order, navigate to Order Detail.
7. Empty/member states: "Waiting for members to join...", "No items added yet."
```

---

### 8.6 Recommendations & "Surprise Me"

**Functional Overview**  
Provides personalized and contextual recommendations, plus a "surprise me" feature for indecisive users and a rebook flow for past orders.

**Technical Deep Dive**  
- **Reorder recommendations:** `GET /api/v1/customers/me/recommendations/reorder` — based on past order history.
- **For you:** `GET /api/v1/customers/me/recommendations/for-you`
- **Time-aware:** `GET /api/v1/customers/me/recommendations/time-aware` — breakfast/lunch/dinner aware.
- **Feed-rank:** `GET /api/v1/customers/me/recommendations/feed-rank` — ranking for home feed insertion.
- **Surprise me:** `GET /api/v1/customers/surprise-me`
- **Rebook:** `POST /api/v1/customers/orders/{orderId}/rebook` — adds past order items to cart.

**Implementation Blueprint**  
```
Recommendation surfaces:
1. "For You" horizontal carousel on Home feed: GET /api/v1/customers/me/recommendations/for-you. Render dish cards.
2. "Reorder" section on Home: GET /api/v1/customers/me/recommendations/reorder. Render "Your usual from {restaurant}" cards.
3. "Surprise Me" button (e.g., in empty cart or hero section of Home):
   - On tap: GET /api/v1/customers/surprise-me. Navigate to a random restaurant or dish detail.
4. Reorder flow:
   - In Order Detail or My Orders: "Reorder" button calls POST /api/v1/customers/orders/{orderId}/rebook.
   - On success: navigate to Cart with a toast "Items added to your cart".
5. Time-aware recommendations:
   - Call GET /api/v1/customers/me/recommendations/time-aware on Home feed mount. Backend returns breakfast items in the morning, dinner in the evening.
   - Use these to populate a "Good evening! craving something?" section.
6. Fallback: if recommendation endpoints return empty or 503, fall back to bestsellers: GET /api/v1/menu/items/restaurant/{id}/bestsellers.
```

---

## 9. Module M8 — Feedback & Support

### 9.1 Post-Delivery Reviews & Ratings

**Functional Overview**  
Lets customers rate and review restaurants and individual menu items after delivery.

**Technical Deep Dive**  
- **My reviews:** `GET /api/v1/reviews/my-reviews`
- **Order reviews:** `GET /api/v1/reviews/order/{orderId}`
- **Delete review:** `DELETE /api/v1/reviews/{reviewId}`
- **Menu item review:** `POST /api/v1/reviews/menu-items` — body includes `menuItemId`, `rating`, `comment?`.
- **Menu item reviews (list):** `GET /api/v1/reviews/menu-items/{menuItemId}`

**Implementation Blueprint**  
```
Review flow (triggered 30-60 min after DELIVERED status):
1. Push notification / in-app banner: "How was your order from {restaurant}?" with "Rate Now" CTA.
2. Review screen:
   - Restaurant overall rating: 1-5 star selector.
   - Per-item rating chips (if API supports it; if not, show only restaurant rating).
   - Optional comment textarea.
   - Photo upload (if supported by backend; verify if a media endpoint exists).
   - On submit: POST /api/v1/reviews/restaurant/{restaurantId} (or a unified endpoint; confirm exact endpoint from ReviewController — likely POST /reviews with body containing orderId).
3. "My Reviews" screen: GET /api/v1/reviews/my-reviews. Render list of submitted reviews with edit/delete options.
4. Delete review: DELETE /api/v1/reviews/{reviewId}. Confirm before delete.
5. Menu item review: POST /api/v1/reviews/menu-items { menuItemId, rating, comment }. Trigger from Menu Item detail screen.
```

---

### 9.2 Survey Submission

**Functional Overview**  
A lightweight post-delivery satisfaction survey (delivery, food, speed ratings) that feeds into restaurant `survey-ratings`.

**Technical Deep Dive**  
- **Submit:** `POST /api/v1/reviews/survey` — body `{ orderId, ratingDelivery, ratingFood, ratingSpeed, comment? }`.
- **Survey ratings (public):** `GET /api/v1/surveys/restaurants/public/{restaurantId}/survey-ratings`
- **Trending dishes:** `GET /api/v1/home/trending?limit=10`

**Implementation Blueprint**  
```
Post-delivery survey modal:
1. Trigger: after order is DELIVERED and tracking screen is dismissed, or via push notification after 30 min.
2. Show a bottom sheet / modal with 3 star ratings (1-5):
   - "How was the delivery?" (ratingDelivery)
   - "How was the food?" (ratingFood)
   - "How was the speed?" (ratingSpeed)
3. Optional comment textarea: "Any other feedback?"
4. On submit: POST /api/v1/reviews/survey with body.
5. On success: show "Thank you for your feedback!" and dismiss.
6. Do not re-show for the same orderId (persist submitted survey IDs in local storage / state).
7. Trending dishes: use GET /api/v1/home/trending in Home feed carousel.
```

---

### 9.3 Support Tickets

**Functional Overview**  
Allows customers to create and view support tickets for order issues, refunds, or platform problems.

**Technical Deep Dive**  
- **Create:** `POST /api/v1/customers/support/tickets` — body `SupportTicketRequest { subject, description, orderId?, priority? }`.
- **List:** `GET /api/v1/customers/support/tickets`

**Implementation Blueprint**  
```
Support screen:
1. "My Tickets" list: GET /api/v1/customers/support/tickets. Render tickets with ticket ID, subject, status (OPEN/IN_PROGRESS/RESOLVED/CLOSED), created date.
2. "Create Ticket" button:
   - Form: subject (text), description (textarea), optional orderId (lookup by order number), priority (LOW/MEDIUM/HIGH — if supported).
   - POST /api/v1/customers/support/tickets.
   - On success: show "Ticket created. We will get back to you within 24 hours." Refresh list.
3. Ticket detail: show full description, status timeline, and any agent responses (if backend returns them in the ticket response).
4. Empty state: "No support tickets yet."
```

---

### 9.4 Disputes

**Functional Overview**  
Enables customers to raise disputes for specific orders (e.g., wrong item, quality issue, missing item) and track their resolution status.

**Technical Deep Dive**  
- **Create dispute:** `POST /api/v1/customers/orders/{orderId}/disputes`
- **List disputes:** `GET /api/v1/customers/disputes`
- **Admin side:** `GET /api/v1/admin/disputes`, `GET /api/v1/admin/disputes/{disputeId}`, `POST /api/v1/admin/disputes/{disputeId}/resolve`, `POST /api/v1/admin/disputes/auto-resolve`

**Implementation Blueprint**  
```
Disputes screen:
1. "My Disputes" list: GET /api/v1/customers/disputes. Render dispute cards: order reference, reason, status (OPEN/UNDER_REVIEW/RESOLVED/REJECTED), created date.
2. "Raise Dispute" button (from Order Detail screen):
   - Only show for orders in DELIVERED or CANCELLED state (or whatever backend allows).
   - Form: orderId (pre-filled from context), reason dropdown (WRONG_ITEM, MISSING_ITEM, QUALITY, OTHER), description, optional photo upload (if backend supports media; verify).
   - POST /api/v1/customers/orders/{orderId}/disputes.
   - On success: navigate to Disputes list with a "Dispute raised" toast.
3. Dispute detail: show status, timeline, and resolution message once resolved.
4. Empty state: "No disputes raised."
```

---

## 10. Cross-Cutting Concerns

### 10.1 ApiResponse Envelope Handling

Every endpoint returns:
```json
{
  "success": true,
  "message": "Operation successful",
  "data": { ... },
  "timestamp": "2026-08-25T19:13:52Z"
}
```

Errors return appropriate HTTP status with `success: false` and a `message`. Validation errors include `data.fieldErrors`.

**Implementation:** Create a centralized API client wrapper (Axios instance or fetch wrapper) that:
- Attaches `Authorization: Bearer {accessToken}`.
- On non-2xx: throws a typed error with `message`, `status`, and `data.fieldErrors` if present.
- Logs `requestId` from response for support.

### 10.2 Pagination Strategy

- **Offset-based:** Use for first-load pagination where page numbers are needed (e.g., Orders with tabs).
- **Cursor-based:** Prefer for infinite-scroll lists (Orders cursor, Wallet transactions, Earnings, Settlements). Response shape: `{ items, nextCursor, hasNext, size }`.

### 10.3 SSE Client Contract

All SSE endpoints:
- Require `Authorization: Bearer {accessToken}` (except guest tracking).
- Accept `Last-Event-ID` header for replay.
- Return `text/event-stream`.
- Are rate-limited (`order-track`).

Client must:
- Auto-reconnect with exponential backoff.
- Send `Last-Event-ID` on reconnect.
- Close emitter on component unmount / route leave.

### 10.4 Rate Limiting

Mutating endpoints carry `@RateLimited` annotations. Frontend must handle HTTP 429:
- Read `Retry-After` header if present.
- Show a non-blocking toast: "Too many requests. Please wait {seconds} seconds."
- Disable the triggering button for the retry duration.

### 10.5 Idempotency

Order-creation endpoints require `Idempotency-Key: <UUID v4>` header. Frontend must:
- Generate a new UUID per user-initiated order placement.
- Store the key locally keyed by order intent; if the user retries the same action, reuse the same key so backend returns the cached response instead of creating a duplicate order.

### 10.6 Presigned Uploads

For menu images and proof-of-delivery photos:
1. Call the upload-url endpoint to get a presigned PUT URL.
2. PUT the binary directly to the returned URL (bypasses backend).
3. Confirm upload to the backend (if required by the endpoint contract).

---

## 11. Quick-Reference: Endpoint Inventory by Feature

| Feature | Method | Path |
|---------|--------|------|
| Register | POST | /api/v1/auth/register |
| Login | POST | /api/v1/auth/login |
| MFA verify | POST | /api/v1/auth/mfa/verify |
| Verify email | POST | /api/v1/auth/verify-email |
| Forgot password | POST | /api/v1/auth/forgot-password |
| Reset password | POST | /api/v1/auth/reset-password |
| Change password | POST | /api/v1/auth/change-password |
| Refresh token | POST | /api/v1/auth/refresh-token |
| Logout | POST | /api/v1/auth/logout |
| Profile | GET | /api/v1/customers/profile |
| Profile by ID | GET | /api/v1/customers/profile/{profileId} |
| Update profile | PUT | /api/v1/customers/profile |
| Delete account | DELETE | /api/v1/customers/account |
| List addresses | GET | /api/v1/customers/addresses |
| Create address | POST | /api/v1/customers/addresses |
| Update address | PUT | /api/v1/customers/addresses/{id} |
| Delete address | DELETE | /api/v1/customers/addresses/{id} |
| Set default address | PUT | /api/v1/customers/addresses/{id}/set-default |
| Notification prefs | GET | /api/v1/customers/notification-preferences |
| Update notification prefs | PUT | /api/v1/customers/notification-preferences |
| Register device token | POST | /api/v1/customers/device-tokens |
| Delete device token | DELETE | /api/v1/customers/device-tokens |
| Wallet balance | GET | /api/v1/customers/wallet/balance |
| Wallet top-up | POST | /api/v1/customers/wallet/top-up |
| Wallet transactions | GET | /api/v1/customers/wallet/transactions |
| Wallet transactions (cursor) | GET | /api/v1/customers/wallet/transactions/cursor |
| Loyalty points | GET | /api/v1/customers/loyalty-points |
| Referral info | GET | /api/v1/customers/referral |
| Favorites list | GET | /api/v1/customers/favorites |
| Add favorite | POST | /api/v1/customers/favorites/{restaurantId} |
| Remove favorite | DELETE | /api/v1/customers/favorites/{restaurantId} |
| Order stats | GET | /api/v1/customers/orders/stats |
| Data export (GDPR) | GET | /api/v1/customers/data-export |
| Support tickets (list) | GET | /api/v1/customers/support/tickets |
| Support tickets (create) | POST | /api/v1/customers/support/tickets |
| Membership plans | GET | /api/v1/customers/membership/plans |
| Membership status | GET | /api/v1/customers/membership/status |
| Subscribe membership | POST | /api/v1/customers/membership/subscribe |
| Home banners | GET | /api/v1/home/banners |
| Home campaigns | GET | /api/v1/home/campaigns |
| Home feed | GET | /api/v1/home/feed |
| Membership plans (public) | GET | /api/v1/home/membership-plans |
| Trending dishes | GET | /api/v1/home/trending |
| Mobile feed | GET | /api/v1/mobile/feed |
| Search suggest | GET | /api/v1/search/suggest |
| Restaurants (public list) | GET | /api/v1/restaurants/public |
| Restaurant detail | GET | /api/v1/restaurants/public/{id} |
| Restaurant search | GET | /api/v1/restaurants/public/search |
| Nearby restaurants | GET | /api/v1/restaurants/public/nearby |
| Filter restaurants | GET | /api/v1/restaurants/public/filter |
| Cuisine detail | GET | /api/v1/cuisines/{id} |
| Menu categories | GET | /api/v1/menu/categories/restaurant/{id} |
| Menu items (restaurant) | GET | /api/v1/menu/items/restaurant/{id} |
| Menu items (category) | GET | /api/v1/menu/items/category/{id} |
| Menu item detail | GET | /api/v1/menu/items/{id} |
| Menu items (diet filter) | GET | /api/v1/menu/items/restaurant/{id} |
| Menu item search | GET | /api/v1/menu/items/search |
| Bestsellers | GET | /api/v1/menu/items/restaurant/{id}/bestsellers |
| Recommended items | GET | /api/v1/menu/items/restaurant/{id}/recommended |
| Low stock | GET | /api/v1/menu/items/restaurant/{id}/low-stock |
| Serviceability check | GET | /api/v1/serviceability/check |
| Cart | GET | /api/v1/cart |
| Add to cart | POST | /api/v1/cart/add |
| Update cart item | PUT | /api/v1/cart/items/{id} |
| Remove cart item | DELETE | /api/v1/cart/items/{id} |
| Clear restaurant cart | DELETE | /api/v1/cart/restaurant/{id} |
| Clear cart | DELETE | /api/v1/cart/clear |
| Apply coupon to cart | POST | /api/v1/cart/apply-coupon |
| Active coupons | GET | /api/v1/coupons/active |
| Validate coupon | GET | /api/v1/coupons/validate |
| Create order | POST | /api/v1/orders/customer/create |
| Create batch orders | POST | /api/v1/orders/customer/create-batch |
| Poll create job | GET | /api/v1/orders/customer/create/jobs/{jobId} |
| My orders | GET | /api/v1/orders/customer/my-orders |
| My orders (cursor) | GET | /api/v1/orders/customer/my-orders/cursor |
| Scheduled orders | GET | /api/v1/orders/customer/scheduled-orders |
| Scheduled orders (cursor) | GET | /api/v1/orders/customer/scheduled-orders/cursor |
| Cancel scheduled order | PUT | /api/v1/orders/customer/scheduled-orders/{id}/cancel |
| Order detail | GET | /api/v1/orders/customer/{orderId} |
| Track order | GET | /api/v1/orders/customer/track/{orderId} |
| Reorder | POST | /api/v1/orders/customer/{orderId}/reorder |
| Export orders (CSV) | GET | /api/v1/orders/customer/export/orders |
| Cancel order | PUT | /api/v1/orders/customer/{orderId}/cancel |
| Batch get orders | GET | /api/v1/orders/customer/batch |
| Lookup by order number | GET | /api/v1/orders/number/{orderNumber} |
| Order timeline | GET | /api/v1/orders/{orderId}/timeline |
| Order invoice (HTML) | GET | /api/v1/orders/{orderId}/invoice |
| Order invoice (PDF) | GET | /api/v1/orders/{orderId}/invoice/pdf |
| Rider location history | GET | /api/v1/orders/{orderId}/rider-location |
| SSE — customer tracking | GET | /api/v1/orders/stream/customer/{orderId} |
| Issue tracking token | POST | /api/v1/orders/customer/{orderId}/tracking-token |
| SSE — guest tracking | GET | /api/v1/orders/stream/customer-token/{orderId} |
| Restaurant reviews (public) | GET | /api/v1/reviews/restaurant/{restaurantId} |
| My reviews | GET | /api/v1/reviews/my-reviews |
| Order reviews | GET | /api/v1/reviews/order/{orderId} |
| Delete review | DELETE | /api/v1/reviews/{reviewId} |
| Menu item reviews | GET | /api/v1/reviews/menu-items/{menuItemId} |
| Submit survey | POST | /api/v1/reviews/survey |
| Survey ratings (public) | GET | /api/v1/surveys/restaurants/public/{restaurantId}/survey-ratings |
| Generate referral | POST | /api/v1/referrals/generate |
| Validate referral | POST | /api/v1/referrals/validate |
| Referral rewards | GET | /api/v1/referrals/rewards |
| Payment for order | GET | /api/v1/payments/orders/{orderId} |
| Payment status | GET | /api/v1/payments/orders/{orderId}/payment-status |
| Gift cards — purchase | POST | /api/v1/gift-cards/purchase |
| Gift cards — redeem | POST | /api/v1/gift-cards/redeem |
| Gift cards — my cards | GET | /api/v1/gift-cards/my-cards |
| Gift cards — received | GET | /api/v1/gift-cards/received |
| Gift cards — lookup | GET | /api/v1/gift-cards/{code} |
| Group orders — create | POST | /api/v1/customers/group-orders |
| Group orders — invite | POST | /api/v1/customers/group-orders/{id}/invite |
| Group orders — join | POST | /api/v1/customers/group-orders/{id}/join |
| Group orders — detail | GET | /api/v1/customers/group-orders/{id} |
| Group orders — split | POST | /api/v1/customers/group-orders/{id}/split |
| Group orders — place | POST | /api/v1/customers/group-orders/{id}/place |
| Subscriptions — list | GET | /api/v1/customers/subscriptions |
| Subscriptions — create | POST | /api/v1/customers/subscriptions |
| Subscriptions — pause | POST | /api/v1/customers/subscriptions/{id}/pause |
| Subscriptions — resume | POST | /api/v1/customers/subscriptions/{id}/resume |
| Subscriptions — cancel | POST | /api/v1/customers/subscriptions/{id}/cancel |
| Subscriptions — skip | POST | /api/v1/customers/subscriptions/{id}/skip |
| Recommendations — reorder | GET | /api/v1/customers/me/recommendations/reorder |
| Recommendations — for-you | GET | /api/v1/customers/me/recommendations/for-you |
| Recommendations — time-aware | GET | /api/v1/customers/me/recommendations/time-aware |
| Recommendations — feed-rank | GET | /api/v1/customers/me/recommendations/feed-rank |
| Surprise me | GET | /api/v1/customers/surprise-me |
| Rebook order | POST | /api/v1/customers/orders/{orderId}/rebook |
| Compliance consents | GET | /api/v1/compliance/consents |
| Update consent | PUT | /api/v1/compliance/consents/{purpose} |
| Data export (initiate) | POST | /api/v1/compliance/export |
| Data export (download) | GET | /api/v1/compliance/export |
| Erase user data | POST | /api/v1/compliance/users/{id}/erase |
| Delivery truth ETA | GET | /api/v1/delivery-truth/orders/{orderId}/eta |
| GraphQL homeFeed | POST | /graphql |
| GraphQL order | POST | /graphql |

---

## Document Notes

- **Scope:** This manual covers the `CUSTOMER` role exclusively. Owner, delivery agent, and admin features are documented in `docs/api/owner-technical-manual.md`, `docs/api/delivery-technical-manual.md`, and `docs/api/admin-technical-manual.md` respectively.
- **Verification:** All endpoints, request shapes, and auth requirements are verified against the actual controller source code in `src/main/java/com/bhukkad/controller/`.
- **Gaps flagged:** Group orders lack a real-time push endpoint (polling required). No customer-facing push-notification send API exists (device-token registration only). GraphQL surface is minimal (homeFeed, order only).
- **Maintainer note:** When backend adds new customer endpoints, append them to Section 11 and add a corresponding module section above.
