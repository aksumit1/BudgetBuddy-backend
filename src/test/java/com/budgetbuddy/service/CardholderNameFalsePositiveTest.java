package com.budgetbuddy.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Regression coverage for the Amex Platinum (1-21002) statement layout, which
 * extracts as a multi-column block when PDFBox runs without {@code setSortByPosition}:
 *
 * <pre>
 *   02/17/26 AplPay STARBUCKS STORE 0030 SEATTLE WA
 *   FAST FOOD RESTAURANT
 *   $13.03
 * </pre>
 *
 * Before this fix, "FAST FOOD RESTAURANT" matched the cardholder-name heuristic
 * (3 ALL-CAPS tokens, first ≥4 letters) which caused {@link
 * PDFImportService#stitchContinuationLines} to flush the in-flight transaction
 * BEFORE the amount line on the next row, dropping the STARBUCKS row entirely.
 *
 * <p>The same false positive affected "VIDEO RENTAL STORE", "MISC SPECIALTY
 * RETAIL", and similar Amex category-descriptor lines. After the fix, any
 * 3+ token ALL-CAPS line containing a retail-category word (FOOD, STORE,
 * RENTAL, RETAIL, …) is no longer treated as a cardholder name.
 */
class CardholderNameFalsePositiveTest {

    @Test
    void falsePositivesRejected() {
        assertFalse(PDFImportService.looksLikeCardholderName("FAST FOOD RESTAURANT"));
        assertFalse(PDFImportService.looksLikeCardholderName("VIDEO RENTAL STORE"));
        assertFalse(PDFImportService.looksLikeCardholderName("MISC SPECIALTY RETAIL"));
        assertFalse(PDFImportService.looksLikeCardholderName("FAMILY CLOTHING STORES"));
        assertFalse(PDFImportService.looksLikeCardholderName("HARDWARE SUPPLY STORE"));
    }

    @Test
    void realCardholderNamesStillAccepted() {
        // 2-token names
        assertTrue(PDFImportService.looksLikeCardholderName("MUDIT AGARWAL"));
        assertTrue(PDFImportService.looksLikeCardholderName("GARIMA AGARWAL"));
        // 3-token names with middle name
        assertTrue(PDFImportService.looksLikeCardholderName("AGARWAL SUMIT KUMAR"));
        assertTrue(PDFImportService.looksLikeCardholderName("GARIMA DIPTI AGARWAL"));
        // 3-token name with middle initial
        assertTrue(PDFImportService.looksLikeCardholderName("AGARWAL S KUMAR"));
    }

    @Test
    void stitchPreservesAmountForStarbucksLayout() {
        // Reproduces the exact Amex Platinum extraction shape that produced
        // the bug. The amount must end up on the same physical line as the
        // merchant row after stitching so amount-anchored regex can capture it.
        final String input = String.join(
                "\n",
                "02/17/26 AplPay STARBUCKS STORE 0030 SEATTLE WA",
                "FAST FOOD RESTAURANT",
                "$13.03",
                "AGARWAL SUMIT KUMAR Account Ending 1-21002");
        final String stitched = PDFImportService.stitchContinuationLines(input);
        final String firstLine = stitched.split("\\r?\\n")[0];
        assertTrue(
                firstLine.contains("STARBUCKS") && firstLine.contains("$13.03"),
                "Expected STARBUCKS + amount on the same stitched line, got: " + firstLine);
    }
}
