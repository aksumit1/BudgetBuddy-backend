package com.budgetbuddy.service;

import com.budgetbuddy.model.dynamodb.UserTable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.budgetbuddy.service.TransactionTypeCategoryService;
import com.budgetbuddy.service.ImportCategoryParser;

/**
 * Tests for account validation during import
 * Tests that transactions are not created without valid account IDs
 */
@ExtendWith(MockitoExtension.class)
class ImportAccountValidationTest {

    @Mock
    private AccountDetectionService accountDetectionService;

    private CSVImportService csvImportService;

    @BeforeEach
    void setUp() {
        com.budgetbuddy.service.ml.EnhancedCategoryDetectionService enhancedCategoryDetection = 
                org.mockito.Mockito.mock(com.budgetbuddy.service.ml.EnhancedCategoryDetectionService.class);
        com.budgetbuddy.service.ml.FuzzyMatchingService fuzzyMatchingService = 
                org.mockito.Mockito.mock(com.budgetbuddy.service.ml.FuzzyMatchingService.class);
        csvImportService = new CSVImportService(accountDetectionService, enhancedCategoryDetection, fuzzyMatchingService,
                org.mockito.Mockito.mock(TransactionTypeCategoryService.class),
                org.mockito.Mockito.mock(ImportCategoryParser.class),
                org.mockito.Mockito.mock(com.budgetbuddy.service.category.strategy.CategoryDetectionManager.class));
    }

    @Test
    void testParseCSV_NoAccountId_TransactionsHaveNullAccountId() {
        // Given
        String csvContent = "Date,Description,Amount\n2025-01-15,Grocery Store,50.00";
        InputStream inputStream = new ByteArrayInputStream(csvContent.getBytes(StandardCharsets.UTF_8));
        String filename = "test.csv";
        String userId = "user-123";

        // No account detected
        when(accountDetectionService.detectFromHeaders(anyList(), eq(filename)))
                .thenReturn(null);
        // matchToExistingAccount won't be called if detectFromHeaders returns null

        // When
        CSVImportService.ImportResult result = csvImportService.parseCSV(inputStream, filename, userId, null);

        // Then
        assertNotNull(result);
        assertEquals(1, result.getSuccessCount());
        
        CSVImportService.ParsedTransaction transaction = result.getTransactions().get(0);
        assertNull(transaction.getAccountId(), "Transaction should have null accountId when no account detected");
    }

    @Test
    void testParseCSV_AccountDetectionThrowsException_ContinuesWithoutAccount() {
        // Given
        String csvContent = "Date,Description,Amount\n2025-01-15,Grocery Store,50.00";
        InputStream inputStream = new ByteArrayInputStream(csvContent.getBytes(StandardCharsets.UTF_8));
        String filename = "test.csv";
        String userId = "user-123";

        // Account detection throws exception
        when(accountDetectionService.detectFromHeaders(anyList(), eq(filename)))
                .thenThrow(new RuntimeException("Database error"));

        // When
        CSVImportService.ImportResult result = csvImportService.parseCSV(inputStream, filename, userId, null);

        // Then - should continue parsing despite exception
        assertNotNull(result);
        assertEquals(1, result.getSuccessCount());
        // Transaction should still be parsed, just without account ID
    }

    @Test
    void testParseCSV_NullUserId_StillParsesTransactions() {
        // Given
        String csvContent = "Date,Description,Amount\n2025-01-15,Grocery Store,50.00";
        InputStream inputStream = new ByteArrayInputStream(csvContent.getBytes(StandardCharsets.UTF_8));
        String filename = "test.csv";
        String userId = null;

        // When
        CSVImportService.ImportResult result = csvImportService.parseCSV(inputStream, filename, userId, null);

        // Then - should parse transactions even without userId
        assertNotNull(result);
        assertEquals(1, result.getSuccessCount());
        
        // Account detection should not be called
        verify(accountDetectionService, never()).detectFromHeaders(anyList(), anyString());
    }
}

