#!/usr/bin/env python3
"""
Scan Subscriptions in LocalStack DynamoDB
Reviews subscription data to identify issues with active/inactive status and false positives

Usage:
    python3 scan-subscriptions-localstack.py [--endpoint-url http://localhost:4566] [--table-prefix BudgetBuddy]
"""

import boto3
import json
import sys
import argparse
from collections import defaultdict
from datetime import datetime, date
from typing import Dict, List, Any, Optional

# Colors for terminal output
class Colors:
    RED = '\033[0;31m'
    GREEN = '\033[0;32m'
    YELLOW = '\033[1;33m'
    BLUE = '\033[0;34m'
    MAGENTA = '\033[0;35m'
    CYAN = '\033[0;36m'
    NC = '\033[0m'  # No Color

def print_header(text: str):
    print(f"\n{Colors.BLUE}{'=' * 80}{Colors.NC}")
    print(f"{Colors.BLUE}{text}{Colors.NC}")
    print(f"{Colors.BLUE}{'=' * 80}{Colors.NC}\n")

def print_success(text: str):
    print(f"{Colors.GREEN}✓{Colors.NC} {text}")

def print_warning(text: str):
    print(f"{Colors.YELLOW}⚠{Colors.NC}  {text}")

def print_error(text: str):
    print(f"{Colors.RED}✗{Colors.NC} {text}")

def print_info(text: str):
    print(f"{Colors.CYAN}ℹ{Colors.NC} {text}")

def parse_dynamodb_item(item: Dict[str, Any]) -> Dict[str, Any]:
    """Convert DynamoDB item to Python dict"""
    result = {}
    for key, value in item.items():
        if 'S' in value:
            result[key] = value['S']
        elif 'N' in value:
            result[key] = value['N']
        elif 'BOOL' in value:
            result[key] = value['BOOL']
        elif 'NULL' in value:
            result[key] = None
        elif 'M' in value:
            result[key] = parse_dynamodb_item(value['M'])
        elif 'L' in value:
            result[key] = [parse_dynamodb_item(v) if 'M' in v else v for v in value['L']]
    return result

def scan_table(dynamodb_client, table_name: str) -> List[Dict[str, Any]]:
    """Scan a DynamoDB table and return all items"""
    items = []
    last_evaluated_key = None
    
    print(f"Scanning {table_name}...", end=" ", flush=True)
    
    try:
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
    except Exception as e:
        print_error(f"Failed to scan {table_name}: {e}")
        return []

def analyze_subscriptions(subscriptions: List[Dict[str, Any]]) -> Dict[str, Any]:
    """Analyze subscriptions for issues"""
    analysis = {
        'total': len(subscriptions),
        'active': 0,
        'inactive': 0,
        'missing_next_payment': 0,
        'missing_start_date': 0,
        'missing_frequency': 0,
        'overdue': [],
        'by_user': defaultdict(int),
        'by_merchant': defaultdict(int),
        'by_frequency': defaultdict(int),
        'issues': []
    }
    
    today = date.today()
    
    for sub in subscriptions:
        user_id = sub.get('userId', 'N/A')
        merchant = sub.get('merchantName', 'N/A')
        frequency = sub.get('frequency', 'N/A')
        active = sub.get('active', True)
        next_payment = sub.get('nextPaymentDate')
        start_date = sub.get('startDate')
        last_payment = sub.get('lastPaymentDate')
        amount = sub.get('amount', '0')
        subscription_id = sub.get('subscriptionId', 'N/A')
        
        analysis['by_user'][user_id] += 1
        analysis['by_merchant'][merchant] += 1
        analysis['by_frequency'][frequency] += 1
        
        if active:
            analysis['active'] += 1
        else:
            analysis['inactive'] += 1
        
        # Check for missing required fields
        if not next_payment:
            analysis['missing_next_payment'] += 1
            analysis['issues'].append({
                'type': 'missing_next_payment',
                'subscription_id': subscription_id,
                'merchant': merchant,
                'user_id': user_id
            })
        
        if not start_date:
            analysis['missing_start_date'] += 1
            analysis['issues'].append({
                'type': 'missing_start_date',
                'subscription_id': subscription_id,
                'merchant': merchant,
                'user_id': user_id
            })
        
        if not frequency or frequency == 'N/A':
            analysis['missing_frequency'] += 1
            analysis['issues'].append({
                'type': 'missing_frequency',
                'subscription_id': subscription_id,
                'merchant': merchant,
                'user_id': user_id
            })
        
        # Check if overdue
        if next_payment:
            try:
                next_payment_date = datetime.strptime(next_payment, '%Y-%m-%d').date()
                days_overdue = (today - next_payment_date).days
                if days_overdue > 0:
                    analysis['overdue'].append({
                        'subscription_id': subscription_id,
                        'merchant': merchant,
                        'user_id': user_id,
                        'next_payment_date': next_payment,
                        'days_overdue': days_overdue,
                        'amount': amount,
                        'active': active
                    })
            except (ValueError, TypeError):
                pass
    
    return analysis

def print_analysis(analysis: Dict[str, Any]):
    """Print subscription analysis"""
    print_header("Subscription Analysis")
    
    print_info(f"Total Subscriptions: {analysis['total']}")
    print_success(f"Active: {analysis['active']}")
    print_warning(f"Inactive: {analysis['inactive']}")
    
    if analysis['missing_next_payment'] > 0:
        print_error(f"Missing nextPaymentDate: {analysis['missing_next_payment']}")
    
    if analysis['missing_start_date'] > 0:
        print_error(f"Missing startDate: {analysis['missing_start_date']}")
    
    if analysis['missing_frequency'] > 0:
        print_error(f"Missing frequency: {analysis['missing_frequency']}")
    
    if analysis['overdue']:
        print_warning(f"\nOverdue Subscriptions ({len(analysis['overdue'])}):")
        for overdue in sorted(analysis['overdue'], key=lambda x: x['days_overdue'], reverse=True)[:10]:
            status = "ACTIVE" if overdue['active'] else "INACTIVE"
            print(f"  {Colors.YELLOW}⚠{Colors.NC}  {overdue['merchant']} - {overdue['days_overdue']} days overdue (Next: {overdue['next_payment_date']}, Status: {status})")
    
    print_header("Subscriptions by User")
    for user_id, count in sorted(analysis['by_user'].items(), key=lambda x: x[1], reverse=True):
        print(f"  {user_id}: {count} subscriptions")
    
    print_header("Top Merchants")
    for merchant, count in sorted(analysis['by_merchant'].items(), key=lambda x: x[1], reverse=True)[:10]:
        print(f"  {merchant}: {count} subscription(s)")
    
    print_header("By Frequency")
    for freq, count in sorted(analysis['by_frequency'].items(), key=lambda x: x[1], reverse=True):
        print(f"  {freq}: {count} subscription(s)")
    
    if analysis['issues']:
        print_header("Issues Found")
        for issue in analysis['issues'][:20]:  # Show first 20 issues
            print_warning(f"{issue['type']}: {issue['merchant']} (ID: {issue['subscription_id'][:8]}...)")

def main():
    parser = argparse.ArgumentParser(description='Scan subscriptions in LocalStack DynamoDB')
    parser.add_argument('--endpoint-url', default='http://localhost:4566',
                       help='LocalStack endpoint URL (default: http://localhost:4566)')
    parser.add_argument('--table-prefix', default='BudgetBuddy',
                       help='Table prefix (default: BudgetBuddy)')
    
    args = parser.parse_args()
    
    table_name = f"{args.table_prefix}-Subscriptions"
    
    print_header(f"Scanning Subscriptions in LocalStack")
    print_info(f"Endpoint: {args.endpoint_url}")
    print_info(f"Table: {table_name}")
    
    try:
        dynamodb = boto3.client(
            'dynamodb',
            endpoint_url=args.endpoint_url,
            region_name='us-east-1',
            aws_access_key_id='test',
            aws_secret_access_key='test'
        )
        
        # Scan subscriptions table
        items = scan_table(dynamodb, table_name)
        
        if not items:
            print_warning("No subscriptions found in database")
            return
        
        # Parse items
        subscriptions = [parse_dynamodb_item(item) for item in items]
        
        # Analyze
        analysis = analyze_subscriptions(subscriptions)
        
        # Print results
        print_analysis(analysis)
        
        # Print sample subscriptions
        print_header("Sample Subscriptions (First 5)")
        for i, sub in enumerate(subscriptions[:5], 1):
            print(f"\n{i}. Merchant: {sub.get('merchantName', 'N/A')}")
            print(f"   User: {sub.get('userId', 'N/A')[:8]}...")
            print(f"   Amount: {sub.get('amount', 'N/A')}")
            print(f"   Frequency: {sub.get('frequency', 'N/A')}")
            print(f"   Active: {sub.get('active', 'N/A')}")
            print(f"   Start Date: {sub.get('startDate', 'N/A')}")
            print(f"   Next Payment: {sub.get('nextPaymentDate', 'N/A')}")
            print(f"   Last Payment: {sub.get('lastPaymentDate', 'N/A')}")
        
        print_success("\nScan completed successfully!")
        
    except Exception as e:
        print_error(f"Error: {e}")
        import traceback
        traceback.print_exc()
        sys.exit(1)

if __name__ == '__main__':
    main()
