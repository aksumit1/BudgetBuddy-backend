# JWT Authentication Fix - 401 Unauthorized After Registration

## Issue
After successful registration, `/api/users/me` returns 401 Unauthorized even though a valid JWT token is sent.

## Root Cause
The JWT secret was being fetched fresh on every token generation and validation call. This could lead to inconsistencies if:
1. Secrets Manager returned different values between calls
2. The fallback mechanism was triggered at different times
3. There was a race condition during secret retrieval

If the secret used to generate the token during registration was different from the secret used to validate the token when calling `/api/users/me`, validation would fail with a signature mismatch.

## Solution

### 1. JWT Secret Caching ✅
- **File**: `src/main/java/com/budgetbuddy/security/JwtTokenProvider.java`
- **Change**: Added caching for both the JWT secret and signing key
- **Implementation**: 
  - Used double-checked locking pattern for thread-safe caching
  - Secret is cached after first successful retrieval
  - Ensures consistency between token generation and validation
  - Both `cachedJwtSecret` and `cachedSigningKey` are cached to avoid repeated key generation

### 2. Enhanced Logging ✅
- **Files**: 
  - `JwtTokenProvider.java` - Added logging for secret source (Secrets Manager vs fallback)
  - `JwtAuthenticationFilter.java` - Added endpoint information to all log messages
  - `JwtAuthenticationEntryPoint.java` - Added detailed error logging with endpoint and exception details

### 3. Improved Error Messages ✅
- All authentication-related logs now include:
  - Endpoint being accessed
  - Correlation ID (if available)
  - Specific validation error type (expired, malformed, signature mismatch, etc.)

## Changes Made

### JwtTokenProvider.java
```java
// Added caching fields
private volatile String cachedJwtSecret = null;
private volatile SecretKey cachedSigningKey = null;
private final Object secretLock = new Object();

// Modified getSigningKey() to use cached key
// Modified getJwtSecret() to cache secret after first retrieval
```

### JwtAuthenticationFilter.java
- Added endpoint information to all log messages
- Improved warning messages to reference JwtTokenProvider logs for specific validation errors
- Added debug logging for protected endpoints accessed without Authorization header

### JwtAuthenticationEntryPoint.java
- Enhanced error logging to include:
  - Exception class name
  - Endpoint URI
  - Full exception message
  - Stack trace at DEBUG level

## Verification Steps

1. **Restart backend** to apply changes
2. **Register a new user** via `/api/auth/register`
3. **Call `/api/users/me`** with the returned access token
4. **Check logs** for:
   - `JWT secret loaded from Secrets Manager` or `JWT secret loaded from fallback`
   - `JWT signing key initialized and cached`
   - `JWT token validated successfully for user: ...`
   - `Successfully authenticated user: ...`

## Expected Behavior

### Successful Authentication
```
DEBUG JWT token extracted from request | CorrelationId: xxx | Token length: 500 | Endpoint: /api/users/me
DEBUG JWT token validated successfully for user: user@example.com | CorrelationId: xxx | Endpoint: /api/users/me
DEBUG Successfully authenticated user: user@example.com | CorrelationId: xxx | Endpoint: /api/users/me
```

### Failed Authentication (with detailed error)
```
WARN JWT token validation failed | CorrelationId: xxx | Token length: 500 | Endpoint: /api/users/me | Check JwtTokenProvider logs for specific validation error
WARN JWT signature verification failed: ... | This may indicate a secret mismatch | Token preview: eyJhbGciOiJIUzUxMiJ9...
ERROR Unauthorized error: InsufficientAuthenticationException | Endpoint: /api/users/me | Message: Full authentication is required to access this resource
```

## Common Causes of 401 After Registration (Now Easier to Diagnose)

1. **JWT Secret Mismatch** (Most Likely - Now Fixed)
   - **Before**: Secret could differ between token generation and validation
   - **After**: Secret is cached, ensuring consistency
   - **Log**: `JWT signature verification failed: ... | This may indicate a secret mismatch`

2. **Token Format Issues**
   - Token contains control characters
   - Token not properly extracted from Authorization header
   - **Log**: `Invalid JWT token format: ...` or `Authorization header does not start with 'Bearer ' prefix`

3. **User Not Found**
   - Token valid but user deleted/not found in database
   - **Log**: `JWT token valid but user not found: ...`

4. **Token Expired**
   - Token expired immediately (unlikely but possible)
   - **Log**: `JWT token is expired: ...`

## Testing

The fix ensures that:
- ✅ JWT secret is consistent between token generation and validation
- ✅ Detailed logging helps diagnose authentication issues
- ✅ Thread-safe caching prevents race conditions
- ✅ Better error messages help identify root causes

## Notes

- The cached secret is stored in memory and persists for the lifetime of the application
- If the secret needs to be refreshed (e.g., after rotation), the application must be restarted
- For production, consider implementing secret rotation with application restart or a more sophisticated caching strategy with TTL

