// Hot-paths load test — the core commerce funnel: browse → menu → cart →
// checkout. This is the gate referenced by docs/scaling.md and the Load Test
// CI workflow. It proves read paths (feed/menu/search) do not saturate the
// order-create write path and vice versa.
//
// SLOs (docs/scaling.md): reads p95 < 500ms; order-create p95 < 800ms.
//
// Usage:
//   k6 run -e BASE_URL=https://staging.bhukkad.com loadtest/hot-paths.js

import { check, sleep } from 'k6';
import http from 'k6/http';
import { BASE_URL, login, baseThresholds } from './config.js';

const IDEMPOTENCY_PREFIX = `k6-${__VU}-${__ITER}`;

export const options = {
    stages: [
        { duration: '1m', target: 5 },   // ramp up
        { duration: '3m', target: 20 },  // sustained load
        { duration: '1m', target: 40 },  // peak
        { duration: '2m', target: 0 },   // cool down
    ],
    thresholds: {
        ...baseThresholds,
        'http_req_duration{endpoint:/api/v1/home/feed}': ['p(95)<500'],
        'http_req_duration{endpoint:/api/v1/search}': ['p(95)<500'],
        'http_req_duration{endpoint:/api/v1/orders/customer/create}': ['p(95)<800'],
    },
};

export default function () {
    const token = login();
    const headers = { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' };

    // 1. Home feed (public)
    http.get(`${BASE_URL}/api/v1/home/feed`, { tags: { endpoint: '/api/v1/home/feed' } });

    // 2. Search (public)
    http.get(`${BASE_URL}/api/v1/search?q=pizza`, { tags: { endpoint: '/api/v1/search' } });

    // 3. View restaurant menu (public)
    http.get(`${BASE_URL}/api/v1/restaurants/public`, { tags: { endpoint: '/api/v1/restaurants/public' } });

    // 4. Add item to cart (authenticated write)
    const cartAdd = http.post(`${BASE_URL}/api/v1/cart/add`, JSON.stringify({
        restaurantId: 1,
        menuItemId: 1,
        quantity: 1,
    }), { headers, tags: { endpoint: '/api/v1/cart/add' } });
    check(cartAdd, { 'cart add 200/201': (r) => r.status === 200 || r.status === 201 });

    // 5. Create order (authenticated, idempotent write — the critical path)
    const order = http.post(`${BASE_URL}/api/v1/orders/customer/create`, JSON.stringify({
        restaurantId: 1,
        deliveryAddressId: 1,
        paymentMethod: 'COD',
        tipAmount: 0,
    }), {
        headers: { ...headers, 'Idempotency-Key': `${IDEMPOTENCY_PREFIX}-order` },
        tags: { endpoint: '/api/v1/orders/customer/create' },
    });
    check(order, { 'order create 200/201': (r) => r.status === 200 || r.status === 201 });

    // 6. Track the created order (read-after-write)
    if (order.status === 200 || order.status === 201) {
        const orderId = (order.json().data || order.json()).id || 1;
        http.get(`${BASE_URL}/api/v1/orders/customer/${orderId}`, {
            headers,
            tags: { endpoint: '/api/v1/orders/customer/{orderId}' },
        });
    }

    sleep(1);
}