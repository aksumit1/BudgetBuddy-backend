#!/bin/bash
# Script to check transactions in the backend database

echo "=== Checking Transactions in Backend Database ==="
echo ""

# Check if LocalStack is running
if ! curl -s http://localhost:4566 > /dev/null 2>&1; then
    echo "❌ LocalStack is not running. Please start it first."
    exit 1
fi

echo "✅ LocalStack is running"
echo ""

# Use AWS CLI to scan TransactionTable
echo "Scanning TransactionTable..."
aws dynamodb scan \
    --table-name TransactionTable \
    --endpoint-url http://localhost:4566 \
    --region us-east-1 \
    --output json \
    | jq -r '.Items | length' \
    | xargs -I {} echo "Total transactions in database: {}"

echo ""
echo "Sample transactions (first 5):"
aws dynamodb scan \
    --table-name TransactionTable \
    --endpoint-url http://localhost:4566 \
    --region us-east-1 \
    --limit 5 \
    --output json \
    | jq -r '.Items[] | "Transaction ID: \(.transactionId.S // .transactionId.N // "N/A"), User ID: \(.userId.S // "N/A"), Date: \(.transactionDate.S // "N/A"), Amount: \(.amount.N // .amount.S // "N/A"), Description: \(.description.S // "N/A")"'

echo ""
echo "Transactions by user (grouped):"
aws dynamodb scan \
    --table-name TransactionTable \
    --endpoint-url http://localhost:4566 \
    --region us-east-1 \
    --output json \
    | jq -r '.Items | group_by(.userId.S) | .[] | "User: \(.[0].userId.S // "N/A"), Count: \(length)"'

echo ""
echo "Date range of transactions:"
aws dynamodb scan \
    --table-name TransactionTable \
    --endpoint-url http://localhost:4566 \
    --region us-east-1 \
    --output json \
    | jq -r '.Items | map(.transactionDate.S // empty) | sort | "Earliest: \(.[0] // "N/A"), Latest: \(.[-1] // "N/A")"'

