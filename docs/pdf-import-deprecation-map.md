# PDF Import: Deprecation Map

Tracks every code path that becomes deletable as the YAML v2 migration completes. Each entry has:

- **What** — file / method / lines
- **Replaced by** — the v2 mechanism that supersedes it
- **Status** — `keep` (still load-bearing), `parity-pending` (v2 covers but not yet verified), `deletable` (v2 verified, safe to remove)
- **Gating test** — what proves it safe to delete

## Process

1. Write the v2 YAML for an issuer.
2. Add an integration test asserting v2-alone produces the same fields as legacy on every PDF for that issuer in `/Users/garimaagarwal/Downloads/statements`.
3. Once green, flip a feature flag from "v2 fill-missing" to "v2 first, legacy fallback".
4. Run the 42-PDF audit. If reconciliation still 42/42, the legacy code paths for that issuer are `deletable`.
5. Open a deletion PR. Update this map.

## Inventory

### Issuer profiles — full v2 takeover targets

| Path | Replaced by | Status | Gating test |
|---|---|---|---|
| `service/pdf/profile/USBankIssuerProfile.java` | `pdf-templates-v2/us-bank.yaml` + `PdfTemplateV2Evaluator` | parity-pending | `UsBankV2ParityTest` (TBD) |
| `service/pdf/profile/AmericanExpressIssuerProfile.java` | `pdf-templates-v2/amex.yaml` (TBD) | keep | `AmexV2ParityTest` (TBD) |
| `service/pdf/profile/ChaseIssuerProfile.java` | `pdf-templates-v2/chase.yaml` (TBD) | keep | `ChaseV2ParityTest` (TBD) |
| `service/pdf/profile/CitiIssuerProfile.java` | `pdf-templates-v2/citi.yaml` (TBD) | keep | `CitiV2ParityTest` (TBD) |
| `service/pdf/profile/WellsFargoIssuerProfile.java` | `pdf-templates-v2/wells-fargo.yaml` (TBD) | keep | `WellsFargoV2ParityTest` (TBD) |
| `service/pdf/profile/AppleCardIssuerProfile.java` | `pdf-templates-v2/apple-card.yaml` (TBD) | keep | `AppleCardV2ParityTest` (TBD) |
| `service/pdf/profile/BankOfAmericaIssuerProfile.java` | `pdf-templates-v2/bank-of-america.yaml` (TBD) | keep | `BofaV2ParityTest` (TBD) |
| `service/pdf/profile/DiscoverIssuerProfile.java` | `pdf-templates-v2/discover.yaml` (TBD) | keep | `DiscoverV2ParityTest` (TBD) |
| `service/pdf/profile/GenericFallbackProfile.java` | Generic v2 fallback template (TBD) | keep | n/a — last-resort path |
| `service/pdf/profile/AbstractIssuerProfile.java` | Common evaluator helpers in `PdfTemplateV2Evaluator` | keep | n/a — base class |

### AccountDetectionService — partial deletion possible per-issuer

| Code | Replaced by | Status | Notes |
|---|---|---|---|
| `usbank` keyword (line 263) | `us-bank.yaml` institution_match | parity-pending | Remove only after Apple Card / Chase / etc. migrations remove the rest of `INSTITUTION_KEYWORDS` |
| `# mask handling in ACCOUNT_NUMBER_PATTERN` | `last_four` rule per-template | keep | Universal regex; still needed for issuers without v2 templates |
| `detectFromFilename` fallback | `filename_fallback: true` rule | keep | Same |
| `extractHolderName` heuristics | `account_holder` rule | parity-pending | Will go after all issuers migrated |

### EnhancedPatternMatcher — generic, keep

The Pattern 1-5 + FuzzyMatch fallback is universally useful for issuers without a v2 template. **Status: keep.**

### Pattern 7 (Amex multi-line) — keep

Genuinely procedural multi-line state machine; not a good fit for declarative YAML. The Amex YAML template can OPT IN via `preprocessing.fx_block_strip: [amex-three-line]` to control which strategies apply. **Status: keep.**

### Cardholder name detection — partial removal

| Code | Replaced by | Status |
|---|---|---|
| `looksLikeCardholderName` + `NON_NAME_TOKENS_2W` + `NON_NAME_BUSINESS_TOKENS` | v2 `account_holder` rule (per-issuer pattern) | keep until all major issuers migrated — generic denylist still serves new banks |

### Stitching / FX strip — partial removal

| Function | Replaced by | Status |
|---|---|---|
| `stripAmexFxBlocks` | `preprocessing.fx_block_strip: [amex-three-line]` reference in YAML | keep — v2 template just selects the strategy by name; strategies stay in code |
| `stripChaseFxFeeParentRef` | `preprocessing.fx_block_strip: [chase-fee-parentref]` | keep — same |
| `stitchContinuationLines` | v2 `preprocessing.stitch` rules | keep — the state machine itself stays code |

## Items SAFE to delete TODAY (post-migration cleanup)

These code paths are either dead or fully superseded by my recent work — committing the deletion now is safe.

1. **Old per-line debug `LOGGER.info` calls that produce one log line per matched row** — they spam the audit harness and have no production value. Replace with DEBUG-level logging. Locations:
   - `EnhancedPatternMatcher.java:367` (`Matched line with pattern …`)
   - `PDFImportService.java:4815` (`Applied detected username …`)

2. **Legacy 4-arg constructor of `PDFImportService`** — used by 24+ unit tests. Genuinely load-bearing, NOT deletable today.

3. **Dead test files left in `/tmp/` directories** — referenced by tests but never re-run. Cleaned up earlier this PR; nothing to do.

Concrete code-cleanup commit follows in this PR.

## Numbers

- **Today**: 22,500 LOC of parser code, 11 issuer profile classes
- **After US Bank v2 verified**: ~22,200 LOC (delete USBankIssuerProfile, ~300 LOC)
- **After all 11 issuers migrated**: ~16,000 LOC (delete all profile classes, ~6,500 LOC)
- **After full v2 takeover**: ~8,000 LOC (deletes most of AccountDetectionService specifics, big chunks of PDFImportService metadata extraction)

End state: parser pipeline is mostly orchestration + YAML evaluation. Bank-specific logic lives in YAML, not Java.
