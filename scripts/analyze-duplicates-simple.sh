#!/bin/bash

# Simple script to analyze duplicate accounts using AWS CLI
# This doesn't require starting the Spring Boot application
# Supports both LocalStack (local) and AWS (production)

echo "=== Analyzing Duplicate Accounts ==="
echo ""

TABLE_NAME="${DYNAMODB_TABLE_PREFIX:-BudgetBuddy}-Accounts"
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
    echo "Warning: jq is not installed. Install with: brew install jq"
    echo "Continuing with basic analysis..."
    USE_JQ=false
else
    USE_JQ=true
fi

echo "Scanning accounts table..."
if [ "$USE_JQ" = true ]; then
    # Use jq for better parsing
    echo "Fetching accounts..."
    ACCOUNTS_JSON=$(aws dynamodb scan \
        --table-name "$TABLE_NAME" \
        --region "$REGION" \
        $ENDPOINT_ARG \
        --output json 2>&1)
    
    if [ $? -ne 0 ]; then
        echo "Error: Failed to scan table"
        echo "$ACCOUNTS_JSON"
        exit 1
    fi
    
    TOTAL=$(echo "$ACCOUNTS_JSON" | jq '.Items | length')
    echo "Total accounts found: $TOTAL"
    echo ""
    
    if [ "$TOTAL" -eq 0 ]; then
        echo "No accounts found in table."
        exit 0
    fi
    
    echo "=== ALL ACCOUNTS (Full Details) ==="
    echo ""
    echo "$ACCOUNTS_JSON" | jq -r --arg total "$TOTAL" '
        .Items | 
        to_entries |
        .[] |
        "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n" +
        "Account #" + (.key + 1 | tostring) + " of " + $total + "\n" +
        "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n" +
        "  accountId:           " + (.value.accountId.S // "N/A") + "\n" +
        "  plaidAccountId:      " + (.value.plaidAccountId.S // "N/A") + "\n" +
        "  plaidItemId:         " + (.value.plaidItemId.S // "N/A") + "\n" +
        "  userId:              " + (.value.userId.S // "N/A") + "\n" +
        "  accountName:         " + (.value.accountName.S // "N/A") + "\n" +
        "  officialName:        " + (.value.officialName.S // "N/A") + "\n" +
        "  accountType:         " + (.value.accountType.S // "N/A") + "\n" +
        "  accountSubtype:      " + (.value.accountSubtype.S // "N/A") + "\n" +
        "  accountNumber:       " + (.value.accountNumber.S // "N/A") + "\n" +
        "  mask:                " + (.value.mask.S // "N/A") + "\n" +
        "  institutionName:     " + (.value.institutionName.S // "N/A") + "\n" +
        "  institutionId:       " + (.value.institutionId.S // "N/A") + "\n" +
        "  balance:             " + (.value.balance.N // "N/A") + "\n" +
        "  currencyCode:        " + (.value.currencyCode.S // "N/A") + "\n" +
        "  active:              " + (.value.active.BOOL // "N/A" | tostring) + "\n" +
        "  createdAt:           " + (.value.createdAt.S // "N/A") + "\n" +
        "  updatedAt:           " + (.value.updatedAt.S // "N/A") + "\n" +
        "  lastSyncedAt:        " + (.value.lastSyncedAt.S // "N/A") + "\n"
        '
    
    echo ""
    echo "=== Duplicates by plaidAccountId ==="
    echo "$ACCOUNTS_JSON" | jq -r '
        .Items | 
        group_by(.plaidAccountId.S // "NO_PLAID_ID") |
        map(select(length > 1)) |
        .[] |
        "Duplicate plaidAccountId: " + (.[0].plaidAccountId.S // "NO_PLAID_ID") + " (" + (length | tostring) + " accounts)\n" +
        (.[] | 
            "  Account #" + (.accountId.S // "N/A") + ":\n" +
            "    accountId: " + (.accountId.S // "N/A") + "\n" +
            "    accountName: " + (.accountName.S // "N/A") + "\n" +
            "    accountNumber: " + (.accountNumber.S // "N/A") + "\n" +
            "    institutionName: " + (.institutionName.S // "N/A") + "\n" +
            "    userId: " + (.userId.S // "N/A") + "\n" +
            "    balance: " + (.balance.N // "N/A") + "\n" +
            "    active: " + (.active.BOOL // "N/A" | tostring) + "\n" +
            "    createdAt: " + (.createdAt.S // "N/A") + "\n" +
            "    updatedAt: " + (.updatedAt.S // "N/A") + "\n"
        )
        '
    
    echo ""
    echo "=== Duplicates by accountNumber + institutionName ==="
    echo "$ACCOUNTS_JSON" | jq -r '
        .Items |
        map(select(.accountNumber.S != null and .institutionName.S != null)) |
        group_by(.accountNumber.S + "|" + .institutionName.S) |
        map(select(length > 1)) |
        .[] |
        "Duplicate: " + (.[0].accountNumber.S // "N/A") + " at " + (.[0].institutionName.S // "N/A") + " (" + (length | tostring) + " accounts)\n" +
        (.[] | 
            "  Account #" + (.accountId.S // "N/A") + ":\n" +
            "    accountId: " + (.accountId.S // "N/A") + "\n" +
            "    plaidAccountId: " + (.plaidAccountId.S // "N/A") + "\n" +
            "    accountName: " + (.accountName.S // "N/A") + "\n" +
            "    accountNumber: " + (.accountNumber.S // "N/A") + "\n" +
            "    institutionName: " + (.institutionName.S // "N/A") + "\n" +
            "    userId: " + (.userId.S // "N/A") + "\n" +
            "    balance: " + (.balance.N // "N/A") + "\n" +
            "    active: " + (.active.BOOL // "N/A" | tostring) + "\n" +
            "    createdAt: " + (.createdAt.S // "N/A") + "\n" +
            "    updatedAt: " + (.updatedAt.S // "N/A") + "\n"
        )
        '
    
    echo ""
    echo "=== Summary ==="
    PLAID_DUPS=$(echo "$ACCOUNTS_JSON" | jq -r '.Items | group_by(.plaidAccountId.S // "NO_PLAID_ID") | map(select(length > 1)) | length')
    NUMBER_DUPS=$(echo "$ACCOUNTS_JSON" | jq -r '.Items | map(select(.accountNumber.S != null and .institutionName.S != null)) | group_by(.accountNumber.S + "|" + .institutionName.S) | map(select(length > 1)) | length')
    echo "Total accounts: $TOTAL"
    echo "Duplicate groups by plaidAccountId: $PLAID_DUPS"
    echo "Duplicate groups by accountNumber+institution: $NUMBER_DUPS"
else
    # Basic analysis without jq
    echo "Fetching all accounts..."
    aws dynamodb scan \
        --table-name "$TABLE_NAME" \
        --region "$REGION" \
        $ENDPOINT_ARG \
        --output json > /tmp/accounts.json 2>&1
    
    if [ $? -ne 0 ]; then
        echo "Error: Failed to scan table"
        cat /tmp/accounts.json
        exit 1
    fi
    
    TOTAL=$(cat /tmp/accounts.json | grep -o '"accountId"' | wc -l | tr -d ' ')
    echo "Total accounts found: $TOTAL"
    echo ""
    echo "To get detailed duplicate analysis, install jq: brew install jq"
    echo "Then run this script again."
fi

echo ""
echo "Done!"

