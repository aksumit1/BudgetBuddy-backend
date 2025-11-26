#!/bin/bash

# Script to clear all users from LocalStack DynamoDB
# This helps when testing registration after deletion

set -e

echo "Clearing all users from DynamoDB..."

# Scan and delete all users
docker-compose exec -T localstack aws --endpoint-url=http://localhost:4566 dynamodb scan \
    --table-name BudgetBuddy-Users \
    --region us-east-1 \
    --output json 2>/dev/null | python3 -c "
import sys, json
try:
    data = json.load(sys.stdin)
    items = data.get('Items', [])
    for item in items:
        userId = item.get('userId', {}).get('S', '')
        if userId:
            print(userId)
except:
    pass
" 2>/dev/null | while read userId; do
    if [ ! -z "$userId" ]; then
        echo "Deleting user: $userId"
        docker-compose exec -T localstack aws --endpoint-url=http://localhost:4566 dynamodb delete-item \
            --table-name BudgetBuddy-Users \
            --key "{\"userId\":{\"S\":\"$userId\"}}" \
            --region us-east-1 > /dev/null 2>&1 || true
    fi
done

echo "All users cleared!"
echo ""
echo "Restarting backend to clear cache..."
docker-compose restart backend

echo ""
echo "Done! You can now register with any email address."
echo "Note: Wait 5-10 seconds for GSI to update (eventual consistency)"

