#!/bin/bash

# Script to find duplicate accounts in DynamoDB
# This uses AWS CLI to query the database

echo "=== Finding Duplicate Accounts ==="
echo ""

# Check if AWS CLI is available
if ! command -v aws &> /dev/null; then
    echo "Error: AWS CLI is not installed"
    exit 1
fi

TABLE_NAME="BudgetBuddy-Accounts"
REGION="${AWS_REGION:-us-east-1}"

echo "Scanning table: $TABLE_NAME"
echo "Region: $REGION"
echo ""

# Scan all accounts
echo "Fetching all accounts..."
aws dynamodb scan \
    --table-name "$TABLE_NAME" \
    --region "$REGION" \
    --output json > /tmp/all_accounts.json

if [ $? -ne 0 ]; then
    echo "Error: Failed to scan table. Check your AWS credentials and table name."
    exit 1
fi

echo "Accounts fetched. Analyzing duplicates..."
echo ""

# Use jq to analyze duplicates
if ! command -v jq &> /dev/null; then
    echo "Warning: jq is not installed. Installing basic analysis..."
    echo "Please install jq for better duplicate detection: brew install jq"
    echo ""
    echo "Total accounts in database:"
    cat /tmp/all_accounts.json | grep -o '"accountId"' | wc -l
else
    echo "=== Duplicate Analysis ==="
    echo ""
    
    # Count total accounts
    TOTAL=$(jq '.Items | length' /tmp/all_accounts.json)
    echo "Total accounts: $TOTAL"
    echo ""
    
    # Find duplicates by plaidAccountId
    echo "1. Duplicates by plaidAccountId:"
    jq -r '.Items[] | select(.plaidAccountId.S != null) | .plaidAccountId.S' /tmp/all_accounts.json | \
        sort | uniq -d | while read plaidId; do
            echo "  Duplicate plaidAccountId: $plaidId"
            jq -r ".Items[] | select(.plaidAccountId.S == \"$plaidId\") | \"    - Account: \(.accountId.S), Name: \(.accountName.S), User: \(.userId.S)\"" /tmp/all_accounts.json
        done
    echo ""
    
    # Find duplicates by accountNumber + institutionName
    echo "2. Duplicates by accountNumber + institutionName:"
    jq -r '.Items[] | select(.accountNumber.S != null and .institutionName.S != null) | "\(.accountNumber.S)|\(.institutionName.S)|\(.accountId.S)|\(.accountName.S)|\(.userId.S)"' /tmp/all_accounts.json | \
        sort | awk -F'|' '{key=$1"|"$2; if (seen[key]++) print "  Duplicate: " $1 " at " $2 " - Accounts: " $3 " (" $4 ") - User: " $5}'
    echo ""
    
    # Find duplicates by accountId (should never happen, but check)
    echo "3. Duplicates by accountId (UUID):"
    jq -r '.Items[] | .accountId.S' /tmp/all_accounts.json | \
        sort | uniq -d | while read accountId; do
            echo "  ERROR: Duplicate accountId found: $accountId (this should never happen!)"
        done
    echo ""
    
    # Group by userId to see account distribution
    echo "4. Accounts per user:"
    jq -r '.Items[] | "\(.userId.S)"' /tmp/all_accounts.json | \
        sort | uniq -c | sort -rn | while read count userId; do
            echo "  User $userId: $count accounts"
        done
    echo ""
    
    # Show accounts without plaidAccountId
    echo "5. Accounts without plaidAccountId:"
    jq -r '.Items[] | select(.plaidAccountId.S == null or .plaidAccountId.S == "") | "  - Account: \(.accountId.S), Name: \(.accountName.S), User: \(.userId.S)"' /tmp/all_accounts.json
    echo ""
fi

echo "Analysis complete. Check /tmp/all_accounts.json for full data."

