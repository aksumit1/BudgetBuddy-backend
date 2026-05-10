package com.budgetbuddy.service.pdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.regex.Matcher;
import org.junit.jupiter.api.Test;

/**
 * Tests pinning the pattern shapes that drive PDF statement import.
 *
 * <p>Every test documents both a <em>matching</em> line (from an actual statement format — Chase,
 * Bank of America, AMEX, Capital One) and something very similar that must <em>not</em> match. The
 * negative cases are the load-bearing ones: a pattern that silently starts grabbing amount
 * fragments out of phone numbers would corrupt every import.
 */
class PdfStatementPatternsTest {

    // ========== AMOUNT MATCHING ==========

    @Test
    void fallbackAmountDoesNotGrabDigitsFromPhoneNumbers() {
        // "Call 800-555-1234" must NOT yield $123 / 123 as an amount.
        // The word-boundary guards in FALLBACK_AMOUNT_PATTERN are what
        // enforce this; if someone removes them, this test fires.
        final Matcher m =
                PdfStatementPatterns.FALLBACK_AMOUNT_PATTERN.matcher(
                        "Call 800-555-1234 for support");
        assertFalse(m.find(), "phone numbers must not match the amount pattern");
    }

    @Test
    void fallbackAmountMatchesAmountWithoutDollarSign() {
        // QuickBooks export format: no $ sign, just "123.45". We still
        // want to extract it — but only when it has a decimal.
        final Matcher m =
                PdfStatementPatterns.FALLBACK_AMOUNT_PATTERN.matcher("GROCERY STORE 123.45");
        assertTrue(m.find());
        assertEquals("123.45", m.group(1));
    }

    @Test
    void fallbackAmountMatchesParenthesizedNegative() {
        // Accountant-style negative: "(123.45)". Common in statements
        // that distinguish debit / credit by bracketing credits.
        final Matcher m = PdfStatementPatterns.FALLBACK_AMOUNT_PATTERN.matcher("REFUND ($45.67)");
        assertTrue(m.find());
        assertTrue(m.group(1).contains("45.67"));
    }

    @Test
    void fallbackAmountAllowsLargeAmountsWithoutThousandsSeparator() {
        // Plaid and some export formats emit "1234.56" without the comma.
        // The pattern was explicitly widened from \\d{1,3} to \\d{1,9}
        // to cover this.
        final Matcher m =
                PdfStatementPatterns.FALLBACK_AMOUNT_PATTERN.matcher("MORTGAGE PAYMENT 12345.67");
        assertTrue(m.find());
        assertEquals("12345.67", m.group(1));
    }

    @Test
    void endAnchoredAmountOnlyMatchesAtEndOfLine() {
        // "... $45.67" matches; "$45.67 ..." does NOT. Critical for the
        // pattern-7 branch that assumes the amount is on its own line.
        assertTrue(
                PdfStatementPatterns.AMOUNT_PATTERN_END_ANCHORED
                        .matcher("GROCERY STORE $45.67")
                        .find());
        assertFalse(
                PdfStatementPatterns.AMOUNT_PATTERN_END_ANCHORED
                        .matcher("$45.67 GROCERY STORE")
                        .find());
    }

    // ========== DATE MATCHING ==========

    @Test
    void datePatternAcceptsSlashAndDash() {
        assertTrue(PdfStatementPatterns.DATE_PATTERN.matcher("12/15/2024").find());
        assertTrue(PdfStatementPatterns.DATE_PATTERN.matcher("12-15-2024").find());
        assertTrue(PdfStatementPatterns.DATE_PATTERN.matcher("12/15/24").find());
    }

    @Test
    void datePatternWithoutYearRequiresSlashNotDash() {
        // Intentional: dashes with no year look too much like a numeric
        // range (e.g., "7-8" could mean a trip from row 7 to row 8).
        assertTrue(PdfStatementPatterns.DATE_PATTERN_NO_YEAR.matcher("12/15").find());
        assertFalse(PdfStatementPatterns.DATE_PATTERN_NO_YEAR.matcher("12-15").find());
    }

    // ========== LINE-SHAPE PATTERNS ==========

    @Test
    void pattern1MatchesTypicalDateDescAmountRow() {
        // Chase personal-checking row format.
        final Matcher m =
                PdfStatementPatterns.PATTERN1_DATE_DESC_AMOUNT.matcher(
                        "12/15 GROCERY STORE $45.67");
        assertTrue(m.find());
        assertEquals("12/15", m.group(1));
        assertEquals("GROCERY STORE", m.group(2));
    }

    @Test
    void pattern7AmountLineIgnoresChaseDiamondGlyph() {
        // Chase credit-card statements mark certain rewards-eligible
        // amounts with a trailing diamond. The pattern must allow (and
        // ignore) the glyph; the amount itself is still group 1.
        final Matcher m = PdfStatementPatterns.PATTERN7_LINE3_AMOUNT.matcher("$45.67 ⧫");
        assertTrue(m.find());
        assertTrue(m.group(1).contains("45.67"));
    }

    @Test
    void informationalPhrasesMatchesCaseInsensitive() {
        assertTrue(
                PdfStatementPatterns.INFORMATIONAL_PHRASES.matcher("Credits this period").find());
        assertTrue(PdfStatementPatterns.INFORMATIONAL_PHRASES.matcher("CHARGES").find());
        assertTrue(PdfStatementPatterns.INFORMATIONAL_PHRASES.matcher("Balance transfers").find());
        // Unrelated line must not match — guards against the filter
        // swallowing real transactions just because they mention "amount".
        assertFalse(
                PdfStatementPatterns.INFORMATIONAL_PHRASES.matcher("GROCERY STORE $45.67").find());
    }
}
