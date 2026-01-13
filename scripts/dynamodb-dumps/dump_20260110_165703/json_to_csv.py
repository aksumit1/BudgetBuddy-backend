#!/usr/bin/env python3
"""
Convert BudgetBuddy-Transactions.json to CSV files that can be opened in Excel
Creates multiple CSV files for different views
"""

import json
import csv
import sys
from collections import defaultdict
from decimal import Decimal
from datetime import datetime
import os


def extract_dynamodb_value(item, key):
    """Extract value from DynamoDB format"""
    if key not in item:
        return None
    value = item[key]
    if 'S' in value:
        return value['S']
    elif 'N' in value:
        return value['N']
    elif 'BOOL' in value:
        return value['BOOL']
    return None


def format_amount(amount_str):
    """Format amount for display"""
    if not amount_str:
        return None
    try:
        amount = Decimal(amount_str)
        return float(amount)
    except:
        return amount_str


def write_csv(filename, headers, rows):
    """Write CSV file"""
    with open(filename, 'w', newline='', encoding='utf-8') as f:
        writer = csv.writer(f)
        writer.writerow(headers)
        writer.writerows(rows)
    print(f"Created: {filename} ({len(rows)} rows)")


def main():
    json_file = sys.argv[1] if len(sys.argv) > 1 else 'BudgetBuddy-Transactions.json'
    base_name = json_file.replace('.json', '')
    
    print(f"Reading transactions from: {json_file}")
    with open(json_file, 'r') as f:
        data = json.load(f)
    
    items = data.get('Items', [])
    print(f"Total transactions in file: {len(items)}")
    
    # Filter to expense transactions for the user
    user_id = 'c8920e3b-7293-4674-a5f3-fa4c0c6f115a'
    transactions = []
    for item in items:
        uid = extract_dynamodb_value(item, 'userId')
        if uid != user_id:
            continue
        tx_type = extract_dynamodb_value(item, 'transactionType')
        if tx_type != 'EXPENSE':
            continue
        transactions.append(item)
    
    print(f"Expense transactions for user: {len(transactions)}")
    
    # Sort by date (newest first)
    transactions.sort(key=lambda x: extract_dynamodb_value(x, 'transactionDate') or '', reverse=True)
    
    # CSV 1: All Transactions
    headers_all = [
        "Transaction ID", "Date", "Merchant Name", "Description", 
        "Amount", "Category Primary", "Category Detailed",
        "Account ID", "Transaction Type", "Created At",
        "Review Notes", "Is Subscription?", "Is Recurring?"
    ]
    
    rows_all = []
    for tx in transactions:
        row = [
            extract_dynamodb_value(tx, 'transactionId') or '',
            extract_dynamodb_value(tx, 'transactionDate') or '',
            extract_dynamodb_value(tx, 'merchantName') or '',
            extract_dynamodb_value(tx, 'description') or '',
            format_amount(extract_dynamodb_value(tx, 'amount')),
            extract_dynamodb_value(tx, 'categoryPrimary') or '',
            extract_dynamodb_value(tx, 'categoryDetailed') or '',
            extract_dynamodb_value(tx, 'accountId') or '',
            extract_dynamodb_value(tx, 'transactionType') or '',
            extract_dynamodb_value(tx, 'createdAt') or '',
            '',  # Review Notes - for annotation
            '',  # Is Subscription? - for annotation
            ''   # Is Recurring? - for annotation
        ]
        rows_all.append(row)
    
    write_csv(f"{base_name}_AllTransactions.csv", headers_all, rows_all)
    
    # CSV 2: Grouped by Merchant
    headers_merchant = [
        "Merchant Name", "Transaction Count", "Total Amount", 
        "Average Amount", "Min Amount", "Max Amount",
        "First Date", "Last Date", "Date Range (Days)",
        "Category Primary", "Category Detailed",
        "Sample Transaction IDs", "Is Subscription?", "Is Recurring?", "Review Notes"
    ]
    
    # Group transactions by merchant
    by_merchant = defaultdict(list)
    for tx in transactions:
        merchant = extract_dynamodb_value(tx, 'merchantName') or extract_dynamodb_value(tx, 'description') or 'UNKNOWN'
        by_merchant[merchant].append(tx)
    
    rows_merchant = []
    sorted_merchants = sorted(by_merchant.items(), key=lambda x: len(x[1]), reverse=True)
    
    for merchant, txs in sorted_merchants:
        if len(txs) < 2:  # Skip single transactions for this view
            continue
        
        amounts = [abs(Decimal(extract_dynamodb_value(tx, 'amount') or '0')) for tx in txs]
        total_amount = sum(amounts)
        avg_amount = total_amount / len(amounts)
        min_amount = min(amounts)
        max_amount = max(amounts)
        
        dates = sorted([extract_dynamodb_value(tx, 'transactionDate') for tx in txs if extract_dynamodb_value(tx, 'transactionDate')])
        first_date = dates[0] if dates else ''
        last_date = dates[-1] if dates else ''
        
        # Calculate date range in days
        date_range_days = ''
        if first_date and last_date:
            try:
                date1 = datetime.strptime(first_date, '%Y-%m-%d')
                date2 = datetime.strptime(last_date, '%Y-%m-%d')
                days = (date2 - date1).days
                date_range_days = days
            except:
                pass
        
        # Get most common categories
        categories_primary = [extract_dynamodb_value(tx, 'categoryPrimary') for tx in txs if extract_dynamodb_value(tx, 'categoryPrimary')]
        categories_detailed = [extract_dynamodb_value(tx, 'categoryDetailed') for tx in txs if extract_dynamodb_value(tx, 'categoryDetailed')]
        category_primary = max(set(categories_primary), key=categories_primary.count) if categories_primary else ''
        category_detailed = max(set(categories_detailed), key=categories_detailed.count) if categories_detailed else ''
        
        # Sample transaction IDs (first 5)
        sample_ids = ', '.join([extract_dynamodb_value(tx, 'transactionId') or '' for tx in txs[:5]])
        if len(txs) > 5:
            sample_ids += f' ... ({len(txs)-5} more)'
        
        row = [
            merchant[:200],  # Truncate long merchant names
            len(txs),
            round(float(total_amount), 2),
            round(float(avg_amount), 2),
            round(float(min_amount), 2),
            round(float(max_amount), 2),
            first_date,
            last_date,
            date_range_days,
            category_primary,
            category_detailed,
            sample_ids,
            '',  # Is Subscription? - for annotation
            '',  # Is Recurring? - for annotation
            ''   # Review Notes - for annotation
        ]
        rows_merchant.append(row)
    
    write_csv(f"{base_name}_GroupedByMerchant.csv", headers_merchant, rows_merchant)
    
    # CSV 3: Single Transactions (potential one-offs to review)
    rows_single = []
    for merchant, txs in sorted_merchants:
        if len(txs) == 1:
            tx = txs[0]
            row = [
                merchant[:200],
                extract_dynamodb_value(tx, 'transactionId') or '',
                extract_dynamodb_value(tx, 'transactionDate') or '',
                format_amount(extract_dynamodb_value(tx, 'amount')),
                extract_dynamodb_value(tx, 'categoryPrimary') or '',
                extract_dynamodb_value(tx, 'categoryDetailed') or '',
                extract_dynamodb_value(tx, 'description') or '',
                '',  # Is Subscription? - for annotation
                '',  # Is Recurring? - for annotation
                ''   # Review Notes - for annotation
            ]
            rows_single.append(row)
    
    if rows_single:
        headers_single = [
            "Merchant Name", "Transaction ID", "Date", "Amount",
            "Category Primary", "Category Detailed", "Description",
            "Is Subscription?", "Is Recurring?", "Review Notes"
        ]
        write_csv(f"{base_name}_SingleTransactions.csv", headers_single, rows_single)
    
    # CSV 4: Evaluated Results (if available)
    try:
        eval_file = json_file.replace('.json', '_evaluated.json')
        with open(eval_file, 'r') as f:
            evaluated_data = json.load(f)
        
        headers_evaluated = [
            "Merchant Name", "Amount", "Frequency", "Subscription Type",
            "Subscription Category", "Transaction Count", "First Date", "Last Date",
            "Transaction IDs", "Correct Category?", "Review Notes"
        ]
        
        rows_evaluated = []
        all_evaluated = evaluated_data.get('subscriptions', []) + evaluated_data.get('recurring', [])
        
        for item in sorted(all_evaluated, key=lambda x: abs(Decimal(x.get('amount', '0'))), reverse=True):
            dates = item.get('dates', [])
            first_date = dates[0] if dates else ''
            last_date = dates[-1] if dates else ''
            
            txs = item.get('transactions', [])
            sample_ids = ', '.join([extract_dynamodb_value(tx, 'transactionId') or '' for tx in txs[:5]])
            if len(txs) > 5:
                sample_ids += f' ... ({len(txs)-5} more)'
            
            row = [
                item.get('merchant', ''),
                item.get('amount', ''),
                item.get('frequency', ''),
                item.get('subscription_type', ''),
                item.get('subscription_category', ''),
                item.get('transaction_count', 0),
                first_date,
                last_date,
                sample_ids,
                '',  # Correct Category? - for annotation
                ''   # Review Notes - for annotation
            ]
            rows_evaluated.append(row)
        
        write_csv(f"{base_name}_EvaluatedResults.csv", headers_evaluated, rows_evaluated)
    except FileNotFoundError:
        print(f"Note: Evaluated results file not found, skipping that CSV")
    
    print(f"\n✅ Conversion complete!")
    print(f"\nCSV files created in: {os.getcwd()}")
    print(f"\nYou can open these CSV files in Excel:")
    print(f"  - {base_name}_AllTransactions.csv - All transactions with annotation columns")
    print(f"  - {base_name}_GroupedByMerchant.csv - Transactions grouped by merchant (2+ transactions)")
    if rows_single:
        print(f"  - {base_name}_SingleTransactions.csv - Single transactions (potential one-offs)")
    print(f"\nAll CSV files have annotation columns where you can mark:")
    print(f"  - Is Subscription? (Yes/No)")
    print(f"  - Is Recurring? (Yes/No)")
    print(f"  - Review Notes (your annotations)")


if __name__ == '__main__':
    main()
