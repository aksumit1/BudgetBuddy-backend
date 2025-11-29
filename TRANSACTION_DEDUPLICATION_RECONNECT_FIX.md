# Transaction Deduplication Fix for Reconnection/Relinking

## Problem

When a user reconnects or relinks their bank account with Plaid, the `plaidTransactionId` may change for the same transaction. This causes duplicates because:

1. **Primary deduplication method** (`findByPlaidTransactionId`) fails because the new Plaid ID doesn't match the old one
2. **Same transaction** (same amount, date, merchant, account) gets created as a new entry
3. **Result**: Duplicate transactions in the database

## Solution

Added **composite key deduplication** as a fallback when `plaidTransactionId` doesn't match. The composite key uses:
- `accountId` - Which account the transaction belongs to
- `amount` - Transaction amount (exact match)
- `transactionDate` - Transaction date (YYYY-MM-DD format)
- `description` or `merchantName` - Transaction description/merchant

This composite key uniquely identifies a transaction even when the Plaid ID changes.

## Implementation

### 1. **TransactionRepository.java**

Added new method `findByCompositeKey()`:

```java
public Optional<TransactionTable> findByCompositeKey(
        final String accountId,
        final java.math.BigDecimal amount,
        final String transactionDate,
        final String description,
        final String userId)
```

**How it works:**
1. Gets all transactions for the user
2. Filters by `accountId`, `amount`, `transactionDate`
3. Matches by `description` or `merchantName` (whichever is provided)
4. Returns first match

**Note**: This is a client-side filter (not using GSI) because DynamoDB doesn't support composite key queries efficiently. For large datasets, consider adding a GSI.

### 2. **PlaidSyncService.java**

Updated transaction sync logic in two places:

#### Location 1: `processTransactionsForAccount()`
- First checks by `plaidTransactionId` (primary)
- If not found, checks by composite key (fallback)
- If found by composite key, updates the existing transaction with new `plaidTransactionId`
- Logs when composite key match is found

#### Location 2: `syncTransactionsForAccount()` (incremental sync)
- Same logic as above
- Handles both first sync and incremental sync scenarios

**Key Changes:**
```java
// Primary check
Optional<TransactionTable> existing = transactionRepository.findByPlaidTransactionId(transactionId);

// Fallback: composite key check
if (existing.isEmpty()) {
    BigDecimal amount = extractAmount(plaidTransaction);
    String transactionDate = extractTransactionDate(plaidTransaction);
    String description = extractDescription(plaidTransaction);
    String merchantName = extractMerchantName(plaidTransaction);
    String matchKey = (description != null && !description.isEmpty()) ? description : merchantName;
    
    if (amount != null && transactionDate != null && matchKey != null && !matchKey.isEmpty()) {
        existing = transactionRepository.findByCompositeKey(
                account.getAccountId(),
                amount,
                transactionDate,
                matchKey,
                user.getUserId());
    }
}

// Update existing transaction with new plaidTransactionId if found
if (existing.isPresent()) {
    TransactionTable transaction = existing.get();
    if (!transactionId.equals(transaction.getPlaidTransactionId())) {
        transaction.setPlaidTransactionId(transactionId); // Update with new Plaid ID
    }
    updateTransactionFromPlaid(transaction, plaidTransaction);
    transactionRepository.save(transaction);
}
```

### 3. **Helper Methods**

Added three new helper methods to extract transaction fields:
- `extractAmount(Object plaidTransaction)` - Extracts amount as BigDecimal
- `extractDescription(Object plaidTransaction)` - Extracts description/name
- `extractMerchantName(Object plaidTransaction)` - Extracts merchant name

These methods handle both direct casting and reflection fallback for different Plaid SDK versions.

## Deduplication Strategy

### Primary Method (Fast - Uses GSI)
1. Check by `plaidTransactionId` using GSI
2. If found, update existing transaction
3. **Performance**: O(1) lookup via GSI

### Fallback Method (Slower - Client-side filter)
1. If not found by `plaidTransactionId`, check by composite key
2. Composite key: `accountId` + `amount` + `transactionDate` + `description/merchantName`
3. If found, update existing transaction with new `plaidTransactionId`
4. **Performance**: O(n) where n = number of user transactions (acceptable for most use cases)

## Edge Cases Handled

1. **Missing Plaid Transaction ID**: Falls back to composite key immediately
2. **Missing Description**: Uses `merchantName` as fallback
3. **Missing Merchant Name**: Uses `description` as fallback
4. **Missing Amount/Date**: Skips composite key check (can't match without these)
5. **Multiple Matches**: Returns first match (should be rare with composite key)

## Logging

Enhanced logging to track composite key matches:
```
Found existing transaction by composite key (plaidTransactionId changed): 
  accountId={}, amount={}, date={}, description={}, oldPlaidId={}, newPlaidId={}
```

## Performance Considerations

### Current Implementation
- **Primary check**: Fast (GSI lookup)
- **Fallback check**: Slower (client-side filter of all user transactions)
- **Impact**: Acceptable for most users (< 10,000 transactions)

### Future Optimization
If performance becomes an issue, consider:
1. **GSI on composite key**: Create GSI with partition key = `userId` + `accountId`, sort key = `transactionDate` + `amount`
2. **Caching**: Cache composite key lookups for recent transactions
3. **Batch processing**: Process transactions in batches and deduplicate in memory first

## Testing

### Test Scenarios
1. ✅ Transaction with same `plaidTransactionId` - deduplicates by Plaid ID
2. ✅ Transaction with different `plaidTransactionId` but same composite key - deduplicates by composite key
3. ✅ Transaction with missing `plaidTransactionId` - deduplicates by composite key
4. ✅ Transaction with missing description - uses merchantName for matching
5. ✅ Transaction with missing merchantName - uses description for matching
6. ✅ New transaction (no match) - creates new entry

### Integration Tests Needed
- Test reconnection scenario where Plaid IDs change
- Test first sync after reconnection
- Test incremental sync after reconnection
- Test performance with large number of transactions

## iOS App Considerations

The iOS app should also implement similar composite key deduplication:
- Match by: `accountID` + `amount` + `date` + `description`
- Update `plaidTransactionId` when found by composite key
- Preserve audit state, notes, and review status

## Summary

This fix ensures that transactions are properly deduplicated even when Plaid transaction IDs change due to reconnection/relinking. The composite key provides a stable identifier that doesn't change with Plaid account reconnections.

**Key Benefits:**
- ✅ Prevents duplicates when Plaid IDs change
- ✅ Updates existing transactions with new Plaid IDs
- ✅ Maintains transaction history integrity
- ✅ Handles edge cases gracefully

**Trade-offs:**
- ⚠️ Fallback method is slower (O(n) client-side filter)
- ⚠️ Requires all composite key fields to be present
- ⚠️ May have false positives if two transactions have identical composite keys (rare)

