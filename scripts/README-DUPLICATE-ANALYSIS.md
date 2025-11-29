# Duplicate Accounts Analysis

## Quick Start

### Option 1: Using the Simple Script (Recommended for Local Development)

This script uses AWS CLI directly with LocalStack - no Spring Boot needed.

**Prerequisites:**
1. LocalStack must be running (via `docker-compose up localstack`)
2. AWS CLI installed
3. `jq` installed (optional but recommended): `brew install jq`

**Run the script:**
```bash
cd BudgetBuddy-Backend
./scripts/analyze-duplicates-simple.sh
```

**If LocalStack is not running:**
```bash
# Start LocalStack
docker-compose up -d localstack

# Wait a few seconds for it to start, then run the script
./scripts/analyze-duplicates-simple.sh
```

### Option 2: Using Java Script (Requires Spring Boot)

This requires the Spring Boot application to start successfully.

**Run the script:**
```bash
cd BudgetBuddy-Backend
mvn spring-boot:run -Dspring-boot.run.arguments="--analyze-duplicates [userId] --dry-run"
```

Replace `[userId]` with the actual user ID you want to analyze.

## Understanding the Output

The script will show:

1. **Duplicates by plaidAccountId**: Accounts with the same Plaid account ID
2. **Duplicates by accountNumber + institutionName**: Accounts with the same account number and institution
3. **Summary**: Total accounts and number of duplicate groups

## Fixing Duplicates

After analyzing, you can remove duplicates using:

```bash
# Dry run first (see what will be deleted)
mvn spring-boot:run -Dspring-boot.run.arguments="--analyze-duplicates [userId] --dry-run"

# Actually delete duplicates (keeps oldest account)
mvn spring-boot:run -Dspring-boot.run.arguments="--analyze-duplicates [userId]"
```

The script will:
- Keep the **oldest** account (by `createdAt`) for each duplicate group
- Delete newer duplicates
- Preserve all transactions linked to the kept account

## Troubleshooting

### "Unable to locate credentials"
- Make sure LocalStack is running: `docker-compose ps`
- The script should automatically set LocalStack credentials (test/test)
- If still failing, manually set: `export AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test`

### "Table not found"
- Make sure the table exists in LocalStack
- Check table name: `aws dynamodb list-tables --endpoint-url http://localhost:4566`
- The table should be named: `BudgetBuddy-Accounts`

### "Connection refused" or "Failed to connect"
- LocalStack might not be running: `docker-compose up -d localstack`
- Wait 10-15 seconds for LocalStack to fully start
- Check LocalStack health: `curl http://localhost:4566/_localstack/health`

