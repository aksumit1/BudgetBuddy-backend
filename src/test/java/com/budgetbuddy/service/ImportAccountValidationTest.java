package com.budgetbuddy.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.anyList;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Tests for account validation during import Tests that transactions are not created without valid
 * account IDs
 */
// Tests intentionally pass null to verify graceful handling /
// AppException paths; SpotBugs's NP_LOAD_OF_KNOWN_NULL_VALUE is expected.
@SuppressFBWarnings(
        value = "NP_LOAD_OF_KNOWN_NULL_VALUE",
        justification = "Tests deliberately exercise null-input paths")
@ExtendWith(MockitoExtension.class)
class ImportAccountValidationTest {

    @Mock private AccountDetectionService accountDetectionService;

    private CSVImportService csvImportService;

    @BeforeEach
    void setUp() {
        final com.budgetbuddy.service.ml.EnhancedCategoryDetectionService enhancedCategoryDetection =
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
    void testParseCSVNoAccountIdTransactionsHaveNullAccountId() {
        // Given
        final String csvContent = "Date,Description,Amount\n2025-01-15,Grocery Store,50.00";
        final InputStream inputStream =
                new ByteArrayInputStream(csvContent.getBytes(StandardCharsets.UTF_8));
        final String filename = "test.csv";
        final String userId = "user-123";

        // No account detected
        when(accountDetectionService.detectFromHeaders(anyList(), eq(filename))).thenReturn(null);
        // matchToExistingAccount won't be called if detectFromHeaders returns null

        // When
        final CSVImportService.ImportResult result =
                csvImportService.parseCSV(inputStream, filename, userId, null);

        // Then
        assertNotNull(result);
        assertEquals(1, result.getSuccessCount());

        final CSVImportService.ParsedTransaction transaction = result.getTransactions().get(0);
        assertNull(
                transaction.getAccountId(),
                "Transaction should have null accountId when no account detected");
    }

    @Test
    void testParseCSVAccountDetectionThrowsExceptionContinuesWithoutAccount() {
        // Given
        final String csvContent = "Date,Description,Amount\n2025-01-15,Grocery Store,50.00";
        final InputStream inputStream =
                new ByteArrayInputStream(csvContent.getBytes(StandardCharsets.UTF_8));
        final String filename = "test.csv";
        final String userId = "user-123";

        // Account detection throws exception
        when(accountDetectionService.detectFromHeaders(anyList(), eq(filename)))
                .thenThrow(new RuntimeException("Database error"));

        // When
        final CSVImportService.ImportResult result =
                csvImportService.parseCSV(inputStream, filename, userId, null);

        // Then - should continue parsing despite exception
        assertNotNull(result);
        assertEquals(1, result.getSuccessCount());
        // Transaction should still be parsed, just without account ID
    }

    @Test
    void testParseCSVNullUserIdStillParsesTransactions() {
        // Given
        final String csvContent = "Date,Description,Amount\n2025-01-15,Grocery Store,50.00";
        final InputStream inputStream =
                new ByteArrayInputStream(csvContent.getBytes(StandardCharsets.UTF_8));
        final String filename = "test.csv";
        final String userId = null;

        // When
        final CSVImportService.ImportResult result =
                csvImportService.parseCSV(inputStream, filename, userId, null);

        // Then - should parse transactions even without userId
        assertNotNull(result);
        assertEquals(1, result.getSuccessCount());

        // Account detection should not be called
        verify(accountDetectionService, never()).detectFromHeaders(anyList(), anyString());
    }
}
