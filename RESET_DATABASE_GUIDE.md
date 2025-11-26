# Reset Local Database Guide

## Quick Reset

To start with a fresh database (clear all existing data):

```bash
cd BudgetBuddy-Backend
./scripts/reset-local-database.sh
```

This script will:
1. List all existing DynamoDB tables
2. Ask for confirmation
3. Delete all tables
4. Backend will recreate tables on next startup

## Manual Reset

If you prefer to reset manually:

```bash
# List all tables
docker exec budgetbuddy-localstack aws --endpoint-url=http://localhost:4566 dynamodb list-tables

# Delete specific table (example)
docker exec budgetbuddy-localstack aws --endpoint-url=http://localhost:4566 dynamodb delete-table --table-name BudgetBuddy-Users

# Or delete all LocalStack data (nuclear option)
docker-compose down -v
docker-compose up -d localstack
```

## After Reset

1. **Restart backend** to recreate tables:
   ```bash
   docker-compose restart backend
   ```

2. **Verify tables created**:
   ```bash
   docker exec budgetbuddy-localstack aws --endpoint-url=http://localhost:4566 dynamodb list-tables
   ```

3. **Test registration**:
   ```bash
   curl -X POST http://127.0.0.1:8080/api/auth/register \
     -H "Content-Type: application/json" \
     -d '{"email":"test@example.com","password_hash":"dGVzdA==","salt":"c2FsdA=="}'
   ```

## What Gets Reset

- ✅ All user accounts
- ✅ All transactions
- ✅ All accounts
- ✅ All budgets
- ✅ All goals
- ✅ All audit logs

**Note**: This only affects LocalStack (local development). Production data is not affected.

