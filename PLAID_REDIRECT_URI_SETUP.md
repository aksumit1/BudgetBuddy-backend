# Plaid Redirect URI Setup Guide

## Issue
Plaid is reporting that `redirect_uri` is missing when creating link tokens.

## Solution Applied
The backend code has been updated to **always set `redirect_uri`** when creating link tokens, regardless of environment.

## Configuration

### 1. Backend Configuration
The `redirect_uri` is now configurable via environment variable:

```yaml
app:
  plaid:
    redirect-uri: ${PLAID_REDIRECT_URI:https://app.budgetbuddy.com/plaid/callback}
```

### 2. Default Values
- **Production**: `https://app.budgetbuddy.com/plaid/callback`
- **Development**: `https://dev.budgetbuddy.com/plaid/callback`
- **Sandbox**: `https://app.budgetbuddy.com/plaid/callback` (or configure via env var)

### 3. Plaid Dashboard Configuration
**IMPORTANT**: The `redirect_uri` must be **configured in your Plaid Dashboard**:

1. Log in to [Plaid Dashboard](https://dashboard.plaid.com/)
2. Go to **Team Settings** → **API** → **Allowed redirect URIs**
3. Add your redirect URI(s):
   - For production: `https://app.budgetbuddy.com/plaid/callback`
   - For development: `https://dev.budgetbuddy.com/plaid/callback`
   - For sandbox: `https://app.budgetbuddy.com/plaid/callback` (or your configured URI)
4. Save the configuration

### 4. Environment Variable
Set the redirect URI via environment variable if different from defaults:

```bash
export PLAID_REDIRECT_URI=https://your-domain.com/plaid/callback
```

Or in `docker-compose.yml`:
```yaml
environment:
  - PLAID_REDIRECT_URI=https://your-domain.com/plaid/callback
```

## Code Changes

The `PlaidService.createLinkToken()` method now:
1. ✅ Always sets `redirect_uri` (previously only set for non-sandbox)
2. ✅ Uses configured value from `app.plaid.redirect-uri` if provided
3. ✅ Falls back to environment-specific defaults if not configured
4. ✅ Logs the redirect URI being used for debugging

## Verification

After updating:
1. **Check backend logs** for "Setting redirect URI: ..." message
2. **Verify in Plaid Dashboard** that the redirect URI is whitelisted
3. **Test link token creation** - should no longer get "redirect_uri is missing" error

## Troubleshooting

### Error: "redirect_uri is missing"
- ✅ **Fixed**: Code now always sets redirect_uri
- ⚠️ **Check**: Ensure redirect_uri matches what's configured in Plaid Dashboard
- ⚠️ **Check**: Verify environment variable is set correctly

### Error: "redirect_uri is not allowed"
- **Solution**: Add the redirect URI to Plaid Dashboard → Team Settings → API → Allowed redirect URIs

### Error: "redirect_uri format is invalid"
- **Solution**: Ensure redirect URI is a valid HTTPS URL (HTTP not allowed in production)
- Format: `https://your-domain.com/path/to/callback`

## Next Steps

1. **Configure in Plaid Dashboard** (most important)
2. **Set environment variable** if using custom redirect URI
3. **Restart backend** to pick up configuration changes
4. **Test link token creation** - error should be resolved

