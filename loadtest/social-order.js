import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE_URL, baseThresholds } from './config.js';

export const options = {
    stages: [
        { duration: '30s', target: 200 },    // Ramp up to 200 users
        { duration: '60s', target: 1000 },   // Ramp up to 1000 users (10K TPS with 10 req/VU)
        { duration: '30s', target: 200 },    // Ramp down
        { duration: '30s', target: 0 },
    ],
    thresholds: {
        ...baseThresholds,
        'http_req_duration{endpoint:/api/v1/social/posts/{id}/order}': ['p(95)<500'],
    },
};

const POST_IDS = Array.from({ length: 50 }, (_, i) => i + 1);

export default function () {
    const postId = POST_IDS[__VU % POST_IDS.length];
    const orderIdempotencyKey = `loadtest-order-${__VU}-${__ITER}`;

    // Create order from post (authenticated)
    const payload = JSON.stringify({
        items: [
            { menuItemId: 200, name: 'Burger', unitPrice: 12.99, quantity: 1 },
        ],
    });

    http.post(`${BASE_URL}/api/v1/social/posts/${postId}/order`, payload, {
        headers: {
            'Content-Type': 'application/json',
            'Idempotency-Key': orderIdempotencyKey,
        },
        tags: { endpoint: '/api/v1/social/posts/{id}/order' },
    });

    sleep(1);
}
