import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE_URL, login, baseThresholds, authedGet, authedPost } from './config.js';

export const options = {
    stages: [
        { duration: '120s', target: 6000 },
        { duration: '300s', target: 6000 },
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
        'http_req_duration{endpoint:/api/v1/orders}': ['p(95)<1000'],
    },
};

let token = null;

export default function () {
    // Login once per VU
    if (!token) {
        token = login(__VU);
    }
    
    const idempotencyKey = `k6-${__VU}-${__ITER}`;

    // Health
    authedGet(token, '/api/v1/health/ping');

    // Restaurant read surface
    authedGet(token, '/api/v1/feed');
    authedGet(token, '/api/v1/restaurants/public');
    authedGet(token, '/api/v1/menu/items?restaurantId=1');

    // Serviceability
    authedGet(token, '/api/v1/serviceability?lat=12.9716&lng=77.5946');

    // Write operations (20% of iterations)
    if (__ITER % 5 === 0) {
        authedPost(token, '/api/v1/orders', {
            customerId: 1,
            restaurantId: 1,
            items: [
                {
                    menuItemId: 1,
                    name: 'Test Item',
                    unitPrice: 100.00,
                    quantity: 1
                }
            ]
        }, idempotencyKey);
    }

    sleep(1);
}