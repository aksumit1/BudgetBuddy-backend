package com.budgetbuddy.service.pdf.v2;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.budgetbuddy.service.PDFImportService;
import com.budgetbuddy.service.PDFImportService.ImportResult;
import com.budgetbuddy.service.PDFImportService.ParsedTransaction;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Per-issuer smoke test for the v2 cutover. For every entry in
 * {@code V2_TX_PRODUCTION_ISSUERS}, find a representative PDF in the local
 * corpus and assert three things:
 *
 * <ol>
 *   <li>Parse succeeds without throwing.</li>
 *   <li>At least one transaction is extracted (cutover didn't silently
 *       produce zero rows and fall back to legacy).</li>
 *   <li>Math reconciliation passes within tolerance — if the parser produces
 *       N transactions whose total doesn't match the statement-printed
 *       section sums, something regressed.</li>
 * </ol>
 *
 * <p>This protects against an issuer-specific regression hiding in a YAML
 * template change that affects only one bank. Each issuer's PDF is exercised
 * as its own {@link DynamicTest} so the surefire report names exactly which
 * one broke when a regression fires.
 */
@EnabledIfSystemProperty(named = "pdf.lbl.dir", matches = ".+")
class V2CutoverPerIssuerSmokeTest {

    private static final String CORPUS_DIR = System.getProperty(
            "pdf.lbl.dir", "/Users/garimaagarwal/Downloads/statements");

    /**
     * Per-issuer sample PDFs to exercise. Each value is a filename in the
     * corpus dir that should produce non-empty v2 extraction for the
     * institution. Keep one canonical sample per issuer — corpus-wide
     * coverage is the floor test's job.
     */
    private static final Map<String, String> ISSUER_SAMPLES = Map.of(
            "Wells Fargo",      "011826 WellsFargo.pdf",
            "Chase",            "April2026costco.pdf",          // Chase Visa Costco
            "Citibank",         "December 19.pdf",              // Citi Double Cash
            "American Express", "Apr_13_-_May_13_2026.pdf",     // Amex Platinum
            "U.S. Bank",        "2026-04-06 Statement - USB Credit Card 1739.pdf",
            "Discover",         "Discover-Statement-20251116-2364.pdf",
            "Chase Checking",   "20251204-statements-3100-.pdf"
    );

    @TestFactory
    java.util.stream.Stream<DynamicTest> perIssuerSmokeTests() {
        return ISSUER_SAMPLES.entrySet().stream().map(entry -> {
            final String issuer = entry.getKey();
            final String filename = entry.getValue();
            return DynamicTest.dynamicTest("v2 cutover smoke — " + issuer + " (" + filename + ")",
                    () -> runSmokeFor(issuer, filename));
        });
    }

    private void runSmokeFor(final String issuer, final String filename) throws Exception {
        final File pdf = new File(CORPUS_DIR, filename);
        assumeTrue(pdf.exists(), "corpus sample missing: " + filename);
        final PDFImportService svc = TestPdfImportFactory.newSvc();
        final ImportResult r;
        try (InputStream in = new FileInputStream(pdf)) {
            r = svc.parsePDF(in, pdf.getName(), "smoke-user", null);
        }
        assertNotNull(r, "[" + issuer + "] parse returned null result");
        assertNotNull(r.getTransactions(),
                "[" + issuer + "] transactions list must not be null");
        assertTrue(r.getTransactions().size() > 0,
                "[" + issuer + "] expected at least 1 transaction, got 0 for " + filename);

        // Math reconciliation when section totals are available — skip if
        // the issuer-format only carries partial summaries (some checking
        // statements don't print purchase/payment subtotals).
        assertMathReconcilesOrSkip(issuer, r);

        // Coverage sanity: at least one tx must have an amount + date.
        assertTrue(r.getTransactions().stream()
                        .anyMatch(t -> t.getAmount() != null && t.getDate() != null),
                "[" + issuer + "] every tx had null amount or date");
    }

    private void assertMathReconcilesOrSkip(
            final String issuer, final ImportResult r) {
        // Skip when no expected total is printed (Discover account-activity,
        // some checking statements). The corpus-floor and math-identity
        // tests cover deeper reconciliation.
        if (r.getPurchasesTotal() == null && r.getPaymentsAndCreditsTotal() == null) {
            return;
        }
        // Sum parsed by direction and confirm both buckets are within $1 of
        // statement-printed totals. $1 absorbs cents-rounding on issuer
        // subtotals; anything bigger indicates an extraction regression.
        final BigDecimal tolerance = new BigDecimal("1.00");
        if (r.getPurchasesTotal() != null) {
            final BigDecimal parsed = sumWhere(r.getTransactions(),
                    t -> t.getFlowDirection() == com.budgetbuddy.service.FlowDirection.DEBIT);
            // Wells Fargo includes fees in purchases bucket; expand expected
            // with non-null subtotals.
            BigDecimal expected = r.getPurchasesTotal().abs();
            if (r.getFeesChargedTotal() != null) {
                expected = expected.add(r.getFeesChargedTotal().abs());
            }
            if (r.getInterestChargedTotal() != null) {
                expected = expected.add(r.getInterestChargedTotal().abs());
            }
            assertTrue(parsed.subtract(expected).abs().compareTo(tolerance) <= 0,
                    "[" + issuer + "] debit reconciliation: parsed=" + parsed
                            + " expected=" + expected
                            + " delta=" + parsed.subtract(expected).abs());
        }
        if (r.getPaymentsAndCreditsTotal() != null) {
            final BigDecimal parsed = sumWhere(r.getTransactions(),
                    t -> t.getFlowDirection() == com.budgetbuddy.service.FlowDirection.CREDIT);
            final BigDecimal expected = r.getPaymentsAndCreditsTotal().abs();
            assertTrue(parsed.subtract(expected).abs().compareTo(tolerance) <= 0,
                    "[" + issuer + "] credit reconciliation: parsed=" + parsed
                            + " expected=" + expected
                            + " delta=" + parsed.subtract(expected).abs());
        }
    }

    private static BigDecimal sumWhere(
            final List<ParsedTransaction> txs,
            final Predicate<ParsedTransaction> filter) {
        BigDecimal sum = BigDecimal.ZERO;
        for (final ParsedTransaction t : txs) {
            if (filter.test(t)) {
                sum = sum.add(t.getAmount() == null ? BigDecimal.ZERO : t.getAmount().abs());
            }
        }
        return sum;
    }
}
