# Account Sync Debugging

## Problem
Accounts and transactions from Plaid are not showing in the iOS app.

## Root Cause Analysis

### Backend Response Issue
Looking at the backend logs, accounts are being returned but with problematic data:

```json
{
  "accountName": null,
  "institutionName": null,
  "accountType": null,
  "balance": null,
  "plaidAccountId": "class AccountBase {\n    accountId: JgppVZJQ5NtrKKBQooVoCJ6oQb1dDxcBrDmaq\n..."
}
```

**Issues Identified:**
1. `plaidAccountId` contains the entire AccountBase object's string representation instead of just the account ID
2. All account fields are `null` (accountName, institutionName, accountType, balance)
3. This suggests `extractAccountId()` and `updateAccountFromPlaid()` methods are not working correctly

### iOS Decoder Issue
The iOS `BackendAccount` decoder requires non-null values for:
- `accountName` (String, not optional)
- `institutionName` (String, not optional)
- `accountType` (String, not optional)

When the backend returns `null`, the iOS decoder fails.

## Fixes Applied

### 1. Enhanced `extractAccountId()` Method
- Added better logging to identify the actual type of Plaid account objects
- Added fallback reflection logic
- Added error logging with object type information

### 2. Enhanced `updateAccountFromPlaid()` Method
- Improved field extraction with better null handling
- Added default values for required fields
- Better error logging
- Ensures all required fields are set even if Plaid data is missing

### 3. iOS Decoder Fix
- Updated `BackendAccount` decoder to handle null values with defaults:
  - `accountName`: defaults to "Unknown Account"
  - `institutionName`: defaults to "Unknown Institution"
  - `accountType`: defaults to "OTHER"

## Next Steps

1. **Restart backend** to apply the fixes
2. **Re-sync accounts** by connecting to Plaid again
3. **Check logs** for:
   - "Extracted account ID: ..." messages
   - "Updated account from Plaid: ..." messages
   - Any errors in account extraction

4. **Verify the fix**:
   - Accounts should now have proper accountName, institutionName, accountType, and balance
   - plaidAccountId should be a simple string (not the entire object)
   - iOS app should be able to decode the accounts

## Testing

After restarting the backend, try connecting to Plaid again and check:
1. Backend logs show proper account extraction
2. Accounts are saved with all fields populated
3. iOS app can decode and display the accounts

