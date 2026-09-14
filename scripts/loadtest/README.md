# Load Test Suite — 10 TPS Validation

## Overview

This directory contains k6 load-test scripts validated at **10 TPS** (requests per second) to verify the system can sustain steady-state traffic before scaling to peak load.

## Scripts

| Script | Target | Description |
|--------|--------|-------------|
| `10tps-smoke-test.js` | 10 TPS | Lightweight smoke test covering health, feed, and serviceability |
| `10tps-comprehensive-test.js` | 10 TPS | Full API surface test including auth, orders, delivery |
| `order-create-load-test.js` | 1000 TPS | Order creation stress test (peak load) |
| `restaurant-feed-load-test.js` | 1000 TPS | Restaurant feed stress test (peak load) |
| `sse-load-test.js` | 1000 TPS | Server-Sent Events capacity test |

## Running the 10 TPS Tests

### Prerequisites

- k6 installed: `brew install k6` (macOS) or see https://k6.io/docs/getting-started/installation/
- Gateway running on `http://localhost:8080` (or set `BASE_URL` env var)

### Run smoke test

```bash
cd scripts/loadtest
k6 run 10tps-smoke-test.js
```

### Run comprehensive test

```bash
cd scripts/loadtest
BASE_URL=http://localhost:8080 k6 run 10tps-comprehensive-test.js
```

### Run with custom duration

```bash
k6 run --duration 5m --vus 10 10tps-smoke-test.js
```

## Test Batches

The 10 TPS validation covers these batches:

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

| Metric | Target |
|--------|--------|
| HTTP error rate | < 1% |
| p95 latency | < 500ms |
| Test duration | 3 minutes (60s ramp + 120s steady) |

## Next Steps After 10 TPS Pass

1. Scale to 100 TPS (10×)
2. Scale to 500 TPS (50×)
3. Scale to 3-5× peak TPS (production gate)
4. Chaos tests: Redis kill, 503 storm, shard interleave

## Notes

- Tests use `localhost:8080` by default; override with `BASE_URL`
- Auth endpoints use test credentials; replace with valid test user in CI
- Order endpoints require a valid JWT; the comprehensive test validates endpoint availability only
- For full auth flow testing, use `scripts/test-all-apis.py` with a running server
