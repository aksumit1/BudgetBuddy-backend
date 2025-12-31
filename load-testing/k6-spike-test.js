import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';

// Custom metrics
const errorRate = new Rate('errors');
const responseTime = new Trend('response_time');

// Spike test - sudden traffic surges
export const options = {
  stages: [
    { duration: '30s', target: 10 },   // Normal load: 10 users
    { duration: '10s', target: 1000 }, // SPIKE: Sudden surge to 1000 users
    { duration: '1m', target: 1000 },  // Hold spike for 1 minute
    { duration: '10s', target: 10 },    // Drop back to normal
    { duration: '30s', target: 10 },   // Normal load
    { duration: '10s', target: 500 },  // Another spike: 500 users
    { duration: '30s', target: 500 },  // Hold spike
    { duration: '10s', target: 10 },    // Drop back
    { duration: '30s', target: 0 },    // Ramp down
  ],
  thresholds: {
    http_req_duration: ['p(95)<3000', 'p(99)<10000'], // Allow higher latency during spikes
    http_req_failed: ['rate<0.15'],                    // Allow up to 15% errors during spikes
    errors: ['rate<0.15'],
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// Test data
const testUsers = [
  { email: 'spike1@test.com', passwordHash: 'test-hash-1' },
  { email: 'spike2@test.com', passwordHash: 'test-hash-2' },
  { email: 'spike3@test.com', passwordHash: 'test-hash-3' },
];

let authTokens = {};

export function setup() {
  // Pre-authenticate users
  console.log('Setting up spike test users...');
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

  // Mix of endpoints to test spike handling
  const endpoints = [
    '/api/transactions',
    '/api/accounts',
    '/api/budgets',
    '/api/goals',
    '/api/users/me',
    '/actuator/health',
  ];

  const endpoint = endpoints[Math.floor(Math.random() * endpoints.length)];
  const url = `${BASE_URL}${endpoint}`;

  const response = http.get(url, {
    headers: {
      'Authorization': `Bearer ${token}`,
      'Content-Type': 'application/json',
    },
    timeout: '15s',
  });

  const success = check(response, {
    'status is acceptable': (r) => r.status >= 200 && r.status < 500,
    'response received': (r) => r.status !== 0,
  });

  if (!success) {
    errorRate.add(1);
  }

  responseTime.add(response.timings.duration);

  // Short sleep during spikes
  sleep(0.1);
}

export function handleSummary(data) {
  return {
    'stdout': `
Spike Test Summary
==================
Total Requests: ${data.metrics.http_reqs.values.count}
Failed Requests: ${(data.metrics.http_req_failed.values.rate * 100).toFixed(2)}%
Average Response Time: ${data.metrics.http_req_duration.values.avg.toFixed(2)}ms
P95 Response Time: ${data.metrics.http_req_duration.values['p(95)'].toFixed(2)}ms
P99 Response Time: ${data.metrics.http_req_duration.values['p(99)'].toFixed(2)}ms
Max Response Time: ${data.metrics.http_req_duration.values.max.toFixed(2)}ms
Errors: ${(data.metrics.errors.values.rate * 100).toFixed(2)}%
    `,
    'spike-test-results.json': JSON.stringify(data),
  };
}

