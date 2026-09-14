import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  stages: [
    { duration: '30s', target: 500 },
    { duration: '60s', target: 1000 },
    { duration: '30s', target: 500 },
    { duration: '30s', target: 0 },
  ],
  thresholds: {
    http_req_duration: ['p(95)<250'],
    http_req_failed: ['rate<0.001'],
  },
};

const BASE = __ENV.RESTAURANT_URL || 'http://localhost:8091';

export default function () {
  http.get(`${BASE}/api/v1/feed`);
  http.get(`${BASE}/api/v1/restaurants/${__VU % 100 + 1}`);
  sleep(1);
}
