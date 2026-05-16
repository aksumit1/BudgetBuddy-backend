package com.budgetbuddy.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Regression: Chase prints a foreign-currency purchase as a 5-line block:
 * <pre>
 *   08/09  TRG HOLDINGS LIMITED LONDON 13.46           ← parent purchase
 *   08/10  POUND STERLING                              ← FX header (stripped)
 *          10.00 X 1.346000000 (EXCHG RATE)            ← FX detail (stripped)
 *   08/10  FOREIGN TRANSACTION FEE .40                 ← FX fee ($0.40)
 *          TRG HOLDINGS LIMITED LONDON $13.46          ← parent-ref (this fix)
 * </pre>
 * Without the parent-ref stripper, stitchContinuationLines glues the parent-ref
 * onto the fee row producing {@code 08/10 FOREIGN TRANSACTION FEE .40 TRG HOLDINGS
 * LIMITED LONDON $13.46} — and the parser picks {@code $13.46} (the parent USD
 * amount) instead of {@code .40} as the fee. The Chase 08363EC3 statement totaled
 * $13.86 in debits but the parser reported $26.92 — a $13.06 over-count.
 */
class ChaseFxFeeStrippingTest {

    @Test
    void stripsParentRefAfterForeignTransactionFee() {
        final String input = String.join("\n",
                "08/09     TRG  HOLDINGS  LIMITED LONDON 13.46",
                "08/10     FOREIGN TRANSACTION FEE .40",
                "TRG  HOLDINGS  LIMITED   LONDON $13.46",
                "TOTAL FEES FOR THIS PERIOD $0.40");
        final String stripped = PDFImportService.stripChaseFxFeeParentRef(input);
        final String[] outLines = stripped.split("\\r?\\n");
        assertTrue(
                java.util.Arrays.stream(outLines).noneMatch(
                        l -> l.contains("TRG  HOLDINGS  LIMITED   LONDON $13.46")),
                "Parent-ref line should be stripped, got:\n" + stripped);
        // The fee row itself must NOT be stripped.
        assertTrue(
                java.util.Arrays.stream(outLines).anyMatch(
                        l -> l.contains("FOREIGN TRANSACTION FEE .40")),
                "Fee row must remain, got:\n" + stripped);
    }

    @Test
    void doesNotStripUnrelatedUppercaseDollarLines() {
        // An UPPERCASE merchant + $amount line that is NOT preceded by a FOREIGN
        // TRANSACTION FEE row should be left untouched — it could be a real
        // transaction continuation on a non-Chase statement.
        final String input = String.join("\n",
                "08/09 SOME MERCHANT $50.00",
                "ANOTHER MERCHANT $25.00");
        final String stripped = PDFImportService.stripChaseFxFeeParentRef(input);
        assertEquals(input, stripped);
    }

    @Test
    void endToEndReconcilesChaseFxStatement() throws Exception {
        // Build a minimal repro of the Chase FX block. Run through the same
        // preprocessing chain parsePDF uses and verify the FOREIGN TRANSACTION
        // FEE row carries the .40 amount, NOT the parent USD amount.
        final String raw = String.join("\n",
                "08/09     TRG  HOLDINGS  LIMITED LONDON 13.46",
                "08/10    POUND STERLING           ",
                "10.00 X 1.346000000 (EXCHG RATE) ",
                "08/10     FOREIGN TRANSACTION FEE .40",
                "TRG  HOLDINGS  LIMITED   LONDON $13.46");
        String t = PDFImportService.stripAndCaptureFxAnnotations(raw).getCleanedText();
        t = PDFImportService.stripAmexFxBlocks(t);
        t = PDFImportService.stripChaseFxFeeParentRef(t);
        t = PDFImportService.stitchContinuationLines(t);
        t = PDFImportService.splitGluedTransactions(t);
        // After preprocessing the FX fee row should be on its own line, with
        // amount `.40` and NO trailing `$13.46`.
        boolean feeRowIsolated = false;
        for (final String line : t.split("\\r?\\n")) {
            if (line.contains("FOREIGN TRANSACTION FEE")) {
                feeRowIsolated = line.contains(".40") && !line.contains("$13.46");
            }
        }
        assertTrue(feeRowIsolated,
                "Fee row should carry .40 only, not $13.46 — got:\n" + t);
    }
}
