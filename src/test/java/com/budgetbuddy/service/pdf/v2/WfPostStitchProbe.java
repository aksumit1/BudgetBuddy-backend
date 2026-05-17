package com.budgetbuddy.service.pdf.v2;

import com.budgetbuddy.service.PDFImportService;
import java.io.File;
import java.io.PrintWriter;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Debug probe — extracts the post-PDFBox text from a WF corpus PDF, applies
 * PDFImportService's exact preprocessing (stitch + FX strip + glue split),
 * and dumps every transaction-shaped line to /tmp so we can author the WF
 * v2 transaction shape against the same text the legacy parser sees.
 *
 * <p>Disabled by default; enable when authoring a transaction shape.
 */
@Disabled("debug probe — enable when authoring a transaction shape")
class WfPostStitchProbe {

    @Test
    void dumpProcessedTextForDocument1() throws Exception {
        final var doc = Loader.loadPDF(new File("/Users/garimaagarwal/Downloads/statements/Document1.pdf"));
        String text = new PDFTextStripper().getText(doc);
        doc.close();
        // Reproduce PDFImportService's transaction-text preprocessing chain.
        text = PDFImportService.stitchContinuationLines(text);
        try (PrintWriter pw = new PrintWriter("/tmp/wf_doc1_processed.txt", "UTF-8")) {
            int n = 0;
            for (final String line : text.split("\\r?\\n")) {
                pw.println(line);
                if (line.contains("$") || line.matches("^\\s*\\d{1,2}/\\d{1,2}.*")) {
                    System.out.println("CANDIDATE: " + line);
                    n++;
                }
            }
            System.out.println("Total candidate lines: " + n);
            System.out.println("Full text saved to /tmp/wf_doc1_processed.txt");
        }
    }
}
