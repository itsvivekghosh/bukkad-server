# Authentication API

All endpoints are under `/api/v1/auth`. They are publicly reachable (no
`Authorization` header required), except where noted. Two abuse defences run
before credential checks on `register`/`login`: a Redis rate limit and a
per-IP/per-device fraud velocity check (both return `429`).

---

## POST /auth/register

### 1. Endpoint Overview
Creates a `CUSTOMER`, `RESTAURANT_OWNER` or `DELIVERY_AGENT` account, returns
a JWT access + refresh token pair, and (for customers) wires referral /
affiliate hooks. `ADMIN` cannot be self-registered.

### 2. Request Specification

**Method & URL:** `POST /api/v1/auth/register`

**Headers:**

| Header | Required | Value |
|---|---|---|
| `Content-Type` | Yes | `application/json` |

**Request Body:**

```json
{
  "fullName": "Aarav Sharma",
  "email": "aarav@example.com",
  "password": "Test@123456",
  "phoneNumber": "9876543210",
  "role": "CUSTOMER",
  "referralCode": "ARAV50",
  "affiliateCode": null
}
```

| Field | Type | Required | Constraints | Description |
|---|---|---|---|---|
| `fullName` | string | Yes | non-blank | Customer's full name |
| `email` | string | Yes | valid email | Login email (unique) |
| `password` | string | Yes | min 6 chars | Account password |
| `phoneNumber` | string | No | `^[0-9]{10}$` | 10-digit phone |
| `role` | string | No | `CUSTOMER` \| `RESTAURANT_OWNER` \| `DELIVERY_AGENT` | Desired role (default CUSTOMER) |
| `referralCode` | string | No | — | Existing customer's referral code (CUSTOMER only) |
| `affiliateCode` | string | No | — | Influencer/affiliate code (CUSTOMER only) |

### 3. Response Specification

**Success — `200 OK`:**

```json
{
  "success": true,
  "message": "Registration successful",
  "data": {
    "token": "eyJhbGciOiJIUzI1NiIs...",
    "refreshToken": "eyJhbGciOiJIUzI1NiIs...",
    "tokenType": "Bearer",
    "userId": 101,
    "email": "aarav@example.com",
    "fullName": "Aarav Sharma",
    "role": "CUSTOMER",
    "mfaRequired": false,
    "mfaToken": null
  },
  "timestamp": "2026-08-24T21:00:00",
  "traceId": "...", "spanId": "...", "requestId": "..."
}
```

| Field | Type | Description |
|---|---|---|
| `token` | string | JWT access token (24 h) |
| `refreshToken` | string | JWT refresh token (7 d) |
| `tokenType` | string | Always `Bearer` |
| `userId` | number | Account id |
| `email` / `fullName` / `role` | string | Profile snapshot |
| `mfaRequired` | boolean | `false` for new registrations |
| `mfaToken` | string \| null | Present only when MFA is required |

**Error Responses:**

| Code | Scenario |
|---|---|
| `400` | Validation (bad email, short password, bad phone) |
| `400` | `"Email already exists"` / `"Phone number already registered"` |
| `400` | `"Invalid user role"` (tried to register ADMIN) |
| `429` | Rate limit (`auth-register`, 10/60s) or fraud block (`Retry-After: 300`) |

### 4. Implementation Example

```bash
curl -X POST https://host/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{"fullName":"Aarav Sharma","email":"aarav@example.com","password":"Test@123456","phoneNumber":"9876543210","role":"CUSTOMER"}'
```

---

## POST /auth/login

### 1. Endpoint Overview
Authenticates an email+password. For `ADMIN`/`RESTAURANT_OWNER` accounts with
TOTP MFA enabled, returns `mfaRequired: true` and a 5-minute `mfaToken`
instead of the token pair — complete the flow with `POST /auth/mfa/verify`.

### 2. Request Specification

**Method & URL:** `POST /api/v1/auth/login`

**Headers:** `Content-Type: application/json`

**Request Body:**

| Field | Type | Required | Constraints | Description |
|---|---|---|---|---|
| `email` | string | Yes | valid email | Account email |
| `password` | string | Yes | non-blank | Account password |

```json
{ "email": "aarav@example.com", "password": "Test@123456" }
```

### 3. Response Specification

**Success — `200 OK` (no MFA):** same `AuthResponse` shape as register.

**Success — `200 OK` (MFA required):**

```json
{
  "success": true,
  "data": {
    "token": null,
    "refreshToken": null,
    "tokenType": "Bearer",
    "userId": 202,
    "email": "owner@example.com",
    "fullName": "Owner",
    "role": "RESTAURANT_OWNER",
    "mfaRequired": true,
    "mfaToken": "eyJhbGciOiJIUzI1NiIsInR5cGUiOiJtZmEi..."
  }
}
```

**Error Responses:**

| Code | Scenario |
|---|---|
| `401` | `"Invalid email or password"` |
| `400` | `"Account is deactivated"` |
| `429` | Rate limit (`auth-login`) or fraud block |

### 4. Implementation Example

```js
const res = await fetch(`${BASE}/api/v1/auth/login`, {
  method: "POST",
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify({ email, password }),
});
const { data } = await res.json();
if (data.mfaRequired) navigate(`/mfa?mfaToken=${data.mfaToken}`);
else { accessToken = data.token; refreshToken = data.refreshToken; }
```

---

## POST /auth/mfa/verify

### 1. Endpoint Overview
Completes a TOTP second-factor login using the `mfaToken` from the login
response. Returns the real token pair.

### 2. Request Specification

**Method & URL:** `POST /api/v1/auth/mfa/verify?mfaToken=...&code=...`

| Param | Type | Required | Description |
|---|---|---|---|
| `mfaToken` | string | Yes | Short-lived token from login response |
| `code` | string | Yes | 6-digit TOTP code from authenticator app |

No request body.

### 3. Response Specification

**Success — `200 OK`:** full `AuthResponse` (token + refreshToken).

**Errors:** `400` `"MFA token expired or invalid"`; `400` invalid code; `429` rate limited.

### 4. Implementation Example

```js
await fetch(`${BASE}/api/v1/auth/mfa/verify?mfaToken=${mfaToken}&code=${code}`, { method: "POST" });
```

---

## POST /auth/refresh-token

### 1. Endpoint Overview
**Rotates** the refresh token: the old refresh token is revoked and a new
access + refresh pair is issued. Call this when the access token expires.

### 2. Request Specification

**Method & URL:** `POST /api/v1/auth/refresh-token`

**Headers:**

| Header | Required | Value |
|---|---|---|
| `Authorization` | Yes | `Bearer <refreshToken>` |

No body / params.

### 3. Response Specification

**Success — `200 OK`:** full `AuthResponse` with a **new** token pair.

**Errors:**

| Code | Scenario |
|---|---|
| `401` | `"Refresh token revoked or expired"` (revoked, expired, or blacklisted) |
| `400` | `"User account is deactivated"` |

### 4. Implementation Example

```js
const res = await fetch(`${BASE}/api/v1/auth/refresh-token`, {
  method: "POST",
  headers: { Authorization: `Bearer ${refreshToken}` },
});
if (res.ok) {
  const { data } = await res.json();
  accessToken = data.token; refreshToken = data.refreshToken;
} else forceLogin();
```

---

## POST /auth/logout

### 1. Endpoint Overview
Revokes the current session server-side. A refresh token is removed from
Redis; an access token is blacklisted for its remaining lifetime. Best-effort
— always returns success.

### 2. Request Specification

**Method & URL:** `POST /api/v1/auth/logout`
**Headers:** `Authorization: Bearer <token>` (access or refresh). No body.

### 3. Response Specification

**Success — `200 OK`:**

```json
{ "success": true, "message": null, "data": "Logger out successfully", "timestamp": "...", "traceId": "...", "spanId": "...", "requestId": "..." }
```

`data` is the confirmation string. Errors: `400` if the header is missing/malformed.

### 4. Implementation Example

```js
await fetch(`${BASE}/api/v1/auth/logout`, {
  method: "POST",
  headers: { Authorization: `Bearer ${accessToken}` },
});
accessToken = refreshToken = null;
```

---

## POST /auth/verify-email

### 1. Endpoint Overview
Marks the account's email as verified. The verification token is a JWT sent
by email; the same `Authorization` header format is used.

**Method & URL:** `POST /api/v1/auth/verify-email?email=...`
**Headers:** `Authorization: Bearer <verificationToken>` (required)

| Param | Type | Required | Description |
|---|---|---|---|
| `email` | string | Yes | Email to verify |

**Success — `200 OK`:** `ApiResponse<Void>` (`data: null`).
**Errors:** `400` `"User not found"` / `"Email already verified"` / invalid token; `401` malformed header.

---

## POST /auth/forgot-password

### 1. Endpoint Overview
Starts password reset: creates a 30-minute token and emails a reset link.
Responds the same whether or not the email exists.

**Method & URL:** `POST /api/v1/auth/forgot-password?email=...`
**Headers:** none.

| Param | Type | Required | Description |
|---|---|---|---|
| `email` | string | Yes | Account email |

**Success — `200 OK`:** `ApiResponse<Void>`. **Errors:** `400` `"User not found"`.

---

## POST /auth/reset-password

### 1. Endpoint Overview
Consumes the emailed reset token and sets a new password; revokes all refresh
tokens for the account.

**Method & URL:** `POST /api/v1/auth/reset-password?token=...&newPassword=...`
**Headers:** none.

| Param | Type | Required | Description |
|---|---|---|---|
| `token` | string | Yes | Reset token from the email |
| `newPassword` | string | Yes | New password (min 6 chars) |

**Success — `200 OK`:** `ApiResponse<Void>`. **Errors:** `400` `"Invalid or expired reset token"`.

---

## POST /auth/change-password

### 1. Endpoint Overview
Changes the password for the authenticated user; requires the current
password and revokes all refresh tokens.

**Method & URL:** `POST /api/v1/auth/change-password?oldPassword=...&newPassword=...`
**Headers:** `Authorization: Bearer <accessToken>` (required).

| Param | Type | Required | Description |
|---|---|---|---|
| `oldPassword` | string | Yes | Current password |
| `newPassword` | string | Yes | New password (min 6, must differ from old) |

**Success — `200 OK`:** `ApiResponse<Void>`. **Errors:** `400` incorrect old password / weak new password; `401` bad token.
