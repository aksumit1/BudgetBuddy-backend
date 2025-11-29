# Duplicate Accounts Analysis and Fix

## Problem
Backend has 24 accounts but should only have 12 accounts, indicating duplicate accounts exist.

## Root Causes Identified

### 1. **Missing plaidAccountId on Initial Sync**
- If `plaidAccountId` is missing or null when an account is first created, subsequent syncs won't find it by `findByPlaidAccountId()`
- The account gets created again with a new UUID-based `accountId`
- **Fix**: Added logic to update existing accounts with missing `plaidAccountId`

### 2. **Missing accountNumber or institutionName**
- If `accountNumber` or `institutionName` is missing initially, the `findByAccountNumberAndInstitution()` check fails
- Account gets created again
- **Fix**: Added comprehensive final check before creating new accounts

### 3. **UUID Fallback Creates Different IDs**
- When `institutionName` is missing, account ID generation falls back to random UUID
- If the same account is synced again with `institutionName` present, it gets a different deterministic ID
- This creates duplicates with different `accountId` but same account
- **Fix**: Added final comprehensive check that scans all user accounts before creating new ones

### 4. **Race Conditions**
- Multiple concurrent syncs can create duplicates
- `saveIfNotExists()` only checks `accountId`, not `plaidAccountId`
- **Fix**: Enhanced deduplication logic with multiple checks

## Deduplication Strategy (Updated)

### Primary Checks (in order):
1. **By plaidAccountId** (primary key) - `findByPlaidAccountId()`
2. **By accountNumber + institutionName** - `findByAccountNumberAndInstitution()`
3. **Comprehensive scan** - Check all user accounts before creating new one
4. **Final safety check** - One more scan before creating to catch edge cases

### When Account is Found:
- Update existing account with latest data from Plaid
- Ensure `plaidAccountId` is set (update if missing)
- Ensure `accountNumber` and `institutionName` are set
- Mark as active

### When Account is Not Found:
- Create new account
- Generate deterministic `accountId` using `institutionName + plaidAccountId`
- Fallback to UUID only if institution name is truly unavailable

## Scripts to Find and Fix Duplicates

### 1. Find Duplicates
```bash
# Using Java script
cd BudgetBuddy-Backend
mvn spring-boot:run -Dspring-boot.run.arguments="--find-duplicates [userId]"

# Or using shell script (requires AWS CLI and jq)
./scripts/find-duplicate-accounts.sh
```

### 2. Analyze and Fix Duplicates
```bash
# Dry run (analyze only)
mvn spring-boot:run -Dspring-boot.run.arguments="--analyze-duplicates [userId] --dry-run"

# Actually delete duplicates (keeps oldest account)
mvn spring-boot:run -Dspring-boot.run.arguments="--analyze-duplicates [userId]"
```

## How to Use

1. **First, find duplicates:**
   ```bash
   # Replace [userId] with actual user ID
   mvn spring-boot:run -Dspring-boot.run.arguments="--find-duplicates [userId]"
   ```

2. **Analyze what will be deleted (dry run):**
   ```bash
   mvn spring-boot:run -Dspring-boot.run.arguments="--analyze-duplicates [userId] --dry-run"
   ```

3. **Delete duplicates (keeps oldest account):**
   ```bash
   mvn spring-boot:run -Dspring-boot.run.arguments="--analyze-duplicates [userId]"
   ```

## Expected Results

- **Before**: 24 accounts
- **After cleanup**: 12 unique accounts
- **Duplicates removed**: 12 accounts

The script will:
- Keep the oldest account (by `createdAt`) for each duplicate group
- Delete newer duplicates
- Preserve all transactions linked to the kept account

## Prevention

The updated deduplication logic should prevent future duplicates by:
1. Checking `plaidAccountId` first
2. Checking `accountNumber + institutionName` as fallback
3. Comprehensive scan before creating new accounts
4. Updating existing accounts with missing `plaidAccountId` to prevent future duplicates

## Testing

After cleanup, test by:
1. Reconnecting the same bank account
2. Verifying no new duplicates are created
3. Checking that existing accounts are updated, not duplicated

