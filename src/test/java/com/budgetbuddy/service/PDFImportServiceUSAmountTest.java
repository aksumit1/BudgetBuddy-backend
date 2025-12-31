package com.budgetbuddy.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for US amount format support: CR/DR, +/-, and parentheses for negatives
 * Uses reflection to test private parseUSAmount method
 */
class PDFImportServiceUSAmountTest {

    private PDFImportService pdfImportService;
    private EnhancedPatternMatcher enhancedPatternMatcher;
    private AccountDetectionService accountDetectionService;
    private ImportCategoryParser importCategoryParser;
    private TransactionTypeCategoryService transactionTypeCategoryService;
    private Method parseUSAmountMethod;

    @BeforeEach
    void setUp() throws Exception {
        accountDetectionService = Mockito.mock(AccountDetectionService.class);
        importCategoryParser = Mockito.mock(ImportCategoryParser.class);
        transactionTypeCategoryService = Mockito.mock(TransactionTypeCategoryService.class);
        enhancedPatternMatcher = new EnhancedPatternMatcher();
        pdfImportService = new PDFImportService(accountDetectionService, importCategoryParser, transactionTypeCategoryService, enhancedPatternMatcher, null);
        
        // Use reflection to access private parseUSAmount method
        parseUSAmountMethod = PDFImportService.class.getDeclaredMethod("parseUSAmount", String.class);
        parseUSAmountMethod.setAccessible(true);
    }
    
    private BigDecimal parseUSAmount(String amountStr) {
        try {
            return (BigDecimal) parseUSAmountMethod.invoke(pdfImportService, amountStr);
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke parseUSAmount", e);
        }
    }

    /**
     * Test parseUSAmount directly with various formats
     */
    @Test
    void testParseUSAmount_StandardAmount() {
        BigDecimal result = parseUSAmount("$458.40");
        assertNotNull(result);
        assertEquals(new BigDecimal("458.40"), result);
    }

    @Test
    void testParseUSAmount_NegativeSign() {
        BigDecimal result = parseUSAmount("-$458.40");
        assertNotNull(result);
        assertEquals(new BigDecimal("-458.40"), result);
    }

    @Test
    void testParseUSAmount_PositiveSign() {
        BigDecimal result = parseUSAmount("+$1,234.56");
        assertNotNull(result);
        assertEquals(new BigDecimal("1234.56"), result);
    }

    @Test
    void testParseUSAmount_ParenthesesNegative() {
        BigDecimal result = parseUSAmount("($458.40)");
        assertNotNull(result);
        assertEquals(new BigDecimal("-458.40"), result);
    }

    @Test
    void testParseUSAmount_CreditIndicator() {
        BigDecimal result = parseUSAmount("$1,234.56 CR");
        assertNotNull(result);
        assertEquals(new BigDecimal("1234.56"), result);
    }

    @Test
    void testParseUSAmount_DebitIndicator() {
        BigDecimal result = parseUSAmount("$458.40 DR");
        assertNotNull(result);
        assertEquals(new BigDecimal("-458.40"), result);
    }

    @Test
    void testParseUSAmount_ParenthesesWithCR() {
        // Parentheses take precedence - should be negative
        BigDecimal result = parseUSAmount("($123.45) CR");
        assertNotNull(result);
        assertEquals(new BigDecimal("-123.45"), result);
    }

    @Test
    void testParseUSAmount_BalanceForward() {
        BigDecimal result = parseUSAmount("$5,432.10 BF");
        assertNotNull(result);
        assertEquals(new BigDecimal("5432.10"), result);
    }

    @Test
    void testParseUSAmount_NoDollarSign() {
        BigDecimal result = parseUSAmount("123.45");
        assertNotNull(result);
        assertEquals(new BigDecimal("123.45"), result);
    }

    @Test
    void testParseUSAmount_NoDollarSignWithCR() {
        BigDecimal result = parseUSAmount("123.45 CR");
        assertNotNull(result);
        assertEquals(new BigDecimal("123.45"), result);
    }

    @Test
    void testParseUSAmount_NoDollarSignWithDR() {
        BigDecimal result = parseUSAmount("123.45 DR");
        assertNotNull(result);
        assertEquals(new BigDecimal("-123.45"), result);
    }

    @Test
    void testParseUSAmount_ParenthesesNoDollarSign() {
        BigDecimal result = parseUSAmount("(123.45)");
        assertNotNull(result);
        assertEquals(new BigDecimal("-123.45"), result);
    }

    @Test
    void testParseUSAmount_VeryLargeAmount() {
        BigDecimal result = parseUSAmount("$999,999.99");
        assertNotNull(result);
        assertEquals(new BigDecimal("999999.99"), result);
    }

    @Test
    void testParseUSAmount_ZeroAmount() {
        BigDecimal result = parseUSAmount("$0.00");
        assertNotNull(result);
        assertEquals(0, result.compareTo(BigDecimal.ZERO), "Should equal zero");
    }

    @Test
    void testParseUSAmount_InvalidFormat() {
        BigDecimal result = parseUSAmount("invalid");
        assertNull(result, "Should return null for invalid format");
    }

    @Test
    void testParseUSAmount_NullInput() {
        BigDecimal result = parseUSAmount(null);
        assertNull(result, "Should return null for null input");
    }

    @Test
    void testParseUSAmount_EmptyString() {
        BigDecimal result = parseUSAmount("");
        assertNull(result, "Should return null for empty string");
    }

    @Test
    void testParseUSAmount_MixedFormats() {
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
    void testParseUSAmount_WhitespaceVariations() {
        assertEquals(new BigDecimal("100.00"), parseUSAmount("  $100.00  "));
        assertEquals(new BigDecimal("100.00"), parseUSAmount("$100.00 CR "));
        assertEquals(new BigDecimal("-100.00"), parseUSAmount(" ($100.00) "));
        assertEquals(new BigDecimal("-100.00"), parseUSAmount("($100.00) DR"));
    }

    @Test
    void testParseUSAmount_MultipleCommas() {
        assertEquals(new BigDecimal("1234567.89"), parseUSAmount("$1,234,567.89"));
        assertEquals(new BigDecimal("-1234567.89"), parseUSAmount("-$1,234,567.89"));
        assertEquals(new BigDecimal("1234567.89"), parseUSAmount("$1,234,567.89 CR"));
    }

    @Test
    void testParseUSAmount_SmallAmounts() {
        assertEquals(new BigDecimal("0.01"), parseUSAmount("$0.01"));
        assertEquals(new BigDecimal("-0.01"), parseUSAmount("-$0.01"));
        assertEquals(new BigDecimal("0.01"), parseUSAmount("$0.01 CR"));
        assertEquals(new BigDecimal("-0.01"), parseUSAmount("$0.01 DR"));
    }

    @Test
    void testParseUSAmount_NoDecimalPlaces() {
        assertEquals(new BigDecimal("100"), parseUSAmount("$100"));
        assertEquals(new BigDecimal("-100"), parseUSAmount("-$100"));
        assertEquals(new BigDecimal("100"), parseUSAmount("$100 CR"));
    }

    @Test
    void testParseUSAmount_SingleDecimalDigit() {
        assertEquals(new BigDecimal("100.5"), parseUSAmount("$100.5"));
        assertEquals(new BigDecimal("-100.5"), parseUSAmount("-$100.5"));
    }

    @Test
    void testParseUSAmount_ParenthesesWithDR() {
        BigDecimal result = parseUSAmount("($123.45) DR");
        assertNotNull(result);
        // DR takes precedence over parentheses
        assertEquals(new BigDecimal("-123.45"), result);
    }

    @Test
    void testParseUSAmount_ParenthesesWithBF() {
        BigDecimal result = parseUSAmount("($123.45) BF");
        assertNotNull(result);
        // Parentheses take precedence over BF
        assertEquals(new BigDecimal("-123.45"), result);
    }

    @Test
    void testParseUSAmount_NegativeWithCR() {
        // Negative sign with CR - CR should make it positive
        BigDecimal result = parseUSAmount("-$123.45 CR");
        assertNotNull(result);
        // CR takes precedence over negative sign
        assertEquals(new BigDecimal("123.45"), result);
    }

    @Test
    void testParseUSAmount_PositiveWithDR() {
        // Positive sign with DR - DR should make it negative
        BigDecimal result = parseUSAmount("+$123.45 DR");
        assertNotNull(result);
        // DR takes precedence over positive sign
        assertEquals(new BigDecimal("-123.45"), result);
    }

    @Test
    void testParseUSAmount_OnlyDollarSign() {
        BigDecimal result = parseUSAmount("$");
        assertNull(result, "Should return null for only dollar sign");
    }

    @Test
    void testParseUSAmount_OnlyParentheses() {
        BigDecimal result = parseUSAmount("()");
        assertNull(result, "Should return null for empty parentheses");
    }

    @Test
    void testParseUSAmount_OnlyCR() {
        BigDecimal result = parseUSAmount("CR");
        assertNull(result, "Should return null for only CR");
    }

    @Test
    void testParseUSAmount_InvalidCharacters() {
        assertNull(parseUSAmount("$abc.def"), "Should reject non-numeric characters");
        assertNull(parseUSAmount("$123.45.67"), "Should reject multiple decimal points");
        // Note: "$123,45" with comma as decimal separator is European format, not US
        // For US format, comma is thousands separator, so "$123,45" would be parsed as "12345"
        // This is actually valid in some contexts, so we'll allow it but it's not standard US format
        BigDecimal result = parseUSAmount("$123,45");
        // This might parse as 12345 (comma removed) or fail - both are acceptable
        // The key is it shouldn't crash
    }

    @Test
    void testParseUSAmount_WhitespaceOnly() {
        assertNull(parseUSAmount("   "));
        assertNull(parseUSAmount("\t"));
        assertNull(parseUSAmount("\n"));
    }

    @Test
    void testParseUSAmount_AmountWithSpaces() {
        // Spaces are removed during parsing, so these should work
        BigDecimal result1 = parseUSAmount("$ 123.45");
        assertNotNull(result1, "Should handle space after $");
        assertEquals(new BigDecimal("123.45"), result1);
        
        // Space in middle of number is invalid, but we'll try to parse what we can
        BigDecimal result2 = parseUSAmount("$123 .45");
        // This might fail or parse differently - let's just check it doesn't crash
        // The actual behavior depends on how BigDecimal handles it
    }

    @Test
    void testParseUSAmount_VerySmallAmount() {
        BigDecimal result = parseUSAmount("$0.001");
        assertNotNull(result);
        assertEquals(new BigDecimal("0.001"), result);
    }

    @Test
    void testParseUSAmount_MaximumReasonableAmount() {
        BigDecimal result = parseUSAmount("$999,999,999.99");
        assertNotNull(result);
        assertEquals(new BigDecimal("999999999.99"), result);
    }

    // ========== Integration Tests with Pattern Matching ==========

    @Test
    void testPattern1_WithCR() {
        String line = "11/09     DEPOSIT RECEIVED $1,234.56 CR";
        Map<String, String> result = parsePattern1ViaReflection(line, 2024);
        assertNotNull(result, "Should parse Pattern 1 with CR");
        assertEquals("11/09", result.get("date"));
        assertTrue(result.get("description").contains("DEPOSIT"));
        assertEquals("$1,234.56 CR", result.get("amount"));
    }

    @Test
    void testPattern1_WithDR() {
        String line = "11/09     PAYMENT MADE $458.40 DR";
        Map<String, String> result = parsePattern1ViaReflection(line, 2024);
        assertNotNull(result, "Should parse Pattern 1 with DR");
        assertEquals("$458.40 DR", result.get("amount"));
    }

    @Test
    void testPattern1_WithParentheses() {
        String line = "11/09     PAYMENT MADE ($458.40)";
        Map<String, String> result = parsePattern1ViaReflection(line, 2024);
        assertNotNull(result, "Should parse Pattern 1 with parentheses");
        assertEquals("($458.40)", result.get("amount"));
    }

    @Test
    void testPattern1_WithPositiveSign() {
        String line = "11/09     DEPOSIT +$1,234.56";
        Map<String, String> result = parsePattern1ViaReflection(line, 2024);
        assertNotNull(result, "Should parse Pattern 1 with + sign");
        assertEquals("+$1,234.56", result.get("amount"));
    }

    @Test
    void testPattern2_WithCR() {
        String line = "Prefix text 10/12 MERCHANT $25.00 CR";
        Map<String, String> result = parsePattern2ViaReflection(line, 2024);
        assertNotNull(result, "Should parse Pattern 2 with CR");
        assertEquals("$25.00 CR", result.get("amount"));
    }

    @Test
    void testPattern5_WithParentheses() {
        String line = "10/08 10/08 DOLLAR TREE TUKWILA WA ($19.84)";
        Map<String, String> result = parsePattern5ViaReflection(line, 2024);
        assertNotNull(result, "Should parse Pattern 5 with parentheses");
        assertEquals("($19.84)", result.get("amount"));
    }

    @Test
    void testPattern5_WithCR() {
        String line = "10/08 10/08 DOLLAR TREE TUKWILA WA $19.84 CR";
        Map<String, String> result = parsePattern5ViaReflection(line, 2024);
        assertNotNull(result, "Should parse Pattern 5 with CR");
        assertEquals("$19.84 CR", result.get("amount"));
    }

    @Test
    void testPattern7_WithDR() {
        String[] lines = {
            "11/27/25* AGARWAL SUMIT KUMAR PAYMENT MADE",
            "JPMorgan Chase Bank, NA",
            "$1,957.91 DR"
        };
        Map<String, String> result = parsePattern7ViaReflection(lines, 0, 2025);
        assertNotNull(result, "Should parse Pattern 7 with DR");
        assertEquals("$1,957.91 DR", result.get("amount"));
    }

    @Test
    void testPattern7_WithParentheses() {
        String[] lines = {
            "11/27/25* AGARWAL SUMIT KUMAR PAYMENT MADE",
            "JPMorgan Chase Bank, NA",
            "($1,957.91)"
        };
        Map<String, String> result = parsePattern7ViaReflection(lines, 0, 2025);
        assertNotNull(result, "Should parse Pattern 7 with parentheses");
        assertEquals("($1,957.91)", result.get("amount"));
    }

    /**
     * Test Pattern 7 variant where merchant name appears before date line (Amex format)
     * Format:
     * Line 1: Merchant name
     * Line 2: Card info
     * Line 3: "Amount" header
     * Line 4: Date + description
     * Line 5: Description continuation
     * Line 6: Amount
     * 
     * Current implementation: Pattern 7 CAN detect this if we start from line 4 (date line),
     * but it will NOT capture the merchant name from line 1.
     */
    @Test
    void testPattern7_AmexVariant_WithMerchantBeforeDate() {
        String[] lines = {
            "HUMPTY DUMPTY WALLFLOWER",
            "Card Ending 7-31034   Monthly Spending Limit: $2,500",
            "Amount",
            "12/07/25 D J*BARRONS 800-544-0422 NJ",
            "SUBSRIPTION",
            "$4.41 ⧫"
        };
        
        // Try starting from line 4 (the date line, index 3) - current Pattern 7 expects date on line 1
        Map<String, String> result = parsePattern7ViaReflection(lines, 3, 2025);
        
        // Current implementation works if we start from the date line (index 3)
        // Line 4 (index 3): "12/07/25 D J*BARRONS 800-544-0422 NJ" (date + description)
        // Line 5 (index 4): "SUBSRIPTION" (would be treated as merchant/description continuation)
        // Line 6 (index 5): "$4.41 ⧫" (amount)
        assertNotNull(result, "Pattern 7 should detect this format when starting from date line");
        assertEquals("12/07/25", result.get("date"), "Date should be extracted correctly");
        assertTrue(result.get("description").contains("D J*BARRONS") || result.get("description").contains("SUBSRIPTION"),
                   "Description should contain transaction details");
        assertEquals("$4.41", result.get("amount"), "Amount should be extracted correctly");
        
        // Note: Merchant name "HUMPTY DUMPTY WALLFLOWER" is NOT captured in current implementation
        // This is a limitation - Pattern 7 would need enhancement to look backwards for merchant name
    }

    // ========== Validation Tests ==========

    @Test
    void testIsValidNonZeroAmount_ZeroAmount() {
        assertFalse(isValidNonZeroAmountViaReflection("$0.00"));
        assertFalse(isValidNonZeroAmountViaReflection("$0.00 CR"));
        assertFalse(isValidNonZeroAmountViaReflection("($0.00)"));
    }

    @Test
    void testIsValidNonZeroAmount_NearZeroAmount() {
        assertFalse(isValidNonZeroAmountViaReflection("$0.001"));
        assertFalse(isValidNonZeroAmountViaReflection("$0.009"));
        assertTrue(isValidNonZeroAmountViaReflection("$0.01"));
    }

    @Test
    void testIsValidNonZeroAmount_ValidAmounts() {
        assertTrue(isValidNonZeroAmountViaReflection("$0.01"));
        assertTrue(isValidNonZeroAmountViaReflection("$1.00"));
        assertTrue(isValidNonZeroAmountViaReflection("$100.00"));
        assertTrue(isValidNonZeroAmountViaReflection("-$100.00"));
        assertTrue(isValidNonZeroAmountViaReflection("($100.00)"));
        assertTrue(isValidNonZeroAmountViaReflection("$100.00 CR"));
        assertTrue(isValidNonZeroAmountViaReflection("$100.00 DR"));
    }

    @Test
    void testIsValidNonZeroAmount_InvalidAmounts() {
        assertFalse(isValidNonZeroAmountViaReflection(null));
        assertFalse(isValidNonZeroAmountViaReflection(""));
        assertFalse(isValidNonZeroAmountViaReflection("invalid"));
        assertFalse(isValidNonZeroAmountViaReflection("$"));
    }

    // ========== createTransactionRow Tests ==========

    @Test
    void testCreateTransactionRow_ValidInput() {
        Map<String, String> result = createTransactionRowViaReflection("11/09", "MERCHANT NAME", "$100.00", 2024);
        assertNotNull(result);
        assertEquals("11/09", result.get("date"));
        assertEquals("MERCHANT NAME", result.get("description"));
        assertEquals("$100.00", result.get("amount"));
        assertEquals("2024", result.get("_inferredYear"));
    }

    @Test
    void testCreateTransactionRow_WithCR() {
        Map<String, String> result = createTransactionRowViaReflection("11/09", "DEPOSIT", "$100.00 CR", 2024);
        assertNotNull(result);
        assertEquals("$100.00 CR", result.get("amount"));
    }

    @Test
    void testCreateTransactionRow_WithDR() {
        Map<String, String> result = createTransactionRowViaReflection("11/09", "PAYMENT", "$100.00 DR", 2024);
        assertNotNull(result);
        assertEquals("$100.00 DR", result.get("amount"));
    }

    @Test
    void testCreateTransactionRow_WithParentheses() {
        Map<String, String> result = createTransactionRowViaReflection("11/09", "PAYMENT", "($100.00)", 2024);
        assertNotNull(result);
        assertEquals("($100.00)", result.get("amount"));
    }

    @Test
    void testCreateTransactionRow_InvalidDate() {
        Map<String, String> result = createTransactionRowViaReflection(null, "MERCHANT", "$100.00", 2024);
        assertNull(result, "Should return null for invalid date");
    }

    @Test
    void testCreateTransactionRow_InvalidDescription() {
        Map<String, String> result = createTransactionRowViaReflection("11/09", "", "$100.00", 2024);
        assertNull(result, "Should return null for empty description");
        
        result = createTransactionRowViaReflection("11/09", "   ", "$100.00", 2024);
        assertNull(result, "Should return null for whitespace-only description");
    }

    @Test
    void testCreateTransactionRow_InvalidAmount() {
        Map<String, String> result = createTransactionRowViaReflection("11/09", "MERCHANT", "$0.00", 2024);
        assertNull(result, "Should return null for zero amount");
        
        result = createTransactionRowViaReflection("11/09", "MERCHANT", "invalid", 2024);
        assertNull(result, "Should return null for invalid amount");
    }

    @Test
    void testCreateTransactionRow_NoInferredYear() {
        Map<String, String> result = createTransactionRowViaReflection("11/09", "MERCHANT", "$100.00", null);
        assertNotNull(result);
        assertFalse(result.containsKey("_inferredYear"), "Should not have _inferredYear when null");
    }

    // ========== Helper Methods for Reflection ==========

    private Map<String, String> parsePattern1ViaReflection(String line, Integer inferredYear) {
        try {
            Method method = PDFImportService.class.getDeclaredMethod("parsePattern1", String.class, Integer.class);
            method.setAccessible(true);
            return (Map<String, String>) method.invoke(pdfImportService, line, inferredYear);
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke parsePattern1", e);
        }
    }

    private Map<String, String> parsePattern2ViaReflection(String line, Integer inferredYear) {
        try {
            Method method = PDFImportService.class.getDeclaredMethod("parsePattern2", String.class, Integer.class);
            method.setAccessible(true);
            return (Map<String, String>) method.invoke(pdfImportService, line, inferredYear);
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke parsePattern2", e);
        }
    }

    private Map<String, String> parsePattern5ViaReflection(String line, Integer inferredYear) {
        try {
            Method method = PDFImportService.class.getDeclaredMethod("parsePattern5", String.class, Integer.class);
            method.setAccessible(true);
            return (Map<String, String>) method.invoke(pdfImportService, line, inferredYear);
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke parsePattern5", e);
        }
    }

    private Map<String, String> parsePattern7ViaReflection(String[] lines, int startIndex, Integer inferredYear) {
        try {
            Method method = PDFImportService.class.getDeclaredMethod("parsePattern7", String[].class, int.class, Integer.class, String.class);
            method.setAccessible(true);
            return (Map<String, String>) method.invoke(pdfImportService, lines, startIndex, inferredYear, null);
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke parsePattern7", e);
        }
    }

    private boolean isValidNonZeroAmountViaReflection(String amountStr) {
        try {
            Method method = PDFImportService.class.getDeclaredMethod("isValidNonZeroAmount", String.class);
            method.setAccessible(true);
            return (Boolean) method.invoke(pdfImportService, amountStr);
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke isValidNonZeroAmount", e);
        }
    }

    private Map<String, String> createTransactionRowViaReflection(String date, String description, String amount, Integer inferredYear) {
        try {
            Method method = PDFImportService.class.getDeclaredMethod("createTransactionRow", String.class, String.class, String.class, Integer.class);
            method.setAccessible(true);
            return (Map<String, String>) method.invoke(pdfImportService, date, description, amount, inferredYear);
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke createTransactionRow", e);
        }
    }
}

