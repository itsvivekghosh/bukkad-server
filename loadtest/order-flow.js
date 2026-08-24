/**
 * Scenario: place an order end-to-end (login → cart → order → track).
 * Authenticated write path — the most business-critical flow.
 *
 * Requires TEST_EMAIL / TEST_PASSWORD for a seeded test customer.
 * Order creation is rate-limited and fraud-gated: keep the arrival rate modest.
 */
import http from 'k6/http';
import { check, group } from 'k6';
import { API_V1, defaultThresholds, login, authHeaders } from './config.js';

export const options = {
    scenarios: {
        order_flow: {
            executor: 'ramping-arrival-rate',
            startRate: 1,
            timeUnit: '5s',
            stages: [
                { target: 2, duration: '30s' },
                { target: 4, duration: '1m' },
                { target: 0, duration: '15s' },
            ],
            preAllocatedVUs: 10,
            maxVUs: 40,
        },
    },
    thresholds: Object.assign({}, defaultThresholds, {
        // Writes may be slower than reads; the gate is still strict on errors.
        http_req_duration: ['p(95)<800'],
    }),
};

export default function () {
    const email = __ENV.TEST_EMAIL || 'loadtest@bhukkad.com';
    const password = __ENV.TEST_PASSWORD || '';

    group('auth', () => {
        var session = login(email, password);
        if (!session || !session.token) {
            check(null, { 'login succeeded': () => false });
            return;
        }
    });

    group('place order', () => {
        const headers = authHeaders(session.token);
        const payload = JSON.stringify({
            restaurantId: Number(__ENV.TEST_RESTAURANT_ID || 1),
            deliveryAddressId: Number(__ENV.TEST_ADDRESS_ID || 1),
            paymentMethod: 'COD',
        });
        const res = http.post(`${API_V1}/orders/customer/create`, payload, headers);
        check(res, {
            'order created or accepted': r => r.status === 200 || r.status === 202,
        });

        if (res.status === 200 && res.json('data.id')) {
            const orderId = res.json('data.id');
            const track = http.get(
                `${API_V1}/orders/customer/${orderId}`, headers);
            check(track, { 'order readable': r => r.status === 200 });
        }
    });
}
