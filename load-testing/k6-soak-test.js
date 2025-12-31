import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';

// Custom metrics
const errorRate = new Rate('errors');
const responseTime = new Trend('response_time');
const memoryLeakIndicator = new Counter('memory_leak_indicators');

// Soak test - long-duration stability test (detect memory leaks)
// Run for extended period to detect gradual degradation
export const options = {
  stages: [
    { duration: '5m', target: 50 },   // Ramp up to 50 users
    { duration: '2h', target: 50 },     // Stay at 50 users for 2 hours (soak period)
    { duration: '5m', target: 0 },    // Ramp down
  ],
  thresholds: {
    http_req_duration: ['p(95)<1000'], // Response time should remain stable
    http_req_failed: ['rate<0.01'],    // Error rate should remain low
    errors: ['rate<0.01'],
    // Memory leak detection: response time should not gradually increase
    'http_req_duration{type:soak}': ['p(95)<1000'],
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// Test data
const testUsers = [
  { email: 'soak1@test.com', passwordHash: 'test-hash-1' },
  { email: 'soak2@test.com', passwordHash: 'test-hash-2' },
  { email: 'soak3@test.com', passwordHash: 'test-hash-3' },
];

let authTokens = {};
let requestCount = 0;

export function setup() {
  // Pre-authenticate users
  console.log('Setting up soak test users...');
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
  return { tokens: authTokens, startTime: Date.now() };
}

export default function (data) {
  const user = testUsers[Math.floor(Math.random() * testUsers.length)];
  const token = data.tokens[user.email] || '';
  requestCount++;

  // Mix of endpoints to test long-term stability
  const endpoints = [
    { path: '/api/transactions', method: 'GET' },
    { path: '/api/accounts', method: 'GET' },
    { path: '/api/budgets', method: 'GET' },
    { path: '/api/goals', method: 'GET' },
    { path: '/api/users/me', method: 'GET' },
    { path: '/api/analytics/spending-summary', method: 'GET' },
    { path: '/api/subscriptions', method: 'GET' },
    { path: '/api/sync/all', method: 'GET' },
    { path: '/actuator/health', method: 'GET' },
  ];

  const endpoint = endpoints[Math.floor(Math.random() * endpoints.length)];
  const url = `${BASE_URL}${endpoint.path}`;

  const response = http.get(url, {
    headers: {
      'Authorization': `Bearer ${token}`,
      'Content-Type': 'application/json',
    },
    timeout: '10s',
    tags: { type: 'soak' },
  });

  const success = check(response, {
    'status is 200 or 401': (r) => r.status === 200 || r.status === 401,
    'response time < 2s': (r) => r.timings.duration < 2000,
  });

  if (!success) {
    errorRate.add(1);
  }

  // Track response time trends - if it gradually increases, might indicate memory leak
  const duration = response.timings.duration;
  responseTime.add(duration);

  // Log every 1000 requests to track trends
  if (requestCount % 1000 === 0) {
    console.log(`Soak test progress: ${requestCount} requests completed`);
  }

  // Detect potential memory leaks: if response time is consistently high after many requests
  if (requestCount > 10000 && duration > 2000) {
    memoryLeakIndicator.add(1);
  }

  // Realistic user behavior: random sleep between 1-3 seconds
  sleep(Math.random() * 2 + 1);
}

export function handleSummary(data) {
  const duration = (Date.now() - data.state.testRunDurationMs) / 1000 / 60;
  
  return {
    'stdout': `
Soak Test Summary
=================
Test Duration: ${duration.toFixed(2)} minutes
Total Requests: ${data.metrics.http_reqs.values.count}
Failed Requests: ${(data.metrics.http_req_failed.values.rate * 100).toFixed(2)}%
Average Response Time: ${data.metrics.http_req_duration.values.avg.toFixed(2)}ms
P95 Response Time: ${data.metrics.http_req_duration.values['p(95)'].toFixed(2)}ms
P99 Response Time: ${data.metrics.http_req_duration.values['p(99)'].toFixed(2)}ms
Max Response Time: ${data.metrics.http_req_duration.values.max.toFixed(2)}ms
Errors: ${(data.metrics.errors.values.rate * 100).toFixed(2)}%
Memory Leak Indicators: ${data.metrics.memory_leak_indicators.values.count}

Note: If response times gradually increased over time, this may indicate memory leaks.
    `,
    'soak-test-results.json': JSON.stringify(data),
  };
}

