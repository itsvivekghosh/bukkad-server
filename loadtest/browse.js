// Browse scenario — simulates a customer browsing restaurants, menus, and
// searching. This is the highest-QPS path in the system and must stay fast
// under the checkout load.
//
// SLOs (from docs/scaling.md): reads (feed/menu/search) p95 < 500ms.
//
// Usage:
//   k6 run -e BASE_URL=https://staging.bhukkad.com loadtest/browse.js

import { check, sleep } from 'k6';
import http from 'k6/http';
import { BASE_URL, login, baseThresholds } from './config.js';

export const options = {
    stages: [
        { duration: '1m', target: 20 },  // ramp up
        { duration: '3m', target: 50 },  // load
        { duration: '1m', target: 80 },  // peak
        { duration: '2m', target: 0 },   // cool down
    ],
    thresholds: {
        ...baseThresholds,
        'http_req_duration{endpoint:/api/v1/home/feed}': ['p(95)<500'],
        'http_req_duration{endpoint:/api/v1/search}': ['p(95)<500'],
        'http_req_duration{endpoint:/api/v1/restaurants/public}': ['p(95)<500'],
    },
};

export default function () {
    const token = login();

    // 1. Home feed (public, no auth needed)
    const feed = http.get(`${BASE_URL}/api/v1/home/feed`, {
        tags: { endpoint: '/api/v1/home/feed' },
    });
    check(feed, { 'feed 200': (r) => r.status === 200 });

    // 2. Browse restaurants (public)
    const restaurants = http.get(`${BASE_URL}/api/v1/restaurants/public`, {
        tags: { endpoint: '/api/v1/restaurants/public' },
    });
    check(restaurants, { 'restaurants 200': (r) => r.status === 200 });

    // 3. Search (public)
    const search = http.get(`${BASE_URL}/api/v1/search?q=biryani&cuisine=North+Indian`, {
        tags: { endpoint: '/api/v1/search' },
    });
    check(search, { 'search 200': (r) => r.status === 200 });

    // 4. View a restaurant's menu (public)
    const menu = http.get(`${BASE_URL}/api/v1/menu/items?restaurantId=1`, {
        tags: { endpoint: '/api/v1/menu/items' },
    });
    check(menu, { 'menu 200': (r) => r.status === 200 });

    // 5. Check serviceability (public)
    const serviceable = http.get(`${BASE_URL}/api/v1/serviceability?lat=12.9716&lng=77.5946`, {
        tags: { endpoint: '/api/v1/serviceability' },
    });
    check(serviceable, { 'serviceability 200': (r) => r.status === 200 });

    // 6. Browse active coupons (public)
    const coupons = http.get(`${BASE_URL}/api/v1/coupons/active`, {
        tags: { endpoint: '/api/v1/coupons/active' },
    });
    check(coupons, { 'coupons 200': (r) => r.status === 200 });

    sleep(1);
}