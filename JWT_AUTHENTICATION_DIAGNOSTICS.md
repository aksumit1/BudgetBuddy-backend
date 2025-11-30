# JWT Authentication Diagnostics

## Issue
After successful registration, `/api/users/me` returns 401 Unauthorized even though a valid JWT token is sent.

## Root Cause Analysis

The JWT authentication filter validates tokens but logs failures at DEBUG level, making it impossible to diagnose why authentication is failing.

## Changes Made

### 1. Enhanced Logging in JwtAuthenticationFilter ✅
- Changed JWT validation failure logs from DEBUG → WARN level
- Added token length and preview to error messages
- Added logging when token is extracted from request
- Added warning when Authorization header doesn't start with "Bearer "

**File**: `src/main/java/com/budgetbuddy/security/JwtAuthenticationFilter.java`

### 2. Enhanced Logging in JwtTokenProvider ✅
- Changed JWT validation exception logs from ERROR → WARN level (more appropriate)
- Added token preview to all error messages
- Added specific handling for SignatureException (indicates secret mismatch)

**File**: `src/main/java/com/budgetbuddy/security/JwtTokenProvider.java`

## Diagnostic Information

With these changes, the logs will now show:
- ✅ When JWT token is extracted from request
- ✅ Token length and preview
- ✅ Specific validation failure reason (expired, malformed, signature mismatch, etc.)
- ✅ Whether Authorization header format is correct

## Common Causes of 401 After Registration

1. **JWT Secret Mismatch**
   - Token generated with one secret, validated with another
   - Check: Secrets Manager vs. environment variable
   - Log: "JWT signature verification failed"

2. **Token Format Issues**
   - Token contains control characters
   - Token not properly extracted from Authorization header
   - Log: "Invalid JWT token format" or "Authorization header does not start with 'Bearer '"

3. **User Not Found**
   - Token valid but user deleted/not found in database
   - Log: "JWT token valid but user not found"

4. **Token Expired**
   - Token expired immediately (unlikely but possible)
   - Log: "JWT token is expired"

## Next Steps

1. **Restart backend** to apply logging changes
2. **Reproduce the issue** (register, then call /api/users/me)
3. **Check logs** for WARN messages about JWT validation
4. **Identify root cause** from log messages
5. **Fix the specific issue** (secret mismatch, token format, etc.)

## Expected Log Output

After these changes, you should see logs like:
```
WARN JWT token validation failed | CorrelationId: xxx | Token length: 500
WARN JWT signature verification failed: ... | This may indicate a secret mismatch | Token preview: eyJhbGciOiJIUzUxMiJ9...
WARN Authorization header does not start with 'Bearer ' prefix | Header value preview: ...
```

This will help identify the exact cause of the 401 error.

