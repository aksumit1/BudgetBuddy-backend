# Critical Fix: Deduplication with Null Institution Name

## Problem Identified

You correctly identified a **critical bug** in the deduplication logic:

### The Issue

1. **Access token was regenerated** → New Plaid account IDs assigned
2. **Multiple link sessions** → Different Plaid account IDs for same account
3. **Institution name is NULL** → Deduplication logic **failed completely**

### Why Deduplication Failed

The original code had this check:

```java
if (accountNumber != null && !accountNumber.isEmpty() 
    && institutionName != null && !institutionName.isEmpty()) {
    // Only runs if BOTH are non-null
    existingAccount = accountRepository.findByAccountNumberAndInstitution(...);
}
```

**Problem**: If `institutionName` is null, this entire check is **skipped**, and a new account is created!

### Why New Accounts Were Created

Even though the deduplication logic runs **every time** a new account comes in, it failed because:

1. ✅ Check 1: `findByPlaidAccountId()` - **Failed** (new Plaid ID after token regeneration)
2. ❌ Check 2: `findByAccountNumberAndInstitution()` - **Skipped** (institutionName is null)
3. ❌ Check 3: Comprehensive scan - **Skipped** (also required institutionName)

Result: **New account created** even though one with the same `accountNumber` already exists!

## The Fix

### 1. Updated Repository Method

**Before**: Required both `accountNumber` AND `institutionName` to be non-null
```java
if (accountNumber == null || accountNumber.isEmpty() || 
    institutionName == null || institutionName.isEmpty()) {
    return Optional.empty(); // FAILS if institutionName is null
}
```

**After**: Works with just `accountNumber` if `institutionName` is null
```java
if (accountNumber == null || accountNumber.isEmpty()) {
    return Optional.empty();
}

if (institutionName == null || institutionName.isEmpty()) {
    // Match by accountNumber only
    return userAccounts.stream()
        .filter(account -> accountNumber.equals(account.getAccountNumber()))
        .findFirst();
} else {
    // Match by both when available
    return userAccounts.stream()
        .filter(account -> accountNumber.equals(account.getAccountNumber()) 
            && institutionName.equals(account.getInstitutionName()))
        .findFirst();
}
```

### 2. Updated Service Logic

**Before**: Skipped check if `institutionName` was null
```java
if (accountNumber != null && !accountNumber.isEmpty() 
    && institutionName != null && !institutionName.isEmpty()) {
    // Only runs if both are non-null
}
```

**After**: Checks by `accountNumber` even if `institutionName` is null
```java
if (accountNumber != null && !accountNumber.isEmpty()) {
    if (institutionName != null && !institutionName.isEmpty()) {
        // Both available - use both for matching
        existingAccount = accountRepository.findByAccountNumberAndInstitution(...);
    } else {
        // Institution name missing - match by accountNumber only
        existingAccount = accountRepository.findByAccountNumber(accountNumber, userId);
    }
}
```

### 3. Added New Repository Method

Added `findByAccountNumber()` for cases where `institutionName` is missing:
```java
public Optional<AccountTable> findByAccountNumber(String accountNumber, String userId)
```

### 4. Updated Comprehensive Checks

All three deduplication checks now handle null `institutionName`:
- ✅ Primary check (after `findByPlaidAccountId`)
- ✅ Comprehensive scan (before creating new account)
- ✅ Final check (last resort before creation)

## How It Works Now

### Scenario: Access Token Regenerated + Null Institution Name

1. **New account comes in** with:
   - New `plaidAccountId` (different from existing)
   - Same `accountNumber` (e.g., "0000")
   - `institutionName` = null

2. **Deduplication checks**:
   - ✅ Check 1: `findByPlaidAccountId()` → **Not found** (new ID)
   - ✅ Check 2: `findByAccountNumber()` → **Found!** (matches by accountNumber only)
   - ✅ Account updated instead of created

3. **Result**: No duplicate created!

### Scenario: Both Have Institution Names

1. **New account comes in** with:
   - New `plaidAccountId`
   - Same `accountNumber`
   - Same `institutionName`

2. **Deduplication checks**:
   - ✅ Check 1: `findByPlaidAccountId()` → **Not found**
   - ✅ Check 2: `findByAccountNumberAndInstitution()` → **Found!** (matches both)
   - ✅ Account updated instead of created

3. **Result**: No duplicate created!

## Testing

To verify the fix works:

1. **Run duplicate analysis**:
   ```bash
   ./scripts/analyze-duplicates-simple.sh
   ```

2. **Check for accounts with null institutionName**:
   - Look for accounts where `institutionName: N/A`
   - These should now be deduplicated by `accountNumber` only

3. **Re-sync accounts**:
   - After the fix, re-sync should update existing accounts instead of creating duplicates
   - Even if `institutionName` is null

## Summary

| Scenario | Before Fix | After Fix |
|----------|-----------|-----------|
| Null institutionName + same accountNumber | ❌ Creates duplicate | ✅ Updates existing |
| Both have institutionName | ✅ Updates existing | ✅ Updates existing |
| Only accountNumber matches | ❌ Creates duplicate | ✅ Updates existing |
| Nothing matches | ✅ Creates new | ✅ Creates new |

**Key Improvement**: Deduplication now works even when `institutionName` is null, preventing duplicates when access tokens are regenerated or multiple link sessions occur.

