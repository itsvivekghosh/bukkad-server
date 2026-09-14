import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  stages: [
    { duration: '60s', target: 10 },
    { duration: '120s', target: 10 },
    { duration: '60s', target: 0 },
  ],
  thresholds: {
    http_req_duration: ['p(95)<500'],
    http_req_failed: ['rate<0.01'],
  },
};

const BASE = __ENV.BASE_URL || 'http://localhost:8080';

// Test data
const TEST_CUSTOMER_ID = 1;
const TEST_RESTAURANT_ID = 1;

export default function () {
  // 1. Health endpoints (no auth)
  http.get(`${BASE}/api/v1/health/ping`);
  http.get(`${BASE}/api/v1/health`);

  // 2. Restaurant/feed endpoints (no auth)
  http.get(`${BASE}/api/v1/feed`);
  http.get(`${BASE}/api/v1/restaurants/${TEST_RESTAURANT_ID}`);
  http.get(`${BASE}/api/v1/menu/${TEST_RESTAURANT_ID}`);

  // 3. Serviceability (no auth)
  http.get(`${BASE}/api/v1/serviceability/check?restaurantId=${TEST_RESTAURANT_ID}&latitude=12.9716&longitude=77.5946&subtotal=500`);

  // 4. Auth endpoints (no auth for login/register)
  // Note: These would need actual credentials in a real test
  // For now, we test the endpoint availability with invalid data
  http.post(`${BASE}/api/v1/auth/login`, JSON.stringify({
    email: 'loadtest@example.com',
    password: 'loadtest123'
  }), {
    headers: { 'Content-Type': 'application/json' },
  });

  // 5. Order endpoints (would need auth token)
  // In a real scenario, we'd login first and use the token
  // For structural test, we verify the endpoint exists
  http.get(`${BASE}/api/v1/orders`);

  // 6. Delivery endpoints
  http.get(`${BASE}/api/v1/delivery/zones`);

  sleep(1);
}
