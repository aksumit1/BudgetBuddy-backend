#!/usr/bin/env python3
"""Add aprPercent=22.99 to existing personas' credit accounts."""

from __future__ import annotations
import sys, os
sys.path.insert(0, os.path.dirname(__file__))
import importlib.util
spec = importlib.util.spec_from_file_location("api", os.path.join(os.path.dirname(__file__), "audit-persona-insights.py"))
api = importlib.util.module_from_spec(spec)
spec.loader.exec_module(api)

for slug, email in api.PERSONAS.items():
    print(f"[{slug}] {email}")
    try:
        tok = api.login(email)
        accounts = api.envelope(api.http("GET", "/api/accounts", token=tok))
        for a in accounts:
            if a.get("accountType") == "credit" and a.get("aprPercent") in (None, 0, 0.0):
                aid = a["accountId"]
                # Send back the existing fields + new APR
                payload = dict(a)
                payload["aprPercent"] = 22.99
                api.http("PUT", f"/api/accounts/{aid}", body=payload, token=tok)
                print(f"  + patched {a['accountName']} apr=22.99%")
    except Exception as e:
        print(f"  ERR: {e}")
