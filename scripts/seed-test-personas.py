#!/usr/bin/env python3
"""
Seed BudgetBuddy with multiple test users, each carrying a distinct
financial portfolio. After running:

    python3 scripts/seed-test-personas.py

four test users exist on the running backend, each populated with:
- 2-3 accounts (checking, credit card, sometimes savings/investment)
- 90 days of realistic transactions (income + spending in their pattern)
- 2-4 active budgets
- 1-2 active goals
- Subscriptions seeded automatically via the subscription detector

Personas:
  saver       — healthy savings, modest spending, on-track emergency fund
  debt-heavy  — credit card near limit, missed payments, high-interest alerts
  paycheck    — paycheck-to-paycheck, near-zero savings, high subscriptions
  spender     — high income, high spending, weak savings discipline

Credentials are printed at the end so the iOS app can log in as each.

Uses ONLY the public REST API — no DB pokes, no admin routes. Same surface
the iOS app uses.
"""

from __future__ import annotations

import base64
import hashlib
import json
import random
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from datetime import date, timedelta
from typing import Any

BASE_URL = "http://localhost:8080"
PASSWORD = "TestPass123!@#"  # all seeded users share this for convenience


def password_hash(email: str, password: str) -> str:
    """Match iOS client-side PBKDF2-HMAC-SHA256 100k iters, base64 32 bytes."""
    salt = hashlib.sha256(email.lower().strip().encode("utf-8")).digest()
    key = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, 100_000, dklen=32)
    return base64.b64encode(key).decode("ascii")


def http(method: str, path: str, *, body: Any = None, token: str | None = None) -> dict:
    req = urllib.request.Request(BASE_URL + path, method=method)
    req.add_header("Content-Type", "application/json")
    req.add_header("Accept", "application/json")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    data = None if body is None else json.dumps(body).encode("utf-8")
    try:
        with urllib.request.urlopen(req, data=data, timeout=30) as resp:
            txt = resp.read().decode("utf-8")
            return json.loads(txt) if txt else {}
    except urllib.error.HTTPError as e:
        body_text = e.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"{method} {path} -> HTTP {e.code}: {body_text[:300]}") from e


def envelope_data(resp: dict) -> dict:
    """Backend wraps successful responses in {status, data, correlationId, ...}."""
    if isinstance(resp, dict) and resp.get("status") == "ok" and "data" in resp:
        return resp["data"]
    return resp


def register_or_login(email: str) -> str:
    """Return an access JWT. Tries register first, falls back to login."""
    # Register flow
    try:
        ch_resp = http("POST", "/api/auth/register/challenge", body={"email": email})
        challenge = envelope_data(ch_resp)["challenge"]
        ph = password_hash(email, PASSWORD)
        reg = http("POST", "/api/auth/register",
                   body={"email": email, "passwordHash": ph, "challenge": challenge})
        return envelope_data(reg)["accessToken"]
    except RuntimeError as e:
        # USER_ALREADY_EXISTS → login
        if "USER_ALREADY_EXISTS" not in str(e):
            raise
    # Login flow
    ch_resp = http("POST", "/api/auth/login/challenge", body={"email": email})
    challenge = envelope_data(ch_resp)["challenge"]
    ph = password_hash(email, PASSWORD)
    login = http("POST", "/api/auth/login",
                 body={"email": email, "passwordHash": ph, "challenge": challenge})
    return envelope_data(login)["accessToken"]


def create_account(token: str, name: str, type_: str, balance: float,
                   subtype: str = "", institution: str = "TestBank") -> str:
    body = {
        "accountName": name,
        "accountType": type_,
        "accountSubtype": subtype or type_,
        "balance": balance,
        "currencyCode": "USD",
        "institutionName": institution,
        "active": True,
    }
    resp = http("POST", "/api/accounts", body=body, token=token)
    return envelope_data(resp)["accountId"]


def create_transaction(token: str, account_id: str, amount: float, txn_date: date,
                       description: str, category: str, detailed: str | None = None) -> None:
    body = {
        "accountId": account_id,
        "amount": amount,
        "transactionDate": txn_date.isoformat(),
        "description": description,
        "categoryPrimary": category,
    }
    if detailed:
        body["categoryDetailed"] = detailed
    http("POST", "/api/transactions", body=body, token=token)


def create_budget(token: str, category: str, monthly_limit: float) -> None:
    body = {"category": category, "monthlyLimit": monthly_limit, "period": "monthly",
            "currencyCode": "USD"}
    http("POST", "/api/budgets", body=body, token=token)


def create_goal(token: str, name: str, target_amount: float, current_amount: float,
                target_date: date, goal_type: str = "SAVINGS") -> None:
    body = {
        "name": name,
        "targetAmount": target_amount,
        "currentAmount": current_amount,
        "targetDate": target_date.isoformat(),
        "goalType": goal_type,
    }
    http("POST", "/api/goals", body=body, token=token)


# ---- Persona definitions ----

@dataclass
class Persona:
    slug: str
    monthly_income: float
    checking_balance: float
    credit_balance: float                  # negative = debt
    savings_balance: float | None          # None = no savings account
    budgets: list[tuple[str, float]]       # (category, monthly limit)
    goals: list[tuple[str, float, float, int]]  # (name, target, current, months out)
    subscription_count: int                # how many recurring services
    monthly_spend_by_cat: dict[str, float]


PERSONAS = {
    "saver": Persona(
        slug="saver",
        monthly_income=6_500.0,
        checking_balance=4_200.0,
        credit_balance=-650.0,
        savings_balance=18_500.0,
        budgets=[("FOOD_AND_DRINK", 600), ("TRANSPORTATION", 250),
                 ("ENTERTAINMENT", 150), ("SHOPPING", 300)],
        goals=[("Emergency Fund", 30_000, 18_500, 18),
               ("House down payment", 80_000, 12_400, 36)],
        subscription_count=3,
        monthly_spend_by_cat={
            "RENT_AND_UTILITIES": 1_800, "FOOD_AND_DRINK": 480,
            "TRANSPORTATION": 220, "ENTERTAINMENT": 90,
            "SHOPPING": 250, "HEALTHCARE": 60},
    ),
    "debt-heavy": Persona(
        slug="debt-heavy",
        monthly_income=4_200.0,
        checking_balance=380.0,
        credit_balance=-8_900.0,
        savings_balance=None,
        budgets=[("FOOD_AND_DRINK", 400), ("TRANSPORTATION", 200)],
        goals=[("Pay off CC debt", 8_900, 1_100, 24)],
        subscription_count=4,
        monthly_spend_by_cat={
            "RENT_AND_UTILITIES": 1_500, "FOOD_AND_DRINK": 520,
            "TRANSPORTATION": 280, "ENTERTAINMENT": 140,
            "SHOPPING": 380, "LOAN_PAYMENTS": 250},
    ),
    "paycheck": Persona(
        slug="paycheck",
        monthly_income=3_400.0,
        checking_balance=140.0,
        credit_balance=-2_200.0,
        savings_balance=None,
        budgets=[("FOOD_AND_DRINK", 350)],
        goals=[("Build $1k emergency fund", 1_000, 200, 12)],
        subscription_count=5,
        monthly_spend_by_cat={
            "RENT_AND_UTILITIES": 1_350, "FOOD_AND_DRINK": 420,
            "TRANSPORTATION": 180, "ENTERTAINMENT": 110,
            "SHOPPING": 220},
    ),
    "spender": Persona(
        slug="spender",
        monthly_income=12_500.0,
        checking_balance=8_400.0,
        credit_balance=-3_400.0,
        savings_balance=6_200.0,
        budgets=[("FOOD_AND_DRINK", 1_500), ("ENTERTAINMENT", 600),
                 ("SHOPPING", 1_000), ("TRAVEL", 1_200)],
        goals=[("New car", 45_000, 6_200, 30)],
        subscription_count=7,
        monthly_spend_by_cat={
            "RENT_AND_UTILITIES": 3_400, "FOOD_AND_DRINK": 1_650,
            "ENTERTAINMENT": 550, "SHOPPING": 1_280, "TRAVEL": 980,
            "TRANSPORTATION": 420, "PERSONAL_CARE": 220},
    ),
}


# Known subscriptions to seed (cycle through up to subscription_count)
SUB_PALETTE = [
    ("Netflix", 15.49, "ENTERTAINMENT"),
    ("Spotify", 9.99, "ENTERTAINMENT"),
    ("Apple iCloud", 2.99, "PERSONAL_CARE"),
    ("Adobe Creative Cloud", 54.99, "GENERAL_SERVICES"),
    ("Amazon Prime", 14.99, "GENERAL_SERVICES"),
    ("HBO Max", 15.99, "ENTERTAINMENT"),
    ("YouTube Premium", 13.99, "ENTERTAINMENT"),
    ("Gym Membership", 39.00, "PERSONAL_CARE"),
]


def seed_persona(persona: Persona) -> tuple[str, str]:
    """Build the persona's portfolio. Returns (email, token)."""
    email = f"persona-{persona.slug}+{int(time.time())}@local.test"
    token = register_or_login(email)
    print(f"  ✓ registered {email}")

    # Accounts
    checking_id = create_account(token, "Primary Checking", "depository",
                                 persona.checking_balance, subtype="checking",
                                 institution="Chase")
    credit_id = create_account(token, "Credit Card", "credit",
                               persona.credit_balance, subtype="credit card",
                               institution="Capital One")
    savings_id = None
    if persona.savings_balance is not None:
        savings_id = create_account(token, "Savings", "depository",
                                    persona.savings_balance, subtype="savings",
                                    institution="Marcus")
    print(f"    + accounts (checking={checking_id[-6:]}, credit={credit_id[-6:]}"
          f"{', savings='+savings_id[-6:] if savings_id else ''})")

    today = date.today()

    # Monthly income — 1 deposit per month for 3 months
    for months_back in (0, 1, 2):
        d = today.replace(day=1) - timedelta(days=30 * months_back)
        create_transaction(token, checking_id, persona.monthly_income, d,
                           "Payroll deposit", "INCOME", "WAGES")

    # Spending — distribute monthly amounts across the 90-day window
    rng = random.Random(hash(persona.slug) & 0xffffffff)
    for cat, monthly_total in persona.monthly_spend_by_cat.items():
        for months_back in (0, 1, 2):
            # Split monthly total into ~4-6 transactions
            num_txns = rng.randint(4, 6)
            for _ in range(num_txns):
                # Random day in that month
                day_offset = rng.randint(0, 27)
                d = today - timedelta(days=30 * months_back + day_offset)
                amount = -(monthly_total / num_txns) * (0.7 + rng.random() * 0.6)
                desc = f"{cat.lower().replace('_', ' ').title()} purchase"
                create_transaction(token, credit_id if "RENT" not in cat else checking_id,
                                   round(amount, 2), d, desc, cat)

    # Subscriptions — recurring monthly, 3 months back
    for sub_name, monthly_cost, cat in SUB_PALETTE[: persona.subscription_count]:
        for months_back in range(3):
            d = today - timedelta(days=30 * months_back + 5)
            create_transaction(token, credit_id, -monthly_cost, d,
                               sub_name, cat, "SUBSCRIPTION")

    # Budgets
    for cat, limit in persona.budgets:
        create_budget(token, cat, limit)
    print(f"    + {len(persona.budgets)} budgets")

    # Goals
    for name, target, current, months_out in persona.goals:
        td = today + timedelta(days=30 * months_out)
        create_goal(token, name, target, current, td)
    print(f"    + {len(persona.goals)} goals")

    return email, token


def main() -> int:
    print("Seeding test personas against", BASE_URL)
    print()
    results: dict[str, str] = {}
    for slug, persona in PERSONAS.items():
        print(f"[{slug}]")
        email, _ = seed_persona(persona)
        results[slug] = email
        print()

    print("=" * 60)
    print("Test accounts (password for all: " + PASSWORD + ")")
    print("=" * 60)
    for slug, email in results.items():
        print(f"  {slug:12}  {email}")
    print()
    return 0


if __name__ == "__main__":
    sys.exit(main())
