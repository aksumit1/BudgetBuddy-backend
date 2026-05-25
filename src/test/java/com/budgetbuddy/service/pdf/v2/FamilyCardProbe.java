package com.budgetbuddy.service.pdf.v2;

import com.budgetbuddy.service.PDFImportService;
import java.io.File;
import java.io.PrintWriter;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

@Disabled("probe")
class FamilyCardProbe {
    @Test
    void dump() throws Exception {
        dumpOne("/Users/garimaagarwal/Downloads/statements/2025-07-18.pdf", "/tmp/amex_family.txt");
        dumpOne("/Users/garimaagarwal/Downloads/statements/2025-08-19.pdf", "/tmp/amex_family_aug.txt");
        dumpOne("/Users/garimaagarwal/Downloads/statements/2025-09-18.pdf", "/tmp/amex_family_sep.txt");
        // Probe Chase candidates for any family/authorized-user header patterns.
        dumpOne("/Users/garimaagarwal/Downloads/statements/20250605-statements-0359-.pdf", "/tmp/chase_0359.txt");
        dumpOne("/Users/garimaagarwal/Downloads/statements/20250605-statements-3100-.pdf", "/tmp/chase_3100.txt");
        dumpOne("/Users/garimaagarwal/Downloads/statements/20250904-statements-4281-.pdf", "/tmp/chase_4281.txt");
        dumpOne("/Users/garimaagarwal/Downloads/statements/20250812-statements-5468-.pdf", "/tmp/chase_5468.txt");
    }

    private static void dumpOne(final String in, final String out) throws Exception {
        var doc = Loader.loadPDF(new File(in));
        String text = new PDFTextStripper().getText(doc);
        doc.close();
        text = PDFImportService.stitchContinuationLines(text);
        try (var pw = new PrintWriter(out, "UTF-8")) {
            for (var line : text.split("\\r?\\n")) pw.println(line);
        }
    }
}
