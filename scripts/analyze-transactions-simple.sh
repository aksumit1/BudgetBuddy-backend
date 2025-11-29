#!/bin/bash

# Script to analyze all transactions from the backend with all fields and data
# This doesn't require starting the Spring Boot application
# Supports both LocalStack (local) and AWS (production)

echo "=== Analyzing All Transactions ==="
echo ""

TABLE_NAME="${DYNAMODB_TABLE_PREFIX:-BudgetBuddy}-Transactions"
REGION="${AWS_REGION:-us-east-1}"

# Check if LocalStack is being used (default for local development)
if [ -z "$DYNAMODB_ENDPOINT" ]; then
    # Default to LocalStack for local development
    ENDPOINT="http://localhost:4566"
    echo "Using LocalStack (default for local development)"
    echo "To use AWS, set DYNAMODB_ENDPOINT environment variable"
else
    ENDPOINT="$DYNAMODB_ENDPOINT"
fi

echo "Table: $TABLE_NAME"
echo "Region: $REGION"
echo "Endpoint: $ENDPOINT"
ENDPOINT_ARG="--endpoint-url $ENDPOINT"

# Set LocalStack credentials if using LocalStack
if [[ "$ENDPOINT" == *"localhost"* ]] || [[ "$ENDPOINT" == *"127.0.0.1"* ]]; then
    export AWS_ACCESS_KEY_ID="${AWS_ACCESS_KEY_ID:-test}"
    export AWS_SECRET_ACCESS_KEY="${AWS_SECRET_ACCESS_KEY:-test}"
    echo "Using LocalStack credentials (test/test)"
fi

echo ""

# Check if AWS CLI is available
if ! command -v aws &> /dev/null; then
    echo "Error: AWS CLI is not installed"
    exit 1
fi

# Check if jq is available
if ! command -v jq &> /dev/null; then
    echo "Error: jq is required for this script. Install with: brew install jq"
    exit 1
fi

# Optional: Filter by userId if provided
USER_EMAIL="${1:-}"
if [ -n "$USER_EMAIL" ]; then
    echo "Filtering transactions for user: $USER_EMAIL"
    echo "(Note: This requires userId lookup. For now, showing all transactions.)"
    echo ""
fi

echo "Scanning transactions table..."
echo "Fetching all transactions (this may take a while for large tables)..."
TRANSACTIONS_JSON=$(aws dynamodb scan \
    --table-name "$TABLE_NAME" \
    --region "$REGION" \
    $ENDPOINT_ARG \
    --output json 2>&1)

if [ $? -ne 0 ]; then
    echo "Error: Failed to scan table"
    echo "$TRANSACTIONS_JSON"
    exit 1
fi

TOTAL=$(echo "$TRANSACTIONS_JSON" | jq '.Items | length')
echo "Total transactions found: $TOTAL"
echo ""

if [ "$TOTAL" -eq 0 ]; then
    echo "No transactions found in table."
    exit 0
fi

echo "=== ALL TRANSACTIONS (Full Details) ==="
echo ""
echo "$TRANSACTIONS_JSON" | jq -r --arg total "$TOTAL" '
    .Items | 
    to_entries |
    .[] |
    "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n" +
    "Transaction #" + (.key + 1 | tostring) + " of " + $total + "\n" +
    "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n" +
    "  transactionId:        " + (.value.transactionId.S // "N/A") + "\n" +
    "  plaidTransactionId:   " + (.value.plaidTransactionId.S // "N/A") + "\n" +
    "  userId:               " + (.value.userId.S // "N/A") + "\n" +
    "  accountId:            " + (.value.accountId.S // "N/A") + "\n" +
    "  amount:               " + (.value.amount.N // "N/A") + "\n" +
    "  description:          " + (.value.description.S // "N/A") + "\n" +
    "  merchantName:         " + (.value.merchantName.S // "N/A") + "\n" +
    "  category:             " + (.value.category.S // "N/A") + "\n" +
    "  transactionDate:      " + (.value.transactionDate.S // "N/A") + "\n" +
    "  currencyCode:         " + (.value.currencyCode.S // "N/A") + "\n" +
    "  pending:              " + (.value.pending.BOOL // "N/A" | tostring) + "\n" +
    "  paymentChannel:       " + (.value.paymentChannel.S // "N/A") + "\n" +
    "  notes:                " + (.value.notes.S // "N/A") + "\n" +
    "  createdAt:            " + (.value.createdAt.S // "N/A") + "\n" +
    "  updatedAt:            " + (.value.updatedAt.S // "N/A") + "\n"
    '

echo ""
echo "=== Duplicates by plaidTransactionId ==="
DUPLICATE_COUNT=$(echo "$TRANSACTIONS_JSON" | jq -r '
    .Items | 
    group_by(.plaidTransactionId.S // "NO_PLAID_ID") |
    map(select(length > 1)) |
    length')
    
if [ "$DUPLICATE_COUNT" -gt 0 ]; then
    echo "$TRANSACTIONS_JSON" | jq -r '
        .Items | 
        group_by(.plaidTransactionId.S // "NO_PLAID_ID") |
        map(select(length > 1)) |
        .[] |
        "Duplicate plaidTransactionId: " + (.[0].plaidTransactionId.S // "NO_PLAID_ID") + " (" + (length | tostring) + " transactions)\n" +
        (.[] | 
            "  Transaction #" + (.transactionId.S // "N/A") + ":\n" +
            "    transactionId: " + (.transactionId.S // "N/A") + "\n" +
            "    plaidTransactionId: " + (.plaidTransactionId.S // "N/A") + "\n" +
            "    userId: " + (.userId.S // "N/A") + "\n" +
            "    accountId: " + (.accountId.S // "N/A") + "\n" +
            "    amount: " + (.amount.N // "N/A") + "\n" +
            "    description: " + (.description.S // "N/A") + "\n" +
            "    merchantName: " + (.merchantName.S // "N/A") + "\n" +
            "    category: " + (.category.S // "N/A") + "\n" +
            "    transactionDate: " + (.transactionDate.S // "N/A") + "\n" +
            "    createdAt: " + (.createdAt.S // "N/A") + "\n" +
            "    updatedAt: " + (.updatedAt.S // "N/A") + "\n"
        )
        '
else
    echo "No duplicates found by plaidTransactionId"
fi

echo ""
echo "=== Duplicates by transactionId (should not happen) ==="
ID_DUPLICATE_COUNT=$(echo "$TRANSACTIONS_JSON" | jq -r '
    .Items | 
    group_by(.transactionId.S) |
    map(select(length > 1)) |
    length')
    
if [ "$ID_DUPLICATE_COUNT" -gt 0 ]; then
    echo "WARNING: Found duplicate transactionIds (this should not happen):"
    echo "$TRANSACTIONS_JSON" | jq -r '
        .Items | 
        group_by(.transactionId.S) |
        map(select(length > 1)) |
        .[] |
        "Duplicate transactionId: " + (.[0].transactionId.S // "N/A") + " (" + (length | tostring) + " transactions)\n" +
        (.[] | 
            "  Transaction:\n" +
            "    transactionId: " + (.transactionId.S // "N/A") + "\n" +
            "    plaidTransactionId: " + (.plaidTransactionId.S // "N/A") + "\n" +
            "    userId: " + (.userId.S // "N/A") + "\n" +
            "    accountId: " + (.accountId.S // "N/A") + "\n" +
            "    amount: " + (.amount.N // "N/A") + "\n" +
            "    description: " + (.description.S // "N/A") + "\n" +
            "    createdAt: " + (.createdAt.S // "N/A") + "\n"
        )
        '
else
    echo "No duplicates found by transactionId (good!)"
fi

echo ""
echo "=== Transactions without plaidTransactionId ==="
NO_PLAID_COUNT=$(echo "$TRANSACTIONS_JSON" | jq -r '.Items | map(select(.plaidTransactionId.S == null or .plaidTransactionId.S == "")) | length')
if [ "$NO_PLAID_COUNT" -gt 0 ]; then
    echo "Found $NO_PLAID_COUNT transactions without plaidTransactionId:"
    echo "$TRANSACTIONS_JSON" | jq -r '
        .Items | 
        map(select(.plaidTransactionId.S == null or .plaidTransactionId.S == "")) |
        .[] |
        "  Transaction #" + (.transactionId.S // "N/A") + ":\n" +
        "    transactionId: " + (.transactionId.S // "N/A") + "\n" +
        "    userId: " + (.userId.S // "N/A") + "\n" +
        "    accountId: " + (.accountId.S // "N/A") + "\n" +
        "    amount: " + (.amount.N // "N/A") + "\n" +
        "    description: " + (.description.S // "N/A") + "\n" +
        "    transactionDate: " + (.transactionDate.S // "N/A") + "\n" +
        "    createdAt: " + (.createdAt.S // "N/A") + "\n"
        '
else
    echo "All transactions have plaidTransactionId (good!)"
fi

echo ""
echo "=== Summary Statistics ==="
echo "Total transactions: $TOTAL"
echo "Duplicate groups by plaidTransactionId: $DUPLICATE_COUNT"
echo "Duplicate groups by transactionId: $ID_DUPLICATE_COUNT"
echo "Transactions without plaidTransactionId: $NO_PLAID_COUNT"

# Group by userId
echo ""
echo "=== Transactions by User ==="
echo "$TRANSACTIONS_JSON" | jq -r '
    .Items | 
    group_by(.userId.S) |
    .[] |
    "User: " + (.[0].userId.S // "N/A") + " (" + (length | tostring) + " transactions)"
    ' | sort

# Group by accountId
echo ""
echo "=== Transactions by Account ==="
echo "$TRANSACTIONS_JSON" | jq -r '
    .Items | 
    group_by(.accountId.S) |
    .[] |
    "Account: " + (.[0].accountId.S // "N/A") + " (" + (length | tostring) + " transactions)"
    ' | sort

# Group by category
echo ""
echo "=== Transactions by Category ==="
echo "$TRANSACTIONS_JSON" | jq -r '
    .Items | 
    group_by(.category.S // "NO_CATEGORY") |
    .[] |
    "Category: " + (.[0].category.S // "NO_CATEGORY") + " (" + (length | tostring) + " transactions)"
    ' | sort

# Date range
echo ""
echo "=== Date Range ==="
EARLIEST=$(echo "$TRANSACTIONS_JSON" | jq -r '.Items | map(.transactionDate.S // "") | map(select(. != "")) | sort | first // "N/A"')
LATEST=$(echo "$TRANSACTIONS_JSON" | jq -r '.Items | map(.transactionDate.S // "") | map(select(. != "")) | sort | last // "N/A"')
echo "Earliest transaction date: $EARLIEST"
echo "Latest transaction date: $LATEST"

echo ""
echo "Done!"

