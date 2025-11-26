#!/bin/bash

# Reset Local Database Script
# Clears all DynamoDB tables in LocalStack for fresh start

set -e

echo "🗑️  Resetting local database..."

# Check if LocalStack is running
if ! docker ps | grep -q budgetbuddy-localstack; then
    echo "❌ LocalStack is not running. Start it with: docker-compose up -d localstack"
    exit 1
fi

# Get list of tables
echo "📋 Listing DynamoDB tables..."
TABLES=$(docker exec budgetbuddy-localstack aws --endpoint-url=http://localhost:4566 --region us-east-1 dynamodb list-tables --output text --query 'TableNames[]' 2>/dev/null | grep -i budget || echo "")

if [ -z "$TABLES" ]; then
    echo "✅ No tables found. Database is already empty."
    exit 0
fi

echo "Found tables:"
echo "$TABLES" | while read -r table; do
    echo "  - $table"
done

# Confirm deletion
read -p "⚠️  Delete all tables? This will remove ALL data. (y/N): " -n 1 -r
echo
if [[ ! $REPLY =~ ^[Yy]$ ]]; then
    echo "❌ Cancelled."
    exit 1
fi

# Delete each table
echo "🗑️  Deleting tables..."
echo "$TABLES" | while read -r table; do
    if [ -n "$table" ]; then
        echo "  Deleting $table..."
        docker exec budgetbuddy-localstack aws --endpoint-url=http://localhost:4566 --region us-east-1 dynamodb delete-table --table-name "$table" >/dev/null 2>&1 || true
    fi
done

# Wait for tables to be deleted
echo "⏳ Waiting for tables to be deleted..."
sleep 3

# Verify deletion
REMAINING=$(docker exec budgetbuddy-localstack aws --endpoint-url=http://localhost:4566 --region us-east-1 dynamodb list-tables --output text --query 'TableNames[]' 2>/dev/null | grep -i budget || echo "")

if [ -z "$REMAINING" ]; then
    echo "✅ All tables deleted successfully!"
    echo ""
    echo "📝 Next steps:"
    echo "1. Restart the backend: docker-compose restart backend"
    echo "2. Tables will be recreated automatically on startup"
    echo "3. You can now register a new account"
else
    echo "⚠️  Some tables may still exist:"
    echo "$REMAINING"
fi

