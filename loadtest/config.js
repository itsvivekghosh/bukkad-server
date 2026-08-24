/**
 * Shared configuration for all Bhukkad k6 scenarios.
 * Override via environment variables — never hardcode environment URLs here.
 */
export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
export const API_V1 = `${BASE_URL}/api/v1`;

// CI gate: fail the build when P95 latency degrades or errors spike.
export const defaultThresholds = {
    http_req_duration: ['p(95)<500'],
    http_req_failed: ['rate<0.01'],
};

/** Registers a customer and logs in, returning { token, refreshToken }. */
export function login(email, password) {
    const res = http.post(`${API_V1}/auth/login`,
        JSON.stringify({ email: email, password: password }),
        { headers: { 'Content-Type': 'application/json' } });
    if (res.status !== 200) {
        return null;
    }
    const body = res.json();
    return body && body.data ? body.data : null;
}

/** Auth headers for a JWT pair. */
export function authHeaders(token) {
    return {
        headers: {
            'Content-Type': 'application/json',
            Authorization: `Bearer ${token}`,
        },
    };
}
