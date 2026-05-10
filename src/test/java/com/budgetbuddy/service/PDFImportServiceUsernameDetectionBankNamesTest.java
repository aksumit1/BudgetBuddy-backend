package com.budgetbuddy.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.lang.reflect.Method;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for rejecting bank names and header words (News, Summary, Rewards) in username detection
 */
// Test methods declare `throws Exception` for setup convenience —
// JUnit idiom; the rule is a noise generator on test classes.
@SuppressFBWarnings(
        value = "THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION",
        justification = "JUnit idiom — test methods accept any setup exception")
class PDFImportServiceUsernameDetectionBankNamesTest {

    private PDFImportService pdfImportService;
    private Method isValidNameFormat;

    @BeforeEach
    void setUp() throws Exception {
        // Use reflection to create PDFImportService instance - we only need it to access
        // isValidNameFormat method
        // The actual constructor requires many dependencies, but we're only testing a private
        // method
        pdfImportService =
                new PDFImportService(
                        null, // accountDetectionService
                        null, // importCategoryParser
                        null, // enhancedPatternMatcher
                        null // rewardExtractor
                        );
        isValidNameFormat =
                PDFImportService.class.getDeclaredMethod("isValidNameFormat", String.class);
        isValidNameFormat.setAccessible(true);
    }

    // ========== Bank Names ==========

    @Test
    @DisplayName("Should reject 'Wells Fargo Rewards Summary' as username")
    void testRejectWellsFargoRewardsSummary() throws Exception {
        final String candidate = "Wells Fargo Rewards Summary";
        final boolean result = (Boolean) isValidNameFormat.invoke(pdfImportService, candidate);
        assertFalse(
                result,
                "Should reject 'Wells Fargo Rewards Summary' as it contains bank name and header words");
    }

    @Test
    @DisplayName("Should reject 'Wells Fargo News' as username")
    void testRejectWellsFargoNews() throws Exception {
        final String candidate = "Wells Fargo News";
        final boolean result = (Boolean) isValidNameFormat.invoke(pdfImportService, candidate);
        assertFalse(result, "Should reject 'Wells Fargo News' as it contains bank name and 'News'");
    }

    @Test
    @DisplayName("Should reject 'Chase Rewards Summary' as username")
    void testRejectChaseRewardsSummary() throws Exception {
        final String candidate = "Chase Rewards Summary";
        final boolean result = (Boolean) isValidNameFormat.invoke(pdfImportService, candidate);
        assertFalse(
                result,
                "Should reject 'Chase Rewards Summary' as it contains bank name and header words");
    }

    @Test
    @DisplayName("Should reject 'Bank of America News' as username")
    void testRejectBankOfAmericaNews() throws Exception {
        final String candidate = "Bank of America News";
        final boolean result = (Boolean) isValidNameFormat.invoke(pdfImportService, candidate);
        assertFalse(
                result, "Should reject 'Bank of America News' as it contains bank name and 'News'");
    }

    @Test
    @DisplayName("Should reject standalone 'News' as username")
    void testRejectStandaloneNews() throws Exception {
        final String candidate = "News";
        final boolean result = (Boolean) isValidNameFormat.invoke(pdfImportService, candidate);
        assertFalse(result, "Should reject standalone 'News' as it's a header word");
    }

    @Test
    @DisplayName("Should reject standalone 'Summary' as username")
    void testRejectStandaloneSummary() throws Exception {
        final String candidate = "Summary";
        final boolean result = (Boolean) isValidNameFormat.invoke(pdfImportService, candidate);
        assertFalse(result, "Should reject standalone 'Summary' as it's a header word");
    }

    @Test
    @DisplayName("Should reject standalone 'Rewards' as username")
    void testRejectStandaloneRewards() throws Exception {
        final String candidate = "Rewards";
        final boolean result = (Boolean) isValidNameFormat.invoke(pdfImportService, candidate);
        assertFalse(result, "Should reject standalone 'Rewards' as it's a header word");
    }

    // ========== Valid Names (should still pass) ==========

    @Test
    @DisplayName("Should accept valid name 'John Doe'")
    void testAcceptValidName() throws Exception {
        final String candidate = "John Doe";
        final boolean result = (Boolean) isValidNameFormat.invoke(pdfImportService, candidate);
        assertTrue(result, "Should accept valid name 'John Doe'");
    }

    @Test
    @DisplayName("Should accept valid name 'Mary-Jane Smith'")
    void testAcceptHyphenatedName() throws Exception {
        final String candidate = "Mary-Jane Smith";
        final boolean result = (Boolean) isValidNameFormat.invoke(pdfImportService, candidate);
        assertTrue(result, "Should accept valid hyphenated name 'Mary-Jane Smith'");
    }
}
