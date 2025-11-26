# Plaid Redirect URI Debug Guide

## Issue
Plaid is reporting: "We noticed that your redirect_uri is missing"

## Current Implementation

The code in `PlaidService.java` sets the redirect URI:
```java
request.redirectUri(finalRedirectUri);
logger.info("✅ Setting redirect URI for Plaid Link token: {}", finalRedirectUri);
```

## Verification Steps

### 1. Check Backend Logs
Look for this log message when creating a link token:
```
✅ Setting redirect URI for Plaid Link token: https://app.budgetbuddy.com/plaid/callback
```

If you don't see this message, the redirect URI is not being set.

### 2. Verify Configuration

Check `application.yml`:
```yaml
app:
  plaid:
    redirect-uri: ${PLAID_REDIRECT_URI:https://app.budgetbuddy.com/plaid/callback}
```

Check `docker-compose.yml`:
```yaml
environment:
  - PLAID_REDIRECT_URI=${PLAID_REDIRECT_URI:-https://app.budgetbuddy.com/plaid/callback}
```

### 3. Verify Plaid Dashboard

**CRITICAL**: The redirect URI must be registered in Plaid Dashboard:

1. Go to [Plaid Dashboard](https://dashboard.plaid.com/)
2. Navigate to **Team Settings** → **API** → **Allowed redirect URIs**
3. Ensure `https://app.budgetbuddy.com/plaid/callback` is listed
4. Click **Save**

### 4. Test the Request

After making a link token request, check the logs:
```bash
docker-compose logs backend | grep -i "redirect\|link token"
```

You should see:
- "✅ Setting redirect URI for Plaid Link token: ..."
- "Creating Plaid link token - Redirect URI: '...'"

### 5. Verify Plaid SDK Method

The Plaid Java SDK uses:
```java
request.redirectUri(uri);
```

If this doesn't work, check:
- Plaid SDK version compatibility
- Method name (might be `setRedirectUri` in some versions)

## Common Issues

### Issue 1: Redirect URI Not Set in Code
**Symptom**: No "Setting redirect URI" log message
**Solution**: Rebuild backend to include latest code changes

### Issue 2: Redirect URI Not in Plaid Dashboard
**Symptom**: Plaid rejects the request even though URI is set
**Solution**: Add redirect URI to Plaid Dashboard → Team Settings → API → Allowed redirect URIs

### Issue 3: Wrong Redirect URI Format
**Symptom**: Plaid accepts but OAuth doesn't work
**Solution**: 
- Must be HTTPS (not HTTP)
- Must match exactly what's in Plaid Dashboard
- Must be accessible (for Universal Links)

### Issue 4: Environment Mismatch
**Symptom**: Works in one environment but not another
**Solution**: 
- Check `PLAID_ENVIRONMENT` matches Plaid Dashboard environment
- Ensure redirect URI is registered for the correct environment (sandbox/development/production)

## Next Steps

1. **Rebuild Backend**: `docker-compose build backend`
2. **Restart Backend**: `docker-compose restart backend`
3. **Check Logs**: Look for "Setting redirect URI" message
4. **Verify Plaid Dashboard**: Ensure redirect URI is registered
5. **Test Link Token Creation**: Make a request and check for errors

## Debugging Commands

```bash
# Check if redirect URI is being set
docker-compose logs backend | grep -i "redirect"

# Check Plaid API errors
docker-compose logs backend | grep -i "Plaid API error"

# Test link token creation
curl -X POST http://localhost:8080/api/plaid/link-token \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -H "Content-Type: application/json"
```

