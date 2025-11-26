# Data Deletion and Security

This document outlines the data deletion capabilities and security measures implemented in BudgetBuddy.

## Overview

BudgetBuddy provides three levels of data deletion:
1. **Delete All Data** - Removes all financial data but keeps the user account
2. **Delete Plaid Integration** - Removes only Plaid-linked accounts and transactions
3. **Delete Account Completely** - Permanently deletes the user account and all data

## Security Measures

### 1. Data Encryption

#### At Rest (DynamoDB)
- **AWS DynamoDB Encryption**: All tables use AWS-managed encryption at rest (AES-256)
- **KMS Integration**: Encryption keys are managed by AWS KMS
- **Point-in-Time Recovery**: Enabled for all tables to protect against data loss

#### In Transit
- **TLS/SSL**: All API communications use HTTPS (TLS 1.2+)
- **JWT Tokens**: Authentication tokens are signed and encrypted
- **Request Signing**: Optional request signing for additional security

#### Sensitive Data Handling
- **Passwords**: Never stored in plaintext
  - Client-side: PBKDF2 hashing with client salt (stored in iOS Keychain)
  - Server-side: Additional PBKDF2 hashing with server salt
  - Salts: Stored separately, never reused
- **Plaid Tokens**: Stored in iOS Keychain with biometric protection
- **Financial Data**: Encrypted at rest by DynamoDB, transmitted over HTTPS

### 2. Secure Deletion

#### Backend Deletion Process
1. **Archive Before Delete**: Data is archived to S3 before deletion (for compliance)
2. **Batch Operations**: Uses batch delete for efficiency and atomicity
3. **Audit Logging**: All deletion actions are logged for compliance
4. **Anonymization**: Audit logs are anonymized (PII removed) but kept for compliance

#### iOS App Deletion Process
1. **Keychain Wipe**: All stored tokens and credentials are securely wiped
2. **Overwrite with Zeros**: Keychain items are overwritten with zeros before deletion
3. **Cache Clear**: All cached data is cleared
4. **Logout**: User is logged out after account deletion

## API Endpoints

### 1. Delete All Data
**Endpoint**: `DELETE /api/user/data?confirm=true`

**Description**: Deletes all financial data but keeps the user account.

**What's Deleted**:
- All accounts and transactions
- All budgets and goals
- Plaid integration

**What's Kept**:
- User account (can still log in)
- Audit logs (anonymized)

**Request**:
```http
DELETE /api/user/data?confirm=true
Authorization: Bearer <token>
```

**Response**:
```json
{
  "status": "success",
  "message": "All user data deleted successfully"
}
```

### 2. Delete Plaid Integration
**Endpoint**: `DELETE /api/user/plaid?confirm=true`

**Description**: Removes Plaid integration and associated data only.

**What's Deleted**:
- All Plaid-linked accounts
- All transactions from Plaid accounts
- Plaid item connections

**What's Kept**:
- User account
- Budgets and goals
- Non-Plaid data

**Request**:
```http
DELETE /api/user/plaid?confirm=true
Authorization: Bearer <token>
```

**Response**:
```json
{
  "status": "success",
  "message": "Plaid integration deleted successfully"
}
```

### 3. Delete Account Completely
**Endpoint**: `DELETE /api/user/account?confirm=true`

**Description**: Permanently deletes the user account and all associated data.

**⚠️ WARNING**: This is irreversible!

**What's Deleted**:
- All user data (accounts, transactions, budgets, goals)
- Plaid integration
- User account

**What's Kept**:
- Audit logs (anonymized, for compliance)

**Request**:
```http
DELETE /api/user/account?confirm=true
Authorization: Bearer <token>
```

**Response**:
```json
{
  "status": "success",
  "message": "Account deleted successfully"
}
```

## iOS App Integration

### Settings View
The iOS app provides a "Privacy & Deletion" section in Settings with three options:

1. **Delete Plaid Integration**
   - Shows confirmation alert
   - Removes Plaid-linked accounts and transactions
   - Keeps user account, budgets, and goals

2. **Delete All Data**
   - Shows confirmation alert
   - Removes all financial data
   - Keeps user account

3. **Delete Account**
   - Shows warning alert (requires explicit confirmation)
   - Permanently deletes account and all data
   - Logs user out automatically

### Implementation Details

**AuthService Methods**:
```swift
// Delete all data (keeps account)
func deleteAllData(confirm: Bool) async throws

// Delete Plaid integration only
func deletePlaidIntegration(confirm: Bool) async throws

// Delete account completely
func deleteAccountCompletely(confirm: Bool) async throws
```

**SecurityService Cleanup**:
- `clearPlaidTokenId()` - Removes Plaid token from keychain
- `secureWipe()` - Securely wipes all credentials
- `clearAuthToken()` - Removes authentication token

## Backend Implementation

### UserDeletionService

The `UserDeletionService` handles all deletion operations:

1. **deleteAllUserData(userId)**
   - Removes Plaid items
   - Deletes transactions in batches
   - Deletes accounts, budgets, goals
   - Anonymizes audit logs

2. **deletePlaidIntegration(userId)**
   - Removes Plaid items
   - Deletes transactions
   - Deletes accounts

3. **deleteAccountCompletely(userId)**
   - Calls `deleteAllUserData()`
   - Deletes user account

### Batch Operations

For efficiency, deletion uses batch operations:
- **Transactions**: Deleted in batches of 1000
- **Accounts**: Batch deleted using `BatchWriteItem`
- **Error Handling**: Falls back to individual deletion if batch fails

### Audit Logging

All deletion actions are logged:
- `DATA_DELETION` - When all data is deleted
- `DELETE_PLAID_INTEGRATION` - When Plaid is removed
- `DELETE_ACCOUNT` - When account is deleted

Audit logs are anonymized (PII removed) but kept for compliance (7 years).

## GDPR Compliance

### Right to Erasure (Article 17)
- Users can request deletion of all their data
- Data is archived before deletion (for compliance)
- Audit logs are anonymized but kept

### Right to Data Portability (Article 20)
- Users can export their data before deletion
- Data is exported in JSON format
- Available via `/api/compliance/gdpr/export/portable`

### Data Minimization
- Only necessary data is stored
- Sensitive data is encrypted
- PII is removed from audit logs after deletion

## Security Best Practices

1. **Confirmation Required**: All deletion operations require explicit confirmation
2. **Authentication Required**: All endpoints require valid JWT token
3. **Audit Trail**: All actions are logged for compliance
4. **Secure Wipe**: iOS app securely wipes keychain data
5. **Encryption**: All data encrypted at rest and in transit
6. **Access Control**: Only authenticated users can delete their own data

## Data Retention

- **User Data**: Deleted immediately upon request
- **Audit Logs**: Retained for 7 years (anonymized)
- **Archived Data**: Stored in S3 for 30 days, then permanently deleted

## Error Handling

- **Network Errors**: Retried with exponential backoff
- **Partial Failures**: Logged but don't block deletion
- **Validation Errors**: Return clear error messages
- **Authentication Errors**: Redirect to sign-in page

## Testing

All deletion operations are tested:
- Unit tests for `UserDeletionService`
- Integration tests for API endpoints
- iOS app tests for deletion flows
- Security tests for encryption and secure wipe

## Monitoring

- **CloudWatch Metrics**: Track deletion operations
- **Audit Logs**: Monitor all deletion actions
- **Error Alerts**: Alert on deletion failures
- **Compliance Reports**: Regular compliance audits

