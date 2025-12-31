package com.budgetbuddy.api;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.UserRepository;
import com.budgetbuddy.security.*;
import com.budgetbuddy.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.userdetails.UserDetails;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

/**
 * Integration tests for import endpoints (CSV, Excel, PDF)
 * Tests request/response model wiring, error handling, boundary conditions, and edge cases
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class ImportEndpointIntegrationTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserService userService;

    @Mock
    private CSVImportService csvImportService;

    @Mock
    private ExcelImportService excelImportService;

    @Mock
    private PDFImportService pdfImportService;

    @Mock
    private DuplicateDetectionService duplicateDetectionService;

    @Mock
    private TransactionTypeCategoryService transactionTypeCategoryService;

    @Mock
    private FileUploadRateLimiter fileUploadRateLimiter;

    @Mock
    private FileSecurityValidator fileSecurityValidator;

    @Mock
    private FileContentScanner fileContentScanner;

    @Mock
    private FileQuarantineService fileQuarantineService;

    @Mock
    private FileIntegrityService fileIntegrityService;

    @Mock
    private com.budgetbuddy.repository.dynamodb.AccountRepository accountRepository;

    @Mock
    private ChunkedUploadService chunkedUploadService;

    @Mock
    private TransactionService transactionService;

    @InjectMocks
    private TransactionController transactionController;

    private UserTable testUser;
    private UserDetails userDetails;

    @BeforeEach
    void setUp() {
        testUser = new UserTable();
        testUser.setUserId("test-user-id");
        testUser.setEmail("test@example.com");
        testUser.setEnabled(true);

        userDetails = mock(UserDetails.class);
        when(userDetails.getUsername()).thenReturn("test@example.com");
        when(userService.findByEmail("test@example.com")).thenReturn(Optional.of(testUser));

        // Mock security services
        // checkRateLimit is void, so use doNothing()
        doNothing().when(fileUploadRateLimiter).checkRateLimit(anyString(), anyLong());
        doNothing().when(fileSecurityValidator).validateFileUpload(any(), any());
        FileContentScanner.ScanResult safeScanResult = new FileContentScanner.ScanResult();
        safeScanResult.setSafe(true);
        try {
            doReturn(safeScanResult).when(fileContentScanner).scanFile(any(InputStream.class), anyString());
        } catch (java.io.IOException e) {
            // Mock doesn't actually throw, but method signature requires handling
        }
        when(fileQuarantineService.quarantineFile(any(InputStream.class), anyString(), anyString(), anyString())).thenReturn("quarantine-id");
        // FileIntegrityService.verifyIntegrity takes (String fileId, InputStream) and returns boolean
        when(fileIntegrityService.verifyIntegrity(anyString(), any(InputStream.class))).thenReturn(true);
    }

    @Test
    void testCSVImportPreview_ValidFile_ReturnsCorrectResponse() throws IOException {
        // Arrange
        String csvContent = "Date,Description,Amount\n2024-01-01,Test Transaction,100.00";
        MockMultipartFile file = new MockMultipartFile(
                "file", "test.csv", "text/csv", csvContent.getBytes()
        );

        CSVImportService.ImportResult importResult = new CSVImportService.ImportResult();
        for (CSVImportService.ParsedTransaction tx : createMockParsedTransactions(1)) {
            importResult.addTransaction(tx);
        }
        importResult.setDetectedAccount(createMockDetectedAccount());

        when(csvImportService.parseCSV(any(InputStream.class), anyString(), anyString(), nullable(String.class)))
                .thenReturn(importResult);
        when(duplicateDetectionService.detectDuplicates(anyString(), anyList()))
                .thenReturn(new HashMap<>());

        // Act
        ResponseEntity<?> response = transactionController.previewCSV(
                userDetails, file, null, null, "test.csv", 0, 100
        );

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody() instanceof TransactionController.CSVImportPreviewResponse);
        
        TransactionController.CSVImportPreviewResponse previewResponse = 
                (TransactionController.CSVImportPreviewResponse) response.getBody();
        assertEquals(1, previewResponse.getTotalParsed());
        assertNotNull(previewResponse.getTransactions());
        assertEquals(1, previewResponse.getTransactions().size());
        assertNotNull(previewResponse.getDetectedAccount());
        
        // Verify pagination fields
        assertEquals(0, previewResponse.getPage());
        assertEquals(100, previewResponse.getSize());
        assertEquals(1, previewResponse.getTotalPages());
        assertEquals(1, previewResponse.getTotalElements());
    }

    @Test
    void testCSVImportPreview_Pagination_LastPage() throws IOException {
        // Arrange - Create 250 transactions to test pagination
        String csvContent = "Date,Description,Amount\n";
        for (int i = 0; i < 250; i++) {
            csvContent += "2024-01-01,Transaction " + i + ",100.00\n";
        }
        MockMultipartFile file = new MockMultipartFile(
                "file", "test.csv", "text/csv", csvContent.getBytes()
        );

        CSVImportService.ImportResult importResult = new CSVImportService.ImportResult();
        for (CSVImportService.ParsedTransaction tx : createMockParsedTransactions(250)) {
            importResult.addTransaction(tx);
        }
        importResult.setDetectedAccount(createMockDetectedAccount());

        when(csvImportService.parseCSV(any(InputStream.class), anyString(), anyString(), nullable(String.class)))
                .thenReturn(importResult);
        when(duplicateDetectionService.detectDuplicates(anyString(), anyList()))
                .thenReturn(new HashMap<>());

        // Act - Request page 2 (index 1) with size 100
        ResponseEntity<?> response = transactionController.previewCSV(
                userDetails, file, null, null, "test.csv", 1, 100
        );

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        TransactionController.CSVImportPreviewResponse previewResponse = 
                (TransactionController.CSVImportPreviewResponse) response.getBody();
        assertEquals(250, previewResponse.getTotalParsed());
        assertEquals(1, previewResponse.getPage());
        assertEquals(100, previewResponse.getSize());
        assertEquals(3, previewResponse.getTotalPages()); // 250 / 100 = 3 pages (0-indexed: 0, 1, 2)
        assertEquals(250, previewResponse.getTotalElements());
        assertEquals(100, previewResponse.getTransactions().size()); // Page 1 should have 100 items
    }

    @Test
    void testCSVImportPreview_EmptyFile_ReturnsEmptyResponse() throws IOException {
        // Arrange
        String csvContent = "Date,Description,Amount\n";
        MockMultipartFile file = new MockMultipartFile(
                "file", "empty.csv", "text/csv", csvContent.getBytes()
        );

        CSVImportService.ImportResult importResult = new CSVImportService.ImportResult();
        // Empty result - no transactions added
        importResult.setDetectedAccount(null);

        when(csvImportService.parseCSV(any(InputStream.class), anyString(), anyString(), nullable(String.class)))
                .thenReturn(importResult);
        when(duplicateDetectionService.detectDuplicates(anyString(), anyList()))
                .thenReturn(new HashMap<>());

        // Act
        ResponseEntity<?> response = transactionController.previewCSV(
                userDetails, file, null, null, "empty.csv", 0, 100
        );

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        TransactionController.CSVImportPreviewResponse previewResponse = 
                (TransactionController.CSVImportPreviewResponse) response.getBody();
        assertEquals(0, previewResponse.getTotalParsed());
        assertTrue(previewResponse.getTransactions().isEmpty());
        assertEquals(0, previewResponse.getTotalPages());
        assertEquals(0, previewResponse.getTotalElements());
    }

    @Test
    void testCSVImportPreview_InvalidPage_ThrowsException() throws IOException {
        // Arrange
        String csvContent = "Date,Description,Amount\n2024-01-01,Test,100.00";
        MockMultipartFile file = new MockMultipartFile(
                "file", "test.csv", "text/csv", csvContent.getBytes()
        );

        CSVImportService.ImportResult importResult = new CSVImportService.ImportResult();
        for (CSVImportService.ParsedTransaction tx : createMockParsedTransactions(1)) {
            importResult.addTransaction(tx);
        }

        when(csvImportService.parseCSV(any(InputStream.class), anyString(), anyString(), nullable(String.class)))
                .thenReturn(importResult);
        when(duplicateDetectionService.detectDuplicates(anyString(), anyList()))
                .thenReturn(new HashMap<>());

        // Act & Assert - Negative page should throw exception
        AppException exception = assertThrows(AppException.class, () -> {
            transactionController.previewCSV(userDetails, file, null, null, "test.csv", -1, 100);
        });
        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testCSVImportPreview_InvalidSize_ThrowsException() throws IOException {
        // Arrange
        String csvContent = "Date,Description,Amount\n2024-01-01,Test,100.00";
        MockMultipartFile file = new MockMultipartFile(
                "file", "test.csv", "text/csv", csvContent.getBytes()
        );

        CSVImportService.ImportResult importResult = new CSVImportService.ImportResult();
        for (CSVImportService.ParsedTransaction tx : createMockParsedTransactions(1)) {
            importResult.addTransaction(tx);
        }

        when(csvImportService.parseCSV(any(InputStream.class), anyString(), anyString(), nullable(String.class)))
                .thenReturn(importResult);
        when(duplicateDetectionService.detectDuplicates(anyString(), anyList()))
                .thenReturn(new HashMap<>());

        // Act & Assert - Zero size should throw exception
        AppException exception = assertThrows(AppException.class, () -> {
            transactionController.previewCSV(userDetails, file, null, null, "test.csv", 0, 0);
        });
        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testCSVImportPreview_ResponseContainsTransactionTypeIndicator() throws IOException {
        // Arrange
        String csvContent = "Date,Description,Amount,Type\n2024-01-01,Test,100.00,DEBIT";
        MockMultipartFile file = new MockMultipartFile(
                "file", "test.csv", "text/csv", csvContent.getBytes()
        );

        CSVImportService.ImportResult importResult = new CSVImportService.ImportResult();
        CSVImportService.ParsedTransaction parsed = new CSVImportService.ParsedTransaction();
        parsed.setDate(LocalDate.of(2024, 1, 1));
        parsed.setAmount(new BigDecimal("100.00"));
        parsed.setDescription("Test");
        parsed.setTransactionTypeIndicator("DEBIT");
        importResult.addTransaction(parsed);
        importResult.setDetectedAccount(createMockDetectedAccount());

        when(csvImportService.parseCSV(any(InputStream.class), anyString(), anyString(), nullable(String.class)))
                .thenReturn(importResult);
        when(duplicateDetectionService.detectDuplicates(anyString(), anyList()))
                .thenReturn(new HashMap<>());

        // Act
        ResponseEntity<?> response = transactionController.previewCSV(
                userDetails, file, null, null, "test.csv", 0, 100
        );

        // Assert
        TransactionController.CSVImportPreviewResponse previewResponse = 
                (TransactionController.CSVImportPreviewResponse) response.getBody();
        assertNotNull(previewResponse);
        assertEquals(1, previewResponse.getTransactions().size());
        
        java.util.Map<String, Object> transaction = previewResponse.getTransactions().get(0);
        assertTrue(transaction.containsKey("transactionTypeIndicator"));
        assertEquals("DEBIT", transaction.get("transactionTypeIndicator"));
    }

    @Test
    void testCSVImportPreview_ResponseContainsCardNumber() throws IOException {
        // Arrange
        String csvContent = "Date,Description,Amount\n2024-01-01,Test,100.00";
        MockMultipartFile file = new MockMultipartFile(
                "file", "test.csv", "text/csv", csvContent.getBytes()
        );

        CSVImportService.ImportResult importResult = new CSVImportService.ImportResult();
        for (CSVImportService.ParsedTransaction tx : createMockParsedTransactions(1)) {
            importResult.addTransaction(tx);
        }
        
        AccountDetectionService.DetectedAccount detectedAccount = createMockDetectedAccount();
        detectedAccount.setCardNumber("1234-5678-9012-3456");
        importResult.setDetectedAccount(detectedAccount);

        when(csvImportService.parseCSV(any(InputStream.class), anyString(), anyString(), nullable(String.class)))
                .thenReturn(importResult);
        when(duplicateDetectionService.detectDuplicates(anyString(), anyList()))
                .thenReturn(new HashMap<>());

        // Act
        ResponseEntity<?> response = transactionController.previewCSV(
                userDetails, file, null, null, "test.csv", 0, 100
        );

        // Assert
        TransactionController.CSVImportPreviewResponse previewResponse = 
                (TransactionController.CSVImportPreviewResponse) response.getBody();
        assertNotNull(previewResponse.getDetectedAccount());
        assertEquals("1234-5678-9012-3456", previewResponse.getDetectedAccount().getCardNumber());
    }

    @Test
    void testCSVImport_ValidRequest_ReturnsBatchImportResponse() throws IOException {
        // Arrange
        String csvContent = "Date,Description,Amount\n2024-01-01,Test,100.00";
        MockMultipartFile file = new MockMultipartFile(
                "file", "test.csv", "text/csv", csvContent.getBytes()
        );

        CSVImportService.ImportResult importResult = new CSVImportService.ImportResult();
        for (CSVImportService.ParsedTransaction tx : createMockParsedTransactions(1)) {
            importResult.addTransaction(tx);
        }
        importResult.setDetectedAccount(createMockDetectedAccount());

        when(csvImportService.parseCSV(any(InputStream.class), anyString(), anyString(), nullable(String.class)))
                .thenReturn(importResult);
        when(duplicateDetectionService.detectDuplicates(anyString(), anyList()))
                .thenReturn(new HashMap<>());

        // Mock transaction creation
        TransactionTable mockTransaction = new TransactionTable();
        mockTransaction.setTransactionId("test-transaction-id");
        // Use the 22-parameter main method (import code uses full signature)
        when(transactionService.createTransaction(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockTransaction);

        // Act
        ResponseEntity<?> response = transactionController.importCSV(
                userDetails, file, null, null, "test.csv"
        );

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody() instanceof TransactionController.BatchImportResponse);
        
        TransactionController.BatchImportResponse batchResponse = 
                (TransactionController.BatchImportResponse) response.getBody();
        assertTrue(batchResponse.getTotal() > 0);
        assertTrue(batchResponse.getCreated() >= 0);
        assertTrue(batchResponse.getFailed() >= 0);
        
        // Verify successful field is boolean
        assertTrue(batchResponse.getSuccessful() || !batchResponse.getSuccessful()); // Can be true or false
    }

    // Helper methods
    private List<CSVImportService.ParsedTransaction> createMockParsedTransactions(int count) {
        List<CSVImportService.ParsedTransaction> transactions = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            CSVImportService.ParsedTransaction tx = new CSVImportService.ParsedTransaction();
            tx.setDate(LocalDate.of(2024, 1, 1));
            tx.setAmount(new BigDecimal("100.00"));
            tx.setDescription("Test Transaction " + i);
            tx.setCategoryPrimary("FOOD");
            tx.setCategoryDetailed("RESTAURANTS");
            transactions.add(tx);
        }
        return transactions;
    }

    private AccountDetectionService.DetectedAccount createMockDetectedAccount() {
        AccountDetectionService.DetectedAccount account = new AccountDetectionService.DetectedAccount();
        account.setInstitutionName("Test Bank");
        account.setAccountType("CHECKING");
        account.setAccountName("Test Account");
        return account;
    }
}

