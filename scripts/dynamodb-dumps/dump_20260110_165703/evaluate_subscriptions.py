#!/usr/bin/env python3
"""
Evaluate transactions and detect subscriptions/recurring transactions
using the same logic as SubscriptionService.java
"""

import json
import sys
from collections import defaultdict
from datetime import datetime, timedelta
from decimal import Decimal
from typing import List, Dict, Optional, Tuple
import re

# Subscription categories (from SubscriptionService.java)
SUBSCRIPTION_CATEGORIES = {
    "subscriptions", "streaming", "software", "membership", "cloud_storage",
    "tech", "entertainment", "health", "insurance", "education"
}

# Known subscription merchants (from isKnownSubscriptionMerchant)
KNOWN_SUBSCRIPTION_MERCHANTS = {
    "netflix", "hulu", "huluplus", "hulu plus", "disney", "disney+", "disney plus",
    "hbo", "hbo max", "hbomax", "paramount", "paramount+", "paramount plus",
    "peacock", "spotify", "apple music", "applemusic",
    "youtube premium", "youtubepremium", "youtube tv", "youtubetv", "youtube music", "youtubemusic",
    "amazon prime", "amazonprime", "prime video", "primevideo",
    "showtime", "starz", "crunchyroll", "funimation",
    "adobe", "microsoft 365", "office 365", "dropbox", "icloud",
    "google drive", "google one", "github", "canva", "grammarly",
    "openai", "chatgpt", "openai chatgpt",
    "cursor", "cursor ai", "cursorapp",
    "anthropic", "claude", "anthropic claude",
    "meta", "meta ai", "facebook premium",
    "nordvpn", "expressvpn", "surfshark",
    "zoom", "slack", "notion", "evernote",
    "onedrive", "box", "pcloud",
    "barrons", "dj*barrons", "dj barrons", "dow jones barrons",
    "wsj", "wall street journal", "wsj.com",
    "moneycontrol", "money control",
    "marketwatch", "market watch",
    "ny times", "new york times", "nytimes", "nytimes.com",
    "financial times", "ft.com", "ft ",
    "health magazine", "health.com",
    "consumer reports", "consumerreports",
    "the economist", "economist.com",
    "forbes", "forbes.com",
    "time magazine", "time.com",
    "the atlantic", "atlantic.com",
    "costco", "costco wholesale", "costco.com",
    "sam's club", "sams club", "samsclub",
    "bjs", "bj's", "bjs wholesale", "bjs.com",
    "amazon prime", "amazon prime membership",
    "wmt plus", "walmart plus", "walmart+", "walmart plus membership",
    "target circle", "target circle membership", "target.com",
    "best buy", "bestbuy", "best buy totaltech", "best buy membership",
    "uber one", "uberone", "uber one membership",
    "lyft pink", "lyftpink", "lyft pink membership",
    "gym", "fitness", "health club", "sports club", "athletic club",
    "planet fitness", "24 hour fitness", "equinox", "lifetime fitness",
    "peloton", "classpass", "orange theory", "crossfit",
    "yoga", "pilates", "barre",
    "parking", "parking pass", "parking permit", "parking subscription",
    "spothero", "parkmobile", "parkwhiz"
}


def extract_dynamodb_value(item: Dict, key: str) -> Optional[str]:
    """Extract value from DynamoDB format"""
    if key not in item:
        return None
    value = item[key]
    if 'S' in value:
        return value['S']
    elif 'N' in value:
        return value['N']
    elif 'BOOL' in value:
        return str(value['BOOL'])
    return None


def normalize_merchant_name(name: str) -> str:
    """Normalize merchant name (simplified version)"""
    if not name:
        return ""
    # Remove special characters, normalize spaces
    normalized = re.sub(r'[^a-zA-Z0-9\s]', '', name)
    normalized = ' '.join(normalized.split())
    return normalized.upper()


def is_known_subscription_merchant(merchant_name: Optional[str], description: Optional[str]) -> bool:
    """Check if merchant is a known subscription merchant"""
    if not merchant_name and not description:
        return False
    
    combined = ((merchant_name or "") + " " + (description or "")).lower()
    
    for merchant in KNOWN_SUBSCRIPTION_MERCHANTS:
        if merchant in combined:
            # Special handling for Lyft and Uber
            if "lyft" in combined and "pink" not in combined and "subscription" not in combined and "membership" not in combined:
                return False
            if "uber" in combined and "one" not in combined and "subscription" not in combined and "membership" not in combined:
                return False
            return True
    return False


def parse_date(date_str: Optional[str]) -> Optional[datetime]:
    """Parse date string to datetime"""
    if not date_str:
        return None
    try:
        return datetime.strptime(date_str, "%Y-%m-%d")
    except:
        return None


def detect_frequency(dates: List[datetime]) -> Optional[str]:
    """Detect frequency pattern from dates"""
    if len(dates) < 2:
        return None
    
    sorted_dates = sorted(dates)
    total_days = 0
    intervals = 0
    
    for i in range(1, len(sorted_dates)):
        days = (sorted_dates[i] - sorted_dates[i-1]).days
        total_days += days
        intervals += 1
    
    if intervals == 0:
        return None
    
    average_days = total_days / intervals
    
    # Determine frequency based on average days
    if 0.5 <= average_days <= 2.5:
        return "DAILY"
    elif 6 <= average_days <= 8:
        return "WEEKLY"
    elif 13 <= average_days <= 15:
        return "BI_WEEKLY"
    elif 25 <= average_days <= 35:
        return "MONTHLY"
    elif 85 <= average_days <= 95:
        return "QUARTERLY"
    elif 175 <= average_days <= 185:
        return "SEMI_ANNUAL"
    elif 360 <= average_days <= 370:
        return "ANNUAL"
    
    # Check day-of-month pattern
    if len(dates) >= 3:
        day_groups = defaultdict(int)
        for date in sorted_dates:
            day_of_month = date.day
            if day_of_month <= 3:
                day_groups[1] += 1
            elif 14 <= day_of_month <= 16:
                day_groups[15] += 1
            elif day_of_month >= date.replace(day=1, month=date.month+1 if date.month < 12 else 1).day - 2:
                day_groups[-1] += 1
        
        total = len(sorted_dates)
        for group, count in day_groups.items():
            if count >= (total * 0.7):
                return "MONTHLY"
    
    return None


def group_by_amount(transactions: List[Dict], tolerance: float = 0.05) -> Dict[Decimal, List[Dict]]:
    """Group transactions by amount within tolerance"""
    grouped = {}
    
    for tx in transactions:
        amount_str = extract_dynamodb_value(tx, 'amount')
        if not amount_str:
            continue
        amount = Decimal(amount_str)
        
        # Find matching group
        matching_amount = None
        for existing_amount in grouped:
            diff = abs(amount - existing_amount)
            abs_existing = abs(existing_amount)
            tolerance_amount = abs_existing * Decimal(str(tolerance))
            if diff <= tolerance_amount:
                matching_amount = existing_amount
                break
        
        if matching_amount is not None:
            grouped[matching_amount].append(tx)
        else:
            grouped[amount] = [tx]
    
    return grouped


def determine_subscription_category(tx: Dict, merchant_name: str, subscription_type: Optional[str]) -> str:
    """Determine if transaction is 'subscription' or 'recurring'"""
    category_primary = extract_dynamodb_value(tx, 'categoryPrimary')
    category_detailed = extract_dynamodb_value(tx, 'categoryDetailed')
    description = extract_dynamodb_value(tx, 'description')
    amount_str = extract_dynamodb_value(tx, 'amount')
    amount = Decimal(amount_str) if amount_str else Decimal('0')
    
    combined = ((merchant_name or "") + " " + (description or "")).lower()
    
    # 1. Known subscription merchants
    if is_known_subscription_merchant(merchant_name, description):
        return "subscription"
    
    # 2. Subscription-related categories
    if category_detailed and "subscriptions" == category_detailed.lower():
        return "subscription"
    if category_primary and category_primary.lower() in SUBSCRIPTION_CATEGORIES:
        if category_primary.lower() not in ["insurance", "loans", "mortgage"]:
            return "subscription"
    
    # 3. Subscription type indicates subscription
    if subscription_type and subscription_type.lower() != "other":
        return "subscription"
    
    # 4. Large recurring payments (likely mortgage, loans)
    if abs(amount) > 500:
        if any(keyword in combined for keyword in ["mortgage", "loan", "auto finance", "car payment", "student loan", "credit card"]):
            return "recurring"
        if category_primary and category_primary.lower() in ["loans", "mortgage", "payment"]:
            return "recurring"
    
    # 5. Utilities and bills
    if category_primary and category_primary.lower() in ["utilities", "bills", "insurance"]:
        return "recurring"
    
    if any(keyword in combined for keyword in ["electric", "gas", "water", "sewer", "trash", "internet", "phone", "cable", "insurance", "premium"]):
        return "recurring"
    
    # 6. Default
    if subscription_type and subscription_type.lower() != "other":
        return "subscription"
    
    return "recurring"


def infer_subscription_type(tx: Dict) -> str:
    """Infer subscription type from transaction"""
    category_primary = extract_dynamodb_value(tx, 'categoryPrimary')
    category_detailed = extract_dynamodb_value(tx, 'categoryDetailed')
    merchant = extract_dynamodb_value(tx, 'merchantName')
    description = extract_dynamodb_value(tx, 'description')
    
    combined = ((merchant or "") + " " + (description or "")).lower()
    
    # Streaming
    if category_primary and category_primary.lower() == "entertainment":
        if any(svc in combined for svc in ["netflix", "hulu", "disney", "hbo", "paramount", "peacock", "spotify", "apple music", "youtube premium", "youtube tv", "amazon prime", "showtime", "starz"]):
            return "streaming"
    
    # Software
    if category_primary and category_primary.lower() == "tech":
        if any(svc in combined for svc in ["adobe", "microsoft 365", "office 365", "github", "canva", "grammarly", "openai", "chatgpt", "cursor", "anthropic", "meta ai", "claude"]):
            return "software"
    
    # Membership
    if any(keyword in combined for keyword in ["barrons", "wsj", "costco", "sam's club", "best buy", "gym", "fitness", "parking"]):
        return "membership"
    
    return "other"


def group_transactions_by_merchant(transactions: List[Dict]) -> Dict[str, List[Dict]]:
    """Group transactions by merchant name"""
    grouped = {}
    
    for tx in transactions:
        merchant_name = extract_dynamodb_value(tx, 'merchantName')
        description = extract_dynamodb_value(tx, 'description')
        
        # Use merchantName primarily, fall back to description
        if merchant_name and merchant_name.strip():
            group_key = normalize_merchant_name(merchant_name)
        elif description and description.strip():
            group_key = normalize_merchant_name(description)
        else:
            group_key = "unknown"
        
        if group_key not in grouped:
            grouped[group_key] = []
        grouped[group_key].append(tx)
    
    # Simple merge of similar groups (exact match only for now)
    merged = {}
    processed = set()
    
    for key, txs in grouped.items():
        if key in processed:
            continue
        
        merged[key] = txs
        processed.add(key)
    
    return merged


def pre_filter_transactions(transactions: List[Dict]) -> List[Dict]:
    """Pre-filter transactions for subscription detection"""
    candidates = []
    
    for tx in transactions:
        # Only expenses
        amount_str = extract_dynamodb_value(tx, 'amount')
        if not amount_str or Decimal(amount_str) >= 0:
            continue
        
        category_primary = extract_dynamodb_value(tx, 'categoryPrimary')
        category_detailed = extract_dynamodb_value(tx, 'categoryDetailed')
        merchant_name = extract_dynamodb_value(tx, 'merchantName')
        description = extract_dynamodb_value(tx, 'description')
        
        # Include if already categorized as subscription
        if (category_primary and "subscriptions" == category_primary.lower()) or \
           (category_detailed and "subscriptions" == category_detailed.lower()):
            candidates.append(tx)
            continue
        
        # Include if category matches subscription-related categories
        if category_primary and category_primary.lower() in SUBSCRIPTION_CATEGORIES:
            candidates.append(tx)
            continue
        
        # Include if known subscription merchant
        if is_known_subscription_merchant(merchant_name, description):
            candidates.append(tx)
            continue
        
        # Include if category detailed contains subscription keywords
        if category_detailed:
            detailed_lower = category_detailed.lower()
            if any(kw in detailed_lower for kw in ["subscription", "membership", "recurring", "streaming", "software", "cloud"]):
                candidates.append(tx)
                continue
        
        # Include other expenses (for recurring detection) but skip very large one-time payments
        amount = abs(Decimal(amount_str))
        if amount <= 1000:
            candidates.append(tx)
    
    return candidates


def main():
    if len(sys.argv) < 2:
        print("Usage: python evaluate_subscriptions.py <transactions_json_file> [user_id]")
        sys.exit(1)
    
    json_file = sys.argv[1]
    target_user_id = sys.argv[2] if len(sys.argv) > 2 else None
    
    print(f"Reading transactions from: {json_file}")
    with open(json_file, 'r') as f:
        data = json.load(f)
    
    items = data.get('Items', [])
    print(f"Total transactions in file: {len(items)}")
    
    # Convert DynamoDB format to simple dict format
    transactions = []
    for item in items:
        user_id = extract_dynamodb_value(item, 'userId')
        if target_user_id and user_id != target_user_id:
            continue
        
        tx_type = extract_dynamodb_value(item, 'transactionType')
        if tx_type != 'EXPENSE':
            continue
        
        transactions.append(item)
    
    print(f"Expense transactions for user: {len(transactions)}")
    
    # Pre-filter transactions
    candidates = pre_filter_transactions(transactions)
    print(f"Pre-filtered subscription candidates: {len(candidates)}")
    
    # Group by merchant
    by_merchant = group_transactions_by_merchant(candidates)
    print(f"Merchant groups: {len(by_merchant)}")
    
    # Detect subscriptions
    detected_subscriptions = []
    
    for merchant, merchant_txs in by_merchant.items():
        if len(merchant_txs) < 2:
            continue
        
        # Group by amount
        by_amount = group_by_amount(merchant_txs)
        
        for amount, amount_txs in by_amount.items():
            if len(amount_txs) < 2:
                continue
            
            # Sort by date
            amount_txs.sort(key=lambda tx: parse_date(extract_dynamodb_value(tx, 'transactionDate')) or datetime.min)
            
            # Extract dates
            dates = []
            for tx in amount_txs:
                date_str = extract_dynamodb_value(tx, 'transactionDate')
                date = parse_date(date_str)
                if date:
                    dates.append(date)
            
            if len(dates) < 2:
                continue
            
            # Detect frequency
            frequency = detect_frequency(dates)
            if not frequency:
                continue
            
            # Get first transaction for details
            first_tx = amount_txs[0]
            merchant_name = extract_dynamodb_value(first_tx, 'merchantName') or extract_dynamodb_value(first_tx, 'description') or merchant
            
            # Infer subscription type
            subscription_type = infer_subscription_type(first_tx)
            
            # Determine category
            subscription_category = determine_subscription_category(first_tx, merchant_name, subscription_type)
            
            detected_subscriptions.append({
                'merchant': merchant_name,
                'merchant_key': merchant,
                'amount': str(amount),
                'frequency': frequency,
                'subscription_type': subscription_type,
                'subscription_category': subscription_category,
                'transaction_count': len(amount_txs),
                'transactions': amount_txs,
                'dates': [d.strftime('%Y-%m-%d') for d in sorted(dates)]
            })
    
    # Output results
    print(f"\n{'='*80}")
    print(f"Detected {len(detected_subscriptions)} subscription/recurring transaction groups")
    print(f"{'='*80}\n")
    
    # Group by category
    subscriptions = [s for s in detected_subscriptions if s['subscription_category'] == 'subscription']
    recurring = [s for s in detected_subscriptions if s['subscription_category'] == 'recurring']
    
    print(f"SUBSCRIPTIONS ({len(subscriptions)}):")
    print(f"{'-'*80}")
    for i, sub in enumerate(sorted(subscriptions, key=lambda x: abs(Decimal(x['amount']))), 1):
        print(f"\n{i}. {sub['merchant']}")
        print(f"   Amount: ${abs(Decimal(sub['amount'])):.2f} | Frequency: {sub['frequency']} | Type: {sub['subscription_type']}")
        print(f"   Transactions: {sub['transaction_count']} | Dates: {', '.join(sub['dates'][:5])}{'...' if len(sub['dates']) > 5 else ''}")
        print(f"   Transaction IDs:")
        for tx in sub['transactions'][:3]:  # Show first 3
            tx_id = extract_dynamodb_value(tx, 'transactionId')
            date = extract_dynamodb_value(tx, 'transactionDate')
            desc = extract_dynamodb_value(tx, 'description')
            print(f"      - {tx_id} | {date} | {desc[:60] if desc else 'N/A'}")
        if len(sub['transactions']) > 3:
            print(f"      ... and {len(sub['transactions']) - 3} more")
    
    print(f"\n\nRECURRING TRANSACTIONS ({len(recurring)}):")
    print(f"{'-'*80}")
    for i, rec in enumerate(sorted(recurring, key=lambda x: abs(Decimal(x['amount']))), 1):
        print(f"\n{i}. {rec['merchant']}")
        print(f"   Amount: ${abs(Decimal(rec['amount'])):.2f} | Frequency: {rec['frequency']} | Type: {rec['subscription_type']}")
        print(f"   Transactions: {rec['transaction_count']} | Dates: {', '.join(rec['dates'][:5])}{'...' if len(rec['dates']) > 5 else ''}")
        print(f"   Transaction IDs:")
        for tx in rec['transactions'][:3]:  # Show first 3
            tx_id = extract_dynamodb_value(tx, 'transactionId')
            date = extract_dynamodb_value(tx, 'transactionDate')
            desc = extract_dynamodb_value(tx, 'description')
            print(f"      - {tx_id} | {date} | {desc[:60] if desc else 'N/A'}")
        if len(rec['transactions']) > 3:
            print(f"      ... and {len(rec['transactions']) - 3} more")
    
    # Output JSON for annotation
    output_file = json_file.replace('.json', '_evaluated.json')
    with open(output_file, 'w') as f:
        json.dump({
            'evaluation_date': datetime.now().isoformat(),
            'total_detected': len(detected_subscriptions),
            'subscriptions': subscriptions,
            'recurring': recurring
        }, f, indent=2, default=str)
    
    print(f"\n\nDetailed results saved to: {output_file}")


if __name__ == '__main__':
    main()
