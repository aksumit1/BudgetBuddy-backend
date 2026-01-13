# TransactionType Override Flag (`transactionTypeOverridden`)

This document explains when and where the `transactionTypeOverridden` flag is written/set for transactions.

## Flag Definition

**File:** `TransactionTable.java` (line 33)

```java
private Boolean transactionTypeOverridden; // Whether user has explicitly overridden transactionType (prevents Plaid sync from recalculating)
```

**Purpose:** Prevents automatic recalculation of TransactionType when:
- Plaid sync updates transactions
- Categories change
- Amounts change

---

## When `transactionTypeOverridden = true` (User Override)

The flag is set to `true` when **user explicitly provides a TransactionType** via API.

### 1. TransactionService.setTransactionTypeFromUserOrCalculate()
**File:** `TransactionService.java` (line 513-528)

**Context:** Creating transactions via API with user-provided TransactionType

```java
if (userTypeOpt.isPresent()) {
    // User provided valid transactionType - use it and mark as overridden
    transaction.setTransactionType(userType.name());
    transaction.setTransactionTypeOverridden(true); // ✅ SET TO TRUE
    return true;
}
```

**Called from:**
- `createTransaction()` - When creating new transactions
- Line 1069: Direct call when user provides TransactionType in request

### 2. TransactionService.createTransaction()
**File:** `TransactionService.java` (line 1064-1075)

**Context:** Creating transaction with user-provided TransactionType in request body

```java
if (transactionType != null && !transactionType.trim().isEmpty()) {
    // User provided transaction type - use it and mark as overridden
    com.budgetbuddy.model.TransactionType userType = com.budgetbuddy.model.TransactionType.valueOf(transactionType.trim().toUpperCase());
    transaction.setTransactionType(userType.name());
    transaction.setTransactionTypeOverridden(true); // ✅ SET TO TRUE
}
```

**API Endpoint:** `POST /api/transactions`
- Request body includes `transactionType` field
- User explicitly selects TransactionType in UI/API

### 3. TransactionService.updateTransaction()
**File:** `TransactionService.java` (line 800-829, 1330-1336)

**Context 1:** Updating existing transaction with Plaid ID (line 800-809)

```java
Optional<com.budgetbuddy.model.TransactionType> userTypeOpt = parseUserTransactionType(transactionType);
if (userTypeOpt.isPresent()) {
    existing.setTransactionType(userTypeOpt.get().name());
    existing.setTransactionTypeOverridden(true); // ✅ SET TO TRUE
}
```

**Context 2:** Updating existing transaction without Plaid ID (line 814-825)

```java
Optional<com.budgetbuddy.model.TransactionType> userTypeOpt = parseUserTransactionType(transactionType);
if (userTypeOpt.isPresent()) {
    com.budgetbuddy.model.TransactionType userType = userTypeOpt.get();
    if (!userType.name().equals(existing.getTransactionType())) {
        existing.setTransactionType(userType.name());
        existing.setTransactionTypeOverridden(true); // ✅ SET TO TRUE
    }
}
```

**Context 3:** Main update logic (line 1330-1336)

```java
if (userTypeOpt.isPresent()) {
    // User provided transaction type - use it (override automatic calculation)
    com.budgetbuddy.model.TransactionType userType = userTypeOpt.get();
    transaction.setTransactionType(userType.name());
    transaction.setTransactionTypeOverridden(true); // ✅ SET TO TRUE
}
```

**API Endpoint:** `PUT /api/transactions/{id}`
- Request body includes `transactionType` field
- User changes TransactionType in UI/API

---

## When `transactionTypeOverridden = false` (System Determined)

The flag is set to `false` when **system automatically determines TransactionType**.

### 1. PlaidDataExtractor.updateTransactionFromPlaid()
**File:** `PlaidDataExtractor.java` (line 552-556)

**Context:** Plaid sync determines TransactionType automatically

```java
if (typeResult != null) {
    transaction.setTransactionType(typeResult.getTransactionType().name());
    // Only set overridden=false if it's currently null (preserve existing override state)
    if (transaction.getTransactionTypeOverridden() == null) {
        transaction.setTransactionTypeOverridden(false); // ✅ SET TO FALSE
    }
}
```

**When:** 
- New transaction from Plaid
- Updating existing transaction from Plaid (if not overridden)
- **Only sets to false if currently null** (preserves existing override)

**Fallback:** Line 665-667 (same logic)

---

## When Flag is NOT Set (Preserves Existing State)

### 1. TransactionService.updateTransaction() - Category/Amount Changes
**File:** `TransactionService.java` (line 1337-1360)

**Context:** User updates category or amount, but NOT TransactionType

```java
else if (!Boolean.TRUE.equals(transaction.getTransactionTypeOverridden()) && (categoryChanged || amount != null)) {
    // User didn't provide type, transaction not overridden, and category/amount changed - recalculate
    // ... recalculates TransactionType ...
    // Does NOT set transactionTypeOverridden flag (preserves existing state)
}
```

**Behavior:**
- If `transactionTypeOverridden == true`: Skips recalculation (user override preserved)
- If `transactionTypeOverridden == false` or `null`: Recalculates TransactionType

### 2. PlaidDataExtractor - User Override Check
**File:** `PlaidDataExtractor.java` (line 539, 641)

**Context:** Plaid sync checks if user has overridden

```java
if (!Boolean.TRUE.equals(transaction.getTransactionTypeOverridden())) {
    // Recalculate TransactionType
} else {
    // Skip recalculation - user override preserved
    // Does NOT modify transactionTypeOverridden flag
}
```

**Behavior:**
- If `true`: Skips TransactionType recalculation
- If `false` or `null`: Recalculates TransactionType

---

## Summary Table

| Scenario | Flag Value | File | Line | Notes |
|----------|------------|------|------|-------|
| **User provides TransactionType via API** | `true` | `TransactionService.java` | 518, 805, 820, 1069, 1335 | User explicitly sets TransactionType |
| **System determines TransactionType (Plaid)** | `false` | `PlaidDataExtractor.java` | 555, 666 | Only if flag is null (preserves existing) |
| **User updates category/amount (not TransactionType)** | Preserved | `TransactionService.java` | 1337 | Doesn't modify flag, respects existing state |
| **Plaid sync (user override exists)** | Preserved | `PlaidDataExtractor.java` | 539, 641 | Skips recalculation, flag unchanged |

---

## Important Notes

1. **User Override Protection:**
   - Once `transactionTypeOverridden = true`, TransactionType is **never automatically recalculated**
   - Even when category/amount changes
   - Even during Plaid sync

2. **Null vs False:**
   - `null` = Never set, treated as "not overridden"
   - `false` = Explicitly set to "system determined"
   - `true` = Explicitly set to "user overridden"

3. **Plaid Sync Behavior:**
   - **Line 554:** Only sets to `false` if currently `null`
   - **Line 539, 641:** Checks `!Boolean.TRUE.equals()` - respects both `false` and `null`

4. **API Endpoints:**
   - `POST /api/transactions` - Can set flag to `true` if TransactionType provided
   - `PUT /api/transactions/{id}` - Can set flag to `true` if TransactionType provided

5. **No Direct API to Clear Override:**
   - To clear override, user must explicitly set a new TransactionType OR
   - Delete and recreate transaction (flag resets to null/false)

---

## Code Flow Diagram

```
User API Request (with transactionType)
    ↓
TransactionController.updateTransaction() / createTransaction()
    ↓
TransactionService.updateTransaction() / createTransaction()
    ↓
parseUserTransactionType() → Valid TransactionType?
    ↓ YES
transaction.setTransactionTypeOverridden(true) ✅
    ↓
Save to Database
```

```
Plaid Sync
    ↓
PlaidDataExtractor.updateTransactionFromPlaid()
    ↓
Check: !Boolean.TRUE.equals(transaction.getTransactionTypeOverridden())?
    ↓ NO (user overridden)
Skip TransactionType recalculation (flag unchanged)
    ↓ YES (not overridden)
Calculate TransactionType
    ↓
if (transactionTypeOverridden == null) {
    transaction.setTransactionTypeOverridden(false) ✅
}
```
