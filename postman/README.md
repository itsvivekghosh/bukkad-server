# Bhukkad Postman Collection

Complete API testing package for the Bhukkad backend (`/api/v1`).

## Files

| File | Purpose |
|------|---------|
| `Bhukkad-API.postman_collection.json` | All endpoints, tests, pre/post scripts |
| `environments/Bhukkad-Local.postman_environment.json` | `http://localhost:8080` |
| `environments/Bhukkad-Docker.postman_environment.json` | Docker Compose stack |
| `environments/Bhukkad-K8s.postman_environment.json` | K8s port-forward |
| `CURL_REFERENCE.md` | cURL equivalents for every request |
| `generate_postman.py` | Regenerate collection after API changes |

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
- Skips auth when request sets `noAuth` (public endpoints)
- Generates fresh `{{idempotencyKey}}` via `{{$guid}}` for order/wallet calls

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
7. **99 - E2E Flow** — single-folder happy path

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
