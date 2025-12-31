package com.budgetbuddy.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for rejecting bank names and header words (News, Summary, Rewards) in username detection
 */
class PDFImportServiceUsernameDetectionBankNamesTest {

    private PDFImportService pdfImportService;
    private Method isValidNameFormat;

    @BeforeEach
    void setUp() throws Exception {
        // Use reflection to create PDFImportService instance - we only need it to access isValidNameFormat method
        // The actual constructor requires many dependencies, but we're only testing a private method
        pdfImportService = new PDFImportService(
            null, // accountDetectionService
            null, // importCategoryParser
            null, // transactionTypeCategoryService
            null, // enhancedPatternMatcher
            null  // rewardExtractor
        );
        isValidNameFormat = PDFImportService.class.getDeclaredMethod("isValidNameFormat", String.class);
        isValidNameFormat.setAccessible(true);
    }

    // ========== Bank Names ==========

    @Test
    @DisplayName("Should reject 'Wells Fargo Rewards Summary' as username")
    void testReject_WellsFargoRewardsSummary() throws Exception {
        String candidate = "Wells Fargo Rewards Summary";
        boolean result = (Boolean) isValidNameFormat.invoke(pdfImportService, candidate);
        assertFalse(result, "Should reject 'Wells Fargo Rewards Summary' as it contains bank name and header words");
    }

    @Test
    @DisplayName("Should reject 'Wells Fargo News' as username")
    void testReject_WellsFargoNews() throws Exception {
        String candidate = "Wells Fargo News";
        boolean result = (Boolean) isValidNameFormat.invoke(pdfImportService, candidate);
        assertFalse(result, "Should reject 'Wells Fargo News' as it contains bank name and 'News'");
    }

    @Test
    @DisplayName("Should reject 'Chase Rewards Summary' as username")
    void testReject_ChaseRewardsSummary() throws Exception {
        String candidate = "Chase Rewards Summary";
        boolean result = (Boolean) isValidNameFormat.invoke(pdfImportService, candidate);
        assertFalse(result, "Should reject 'Chase Rewards Summary' as it contains bank name and header words");
    }

    @Test
    @DisplayName("Should reject 'Bank of America News' as username")
    void testReject_BankOfAmericaNews() throws Exception {
        String candidate = "Bank of America News";
        boolean result = (Boolean) isValidNameFormat.invoke(pdfImportService, candidate);
        assertFalse(result, "Should reject 'Bank of America News' as it contains bank name and 'News'");
    }

    @Test
    @DisplayName("Should reject standalone 'News' as username")
    void testReject_StandaloneNews() throws Exception {
        String candidate = "News";
        boolean result = (Boolean) isValidNameFormat.invoke(pdfImportService, candidate);
        assertFalse(result, "Should reject standalone 'News' as it's a header word");
    }

    @Test
    @DisplayName("Should reject standalone 'Summary' as username")
    void testReject_StandaloneSummary() throws Exception {
        String candidate = "Summary";
        boolean result = (Boolean) isValidNameFormat.invoke(pdfImportService, candidate);
        assertFalse(result, "Should reject standalone 'Summary' as it's a header word");
    }

    @Test
    @DisplayName("Should reject standalone 'Rewards' as username")
    void testReject_StandaloneRewards() throws Exception {
        String candidate = "Rewards";
        boolean result = (Boolean) isValidNameFormat.invoke(pdfImportService, candidate);
        assertFalse(result, "Should reject standalone 'Rewards' as it's a header word");
    }

    // ========== Valid Names (should still pass) ==========

    @Test
    @DisplayName("Should accept valid name 'John Doe'")
    void testAccept_ValidName() throws Exception {
        String candidate = "John Doe";
        boolean result = (Boolean) isValidNameFormat.invoke(pdfImportService, candidate);
        assertTrue(result, "Should accept valid name 'John Doe'");
    }

    @Test
    @DisplayName("Should accept valid name 'Mary-Jane Smith'")
    void testAccept_HyphenatedName() throws Exception {
        String candidate = "Mary-Jane Smith";
        boolean result = (Boolean) isValidNameFormat.invoke(pdfImportService, candidate);
        assertTrue(result, "Should accept valid hyphenated name 'Mary-Jane Smith'");
    }
}

