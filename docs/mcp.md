# BudgetBuddy MCP Server

Lets an external AI session (Claude Desktop, Claude Code, an MCP-aware
editor, etc.) call BudgetBuddy's read + write surfaces as tools, on
behalf of the authenticated user.

## Feature flag

Off by default. Turn on per environment:

```yaml
app:
  mcp:
    enabled: true
```

When `false`, the `McpController` bean isn't registered → `POST /mcp`
returns 404, `tools/list` etc. simply don't exist on the surface. No
other behaviour is affected.

## Transport

Single HTTP endpoint — `POST /mcp` — accepting a JSON-RPC 2.0 envelope.

```
Authorization: Bearer <JWT>           // same JWT as the rest of the API
Content-Type:  application/json
Mcp-Session-Id: <uuid>                // returned by initialize
```

The first call must be `initialize` (no session id required). The
server returns the session id in both the response body's `result.sessionId`
field AND a `Mcp-Session-Id` response header. The client echoes that
id back on every subsequent call.

Sessions are in-memory, capped at 10 000 per process, and idle-evict
at 30 minutes. The client is expected to re-`initialize` on a 4xx.

## Methods supported

| Method                       | Behaviour                                            |
|------------------------------|------------------------------------------------------|
| `initialize`                 | Capability handshake; creates a session              |
| `tools/list`                 | Returns every registered tool + JSON Schema          |
| `tools/call`                 | Invokes a tool by name; returns `content` + `structuredContent` |
| `ping`                       | Health check; empty result                           |
| `notifications/initialized`  | Fire-and-forget from client; 202 Accepted, no body   |
| `notifications/cancelled`    | Same                                                 |

Anything else returns `-32601 method not found`.

## Tool catalogue

### Read-only (always available)

| Tool                          | Description                                                 |
|-------------------------------|-------------------------------------------------------------|
| `list_budgets`                | Active budgets                                              |
| `list_goals`                  | Active + completed goals                                    |
| `list_subscriptions`          | Detected subscriptions (active + inactive)                  |
| `upcoming_renewals`           | Subs renewing within N days (default 30)                    |
| `cash_flow_forecast`          | Runway days + 30/60/90 projection                           |
| `subscription_creep`          | Month-over-month portfolio delta                            |
| `budget_exhaustion`           | Budgets projected to exhaust before cycle end               |
| `list_anomalies`              | Detected transaction anomalies                              |
| `merchant_trend`              | Weekly per-merchant sparkline series                        |
| `engagement_scores`           | Per-subscription engagement tier                            |
| `search_transactions`         | Transactions in a date range, optional category/merchant    |
| `goal_projection`             | Per-goal completion forecast with p50/p90 bands             |

### Money-moving (consent-gated)

Refused until the session has called `enable_money_moving_consent`
with `confirmation=true`. The iOS app surfaces a confirmation dialog
before setting that flag.

| Tool                          | Description                                                 |
|-------------------------------|-------------------------------------------------------------|
| `enable_money_moving_consent` | Grant the session permission to call money-moving tools     |
| `create_or_update_budget`     | Create / update a budget                                    |
| `delete_budget`               | Soft-delete a budget (cascades clear linked goal pointers)  |
| `create_goal`                 | Create a new goal                                           |
| `mark_goal_complete`          | Manually mark a goal completed                              |
| `restore_goal`                | Restore a soft-deleted goal                                 |
| `delete_goal`                 | Soft-delete a goal                                          |
| `delete_subscription`         | Delete a subscription                                       |

## Error codes

Standard JSON-RPC plus two custom:

| Code      | Meaning                  |
|-----------|--------------------------|
| -32700    | Parse error              |
| -32600    | Invalid request          |
| -32601    | Method not found         |
| -32602    | Invalid params           |
| -32603    | Internal error           |
| -32000    | Tool execution failed    |
| -32001    | **Consent required** — call `enable_money_moving_consent` first |

## Safety model

- **Auth**: every request authenticates with the existing Bearer JWT.
  The MCP layer never opens a new auth surface.
- **Scope**: tools only act on the authenticated user's data — no
  cross-user access; the `UserTable` is resolved server-side per call,
  not from tool args.
- **Consent**: money-moving tools refuse with `-32001` until the
  session explicitly opts in via `enable_money_moving_consent`. The
  consent is per-session, never persisted, never inherited.
- **Audit**: every write tool routes through the existing service
  layer, which already writes audit trail rows
  (`MutationAuditInterceptor`). MCP is a second access surface — no
  new auditing semantics.

## Adding a new tool

1. Implement `McpTool` (or use the `SimpleTool` lambda wrapper in
   `mcp/tools/ReadTools.java` / `WriteTools.java`).
2. Register as a `@Bean` inside the right configuration class.
3. Spring autowires it into `McpToolRegistry` at startup.
4. Document it here.

The `BudgetCycleMathArchitectureTest`-style pinning isn't needed —
tools are pure adapters over services that already have their own
tests.
