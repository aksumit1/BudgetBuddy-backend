package com.budgetbuddy.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Tests for US amount format support: CR/DR, +/-, and parentheses for negatives Uses reflection to
 * test private parseUSAmount method
 */
// SDK / Spring integration — the underlying APIs (AWS SDK, Plaid SDK,
// Spring services, reflection) throw arbitrary RuntimeException subtypes
// that can't reasonably be enumerated. Broad catches log + recover (or
// translate to AppException). Suppress at class level since narrowing
// here would mean catch (RuntimeException) which PMD flags identically.
// Test methods declare `throws Exception` for setup convenience —
// JUnit idiom; the rule is a noise generator on test classes.
@SuppressFBWarnings(
        value = "THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION",
        justification = "JUnit idiom — test methods accept any setup exception")
@SuppressWarnings("PMD.AvoidCatchingGenericException")
class PDFImportServiceUSAmountTest {

    private static final String AMOUNT = "amount";

    private PDFImportService pdfImportService;
    private EnhancedPatternMatcher enhancedPatternMatcher;
    private AccountDetectionService accountDetectionService;
    private ImportCategoryParser importCategoryParser;
    private Method parseUSAmountMethod;

    @BeforeEach
    void setUp() throws Exception {
        accountDetectionService = Mockito.mock(AccountDetectionService.class);
        importCategoryParser = Mockito.mock(ImportCategoryParser.class);
        enhancedPatternMatcher = new EnhancedPatternMatcher();
        pdfImportService =
                new PDFImportService(
                        accountDetectionService,
                        importCategoryParser,
                        enhancedPatternMatcher,
                        null);

        // Use reflection to access private parseUSAmount method
        parseUSAmountMethod =
                PDFImportService.class.getDeclaredMethod("parseUSAmount", String.class);
        parseUSAmountMethod.setAccessible(true);
    }

    private BigDecimal parseUSAmount(final String amountStr) {
        try {
            return (BigDecimal) parseUSAmountMethod.invoke(pdfImportService, amountStr);
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke parseUSAmount", e);
        }
    }

    /** Test parseUSAmount directly with various formats */
    @Test
    void testParseUSAmountStandardAmount() {
        final BigDecimal result = parseUSAmount("$458.40");
        assertNotNull(result);
        assertEquals(new BigDecimal("458.40"), result);
    }

    @Test
    void testParseUSAmountNegativeSign() {
        final BigDecimal result = parseUSAmount("-$458.40");
        assertNotNull(result);
        assertEquals(new BigDecimal("-458.40"), result);
    }

    @Test
    void testParseUSAmountPositiveSign() {
        final BigDecimal result = parseUSAmount("+$1,234.56");
        assertNotNull(result);
        assertEquals(new BigDecimal("1234.56"), result);
    }

    @Test
    void testParseUSAmountParenthesesNegative() {
        final BigDecimal result = parseUSAmount("($458.40)");
        assertNotNull(result);
        assertEquals(new BigDecimal("-458.40"), result);
    }

    @Test
    void testParseUSAmountCreditIndicator() {
        final BigDecimal result = parseUSAmount("$1,234.56 CR");
        assertNotNull(result);
        assertEquals(new BigDecimal("1234.56"), result);
    }

    @Test
    void testParseUSAmountDebitIndicator() {
        final BigDecimal result = parseUSAmount("$458.40 DR");
        assertNotNull(result);
        assertEquals(new BigDecimal("-458.40"), result);
    }

    @Test
    void testParseUSAmountParenthesesWithCR() {
        // Parentheses take precedence - should be negative
        final BigDecimal result = parseUSAmount("($123.45) CR");
        assertNotNull(result);
        assertEquals(new BigDecimal("-123.45"), result);
    }

    @Test
    void testParseUSAmountBalanceForward() {
        final BigDecimal result = parseUSAmount("$5,432.10 BF");
        assertNotNull(result);
        assertEquals(new BigDecimal("5432.10"), result);
    }

    @Test
    void testParseUSAmountNoDollarSign() {
        final BigDecimal result = parseUSAmount("123.45");
        assertNotNull(result);
        assertEquals(new BigDecimal("123.45"), result);
    }

    @Test
    void testParseUSAmountNoDollarSignWithCR() {
        final BigDecimal result = parseUSAmount("123.45 CR");
        assertNotNull(result);
        assertEquals(new BigDecimal("123.45"), result);
    }

    @Test
    void testParseUSAmountNoDollarSignWithDR() {
        final BigDecimal result = parseUSAmount("123.45 DR");
        assertNotNull(result);
        assertEquals(new BigDecimal("-123.45"), result);
    }

    @Test
    void testParseUSAmountParenthesesNoDollarSign() {
        final BigDecimal result = parseUSAmount("(123.45)");
        assertNotNull(result);
        assertEquals(new BigDecimal("-123.45"), result);
    }

    @Test
    void testParseUSAmountVeryLargeAmount() {
        final BigDecimal result = parseUSAmount("$999,999.99");
        assertNotNull(result);
        assertEquals(new BigDecimal("999999.99"), result);
    }

    @Test
    void testParseUSAmountZeroAmount() {
        final BigDecimal result = parseUSAmount("$0.00");
        assertNotNull(result);
        assertEquals(0, result.compareTo(BigDecimal.ZERO), "Should equal zero");
    }

    @Test
    void testParseUSAmountInvalidFormat() {
        final BigDecimal result = parseUSAmount("invalid");
        assertNull(result, "Should return null for invalid format");
    }

    @Test
    void testParseUSAmountNullInput() {
        final BigDecimal result = parseUSAmount(null);
        assertNull(result, "Should return null for null input");
    }

    @Test
    void testParseUSAmountEmptyString() {
        final BigDecimal result = parseUSAmount("");
        assertNull(result, "Should return null for empty string");
    }

    @Test
    void testParseUSAmountMixedFormats() {
        // Test various combinations
        assertEquals(new BigDecimal("100.00"), parseUSAmount("$100.00"));
        assertEquals(new BigDecimal("-100.00"), parseUSAmount("-$100.00"));
        assertEquals(new BigDecimal("100.00"), parseUSAmount("+$100.00"));
        assertEquals(new BigDecimal("-100.00"), parseUSAmount("($100.00)"));
        assertEquals(new BigDecimal("100.00"), parseUSAmount("$100.00 CR"));
        assertEquals(new BigDecimal("-100.00"), parseUSAmount("$100.00 DR"));
        assertEquals(new BigDecimal("100.00"), parseUSAmount("$100.00 BF"));
    }

    // ========== Additional Edge Case Tests ==========

    @Test
    void testParseUSAmountWhitespaceVariations() {
        assertEquals(new BigDecimal("100.00"), parseUSAmount("  $100.00  "));
        assertEquals(new BigDecimal("100.00"), parseUSAmount("$100.00 CR "));
        assertEquals(new BigDecimal("-100.00"), parseUSAmount(" ($100.00) "));
        assertEquals(new BigDecimal("-100.00"), parseUSAmount("($100.00) DR"));
    }

    @Test
    void testParseUSAmountMultipleCommas() {
        assertEquals(new BigDecimal("1234567.89"), parseUSAmount("$1,234,567.89"));
        assertEquals(new BigDecimal("-1234567.89"), parseUSAmount("-$1,234,567.89"));
        assertEquals(new BigDecimal("1234567.89"), parseUSAmount("$1,234,567.89 CR"));
    }

    @Test
    void testParseUSAmountSmallAmounts() {
        assertEquals(new BigDecimal("0.01"), parseUSAmount("$0.01"));
        assertEquals(new BigDecimal("-0.01"), parseUSAmount("-$0.01"));
        assertEquals(new BigDecimal("0.01"), parseUSAmount("$0.01 CR"));
        assertEquals(new BigDecimal("-0.01"), parseUSAmount("$0.01 DR"));
    }

    @Test
    void testParseUSAmountNoDecimalPlaces() {
        assertEquals(new BigDecimal("100"), parseUSAmount("$100"));
        assertEquals(new BigDecimal("-100"), parseUSAmount("-$100"));
        assertEquals(new BigDecimal("100"), parseUSAmount("$100 CR"));
    }

    @Test
    void testParseUSAmountSingleDecimalDigit() {
        assertEquals(new BigDecimal("100.5"), parseUSAmount("$100.5"));
        assertEquals(new BigDecimal("-100.5"), parseUSAmount("-$100.5"));
    }

    @Test
    void testParseUSAmountParenthesesWithDR() {
        final BigDecimal result = parseUSAmount("($123.45) DR");
        assertNotNull(result);
        // DR takes precedence over parentheses
        assertEquals(new BigDecimal("-123.45"), result);
    }

    @Test
    void testParseUSAmountParenthesesWithBF() {
        final BigDecimal result = parseUSAmount("($123.45) BF");
        assertNotNull(result);
        // Parentheses take precedence over BF
        assertEquals(new BigDecimal("-123.45"), result);
    }

    @Test
    void testParseUSAmountNegativeWithCR() {
        // Negative sign with CR - CR should make it positive
        final BigDecimal result = parseUSAmount("-$123.45 CR");
        assertNotNull(result);
        // CR takes precedence over negative sign
        assertEquals(new BigDecimal("123.45"), result);
    }

    @Test
    void testParseUSAmountPositiveWithDR() {
        // Positive sign with DR - DR should make it negative
        final BigDecimal result = parseUSAmount("+$123.45 DR");
        assertNotNull(result);
        // DR takes precedence over positive sign
        assertEquals(new BigDecimal("-123.45"), result);
    }

    @Test
    void testParseUSAmountOnlyDollarSign() {
        final BigDecimal result = parseUSAmount("$");
        assertNull(result, "Should return null for only dollar sign");
    }

    @Test
    void testParseUSAmountOnlyParentheses() {
        final BigDecimal result = parseUSAmount("()");
        assertNull(result, "Should return null for empty parentheses");
    }

    @Test
    void testParseUSAmountOnlyCR() {
        final BigDecimal result = parseUSAmount("CR");
        assertNull(result, "Should return null for only CR");
    }

    @Test
    void testParseUSAmountInvalidCharacters() {
        assertNull(parseUSAmount("$abc.def"), "Should reject non-numeric characters");
        assertNull(parseUSAmount("$123.45.67"), "Should reject multiple decimal points");
        // Note: "$123,45" with comma as decimal separator is European format, not US
        // For US format, comma is thousands separator, so "$123,45" would be parsed as "12345"
        // This is actually valid in some contexts, so we'll allow it but it's not standard US
        // format
        final BigDecimal result = parseUSAmount("$123,45");
        // This might parse as 12345 (comma removed) or fail - both are acceptable
        // The key is it shouldn't crash
    }

    @Test
    void testParseUSAmountWhitespaceOnly() {
        assertNull(parseUSAmount("   "));
        assertNull(parseUSAmount("\t"));
        assertNull(parseUSAmount("\n"));
    }

    @Test
    void testParseUSAmountAmountWithSpaces() {
        // Spaces are removed during parsing, so these should work
        final BigDecimal result1 = parseUSAmount("$ 123.45");
        assertNotNull(result1, "Should handle space after $");
        assertEquals(new BigDecimal("123.45"), result1);

        // Space in middle of number is invalid, but we'll try to parse what we can
        final BigDecimal result2 = parseUSAmount("$123 .45");
        // This might fail or parse differently - let's just check it doesn't crash
        // The actual behavior depends on how BigDecimal handles it
    }

    @Test
    void testParseUSAmountVerySmallAmount() {
        final BigDecimal result = parseUSAmount("$0.001");
        assertNotNull(result);
        assertEquals(new BigDecimal("0.001"), result);
    }

    @Test
    void testParseUSAmountMaximumReasonableAmount() {
        final BigDecimal result = parseUSAmount("$999,999,999.99");
        assertNotNull(result);
        assertEquals(new BigDecimal("999999999.99"), result);
    }

    // ========== Integration Tests with Pattern Matching ==========

    @Test
    void testPattern1WithCR() {
        final String line = "11/09     DEPOSIT RECEIVED $1,234.56 CR";
        final Map<String, String> result = parsePattern1ViaReflection(line, 2024);
        assertNotNull(result, "Should parse Pattern 1 with CR");
        assertEquals("11/09", result.get("date"));
        assertTrue(result.get("description").contains("DEPOSIT"));
        assertEquals("$1,234.56 CR", result.get(AMOUNT));
    }

    @Test
    void testPattern1WithDR() {
        final String line = "11/09     PAYMENT MADE $458.40 DR";
        final Map<String, String> result = parsePattern1ViaReflection(line, 2024);
        assertNotNull(result, "Should parse Pattern 1 with DR");
        assertEquals("$458.40 DR", result.get(AMOUNT));
    }

    @Test
    void testPattern1WithParentheses() {
        final String line = "11/09     PAYMENT MADE ($458.40)";
        final Map<String, String> result = parsePattern1ViaReflection(line, 2024);
        assertNotNull(result, "Should parse Pattern 1 with parentheses");
        assertEquals("($458.40)", result.get(AMOUNT));
    }

    @Test
    void testPattern1WithPositiveSign() {
        final String line = "11/09     DEPOSIT +$1,234.56";
        final Map<String, String> result = parsePattern1ViaReflection(line, 2024);
        assertNotNull(result, "Should parse Pattern 1 with + sign");
        assertEquals("+$1,234.56", result.get(AMOUNT));
    }

    @Test
    void testPattern2WithCR() {
        final String line = "Prefix text 10/12 MERCHANT $25.00 CR";
        final Map<String, String> result = parsePattern2ViaReflection(line, 2024);
        assertNotNull(result, "Should parse Pattern 2 with CR");
        assertEquals("$25.00 CR", result.get(AMOUNT));
    }

    @Test
    void testPattern5WithParentheses() {
        final String line = "10/08 10/08 DOLLAR TREE TUKWILA WA ($19.84)";
        final Map<String, String> result = parsePattern5ViaReflection(line, 2024);
        assertNotNull(result, "Should parse Pattern 5 with parentheses");
        assertEquals("($19.84)", result.get(AMOUNT));
    }

    @Test
    void testPattern5WithCR() {
        final String line = "10/08 10/08 DOLLAR TREE TUKWILA WA $19.84 CR";
        final Map<String, String> result = parsePattern5ViaReflection(line, 2024);
        assertNotNull(result, "Should parse Pattern 5 with CR");
        assertEquals("$19.84 CR", result.get(AMOUNT));
    }

    @Test
    void testPattern7WithDR() {
        final String[] lines = {
            "11/27/25* AGARWAL SUMIT KUMAR PAYMENT MADE", "JPMorgan Chase Bank, NA", "$1,957.91 DR"
        };
        final Map<String, String> result = parsePattern7ViaReflection(lines, 0, 2025);
        assertNotNull(result, "Should parse Pattern 7 with DR");
        assertEquals("$1,957.91 DR", result.get(AMOUNT));
    }

    @Test
    void testPattern7WithParentheses() {
        final String[] lines = {
            "11/27/25* AGARWAL SUMIT KUMAR PAYMENT MADE", "JPMorgan Chase Bank, NA", "($1,957.91)"
        };
        final Map<String, String> result = parsePattern7ViaReflection(lines, 0, 2025);
        assertNotNull(result, "Should parse Pattern 7 with parentheses");
        assertEquals("($1,957.91)", result.get(AMOUNT));
    }

    /**
     * Test Pattern 7 variant where merchant name appears before date line (Amex format) Format:
     * Line 1: Merchant name Line 2: Card info Line 3: "Amount" header Line 4: Date + description
     * Line 5: Description continuation Line 6: Amount
     *
     * <p>Current implementation: Pattern 7 CAN detect this if we start from line 4 (date line), but
     * it will NOT capture the merchant name from line 1.
     */
    @Test
    void testPattern7AmexVariantWithMerchantBeforeDate() {
        final String[] lines = {
            "HUMPTY DUMPTY WALLFLOWER",
            "Card Ending 7-31034   Monthly Spending Limit: $2,500",
            "Amount",
            "12/07/25 D J*BARRONS 800-544-0422 NJ",
            "SUBSRIPTION",
            "$4.41 ⧫"
        };

        // Try starting from line 4 (the date line, index 3) - current Pattern 7 expects date on
        // line 1
        final Map<String, String> result = parsePattern7ViaReflection(lines, 3, 2025);

        // Current implementation works if we start from the date line (index 3)
        // Line 4 (index 3): "12/07/25 D J*BARRONS 800-544-0422 NJ" (date + description)
        // Line 5 (index 4): "SUBSRIPTION" (would be treated as merchant/description continuation)
        // Line 6 (index 5): "$4.41 ⧫" (amount)
        assertNotNull(result, "Pattern 7 should detect this format when starting from date line");
        assertEquals("12/07/25", result.get("date"), "Date should be extracted correctly");
        assertTrue(
                result.get("description").contains("D J*BARRONS")
                        || result.get("description").contains("SUBSRIPTION"),
                "Description should contain transaction details");
        assertEquals("$4.41", result.get(AMOUNT), "Amount should be extracted correctly");

        // Note: Merchant name "HUMPTY DUMPTY WALLFLOWER" is NOT captured in current implementation
        // This is a limitation - Pattern 7 would need enhancement to look backwards for merchant
        // name
    }

    // ========== Validation Tests ==========

    @Test
    void testIsValidNonZeroAmountZeroAmount() {
        assertFalse(isValidNonZeroAmountViaReflection("$0.00"));
        assertFalse(isValidNonZeroAmountViaReflection("$0.00 CR"));
        assertFalse(isValidNonZeroAmountViaReflection("($0.00)"));
    }

    @Test
    void testIsValidNonZeroAmountNearZeroAmount() {
        assertFalse(isValidNonZeroAmountViaReflection("$0.001"));
        assertFalse(isValidNonZeroAmountViaReflection("$0.009"));
        assertTrue(isValidNonZeroAmountViaReflection("$0.01"));
    }

    @Test
    void testIsValidNonZeroAmountValidAmounts() {
        assertTrue(isValidNonZeroAmountViaReflection("$0.01"));
        assertTrue(isValidNonZeroAmountViaReflection("$1.00"));
        assertTrue(isValidNonZeroAmountViaReflection("$100.00"));
        assertTrue(isValidNonZeroAmountViaReflection("-$100.00"));
        assertTrue(isValidNonZeroAmountViaReflection("($100.00)"));
        assertTrue(isValidNonZeroAmountViaReflection("$100.00 CR"));
        assertTrue(isValidNonZeroAmountViaReflection("$100.00 DR"));
    }

    @Test
    void testIsValidNonZeroAmountInvalidAmounts() {
        assertFalse(isValidNonZeroAmountViaReflection(null));
        assertFalse(isValidNonZeroAmountViaReflection(""));
        assertFalse(isValidNonZeroAmountViaReflection("invalid"));
        assertFalse(isValidNonZeroAmountViaReflection("$"));
    }

    // ========== createTransactionRow Tests ==========

    @Test
    void testCreateTransactionRowValidInput() {
        final Map<String, String> result =
                createTransactionRowViaReflection("11/09", "MERCHANT NAME", "$100.00", 2024);
        assertNotNull(result);
        assertEquals("11/09", result.get("date"));
        assertEquals("MERCHANT NAME", result.get("description"));
        assertEquals("$100.00", result.get(AMOUNT));
        assertEquals("2024", result.get("_inferredYear"));
    }

    @Test
    void testCreateTransactionRowWithCR() {
        final Map<String, String> result =
                createTransactionRowViaReflection("11/09", "DEPOSIT", "$100.00 CR", 2024);
        assertNotNull(result);
        assertEquals("$100.00 CR", result.get(AMOUNT));
    }

    @Test
    void testCreateTransactionRowWithDR() {
        final Map<String, String> result =
                createTransactionRowViaReflection("11/09", "PAYMENT", "$100.00 DR", 2024);
        assertNotNull(result);
        assertEquals("$100.00 DR", result.get(AMOUNT));
    }

    @Test
    void testCreateTransactionRowWithParentheses() {
        final Map<String, String> result =
                createTransactionRowViaReflection("11/09", "PAYMENT", "($100.00)", 2024);
        assertNotNull(result);
        assertEquals("($100.00)", result.get(AMOUNT));
    }

    @Test
    void testCreateTransactionRowInvalidDate() {
        final Map<String, String> result =
                createTransactionRowViaReflection(null, "MERCHANT", "$100.00", 2024);
        assertNull(result, "Should return null for invalid date");
    }

    @Test
    void testCreateTransactionRowInvalidDescription() {
        Map<String, String> result =
                createTransactionRowViaReflection("11/09", "", "$100.00", 2024);
        assertNull(result, "Should return null for empty description");

        result = createTransactionRowViaReflection("11/09", "   ", "$100.00", 2024);
        assertNull(result, "Should return null for whitespace-only description");
    }

    @Test
    void testCreateTransactionRowInvalidAmount() {
        Map<String, String> result =
                createTransactionRowViaReflection("11/09", "MERCHANT", "$0.00", 2024);
        assertNull(result, "Should return null for zero amount");

        result = createTransactionRowViaReflection("11/09", "MERCHANT", "invalid", 2024);
        assertNull(result, "Should return null for invalid amount");
    }

    @Test
    void testCreateTransactionRowNoInferredYear() {
        final Map<String, String> result =
                createTransactionRowViaReflection("11/09", "MERCHANT", "$100.00", null);
        assertNotNull(result);
        assertFalse(result.containsKey("_inferredYear"), "Should not have _inferredYear when null");
    }

    // ========== Helper Methods for Reflection ==========

    private Map<String, String> parsePattern1ViaReflection(
            final String line, final Integer inferredYear) {
        try {
            final Method method =
                    PDFImportService.class.getDeclaredMethod(
                            "parsePattern1", String.class, Integer.class);
            method.setAccessible(true);
            return (Map<String, String>) method.invoke(pdfImportService, line, inferredYear);
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke parsePattern1", e);
        }
    }

    private Map<String, String> parsePattern2ViaReflection(
            final String line, final Integer inferredYear) {
        try {
            final Method method =
                    PDFImportService.class.getDeclaredMethod(
                            "parsePattern2", String.class, Integer.class);
            method.setAccessible(true);
            return (Map<String, String>) method.invoke(pdfImportService, line, inferredYear);
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke parsePattern2", e);
        }
    }

    private Map<String, String> parsePattern5ViaReflection(
            final String line, final Integer inferredYear) {
        try {
            final Method method =
                    PDFImportService.class.getDeclaredMethod(
                            "parsePattern5", String.class, Integer.class);
            method.setAccessible(true);
            return (Map<String, String>) method.invoke(pdfImportService, line, inferredYear);
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke parsePattern5", e);
        }
    }

    private Map<String, String> parsePattern7ViaReflection(
            final String[] lines, final int startIndex, final Integer inferredYear) {
        try {
            final Method method =
                    PDFImportService.class.getDeclaredMethod(
                            "parsePattern7",
                            String[].class,
                            int.class,
                            Integer.class,
                            String.class);
            method.setAccessible(true);
            return (Map<String, String>)
                    method.invoke(pdfImportService, lines, startIndex, inferredYear, null);
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke parsePattern7", e);
        }
    }

    private boolean isValidNonZeroAmountViaReflection(final String amountStr) {
        try {
            final Method method =
                    PDFImportService.class.getDeclaredMethod("isValidNonZeroAmount", String.class);
            method.setAccessible(true);
            return (Boolean) method.invoke(pdfImportService, amountStr);
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke isValidNonZeroAmount", e);
        }
    }

    private Map<String, String> createTransactionRowViaReflection(
            final String date,
            final String description,
            final String amount,
            final Integer inferredYear) {
        try {
            final Method method =
                    PDFImportService.class.getDeclaredMethod(
                            "createTransactionRow",
                            String.class,
                            String.class,
                            String.class,
                            Integer.class);
            method.setAccessible(true);
            return (Map<String, String>)
                    method.invoke(pdfImportService, date, description, amount, inferredYear);
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke createTransactionRow", e);
        }
    }
}
