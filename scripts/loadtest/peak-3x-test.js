import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  stages: [
    { duration: '120s', target: 6000 },
    { duration: '300s', target: 6000 },
    { duration: '120s', target: 0 },
  ],
  thresholds: {
    http_req_duration: ['p(95)<500'],
    http_req_failed: ['rate<0.01'],
  },
};

const BASE = __ENV.BASE_URL || 'http://localhost:8080';

export default function () {
  // Health (lightweight, always checked)
  http.get(`${BASE}/api/v1/health/ping`);

  // Restaurant read surface (primary read path)
  http.get(`${BASE}/api/v1/feed`);
  http.get(`${BASE}/api/v1/restaurants/1`);
  http.get(`${BASE}/api/v1/menu/1`);

  // Serviceability
  http.get(`${BASE}/api/v1/serviceability/check?restaurantId=1&latitude=12.9716&longitude=77.5946&subtotal=500`);

  // Auth probe
  http.post(`${BASE}/api/v1/auth/login`, JSON.stringify({
    email: 'loadtest@example.com',
    password: 'loadtest123'
  }), {
    headers: { 'Content-Type': 'application/json' },
  });

  sleep(1);
}
