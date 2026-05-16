package com.budgetbuddy.service;

import java.io.FileInputStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Trace each preprocessing stage for a single PDF so we can see exactly where
 * transactions disappear (or get glued together). Enabled only via
 * {@code -Damex.diag.pdf=path}. Use {@code -Damex.diag.start=...} and
 * {@code -Damex.diag.end=...} to narrow the printed window — defaults to a
 * Chase-FX Amex-style range that's useful for the Morgan Stanley Platinum
 * 1-21002 statements.
 *
 * <p>Kept for future debugging of new PDF layouts or parser regressions. Not run
 * in CI by default. Example:
 * <pre>
 *   mvn test -Dtest=AmexUnderExtractionDiagnostic \
 *       -Damex.diag.pdf=/path/to/Statement.pdf \
 *       -Damex.diag.start="STARBUCKS" -Damex.diag.end="CHIPOTLE"
 * </pre>
 */
@EnabledIfSystemProperty(named = "amex.diag.pdf", matches = ".+")
class AmexUnderExtractionDiagnostic {

    @Test
    void trace() throws Exception {
        final String path = System.getProperty("amex.diag.pdf");
        final byte[] bytes;
        try (FileInputStream in = new FileInputStream(path)) {
            bytes = in.readAllBytes();
        }

        final String raw;
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            final PDFTextStripper s = new PDFTextStripper();
            // Match production: PDFImportService.parsePDF uses default (no setSortByPosition).
            raw = s.getText(doc);
        }

        // Replicate parsePDF preprocessing
        final PDFImportService.FxStripResult fxStrip =
                PDFImportService.stripAndCaptureFxAnnotations(raw);
        final String stage1 = fxStrip.getCleanedText();
        final String stage2 = PDFImportService.stripAmexFxBlocks(stage1);
        final String stage3 = PDFImportService.stripChaseFxFeeParentRef(stage2);
        final String stage4 = PDFImportService.stitchContinuationLines(stage3);
        final String stage5 = PDFImportService.splitTransactionFromTrailingSectionHeader(stage4);
        final String stage6 = PDFImportService.splitGluedTransactions(stage5);

        final String startNeedle =
                System.getProperty("amex.diag.start", "BCD TRAVEL");
        final String endNeedle =
                System.getProperty("amex.diag.end", "UW PAY BY PHONE");
        printWindow("RAW", raw, startNeedle, endNeedle);
        System.out.println();
        printWindow("AFTER stripAmexFxBlocks", stage2, startNeedle, endNeedle);
        System.out.println();
        printWindow("AFTER stripChaseFxFeeParentRef", stage3, startNeedle, endNeedle);
        System.out.println();
        printWindow("AFTER stitchContinuationLines", stage4, startNeedle, endNeedle);
        System.out.println();
        printWindow("AFTER splitGluedTransactions (final pre-parse)", stage6, startNeedle, endNeedle);
    }

    private static void printWindow(
            final String label, final String text, final String startNeedle, final String endNeedle) {
        System.out.println("===== " + label + " =====");
        final String[] lines = text.split("\\r?\\n", -1);
        int startIdx = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(startNeedle)) {
                startIdx = i;
                break;
            }
        }
        if (startIdx < 0) {
            System.out.println("(start needle not found)");
            return;
        }
        int endIdx = lines.length;
        for (int i = startIdx + 1; i < lines.length; i++) {
            if (lines[i].contains(endNeedle)) {
                endIdx = Math.min(i + 3, lines.length);
                break;
            }
        }
        for (int i = startIdx; i < endIdx; i++) {
            System.out.printf("%4d | %s%n", i + 1, lines[i]);
        }
    }
}
