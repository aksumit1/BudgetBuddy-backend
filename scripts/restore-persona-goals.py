#!/usr/bin/env python3
"""Restore missing goals on existing personas with proper goalType."""
import importlib.util, os
from datetime import date, timedelta

spec = importlib.util.spec_from_file_location("api", os.path.join(os.path.dirname(__file__), "audit-persona-insights.py"))
api = importlib.util.module_from_spec(spec)
spec.loader.exec_module(api)

EXPECTED = {
    "saver": [
        ("Emergency Fund", 30_000, 18_500, 18, "EMERGENCY_FUND"),
        ("House down payment", 80_000, 12_400, 36, "SAVINGS"),
    ],
    "debt-heavy": [
        ("Pay off CC debt", 8_900, 1_100, 24, "DEBT_PAYOFF"),
    ],
    "paycheck": [
        ("Build $1k emergency fund", 1_000, 200, 12, "EMERGENCY_FUND"),
    ],
    "spender": [
        ("New car", 45_000, 6_200, 30, "SAVINGS"),
    ],
}

for slug, email in api.PERSONAS.items():
    try:
        tok = api.login(email)
        existing = api.envelope(api.http("GET", "/api/goals", token=tok))
        names = {g["name"] for g in existing}
        for name, target, current, months_out, gtype in EXPECTED[slug]:
            if name in names:
                continue
            target_date = date.today() + timedelta(days=30 * months_out)
            body = {
                "name": name,
                "targetAmount": target,
                "currentAmount": current,
                "targetDate": target_date.isoformat(),
                "goalType": gtype,
            }
            api.http("POST", "/api/goals", body=body, token=tok)
            print(f"[{slug}] + created {name} ({gtype})")
    except Exception as e:
        print(f"[{slug}] ERR {e}")
