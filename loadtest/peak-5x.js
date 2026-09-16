import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE_URL, login, baseThresholds } from './config.js';

const IDEMPOTENCY_PREFIX = `k6-${__VU}-${__ITER}`;

export const options = {
    stages: [
        { duration: '120s', target: 10000 },
        { duration: '300s', target: 10000 },
        { duration: '120s', target: 0 },
    ],
    thresholds: {
        ...baseThresholds,
        'http_req_duration{endpoint:/api/v1/health/ping}': ['p(95)<200'],
        'http_req_duration{endpoint:/api/v1/feed}': ['p(95)<500'],
        'http_req_duration{endpoint:/api/v1/restaurants/public}': ['p(95)<500'],
        'http_req_duration{endpoint:/api/v1/menu/items}': ['p(95)<500'],
        'http_req_duration{endpoint:/api/v1/serviceability}': ['p(95)<500'],
        'http_req_duration{endpoint:/api/v1/auth/login}': ['p(95)<800'],
    },
};

export default function () {
    const token = login();
    const headers = { Authorization: `Bearer ${token}` };

    // Health
    http.get(`${BASE_URL}/api/v1/health/ping`, { tags: { endpoint: '/api/v1/health/ping' } });

    // Restaurant read surface
    http.get(`${BASE_URL}/api/v1/feed`, { tags: { endpoint: '/api/v1/feed' } });
    http.get(`${BASE_URL}/api/v1/restaurants/public`, { tags: { endpoint: '/api/v1/restaurants/public' } });
    http.get(`${BASE_URL}/api/v1/menu/items?restaurantId=1`, { tags: { endpoint: '/api/v1/menu/items' } });

    // Serviceability
    http.get(`${BASE_URL}/api/v1/serviceability?lat=12.9716&lng=77.5946`, { tags: { endpoint: '/api/v1/serviceability' } });

    // Auth probe
    http.post(`${BASE_URL}/api/v1/auth/login`, JSON.stringify({
        email: 'loadtest@example.com',
        password: 'LoadTest@123456',
    }), {
        headers: { 'Content-Type': 'application/json' },
        tags: { endpoint: '/api/v1/auth/login' },
    });

    sleep(1);
}
