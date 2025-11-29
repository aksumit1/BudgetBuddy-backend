# Account Deduplication Logic Sync - Backend and iOS App

## Summary

Fixed critical deduplication bug where accounts with **null institutionName** were not being deduplicated, causing duplicates when:
- Access token was regenerated (new Plaid account IDs)
- Multiple link sessions occurred (different Plaid account IDs)
- Institution name was missing from Plaid response

## Changes Made

### Backend (Java)

#### 1. **AccountRepository.java**
- ✅ Updated `findByAccountNumberAndInstitution()` to work with just `accountNumber` when `institutionName` is null
- ✅ Added new method `findByAccountNumber()` for cases where `institutionName` is missing

**Before:**
```java
if (accountNumber == null || accountNumber.isEmpty() || 
    institutionName == null || institutionName.isEmpty()) {
    return Optional.empty(); // FAILS if institutionName is null
}
```

**After:**
```java
if (accountNumber == null || accountNumber.isEmpty()) {
    return Optional.empty();
}

if (institutionName == null || institutionName.isEmpty()) {
    // Match by accountNumber only
    return userAccounts.stream()
        .filter(account -> accountNumber.equals(account.getAccountNumber()))
        .findFirst();
} else {
    // Match by both when available
    return userAccounts.stream()
        .filter(account -> accountNumber.equals(account.getAccountNumber()) 
            && institutionName.equals(account.getInstitutionName()))
        .findFirst();
}
```

#### 2. **PlaidSyncService.java**
- ✅ Updated all three deduplication checks to handle null `institutionName`:
  1. Primary check (after `findByPlaidAccountId`)
  2. Comprehensive scan (before creating new account)
  3. Final check (last resort before creation)

**Key Changes:**
- Checks by `accountNumber` even if `institutionName` is null
- Updates existing accounts with missing `plaidAccountId` when found
- Updates existing accounts with missing `institutionName` when available

### iOS App (Swift)

#### 1. **AppViewModel.swift**
- ✅ Updated deduplication logic in two places:
  - `mergeAccountsFromBackend()` method
  - `fetchAccountsDirectlyFromBackend()` method

**Before:**
```swift
else if let accountNumber = account.accountNumber, !accountNumber.isEmpty,
        !account.institutionName.isEmpty { // FAILS if institutionName is empty
    let key = "\(account.institutionName)|\(accountNumber)"
    // ...
}
```

**After:**
```swift
else if let accountNumber = account.accountNumber, !accountNumber.isEmpty {
    if !account.institutionName.isEmpty {
        // Both available - match by both
        let key = "\(account.institutionName)|\(accountNumber)"
        // ...
    } else {
        // Institution name missing - match by accountNumber only
        // Search through existing accounts by accountNumber
        for (key, existing) in existingAccountsByAccountNumber {
            if key.hasSuffix("|\(accountNumber)") {
                isDuplicate = true
                existingAccount = existing
                break
            }
        }
        // Also check accounts that might not be in the index
        if !isDuplicate {
            for existing in accounts {
                if let existingNumber = existing.accountNumber, 
                   existingNumber == accountNumber,
                   existing.institutionName.isEmpty {
                    isDuplicate = true
                    existingAccount = existing
                    break
                }
            }
        }
    }
}
```

- ✅ Updated indexing logic to handle null `institutionName`:
  - Indexes by `"|accountNumber"` when `institutionName` is empty
  - Indexes by `"institutionName|accountNumber"` when both are available

## Integration Tests Added

### Backend Tests

#### 1. **AccountDeduplicationNullInstitutionIntegrationTest.java**
New test class with 6 test cases:
- ✅ `testAccountDeduplication_WithNullInstitutionName_MatchesByAccountNumber`
- ✅ `testAccountDeduplication_WithNullInstitutionName_NewAccountWithSameNumber_DoesNotCreateDuplicate`
- ✅ `testAccountDeduplication_WithNullInstitutionName_NewAccountWithInstitutionName_UpdatesExisting`
- ✅ `testFindByAccountNumberAndInstitution_WithNullInstitutionName_ReturnsEmpty`
- ✅ `testFindByAccountNumberAndInstitution_WithNullInstitutionName_MatchesByAccountNumberOnly`
- ✅ `testAccountDeduplication_AccessTokenRegenerated_WithNullInstitutionName_PreventsDuplicate`

#### 2. **PlaidDeduplicationIntegrationTest.java**
Added 2 new test cases:
- ✅ `testAccountDeduplication_WithNullInstitutionName_MatchesByAccountNumber`
- ✅ `testAccountDeduplication_AccessTokenRegenerated_WithNullInstitutionName_PreventsDuplicate`

### iOS App Tests

#### **DeduplicationTests.swift**
Added 3 new test cases:
- ✅ `testAccountDeduplication_WithNullInstitutionName_MatchesByAccountNumber`
- ✅ `testAccountDeduplication_WithNullInstitutionName_NewAccountWithInstitutionName_UpdatesExisting`
- ✅ `testAccountDeduplication_AccessTokenRegenerated_WithNullInstitutionName_PreventsDuplicate`

- ✅ Updated `deduplicateAccounts()` helper method to match AppViewModel logic:
  - Uses normalized UUID strings
  - Handles null `institutionName` in deduplication
  - Indexes accounts by accountNumber even when institutionName is null

## Test Scenarios Covered

### Scenario 1: Access Token Regenerated + Null Institution Name
- **Given**: Existing account with `accountNumber="0000"`, `institutionName=null`, `plaidAccountId="old-123"`
- **When**: New account comes in with `accountNumber="0000"`, `institutionName=null`, `plaidAccountId="new-456"`
- **Expected**: Should find existing account by `accountNumber`, update with new Plaid ID, prevent duplicate

### Scenario 2: Multiple Link Sessions + Null Institution Name
- **Given**: Existing account with `accountNumber="1234"`, `institutionName=null`
- **When**: New account from different link session with same `accountNumber`
- **Expected**: Should deduplicate by `accountNumber` only

### Scenario 3: Institution Name Becomes Available
- **Given**: Existing account with `accountNumber="5678"`, `institutionName=null`
- **When**: New account with same `accountNumber` but `institutionName="Test Bank"`
- **Expected**: Should find existing account, update with `institutionName`

## How to Run Tests

### Backend Tests
```bash
cd BudgetBuddy-Backend
mvn test -Dtest=AccountDeduplicationNullInstitutionIntegrationTest
mvn test -Dtest=PlaidDeduplicationIntegrationTest
```

### iOS App Tests
```bash
cd BudgetBuddy
xcodebuild test -scheme BudgetBuddy \
  -destination 'platform=iOS Simulator,name=iPhone 15,OS=latest' \
  -only-testing:BudgetBuddyTests/DeduplicationTests
```

## Verification

### Before Fix
- ❌ Accounts with null `institutionName` created duplicates
- ❌ Access token regeneration caused duplicates
- ❌ Multiple link sessions created duplicates

### After Fix
- ✅ Accounts deduplicated by `accountNumber` even when `institutionName` is null
- ✅ Access token regeneration updates existing accounts instead of creating duplicates
- ✅ Multiple link sessions deduplicate correctly
- ✅ Logic is consistent between backend and iOS app

## Files Modified

### Backend
- `src/main/java/com/budgetbuddy/repository/dynamodb/AccountRepository.java`
- `src/main/java/com/budgetbuddy/service/PlaidSyncService.java`
- `src/test/java/com/budgetbuddy/integration/AccountDeduplicationNullInstitutionIntegrationTest.java` (NEW)
- `src/test/java/com/budgetbuddy/integration/PlaidDeduplicationIntegrationTest.java`

### iOS App
- `BudgetBuddy/ViewModels/AppViewModel.swift`
- `BudgetBuddyTests/DeduplicationTests.swift`

