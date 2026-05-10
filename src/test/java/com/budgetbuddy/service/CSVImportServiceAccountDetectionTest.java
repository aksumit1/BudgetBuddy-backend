package com.budgetbuddy.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyList;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Tests for CSVImportService with account detection integration */
// Tests intentionally pass null to verify graceful handling /
// AppException paths; SpotBugs's NP_LOAD_OF_KNOWN_NULL_VALUE is expected.
@SuppressFBWarnings(
        value = "NP_LOAD_OF_KNOWN_NULL_VALUE",
        justification = "Tests deliberately exercise null-input paths")
@ExtendWith(MockitoExtension.class)
class CSVImportServiceAccountDetectionTest {

    @Mock private AccountDetectionService accountDetectionService;

    private CSVImportService csvImportService;

    @BeforeEach
    void setUp() {
        final com.budgetbuddy.service.ml.EnhancedCategoryDetectionService
                enhancedCategoryDetection =
                        org.mockito.Mockito.mock(
                                com.budgetbuddy.service.ml.EnhancedCategoryDetectionService.class);
        final com.budgetbuddy.service.ml.FuzzyMatchingService fuzzyMatchingService =
                org.mockito.Mockito.mock(com.budgetbuddy.service.ml.FuzzyMatchingService.class);
        csvImportService =
                new CSVImportService(
                        accountDetectionService,
                        enhancedCategoryDetection,
                        org.mockito.Mockito.mock(ImportCategoryParser.class),
                        org.mockito.Mockito.mock(
                                com.budgetbuddy.service.category.strategy.CategoryDetectionManager
                                        .class));
    }

    @Test
    void testParseCSVWithAccountDetectionDetectsAccountFromFilename() {
        // Given
        final String csvContent = "Date,Description,Amount\n2025-01-15,Grocery Store,50.00";
        final InputStream inputStream =
                new ByteArrayInputStream(csvContent.getBytes(StandardCharsets.UTF_8));
        final String filename = "chase_checking_1234.csv";
        final String userId = "user-123";

        final AccountDetectionService.DetectedAccount detectedAccount =
                new AccountDetectionService.DetectedAccount();
        detectedAccount.setInstitutionName("Chase");
        detectedAccount.setAccountType("depository");
        detectedAccount.setAccountSubtype("checking");
        detectedAccount.setAccountNumber("1234");
        detectedAccount.setAccountName("Chase checking 1234");

        when(accountDetectionService.detectFromHeaders(anyList(), eq(filename)))
                .thenReturn(detectedAccount);
        when(accountDetectionService.matchToExistingAccount(
                        eq(userId), any(AccountDetectionService.DetectedAccount.class)))
                .thenReturn("matched-account-id");

        // When
        final CSVImportService.ImportResult result =
                csvImportService.parseCSV(inputStream, filename, userId, null);

        // Then
        assertNotNull(result);
        assertEquals(1, result.getSuccessCount());
        assertFalse(result.getTransactions().isEmpty());

        final CSVImportService.ParsedTransaction transaction = result.getTransactions().get(0);
        assertEquals("matched-account-id", transaction.getAccountId());

        verify(accountDetectionService, times(1)).detectFromHeaders(anyList(), eq(filename));
        verify(accountDetectionService, times(1))
                .matchToExistingAccount(
                        eq(userId), any(AccountDetectionService.DetectedAccount.class));
    }

    @Test
    void testParseCSVWithAccountDetectionNoMatchSetsNullAccountId() {
        // Given
        final String csvContent = "Date,Description,Amount\n2025-01-15,Grocery Store,50.00";
        final InputStream inputStream =
                new ByteArrayInputStream(csvContent.getBytes(StandardCharsets.UTF_8));
        final String filename = "chase_checking_1234.csv";
        final String userId = "user-123";

        final AccountDetectionService.DetectedAccount detectedAccount =
                new AccountDetectionService.DetectedAccount();
        detectedAccount.setInstitutionName("Chase");
        detectedAccount.setAccountType("depository");

        when(accountDetectionService.detectFromHeaders(anyList(), eq(filename)))
                .thenReturn(detectedAccount);
        when(accountDetectionService.matchToExistingAccount(
                        eq(userId), any(AccountDetectionService.DetectedAccount.class)))
                .thenReturn(null);

        // When
        final CSVImportService.ImportResult result =
                csvImportService.parseCSV(inputStream, filename, userId, null);

        // Then
        assertNotNull(result);
        assertEquals(1, result.getSuccessCount());

        final CSVImportService.ParsedTransaction transaction = result.getTransactions().get(0);
        assertNull(transaction.getAccountId()); // No match found
    }

    @Test
    void testParseCSVWithAccountDetectionNullUserIdStillParsesTransactions() {
        // Given
        final String csvContent = "Date,Description,Amount\n2025-01-15,Grocery Store,50.00";
        final InputStream inputStream =
                new ByteArrayInputStream(csvContent.getBytes(StandardCharsets.UTF_8));
        final String filename = "test.csv";
        final String userId = null;

        // When
        final CSVImportService.ImportResult result =
                csvImportService.parseCSV(inputStream, filename, userId, null);

        // Then
        assertNotNull(result);
        assertEquals(1, result.getSuccessCount());

        // Account detection should not be called when userId is null
        verify(accountDetectionService, never()).detectFromHeaders(anyList(), anyString());
    }
}
