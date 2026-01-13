# TransactionType Assignment Flow

This document explains where and how `TransactionType` is allocated/assigned for transactions in the BudgetBuddy backend.

## Flow Overview

### Entry Points
1. **Plaid Sync** (`TransactionSyncService.java`)
   - `syncTransactions()` - Full sync
   - `syncIncremental()` - Incremental sync
   
2. **CSV Import** (`CSVImportService.java`)
   - `importTransactions()` - CSV/Excel import

3. **PDF Import** (`PDFImportService.java`)
   - `importTransactions()` - PDF import

4. **Manual/API** (`TransactionService.java`)
   - Transaction updates via API

---

## Main Flow: Plaid Sync (Most Common)

### Step 1: TransactionSyncService.createTransactionFromPlaid()
**File:** `TransactionSyncService.java` (line 535-640)

**What happens:**
1. Creates new `TransactionTable` object
2. Sets basic fields: `userId`, `plaidTransactionId`, `createdAt`, `updatedAt`
3. Calls `dataExtractor.updateTransactionFromPlaid(transaction, plaidTransaction)`

### Step 2: PlaidDataExtractor.updateTransactionFromPlaid()
**File:** `PlaidDataExtractor.java` (line 265-637)

**Order of operations:**

1. **Extract raw amount from Plaid** (line 284-288)
   - Gets amount from Plaid transaction

2. **Normalize Plaid amount** (line 290-308)
   - Plaid uses reverse convention for checking/savings: -ve = income, +ve = expense
   - Normalizes to standard: +ve = income, -ve = expense
   - Uses `normalizePlaidAmount()` method

3. **Extract basic fields** (line 310-335)
   - Description, merchant name, payment channel, etc.

4. **Determine Category** (line 337-513)
   - Calls `transactionTypeCategoryService.detectCategory()`
   - Sets `categoryPrimary` and `categoryDetailed`
   - Handles HSA special cases
   - Adjusts amount for credit card/loan payments

5. **Determine TransactionType** ⭐ (line 536-562)
   - **ONLY if user hasn't overridden** (`!Boolean.TRUE.equals(transaction.getTransactionTypeOverridden())`)
   - Calls `transactionTypeCategoryService.determineTransactionType()`
   - Sets `transaction.setTransactionType(typeResult.getTransactionType().name())`
   - Sets `transactionTypeOverridden = false` if null

6. **Fallback TransactionType determination** (line 638-682)
   - If earlier determination failed, tries again here
   - Same logic as step 5

---

## TransactionType Determination Logic

### TransactionTypeCategoryService.determineTransactionType()
**File:** `TransactionTypeCategoryService.java` (line 256-650+)

**What it does:**
1. **Priority 0:** Account type-based inference (credit card, investment, loan, checking)
   - Credit card: Category-based logic for negative amounts (expense categories → EXPENSE, payment keywords → PAYMENT)
   - Investment: Transfer/deposit detection for positive amounts (transfers → INVESTMENT, else → INCOME)
   - Checking/savings: Credit card payment detection (payments → PAYMENT)
2. **Priority 1:** Transaction type indicator (from imports)
3. **Priority 2:** Calls `TransactionTypeDeterminer.determineTransactionType()` as base logic
4. **Priority 3:** Override logic (e.g., PAYMENT → INCOME for checking accounts)

**Input parameters:**
- `AccountTable account` - Account information
- `String categoryPrimary` - Determined category primary
- `String categoryDetailed` - Determined category detailed  
- `BigDecimal amount` - Normalized amount
- `String transactionTypeIndicator` - Optional indicator from import
- `String description` - Transaction description
- `String paymentChannel` - Payment channel (ach, online, etc.)

**Returns:**
- `TypeResult` with:
  - `TransactionType` (INCOME, EXPENSE, INVESTMENT, PAYMENT)
  - `String source` (ACCOUNT_TYPE, CATEGORY, AMOUNT, HYBRID, etc.)
  - `double confidence` (0.0 to 1.0)

---

## TransactionTypeDeterminer Logic

### TransactionTypeDeterminer.determineTransactionType()
**File:** `TransactionTypeDeterminer.java` (line 39-105)

**Priority order:**

1. **INVESTMENT** (highest priority)
   - Account type is investment-related (401k, IRA, HSA, 529, CD, bonds, etc.)
   - OR category is investment-related

2. **INCOME**
   - Category primary is "income"
   - OR category detailed is income-related (salary, interest, dividend, etc.)
   - OR positive amount (and not clearly expense category)

3. **EXPENSE** (default)
   - Everything else

**Note:** PAYMENT type is determined by `TransactionTypeCategoryService`, not `TransactionTypeDeterminer`
- PAYMENT is determined based on account type + category + payment keywords
- See `TransactionTypeCategoryService.determineTransactionType()` for PAYMENT logic

---

## Update Flow (for existing transactions)

### TransactionSyncService.updateTransactionFromPlaid()
**File:** `TransactionSyncService.java` (line 646-651)

1. Calls `dataExtractor.updateTransactionFromPlaid(transaction, plaidTransaction)`
2. Same logic as creation flow
3. **TransactionType is recalculated UNLESS user has overridden it**

### PlaidDataExtractor.updateTransactionFromPlaid()
**File:** `PlaidDataExtractor.java` (line 265-637)

- Same flow as creation
- **Line 539:** Checks `transactionTypeOverridden` flag
- If `true`, skips TransactionType recalculation
- If `false` or `null`, recalculates TransactionType

---

## CSV/PDF Import Flow

### CSV Import
**File:** `CSVImportService.java`

1. Parses transaction from CSV
2. Determines account type
3. Calls `transactionTypeCategoryService.determineTransactionTypeFromAccountType()` (line 1585-1671)
4. Sets TransactionType (line 1708)

### PDF Import  
**File:** `PDFImportService.java`

1. Parses transaction from PDF
2. Determines account type
3. Calls `transactionTypeCategoryService.determineTransactionTypeFromAccountType()` (line 4192-4197)
4. Sets TransactionType (line 4202)

---

## Key Files Summary

| File | Role | Key Methods |
|------|------|-------------|
| `TransactionSyncService.java` | Plaid sync orchestration | `syncTransactions()`, `createTransactionFromPlaid()` |
| `PlaidDataExtractor.java` | Extract & map Plaid data | `updateTransactionFromPlaid()` - **Sets TransactionType** |
| `TransactionTypeCategoryService.java` | Unified type/category service | `determineTransactionType()` - **Determines TransactionType** |
| `TransactionTypeDeterminer.java` | Core type determination logic | `determineTransactionType()` - **Core logic** |
| `CSVImportService.java` | CSV import | Sets TransactionType for imports |
| `PDFImportService.java` | PDF import | Sets TransactionType for imports |
| `TransactionType.java` | Enum definition | `INCOME`, `EXPENSE`, `INVESTMENT`, `PAYMENT` |

---

## Important Notes

1. **User Override Protection:**
   - `transactionTypeOverridden` flag prevents recalculation
   - Check: `!Boolean.TRUE.equals(transaction.getTransactionTypeOverridden())`

2. **Order Matters:**
   - Amount normalization happens FIRST
   - Category determination happens SECOND  
   - TransactionType determination happens LAST (uses category + amount)

3. **TransactionType is determined AFTER category:**
   - TransactionType uses the determined category as input
   - So category determination affects TransactionType

4. **Multiple determination points:**
   - Main: Line 536-562 in `PlaidDataExtractor.updateTransactionFromPlaid()`
   - Fallback: Line 638-682 in `PlaidDataExtractor.updateTransactionFromPlaid()`

5. **PAYMENT type logic:**
   - Determined by `TransactionTypeCategoryService`, not `TransactionTypeDeterminer`
   - Based on account type (credit card/loan) + category ("payment") + payment keywords
