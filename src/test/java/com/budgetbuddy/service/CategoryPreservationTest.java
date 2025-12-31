package com.budgetbuddy.service;

import com.budgetbuddy.api.ImportCategoryPreservationRequest;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.UserRepository;
import com.budgetbuddy.service.ml.EnhancedCategoryDetectionService;
import com.budgetbuddy.service.ml.FuzzyMatchingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.lenient;

/**
 * Unit tests for category preservation during CSV import
 */
@ExtendWith(MockitoExtension.class)
class CategoryPreservationTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private AccountDetectionService accountDetectionService;

    @Mock
    private EnhancedCategoryDetectionService enhancedCategoryDetection;

    @Mock
    private FuzzyMatchingService fuzzyMatchingService;

    @Mock
    private TransactionTypeCategoryService transactionTypeCategoryService;

    @Mock
    private ImportCategoryParser importCategoryParser;

    @InjectMocks
    private CSVImportService csvImportService;

    private UserTable testUser;
    private String testUserId;

    @BeforeEach
    void setUp() {
        testUserId = "test-user-123";
        testUser = new UserTable();
        testUser.setUserId(testUserId);
        testUser.setEmail("test@example.com");

        // Configure mocks to return safe defaults
        // AccountDetectionService - mock to return null/empty to avoid NPE
        lenient().when(accountDetectionService.isTransactionTableHeaders(anyList())).thenReturn(false);

        // EnhancedCategoryDetectionService - mock to return null (will use fallback)
        // Method signature: detectCategoryWithContext(String merchantName, String description, 
        //                                             BigDecimal amount, String paymentChannel, 
        //                                             String categoryString, String accountType, String accountSubtype)
        lenient().when(enhancedCategoryDetection.detectCategoryWithContext(
                anyString(), anyString(), any(BigDecimal.class), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(null);

        // TransactionTypeCategoryService - mock to return a default category result
        TransactionTypeCategoryService.CategoryResult defaultCategoryResult = 
                new TransactionTypeCategoryService.CategoryResult("other", "other", "DEFAULT", 0.5);
        
        // Use any() for all parameters to be more lenient (account can be null)
        lenient().when(transactionTypeCategoryService.determineCategory(
                any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(defaultCategoryResult);

        // ImportCategoryParser - mock to return a default category
        // Use any() for all parameters to be more lenient
        lenient().when(importCategoryParser.parseCategory(any(), any(), any(), any(), any(), any()))
                .thenReturn("other");
    }

    @Test
    void testCategoryPreservation_AccountMatches() {
        // Given: CSV with transactions and preview categories
        String csvContent = "Date,Description,Amount\n" +
                "2025-01-01,Test Transaction 1,100.00\n" +
                "2025-01-02,Test Transaction 2,200.00\n";
        
        InputStream inputStream = new ByteArrayInputStream(csvContent.getBytes());
        
        // Create preview categories
        List<ImportCategoryPreservationRequest.PreviewCategory> previewCategories = new ArrayList<>();
        
        ImportCategoryPreservationRequest.PreviewCategory cat1 = new ImportCategoryPreservationRequest.PreviewCategory();
        cat1.setCategoryPrimary("groceries");
        cat1.setCategoryDetailed("groceries");
        cat1.setImporterCategoryPrimary("groceries");
        cat1.setImporterCategoryDetailed("groceries");
        previewCategories.add(cat1);
        
        ImportCategoryPreservationRequest.PreviewCategory cat2 = new ImportCategoryPreservationRequest.PreviewCategory();
        cat2.setCategoryPrimary("dining");
        cat2.setCategoryDetailed("dining");
        cat2.setImporterCategoryPrimary("dining");
        cat2.setImporterCategoryDetailed("dining");
        previewCategories.add(cat2);
        
        String previewAccountId = "account-123";
        
        // When: Parse CSV with preview categories and matching account
        CSVImportService.ImportResult result = csvImportService.parseCSV(
                inputStream,
                "test.csv",
                testUserId,
                null,
                previewCategories,
                previewAccountId
        );
        
        // Then: Categories should be preserved
        assertNotNull(result);
        // Check transactions list size instead of successCount (which may not be accurate in unit tests)
        assertEquals(2, result.getTransactions().size(), 
                "Expected 2 transactions, but got " + result.getTransactions().size() + ". Errors: " + result.getErrors());
        
        // Verify first transaction has preserved category
        CSVImportService.ParsedTransaction tx1 = result.getTransactions().get(0);
        assertEquals("groceries", tx1.getCategoryPrimary());
        assertEquals("groceries", tx1.getImporterCategoryPrimary());
        
        // Verify second transaction has preserved category
        CSVImportService.ParsedTransaction tx2 = result.getTransactions().get(1);
        assertEquals("dining", tx2.getCategoryPrimary());
        assertEquals("dining", tx2.getImporterCategoryPrimary());
    }

    @Test
    void testCategoryPreservation_NoPreviewCategories() {
        // Given: CSV without preview categories
        String csvContent = "Date,Description,Amount\n" +
                "2025-01-01,Test Transaction,100.00\n";
        
        InputStream inputStream = new ByteArrayInputStream(csvContent.getBytes());
        
        // When: Parse CSV without preview categories
        CSVImportService.ImportResult result = csvImportService.parseCSV(
                inputStream,
                "test.csv",
                testUserId,
                null,
                null, // No preview categories
                null
        );
        
        // Then: Categories should be detected normally
        assertNotNull(result);
        assertEquals(1, result.getTransactions().size(), 
                "Expected 1 transaction, but got " + result.getTransactions().size() + ". Errors: " + result.getErrors());
        
        CSVImportService.ParsedTransaction tx = result.getTransactions().get(0);
        assertNotNull(tx.getCategoryPrimary());
        // Category should be detected by backend (not preserved)
    }

    @Test
    void testCategoryPreservation_IndexMatching() {
        // Given: CSV with 3 transactions, but only 2 preview categories
        String csvContent = "Date,Description,Amount\n" +
                "2025-01-01,Transaction 1,100.00\n" +
                "2025-01-02,Transaction 2,200.00\n" +
                "2025-01-03,Transaction 3,300.00\n";
        
        InputStream inputStream = new ByteArrayInputStream(csvContent.getBytes());
        
        // Create preview categories for first 2 transactions only
        List<ImportCategoryPreservationRequest.PreviewCategory> previewCategories = new ArrayList<>();
        
        ImportCategoryPreservationRequest.PreviewCategory cat1 = new ImportCategoryPreservationRequest.PreviewCategory();
        cat1.setCategoryPrimary("groceries");
        cat1.setImporterCategoryPrimary("groceries");
        previewCategories.add(cat1);
        
        ImportCategoryPreservationRequest.PreviewCategory cat2 = new ImportCategoryPreservationRequest.PreviewCategory();
        cat2.setCategoryPrimary("dining");
        cat2.setImporterCategoryPrimary("dining");
        previewCategories.add(cat2);
        
        // When: Parse CSV
        CSVImportService.ImportResult result = csvImportService.parseCSV(
                inputStream,
                "test.csv",
                testUserId,
                null,
                previewCategories,
                "account-123"
        );
        
        // Then: First 2 transactions should have preserved categories, 3rd should be detected
        assertNotNull(result);
        assertEquals(3, result.getTransactions().size(), 
                "Expected 3 transactions, but got " + result.getTransactions().size() + ". Errors: " + result.getErrors());
        
        assertEquals("groceries", result.getTransactions().get(0).getCategoryPrimary());
        assertEquals("dining", result.getTransactions().get(1).getCategoryPrimary());
        // Third transaction should have detected category (not preserved)
        assertNotNull(result.getTransactions().get(2).getCategoryPrimary());
    }

    @Test
    void testCategoryPreservation_EmptyPreviewCategories() {
        // Given: CSV with empty preview categories list
        String csvContent = "Date,Description,Amount\n" +
                "2025-01-01,Test Transaction,100.00\n";
        
        InputStream inputStream = new ByteArrayInputStream(csvContent.getBytes());
        
        // When: Parse CSV with empty preview categories
        CSVImportService.ImportResult result = csvImportService.parseCSV(
                inputStream,
                "test.csv",
                testUserId,
                null,
                new ArrayList<>(), // Empty list
                "account-123"
        );
        
        // Then: Categories should be detected normally (not preserved)
        assertNotNull(result);
        assertEquals(1, result.getTransactions().size(), 
                "Expected 1 transaction, but got " + result.getTransactions().size() + ". Errors: " + result.getErrors());
        
        CSVImportService.ParsedTransaction tx = result.getTransactions().get(0);
        assertNotNull(tx.getCategoryPrimary());
    }
}

