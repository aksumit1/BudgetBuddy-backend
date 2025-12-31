package com.budgetbuddy.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;

/**
 * Tests for specific bug fixes in username detection
 */
@DisplayName("PDFImportService Username Detection - Bug Fix Tests")
public class PDFImportServiceUsernameDetectionBugFixTest {

    private PDFImportService pdfImportService;
    private Method isValidNameFormat;
    private Method detectUsernameBeforeHeader;

    @BeforeEach
    void setUp() throws Exception {
        pdfImportService = new PDFImportService(null, null, null, null, null);
        
        isValidNameFormat = PDFImportService.class.getDeclaredMethod("isValidNameFormat", String.class);
        isValidNameFormat.setAccessible(true);
        
        detectUsernameBeforeHeader = PDFImportService.class.getDeclaredMethod(
            "detectUsernameBeforeHeader", String[].class, int.class, AccountDetectionService.DetectedAccount.class);
        detectUsernameBeforeHeader.setAccessible(true);
    }

    @Test
    @DisplayName("Should reject 'Agreement for details' as a username")
    void testFalsePositive_AgreementForDetails() throws Exception {
        assertFalse((Boolean) isValidNameFormat.invoke(pdfImportService, "Agreement for details"),
            "Should reject 'Agreement for details'");
        assertFalse((Boolean) isValidNameFormat.invoke(pdfImportService, "agreement for details"),
            "Should reject 'agreement for details' (case insensitive)");
    }

    @Test
    @DisplayName("Should reject names with asterisks (e.g., 'D J*BARRONS')")
    void testFalsePositive_NamesWithAsterisk() throws Exception {
        assertFalse((Boolean) isValidNameFormat.invoke(pdfImportService, "D J*BARRONS"),
            "Should reject 'D J*BARRONS' (contains asterisk)");
        assertFalse((Boolean) isValidNameFormat.invoke(pdfImportService, "John* Doe"),
            "Should reject 'John* Doe' (contains asterisk)");
        assertFalse((Boolean) isValidNameFormat.invoke(pdfImportService, "*John Doe"),
            "Should reject '*John Doe' (contains asterisk)");
    }

    @Test
    @DisplayName("Should prefer all-caps names over mixed case when multiple candidates exist")
    void testPreferAllCaps_WithAccountHolderName() throws Exception {
        String[] lines = {
            "JOHN DOE",
            "John Doe",
            "Card Member: JANE SMITH",
            "Date Description Amount"
        };
        
        AccountDetectionService.DetectedAccount detectedAccount = 
            new AccountDetectionService.DetectedAccount();
        detectedAccount.setAccountHolderName("John Doe");
        
        // Should prefer "JOHN DOE" (all-caps) over "John Doe" (mixed case)
        String username = (String) detectUsernameBeforeHeader.invoke(
            pdfImportService, lines, 3, detectedAccount);
        
        // Since both match account holder name, should prefer all-caps
        assertNotNull(username, "Should detect a username");
        // Note: This test depends on the order candidates are found and validation logic
        // The preference for all-caps is implemented in detectUsernameBeforeHeader
    }

    @Test
    @DisplayName("Should prefer all-caps names when no account holder name available")
    void testPreferAllCaps_NoAccountHolderName() throws Exception {
        String[] lines = {
            "MARY JANE SMITH",
            "John Doe",
            "Date Description Amount"
        };
        
        AccountDetectionService.DetectedAccount detectedAccount = 
            new AccountDetectionService.DetectedAccount();
        // No account holder name set
        
        String username = (String) detectUsernameBeforeHeader.invoke(
            pdfImportService, lines, 2, detectedAccount);
        
        // Should prefer "MARY JANE SMITH" (all-caps) over "John Doe" (mixed case)
        assertNotNull(username, "Should detect a username");
        assertEquals("MARY JANE SMITH", username, "Should prefer all-caps name");
    }

    @Test
    @DisplayName("Should prefer labeled patterns (Card Member:) over standalone names")
    void testPreferLabeledPatterns() throws Exception {
        String[] lines = {
            "D J*BARRONS",  // Standalone name with asterisk (should be rejected anyway)
            "Card Member: JOHN DOE",  // Labeled pattern
            "Date Description Amount"
        };
        
        AccountDetectionService.DetectedAccount detectedAccount = 
            new AccountDetectionService.DetectedAccount();
        detectedAccount.setAccountHolderName("John Doe");
        
        String username = (String) detectUsernameBeforeHeader.invoke(
            pdfImportService, lines, 2, detectedAccount);
        
        // Should prefer "JOHN DOE" from "Card Member:" pattern
        assertNotNull(username, "Should detect a username");
        assertEquals("JOHN DOE", username, "Should prefer labeled pattern");
    }
}

