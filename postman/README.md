# Bhukkad Postman Collection

Complete API testing package for the Bhukkad backend (`/api/v1`).

**Synchronization:** the collection is generated from the backend source
(`services/*` controllers, gateway routes) and cross-checked against the
live-tested contract suite (`scripts/test-all-apis.py`). Regenerate with
`python3 postman/generate_postman.py` after API changes.

## Files

| File | Purpose |
|------|---------|
| `Bhukkad-API.postman_collection.json` | 261 requests / 20 folders / 616 assertions |
| `Bhukkad-API.postman_environment.json` | Root E2E environment (`http://localhost:8080`) |
| `environments/Bhukkad-Local.postman_environment.json` | `http://localhost:8080` |
| `environments/Bhukkad-Docker.postman_environment.json` | Docker Compose stack |
| `environments/Bhukkad-K8s.postman_environment.json` | K8s port-forward |
| `CURL_REFERENCE.md` | cURL equivalents for every request |
| `generate_postman.py` | Regenerate collection after API changes |

## Test layers

Every request carries test scripts covering three layers:

1. **Functional (folders 01–16, 99)** — happy paths asserting the
   `ApiResponse` envelope (`success:true`, `data`, `timestamp`, `traceId`),
   field-type schemas (`data.id:number`, …), data-integrity invariants
   (pagination caps, ledger ordering, non-negative money, order state-machine
   codes, JWT shape) and response chaining (IDs auto-saved to the environment).
2. **Edge & boundary (folder 17)** — empty payloads `{}`, boundary values
   (quantity `0 / -1 / 100000`, rating `0 / 6`, page `-1`, size `0 / 10000`),
   unusual data types (string where number expected), out-of-range geo,
   SQL-meta-character and unicode/emoji inputs (must return 200/400, never 500).
3. **Error handling (folders 18–19)** — missing/garbage/malformed tokens
   (401), wrong-role access (403), unknown IDs and routes (404), missing
   params (`400 MISSING_PARAM`), malformed JSON (`400 INVALID_BODY`), wrong
   content type (400/415), oversized payloads (400/413), idempotency replay
   (same key → same result or 409 — no double credit), unsigned webhook
   rejection, and `/internal/**` protection.

Error assertions match the platform contract: success responses use
`ApiResponse{success, data, timestamp, traceId}`; failures use
`ApiError{status, code, message, traceId, timestamp}` (codes like
`VALIDATION_FAILED`, `INVALID_BODY`, `MISSING_PARAM`, `DATA_CONFLICT`,
`UNAUTHORIZED`, `ACCESS_DENIED`, `RATE_LIMIT_EXCEEDED`).

## Quick start

1. Start the backend:
   ```bash
   ./docker/scripts/deploy.sh
   # or: mvn spring-boot:run
   ```
2. **Postman** → Import → `Bhukkad-API.postman_collection.json`
3. Import one environment from `environments/`
4. Select the environment (top-right dropdown)
5. Run **02 - Auth → Register Customer** (or Login if already registered)
6. Token is saved to `accessToken` automatically — other requests use it

## Collection scripts

### Collection pre-request (runs before every request)

- Injects `Authorization: Bearer {{accessToken}}` when a token exists
- Skips auth when request sets `noAuth` (public endpoints) or strips the
  header itself (health paths)
- Generates fresh `{{idempotencyKey}}` via `{{$guid}}` for order/wallet calls
  (replay tests override it explicitly)

### Collection test (runs after every request)

- Asserts response time &lt; 10 seconds
- Validates JSON `Content-Type` when a body is present

### Auth folder post-response

On login/register success:

```javascript
pm.environment.set('accessToken', json.data.token);
pm.environment.set('refreshToken', json.data.refreshToken);
pm.environment.set('userId', String(json.data.userId));
pm.environment.set('userRole', json.data.role);
```

> **Note:** The API returns `data.token`, not `accessToken`.

### Chained variables

Several requests save IDs for follow-up calls:

| Request | Saves |
|---------|--------|
| Add Address | `addressId` |
| Add To Cart | `cartItemId` |
| Create Order | `orderId` |
| Create Restaurant | `restaurantId` |
| Create Category | `categoryId` |
| Create Menu Item | `menuItemId` |
| Create Order Async | `jobId` |

Set `restaurantId` / `menuItemId` manually if using seed data instead of owner flow.

## Newman (CLI)

```bash
npm install -g newman

newman run postman/Bhukkad-API.postman_collection.json \
  -e postman/environments/Bhukkad-Local.postman_environment.json \
  --folder "01 - Health" \
  --reporters cli,htmlextra \
  --reporter-htmlextra-export postman/reports/health.html
```

Full collection (requires registered users + seed IDs):

```bash
newman run postman/Bhukkad-API.postman_collection.json \
  -e postman/environments/Bhukkad-Local.postman_environment.json \
  --delay-request 200
```

## Recommended test order

1. **01 - Health** — verify server is up
2. **02 - Auth** — register/login all roles
3. **05 - Restaurant Owner** — create restaurant, category, menu item (saves IDs)
4. **04 - Customer** — address → cart → order
5. **05 - Restaurant Owner** — accept → ready → assign agent
6. **06 - Delivery Agent** — accept → picked up → delivered
7. **17–19 - Edge / Access / Transport** — negative suites (after Auth)
8. **99 - E2E Flow** — single-folder happy path

## Newman (CLI) — full suite including negative tests

```bash
newman run postman/Bhukkad-API.postman_collection.json \
  -e postman/environments/Bhukkad-Local.postman_environment.json \
  --folder "02 - Auth" --folder "17 - Edge & Boundary" \
  --folder "18 - Auth & Access Errors" --folder "19 - Transport & Server Errors" \
  --reporters cli
```

## Environment variables

| Variable | Description |
|----------|-------------|
| `baseUrl` | API root (no trailing slash) |
| `password` | Default test password (`secret123`) |
| `customerEmail` / `ownerEmail` / … | Unique per environment file |
| `accessToken` | JWT (auto-set on login) |
| `restaurantId`, `menuItemId`, `orderId`, … | Entity IDs for path params |
| `idempotencyKey` | Auto-generated per request |
| `couponCode` | Default `SAVE10` |

## cURL example

```bash
export BASE=http://localhost:8080

# Login
TOKEN=$(curl -s -X POST "$BASE/api/v1/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"email":"customer.local@bhukkad.test","password":"secret123"}' \
  | jq -r '.data.token')

# Authenticated request
curl -s "$BASE/api/v1/customers/profile" \
  -H "Authorization: Bearer $TOKEN" | jq
```

See `CURL_REFERENCE.md` for all endpoints.

## Regenerate after API changes

```bash
python3 postman/generate_postman.py
```

Edit `generate_postman.py` to add new endpoints, then rerun.
