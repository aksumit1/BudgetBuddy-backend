package com.budgetbuddy.service.pdf.v2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.FileInputStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Probe: load one v2 template + one PDF, run evaluator, print every metadata
 * field with WHERE the value came from. Used to debug why v2 is returning the
 * wrong (or null) value for a specific statement.
 *
 * Usage:
 *   mvn test -Dtest=V2DebugProbe \
 *       -Dv2.probe.pdf=/path/to/statement.pdf \
 *       -Dv2.probe.yaml=pdf-templates-v2/american-express.yaml
 */
@EnabledIfSystemProperty(named = "v2.probe.pdf", matches = ".+")
class V2DebugProbe {

    @Test
    void probe() throws Exception {
        final String pdfPath = System.getProperty("v2.probe.pdf");
        final String yamlPath = System.getProperty("v2.probe.yaml");

        final byte[] bytes;
        try (FileInputStream in = new FileInputStream(pdfPath)) {
            bytes = in.readAllBytes();
        }
        final String rawNoStitch;
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            rawNoStitch = new PDFTextStripper().getText(doc);
        }
        // Apply same preprocessing PDFImportService does before v2 runs.
        String raw = com.budgetbuddy.service.PDFImportService
                .stripAndCaptureFxAnnotations(rawNoStitch).getCleanedText();
        raw = com.budgetbuddy.service.PDFImportService.stripAmexFxBlocks(raw);
        raw = com.budgetbuddy.service.PDFImportService.stripChaseFxFeeParentRef(raw);
        raw = com.budgetbuddy.service.PDFImportService.stitchContinuationLines(raw);
        raw = com.budgetbuddy.service.PDFImportService
                .splitTransactionFromTrailingSectionHeader(raw);
        raw = com.budgetbuddy.service.PDFImportService.splitGluedTransactions(raw);

        final ObjectMapper m = new ObjectMapper(new YAMLFactory());
        final PdfTemplateV2 tpl = m.readValue(
                getClass().getResourceAsStream("/" + yamlPath), PdfTemplateV2.class);

        System.out.println("=== Template: " + tpl.getId() + " ===");
        final PdfTemplateV2Evaluator e = new PdfTemplateV2Evaluator();

        final PdfTemplateV2Evaluator.CardDetectionResult cd =
                e.evaluateCardDetection(tpl, raw, pdfPath);
        if (cd != null) {
            System.out.println("Card detection:");
            System.out.println("  institution = " + cd.institution);
            System.out.println("  last_four   = " + cd.lastFour);
            System.out.println("  holder      = " + cd.accountHolder);
        }

        final PdfTemplateV2Evaluator.MetadataResult mr = e.evaluateMetadata(tpl, raw);
        if (mr != null) {
            System.out.println("Metadata:");
            System.out.println("  new_balance       = " + mr.newBalance);
            System.out.println("  previous_balance  = " + mr.previousBalance);
            System.out.println("  purchases_total   = " + mr.purchasesTotal);
            System.out.println("  payments_total    = " + mr.paymentsAndCreditsTotal);
            System.out.println("  fees_total        = " + mr.feesTotal);
            System.out.println("  interest_total    = " + mr.interestTotal);
            System.out.println("  statement_date    = " + mr.statementDate);
            System.out.println("  statement_start   = " + mr.statementStart);
            System.out.println("  statement_end     = " + mr.statementEnd);
        }

        System.out.println();
        System.out.println("=== Lines around 'New Balance' / 'Account Total' ===");
        final String[] allLines = raw.split("\\r?\\n");
        for (int i = 0; i < allLines.length; i++) {
            if (allLines[i].toLowerCase().contains("new balance")
                    || allLines[i].toLowerCase().contains("account total")) {
                for (int j = Math.max(0, i - 1); j < Math.min(allLines.length, i + 8); j++) {
                    System.out.printf("%4d | %s%n", j, allLines[j]);
                }
                System.out.println("---");
            }
        }

        System.out.println();
        System.out.println("=== Window around Account Total block ===");
        final String[] lines = raw.split("\\r?\\n");
        int anchor = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].toLowerCase().contains("account total")) { anchor = i; break; }
        }
        if (anchor >= 0) {
            for (int i = Math.max(0, anchor - 2); i < Math.min(lines.length, anchor + 25); i++) {
                System.out.printf("%4d | %s%n", i, lines[i]);
            }
        }
        System.out.println();
        System.out.println("=== Lines containing $ ===");
        int n = 0;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains("$") && lines[i].length() < 80) {
                System.out.printf("%4d | %s%n", i, lines[i]);
                if (++n > 30) { System.out.println("..."); break; }
            }
        }
    }
}
