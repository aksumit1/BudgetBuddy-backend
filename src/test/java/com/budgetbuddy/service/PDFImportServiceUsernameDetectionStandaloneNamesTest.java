package com.budgetbuddy.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for detecting standalone all-caps names before transactions (e.g., "TOM TRACKER", "ROGER BRANDON")
 * This tests the scenario where names appear on their own line right before transaction lines
 */
class PDFImportServiceUsernameDetectionStandaloneNamesTest {

    private PDFImportService pdfImportService;
    private Method detectUsernameBeforeHeader;
    private Method isValidNameFormat;

    @BeforeEach
    void setUp() throws Exception {
        // Create PDFImportService instance with null dependencies (we're only testing private methods)
        pdfImportService = new PDFImportService(
            null, // accountDetectionService
            null, // importCategoryParser
            null, // transactionTypeCategoryService
            null, // enhancedPatternMatcher
            null  // rewardExtractor
        );
        detectUsernameBeforeHeader = PDFImportService.class.getDeclaredMethod(
            "detectUsernameBeforeHeader", String[].class, int.class, AccountDetectionService.DetectedAccount.class);
        detectUsernameBeforeHeader.setAccessible(true);
        isValidNameFormat = PDFImportService.class.getDeclaredMethod("isValidNameFormat", String.class);
        isValidNameFormat.setAccessible(true);
    }

    @Test
    @DisplayName("Should detect TOM TRACKER before transaction line")
    void testDetect_TomTracker_BeforeTransaction() throws Exception {
        // Given: Lines with TOM TRACKER on its own line before a transaction
        String[] lines = {
            "Date, Post,  Date Description Amount, Payments, Credits and Adjustments",
            "TOM TRACKER",
            "09/19 09/20 COSTCO WHSE #0110        ISSAQUAH     WA $7.70",
            "09/20 09/20 SHELL OIL 57444030803    KENT         WA $30.00"
        };
        
        // Transaction line is at index 2
        int transactionIndex = 2;
        
        // When: Detect username before transaction
        String result = (String) detectUsernameBeforeHeader.invoke(pdfImportService, lines, transactionIndex, (AccountDetectionService.DetectedAccount) null);
        
        // Then: Should detect TOM TRACKER
        assertNotNull(result, "Should detect TOM TRACKER");
        assertEquals("TOM TRACKER", result, "Should return TOM TRACKER");
    }

    @Test
    @DisplayName("Should detect ROGER BRANDON before transaction line")
    void testDetect_RogerBrandon_BeforeTransaction() throws Exception {
        // Given: Lines with ROGER BRANDON on its own line before transactions
        String[] lines = {
            "Date, Post,  Date Description Amount",
            "ROGER BRANDON",
            "09/19 09/20 COSTCO WHSE #0110        ISSAQUAH     WA $104.36",
            "09/19 09/20 COSTCO WHSE #0110        ISSAQUAH     WA $117.46"
        };
        
        // Transaction line is at index 2
        int transactionIndex = 2;
        
        // When: Detect username before transaction
        String result = (String) detectUsernameBeforeHeader.invoke(pdfImportService, lines, transactionIndex, (AccountDetectionService.DetectedAccount) null);
        
        // Then: Should detect ROGER BRANDON
        assertNotNull(result, "Should detect ROGER BRANDON");
        assertEquals("ROGER BRANDON", result, "Should return ROGER BRANDON");
    }

    @Test
    @DisplayName("Should detect name 2 lines before transaction")
    void testDetect_Name_TwoLinesBeforeTransaction() throws Exception {
        // Given: Name appears 2 lines before transaction
        String[] lines = {
            "Date Description Amount",
            "TOM TRACKER",
            "Promo Purchase-Offer 4 (9.990%)",
            "09/19 09/20 COSTCO WHSE #0110        ISSAQUAH     WA $7.70"
        };
        
        // Transaction line is at index 3
        int transactionIndex = 3;
        
        // When: Detect username before transaction
        String result = (String) detectUsernameBeforeHeader.invoke(pdfImportService, lines, transactionIndex, (AccountDetectionService.DetectedAccount) null);
        
        // Then: Should detect TOM TRACKER
        assertNotNull(result, "Should detect TOM TRACKER");
        assertEquals("TOM TRACKER", result, "Should return TOM TRACKER");
    }

    @Test
    @DisplayName("Should validate TOM TRACKER as valid name format")
    void testIsValidNameFormat_TomTracker() throws Exception {
        // When
        boolean result = (Boolean) isValidNameFormat.invoke(pdfImportService, "TOM TRACKER");
        
        // Then
        assertTrue(result, "TOM TRACKER should be valid name format");
    }

    @Test
    @DisplayName("Should validate ROGER BRANDON as valid name format")
    void testIsValidNameFormat_RogerBrandon() throws Exception {
        // When
        boolean result = (Boolean) isValidNameFormat.invoke(pdfImportService, "ROGER BRANDON");
        
        // Then
        assertTrue(result, "ROGER BRANDON should be valid name format");
    }

    @Test
    @DisplayName("Should detect name when it appears with promo text on next line")
    void testDetect_Name_WithPromoTextOnNextLine() throws Exception {
        // Given: Name followed by promo text, then transaction
        String[] lines = {
            "Date Description Amount",
            "TOM TRACKER",
            "Promo Purchase-Offer 4 (9.990%)",
            "09/19 09/20 COSTCO WHSE #0110        ISSAQUAH     WA $7.70"
        };
        
        // Transaction line is at index 3
        int transactionIndex = 3;
        
        // When: Detect username before transaction
        String result = (String) detectUsernameBeforeHeader.invoke(pdfImportService, lines, transactionIndex, (AccountDetectionService.DetectedAccount) null);
        
        // Then: Should detect TOM TRACKER (promo text should not prevent detection)
        assertNotNull(result, "Should detect TOM TRACKER even with promo text on next line");
        assertEquals("TOM TRACKER", result, "Should return TOM TRACKER");
    }

    @Test
    @DisplayName("Should prefer closest name when multiple names appear")
    void testDetect_MultipleNames_PrefersClosest() throws Exception {
        // Given: Multiple names before transaction
        String[] lines = {
            "Date Description Amount",
            "TOM TRACKER",
            "ROGER BRANDON",
            "09/19 09/20 COSTCO WHSE #0110        ISSAQUAH     WA $104.36"
        };
        
        // Transaction line is at index 3
        int transactionIndex = 3;
        
        // When: Detect username before transaction
        String result = (String) detectUsernameBeforeHeader.invoke(pdfImportService, lines, transactionIndex, (AccountDetectionService.DetectedAccount) null);
        
        // Then: Should detect ROGER BRANDON (closest to transaction)
        assertNotNull(result, "Should detect a name");
        // The implementation returns the first candidate found (from end to start), so ROGER BRANDON should be detected
        assertEquals("ROGER BRANDON", result, "Should return ROGER BRANDON (closest to transaction)");
    }
}

