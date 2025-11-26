# Docker Compose Authentication Issues - Fixed

## Issues Found

### 1. Invalid JWT Token Errors
**Problem**: Backend logs show:
```
ERROR c.b.security.JwtTokenProvider - Invalid JWT token: Invalid compact JWT string: Compact JWSs must contain exactly 2 period characters, and compact JWEs must contain exactly 4. Found: 0
```

**Root Cause**: iOS app is sending invalid tokens (like "mock-token" or empty strings) when trying to access protected endpoints.

**Fix Applied**:
- Added token validation in `AuthService.authorizedRequest()` to reject invalid tokens
- Tokens must be valid JWT format (3 parts separated by dots)
- Invalid tokens are automatically cleared and user is logged out

### 2. Registration Endpoint Error
**Problem**: Registration fails with:
```
Unrecognized field "password_hash" (class com.budgetbuddy.dto.AuthRequest), not marked as ignorable
```

**Root Cause**: Backend DTO expects `passwordHash` (camelCase) but iOS app sends `password_hash` (snake_case).

**Fix Applied**:
- Added `@JsonAlias("password_hash")` to `AuthRequest.passwordHash` field
- Backend now accepts both `passwordHash` and `password_hash` formats

## Status

### ✅ Fixed
1. **Token Validation**: iOS app now validates tokens before sending
2. **DTO Compatibility**: Backend accepts both camelCase and snake_case

### ⚠️ Requires Rebuild
The backend needs to be rebuilt with the updated `AuthRequest.java`:

```bash
cd BudgetBuddy-Backend
mvn clean package -DskipTests
docker-compose build backend
docker-compose up -d backend
```

## Testing

After rebuild, test registration:
```bash
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"test@example.com","password_hash":"hashedpass","salt":"salt123"}'
```

Should return 201 Created with JWT token.

## Current Backend Status

- ✅ Backend is running
- ✅ Health endpoint works
- ⚠️ Registration endpoint needs rebuild (DTO fix)
- ✅ CORS is configured correctly
- ✅ Authentication endpoints are accessible

