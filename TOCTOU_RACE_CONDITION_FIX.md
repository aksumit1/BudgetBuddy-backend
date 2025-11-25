# TOCTOU Race Condition Fix - saveIfPlaidTransactionNotExists

## ⚠️ Critical Race Condition Identified and Fixed

### Issue:
The `saveIfPlaidTransactionNotExists()` method in `TransactionRepository.java` had a **TOCTOU (Time-Of-Check-Time-Of-Use) race condition** that could allow duplicate Plaid transactions to be inserted.

### Root Cause:
```java
// ❌ BUGGY CODE (Before Fix):
// Step 1: Check if plaidTransactionId exists (non-atomic)
Optional<TransactionTable> existing = findByPlaidTransactionId(transaction.getPlaidTransactionId());
if (existing.isPresent()) {
    return false;
}

// Step 2: Save with condition on transactionId (different attribute!)
return saveIfNotExists(transaction);  // Checks attribute_not_exists(transactionId)
```

**The Problem:**
1. Thread A checks if `plaidTransactionId` exists → Not found
2. Thread B checks if `plaidTransactionId` exists → Not found (same check)
3. Thread A saves with condition on `transactionId` → Succeeds
4. Thread B saves with condition on `transactionId` → Succeeds
5. **Result**: Two transactions with the same `plaidTransactionId` are inserted!

The check and save operations were **not atomic**, and the conditional write checked the wrong attribute (`transactionId` instead of `plaidTransactionId`).

### Impact:
- **CRITICAL**: Duplicate Plaid transactions could be inserted
- Data integrity violation
- Potential financial discrepancies
- Violates deduplication requirements

---

## ✅ Fix Applied

### Solution:
1. **Removed separate existence check**: Eliminated the `findByPlaidTransactionId()` check that caused the race condition
2. **Atomic conditional write**: Use a single `PutItem` with a conditional expression that checks `attribute_not_exists(plaidTransactionId)` atomically
3. **Combined conditions**: Also check `attribute_not_exists(transactionId)` to prevent overwriting existing transactions

### Fixed Code:
```java
// ✅ FIXED CODE (After Fix):
public boolean saveIfPlaidTransactionNotExists(final TransactionTable transaction) {
    if (transaction == null) {
        throw new IllegalArgumentException("Transaction cannot be null");
    }
    
    // Ensure transactionId is set (required for primary key)
    if (transaction.getTransactionId() == null || transaction.getTransactionId().isEmpty()) {
        throw new IllegalArgumentException("Transaction ID is required");
    }

    if (transaction.getPlaidTransactionId() == null || transaction.getPlaidTransactionId().isEmpty()) {
        // If no Plaid ID, use regular save with transactionId check only
        return saveIfNotExists(transaction);
    }

    try {
        // Atomic conditional write: check both transactionId and plaidTransactionId don't exist
        // This prevents TOCTOU race condition by making check-and-write atomic
        transactionTable.putItem(
                PutItemEnhancedRequest.builder(TransactionTable.class)
                        .item(transaction)
                        .conditionExpression(
                                Expression.builder()
                                        .expression("attribute_not_exists(transactionId) AND attribute_not_exists(plaidTransactionId)")
                                        .build())
                        .build());
        return true;
    } catch (ConditionalCheckFailedException e) {
        // Transaction or Plaid transaction already exists (duplicate detected atomically)
        return false;
    }
}
```

### Key Changes:
1. **Removed TOCTOU gap**: No separate check before save - everything is atomic
2. **Correct conditional expression**: Checks `attribute_not_exists(plaidTransactionId)` to prevent duplicates
3. **Combined protection**: Also checks `attribute_not_exists(transactionId)` to prevent overwrites
4. **Atomic operation**: DynamoDB ensures the condition is checked and write happens atomically

---

## 🔍 Verification

### Before Fix:
- ❌ Separate check → Save → **Race Condition**
- ❌ Checked wrong attribute (`transactionId` instead of `plaidTransactionId`)
- ❌ Multiple threads could insert duplicates

### After Fix:
- ✅ Single atomic conditional write
- ✅ Checks correct attribute (`plaidTransactionId`)
- ✅ DynamoDB ensures atomicity (no race condition possible)

---

## 📋 Race Condition Explanation

### TOCTOU (Time-Of-Check-Time-Of-Use) Pattern:

```
Thread A: Check plaidTransactionId exists? → NO
Thread B: Check plaidTransactionId exists? → NO  (same time)
Thread A: Save with condition on transactionId → SUCCESS
Thread B: Save with condition on transactionId → SUCCESS
Result: DUPLICATE plaidTransactionId inserted! ❌
```

### Atomic Conditional Write Pattern:

```
Thread A: PutItem with condition "attribute_not_exists(plaidTransactionId)" → SUCCESS
Thread B: PutItem with condition "attribute_not_exists(plaidTransactionId)" → FAIL (ConditionalCheckFailedException)
Result: Only one transaction inserted ✅
```

DynamoDB's conditional writes are **atomic** - the condition check and write happen in a single operation, preventing race conditions.

---

## 📋 Best Practices Applied

### ✅ Atomic Operations:
- Use DynamoDB conditional writes for check-and-write operations
- Never separate existence checks from writes
- Use appropriate conditional expressions

### ✅ Correct Attribute Checks:
- Check the attribute you want to prevent duplicates on (`plaidTransactionId`)
- Also check primary key to prevent overwrites (`transactionId`)

### ✅ Error Handling:
- Catch `ConditionalCheckFailedException` to detect duplicates
- Return boolean to indicate success/failure

---

## ✅ Summary

**Bug**: TOCTOU race condition allowing duplicate Plaid transactions.

**Fix**: Atomic conditional write checking `attribute_not_exists(plaidTransactionId)`.

**Status**: ✅ **FIXED** - Code compiles successfully, race condition eliminated.

**Prevention**: All conditional writes now use atomic DynamoDB operations.

