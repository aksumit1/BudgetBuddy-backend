import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate } from 'k6/metrics';
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.0.1/index.js';

// Custom metrics
const errorRate = new Rate('errors');

// Test configuration
export const options = {
    stages: [
        { duration: '30s', target: 50 },   // Ramp up to 50 users
        { duration: '1m', target: 100 },    // Ramp up to 100 users
        { duration: '2m', target: 100 },   // Stay at 100 users
        { duration: '1m', target: 50 },      // Ramp down to 50 users
        { duration: '30s', target: 0 },     // Ramp down to 0 users
    ],
    thresholds: {
        http_req_duration: ['p(95)<500'],  // 95% of requests should be below 500ms
        http_req_failed: ['rate<0.01'],    // Error rate should be less than 1%
        errors: ['rate<0.01'],
    },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// Test data
// BREAKING CHANGE: Backend now only accepts email and password_hash (no salt)
const testUser = {
    email: 'loadtest@example.com',
    passwordHash: 'test-hash'
};

export default function () {
    // Test authentication endpoint
    // BREAKING CHANGE: Use new format (no salt)
    const authResponse = http.post(`${BASE_URL}/api/auth/login`, JSON.stringify({
        email: testUser.email,
        password_hash: testUser.passwordHash
    }), {
        headers: { 'Content-Type': 'application/json' },
    });

    const authCheck = check(authResponse, {
        'auth status is 200 or 401': (r) => r.status === 200 || r.status === 401,
    });

    if (!authCheck) {
        errorRate.add(1);
    }

    sleep(1);

    // Test transactions endpoint (if authenticated)
    if (authResponse.status === 200) {
        const token = authResponse.json('accessToken');
        const transactionsResponse = http.get(`${BASE_URL}/api/transactions`, {
            headers: {
                'Authorization': `Bearer ${token}`,
                'Content-Type': 'application/json',
            },
        });

        check(transactionsResponse, {
            'transactions status is 200': (r) => r.status === 200,
            'transactions response time < 500ms': (r) => r.timings.duration < 500,
        });
    }

    sleep(1);
}

export function handleSummary(data) {
    return {
        'stdout': textSummary(data, { indent: ' ', enableColors: true }),
        'load-test-results.json': JSON.stringify(data),
    };
}

function textSummary(data, options) {
    // Simple text summary
    return `
Load Test Summary
=================
Total Requests: ${data.metrics.http_reqs.values.count}
Failed Requests: ${data.metrics.http_req_failed.values.rate * 100}%
Average Response Time: ${data.metrics.http_req_duration.values.avg}ms
P95 Response Time: ${data.metrics.http_req_duration.values['p(95)']}ms
`;
}

