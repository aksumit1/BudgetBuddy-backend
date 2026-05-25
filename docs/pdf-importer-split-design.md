# PDFImportService monolith split — design proposal

**Status:** draft, awaiting human review before execution
**Owner:** Backend
**Last updated:** 2026-05-23

## Why

`PDFImportService` is **10,201 lines, 321 methods**, with the largest method (`parsePdfInternal`) at 566 lines and 9 methods over 200 lines. Every PDF-import fix in the recent audit required reading hundreds of lines of unrelated code to find the right hook. The class is the most-touched file in the repo, and merge conflicts on it are common.

Two parser systems live inside it (legacy Pattern 1-7 regex monolith + V2 YAML cutover) and they share extraction helpers across 300+ private methods. The cost of NOT splitting it is paid on every change: ~30% of the time on each PDF fix during the recent audit was spent navigating this file.

## Proposed split

Target: 6 services, none over 2,000 lines, each with a single responsibility. Behavior **identical** — this is pure refactoring.

```
PDFImportService (orchestrator, ~800 lines)
├── PdfBytesReader            (~200 LOC) — bounded read, OCR fallback, page-cap, text-cap
├── AccountDetectionService    (already exists, no change)
├── PdfMetadataExtractor      (~1500 LOC) — IssuerProfile chain + V2 fill-missing
├── PdfTransactionExtractor    (~2500 LOC) — Pattern 1-7 legacy + V2 cutover
├── PdfTransactionEnricher    (~1500 LOC) — geo + merchant + payment-channel + tx-type + FX
└── PdfImportDiagnostics      (~600 LOC) — metrics + diagnostic + archive wiring
```

### Service contracts

**`PdfBytesReader`**
```java
interface PdfBytesReader {
    PdfDocumentContext read(InputStream in, String fileName, String password);
}
record PdfDocumentContext(byte[] bytes, PDDocument doc, String fullText, int pageCount);
```
Owns: bounded read, magic-byte validation, OCR fallback, page-count cap, text-length cap. All input-DoS defenses live here.

**`PdfMetadataExtractor`**
```java
interface PdfMetadataExtractor {
    void extract(ImportResult result, String fullText, DetectedAccount account, Integer inferredYear);
}
```
Owns: IssuerProfile chain (Wells Fargo, Chase, Citi, etc.), V2 fill-missing pass, `annualMembershipFee`-default-to-0 logic.

**`PdfTransactionExtractor`**
```java
interface PdfTransactionExtractor {
    List<ParsedTransaction> extract(ImportResult result, String fullText, byte[] pdfBytes,
                                     String fileName, Integer inferredYear, String password);
}
```
Owns: Pattern 1-7 legacy parser, V2 cutover decision, V2 shadow pass, year-rollover correction. Internally selects path based on V2_TX_PRODUCTION_ISSUERS allow-list.

**`PdfTransactionEnricher`** (partial extraction already started in `service/pdf/enrich/PdfDerivedFields.java`)
```java
interface PdfTransactionEnricher {
    void enrich(ParsedTransaction tx, EnrichmentContext ctx);
}
```
Owns: geo (city/state/country/etc.) + merchant cleanup + wallet detection + transaction-type derivation + payment-channel derivation + FX attachment.

**`PdfImportDiagnostics`**
```java
interface PdfImportDiagnostics {
    void recordParse(ImportResult result, Throwable failure, long durationNanos, byte[] pdfBytes,
                      String fullText, int pageCount, String fileName);
}
```
Owns: PdfImportMetrics calls, PdfImportDiagnosticStore.store, PdfRawArchive.archive, PdfDiagnosticCorrelation path scheme. Single seam for observability so PDFImportService.parsePdfInternal doesn't carry 4 separate try/catch blocks.

**`PDFImportService` (after)**
```java
public ImportResult parsePDF(InputStream in, String fileName, String userId, String password) {
    PdfDocumentContext ctx = bytesReader.read(in, fileName, password);
    Integer inferredYear = inferYear(fileName, ctx);
    DetectedAccount account = accountDetectionService.detectAccount(ctx.fullText());
    ImportResult result = new ImportResult();
    result.setDetectedAccount(account);
    metadataExtractor.extract(result, ctx.fullText(), account, inferredYear);
    List<ParsedTransaction> txs = transactionExtractor.extract(
            result, ctx.fullText(), ctx.bytes(), fileName, inferredYear, password);
    for (ParsedTransaction tx : txs) {
        transactionEnricher.enrich(tx, enrichmentContext(account));
    }
    result.getTransactions().addAll(txs);
    reconcileTransactionSums(result);
    diagnostics.recordParse(result, null, durationNanos, ctx.bytes(), ctx.fullText(),
            ctx.pageCount(), fileName);
    return result;
}
```

~80 lines instead of 566.

## Migration strategy

1. **Phase 1 — interfaces + facades (no behavior change)**
   - Define the 5 interfaces above
   - Create thin facade classes that delegate back to `PDFImportService` private methods (package-private access)
   - PDFImportService.parsePdfInternal calls the facades instead of its own private methods
   - All 282 existing tests pass with zero changes

2. **Phase 2 — move code, keep tests**
   - Move each interface's implementation into its own file
   - PDFImportService imports the new beans instead of carrying the code
   - Run full corpus regression after each move (1 service per PR)

3. **Phase 3 — drop the orchestrator's leftovers**
   - Remove dead private helpers in PDFImportService
   - Target: < 1000 lines, fewer than 30 methods

## Risk

- **Bahavior changes from accidental field-access changes** when moving private statics to package-private. Mitigation: run V2PerProductInvariantsTest + corpus floors after every move.
- **Spring-bean ordering issues** if the new beans introduce circular deps. Mitigation: enforce a one-direction dependency graph (orchestrator → all, no laterals).
- **Two systems coexisting** — refactoring this monolith while the legacy/V2 split is still in flight risks compounding complexity. Recommendation: complete V2 cutover for all issuers FIRST (kill legacy Pattern 1-7), then split.

## Timeline estimate

- Phase 1 (facades): **2 days**
- Phase 2 (move): **5 days** (1 per service + 1 buffer)
- Phase 3 (cleanup): **2 days**

Total: ~2 calendar weeks of focused work + corpus-regression validation between phases.

## Test net protecting the refactor

Already in place:
- `V2PerProductInvariantsTest` — 17 per-card invariants
- `V2GeoAuditProbe` — 4 corpus floor tests (city ≥58%, state ≥63%, country ≥63%, merchant-tx ≥83%)
- `V2CutoverPerIssuerSmokeTest` — 7 dynamic per-issuer smoke tests
- `V2BugRegressionTest` — 7 specific bug-class pins
- `V2BankingScenariosTest` — 43 real-world bank-statement scenarios
- `PdfImportGeoPersistIntegrationTest` — E2E DDB round-trip
- 282 unit tests, 7 integration tests passing today

Net: if any phase breaks observable behavior, at least one of these tests fires.
