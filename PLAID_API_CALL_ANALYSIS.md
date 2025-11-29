# Plaid API Call Analysis and Optimization

## Current API Call Pattern

### Initial Connection Flow (First Time User Connects Bank)

1. **Link Token Creation**: `createLinkToken()` - **1 call**
   - Called when user initiates Plaid Link
   - Location: `PlaidController.createLinkToken()`

2. **Token Exchange**: `exchangePublicToken()` - **1 call**
   - Called when user completes Plaid Link
   - Location: `PlaidController.exchangePublicToken()`

3. **Account Sync**: `getAccounts(accessToken)` - **1 call**
   - Fetches ALL accounts for the Plaid item
   - Location: `PlaidSyncService.syncAccounts()`
   - **Note**: We check for existing accounts first, but still make the call to get updated balances

4. **Transaction Sync**: `getTransactions(accessToken, startDate, endDate)` - **N calls + pagination**
   - Called **once per account** (N = number of accounts)
   - Location: `PlaidSyncService.syncTransactionsForAccount()`
   - Each call can trigger **multiple pagination calls** (up to 100 pages max)
   - **Pagination**: Each page is a separate API call

**Total for Initial Connection:**
- Minimum: 3 + N calls (where N = number of accounts)
- With pagination: 3 + N + P calls (where P = total pagination pages across all accounts)

### Reconnection/Sync Flow (User Reconnects Same Bank)

1. **Account Sync**: `getAccounts(accessToken)` - **1 call**
   - We check for existing accounts first, but still make the call
   - **Optimization Opportunity**: Skip if accounts exist and recently synced

2. **Transaction Sync**: `getTransactions(accessToken, startDate, endDate)` - **N calls + pagination**
   - Called once per account
   - We now check `lastSyncedAt` and skip if synced within 5 minutes
   - **Optimization Opportunity**: Batch all accounts into one call

**Total for Reconnection:**
- Minimum: 1 + N calls
- With pagination: 1 + N + P calls

## Current Call Count Example

For a user with **3 accounts** and **2 years of transaction history**:

### Initial Connection:
- Link token: 1 call
- Token exchange: 1 call
- Get accounts: 1 call
- Get transactions (account 1): 1 call + ~10 pagination calls = **11 calls**
- Get transactions (account 2): 1 call + ~8 pagination calls = **9 calls**
- Get transactions (account 3): 1 call + ~5 pagination calls = **6 calls**

**Total: 1 + 1 + 1 + 11 + 9 + 6 = 29 API calls**

### Reconnection (same day):
- Get accounts: 1 call
- Get transactions (account 1): 1 call + ~2 pagination calls = **3 calls** (incremental)
- Get transactions (account 2): 1 call + ~1 pagination call = **2 calls** (incremental)
- Get transactions (account 3): 1 call + ~1 pagination call = **2 calls** (incremental)

**Total: 1 + 3 + 2 + 2 = 8 API calls**

## Optimization Opportunities

### 1. **Batch Transaction Fetching** ⭐ HIGH IMPACT
**Current**: One `getTransactions()` call per account
**Optimized**: One `getTransactions()` call for ALL accounts

**Plaid API Support**: The `/transactions/get` endpoint accepts an optional `account_ids` filter.
- If not provided, it returns transactions for ALL accounts
- We can fetch all transactions in one call and then filter by account

**Savings**: 
- Initial connection: N calls → 1 call (saves N-1 calls)
- Reconnection: N calls → 1 call (saves N-1 calls)

**Implementation**:
```java
// Instead of:
for (var account : userAccounts) {
    syncTransactionsForAccount(user, accessToken, account, endDate);
}

// Do:
var allTransactions = plaidService.getTransactions(accessToken, startDate, endDate);
// Then group by account and process
```

**Estimated Savings**: 2-10 calls per sync (depending on number of accounts)

### 2. **Skip Account Sync if Recently Updated** ⭐ MEDIUM IMPACT
**Current**: Always calls `getAccounts()` even if accounts exist
**Optimized**: Skip if accounts exist and were updated recently (e.g., within last hour)

**Savings**: 1 call per sync if accounts recently synced

**Implementation**:
```java
if (itemId != null && !itemId.isEmpty()) {
    var existingAccounts = accountRepository.findByPlaidItemId(itemId);
    if (!existingAccounts.isEmpty()) {
        // Check if any account was updated recently
        boolean recentlyUpdated = existingAccounts.stream()
            .anyMatch(acc -> acc.getUpdatedAt() != null && 
                Duration.between(acc.getUpdatedAt(), Instant.now()).toHours() < 1);
        if (recentlyUpdated) {
            logger.info("Skipping account sync - accounts updated within last hour");
            return; // Skip API call
        }
    }
}
```

**Estimated Savings**: 1 call per sync (if recently synced)

### 3. **Optimize Pagination** ⭐ MEDIUM IMPACT
**Current**: Fetches all pages sequentially
**Optimized**: 
- Use larger page sizes if Plaid supports it
- Cache pagination state to resume if interrupted
- Parallel pagination for multiple accounts (if batching not possible)

**Savings**: Reduces pagination overhead

### 4. **Cache Account Data** ⭐ LOW IMPACT
**Current**: Always fetches accounts from Plaid
**Optimized**: Cache account metadata (name, type, balance) and only refresh when needed

**Savings**: 1 call per sync (if cached)

### 5. **Incremental Sync Optimization** ⭐ HIGH IMPACT (Already Implemented)
**Current**: ✅ We now skip transaction sync if `lastSyncedAt` is within 5 minutes
**Optimized**: ✅ Already implemented

**Savings**: N calls saved if synced recently

## Recommended Optimization Priority

1. **Batch Transaction Fetching** (HIGH) - Save N-1 calls per sync
2. **Skip Account Sync if Recent** (MEDIUM) - Save 1 call per sync
3. **Optimize Pagination** (MEDIUM) - Reduce pagination overhead

## Expected Impact

### Before Optimization:
- Initial connection: **29 calls** (3 accounts, 2 years history)
- Reconnection: **8 calls** (3 accounts, incremental)

### After Optimization:
- Initial connection: **~15 calls** (1 account call + 1 batched transaction call + pagination)
- Reconnection: **~3 calls** (skip account if recent + 1 batched transaction call + pagination)

**Total Reduction: ~50% fewer API calls**

## Implementation Notes

1. **Batch Transaction Fetching** requires refactoring `syncTransactions()` to:
   - Fetch all transactions in one call
   - Group transactions by account ID
   - Process each account's transactions separately

2. **Account Sync Skip** requires:
   - Tracking `updatedAt` timestamp on accounts
   - Configurable threshold (e.g., 1 hour)

3. **Pagination Optimization** requires:
   - Checking Plaid API documentation for max page size
   - Implementing pagination state management

## Rate Limit Considerations

Plaid rate limits (from error message):
- `TRANSACTIONS_LIMIT`: Rate limit exceeded for transactions endpoint
- Typical limits: ~100 requests per minute per access token

With current implementation:
- 3 accounts × 10 pagination pages = 30 calls per sync
- Multiple users syncing = risk of hitting rate limits

With optimizations:
- 1 batched call + 10 pagination pages = 11 calls per sync
- Much lower risk of rate limits

