// Shared k6 configuration for Bhukkad load tests.
//
// Consumed by every scenario (browse.js, hot-paths.js, ...). Thresholds encode
// the production SLOs from monitoring/README.md:
//   p95 < 1s (read-heavy browsing targets < 500ms per docs/scaling.md)
//   p99 < 3s
//   error budget 0.1% (availability 99.9%)
//
// Override the target with:
//   k6 run -e BASE_URL=https://staging.example.com loadtest/browse.js

import http from 'k6/http';

export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8095';
export const TEST_EMAIL = __ENV.TEST_EMAIL || 'loadtest@example.com';
export const TEST_PASSWORD = __ENV.TEST_PASSWORD || 'LoadTest@123456';

// Common thresholds: latency percentiles and an error budget. Scenarios add
// their own per-endpoint thresholds on top of these.
export const baseThresholds = {
    http_req_failed: ['rate<0.001'], // 99.9% availability budget
    http_req_duration: ['p(95)<1000', 'p(99)<3000'],
    checks: ['rate>0.99'],
};

/**
 * Authenticates once and returns the Bearer access token. Fails the scenario
 * fast if the load-test account cannot sign in, so a broken credential secret
 * is surfaced immediately instead of silently skewing every request.
 * 
 * Uses a per-VU email to avoid rate limiting on the auth endpoint.
 */
export function login(vuId = 0) {
    const email = `loadtest-vu${vuId}@bhukkad.dev`;
    const res = http.post(`${BASE_URL}/api/v1/auth/login`, JSON.stringify({
        email: email,
        password: TEST_PASSWORD,
    }), { headers: { 'Content-Type': 'application/json' } });

    if (res.status !== 200) {
        // Try fallback to the default test account
        const fallbackRes = http.post(`${BASE_URL}/api/v1/auth/login`, JSON.stringify({
            email: TEST_EMAIL,
            password: TEST_PASSWORD,
        }), { headers: { 'Content-Type': 'application/json' } });
        
        if (fallbackRes.status !== 200) {
            throw new Error(`load-test login failed: HTTP ${fallbackRes.status} ${fallbackRes.body}`);
        }
        const payload = fallbackRes.json();
        const data = payload.data || payload;
        if (!data || !data.token) {
            throw new Error(`load-test login did not return a token: ${fallbackRes.body}`);
        }
        return data.token;
    }
    const payload = res.json();
    const data = payload.data || payload;
    if (!data || !data.token) {
        throw new Error(`load-test login did not return a token: ${res.body}`);
    }
    return data.token;
}

/**
 * GET helper that attaches the Bearer token and records the endpoint for
 * threshold tagging via the `endpoint` URL tag.
 */
export function authedGet(token, path) {
    return http.get(`${BASE_URL}${path}`, {
        headers: { Authorization: `Bearer ${token}` },
        tags: { endpoint: path },
    });
}

/**
 * POST helper with auth and idempotency key
 */
export function authedPost(token, path, body, idempotencyKey) {
    const headers = { 
        Authorization: `Bearer ${token}`,
        'Content-Type': 'application/json',
    };
    if (idempotencyKey) {
        headers['Idempotency-Key'] = idempotencyKey;
    }
    return http.post(`${BASE_URL}${path}`, JSON.stringify(body), {
        headers: headers,
        tags: { endpoint: path },
    });
}