package com.budgetbuddy.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PDFImportService parsing logic
 * These tests focus on the core parsing methods and would have caught the bugs:
 * - Multi-word descriptions not being parsed correctly
 * - Negative amounts with $ sign (-$436.80)
 * - Column detection failures
 * 
 * Uses reflection to test private methods directly, avoiding PDF creation overhead
 */
@DisplayName("PDF Import Service Parsing Logic Tests")
class PDFImportServiceParsingLogicTest {

    private PDFImportService pdfImportService;

    @BeforeEach
    void setUp() {
        AccountDetectionService accountDetectionService = org.mockito.Mockito.mock(AccountDetectionService.class);
        ImportCategoryParser importCategoryParser = org.mockito.Mockito.mock(ImportCategoryParser.class);
        TransactionTypeCategoryService transactionTypeCategoryService = 
                org.mockito.Mockito.mock(TransactionTypeCategoryService.class);
        EnhancedPatternMatcher enhancedPatternMatcher = new EnhancedPatternMatcher();
        pdfImportService = new PDFImportService(accountDetectionService, importCategoryParser, transactionTypeCategoryService, enhancedPatternMatcher, null);
    }

    @Test
    @DisplayName("Should parse transactions with multi-word descriptions using smart column detection")
    void testSmartColumnDetection_MultiWordDescriptions_ParsesCorrectly() throws Exception {
        // This test case would have caught the bug where "SAFEWAY #1444 BELLEVUE WA" 
        // was being split incorrectly
        String line = "11/25 11/25 SAFEWAY #1444 BELLEVUE WA $14.27";
        
        // Use reflection to test the private method
        Method method = PDFImportService.class.getDeclaredMethod(
            "extractRowWithSmartColumnDetection", 
            String.class, 
            List.class, 
            Integer.class
        );
        method.setAccessible(true);
        
        List<String> headers = List.of("date", "date", "description", "amount");
        @SuppressWarnings("unchecked")
        Map<String, String> row = (Map<String, String>) method.invoke(
            pdfImportService, 
            line, 
            headers, 
            2025
        );
        
        assertNotNull(row, "Should extract row");
        assertEquals("11/25", row.get("date"), "Should extract first date");
        assertEquals("$14.27", row.get("amount"), "Should extract amount at end");
        assertTrue(row.get("description").contains("SAFEWAY"), "Should extract full description");
        assertTrue(row.get("description").contains("BELLEVUE WA"), "Should preserve multi-word description");
        assertEquals("SAFEWAY #1444 BELLEVUE WA", row.get("description"), 
                     "Should extract complete description between date and amount");
    }

    @Test
    @DisplayName("Should parse negative amounts with dollar sign (-$436.80)")
    void testSmartColumnDetection_NegativeAmountWithDollarSign_ParsesCorrectly() throws Exception {
        String line = "11/28 11/28 AUTOPAY 999990000012756RAUTOPAY AUTO-PMT -$436.80";
        
        Method method = PDFImportService.class.getDeclaredMethod(
            "extractRowWithSmartColumnDetection", 
            String.class, 
            List.class, 
            Integer.class
        );
        method.setAccessible(true);
        
        List<String> headers = List.of("date", "date", "description", "amount");
        @SuppressWarnings("unchecked")
        Map<String, String> row = (Map<String, String>) method.invoke(
            pdfImportService, 
            line, 
            headers, 
            2025
        );
        
        assertNotNull(row, "Should extract row");
        assertEquals("-$436.80", row.get("amount"), "Should extract negative amount with dollar sign");
        
        // Test amount parsing
        Method parseAmountMethod = PDFImportService.class.getDeclaredMethod("parseAmount", String.class);
        parseAmountMethod.setAccessible(true);
        BigDecimal amount = (BigDecimal) parseAmountMethod.invoke(pdfImportService, "-$436.80");
        
        assertNotNull(amount, "Should parse negative amount");
        assertTrue(amount.compareTo(BigDecimal.ZERO) < 0, "Amount should be negative");
        assertEquals(0, amount.compareTo(new BigDecimal("-436.80")), "Amount should be -436.80");
    }

    @Test
    @DisplayName("Should handle descriptions with special characters")
    void testSmartColumnDetection_SpecialCharacters_ParsesCorrectly() throws Exception {
        String line = "12/01 12/01 CURSOR, AI POWERED IDE NEW YORK NY $66.12";
        
        Method method = PDFImportService.class.getDeclaredMethod(
            "extractRowWithSmartColumnDetection", 
            String.class, 
            List.class, 
            Integer.class
        );
        method.setAccessible(true);
        
        List<String> headers = List.of("date", "date", "description", "amount");
        @SuppressWarnings("unchecked")
        Map<String, String> row = (Map<String, String>) method.invoke(
            pdfImportService, 
            line, 
            headers, 
            2025
        );
        
        assertNotNull(row, "Should extract row");
        assertTrue(row.get("description").contains(","), "Should preserve comma in description");
        assertEquals("CURSOR, AI POWERED IDE NEW YORK NY", row.get("description"), 
                     "Should extract full description with special characters");
    }

    @Test
    @DisplayName("Should handle descriptions with apostrophes and asterisks")
    void testSmartColumnDetection_ApostrophesAndAsterisks_ParsesCorrectly() throws Exception {
        String line = "12/02 12/02 SQ *JAYAM'S TIFFINS AN Bellevue WA $14.48";
        
        Method method = PDFImportService.class.getDeclaredMethod(
            "extractRowWithSmartColumnDetection", 
            String.class, 
            List.class, 
            Integer.class
        );
        method.setAccessible(true);
        
        List<String> headers = List.of("date", "date", "description", "amount");
        @SuppressWarnings("unchecked")
        Map<String, String> row = (Map<String, String>) method.invoke(
            pdfImportService, 
            line, 
            headers, 
            2025
        );
        
        assertNotNull(row, "Should extract row");
        assertTrue(row.get("description").contains("*"), "Should preserve asterisk");
        assertTrue(row.get("description").contains("'"), "Should preserve apostrophe");
        assertEquals("SQ *JAYAM'S TIFFINS AN Bellevue WA", row.get("description"));
    }

    @Test
    @DisplayName("Should handle descriptions with hash symbols and numbers")
    void testSmartColumnDetection_HashSymbols_ParsesCorrectly() throws Exception {
        String line = "12/03 12/03 THE HOME DEPOT #4723 REDMOND WA $5.81";
        
        Method method = PDFImportService.class.getDeclaredMethod(
            "extractRowWithSmartColumnDetection", 
            String.class, 
            List.class, 
            Integer.class
        );
        method.setAccessible(true);
        
        List<String> headers = List.of("date", "date", "description", "amount");
        @SuppressWarnings("unchecked")
        Map<String, String> row = (Map<String, String>) method.invoke(
            pdfImportService, 
            line, 
            headers, 
            2025
        );
        
        assertNotNull(row, "Should extract row");
        assertTrue(row.get("description").contains("#"), "Should preserve hash symbol");
        assertTrue(row.get("description").contains("4723"), "Should preserve numbers in description");
        assertEquals("$5.81", row.get("amount"), "Should extract amount, not description numbers");
        assertEquals("THE HOME DEPOT #4723 REDMOND WA", row.get("description"));
    }

    @Test
    @DisplayName("Should parse various amount formats correctly")
    void testParseAmount_VariousFormats_ParsesCorrectly() throws Exception {
        Method parseAmountMethod = PDFImportService.class.getDeclaredMethod("parseAmount", String.class);
        parseAmountMethod.setAccessible(true);
        
        // Test positive with dollar
        BigDecimal pos1 = (BigDecimal) parseAmountMethod.invoke(pdfImportService, "$14.27");
        assertEquals(0, pos1.compareTo(new BigDecimal("14.27")));
        
        // Test negative with dollar and minus
        BigDecimal neg1 = (BigDecimal) parseAmountMethod.invoke(pdfImportService, "-$436.80");
        assertEquals(0, neg1.compareTo(new BigDecimal("-436.80")));
        assertTrue(neg1.compareTo(BigDecimal.ZERO) < 0);
        
        // Test negative with parentheses
        BigDecimal neg2 = (BigDecimal) parseAmountMethod.invoke(pdfImportService, "($123.45)");
        assertEquals(0, neg2.compareTo(new BigDecimal("-123.45")));
        assertTrue(neg2.compareTo(BigDecimal.ZERO) < 0);
        
        // Test with commas
        BigDecimal withCommas = (BigDecimal) parseAmountMethod.invoke(pdfImportService, "$1,234.56");
        assertEquals(0, withCommas.compareTo(new BigDecimal("1234.56")));
        
        // Test without dollar sign
        BigDecimal noDollar = (BigDecimal) parseAmountMethod.invoke(pdfImportService, "99.99");
        assertEquals(0, noDollar.compareTo(new BigDecimal("99.99")));
    }

    @Test
    @DisplayName("Should handle multiple date columns correctly")
    void testSmartColumnDetection_MultipleDates_UsesFirstDate() throws Exception {
        // Format: Trans Date | Post Date | Description | Amount
        String line = "11/03 11/05 STARBUCKS STORE 00331 TUKWILA WA $13.02";
        
        Method method = PDFImportService.class.getDeclaredMethod(
            "extractRowWithSmartColumnDetection", 
            String.class, 
            List.class, 
            Integer.class
        );
        method.setAccessible(true);
        
        List<String> headers = List.of("date", "date", "description", "amount");
        @SuppressWarnings("unchecked")
        Map<String, String> row = (Map<String, String>) method.invoke(
            pdfImportService, 
            line, 
            headers, 
            2025
        );
        
        assertNotNull(row, "Should extract row");
        assertEquals("11/03", row.get("date"), "Should use first date (transaction date)");
        assertEquals("$13.02", row.get("amount"), "Should extract amount");
        assertTrue(row.get("description").contains("STARBUCKS"), "Should extract description");
    }

    @Test
    @DisplayName("Should handle amounts at different positions")
    void testSmartColumnDetection_AmountAtEnd_ParsesCorrectly() throws Exception {
        // Test that amount is correctly identified when at end of line
        String line1 = "11/25 11/25 SAFEWAY #1444 BELLEVUE WA $14.27";
        String line2 = "11/26 11/26 PCC - ISSAQUAH ISSAQUAH WA $37.25";
        
        Method method = PDFImportService.class.getDeclaredMethod(
            "extractRowWithSmartColumnDetection", 
            String.class, 
            List.class, 
            Integer.class
        );
        method.setAccessible(true);
        
        List<String> headers = List.of("date", "date", "description", "amount");
        
        @SuppressWarnings("unchecked")
        Map<String, String> row1 = (Map<String, String>) method.invoke(
            pdfImportService, line1, headers, 2025
        );
        
        @SuppressWarnings("unchecked")
        Map<String, String> row2 = (Map<String, String>) method.invoke(
            pdfImportService, line2, headers, 2025
        );
        
        assertNotNull(row1);
        assertNotNull(row2);
        assertEquals("$14.27", row1.get("amount"));
        assertEquals("$37.25", row2.get("amount"));
        
        // Verify descriptions don't contain amounts
        assertFalse(row1.get("description").contains("14.27"));
        assertFalse(row2.get("description").contains("37.25"));
    }

    @Test
    @DisplayName("Should handle fallback when no date/amount pattern found")
    void testSmartColumnDetection_FallbackToSimpleExtraction() throws Exception {
        // Line without clear date/amount pattern should fall back to simple column extraction
        String line = "Some random text without date or amount";
        
        Method method = PDFImportService.class.getDeclaredMethod(
            "extractRowWithSmartColumnDetection", 
            String.class, 
            List.class, 
            Integer.class
        );
        method.setAccessible(true);
        
        List<String> headers = List.of("col1", "col2", "col3");
        @SuppressWarnings("unchecked")
        Map<String, String> row = (Map<String, String>) method.invoke(
            pdfImportService, 
            line, 
            headers, 
            2025
        );
        
        // Should return empty or fallback row, not null
        assertNotNull(row, "Should return a row (possibly empty)");
    }

    @Test
    @DisplayName("Should parse Citi bank PDF format correctly")
    void testSmartColumnDetection_CitiBankFormat_ParsesCorrectly() throws Exception {
        // This is the exact format from Citi bank that was failing
        String line = "11/25 11/25 SAFEWAY #1444 BELLEVUE WA $14.27";
        
        Method method = PDFImportService.class.getDeclaredMethod(
            "extractRowWithSmartColumnDetection",
            String.class,
            List.class,
            Integer.class
        );
        method.setAccessible(true);
        
        List<String> headers = List.of("date", "date", "description", "amount");
        @SuppressWarnings("unchecked")
        Map<String, String> row = (Map<String, String>) method.invoke(
            pdfImportService,
            line,
            headers,
            2025
        );
        
        assertNotNull(row, "Should extract row from Citi format");
        assertEquals("11/25", row.get("date"), "Should extract first date");
        assertEquals("$14.27", row.get("amount"), "Should extract amount at end");
        assertEquals("SAFEWAY #1444 BELLEVUE WA", row.get("description"), 
                     "Should extract complete description between date and amount");
        
        // Test negative amount format
        String negativeLine = "11/28 11/28 AUTOPAY 999990000012756RAUTOPAY AUTO-PMT -$436.80";
        @SuppressWarnings("unchecked")
        Map<String, String> negativeRow = (Map<String, String>) method.invoke(
            pdfImportService,
            negativeLine,
            headers,
            2025
        );
        
        assertNotNull(negativeRow, "Should extract row with negative amount");
        assertEquals("-$436.80", negativeRow.get("amount"), "Should extract negative amount");
    }
}

