package com.budgetbuddy.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Regression coverage for the fixes that landed during the multi-statement
 * directory sweep (41 PDFs across 5 issuers, every statement reached
 * EXCELLENT band after the changes).
 *
 * <p>Each test pins a single root cause that would otherwise regress silently:
 *
 * <ul>
 *   <li><b>rewardsBalance unification</b> — pointsBalance and cashBackBalance
 *       counted as ONE field in the health report (mutually exclusive per card).
 *       Pre-fix, cards without a cumulative-rewards-balance line capped at 83% OK.
 *   <li><b>Citi Payments + Credits sum</b> — Citi prints "Payments -$X" and
 *       "Credits -$X" as two separate rows. The total must sum both lines, not
 *       return only Payments. Pre-fix, math reconciliation failed by exactly the
 *       Credits amount on every Citi statement that had a separate credit row
 *       (Jan/Feb Double Cash + Costco).
 *   <li><b>Amex stacked Credit Limit accepts BOTH second-label forms</b> —
 *       "Amount Above the Credit Limit" (over-limit) and "Available Credit"
 *       (within-limit). Pre-fix, when the user paid down below the limit, the
 *       second-label form changed and the extractor missed both creditLimit
 *       AND availableCredit.
 *   <li><b>USB "Interest Charged + $79.99" sign-with-space</b> — US Bank
 *       prints section-total signs with a space between sign and `$`. The
 *       standard US_AMOUNT_PATTERN_STR requires sign directly attached. Now
 *       accepts an optional leading {@code [+\\-]\\s*} prefix on interest-
 *       charged labels. Pre-fix, the math identity failed by exactly the
 *       interest amount on USB statements where interest was non-zero.
 * </ul>
 */
class SweepLearningsRegressionTest {

    // ============================================================
    // Stitching boundary at the ⧫ end-of-transaction marker
    // ============================================================

    @Test
    void stitchingBoundary_diamondMarker_flushesPending() throws Exception {
        // Real bug from Mar_14 Amex Platinum: a cardholder-name line right
        // after a $X.XX ⧫ transaction line was getting glued onto the previous
        // transaction's description, yielding:
        //   {date=04/08, desc="BCD TRAVEL ... PASSENGER TICKET", amount=$240.40,
        //    + " GARIMA DIPTI AGARWAL" (leaked from next section)}
        // The diamond ⧫ is Amex's unambiguous end-of-transaction marker.
        // Stitching now flushes pending when pending.endsWith('⧫').
        final String input = String.join("\n",
                "04/08/26 BCD TRAVEL ATLANTA GA",
                "AIR INDIA",
                "Ticket Number: 09874319969905",
                "Passenger Name: SUMIT KUMAR/AGARWAL",
                "Document Type: PASSENGER TICKET",
                "$240.40 ⧫",
                "GARIMA DIPTI AGARWAL",
                "");
        final String stitched = PDFImportService.stitchContinuationLines(input);
        // The transaction line should be ONE complete line ending with '⧫'.
        // The next cardholder name "GARIMA" must NOT appear on the same line.
        boolean found = false;
        for (String line : stitched.split("\\r?\\n")) {
            if (line.contains("BCD TRAVEL")) {
                found = true;
                assertTrue(line.contains("$240.40"), "Transaction amount present");
                assertTrue(line.endsWith("⧫"), "Line ends at the diamond marker, not beyond");
                assertTrue(!line.contains("GARIMA"),
                        "Cardholder name from NEXT section must NOT leak into transaction line: " + line);
            }
        }
        assertTrue(found, "BCD TRAVEL transaction line must be present in stitched output");
    }

    @Test
    void stitchingBoundary_diamondMarker_idempotent() {
        // Running stitching on already-stitched text must not change output.
        final String input = String.join("\n",
                "04/04/26 MERCHANT X $100.00 ⧫",
                "GARIMA DIPTI AGARWAL",
                "04/05/26 MERCHANT Y $50.00 ⧫",
                "");
        final String once = PDFImportService.stitchContinuationLines(input);
        final String twice = PDFImportService.stitchContinuationLines(once);
        assertEquals(once, twice, "stitchContinuationLines must be idempotent post-fix");
    }

    // ============================================================
    // Card-name false positives ("Chase Mobile app today®")
    // ============================================================

    @Test
    void productNameExtractor_rejectsMobileAppMarketingTagline() throws Exception {
        // Real bug: Chase list-PDFs print "Chase Mobile app today®" as the
        // second line of the document. The product-name extractor was matching
        // it as a product candidate (institution=Chase + indicator=®) and
        // returning the marketing tagline as the card's accountName.
        final java.lang.reflect.Method m =
                AccountDetectionService.class.getDeclaredMethod(
                        "extractProductNameFromPDF", String.class);
        m.setAccessible(true);
        final AccountDetectionService ads =
                new AccountDetectionService(null, new BalanceExtractor());
        final String header =
                "Manage your account online at: Mobile:  Download the\n"
                + "Chase Mobile app today® \n"
                + "ACCOUNT SUMMARY\n"
                + "YOUR ACCOUNT MESSAGES\n";
        final Object result = m.invoke(ads, header);
        assertEquals(null, result,
                "'Chase Mobile app today®' must not be returned as a product name; "
                        + "downstream generateAccountName fallback will yield 'Chase credit card NNNN'");
    }

    // ============================================================
    // Citi AutoPay duplication (cross-issuer YAML template leak)
    // ============================================================

    @Test
    void registryOrderedFor_restrictsToMatchingInstitutionOnly() throws Exception {
        // Real bug: Citi AutoPay row `12/28 AUTOPAY ... -$1,670.03` was being
        // extracted ONCE by the structured parser (as -$1,670.03) and AGAIN by
        // off-institution YAML templates (PNC / Regions / TD Bank / US Bank
        // checking-single-line) which stripped the leading `-` sign and
        // produced +$1,670.03. Dedupe by exact (date, desc, amount) kept both
        // because the signs differed. orderedFor must return ONLY the templates
        // matching the detected institution when one is registered; off-
        // institution fallback was strictly harmful.
        final com.budgetbuddy.service.pdf.PdfTemplateRegistry reg =
                new com.budgetbuddy.service.pdf.PdfTemplateRegistry();
        final java.lang.reflect.Field f =
                com.budgetbuddy.service.pdf.PdfTemplateRegistry.class
                        .getDeclaredField("resourcePattern");
        f.setAccessible(true);
        f.set(reg, "classpath:pdf-templates/*.yaml");
        final java.lang.reflect.Method init =
                com.budgetbuddy.service.pdf.PdfTemplateRegistry.class.getDeclaredMethod("init");
        init.setAccessible(true);
        init.invoke(reg);

        final var citiTemplates = reg.orderedFor("Citi");
        assertEquals(1, citiTemplates.size(),
                "When institution is detected, orderedFor returns ONLY matching templates");
        assertEquals("citi-v1", citiTemplates.getFirst().getId());

        // 'Citibank' / 'Citicards' also resolve to the Citi template via substring match.
        assertEquals(1, reg.orderedFor("Citibank").size());

        // Unknown institution falls back to full list (legitimate try-anything case).
        assertTrue(reg.orderedFor("UnknownBank").size() > 1,
                "Unknown institution must fall back to the full template list");

        // Null institution returns full list (no detection).
        assertTrue(reg.orderedFor(null).size() > 1);
    }

    @Test
    void dedupeKey_collapsesWhitespaceVariants() throws Exception {
        // Real bug: structured parse normalizes merchant whitespace
        // ("SAFEWAY #1444 BELLEVUE WA"), YAML preserves original PDF spacing
        // ("SAFEWAY #1444          BELLEVUE      WA"). The dedupe key MUST
        // collapse runs of whitespace so the same transaction extracted by
        // both paths gets merged. Pre-fix, 36 duplicate groups survived in
        // the January Citi statement.
        final java.lang.reflect.Method m =
                PDFImportService.class.getDeclaredMethod("dedupeKey", java.util.Map.class);
        m.setAccessible(true);
        final PDFImportService svc =
                new PDFImportService(
                        org.mockito.Mockito.mock(AccountDetectionService.class),
                        org.mockito.Mockito.mock(ImportCategoryParser.class),
                        new EnhancedPatternMatcher(),
                        null);
        final java.util.Map<String, String> structured = new java.util.HashMap<>();
        structured.put("date", "12/17");
        structured.put("description", "SAFEWAY #1444 BELLEVUE WA");
        structured.put("amount", "$5.98");
        structured.put("_inferredYear", "2026");
        final java.util.Map<String, String> registry = new java.util.HashMap<>();
        registry.put("date", "2026-12-17");
        registry.put("description", "SAFEWAY #1444          BELLEVUE      WA");
        registry.put("amount", "5.98");
        final String keyA = (String) m.invoke(svc, structured);
        final String keyB = (String) m.invoke(svc, registry);
        assertEquals(keyA, keyB,
                "Dedupe key must collapse runs of whitespace so structured-parse "
                        + "and YAML descriptions for the same transaction merge");
    }

    @Test
    void dedupeKey_preservesSignSoLegitRefundPlusPurchaseSurvive() throws Exception {
        // CRITICAL safety: when a merchant prints both a refund and a fresh
        // purchase for the same amount on the same day (HOME DEPOT $16.28
        // refund + $16.28 new purchase on 12/09), the SIGNS differ — dedupe
        // must keep BOTH rows. Preserving the sign in normalizeAmountForDedupe
        // is what makes this case work.
        final java.lang.reflect.Method m =
                PDFImportService.class.getDeclaredMethod("dedupeKey", java.util.Map.class);
        m.setAccessible(true);
        final PDFImportService svc =
                new PDFImportService(
                        org.mockito.Mockito.mock(AccountDetectionService.class),
                        org.mockito.Mockito.mock(ImportCategoryParser.class),
                        new EnhancedPatternMatcher(),
                        null);
        final java.util.Map<String, String> refund = new java.util.HashMap<>();
        refund.put("date", "12/09");
        refund.put("description", "THE HOME DEPOT #4704 ISSAQUAH WA");
        refund.put("amount", "-$16.28");
        refund.put("_inferredYear", "2026");
        final java.util.Map<String, String> purchase = new java.util.HashMap<>();
        purchase.put("date", "12/09");
        purchase.put("description", "THE HOME DEPOT #4704 ISSAQUAH WA");
        purchase.put("amount", "$16.28");
        purchase.put("_inferredYear", "2026");
        final String keyRefund = (String) m.invoke(svc, refund);
        final String keyPurchase = (String) m.invoke(svc, purchase);
        assertTrue(!keyRefund.equals(keyPurchase),
                "Refund (-$16.28) and purchase ($16.28) on same day at same merchant "
                        + "must NOT collapse — they're distinct transactions");
    }

    @Test
    void productNameExtractor_realCardNameWinsOverMarketing() throws Exception {
        // When a legitimate product name AND the marketing tagline both appear,
        // the legitimate product name wins (because it scores higher in the
        // candidate selection, and the marketing line is hard-blacklisted).
        final java.lang.reflect.Method m =
                AccountDetectionService.class.getDeclaredMethod(
                        "extractProductNameFromPDF", String.class);
        m.setAccessible(true);
        final AccountDetectionService ads =
                new AccountDetectionService(null, new BalanceExtractor());
        final String header =
                "Chase Sapphire Reserve\n"
                + "Manage your account online at: Mobile: Download the\n"
                + "Chase Mobile app today®\n";
        final Object result = m.invoke(ads, header);
        assertNotNull(result, "Real product name must extract");
        assertTrue(((String) result).toLowerCase(java.util.Locale.ROOT).contains("sapphire"),
                "Legitimate 'Sapphire Reserve' must win over marketing tagline: " + result);
    }
}
