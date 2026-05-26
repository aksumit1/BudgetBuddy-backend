package com.budgetbuddy.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyList;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.service.AccountDetectionService;
import com.budgetbuddy.service.CSVImportService;
import com.budgetbuddy.service.DuplicateDetectionService;
import com.budgetbuddy.service.TransactionService;
import com.budgetbuddy.service.UserService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * Tests for transactionTypeIndicator and cardNumber fields in preview responses Ensures these
 * fields are properly included and populated
 */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
// Test methods declare `throws Exception` for setup convenience —
// JUnit idiom; the rule is a noise generator on test classes.
@SuppressFBWarnings(
        value = "THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION",
        justification = "JUnit idiom — test methods accept any setup exception")
@SuppressWarnings("PMD.LawOfDemeter")
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class PreviewModelFieldsTest {

    private static final String TRANSACTIONTYPEINDICATOR = "transactionTypeIndicator";
    private static final String FILE = "file";
    private static final String TEST = "test";

    @Mock private TransactionService transactionService;

    @Mock private CSVImportService csvImportService;

    @Mock private UserService userService;

    @Mock private DuplicateDetectionService duplicateDetectionService;

    @Mock private com.budgetbuddy.security.FileUploadRateLimiter fileUploadRateLimiter;

    @Mock private com.budgetbuddy.security.FileSecurityValidator fileSecurityValidator;

    @Mock private com.budgetbuddy.security.FileContentScanner fileContentScanner;

    @Mock private com.budgetbuddy.security.FileQuarantineService fileQuarantineService;

    @Mock private com.budgetbuddy.security.FileIntegrityService fileIntegrityService;

    @Mock private com.budgetbuddy.repository.dynamodb.AccountRepository accountRepository;

    @Mock
    private com.budgetbuddy.service.TransactionTypeCategoryService transactionTypeCategoryService;

    @Mock private com.budgetbuddy.service.ChunkedUploadService chunkedUploadService;

    @Mock private com.budgetbuddy.service.AccountDetectionService accountDetectionService;

    @Mock private com.budgetbuddy.api.config.TransactionControllerConfig config;

    @Mock private UserDetails userDetails;

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

        final com.budgetbuddy.security.FileContentScanner.ScanResult safeScanResult =
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
        when(config.getExcelImportService())
                .thenReturn(
                        org.mockito.Mockito.mock(com.budgetbuddy.service.ExcelImportService.class));
        when(config.getPdfImportService())
                .thenReturn(
                        org.mockito.Mockito.mock(com.budgetbuddy.service.PDFImportService.class));
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

        doNothing().when(fileUploadRateLimiter).recordUpload(anyString(), anyLong());
    }

    // ========== transactionTypeIndicator Tests ==========

    @Test
    void testCSVPreviewIncludesTransactionTypeIndicator() throws Exception {
        // Arrange: Create transaction with transactionTypeIndicator
        final CSVImportService.ParsedTransaction tx = createParsedTransaction();
        tx.setTransactionTypeIndicator("DEBIT");

        final CSVImportService.ImportResult importResult = new CSVImportService.ImportResult();
        importResult.addTransaction(tx);

        when(csvImportService.parseCSV(any(), anyString(), anyString(), any()))
                .thenReturn(importResult);

        final MockMultipartFile file =
                new MockMultipartFile(
                        FILE, "test.csv", "text/csv", TEST.getBytes(StandardCharsets.UTF_8));

        // Act
        final ResponseEntity<TransactionController.CSVImportPreviewResponse> response =
                transactionController.previewCSV(userDetails, file, null, null, null, 0, 100);

        // Assert
        assertNotNull(response.getBody());
        final List<Map<String, Object>> transactions = response.getBody().getTransactions();
        assertEquals(1, transactions.size());

        final Map<String, Object> txMap = transactions.getFirst();
        assertTrue(txMap.containsKey(TRANSACTIONTYPEINDICATOR));
        assertEquals("DEBIT", txMap.get(TRANSACTIONTYPEINDICATOR));
    }

    @Test
    void testCSVPreviewTransactionTypeIndicatorNullNotIncluded() throws Exception {
        // Arrange: Create transaction without transactionTypeIndicator (null)
        final CSVImportService.ParsedTransaction tx = createParsedTransaction();
        tx.setTransactionTypeIndicator(null); // Explicitly null

        final CSVImportService.ImportResult importResult = new CSVImportService.ImportResult();
        importResult.addTransaction(tx);

        when(csvImportService.parseCSV(any(), anyString(), anyString(), any()))
                .thenReturn(importResult);

        final MockMultipartFile file =
                new MockMultipartFile(
                        FILE, "test.csv", "text/csv", TEST.getBytes(StandardCharsets.UTF_8));

        // Act
        final ResponseEntity<TransactionController.CSVImportPreviewResponse> response =
                transactionController.previewCSV(userDetails, file, null, null, null, 0, 100);

        // Assert: null values should not be included in the map
        assertNotNull(response.getBody());
        final List<Map<String, Object>> transactions = response.getBody().getTransactions();
        assertEquals(1, transactions.size());

        final Map<String, Object> txMap = transactions.getFirst();
        // Field should not be present if null (based on buildTransactionMap logic)
        assertFalse(
                txMap.containsKey(TRANSACTIONTYPEINDICATOR)
                        && txMap.get(TRANSACTIONTYPEINDICATOR) != null);
    }

    @Test
    void testCSVPreviewTransactionTypeIndicatorCREDIT() throws Exception {
        // Arrange
        final CSVImportService.ParsedTransaction tx = createParsedTransaction();
        tx.setTransactionTypeIndicator("CREDIT");

        final CSVImportService.ImportResult importResult = new CSVImportService.ImportResult();
        importResult.addTransaction(tx);

        when(csvImportService.parseCSV(any(), anyString(), anyString(), any()))
                .thenReturn(importResult);

        final MockMultipartFile file =
                new MockMultipartFile(
                        FILE, "test.csv", "text/csv", TEST.getBytes(StandardCharsets.UTF_8));

        // Act
        final ResponseEntity<TransactionController.CSVImportPreviewResponse> response =
                transactionController.previewCSV(userDetails, file, null, null, null, 0, 100);

        // Assert
        assertNotNull(response.getBody());
        final List<Map<String, Object>> transactions = response.getBody().getTransactions();
        final Map<String, Object> txMap = transactions.getFirst();
        assertEquals("CREDIT", txMap.get(TRANSACTIONTYPEINDICATOR));
    }

    @Test
    void testCSVPreviewTransactionTypeIndicatorDR() throws Exception {
        // Arrange: Test alternative format
        final CSVImportService.ParsedTransaction tx = createParsedTransaction();
        tx.setTransactionTypeIndicator("DR");

        final CSVImportService.ImportResult importResult = new CSVImportService.ImportResult();
        importResult.addTransaction(tx);

        when(csvImportService.parseCSV(any(), anyString(), anyString(), any()))
                .thenReturn(importResult);

        final MockMultipartFile file =
                new MockMultipartFile(
                        FILE, "test.csv", "text/csv", TEST.getBytes(StandardCharsets.UTF_8));

        // Act
        final ResponseEntity<TransactionController.CSVImportPreviewResponse> response =
                transactionController.previewCSV(userDetails, file, null, null, null, 0, 100);

        // Assert
        assertNotNull(response.getBody());
        final List<Map<String, Object>> transactions = response.getBody().getTransactions();
        final Map<String, Object> txMap = transactions.getFirst();
        assertEquals("DR", txMap.get(TRANSACTIONTYPEINDICATOR));
    }

    // ========== cardNumber in DetectedAccountInfo Tests ==========

    @Test
    void testCSVPreviewDetectedAccountInfoIncludesCardNumber() throws Exception {
        // Arrange: Create import result with detected account that has cardNumber
        final CSVImportService.ImportResult importResult = new CSVImportService.ImportResult();
        importResult.addTransaction(createParsedTransaction());

        final AccountDetectionService.DetectedAccount detectedAccount =
                new AccountDetectionService.DetectedAccount();
        detectedAccount.setAccountName("Chase Credit Card");
        detectedAccount.setInstitutionName("Chase");
        detectedAccount.setAccountType("CREDIT_CARD");
        detectedAccount.setAccountSubtype("REWARDS");
        detectedAccount.setAccountNumber("****1234");
        detectedAccount.setCardNumber("1234"); // Card number (last 4 digits)
        importResult.setDetectedAccount(detectedAccount);

        when(csvImportService.parseCSV(any(), anyString(), anyString(), any()))
                .thenReturn(importResult);

        final MockMultipartFile file =
                new MockMultipartFile(
                        FILE, "test.csv", "text/csv", TEST.getBytes(StandardCharsets.UTF_8));

        // Act
        final ResponseEntity<TransactionController.CSVImportPreviewResponse> response =
                transactionController.previewCSV(userDetails, file, null, null, null, 0, 100);

        // Assert
        assertNotNull(response.getBody());
        final TransactionController.DetectedAccountInfo accountInfo =
                response.getBody().getDetectedAccount();
        assertNotNull(accountInfo);
        assertEquals("Chase Credit Card", accountInfo.getAccountName());
        assertEquals("Chase", accountInfo.getInstitutionName());
        assertEquals("CREDIT_CARD", accountInfo.getAccountType());
        assertEquals("****1234", accountInfo.getAccountNumber());
        assertEquals("1234", accountInfo.getCardNumber()); // Card number should be included
    }

    @Test
    void testCSVPreviewDetectedAccountInfoCardNumberNull() throws Exception {
        // Arrange: Detected account without cardNumber (null)
        final CSVImportService.ImportResult importResult = new CSVImportService.ImportResult();
        importResult.addTransaction(createParsedTransaction());

        final AccountDetectionService.DetectedAccount detectedAccount =
                new AccountDetectionService.DetectedAccount();
        detectedAccount.setAccountName("Checking Account");
        detectedAccount.setInstitutionName("Bank of America");
        detectedAccount.setAccountType("CHECKING");
        detectedAccount.setAccountNumber("****5678");
        detectedAccount.setCardNumber(null); // No card number for checking account
        importResult.setDetectedAccount(detectedAccount);

        when(csvImportService.parseCSV(any(), anyString(), anyString(), any()))
                .thenReturn(importResult);

        final MockMultipartFile file =
                new MockMultipartFile(
                        FILE, "test.csv", "text/csv", TEST.getBytes(StandardCharsets.UTF_8));

        // Act
        final ResponseEntity<TransactionController.CSVImportPreviewResponse> response =
                transactionController.previewCSV(userDetails, file, null, null, null, 0, 100);

        // Assert
        assertNotNull(response.getBody());
        final TransactionController.DetectedAccountInfo accountInfo =
                response.getBody().getDetectedAccount();
        assertNotNull(accountInfo);
        assertEquals("Checking Account", accountInfo.getAccountName());
        assertNull(accountInfo.getCardNumber()); // Should be null for non-credit-card accounts
    }

    @Test
    void testCSVPreviewDetectedAccountInfoNoDetectedAccount() throws Exception {
        // Arrange: Import result without detected account
        final CSVImportService.ImportResult importResult = new CSVImportService.ImportResult();
        importResult.addTransaction(createParsedTransaction());
        // No detected account set

        when(csvImportService.parseCSV(any(), anyString(), anyString(), any()))
                .thenReturn(importResult);

        final MockMultipartFile file =
                new MockMultipartFile(
                        FILE, "test.csv", "text/csv", TEST.getBytes(StandardCharsets.UTF_8));

        // Act
        final ResponseEntity<TransactionController.CSVImportPreviewResponse> response =
                transactionController.previewCSV(userDetails, file, null, null, null, 0, 100);

        // Assert
        assertNotNull(response.getBody());
        final TransactionController.DetectedAccountInfo accountInfo =
                response.getBody().getDetectedAccount();
        assertNull(accountInfo); // Should be null when no account detected
    }

    @Test
    void testCSVPreviewMultipleTransactionsAllHaveTransactionTypeIndicator() throws Exception {
        // Arrange: Create multiple transactions with different indicators
        final CSVImportService.ImportResult importResult = new CSVImportService.ImportResult();

        final CSVImportService.ParsedTransaction tx1 = createParsedTransaction();
        tx1.setTransactionTypeIndicator("DEBIT");
        importResult.addTransaction(tx1);

        final CSVImportService.ParsedTransaction tx2 = createParsedTransaction();
        tx2.setTransactionTypeIndicator("CREDIT");
        importResult.addTransaction(tx2);

        final CSVImportService.ParsedTransaction tx3 = createParsedTransaction();
        tx3.setTransactionTypeIndicator("DR");
        importResult.addTransaction(tx3);

        when(csvImportService.parseCSV(any(), anyString(), anyString(), any()))
                .thenReturn(importResult);

        final MockMultipartFile file =
                new MockMultipartFile(
                        FILE, "test.csv", "text/csv", TEST.getBytes(StandardCharsets.UTF_8));

        // Act
        final ResponseEntity<TransactionController.CSVImportPreviewResponse> response =
                transactionController.previewCSV(userDetails, file, null, null, null, 0, 100);

        // Assert
        assertNotNull(response.getBody());
        final List<Map<String, Object>> transactions = response.getBody().getTransactions();
        assertEquals(3, transactions.size());

        assertEquals("DEBIT", transactions.getFirst().get(TRANSACTIONTYPEINDICATOR));
        assertEquals("CREDIT", transactions.get(1).get(TRANSACTIONTYPEINDICATOR));
        assertEquals("DR", transactions.get(2).get(TRANSACTIONTYPEINDICATOR));
    }

    // ========== Helper Methods ==========

    private CSVImportService.ParsedTransaction createParsedTransaction() {
        final CSVImportService.ParsedTransaction tx = new CSVImportService.ParsedTransaction();
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
