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

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8095';

let token = null;

function login(vuId) {
    const email = `loadtest-vu${vuId}@bhukkad.dev`;
    const res = http.post(`${BASE_URL}/api/v1/auth/login`, JSON.stringify({
        email: email,
        password: 'LoadTest@123456',
    }), { headers: { 'Content-Type': 'application/json' } });

    if (res.status !== 200) {
        return null;
    }
    const payload = res.json();
    const data = payload.data || payload;
    if (!data || !data.token) {
        return null;
    }
    return data.token;
}

function authedGet(token, path) {
    return http.get(`${BASE_URL}${path}`, {
        headers: { Authorization: `Bearer ${token}` },
        tags: { endpoint: path },
    });
}

export default function () {
    // Login once per VU
    if (!token) {
        token = login(__VU);
    }

    if (!token) {
        // Skip if auth failed
        sleep(1);
        return;
    }

    // Restaurant read
    authedGet(token, '/api/v1/restaurants/public');
    // Feed read
    authedGet(token, '/api/v1/feed');

    sleep(1);
}