package com.budgetbuddy.api;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.userdetails.UserDetails;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive tests for pagination in preview endpoints (CSV, Excel, PDF)
 * Tests edge cases, boundary conditions, and error scenarios
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class PreviewPaginationTest {

    @Mock
    private TransactionService transactionService;
    
    @Mock
    private CSVImportService csvImportService;
    
    @Mock
    private ExcelImportService excelImportService;
    
    @Mock
    private PDFImportService pdfImportService;
    
    @Mock
    private UserService userService;
    
    @Mock
    private DuplicateDetectionService duplicateDetectionService;
    
    @Mock
    private com.budgetbuddy.security.FileUploadRateLimiter fileUploadRateLimiter;
    
    @Mock
    private com.budgetbuddy.security.FileSecurityValidator fileSecurityValidator;
    
    @Mock
    private com.budgetbuddy.security.FileContentScanner fileContentScanner;
    
    @Mock
    private com.budgetbuddy.security.FileQuarantineService fileQuarantineService;
    
    @Mock
    private com.budgetbuddy.security.FileIntegrityService fileIntegrityService;
    
    @Mock
    private com.budgetbuddy.repository.dynamodb.AccountRepository accountRepository;
    
    @Mock
    private com.budgetbuddy.service.TransactionTypeCategoryService transactionTypeCategoryService;
    
    @Mock
    private com.budgetbuddy.service.ChunkedUploadService chunkedUploadService;
    
    @Mock
    private com.budgetbuddy.service.AccountDetectionService accountDetectionService;
    
    @Mock
    private com.budgetbuddy.api.config.TransactionControllerConfig config;
    
    @Mock
    private UserDetails userDetails;
    
    private TransactionController transactionController;
    
    private UserTable testUser;
    private String testUserId = "test-user-id";
    
    @BeforeEach
    void setUp() throws java.io.IOException {
        testUser = new UserTable();
        testUser.setUserId(testUserId);
        testUser.setEmail("test@example.com");
        
        // Setup config mock to return all service mocks
        when(config.getTransactionService()).thenReturn(transactionService);
        when(config.getUserService()).thenReturn(userService);
        when(config.getAccountRepository()).thenReturn(accountRepository);
        when(config.getFileUploadRateLimiter()).thenReturn(fileUploadRateLimiter);
        when(config.getFileSecurityValidator()).thenReturn(fileSecurityValidator);
        when(config.getFileContentScanner()).thenReturn(fileContentScanner);
        when(config.getFileQuarantineService()).thenReturn(fileQuarantineService);
        when(config.getFileIntegrityService()).thenReturn(fileIntegrityService);
        when(config.getCsvImportService()).thenReturn(csvImportService);
        when(config.getExcelImportService()).thenReturn(excelImportService);
        when(config.getPdfImportService()).thenReturn(pdfImportService);
        when(config.getDuplicateDetectionService()).thenReturn(duplicateDetectionService);
        when(config.getTransactionTypeCategoryService()).thenReturn(transactionTypeCategoryService);
        when(config.getChunkedUploadService()).thenReturn(chunkedUploadService);
        when(config.getAccountDetectionService()).thenReturn(accountDetectionService);
        when(config.getObjectMapper()).thenReturn(org.mockito.Mockito.mock(com.fasterxml.jackson.databind.ObjectMapper.class));
        
        // Create controller with mocked config
        transactionController = new TransactionController(config);
        
        when(userDetails.getUsername()).thenReturn("test@example.com");
        when(userService.findByEmail(anyString())).thenReturn(java.util.Optional.of(testUser));
        when(duplicateDetectionService.detectDuplicates(anyString(), anyList()))
            .thenReturn(Collections.emptyMap());
        
        // Mock security services to allow file uploads in tests
        doNothing().when(fileUploadRateLimiter).checkRateLimit(anyString(), anyLong());
        doNothing().when(fileSecurityValidator).validateFileUpload(any(), any());
        
        com.budgetbuddy.security.FileContentScanner.ScanResult safeScanResult = 
            new com.budgetbuddy.security.FileContentScanner.ScanResult();
        safeScanResult.setSafe(true);
        // Use doReturn().when() for methods that throw checked exceptions
        doReturn(safeScanResult).when(fileContentScanner).scanFile(any(), anyString());
        
        when(fileQuarantineService.quarantineFile(any(), anyString(), anyString(), anyString()))
            .thenReturn("quarantine-id");
        
        doNothing().when(fileUploadRateLimiter).recordUpload(anyString(), anyLong());
    }
    
    // ========== CSV Preview Pagination Tests ==========
    
    @Test
    void testCSVPreview_FirstPage_DefaultSize() throws Exception {
        // Arrange: Create 250 transactions
        List<CSVImportService.ParsedTransaction> transactions = createParsedTransactions(250);
        CSVImportService.ImportResult importResult = createImportResult(transactions);
        
        when(csvImportService.parseCSV(any(), anyString(), anyString(), any()))
            .thenReturn(importResult);
        
        MockMultipartFile file = new MockMultipartFile("file", "test.csv", "text/csv", "test".getBytes());
        
        // Act: Request first page with default size (100)
        ResponseEntity<TransactionController.CSVImportPreviewResponse> response = 
            transactionController.previewCSV(userDetails, file, null, null, null, 0, 100);
        
        // Assert
        assertNotNull(response.getBody());
        TransactionController.CSVImportPreviewResponse body = response.getBody();
        assertEquals(250, body.getTotalParsed());
        assertEquals(100, body.getTransactions().size()); // First 100 transactions
        assertEquals(0, body.getPage());
        assertEquals(100, body.getSize());
        assertEquals(3, body.getTotalPages()); // 250 / 100 = 2.5 -> 3 pages
        assertEquals(250, body.getTotalElements());
    }
    
    @Test
    void testCSVPreview_LastPage_PartialResults() throws Exception {
        // Arrange: Create 250 transactions
        List<CSVImportService.ParsedTransaction> transactions = createParsedTransactions(250);
        CSVImportService.ImportResult importResult = createImportResult(transactions);
        
        when(csvImportService.parseCSV(any(), anyString(), anyString(), any()))
            .thenReturn(importResult);
        
        MockMultipartFile file = new MockMultipartFile("file", "test.csv", "text/csv", "test".getBytes());
        
        // Act: Request last page (page 2, size 100) - should return 50 transactions
        ResponseEntity<TransactionController.CSVImportPreviewResponse> response = 
            transactionController.previewCSV(userDetails, file, null, null, null, 2, 100);
        
        // Assert
        assertNotNull(response.getBody());
        TransactionController.CSVImportPreviewResponse body = response.getBody();
        assertEquals(250, body.getTotalParsed());
        assertEquals(50, body.getTransactions().size()); // Last 50 transactions
        assertEquals(2, body.getPage());
        assertEquals(100, body.getSize());
        assertEquals(3, body.getTotalPages());
        assertEquals(250, body.getTotalElements());
    }
    
    @Test
    void testCSVPreview_EmptyTransactions() throws Exception {
        // Arrange: Empty transaction list
        CSVImportService.ImportResult importResult = createImportResult(Collections.emptyList());
        
        when(csvImportService.parseCSV(any(), anyString(), anyString(), any()))
            .thenReturn(importResult);
        
        MockMultipartFile file = new MockMultipartFile("file", "test.csv", "text/csv", "test".getBytes());
        
        // Act
        ResponseEntity<TransactionController.CSVImportPreviewResponse> response = 
            transactionController.previewCSV(userDetails, file, null, null, null, 0, 100);
        
        // Assert
        assertNotNull(response.getBody());
        TransactionController.CSVImportPreviewResponse body = response.getBody();
        assertEquals(0, body.getTotalParsed());
        assertEquals(0, body.getTransactions().size());
        assertEquals(0, body.getPage());
        assertEquals(100, body.getSize());
        assertEquals(0, body.getTotalPages()); // Edge case: 0 transactions = 0 pages
        assertEquals(0, body.getTotalElements());
    }
    
    @Test
    void testCSVPreview_PageBeyondTotalPages() throws Exception {
        // Arrange: Create 50 transactions (1 page with size 100)
        List<CSVImportService.ParsedTransaction> transactions = createParsedTransactions(50);
        CSVImportService.ImportResult importResult = createImportResult(transactions);
        
        when(csvImportService.parseCSV(any(), anyString(), anyString(), any()))
            .thenReturn(importResult);
        
        MockMultipartFile file = new MockMultipartFile("file", "test.csv", "text/csv", "test".getBytes());
        
        // Act: Request page 5 when only 1 page exists
        ResponseEntity<TransactionController.CSVImportPreviewResponse> response = 
            transactionController.previewCSV(userDetails, file, null, null, null, 5, 100);
        
        // Assert: Should return empty list, not throw exception
        assertNotNull(response.getBody());
        TransactionController.CSVImportPreviewResponse body = response.getBody();
        assertEquals(50, body.getTotalParsed());
        assertEquals(0, body.getTransactions().size()); // Empty because startIndex (500) > total (50)
        assertEquals(5, body.getPage());
        assertEquals(100, body.getSize());
        assertEquals(1, body.getTotalPages()); // 50 / 100 = 1 page
        assertEquals(50, body.getTotalElements());
    }
    
    @Test
    void testCSVPreview_NegativePage_ThrowsException() throws Exception {
        // Arrange
        CSVImportService.ImportResult importResult = createImportResult(createParsedTransactions(10));
        
        when(csvImportService.parseCSV(any(), anyString(), anyString(), any()))
            .thenReturn(importResult);
        
        MockMultipartFile file = new MockMultipartFile("file", "test.csv", "text/csv", "test".getBytes());
        
        // Act & Assert
        AppException exception = assertThrows(AppException.class, () -> 
            transactionController.previewCSV(userDetails, file, null, null, null, -1, 100));
        
        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("Page number must be >= 0"));
    }
    
    @Test
    void testCSVPreview_ZeroSize_ThrowsException() throws Exception {
        // Arrange
        CSVImportService.ImportResult importResult = createImportResult(createParsedTransactions(10));
        
        when(csvImportService.parseCSV(any(), anyString(), anyString(), any()))
            .thenReturn(importResult);
        
        MockMultipartFile file = new MockMultipartFile("file", "test.csv", "text/csv", "test".getBytes());
        
        // Act & Assert
        AppException exception = assertThrows(AppException.class, () -> 
            transactionController.previewCSV(userDetails, file, null, null, null, 0, 0));
        
        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("Page size must be between 1 and 1000"));
    }
    
    @Test
    void testCSVPreview_SizeTooLarge_ThrowsException() throws Exception {
        // Arrange
        CSVImportService.ImportResult importResult = createImportResult(createParsedTransactions(10));
        
        when(csvImportService.parseCSV(any(), anyString(), anyString(), any()))
            .thenReturn(importResult);
        
        MockMultipartFile file = new MockMultipartFile("file", "test.csv", "text/csv", "test".getBytes());
        
        // Act & Assert
        AppException exception = assertThrows(AppException.class, () -> 
            transactionController.previewCSV(userDetails, file, null, null, null, 0, 1001));
        
        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("Page size must be between 1 and 1000"));
    }
    
    @Test
    void testCSVPreview_MaxSize_Works() throws Exception {
        // Arrange: Create 2000 transactions
        List<CSVImportService.ParsedTransaction> transactions = createParsedTransactions(2000);
        CSVImportService.ImportResult importResult = createImportResult(transactions);
        
        when(csvImportService.parseCSV(any(), anyString(), anyString(), any()))
            .thenReturn(importResult);
        
        MockMultipartFile file = new MockMultipartFile("file", "test.csv", "text/csv", "test".getBytes());
        
        // Act: Request with max size (1000)
        ResponseEntity<TransactionController.CSVImportPreviewResponse> response = 
            transactionController.previewCSV(userDetails, file, null, null, null, 0, 1000);
        
        // Assert
        assertNotNull(response.getBody());
        TransactionController.CSVImportPreviewResponse body = response.getBody();
        assertEquals(2000, body.getTotalParsed());
        assertEquals(1000, body.getTransactions().size());
        assertEquals(0, body.getPage());
        assertEquals(1000, body.getSize());
        assertEquals(2, body.getTotalPages()); // 2000 / 1000 = 2 pages
        assertEquals(2000, body.getTotalElements());
    }
    
    @Test
    void testCSVPreview_SingleTransaction() throws Exception {
        // Arrange: Single transaction
        List<CSVImportService.ParsedTransaction> transactions = createParsedTransactions(1);
        CSVImportService.ImportResult importResult = createImportResult(transactions);
        
        when(csvImportService.parseCSV(any(), anyString(), anyString(), any()))
            .thenReturn(importResult);
        
        MockMultipartFile file = new MockMultipartFile("file", "test.csv", "text/csv", "test".getBytes());
        
        // Act
        ResponseEntity<TransactionController.CSVImportPreviewResponse> response = 
            transactionController.previewCSV(userDetails, file, null, null, null, 0, 100);
        
        // Assert
        assertNotNull(response.getBody());
        TransactionController.CSVImportPreviewResponse body = response.getBody();
        assertEquals(1, body.getTotalParsed());
        assertEquals(1, body.getTransactions().size());
        assertEquals(0, body.getPage());
        assertEquals(100, body.getSize());
        assertEquals(1, body.getTotalPages()); // 1 / 100 = 1 page (ceiling)
        assertEquals(1, body.getTotalElements());
    }
    
    // ========== Excel Preview Pagination Tests ==========
    
    @Test
    void testExcelPreview_Pagination_Works() throws Exception {
        // Arrange: Create 150 transactions
        List<CSVImportService.ParsedTransaction> transactions = createParsedTransactions(150);
        ExcelImportService.ImportResult importResult = new ExcelImportService.ImportResult();
        for (CSVImportService.ParsedTransaction tx : transactions) {
            importResult.addTransaction(tx);
        }
        
        when(excelImportService.parseExcel(any(), anyString(), anyString(), any()))
            .thenReturn(importResult);
        
        MockMultipartFile file = new MockMultipartFile("file", "test.xlsx", 
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "test".getBytes());
        
        // Act: Request page 1 (second page)
        ResponseEntity<TransactionController.ExcelImportPreviewResponse> response = 
            transactionController.previewExcel(userDetails, file, null, null, 1, 50);
        
        // Assert
        assertNotNull(response.getBody());
        TransactionController.ExcelImportPreviewResponse body = response.getBody();
        assertEquals(150, body.getTotalParsed());
        assertEquals(50, body.getTransactions().size()); // Transactions 50-99
        assertEquals(1, body.getPage());
        assertEquals(50, body.getSize());
        assertEquals(3, body.getTotalPages()); // 150 / 50 = 3 pages
        assertEquals(150, body.getTotalElements());
    }
    
    @Test
    void testExcelPreview_EmptyTransactions() throws Exception {
        // Arrange
        ExcelImportService.ImportResult importResult = new ExcelImportService.ImportResult();
        
        when(excelImportService.parseExcel(any(), anyString(), anyString(), any()))
            .thenReturn(importResult);
        
        MockMultipartFile file = new MockMultipartFile("file", "test.xlsx", 
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "test".getBytes());
        
        // Act
        ResponseEntity<TransactionController.ExcelImportPreviewResponse> response = 
            transactionController.previewExcel(userDetails, file, null, null, 0, 100);
        
        // Assert
        assertNotNull(response.getBody());
        TransactionController.ExcelImportPreviewResponse body = response.getBody();
        assertEquals(0, body.getTotalParsed());
        assertEquals(0, body.getTransactions().size());
        assertEquals(0, body.getTotalPages());
        assertEquals(0, body.getTotalElements());
    }
    
    // ========== PDF Preview Pagination Tests ==========
    
    @Test
    void testPDFPreview_Pagination_Works() throws Exception {
        // Arrange: Create 75 transactions
        List<PDFImportService.ParsedTransaction> transactions = createPDFParsedTransactions(75);
        PDFImportService.ImportResult importResult = new PDFImportService.ImportResult();
        for (PDFImportService.ParsedTransaction tx : transactions) {
            importResult.addTransaction(tx);
        }
        
        when(pdfImportService.parsePDF(any(), anyString(), anyString(), any()))
            .thenReturn(importResult);
        
        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", "test".getBytes());
        
        // Act: Request page 0 with size 25
        ResponseEntity<TransactionController.PDFImportPreviewResponse> response = 
            transactionController.previewPDF(userDetails, file, null, null, 0, 25);
        
        // Assert
        assertNotNull(response.getBody());
        TransactionController.PDFImportPreviewResponse body = response.getBody();
        assertEquals(75, body.getTotalParsed());
        assertEquals(25, body.getTransactions().size()); // First 25 transactions
        assertEquals(0, body.getPage());
        assertEquals(25, body.getSize());
        assertEquals(3, body.getTotalPages()); // 75 / 25 = 3 pages
        assertEquals(75, body.getTotalElements());
    }
    
    @Test
    void testPDFPreview_EmptyTransactions() throws Exception {
        // Arrange
        PDFImportService.ImportResult importResult = new PDFImportService.ImportResult();
        
        when(pdfImportService.parsePDF(any(), anyString(), anyString(), any()))
            .thenReturn(importResult);
        
        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", "test".getBytes());
        
        // Act
        ResponseEntity<TransactionController.PDFImportPreviewResponse> response = 
            transactionController.previewPDF(userDetails, file, null, null, 0, 100);
        
        // Assert
        assertNotNull(response.getBody());
        TransactionController.PDFImportPreviewResponse body = response.getBody();
        assertEquals(0, body.getTotalParsed());
        assertEquals(0, body.getTransactions().size());
        assertEquals(0, body.getTotalPages());
        assertEquals(0, body.getTotalElements());
    }
    
    // ========== Helper Methods ==========
    
    private List<CSVImportService.ParsedTransaction> createParsedTransactions(int count) {
        List<CSVImportService.ParsedTransaction> transactions = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            CSVImportService.ParsedTransaction tx = new CSVImportService.ParsedTransaction();
            tx.setDate(LocalDate.now().minusDays(i));
            tx.setAmount(BigDecimal.valueOf(10.0 + i));
            tx.setDescription("Transaction " + i);
            tx.setMerchantName("Merchant " + i);
            tx.setCategoryPrimary("other");
            tx.setCategoryDetailed("other");
            tx.setCurrencyCode("USD");
            tx.setTransactionType("EXPENSE");
            transactions.add(tx);
        }
        return transactions;
    }
    
    private List<PDFImportService.ParsedTransaction> createPDFParsedTransactions(int count) {
        List<PDFImportService.ParsedTransaction> transactions = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            PDFImportService.ParsedTransaction tx = new PDFImportService.ParsedTransaction();
            tx.setDate(LocalDate.now().minusDays(i));
            tx.setAmount(BigDecimal.valueOf(10.0 + i));
            tx.setDescription("Transaction " + i);
            tx.setMerchantName("Merchant " + i);
            tx.setCategoryPrimary("other");
            tx.setCategoryDetailed("other");
            tx.setCurrencyCode("USD");
            tx.setTransactionType("EXPENSE");
            transactions.add(tx);
        }
        return transactions;
    }
    
    private CSVImportService.ImportResult createImportResult(List<CSVImportService.ParsedTransaction> transactions) {
        CSVImportService.ImportResult result = new CSVImportService.ImportResult();
        for (CSVImportService.ParsedTransaction tx : transactions) {
            result.addTransaction(tx);
        }
        return result;
    }
}

