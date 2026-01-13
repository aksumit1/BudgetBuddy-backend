# Multiple determineTransactionType Methods - Call Order

## Overview

There are **3 different `determineTransactionType` methods** in the codebase, each used in different scenarios. This document explains which ones run and in what order.

---

## Method 1: `TransactionTypeCategoryService.determineTransactionTypeFromAccountType()`

**File:** `TransactionTypeCategoryService.java` (line 123-248)

**Purpose:** Used for **PDF/CSV imports** when account object is not available, but account type string is known.

**Signature:**
```java
public TypeResult determineTransactionTypeFromAccountType(
    String accountType,        // Account type string (e.g., "credit", "depository")
    String accountSubtype,     // Account subtype (e.g., "checking", "credit card")
    BigDecimal amount,
    String description,
    String paymentChannel)
```

**When it runs:**
- **PDF Import:** `PDFImportService.java` (line 4191)
- **CSV Import:** `CSVImportService.java` (line 1588) - Primary method when account type string is available

**Logic:**
- Determines type based on account type string + amount sign
- Returns early (doesn't call other methods)
- Returns `TypeResult` with type and source "ACCOUNT_TYPE"

**Example:**
```java
// PDF Import
TypeResult typeResult = transactionTypeCategoryService.determineTransactionTypeFromAccountType(
    accountTypeString,      // "credit"
    accountSubtypeString,   // "credit_card"
    amount,
    description,
    paymentChannel
);
transaction.setTransactionType(typeResult.getTransactionType().name());
```

---

## Method 2: `TransactionTypeCategoryService.determineTransactionType()`

**File:** `TransactionTypeCategoryService.java` (line 262-650+)

**Purpose:** Main method used for **Plaid sync** and most cases. Uses hybrid logic (account type + category + amount + description).

**Signature:**
```java
@Cacheable(...)
public TypeResult determineTransactionType(
    AccountTable account,           // Full account object (not just string)
    String categoryPrimary,
    String categoryDetailed,
    BigDecimal amount,
    String transactionTypeIndicator,
    String description,
    String paymentChannel)
```

**When it runs:**
- **Plaid Sync:** `PlaidDataExtractor.java` (line 540-549)
- **TransactionService:** `TransactionService.java` (line 556, 1347)
- **CSV Import:** `CSVImportService.java` (line 1660) - Fallback if Method 1 returns null

**Call Order (Internal Logic):**
1. **Priority 0: Account type-based inference** (line 272-594)
   - **Credit card accounts:**
     - Positive amounts: If `isPaymentReceived(description)` → PAYMENT, else → EXPENSE
     - Negative amounts: Category-based logic (see below for details)
     - **Category-based logic for negative amounts:**
       - If category is "payment" or description contains payment keywords → PAYMENT
       - If category is expense category (dining, shopping, groceries, etc.) → EXPENSE
       - Default (no category match, no payment keywords) → EXPENSE
   - **Investment accounts:**
     - Positive amounts: Check for transfers/deposits → INVESTMENT, else → INCOME (dividends, interest)
     - Negative amounts: Check for fees → EXPENSE, else → INVESTMENT (purchases)
   - Loan accounts
   - Checking/savings accounts (with credit card payment detection)
   - If match found, returns early with `TypeResult`

2. **Priority 1: Transaction type indicator** (line ~595)
   - If `transactionTypeIndicator` is provided (from imports), uses it
   - Returns early if indicator is valid

3. **Priority 2: Calls `TransactionTypeDeterminer.determineTransactionType()`** ⭐ (line 597)
   ```java
   TransactionType baseType = transactionTypeDeterminer.determineTransactionType(
       account, categoryPrimary, categoryDetailed, amount);
   ```
   - This calls **Method 3** below
   - Uses the result as a "base type"

4. **Priority 3: Override logic** (line 600-650+)
   - Overrides `PAYMENT` type for checking accounts with positive amounts → `INCOME`
   - Other override logic based on account type and amount

**Example:**
```java
// Plaid Sync
TypeResult typeResult = transactionTypeCategoryService.determineTransactionType(
    account,                    // Full AccountTable object
    transaction.getCategoryPrimary(),
    transaction.getCategoryDetailed(),
    transactionAmount,          // Normalized amount
    null,                       // No indicator for Plaid
    transaction.getDescription(),
    paymentChannel
);
transaction.setTransactionType(typeResult.getTransactionType().name());
```

---

## Method 3: `TransactionTypeDeterminer.determineTransactionType()`

**File:** `TransactionTypeDeterminer.java` (line 39-109)

**Purpose:** Base/simple logic used internally by `TransactionTypeCategoryService.determineTransactionType()`. Does NOT determine PAYMENT type.

**Signature:**
```java
public TransactionType determineTransactionType(
    final AccountTable account,
    final String categoryPrimary,
    final String categoryDetailed,
    final BigDecimal amount)
```

**When it runs:**
- **Called internally** by `TransactionTypeCategoryService.determineTransactionType()` (line 597)
- **Directly called** by `TransactionService` fallback logic (line 468, 527)

**Call Order (Internal Logic):**
1. **INVESTMENT** (highest priority)
   - Account type is investment-related
   - OR category is investment-related

2. **INCOME**
   - Category primary is "income"
   - OR category detailed is income-related
   - OR positive amount (with exceptions)

3. **EXPENSE** (default)
   - Everything else

**Note:** This method does NOT determine PAYMENT type - that's done by `TransactionTypeCategoryService`.

**Example:**
```java
// Called internally by TransactionTypeCategoryService
TransactionType baseType = transactionTypeDeterminer.determineTransactionType(
    account, categoryPrimary, categoryDetailed, amount);
// Returns: INCOME, INVESTMENT, or EXPENSE (NOT PAYMENT)
```

---

## Complete Call Flow

### Scenario 1: Plaid Sync

```
PlaidDataExtractor.updateTransactionFromPlaid()
    ↓
TransactionTypeCategoryService.determineTransactionType()  ← Method 2
    ↓
    Priority 0: Account type inference (credit card, investment, loan, checking)
        ├─ Credit card: Category-based logic for negative amounts (expense categories → EXPENSE, payment keywords → PAYMENT)
        ├─ Investment: Transfer/deposit detection for positive amounts (transfers → INVESTMENT, else → INCOME)
        ├─ Match found? → Return TypeResult (STOP)
        └─ No match? → Continue
    ↓
    Priority 1: Transaction type indicator
        ├─ Valid indicator? → Return TypeResult (STOP)
        └─ No indicator? → Continue
    ↓
    TransactionTypeDeterminer.determineTransactionType()  ← Method 3
        ├─ INVESTMENT check
        ├─ INCOME check
        └─ EXPENSE (default)
    ↓
    Priority 3: Override logic (e.g., PAYMENT → INCOME for checking accounts)
    ↓
    Return TypeResult
```

### Scenario 2: PDF Import

```
PDFImportService.parseTransactionRow()
    ↓
TransactionTypeCategoryService.determineTransactionTypeFromAccountType()  ← Method 1
    ↓
    Account type string + amount sign logic
    ↓
    Return TypeResult (STOP - doesn't call other methods)
```

### Scenario 3: CSV Import

```
CSVImportService.parseTransactionRow()
    ↓
TransactionTypeCategoryService.determineTransactionTypeFromAccountType()  ← Method 1 (line 1588)
    ↓
    If accountTypeString is available:
        ├─ Account type string + amount sign logic
        └─ Return TypeResult (STOP - doesn't call other methods)
    ↓
    If Method 1 returns null OR accountTypeString is null:
        ↓
    TransactionTypeCategoryService.determineTransactionType()  ← Method 2 (line 1660)
        ↓
        (Same flow as Plaid Sync above)
        ↓
        TransactionTypeDeterminer.determineTransactionType()  ← Method 3 (line 597)
```

### Scenario 4: TransactionService Fallback

```
TransactionService.setTransactionTypeFromUnifiedServiceOrCalculate()
    ↓
TransactionTypeCategoryService.determineTransactionType()  ← Method 2
    ↓
    (Same flow as Plaid Sync above)
```

**OR**

```
TransactionService.setTransactionTypeFromUserOrCalculate()
    ↓
TransactionTypeDeterminer.determineTransactionType()  ← Method 3 (direct call, bypasses Method 2)
    ↓
    Simple logic (INVESTMENT → INCOME → EXPENSE)
```

---

## Summary Table

| Method | File | Line | When Used | Calls Other Methods? |
|--------|------|------|-----------|---------------------|
| **1. determineTransactionTypeFromAccountType()** | TransactionTypeCategoryService.java | 123-248 | PDF Import (line 4191), CSV Import (line 1588) - when account type string available | ❌ No - Returns early |
| **2. determineTransactionType()** | TransactionTypeCategoryService.java | 262-650+ | Plaid Sync (line 541), CSV Import fallback (line 1660), TransactionService (line 556, 1347) | ✅ Yes - Calls Method 3 (line 597) |
| **3. determineTransactionType()** | TransactionTypeDeterminer.java | 39-109 | Called by Method 2 (line 597), or directly by TransactionService fallback (line 468, 527) | ❌ No - Base logic |

---

## Key Differences

### Method 1 vs Method 2

| Aspect | Method 1 (FromAccountType) | Method 2 (Main) |
|--------|---------------------------|-----------------|
| **Input** | Account type **string** | Full **AccountTable** object |
| **Used For** | PDF Import (line 4191), CSV Import primary (line 1588) | Plaid sync (line 541), CSV Import fallback (line 1660), TransactionService |
| **Complexity** | Simple (account type + amount) | Complex (account + category + description + overrides) |
| **Returns PAYMENT?** | ✅ Yes | ✅ Yes (via override logic) |
| **Calls Method 3?** | ❌ No | ✅ Yes (line 597) |

### Method 2 vs Method 3

| Aspect | Method 2 (TransactionTypeCategoryService) | Method 3 (TransactionTypeDeterminer) |
|--------|------------------------------------------|--------------------------------------|
| **Calls Method 3?** | ✅ Yes (uses as base) | ❌ No |
| **Determines PAYMENT?** | ✅ Yes | ❌ No |
| **Override Logic?** | ✅ Yes (complex overrides) | ❌ No (simple priority) |
| **Cached?** | ✅ Yes (@Cacheable) | ❌ No |

---

## Important Notes

1. **Method 1 (`determineTransactionTypeFromAccountType`) is only used for PDF imports** when account object is not available
2. **Method 2 (`TransactionTypeCategoryService.determineTransactionType`) is the main method** used for Plaid and most cases
3. **Method 3 (`TransactionTypeDeterminer.determineTransactionType`) is called internally** by Method 2, or directly as a fallback
4. **Method 3 does NOT determine PAYMENT type** - that logic is in Method 2
5. **Method 2 can return early** at Priority 0 (account type inference) without calling Method 3
6. **Order matters**: Method 2 checks account type first, then falls back to Method 3, then applies overrides
