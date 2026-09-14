import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  stages: [
    { duration: '30s', target: 1000 },
    { duration: '60s', target: 10000 },
    { duration: '30s', target: 1000 },
    { duration: '30s', target: 0 },
  ],
  thresholds: {
    http_req_duration: ['p(95)<200'],
    http_req_failed: ['rate<0.01'],
  },
};

const BASE = __ENV.REALTIME_URL || 'http://localhost:8077';

export default function () {
  const userId = __VU % 10000;
  http.get(`${BASE}/api/v1/live/${userId}`, {
    headers: { Accept: 'text/event-stream' },
    timeout: '60s',
  });
  sleep(1);
}
