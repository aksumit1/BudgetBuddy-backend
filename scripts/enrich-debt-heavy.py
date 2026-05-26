#!/usr/bin/env python3
"""Add paymentDueDate + a recent interest-charge transaction to the
debt-heavy persona so the UpcomingBills and interest-charge detectors
have something to surface."""
import importlib.util, os
from datetime import date, timedelta

spec = importlib.util.spec_from_file_location("api", os.path.join(os.path.dirname(__file__), "audit-persona-insights.py"))
api = importlib.util.module_from_spec(spec)
spec.loader.exec_module(api)

tok = api.login("persona-debt-heavy+1779755280@local.test")
accounts = api.envelope(api.http("GET", "/api/accounts", token=tok))

cc = next((a for a in accounts if a["accountType"] == "credit"), None)
if not cc:
    print("no credit card found"); raise SystemExit(1)

# Set paymentDueDate 12 days out + a minimum payment
due = (date.today() + timedelta(days=12)).isoformat()
payload = dict(cc)
payload["paymentDueDate"] = due
payload["minimumPaymentDue"] = 222.50  # ~2.5% of balance
payload["aprPercent"] = 22.99  # keep
payload["creditLimit"] = 12000
api.http("PUT", f"/api/accounts/{cc['accountId']}", body=payload, token=tok)
print(f"+ debt-heavy CC paymentDueDate={due}, minPay=$222.50, limit=$12K")

# Add a recent interest charge transaction
yesterday = (date.today() - timedelta(days=1)).isoformat()
api.http("POST", "/api/transactions", body={
    "accountId": cc["accountId"],
    "amount": -176.45,
    "transactionDate": yesterday,
    "description": "Interest Charged on Purchases",
    "categoryPrimary": "interest_charged",
}, token=tok)
print(f"+ debt-heavy interest charge -$176.45 on {yesterday}")
