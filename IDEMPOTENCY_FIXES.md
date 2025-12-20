# Backend Idempotency Fixes

## Overview
All POST endpoints that create resources have been updated to be idempotent. This means that sending the same request multiple times will return the same result without creating duplicates.

## Fixed Endpoints

### 1. Transaction Creation (`POST /api/transactions`)
**File**: `TransactionService.java`

**Fix**: 
- If a transaction with the provided `transactionId` already exists and belongs to the same user:
  - **If Plaid ID is provided and matches**: Return the existing transaction (idempotent)
  - **If Plaid ID is provided and doesn't match**: Generate a new UUID to prevent data corruption
  - **If Plaid ID is not provided**: Return the existing transaction (idempotent for manual transactions)
  - **If Plaid ID is provided but existing transaction doesn't have one**: Return existing (will be updated with Plaid ID)
- UUIDs are normalized to lowercase for DynamoDB consistency

**Key Logic** (lines 297-334):
```java
if (existingOpt.isPresent()) {
    TransactionTable existing = existingOpt.get();
    if (!existing.getUserId().equals(user.getUserId())) {
        // Security: different user - generate new UUID
    } else {
        // Same user - check Plaid ID matching
        if (requestHasPlaidId && plaidTransactionId != null) {
            if (existingHasPlaidId && providedPlaidId.equals(existingPlaidId)) {
                return existing; // Idempotent: Plaid ID matches
            } else if (existingHasPlaidId && !providedPlaidId.equals(existingPlaidId)) {
                // Generate new UUID: Plaid ID conflict
            } else {
                return existing; // Update existing with Plaid ID
            }
        } else {
            return existing; // Idempotent: no Plaid ID in request
        }
    }
}
```

**Behavior**:
- **Plaid ID exists and matches**: Return same ID (idempotent) ✅
- **Plaid ID exists and doesn't match**: Generate new ID (prevents data corruption) ⚠️
- **Plaid ID doesn't exist or not provided**: Return same ID (idempotent) ✅

### 2. Transaction Action Creation (`POST /api/transactions/{transactionId}/actions`)
**File**: `TransactionActionService.java`

**Fix**:
- If an action with the provided `actionId` already exists and belongs to the same user and transaction, return the existing action
- UUIDs are normalized to lowercase

**Key Logic** (lines 133-148):
```java
if (existingById.isPresent()) {
    TransactionActionTable existing = existingById.get();
    if (existing.getUserId().equals(user.getUserId()) && 
        existing.getTransactionId().equals(actualTransactionId)) {
        return existing; // Idempotent
    }
}
```

### 3. Goal Creation (`POST /api/goals`)
**File**: `GoalService.java`

**Fix**:
- If a goal with the provided `goalId` already exists and belongs to the same user, return the existing goal
- UUIDs are normalized to lowercase
- Deterministic ID generation from user + goal name ensures consistency

**Key Logic** (lines 59-77):
```java
if (existingById.isPresent()) {
    GoalTable existing = existingById.get();
    if (existing.getUserId().equals(user.getUserId())) {
        return existing; // Idempotent
    }
}
```

### 4. Budget Creation (`POST /api/budgets`)
**File**: `BudgetService.java`

**Fix**:
- Primary check: If a budget exists for the same user + category, update and return it (idempotent)
- Secondary check: If a budget with the provided `budgetId` exists and matches user + category, update and return it
- UUIDs are normalized to lowercase
- Deterministic ID generation from user + category ensures consistency

**Key Logic** (lines 55-79):
```java
// Primary check: by userId + category
Optional<BudgetTable> existing = budgetRepository.findByUserIdAndCategory(user.getUserId(), category);
if (existing.isPresent()) {
    budget = existing.get();
    budget.setMonthlyLimit(monthlyLimit);
    return budget; // Idempotent
}

// Secondary check: by provided budgetId
if (existingById.isPresent()) {
    BudgetTable existingByIdTable = existingById.get();
    if (existingByIdTable.getUserId().equals(user.getUserId()) && 
        existingByIdTable.getCategory().equals(category)) {
        existingByIdTable.setMonthlyLimit(monthlyLimit);
        return existingByIdTable; // Idempotent
    }
}
```

### 5. Account Creation (`POST /api/accounts`)
**File**: `AccountController.java`

**Fix**:
- If an account with the provided `accountId` already exists and belongs to the same user, return the existing account
- UUIDs are normalized to lowercase

**Key Logic** (lines 111-140):
```java
if (existingById.isPresent()) {
    AccountTable existing = existingById.get();
    if (existing.getUserId().equals(user.getUserId())) {
        return ResponseEntity.status(HttpStatus.OK).body(existing); // Idempotent
    }
}
```

## Security Considerations

All idempotency checks verify that:
1. The existing resource belongs to the same user (prevents unauthorized access)
2. For transaction actions, the action belongs to the same transaction
3. For budgets, the budget matches the same user + category

If a resource exists but belongs to a different user or doesn't match the expected criteria, a new UUID is generated to prevent security issues.

## UUID Normalization

All UUIDs are normalized to lowercase for DynamoDB consistency:
- DynamoDB partition keys are case-sensitive
- Normalization ensures consistent lookups and prevents duplicate entries due to case differences
- Applied to both provided IDs and generated IDs

## Benefits

1. **Prevents Duplicate Resources**: Multiple identical requests won't create duplicates
2. **Network Resilience**: Retries after network failures won't create duplicates
3. **Consistent Behavior**: Same request always returns same result
4. **Better User Experience**: No duplicate transactions, goals, budgets, or accounts

## Testing Recommendations

For each endpoint, test:
1. First POST request creates the resource
2. Second identical POST request returns the existing resource (same ID)
3. POST request with different user ID generates new UUID (security)
4. POST request with invalid UUID format generates new UUID

