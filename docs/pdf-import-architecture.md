# PDF Import: Architecture Review & Roadmap

## Where we are today

22,500 lines of Java code parse a single PDF statement. The blame distribution:

| File | Lines | What it does |
|---|---|---|
| `PDFImportService.java` | 8,717 | Master parse pipeline, Pattern 1-7 matchers, FX strip, stitching, year inference, reconciliation, metadata extraction |
| `AccountDetectionService.java` | 4,621 | Institution/last-4/holder detection from filename + PDF body |
| `StatementParsingUtilities.java` | 1,663 | Shared metadata extractors used by issuer profiles |
| `EnhancedPatternMatcher.java` | 1,039 | Single-line transaction shape matchers |
| `BalanceExtractor.java` | 779 | Balance lookup helpers |
| 11 `IssuerProfile.java` classes | ~3,000 | Per-issuer metadata overrides |
| `RewardExtractor.java` | 608 | Points / cashback parsing |
| YAML templates (`pdf-templates/*.yaml`) | ~25 files, transaction shape ONLY | Declarative single-line transaction regex |

**The YAML system covers ~5% of the parse logic** — only the single-line transaction shape. Everything else (card detection, statement summary, stitching, year inference, FX handling, sign convention, cardholder name detection, denylists) lives in code. Adding a new bank usually means writing a new IssuerProfile class. Fixing a parse bug for an existing bank means editing code, adding regex tweaks, growing denylists.

## The trust-buster

Missing transactions destroy user trust faster than any other bug, because money silently disappears from their budget. The current system has zero production observability on whether parsed transactions reconcile with the statement's printed totals — failures are found by users, escalated, manually investigated, manually patched, and shipped over days. There's no self-healing.

We also have no automated way to convert a real failing PDF into a regression fixture without manual scrubbing.

## Target architecture

**YAML-first, code-fallback, self-learning.**

```
┌─────────────────────────────────────────────────────────────────┐
│                       Import request                            │
└──────────────────────────────┬──────────────────────────────────┘
                               │
                               ▼
              ┌────────────────────────────────┐
              │  PDFImportService (orchestrator)│   ← thin
              └─────────┬──────────────────────┘
                        │
       ┌────────────────┼─────────────────┬────────────────┐
       ▼                ▼                 ▼                ▼
  YAML "card"     YAML "metadata"   YAML "transaction"  Code fallback
  rules           rules             rules               (legacy)
  (Layer 1 in     (period, totals,  (line regex,
   profile yaml)  balances)         sign convention)
                                    
       └────────────────┬─────────────────┘
                        │
                        ▼
              ┌──────────────────────┐
              │  Reconciliation gate │   ← always runs (already built)
              └─────────┬────────────┘
                        │
              fail ─────┼───── pass
                        ▼
              ┌────────────────────────┐
              │  Diagnostic capture    │   ← Layer 2 (this PR)
              │  (redacted blob → S3)  │
              └─────────┬──────────────┘
                        │
                        ▼
              ┌────────────────────────┐
              │  LLM patch proposer    │   ← Layer 3
              │  (batched async job)   │
              └─────────┬──────────────┘
                        │
                        ▼
              ┌────────────────────────┐
              │  PR with YAML patch    │
              │  + regression test     │
              └────────────────────────┘
```

Each layer also feeds **Layer 4 (metrics)** so we have dashboards and alerts on parser quality per issuer.

## YAML schema v2 — what we move out of code

### Today (v1) — transaction shape only
```yaml
id: chase-v1
institution: Chase
layouts:
  - name: credit-card-two-date
    lineRegex: '^(?<date>\d{1,2}/\d{1,2})\s+...'
    signConvention: credit-positive
```

### Target (v2) — full extraction
```yaml
id: chase-v2
institution: Chase
status: production

# WHO is this statement from?
card_detection:
  institution_match:
    - any_of:
        - "(?i)chase\\.com/[a-z]+"
        - "(?i)JPMorgan Chase Bank"
  last_four:
    - patterns:
        - "(?i)account\\s+ending\\s+(?:in\\s+)?([*xX#\\s-]{0,24}\\d{3,5})"
        - "(?i)account\\s+number[\\s:]+([*xX#\\s-]{0,24}\\d{3,5})"
      filename_fallback: true
  account_holder:
    - source: header
      pattern: "^([A-Z]+\\s+[A-Z]+(?:\\s+[A-Z]+)?)\\s*$"
      lines_above_account: 1-3

# Statement period / metadata
metadata:
  statement_date:
    - pattern: "(?i)closing\\s+date\\s+(\\d{1,2}/\\d{1,2}/\\d{2,4})"
  statement_period:
    - pattern: "(?i)opening/closing\\s+date\\s+(\\d{1,2}/\\d{1,2})\\s*-\\s*(\\d{1,2}/\\d{1,2}/\\d{2,4})"
  new_balance:
    - label: "New Balance"
      adjacent: dollar
  previous_balance:
    - label: "Previous Balance"
      adjacent: dollar
  purchases_total:
    - label: "Purchases"
      bucket: debit
  payments_total:
    - label: "Payments, Credits"
      bucket: credit
  fees_total:
    - label: "Fees Charged"
      bucket: debit
  interest_total:
    - label: "Interest Charged"
      bucket: debit

# Preprocessing hints (stitch, strip)
preprocessing:
  fx_block_strip:
    - kind: chase-exchg-rate    # built-in strategy
    - kind: chase-fee-parentref # built-in strategy
  stitch:
    end_of_transaction_marker: ⧫
    section_headers:
      - "Standard Purchases"
      - "Payments, Credits and Adjustments"

# Transaction line layouts (one bank can have multiple)
layouts:
  - name: credit-card-two-date
    accountType: credit
    lineRegex: '^(?<date>\d{1,2}/\d{1,2})\s+\d{1,2}/\d{1,2}\s+(?<description>.+?)\s+\$?(?<amount>-?[\d,]+\.\d{2})\s*$'
    signConvention: credit-positive
```

This schema gives a non-programmer everything they need to add a new bank: write one YAML file, drop it in `pdf-templates/`, ship.

## What stays in code (intentionally)

Some logic is genuinely procedural and not declarable:
- Multi-line stitching state machine (`stitchContinuationLines`) — but its **rules** (what counts as a section header) move to YAML.
- Year-rollover correction — pure post-parse adjustment, no per-issuer variance.
- PDFBox extraction strategy — but try-multiple is added as a fallback when reconciliation fails.
- Reconciliation math — universal, no per-issuer config.

Everything else moves out.

## Migration order (low-risk → high-risk)

1. **Card detection rules → YAML** (low risk; only affects display).
2. **Metadata rules → YAML** (medium; affects subtotals + reconciliation).
3. **Stitch rules → YAML** (medium; risky if mis-tuned).
4. **Per-issuer profile classes → YAML + delete the .java files** (high; touches many tests).

Each migration is gated by: (a) all existing tests pass, (b) full corpus audit shows ≥ current reconciliation rate, (c) at least 1 anonymised real-PDF fixture added per issuer.

## Layer 2: diagnostic capture (this PR)

`PdfImportDiagnostic.java` is a JSON-serialisable record built when reconciliation fails. It carries:

- `pdf_text_excerpt` — first 2KB of text around each missing/extra row, PII stripped
- `declared_totals` — `purchases`, `payments`, `fees`, `interest`, `new_balance`, `prev_balance`
- `parsed_rows` — every transaction row with date/desc/amount/direction, descriptions hashed if they look like PII
- `expected_vs_parsed_deltas` — debit_delta, credit_delta, per-bucket deltas
- `detected_account` — institution, last-4 (masked), accountType (no holder name)
- `pdf_hash` — SHA-256 of the original PDF for deduplication
- `parser_version` — git commit hash so we can correlate against deploys

Stored to S3 under `s3://budgetbuddy-import-diagnostics/<issuer>/<yyyy-mm>/<pdf_hash>.json` (locally to `/tmp/pdf-diagnostics/` in dev). One blob per failure.

## Layer 3: LLM patch proposer

CLI: `mvn exec:java -Dexec.mainClass=com.budgetbuddy.tools.PatchProposer -Dexec.args="/path/to/diagnostic.json"`

Workflow:
1. Read diagnostic JSON.
2. Build a structured prompt (Anthropic API):
   - System: "You are a regex / YAML template expert for financial statements."
   - User: "Here is a diagnostic. The parser missed N transactions or over-counted by $X. Propose either: (a) a YAML template patch, (b) a regex tweak, or (c) a denylist addition. Return as structured JSON: `{strategy, patch_yaml, rationale, test_input, test_expected}`."
3. Validate the proposed patch:
   - Apply to a scratch branch
   - Run the diagnostic blob's `pdf_text_excerpt` through the patched parser
   - Verify the missing rows are now captured AND the existing 42-PDF corpus still passes
4. Auto-generate a regression test from the diagnostic + the LLM's `test_input`/`test_expected`.
5. Emit a Markdown PR description summarising the issue + the patch.

The CLI is offline by design — no live API calls in CI. It chews through a backlog of S3 diagnostics nightly.

## Layer 4: parser quality metrics

Micrometer counters emitted from `PDFImportService.parsePDF`:

```
pdf_import_total{institution, account_type, status}
pdf_import_reconciliation{institution, bucket, status}  # status: pass|fail|skipped
pdf_import_transactions{institution}                     # gauge: tx per parse
pdf_import_parse_duration_seconds{institution}           # histogram
pdf_import_failure_reasons{institution, reason}          # reason: empty_text, no_template_match, ...
```

Dashboard: per-issuer reconciliation pass rate trend. Alert when a previously-stable issuer drops below 95% — their template probably changed.

User-facing: when an import fails reconciliation, the iOS app shows a banner: "We may have missed some transactions on this statement. Expected $X in purchases, found $Y. Tap to review." The user can then either accept the partial import or flag it for review (which triggers Layer 2 capture even on the iOS side).

## What this gives us

- **A new bank**: drop a YAML file, run the audit harness, ship. No code, no rebuild.
- **A parse bug**: user clicks "flag" → S3 diagnostic → LLM proposes a YAML patch → CI auto-tests against the captured PDF + the 42-PDF corpus → PR → human reviews → merge. Hours, not weeks.
- **A silent regression**: dashboard alerts before users notice.
- **A growing corpus**: every failure becomes a fixture, locked in by a regression test.

## Risks

- LLM proposes a wrong patch that overfits the diagnostic and breaks something else → mitigated by mandatory full-corpus run before PR-open.
- Redaction misses a PII pattern → mitigated by deterministic redaction pipeline + audit log per redaction rule.
- YAML schema becomes the new tarpit → mitigated by versioning + lint tool that flags non-portable patterns.
- LLM API cost → mitigated by batching (run nightly across S3 backlog, not per-failure).
