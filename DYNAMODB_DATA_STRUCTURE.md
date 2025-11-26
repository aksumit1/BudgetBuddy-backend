# DynamoDB Data Structure

This document outlines all the information stored in DynamoDB by the BudgetBuddy backend.

## Tables Overview

The backend stores data in **6 DynamoDB tables**:

1. **Users** - User accounts and authentication
2. **Accounts** - Financial accounts linked via Plaid
3. **Transactions** - Financial transactions from linked accounts
4. **Budgets** - User-defined spending budgets
5. **Goals** - Financial goals and savings targets
6. **AuditLogs** - Compliance and audit trail

---

## 1. Users Table (`UserTable`)

**Partition Key:** `userId`  
**GSI:** `EmailIndex` (email as partition key)

### Fields Stored:

| Field | Type | Description |
|-------|------|-------------|
| `userId` | String | Unique user identifier (partition key) |
| `email` | String | User email address (GSI partition key) |
| `passwordHash` | String | Server-side PBKDF2 password hash |
| `serverSalt` | String | Server-side salt for password hashing |
| `clientSalt` | String | Client-side salt (for reference, optional) |
| `firstName` | String | User's first name |
| `lastName` | String | User's last name |
| `phoneNumber` | String | User's phone number |
| `enabled` | Boolean | Whether account is enabled |
| `emailVerified` | Boolean | Whether email is verified |
| `twoFactorEnabled` | Boolean | Whether 2FA is enabled |
| `preferredCurrency` | String | User's preferred currency (e.g., "USD") |
| `timezone` | String | User's timezone |
| `roles` | Set<String> | User roles/permissions |
| `createdAt` | Instant | Account creation timestamp |
| `updatedAt` | Instant | Last update timestamp |
| `lastLoginAt` | Instant | Last login timestamp |
| `passwordChangedAt` | Instant | Last password change timestamp |

### Security Notes:
- Passwords are hashed using PBKDF2 with server-side salt
- Never stores plaintext passwords
- Client salt is stored for reference but not used for server-side hashing

---

## 2. Accounts Table (`AccountTable`)

**Partition Key:** `accountId`  
**GSI:** `UserIdIndex` (userId as partition key)  
**GSI:** `PlaidAccountIdIndex` (plaidAccountId as partition key)

### Fields Stored:

| Field | Type | Description |
|-------|------|-------------|
| `accountId` | String | Unique account identifier (partition key) |
| `userId` | String | Owner user ID (GSI partition key) |
| `accountName` | String | Account name (e.g., "Chase Checking") |
| `institutionName` | String | Financial institution name (e.g., "Chase") |
| `accountType` | String | Account type (e.g., "checking", "savings", "credit") |
| `accountSubtype` | String | Account subtype (e.g., "checking", "cd") |
| `balance` | BigDecimal | Current account balance |
| `currencyCode` | String | Currency code (e.g., "USD") |
| `plaidAccountId` | String | Plaid account identifier (GSI partition key) |
| `plaidItemId` | String | Plaid item identifier |
| `active` | Boolean | Whether account is active |
| `lastSyncedAt` | Instant | **Last sync timestamp (per-account sync settings)** |
| `createdAt` | Instant | Account creation timestamp |
| `updatedAt` | Instant | Last update timestamp |

### Key Features:
- **Per-account sync settings**: `lastSyncedAt` tracks when each account was last synced
- **Deduplication**: Uses `plaidAccountId` GSI to prevent duplicate accounts
- **Multi-account support**: One user can have multiple accounts

---

## 3. Transactions Table (`TransactionTable`)

**Partition Key:** `transactionId`  
**GSI:** `UserIdDateIndex` (userId as partition key, transactionDate as sort key)  
**GSI:** `PlaidTransactionIdIndex` (plaidTransactionId as partition key)

### Fields Stored:

| Field | Type | Description |
|-------|------|-------------|
| `transactionId` | String | Unique transaction identifier (partition key) |
| `userId` | String | Owner user ID (GSI partition key) |
| `accountId` | String | Associated account ID |
| `amount` | BigDecimal | Transaction amount (positive for income, negative for expenses) |
| `description` | String | Transaction description |
| `merchantName` | String | Merchant name |
| `category` | String | Transaction category (e.g., "Food and Drink, Restaurants") |
| `transactionDate` | String | Transaction date in YYYY-MM-DD format (GSI sort key) |
| `currencyCode` | String | Currency code (e.g., "USD") |
| `plaidTransactionId` | String | Plaid transaction identifier (GSI partition key) |
| `pending` | Boolean | Whether transaction is pending |
| `paymentChannel` | String | Payment channel (e.g., "online", "in_store", "ach") |
| `createdAt` | Instant | Transaction creation timestamp |
| `updatedAt` | Instant | Last update timestamp |

### Key Features:
- **Deduplication**: Uses `plaidTransactionId` GSI to prevent duplicate transactions
- **Date range queries**: `UserIdDateIndex` enables efficient date range filtering
- **ACH detection**: `paymentChannel` field helps identify ACH transactions for categorization

---

## 4. Budgets Table (`BudgetTable`)

**Partition Key:** `budgetId`  
**GSI:** `UserIdIndex` (userId as partition key)

### Fields Stored:

| Field | Type | Description |
|-------|------|-------------|
| `budgetId` | String | Unique budget identifier (partition key) |
| `userId` | String | Owner user ID (GSI partition key) |
| `category` | String | Budget category (e.g., "Food and Drink") |
| `monthlyLimit` | BigDecimal | Monthly spending limit |
| `currentSpent` | BigDecimal | Current month's spending |
| `currencyCode` | String | Currency code (e.g., "USD") |
| `createdAt` | Instant | Budget creation timestamp |
| `updatedAt` | Instant | Last update timestamp |

### Key Features:
- **Category-based budgets**: Each budget is tied to a spending category
- **Monthly tracking**: Tracks spending against monthly limits

---

## 5. Goals Table (`GoalTable`)

**Partition Key:** `goalId`  
**GSI:** `UserIdIndex` (userId as partition key)  
**GSI:** `UserIdTargetDateIndex` (userId as partition key, targetDate as sort key)

### Fields Stored:

| Field | Type | Description |
|-------|------|-------------|
| `goalId` | String | Unique goal identifier (partition key) |
| `userId` | String | Owner user ID (GSI partition key) |
| `name` | String | Goal name (e.g., "Emergency Fund") |
| `description` | String | Goal description |
| `targetAmount` | BigDecimal | Target amount to save |
| `currentAmount` | BigDecimal | Current amount saved |
| `targetDate` | String | Target date in ISO format (GSI sort key) |
| `monthlyContribution` | BigDecimal | Monthly contribution amount |
| `goalType` | String | Goal type (e.g., "savings", "vacation") |
| `currencyCode` | String | Currency code (e.g., "USD") |
| `active` | Boolean | Whether goal is active |
| `createdAt` | Instant | Goal creation timestamp |
| `updatedAt` | Instant | Last update timestamp |

### Key Features:
- **Target date queries**: `UserIdTargetDateIndex` enables sorting by target date
- **Progress tracking**: Tracks current vs. target amounts

---

## 6. Audit Logs Table (`AuditLogTable`)

**Partition Key:** `auditLogId`  
**GSI:** `UserIdCreatedAtIndex` (userId as partition key, createdAt as sort key)

### Fields Stored:

| Field | Type | Description |
|-------|------|-------------|
| `auditLogId` | String | Unique audit log identifier (partition key) |
| `userId` | String | User ID who performed action (GSI partition key) |
| `action` | String | Action performed (e.g., "LOGIN", "CREATE_ACCOUNT") |
| `resourceType` | String | Resource type (e.g., "ACCOUNT", "TRANSACTION") |
| `resourceId` | String | Resource ID affected |
| `details` | String | Additional details (JSON string) |
| `ipAddress` | String | IP address of request |
| `userAgent` | String | User agent string |
| `createdAt` | Long | Unix timestamp (GSI sort key) |

### Key Features:
- **Compliance**: Tracks all user actions for audit purposes
- **Time-based queries**: `UserIdCreatedAtIndex` enables chronological queries
- **Security**: Logs IP addresses and user agents for security analysis

---

## Global Secondary Indexes (GSI) Summary

| Table | GSI Name | Partition Key | Sort Key | Purpose |
|-------|----------|---------------|----------|---------|
| Users | EmailIndex | email | - | Lookup user by email |
| Accounts | UserIdIndex | userId | - | Get all accounts for a user |
| Accounts | PlaidAccountIdIndex | plaidAccountId | - | Deduplicate accounts by Plaid ID |
| Transactions | UserIdDateIndex | userId | transactionDate | Query transactions by date range |
| Transactions | PlaidTransactionIdIndex | plaidTransactionId | - | Deduplicate transactions by Plaid ID |
| Budgets | UserIdIndex | userId | - | Get all budgets for a user |
| Goals | UserIdIndex | userId | - | Get all goals for a user |
| Goals | UserIdTargetDateIndex | userId | targetDate | Sort goals by target date |
| AuditLogs | UserIdCreatedAtIndex | userId | createdAt | Chronological audit log queries |

---

## Data Relationships

```
User (userId)
  ├── Accounts (userId → accountId)
  │     └── Transactions (accountId → transactionId)
  ├── Budgets (userId → budgetId)
  ├── Goals (userId → goalId)
  └── AuditLogs (userId → auditLogId)
```

---

## Important Notes

1. **Sync Settings**: The `lastSyncedAt` field in `AccountTable` is the **source of truth** for sync settings. It persists even if the iOS app is deleted and reinstalled.

2. **Deduplication**: Both `AccountTable` and `TransactionTable` use Plaid IDs as GSI partition keys to prevent duplicates when syncing multiple times.

3. **Billing Mode**: All tables use **PAY_PER_REQUEST** (on-demand) billing mode for cost optimization.

4. **Point-in-Time Recovery**: All tables have Point-in-Time Recovery enabled for data protection.

5. **Streams**: All tables have DynamoDB Streams enabled for real-time processing and event-driven architectures.

6. **Security**: Passwords are never stored in plaintext - only PBKDF2 hashes with server-side salts.

---

## Data Flow

1. **User Registration**: Creates `UserTable` entry with hashed password
2. **Plaid Link**: Creates `AccountTable` entries for linked accounts
3. **Sync**: Creates/updates `TransactionTable` entries from Plaid
4. **Budget Creation**: Creates `BudgetTable` entries
5. **Goal Creation**: Creates `GoalTable` entries
6. **All Actions**: Logged in `AuditLogTable` for compliance

---

## Storage Considerations

- **Scalability**: DynamoDB scales automatically with on-demand billing
- **Performance**: GSIs enable fast queries without full table scans
- **Cost**: Pay only for what you use with on-demand billing
- **Durability**: Point-in-Time Recovery ensures data protection

