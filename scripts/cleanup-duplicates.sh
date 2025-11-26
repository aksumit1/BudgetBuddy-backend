#!/bin/bash

# Database Cleanup Script for BudgetBuddy Backend
# Removes duplicate accounts and transactions from DynamoDB

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
DRY_RUN="${DRY_RUN:-true}"

echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}BudgetBuddy Duplicate Cleanup Script${NC}"
echo -e "${BLUE}========================================${NC}"
echo ""
echo "Table Prefix: $TABLE_PREFIX"
echo "AWS Region: $AWS_REGION"
echo "Dry Run: $DRY_RUN"
echo ""

if [ "$DRY_RUN" = "true" ]; then
    echo -e "${YELLOW}⚠️  DRY RUN MODE - No changes will be made${NC}"
    echo ""
fi

# Function to cleanup duplicate accounts
cleanup_duplicate_accounts() {
    local table_name="BudgetBuddy-Accounts"
    local temp_file=$(mktemp)
    
    echo -e "${YELLOW}Scanning $table_name for duplicates...${NC}"
    
    # Scan table
    aws dynamodb scan \
        --table-name "$table_name" \
        --region "$AWS_REGION" \
        --output json > "$temp_file" 2>&1
    
    # Use Python to identify and remove duplicates
    python3 << EOF
import json
import sys
import subprocess
from collections import defaultdict

try:
    with open('$temp_file', 'r') as f:
        data = json.load(f)
    
    items = data.get('Items', [])
    print(f"Total Accounts: {len(items)}")
    
    # Group by plaidAccountId (primary deduplication key)
    plaid_groups = defaultdict(list)
    for item in items:
        plaid_id = item.get('plaidAccountId', {}).get('S')
        if plaid_id:
            plaid_groups[plaid_id].append(item)
    
    # Find duplicates
    duplicates_to_delete = []
    for plaid_id, group in plaid_groups.items():
        if len(group) > 1:
            # Keep the one with the latest updatedAt, delete others
            group_sorted = sorted(group, 
                key=lambda x: int(x.get('updatedAt', {}).get('N', '0')), 
                reverse=True)
            
            # Keep first (most recent), mark others for deletion
            for dup in group_sorted[1:]:
                account_id = dup.get('accountId', {}).get('S')
                if account_id:
                    duplicates_to_delete.append({
                        'accountId': account_id,
                        'plaidAccountId': plaid_id,
                        'accountName': dup.get('accountName', {}).get('S', 'N/A')
                    })
    
    print(f"\nFound {len(duplicates_to_delete)} duplicate accounts to remove")
    
    if len(duplicates_to_delete) > 0:
        print("\nDuplicates to delete:")
        for dup in duplicates_to_delete[:10]:  # Show first 10
            print(f"  - Account ID: {dup['accountId']}, Plaid ID: {dup['plaidAccountId']}, Name: {dup['accountName']}")
        if len(duplicates_to_delete) > 10:
            print(f"  ... and {len(duplicates_to_delete) - 10} more")
        
        if '$DRY_RUN' == 'false':
            print("\nDeleting duplicates...")
            for dup in duplicates_to_delete:
                account_id = dup['accountId']
                try:
                    subprocess.run([
                        'aws', 'dynamodb', 'delete-item',
                        '--table-name', '$table_name',
                        '--region', '$AWS_REGION',
                        '--key', json.dumps({
                            'accountId': {'S': account_id}
                        })
                    ], check=True, capture_output=True)
                    print(f"  ✓ Deleted account {account_id}")
                except subprocess.CalledProcessError as e:
                    print(f"  ✗ Failed to delete account {account_id}: {e}")
        else:
            print("\n⚠️  DRY RUN - Would delete the above accounts")
    else:
        print("\n✅ No duplicate accounts found")
    
except Exception as e:
    print(f"Error: {e}", file=sys.stderr)
    sys.exit(1)
EOF
    
    rm -f "$temp_file"
}

# Function to cleanup duplicate transactions
cleanup_duplicate_transactions() {
    local table_name="BudgetBuddy-Transactions"
    local temp_file=$(mktemp)
    
    echo -e "${YELLOW}Scanning $table_name for duplicates...${NC}"
    
    # Scan table
    aws dynamodb scan \
        --table-name "$table_name" \
        --region "$AWS_REGION" \
        --output json > "$temp_file" 2>&1
    
    # Use Python to identify and remove duplicates
    python3 << EOF
import json
import sys
import subprocess
from collections import defaultdict

try:
    with open('$temp_file', 'r') as f:
        data = json.load(f)
    
    items = data.get('Items', [])
    print(f"Total Transactions: {len(items)}")
    
    # Group by plaidTransactionId (primary deduplication key)
    plaid_groups = defaultdict(list)
    for item in items:
        plaid_id = item.get('plaidTransactionId', {}).get('S')
        if plaid_id:
            plaid_groups[plaid_id].append(item)
    
    # Find duplicates
    duplicates_to_delete = []
    for plaid_id, group in plaid_groups.items():
        if len(group) > 1:
            # Keep the one with the latest updatedAt, delete others
            group_sorted = sorted(group, 
                key=lambda x: int(x.get('updatedAt', {}).get('N', '0')), 
                reverse=True)
            
            # Keep first (most recent), mark others for deletion
            for dup in group_sorted[1:]:
                transaction_id = dup.get('transactionId', {}).get('S')
                if transaction_id:
                    duplicates_to_delete.append({
                        'transactionId': transaction_id,
                        'plaidTransactionId': plaid_id,
                        'description': dup.get('description', {}).get('S', 'N/A')
                    })
    
    print(f"\nFound {len(duplicates_to_delete)} duplicate transactions to remove")
    
    if len(duplicates_to_delete) > 0:
        print("\nDuplicates to delete (showing first 10):")
        for dup in duplicates_to_delete[:10]:
            print(f"  - Transaction ID: {dup['transactionId']}, Plaid ID: {dup['plaidTransactionId']}, Desc: {dup['description']}")
        if len(duplicates_to_delete) > 10:
            print(f"  ... and {len(duplicates_to_delete) - 10} more")
        
        if '$DRY_RUN' == 'false':
            print("\nDeleting duplicates...")
            deleted = 0
            for dup in duplicates_to_delete:
                transaction_id = dup['transactionId']
                try:
                    subprocess.run([
                        'aws', 'dynamodb', 'delete-item',
                        '--table-name', '$table_name',
                        '--region', '$AWS_REGION',
                        '--key', json.dumps({
                            'transactionId': {'S': transaction_id}
                        })
                    ], check=True, capture_output=True)
                    deleted += 1
                    if deleted % 100 == 0:
                        print(f"  Deleted {deleted} transactions...")
                except subprocess.CalledProcessError as e:
                    print(f"  ✗ Failed to delete transaction {transaction_id}: {e}")
            print(f"\n✓ Deleted {deleted} duplicate transactions")
        else:
            print("\n⚠️  DRY RUN - Would delete the above transactions")
    else:
        print("\n✅ No duplicate transactions found")
    
except Exception as e:
    print(f"Error: {e}", file=sys.stderr)
    sys.exit(1)
EOF
    
    rm -f "$temp_file"
}

# Main execution
read -p "Do you want to cleanup duplicate accounts? (y/N): " -n 1 -r
echo
if [[ $REPLY =~ ^[Yy]$ ]]; then
    cleanup_duplicate_accounts
    echo ""
fi

read -p "Do you want to cleanup duplicate transactions? (y/N): " -n 1 -r
echo
if [[ $REPLY =~ ^[Yy]$ ]]; then
    cleanup_duplicate_transactions
    echo ""
fi

echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}Cleanup complete!${NC}"
echo -e "${GREEN}========================================${NC}"

