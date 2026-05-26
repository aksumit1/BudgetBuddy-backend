#!/usr/bin/env python3
"""Trigger subscription detection for each persona."""
import importlib.util, os, sys
spec = importlib.util.spec_from_file_location("api", os.path.join(os.path.dirname(__file__), "audit-persona-insights.py"))
api = importlib.util.module_from_spec(spec)
spec.loader.exec_module(api)

for slug, email in api.PERSONAS.items():
    try:
        tok = api.login(email)
        try:
            resp = api.http("POST", "/api/subscriptions/detect", body={}, token=tok)
        except RuntimeError as e:
            print(f"[{slug}] detect failed: {e}")
            continue
        subs = api.envelope(api.http("GET", "/api/subscriptions", token=tok))
        print(f"[{slug}] {len(subs)} subscriptions")
        for s in subs[:5]:
            print(f"  - {s.get('merchantName')} ${s.get('amount')}/mo")
    except Exception as e:
        print(f"[{slug}] ERR {e}")
