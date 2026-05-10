package com.budgetbuddy.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.when;

import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.service.TransactionService;
import com.budgetbuddy.service.UserService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.UserDetails;

/** Unit Tests for TransactionController */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
@SuppressWarnings("PMD.LawOfDemeter")
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class TransactionControllerTest {

    @Mock private TransactionService transactionService;

    @Mock private UserService userService;

    @Mock private UserDetails userDetails;

    @Mock private com.budgetbuddy.repository.dynamodb.AccountRepository accountRepository;

    @Mock private com.budgetbuddy.security.FileUploadRateLimiter fileUploadRateLimiter;

    @Mock private com.budgetbuddy.security.FileSecurityValidator fileSecurityValidator;

    @Mock private com.budgetbuddy.security.FileContentScanner fileContentScanner;

    @Mock private com.budgetbuddy.security.FileQuarantineService fileQuarantineService;

    @Mock private com.budgetbuddy.security.FileIntegrityService fileIntegrityService;

    @Mock private com.budgetbuddy.service.CSVImportService csvImportService;

    @Mock private com.budgetbuddy.service.ExcelImportService excelImportService;

    @Mock private com.budgetbuddy.service.PDFImportService pdfImportService;

    @Mock private com.budgetbuddy.service.DuplicateDetectionService duplicateDetectionService;

    @Mock
    private com.budgetbuddy.service.TransactionTypeCategoryService transactionTypeCategoryService;

    @Mock private com.budgetbuddy.service.ChunkedUploadService chunkedUploadService;

    @Mock private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @Mock private com.budgetbuddy.service.AccountDetectionService accountDetectionService;

    @Mock private com.budgetbuddy.api.config.TransactionControllerConfig config;

    private TransactionController transactionController;

    private UserTable testUser;

    @BeforeEach
    void setUp() {
        testUser = new UserTable();
        testUser.setUserId("user-123");
        testUser.setEmail("test@example.com");

        when(userDetails.getUsername()).thenReturn("test@example.com");

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

        // Pass-through idempotency so the test's createTransaction stub
        // actually runs; we locally wire it so the supplier is invoked and
        // its result is returned unchanged.
        final com.budgetbuddy.service.correctness.IdempotencyService idempotencyMock =
                org.mockito.Mockito.mock(
                        com.budgetbuddy.service.correctness.IdempotencyService.class);
        org.mockito.Mockito.lenient()
                .when(
                        idempotencyMock.runOnce(
                                org.mockito.ArgumentMatchers.anyString(),
                                org.mockito.ArgumentMatchers.any(),
                                org.mockito.ArgumentMatchers.any(
                                        java.util.function.Supplier.class)))
                .thenAnswer(inv -> ((java.util.function.Supplier<?>) inv.getArgument(2)).get());

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
                        idempotencyMock,
                        org.mockito.Mockito.mock(
                                com.budgetbuddy.service.importer.TransactionImportOrchestrator
                                        .class));
    }

    @Test
    void testGetTransactionsWithValidUserReturnsTransactions() {
        // Given
        final List<TransactionTable> mockTransactions = Arrays.asList(createTransaction("tx-1"));
        when(userService.findByEmail("test@example.com"))
                .thenReturn(java.util.Optional.of(testUser));
        when(transactionService.getTransactions(testUser, 0, 20)).thenReturn(mockTransactions);

        // When
        final ResponseEntity<List<TransactionTable>> response =
                transactionController.getTransactions(userDetails, 0, 20);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        // Mock returns 1 transaction, so assertion should match
        assertEquals(1, response.getBody().size());
    }

    @Test
    void testGetTransactionsWithNullUserDetailsThrowsException() {
        // When/Then
        final com.budgetbuddy.exception.AppException exception =
                assertThrows(
                        com.budgetbuddy.exception.AppException.class,
                        () -> transactionController.getTransactions(null, 0, 20));
        assertEquals(
                com.budgetbuddy.exception.ErrorCode.UNAUTHORIZED_ACCESS, exception.getErrorCode());
    }

    @Test
    void testGetTransactionsWithNullUsernameThrowsException() {
        // Given
        when(userDetails.getUsername()).thenReturn(null);

        // When/Then
        final com.budgetbuddy.exception.AppException exception =
                assertThrows(
                        com.budgetbuddy.exception.AppException.class,
                        () -> transactionController.getTransactions(userDetails, 0, 20));
        assertEquals(
                com.budgetbuddy.exception.ErrorCode.UNAUTHORIZED_ACCESS, exception.getErrorCode());
    }

    @Test
    void testGetTransactionsWithNegativePageThrowsException() {
        // Given
        when(userService.findByEmail("test@example.com"))
                .thenReturn(java.util.Optional.of(testUser));

        // When/Then
        final com.budgetbuddy.exception.AppException exception =
                assertThrows(
                        com.budgetbuddy.exception.AppException.class,
                        () -> transactionController.getTransactions(userDetails, -1, 20));
        assertEquals(com.budgetbuddy.exception.ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testGetTransactionsWithInvalidPageSizeThrowsException() {
        // Given
        when(userService.findByEmail("test@example.com"))
                .thenReturn(java.util.Optional.of(testUser));

        // When/Then - Size too small
        final com.budgetbuddy.exception.AppException exception1 =
                assertThrows(
                        com.budgetbuddy.exception.AppException.class,
                        () -> transactionController.getTransactions(userDetails, 0, 0));
        assertEquals(com.budgetbuddy.exception.ErrorCode.INVALID_INPUT, exception1.getErrorCode());

        // When/Then - Size too large
        final com.budgetbuddy.exception.AppException exception2 =
                assertThrows(
                        com.budgetbuddy.exception.AppException.class,
                        () -> transactionController.getTransactions(userDetails, 0, 101));
        assertEquals(com.budgetbuddy.exception.ErrorCode.INVALID_INPUT, exception2.getErrorCode());
    }

    @Test
    void testGetTransactionsWithUserNotFoundThrowsException() {
        // Given
        when(userService.findByEmail("test@example.com")).thenReturn(java.util.Optional.empty());

        // When/Then
        final com.budgetbuddy.exception.AppException exception =
                assertThrows(
                        com.budgetbuddy.exception.AppException.class,
                        () -> transactionController.getTransactions(userDetails, 0, 20));
        assertEquals(com.budgetbuddy.exception.ErrorCode.USER_NOT_FOUND, exception.getErrorCode());
    }

    @Test
    void testGetTransactionsInRangeWithNullUserDetailsThrowsException() {
        // When/Then
        final com.budgetbuddy.exception.AppException exception =
                assertThrows(
                        com.budgetbuddy.exception.AppException.class,
                        () ->
                                transactionController.getTransactionsInRange(
                                        null, LocalDate.now().minusDays(7), LocalDate.now()));
        assertEquals(
                com.budgetbuddy.exception.ErrorCode.UNAUTHORIZED_ACCESS, exception.getErrorCode());
    }

    @Test
    void testGetTransactionsInRangeWithInvalidDateRangeThrowsException() {
        // Given
        final LocalDate startDate = LocalDate.now();
        final LocalDate endDate = LocalDate.now().minusDays(7); // End before start
        when(userService.findByEmail("test@example.com"))
                .thenReturn(java.util.Optional.of(testUser));

        // When/Then
        final com.budgetbuddy.exception.AppException exception =
                assertThrows(
                        com.budgetbuddy.exception.AppException.class,
                        () ->
                                transactionController.getTransactionsInRange(
                                        userDetails, startDate, endDate));
        assertEquals(
                com.budgetbuddy.exception.ErrorCode.INVALID_DATE_RANGE, exception.getErrorCode());
    }

    @Test
    void testGetTransactionWithNullIdThrowsException() {
        // Given
        when(userService.findByEmail("test@example.com"))
                .thenReturn(java.util.Optional.of(testUser));

        // When/Then
        final com.budgetbuddy.exception.AppException exception =
                assertThrows(
                        com.budgetbuddy.exception.AppException.class,
                        () -> transactionController.getTransaction(userDetails, null, null));
        assertEquals(com.budgetbuddy.exception.ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testGetTransactionWithEmptyIdThrowsException() {
        // Given
        when(userService.findByEmail("test@example.com"))
                .thenReturn(java.util.Optional.of(testUser));

        // When/Then
        final com.budgetbuddy.exception.AppException exception =
                assertThrows(
                        com.budgetbuddy.exception.AppException.class,
                        () -> transactionController.getTransaction(userDetails, "", null));
        assertEquals(com.budgetbuddy.exception.ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testCreateTransactionWithNullRequestThrowsException() {
        // Given
        when(userService.findByEmail("test@example.com"))
                .thenReturn(java.util.Optional.of(testUser));

        // When/Then
        assertThrows(
                com.budgetbuddy.exception.AppException.class,
                () -> transactionController.createTransaction(userDetails, null, null));
    }

    @Test
    void testCreateTransactionWithNullAmountThrowsException() {
        // Given
        final TransactionController.CreateTransactionRequest request =
                new TransactionController.CreateTransactionRequest();
        request.setTransactionDate(LocalDate.now());
        request.setCategoryPrimary("dining");
        when(userService.findByEmail("test@example.com"))
                .thenReturn(java.util.Optional.of(testUser));

        // When/Then
        assertThrows(
                com.budgetbuddy.exception.AppException.class,
                () -> transactionController.createTransaction(userDetails, null, request));
    }

    @Test
    void testCreateTransactionWithNullDateThrowsException() {
        // Given
        final TransactionController.CreateTransactionRequest request =
                new TransactionController.CreateTransactionRequest();
        request.setAmount(BigDecimal.valueOf(100.00));
        request.setCategoryPrimary("dining");
        when(userService.findByEmail("test@example.com"))
                .thenReturn(java.util.Optional.of(testUser));

        // When/Then
        assertThrows(
                com.budgetbuddy.exception.AppException.class,
                () -> transactionController.createTransaction(userDetails, null, request));
    }

    @Test
    void testCreateTransactionWithNullCategoryThrowsException() {
        // Given
        final TransactionController.CreateTransactionRequest request =
                new TransactionController.CreateTransactionRequest();
        request.setAmount(BigDecimal.valueOf(100.00));
        request.setTransactionDate(LocalDate.now());
        when(userService.findByEmail("test@example.com"))
                .thenReturn(java.util.Optional.of(testUser));

        // When/Then
        assertThrows(
                com.budgetbuddy.exception.AppException.class,
                () -> transactionController.createTransaction(userDetails, null, request));
    }

    @Test
    void testGetTransactionsInRangeWithValidRangeReturnsTransactions() {
        // Given
        final LocalDate startDate = LocalDate.now().minusDays(7);
        final LocalDate endDate = LocalDate.now();
        final List<TransactionTable> mockTransactions = Arrays.asList(createTransaction("tx-1"));
        when(userService.findByEmail("test@example.com"))
                .thenReturn(java.util.Optional.of(testUser));
        when(transactionService.getTransactionsInRange(testUser, startDate, endDate))
                .thenReturn(mockTransactions);

        // When
        final ResponseEntity<List<TransactionTable>> response =
                transactionController.getTransactionsInRange(userDetails, startDate, endDate);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    void testCreateTransactionWithValidDataCreatesTransaction() {
        // Given
        final TransactionController.CreateTransactionRequest request =
                new TransactionController.CreateTransactionRequest();
        request.setAccountId("account-123");
        request.setAmount(BigDecimal.valueOf(100.00));
        request.setTransactionDate(LocalDate.now());
        request.setDescription("Test transaction");
        request.setCategoryPrimary("dining");
        request.setCategoryDetailed("dining");

        final TransactionTable mockTransaction = createTransaction("tx-1");
        when(userService.findByEmail("test@example.com"))
                .thenReturn(java.util.Optional.of(testUser));
        // Updated to match the full 25-parameter method signature (includes location)
        when(transactionService.createTransaction(
                        eq(testUser),
                        eq("account-123"),
                        any(BigDecimal.class),
                        any(LocalDate.class),
                        any(),
                        anyString(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any()))
                .thenReturn(mockTransaction);
        // Controller now re-fetches after create to return the authoritative
        // row (idempotency-cache-hit path). Stub getTransaction too.
        when(transactionService.getTransaction(eq(testUser), eq("tx-1"), any()))
                .thenReturn(mockTransaction);

        // When
        final ResponseEntity<TransactionTable> response =
                transactionController.createTransaction(userDetails, null, request);
        // Then
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertNotNull(response.getBody(), "Response body should not be null");
        assertEquals("tx-1", response.getBody().getTransactionId());
    }

    // Helper methods
    private TransactionTable createTransaction(final String id) {
        final TransactionTable transaction = new TransactionTable();
        transaction.setTransactionId(id);
        transaction.setUserId("user-123");
        transaction.setAmount(BigDecimal.valueOf(100.00));
        transaction.setTransactionDate(
                LocalDate.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE));
        return transaction;
    }
}
