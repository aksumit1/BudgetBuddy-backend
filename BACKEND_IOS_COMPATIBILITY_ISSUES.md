# Backend-iOS Compatibility Issues

## Summary
This document lists all identified incompatibilities between backend DTOs and iOS models, along with fixes.

## 1. AuthResponse ✅ FIXED
**Issue**: Backend sends `accessToken`, iOS expected `token`
**Status**: ✅ Fixed in `AuthResponse.swift`

## 2. TransactionTable vs Transaction

### Backend (TransactionTable)
- `transactionId` (String)
- `userId` (String)
- `accountId` (String)
- `amount` (BigDecimal)
- `description` (String)
- `merchantName` (String)
- `category` (String)
- `transactionDate` (String, format: "YYYY-MM-DD")
- `currencyCode` (String)
- `plaidTransactionId` (String)
- `pending` (Boolean)
- `createdAt` (Instant - epoch seconds)
- `updatedAt` (Instant - epoch seconds)

### iOS (Transaction)
- `id` (UUID)
- `accountID` (UUID)
- `date` (Date)
- `description` (String)
- `amount` (Double)
- `category` (TransactionCategory enum)

### Issues
1. ❌ ID type mismatch: String vs UUID
2. ❌ Date format: String "YYYY-MM-DD" vs Date
3. ❌ Amount type: BigDecimal vs Double
4. ❌ Missing fields: `merchantName`, `currencyCode`, `plaidTransactionId`, `pending`, `createdAt`, `updatedAt`
5. ❌ Category: String vs enum

## 3. AccountTable vs Account

### Backend (AccountTable)
- `accountId` (String)
- `userId` (String)
- `accountName` (String)
- `institutionName` (String)
- `accountType` (String)
- `accountSubtype` (String)
- `balance` (BigDecimal)
- `currencyCode` (String)
- `plaidAccountId` (String)
- `plaidItemId` (String)
- `active` (Boolean)
- `lastSyncedAt` (Instant)
- `createdAt` (Instant)
- `updatedAt` (Instant)

### iOS (Account)
- `id` (UUID)
- `institutionName` (String)
- `accountName` (String)
- `type` (AccountType enum)
- `balance` (Double)
- `currencyCode` (String)

### Issues
1. ❌ ID type mismatch: String vs UUID
2. ❌ Account type: String vs enum
3. ❌ Amount type: BigDecimal vs Double
4. ❌ Missing fields: `userId`, `accountSubtype`, `plaidAccountId`, `plaidItemId`, `active`, `lastSyncedAt`, `createdAt`, `updatedAt`

## 4. BudgetTable vs BudgetCategory

### Backend (BudgetTable)
- `budgetId` (String)
- `userId` (String)
- `category` (String)
- `monthlyLimit` (BigDecimal)
- `currentSpent` (BigDecimal)
- `currencyCode` (String)
- `createdAt` (Instant)
- `updatedAt` (Instant)

### iOS (BudgetCategory)
- `id` (UUID)
- `category` (TransactionCategory enum)
- `monthlyLimit` (Double)

### Issues
1. ❌ ID type mismatch: String vs UUID
2. ❌ Category: String vs enum
3. ❌ Amount type: BigDecimal vs Double
4. ❌ Missing fields: `userId`, `currentSpent`, `currencyCode`, `createdAt`, `updatedAt`

## 5. GoalTable vs Goal

### Backend (GoalTable)
- `goalId` (String)
- `userId` (String)
- `name` (String)
- `description` (String)
- `targetAmount` (BigDecimal)
- `currentAmount` (BigDecimal)
- `targetDate` (String, ISO format)
- `monthlyContribution` (BigDecimal)
- `goalType` (String)
- `currencyCode` (String)
- `active` (Boolean)
- `createdAt` (Instant)
- `updatedAt` (Instant)

### iOS (Goal)
- `id` (UUID)
- `name` (String)
- `targetAmount` (Double)
- `currentAmount` (Double)
- `targetDate` (Date?)
- `monthlyContribution` (Double?)
- `createdAt` (Date?)

### Issues
1. ❌ ID type mismatch: String vs UUID
2. ❌ Date format: String ISO vs Date
3. ❌ Amount type: BigDecimal vs Double
4. ❌ Missing fields: `userId`, `description`, `goalType`, `currencyCode`, `active`, `updatedAt`

## 6. Date/Time Format Issues

### Backend Formats
- `Instant`: Serialized as epoch seconds (Long) or ISO8601 string
- `LocalDate`: Serialized as "YYYY-MM-DD" string
- `LocalDateTime`: Serialized as "YYYY-MM-DDTHH:mm:ss" string

### iOS Formats
- `Date`: Expects ISO8601 string or epoch seconds (TimeInterval)

### Issues
1. ❌ `LocalDate` format "YYYY-MM-DD" may not parse correctly as Date
2. ❌ `LocalDateTime` format "YYYY-MM-DDTHH:mm:ss" (no timezone) may not parse correctly
3. ❌ `Instant` as epoch seconds (Long) vs TimeInterval (Double)

## Fix Strategy

1. **Create iOS Response Models** that match backend DTOs exactly
2. **Add custom Codable decoders** to handle:
   - String IDs → UUID conversion
   - String dates → Date conversion
   - BigDecimal → Double conversion
   - String enums → Enum conversion
3. **Add integration tests** to verify compatibility
4. **Update iOS models** to include all backend fields (optional, for future use)

