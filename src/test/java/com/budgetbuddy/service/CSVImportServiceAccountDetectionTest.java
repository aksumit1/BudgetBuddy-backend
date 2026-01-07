package com.budgetbuddy.service;

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
 * Tests for CSVImportService with account detection integration
 */
@ExtendWith(MockitoExtension.class)
class CSVImportServiceAccountDetectionTest {

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
    void testParseCSV_WithAccountDetection_DetectsAccountFromFilename() {
        // Given
        String csvContent = "Date,Description,Amount\n2025-01-15,Grocery Store,50.00";
        InputStream inputStream = new ByteArrayInputStream(csvContent.getBytes(StandardCharsets.UTF_8));
        String filename = "chase_checking_1234.csv";
        String userId = "user-123";

        AccountDetectionService.DetectedAccount detectedAccount = new AccountDetectionService.DetectedAccount();
        detectedAccount.setInstitutionName("Chase");
        detectedAccount.setAccountType("depository");
        detectedAccount.setAccountSubtype("checking");
        detectedAccount.setAccountNumber("1234");
        detectedAccount.setAccountName("Chase checking 1234");

        when(accountDetectionService.detectFromHeaders(anyList(), eq(filename)))
                .thenReturn(detectedAccount);
        when(accountDetectionService.matchToExistingAccount(eq(userId), any(AccountDetectionService.DetectedAccount.class)))
                .thenReturn("matched-account-id");

        // When
        CSVImportService.ImportResult result = csvImportService.parseCSV(inputStream, filename, userId, null);

        // Then
        assertNotNull(result);
        assertEquals(1, result.getSuccessCount());
        assertFalse(result.getTransactions().isEmpty());
        
        CSVImportService.ParsedTransaction transaction = result.getTransactions().get(0);
        assertEquals("matched-account-id", transaction.getAccountId());
        
        verify(accountDetectionService, times(1)).detectFromHeaders(anyList(), eq(filename));
        verify(accountDetectionService, times(1)).matchToExistingAccount(eq(userId), any(AccountDetectionService.DetectedAccount.class));
    }

    @Test
    void testParseCSV_WithAccountDetection_NoMatch_SetsNullAccountId() {
        // Given
        String csvContent = "Date,Description,Amount\n2025-01-15,Grocery Store,50.00";
        InputStream inputStream = new ByteArrayInputStream(csvContent.getBytes(StandardCharsets.UTF_8));
        String filename = "chase_checking_1234.csv";
        String userId = "user-123";

        AccountDetectionService.DetectedAccount detectedAccount = new AccountDetectionService.DetectedAccount();
        detectedAccount.setInstitutionName("Chase");
        detectedAccount.setAccountType("depository");

        when(accountDetectionService.detectFromHeaders(anyList(), eq(filename)))
                .thenReturn(detectedAccount);
        when(accountDetectionService.matchToExistingAccount(eq(userId), any(AccountDetectionService.DetectedAccount.class)))
                .thenReturn(null);

        // When
        CSVImportService.ImportResult result = csvImportService.parseCSV(inputStream, filename, userId, null);

        // Then
        assertNotNull(result);
        assertEquals(1, result.getSuccessCount());
        
        CSVImportService.ParsedTransaction transaction = result.getTransactions().get(0);
        assertNull(transaction.getAccountId()); // No match found
    }

    @Test
    void testParseCSV_WithAccountDetection_NullUserId_StillParsesTransactions() {
        // Given
        String csvContent = "Date,Description,Amount\n2025-01-15,Grocery Store,50.00";
        InputStream inputStream = new ByteArrayInputStream(csvContent.getBytes(StandardCharsets.UTF_8));
        String filename = "test.csv";
        String userId = null;

        // When
        CSVImportService.ImportResult result = csvImportService.parseCSV(inputStream, filename, userId, null);

        // Then
        assertNotNull(result);
        assertEquals(1, result.getSuccessCount());
        
        // Account detection should not be called when userId is null
        verify(accountDetectionService, never()).detectFromHeaders(anyList(), anyString());
    }
}

