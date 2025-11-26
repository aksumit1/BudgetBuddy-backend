# Today's Bug Fixes - Test Coverage

## Bugs Fixed Today

### 1. **Null Category in Transactions** ✅
**Issue**: Backend was returning `"category": null` for transactions, causing iOS app decoding failures.

**Fix**: 
- iOS `BackendTransaction` decoder now defaults to `"other"` when category is null
- Backend `PlaidSyncService` ensures category is never null (defaults to "Other")

**Tests Needed**:
- ✅ `BackendTransaction` decoding with null category
- ✅ Transaction sync with null category from Plaid
- ✅ Transaction creation with null category defaults to "Other"

### 2. **ISO8601 Date Strings Instead of Int64** ✅
**Issue**: Backend was returning ISO8601 date strings (e.g., `"2025-11-26T09:29:28.821798377Z"`) instead of Int64 epoch seconds for `createdAt`, `updatedAt`, `lastSyncedAt`.

**Fix**:
- iOS `BackendAccount` and `BackendTransaction` decoders now handle both Int64 and ISO8601 strings
- Backend should ideally return Int64, but iOS handles both for compatibility

**Tests Needed**:
- ✅ `BackendAccount` decoding with ISO8601 date strings
- ✅ `BackendTransaction` decoding with ISO8601 date strings
- ✅ Backend DTO serialization (should return Int64)

### 3. **Account Sync - Null Active Field** ✅
**Issue**: Accounts with `active = null` were being filtered out by `AccountRepository.findByUserId()`, causing accounts to not appear in the app.

**Fix**:
- `AccountRepository.findByUserId()` now includes accounts where `active` is `null` (treats them as active)
- `PlaidSyncService.updateAccountFromPlaid()` now always sets `active = true` for new accounts

**Tests Needed**:
- ✅ Account sync sets `active = true` for new accounts
- ✅ `AccountRepository.findByUserId()` includes null-active accounts
- ✅ Account sync updates existing accounts without changing active status

### 4. **Transaction Sync - Date Format** ✅
**Issue**: Transaction date was not being set correctly, causing date range queries to fail.

**Fix**:
- `PlaidSyncService.updateTransactionFromPlaid()` now correctly sets `transactionDate` in "YYYY-MM-DD" format
- Transaction date is extracted from Plaid Transaction object

**Tests Needed**:
- ✅ Transaction sync sets correct date format ("YYYY-MM-DD")
- ✅ Transaction date range queries work correctly
- ✅ Transaction sync handles missing dates gracefully

### 5. **Individual Item Failures Don't Block Entire Load** ✅
**Issue**: If one transaction or account failed to decode/convert, the entire load would fail.

**Fix**:
- iOS app now processes each item individually
- Failed items are logged but don't stop the load
- Successfully converted items are returned even if some fail

**Tests Needed**:
- ✅ iOS app handles partial failures gracefully
- ✅ Backend returns valid data even if some items have issues

