// Bhukkad backend — k6 load test scenarios.
//
// Target gateway: http://localhost:8095  (override with -e BASE_URL=...)
// Scenarios: smoke, catalog browse, search, orders (auth), referral codes,
// admin health.
//
// Usage examples:
//   k6 run tests/load/k6-scenarios.js
//   k6 run -e BASE_URL=http://localhost:8095 -e TEST_EMAIL=x@y.z tests/load/k6-scenarios.js
//   k6 run --out json=tests/load/k6-results.json tests/load/k6-scenarios.js
//
// Every scenario uses the same VU ramp-up:
//   100 VUs over 2m -> 500 VUs over 5m -> 1000 VUs over 10m, then a 3m cool-down.
//
// Global SLO thresholds:
//   p(95) http_req_duration < 200ms
//   p(99) http_req_duration < 500ms
//   http_req_failed rate          < 0.01%  (rate < 0.0001)

import { check, sleep } from 'k6';
import http from 'k6/http';

// ---------------------------------------------------------------------------
// Configuration
// ---------------------------------------------------------------------------
export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8095';
export const TEST_EMAIL = __ENV.TEST_EMAIL || 'loadtest@bhukkad.dev';
export const TEST_PASSWORD = __ENV.TEST_PASSWORD || 'LoadTest@123456';

// Per-scenario ramp-up: 100 VUs (2m) -> 500 VUs (5m) -> 1000 VUs (10m).
const RAMP_STAGES = [
    { duration: '2m', target: 100 },
    { duration: '5m', target: 500 },
    { duration: '10m', target: 1000 },
];
const COOL_DOWN = '3m';

// Common thresholds applied to every scenario (scoped by the `scenario` tag
// that k6 adds automatically when using the `scenarios` executor).
export const commonThresholds = {
    http_req_failed: ['rate<0.0001'], // error rate < 0.01%
    http_req_duration: ['p(95)<200', 'p(99)<500'],
    checks: ['rate>0.99'],
};

function scenarioThresholds(name) {
    return {
        [`http_req_duration{scenario:${name}}`]: ['p(95)<200', 'p(99)<500'],
        [`http_req_failed{scenario:${name}}`]: ['rate<0.0001'],
        [`checks{scenario:${name}}`]: ['rate>0.99'],
    };
}

// ---------------------------------------------------------------------------
// Auth helpers (identity returns { data: { token } })
// ---------------------------------------------------------------------------
let tokenCache = '';

export function login() {
    // Per-VU email to avoid hammering a single account / rate limits.
    const email = `loadtest-vu${__VU}@bhukkad.dev`;
    const payload = JSON.stringify({ email, password: TEST_PASSWORD });
    const res = http.post(`${BASE_URL}/api/v1/auth/login`, payload, {
        headers: { 'Content-Type': 'application/json' },
        tags: { endpoint: '/api/v1/auth/login', scenario: 'auth' },
    });
    const ok =
        res.status === 200 ||
        check(res, { 'login 200-299': (r) => r.status >= 200 && r.status < 300 });
    if (!ok) {
        throw new Error(`auth login failed: HTTP ${res.status} ${res.body}`);
    }
    const json = res.json();
    const data = json && json.data ? json.data : json;
    const token = data && data.token ? data.token : null;
    if (!token) {
        throw new Error(`auth login returned no token: ${res.body}`);
    }
    return token;
}

// Token is cached per VU after the first authenticated request.
export function getToken() {
    if (!tokenCache) {
        tokenCache = login();
    }
    return tokenCache;
}

export function authHeaders() {
    return {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${getToken()}`,
    };
}

// ---------------------------------------------------------------------------
// Assertion helper: status in 200-299
// ---------------------------------------------------------------------------
export function assertOk(res, name) {
    return check(res, {
        [`${name} status 200-299`]: function (r) {
            return r.status >= 200 && r.status < 300;
        },
    });
}

// ---------------------------------------------------------------------------
// Scenarios
// ---------------------------------------------------------------------------

export const options = {
    scenarios: {
        smoke: {
            exec: 'smokeTest',
            executor: 'ramping-vus',
            startVUs: 0,
            stages: RAMP_STAGES,
            gracefulRampDown: COOL_DOWN,
        },
        catalogBrowse: {
            exec: 'catalogBrowse',
            executor: 'ramping-vus',
            startVUs: 0,
            stages: RAMP_STAGES,
            gracefulRampDown: COOL_DOWN,
        },
        search: {
            exec: 'search',
            executor: 'ramping-vus',
            startVUs: 0,
            stages: RAMP_STAGES,
            gracefulRampDown: COOL_DOWN,
        },
        orders: {
            exec: 'orders',
            executor: 'ramping-vus',
            startVUs: 0,
            stages: RAMP_STAGES,
            gracefulRampDown: COOL_DOWN,
        },
        referralCodes: {
            exec: 'referralCodes',
            executor: 'ramping-vus',
            startVUs: 0,
            stages: RAMP_STAGES,
            gracefulRampDown: COOL_DOWN,
        },
        adminHealth: {
            exec: 'adminHealth',
            executor: 'ramping-vus',
            startVUs: 0,
            stages: RAMP_STAGES,
            gracefulRampDown: COOL_DOWN,
        },
    },
    thresholds: {
        ...commonThresholds,
        ...scenarioThresholds('smoke'),
        ...scenarioThresholds('catalogBrowse'),
        ...scenarioThresholds('search'),
        ...scenarioThresholds('orders'),
        ...scenarioThresholds('referralCodes'),
        ...scenarioThresholds('adminHealth'),
    },
};

// 1. Smoke test — basic health checks (no auth)
export function smokeTest() {
    // Gateway health
    const gateway = http.get(`${BASE_URL}/actuator/health`, {
        headers: { 'Content-Type': 'application/json' },
        tags: { endpoint: '/actuator/health', scenario: 'smoke' },
    });
    assertOk(gateway, 'gateway /actuator/health');

    // Identity service health
    const identity = http.get(`${BASE_URL}/api/v1/health`, {
        headers: { 'Content-Type': 'application/json' },
        tags: { endpoint: '/api/v1/health', scenario: 'smoke' },
    });
    assertOk(identity, 'identity /api/v1/health');

    sleep(1);
}

// 2. Catalog browse — GET /api/v1/cuisines, GET /api/v1/restaurants?page=0&size=20
export function catalogBrowse() {
    const cuisines = http.get(`${BASE_URL}/api/v1/cuisines`, {
        headers: { 'Content-Type': 'application/json' },
        tags: { endpoint: '/api/v1/cuisines', scenario: 'catalogBrowse' },
    });
    assertOk(cuisines, 'catalog cuisines');

    const restaurants = http.get(`${BASE_URL}/api/v1/restaurants?page=0&size=20`, {
        headers: { 'Content-Type': 'application/json' },
        tags: { endpoint: '/api/v1/restaurants', scenario: 'catalogBrowse' },
    });
    assertOk(restaurants, 'catalog restaurants');

    sleep(1);
}

// 3. Search — GET /api/v1/search?keyword=<various>
export function search() {
    const keywords = [
        'biryani',
        'pizza',
        'burger',
        'chinese',
        'south indian',
        'cafe',
        'dessert',
        'beverages',
    ];
    const keyword = keywords[__VU % keywords.length];
    const res = http.get(
        `${BASE_URL}/api/v1/search?keyword=${encodeURIComponent(keyword)}`,
        {
            headers: { 'Content-Type': 'application/json' },
            tags: { endpoint: '/api/v1/search', scenario: 'search' },
        }
    );
    assertOk(res, 'search');
    sleep(1);
}

// 4. Orders (authenticated) — GET /api/v1/orders?cursor=<nextCursor>&size=20
export function orders() {
    const headers = authHeaders();
    let cursor = '';
    const res = http.get(
        `${BASE_URL}/api/v1/orders?cursor=${cursor}&size=20`,
        {
            headers: headers,
            tags: { endpoint: '/api/v1/orders', scenario: 'orders' },
        }
    );
    assertOk(res, 'orders');

    // Capture nextCursor from a successful response for a follow-up page.
    try {
        const json = res.json();
        const data = json && json.data ? json.data : json;
        if (data && data.nextCursor) {
            // Stored for the next iteration; kept lightweight to avoid state
            // skew across VUs. The next page URL would reuse data.nextCursor.
            cursor = data.nextCursor;
        }
    } catch (e) {
        // Ignore parse errors; the status check is the assertion.
    }

    sleep(1);
}

// 5. Referral codes — GET /api/v1/referral/codes (auth required)
export function referralCodes() {
    const headers = authHeaders();
    const res = http.get(`${BASE_URL}/api/v1/referral/codes`, {
        headers: headers,
        tags: { endpoint: '/api/v1/referral/codes', scenario: 'referralCodes' },
    });
    assertOk(res, 'referral codes');
    sleep(1);
}

// 6. Admin endpoints — GET /api/v1/admin/health
export function adminHealth() {
    const res = http.get(`${BASE_URL}/api/v1/admin/health`, {
        headers: { 'Content-Type': 'application/json' },
        tags: { endpoint: '/api/v1/admin/health', scenario: 'adminHealth' },
    });
    assertOk(res, 'admin /api/v1/admin/health');
    sleep(1);
}

// ---------------------------------------------------------------------------
// Export results as JSON (k6 writes one file per returned key).
// Also supported via CLI: k6 run --out json=tests/load/k6-results.json ...
// ---------------------------------------------------------------------------
export function handleSummary(data) {
    const stamp = new Date().toISOString().replace(/[:.]/g, '-');
    return {
        [`tests/load/k6-results-${stamp}.json`]: JSON.stringify(data, null, 2),
    };
}
