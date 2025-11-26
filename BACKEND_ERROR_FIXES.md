# Backend Error Fixes

## Issues Fixed

### 1. ClassCastException in JWT Token Generation
**Problem**: `JwtTokenProvider.generateToken()` was trying to cast a `String` (email) to `UserDetails`, causing `ClassCastException`.

**Root Cause**: In `AuthService.authenticate()`, the `Authentication` object was created with `user.getEmail()` (a String) as the principal, but `JwtTokenProvider.generateToken()` expected a `UserDetails` object.

**Fix**: Updated `AuthService.authenticate()` to create a proper `UserDetails` object and use it as the principal in the `Authentication` object.

**File**: `src/main/java/com/budgetbuddy/service/AuthService.java`

### 2. JWT Secret Too Short for HS512
**Problem**: JWT secret was only 32 characters (256 bits), but HS512 algorithm requires at least 512 bits (64 characters).

**Error**: `SignatureException: The signing key's size is 256 bits which is not secure enough for the HS512 algorithm`

**Fix**: Updated `docker-compose.yml` to use a longer JWT secret (at least 64 characters).

**File**: `docker-compose.yml`

### 3. SpEL Evaluation Error in Cache Annotation
**Problem**: `@Cacheable` annotation had an invalid `unless` condition that tried to call `isPresent()` on the result, causing `SpelEvaluationException`.

**Error**: `Method call: Method isPresent() cannot be found on type com.budgetbuddy.model.dynamodb.UserTable`

**Fix**: Simplified the `unless` condition from `unless = "#result == null || !#result.isPresent()"` to `unless = "#result == null"`.

**File**: `src/main/java/com/budgetbuddy/repository/dynamodb/UserRepository.java`

### 4. Error Logging Level
**Problem**: Business logic errors (like `USER_ALREADY_EXISTS`) were being logged at ERROR level, filling logs unnecessarily.

**Fix**: Added `isBusinessLogicError()` method to distinguish business logic errors from system errors. Business logic errors now log at WARN level.

**File**: `src/main/java/com/budgetbuddy/exception/EnhancedGlobalExceptionHandler.java`

## Testing

After these fixes:
1. ✅ New users can register successfully
2. ✅ Duplicate registrations return proper error (400 with USER_ALREADY_EXISTS)
3. ✅ Errors are logged at appropriate levels (WARN for business logic, ERROR for system errors)
4. ✅ JWT tokens are generated correctly
5. ✅ No more ClassCastException or SpEL errors

## Status
✅ **All fixes applied and tested**

