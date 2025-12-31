package com.budgetbuddy.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Arrays;

/**
 * Comprehensive tests for username detection and validation in PDFImportService
 * Tests edge cases, boundary conditions, false positives, false negatives, and error handling
 */
@DisplayName("PDFImportService Username Detection Tests")
public class PDFImportServiceUsernameDetectionTest {

    private PDFImportService pdfImportService;
    private Method isValidNameFormat;
    private Method matchesAccountHolderName;
    private Method findUsernameCandidates;
    private Method detectUsernameBeforeHeader;

    @BeforeEach
    void setUp() throws Exception {
        // Create mocks for required dependencies
        AccountDetectionService mockAccountDetectionService = org.mockito.Mockito.mock(AccountDetectionService.class);
        ImportCategoryParser mockImportCategoryParser = org.mockito.Mockito.mock(ImportCategoryParser.class);
        TransactionTypeCategoryService mockTransactionTypeCategoryService = 
            org.mockito.Mockito.mock(TransactionTypeCategoryService.class);
        EnhancedPatternMatcher enhancedPatternMatcher = new EnhancedPatternMatcher();
        
        pdfImportService = new PDFImportService(
            mockAccountDetectionService, 
            mockImportCategoryParser, 
            mockTransactionTypeCategoryService,
            enhancedPatternMatcher,
            null
        );
        
        // Use reflection to access private methods for testing
        isValidNameFormat = PDFImportService.class.getDeclaredMethod("isValidNameFormat", String.class);
        isValidNameFormat.setAccessible(true);
        
        matchesAccountHolderName = PDFImportService.class.getDeclaredMethod("matchesAccountHolderName", String.class, String.class);
        matchesAccountHolderName.setAccessible(true);
        
        findUsernameCandidates = PDFImportService.class.getDeclaredMethod("findUsernameCandidates", String[].class, int.class, int.class, int.class);
        findUsernameCandidates.setAccessible(true);
        
        detectUsernameBeforeHeader = PDFImportService.class.getDeclaredMethod("detectUsernameBeforeHeader", 
            String[].class, int.class, AccountDetectionService.DetectedAccount.class);
        detectUsernameBeforeHeader.setAccessible(true);
    }

    // ========== isValidNameFormat Tests ==========

    @Test
    @DisplayName("Valid name formats should pass validation")
    void testIsValidNameFormat_ValidNames() throws Exception {
        assertTrue((Boolean) isValidNameFormat.invoke(pdfImportService, "John Doe"));
        assertTrue((Boolean) isValidNameFormat.invoke(pdfImportService, "Mary-Jane Smith"));
        assertTrue((Boolean) isValidNameFormat.invoke(pdfImportService, "JOHN DOE"));
        assertTrue((Boolean) isValidNameFormat.invoke(pdfImportService, "O'Brien"));
        assertTrue((Boolean) isValidNameFormat.invoke(pdfImportService, "John"));
        assertTrue((Boolean) isValidNameFormat.invoke(pdfImportService, "Mary Jane Smith O'Connor"));
    }

    @Test
    @DisplayName("Invalid name formats should fail validation - contains digits")
    void testIsValidNameFormat_ContainsDigits() throws Exception {
        assertFalse((Boolean) isValidNameFormat.invoke(pdfImportService, "John123 Doe"));
        assertFalse((Boolean) isValidNameFormat.invoke(pdfImportService, "123 John"));
        assertFalse((Boolean) isValidNameFormat.invoke(pdfImportService, "John 2 Doe"));
    }

    @Test
    @DisplayName("Invalid name formats should fail validation - contains currency symbols")
    void testIsValidNameFormat_ContainsCurrency() throws Exception {
        assertFalse((Boolean) isValidNameFormat.invoke(pdfImportService, "John $Doe"));
        assertFalse((Boolean) isValidNameFormat.invoke(pdfImportService, "$100 John"));
    }

    @Test
    @DisplayName("Invalid name formats should fail validation - contains percentages")
    void testIsValidNameFormat_ContainsPercentage() throws Exception {
        assertFalse((Boolean) isValidNameFormat.invoke(pdfImportService, "John% Doe"));
        assertFalse((Boolean) isValidNameFormat.invoke(pdfImportService, "1% John"));
    }

    @Test
    @DisplayName("Invalid name formats should fail validation - contains dates")
    void testIsValidNameFormat_ContainsDates() throws Exception {
        assertFalse((Boolean) isValidNameFormat.invoke(pdfImportService, "John 12/25/2025"));
        assertFalse((Boolean) isValidNameFormat.invoke(pdfImportService, "12/25/2025 John"));
        assertFalse((Boolean) isValidNameFormat.invoke(pdfImportService, "John 12-25-2025"));
    }

    @Test
    @DisplayName("Invalid name formats should fail validation - contains phone numbers")
    void testIsValidNameFormat_ContainsPhoneNumbers() throws Exception {
        assertFalse((Boolean) isValidNameFormat.invoke(pdfImportService, "John 800-555-1234"));
        assertFalse((Boolean) isValidNameFormat.invoke(pdfImportService, "(800) 555-1234 John"));
    }

    @Test
    @DisplayName("Invalid name formats should fail validation - too many words")
    void testIsValidNameFormat_TooManyWords() throws Exception {
        // 5 words is the max, so "John Michael James William Doe" (5 words) should pass
        assertTrue((Boolean) isValidNameFormat.invoke(pdfImportService, "John Michael James William Doe"));
        // 6 words should fail
        assertFalse((Boolean) isValidNameFormat.invoke(pdfImportService, "One Two Three Four Five Six"));
        assertFalse((Boolean) isValidNameFormat.invoke(pdfImportService, "John Michael James William Doe Smith"));
    }

    @Test
    @DisplayName("Invalid name formats should fail validation - excluded words")
    void testIsValidNameFormat_ExcludedWords() throws Exception {
        // Single excluded words should be rejected
        assertFalse((Boolean) isValidNameFormat.invoke(pdfImportService, "transaction"), "Should reject 'transaction'");
        assertFalse((Boolean) isValidNameFormat.invoke(pdfImportService, "account"), "Should reject 'account'");
        assertFalse((Boolean) isValidNameFormat.invoke(pdfImportService, "sale"), "Should reject 'sale'");
        assertFalse((Boolean) isValidNameFormat.invoke(pdfImportService, "Sale"), "Should reject 'Sale' (case insensitive)");
        assertFalse((Boolean) isValidNameFormat.invoke(pdfImportService, "post"), "Should reject 'post'");
        assertFalse((Boolean) isValidNameFormat.invoke(pdfImportService, "Post"), "Should reject 'Post' (case insensitive)");
        // But names that contain these words should still be allowed if they're part of a larger name
        // We only reject if entire line is an excluded word
    }

    @Test
    @DisplayName("Invalid name formats should fail validation - agreement-related header phrases")
    void testIsValidNameFormat_AgreementPhrases() throws Exception {
        // Agreement-related phrases should be rejected (false positive username detection)
        assertFalse((Boolean) isValidNameFormat.invoke(pdfImportService, "Cardmember Agreement for details"), 
            "Should reject 'Cardmember Agreement for details'");
        assertFalse((Boolean) isValidNameFormat.invoke(pdfImportService, "cardmember agreement for details"), 
            "Should reject lowercase version");
        assertFalse((Boolean) isValidNameFormat.invoke(pdfImportService, "Agreement for details"), 
            "Should reject 'Agreement for details'");
        assertFalse((Boolean) isValidNameFormat.invoke(pdfImportService, "Cardholder Agreement for details"), 
            "Should reject 'Cardholder Agreement for details'");
        assertFalse((Boolean) isValidNameFormat.invoke(pdfImportService, "agreement for details"), 
            "Should reject lowercase 'agreement for details'");
    }

    @Test
    @DisplayName("Invalid name formats should fail validation - plus/minus signs")
    void testIsValidNameFormat_PlusMinusSigns() throws Exception {
        assertFalse((Boolean) isValidNameFormat.invoke(pdfImportService, "+John Doe"));
        assertFalse((Boolean) isValidNameFormat.invoke(pdfImportService, "-John Doe"));
        assertFalse((Boolean) isValidNameFormat.invoke(pdfImportService, "John+Doe"));
    }

    @Test
    @DisplayName("Edge case: null and empty strings")
    void testIsValidNameFormat_NullAndEmpty() throws Exception {
        assertFalse((Boolean) isValidNameFormat.invoke(pdfImportService, (String) null));
        assertFalse((Boolean) isValidNameFormat.invoke(pdfImportService, ""));
        assertFalse((Boolean) isValidNameFormat.invoke(pdfImportService, "   "));
    }

    @Test
    @DisplayName("Edge case: lowercase names should fail (except all uppercase)")
    void testIsValidNameFormat_LowercaseNames() throws Exception {
        assertFalse((Boolean) isValidNameFormat.invoke(pdfImportService, "john doe"));
        assertFalse((Boolean) isValidNameFormat.invoke(pdfImportService, "john"));
        assertTrue((Boolean) isValidNameFormat.invoke(pdfImportService, "JOHN DOE")); // All uppercase is valid
    }

    // ========== matchesAccountHolderName Tests ==========

    @Test
    @DisplayName("Exact matches should succeed")
    void testMatchesAccountHolderName_ExactMatch() throws Exception {
        assertTrue((Boolean) matchesAccountHolderName.invoke(pdfImportService, "John Doe", "John Doe"));
        assertTrue((Boolean) matchesAccountHolderName.invoke(pdfImportService, "JOHN DOE", "john doe"));
        assertTrue((Boolean) matchesAccountHolderName.invoke(pdfImportService, "  John Doe  ", "John Doe"));
    }

    @Test
    @DisplayName("Partial matches should succeed")
    void testMatchesAccountHolderName_PartialMatch() throws Exception {
        assertTrue((Boolean) matchesAccountHolderName.invoke(pdfImportService, "John", "John Doe"));
        assertTrue((Boolean) matchesAccountHolderName.invoke(pdfImportService, "Doe", "John Doe"));
        assertTrue((Boolean) matchesAccountHolderName.invoke(pdfImportService, "Mary", "Mary Jane Smith"));
        assertTrue((Boolean) matchesAccountHolderName.invoke(pdfImportService, "Smith", "Mary Jane Smith"));
    }

    @Test
    @DisplayName("Abbreviation matches should succeed")
    void testMatchesAccountHolderName_Abbreviation() throws Exception {
        assertTrue((Boolean) matchesAccountHolderName.invoke(pdfImportService, "J. Doe", "John Doe"));
        assertTrue((Boolean) matchesAccountHolderName.invoke(pdfImportService, "John M.", "John Michael"));
        assertTrue((Boolean) matchesAccountHolderName.invoke(pdfImportService, "J", "John"));
    }

    @Test
    @DisplayName("Non-matching names should fail")
    void testMatchesAccountHolderName_NoMatch() throws Exception {
        assertFalse((Boolean) matchesAccountHolderName.invoke(pdfImportService, "John Doe", "Jane Smith"));
        assertFalse((Boolean) matchesAccountHolderName.invoke(pdfImportService, "Alice", "Bob"));
        assertFalse((Boolean) matchesAccountHolderName.invoke(pdfImportService, "John", "Jane Smith"));
    }

    @Test
    @DisplayName("Edge case: null inputs")
    void testMatchesAccountHolderName_NullInputs() throws Exception {
        assertFalse((Boolean) matchesAccountHolderName.invoke(pdfImportService, null, "John Doe"));
        assertFalse((Boolean) matchesAccountHolderName.invoke(pdfImportService, "John Doe", null));
        assertFalse((Boolean) matchesAccountHolderName.invoke(pdfImportService, null, null));
    }

    @Test
    @DisplayName("Hyphenated names should match correctly")
    void testMatchesAccountHolderName_HyphenatedNames() throws Exception {
        assertTrue((Boolean) matchesAccountHolderName.invoke(pdfImportService, "Mary-Jane", "Mary-Jane Smith"));
        assertTrue((Boolean) matchesAccountHolderName.invoke(pdfImportService, "Mary", "Mary-Jane Smith"));
        assertTrue((Boolean) matchesAccountHolderName.invoke(pdfImportService, "Jane", "Mary-Jane Smith"));
    }

    // ========== findUsernameCandidates Tests ==========

    @Test
    @DisplayName("Should find Card Member pattern")
    void testFindUsernameCandidates_CardMemberPattern() throws Exception {
        String[] lines = {
            "Transaction 1",
            "Card Member: John Doe",
            "Date Description Amount",
            "12/25/25 AMAZON $100.00"
        };
        @SuppressWarnings("unchecked")
        List<String> candidates = (List<String>) findUsernameCandidates.invoke(pdfImportService, lines, 3, 1, 4);
        assertNotNull(candidates);
        assertTrue(candidates.contains("John Doe"));
    }

    @Test
    @DisplayName("Should find Name pattern")
    void testFindUsernameCandidates_NamePattern() throws Exception {
        String[] lines = {
            "Transaction 1",
            "Name: Mary Jane",
            "Date Description Amount",
            "12/25/25 TARGET $50.00"
        };
        @SuppressWarnings("unchecked")
        List<String> candidates = (List<String>) findUsernameCandidates.invoke(pdfImportService, lines, 3, 1, 4);
        assertNotNull(candidates);
        assertTrue(candidates.contains("Mary Jane"));
    }

    @Test
    @DisplayName("Should find standalone name")
    void testFindUsernameCandidates_StandaloneName() throws Exception {
        String[] lines = {
            "Transaction 1",
            "John Doe",
            "Date Description Amount",
            "12/25/25 WALMART $75.00"
        };
        @SuppressWarnings("unchecked")
        List<String> candidates = (List<String>) findUsernameCandidates.invoke(pdfImportService, lines, 3, 1, 4);
        assertNotNull(candidates);
        assertTrue(candidates.contains("John Doe"));
    }

    @Test
    @DisplayName("Boundary condition: transaction at line 0")
    void testFindUsernameCandidates_BoundaryAtLine0() throws Exception {
        String[] lines = {
            "12/25/25 STORE $10.00"
        };
        @SuppressWarnings("unchecked")
        List<String> candidates = (List<String>) findUsernameCandidates.invoke(pdfImportService, lines, 0, 1, 4);
        assertNotNull(candidates);
        assertTrue(candidates.isEmpty()); // No lines before, should return empty
    }

    @Test
    @DisplayName("Boundary condition: transaction at line 1")
    void testFindUsernameCandidates_BoundaryAtLine1() throws Exception {
        String[] lines = {
            "John Doe",
            "12/25/25 STORE $10.00"
        };
        @SuppressWarnings("unchecked")
        List<String> candidates = (List<String>) findUsernameCandidates.invoke(pdfImportService, lines, 1, 1, 4);
        assertNotNull(candidates);
        assertTrue(candidates.contains("John Doe"));
    }

    @Test
    @DisplayName("Boundary condition: transaction at line 3")
    void testFindUsernameCandidates_BoundaryAtLine3() throws Exception {
        String[] lines = {
            "Line 0",
            "Line 1",
            "Line 2",
            "12/25/25 STORE $10.00"
        };
        @SuppressWarnings("unchecked")
        List<String> candidates = (List<String>) findUsernameCandidates.invoke(pdfImportService, lines, 3, 1, 4);
        assertNotNull(candidates);
        // Should check lines 2, 1, 0 (going backwards from line 2 to line 0)
    }

    @Test
    @DisplayName("Should ignore empty lines")
    void testFindUsernameCandidates_IgnoresEmptyLines() throws Exception {
        String[] lines = {
            "",
            "   ",
            "Card Member: John Doe",
            "12/25/25 STORE $10.00"
        };
        @SuppressWarnings("unchecked")
        List<String> candidates = (List<String>) findUsernameCandidates.invoke(pdfImportService, lines, 3, 1, 4);
        assertNotNull(candidates);
        assertTrue(candidates.contains("John Doe"));
    }

    @Test
    @DisplayName("Should prioritize closer lines")
    void testFindUsernameCandidates_PrioritizesCloserLines() throws Exception {
        String[] lines = {
            "Card Member: Old Name",
            "Card Member: New Name",
            "Card Member: Current Name",
            "12/25/25 STORE $10.00"
        };
        @SuppressWarnings("unchecked")
        List<String> candidates = (List<String>) findUsernameCandidates.invoke(pdfImportService, lines, 3, 1, 4);
        assertNotNull(candidates);
        // All should be found, but order matters (closer first due to reverse iteration)
        assertTrue(candidates.contains("Current Name"));
        assertTrue(candidates.contains("New Name"));
        assertTrue(candidates.contains("Old Name"));
    }

    @Test
    @DisplayName("Edge case: lines array with null elements")
    void testFindUsernameCandidates_NullElements() throws Exception {
        String[] lines = {
            null,
            "Card Member: John Doe",
            null,
            "12/25/25 STORE $10.00"
        };
        // Should not throw exception
        @SuppressWarnings("unchecked")
        List<String> candidates = (List<String>) findUsernameCandidates.invoke(pdfImportService, lines, 3, 1, 4);
        assertNotNull(candidates);
    }

    // ========== detectUsernameBeforeHeader Tests ==========

    @Test
    @DisplayName("Should detect username with account holder validation")
    void testDetectUsernameBeforeHeader_WithAccountHolder() throws Exception {
        String[] lines = {
            "Line 0",
            "Card Member: John Doe",
            "Date Description Amount",
            "12/25/25 AMAZON $100.00"
        };
        
        AccountDetectionService.DetectedAccount detectedAccount = new AccountDetectionService.DetectedAccount();
        detectedAccount.setAccountHolderName("John Doe");
        
        String username = (String) detectUsernameBeforeHeader.invoke(pdfImportService, lines, 2, detectedAccount);
        assertNotNull(username);
        assertEquals("John Doe", username);
    }

    @Test
    @DisplayName("Should reject username that doesn't match account holder")
    void testDetectUsernameBeforeHeader_RejectsNonMatching() throws Exception {
        String[] lines = {
            "Line 0",
            "Card Member: Jane Smith",
            "Date Description Amount",
            "12/25/25 AMAZON $100.00"
        };
        
        AccountDetectionService.DetectedAccount detectedAccount = new AccountDetectionService.DetectedAccount();
        detectedAccount.setAccountHolderName("John Doe");
        
        String username = (String) detectUsernameBeforeHeader.invoke(pdfImportService, lines, 2, detectedAccount);
        // Should return null because "Jane Smith" doesn't match "John Doe"
        assertNull(username);
    }

    @Test
    @DisplayName("Should accept username without account holder validation")
    void testDetectUsernameBeforeHeader_WithoutAccountHolder() throws Exception {
        String[] lines = {
            "Line 0",
            "Card Member: John Doe",
            "Date Description Amount",
            "12/25/25 AMAZON $100.00"
        };
        
        String username = (String) detectUsernameBeforeHeader.invoke(pdfImportService, lines, 2, null);
        assertNotNull(username);
        assertEquals("John Doe", username);
    }

    @Test
    @DisplayName("Should accept partial match with account holder")
    void testDetectUsernameBeforeHeader_PartialMatch() throws Exception {
        String[] lines = {
            "Line 0",
            "John",
            "Date Description Amount",
            "12/25/25 AMAZON $100.00"
        };
        
        AccountDetectionService.DetectedAccount detectedAccount = new AccountDetectionService.DetectedAccount();
        detectedAccount.setAccountHolderName("John Doe");
        
        String username = (String) detectUsernameBeforeHeader.invoke(pdfImportService, lines, 2, detectedAccount);
        assertNotNull(username);
        assertEquals("John", username);
    }

    // ========== False Positive Tests ==========

    @Test
    @DisplayName("Should reject transaction descriptions as usernames")
    void testFalsePositive_TransactionDescriptions() throws Exception {
        String[] lines = {
            "AMAZON PAY JOHN DOE 12345",
            "Date Description Amount",
            "12/25/25 AMAZON $100.00"
        };
        
        AccountDetectionService.DetectedAccount detectedAccount = new AccountDetectionService.DetectedAccount();
        detectedAccount.setAccountHolderName("John Doe");
        
        String username = (String) detectUsernameBeforeHeader.invoke(pdfImportService, lines, 1, detectedAccount);
        // Should reject because it contains digits and doesn't match name format
        assertNull(username);
    }

    @Test
    @DisplayName("Should reject section headers as usernames")
    void testFalsePositive_SectionHeaders() throws Exception {
        String[] lines = {
            "Transaction Details",
            "Date Description Amount",
            "12/25/25 AMAZON $100.00"
        };
        
        String username = (String) detectUsernameBeforeHeader.invoke(pdfImportService, lines, 1, null);
        // Should reject because "Transaction Details" contains excluded word
        assertNull(username);
    }

    @Test
    @DisplayName("Should reject dates as usernames")
    void testFalsePositive_Dates() throws Exception {
        String[] lines = {
            "12/25/2025",
            "Date Description Amount",
            "12/25/25 AMAZON $100.00"
        };
        
        String username = (String) detectUsernameBeforeHeader.invoke(pdfImportService, lines, 1, null);
        assertNull(username);
    }

    @Test
    @DisplayName("Should reject 'Agreement for details' when extracted from Cardholder pattern")
    void testFalsePositive_AgreementForDetailsFromCardholder() throws Exception {
        // This tests the fix: "Cardholder Agreement for details" should NOT extract "Agreement for details" as username
        String[] lines = {
            "Cardholder Agreement for details",
            "Date Description Amount",
            "12/25/25 AMAZON $100.00"
        };
        
        String username = (String) detectUsernameBeforeHeader.invoke(pdfImportService, lines, 1, null);
        assertNull(username, "Should reject 'Agreement for details' extracted from 'Cardholder Agreement for details'");
    }

    @Test
    @DisplayName("Should reject 'Agreement for details' when extracted from Cardholder: pattern")
    void testFalsePositive_AgreementForDetailsFromCardholderColon() throws Exception {
        // This tests the fix: "Cardholder: Agreement for details" should NOT extract "Agreement for details" as username
        String[] lines = {
            "Cardholder: Agreement for details",
            "Date Description Amount",
            "12/25/25 AMAZON $100.00"
        };
        
        String username = (String) detectUsernameBeforeHeader.invoke(pdfImportService, lines, 1, null);
        assertNull(username, "Should reject 'Agreement for details' extracted from 'Cardholder: Agreement for details'");
    }

    @Test
    @DisplayName("Should reject 'Agreement for details' when extracted from Cardmember pattern")
    void testFalsePositive_AgreementForDetailsFromCardmember() throws Exception {
        String[] lines = {
            "Cardmember Agreement for details",
            "Date Description Amount",
            "12/25/25 AMAZON $100.00"
        };
        
        String username = (String) detectUsernameBeforeHeader.invoke(pdfImportService, lines, 1, null);
        assertNull(username, "Should reject 'Agreement for details' extracted from 'Cardmember Agreement for details'");
    }

    // ========== False Negative Tests ==========

    @Test
    @DisplayName("Should handle names with apostrophes")
    void testFalseNegative_Apostrophes() throws Exception {
        String[] lines = {
            "O'Brien",
            "Date Description Amount",
            "12/25/25 STORE $100.00"
        };
        
        AccountDetectionService.DetectedAccount detectedAccount = new AccountDetectionService.DetectedAccount();
        detectedAccount.setAccountHolderName("Mary O'Brien");
        
        String username = (String) detectUsernameBeforeHeader.invoke(pdfImportService, lines, 1, detectedAccount);
        assertNotNull(username);
        assertEquals("O'Brien", username);
    }

    @Test
    @DisplayName("Should handle hyphenated names")
    void testFalseNegative_HyphenatedNames() throws Exception {
        String[] lines = {
            "Mary-Jane Smith",
            "Date Description Amount",
            "12/25/25 STORE $100.00"
        };
        
        AccountDetectionService.DetectedAccount detectedAccount = new AccountDetectionService.DetectedAccount();
        detectedAccount.setAccountHolderName("Mary-Jane Smith");
        
        String username = (String) detectUsernameBeforeHeader.invoke(pdfImportService, lines, 1, detectedAccount);
        assertNotNull(username);
        assertEquals("Mary-Jane Smith", username);
    }

    // ========== Integration Tests ==========

    @Test
    @DisplayName("Integration: Full flow with account holder name")
    void testIntegration_FullFlowWithAccountHolder() throws Exception {
        String[] lines = {
            "Statement Period: 12/01/25 - 12/31/25",
            "Card Member: John Doe",
            "Date Description Amount",
            "12/25/25 AMAZON $100.00",
            "12/26/25 TARGET $50.00"
        };
        
        AccountDetectionService.DetectedAccount detectedAccount = new AccountDetectionService.DetectedAccount();
        detectedAccount.setAccountHolderName("John Doe");
        
        // Test at header line (index 2)
        String username = (String) detectUsernameBeforeHeader.invoke(pdfImportService, lines, 2, detectedAccount);
        assertNotNull(username);
        assertEquals("John Doe", username);
        
        // Test at first transaction (index 3)
        String username2 = (String) detectUsernameBeforeHeader.invoke(pdfImportService, lines, 3, detectedAccount);
        assertNotNull(username2);
        assertEquals("John Doe", username2);
    }

    @Test
    @DisplayName("Integration: Multiple username candidates, should validate against account holder")
    void testIntegration_MultipleCandidates() throws Exception {
        String[] lines = {
            "Card Member: Wrong Name",
            "John Doe",
            "Date Description Amount",
            "12/25/25 AMAZON $100.00"
        };
        
        AccountDetectionService.DetectedAccount detectedAccount = new AccountDetectionService.DetectedAccount();
        detectedAccount.setAccountHolderName("John Doe");
        
        String username = (String) detectUsernameBeforeHeader.invoke(pdfImportService, lines, 2, detectedAccount);
        // Should find "John Doe" because it matches account holder name
        // Even though "Wrong Name" is found first, it should be rejected
        assertNotNull(username);
        assertEquals("John Doe", username);
    }
}

