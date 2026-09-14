import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  stages: [
    { duration: '60s', target: 500 },
    { duration: '120s', target: 2000 },
    { duration: '60s', target: 500 },
    { duration: '60s', target: 0 },
  ],
  thresholds: {
    http_req_duration: ['p(95)<500'],
    http_req_failed: ['rate<0.001'],
  },
};

export default function () {
  const base = 'http://localhost:8080';

  // Restaurant read
  http.get(`${base}/api/v1/restaurants/1`);
  // Feed read
  http.get(`${base}/api/v1/feed`);
  // Auth probe
  http.post(`${base}/api/v1/auth/login`, JSON.stringify({
    email: 'loadtest@example.com',
    password: 'loadtest',
  }), {
    headers: { 'Content-Type': 'application/json' },
  });

  sleep(1);
}
