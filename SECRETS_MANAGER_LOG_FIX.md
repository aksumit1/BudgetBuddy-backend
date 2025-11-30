# Secrets Manager Log Noise Fix

## Problem
The backend was logging ERROR messages every time it tried to fetch secrets from AWS Secrets Manager that don't exist in LocalStack:
```
ERROR c.b.a.secrets.SecretsManagerService - Error fetching secret budgetbuddy/jwt-secret from AWS Secrets Manager: Secrets Manager can't find the specified secret.
```

While the service correctly falls back to environment variables, the ERROR logs create noise in local development.

## Root Cause
In local development with LocalStack, secrets are not pre-created. The service tries to fetch them from Secrets Manager, gets a 400 error (secret not found), and logs it as an ERROR before falling back to environment variables.

## Solution
Added LocalStack detection and conditional logging:
- **LocalStack + Missing Secret**: Log at DEBUG level (expected behavior)
- **Production + Missing Secret**: Log at ERROR level (actual problem)
- **Other Errors**: Always log at ERROR level

## Changes Made

### SecretsManagerService.java
1. **Added LocalStack detection**:
   ```java
   private boolean isLocalStack() {
       return secretsManagerEndpoint != null && 
              (secretsManagerEndpoint.contains("localstack") || 
               secretsManagerEndpoint.contains(":4566") ||
               secretsManagerEndpoint.contains("localhost:4566"));
   }
   ```

2. **Conditional error logging**:
   ```java
   boolean isMissingSecret = e.statusCode() == 400 && e.getMessage() != null && 
       (e.getMessage().contains("can't find the specified secret") || 
        e.getMessage().contains("ResourceNotFoundException"));
   
   if (isLocalStack() && isMissingSecret) {
       // LocalStack - secret doesn't exist (expected), log at DEBUG level
       logger.debug("Secret {} not found in LocalStack (this is expected in local development). " +
               "Using environment variable {} as fallback.", secretName, envVarFallback);
   } else {
       // Production or other error - log at ERROR level
       logger.error("Error fetching secret {} from AWS Secrets Manager: {}", secretName, e.getMessage());
   }
   ```

3. **Reduced INFO log noise**:
   - Only log INFO message about using environment variable fallback if not LocalStack or if it's not a missing secret error

## Result

### Before
```
ERROR c.b.a.secrets.SecretsManagerService - Error fetching secret budgetbuddy/jwt-secret from AWS Secrets Manager: Secrets Manager can't find the specified secret.
INFO  c.b.a.secrets.SecretsManagerService - Using environment variable JWT_SECRET as fallback for secret budgetbuddy/jwt-secret
```

### After (LocalStack)
```
DEBUG c.b.a.secrets.SecretsManagerService - Secret budgetbuddy/jwt-secret not found in LocalStack (this is expected in local development). Using environment variable JWT_SECRET as fallback.
```

### After (Production)
```
ERROR c.b.a.secrets.SecretsManagerService - Error fetching secret budgetbuddy/jwt-secret from AWS Secrets Manager: Secrets Manager can't find the specified secret.
INFO  c.b.a.secrets.SecretsManagerService - Using environment variable JWT_SECRET as fallback for secret budgetbuddy/jwt-secret
```

## Benefits
- ✅ Reduced log noise in local development
- ✅ Still logs errors appropriately in production
- ✅ Clear distinction between expected (LocalStack) and unexpected (production) errors
- ✅ Fallback to environment variables still works correctly

## Note
This follows the same pattern used in `AppConfigIntegration` for handling LocalStack limitations gracefully.

