package com.budgetbuddy.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Tests for detecting multiple usernames in a single PDF (multi-card family accounts)
 * Tests the scenario where names like "TOM TRACKER" and "ROGER BRANDON" appear after headers
 * but before their respective transaction lines
 */
class PDFImportServiceUsernameDetectionMultiUserTest {

    private PDFImportService pdfImportService;
    private Method parsePDFText;

    @Mock
    private AccountDetectionService accountDetectionService;

    @Mock
    private ImportCategoryParser importCategoryParser;

    @Mock
    private TransactionTypeCategoryService transactionTypeCategoryService;

    @Mock
    private EnhancedPatternMatcher enhancedPatternMatcher;

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        
        // Create PDFImportService with mocked dependencies
        pdfImportService = new PDFImportService(
            accountDetectionService,
            importCategoryParser,
            transactionTypeCategoryService,
            enhancedPatternMatcher,
            null
        );
        
        parsePDFText = PDFImportService.class.getDeclaredMethod(
            "parsePDFText", String.class, Integer.class, boolean.class, AccountDetectionService.DetectedAccount.class);
        parsePDFText.setAccessible(true);
    }

    @Test
    @DisplayName("Should detect TOM TRACKER and ROGER BRANDON from transaction list with names after header")
    @SuppressWarnings("unchecked")
    void testDetect_MultiUser_NamesAfterHeader() throws Exception {
        // Given: PDF text with header, then names, then transactions
        String pdfText = "Date, Post,  Date Description Amount, Payments, Credits and Adjustments\n" +
                        "10/17 AUTOPAY 999990000061086RAUTOPAY AUTO-PMT -$1,533.26\n" +
                        "09/23 09/23 COSTCO WHSE #0110        ISSAQUAH     WA -$33.09\n" +
                        "10/06 10/06 WWW COSTCO COM           800-955-2292 WA -$33.05\n" +
                        "10/06 10/06 COSTCO WHSE #0110        ISSAQUAH     WA -$96.11\n" +
                        "TOM TRACKER\n" +
                        "Promo Purchase-Offer 4 (9.990%)\n" +
                        "09/19 09/20 COSTCO WHSE #0110        ISSAQUAH     WA $7.70\n" +
                        "09/20 09/20 SHELL OIL 57444030803    KENT         WA $30.00\n" +
                        "09/20 09/20 STARBUCKS STORE 59230    KENT         WA $5.79\n" +
                        "ROGER BRANDON\n" +
                        "09/19 09/20 COSTCO WHSE #0110        ISSAQUAH     WA $104.36\n" +
                        "09/19 09/20 COSTCO WHSE #0110        ISSAQUAH     WA $117.46\n" +
                        "09/23 09/23 COSTCO GAS #0110         ISSAQUAH     WA $91.96\n" +
                        "09/23 09/23 COSTCO WHSE #0110        ISSAQUAH     WA $194.80\n";
        
        // Mock the accountDetectionService to return null (no account holder name for validation)
        when(accountDetectionService.getInstitutionKeywordsForFiltering()).thenReturn(java.util.Collections.emptyList());
        
        // When: Parse PDF text
        List<Map<String, String>> rows = (List<Map<String, String>>) parsePDFText.invoke(
            pdfImportService, pdfText, 2024, true, (AccountDetectionService.DetectedAccount) null);
        
        // Then: Should parse transactions and assign correct usernames
        assertNotNull(rows, "Should parse transactions");
        assertFalse(rows.isEmpty(), "Should have parsed some transactions");
        
        // Find transactions that should have TOM TRACKER as user
        // Look for the COSTCO transaction with $7.70 (should be TOM TRACKER)
        boolean foundTomTracker = false;
        boolean foundRogerBrandon = false;
        
        for (Map<String, String> row : rows) {
            String user = row.get("user");
            String description = row.get("description");
            String amount = row.get("amount");
            
            if (user != null && user.contains("TOM TRACKER")) {
                foundTomTracker = true;
                // Verify it's associated with the right transaction
                if (description != null && description.contains("COSTCO") && amount != null && amount.contains("7.70")) {
                    assertEquals("TOM TRACKER", user, "Transaction should have TOM TRACKER as user");
                }
            }
            
            if (user != null && user.contains("ROGER BRANDON")) {
                foundRogerBrandon = true;
                // Verify it's associated with the right transaction
                if (description != null && description.contains("COSTCO") && amount != null && amount.contains("104.36")) {
                    assertEquals("ROGER BRANDON", user, "Transaction should have ROGER BRANDON as user");
                }
            }
        }
        
        // At least one transaction should have each username
        assertTrue(foundTomTracker, "Should find at least one transaction with TOM TRACKER");
        assertTrue(foundRogerBrandon, "Should find at least one transaction with ROGER BRANDON");
    }

    @Test
    @DisplayName("Should detect username before transaction when name appears after header")
    @SuppressWarnings("unchecked")
    void testDetect_UsernameAfterHeader_BeforeTransaction() throws Exception {
        // Given: PDF text with header, then name, then transaction
        String pdfText = "Date Description Amount\n" +
                        "TOM TRACKER\n" +
                        "09/19 09/20 COSTCO WHSE #0110        ISSAQUAH     WA $7.70\n" +
                        "09/20 09/20 SHELL OIL 57444030803    KENT         WA $30.00\n";
        
        // Mock the accountDetectionService
        when(accountDetectionService.getInstitutionKeywordsForFiltering()).thenReturn(java.util.Collections.emptyList());
        
        // When: Parse PDF text
        List<Map<String, String>> rows = (List<Map<String, String>>) parsePDFText.invoke(
            pdfImportService, pdfText, 2024, true, (AccountDetectionService.DetectedAccount) null);
        
        // Then: Transactions should have TOM TRACKER as user
        assertNotNull(rows, "Should parse transactions");
        assertFalse(rows.isEmpty(), "Should have parsed transactions");
        
        for (Map<String, String> row : rows) {
            String user = row.get("user");
            if (user != null) {
                assertEquals("TOM TRACKER", user, "Transaction should have TOM TRACKER as user");
            }
        }
    }
}

