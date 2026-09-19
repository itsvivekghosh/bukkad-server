import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE_URL, baseThresholds } from './config.js';

export const options = {
    stages: [
        { duration: '30s', target: 1000 },   // Ramp up to 1000 users
        { duration: '60s', target: 5000 },   // Ramp up to 5000 users (50K TPS with 10 req/VU)
        { duration: '30s', target: 1000 },   // Ramp down
        { duration: '30s', target: 0 },
    ],
    thresholds: {
        ...baseThresholds,
        'http_req_duration{endpoint:/api/v1/social/feed/nearby}': ['p(95)<200'],
        'http_req_duration{endpoint:/api/v1/social/posts}': ['p(95)<200'],
    },
};

const POST_IDS = Array.from({ length: 100 }, (_, i) => i + 1);

export default function () {
    // 1. Social feed (public read)
    http.get(`${BASE_URL}/api/v1/social/feed/nearby?lat=28.6139&lng=77.2090&radiusKm=5`, {
        tags: { endpoint: '/api/v1/social/feed/nearby' },
    });

    // 2. Post details (public read)
    const postId = POST_IDS[__VU % POST_IDS.length];
    http.get(`${BASE_URL}/api/v1/social/posts/${postId}`, {
        tags: { endpoint: '/api/v1/social/posts' },
    });

    // 3. Restaurant posts (public read)
    http.get(`${BASE_URL}/api/v1/social/posts/restaurant/100`, {
        tags: { endpoint: '/api/v1/social/posts/restaurant' },
    });

    sleep(1);
}
