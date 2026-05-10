package com.budgetbuddy.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.lang.reflect.Method;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for detecting standalone all-caps names before transactions (e.g., "TOM TRACKER", "ROGER
 * BRANDON") This tests the scenario where names appear on their own line right before transaction
 * lines
 */
// Test methods declare `throws Exception` for setup convenience —
// JUnit idiom; the rule is a noise generator on test classes.
@SuppressFBWarnings(
        value = "THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION",
        justification = "JUnit idiom — test methods accept any setup exception")
class PDFImportServiceUsernameDetectionStandaloneNamesTest {

    private PDFImportService pdfImportService;
    private Method detectUsernameBeforeHeader;
    private Method isValidNameFormat;

    @BeforeEach
    void setUp() throws Exception {
        // Create PDFImportService instance with null dependencies (we're only testing private
        // methods)
        pdfImportService =
                new PDFImportService(
                        null, // accountDetectionService
                        null, // importCategoryParser
                        null, // enhancedPatternMatcher
                        null // rewardExtractor
                        );
        detectUsernameBeforeHeader =
                PDFImportService.class.getDeclaredMethod(
                        "detectUsernameBeforeHeader",
                        String[].class,
                        int.class,
                        AccountDetectionService.DetectedAccount.class);
        detectUsernameBeforeHeader.setAccessible(true);
        isValidNameFormat =
                PDFImportService.class.getDeclaredMethod("isValidNameFormat", String.class);
        isValidNameFormat.setAccessible(true);
    }

    @Test
    @DisplayName("Should detect TOM TRACKER before transaction line")
    void testDetectTomTrackerBeforeTransaction() throws Exception {
        // Given: Lines with TOM TRACKER on its own line before a transaction
        final String[] lines = {
            "Date, Post,  Date Description Amount, Payments, Credits and Adjustments",
            "TOM TRACKER",
            "09/19 09/20 COSTCO WHSE #0110        ISSAQUAH     WA $7.70",
            "09/20 09/20 SHELL OIL 57444030803    KENT         WA $30.00"
        };

        // Transaction line is at index 2
        final int transactionIndex = 2;

        // When: Detect username before transaction
        final String result =
                (String)
                        detectUsernameBeforeHeader.invoke(
                                pdfImportService,
                                lines,
                                transactionIndex,
                                (AccountDetectionService.DetectedAccount) null);

        // Then: Should detect TOM TRACKER
        assertNotNull(result, "Should detect TOM TRACKER");
        assertEquals("TOM TRACKER", result, "Should return TOM TRACKER");
    }

    @Test
    @DisplayName("Should detect ROGER BRANDON before transaction line")
    void testDetectRogerBrandonBeforeTransaction() throws Exception {
        // Given: Lines with ROGER BRANDON on its own line before transactions
        final String[] lines = {
            "Date, Post,  Date Description Amount",
            "ROGER BRANDON",
            "09/19 09/20 COSTCO WHSE #0110        ISSAQUAH     WA $104.36",
            "09/19 09/20 COSTCO WHSE #0110        ISSAQUAH     WA $117.46"
        };

        // Transaction line is at index 2
        final int transactionIndex = 2;

        // When: Detect username before transaction
        final String result =
                (String)
                        detectUsernameBeforeHeader.invoke(
                                pdfImportService,
                                lines,
                                transactionIndex,
                                (AccountDetectionService.DetectedAccount) null);

        // Then: Should detect ROGER BRANDON
        assertNotNull(result, "Should detect ROGER BRANDON");
        assertEquals("ROGER BRANDON", result, "Should return ROGER BRANDON");
    }

    @Test
    @DisplayName("Should detect name 2 lines before transaction")
    void testDetectNameTwoLinesBeforeTransaction() throws Exception {
        // Given: Name appears 2 lines before transaction
        final String[] lines = {
            "Date Description Amount",
            "TOM TRACKER",
            "Promo Purchase-Offer 4 (9.990%)",
            "09/19 09/20 COSTCO WHSE #0110        ISSAQUAH     WA $7.70"
        };

        // Transaction line is at index 3
        final int transactionIndex = 3;

        // When: Detect username before transaction
        final String result =
                (String)
                        detectUsernameBeforeHeader.invoke(
                                pdfImportService,
                                lines,
                                transactionIndex,
                                (AccountDetectionService.DetectedAccount) null);

        // Then: Should detect TOM TRACKER
        assertNotNull(result, "Should detect TOM TRACKER");
        assertEquals("TOM TRACKER", result, "Should return TOM TRACKER");
    }

    @Test
    @DisplayName("Should validate TOM TRACKER as valid name format")
    void testIsValidNameFormatTomTracker() throws Exception {
        // When
        final boolean result = (Boolean) isValidNameFormat.invoke(pdfImportService, "TOM TRACKER");

        // Then
        assertTrue(result, "TOM TRACKER should be valid name format");
    }

    @Test
    @DisplayName("Should validate ROGER BRANDON as valid name format")
    void testIsValidNameFormatRogerBrandon() throws Exception {
        // When
        final boolean result =
                (Boolean) isValidNameFormat.invoke(pdfImportService, "ROGER BRANDON");

        // Then
        assertTrue(result, "ROGER BRANDON should be valid name format");
    }

    @Test
    @DisplayName("Should detect name when it appears with promo text on next line")
    void testDetectNameWithPromoTextOnNextLine() throws Exception {
        // Given: Name followed by promo text, then transaction
        final String[] lines = {
            "Date Description Amount",
            "TOM TRACKER",
            "Promo Purchase-Offer 4 (9.990%)",
            "09/19 09/20 COSTCO WHSE #0110        ISSAQUAH     WA $7.70"
        };

        // Transaction line is at index 3
        final int transactionIndex = 3;

        // When: Detect username before transaction
        final String result =
                (String)
                        detectUsernameBeforeHeader.invoke(
                                pdfImportService,
                                lines,
                                transactionIndex,
                                (AccountDetectionService.DetectedAccount) null);

        // Then: Should detect TOM TRACKER (promo text should not prevent detection)
        assertNotNull(result, "Should detect TOM TRACKER even with promo text on next line");
        assertEquals("TOM TRACKER", result, "Should return TOM TRACKER");
    }

    @Test
    @DisplayName("Should prefer closest name when multiple names appear")
    void testDetectMultipleNamesPrefersClosest() throws Exception {
        // Given: Multiple names before transaction
        final String[] lines = {
            "Date Description Amount",
            "TOM TRACKER",
            "ROGER BRANDON",
            "09/19 09/20 COSTCO WHSE #0110        ISSAQUAH     WA $104.36"
        };

        // Transaction line is at index 3
        final int transactionIndex = 3;

        // When: Detect username before transaction
        final String result =
                (String)
                        detectUsernameBeforeHeader.invoke(
                                pdfImportService,
                                lines,
                                transactionIndex,
                                (AccountDetectionService.DetectedAccount) null);

        // Then: Should detect ROGER BRANDON (closest to transaction)
        assertNotNull(result, "Should detect a name");
        // The implementation returns the first candidate found (from end to start), so ROGER
        // BRANDON should be detected
        assertEquals(
                "ROGER BRANDON", result, "Should return ROGER BRANDON (closest to transaction)");
    }
}
