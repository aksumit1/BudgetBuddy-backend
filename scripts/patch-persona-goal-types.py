#!/usr/bin/env python3
"""Delete + recreate persona goals with proper goalType so the
recommendation service stops suggesting goals the user already has."""
import importlib.util, os, sys, re
spec = importlib.util.spec_from_file_location("api", os.path.join(os.path.dirname(__file__), "audit-persona-insights.py"))
api = importlib.util.module_from_spec(spec)
spec.loader.exec_module(api)

NAME_TO_TYPE = [
    (r"emergency.*fund|build.*1k", "EMERGENCY_FUND"),
    (r"pay.*off.*debt|cc.*debt|credit.*card.*payoff", "DEBT_PAYOFF"),
    (r"retire", "RETIREMENT"),
    (r"down.*payment|house|home", "SAVINGS"),
    (r"car|vehicle", "SAVINGS"),
    (r"wants.*budget", "WANTS_BUDGET"),
]

def infer_type(name: str) -> str:
    nl = name.lower()
    for pat, t in NAME_TO_TYPE:
        if re.search(pat, nl):
            return t
    return "SAVINGS"

for slug, email in api.PERSONAS.items():
    try:
        tok = api.login(email)
        goals = api.envelope(api.http("GET", "/api/goals", token=tok))
        for g in goals:
            current = (g.get("goalType") or "").upper()
            inferred = infer_type(g.get("name", ""))
            if current == inferred:
                continue
            gid = g.get("goalId") or g.get("id")
            api.http("DELETE", f"/api/goals/{gid}", token=tok)
            new_body = {
                "name": g["name"],
                "targetAmount": g["targetAmount"],
                "currentAmount": g["currentAmount"],
                "targetDate": g.get("targetDate"),
                "goalType": inferred,
            }
            api.http("POST", "/api/goals", body=new_body, token=tok)
            print(f"[{slug}] {g['name']:<35} {current or '(none)'} -> {inferred}")
    except Exception as e:
        print(f"[{slug}] ERR {e}")
