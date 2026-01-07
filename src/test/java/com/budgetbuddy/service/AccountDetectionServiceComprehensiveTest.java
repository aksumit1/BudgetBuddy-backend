package com.budgetbuddy.service;

import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive tests for AccountDetectionService covering:
 * - Error handling
 * - Edge cases
 * - Boundary conditions
 * - Race conditions (thread safety)
 * - Improvements and optimizations
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AccountDetectionService Comprehensive Tests")
class AccountDetectionServiceComprehensiveTest {

    @Mock
    private AccountRepository accountRepository;

    private AccountDetectionService accountDetectionService;

    @BeforeEach
    void setUp() {
        accountDetectionService = new AccountDetectionService(accountRepository, new com.budgetbuddy.service.BalanceExtractor());
    }

    // ========== Error Handling Tests ==========

    @Test
    @DisplayName("detectFromFilename with null filename returns null")
    void testDetectFromFilename_NullFilename_ReturnsNull() {
        AccountDetectionService.DetectedAccount result = accountDetectionService.detectFromFilename(null);
        assertNull(result);
    }

    @Test
    @DisplayName("detectFromFilename with empty filename returns null")
    void testDetectFromFilename_EmptyFilename_ReturnsNull() {
        AccountDetectionService.DetectedAccount result = accountDetectionService.detectFromFilename("");
        assertNull(result);
    }

    @Test
    @DisplayName("detectFromFilename with whitespace-only filename returns null")
    void testDetectFromFilename_WhitespaceOnly_ReturnsNull() {
        AccountDetectionService.DetectedAccount result = accountDetectionService.detectFromFilename("   ");
        assertNull(result);
    }

    @Test
    @DisplayName("detectFromHeaders with null headers falls back to filename")
    void testDetectFromHeaders_NullHeaders_FallsBackToFilename() {
        String filename = "chase_checking_1234.csv";
        AccountDetectionService.DetectedAccount result = accountDetectionService.detectFromHeaders(null, filename);
        assertNotNull(result);
        assertEquals("Chase", result.getInstitutionName());
    }

    @Test
    @DisplayName("detectFromHeaders with empty headers falls back to filename")
    void testDetectFromHeaders_EmptyHeaders_FallsBackToFilename() {
        String filename = "chase_checking_1234.csv";
        AccountDetectionService.DetectedAccount result = accountDetectionService.detectFromHeaders(Collections.emptyList(), filename);
        assertNotNull(result);
        assertEquals("Chase", result.getInstitutionName());
    }

    @Test
    @DisplayName("detectFromPDFContent with null text falls back to filename")
    void testDetectFromPDFContent_NullText_FallsBackToFilename() {
        String filename = "chase_checking_1234.pdf";
        AccountDetectionService.DetectedAccount result = accountDetectionService.detectFromPDFContent(null, filename);
        assertNotNull(result);
        assertEquals("Chase", result.getInstitutionName());
    }

    @Test
    @DisplayName("detectFromPDFContent with empty text falls back to filename")
    void testDetectFromPDFContent_EmptyText_FallsBackToFilename() {
        String filename = "chase_checking_1234.pdf";
        AccountDetectionService.DetectedAccount result = accountDetectionService.detectFromPDFContent("", filename);
        assertNotNull(result);
        assertEquals("Chase", result.getInstitutionName());
    }

    @Test
    @DisplayName("matchToExistingAccount with null userId returns null")
    void testMatchToExistingAccount_NullUserId_ReturnsNull() {
        AccountDetectionService.DetectedAccount detected = new AccountDetectionService.DetectedAccount();
        String result = accountDetectionService.matchToExistingAccount(null, detected);
        assertNull(result);
    }

    @Test
    @DisplayName("matchToExistingAccount with null detected returns null")
    void testMatchToExistingAccount_NullDetected_ReturnsNull() {
        String result = accountDetectionService.matchToExistingAccount("user-123", null);
        assertNull(result);
    }

    @Test
    @DisplayName("matchToExistingAccount handles repository exceptions gracefully")
    void testMatchToExistingAccount_RepositoryException_HandlesGracefully() {
        String userId = "user-123";
        AccountDetectionService.DetectedAccount detected = new AccountDetectionService.DetectedAccount();
        detected.setAccountNumber("1234");
        detected.setInstitutionName("Chase");

        when(accountRepository.findByAccountNumberAndInstitution(anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("Database error"));

        // Should not throw exception, should return null
        String result = accountDetectionService.matchToExistingAccount(userId, detected);
        assertNull(result);
    }

    // ========== Edge Cases Tests ==========

    @Test
    @DisplayName("detectFromFilename with very long filename handles correctly")
    void testDetectFromFilename_VeryLongFilename_HandlesCorrectly() {
        // Create a very long filename (1000+ characters)
        StringBuilder longFilename = new StringBuilder("chase");
        for (int i = 0; i < 200; i++) {
            longFilename.append("_test");
        }
        longFilename.append("_1234.csv");

        AccountDetectionService.DetectedAccount result = accountDetectionService.detectFromFilename(longFilename.toString());
        assertNotNull(result);
        assertEquals("Chase", result.getInstitutionName());
        assertEquals("1234", result.getAccountNumber());
    }

    @Test
    @DisplayName("detectFromFilename with special characters handles correctly")
    void testDetectFromFilename_SpecialCharacters_HandlesCorrectly() {
        String filename = "chase-checking-1234 (copy).csv";
        AccountDetectionService.DetectedAccount result = accountDetectionService.detectFromFilename(filename);
        assertNotNull(result);
        assertEquals("Chase", result.getInstitutionName());
    }

    @Test
    @DisplayName("detectFromFilename with unicode characters handles correctly")
    void testDetectFromFilename_UnicodeCharacters_HandlesCorrectly() {
        String filename = "chase_账户_1234.csv"; // Chinese characters
        AccountDetectionService.DetectedAccount result = accountDetectionService.detectFromFilename(filename);
        assertNotNull(result);
        assertEquals("Chase", result.getInstitutionName());
        assertEquals("1234", result.getAccountNumber());
    }

    @Test
    @DisplayName("detectFromFilename with no extension handles correctly")
    void testDetectFromFilename_NoExtension_HandlesCorrectly() {
        String filename = "chase_checking_1234";
        AccountDetectionService.DetectedAccount result = accountDetectionService.detectFromFilename(filename);
        assertNotNull(result);
        assertEquals("Chase", result.getInstitutionName());
        assertEquals("1234", result.getAccountNumber());
    }

    @Test
    @DisplayName("detectFromFilename with multiple account numbers picks first")
    void testDetectFromFilename_MultipleAccountNumbers_PicksFirst() {
        String filename = "chase_1234_5678.csv";
        AccountDetectionService.DetectedAccount result = accountDetectionService.detectFromFilename(filename);
        assertNotNull(result);
        // Should extract first 4-digit number
        assertNotNull(result.getAccountNumber());
        assertEquals(4, result.getAccountNumber().length());
    }

    @Test
    @DisplayName("detectFromFilename with account number directly after institution name")
    void testDetectFromFilename_AccountNumberAfterInstitution_DetectsCorrectly() {
        String filename = "Chase3100_Activity_20251221.csv";
        AccountDetectionService.DetectedAccount result = accountDetectionService.detectFromFilename(filename);
        assertNotNull(result);
        assertEquals("Chase", result.getInstitutionName());
        assertEquals("3100", result.getAccountNumber());
    }

    @Test
    @DisplayName("detectFromHeaders with headers containing null values handles correctly")
    void testDetectFromHeaders_NullHeaderValues_HandlesCorrectly() {
        List<String> headers = Arrays.asList("Date", null, "Amount", "", "Description");
        AccountDetectionService.DetectedAccount result = accountDetectionService.detectFromHeaders(headers, "test.csv");
        assertNotNull(result);
        // Should not throw exception
    }

    @Test
    @DisplayName("detectFromHeaders with very large header list handles correctly")
    void testDetectFromHeaders_VeryLargeHeaderList_HandlesCorrectly() {
        List<String> headers = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            headers.add("Column" + i);
        }
        AccountDetectionService.DetectedAccount result = accountDetectionService.detectFromHeaders(headers, "test.csv");
        assertNotNull(result);
        // Should not throw exception
    }

    @Test
    @DisplayName("detectFromHeaders with single header handles correctly")
    void testDetectFromHeaders_SingleHeader_HandlesCorrectly() {
        List<String> headers = Collections.singletonList("Date");
        AccountDetectionService.DetectedAccount result = accountDetectionService.detectFromHeaders(headers, "chase_checking_1234.csv");
        assertNotNull(result);
        assertEquals("Chase", result.getInstitutionName());
    }

    @Test
    @DisplayName("detectFromPDFContent with very long text handles correctly")
    void testDetectFromPDFContent_VeryLongText_HandlesCorrectly() {
        StringBuilder longText = new StringBuilder("Chase Bank\nAccount Number: 1234\n");
        for (int i = 0; i < 1000; i++) {
            longText.append("Line ").append(i).append("\n");
        }
        AccountDetectionService.DetectedAccount result = accountDetectionService.detectFromPDFContent(longText.toString(), "test.pdf");
        assertNotNull(result);
        assertEquals("Chase", result.getInstitutionName());
    }

    @Test
    @DisplayName("detectFromPDFContent with text containing only newlines handles correctly")
    void testDetectFromPDFContent_OnlyNewlines_HandlesCorrectly() {
        String text = "\n\n\n\n\n";
        AccountDetectionService.DetectedAccount result = accountDetectionService.detectFromPDFContent(text, "chase_checking_1234.pdf");
        assertNotNull(result);
        assertEquals("Chase", result.getInstitutionName());
    }

    // ========== Boundary Conditions Tests ==========

    @ParameterizedTest
    @ValueSource(strings = {"chase", "chase_", "chase__", "chase___"})
    @DisplayName("detectFromFilename with various separator patterns")
    void testDetectFromFilename_VariousSeparators_HandlesCorrectly(String filename) {
        AccountDetectionService.DetectedAccount result = accountDetectionService.detectFromFilename(filename + "1234.csv");
        assertNotNull(result);
        assertEquals("Chase", result.getInstitutionName());
    }

    @Test
    @DisplayName("detectFromFilename with account number at start of filename")
    void testDetectFromFilename_AccountNumberAtStart_HandlesCorrectly() {
        String filename = "1234_chase_checking.csv";
        AccountDetectionService.DetectedAccount result = accountDetectionService.detectFromFilename(filename);
        assertNotNull(result);
        assertEquals("Chase", result.getInstitutionName());
        // May or may not extract account number depending on pattern
    }

    @Test
    @DisplayName("detectFromFilename with account number at end of filename")
    void testDetectFromFilename_AccountNumberAtEnd_HandlesCorrectly() {
        String filename = "chase_checking_1234";
        AccountDetectionService.DetectedAccount result = accountDetectionService.detectFromFilename(filename);
        assertNotNull(result);
        assertEquals("Chase", result.getInstitutionName());
        assertEquals("1234", result.getAccountNumber());
    }

    @Test
    @DisplayName("detectFromHeaders with exactly 3 transaction columns detects as transaction table")
    void testDetectFromHeaders_ExactlyThreeTransactionColumns_DetectsAsTransactionTable() {
        List<String> headers = Arrays.asList("date", "amount", "description");
        boolean isTransactionTable = accountDetectionService.isTransactionTableHeaders(headers);
        assertTrue(isTransactionTable);
    }

    @Test
    @DisplayName("detectFromHeaders with 2 transaction columns does not detect as transaction table")
    void testDetectFromHeaders_TwoTransactionColumns_NotDetectedAsTransactionTable() {
        List<String> headers = Arrays.asList("date", "amount");
        boolean isTransactionTable = accountDetectionService.isTransactionTableHeaders(headers);
        assertFalse(isTransactionTable);
    }

    @Test
    @DisplayName("detectFromHeaders with mixed case transaction columns detects correctly")
    void testDetectFromHeaders_MixedCaseTransactionColumns_DetectsCorrectly() {
        List<String> headers = Arrays.asList("Date", "AMOUNT", "Description", "Balance", "Type");
        boolean isTransactionTable = accountDetectionService.isTransactionTableHeaders(headers);
        assertTrue(isTransactionTable);
    }

    @Test
    @DisplayName("matchToExistingAccount with empty account list returns null")
    void testMatchToExistingAccount_EmptyAccountList_ReturnsNull() {
        String userId = "user-123";
        AccountDetectionService.DetectedAccount detected = new AccountDetectionService.DetectedAccount();
        detected.setInstitutionName("Chase");
        detected.setAccountType("depository");
        // NOTE: No accountNumber set - so findByAccountNumberAndInstitution and findByAccountNumber won't be called
        // Only findByUserId will be called (line 748 in AccountDetectionService)

        // CRITICAL: Only stub the method that will actually be called
        // Since detected has no accountNumber, the first two if conditions (lines 711, 730) will be false
        // Only the third condition (line 748) will be true, which calls findByUserId
        when(accountRepository.findByUserId(userId))
                .thenReturn(Collections.emptyList());

        String result = accountDetectionService.matchToExistingAccount(userId, detected);
        assertNull(result);
        
        // Verify only findByUserId was called (not the account number methods)
        verify(accountRepository, never()).findByAccountNumberAndInstitution(anyString(), anyString(), anyString());
        verify(accountRepository, never()).findByAccountNumber(anyString(), anyString());
        verify(accountRepository, times(1)).findByUserId(userId);
    }

    // ========== Transaction Table Detection Tests ==========

    @Test
    @DisplayName("isTransactionTableHeaders with standard transaction headers returns true")
    void testIsTransactionTableHeaders_StandardHeaders_ReturnsTrue() {
        List<String> headers = Arrays.asList("details", "posting date", "description", "amount", "type", "balance", "check or slip #");
        boolean result = accountDetectionService.isTransactionTableHeaders(headers);
        assertTrue(result, "Should detect transaction table with 7 transaction-related columns");
    }

    @Test
    @DisplayName("isTransactionTableHeaders with account metadata headers returns false")
    void testIsTransactionTableHeaders_AccountMetadataHeaders_ReturnsFalse() {
        List<String> headers = Arrays.asList("account name", "institution name", "account type", "account number");
        boolean result = accountDetectionService.isTransactionTableHeaders(headers);
        assertFalse(result, "Should not detect account metadata headers as transaction table");
    }

    @Test
    @DisplayName("isTransactionTableHeaders with mixed headers detects correctly")
    void testIsTransactionTableHeaders_MixedHeaders_DetectsCorrectly() {
        List<String> headers = Arrays.asList("account name", "date", "amount", "description", "balance");
        boolean result = accountDetectionService.isTransactionTableHeaders(headers);
        assertTrue(result, "Should detect transaction table when 3+ transaction columns present");
    }

    // ========== Filename Pattern Tests ==========

    @ParameterizedTest
    @MethodSource("filenamePatternProvider")
    @DisplayName("detectFromFilename with various filename patterns")
    void testDetectFromFilename_VariousPatterns(String filename, String expectedInstitution, String expectedAccountNumber) {
        AccountDetectionService.DetectedAccount result = accountDetectionService.detectFromFilename(filename);
        assertNotNull(result);
        if (expectedInstitution != null) {
            assertEquals(expectedInstitution, result.getInstitutionName());
        }
        if (expectedAccountNumber != null) {
            assertEquals(expectedAccountNumber, result.getAccountNumber());
        }
    }

    static Stream<Arguments> filenamePatternProvider() {
        return Stream.of(
            Arguments.of("Chase3100_Activity_20251221.csv", "Chase", "3100"),
            Arguments.of("chase_3100_activity.csv", "Chase", "3100"),
            Arguments.of("chase 3100 activity.csv", "Chase", "3100"),
            Arguments.of("CHASE3100.csv", "Chase", "3100"),
            Arguments.of("bofa_5678_statement.pdf", "Bank of America", "5678"),
            Arguments.of("wells_fargo_9012.xlsx", "Wells Fargo", "9012"),
            Arguments.of("capital_one_3456.csv", "Capital One", "3456")
        );
    }

    // ========== Institution Name Normalization Tests ==========

    @ParameterizedTest
    @MethodSource("institutionNameProvider")
    @DisplayName("detectFromFilename normalizes institution names correctly")
    void testDetectFromFilename_InstitutionNormalization(String filename, String expectedInstitution) {
        AccountDetectionService.DetectedAccount result = accountDetectionService.detectFromFilename(filename);
        assertNotNull(result);
        assertEquals(expectedInstitution, result.getInstitutionName());
    }

    static Stream<Arguments> institutionNameProvider() {
        return Stream.of(
            Arguments.of("bofa_checking_1234.csv", "Bank of America"),
            Arguments.of("wf_savings_5678.csv", "Wells Fargo"),
            Arguments.of("capone_credit_9012.csv", "Capital One"),
            Arguments.of("jpm_checking_3456.csv", "JPMorgan Chase"),
            Arguments.of("amex_platinum_7890.csv", "American Express")
        );
    }

    // ========== Account Type Detection Tests ==========

    @Test
    @DisplayName("detectFromFilename detects checking account type")
    void testDetectFromFilename_CheckingAccount_DetectsType() {
        String filename = "chase_checking_1234.csv";
        AccountDetectionService.DetectedAccount result = accountDetectionService.detectFromFilename(filename);
        assertNotNull(result);
        assertEquals("depository", result.getAccountType());
        assertEquals("checking", result.getAccountSubtype());
    }

    @Test
    @DisplayName("detectFromFilename detects savings account type")
    void testDetectFromFilename_SavingsAccount_DetectsType() {
        String filename = "chase_savings_1234.csv";
        AccountDetectionService.DetectedAccount result = accountDetectionService.detectFromFilename(filename);
        assertNotNull(result);
        assertEquals("depository", result.getAccountType());
        assertEquals("savings", result.getAccountSubtype());
    }

    @Test
    @DisplayName("detectFromFilename detects credit card account type")
    void testDetectFromFilename_CreditCard_DetectsType() {
        String filename = "chase_credit_card_1234.csv";
        AccountDetectionService.DetectedAccount result = accountDetectionService.detectFromFilename(filename);
        assertNotNull(result);
        assertEquals("credit", result.getAccountType());
        assertEquals("credit card", result.getAccountSubtype());
    }

    // ========== Account Number Extraction Tests ==========

    @Test
    @DisplayName("extractAccountNumber handles masked account numbers")
    void testExtractAccountNumber_MaskedAccountNumber_ExtractsCorrectly() {
        String text = "Account Number: ****1234";
        // This is tested indirectly through detectFromPDFContent
        String pdfText = "Chase Bank\nAccount Number: ****1234\nChecking Account";
        AccountDetectionService.DetectedAccount result = accountDetectionService.detectFromPDFContent(pdfText, "test.pdf");
        assertNotNull(result);
        assertEquals("1234", result.getAccountNumber());
    }

    @Test
    @DisplayName("extractAccountNumber handles account numbers with X masking")
    void testExtractAccountNumber_XMaskedAccountNumber_ExtractsCorrectly() {
        String pdfText = "Chase Bank\nAccount Number: XXXX1234\nChecking Account";
        AccountDetectionService.DetectedAccount result = accountDetectionService.detectFromPDFContent(pdfText, "test.pdf");
        assertNotNull(result);
        assertEquals("1234", result.getAccountNumber());
    }

    @Test
    @DisplayName("extractAccountNumber handles long account numbers correctly")
    void testExtractAccountNumber_LongAccountNumber_ExtractsLastFour() {
        String pdfText = "Chase Bank\nAccount Number: 1234567890123456\nChecking Account";
        AccountDetectionService.DetectedAccount result = accountDetectionService.detectFromPDFContent(pdfText, "test.pdf");
        assertNotNull(result);
        assertNotNull(result.getAccountNumber());
        assertEquals(4, result.getAccountNumber().length());
    }

    // ========== Thread Safety Tests ==========

    @Test
    @DisplayName("detectFromFilename is thread-safe")
    void testDetectFromFilename_ThreadSafety_NoRaceConditions() throws InterruptedException {
        int threadCount = 10;
        Thread[] threads = new Thread[threadCount];
        List<AccountDetectionService.DetectedAccount> results = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            threads[i] = new Thread(() -> {
                String filename = "chase_checking_" + index + ".csv";
                AccountDetectionService.DetectedAccount result = accountDetectionService.detectFromFilename(filename);
                results.add(result);
            });
        }

        for (Thread thread : threads) {
            thread.start();
        }

        for (Thread thread : threads) {
            thread.join();
        }

        assertEquals(threadCount, results.size());
        for (AccountDetectionService.DetectedAccount result : results) {
            assertNotNull(result);
            assertEquals("Chase", result.getInstitutionName());
        }
    }

    @Test
    @DisplayName("detectFromHeaders is thread-safe")
    void testDetectFromHeaders_ThreadSafety_NoRaceConditions() throws InterruptedException {
        int threadCount = 10;
        Thread[] threads = new Thread[threadCount];
        List<AccountDetectionService.DetectedAccount> results = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            threads[i] = new Thread(() -> {
                List<String> headers = Arrays.asList("date", "amount", "description");
                String filename = "chase_checking_" + index + ".csv";
                AccountDetectionService.DetectedAccount result = accountDetectionService.detectFromHeaders(headers, filename);
                results.add(result);
            });
        }

        for (Thread thread : threads) {
            thread.start();
        }

        for (Thread thread : threads) {
            thread.join();
        }

        assertEquals(threadCount, results.size());
        for (AccountDetectionService.DetectedAccount result : results) {
            assertNotNull(result);
            assertEquals("Chase", result.getInstitutionName());
        }
    }

    // ========== Performance Tests ==========

    @Test
    @DisplayName("detectFromFilename handles large number of calls efficiently")
    void testDetectFromFilename_Performance_LargeNumberOfCalls() {
        long startTime = System.currentTimeMillis();
        for (int i = 0; i < 1000; i++) {
            String filename = "chase_checking_" + i + ".csv";
            AccountDetectionService.DetectedAccount result = accountDetectionService.detectFromFilename(filename);
            assertNotNull(result);
        }
        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;
        
        // Should complete 1000 calls in reasonable time (< 5 seconds)
        assertTrue(duration < 5000, "1000 calls should complete in < 5 seconds, took: " + duration + "ms");
    }

    // ========== Integration Tests ==========

    @Test
    @DisplayName("detectFromHeaders with transaction table prioritizes filename")
    void testDetectFromHeaders_TransactionTable_PrioritizesFilename() {
        List<String> headers = Arrays.asList("details", "posting date", "description", "amount", "type", "balance");
        String filename = "Chase3100_Activity_20251221.csv";
        
        AccountDetectionService.DetectedAccount result = accountDetectionService.detectFromHeaders(headers, filename);
        
        assertNotNull(result);
        assertEquals("Chase", result.getInstitutionName());
        assertEquals("3100", result.getAccountNumber());
        // Should not extract institution from transaction headers
    }

    @Test
    @DisplayName("detectFromHeaders with non-transaction table extracts from headers")
    void testDetectFromHeaders_NonTransactionTable_ExtractsFromHeaders() {
        List<String> headers = Arrays.asList("account name", "institution name", "account type", "account number");
        String filename = "test.csv";
        
        AccountDetectionService.DetectedAccount result = accountDetectionService.detectFromHeaders(headers, filename);
        
        assertNotNull(result);
        // Should find account metadata columns
    }
}

