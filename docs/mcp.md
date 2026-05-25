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
| `prompts/list`               | Returns curated prompt templates                     |
| `prompts/get`                | Renders one prompt with optional arguments           |
| `resources/list`             | Returns addressable `bb://…` snapshots               |
| `resources/read`             | Reads one resource as JSON                           |
| `ping`                       | Health check; empty result                           |
| `notifications/initialized`  | Fire-and-forget from client; 202 Accepted, no body   |
| `notifications/cancelled`    | Same                                                 |

Anything else returns `-32601 method not found`.

### Streaming

Set `Accept: text/event-stream` on `POST /mcp` and the response is
emitted as a single SSE `message` event instead of a JSON body. The
session id is still returned in the `Mcp-Session-Id` header on
`initialize` so the next request can echo it. Tool calls are synchronous
under the hood — clients consume the single event and move on.

### Prompts

Curated workflows the client can fetch and render verbatim:

| Name                       | Description                                         |
|----------------------------|-----------------------------------------------------|
| `weekly_review`            | Walks through a weekly money check-in              |
| `subscription_audit`       | Audits subscriptions for duplicates / waste        |
| `goal_coaching`            | Coaches goal progress + suggests contributions     |
| `anomaly_investigation`    | Walks through recent transaction anomalies         |
| `budget_planning`          | Drafts a realistic monthly budget                  |

### Resources

Addressable read-only snapshots:

| URI                                | Description                          |
|------------------------------------|--------------------------------------|
| `bb://budgets/current`             | Active budgets                       |
| `bb://goals/active`                | Active + completed goals             |
| `bb://forecasts/summary`           | Cash-flow runway + budget exhaustion |
| `bb://insights/anomalies/recent`   | Recent transaction anomalies         |
| `bb://subscriptions/active`        | Active subscriptions                 |
| `bb://transactions/recent`         | Last 60 days of transactions         |

## Tool catalogue

### Read-only (always available)

| Tool                              | Description                                                 |
|-----------------------------------|-------------------------------------------------------------|
| `list_budgets`                    | Active budgets                                              |
| `list_goals`                      | Active + completed goals                                    |
| `list_subscriptions`              | Detected subscriptions (active + inactive)                  |
| `upcoming_renewals`               | Subs renewing within N days (default 30)                    |
| `cash_flow_forecast`              | Runway days + 30/60/90 projection                           |
| `subscription_creep`              | Month-over-month portfolio delta                            |
| `budget_exhaustion`               | Budgets projected to exhaust before cycle end               |
| `list_anomalies`                  | Detected transaction anomalies                              |
| `merchant_trend`                  | Weekly per-merchant sparkline series                        |
| `engagement_scores`               | Per-subscription engagement tier                            |
| `search_transactions`             | Transactions in a date range, optional category/merchant    |
| `goal_projection`                 | Per-goal completion forecast with p50/p90 bands             |
| `get_user_profile`                | User profile + MCP consent state                            |
| `list_accounts`                   | Linked accounts with balance + institution                  |
| `list_transactions`               | Higher-cap transactions in a date range                     |
| `missed_payments`                 | Bills that look like they should have charged but didn't    |
| `high_interest_alerts`            | High-interest credit-card / payday-loan activity            |
| `expense_recommendations`         | Ranked expense-reduction suggestions                        |
| `goal_suggestions`                | Suggested goals from spending patterns                      |
| `budget_suggestions`              | Suggested per-category budget limits                        |
| `allocation_status`               | Zero-based-budget allocation status                         |
| `cancellation_recommendations`    | Subscriptions to consider cancelling                        |
| `subscription_alternatives`       | Cheaper alternatives to current subscriptions               |
| `subscription_health`             | Per-subscription health score (0-100)                       |
| `tax_deductibility`               | Tax-deductibility tier per subscription                     |
| `financial_insights_summary`      | One-shot rollup for AI briefings                            |

### Money-moving (consent-gated)

Refused until the session has called `enable_money_moving_consent`
with `confirmation=true`. The iOS app surfaces a confirmation dialog
before setting that flag.

| Tool                          | Description                                                 |
|-------------------------------|-------------------------------------------------------------|
| `enable_money_moving_consent` | Grant the session permission to call money-moving tools     |
| `revoke_money_moving_consent` | Revoke consent (and clear the persistent flag)              |
| `create_or_update_budget`     | Create / update a budget                                    |
| `delete_budget`               | Soft-delete a budget (cascades clear linked goal pointers)  |
| `create_goal`                 | Create a new goal                                           |
| `mark_goal_complete`          | Manually mark a goal completed                              |
| `restore_goal`                | Restore a soft-deleted goal                                 |
| `delete_goal`                 | Soft-delete a goal                                          |
| `delete_subscription`         | Delete a subscription                                       |
| `create_transaction`          | Create a transaction on one of the user's accounts          |
| `delete_transaction`          | Soft-delete a transaction                                   |
| `set_anomaly_sensitivity`     | Update the user's anomaly-detector sensitivity              |

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
  session has explicit consent. By default consent is per-session
  (granted via `enable_money_moving_consent` with `confirmation=true`),
  but the user may toggle "Always allow" in iOS Settings → AI
  Integrations to persist consent on their user record; future
  sessions then start with consent already granted. Revoke at any time
  via `revoke_money_moving_consent` or the same Settings screen.
- **Session management**: the user can list active MCP sessions and
  end any one of them in iOS Settings → AI Integrations.
  `GET /mcp/sessions`, `DELETE /mcp/sessions/{id}`,
  `GET /mcp/consent`, `PUT /mcp/consent` back this surface.
  `DELETE /mcp` (header-based termination) enforces session ownership
  too — refuses to terminate another user's session even with a
  guessed id.
- **Connection tokens**: `POST /mcp/connection-token` mints a 24h JWT
  carrying `scope=mcp` for the user to paste into Claude Desktop /
  Cursor / etc. The iOS Settings screen surfaces this so users don't
  copy their long-lived session JWT into third-party config files.
- **Audit**: every `tools/call` is logged at the protocol layer with
  tool name, category, sessionId, userId, ok/fail, and elapsed ms.
  MONEY_MOVING calls log at INFO; READ calls at DEBUG. Underlying
  services still emit their own audit-trail rows.
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
