import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE_URL, baseThresholds } from './config.js';

export const options = {
    stages: [
        { duration: '30s', target: 1000 },
        { duration: '60s', target: 10000 },
        { duration: '30s', target: 1000 },
        { duration: '30s', target: 0 },
    ],
    thresholds: {
        ...baseThresholds,
        'http_req_duration{endpoint:/api/v1/live}': ['p(95)<200'],
    },
};

export default function () {
    const userId = __VU % 10000;
    http.get(`${BASE_URL}/api/v1/live/${userId}`, {
        headers: { Accept: 'text/event-stream' },
        timeout: '60s',
        tags: { endpoint: '/api/v1/live/{userId}' },
    });
    sleep(1);
}
