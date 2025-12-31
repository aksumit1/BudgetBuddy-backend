import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';

// Custom metrics
const errorRate = new Rate('errors');
const responseTime = new Trend('response_time');

// Stress test - push system to breaking point
export const options = {
  stages: [
    { duration: '1m', target: 50 },   // Ramp up to 50 users
    { duration: '2m', target: 100 },   // Ramp up to 100 users
    { duration: '3m', target: 200 },   // Ramp up to 200 users
    { duration: '3m', target: 500 },   // Push to 500 users (stress level)
    { duration: '3m', target: 1000 }, // Push to 1000 users (breaking point)
    { duration: '2m', target: 500 },  // Ramp down to 500
    { duration: '1m', target: 0 },     // Ramp down to 0
  ],
  thresholds: {
    http_req_duration: ['p(95)<2000', 'p(99)<5000'], // Allow higher latency under stress
    http_req_failed: ['rate<0.10'],                   // Allow up to 10% errors under stress
    errors: ['rate<0.10'],
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// Test data
const testUsers = [
  { email: 'stress1@test.com', passwordHash: 'test-hash-1' },
  { email: 'stress2@test.com', passwordHash: 'test-hash-2' },
  { email: 'stress3@test.com', passwordHash: 'test-hash-3' },
];

let authTokens = {};

export function setup() {
  // Pre-authenticate users
  console.log('Setting up stress test users...');
  for (const user of testUsers) {
    const loginRes = http.post(`${BASE_URL}/api/auth/login`, JSON.stringify({
      email: user.email,
      password_hash: user.passwordHash
    }), {
      headers: { 'Content-Type': 'application/json' },
    });
    
    if (loginRes.status === 200) {
      const body = JSON.parse(loginRes.body);
      authTokens[user.email] = body.accessToken;
    }
  }
  return { tokens: authTokens };
}

export default function (data) {
  const user = testUsers[Math.floor(Math.random() * testUsers.length)];
  const token = data.tokens[user.email] || '';

  // Random endpoint selection to stress different parts of the system
  const endpoints = [
    { method: 'GET', path: '/api/transactions' },
    { method: 'GET', path: '/api/accounts' },
    { method: 'GET', path: '/api/budgets' },
    { method: 'GET', path: '/api/goals' },
    { method: 'GET', path: '/api/users/me' },
    { method: 'GET', path: '/api/analytics/spending-summary' },
    { method: 'GET', path: '/api/subscriptions' },
    { method: 'GET', path: '/api/sync/all' },
    { method: 'GET', path: '/actuator/health' },
  ];

  const endpoint = endpoints[Math.floor(Math.random() * endpoints.length)];
  const url = `${BASE_URL}${endpoint.path}`;

  let response;
  if (endpoint.method === 'GET') {
    response = http.get(url, {
      headers: {
        'Authorization': `Bearer ${token}`,
        'Content-Type': 'application/json',
      },
      timeout: '10s',
    });
  } else {
    response = http.post(url, JSON.stringify({}), {
      headers: {
        'Authorization': `Bearer ${token}`,
        'Content-Type': 'application/json',
      },
      timeout: '10s',
    });
  }

  const success = check(response, {
    'status is acceptable': (r) => r.status >= 200 && r.status < 500,
    'response time < 5s': (r) => r.timings.duration < 5000,
  });

  if (!success) {
    errorRate.add(1);
  }

  responseTime.add(response.timings.duration);

  // Random sleep to simulate real user behavior
  sleep(Math.random() * 2 + 0.5);
}

export function handleSummary(data) {
  return {
    'stdout': `
Stress Test Summary
===================
Total Requests: ${data.metrics.http_reqs.values.count}
Failed Requests: ${(data.metrics.http_req_failed.values.rate * 100).toFixed(2)}%
Average Response Time: ${data.metrics.http_req_duration.values.avg.toFixed(2)}ms
P95 Response Time: ${data.metrics.http_req_duration.values['p(95)'].toFixed(2)}ms
P99 Response Time: ${data.metrics.http_req_duration.values['p(99)'].toFixed(2)}ms
Max Response Time: ${data.metrics.http_req_duration.values.max.toFixed(2)}ms
Errors: ${(data.metrics.errors.values.rate * 100).toFixed(2)}%
    `,
    'stress-test-results.json': JSON.stringify(data),
  };
}

