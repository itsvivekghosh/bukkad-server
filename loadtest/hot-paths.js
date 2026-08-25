// =============================================================================
// Hot-paths load test (Phase 1 scaling proof).
//
// Exercises the four hot read/write paths that must not saturate each other
// when the monolith is scaled horizontally:
//   1. home feed (anonymous, cached)
//   2. menu (anonymous, cached)
//   3. search (unified search)
//   4. order create (authenticated write)
//
// Run:
//   BASE_URL=http://localhost:8080 k6 run loadtest/hot-paths.js
//   BASE_URL=https://staging.example.com k6 run loadtest/hot-paths.js
//
// Thresholds prove the scaling claims: p95 < 500ms for the cached reads and
// < 800ms for order create, with an error budget of 1%.
// =============================================================================

import { check, sleep } from "k6";
import http from "k6/http";
import { SharedArray } from "k6/data";
import { scenario } from "k6/execution";

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";
const API = `${BASE_URL}/api/v1`;

export const options = {
  scenarios: {
    feed: {
      executor: "constant-arrival-rate",
      rate: __ENV.FEED_RPS ? Number(__ENV.FEED_RPS) : 30,
      duration: "3m",
      preAllocatedVUs: 20,
      maxVUs: 80,
      exec: "homeFeed",
    },
    menu: {
      executor: "constant-arrival-rate",
      rate: __ENV.MENU_RPS ? Number(__ENV.MENU_RPS) : 30,
      duration: "3m",
      preAllocatedVUs: 20,
      maxVUs: 80,
      exec: "menu",
    },
    search: {
      executor: "constant-arrival-rate",
      rate: __ENV.SEARCH_RPS ? Number(__ENV.SEARCH_RPS) : 20,
      duration: "3m",
      preAllocatedVUs: 20,
      maxVUs: 80,
      exec: "search",
    },
    order: {
      executor: "constant-arrival-rate",
      rate: __ENV.ORDER_RPS ? Number(__ENV.ORDER_RPS) : 5,
      duration: "3m",
      preAllocatedVUs: 10,
      maxVUs: 30,
      exec: "orderCreate",
    },
  },
  thresholds: {
    // Cached anonymous reads must stay well under 500ms p95.
    "http_req_duration{scenario:feed}": ["p(95)<500", "p(99)<1000"],
    "http_req_duration{scenario:menu}": ["p(95)<500", "p(99)<1000"],
    "http_req_duration{scenario:search}": ["p(95)<500", "p(99)<1200"],
    // Order create is a write path touching inventory + payment; allow more.
    "http_req_duration{scenario:order}": ["p(95)<800", "p(99)<1500"],
    // 1% error budget across the whole run.
    http_req_failed: ["rate<0.01"],
  },
};

const SEARCH_TERMS = ["pizza", "biryani", "dosa", "burger", "chai", "cake"];

const credentials = __ENV.TEST_EMAIL && __ENV.TEST_PASSWORD
  ? { email: __ENV.TEST_EMAIL, password: __ENV.TEST_PASSWORD }
  : null;

function authHeaders() {
  if (!credentials) {
    return {};
  }
  const res = http.post(`${API}/auth/login`, JSON.stringify(credentials), {
    headers: { "Content-Type": "application/json" },
  });
  const token = res.json("data.token") || res.json("token");
  return token ? { Authorization: `Bearer ${token}` } : {};
}

export function homeFeed() {
  const res = http.get(`${API}/home/feed`, { tags: { scenario: "feed" } });
  check(res, { "feed status 200": (r) => r.status === 200 });
}

export function menu() {
  const restaurantId = (scenario.iterationInTest % 10) + 1;
  const res = http.get(`${API}/restaurants/${restaurantId}/menu`, {
    tags: { scenario: "menu" },
  });
  check(res, { "menu status 200": (r) => r.status === 200 });
}

export function search() {
  const q = SEARCH_TERMS[scenario.iterationInTest % SEARCH_TERMS.length];
  const res = http.get(`${API}/search?q=${encodeURIComponent(q)}`, {
    tags: { scenario: "search" },
  });
  check(res, { "search status 200": (r) => r.status === 200 });
}

export function orderCreate() {
  const headers = { "Content-Type": "application/json", ...authHeaders() };
  const payload = JSON.stringify({
    restaurantId: __ENV.TEST_RESTAURANT_ID ? Number(__ENV.TEST_RESTAURANT_ID) : 1,
    addressId: __ENV.TEST_ADDRESS_ID ? Number(__ENV.TEST_ADDRESS_ID) : 1,
    items: [{ menuItemId: 1, quantity: 1 }],
    paymentMethod: "CASH_ON_DELIVERY",
    specialInstructions: "load-test",
  });
  const res = http.post(`${API}/orders/customer/create`, payload, {
    headers,
    tags: { scenario: "order" },
  });
  check(res, {
    "order create 200/201": (r) => r.status === 200 || r.status === 201,
  });
  sleep(1);
}