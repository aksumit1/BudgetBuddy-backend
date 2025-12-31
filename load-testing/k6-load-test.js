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
  const token = data.tokens[user.email] || '';

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
    'transactions status is 200 or 401': (r) => r.status === 200 || r.status === 401,
    'transactions response time < 500ms': (r) => r.timings.duration < 500,
  });
  errorRate.add(transactionsRes.status >= 500);
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
    'accounts status is 200 or 401': (r) => r.status === 200 || r.status === 401,
    'accounts response time < 500ms': (r) => r.timings.duration < 500,
  });
  errorRate.add(accountsRes.status >= 500);
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
    'budgets status is 200 or 401': (r) => r.status === 200 || r.status === 401,
  });
  errorRate.add(budgetsRes.status >= 500);
  responseTime.add(budgetsRes.timings.duration);
  sleep(0.5);

  // Test 5: Get Goals
  const goalsRes = http.get(`${BASE_URL}/api/goals`, {
    headers: {
      'Authorization': `Bearer ${token}`,
      'Content-Type': 'application/json',
    },
  });
  check(goalsRes, {
    'goals status is 200 or 401': (r) => r.status === 200 || r.status === 401,
  });
  errorRate.add(goalsRes.status >= 500);
  responseTime.add(goalsRes.timings.duration);
  sleep(0.5);

  // Test 6: Get Current User
  const userRes = http.get(`${BASE_URL}/api/users/me`, {
    headers: {
      'Authorization': `Bearer ${token}`,
      'Content-Type': 'application/json',
    },
  });
  check(userRes, {
    'user status is 200 or 401': (r) => r.status === 200 || r.status === 401,
  });
  errorRate.add(userRes.status >= 500);
  responseTime.add(userRes.timings.duration);
  sleep(0.5);

  // Test 7: Get Analytics Spending Summary
  const analyticsRes = http.get(`${BASE_URL}/api/analytics/spending-summary`, {
    headers: {
      'Authorization': `Bearer ${token}`,
      'Content-Type': 'application/json',
    },
  });
  check(analyticsRes, {
    'analytics status is 200 or 401': (r) => r.status === 200 || r.status === 401,
  });
  errorRate.add(analyticsRes.status >= 500);
  responseTime.add(analyticsRes.timings.duration);
  sleep(0.5);

  // Test 8: Get Subscriptions
  const subscriptionsRes = http.get(`${BASE_URL}/api/subscriptions`, {
    headers: {
      'Authorization': `Bearer ${token}`,
      'Content-Type': 'application/json',
    },
  });
  check(subscriptionsRes, {
    'subscriptions status is 200 or 401': (r) => r.status === 200 || r.status === 401,
  });
  errorRate.add(subscriptionsRes.status >= 500);
  responseTime.add(subscriptionsRes.timings.duration);
  sleep(0.5);

  // Test 9: Get Sync All
  const syncRes = http.get(`${BASE_URL}/api/sync/all`, {
    headers: {
      'Authorization': `Bearer ${token}`,
      'Content-Type': 'application/json',
    },
  });
  check(syncRes, {
    'sync status is 200 or 401': (r) => r.status === 200 || r.status === 401,
  });
  errorRate.add(syncRes.status >= 500);
  responseTime.add(syncRes.timings.duration);
  sleep(0.5);

  // Test 10: GDPR Export (if authenticated)
  if (token) {
    const gdprRes = http.get(`${BASE_URL}/api/compliance/gdpr/export`, {
      headers: {
        'Authorization': `Bearer ${token}`,
        'Content-Type': 'application/json',
      },
    });
    check(gdprRes, {
      'gdpr export status is 200 or 401 or 403': (r) => r.status === 200 || r.status === 401 || r.status === 403,
    });
    errorRate.add(gdprRes.status >= 500);
    responseTime.add(gdprRes.timings.duration);
    sleep(0.5);
  }

  // Test 11: DMA Export (if authenticated)
  if (token) {
    const dmaRes = http.get(`${BASE_URL}/api/dma/export?format=JSON`, {
      headers: {
        'Authorization': `Bearer ${token}`,
        'Content-Type': 'application/json',
      },
    });
    check(dmaRes, {
      'dma export status is 200 or 401': (r) => r.status === 200 || r.status === 401,
    });
    errorRate.add(dmaRes.status >= 500);
    responseTime.add(dmaRes.timings.duration);
    sleep(0.5);
  }

  // Test 12: FIDO2 Register Challenge (if authenticated)
  if (token) {
    const fido2Res = http.post(`${BASE_URL}/api/fido2/register/challenge`, JSON.stringify({}), {
      headers: {
        'Authorization': `Bearer ${token}`,
        'Content-Type': 'application/json',
      },
    });
    check(fido2Res, {
      'fido2 challenge status is 200 or 401': (r) => r.status === 200 || r.status === 401,
    });
    errorRate.add(fido2Res.status >= 500);
    responseTime.add(fido2Res.timings.duration);
    sleep(0.5);
  }

  // Test 13: OAuth2 User Info (if authenticated)
  if (token) {
    const oauth2Res = http.get(`${BASE_URL}/api/oauth2/userinfo`, {
      headers: {
        'Authorization': `Bearer ${token}`,
        'Content-Type': 'application/json',
      },
    });
    check(oauth2Res, {
      'oauth2 userinfo status is 200 or 401': (r) => r.status === 200 || r.status === 401,
    });
    errorRate.add(oauth2Res.status >= 500);
    responseTime.add(oauth2Res.timings.duration);
    sleep(0.5);
  }

  // Test 14: Transaction Sync Status (if authenticated)
  if (token) {
    const syncStatusRes = http.get(`${BASE_URL}/api/transactions/sync/status`, {
      headers: {
        'Authorization': `Bearer ${token}`,
        'Content-Type': 'application/json',
      },
    });
    check(syncStatusRes, {
      'sync status endpoint status is 200 or 401': (r) => r.status === 200 || r.status === 401,
    });
    errorRate.add(syncStatusRes.status >= 500);
    responseTime.add(syncStatusRes.timings.duration);
    sleep(0.5);
  }
}

export function teardown(data) {
  console.log('Load test completed');
}

