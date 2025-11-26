# Backend Testing Endpoints Reference

## Quick Reference for iOS Simulator Testing

### Base URL
- **Development**: `http://localhost:8080`
- **Production**: `https://api.budgetbuddy.com`

---

## Public Endpoints (No Authentication)

### Health Check
```http
GET /actuator/health
```

**Response**:
```json
{
  "status": "UP",
  "groups": ["liveness", "readiness"]
}
```

**Test**:
```bash
curl http://localhost:8080/actuator/health
```

---

### Register User
```http
POST /api/auth/register
Content-Type: application/json
```

**Request Body**:
```json
{
  "email": "user@example.com",
  "password_hash": "<client-side-hashed-password>",
  "salt": "<random-salt>"
}
```

**Response** (201 Created):
```json
{
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "refreshToken": "refresh_token_here",
  "expiresIn": 86400000,
  "user": {
    "userId": "user-id",
    "email": "user@example.com"
  }
}
```

**Test**:
```bash
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "email": "test@example.com",
    "password_hash": "hashed_password",
    "salt": "random_salt"
  }'
```

---

### Login
```http
POST /api/auth/login
Content-Type: application/json
```

**Request Body**:
```json
{
  "email": "user@example.com",
  "password_hash": "<client-side-hashed-password>",
  "salt": "<random-salt>"
}
```

**Response** (200 OK):
```json
{
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "refreshToken": "refresh_token_here",
  "expiresIn": 86400000,
  "user": {
    "userId": "user-id",
    "email": "user@example.com"
  }
}
```

**Test**:
```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "email": "test@example.com",
    "password_hash": "hashed_password",
    "salt": "random_salt"
  }'
```

---

## Protected Endpoints (Require JWT Token)

### Get Current User
```http
GET /api/users/me
Authorization: Bearer <jwt-token>
```

**Response** (200 OK):
```json
{
  "userId": "user-id",
  "email": "user@example.com",
  "firstName": "John",
  "lastName": "Doe",
  "emailVerified": true,
  "enabled": true
}
```

**Test**:
```bash
TOKEN="your-jwt-token-here"
curl http://localhost:8080/api/users/me \
  -H "Authorization: Bearer $TOKEN"
```

---

### List Transactions
```http
GET /api/transactions
Authorization: Bearer <jwt-token>
```

**Query Parameters**:
- `page` (optional): Page number (default: 0)
- `size` (optional): Page size (default: 20)
- `startDate` (optional): Start date (ISO format)
- `endDate` (optional): End date (ISO format)

**Response** (200 OK):
```json
{
  "content": [
    {
      "transactionId": "txn-id",
      "userId": "user-id",
      "amount": 100.50,
      "description": "Transaction description",
      "date": "2024-01-01T00:00:00Z",
      "category": "Food"
    }
  ],
  "totalElements": 1,
  "totalPages": 1,
  "currentPage": 0
}
```

**Test**:
```bash
TOKEN="your-jwt-token-here"
curl http://localhost:8080/api/transactions \
  -H "Authorization: Bearer $TOKEN"
```

---

### Create Transaction
```http
POST /api/transactions
Authorization: Bearer <jwt-token>
Content-Type: application/json
```

**Request Body**:
```json
{
  "amount": 100.50,
  "description": "Test transaction",
  "date": "2024-01-01T00:00:00Z",
  "category": "Food",
  "type": "EXPENSE"
}
```

**Response** (201 Created):
```json
{
  "transactionId": "txn-id",
  "userId": "user-id",
  "amount": 100.50,
  "description": "Test transaction",
  "date": "2024-01-01T00:00:00Z",
  "category": "Food",
  "type": "EXPENSE",
  "createdAt": "2024-01-01T00:00:00Z"
}
```

**Test**:
```bash
TOKEN="your-jwt-token-here"
curl -X POST http://localhost:8080/api/transactions \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "amount": 100.50,
    "description": "Test transaction",
    "date": "2024-01-01T00:00:00Z",
    "category": "Food",
    "type": "EXPENSE"
  }'
```

---

### List Accounts
```http
GET /api/accounts
Authorization: Bearer <jwt-token>
```

**Response** (200 OK):
```json
{
  "content": [
    {
      "accountId": "account-id",
      "userId": "user-id",
      "name": "Checking Account",
      "type": "CHECKING",
      "balance": 1000.00,
      "currency": "USD"
    }
  ],
  "totalElements": 1
}
```

---

### List Budgets
```http
GET /api/budgets
Authorization: Bearer <jwt-token>
```

**Response** (200 OK):
```json
{
  "content": [
    {
      "budgetId": "budget-id",
      "userId": "user-id",
      "category": "Food",
      "amount": 500.00,
      "period": "MONTHLY",
      "spent": 250.00
    }
  ]
}
```

---

### List Goals
```http
GET /api/goals
Authorization: Bearer <jwt-token>
```

**Response** (200 OK):
```json
{
  "content": [
    {
      "goalId": "goal-id",
      "userId": "user-id",
      "name": "Vacation Fund",
      "targetAmount": 5000.00,
      "currentAmount": 2000.00,
      "deadline": "2024-12-31T00:00:00Z"
    }
  ]
}
```

---

## Testing Workflow

### 1. Register a Test User
```bash
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "email": "test@example.com",
    "password_hash": "hashed_password",
    "salt": "random_salt"
  }'
```

### 2. Login to Get Token
```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "email": "test@example.com",
    "password_hash": "hashed_password",
    "salt": "random_salt"
  }'
```

### 3. Use Token for Authenticated Requests
```bash
TOKEN="<token-from-login-response>"
curl http://localhost:8080/api/users/me \
  -H "Authorization: Bearer $TOKEN"
```

---

## Error Responses

### 400 Bad Request
```json
{
  "error": "INVALID_INPUT",
  "message": "Validation failed",
  "timestamp": "2024-01-01T00:00:00Z"
}
```

### 401 Unauthorized
```json
{
  "error": "UNAUTHORIZED",
  "message": "Invalid or expired token",
  "timestamp": "2024-01-01T00:00:00Z"
}
```

### 404 Not Found
```json
{
  "error": "NOT_FOUND",
  "message": "Resource not found",
  "timestamp": "2024-01-01T00:00:00Z"
}
```

### 500 Internal Server Error
```json
{
  "error": "INTERNAL_SERVER_ERROR",
  "message": "An unexpected error occurred",
  "timestamp": "2024-01-01T00:00:00Z"
}
```

---

## Notes

1. **Password Hashing**: The backend expects client-side hashed passwords. The iOS app must hash passwords before sending.

2. **JWT Tokens**: Tokens expire after 24 hours. Use refresh token to get new access token.

3. **CORS**: In development, CORS allows all origins. In production, specific origins must be configured.

4. **Rate Limiting**: API has rate limiting (60 requests/minute, 1000 requests/hour per IP).

5. **Error Handling**: All errors follow the standard error response format.

---

## Quick Test Script

```bash
#!/bin/bash

BASE_URL="http://localhost:8080"

# Health check
echo "1. Health Check:"
curl -s "$BASE_URL/actuator/health" | jq .
echo ""

# Register (requires password hashing in real app)
echo "2. Register (manual - requires hashed password):"
echo "Use iOS app or hash password first"
echo ""

# Login (requires password hashing in real app)
echo "3. Login (manual - requires hashed password):"
echo "Use iOS app or hash password first"
echo ""

echo "For full testing, use the iOS app in simulator!"
```

---

**Status**: ✅ All endpoints ready for testing!

