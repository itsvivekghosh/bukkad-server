import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE_URL, baseThresholds } from './config.js';

export const options = {
    stages: [
        { duration: '30s', target: 2000 },   // Ramp up to 2000 users
        { duration: '60s', target: 10000 },  // Ramp up to 10000 users (100K TPS with 10 req/VU)
        { duration: '30s', target: 2000 },   // Ramp down
        { duration: '30s', target: 0 },
    ],
    thresholds: {
        ...baseThresholds,
        'http_req_duration{endpoint:/api/v1/social/posts/{id}/like}': ['p(95)<50'],
    },
};

const POST_IDS = Array.from({ length: 100 }, (_, i) => i + 1);

export default function () {
    const postId = POST_IDS[__VU % POST_IDS.length];
    const likeIdempotencyKey = `loadtest-like-${__VU}-${__ITER}`;

    // Toggle like (authenticated)
    http.post(`${BASE_URL}/api/v1/social/posts/${postId}/like`, null, {
        headers: {
            'Content-Type': 'application/json',
            'Idempotency-Key': likeIdempotencyKey,
        },
        tags: { endpoint: '/api/v1/social/posts/{id}/like' },
    });

    sleep(1);
}
