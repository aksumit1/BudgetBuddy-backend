package com.budgetbuddy.service.pdf.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.budgetbuddy.service.PDFImportService;
import com.budgetbuddy.service.PDFImportService.ImportResult;
import com.budgetbuddy.service.PDFImportService.ParsedTransaction;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Per-product field-extraction invariants. For each card we care about, this
 * pins the four most important contracts so a future YAML edit or extractor
 * change can't silently regress a specific product:
 *
 * <ol>
 *   <li>The detected account-level last-4 matches the expected value.</li>
 *   <li>Every transaction carries a {@code cardLastFour} (no nulls).</li>
 *   <li>For single-card products, the per-tx last-4 matches the account's.</li>
 *   <li>For family-card products (Amex Blue Biz), per-tx last-4 includes the
 *       expected additional-user last-4 too.</li>
 * </ol>
 *
 * <p>This is the regression net that would have caught "Prime Visa stopped
 * extracting last-4" — which the prior corpus-level coverage tests didn't,
 * because the average across all products masked a single-product drop.
 *
 * <p>Tests are dynamic so the surefire report names the failing product
 * explicitly.
 */
@EnabledIfSystemProperty(named = "pdf.lbl.dir", matches = ".+")
class V2PerProductInvariantsTest {

    private static final String CORPUS_DIR = System.getProperty(
            "pdf.lbl.dir", "/Users/garimaagarwal/Downloads/statements");

    /**
     * Walk the corpus, detect every PDF's product, and group the first
     * file for each product. Then for each expected product run the
     * invariants. Avoids hard-coding filename → product mappings (which
     * the original version got wrong because filenames don't reliably
     * encode the product type).
     */
    @TestFactory
    java.util.stream.Stream<DynamicTest> perProductLastFour() throws Exception {
        final File dir = new File(CORPUS_DIR);
        if (!dir.isDirectory()) return java.util.stream.Stream.empty();
        final File[] pdfs = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".pdf"));
        if (pdfs == null || pdfs.length == 0) return java.util.stream.Stream.empty();

        final PDFImportService svc = TestPdfImportFactory.newSvc();
        final java.util.Map<String, File> firstFileByProduct = new java.util.LinkedHashMap<>();
        for (final File pdf : pdfs) {
            try (InputStream in = new FileInputStream(pdf)) {
                final ImportResult r = svc.parsePDF(in, pdf.getName(), "scan", null);
                if (r.getDetectedAccount() == null) continue;
                final String key = r.getDetectedAccount().getInstitutionName()
                        + " | " + r.getDetectedAccount().getAccountName();
                firstFileByProduct.putIfAbsent(key, pdf);
            } catch (final Exception ignored) { }
        }

        final List<ProductCase> cases = List.of(
                // Already-pinned cards (regression net from prior round).
                new ProductCase("Chase | Prime Visa", "5468"),
                new ProductCase("Citibank | Costco Anywhere Visa® Card by Citi", "5050"),
                new ProductCase("Citibank | Citi Double Cash® Card", "1618"),
                new ProductCase("Chase | Marriott Bonvoy® Premier", "0912"),
                new ProductCase("American Express | Morgan Stanley Platinum Card®", "1007"),
                new ProductCase("Wells Fargo | ACTIVE CASH VISA SIGNATURE", "6779"),
                new ProductCase("American Express | American Express Blue Business Cashº",
                        "1002", Set.of("1002", "1010")),
                new ProductCase("U.S. Bank | U.S. Bank credit card 1739", "1739"),
                new ProductCase("Discover | DISCOVER IT CARD ENDING IN 2364", "2364"),
                // Newly-pinned: the unmigrated 8 products the per-product
                // audit surfaced. Each card now gets its own invariant so a
                // future YAML edit can't silently break it.
                new ProductCase("Chase | Chase credit card 4281", "4281"),
                new ProductCase("Chase | Chase credit card 4666", "4666"),
                new ProductCase("Chase | Chase credit card 6494", "6494"),
                new ProductCase("Mastercard | Mastercard credit card 7705", "7705"),
                new ProductCase("Synchrony | Synchrony credit card 6127", "6127"),
                new ProductCase("Chase Checking | Chase Checking credit card 0359", "3100"),
                new ProductCase("Chase Checking | Chase Checking credit card 3100", "3100"),
                new ProductCase("Discover | Discover credit card 4418", "2364")
        );
        return cases.stream().map(c -> {
            final File pdf = firstFileByProduct.get(c.productKey);
            return DynamicTest.dynamicTest(
                    c.productKey + " — last-4 = " + c.expectedAcctLast4,
                    () -> runCase(c, pdf, svc));
        });
    }

    private void runCase(final ProductCase c, final File pdf, final PDFImportService svc)
            throws Exception {
        assumeTrue(pdf != null && pdf.exists(),
                "no corpus PDF found for product: " + c.productKey);
        final ImportResult r;
        try (InputStream in = new FileInputStream(pdf)) {
            r = svc.parsePDF(in, pdf.getName(), "invariant-test", null);
        }
        assertNotNull(r.getDetectedAccount(),
                "[" + c.productKey + "] account detection must produce a DetectedAccount");
        final String detectedKey = r.getDetectedAccount().getInstitutionName()
                + " | " + r.getDetectedAccount().getAccountName();
        assertEquals(c.productKey, detectedKey,
                "[" + c.productKey + "] product key mismatch (file=" + pdf.getName() + ")");
        assertEquals(c.expectedAcctLast4, r.getDetectedAccount().getAccountNumber(),
                "[" + c.productKey + "] account last-4 mismatch (file=" + pdf.getName() + ")");

        final Set<String> distinctLast4 = new HashSet<>();
        for (final ParsedTransaction t : r.getTransactions()) {
            assertNotNull(t.getCardLastFour(),
                    "[" + c.productKey + "] tx missing cardLastFour: "
                            + t.getDescription() + " date=" + t.getDate());
            distinctLast4.add(t.getCardLastFour());
        }
        if (c.expectedTxLast4Pool != null) {
            assertTrue(distinctLast4.containsAll(c.expectedTxLast4Pool),
                    "[" + c.productKey + "] family-card tx last-4 pool missing: "
                            + "expected superset of " + c.expectedTxLast4Pool
                            + " got " + distinctLast4);
        } else if (!r.getTransactions().isEmpty()) {
            assertEquals(Set.of(c.expectedAcctLast4), distinctLast4,
                    "[" + c.productKey + "] single-card per-tx last-4 must match "
                            + "account last-4. expected={" + c.expectedAcctLast4
                            + "} got=" + distinctLast4);
        }

        // Additional cross-cutting invariants — same per-product surface:
        //  1. ACH/PPD rows must NOT have a phone number extracted (this was
        //     the per-product audit bug: PPD-ID 10-digit runs masquerading
        //     as phones on Chase Checking).
        //  2. Every transaction must have a paymentChannel set (real
        //     audit found 0/N for every product before the fix).
        for (final ParsedTransaction t : r.getTransactions()) {
            final String desc = t.getDescription() == null ? "" : t.getDescription();
            final boolean isAchRow = desc.matches(
                    "(?i).*\\b(PPD\\s+ID|WEB\\s+ID|TEL\\s+ID|ACH\\s+(?:Credit|Debit|Pmt))\\b.*");
            if (isAchRow) {
                assertTrue(t.getPhoneNumber() == null,
                        "[" + c.productKey + "] ACH row must NOT have phone: '"
                                + desc + "' got phone=" + t.getPhoneNumber());
            }
            assertNotNull(t.getPaymentChannel(),
                    "[" + c.productKey + "] every tx must have a paymentChannel "
                            + "(derived from descriptor). missing on: "
                            + desc.substring(0, Math.min(60, desc.length())));
        }
    }

    private static final class ProductCase {
        final String productKey;
        final String expectedAcctLast4;
        final Set<String> expectedTxLast4Pool;

        ProductCase(final String productKey, final String expectedAcctLast4) {
            this(productKey, expectedAcctLast4, null);
        }

        ProductCase(final String productKey, final String expectedAcctLast4,
                    final Set<String> expectedTxLast4Pool) {
            this.productKey = productKey;
            this.expectedAcctLast4 = expectedAcctLast4;
            this.expectedTxLast4Pool = expectedTxLast4Pool;
        }
    }
}
