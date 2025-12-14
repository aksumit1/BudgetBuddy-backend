# DynamoDB Table Dump Scripts

Scripts to dump all DynamoDB tables with all fields and rows.

## Scripts

### 1. `dump-all-tables.sh` - LocalStack (Local Development)

Dumps all tables from LocalStack (default: `http://localhost:4566`).

**Usage:**
```bash
# Default (LocalStack at localhost:4566)
./scripts/dump-all-tables.sh

# Custom LocalStack endpoint
DYNAMODB_ENDPOINT=http://localhost:4566 ./scripts/dump-all-tables.sh

# Custom output directory
DUMP_OUTPUT_DIR=./my-dumps ./scripts/dump-all-tables.sh
```

**Output:**
- Creates `./dynamodb-dumps/dump_YYYYMMDD_HHMMSS/` directory
- One JSON file per table: `{table_name}.json`
- Combined file: `all_tables_combined.json`
- Summary file: `SUMMARY.txt`

### 2. `dump-all-tables-aws.sh` - AWS DynamoDB (Production/Staging)

Dumps all tables from AWS DynamoDB.

**Prerequisites:**
```bash
# Configure AWS credentials
aws configure

# Or set environment variables
export AWS_ACCESS_KEY_ID=your-key
export AWS_SECRET_ACCESS_KEY=your-secret
export AWS_REGION=us-east-1
```

**Usage:**
```bash
# Default region (us-east-1)
./scripts/dump-all-tables-aws.sh

# Custom region
AWS_REGION=us-west-2 ./scripts/dump-all-tables-aws.sh

# Custom output directory
DUMP_OUTPUT_DIR=./production-dumps ./scripts/dump-all-tables-aws.sh
```

**Output:**
- Creates `./dynamodb-dumps-aws/dump_YYYYMMDD_HHMMSS/` directory
- One JSON file per table: `{table_name}.json`
- Summary file: `SUMMARY.txt`

## Requirements

- AWS CLI installed: `brew install awscli` (macOS) or `pip install awscli`
- `jq` installed: `brew install jq` (macOS) or `apt-get install jq` (Linux)
- For LocalStack: Docker Compose running with LocalStack
- For AWS: Valid AWS credentials configured

## Example Output Structure

```
dynamodb-dumps/
└── dump_20241213_183000/
    ├── SUMMARY.txt
    ├── all_tables_combined.json
    ├── UserTable.json
    ├── AccountTable.json
    ├── TransactionTable.json
    ├── SubscriptionTable.json
    └── ...
```

## JSON Format

Each table JSON file contains an array of items:
```json
[
  {
    "transactionId": {"S": "123e4567-e89b-12d3-a456-426614174000"},
    "userId": {"S": "user-123"},
    "amount": {"N": "100.50"},
    "description": {"S": "Test transaction"},
    ...
  },
  ...
]
```

## Notes

- Large tables are automatically paginated (AWS script)
- Empty tables are still dumped (empty arrays)
- Failed tables are logged but don't stop the script
- All timestamps are included in filenames for easy tracking

