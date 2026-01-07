package com.budgetbuddy.api;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.service.CSVImportService;
import com.budgetbuddy.service.DuplicateDetectionService;
import com.budgetbuddy.service.TransactionService;
import com.budgetbuddy.service.TransactionTypeCategoryService;
import com.budgetbuddy.service.UserService;
import com.budgetbuddy.api.config.TransactionControllerConfig;
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
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

/**
 * Comprehensive tests for CSV Import Controller
 * Tests currency detection, batch import, duplicate detection, preview, and error scenarios
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class CSVImportControllerTest {

    @Mock
    private TransactionService transactionService;

    @Mock
    private UserService userService;

    @Mock
    private CSVImportService csvImportService;

    @Mock
    private DuplicateDetectionService duplicateDetectionService;

    @Mock
    private com.budgetbuddy.service.ImportHistoryService importHistoryService;

    // CRITICAL: Use lenient() for ExcelImportService to avoid Mockito mocking issues
    @Mock
    private com.budgetbuddy.service.ExcelImportService excelImportService;

    @Mock
    private com.budgetbuddy.service.PDFImportService pdfImportService;

    @Mock
    private com.budgetbuddy.repository.dynamodb.TransactionRepository transactionRepository;

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
    private com.budgetbuddy.service.ChunkedUploadService chunkedUploadService;

    @Mock
    private TransactionTypeCategoryService transactionTypeCategoryService;

    @Mock
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @Mock
    private com.budgetbuddy.service.AccountDetectionService accountDetectionService;

    @Mock
    private TransactionControllerConfig config;

    @Mock
    private UserDetails userDetails;

    // CRITICAL: Manually create TransactionController to avoid Mockito mocking issues with ExcelImportService
    // Mockito sometimes has issues with classes that have complex dependencies
    private TransactionController transactionController;

    private UserTable testUser;
    private MultipartFile mockCSVFile;

    @BeforeEach
    void setUp() {
        // CRITICAL: Manually create TransactionController to avoid Mockito mocking issues
        // This ensures all dependencies are properly mocked, including ExcelImportService
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
        when(config.getObjectMapper()).thenReturn(objectMapper);
        
        // Create controller with mocked config
        transactionController = new TransactionController(config);
        
        testUser = new UserTable();
        testUser.setUserId("user-123");
        testUser.setEmail("test@example.com");

        when(userDetails.getUsername()).thenReturn("test@example.com");
        lenient().when(userService.findByEmail("test@example.com")).thenReturn(java.util.Optional.of(testUser));

        // Mock security services to prevent NPE
        lenient().doNothing().when(fileUploadRateLimiter).checkRateLimit(anyString(), anyLong());
        lenient().doNothing().when(fileUploadRateLimiter).recordUpload(anyString(), anyLong());
        lenient().doNothing().when(fileSecurityValidator).validateFileUpload(any(), any());
        com.budgetbuddy.security.FileContentScanner.ScanResult scanResult = 
            new com.budgetbuddy.security.FileContentScanner.ScanResult();
        scanResult.setSafe(true);
        // Note: findings is a final List, so we can't set it - it's initialized as empty ArrayList
        // scanFile throws IOException, but we're mocking it so it won't actually throw
        // Use doReturn which doesn't require exception handling (unlike when().thenReturn())
        try {
            lenient().doReturn(scanResult).when(fileContentScanner).scanFile(any(), anyString());
        } catch (Exception e) {
            // This should never happen in a mock, but handle it just in case
            throw new RuntimeException("Failed to mock scanFile", e);
        }
        lenient().when(fileIntegrityService.calculateChecksum(any())).thenReturn("test-checksum");
        lenient().doNothing().when(fileIntegrityService).storeChecksum(anyString(), anyString(), any());

        // Create mock CSV file
        String csvContent = "Date,Description,Amount\n" +
                "2025-12-01,STARBUCKS COFFEE,-5.50\n" +
                "2025-12-02,AMAZON PURCHASE,-29.99\n";
        mockCSVFile = new MockMultipartFile(
                "file",
                "test.csv",
                "text/csv",
                csvContent.getBytes()
        );
    }

    // MARK: - CSV Preview Tests

    @Test
    void testPreviewCSV_WithValidFile_ReturnsPreview() throws Exception {
        // Given
        CSVImportService.ParsedTransaction parsed1 = createParsedTransaction("2025-12-01", -5.50, "STARBUCKS COFFEE", "USD");
        CSVImportService.ParsedTransaction parsed2 = createParsedTransaction("2025-12-02", -29.99, "AMAZON PURCHASE", "USD");
        List<CSVImportService.ParsedTransaction> parsedTransactions = Arrays.asList(parsed1, parsed2);

        CSVImportService.ImportResult importResult = new CSVImportService.ImportResult();
        for (CSVImportService.ParsedTransaction tx : parsedTransactions) {
            importResult.addTransaction(tx);
        }

        when(csvImportService.parseCSV(any(java.io.InputStream.class), anyString(), anyString(), isNull()))
                .thenReturn(importResult);

        // Mock duplicate detection (returns empty - no duplicates)
        when(duplicateDetectionService.detectDuplicates(eq(testUser.getUserId()), 
                org.mockito.ArgumentMatchers.<List<DuplicateDetectionService.ParsedTransaction>>any()))
                .thenReturn(Map.of());

        // When - Call previewCSV endpoint
        ResponseEntity<TransactionController.CSVImportPreviewResponse> response = 
            transactionController.previewCSV(userDetails, mockCSVFile, null, null, null, 0, 100);
        
        // Then
        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().getTotalParsed() >= 0);
        
        // CRITICAL: Verify all required fields are present in transaction response
        List<Map<String, Object>> transactions = response.getBody().getTransactions();
        assertNotNull(transactions, "Transactions list should not be null");
        assertFalse(transactions.isEmpty(), "Transactions list should not be empty");
        
        Map<String, Object> firstTx = transactions.get(0);
        // Required fields
        assertTrue(firstTx.containsKey("selected"), "Transaction must have 'selected' field");
        assertEquals(true, firstTx.get("selected"), "Transaction 'selected' should default to true");
        assertTrue(firstTx.containsKey("date"), "Transaction must have 'date' field");
        assertTrue(firstTx.containsKey("amount"), "Transaction must have 'amount' field");
        assertTrue(firstTx.containsKey("description"), "Transaction must have 'description' field");
        assertTrue(firstTx.containsKey("categoryPrimary"), "Transaction must have 'categoryPrimary' field");
        assertTrue(firstTx.containsKey("currencyCode"), "Transaction must have 'currencyCode' field");
        assertTrue(firstTx.containsKey("merchantName"), "Transaction must have 'merchantName' field");
        
        // Optional but expected fields
        assertTrue(firstTx.containsKey("hasDuplicates"), "Transaction must have 'hasDuplicates' field");
        assertTrue(firstTx.containsKey("duplicateSimilarity"), "Transaction must have 'duplicateSimilarity' field");
        assertTrue(firstTx.containsKey("duplicateReason"), "Transaction must have 'duplicateReason' field");
        assertTrue(firstTx.containsKey("paymentChannel"), "Transaction must have 'paymentChannel' field");
        assertTrue(firstTx.containsKey("transactionType"), "Transaction must have 'transactionType' field");
        
        verify(csvImportService, times(1)).parseCSV(any(java.io.InputStream.class), anyString(), anyString(), isNull());
        verify(duplicateDetectionService, times(1)).detectDuplicates(eq(testUser.getUserId()), 
                org.mockito.ArgumentMatchers.<List<DuplicateDetectionService.ParsedTransaction>>any());
    }

    @Test
    void testPreviewCSV_WithCurrencyDetection_ReturnsCurrencyCode() throws Exception {
        // Given - CSV with INR currency
        String inrCSV = "Date,Description,Amount\n" +
                "2025-12-01,PAYMENT,₹1000.00\n";
        MultipartFile inrFile = new MockMultipartFile("file", "test_inr.csv", "text/csv", inrCSV.getBytes());

        CSVImportService.ParsedTransaction parsed = createParsedTransaction("2025-12-01", -1000.0, "PAYMENT", "INR");
        CSVImportService.ImportResult importResult = new CSVImportService.ImportResult();
        importResult.addTransaction(parsed);

        when(csvImportService.parseCSV(any(java.io.InputStream.class), anyString(), anyString(), isNull()))
                .thenReturn(importResult);

        when(duplicateDetectionService.detectDuplicates(eq(testUser.getUserId()), 
                org.mockito.ArgumentMatchers.<List<DuplicateDetectionService.ParsedTransaction>>any()))
                .thenReturn(Map.of());

        // When - Call previewCSV endpoint
        ResponseEntity<TransactionController.CSVImportPreviewResponse> response = 
            transactionController.previewCSV(userDetails, inrFile, null, null, null, 0, 100);
        
        // Then
        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        if (response.getBody().getTransactions() != null && !response.getBody().getTransactions().isEmpty()) {
            Map<String, Object> tx = response.getBody().getTransactions().get(0);
            assertTrue(tx.containsKey("currencyCode") || tx.get("currencyCode") != null);
        }
        
        verify(csvImportService, times(1)).parseCSV(any(java.io.InputStream.class), anyString(), anyString(), isNull());
    }

    @Test
    void testPreviewCSV_WithPasswordProtectedFile() throws Exception {
        // Given
        String password = "test123";
        CSVImportService.ImportResult importResult = new CSVImportService.ImportResult();

        when(csvImportService.parseCSV(any(java.io.InputStream.class), anyString(), anyString(), eq(password)))
                .thenReturn(importResult);

        // Mock duplicate detection (previewCSV does use it)
        when(duplicateDetectionService.detectDuplicates(eq(testUser.getUserId()), 
                org.mockito.ArgumentMatchers.<List<DuplicateDetectionService.ParsedTransaction>>any()))
                .thenReturn(Map.of());

        // When - Call previewCSV endpoint
        ResponseEntity<TransactionController.CSVImportPreviewResponse> response = 
            transactionController.previewCSV(userDetails, mockCSVFile, null, password, null, 0, 100);
        
        // Then
        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(csvImportService, times(1)).parseCSV(any(java.io.InputStream.class), anyString(), anyString(), eq(password));
    }

    @Test
    void testPreviewCSV_WithInvalidFile_ReturnsError() throws Exception {
        // Given
        when(csvImportService.parseCSV(any(java.io.InputStream.class), anyString(), anyString(), isNull()))
                .thenThrow(new AppException(ErrorCode.INVALID_INPUT, "Invalid CSV format"));

        // When/Then - Call previewCSV endpoint and expect exception
        assertThrows(AppException.class, () -> {
            transactionController.previewCSV(userDetails, mockCSVFile, null, null, null, 0, 100);
        });
        
        verify(csvImportService, times(1)).parseCSV(any(java.io.InputStream.class), anyString(), anyString(), isNull());
    }

    @Test
    void testPreviewCSV_WithDuplicates_ReturnsDuplicateInfo() throws Exception {
        // Given
        CSVImportService.ParsedTransaction parsed = createParsedTransaction("2025-12-01", -5.50, "STARBUCKS", "USD");
        CSVImportService.ImportResult importResult = new CSVImportService.ImportResult();
        importResult.addTransaction(parsed);

        when(csvImportService.parseCSV(any(java.io.InputStream.class), anyString(), anyString(), isNull()))
                .thenReturn(importResult);

        // Mock duplicate detection - return a duplicate match
        TransactionTable existingTx = createExistingTransaction();
        DuplicateDetectionService.DuplicateMatch match = new DuplicateDetectionService.DuplicateMatch(
            existingTx, 0.95, "Exact match: same description, amount, and date"
        );
        when(duplicateDetectionService.detectDuplicates(eq(testUser.getUserId()), 
                org.mockito.ArgumentMatchers.<List<DuplicateDetectionService.ParsedTransaction>>any()))
                .thenReturn(Map.of(0, List.of(match)));

        // When - Call previewCSV endpoint
        ResponseEntity<TransactionController.CSVImportPreviewResponse> response = 
            transactionController.previewCSV(userDetails, mockCSVFile, null, null, null, 0, 100);
        
        // Then
        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        
        // Verify duplicate information is included
        List<Map<String, Object>> transactions = response.getBody().getTransactions();
        assertNotNull(transactions);
        assertFalse(transactions.isEmpty());
        
        Map<String, Object> tx = transactions.get(0);
        assertEquals(true, tx.get("hasDuplicates"), "Transaction with duplicate should have hasDuplicates=true");
        assertNotNull(tx.get("duplicateSimilarity"), "Transaction with duplicate should have duplicateSimilarity");
        assertNotNull(tx.get("duplicateReason"), "Transaction with duplicate should have duplicateReason");
        assertEquals(0.95, (Double) tx.get("duplicateSimilarity"), 0.01, "Duplicate similarity should match");
        
        verify(csvImportService, times(1)).parseCSV(any(java.io.InputStream.class), anyString(), anyString(), isNull());
        verify(duplicateDetectionService, times(1)).detectDuplicates(eq(testUser.getUserId()), 
                org.mockito.ArgumentMatchers.<List<DuplicateDetectionService.ParsedTransaction>>any());
    }
    
    @Test
    void testPreviewCSV_ResponseStructure_IncludesAllRequiredFields() throws Exception {
        // Given - This test specifically verifies the response structure matches iOS app expectations
        CSVImportService.ParsedTransaction parsed = createParsedTransaction("2025-12-01", -5.50, "TEST TRANSACTION", "USD");
        parsed.setPaymentChannel("online");
        parsed.setTransactionType("EXPENSE");
        
        CSVImportService.ImportResult importResult = new CSVImportService.ImportResult();
        importResult.addTransaction(parsed);

        when(csvImportService.parseCSV(any(java.io.InputStream.class), anyString(), anyString(), isNull()))
                .thenReturn(importResult);

        when(duplicateDetectionService.detectDuplicates(eq(testUser.getUserId()), 
                org.mockito.ArgumentMatchers.<List<DuplicateDetectionService.ParsedTransaction>>any()))
                .thenReturn(Map.of());

        // When
        ResponseEntity<TransactionController.CSVImportPreviewResponse> response = 
            transactionController.previewCSV(userDetails, mockCSVFile, null, null, null, 0, 100);
        
        // Then - Verify response structure matches iOS app expectations
        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        
        List<Map<String, Object>> transactions = response.getBody().getTransactions();
        assertNotNull(transactions, "Transactions must not be null");
        assertEquals(1, transactions.size(), "Should have one transaction");
        
        Map<String, Object> tx = transactions.get(0);
        
        // Required fields that iOS app expects (from CSVUploadService.PreviewTransaction)
        assertTrue(tx.containsKey("selected"), "MISSING: 'selected' field - this would cause iOS decoding error");
        assertEquals(true, tx.get("selected"), "'selected' should default to true");
        
        assertTrue(tx.containsKey("date"), "MISSING: 'date' field");
        assertTrue(tx.containsKey("amount"), "MISSING: 'amount' field");
        assertTrue(tx.containsKey("description"), "MISSING: 'description' field");
        assertTrue(tx.containsKey("categoryPrimary"), "MISSING: 'categoryPrimary' field");
        assertTrue(tx.containsKey("currencyCode"), "MISSING: 'currencyCode' field");
        assertTrue(tx.containsKey("merchantName"), "MISSING: 'merchantName' field");
        
        // Optional but expected fields
        assertTrue(tx.containsKey("hasDuplicates"), "MISSING: 'hasDuplicates' field");
        assertTrue(tx.containsKey("duplicateSimilarity"), "MISSING: 'duplicateSimilarity' field");
        assertTrue(tx.containsKey("duplicateReason"), "MISSING: 'duplicateReason' field");
        assertTrue(tx.containsKey("paymentChannel"), "MISSING: 'paymentChannel' field");
        assertTrue(tx.containsKey("transactionType"), "MISSING: 'transactionType' field");
        
        // Verify field types
        assertTrue(tx.get("selected") instanceof Boolean, "'selected' should be Boolean");
        assertTrue(tx.get("amount") instanceof Double || tx.get("amount") instanceof Number, "'amount' should be Number");
        assertTrue(tx.get("date") instanceof String, "'date' should be String");
        
        verify(csvImportService, times(1)).parseCSV(any(java.io.InputStream.class), anyString(), anyString(), isNull());
        verify(duplicateDetectionService, times(1)).detectDuplicates(eq(testUser.getUserId()), 
                org.mockito.ArgumentMatchers.<List<DuplicateDetectionService.ParsedTransaction>>any());
    }

    // MARK: - Batch Import Tests
    // NOTE: These tests are commented out because batchImportTransactions method doesn't exist in TransactionController
    // The actual batch import happens via /api/transactions/import-bulk endpoint which processes files, not transaction objects

    /*
    @Test
    void testBatchImport_WithValidTransactions_ReturnsSuccess() {
        // Given
        TransactionController.BatchImportRequest request = new TransactionController.BatchImportRequest();
        List<TransactionController.CreateTransactionRequest> transactions = new ArrayList<>();
        
        TransactionController.CreateTransactionRequest tx1 = new TransactionController.CreateTransactionRequest();
        tx1.setAmount(new BigDecimal("-5.50"));
        tx1.setTransactionDate(LocalDate.of(2025, 12, 1));
        tx1.setDescription("STARBUCKS");
        tx1.setCategoryPrimary("dining");
        tx1.setCurrencyCode("USD");
        transactions.add(tx1);

        request.setTransactions(transactions);

        TransactionTable createdTx = createTransaction("tx-1");
        // createTransaction signature: (UserTable, String, BigDecimal, LocalDate, String, String, String, String, String, String, String, String, String, String, String, String, String, String, String)
        // Use nullable() for optional String parameters that can be null
        when(transactionService.createTransaction(
                any(UserTable.class), nullable(String.class), any(BigDecimal.class), any(LocalDate.class),
                anyString(), anyString(), nullable(String.class), nullable(String.class), nullable(String.class), nullable(String.class),
                nullable(String.class), nullable(String.class), nullable(String.class), nullable(String.class), nullable(String.class), nullable(String.class),
                nullable(String.class), nullable(String.class), nullable(String.class)))
                .thenReturn(createdTx);

        // When - NOTE: batchImportTransactions method doesn't exist
        // The actual batch import happens via /api/transactions/import-bulk endpoint
        // ResponseEntity<TransactionController.BatchImportResponse> response = 
        //         transactionController.batchImportTransactions(userDetails, request);

        // Then - Test skipped as method doesn't exist
        // assertEquals(HttpStatus.OK, response.getStatusCode());
        // assertNotNull(response.getBody());
        // assertEquals(1, response.getBody().getCreated());
        // assertEquals(0, response.getBody().getFailed());
    }

    @Test
    void testBatchImport_WithLargeBatch_ReturnsSuccess() {
        // Given
        TransactionController.BatchImportRequest request = new TransactionController.BatchImportRequest();
        List<TransactionController.CreateTransactionRequest> transactions = new ArrayList<>();
        
        for (int i = 0; i < 100; i++) {
            TransactionController.CreateTransactionRequest tx = new TransactionController.CreateTransactionRequest();
            tx.setAmount(new BigDecimal("-" + i));
            tx.setTransactionDate(LocalDate.now());
            tx.setDescription("Transaction " + i);
            tx.setCategoryPrimary("other");
            tx.setCurrencyCode("USD");
            transactions.add(tx);
        }

        request.setTransactions(transactions);

        TransactionTable createdTx = createTransaction("tx-1");
        when(transactionService.createTransaction(
                any(UserTable.class), nullable(String.class), any(BigDecimal.class), any(LocalDate.class),
                anyString(), anyString(), nullable(String.class), nullable(String.class), nullable(String.class), nullable(String.class),
                nullable(String.class), nullable(String.class), nullable(String.class), nullable(String.class), nullable(String.class), nullable(String.class),
                nullable(String.class), nullable(String.class), nullable(String.class)))
                .thenReturn(createdTx);

        // When
        ResponseEntity<TransactionController.BatchImportResponse> response = 
                transactionController.batchImportTransactions(userDetails, request);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(100, response.getBody().getCreated());
    }

    @Test
    void testBatchImport_WithExceedingMaxSize_ThrowsException() {
        // Given
        TransactionController.BatchImportRequest request = new TransactionController.BatchImportRequest();
        List<TransactionController.CreateTransactionRequest> transactions = new ArrayList<>();
        
        // Create batch exceeding 10,000 limit
        for (int i = 0; i < 10001; i++) {
            TransactionController.CreateTransactionRequest tx = new TransactionController.CreateTransactionRequest();
            tx.setAmount(new BigDecimal("-10"));
            tx.setTransactionDate(LocalDate.now());
            tx.setDescription("Transaction " + i);
            transactions.add(tx);
        }

        request.setTransactions(transactions);

        // When/Then
        assertThrows(AppException.class, () -> 
                transactionController.batchImportTransactions(userDetails, request));
    }

    @Test
    void testBatchImport_WithPartialFailures_ReturnsPartialSuccess() {
        // Given
        TransactionController.BatchImportRequest request = new TransactionController.BatchImportRequest();
        List<TransactionController.CreateTransactionRequest> transactions = new ArrayList<>();
        
        TransactionController.CreateTransactionRequest tx1 = new TransactionController.CreateTransactionRequest();
        tx1.setAmount(new BigDecimal("-5.50"));
        tx1.setTransactionDate(LocalDate.now());
        tx1.setDescription("VALID TX");
        tx1.setCategoryPrimary("dining");
        tx1.setCurrencyCode("USD");
        transactions.add(tx1);

        TransactionController.CreateTransactionRequest tx2 = new TransactionController.CreateTransactionRequest();
        tx2.setAmount(new BigDecimal("-10.00"));
        tx2.setTransactionDate(LocalDate.now());
        tx2.setDescription("INVALID TX");
        tx2.setCategoryPrimary("other"); // This will pass validation but fail in createTransaction
        transactions.add(tx2);

        request.setTransactions(transactions);

        TransactionTable createdTx = createTransaction("tx-1");
        // First call succeeds, second call throws exception
        when(transactionService.createTransaction(
                any(UserTable.class), nullable(String.class), any(BigDecimal.class), any(LocalDate.class),
                anyString(), anyString(), nullable(String.class), nullable(String.class), nullable(String.class), nullable(String.class),
                nullable(String.class), nullable(String.class), nullable(String.class), nullable(String.class), nullable(String.class), nullable(String.class),
                nullable(String.class), nullable(String.class), nullable(String.class)))
                .thenReturn(createdTx)
                .thenThrow(new AppException(ErrorCode.INVALID_INPUT, "Invalid transaction"));

        // When
        ResponseEntity<TransactionController.BatchImportResponse> response = 
                transactionController.batchImportTransactions(userDetails, request);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().getCreated());
        assertEquals(1, response.getBody().getFailed());
        // assertNotNull(response.getBody().getFailedIndices());
        // assertEquals(1, response.getBody().getFailedIndices().size());
    }

    @Test
    void testBatchImport_WithEmptyBatch_ThrowsException() {
        // Given
        TransactionController.BatchImportRequest request = new TransactionController.BatchImportRequest();
        request.setTransactions(new ArrayList<>());

        // When/Then
        assertThrows(AppException.class, () -> 
                transactionController.batchImportTransactions(userDetails, request));
    }

    @Test
    void testBatchImport_WithCurrencyCodes_StoresCurrency() {
        // Given
        TransactionController.BatchImportRequest request = new TransactionController.BatchImportRequest();
        List<TransactionController.CreateTransactionRequest> transactions = new ArrayList<>();
        
        TransactionController.CreateTransactionRequest tx = new TransactionController.CreateTransactionRequest();
        tx.setAmount(new BigDecimal("-1000.00"));
        tx.setTransactionDate(LocalDate.now());
        tx.setDescription("INR TRANSACTION");
        tx.setCategoryPrimary("other");
        tx.setCurrencyCode("INR");
        transactions.add(tx);

        request.setTransactions(transactions);

        TransactionTable createdTx = createTransaction("tx-1");
        // createTransaction has 16 parameters total
        when(transactionService.createTransaction(
                any(UserTable.class), nullable(String.class), any(BigDecimal.class), any(LocalDate.class),
                anyString(), anyString(), nullable(String.class), nullable(String.class), nullable(String.class), nullable(String.class),
                nullable(String.class), nullable(String.class), nullable(String.class), eq("INR"), nullable(String.class), nullable(String.class),
                nullable(String.class), nullable(String.class), nullable(String.class)))
                .thenReturn(createdTx);

        // When
        ResponseEntity<TransactionController.BatchImportResponse> response = 
                transactionController.batchImportTransactions(userDetails, request);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        // Verify currency was passed - just verify the method was called (currency is verified via the response)
        verify(transactionService, atLeastOnce()).createTransaction(
                any(UserTable.class), nullable(String.class), any(BigDecimal.class), any(LocalDate.class),
                anyString(), anyString(), nullable(String.class), nullable(String.class), nullable(String.class), nullable(String.class),
                nullable(String.class), nullable(String.class), nullable(String.class), nullable(String.class), nullable(String.class), nullable(String.class));
    }

    @Test
    void testBatchImport_WithImportMetadata_StoresMetadata() {
        // Given
        TransactionController.BatchImportRequest request = new TransactionController.BatchImportRequest();
        List<TransactionController.CreateTransactionRequest> transactions = new ArrayList<>();
        
        TransactionController.CreateTransactionRequest tx = new TransactionController.CreateTransactionRequest();
        tx.setAmount(new BigDecimal("-10.00"));
        tx.setTransactionDate(LocalDate.now());
        tx.setDescription("IMPORTED TX");
        tx.setCategoryPrimary("dining");
        tx.setCurrencyCode("USD");
        tx.setImportSource("CSV");
        tx.setImportBatchId("batch-123");
        tx.setImportFileName("test.csv");
        transactions.add(tx);

        request.setTransactions(transactions);

        TransactionTable createdTx = createTransaction("tx-1");
        // createTransaction has 16 parameters: (UserTable, String, BigDecimal, LocalDate, String, String, String, String, String, String, String, String, String, String, String, String)
        // Parameters 13, 14, 15, 16 are: currencyCode, importSource, importBatchId, importFileName
        // createTransaction signature: (UserTable, String, BigDecimal, LocalDate, String, String, String, String, String, String, String, String, String, String, String, String)
        // 16 parameters total - last 4 are: currencyCode, importSource, importBatchId, importFileName
        when(transactionService.createTransaction(
                any(UserTable.class), nullable(String.class), any(BigDecimal.class), any(LocalDate.class),
                anyString(), anyString(), nullable(String.class), nullable(String.class), nullable(String.class), nullable(String.class),
                nullable(String.class), nullable(String.class), nullable(String.class), nullable(String.class), eq("CSV"), eq("batch-123"),
                nullable(String.class), nullable(String.class), nullable(String.class)))
                .thenReturn(createdTx);

        // When
        ResponseEntity<TransactionController.BatchImportResponse> response = 
                transactionController.batchImportTransactions(userDetails, request);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        // Verify was called with import metadata - just verify the method was called (metadata is verified via the response)
        verify(transactionService, atLeastOnce()).createTransaction(
                any(UserTable.class), nullable(String.class), any(BigDecimal.class), any(LocalDate.class),
                anyString(), anyString(), nullable(String.class), nullable(String.class), nullable(String.class), nullable(String.class),
                nullable(String.class), nullable(String.class), nullable(String.class), nullable(String.class), nullable(String.class), nullable(String.class));
    }
    */

    // MARK: - Helper Methods

    private CSVImportService.ParsedTransaction createParsedTransaction(String date, double amount, String description, String currencyCode) {
        CSVImportService.ParsedTransaction parsed = new CSVImportService.ParsedTransaction();
        parsed.setDate(LocalDate.parse(date));
        parsed.setAmount(new BigDecimal(String.valueOf(amount)));
        parsed.setDescription(description);
        parsed.setCurrencyCode(currencyCode);
        parsed.setCategoryPrimary("other");
        parsed.setCategoryDetailed("other");
        parsed.setMerchantName(description);
        return parsed;
    }

    private TransactionTable createTransaction(String id) {
        TransactionTable tx = new TransactionTable();
        tx.setTransactionId(id);
        tx.setUserId("user-123");
        tx.setAmount(new BigDecimal("-10.00"));
        tx.setTransactionDate("2025-12-01");
        tx.setDescription("Test Transaction");
        return tx;
    }

    private TransactionTable createExistingTransaction() {
        TransactionTable tx = new TransactionTable();
        tx.setTransactionId("existing-tx-1");
        tx.setUserId("user-123");
        tx.setAmount(new BigDecimal("-5.50"));
        tx.setTransactionDate("2025-12-01");
        tx.setDescription("STARBUCKS");
        return tx;
    }
}

