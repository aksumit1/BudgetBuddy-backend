#!/bin/bash

# Delete All Data Script for BudgetBuddy Backend
# WARNING: This will delete ALL data from all DynamoDB tables!
# Use only for testing/development environments

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Configuration
TABLE_PREFIX="${TABLE_PREFIX:-BudgetBuddy}"
AWS_REGION="${AWS_REGION:-us-east-1}"
CONFIRM="${CONFIRM:-false}"

echo -e "${RED}========================================${NC}"
echo -e "${RED}⚠️  WARNING: DELETE ALL DATA ⚠️${NC}"
echo -e "${RED}========================================${NC}"
echo ""
echo -e "${YELLOW}This script will DELETE ALL DATA from the following tables:${NC}"
echo "  - ${TABLE_PREFIX}-Users"
echo "  - ${TABLE_PREFIX}-Accounts"
echo "  - ${TABLE_PREFIX}-Transactions"
echo "  - ${TABLE_PREFIX}-Budgets"
echo "  - ${TABLE_PREFIX}-Goals"
echo "  - ${TABLE_PREFIX}-AuditLogs"
echo ""
echo -e "${RED}This action CANNOT be undone!${NC}"
echo ""

# Double confirmation
if [ "$CONFIRM" != "true" ]; then
    echo -e "${YELLOW}To proceed, you must:${NC}"
    echo "  1. Set CONFIRM=true environment variable"
    echo "  2. Type 'DELETE ALL DATA' when prompted"
    echo ""
    read -p "Type 'DELETE ALL DATA' to confirm: " confirmation
    
    if [ "$confirmation" != "DELETE ALL DATA" ]; then
        echo -e "${GREEN}Cancelled. No data was deleted.${NC}"
        exit 0
    fi
fi

# Tables to clear
TABLES=(
    "BudgetBuddy-Users"
    "BudgetBuddy-Accounts"
    "BudgetBuddy-Transactions"
    "BudgetBuddy-Budgets"
    "BudgetBuddy-Goals"
    "BudgetBuddy-AuditLogs"
)

# Function to check if table exists
check_table_exists() {
    local table_name=$1
    aws dynamodb describe-table --table-name "$table_name" --region "$AWS_REGION" > /dev/null 2>&1
}

# Function to delete all items from a table
delete_all_items() {
    local table_name=$1
    local temp_file=$(mktemp)
    
    echo -e "${YELLOW}Deleting all items from $table_name...${NC}"
    
    # Scan and delete in batches
    python3 << EOF
import boto3
import sys
from botocore.exceptions import ClientError

try:
    dynamodb = boto3.client('dynamodb', region_name='$AWS_REGION')
    table_name = '$table_name'
    
    deleted_count = 0
    last_evaluated_key = None
    
    while True:
        # Scan for items
        if last_evaluated_key:
            response = dynamodb.scan(
                TableName=table_name,
                ExclusiveStartKey=last_evaluated_key
            )
        else:
            response = dynamodb.scan(TableName=table_name)
        
        items = response.get('Items', [])
        
        if not items:
            break
        
        # Delete items in batches (DynamoDB allows up to 25 items per batch)
        batch_size = 25
        for i in range(0, len(items), batch_size):
            batch = items[i:i + batch_size]
            
            write_requests = []
            for item in batch:
                # Extract key based on table
                key = {}
                if 'userId' in item:
                    key['userId'] = item['userId']
                if 'accountId' in item:
                    key['accountId'] = item['accountId']
                if 'transactionId' in item:
                    key['transactionId'] = item['transactionId']
                if 'budgetId' in item:
                    key['budgetId'] = item['budgetId']
                if 'goalId' in item:
                    key['goalId'] = item['goalId']
                if 'auditLogId' in item:
                    key['auditLogId'] = item['auditLogId']
                
                if key:
                    write_requests.append({
                        'DeleteRequest': {'Key': key}
                    })
            
            if write_requests:
                try:
                    dynamodb.batch_write_item(
                        RequestItems={
                            table_name: write_requests
                        }
                    )
                    deleted_count += len(write_requests)
                    print(f"  Deleted {deleted_count} items...", end='\r', flush=True)
                except ClientError as e:
                    print(f"\nError deleting batch: {e}", file=sys.stderr)
        
        last_evaluated_key = response.get('LastEvaluatedKey')
        if not last_evaluated_key:
            break
    
    print(f"\n✓ Deleted {deleted_count} items from {table_name}")
    
except Exception as e:
    print(f"Error: {e}", file=sys.stderr)
    sys.exit(1)
EOF
    
    rm -f "$temp_file"
}

# Main execution
echo -e "${BLUE}Starting deletion process...${NC}"
echo ""

for table in "${TABLES[@]}"; do
    if check_table_exists "$table"; then
        delete_all_items "$table"
    else
        echo -e "${YELLOW}⚠${NC}  $table does not exist, skipping"
    fi
done

echo ""
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}All data deleted successfully!${NC}"
echo -e "${GREEN}========================================${NC}"

