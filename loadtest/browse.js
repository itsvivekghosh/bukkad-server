/**
 * Scenario: browse restaurants + home feed + menu reads.
 * Anonymous traffic — the highest-volume read path.
 */
import http from 'k6/http';
import { check, group } from 'k6';
import { API_V1, defaultThresholds } from './config.js';

export const options = {
    scenarios: {
        browse: {
            executor: 'ramping-vus',
            startVUs: 0,
            stages: [
                { duration: '30s', target: Number(__ENV.BROWSE_VUS || 20) },
                { duration: '2m', target: Number(__ENV.BROWSE_VUS || 20) },
                { duration: '30s', target: 0 },
            ],
        },
    },
    thresholds: defaultThresholds,
};

export default function () {
    group('home feed', () => {
        const feed = http.get(`${API_V1}/home/feed`);
        check(feed, { 'feed 200': r => r.status === 200 });
    });

    group('restaurant list', () => {
        const restaurants = http.get(`${API_V1}/restaurants/public?page=0&size=20`);
        check(restaurants, { 'restaurants 200': r => r.status === 200 });

        const body = restaurants.json();
        const firstId = body && body.items && body.items.length > 0
            ? body.items[0].id : null;
        if (firstId) {
            const menu = http.get(`${API_V1}/menu/items/restaurant/${firstId}`);
            check(menu, { 'menu 200': r => r.status === 200 });
        }
    });
}
