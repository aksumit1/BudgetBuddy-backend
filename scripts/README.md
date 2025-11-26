# Database Review and Cleanup Scripts

This directory contains scripts to review and clean up the BudgetBuddy backend DynamoDB database.

## Scripts

### 1. `review-database.py` (Recommended)

Python-based database review script with better duplicate detection.

**Usage:**
```bash
# Basic usage
python3 scripts/review-database.py

# With custom region and table prefix
python3 scripts/review-database.py --region us-west-2 --table-prefix BudgetBuddy

# Save analysis to files
python3 scripts/review-database.py --output-dir ./database-analysis
```

**Requirements:**
- Python 3.6+
- boto3 (`pip install boto3`)
- AWS credentials configured (via `aws configure` or environment variables)

**Features:**
- Scans all tables
- Identifies duplicates by both primary keys and Plaid IDs
- Provides detailed statistics
- Can save analysis to JSON files

### 2. `review-database.sh`

Shell-based database review script that:
- Lists all DynamoDB tables
- Scans each table and saves data to JSON files
- Analyzes accounts for duplicates (by `accountId` and `plaidAccountId`)
- Analyzes transactions for duplicates (by `transactionId` and `plaidTransactionId`)
- Provides statistics (counts, active/inactive, etc.)

**Usage:**
```bash
# Set environment variables (optional)
export AWS_REGION=us-east-1
export TABLE_PREFIX=BudgetBuddy

# Run the review
./scripts/review-database.sh
```

**Output:**
- Creates a timestamped directory with:
  - JSON files for each table (`BudgetBuddy-Accounts.json`, etc.)
  - Analysis files showing duplicates and statistics

**Requirements:**
- AWS CLI configured with appropriate credentials
- `jq` installed (for JSON parsing)
- `python3` installed (for duplicate analysis)

### 3. `cleanup-duplicates.sh`

Removes duplicate accounts and transactions from DynamoDB.

**Usage:**
```bash
# Dry run (default - shows what would be deleted)
./scripts/cleanup-duplicates.sh

# Actually delete duplicates (use with caution!)
DRY_RUN=false ./scripts/cleanup-duplicates.sh
```

**What it does:**
- Scans the Accounts table for duplicates by `plaidAccountId`
- Scans the Transactions table for duplicates by `plaidTransactionId`
- Keeps the most recent version (by `updatedAt` timestamp)
- Deletes older duplicates

**Safety:**
- Defaults to DRY_RUN mode (no changes made)
- Shows what would be deleted before doing it
- Requires explicit confirmation

### 4. `review-database.java`

Java-based review script (alternative to shell script).

**Usage:**
```bash
# Run as Spring Boot application
mvn spring-boot:run -Dspring-boot.run.arguments="--review-database"

# Or if you have a JAR
java -jar target/budgetbuddy-backend.jar --review-database
```

**Note:** The Java version is more limited due to DynamoDB scan costs. Use the Python script for comprehensive reviews.

### 5. `delete-all-data.sh`

⚠️ **DANGER:** Deletes ALL data from all tables. Use only for testing/development!

**Usage:**
```bash
# Requires double confirmation
./scripts/delete-all-data.sh

# Or with environment variable (still requires typing confirmation)
CONFIRM=true ./scripts/delete-all-data.sh
```

**Safety:**
- Requires typing "DELETE ALL DATA" to confirm
- Shows which tables will be cleared
- Cannot be undone - use with extreme caution!

## Tables Reviewed

The scripts review the following DynamoDB tables:

1. **BudgetBuddy-Users** - User accounts
2. **BudgetBuddy-Accounts** - Financial accounts
3. **BudgetBuddy-Transactions** - Financial transactions
4. **BudgetBuddy-Budgets** - Budget definitions
5. **BudgetBuddy-Goals** - Financial goals
6. **BudgetBuddy-AuditLogs** - Audit trail

## Duplicate Detection

### Accounts
- Duplicates by `accountId` (should never happen - primary key)
- Duplicates by `plaidAccountId` (can happen if same Plaid account is linked multiple times)

### Transactions
- Duplicates by `transactionId` (should never happen - primary key)
- Duplicates by `plaidTransactionId` (can happen if same Plaid transaction is synced multiple times)

## Cost Considerations

⚠️ **Warning:** Full table scans can be expensive in DynamoDB!

- Each scan operation consumes read capacity units
- For large tables, consider:
  - Running during off-peak hours
  - Using pagination
  - Reviewing specific users instead of full table
  - Using DynamoDB Streams for real-time duplicate detection

## Example Output

```
========================================
BudgetBuddy Database Review Script
========================================

Step 1: Checking tables...
✓ BudgetBuddy-Users exists (Item Count: 150)
✓ BudgetBuddy-Accounts exists (Item Count: 450)
✓ BudgetBuddy-Transactions exists (Item Count: 12500)

Step 2: Scanning tables...
Scanning BudgetBuddy-Accounts...
  Found 450 items

Step 3: Analyzing for duplicates...

⚠️  DUPLICATE PLAID ACCOUNT IDs FOUND: 3
  Plaid Account ID: plaid-123 (2 duplicates)
    - Account ID: acc-1, User: user-1, Name: Checking, Active: true
    - Account ID: acc-2, User: user-1, Name: Checking, Active: true

✅ No duplicate transaction IDs found
```

## Troubleshooting

### AWS Credentials
Make sure AWS CLI is configured:
```bash
aws configure
```

### Permissions
The scripts need the following DynamoDB permissions:
- `dynamodb:DescribeTable`
- `dynamodb:Scan`
- `dynamodb:DeleteItem` (for cleanup script)

### Python Dependencies
For `review-database.py`:
```bash
pip install boto3
```

For `review-database.sh`:
- Uses Python 3 with standard library only (no external dependencies required)

## Best Practices

1. **Always run in DRY_RUN mode first** to see what would be changed
2. **Backup important data** before running cleanup scripts
3. **Review the analysis output** carefully before deleting duplicates
4. **Run during maintenance windows** to avoid impacting users
5. **Monitor DynamoDB costs** after running scans

