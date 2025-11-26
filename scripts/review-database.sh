#!/bin/bash

# Database Review Script for BudgetBuddy Backend
# Reviews all DynamoDB tables, identifies duplicates, and provides statistics

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
OUTPUT_DIR="${OUTPUT_DIR:-./database-review-$(date +%Y%m%d-%H%M%S)}"

# Tables to review
TABLES=(
    "BudgetBuddy-Users"
    "BudgetBuddy-Accounts"
    "BudgetBuddy-Transactions"
    "BudgetBuddy-Budgets"
    "BudgetBuddy-Goals"
    "BudgetBuddy-AuditLogs"
)

echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}BudgetBuddy Database Review Script${NC}"
echo -e "${BLUE}========================================${NC}"
echo ""
echo "Table Prefix: $TABLE_PREFIX"
echo "AWS Region: $AWS_REGION"
echo "Output Directory: $OUTPUT_DIR"
echo ""

# Create output directory
mkdir -p "$OUTPUT_DIR"

# Function to check if table exists
check_table_exists() {
    local table_name=$1
    aws dynamodb describe-table --table-name "$table_name" --region "$AWS_REGION" > /dev/null 2>&1
}

# Function to get table item count
get_table_count() {
    local table_name=$1
    aws dynamodb describe-table --table-name "$table_name" --region "$AWS_REGION" \
        --query 'Table.ItemCount' --output text 2>/dev/null || echo "N/A"
}

# Function to scan table and save to file
scan_table() {
    local table_name=$1
    local output_file="$OUTPUT_DIR/${table_name}.json"
    
    echo -e "${YELLOW}Scanning $table_name...${NC}"
    
    # Use pagination to get all items
    aws dynamodb scan \
        --table-name "$table_name" \
        --region "$AWS_REGION" \
        --output json > "$output_file" 2>&1 || {
        echo -e "${RED}Error scanning $table_name${NC}"
        return 1
    }
    
    local count=$(jq '.Items | length' "$output_file" 2>/dev/null || echo "0")
    echo -e "${GREEN}  Found $count items${NC}"
    echo "$count"
}

# Function to analyze accounts for duplicates
analyze_accounts() {
    local table_name="BudgetBuddy-Accounts"
    local output_file="$OUTPUT_DIR/${table_name}.json"
    local analysis_file="$OUTPUT_DIR/${table_name}-analysis.txt"
    
    if [ ! -f "$output_file" ]; then
        echo -e "${RED}Accounts file not found. Please scan first.${NC}"
        return 1
    fi
    
    echo -e "${YELLOW}Analyzing accounts for duplicates...${NC}"
    
    # Use Python for complex analysis
    python3 << EOF > "$analysis_file" 2>&1 || echo "Python analysis failed"
import json
import sys
from collections import defaultdict

try:
    with open('$output_file', 'r') as f:
        data = json.load(f)
    
    items = data.get('Items', [])
    print(f"Total Accounts: {len(items)}")
    print("=" * 60)
    
    # Track by accountId
    account_ids = defaultdict(list)
    plaid_account_ids = defaultdict(list)
    user_accounts = defaultdict(list)
    
    for item in items:
        account_id = item.get('accountId', {}).get('S', 'N/A')
        plaid_id = item.get('plaidAccountId', {}).get('S')
        user_id = item.get('userId', {}).get('S', 'N/A')
        account_name = item.get('accountName', {}).get('S', 'N/A')
        active = item.get('active', {}).get('BOOL', True)
        
        account_ids[account_id].append({
            'accountId': account_id,
            'plaidAccountId': plaid_id,
            'userId': user_id,
            'accountName': account_name,
            'active': active
        })
        
        if plaid_id:
            plaid_account_ids[plaid_id].append({
                'accountId': account_id,
                'plaidAccountId': plaid_id,
                'userId': user_id,
                'accountName': account_name,
                'active': active
            })
        
        user_accounts[user_id].append(account_id)
    
    # Find duplicates by accountId
    duplicate_account_ids = {k: v for k, v in account_ids.items() if len(v) > 1}
    if duplicate_account_ids:
        print(f"\n⚠️  DUPLICATE ACCOUNT IDs FOUND: {len(duplicate_account_ids)}")
        for acc_id, accounts in duplicate_account_ids.items():
            print(f"  Account ID: {acc_id} ({len(accounts)} duplicates)")
            for acc in accounts:
                print(f"    - Plaid ID: {acc['plaidAccountId']}, User: {acc['userId']}, Name: {acc['accountName']}, Active: {acc['active']}")
    else:
        print("\n✅ No duplicate account IDs found")
    
    # Find duplicates by plaidAccountId
    duplicate_plaid_ids = {k: v for k, v in plaid_account_ids.items() if len(v) > 1}
    if duplicate_plaid_ids:
        print(f"\n⚠️  DUPLICATE PLAID ACCOUNT IDs FOUND: {len(duplicate_plaid_ids)}")
        for plaid_id, accounts in duplicate_plaid_ids.items():
            print(f"  Plaid Account ID: {plaid_id} ({len(accounts)} duplicates)")
            for acc in accounts:
                print(f"    - Account ID: {acc['accountId']}, User: {acc['userId']}, Name: {acc['accountName']}, Active: {acc['active']}")
    else:
        print("\n✅ No duplicate Plaid account IDs found")
    
    # Statistics by user
    print(f"\n📊 Accounts by User:")
    for user_id, account_list in sorted(user_accounts.items(), key=lambda x: len(x[1]), reverse=True):
        print(f"  User {user_id}: {len(account_list)} accounts")
    
    # Active vs Inactive
    active_count = sum(1 for item in items if item.get('active', {}).get('BOOL', True))
    inactive_count = len(items) - active_count
    print(f"\n📊 Active Accounts: {active_count}")
    print(f"📊 Inactive Accounts: {inactive_count}")
    
except Exception as e:
    print(f"Error: {e}", file=sys.stderr)
    sys.exit(1)
EOF
    
    cat "$analysis_file"
}

# Function to analyze transactions for duplicates
analyze_transactions() {
    local table_name="BudgetBuddy-Transactions"
    local output_file="$OUTPUT_DIR/${table_name}.json"
    local analysis_file="$OUTPUT_DIR/${table_name}-analysis.txt"
    
    if [ ! -f "$output_file" ]; then
        echo -e "${RED}Transactions file not found. Please scan first.${NC}"
        return 1
    fi
    
    echo -e "${YELLOW}Analyzing transactions for duplicates...${NC}"
    
    # Use Python for complex analysis
    python3 << EOF > "$analysis_file" 2>&1 || echo "Python analysis failed"
import json
import sys
from collections import defaultdict

try:
    with open('$output_file', 'r') as f:
        data = json.load(f)
    
    items = data.get('Items', [])
    print(f"Total Transactions: {len(items)}")
    print("=" * 60)
    
    # Track by transactionId and plaidTransactionId
    transaction_ids = defaultdict(list)
    plaid_transaction_ids = defaultdict(list)
    user_transactions = defaultdict(list)
    
    for item in items:
        trans_id = item.get('transactionId', {}).get('S', 'N/A')
        plaid_id = item.get('plaidTransactionId', {}).get('S')
        user_id = item.get('userId', {}).get('S', 'N/A')
        account_id = item.get('accountId', {}).get('S', 'N/A')
        description = item.get('description', {}).get('S', 'N/A')
        amount = item.get('amount', {}).get('N', '0')
        date = item.get('transactionDate', {}).get('S', 'N/A')
        
        transaction_ids[trans_id].append({
            'transactionId': trans_id,
            'plaidTransactionId': plaid_id,
            'userId': user_id,
            'accountId': account_id,
            'description': description,
            'amount': amount,
            'date': date
        })
        
        if plaid_id:
            plaid_transaction_ids[plaid_id].append({
                'transactionId': trans_id,
                'plaidTransactionId': plaid_id,
                'userId': user_id,
                'accountId': account_id,
                'description': description,
                'amount': amount,
                'date': date
            })
        
        user_transactions[user_id].append(trans_id)
    
    # Find duplicates by transactionId
    duplicate_trans_ids = {k: v for k, v in transaction_ids.items() if len(v) > 1}
    if duplicate_trans_ids:
        print(f"\n⚠️  DUPLICATE TRANSACTION IDs FOUND: {len(duplicate_trans_ids)}")
        for trans_id, transactions in list(duplicate_trans_ids.items())[:10]:  # Show first 10
            print(f"  Transaction ID: {trans_id} ({len(transactions)} duplicates)")
            for trans in transactions:
                print(f"    - Plaid ID: {trans['plaidTransactionId']}, User: {trans['userId']}, Account: {trans['accountId']}, Date: {trans['date']}, Amount: {trans['amount']}")
        if len(duplicate_trans_ids) > 10:
            print(f"    ... and {len(duplicate_trans_ids) - 10} more")
    else:
        print("\n✅ No duplicate transaction IDs found")
    
    # Find duplicates by plaidTransactionId
    duplicate_plaid_ids = {k: v for k, v in plaid_transaction_ids.items() if len(v) > 1}
    if duplicate_plaid_ids:
        print(f"\n⚠️  DUPLICATE PLAID TRANSACTION IDs FOUND: {len(duplicate_plaid_ids)}")
        for plaid_id, transactions in list(duplicate_plaid_ids.items())[:10]:  # Show first 10
            print(f"  Plaid Transaction ID: {plaid_id} ({len(transactions)} duplicates)")
            for trans in transactions:
                print(f"    - Transaction ID: {trans['transactionId']}, User: {trans['userId']}, Account: {trans['accountId']}, Date: {trans['date']}, Amount: {trans['amount']}")
        if len(duplicate_plaid_ids) > 10:
            print(f"    ... and {len(duplicate_plaid_ids) - 10} more")
    else:
        print("\n✅ No duplicate Plaid transaction IDs found")
    
    # Statistics by user
    print(f"\n📊 Transactions by User:")
    for user_id, trans_list in sorted(user_transactions.items(), key=lambda x: len(x[1]), reverse=True):
        print(f"  User {user_id}: {len(trans_list)} transactions")
    
except Exception as e:
    print(f"Error: {e}", file=sys.stderr)
    sys.exit(1)
EOF
    
    cat "$analysis_file"
}

# Main execution
echo -e "${BLUE}Step 1: Checking tables...${NC}"
echo ""

for table in "${TABLES[@]}"; do
    if check_table_exists "$table"; then
        count=$(get_table_count "$table")
        echo -e "${GREEN}✓${NC} $table exists (Item Count: $count)"
    else
        echo -e "${YELLOW}⚠${NC}  $table does not exist"
    fi
done

echo ""
echo -e "${BLUE}Step 2: Scanning tables...${NC}"
echo ""

TOTAL_ITEMS=0
for table in "${TABLES[@]}"; do
    if check_table_exists "$table"; then
        count=$(scan_table "$table")
        TOTAL_ITEMS=$((TOTAL_ITEMS + count))
    fi
done

echo ""
echo -e "${GREEN}Total items scanned: $TOTAL_ITEMS${NC}"
echo ""

echo -e "${BLUE}Step 3: Analyzing for duplicates...${NC}"
echo ""

# Analyze Accounts
if check_table_exists "BudgetBuddy-Accounts"; then
    analyze_accounts
    echo ""
fi

# Analyze Transactions
if check_table_exists "BudgetBuddy-Transactions"; then
    analyze_transactions
    echo ""
fi

echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}Review complete!${NC}"
echo -e "${GREEN}Output saved to: $OUTPUT_DIR${NC}"
echo -e "${GREEN}========================================${NC}"

