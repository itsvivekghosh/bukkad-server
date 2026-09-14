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

export default function () {
  // Health endpoints (no auth)
  http.get(`${BASE}/api/v1/health/ping`);
  http.get(`${BASE}/api/v1/health`);

  // Restaurant feed (no auth)
  http.get(`${BASE}/api/v1/feed`);

  // Serviceability check (no auth)
  http.get(`${BASE}/api/v1/serviceability/check?restaurantId=1&latitude=12.9716&longitude=77.5946&subtotal=500`);

  sleep(1);
}
