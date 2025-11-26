# Backend Authentication Fixes Summary

## Issues Fixed

### 1. ✅ Invalid JWT Token Validation (iOS App)
**Problem**: iOS app was sending invalid tokens (like "mock-token") to protected endpoints, causing 401 errors.

**Fix**: Added token format validation in `AuthService.authorizedRequest()`:
- Validates tokens are proper JWT format (3 parts separated by dots)
- Rejects invalid tokens like "mock-token" or empty strings
- Automatically clears invalid tokens and logs out user

**File**: `BudgetBuddy/BudgetBuddy/ViewModels/AuthService.swift`

### 2. ✅ Registration Endpoint - JSON Field Mapping
**Problem**: Backend DTO expected `passwordHash` (camelCase) but iOS app sends `password_hash` (snake_case), causing:
```
Unrecognized field "password_hash" (class com.budgetbuddy.dto.AuthRequest)
```

**Fix**: Added `@JsonAlias("password_hash")` to `AuthRequest.passwordHash` field to accept both formats.

**File**: `BudgetBuddy-Backend/src/main/java/com/budgetbuddy/dto/AuthRequest.java`

### 3. ✅ Compilation Error Fix
**Problem**: `EnhancedGlobalExceptionHandler` had compilation error with `Enum::name` method reference.

**Fix**: Changed to lambda expression `method -> method.name()`.

**File**: `BudgetBuddy-Backend/src/main/java/com/budgetbuddy/exception/EnhancedGlobalExceptionHandler.java`

## Current Status

### Backend
- ✅ Rebuilt with fixes
- ✅ Registration endpoint accepts `password_hash`
- ✅ Health endpoint working
- ✅ CORS configured for development

### iOS App
- ✅ Token validation before sending requests
- ✅ Invalid tokens automatically cleared
- ✅ Login screen shows on first launch
- ✅ Logout available in Settings

## Testing

### Test Registration
```bash
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"test@example.com","password_hash":"hashedpass","salt":"salt123"}'
```

Expected: 201 Created with JWT token

### Test Login
```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"test@example.com","password_hash":"hashedpass","salt":"salt123"}'
```

Expected: 200 OK with JWT token

## Next Steps

1. **Test iOS App Registration**:
   - Open app in simulator
   - Should see login screen
   - Register new account
   - Should receive valid JWT token
   - Should navigate to main app

2. **Monitor Backend Logs**:
   ```bash
   docker-compose logs -f backend
   ```

3. **Verify No More Invalid Token Errors**:
   - Check logs for "Invalid JWT token" errors
   - Should only see these for truly invalid tokens (not "mock-token")

## Notes

- Backend now accepts both `passwordHash` and `password_hash` for compatibility
- iOS app validates tokens before sending (rejects "mock-token" and invalid formats)
- All authentication errors now properly redirect to login screen

