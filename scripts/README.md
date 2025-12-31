# PDF Import Test Script

A Python script to test PDF import functionality by editing PDF text extracts, running import preview and import, then displaying full account and transaction information from the backend.

## Prerequisites

- Python 3.6+
- `requests` library: 
  ```bash
  pip install requests
  ```
- Backend server running (default: http://localhost:8080)
- Authentication token for the backend API

## Installation

```bash
# Install required Python packages
pip install requests

# Make script executable (optional)
chmod +x scripts/pdf_import_test.py
```

## Usage

### Basic Usage

```bash
# Set environment variables
export BACKEND_URL="http://localhost:8080"
export AUTH_TOKEN="your-auth-token-here"

# Run the script
python3 scripts/pdf_import_test.py
```

### Interactive Mode

The script will:
1. Prompt you for an authentication token (if not set via environment variable)
2. Allow you to edit PDF text extract interactively
3. Run a preview of the import
4. Ask if you want to proceed with the import
5. Display the full account and transaction information

### Example PDF Text Format

```
Credit Card Statement
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
```

## Features

- **Interactive Text Editor**: Edit PDF text extracts before importing
- **Preview Mode**: See what will be imported before committing
- **Full Import**: Import transactions and update account metadata
- **Account Details**: View complete account information including credit card metadata
- **Transaction List**: See all imported transactions with details

## Output

The script displays:
- Preview results (detected account, credit card metadata, transactions)
- Import results (created/failed counts, account ID)
- Full account details (including payment due date, minimum payment, reward points)
- Transaction list (date, description, amount, category, etc.)

## Notes

- The script creates a temporary text file to simulate PDF content
- For production use, you'd need to create actual PDF files using a library like PyPDF2 or reportlab
- The backend PDF parser should be able to extract text from the file
- Account metadata (payment due date, minimum payment, reward points) is automatically extracted and updated

