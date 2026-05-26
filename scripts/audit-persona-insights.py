#!/usr/bin/env python3
"""
Audit what the backend is surfacing as "insights" for each persona.

Calls the same endpoints the iOS app calls and dumps the results so we can
eyeball whether each signal is a true positive or a false positive.

Usage: python3 scripts/audit-persona-insights.py
"""

from __future__ import annotations

import base64
import hashlib
import json
import urllib.error
import urllib.request

BASE_URL = "http://localhost:8080"
PASSWORD = "TestPass123!@#"

PERSONAS = {
    "saver":      "persona-saver+1779755278@local.test",
    "debt-heavy": "persona-debt-heavy+1779755280@local.test",
    "paycheck":   "persona-paycheck+1779755283@local.test",
    "spender":    "persona-spender+1779755284@local.test",
}


def password_hash(email: str, password: str) -> str:
    salt = hashlib.sha256(email.lower().strip().encode()).digest()
    key = hashlib.pbkdf2_hmac("sha256", password.encode(), salt, 100_000, dklen=32)
    return base64.b64encode(key).decode()


def http(method: str, path: str, body=None, token: str | None = None) -> dict:
    req = urllib.request.Request(BASE_URL + path, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    data = None if body is None else json.dumps(body).encode()
    try:
        with urllib.request.urlopen(req, data=data, timeout=30) as r:
            txt = r.read().decode()
            return json.loads(txt) if txt else {}
    except urllib.error.HTTPError as e:
        body_text = e.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"{method} {path} -> HTTP {e.code}: {body_text[:200]}") from e


def envelope(resp):
    return resp["data"] if isinstance(resp, dict) and resp.get("status") == "ok" else resp


def login(email: str) -> str:
    ch = envelope(http("POST", "/api/auth/login/challenge", {"email": email}))["challenge"]
    ph = password_hash(email, PASSWORD)
    return envelope(http("POST", "/api/auth/login",
        {"email": email, "passwordHash": ph, "challenge": ch}))["accessToken"]


def audit(persona: str, email: str) -> None:
    print("=" * 70)
    print(f"PERSONA: {persona}")
    print("=" * 70)
    tok = login(email)

    # Accounts
    accounts = envelope(http("GET", "/api/accounts", token=tok))
    print(f"\nACCOUNTS ({len(accounts)}):")
    for a in accounts:
        print(f"  - {a.get('accountName'):<25} {a.get('accountType'):<12} ${a.get('balance'):>10,.2f}")

    # Transactions count
    txs_resp = envelope(http("GET", "/api/transactions?limit=500", token=tok))
    txs = txs_resp.get("transactions", txs_resp) if isinstance(txs_resp, dict) else txs_resp
    if not isinstance(txs, list):
        txs = []
    print(f"\nTRANSACTIONS: {len(txs)}")

    # Anomalies
    try:
        anomalies = envelope(http("GET", "/api/insights/anomalies", token=tok))
        if isinstance(anomalies, dict):
            anomalies = anomalies.get("anomalies", [])
        print(f"\nANOMALIES ({len(anomalies)}):")
        for a in anomalies[:30]:
            print(f"  - sev={a.get('severity'):<6} ${a.get('amount'):>9} "
                  f"{a.get('merchantName') or a.get('description'):<30} :: {a.get('reason')[:60]}")
    except Exception as e:
        print(f"\nANOMALIES: ERR {e}")

    # Missed payments
    try:
        missed = envelope(http("GET", "/api/insights/missed-payments", token=tok))
        if isinstance(missed, dict):
            missed = missed.get("missedPayments", missed.get("alerts", []))
        print(f"\nMISSED PAYMENTS ({len(missed)}):")
        for p in missed[:20]:
            print(f"  - sev={p.get('severity'):<6} due={p.get('dueDate')} "
                  f"overdue={p.get('daysOverdue')}d :: {p.get('title')} -- {p.get('message')[:60]}")
    except Exception as e:
        print(f"\nMISSED PAYMENTS: ERR {e}")

    # High-interest
    try:
        hi = envelope(http("GET", "/api/insights/high-interest", token=tok))
        if isinstance(hi, dict):
            hi = hi.get("alerts", hi.get("accounts", []))
        print(f"\nHIGH-INTEREST ({len(hi)}):")
        for h in hi[:20]:
            print(f"  - {h.get('accountName'):<25} rate={h.get('interestRate')}% "
                  f"bal=${h.get('balance')} yr=${h.get('annualInterestCost')}")
    except Exception as e:
        print(f"\nHIGH-INTEREST: ERR {e}")

    # Saving opportunities (expense recommendations)
    try:
        sav = envelope(http("GET", "/api/insights/expense-reductions", token=tok))
        if isinstance(sav, dict):
            sav = sav.get("recommendations", [])
        print(f"\nSAVING OPPS ({len(sav)}):")
        for s in sav[:20]:
            print(f"  - save=${s.get('monthlySavings')}/mo :: {s.get('title')} -- {s.get('description')[:60]}")
    except Exception as e:
        print(f"\nSAVING OPPS: ERR {e}")

    # Goal recommendations
    try:
        gr = envelope(http("GET", "/api/insights/goal-recommendations", token=tok))
        if isinstance(gr, dict):
            gr = gr.get("recommendations", [])
        print(f"\nGOAL RECS ({len(gr)}):")
        for g in gr[:20]:
            print(f"  - gap=${g.get('gap')} :: {g.get('title')} -- {g.get('description')[:60]}")
    except Exception as e:
        print(f"\nGOAL RECS: ERR {e}")

    print()


def main() -> int:
    for persona, email in PERSONAS.items():
        try:
            audit(persona, email)
        except Exception as e:
            print(f"\n[{persona}] FATAL: {e}\n")
    return 0


if __name__ == "__main__":
    import sys
    sys.exit(main())
