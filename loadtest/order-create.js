import http from 'k6/http';
import { check, sleep } from 'k6';
import { BASE_URL, login, baseThresholds } from './config.js';

const IDEMPOTENCY_PREFIX = `k6-${__VU}-${__ITER}`;

export const options = {
    stages: [
        { duration: '30s', target: 200 },
        { duration: '60s', target: 1000 },
        { duration: '30s', target: 200 },
        { duration: '30s', target: 0 },
    ],
    thresholds: {
        ...baseThresholds,
        'http_req_duration{endpoint:/api/v1/orders/customer/create}': ['p(95)<800'],
    },
};

export default function () {
    const token = login();
    const headers = {
        Authorization: `Bearer ${token}`,
        'Content-Type': 'application/json',
        'Idempotency-Key': `${IDEMPOTENCY_PREFIX}-order`,
    };

    const payload = JSON.stringify({
        restaurantId: 1,
        deliveryAddressId: 1,
        paymentMethod: 'COD',
        tipAmount: 0,
    });

    const res = http.post(`${BASE_URL}/api/v1/orders/customer/create`, payload, {
        headers,
        tags: { endpoint: '/api/v1/orders/customer/create' },
    });

    check(res, {
        'order create 2xx': (r) => r.status === 200 || r.status === 201,
    });

    sleep(1);
}
