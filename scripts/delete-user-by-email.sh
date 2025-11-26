#!/bin/bash

# Script to delete a user by email from LocalStack DynamoDB
# Usage: ./scripts/delete-user-by-email.sh <email>

set -e

EMAIL="${1}"
if [ -z "$EMAIL" ]; then
    echo "Usage: $0 <email>"
    echo "Example: $0 s@yahoo.com"
    exit 1
fi

echo "Deleting user with email: $EMAIL"

# Query the user by email using the GSI
USER_DATA=$(docker-compose exec -T localstack aws --endpoint-url=http://localhost:4566 dynamodb query \
    --table-name BudgetBuddy-Users \
    --index-name EmailIndex \
    --key-condition-expression "email = :email" \
    --expression-attribute-values "{\":email\":{\"S\":\"$EMAIL\"}}" \
    --region us-east-1 2>/dev/null || echo "{}")

# Extract userId from the response
USER_ID=$(echo "$USER_DATA" | python3 -c "
import sys, json
try:
    data = json.load(sys.stdin)
    items = data.get('Items', [])
    if items:
        userId = items[0].get('userId', {}).get('S', '')
        print(userId)
    else:
        print('')
except:
    print('')
" 2>/dev/null || echo "")

if [ -z "$USER_ID" ]; then
    echo "User with email $EMAIL not found in database"
    exit 0
fi

echo "Found user ID: $USER_ID"
echo "Deleting user..."

# Delete the user by userId
docker-compose exec -T localstack aws --endpoint-url=http://localhost:4566 dynamodb delete-item \
    --table-name BudgetBuddy-Users \
    --key "{\"userId\":{\"S\":\"$USER_ID\"}}" \
    --region us-east-1 > /dev/null 2>&1

echo "User deleted successfully!"
echo ""
echo "Note: You may need to wait a few seconds for the GSI to update (eventual consistency)"
echo "You may also need to restart the backend to clear the cache: docker-compose restart backend"

