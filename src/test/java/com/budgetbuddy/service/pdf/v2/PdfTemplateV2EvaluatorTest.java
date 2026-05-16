package com.budgetbuddy.service.pdf.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PdfTemplateV2EvaluatorTest {

    private final PdfTemplateV2Evaluator evaluator = new PdfTemplateV2Evaluator();
    private PdfTemplateV2 usBankTemplate;

    @BeforeEach
    void loadTemplate() throws Exception {
        final ObjectMapper m = new ObjectMapper(new YAMLFactory());
        try (InputStream is = getClass().getResourceAsStream("/pdf-templates-v2/us-bank.yaml")) {
            usBankTemplate = m.readValue(is, PdfTemplateV2.class);
        }
    }

    @Test
    void cardDetectionExtractsLastFourFromHashMaskedString() {
        final String text =
                "U.S. Bank Smartly Visa Signature Card\n"
                        + "Account Ending in: #### #### #### 1739\n";
        final PdfTemplateV2Evaluator.CardDetectionResult r =
                evaluator.evaluateCardDetection(usBankTemplate, text, "statement.pdf");
        assertNotNull(r);
        assertEquals("U.S. Bank", r.institution);
        assertEquals("1739", r.lastFour);
    }

    @Test
    void cardDetectionFallsBackToFilenameForLastFour() {
        // PDF body has no recoverable last-4 — the template's filename_fallback flag
        // should kick in and parse the trailing 4-digit group from the filename.
        final String text = "U.S. Bank Cardmember Service\nMinimum Payment Due $213.00";
        final PdfTemplateV2Evaluator.CardDetectionResult r =
                evaluator.evaluateCardDetection(usBankTemplate, text, "USB Credit Card 4242.pdf");
        assertNotNull(r);
        assertEquals("4242", r.lastFour);
    }

    @Test
    void cardDetectionFindsHolderAboveAccountEnding() {
        final String text =
                "U.S. Bank Smartly Visa Signature Card\n"
                        + "AGARWAL KUMAR BNK 25 USB 4\n"
                        + "Account Ending in: #### #### #### 1739\n";
        final PdfTemplateV2Evaluator.CardDetectionResult r =
                evaluator.evaluateCardDetection(usBankTemplate, text, "statement.pdf");
        assertNotNull(r);
        // The holder rule walks 1-3 lines up from "Account Ending" looking for
        // an ALL-CAPS-ish 2-5 word line.
        assertEquals("AGARWAL KUMAR BNK", r.accountHolder.split("\\s+\\d")[0].trim());
    }

    @Test
    void metadataExtractsBalancesAndPeriod() {
        final String text =
                "U.S. Bank\n"
                        + "Open Date: 04/07/2026 Closing Date: 05/06/2026\n"
                        + "New Balance $79.99\n"
                        + "Previous Balance $20,574.32\n"
                        + "Purchases $0.00\n"
                        + "Payments $20,574.32\n"
                        + "Fees Charged $0.00\n"
                        + "Interest Charged $79.99\n";
        final PdfTemplateV2Evaluator.MetadataResult r =
                evaluator.evaluateMetadata(usBankTemplate, text);
        assertNotNull(r);
        assertEquals(new BigDecimal("79.99"), r.newBalance);
        assertEquals(new BigDecimal("20574.32"), r.previousBalance);
        assertEquals(new BigDecimal("0.00"), r.purchasesTotal);
        assertEquals(new BigDecimal("20574.32"), r.paymentsAndCreditsTotal);
        assertEquals(new BigDecimal("0.00"), r.feesTotal);
        assertEquals(new BigDecimal("79.99"), r.interestTotal);
        assertEquals(LocalDate.of(2026, 4, 7), r.statementStart);
        assertEquals(LocalDate.of(2026, 5, 6), r.statementEnd);
    }

    @Test
    void stackedLabelBlockExtractsValueAtIndex() {
        // Reproduces the Amex Account Summary block (5 labels, 5 values).
        final String text = String.join("\n",
                "Account Summary",
                "Previous Balance",
                "Payments/Credits",
                "New Charges",
                "Fees",
                "Interest Charged",
                "$26,145.56",
                "-$1,424.36",
                "+$1,461.05",
                "+$0.00",
                "+$0.00");
        final java.math.BigDecimal v = PdfTemplateV2Evaluator.extractStackedDollarValue(
                text,
                "Account Summary",
                java.util.List.of(
                        "Previous Balance",
                        "Payments/Credits",
                        "New Charges",
                        "Fees",
                        "Interest Charged"),
                /* index */ 2 /* New Charges */);
        assertNotNull(v);
        assertEquals(new java.math.BigDecimal("1461.05"), v);
    }

    @Test
    void stackedLabelBlockReturnsNullWhenLabelsMissing() {
        final String text = "Account Summary\n$100.00\n$200.00\n";
        assertNull(PdfTemplateV2Evaluator.extractStackedDollarValue(
                text,
                "Account Summary",
                java.util.List.of("Previous Balance", "Payments/Credits"),
                0));
    }

    @Test
    void doesNotCrashOnNullInputs() {
        assertNull(evaluator.evaluateCardDetection(null, "x", "f.pdf"));
        assertNull(evaluator.evaluateCardDetection(usBankTemplate, null, "f.pdf"));
        assertNull(evaluator.evaluateMetadata(null, "x"));
        assertNull(evaluator.evaluateMetadata(usBankTemplate, null));
    }
}
