/**
 * Scenario: search + autocomplete reads.
 * Public traffic; exercises the Redis-backed search cache.
 */
import http from 'k6/http';
import { check, group, sleep } from 'k6';
import { API_V1, defaultThresholds } from './config.js';

export const options = {
    scenarios: {
        search: {
            executor: 'constant-arrival-rate',
            rate: Number(__ENV.SEARCH_RPS || 20), // requests per second
            timeUnit: '1s',
            duration: '2m',
            preAllocatedVUs: 10,
            maxVUs: 50,
        },
    },
    thresholds: defaultThresholds,
};

const KEYWORDS = ['pizza', 'biryani', 'dosa', 'burger', 'chai', 'cake'];

export default function () {
    group('unified search', () => {
        const keyword = KEYWORDS[Math.floor(Math.random() * KEYWORDS.length)];
        const res = http.get(`${API_V1}/search?keyword=${keyword}`);
        check(res, { 'search 200': r => r.status === 200 });
        sleep(1);
    });
}
