# Amount Normalization vs Transaction Type Determination Order

## Answer: **Sign normalization happens BEFORE transaction type determination**

---

## Order of Operations in PlaidDataExtractor.updateTransactionFromPlaid()

**File:** `PlaidDataExtractor.java` (line 265-637)

### Step-by-Step Flow:

1. **Extract raw amount from Plaid** (line 284-288)
   ```java
   java.math.BigDecimal rawAmount = null;
   if (plaidTx.getAmount() != null) {
       rawAmount = java.math.BigDecimal.valueOf(plaidTx.getAmount());
   }
   ```

2. **Normalize Plaid amount** ⭐ **FIRST** (line 316-317)
   ```java
   java.math.BigDecimal normalizedAmount = normalizePlaidAmount(rawAmount, account);
   transaction.setAmount(normalizedAmount);
   ```
   - **Plaid convention:** For checking/savings/debit/money_market accounts: `-ve = income`, `+ve = expense`
   - **System convention:** Standard convention: `+ve = income`, `-ve = expense`
   - This normalization reverses the sign for checking/savings accounts

3. **Set normalized amount on transaction** (line 317)
   ```java
   transaction.setAmount(normalizedAmount);
   ```

4. **Extract basic fields** (line 319-357)
   - Merchant name, description, payment channel, etc.

5. **Determine Category** (line 374-513)
   - Uses **normalized amount** (line 384: `transactionAmount` which is `transaction.getAmount()`)
   - Calls `transactionTypeCategoryService.determineCategory()`

6. **Determine TransactionType** ⭐ **AFTER** (line 536-562)
   ```java
   TransactionTypeCategoryService.TypeResult typeResult = 
       transactionTypeCategoryService.determineTransactionType(
           account,
           transaction.getCategoryPrimary(),
           transaction.getCategoryDetailed(),
           transactionAmount,  // ⭐ Uses NORMALIZED amount (line 545)
           null, // No transaction type indicator for Plaid
           transaction.getDescription(),
           paymentChannel
       );
   ```
   - Uses **normalized amount** (line 545: `transactionAmount` which is `transaction.getAmount()`)

---

## Why This Order Matters

### 1. **Consistent Convention**
- Transaction type determination relies on amount sign:
  - **Positive amount** → Likely INCOME
  - **Negative amount** → Likely EXPENSE
- If normalization happened after, the type would be determined using Plaid's reverse convention, leading to incorrect types

### 2. **Account Type Handling**
- `normalizePlaidAmount()` handles different account types:
  - **Credit card accounts:** No normalization (already uses standard convention)
  - **Checking/savings accounts:** Reverse sign (Plaid's reverse convention → standard)
  - **Investment accounts:** May have special handling

### 3. **Transaction Type Logic**
- `TransactionTypeDeterminer.determineTransactionType()` uses amount sign as a key indicator:
  - Positive amounts suggest INCOME
  - Negative amounts suggest EXPENSE
  - This logic expects the **normalized** (standard convention) amount

---

## Code Evidence

### PlaidDataExtractor.java

**Line 316:** Amount normalization (FIRST)
```java
java.math.BigDecimal normalizedAmount = normalizePlaidAmount(rawAmount, account);
transaction.setAmount(normalizedAmount);
```

**Line 360:** Get normalized amount for use
```java
java.math.BigDecimal transactionAmount = transaction.getAmount(); // This is the normalized amount
```

**Line 545:** Transaction type determination uses normalized amount (AFTER)
```java
transactionTypeCategoryService.determineTransactionType(
    account,
    transaction.getCategoryPrimary(),
    transaction.getCategoryDetailed(),
    transactionAmount,  // ⭐ Normalized amount (from line 360)
    ...
)
```

---

## normalizePlaidAmount() Method

**File:** `PlaidDataExtractor.java` (line ~100-180)

**What it does:**
- For **checking/savings/debit/money_market accounts:** Reverses sign (Plaid's `-ve = income, +ve = expense` → System's `+ve = income, -ve = expense`)
- For **credit card accounts:** No change (already uses standard convention)
- For **investment accounts:** May have special handling
- For **loan accounts:** May have special handling

**Example:**
- Plaid sends: `+100.00` (expense) for checking account
- After normalization: `-100.00` (expense) - standard convention
- Transaction type determination: Sees `-100.00` → Determines EXPENSE ✅

---

## Summary

| Step | Operation | Line | Uses Amount |
|------|-----------|------|-------------|
| 1 | Extract raw amount from Plaid | 284-288 | Raw (Plaid convention) |
| 2 | **Normalize amount** ⭐ | 316 | Raw → Normalized |
| 3 | Set normalized amount on transaction | 317 | Normalized |
| 4 | Extract basic fields | 319-357 | - |
| 5 | Determine Category | 374-513 | Normalized |
| 6 | **Determine TransactionType** ⭐ | 536-562 | Normalized |

**Key Point:** Sign normalization happens **BEFORE** transaction type determination, ensuring the type logic uses amounts in the standard convention (`+ve = income, -ve = expense`).
