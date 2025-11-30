# Deprecated Code Removal Summary

## Overview

This document summarizes all deprecated code and endpoints that have been removed or marked as deprecated in the authentication overhaul.

## ✅ Removed Endpoints

### PIN Backend Endpoints (DELETED)
All PIN backend endpoints have been **completely removed**:

- ❌ `POST /api/pin` - Store PIN on backend
- ❌ `POST /api/pin/verify` - Verify PIN with backend
- ❌ `POST /api/pin/login` - Login with PIN
- ❌ `DELETE /api/pin/{deviceId}` - Delete PIN from backend

**Reason**: PIN is now local-only and used only to decrypt refresh token from Keychain. Backend no longer stores or verifies PINs.

**Migration**: iOS app must use local PIN only. PIN should decrypt refresh token locally, then use refresh token to get new access token.

## ⚠️ Deprecated Code (Marked for Removal)

### DevicePinService
- **Status**: `@Deprecated`
- **Location**: `src/main/java/com/budgetbuddy/service/DevicePinService.java`
- **Reason**: PIN backend endpoints removed
- **Action**: Kept for backward compatibility but should not be used for new code

### DevicePinRepository
- **Status**: `@Deprecated`
- **Location**: `src/main/java/com/budgetbuddy/repository/dynamodb/DevicePinRepository.java`
- **Reason**: PIN backend endpoints removed
- **Action**: Kept for backward compatibility but should not be used for new code

### DevicePinTable
- **Status**: `@Deprecated`
- **Location**: `src/main/java/com/budgetbuddy/model/dynamodb/DevicePinTable.java`
- **Reason**: PIN backend endpoints removed
- **Action**: Kept for backward compatibility but should not be used for new code

### DynamoDBTableManager.createDevicePinTable()
- **Status**: `@Deprecated` and commented out
- **Location**: `src/main/java/com/budgetbuddy/service/dynamodb/DynamoDBTableManager.java`
- **Reason**: PIN backend endpoints removed
- **Action**: Method is deprecated and table creation is disabled

### PlaidSyncService.syncTransactionsForAccount()
- **Status**: `@Deprecated`
- **Location**: `src/main/java/com/budgetbuddy/service/PlaidSyncService.java`
- **Reason**: Replaced by batched fetching in `syncTransactions()`
- **Action**: Kept for reference/testing but should not be used

### ComplianceController.exportDataDMA()
- **Status**: `@Deprecated`
- **Location**: `src/main/java/com/budgetbuddy/api/ComplianceController.java`
- **Reason**: Use `/api/dma/export` instead
- **Action**: Kept for backward compatibility

### iOS: SecurityService Client Salt Methods
- **Status**: `@available(*, deprecated)`
- **Location**: `BudgetBuddy/BudgetBuddy/Services/Auth/SecurityService.swift`
- **Methods**:
  - `saveClientSalt(_:forEmail:)` - No-op, always succeeds
  - `loadClientSalt(forEmail:)` - Always returns nil
  - `clearClientSalt(forEmail:)` - No-op
- **Reason**: Client salt removed from authentication flow
- **Action**: Methods are deprecated and do nothing (kept for backward compatibility)

### iOS: AuthService PIN Backend Methods
- **Status**: `@available(*, deprecated)`
- **Location**: `BudgetBuddy/BudgetBuddy/ViewModels/AuthService.swift`
- **Methods**:
  - `deletePINFromBackend()` - No-op
  - `storePINOnBackend(_:)` - No-op
  - `verifyPINWithBackend(_:)` - Throws error
- **Reason**: PIN backend endpoints removed
- **Action**: Methods are deprecated and do nothing or throw errors (kept for backward compatibility)

## 🔄 Breaking Changes

### 1. Client Salt Removed
**Before**:
```json
POST /api/auth/login
{
  "email": "user@example.com",
  "password_hash": "...",
  "salt": "..."
}
```

**After**:
```json
POST /api/auth/login
{
  "email": "user@example.com",
  "password_hash": "..."
}
```

**Impact**: All authentication endpoints no longer accept `salt` parameter.

### 2. PIN Backend Removed
**Before**: `POST /api/pin/login`, `POST /api/pin/verify`, etc.

**After**: All PIN endpoints removed. PIN is local-only.

**Impact**: iOS app must use local PIN only to decrypt refresh token.

## 📋 Migration Guide

### For iOS Developers

1. **Remove Client Salt Usage**:
   ```swift
   // OLD (won't work):
   let salt = EncryptionService.shared.generateSalt()
   let passwordHash = hashPassword(password, salt: salt)
   let body = RegisterBody(email: email, passwordHash: hash, salt: salt.base64EncodedString())
   
   // NEW (required):
   let salt = EncryptionService.shared.generateSalt()
   let passwordHash = hashPassword(password, salt: salt)
   let body = RegisterBody(email: email, passwordHash: hash) // No salt
   ```

2. **Remove PIN Backend Calls**:
   ```swift
   // OLD (won't work):
   try await authService.storePINOnBackend(pin)
   let response = try await authService.verifyPINWithBackend(pin)
   
   // NEW (required):
   // PIN is local-only - use it to decrypt refresh token
   let refreshToken = try security.loadRefreshToken() // Requires PIN/biometric
   // Then use refresh token to get new access token
   ```

3. **Update Error Handling**:
   ```swift
   // OLD:
   catch AuthError.clientSaltNotFound {
       // Handle missing salt
   }
   
   // NEW:
   // clientSaltNotFound error removed - backend handles salt management
   ```

## 🗑️ Future Removal

The following deprecated code will be **completely removed** in the next major version:

1. `DevicePinService` - Will be deleted
2. `DevicePinRepository` - Will be deleted
3. `DevicePinTable` - Will be deleted
4. `DynamoDBTableManager.createDevicePinTable()` - Will be deleted
5. `PlaidSyncService.syncTransactionsForAccount()` - Will be deleted
6. `ComplianceController.exportDataDMA()` - Will be deleted
7. iOS `SecurityService` client salt methods - Will be deleted
8. iOS `AuthService` PIN backend methods - Will be deleted

## ✅ Clean Code

All deprecated code has been:
- ✅ Marked with `@Deprecated` annotation (Java) or `@available(*, deprecated)` (Swift)
- ✅ Documented with deprecation reason
- ✅ Kept for backward compatibility (will be removed in next major version)
- ✅ Updated to do nothing or throw errors (iOS methods)

## 📝 Notes

- **No Immediate Action Required**: Deprecated code still exists but should not be used for new code
- **Backward Compatibility**: Deprecated code is kept to prevent immediate breakage
- **Future Removal**: All deprecated code will be removed in next major version
- **Migration Path**: Clear migration path provided for all breaking changes

