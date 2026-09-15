# Load Test Suite — Scale Validation

## Overview

This directory contains k6 load-test scripts for validating the Bhukkad platform at increasing scale. All scripts target the gateway entrypoint and exercise the primary read paths.

## Scripts

| Script | Target TPS | Duration | Description |
|--------|-----------|----------|-------------|
| `10tps-smoke-test.js` | 10 TPS | 3 min | Lightweight smoke test covering health, feed, and serviceability |
| `10tps-comprehensive-test.js` | 10 TPS | 3 min | Full API surface test including auth, orders, delivery |
| `100tps-test.js` | 100 TPS | 5 min | Sustained 100 TPS validation |
| `500tps-test.js` | 500 TPS | 5 min | Sustained 500 TPS validation |
| `peak-3x-test.js` | 6000 TPS | 9 min | 3× peak load validation |
| `peak-5x-test.js` | 10000 TPS | 9 min | 5× peak load validation |
| `order-create-load-test.js` | 1000 TPS | 2 min | Order creation stress test |
| `restaurant-feed-load-test.js` | 1000 TPS | 2 min | Restaurant feed stress test |
| `sse-load-test.js` | — | — | Server-Sent Events capacity test |

## Running the Tests

### Prerequisites

- k6 installed: `brew install k6` (macOS) or see https://k6.io/docs/getting-started/installation/
- Gateway running on `http://localhost:8080` (or set `BASE_URL` env var)

### 10 TPS Tests (smoke / comprehensive)

```bash
cd scripts/loadtest
k6 run 10tps-smoke-test.js
k6 run 10tps-comprehensive-test.js
```

### Scale Tests

```bash
# 100 TPS
k6 run 100tps-test.js

# 500 TPS
k6 run 500tps-test.js

# 3× peak (6000 TPS)
k6 run peak-3x-test.js

# 5× peak (10000 TPS)
k6 run peak-5x-test.js
```

### Custom base URL

```bash
BASE_URL=http://gateway:8080 k6 run 100tps-test.js
```

## Test Batches Covered

### Batch 1: Health & Platform
- `GET /api/v1/health/ping`
- `GET /api/v1/health`

### Batch 2: Restaurant Read Surface
- `GET /api/v1/feed`
- `GET /api/v1/restaurants/{id}`
- `GET /api/v1/menu/{id}`

### Batch 3: Delivery & Serviceability
- `GET /api/v1/serviceability/check`
- `GET /api/v1/delivery/zones`

### Batch 4: Auth Endpoints
- `POST /api/v1/auth/login`

### Batch 5: Order Surface
- `GET /api/v1/orders`

## Success Criteria

| Scale | p95 Latency | Error Rate | Duration |
|-------|-------------|------------|----------|
| 10 TPS | < 500ms | < 1% | 3 min |
| 100 TPS | < 500ms | < 1% | 5 min |
| 500 TPS | < 500ms | < 1% | 5 min |
| 3× peak (6000 TPS) | < 500ms | < 1% | 9 min |
| 5× peak (10000 TPS) | < 500ms | < 1% | 9 min |

## Next Steps After Scale Tests Pass

1. Chaos tests: Redis kill, 503 storm, shard interleave
2. Run full test suite on Java 17/21
3. Production deployment with monitoring

## Notes

- Tests use `localhost:8080` by default; override with `BASE_URL`
- Auth endpoints use test credentials; replace with valid test user in CI
- Order endpoints require a valid JWT; the comprehensive test validates endpoint availability only
- For full auth flow testing, use `scripts/test-all-apis.py` with a running server
- Do NOT run scale tests (100+ TPS) against production — use staging only
