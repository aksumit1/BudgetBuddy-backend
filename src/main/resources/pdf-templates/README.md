# PDF parse templates

Data-driven PDF statement parsers. Each `*.yaml` file in this directory
describes one financial institution's statement layouts. The registry
(`com.budgetbuddy.service.pdf.PdfTemplateRegistry`) loads every YAML at
startup and `PDFImportService` runs them alongside the legacy Java
`parsePattern1..7` methods — templates supplement, don't replace.

## Adding a new bank

1. Drop a new file named `<slug>.yaml` here, mirroring the schema below.
2. Ship it. No Java change, no deploy-triggering code change — the registry
   picks it up on next restart.

## Schema (single institution, multiple layouts)

```yaml
id: <stable-identifier>            # used in logs; e.g. "chase-v1"
institution: <human name>          # e.g. "Chase". Used for institution-keyed priority.
description: "<short prose>"
status: UNVERIFIED | VALIDATED | PRODUCTION
layouts:
  - name: <layout name>            # e.g. "checking-single-line"
    accountType: <optional>        # "checking" | "savings" | "credit" | "investment"
    lineRegex: '^\s*(?<date>...)\s+(?<description>...)\s+(?<amount>...)\s*$'
    dateFormat: "M/d/yy"           # optional; defaults to parser auto-detection
    signConvention: as-is | negate | credit-positive
    minAmount: 0.01
```

### Required named groups

Every `lineRegex` MUST capture exactly three named groups:

| Group | Contains |
|---|---|
| `date` | the transaction date token (e.g. `03/15`, `2024-03-15`) |
| `description` | merchant / memo / narration |
| `amount` | a numeric amount as it appears in the PDF |

Missing a group → registry skips the template at startup with a WARN.

### Sign conventions

- `as-is` — amount stays as parsed (already signed correctly for money-out).
- `negate` — flip the sign. Use when the PDF shows money-out as positive
  (common in international savings statements with separate Debit/Credit
  columns and no sign).
- `credit-positive` — credit card convention: purchases positive in the
  PDF, payments negative. Our storage uses the opposite, so the parser flips.

## Status lifecycle

| Status | Meaning |
|---|---|
| `UNVERIFIED` | Regex was written from general knowledge of the bank's layouts. Expected 60-80% of real rows to match. Must be re-validated against real samples before promotion. Logged at INFO when used. |
| `VALIDATED` | At least one real anonymised statement passed a snapshot test. |
| `PRODUCTION` | Multiple real samples across customers + on-call team signoff. |

At time of first fleet publication every template here is `UNVERIFIED`.
Promote by:
1. Obtaining anonymised sample statements from the institution.
2. Adding a fixture PDF + snapshot test (target directory:
   `src/test/resources/pdf-samples/<institution>/`).
3. Flipping `status: VALIDATED` when the snapshot test passes.

## Institution coverage (fleet v1)

### United States
- Chase (checking, credit card)
- Bank of America (checking, credit card)
- Wells Fargo (checking, credit card)
- Capital One (credit card)
- Discover (credit card)
- Citi (credit card)
- Apple Card (credit card, Goldman Sachs)
- U.S. Bank (checking, credit card)
- TD Bank (checking)
- PNC Bank (checking)
- Regions Bank (checking)
- Ally Bank (checking, savings, credit card)
- Chime (checking)
- SoFi (checking)

### United Kingdom
- HSBC UK (current account, credit card)
- Barclays UK (current account, credit card)

### India
- HDFC Bank (savings, credit card)
- ICICI Bank (savings, credit card)
- State Bank of India (savings)
- Axis Bank (savings)

### Investment
- Fidelity (brokerage, IRA, 401k)
- Charles Schwab (brokerage, retirement)
- Vanguard (brokerage, retirement)

## Intentionally NOT expressed as YAML

These layouts have structural complexity that loses fidelity in a single-line
regex. They live in Java under `PDFImportService` and stay there:

- **American Express multi-line (`parsePattern7`)** — one transaction spans
  3-5 physical lines with cascading description + separate amount line.
  Needs stateful line accumulation that a declarative regex can't express.
  Covered by 75 unit tests in `PDFImportServicePattern7Test`.
- **WSDOT toll statements (`parsePattern4`)** — card-number prefix + two dates
  + location regex. Narrow use case, full-Java is fine.

## Debugging

If a new import isn't producing rows:

1. Enable `logger.level.com.budgetbuddy.service.pdf=DEBUG` and watch for
   `PDF_TEMPLATE_MISS | institution="..." | pages=...`. That line fires when
   every template (legacy + registry) came up empty and the loose fallback
   kicked in.
2. If templates match but produce wrong data, add a targeted snapshot test.
