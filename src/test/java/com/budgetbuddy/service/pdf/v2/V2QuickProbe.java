package com.budgetbuddy.service.pdf.v2;

import org.junit.jupiter.api.Test;

class V2QuickProbe {

    @Test
    void usbInterestExtraction() {
        final String text = String.join("\n",
                "Previous Balance + $20,574.32",
                "Payments - $20,574.32",
                "Other Credits $0.00",
                "Purchases $0.00",
                "Fees Charged $0.00",
                "Interest Charged + $79.99",
                "New Balance $79.99");

        // Build a minimal template inline
        final PdfTemplateV2 t = new PdfTemplateV2();
        final PdfTemplateV2.MetadataRules mr = new PdfTemplateV2.MetadataRules();
        final PdfTemplateV2.LabelRule ir = new PdfTemplateV2.LabelRule();
        ir.setLabel("Interest Charged");
        ir.setAdjacent("dollar");
        mr.setInterestTotal(java.util.List.of(ir));
        t.setMetadata(mr);

        final PdfTemplateV2Evaluator e = new PdfTemplateV2Evaluator();
        final PdfTemplateV2Evaluator.MetadataResult r = e.evaluateMetadata(t, text);
        System.out.println("Interest Total: " + (r == null ? "null result" : r.interestTotal));
    }
}
