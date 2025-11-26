#!/usr/bin/env python3
"""
Database Review Script for BudgetBuddy Backend
Reviews all DynamoDB tables, identifies duplicates, and provides statistics

Usage:
    python3 review-database.py [--region us-east-1] [--table-prefix BudgetBuddy]
"""

import boto3
import json
import sys
import argparse
from collections import defaultdict
from datetime import datetime
from typing import Dict, List, Any

# Colors for terminal output
class Colors:
    RED = '\033[0;31m'
    GREEN = '\033[0;32m'
    YELLOW = '\033[1;33m'
    BLUE = '\033[0;34m'
    NC = '\033[0m'  # No Color

def print_header(text: str):
    print(f"\n{Colors.BLUE}{'=' * 60}{Colors.NC}")
    print(f"{Colors.BLUE}{text}{Colors.NC}")
    print(f"{Colors.BLUE}{'=' * 60}{Colors.NC}\n")

def print_success(text: str):
    print(f"{Colors.GREEN}✓{Colors.NC} {text}")

def print_warning(text: str):
    print(f"{Colors.YELLOW}⚠{Colors.NC}  {text}")

def print_error(text: str):
    print(f"{Colors.RED}✗{Colors.NC} {text}")

def scan_table(dynamodb_client, table_name: str) -> List[Dict[str, Any]]:
    """Scan a DynamoDB table and return all items"""
    items = []
    last_evaluated_key = None
    
    print(f"Scanning {table_name}...", end=" ", flush=True)
    
    while True:
        if last_evaluated_key:
            response = dynamodb_client.scan(
                TableName=table_name,
                ExclusiveStartKey=last_evaluated_key
            )
        else:
            response = dynamodb_client.scan(TableName=table_name)
        
        items.extend(response.get('Items', []))
        
        last_evaluated_key = response.get('LastEvaluatedKey')
        if not last_evaluated_key:
            break
    
    print(f"Found {len(items)} items")
    return items

def analyze_accounts(items: List[Dict[str, Any]]) -> Dict[str, Any]:
    """Analyze accounts for duplicates and statistics"""
    analysis = {
        'total': len(items),
        'duplicate_account_ids': {},
        'duplicate_plaid_ids': {},
        'by_user': defaultdict(int),
        'active': 0,
        'inactive': 0
    }
    
    account_id_map = defaultdict(list)
    plaid_id_map = defaultdict(list)
    
    for item in items:
        account_id = item.get('accountId', {}).get('S')
        plaid_id = item.get('plaidAccountId', {}).get('S')
        user_id = item.get('userId', {}).get('S', 'N/A')
        active = item.get('active', {}).get('BOOL', True)
        
        if account_id:
            account_id_map[account_id].append(item)
        
        if plaid_id:
            plaid_id_map[plaid_id].append(item)
        
        analysis['by_user'][user_id] += 1
        
        if active:
            analysis['active'] += 1
        else:
            analysis['inactive'] += 1
    
    # Find duplicates
    for acc_id, group in account_id_map.items():
        if len(group) > 1:
            analysis['duplicate_account_ids'][acc_id] = group
    
    for plaid_id, group in plaid_id_map.items():
        if len(group) > 1:
            analysis['duplicate_plaid_ids'][plaid_id] = group
    
    return analysis

def analyze_transactions(items: List[Dict[str, Any]]) -> Dict[str, Any]:
    """Analyze transactions for duplicates and statistics"""
    analysis = {
        'total': len(items),
        'duplicate_transaction_ids': {},
        'duplicate_plaid_ids': {},
        'by_user': defaultdict(int),
        'by_account': defaultdict(int)
    }
    
    transaction_id_map = defaultdict(list)
    plaid_id_map = defaultdict(list)
    
    for item in items:
        trans_id = item.get('transactionId', {}).get('S')
        plaid_id = item.get('plaidTransactionId', {}).get('S')
        user_id = item.get('userId', {}).get('S', 'N/A')
        account_id = item.get('accountId', {}).get('S', 'N/A')
        
        if trans_id:
            transaction_id_map[trans_id].append(item)
        
        if plaid_id:
            plaid_id_map[plaid_id].append(item)
        
        analysis['by_user'][user_id] += 1
        analysis['by_account'][account_id] += 1
    
    # Find duplicates
    for trans_id, group in transaction_id_map.items():
        if len(group) > 1:
            analysis['duplicate_transaction_ids'][trans_id] = group
    
    for plaid_id, group in plaid_id_map.items():
        if len(group) > 1:
            analysis['duplicate_plaid_ids'][plaid_id] = group
    
    return analysis

def print_account_analysis(analysis: Dict[str, Any]):
    """Print account analysis results"""
    print(f"\nTotal Accounts: {analysis['total']}")
    print(f"Active: {analysis['active']}, Inactive: {analysis['inactive']}")
    
    if analysis['duplicate_account_ids']:
        print_warning(f"DUPLICATE ACCOUNT IDs FOUND: {len(analysis['duplicate_account_ids'])}")
        for acc_id, group in list(analysis['duplicate_account_ids'].items())[:10]:
            print(f"  Account ID: {acc_id} ({len(group)} duplicates)")
            for acc in group:
                plaid_id = acc.get('plaidAccountId', {}).get('S', 'N/A')
                user_id = acc.get('userId', {}).get('S', 'N/A')
                name = acc.get('accountName', {}).get('S', 'N/A')
                print(f"    - Plaid ID: {plaid_id}, User: {user_id}, Name: {name}")
        if len(analysis['duplicate_account_ids']) > 10:
            print(f"    ... and {len(analysis['duplicate_account_ids']) - 10} more")
    else:
        print_success("No duplicate account IDs found")
    
    if analysis['duplicate_plaid_ids']:
        print_warning(f"DUPLICATE PLAID ACCOUNT IDs FOUND: {len(analysis['duplicate_plaid_ids'])}")
        for plaid_id, group in list(analysis['duplicate_plaid_ids'].items())[:10]:
            print(f"  Plaid Account ID: {plaid_id} ({len(group)} duplicates)")
            for acc in group:
                acc_id = acc.get('accountId', {}).get('S', 'N/A')
                user_id = acc.get('userId', {}).get('S', 'N/A')
                name = acc.get('accountName', {}).get('S', 'N/A')
                print(f"    - Account ID: {acc_id}, User: {user_id}, Name: {name}")
        if len(analysis['duplicate_plaid_ids']) > 10:
            print(f"    ... and {len(analysis['duplicate_plaid_ids']) - 10} more")
    else:
        print_success("No duplicate Plaid account IDs found")
    
    print(f"\nAccounts by User (top 10):")
    for user_id, count in sorted(analysis['by_user'].items(), key=lambda x: x[1], reverse=True)[:10]:
        print(f"  User {user_id}: {count} accounts")

def print_transaction_analysis(analysis: Dict[str, Any]):
    """Print transaction analysis results"""
    print(f"\nTotal Transactions: {analysis['total']}")
    
    if analysis['duplicate_transaction_ids']:
        print_warning(f"DUPLICATE TRANSACTION IDs FOUND: {len(analysis['duplicate_transaction_ids'])}")
        for trans_id, group in list(analysis['duplicate_transaction_ids'].items())[:5]:
            print(f"  Transaction ID: {trans_id} ({len(group)} duplicates)")
        if len(analysis['duplicate_transaction_ids']) > 5:
            print(f"    ... and {len(analysis['duplicate_transaction_ids']) - 5} more")
    else:
        print_success("No duplicate transaction IDs found")
    
    if analysis['duplicate_plaid_ids']:
        print_warning(f"DUPLICATE PLAID TRANSACTION IDs FOUND: {len(analysis['duplicate_plaid_ids'])}")
        for plaid_id, group in list(analysis['duplicate_plaid_ids'].items())[:5]:
            print(f"  Plaid Transaction ID: {plaid_id} ({len(group)} duplicates)")
        if len(analysis['duplicate_plaid_ids']) > 5:
            print(f"    ... and {len(analysis['duplicate_plaid_ids']) - 5} more")
    else:
        print_success("No duplicate Plaid transaction IDs found")
    
    print(f"\nTransactions by User (top 10):")
    for user_id, count in sorted(analysis['by_user'].items(), key=lambda x: x[1], reverse=True)[:10]:
        print(f"  User {user_id}: {count} transactions")

def main():
    parser = argparse.ArgumentParser(description='Review BudgetBuddy DynamoDB tables')
    parser.add_argument('--region', default='us-east-1', help='AWS region')
    parser.add_argument('--table-prefix', default='BudgetBuddy', help='Table name prefix')
    parser.add_argument('--output-dir', default=None, help='Output directory for JSON files')
    args = parser.parse_args()
    
    print_header("BudgetBuddy Database Review")
    print(f"Region: {args.region}")
    print(f"Table Prefix: {args.table_prefix}")
    print(f"Timestamp: {datetime.now().isoformat()}")
    
    # Initialize DynamoDB client
    try:
        dynamodb = boto3.client('dynamodb', region_name=args.region)
    except Exception as e:
        print_error(f"Failed to initialize DynamoDB client: {e}")
        sys.exit(1)
    
    # Tables to review
    tables = [
        f"{args.table_prefix}-Users",
        f"{args.table_prefix}-Accounts",
        f"{args.table_prefix}-Transactions",
        f"{args.table_prefix}-Budgets",
        f"{args.table_prefix}-Goals",
        f"{args.table_prefix}-AuditLogs"
    ]
    
    # Check which tables exist
    print_header("Step 1: Checking Tables")
    existing_tables = []
    for table in tables:
        try:
            response = dynamodb.describe_table(TableName=table)
            item_count = response['Table'].get('ItemCount', 'N/A')
            print_success(f"{table} exists (Item Count: {item_count})")
            existing_tables.append(table)
        except dynamodb.exceptions.ResourceNotFoundException:
            print_warning(f"{table} does not exist")
        except Exception as e:
            print_error(f"Error checking {table}: {e}")
    
    # Scan and analyze tables
    print_header("Step 2: Analyzing Tables")
    
    # Analyze Accounts
    if f"{args.table_prefix}-Accounts" in existing_tables:
        print_header("Accounts Analysis")
        try:
            accounts = scan_table(dynamodb, f"{args.table_prefix}-Accounts")
            if accounts:
                analysis = analyze_accounts(accounts)
                print_account_analysis(analysis)
                
                # Save to file if output directory specified
                if args.output_dir:
                    import os
                    os.makedirs(args.output_dir, exist_ok=True)
                    with open(f"{args.output_dir}/accounts-analysis.json", 'w') as f:
                        json.dump(analysis, f, indent=2, default=str)
        except Exception as e:
            print_error(f"Error analyzing accounts: {e}")
    
    # Analyze Transactions
    if f"{args.table_prefix}-Transactions" in existing_tables:
        print_header("Transactions Analysis")
        try:
            transactions = scan_table(dynamodb, f"{args.table_prefix}-Transactions")
            if transactions:
                analysis = analyze_transactions(transactions)
                print_transaction_analysis(analysis)
                
                # Save to file if output directory specified
                if args.output_dir:
                    import os
                    os.makedirs(args.output_dir, exist_ok=True)
                    with open(f"{args.output_dir}/transactions-analysis.json", 'w') as f:
                        json.dump(analysis, f, indent=2, default=str)
        except Exception as e:
            print_error(f"Error analyzing transactions: {e}")
    
    print_header("Review Complete!")

if __name__ == '__main__':
    main()

