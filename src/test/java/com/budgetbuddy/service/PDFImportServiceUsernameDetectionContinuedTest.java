package com.budgetbuddy.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;

/**
 * Tests for "continued" false positive and all-caps preference bug fixes
 */
@DisplayName("PDFImportService Username Detection - Continued and All-Caps Preference Tests")
public class PDFImportServiceUsernameDetectionContinuedTest {

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
    @DisplayName("Should reject 'continued' as a username")
    void testFalsePositive_Continued() throws Exception {
        assertFalse((Boolean) isValidNameFormat.invoke(pdfImportService, "continued"),
            "Should reject 'continued'");
        assertFalse((Boolean) isValidNameFormat.invoke(pdfImportService, "Continued"),
            "Should reject 'Continued' (case insensitive)");
        assertFalse((Boolean) isValidNameFormat.invoke(pdfImportService, "CONTINUED"),
            "Should reject 'CONTINUED' (all caps)");
    }

    @Test
    @DisplayName("Should prefer all-caps name 'ASHTON BASHTON HASHTON' over 'continued' in 3-line address format")
    void testPreferAllCaps_OverContinued() throws Exception {
        // Simulate the 3-line address format scenario
        String[] lines = {
            "ASHTON BASHTON HASHTON",
            "73529 NE 43ST ST",
            "SEATTLE WA 98119-3579",
            "continued",  // This should be rejected
            "Date Description Amount"
        };
        
        AccountDetectionService.DetectedAccount detectedAccount = 
            new AccountDetectionService.DetectedAccount();
        // No account holder name set (to test all-caps preference without account holder validation)
        
        String username = (String) detectUsernameBeforeHeader.invoke(
            pdfImportService, lines, 4, detectedAccount);
        
        // Should prefer "ASHTON BASHTON HASHTON" (all-caps) over "continued" (which should be rejected anyway)
        assertNotNull(username, "Should detect a username");
        assertEquals("ASHTON BASHTON HASHTON", username, "Should prefer all-caps name over 'continued'");
    }

    @Test
    @DisplayName("Should prefer all-caps name even when 'continued' appears before it")
    void testPreferAllCaps_ContinuedBeforeName() throws Exception {
        // Test case where "continued" appears before the all-caps name
        String[] lines = {
            "continued",  // This should be rejected
            "ASHTON BASHTON HASHTON",
            "73529 NE 43ST ST",
            "SEATTLE WA 98119-3579",
            "Date Description Amount"
        };
        
        AccountDetectionService.DetectedAccount detectedAccount = 
            new AccountDetectionService.DetectedAccount();
        
        String username = (String) detectUsernameBeforeHeader.invoke(
            pdfImportService, lines, 4, detectedAccount);
        
        // Should prefer "ASHTON BASHTON HASHTON" (all-caps) even though "continued" appears first
        assertNotNull(username, "Should detect a username");
        assertEquals("ASHTON BASHTON HASHTON", username, "Should prefer all-caps name even when 'continued' appears first");
    }

    @Test
    @DisplayName("Should prefer all-caps name when account holder name matches")
    void testPreferAllCaps_WithAccountHolderNameMatch() throws Exception {
        String[] lines = {
            "ASHTON BASHTON HASHTON",
            "continued",
            "Date Description Amount"
        };
        
        AccountDetectionService.DetectedAccount detectedAccount = 
            new AccountDetectionService.DetectedAccount();
        detectedAccount.setAccountHolderName("Ashton Bashton Hashton"); // Mixed case account holder name
        
        String username = (String) detectUsernameBeforeHeader.invoke(
            pdfImportService, lines, 2, detectedAccount);
        
        // Should prefer "ASHTON BASHTON HASHTON" (all-caps) over "continued" (which should be rejected)
        // and it should match the account holder name
        assertNotNull(username, "Should detect a username");
        assertEquals("ASHTON BASHTON HASHTON", username, "Should prefer all-caps name that matches account holder name");
    }
}

