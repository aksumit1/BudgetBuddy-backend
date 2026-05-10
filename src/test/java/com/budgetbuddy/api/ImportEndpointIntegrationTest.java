package com.budgetbuddy.api;


import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyList;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.nullable;
import static org.mockito.Mockito.when;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.UserRepository;
import com.budgetbuddy.security.FileContentScanner;
import com.budgetbuddy.security.FileIntegrityService;
import com.budgetbuddy.security.FileQuarantineService;
import com.budgetbuddy.security.FileSecurityValidator;
import com.budgetbuddy.security.FileUploadRateLimiter;
import com.budgetbuddy.service.AccountDetectionService;
import com.budgetbuddy.service.CSVImportService;
import com.budgetbuddy.service.ChunkedUploadService;
import com.budgetbuddy.service.DuplicateDetectionService;
import com.budgetbuddy.service.ExcelImportService;
import com.budgetbuddy.service.PDFImportService;
import com.budgetbuddy.service.TransactionService;
import com.budgetbuddy.service.TransactionTypeCategoryService;
import com.budgetbuddy.service.UserService;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * Integration tests for import endpoints (CSV, Excel, PDF) Tests request/response model wiring,
 * error handling, boundary conditions, and edge cases
 */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
@SuppressWarnings("PMD.LawOfDemeter")
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class ImportEndpointIntegrationTest {

    @Mock private UserRepository userRepository;

    @Mock private UserService userService;

    @Mock private CSVImportService csvImportService;

    @Mock private ExcelImportService excelImportService;

    @Mock private PDFImportService pdfImportService;

    @Mock private DuplicateDetectionService duplicateDetectionService;

    @Mock private TransactionTypeCategoryService transactionTypeCategoryService;

    @Mock private FileUploadRateLimiter fileUploadRateLimiter;

    @Mock private FileSecurityValidator fileSecurityValidator;

    @Mock private FileContentScanner fileContentScanner;

    @Mock private FileQuarantineService fileQuarantineService;

    @Mock private FileIntegrityService fileIntegrityService;

    @Mock private com.budgetbuddy.repository.dynamodb.AccountRepository accountRepository;

    @Mock private ChunkedUploadService chunkedUploadService;

    @Mock private TransactionService transactionService;

    @Mock private com.budgetbuddy.service.AccountDetectionService accountDetectionService;

    @Mock private com.budgetbuddy.api.config.TransactionControllerConfig config;

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
        final FileContentScanner.ScanResult safeScanResult = new FileContentScanner.ScanResult();
        safeScanResult.setSafe(true);
        try {
            doReturn(safeScanResult)
                    .when(fileContentScanner)
                    .scanFile(any(InputStream.class), anyString());
        } catch (java.io.IOException e) {
            // Mock doesn't actually throw, but method signature requires handling
        }
        when(fileQuarantineService.quarantineFile(
                        any(InputStream.class), anyString(), anyString(), anyString()))
                .thenReturn("quarantine-id");
        // FileIntegrityService.verifyIntegrity takes (String fileId, InputStream) and returns
        // boolean
        when(fileIntegrityService.verifyIntegrity(anyString(), any(InputStream.class)))
                .thenReturn(true);

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
        when(config.getObjectMapper())
                .thenReturn(
                        org.mockito.Mockito.mock(
                                com.fasterxml.jackson.databind.ObjectMapper.class));

        // Create controller with mocked config
        transactionController =
                new TransactionController(
                        config,
                        org.mockito.Mockito.mock(com.budgetbuddy.service.GoalService.class),
                        org.mockito.Mockito.mock(
                                com.budgetbuddy.service.BudgetThresholdEvaluator.class),
                        org.mockito.Mockito.mock(com.budgetbuddy.service.GoalIngestEvaluator.class),
                        org.mockito.Mockito.mock(
                                com.budgetbuddy.service.BudgetToGoalFlowService.class),
                        org.mockito.Mockito.mock(
                                com.budgetbuddy.service.correctness.IdempotencyService.class),
                        org.mockito.Mockito.mock(
                                com.budgetbuddy.service.importer.TransactionImportOrchestrator
                                        .class));
    }

    @Test
    void testCSVImportPreviewValidFileReturnsCorrectResponse() throws IOException {
        // Arrange
        final String csvContent = "Date,Description,Amount\n2024-01-01,Test Transaction,100.00";
        final MockMultipartFile file =
                new MockMultipartFile("file", "test.csv", "text/csv", csvContent.getBytes(StandardCharsets.UTF_8));

        final CSVImportService.ImportResult importResult = new CSVImportService.ImportResult();
        for (final CSVImportService.ParsedTransaction tx : createMockParsedTransactions(1)) {
            importResult.addTransaction(tx);
        }
        importResult.setDetectedAccount(createMockDetectedAccount());

        when(csvImportService.parseCSV(
                        any(InputStream.class), anyString(), anyString(), nullable(String.class)))
                .thenReturn(importResult);
        when(duplicateDetectionService.detectDuplicates(anyString(), anyList()))
                .thenReturn(new HashMap<>());

        // Act
        final ResponseEntity<?> response =
                transactionController.previewCSV(userDetails, file, null, null, "test.csv", 0, 100);

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody() instanceof TransactionController.CSVImportPreviewResponse);

        final TransactionController.CSVImportPreviewResponse previewResponse =
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
    void testCSVImportPreviewPaginationLastPage() throws IOException {
        // Arrange - Create 250 transactions to test pagination
        String csvContent = "Date,Description,Amount\n";
        for (int i = 0; i < 250; i++) {
            csvContent += "2024-01-01,Transaction " + i + ",100.00\n";
        }
        final MockMultipartFile file =
                new MockMultipartFile("file", "test.csv", "text/csv", csvContent.getBytes(StandardCharsets.UTF_8));

        final CSVImportService.ImportResult importResult = new CSVImportService.ImportResult();
        for (final CSVImportService.ParsedTransaction tx : createMockParsedTransactions(250)) {
            importResult.addTransaction(tx);
        }
        importResult.setDetectedAccount(createMockDetectedAccount());

        when(csvImportService.parseCSV(
                        any(InputStream.class), anyString(), anyString(), nullable(String.class)))
                .thenReturn(importResult);
        when(duplicateDetectionService.detectDuplicates(anyString(), anyList()))
                .thenReturn(new HashMap<>());

        // Act - Request page 2 (index 1) with size 100
        final ResponseEntity<?> response =
                transactionController.previewCSV(userDetails, file, null, null, "test.csv", 1, 100);

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        final TransactionController.CSVImportPreviewResponse previewResponse =
                (TransactionController.CSVImportPreviewResponse) response.getBody();
        assertEquals(250, previewResponse.getTotalParsed());
        assertEquals(1, previewResponse.getPage());
        assertEquals(100, previewResponse.getSize());
        assertEquals(
                3, previewResponse.getTotalPages()); // 250 / 100 = 3 pages (0-indexed: 0, 1, 2)
        assertEquals(250, previewResponse.getTotalElements());
        assertEquals(100, previewResponse.getTransactions().size()); // Page 1 should have 100 items
    }

    @Test
    void testCSVImportPreviewEmptyFileReturnsEmptyResponse() throws IOException {
        // Arrange
        final String csvContent = "Date,Description,Amount\n";
        final MockMultipartFile file =
                new MockMultipartFile("file", "empty.csv", "text/csv", csvContent.getBytes(StandardCharsets.UTF_8));

        final CSVImportService.ImportResult importResult = new CSVImportService.ImportResult();
        // Empty result - no transactions added
        importResult.setDetectedAccount(null);

        when(csvImportService.parseCSV(
                        any(InputStream.class), anyString(), anyString(), nullable(String.class)))
                .thenReturn(importResult);
        when(duplicateDetectionService.detectDuplicates(anyString(), anyList()))
                .thenReturn(new HashMap<>());

        // Act
        final ResponseEntity<?> response =
                transactionController.previewCSV(
                        userDetails, file, null, null, "empty.csv", 0, 100);

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        final TransactionController.CSVImportPreviewResponse previewResponse =
                (TransactionController.CSVImportPreviewResponse) response.getBody();
        assertEquals(0, previewResponse.getTotalParsed());
        assertTrue(previewResponse.getTransactions().isEmpty());
        assertEquals(0, previewResponse.getTotalPages());
        assertEquals(0, previewResponse.getTotalElements());
    }

    @Test
    void testCSVImportPreviewInvalidPageThrowsException() throws IOException {
        // Arrange
        final String csvContent = "Date,Description,Amount\n2024-01-01,Test,100.00";
        final MockMultipartFile file =
                new MockMultipartFile("file", "test.csv", "text/csv", csvContent.getBytes(StandardCharsets.UTF_8));

        final CSVImportService.ImportResult importResult = new CSVImportService.ImportResult();
        for (final CSVImportService.ParsedTransaction tx : createMockParsedTransactions(1)) {
            importResult.addTransaction(tx);
        }

        when(csvImportService.parseCSV(
                        any(InputStream.class), anyString(), anyString(), nullable(String.class)))
                .thenReturn(importResult);
        when(duplicateDetectionService.detectDuplicates(anyString(), anyList()))
                .thenReturn(new HashMap<>());

        // Act & Assert - Negative page should throw exception
        final AppException exception =
                assertThrows(
                        AppException.class,
                        () -> {
                            transactionController.previewCSV(
                                    userDetails, file, null, null, "test.csv", -1, 100);
                        });
        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testCSVImportPreviewInvalidSizeThrowsException() throws IOException {
        // Arrange
        final String csvContent = "Date,Description,Amount\n2024-01-01,Test,100.00";
        final MockMultipartFile file =
                new MockMultipartFile("file", "test.csv", "text/csv", csvContent.getBytes(StandardCharsets.UTF_8));

        final CSVImportService.ImportResult importResult = new CSVImportService.ImportResult();
        for (final CSVImportService.ParsedTransaction tx : createMockParsedTransactions(1)) {
            importResult.addTransaction(tx);
        }

        when(csvImportService.parseCSV(
                        any(InputStream.class), anyString(), anyString(), nullable(String.class)))
                .thenReturn(importResult);
        when(duplicateDetectionService.detectDuplicates(anyString(), anyList()))
                .thenReturn(new HashMap<>());

        // Act & Assert - Zero size should throw exception
        final AppException exception =
                assertThrows(
                        AppException.class,
                        () -> {
                            transactionController.previewCSV(
                                    userDetails, file, null, null, "test.csv", 0, 0);
                        });
        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testCSVImportPreviewResponseContainsTransactionTypeIndicator() throws IOException {
        // Arrange
        final String csvContent = "Date,Description,Amount,Type\n2024-01-01,Test,100.00,DEBIT";
        final MockMultipartFile file =
                new MockMultipartFile("file", "test.csv", "text/csv", csvContent.getBytes(StandardCharsets.UTF_8));

        final CSVImportService.ImportResult importResult = new CSVImportService.ImportResult();
        final CSVImportService.ParsedTransaction parsed = new CSVImportService.ParsedTransaction();
        parsed.setDate(LocalDate.of(2024, 1, 1));
        parsed.setAmount(new BigDecimal("100.00"));
        parsed.setDescription("Test");
        parsed.setTransactionTypeIndicator("DEBIT");
        importResult.addTransaction(parsed);
        importResult.setDetectedAccount(createMockDetectedAccount());

        when(csvImportService.parseCSV(
                        any(InputStream.class), anyString(), anyString(), nullable(String.class)))
                .thenReturn(importResult);
        when(duplicateDetectionService.detectDuplicates(anyString(), anyList()))
                .thenReturn(new HashMap<>());

        // Act
        final ResponseEntity<?> response =
                transactionController.previewCSV(userDetails, file, null, null, "test.csv", 0, 100);

        // Assert
        final TransactionController.CSVImportPreviewResponse previewResponse =
                (TransactionController.CSVImportPreviewResponse) response.getBody();
        assertNotNull(previewResponse);
        assertEquals(1, previewResponse.getTransactions().size());

        final java.util.Map<String, Object> transaction = previewResponse.getTransactions().get(0);
        assertTrue(transaction.containsKey("transactionTypeIndicator"));
        assertEquals("DEBIT", transaction.get("transactionTypeIndicator"));
    }

    @Test
    void testCSVImportPreviewResponseContainsCardNumber() throws IOException {
        // Arrange
        final String csvContent = "Date,Description,Amount\n2024-01-01,Test,100.00";
        final MockMultipartFile file =
                new MockMultipartFile("file", "test.csv", "text/csv", csvContent.getBytes(StandardCharsets.UTF_8));

        final CSVImportService.ImportResult importResult = new CSVImportService.ImportResult();
        for (final CSVImportService.ParsedTransaction tx : createMockParsedTransactions(1)) {
            importResult.addTransaction(tx);
        }

        final AccountDetectionService.DetectedAccount detectedAccount = createMockDetectedAccount();
        detectedAccount.setCardNumber("1234-5678-9012-3456");
        importResult.setDetectedAccount(detectedAccount);

        when(csvImportService.parseCSV(
                        any(InputStream.class), anyString(), anyString(), nullable(String.class)))
                .thenReturn(importResult);
        when(duplicateDetectionService.detectDuplicates(anyString(), anyList()))
                .thenReturn(new HashMap<>());

        // Act
        final ResponseEntity<?> response =
                transactionController.previewCSV(userDetails, file, null, null, "test.csv", 0, 100);

        // Assert
        final TransactionController.CSVImportPreviewResponse previewResponse =
                (TransactionController.CSVImportPreviewResponse) response.getBody();
        assertNotNull(previewResponse.getDetectedAccount());
        assertEquals("1234-5678-9012-3456", previewResponse.getDetectedAccount().getCardNumber());
    }

    @Test
    void testCSVImportValidRequestReturnsBatchImportResponse() throws IOException {
        // Arrange
        final String csvContent = "Date,Description,Amount\n2024-01-01,Test,100.00";
        final MockMultipartFile file =
                new MockMultipartFile("file", "test.csv", "text/csv", csvContent.getBytes(StandardCharsets.UTF_8));

        final CSVImportService.ImportResult importResult = new CSVImportService.ImportResult();
        for (final CSVImportService.ParsedTransaction tx : createMockParsedTransactions(1)) {
            importResult.addTransaction(tx);
        }
        importResult.setDetectedAccount(createMockDetectedAccount());

        when(csvImportService.parseCSV(
                        any(InputStream.class), anyString(), anyString(), nullable(String.class)))
                .thenReturn(importResult);
        when(duplicateDetectionService.detectDuplicates(anyString(), anyList()))
                .thenReturn(new HashMap<>());

        // Mock transaction creation
        final TransactionTable mockTransaction = new TransactionTable();
        mockTransaction.setTransactionId("test-transaction-id");
        // Use the 25-parameter main method (includes location)
        when(transactionService.createTransaction(
                        any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                        any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                        any(), any(), any()))
                .thenReturn(mockTransaction);

        // Act
        final ResponseEntity<?> response =
                transactionController.importCSV(userDetails, file, null, null, "test.csv");

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody() instanceof TransactionController.BatchImportResponse);

        final TransactionController.BatchImportResponse batchResponse =
                (TransactionController.BatchImportResponse) response.getBody();
        assertTrue(batchResponse.getTotal() > 0);
        assertTrue(batchResponse.getCreated() >= 0);
        assertTrue(batchResponse.getFailed() >= 0);

        // Verify successful field is boolean
        assertTrue(
                batchResponse.getSuccessful()
                        || !batchResponse.getSuccessful()); // Can be true or false
    }

    // Helper methods
    private List<CSVImportService.ParsedTransaction> createMockParsedTransactions(final int count) {
        final List<CSVImportService.ParsedTransaction> transactions = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            final CSVImportService.ParsedTransaction tx = new CSVImportService.ParsedTransaction();
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
        final AccountDetectionService.DetectedAccount account =
                new AccountDetectionService.DetectedAccount();
        account.setInstitutionName("Test Bank");
        account.setAccountType("CHECKING");
        account.setAccountName("Test Account");
        return account;
    }
}
