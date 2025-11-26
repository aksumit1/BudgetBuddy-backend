# Docker Compose Troubleshooting Guide

## Current Issues and Fixes

### ✅ Issue 1: Invalid JWT Token Errors
**Symptoms**: Backend logs show:
```
ERROR c.b.security.JwtTokenProvider - Invalid JWT token: Invalid compact JWT string: Compact JWSs must contain exactly 2 period characters
```

**Root Cause**: iOS app sending invalid tokens (like "mock-token") to protected endpoints.

**Fix Applied**:
- ✅ Added token validation in iOS `AuthService.authorizedRequest()`
- ✅ Rejects tokens that aren't valid JWT format (3 parts separated by dots)
- ✅ Invalid tokens are automatically cleared

### ✅ Issue 2: Registration Endpoint - JSON Field Mapping
**Symptoms**: Registration fails with:
```
Unrecognized field "password_hash" (class com.budgetbuddy.dto.AuthRequest)
```

**Root Cause**: Backend DTO expected `passwordHash` (camelCase) but iOS sends `password_hash` (snake_case).

**Fix Applied**:
- ✅ Added `@JsonAlias("password_hash")` to `AuthRequest.passwordHash`
- ✅ Backend now accepts both formats
- ✅ Backend rebuilt with fix

### ✅ Issue 3: Compilation Error
**Symptoms**: Maven build fails with:
```
incompatible types: cannot infer type-variable(s) R
```

**Root Cause**: `Enum::name` method reference issue in `EnhancedGlobalExceptionHandler`.

**Fix Applied**:
- ✅ Changed to lambda: `method -> method.name()`
- ✅ Backend compiles successfully

## Testing Backend

### 1. Check Backend Status
```bash
cd BudgetBuddy-Backend
docker-compose ps
docker-compose logs --tail=50 backend
```

### 2. Test Health Endpoint
```bash
curl http://localhost:8080/actuator/health
# Should return: {"status":"UP"}
```

### 3. Test Registration
```bash
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"test@example.com","password_hash":"hashedpass","salt":"salt123"}'
```

**Expected**: 201 Created with JWT token

### 4. Test Login
```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"test@example.com","password_hash":"hashedpass","salt":"salt123"}'
```

**Expected**: 200 OK with JWT token

## Common Issues

### Backend Not Starting
```bash
# Check logs
docker-compose logs backend

# Restart
docker-compose restart backend

# Rebuild if needed
docker-compose build backend
docker-compose up -d backend
```

### Port Already in Use
```bash
# Check what's using port 8080
lsof -i :8080

# Stop conflicting service or change port in docker-compose.yml
```

### LocalStack Not Working
```bash
# Check LocalStack health
curl http://localhost:4566/_localstack/health

# Restart LocalStack
docker-compose restart localstack
```

### Redis Connection Issues
```bash
# Check Redis
docker-compose exec redis redis-cli ping
# Should return: PONG

# Restart Redis
docker-compose restart redis
```

## Monitoring

### Watch Backend Logs
```bash
docker-compose logs -f backend
```

### Watch All Services
```bash
docker-compose logs -f
```

### Check Service Health
```bash
docker-compose ps
```

## Next Steps

1. **Test iOS App Registration**:
   - Open app in simulator
   - Should see login screen
   - Register new account
   - Should work now with `password_hash` support

2. **Monitor for Errors**:
   - Watch backend logs for any remaining issues
   - Check iOS app logs for network errors

3. **Verify Token Flow**:
   - Registration should return valid JWT
   - Login should return valid JWT
   - Protected endpoints should accept JWT

---

**Status**: ✅ Backend rebuilt with all fixes. Ready for testing!

