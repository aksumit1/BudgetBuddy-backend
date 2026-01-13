import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate } from 'k6/metrics';

// Chaos test - simulates various failure scenarios
const errorRate = new Rate('errors');

export const options = {
    stages: [
        { duration: '1m', target: 200 },  // Spike to 200 users
        { duration: '30s', target: 50 },  // Drop to 50 users
        { duration: '1m', target: 200 },  // Spike again
        { duration: '30s', target: 0 },   // Drop to 0
    ],
    thresholds: {
        http_req_duration: ['p(95)<1000'], // Allow higher latency during chaos
        http_req_failed: ['rate<0.05'],    // Allow up to 5% errors during chaos
    },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export default function () {
    // Random endpoint selection to simulate unpredictable load
    const endpoints = [
        '/api/auth/login',
        '/api/transactions',
        '/api/accounts',
        '/api/budgets',
        '/api/goals',
    ];

    const endpoint = endpoints[Math.floor(Math.random() * endpoints.length)];
    const url = `${BASE_URL}${endpoint}`;

    // Random request method
    const methods = ['GET', 'POST'];
    const method = methods[Math.floor(Math.random() * methods.length)];

    let response;
    if (method === 'GET') {
        response = http.get(url, {
            headers: { 'Content-Type': 'application/json' },
        });
    } else {
        response = http.post(url, JSON.stringify({}), {
            headers: { 'Content-Type': 'application/json' },
        });
    }

    const success = check(response, {
        'status is 200, 401, or 403': (r) => [200, 401, 403].includes(r.status),
    });

    if (!success) {
        errorRate.add(1);
    }

    // Random sleep to simulate real user behavior
    sleep(Math.random() * 2);
}

