# Transaction Type Consolidation

## Summary

All transaction type determination logic has been consolidated into a single place: `TransactionTypeDeterminer` in the backend.

## Backend Logic Consolidation

### Single Source of Truth
- **Location**: `com.budgetbuddy.service.TransactionTypeDeterminer`
- **Method**: `determineTransactionType(AccountTable, String, String, BigDecimal)`
- **Returns**: `TransactionType` enum (INCOME, INVESTMENT, LOAN, EXPENSE)

### Where TransactionType is Set

1. **Transaction Creation** (`TransactionService.createTransaction()`)
   - Sets `transactionType` when creating new transactions
   - Uses `TransactionTypeDeterminer` with account, category, and amount

2. **Transaction Updates** (`TransactionService.updateTransaction()`)
   - **Recalculates** `transactionType` when:
     - Category changes (`categoryPrimary` or `categoryDetailed`)
     - Amount changes
   - Ensures `transactionType` stays in sync with category/amount updates

3. **Plaid Sync** (`PlaidDataExtractor.updateTransactionFromPlaid()`)
   - Sets `transactionType` for all transactions synced from Plaid
   - Uses `TransactionTypeDeterminer` after all fields are extracted

### Transaction Type Determination Rules

Priority order:
1. **INVESTMENT**: Account type is investment-related (401K, IRA, HSA, 529, Certificates, Bonds, Treasury, money market) OR category is investment-related (CD, stocks, bonds, etc.)
2. **LOAN**: Account type is loan-related (mortgage, credit card, student loan, home loan)
3. **INCOME**: `categoryPrimary == "income"` OR positive amount with income categories (interest, salary, dividend, etc.)
4. **EXPENSE**: Default for everything else

## iOS App

### No Duplicate Logic
- iOS app **does NOT** derive `transactionType` from categories
- iOS app **reads** `transactionType` directly from backend API response
- `transactionType` is stored in `Transaction` model and used for display/filtering

### Category Updates
- When iOS updates a transaction category, it sends `categoryPrimary` and `categoryDetailed` to backend
- Backend automatically recalculates `transactionType` when categories are updated
- iOS receives the updated `transactionType` in the response

### Note on `getTransactionType()`
- `AppViewModel.getTransactionType()` is for **UI purposes only** (TransactionQuickActionsView)
- It returns `TransactionQuickActionsView.TransactionType` (different from backend `TransactionType`)
- It is **NOT** used to derive backend `transactionType` - backend is the source of truth

## Sync Flow

### When Transaction is Created
1. iOS sends transaction with `categoryPrimary` and `categoryDetailed`
2. Backend `TransactionService.createTransaction()` calls `TransactionTypeDeterminer`
3. Backend sets `transactionType` and saves to database
4. Backend returns transaction with `transactionType` to iOS
5. iOS stores `transactionType` in local `Transaction` model

### When Category is Updated
1. iOS sends PUT request with new `categoryPrimary` and `categoryDetailed`
2. Backend `TransactionService.updateTransaction()` detects category change
3. Backend recalculates `transactionType` using `TransactionTypeDeterminer`
4. Backend saves updated `transactionType` to database
5. Backend returns transaction with updated `transactionType` to iOS
6. iOS updates local `Transaction` model with new `transactionType`

### When Amount is Updated
1. iOS sends PUT request with new `amount`
2. Backend `TransactionService.updateTransaction()` detects amount change
3. Backend recalculates `transactionType` using `TransactionTypeDeterminer`
4. Backend saves updated `transactionType` to database
5. Backend returns transaction with updated `transactionType` to iOS

## Testing

All transaction type determination logic is tested in:
- `TransactionTypeDeterminerTest.java` (19 tests, all passing)
- Tests cover: Investment accounts, Loan accounts, Income categories, Expense defaults, Priority rules

## Conclusion

✅ **All transaction type logic is consolidated in one place** (`TransactionTypeDeterminer`)
✅ **No duplicate logic** - iOS app reads `transactionType` from backend
✅ **Sync works correctly** - `transactionType` is recalculated when categories or amount change
✅ **Backend is the source of truth** for transaction type determination

