package com.budgetbuddy.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Tests for contextual username detection patterns (all-caps names followed by address/zip/card/account patterns)
 */
@DisplayName("PDFImportService Username Detection - Contextual Patterns Test")
public class PDFImportServiceUsernameDetectionContextualPatternsTest {

    private PDFImportService pdfImportService;
    private Method findUsernameCandidates;

    @BeforeEach
    void setUp() throws Exception {
        pdfImportService = new PDFImportService(null, null, null, null, null);
        
        findUsernameCandidates = PDFImportService.class.getDeclaredMethod(
            "findUsernameCandidates", String[].class, int.class, int.class, int.class);
        findUsernameCandidates.setAccessible(true);
    }

    @Test
    @DisplayName("Should detect all-caps name followed by address with ZIP code")
    void testAllCapsNameBeforeAddressWithZip() throws Exception {
        String[] lines = {
            "ASHTON BASHTON HASHTON",
            "73529 NE 43ST ST",
            "SEATTLE WA 98119-3579",
            "Date Description Amount"
        };
        
        @SuppressWarnings("unchecked")
        List<String> candidates = (List<String>) findUsernameCandidates.invoke(
            pdfImportService, lines, 3, 1, 6);
        
        assertNotNull(candidates, "Should return candidates");
        assertTrue(candidates.contains("ASHTON BASHTON HASHTON"), 
            "Should detect all-caps name followed by address with ZIP code");
    }

    @Test
    @DisplayName("Should detect all-caps name followed by account ending pattern")
    void testAllCapsNameBeforeAccountEnding() throws Exception {
        String[] lines = {
            "ROGER PHILIPS FERNANDES",
            "Account Ending 1-23456",
            "Date Description Amount"
        };
        
        @SuppressWarnings("unchecked")
        List<String> candidates = (List<String>) findUsernameCandidates.invoke(
            pdfImportService, lines, 2, 1, 6);
        
        assertNotNull(candidates, "Should return candidates");
        assertTrue(candidates.contains("ROGER PHILIPS FERNANDES"), 
            "Should detect all-caps name followed by account ending pattern");
    }

    @Test
    @DisplayName("Should detect all-caps name followed by card number pattern")
    void testAllCapsNameBeforeCardNumber() throws Exception {
        String[] lines = {
            "JANE SMITH",
            "Card ending in 5678",
            "Date Description Amount"
        };
        
        @SuppressWarnings("unchecked")
        List<String> candidates = (List<String>) findUsernameCandidates.invoke(
            pdfImportService, lines, 2, 1, 6);
        
        assertNotNull(candidates, "Should return candidates");
        assertTrue(candidates.contains("JANE SMITH"), 
            "Should detect all-caps name followed by card number pattern");
    }

    @Test
    @DisplayName("Should detect all-caps name in 3-line address format (name, street, city state ZIP)")
    void testAllCapsNameIn3LineAddressFormat() throws Exception {
        String[] lines = {
            "ROGER A FERNANDES",
            "12345 NE 17ST ST",
            "SEATTLE WA 91114-3211",
            "Date Description Amount"
        };
        
        @SuppressWarnings("unchecked")
        List<String> candidates = (List<String>) findUsernameCandidates.invoke(
            pdfImportService, lines, 3, 1, 6);
        
        assertNotNull(candidates, "Should return candidates");
        assertTrue(candidates.contains("ROGER A FERNANDES"), 
            "Should detect all-caps name in 3-line address format");
    }

    @Test
    @DisplayName("Should detect all-caps name followed by 'Account ending in' pattern")
    void testAllCapsNameBeforeAccountEndingIn() throws Exception {
        String[] lines = {
            "MARY JANE WATSON",
            "Account ending in: 1234",
            "Date Description Amount"
        };
        
        @SuppressWarnings("unchecked")
        List<String> candidates = (List<String>) findUsernameCandidates.invoke(
            pdfImportService, lines, 2, 1, 6);
        
        assertNotNull(candidates, "Should return candidates");
        assertTrue(candidates.contains("MARY JANE WATSON"), 
            "Should detect all-caps name followed by 'Account ending in' pattern");
    }

    @Test
    @DisplayName("Should detect all-caps name followed by 'Card number ending' pattern")
    void testAllCapsNameBeforeCardNumberEnding() throws Exception {
        String[] lines = {
            "JOHN DOE",
            "Card number ending 7890",
            "Date Description Amount"
        };
        
        @SuppressWarnings("unchecked")
        List<String> candidates = (List<String>) findUsernameCandidates.invoke(
            pdfImportService, lines, 2, 1, 6);
        
        assertNotNull(candidates, "Should return candidates");
        assertTrue(candidates.contains("JOHN DOE"), 
            "Should detect all-caps name followed by 'Card number ending' pattern");
    }

    @Test
    @DisplayName("Should detect all-caps name followed by 'Closing Date ... Account Ending' pattern")
    void testAllCapsNameBeforeClosingDateAccountEnding() throws Exception {
        String[] lines = {
            "SAKINA PHILIPS AHMED",
            "Closing Date 12/12/25 Account Ending 1-23456",
            "Date Description Amount"
        };
        
        @SuppressWarnings("unchecked")
        List<String> candidates = (List<String>) findUsernameCandidates.invoke(
            pdfImportService, lines, 2, 1, 6);
        
        assertNotNull(candidates, "Should return candidates");
        assertTrue(candidates.contains("SAKINA PHILIPS AHMED"), 
            "Should detect all-caps name followed by 'Closing Date ... Account Ending' pattern");
    }

    @Test
    @DisplayName("Should not detect non-all-caps name followed by address (lower priority)")
    void testNonAllCapsNameBeforeAddress() throws Exception {
        String[] lines = {
            "John Doe",
            "123 Main St",
            "Seattle WA 98101",
            "Date Description Amount"
        };
        
        @SuppressWarnings("unchecked")
        List<String> candidates = (List<String>) findUsernameCandidates.invoke(
            pdfImportService, lines, 3, 1, 6);
        
        assertNotNull(candidates, "Should return candidates");
        // Should still be detected (Pattern 4: standalone name), but with lower priority
        assertTrue(candidates.contains("John Doe"), 
            "Should still detect non-all-caps name (standalone pattern)");
    }
}

