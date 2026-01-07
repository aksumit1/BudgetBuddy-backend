package com.budgetbuddy.api;

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
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for transactionTypeIndicator and cardNumber fields in preview responses
 * Ensures these fields are properly included and populated
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class PreviewModelFieldsTest {

    @Mock
    private TransactionService transactionService;
    
    @Mock
    private CSVImportService csvImportService;
    
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
    
    @BeforeEach
    void setUp() throws java.io.IOException {
        testUser = new UserTable();
        testUser.setUserId("test-user-id");
        testUser.setEmail("test@example.com");
        
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
        when(config.getExcelImportService()).thenReturn(org.mockito.Mockito.mock(com.budgetbuddy.service.ExcelImportService.class));
        when(config.getPdfImportService()).thenReturn(org.mockito.Mockito.mock(com.budgetbuddy.service.PDFImportService.class));
        when(config.getDuplicateDetectionService()).thenReturn(duplicateDetectionService);
        when(config.getTransactionTypeCategoryService()).thenReturn(transactionTypeCategoryService);
        when(config.getChunkedUploadService()).thenReturn(chunkedUploadService);
        when(config.getAccountDetectionService()).thenReturn(accountDetectionService);
        when(config.getObjectMapper()).thenReturn(org.mockito.Mockito.mock(com.fasterxml.jackson.databind.ObjectMapper.class));
        
        // Create controller with mocked config
        transactionController = new TransactionController(config);
        
        doNothing().when(fileUploadRateLimiter).recordUpload(anyString(), anyLong());
    }
    
    // ========== transactionTypeIndicator Tests ==========
    
    @Test
    void testCSVPreview_IncludesTransactionTypeIndicator() throws Exception {
        // Arrange: Create transaction with transactionTypeIndicator
        CSVImportService.ParsedTransaction tx = createParsedTransaction();
        tx.setTransactionTypeIndicator("DEBIT");
        
        CSVImportService.ImportResult importResult = new CSVImportService.ImportResult();
        importResult.addTransaction(tx);
        
        when(csvImportService.parseCSV(any(), anyString(), anyString(), any()))
            .thenReturn(importResult);
        
        MockMultipartFile file = new MockMultipartFile("file", "test.csv", "text/csv", "test".getBytes());
        
        // Act
        ResponseEntity<TransactionController.CSVImportPreviewResponse> response = 
            transactionController.previewCSV(userDetails, file, null, null, null, 0, 100);
        
        // Assert
        assertNotNull(response.getBody());
        List<Map<String, Object>> transactions = response.getBody().getTransactions();
        assertEquals(1, transactions.size());
        
        Map<String, Object> txMap = transactions.get(0);
        assertTrue(txMap.containsKey("transactionTypeIndicator"));
        assertEquals("DEBIT", txMap.get("transactionTypeIndicator"));
    }
    
    @Test
    void testCSVPreview_TransactionTypeIndicator_Null_NotIncluded() throws Exception {
        // Arrange: Create transaction without transactionTypeIndicator (null)
        CSVImportService.ParsedTransaction tx = createParsedTransaction();
        tx.setTransactionTypeIndicator(null); // Explicitly null
        
        CSVImportService.ImportResult importResult = new CSVImportService.ImportResult();
        importResult.addTransaction(tx);
        
        when(csvImportService.parseCSV(any(), anyString(), anyString(), any()))
            .thenReturn(importResult);
        
        MockMultipartFile file = new MockMultipartFile("file", "test.csv", "text/csv", "test".getBytes());
        
        // Act
        ResponseEntity<TransactionController.CSVImportPreviewResponse> response = 
            transactionController.previewCSV(userDetails, file, null, null, null, 0, 100);
        
        // Assert: null values should not be included in the map
        assertNotNull(response.getBody());
        List<Map<String, Object>> transactions = response.getBody().getTransactions();
        assertEquals(1, transactions.size());
        
        Map<String, Object> txMap = transactions.get(0);
        // Field should not be present if null (based on buildTransactionMap logic)
        assertFalse(txMap.containsKey("transactionTypeIndicator") && txMap.get("transactionTypeIndicator") != null);
    }
    
    @Test
    void testCSVPreview_TransactionTypeIndicator_CREDIT() throws Exception {
        // Arrange
        CSVImportService.ParsedTransaction tx = createParsedTransaction();
        tx.setTransactionTypeIndicator("CREDIT");
        
        CSVImportService.ImportResult importResult = new CSVImportService.ImportResult();
        importResult.addTransaction(tx);
        
        when(csvImportService.parseCSV(any(), anyString(), anyString(), any()))
            .thenReturn(importResult);
        
        MockMultipartFile file = new MockMultipartFile("file", "test.csv", "text/csv", "test".getBytes());
        
        // Act
        ResponseEntity<TransactionController.CSVImportPreviewResponse> response = 
            transactionController.previewCSV(userDetails, file, null, null, null, 0, 100);
        
        // Assert
        assertNotNull(response.getBody());
        List<Map<String, Object>> transactions = response.getBody().getTransactions();
        Map<String, Object> txMap = transactions.get(0);
        assertEquals("CREDIT", txMap.get("transactionTypeIndicator"));
    }
    
    @Test
    void testCSVPreview_TransactionTypeIndicator_DR() throws Exception {
        // Arrange: Test alternative format
        CSVImportService.ParsedTransaction tx = createParsedTransaction();
        tx.setTransactionTypeIndicator("DR");
        
        CSVImportService.ImportResult importResult = new CSVImportService.ImportResult();
        importResult.addTransaction(tx);
        
        when(csvImportService.parseCSV(any(), anyString(), anyString(), any()))
            .thenReturn(importResult);
        
        MockMultipartFile file = new MockMultipartFile("file", "test.csv", "text/csv", "test".getBytes());
        
        // Act
        ResponseEntity<TransactionController.CSVImportPreviewResponse> response = 
            transactionController.previewCSV(userDetails, file, null, null, null, 0, 100);
        
        // Assert
        assertNotNull(response.getBody());
        List<Map<String, Object>> transactions = response.getBody().getTransactions();
        Map<String, Object> txMap = transactions.get(0);
        assertEquals("DR", txMap.get("transactionTypeIndicator"));
    }
    
    // ========== cardNumber in DetectedAccountInfo Tests ==========
    
    @Test
    void testCSVPreview_DetectedAccountInfo_IncludesCardNumber() throws Exception {
        // Arrange: Create import result with detected account that has cardNumber
        CSVImportService.ImportResult importResult = new CSVImportService.ImportResult();
        importResult.addTransaction(createParsedTransaction());
        
        AccountDetectionService.DetectedAccount detectedAccount = new AccountDetectionService.DetectedAccount();
        detectedAccount.setAccountName("Chase Credit Card");
        detectedAccount.setInstitutionName("Chase");
        detectedAccount.setAccountType("CREDIT_CARD");
        detectedAccount.setAccountSubtype("REWARDS");
        detectedAccount.setAccountNumber("****1234");
        detectedAccount.setCardNumber("1234"); // Card number (last 4 digits)
        importResult.setDetectedAccount(detectedAccount);
        
        when(csvImportService.parseCSV(any(), anyString(), anyString(), any()))
            .thenReturn(importResult);
        
        MockMultipartFile file = new MockMultipartFile("file", "test.csv", "text/csv", "test".getBytes());
        
        // Act
        ResponseEntity<TransactionController.CSVImportPreviewResponse> response = 
            transactionController.previewCSV(userDetails, file, null, null, null, 0, 100);
        
        // Assert
        assertNotNull(response.getBody());
        TransactionController.DetectedAccountInfo accountInfo = response.getBody().getDetectedAccount();
        assertNotNull(accountInfo);
        assertEquals("Chase Credit Card", accountInfo.getAccountName());
        assertEquals("Chase", accountInfo.getInstitutionName());
        assertEquals("CREDIT_CARD", accountInfo.getAccountType());
        assertEquals("****1234", accountInfo.getAccountNumber());
        assertEquals("1234", accountInfo.getCardNumber()); // Card number should be included
    }
    
    @Test
    void testCSVPreview_DetectedAccountInfo_CardNumber_Null() throws Exception {
        // Arrange: Detected account without cardNumber (null)
        CSVImportService.ImportResult importResult = new CSVImportService.ImportResult();
        importResult.addTransaction(createParsedTransaction());
        
        AccountDetectionService.DetectedAccount detectedAccount = new AccountDetectionService.DetectedAccount();
        detectedAccount.setAccountName("Checking Account");
        detectedAccount.setInstitutionName("Bank of America");
        detectedAccount.setAccountType("CHECKING");
        detectedAccount.setAccountNumber("****5678");
        detectedAccount.setCardNumber(null); // No card number for checking account
        importResult.setDetectedAccount(detectedAccount);
        
        when(csvImportService.parseCSV(any(), anyString(), anyString(), any()))
            .thenReturn(importResult);
        
        MockMultipartFile file = new MockMultipartFile("file", "test.csv", "text/csv", "test".getBytes());
        
        // Act
        ResponseEntity<TransactionController.CSVImportPreviewResponse> response = 
            transactionController.previewCSV(userDetails, file, null, null, null, 0, 100);
        
        // Assert
        assertNotNull(response.getBody());
        TransactionController.DetectedAccountInfo accountInfo = response.getBody().getDetectedAccount();
        assertNotNull(accountInfo);
        assertEquals("Checking Account", accountInfo.getAccountName());
        assertNull(accountInfo.getCardNumber()); // Should be null for non-credit-card accounts
    }
    
    @Test
    void testCSVPreview_DetectedAccountInfo_NoDetectedAccount() throws Exception {
        // Arrange: Import result without detected account
        CSVImportService.ImportResult importResult = new CSVImportService.ImportResult();
        importResult.addTransaction(createParsedTransaction());
        // No detected account set
        
        when(csvImportService.parseCSV(any(), anyString(), anyString(), any()))
            .thenReturn(importResult);
        
        MockMultipartFile file = new MockMultipartFile("file", "test.csv", "text/csv", "test".getBytes());
        
        // Act
        ResponseEntity<TransactionController.CSVImportPreviewResponse> response = 
            transactionController.previewCSV(userDetails, file, null, null, null, 0, 100);
        
        // Assert
        assertNotNull(response.getBody());
        TransactionController.DetectedAccountInfo accountInfo = response.getBody().getDetectedAccount();
        assertNull(accountInfo); // Should be null when no account detected
    }
    
    @Test
    void testCSVPreview_MultipleTransactions_AllHaveTransactionTypeIndicator() throws Exception {
        // Arrange: Create multiple transactions with different indicators
        CSVImportService.ImportResult importResult = new CSVImportService.ImportResult();
        
        CSVImportService.ParsedTransaction tx1 = createParsedTransaction();
        tx1.setTransactionTypeIndicator("DEBIT");
        importResult.addTransaction(tx1);
        
        CSVImportService.ParsedTransaction tx2 = createParsedTransaction();
        tx2.setTransactionTypeIndicator("CREDIT");
        importResult.addTransaction(tx2);
        
        CSVImportService.ParsedTransaction tx3 = createParsedTransaction();
        tx3.setTransactionTypeIndicator("DR");
        importResult.addTransaction(tx3);
        
        when(csvImportService.parseCSV(any(), anyString(), anyString(), any()))
            .thenReturn(importResult);
        
        MockMultipartFile file = new MockMultipartFile("file", "test.csv", "text/csv", "test".getBytes());
        
        // Act
        ResponseEntity<TransactionController.CSVImportPreviewResponse> response = 
            transactionController.previewCSV(userDetails, file, null, null, null, 0, 100);
        
        // Assert
        assertNotNull(response.getBody());
        List<Map<String, Object>> transactions = response.getBody().getTransactions();
        assertEquals(3, transactions.size());
        
        assertEquals("DEBIT", transactions.get(0).get("transactionTypeIndicator"));
        assertEquals("CREDIT", transactions.get(1).get("transactionTypeIndicator"));
        assertEquals("DR", transactions.get(2).get("transactionTypeIndicator"));
    }
    
    // ========== Helper Methods ==========
    
    private CSVImportService.ParsedTransaction createParsedTransaction() {
        CSVImportService.ParsedTransaction tx = new CSVImportService.ParsedTransaction();
        tx.setDate(LocalDate.now());
        tx.setAmount(BigDecimal.valueOf(100.0));
        tx.setDescription("Test Transaction");
        tx.setMerchantName("Test Merchant");
        tx.setCategoryPrimary("other");
        tx.setCategoryDetailed("other");
        tx.setCurrencyCode("USD");
        tx.setTransactionType("EXPENSE");
        return tx;
    }
}

