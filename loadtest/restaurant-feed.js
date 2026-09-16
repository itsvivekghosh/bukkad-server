import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE_URL, baseThresholds } from './config.js';

export const options = {
    stages: [
        { duration: '30s', target: 500 },
        { duration: '60s', target: 1000 },
        { duration: '30s', target: 500 },
        { duration: '30s', target: 0 },
    ],
    thresholds: {
        ...baseThresholds,
        'http_req_duration{endpoint:/api/v1/feed}': ['p(95)<250'],
        'http_req_duration{endpoint:/api/v1/restaurants/public}': ['p(95)<250'],
    },
};

export default function () {
    http.get(`${BASE_URL}/api/v1/feed`, { tags: { endpoint: '/api/v1/feed' } });
    http.get(`${BASE_URL}/api/v1/restaurants/public/${__VU % 100 + 1}`, {
        tags: { endpoint: '/api/v1/restaurants/public/{id}' },
    });
    sleep(1);
}
