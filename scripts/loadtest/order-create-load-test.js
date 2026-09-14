import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  stages: [
    { duration: '30s', target: 200 },
    { duration: '60s', target: 1000 },
    { duration: '30s', target: 200 },
    { duration: '30s', target: 0 },
  ],
  thresholds: {
    http_req_duration: ['p(95)<1000'],
    http_req_failed: ['rate<0.01'],
  },
};

const BASE = __ENV.ORDER_URL || 'http://localhost:8092';

export default function () {
  const payload = JSON.stringify({
    customerId: 1,
    restaurantId: 1,
    items: [{ menuItemId: 1, quantity: 1, price: 100 }],
    totalAmount: 100,
    address: 'load-test',
    paymentMethod: 'CASH',
  });

  http.post(`${BASE}/api/v1/orders`, payload, {
    headers: { 'Content-Type': 'application/json' },
  });

  sleep(1);
}
