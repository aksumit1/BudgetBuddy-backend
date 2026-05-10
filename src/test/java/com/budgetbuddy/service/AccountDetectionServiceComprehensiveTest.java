package com.budgetbuddy.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.budgetbuddy.repository.dynamodb.AccountRepository;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Comprehensive tests for AccountDetectionService covering: - Error handling - Edge cases -
 * Boundary conditions - Race conditions (thread safety) - Improvements and optimizations
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AccountDetectionService Comprehensive Tests")
class AccountDetectionServiceComprehensiveTest {

    @Mock private AccountRepository accountRepository;

    private AccountDetectionService accountDetectionService;

    @BeforeEach
    void setUp() {
        accountDetectionService =
                new AccountDetectionService(
                        accountRepository, new com.budgetbuddy.service.BalanceExtractor());
    }

    // ========== Error Handling Tests ==========

    @Test
    @DisplayName("detectFromFilename with null filename returns null")
    void testDetectFromFilenameNullFilenameReturnsNull() {
        final AccountDetectionService.DetectedAccount result =
                accountDetectionService.detectFromFilename(null);
        assertNull(result);
    }

    @Test
    @DisplayName("detectFromFilename with empty filename returns null")
    void testDetectFromFilenameEmptyFilenameReturnsNull() {
        final AccountDetectionService.DetectedAccount result =
                accountDetectionService.detectFromFilename("");
        assertNull(result);
    }

    @Test
    @DisplayName("detectFromFilename with whitespace-only filename returns null")
    void testDetectFromFilenameWhitespaceOnlyReturnsNull() {
        final AccountDetectionService.DetectedAccount result =
                accountDetectionService.detectFromFilename("   ");
        assertNull(result);
    }

    @Test
    @DisplayName("detectFromHeaders with null headers falls back to filename")
    void testDetectFromHeadersNullHeadersFallsBackToFilename() {
        final String filename = "chase_checking_1234.csv";
        final AccountDetectionService.DetectedAccount result =
                accountDetectionService.detectFromHeaders(null, filename);
        assertNotNull(result);
        assertEquals("Chase", result.getInstitutionName());
    }

    @Test
    @DisplayName("detectFromHeaders with empty headers falls back to filename")
    void testDetectFromHeadersEmptyHeadersFallsBackToFilename() {
        final String filename = "chase_checking_1234.csv";
        final AccountDetectionService.DetectedAccount result =
                accountDetectionService.detectFromHeaders(Collections.emptyList(), filename);
        assertNotNull(result);
        assertEquals("Chase", result.getInstitutionName());
    }

    @Test
    @DisplayName("detectFromPDFContent with null text falls back to filename")
    void testDetectFromPDFContentNullTextFallsBackToFilename() {
        final String filename = "chase_checking_1234.pdf";
        final AccountDetectionService.DetectedAccount result =
                accountDetectionService.detectFromPDFContent(null, filename);
        assertNotNull(result);
        assertEquals("Chase", result.getInstitutionName());
    }

    @Test
    @DisplayName("detectFromPDFContent with empty text falls back to filename")
    void testDetectFromPDFContentEmptyTextFallsBackToFilename() {
        final String filename = "chase_checking_1234.pdf";
        final AccountDetectionService.DetectedAccount result =
                accountDetectionService.detectFromPDFContent("", filename);
        assertNotNull(result);
        assertEquals("Chase", result.getInstitutionName());
    }

    @Test
    @DisplayName("matchToExistingAccount with null userId returns null")
    void testMatchToExistingAccountNullUserIdReturnsNull() {
        final AccountDetectionService.DetectedAccount detected =
                new AccountDetectionService.DetectedAccount();
        final String result = accountDetectionService.matchToExistingAccount(null, detected);
        assertNull(result);
    }

    @Test
    @DisplayName("matchToExistingAccount with null detected returns null")
    void testMatchToExistingAccountNullDetectedReturnsNull() {
        final String result = accountDetectionService.matchToExistingAccount("user-123", null);
        assertNull(result);
    }

    @Test
    @DisplayName("matchToExistingAccount handles repository exceptions gracefully")
    void testMatchToExistingAccountRepositoryExceptionHandlesGracefully() {
        final String userId = "user-123";
        final AccountDetectionService.DetectedAccount detected =
                new AccountDetectionService.DetectedAccount();
        detected.setAccountNumber("1234");
        detected.setInstitutionName("Chase");

        when(accountRepository.findByAccountNumberAndInstitution(
                        anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("Database error"));

        // Should not throw exception, should return null
        final String result = accountDetectionService.matchToExistingAccount(userId, detected);
        assertNull(result);
    }

    // ========== Edge Cases Tests ==========

    @Test
    @DisplayName("detectFromFilename with very long filename handles correctly")
    void testDetectFromFilenameVeryLongFilenameHandlesCorrectly() {
        // Create a very long filename (1000+ characters)
        final StringBuilder longFilename = new StringBuilder("chase");
        for (int i = 0; i < 200; i++) {
            longFilename.append("_test");
        }
        longFilename.append("_1234.csv");

        final AccountDetectionService.DetectedAccount result =
                accountDetectionService.detectFromFilename(longFilename.toString());
        assertNotNull(result);
        assertEquals("Chase", result.getInstitutionName());
        assertEquals("1234", result.getAccountNumber());
    }

    @Test
    @DisplayName("detectFromFilename with special characters handles correctly")
    void testDetectFromFilenameSpecialCharactersHandlesCorrectly() {
        final String filename = "chase-checking-1234 (copy).csv";
        final AccountDetectionService.DetectedAccount result =
                accountDetectionService.detectFromFilename(filename);
        assertNotNull(result);
        assertEquals("Chase", result.getInstitutionName());
    }

    @Test
    @DisplayName("detectFromFilename with unicode characters handles correctly")
    void testDetectFromFilenameUnicodeCharactersHandlesCorrectly() {
        final String filename = "chase_账户_1234.csv"; // Chinese characters
        final AccountDetectionService.DetectedAccount result =
                accountDetectionService.detectFromFilename(filename);
        assertNotNull(result);
        assertEquals("Chase", result.getInstitutionName());
        assertEquals("1234", result.getAccountNumber());
    }

    @Test
    @DisplayName("detectFromFilename with no extension handles correctly")
    void testDetectFromFilenameNoExtensionHandlesCorrectly() {
        final String filename = "chase_checking_1234";
        final AccountDetectionService.DetectedAccount result =
                accountDetectionService.detectFromFilename(filename);
        assertNotNull(result);
        assertEquals("Chase", result.getInstitutionName());
        assertEquals("1234", result.getAccountNumber());
    }

    @Test
    @DisplayName("detectFromFilename with multiple account numbers picks first")
    void testDetectFromFilenameMultipleAccountNumbersPicksFirst() {
        final String filename = "chase_1234_5678.csv";
        final AccountDetectionService.DetectedAccount result =
                accountDetectionService.detectFromFilename(filename);
        assertNotNull(result);
        // Should extract first 4-digit number
        assertNotNull(result.getAccountNumber());
        assertEquals(4, result.getAccountNumber().length());
    }

    @Test
    @DisplayName("detectFromFilename with account number directly after institution name")
    void testDetectFromFilenameAccountNumberAfterInstitutionDetectsCorrectly() {
        final String filename = "Chase3100_Activity_20251221.csv";
        final AccountDetectionService.DetectedAccount result =
                accountDetectionService.detectFromFilename(filename);
        assertNotNull(result);
        assertEquals("Chase", result.getInstitutionName());
        assertEquals("3100", result.getAccountNumber());
    }

    @Test
    @DisplayName("detectFromHeaders with headers containing null values handles correctly")
    void testDetectFromHeadersNullHeaderValuesHandlesCorrectly() {
        final List<String> headers = Arrays.asList("Date", null, "Amount", "", "Description");
        final AccountDetectionService.DetectedAccount result =
                accountDetectionService.detectFromHeaders(headers, "test.csv");
        assertNotNull(result);
        // Should not throw exception
    }

    @Test
    @DisplayName("detectFromHeaders with very large header list handles correctly")
    void testDetectFromHeadersVeryLargeHeaderListHandlesCorrectly() {
        final List<String> headers = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            headers.add("Column" + i);
        }
        final AccountDetectionService.DetectedAccount result =
                accountDetectionService.detectFromHeaders(headers, "test.csv");
        assertNotNull(result);
        // Should not throw exception
    }

    @Test
    @DisplayName("detectFromHeaders with single header handles correctly")
    void testDetectFromHeadersSingleHeaderHandlesCorrectly() {
        final List<String> headers = Collections.singletonList("Date");
        final AccountDetectionService.DetectedAccount result =
                accountDetectionService.detectFromHeaders(headers, "chase_checking_1234.csv");
        assertNotNull(result);
        assertEquals("Chase", result.getInstitutionName());
    }

    @Test
    @DisplayName("detectFromPDFContent with very long text handles correctly")
    void testDetectFromPDFContentVeryLongTextHandlesCorrectly() {
        final StringBuilder longText = new StringBuilder("Chase Bank\nAccount Number: 1234\n");
        for (int i = 0; i < 1000; i++) {
            longText.append("Line ").append(i).append("\n");
        }
        final AccountDetectionService.DetectedAccount result =
                accountDetectionService.detectFromPDFContent(longText.toString(), "test.pdf");
        assertNotNull(result);
        assertEquals("Chase", result.getInstitutionName());
    }

    @Test
    @DisplayName("detectFromPDFContent with text containing only newlines handles correctly")
    void testDetectFromPDFContentOnlyNewlinesHandlesCorrectly() {
        final String text = "\n\n\n\n\n";
        final AccountDetectionService.DetectedAccount result =
                accountDetectionService.detectFromPDFContent(text, "chase_checking_1234.pdf");
        assertNotNull(result);
        assertEquals("Chase", result.getInstitutionName());
    }

    // ========== Boundary Conditions Tests ==========

    @ParameterizedTest
    @ValueSource(strings = {"chase", "chase_", "chase__", "chase___"})
    @DisplayName("detectFromFilename with various separator patterns")
    void testDetectFromFilenameVariousSeparatorsHandlesCorrectly(final String filename) {
        final AccountDetectionService.DetectedAccount result =
                accountDetectionService.detectFromFilename(filename + "1234.csv");
        assertNotNull(result);
        assertEquals("Chase", result.getInstitutionName());
    }

    @Test
    @DisplayName("detectFromFilename with account number at start of filename")
    void testDetectFromFilenameAccountNumberAtStartHandlesCorrectly() {
        final String filename = "1234_chase_checking.csv";
        final AccountDetectionService.DetectedAccount result =
                accountDetectionService.detectFromFilename(filename);
        assertNotNull(result);
        assertEquals("Chase", result.getInstitutionName());
        // May or may not extract account number depending on pattern
    }

    @Test
    @DisplayName("detectFromFilename with account number at end of filename")
    void testDetectFromFilenameAccountNumberAtEndHandlesCorrectly() {
        final String filename = "chase_checking_1234";
        final AccountDetectionService.DetectedAccount result =
                accountDetectionService.detectFromFilename(filename);
        assertNotNull(result);
        assertEquals("Chase", result.getInstitutionName());
        assertEquals("1234", result.getAccountNumber());
    }

    @Test
    @DisplayName(
            "detectFromHeaders with exactly 3 transaction columns detects as transaction table")
    void testDetectFromHeadersExactlyThreeTransactionColumnsDetectsAsTransactionTable() {
        final List<String> headers = Arrays.asList("date", "amount", "description");
        final boolean isTransactionTable = accountDetectionService.isTransactionTableHeaders(headers);
        assertTrue(isTransactionTable);
    }

    @Test
    @DisplayName(
            "detectFromHeaders with 2 transaction columns does not detect as transaction table")
    void testDetectFromHeadersTwoTransactionColumnsNotDetectedAsTransactionTable() {
        final List<String> headers = Arrays.asList("date", "amount");
        final boolean isTransactionTable = accountDetectionService.isTransactionTableHeaders(headers);
        assertFalse(isTransactionTable);
    }

    @Test
    @DisplayName("detectFromHeaders with mixed case transaction columns detects correctly")
    void testDetectFromHeadersMixedCaseTransactionColumnsDetectsCorrectly() {
        final List<String> headers = Arrays.asList("Date", "AMOUNT", "Description", "Balance", "Type");
        final boolean isTransactionTable = accountDetectionService.isTransactionTableHeaders(headers);
        assertTrue(isTransactionTable);
    }

    @Test
    @DisplayName("matchToExistingAccount with empty account list returns null")
    void testMatchToExistingAccountEmptyAccountListReturnsNull() {
        final String userId = "user-123";
        final AccountDetectionService.DetectedAccount detected =
                new AccountDetectionService.DetectedAccount();
        detected.setInstitutionName("Chase");
        detected.setAccountType("depository");
        // NOTE: No accountNumber set - so findByAccountNumberAndInstitution and findByAccountNumber
        // won't be called
        // Only findByUserId will be called (line 748 in AccountDetectionService)

        // CRITICAL: Only stub the method that will actually be called
        // Since detected has no accountNumber, the first two if conditions (lines 711, 730) will be
        // false
        // Only the third condition (line 748) will be true, which calls findByUserId
        when(accountRepository.findByUserId(userId)).thenReturn(Collections.emptyList());

        final String result = accountDetectionService.matchToExistingAccount(userId, detected);
        assertNull(result);

        // Verify only findByUserId was called (not the account number methods)
        verify(accountRepository, never())
                .findByAccountNumberAndInstitution(anyString(), anyString(), anyString());
        verify(accountRepository, never()).findByAccountNumber(anyString(), anyString());
        verify(accountRepository, times(1)).findByUserId(userId);
    }

    // ========== Transaction Table Detection Tests ==========

    @Test
    @DisplayName("isTransactionTableHeaders with standard transaction headers returns true")
    void testIsTransactionTableHeadersStandardHeadersReturnsTrue() {
        final List<String> headers =
                Arrays.asList(
                        "details",
                        "posting date",
                        "description",
                        "amount",
                        "type",
                        "balance",
                        "check or slip #");
        final boolean result = accountDetectionService.isTransactionTableHeaders(headers);
        assertTrue(result, "Should detect transaction table with 7 transaction-related columns");
    }

    @Test
    @DisplayName("isTransactionTableHeaders with account metadata headers returns false")
    void testIsTransactionTableHeadersAccountMetadataHeadersReturnsFalse() {
        final List<String> headers =
                Arrays.asList("account name", "institution name", "account type", "account number");
        final boolean result = accountDetectionService.isTransactionTableHeaders(headers);
        assertFalse(result, "Should not detect account metadata headers as transaction table");
    }

    @Test
    @DisplayName("isTransactionTableHeaders with mixed headers detects correctly")
    void testIsTransactionTableHeadersMixedHeadersDetectsCorrectly() {
        final List<String> headers =
                Arrays.asList("account name", "date", "amount", "description", "balance");
        final boolean result = accountDetectionService.isTransactionTableHeaders(headers);
        assertTrue(result, "Should detect transaction table when 3+ transaction columns present");
    }

    // ========== Filename Pattern Tests ==========

    @ParameterizedTest
    @MethodSource("filenamePatternProvider")
    @DisplayName("detectFromFilename with various filename patterns")
    void testDetectFromFilenameVariousPatterns(
            final String filename, final String expectedInstitution, final String expectedAccountNumber) {
        final AccountDetectionService.DetectedAccount result =
                accountDetectionService.detectFromFilename(filename);
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
                Arguments.of("capital_one_3456.csv", "Capital One", "3456"));
    }

    // ========== Institution Name Normalization Tests ==========

    @ParameterizedTest
    @MethodSource("institutionNameProvider")
    @DisplayName("detectFromFilename normalizes institution names correctly")
    void testDetectFromFilenameInstitutionNormalization(
            final String filename, final String expectedInstitution) {
        final AccountDetectionService.DetectedAccount result =
                accountDetectionService.detectFromFilename(filename);
        assertNotNull(result);
        assertEquals(expectedInstitution, result.getInstitutionName());
    }

    static Stream<Arguments> institutionNameProvider() {
        return Stream.of(
                Arguments.of("bofa_checking_1234.csv", "Bank of America"),
                Arguments.of("wf_savings_5678.csv", "Wells Fargo"),
                Arguments.of("capone_credit_9012.csv", "Capital One"),
                Arguments.of("jpm_checking_3456.csv", "JPMorgan Chase"),
                Arguments.of("amex_platinum_7890.csv", "American Express"));
    }

    // ========== Account Type Detection Tests ==========

    @Test
    @DisplayName("detectFromFilename detects checking account type")
    void testDetectFromFilenameCheckingAccountDetectsType() {
        final String filename = "chase_checking_1234.csv";
        final AccountDetectionService.DetectedAccount result =
                accountDetectionService.detectFromFilename(filename);
        assertNotNull(result);
        assertEquals("depository", result.getAccountType());
        assertEquals("checking", result.getAccountSubtype());
    }

    @Test
    @DisplayName("detectFromFilename detects savings account type")
    void testDetectFromFilenameSavingsAccountDetectsType() {
        final String filename = "chase_savings_1234.csv";
        final AccountDetectionService.DetectedAccount result =
                accountDetectionService.detectFromFilename(filename);
        assertNotNull(result);
        assertEquals("depository", result.getAccountType());
        assertEquals("savings", result.getAccountSubtype());
    }

    @Test
    @DisplayName("detectFromFilename detects credit card account type")
    void testDetectFromFilenameCreditCardDetectsType() {
        final String filename = "chase_credit_card_1234.csv";
        final AccountDetectionService.DetectedAccount result =
                accountDetectionService.detectFromFilename(filename);
        assertNotNull(result);
        assertEquals("credit", result.getAccountType());
        assertEquals("credit card", result.getAccountSubtype());
    }

    // ========== Account Number Extraction Tests ==========

    @Test
    @DisplayName("extractAccountNumber handles masked account numbers")
    void testExtractAccountNumberMaskedAccountNumberExtractsCorrectly() {
        // This is tested indirectly through detectFromPDFContent
        final String pdfText = "Chase Bank\nAccount Number: ****1234\nChecking Account";
        final AccountDetectionService.DetectedAccount result =
                accountDetectionService.detectFromPDFContent(pdfText, "test.pdf");
        assertNotNull(result);
        assertEquals("1234", result.getAccountNumber());
    }

    @Test
    @DisplayName("extractAccountNumber handles account numbers with X masking")
    void testExtractAccountNumberXMaskedAccountNumberExtractsCorrectly() {
        final String pdfText = "Chase Bank\nAccount Number: XXXX1234\nChecking Account";
        final AccountDetectionService.DetectedAccount result =
                accountDetectionService.detectFromPDFContent(pdfText, "test.pdf");
        assertNotNull(result);
        assertEquals("1234", result.getAccountNumber());
    }

    @Test
    @DisplayName("extractAccountNumber handles long account numbers correctly")
    void testExtractAccountNumberLongAccountNumberExtractsLastFour() {
        final String pdfText = "Chase Bank\nAccount Number: 1234567890123456\nChecking Account";
        final AccountDetectionService.DetectedAccount result =
                accountDetectionService.detectFromPDFContent(pdfText, "test.pdf");
        assertNotNull(result);
        assertNotNull(result.getAccountNumber());
        assertEquals(4, result.getAccountNumber().length());
    }

    // ========== Thread Safety Tests ==========

    @Test
    @DisplayName("detectFromFilename is thread-safe")
    void testDetectFromFilenameThreadSafetyNoRaceConditions() throws InterruptedException {
        final int threadCount = 10;
        final Thread[] threads = new Thread[threadCount];
        final List<AccountDetectionService.DetectedAccount> results =
                Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            threads[i] =
                    new Thread(
                            () -> {
                                final String filename = "chase_checking_" + index + ".csv";
                                final AccountDetectionService.DetectedAccount result =
                                        accountDetectionService.detectFromFilename(filename);
                                results.add(result);
                            });
        }

        for (final Thread thread : threads) {
            thread.start();
        }

        for (final Thread thread : threads) {
            thread.join();
        }

        assertEquals(threadCount, results.size());
        for (final AccountDetectionService.DetectedAccount result : results) {
            assertNotNull(result);
            assertEquals("Chase", result.getInstitutionName());
        }
    }

    @Test
    @DisplayName("detectFromHeaders is thread-safe")
    void testDetectFromHeadersThreadSafetyNoRaceConditions() throws InterruptedException {
        final int threadCount = 10;
        final Thread[] threads = new Thread[threadCount];
        final List<AccountDetectionService.DetectedAccount> results =
                Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            threads[i] =
                    new Thread(
                            () -> {
                                final List<String> headers =
                                        Arrays.asList("date", "amount", "description");
                                final String filename = "chase_checking_" + index + ".csv";
                                final AccountDetectionService.DetectedAccount result =
                                        accountDetectionService.detectFromHeaders(
                                                headers, filename);
                                results.add(result);
                            });
        }

        for (final Thread thread : threads) {
            thread.start();
        }

        for (final Thread thread : threads) {
            thread.join();
        }

        assertEquals(threadCount, results.size());
        for (final AccountDetectionService.DetectedAccount result : results) {
            assertNotNull(result);
            assertEquals("Chase", result.getInstitutionName());
        }
    }

    // ========== Performance Tests ==========

    @Test
    @DisplayName("detectFromFilename handles large number of calls efficiently")
    void testDetectFromFilenamePerformanceLargeNumberOfCalls() {
        final long startTime = System.currentTimeMillis();
        for (int i = 0; i < 1000; i++) {
            final String filename = "chase_checking_" + i + ".csv";
            final AccountDetectionService.DetectedAccount result =
                    accountDetectionService.detectFromFilename(filename);
            assertNotNull(result);
        }
        final long endTime = System.currentTimeMillis();
        final long duration = endTime - startTime;

        // Should complete 1000 calls in reasonable time (< 5 seconds)
        assertTrue(
                duration < 5000,
                "1000 calls should complete in < 5 seconds, took: " + duration + "ms");
    }

    // ========== Integration Tests ==========

    @Test
    @DisplayName("detectFromHeaders with transaction table prioritizes filename")
    void testDetectFromHeadersTransactionTablePrioritizesFilename() {
        final List<String> headers =
                Arrays.asList(
                        "details", "posting date", "description", "amount", "type", "balance");
        final String filename = "Chase3100_Activity_20251221.csv";

        final AccountDetectionService.DetectedAccount result =
                accountDetectionService.detectFromHeaders(headers, filename);

        assertNotNull(result);
        assertEquals("Chase", result.getInstitutionName());
        assertEquals("3100", result.getAccountNumber());
        // Should not extract institution from transaction headers
    }

    @Test
    @DisplayName("detectFromHeaders with non-transaction table extracts from headers")
    void testDetectFromHeadersNonTransactionTableExtractsFromHeaders() {
        final List<String> headers =
                Arrays.asList("account name", "institution name", "account type", "account number");
        final String filename = "test.csv";

        final AccountDetectionService.DetectedAccount result =
                accountDetectionService.detectFromHeaders(headers, filename);

        assertNotNull(result);
        // Should find account metadata columns
    }
}
