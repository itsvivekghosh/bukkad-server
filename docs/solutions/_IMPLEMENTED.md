# Implementation Mapping

This file maps each section of `frontend-implementations.md` to the actual
implemented files in the `bhukkad-customer` React Native app.

## 1. Multi-Restaurant Batch Checkout

| Doc Section | Implemented File |
|---|---|
| `src/utils/idempotency.ts` | `bhukkad-customer/src/utils/idempotency.ts` — uses `globalThis.crypto.randomUUID()` instead of `uuid` package (not a dependency) |
| `src/hooks/useBatchCheckout.ts` | `bhukkad-customer/src/hooks/useBatchCheckout.ts` — accepts `onSuccess` callback for navigation |
| `src/hooks/queries.ts` (`useCart`) | `bhukkad-customer/src/hooks/queries.ts` — existing hook reused, `cart.restaurantCarts` already supported |
| `src/hooks/useCartActions.ts` | `bhukkad-customer/src/hooks/useCartActions.ts` — existing hook reused unchanged |
| `src/services/endpoints.ts` (`orderApi`) | `bhukkad-customer/src/services/endpoints.ts` — added `createBatch()` method |
| `src/types/api.ts` | `bhukkad-customer/src/types/api.ts` — added `BatchOrderResult` and `BatchOrderResponse` interfaces |
| `src/flows/checkout/BatchCheckoutScreen.tsx` | `bhukkad-customer/src/flows/checkout/BatchCheckoutScreen.tsx` |
| `src/flows/checkout/BatchConfirmationScreen.tsx` | `bhukkad-customer/src/flows/checkout/BatchConfirmationScreen.tsx` |
| `src/flows/checkout/BatchPartialFailureScreen.tsx` | `bhukkad-customer/src/flows/checkout/BatchPartialFailureScreen.tsx` |
| `src/navigation/RootNavigator.tsx` | `bhukkad-customer/src/navigation/RootNavigator.tsx` — added BatchCheckout, BatchConfirmation, BatchPartialFailure screens |
| `src/navigation/types.ts` | `bhukkad-customer/src/navigation/types.ts` — added BatchCheckout, BatchConfirmation, BatchPartialFailure to RootStackParamList |
| `src/flows/cart/CartScreen.tsx` | `bhukkad-customer/src/flows/cart/CartScreen.tsx` — modified to show batch checkout button when multiple restaurants |

## 2. Geolocation Addressing

| Doc Section | Implemented File |
|---|---|
| `src/services/geocoding.ts` | `bhukkad-customer/src/services/geocoding.ts` — reverse + forward geocoding via Nominatim with in-memory cache |
| `src/hooks/useGeolocation.ts` | `bhukkad-customer/src/hooks/useGeolocation.ts` — uses `globalThis.navigator.geolocation` (RN 0.87 built-in) |
| `src/hooks/useReverseGeocode.ts` | `bhukkad-customer/src/hooks/useReverseGeocode.ts` — validates coordinate ranges, rejects NaN |
| `src/flows/account/GeocodedAddAddressScreen.tsx` | `bhukkad-customer/src/flows/account/GeocodedAddAddressScreen.tsx` |
| `src/navigation/RootNavigator.tsx` | Added `GeocodedAddAddress` screen |
| `src/navigation/types.ts` | Added `GeocodedAddAddress` route to RootStackParamList |
| `src/flows/account/AddressBookScreen.tsx` | Modified to navigate to `GeocodedAddAddress` instead of `AddAddress` |

## 3. Phone-First Registration

| Doc Section | Implemented File |
|---|---|
| `src/services/endpoints.ts` (`authApi`) | `bhukkad-customer/src/services/endpoints.ts` — added `registerPhone`, `resendPhoneOtp`, `verifyPhone`, `completeProfile` |
| `src/types/api.ts` | Added `PhoneRegisterRequest`, `PhoneRegisterResponse`, `OtpVerifyRequest`, `OtpResendRequest`, `CompleteProfileRequest` |
| `src/flows/auth/PhoneRegisterScreen.tsx` | `bhukkad-customer/src/flows/auth/PhoneRegisterScreen.tsx` — SMS/WhatsApp channel selector |
| `src/flows/auth/OtpVerificationScreen.tsx` | `bhukkad-customer/src/flows/auth/OtpVerificationScreen.tsx` — 6-digit OTP input with auto-advance, countdown timer |
| `src/flows/auth/CompleteProfileScreen.tsx` | `bhukkad-customer/src/flows/auth/CompleteProfileScreen.tsx` — email + optional password |
| `src/navigation/AuthNavigator.tsx` | Registered PhoneRegister, OtpVerification, CompleteProfile screens |
| `src/navigation/types.ts` | Added phone-first routes to AuthStackParamList |
| `src/flows/auth/LoginScreen.tsx` | Added "Sign in with phone number" link |
| `src/flows/auth/RegisterScreen.tsx` | Added "Use phone number instead" toggle |
| `src/store/sessionStore.ts` | Existing `setAuth` reused — handles phone-first auth responses with placeholder emails |

## Test Coverage

| Test File | Tests | Description |
|---|---|---|
| `__tests__/phoneAuth.test.ts` | 6 | API contract tests for registerPhone, resendPhoneOtp, verifyPhone, completeProfile |
| `__tests__/batchCheckout.test.ts` | 4 | Batch order API tests: endpoint, idempotency key, partial failure |
| `__tests__/batchOrderTypes.test.ts` | 4 | BatchOrderResponse type contract: success, partial failure, complete failure |
| `__tests__/geocoding.test.ts` | 7 | Reverse/forward geocode: Nominatim parsing, caching, fallback, abort signal |
| `__tests__/geolocation.test.ts` | 6 | Error mapping: permission, position, timeout, unknown, null |
| `__tests__/idempotency.test.ts` | 6 | Key generation, create-or-reuse, clear, missing crypto |
| `__tests__/sessionStore.test.ts` | 2 (new) | Phone-first setAuth: placeholder email, null fullName |
| `__tests__/services.test.ts` | 1 (new) | Batch order API integration with idempotency header |
