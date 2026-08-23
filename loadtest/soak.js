/**
 * Soak test: 30 minutes of steady mixed traffic.
 *
 * Verifies stability under sustained load: no memory-leak-driven latency growth,
 * no connection-pool exhaustion, no Redis handle leaks. Run before releases:
 *
 *   BASE_URL=https://staging.example.com k6 run loadtest/soak.js
 */
import http from 'k6/http';
import { check, group, sleep } from 'k6';
import { API_V1 } from './config.js';

// Soak gates are deliberately looser than the CI smoke thresholds but still
// catch degradation trends (compare p95 at minute 1 vs minute 29 in the output).
export const options = {
    scenarios: {
        steady_mixed: {
            executor: 'constant-vus',
            vus: Number(__ENV.SOAK_VUS || 10),
            duration: '30m',
        },
    },
    thresholds: {
        http_req_duration: ['p(95)<1000'],
        http_req_failed: ['rate<0.02'],
    },
};

export default function () {
    group('mixed anonymous reads', () => {
        check(http.get(`${API_V1}/home/feed`), { feed ok: r => r.status === 200 });
        check(http.get(`${API_V1}/restaurants/public?page=0&size=10`), { list ok: r => r.status === 200 });
        check(http.get(`${API_V1}/search?keyword=biryani`), { search ok: r => r.status === 200 });
        sleep(2);
    });
}
