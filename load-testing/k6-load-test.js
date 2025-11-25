import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';

// Custom metrics
const errorRate = new Rate('errors');
const responseTime = new Trend('response_time');

// Test configuration
export const options = {
  stages: [
    { duration: '2m', target: 100 },  // Ramp up to 100 users
    { duration: '5m', target: 100 },   // Stay at 100 users
    { duration: '2m', target: 200 },   // Ramp up to 200 users
    { duration: '5m', target: 200 },  // Stay at 200 users
    { duration: '2m', target: 0 },    // Ramp down to 0 users
  ],
  thresholds: {
    http_req_duration: ['p(95)<500', 'p(99)<1000'], // 95% of requests < 500ms, 99% < 1s
    http_req_failed: ['rate<0.01'],                 // Error rate < 1%
    errors: ['rate<0.01'],
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// Test data
const testUsers = [
  { email: 'user1@test.com', password: 'password123' },
  { email: 'user2@test.com', password: 'password123' },
  { email: 'user3@test.com', password: 'password123' },
];

let authTokens = {};

export function setup() {
  // Pre-authenticate users
  console.log('Setting up test users...');
  for (const user of testUsers) {
    const registerRes = http.post(`${BASE_URL}/api/auth/register`, JSON.stringify({
      email: user.email,
      password: user.password,
      firstName: 'Test',
      lastName: 'User',
    }), {
      headers: { 'Content-Type': 'application/json' },
    });
    
    if (registerRes.status === 200) {
      const body = JSON.parse(registerRes.body);
      authTokens[user.email] = body.token;
    }
  }
  return { tokens: authTokens };
}

export default function (data) {
  const user = testUsers[Math.floor(Math.random() * testUsers.length)];
  const token = data.tokens[user.email];

  // Test 1: Health Check
  const healthCheck = http.get(`${BASE_URL}/actuator/health`);
  check(healthCheck, {
    'health check status is 200': (r) => r.status === 200,
  });
  errorRate.add(healthCheck.status !== 200);
  responseTime.add(healthCheck.timings.duration);

  sleep(1);

  // Test 2: Get Transactions
  const transactionsRes = http.get(`${BASE_URL}/api/transactions`, {
    headers: {
      'Authorization': `Bearer ${token}`,
      'Content-Type': 'application/json',
    },
  });
  check(transactionsRes, {
    'transactions status is 200': (r) => r.status === 200,
    'transactions response time < 500ms': (r) => r.timings.duration < 500,
  });
  errorRate.add(transactionsRes.status !== 200);
  responseTime.add(transactionsRes.timings.duration);

  sleep(1);

  // Test 3: Get Accounts
  const accountsRes = http.get(`${BASE_URL}/api/accounts`, {
    headers: {
      'Authorization': `Bearer ${token}`,
      'Content-Type': 'application/json',
    },
  });
  check(accountsRes, {
    'accounts status is 200': (r) => accountsRes.status === 200,
    'accounts response time < 500ms': (r) => accountsRes.timings.duration < 500,
  });
  errorRate.add(accountsRes.status !== 200);
  responseTime.add(accountsRes.timings.duration);

  sleep(1);

  // Test 4: Get Budgets
  const budgetsRes = http.get(`${BASE_URL}/api/budgets`, {
    headers: {
      'Authorization': `Bearer ${token}`,
      'Content-Type': 'application/json',
    },
  });
  check(budgetsRes, {
    'budgets status is 200': (r) => budgetsRes.status === 200,
  });
  errorRate.add(budgetsRes.status !== 200);
  responseRate.add(budgetsRes.timings.duration);

  sleep(1);
}

export function teardown(data) {
  console.log('Load test completed');
}

