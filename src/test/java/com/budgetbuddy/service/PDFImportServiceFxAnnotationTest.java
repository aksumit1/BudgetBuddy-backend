package com.budgetbuddy.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Coverage for the Chase Marriott Bonvoy foreign-currency annotation bug.
 *
 * <p>The Chase statement layout for a single international purchase is a 3-line block
 * (date / merchant / USD amount on line 1, "MM/DD CURRENCY-NAME" on line 2, "AMOUNT X
 * RATE (EXCHG RATE)" on line 3). All fixture values below are synthetic.
 *
 * <p>Before the fix, the parser saw 5 candidate transaction rows: 3 real ones plus two
 * phantom rows whose "amount" was the foreign-currency principal — picked up because the
 * FX header "MM/DD CURRENCY-NAME" starts with a date and the rate-detail line carries a
 * comma-grouped number that satisfies the amount pattern. The fix strips both halves of
 * the FX block before stitching and preserves the FX data as an anchor for the parent
 * USD purchase so the original currency / amount / rate stay queryable downstream.
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class PDFImportServiceFxAnnotationTest {

    /**
     * Synthetic fixture that reproduces the Chase Marriott Bonvoy 3-line FX-block layout
     * (parent USD purchase / "MM/DD CURRENCY-NAME" header / "AMOUNT X RATE (EXCHG RATE)"
     * detail). Merchant names, amounts, dates, and exchange rates are fabricated for test
     * isolation — DO NOT replace with values from a real statement.
     */
    private static final String CHASE_MARRIOTT_BONVOY_FIXTURE =
            String.join(
                    "\n",
                    "ACCOUNT ACTIVITY",
                    "PAYMENTS AND OTHER CREDITS",
                    "06/22     AUTOMATIC PAYMENT - THANK YOU -2,500.00",
                    "PURCHASE",
                    "06/03     SYNTHETIC HOTEL ZURICH 211.11",
                    "06/04    SWISS FRANC             ",
                    "192.50 X 1.096493506 (EXCHG RATE) ",
                    "06/15     FAKE BISTRO MUNICH 88.50",
                    "06/16    EURO             ",
                    "78.45 X 1.127979605 (EXCHG RATE) ");

    @Test
    void stripFxAnnotations_removesBothHalvesOfTheChaseExchangeRateBlock() {
        final String stripped = PDFImportService.stripFxAnnotations(CHASE_MARRIOTT_BONVOY_FIXTURE);

        // The two FX-detail lines must be gone.
        assertFalse(
                stripped.contains("EXCHG RATE"),
                "FX detail lines must be stripped — they're not real transactions");
        // Their paired headers must be gone too (otherwise the bare "MM/DD CURRENCY"
        // line still trips TXN_START_PATTERN downstream).
        assertFalse(
                stripped.contains("SWISS FRANC"),
                "FX header lines must be stripped along with the rate detail");
        assertFalse(stripped.contains("EURO\n"), "EURO header line must be stripped");

        // And the three real transactions must still be there, unmodified.
        assertTrue(stripped.contains("AUTOMATIC PAYMENT - THANK YOU -2,500.00"));
        assertTrue(stripped.contains("SYNTHETIC HOTEL ZURICH 211.11"));
        assertTrue(stripped.contains("FAKE BISTRO MUNICH 88.50"));
    }

    @Test
    void stripFxAnnotations_preservesLinesThatLookLikeFxHeaderButAreNotFollowedByRateDetail() {
        // A bare "MM/DD ALLCAPS WORDS" line WITHOUT an EXCHG RATE follower must be
        // preserved — that's a real transaction whose merchant happens to be uppercase
        // (very common for Chase-formatted statements). The pair-detection prevents the
        // FX filter from eating legitimate uppercase merchant rows.
        final String input =
                String.join(
                        "\n",
                        "06/03     SYNTHETIC HOTEL ZURICH 211.11",
                        "06/04     SOMETHING ALL CAPS NO AMOUNT YET",
                        "06/05     SOME OTHER PURCHASE 50.00");

        final String stripped = PDFImportService.stripFxAnnotations(input);

        // All three lines must survive — none have an EXCHG RATE follower.
        assertTrue(stripped.contains("SYNTHETIC HOTEL ZURICH 211.11"));
        assertTrue(stripped.contains("SOMETHING ALL CAPS NO AMOUNT YET"));
        assertTrue(stripped.contains("SOME OTHER PURCHASE 50.00"));
    }

    @Test
    void stripFxAnnotations_dropsStandaloneExchgRateLine_evenWithoutFxHeader() {
        // Defense-in-depth: if a statement emits an FX detail line on its own (no
        // preceding "date CURRENCY" header — e.g. a different bank's variant), we should
        // STILL drop the detail line. It's never a real transaction.
        final String input =
                String.join(
                        "\n",
                        "06/03     SYNTHETIC HOTEL ZURICH 211.11",
                        "192.50 X 1.096493506 (EXCHG RATE)",
                        "06/05     OTHER PURCHASE 50.00");

        final String stripped = PDFImportService.stripFxAnnotations(input);

        assertFalse(stripped.contains("EXCHG RATE"));
        assertTrue(stripped.contains("SYNTHETIC HOTEL ZURICH 211.11"));
        assertTrue(stripped.contains("OTHER PURCHASE 50.00"));
    }

    @Test
    void stripFxAnnotations_isCaseInsensitiveForExchgRateMarker() {
        // Chase emits in uppercase; defensive against future Plaid-feed normalization
        // that may downcase the marker.
        final String input =
                String.join(
                        "\n",
                        "06/03     SYNTHETIC HOTEL ZURICH 211.11",
                        "06/04     SWISS FRANC",
                        "192.50 X 1.096493506 (Exchg Rate)");

        final String stripped = PDFImportService.stripFxAnnotations(input);
        assertFalse(stripped.toLowerCase(java.util.Locale.ROOT).contains("exchg rate"));
        assertFalse(stripped.contains("SWISS FRANC"));
    }

    @Test
    void stripFxAnnotations_handlesNullAndEmptyInputs() {
        assertEquals(null, PDFImportService.stripFxAnnotations(null));
        assertEquals("", PDFImportService.stripFxAnnotations(""));
    }

    @Test
    void stripFxAnnotations_leavesNonFxStatementUntouched() {
        // No FX-related lines at all — output should be byte-identical to input.
        final String input =
                String.join(
                        "\n",
                        "04/01     STARBUCKS 5.25",
                        "04/02     AMAZON.COM 42.00",
                        "04/03     PAYMENT - THANK YOU -100.00");

        final String stripped = PDFImportService.stripFxAnnotations(input);
        assertEquals(input, stripped);
    }

    // ---------- FX info preservation (round-trip through ParsedTransaction) ----------

    @Test
    void stripAndCaptureFxAnnotations_capturesOriginalAmountAndRateKeyedByParentTxn() {
        final PDFImportService.FxStripResult res =
                PDFImportService.stripAndCaptureFxAnnotations(CHASE_MARRIOTT_BONVOY_FIXTURE);

        // Both FX blocks must surface as anchored hints. Anchor key uses MM-DD|amount.
        assertEquals(2, res.getAnnotationsByAnchor().size());
        final PDFImportService.FxAnnotation zurich =
                res.getAnnotationsByAnchor().get("06-03|211.11");
        final PDFImportService.FxAnnotation munich =
                res.getAnnotationsByAnchor().get("06-15|88.50");
        assertEquals("CHF", zurich.getOriginalCurrencyCode());
        assertEquals("SWISS FRANC", zurich.getOriginalCurrencyDisplay());
        assertEquals(0, new java.math.BigDecimal("192.50").compareTo(zurich.getOriginalAmount()));
        assertEquals(
                0, new java.math.BigDecimal("1.096493506").compareTo(zurich.getExchangeRate()));

        assertEquals("EUR", munich.getOriginalCurrencyCode());
        assertEquals(
                0, new java.math.BigDecimal("78.45").compareTo(munich.getOriginalAmount()));
        assertEquals(
                0, new java.math.BigDecimal("1.127979605").compareTo(munich.getExchangeRate()));
    }

    @Test
    void applyFxAnnotationIfPresent_attachesFieldsAndAppendsDescriptionSuffix() {
        final PDFImportService.FxStripResult res =
                PDFImportService.stripAndCaptureFxAnnotations(CHASE_MARRIOTT_BONVOY_FIXTURE);

        final PDFImportService.ParsedTransaction txn = new PDFImportService.ParsedTransaction();
        txn.setDate(java.time.LocalDate.of(2026, 6, 3));
        txn.setAmount(new java.math.BigDecimal("211.11"));
        txn.setDescription("SYNTHETIC HOTEL ZURICH");

        PDFImportService.applyFxAnnotationIfPresent(txn, res.getAnnotationsByAnchor());

        assertEquals("CHF", txn.getOriginalCurrencyCode());
        assertEquals(
                0, new java.math.BigDecimal("192.50").compareTo(txn.getOriginalAmount()));
        assertEquals(
                0, new java.math.BigDecimal("1.096493506").compareTo(txn.getExchangeRate()));
        assertTrue(txn.getDescription().contains("(CHF 192.50 @ 1.096493506)"),
                "Description must carry a human-readable FX suffix for UIs that don't yet read"
                        + " the structured fields. Got: " + txn.getDescription());
    }

    @Test
    void applyFxAnnotationIfPresent_isIdempotent_doesNotDoubleAppendSuffix() {
        final PDFImportService.FxStripResult res =
                PDFImportService.stripAndCaptureFxAnnotations(CHASE_MARRIOTT_BONVOY_FIXTURE);

        final PDFImportService.ParsedTransaction txn = new PDFImportService.ParsedTransaction();
        txn.setDate(java.time.LocalDate.of(2026, 6, 15));
        txn.setAmount(new java.math.BigDecimal("88.50"));
        txn.setDescription("FAKE BISTRO MUNICH");

        PDFImportService.applyFxAnnotationIfPresent(txn, res.getAnnotationsByAnchor());
        final String firstPass = txn.getDescription();
        PDFImportService.applyFxAnnotationIfPresent(txn, res.getAnnotationsByAnchor());

        assertEquals(firstPass, txn.getDescription(),
                "Re-applying the same annotation must not double-append the FX suffix");
    }

    @Test
    void applyFxAnnotationIfPresent_isNoOp_whenTransactionDoesNotMatchAnyAnchor() {
        final PDFImportService.FxStripResult res =
                PDFImportService.stripAndCaptureFxAnnotations(CHASE_MARRIOTT_BONVOY_FIXTURE);

        // Different date — should not match either FX anchor (06-03, 06-15).
        final PDFImportService.ParsedTransaction txn = new PDFImportService.ParsedTransaction();
        txn.setDate(java.time.LocalDate.of(2026, 6, 22));
        txn.setAmount(new java.math.BigDecimal("2500.00"));
        txn.setDescription("AUTOMATIC PAYMENT - THANK YOU");

        PDFImportService.applyFxAnnotationIfPresent(txn, res.getAnnotationsByAnchor());

        // Description must be untouched; FX fields must remain null.
        assertEquals("AUTOMATIC PAYMENT - THANK YOU", txn.getDescription());
        assertEquals(null, txn.getOriginalCurrencyCode());
        assertEquals(null, txn.getOriginalAmount());
    }

    @Test
    void stripAndCaptureFxAnnotations_mapsUnknownCurrencyDisplayToItself() {
        // A currency display name we don't have an ISO mapping for must still be captured —
        // we fall back to the raw display name as the "code" so the data is preserved.
        final String input =
                String.join(
                        "\n",
                        "04/01     SOUK MARRAKECH 99.99",
                        "04/02     MOROCCAN DIRHAM",
                        "1,000.00 X 0.099 (EXCHG RATE)");
        final PDFImportService.FxStripResult res =
                PDFImportService.stripAndCaptureFxAnnotations(input);

        final PDFImportService.FxAnnotation fx = res.getAnnotationsByAnchor().get("04-01|99.99");
        assertTrue(fx != null, "FX must still be captured even for unknown currency names");
        // Unknown display → use the raw label as the "code" so the data is preserved.
        assertEquals("MOROCCAN DIRHAM", fx.getOriginalCurrencyCode());
        assertEquals("MOROCCAN DIRHAM", fx.getOriginalCurrencyDisplay());
    }

    @Test
    void stitchContinuationLines_afterStripping_keepsRealTransactionsOnSeparateLines() {
        // End-to-end: after the FX strip, the downstream stitching pass must NOT eat the
        // 04/14 / 04/08 / 04/10 lines into a single mega-line. They start with dates →
        // TXN_START_PATTERN matches each → each remains its own row.
        final String stripped = PDFImportService.stripFxAnnotations(CHASE_MARRIOTT_BONVOY_FIXTURE);
        final String stitched = PDFImportService.stitchContinuationLines(stripped);

        // Each transaction line must appear once as a self-contained line.
        final String[] lines = stitched.split("\\n");
        int paymentMatches = 0;
        int westinPuneMatches = 0;
        int westinChennaiMatches = 0;
        for (final String line : lines) {
            if (line.contains("AUTOMATIC PAYMENT - THANK YOU -2,500.00")) {
                paymentMatches++;
            }
            if (line.contains("SYNTHETIC HOTEL ZURICH 211.11")) {
                westinPuneMatches++;
            }
            if (line.contains("FAKE BISTRO MUNICH 88.50")) {
                westinChennaiMatches++;
            }
        }
        assertEquals(1, paymentMatches);
        assertEquals(1, westinPuneMatches);
        assertEquals(1, westinChennaiMatches);
    }
}
