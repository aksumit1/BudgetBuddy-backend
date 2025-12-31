#!/usr/bin/env python3
"""
PDF Import Test Script
Allows editing PDF text extract, running import preview and import,
then displaying full account and transaction information from backend.
"""

import requests
import json
import sys
import os
from typing import Optional, Dict, Any
from io import BytesIO
import tempfile

# Configuration
BASE_URL = os.getenv("BACKEND_URL", "http://localhost:8080")
API_BASE = f"{BASE_URL}/api"

class PDFImportTester:
    def __init__(self, auth_token: str):
        self.auth_token = auth_token
        self.headers = {
            "Authorization": f"Bearer {auth_token}",
            "Content-Type": "application/json"
        }
        self.multipart_headers = {
            "Authorization": f"Bearer {auth_token}"
        }
    
    def create_pdf_from_text(self, text: str, filename: str = "test.pdf") -> BytesIO:
        """Create a simple text file that simulates PDF content.
        
        Note: In a real scenario, you'd need to create an actual PDF file.
        For testing purposes, we'll create a text file with .pdf extension.
        The backend PDF parser should be able to extract text from it.
        """
        # Create a simple text file with PDF-like content
        # In production, use a PDF library like PyPDF2 or reportlab
        return BytesIO(text.encode('utf-8'))
    
    def preview_pdf_import(self, pdf_text: str, filename: str = "test.pdf") -> Dict[str, Any]:
        """Run PDF import preview."""
        print(f"\n{'='*60}")
        print("Running PDF Import Preview...")
        print(f"{'='*60}\n")
        
        # Create a temporary file with the PDF text
        # Note: For a real PDF, you'd need to create an actual PDF file
        # For testing, we'll create a text file that the backend can parse
        with tempfile.NamedTemporaryFile(mode='w', suffix='.txt', delete=False) as f:
            f.write(pdf_text)
            temp_filename = f.name
        
        try:
            # Read the file as binary for multipart upload
            with open(temp_filename, 'rb') as f:
                files = {
                    'file': (filename, f, 'application/pdf')
                }
                
                response = requests.post(
                    f"{API_BASE}/import-pdf/preview",
                    headers=self.multipart_headers,
                    files=files,
                    params={"filename": filename}
                )
            
            if response.status_code == 200:
                return response.json()
            else:
                print(f"❌ Preview failed: {response.status_code}")
                print(f"Response: {response.text}")
                return {"error": response.text, "status_code": response.status_code}
        finally:
            # Clean up temp file
            if os.path.exists(temp_filename):
                os.unlink(temp_filename)
    
    def import_pdf(self, pdf_text: str, account_id: Optional[str] = None, 
                   filename: str = "test.pdf", password: Optional[str] = None) -> Dict[str, Any]:
        """Run PDF import."""
        print(f"\n{'='*60}")
        print("Running PDF Import...")
        print(f"{'='*60}\n")
        
        # Create a temporary file with the PDF text
        with tempfile.NamedTemporaryFile(mode='w', suffix='.txt', delete=False) as f:
            f.write(pdf_text)
            temp_filename = f.name
        
        try:
            # Read the file as binary for multipart upload
            with open(temp_filename, 'rb') as f:
                files = {
                    'file': (filename, f, 'application/pdf')
                }
                
                params = {}
                if account_id:
                    params['accountId'] = account_id
                if password:
                    params['password'] = password
                if filename:
                    params['filename'] = filename
                
                response = requests.post(
                    f"{API_BASE}/import-pdf",
                    headers=self.multipart_headers,
                    files=files,
                    params=params
                )
            
            if response.status_code == 200:
                return response.json()
            else:
                print(f"❌ Import failed: {response.status_code}")
                print(f"Response: {response.text}")
                return {"error": response.text, "status_code": response.status_code}
        finally:
            # Clean up temp file
            if os.path.exists(temp_filename):
                os.unlink(temp_filename)
    
    def get_account(self, account_id: str) -> Dict[str, Any]:
        """Get account details."""
        response = requests.get(
            f"{API_BASE}/accounts/{account_id}",
            headers=self.headers
        )
        
        if response.status_code == 200:
            return response.json()
        else:
            print(f"❌ Failed to get account: {response.status_code}")
            print(f"Response: {response.text}")
            return {"error": response.text, "status_code": response.status_code}
    
    def get_all_accounts(self) -> list:
        """Get all accounts for the user."""
        response = requests.get(
            f"{API_BASE}/accounts",
            headers=self.headers
        )
        
        if response.status_code == 200:
            return response.json()
        else:
            print(f"❌ Failed to get accounts: {response.status_code}")
            print(f"Response: {response.text}")
            return []
    
    def get_transactions(self, account_id: Optional[str] = None, limit: int = 100) -> list:
        """Get transactions for an account or all transactions."""
        url = f"{API_BASE}/transactions"
        params = {"limit": limit}
        
        if account_id:
            params["accountId"] = account_id
        
        response = requests.get(url, headers=self.headers, params=params)
        
        if response.status_code == 200:
            return response.json()
        else:
            print(f"❌ Failed to get transactions: {response.status_code}")
            print(f"Response: {response.text}")
            return []
    
    def print_preview_results(self, preview_data: Dict[str, Any]):
        """Print preview results in a readable format."""
        print("\n" + "="*60)
        print("PREVIEW RESULTS")
        print("="*60)
        
        if "error" in preview_data:
            print(f"❌ Error: {preview_data.get('error')}")
            return
        
        # Print detected account info
        if "detectedAccount" in preview_data:
            account = preview_data["detectedAccount"]
            print("\n📋 Detected Account:")
            print(f"  Account Name: {account.get('accountName', 'N/A')}")
            print(f"  Institution: {account.get('institutionName', 'N/A')}")
            print(f"  Account Type: {account.get('accountType', 'N/A')}")
            print(f"  Account Subtype: {account.get('accountSubtype', 'N/A')}")
            print(f"  Account Number: {account.get('accountNumber', 'N/A')}")
            print(f"  Balance: {account.get('balance', 'N/A')}")
        
        # Print matched account info
        if "matchedAccountId" in preview_data:
            print(f"\n🔗 Matched Account ID: {preview_data.get('matchedAccountId', 'N/A')}")
        
        # Print credit card metadata
        if "paymentDueDate" in preview_data or "minimumPaymentDue" in preview_data or "rewardPoints" in preview_data:
            print(f"\n💳 Credit Card Metadata:")
            print(f"  Payment Due Date: {preview_data.get('paymentDueDate', 'N/A')}")
            print(f"  Minimum Payment Due: {preview_data.get('minimumPaymentDue', 'N/A')}")
            print(f"  Reward Points: {preview_data.get('rewardPoints', 'N/A')}")
        
        # Print transactions
        transactions = preview_data.get("transactions", [])
        if transactions:
            print(f"\n📊 Transactions Preview ({len(transactions)} found):")
            for i, tx in enumerate(transactions[:10], 1):  # Show first 10
                print(f"\n  Transaction {i}:")
                print(f"    Date: {tx.get('date', tx.get('transactionDate', 'N/A'))}")
                print(f"    Description: {tx.get('description', 'N/A')}")
                print(f"    Amount: {tx.get('amount', 'N/A')}")
                print(f"    Category: {tx.get('category', tx.get('categoryPrimary', 'N/A'))}")
                if tx.get('duplicates'):
                    print(f"    ⚠️  Duplicates: {len(tx.get('duplicates', []))} potential duplicate(s)")
            if len(transactions) > 10:
                print(f"\n  ... and {len(transactions) - 10} more transactions")
        
        # Print pagination info
        if "totalPages" in preview_data:
            print(f"\n📄 Pagination: Page {preview_data.get('currentPage', 0) + 1} of {preview_data.get('totalPages', 1)}")
            print(f"   Total Transactions: {preview_data.get('totalTransactions', len(transactions))}")
    
    def print_import_results(self, import_data: Dict[str, Any]):
        """Print import results in a readable format."""
        print("\n" + "="*60)
        print("IMPORT RESULTS")
        print("="*60)
        
        if "error" in import_data:
            print(f"❌ Error: {import_data.get('error')}")
            return
        
        print(f"\n✅ Import Status:")
        print(f"  Created: {import_data.get('created', 0)}")
        print(f"  Failed: {import_data.get('failed', 0)}")
        print(f"  Successful: {import_data.get('successful', 0)}")
        
        if "createdAccountId" in import_data:
            print(f"\n📋 Created/Matched Account ID: {import_data['createdAccountId']}")
        
        if "errors" in import_data and import_data["errors"]:
            print(f"\n⚠️ Errors:")
            for error in import_data["errors"]:
                print(f"  - {error}")
    
    def print_account_details(self, account: Dict[str, Any]):
        """Print account details in a readable format."""
        print("\n" + "="*60)
        print("ACCOUNT DETAILS")
        print("="*60)
        
        if "error" in account:
            print(f"❌ Error: {account.get('error')}")
            return
        
        print(f"\n📋 Account Information:")
        print(f"  Account ID: {account.get('accountId', 'N/A')}")
        print(f"  Account Name: {account.get('accountName', 'N/A')}")
        print(f"  Institution: {account.get('institutionName', 'N/A')}")
        print(f"  Account Type: {account.get('accountType', 'N/A')}")
        print(f"  Account Subtype: {account.get('accountSubtype', 'N/A')}")
        print(f"  Balance: {account.get('balance', 'N/A')}")
        print(f"  Currency: {account.get('currencyCode', 'N/A')}")
        print(f"  Account Number: {account.get('accountNumber', 'N/A')}")
        print(f"  Active: {account.get('active', 'N/A')}")
        print(f"  Last Synced: {account.get('lastSyncedAt', 'N/A')}")
        
        # Credit card metadata
        if account.get('paymentDueDate') or account.get('minimumPaymentDue') or account.get('rewardPoints'):
            print(f"\n💳 Credit Card Metadata:")
            print(f"  Payment Due Date: {account.get('paymentDueDate', 'N/A')}")
            print(f"  Minimum Payment Due: {account.get('minimumPaymentDue', 'N/A')}")
            print(f"  Reward Points: {account.get('rewardPoints', 'N/A')}")
    
    def print_transactions(self, transactions: list, limit: int = 20):
        """Print transactions in a readable format."""
        print("\n" + "="*60)
        print(f"TRANSACTIONS (showing {min(limit, len(transactions))} of {len(transactions)})")
        print("="*60)
        
        if not transactions:
            print("No transactions found.")
            return
        
        for i, tx in enumerate(transactions[:limit], 1):
            print(f"\n  Transaction {i}:")
            print(f"    Transaction ID: {tx.get('transactionId', 'N/A')}")
            print(f"    Account ID: {tx.get('accountId', 'N/A')}")
            print(f"    Date: {tx.get('transactionDate', 'N/A')}")
            print(f"    Description: {tx.get('description', 'N/A')}")
            print(f"    Amount: {tx.get('amount', 'N/A')}")
            print(f"    Category: {tx.get('categoryPrimary', 'N/A')}")
            print(f"    Merchant: {tx.get('merchantName', 'N/A')}")
            if tx.get('plaidTransactionId'):
                print(f"    Plaid ID: {tx.get('plaidTransactionId')}")
    
    def run_full_test(self, pdf_text: str, account_id: Optional[str] = None):
        """Run full test: preview, import, and fetch results."""
        # Step 1: Preview
        preview_result = self.preview_pdf_import(pdf_text)
        self.print_preview_results(preview_result)
        
        # Ask user if they want to proceed with import
        print("\n" + "="*60)
        response = input("Do you want to proceed with the import? (y/n): ").strip().lower()
        if response != 'y':
            print("Import cancelled.")
            return
        
        # Step 2: Import
        import_result = self.import_pdf(pdf_text, account_id=account_id)
        self.print_import_results(import_result)
        
        # Step 3: Get account details if account was created/matched
        created_account_id = import_result.get('createdAccountId')
        if created_account_id:
            print(f"\n{'='*60}")
            print("Fetching account details...")
            account = self.get_account(created_account_id)
            self.print_account_details(account)
            
            # Step 4: Get transactions for this account
            print(f"\n{'='*60}")
            print("Fetching transactions...")
            transactions = self.get_transactions(account_id=created_account_id, limit=50)
            self.print_transactions(transactions, limit=50)
        else:
            print("\n⚠️ No account ID returned from import. Listing all accounts...")
            accounts = self.get_all_accounts()
            if accounts:
                print(f"\nFound {len(accounts)} accounts:")
                for acc in accounts:
                    print(f"  - {acc.get('accountName')} ({acc.get('accountId')})")
            
            # Get all transactions
            print(f"\n{'='*60}")
            print("Fetching all transactions...")
            transactions = self.get_transactions(limit=50)
            self.print_transactions(transactions, limit=50)


def edit_text_interactive(default_text: str = "") -> str:
    """Allow user to edit text interactively."""
    print("\n" + "="*60)
    print("PDF TEXT EDITOR")
    print("="*60)
    print("\nEnter or paste your PDF text extract below.")
    print("Press Ctrl+D (Mac/Linux) or Ctrl+Z+Enter (Windows) when done, or type 'END' on a new line.")
    print("-"*60)
    
    if default_text:
        print(f"\nDefault text (you can edit this):\n{default_text}\n")
        use_default = input("Use default text? (y/n): ").strip().lower()
        if use_default == 'y':
            return default_text
    
    lines = []
    try:
        while True:
            line = input()
            if line.strip() == 'END':
                break
            lines.append(line)
    except EOFError:
        pass
    
    return '\n'.join(lines)


def main():
    """Main function."""
    print("="*60)
    print("PDF IMPORT TEST SCRIPT")
    print("="*60)
    
    # Get auth token
    auth_token = os.getenv("AUTH_TOKEN")
    if not auth_token:
        auth_token = input("\nEnter your authentication token: ").strip()
        if not auth_token:
            print("❌ Authentication token is required.")
            sys.exit(1)
    
    # Get backend URL
    backend_url = os.getenv("BACKEND_URL", "http://localhost:8080")
    print(f"\nUsing backend URL: {backend_url}")
    
    # Initialize tester
    tester = PDFImportTester(auth_token)
    
    # Sample PDF text (for testing)
    sample_text = """Credit Card Statement
Account Number: ****1234
Statement Period: 12/01/2024 - 12/31/2024

Payment due date: 01/15/2025
Minimum Payment Due: $25.00

Membership Rewards Points: 12,345

Date Description Amount
12/01/2024 Grocery Store $150.00
12/05/2024 Gas Station $45.00
12/10/2024 Restaurant $75.00
12/15/2024 Payment -$200.00
12/20/2024 Online Purchase $89.99
"""
    
    # Edit text
    pdf_text = edit_text_interactive(sample_text)
    
    if not pdf_text.strip():
        print("❌ No text provided.")
        sys.exit(1)
    
    # Optional: Get account ID
    account_id = input("\nEnter account ID (optional, press Enter to skip): ").strip()
    if not account_id:
        account_id = None
    
    # Run full test
    tester.run_full_test(pdf_text, account_id=account_id)
    
    print("\n" + "="*60)
    print("Test completed!")
    print("="*60)


if __name__ == "__main__":
    main()

