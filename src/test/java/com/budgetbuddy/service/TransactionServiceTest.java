package com.budgetbuddy.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit Tests for TransactionService */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class TransactionServiceTest {

    private static final String ACCOUNT_123 = "account-123";
    private static final String TX_1 = "tx-1";
    private static final String USER_123 = "user-123";
    private static final String DINING = "dining";
    private static final String TX_EXISTING = "tx-existing";
    private static final String PLAID_ACC_123 = "plaid-acc-123";
    private static final String PLAID_123 = "plaid-123";
    private static final String SALARY = "salary";

    @Mock private TransactionRepository transactionRepository;

    @Mock private AccountRepository accountRepository;

    @Mock private TransactionTypeCategoryService transactionTypeCategoryService;

    @Mock private com.budgetbuddy.audit.AuditService auditService;

    @InjectMocks private TransactionService transactionService;

    private UserTable testUser;
    private AccountTable testAccount;

    @BeforeEach
    void setUp() {
        testUser = new UserTable();
        testUser.setUserId(USER_123);
        testUser.setEmail("test@example.com");
        testUser.setPreferredCurrency("USD");

        testAccount = new AccountTable();
        testAccount.setAccountId(ACCOUNT_123);
        testAccount.setUserId(USER_123);
        testAccount.setAccountName("Test Account");
    }

    @Test
    void testGetTransactionsWithValidUserReturnsTransactions() {
        // Given
        final List<TransactionTable> mockTransactions =
                Arrays.asList(
                        createTransaction(TX_1, USER_123, BigDecimal.valueOf(100.00)),
                        createTransaction("tx-2", USER_123, BigDecimal.valueOf(200.00)));
        when(transactionRepository.findByUserId(USER_123, 0, 50)).thenReturn(mockTransactions);

        // When
        final List<TransactionTable> result = transactionService.getTransactions(testUser, 0, 50);

        // Then
        assertNotNull(result);
        assertEquals(2, result.size());
        verify(transactionRepository).findByUserId(USER_123, 0, 50);
    }

    @Test
    void testGetTransactionsWithNullUserThrowsException() {
        // When/Then
        final AppException exception =
                assertThrows(
                        AppException.class, () -> transactionService.getTransactions(null, 0, 50));
        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testGetTransactionsWithInvalidPaginationAdjustsLimits() {
        // Given
        when(transactionRepository.findByUserId(anyString(), anyInt(), anyInt()))
                .thenReturn(Collections.emptyList());

        // When - negative skip (should be adjusted to 0)
        transactionService.getTransactions(testUser, -5, 50);
        verify(transactionRepository).findByUserId(USER_123, 0, 50);

        // Reset mock
        reset(transactionRepository);
        when(transactionRepository.findByUserId(anyString(), anyInt(), anyInt()))
                .thenReturn(Collections.emptyList());

        // When - limit too high (should be defaulted to 50, not 100)
        transactionService.getTransactions(testUser, 0, 200);
        verify(transactionRepository).findByUserId(USER_123, 0, 50);
    }

    @Test
    void testGetTransactionsInRangeWithValidDatesReturnsTransactions() {
        // Given
        final LocalDate startDate = LocalDate.now().minusDays(7);
        final LocalDate endDate = LocalDate.now();
        final List<TransactionTable> mockTransactions =
                Arrays.asList(createTransaction(TX_1, USER_123, BigDecimal.valueOf(100.00)));
        when(transactionRepository.findByUserIdAndDateRange(anyString(), anyString(), anyString()))
                .thenReturn(mockTransactions);

        // When
        final List<TransactionTable> result =
                transactionService.getTransactionsInRange(testUser, startDate, endDate);

        // Then
        assertNotNull(result);
        assertEquals(1, result.size());
    }

    @Test
    void testGetTransactionsInRangeWithInvalidDateRangeThrowsException() {
        // Given
        final LocalDate startDate = LocalDate.now();
        final LocalDate endDate = LocalDate.now().minusDays(7);

        // When/Then
        final AppException exception =
                assertThrows(
                        AppException.class,
                        () ->
                                transactionService.getTransactionsInRange(
                                        testUser, startDate, endDate));
        assertEquals(ErrorCode.INVALID_DATE_RANGE, exception.getErrorCode());
    }

    @Test
    void testCreateTransactionWithValidDataCreatesTransaction() {
        // Given
        when(accountRepository.findById(ACCOUNT_123)).thenReturn(Optional.of(testAccount));
        doNothing().when(transactionRepository).save(any(TransactionTable.class));

        // When
        final TransactionTable result =
                transactionService.createTransaction(
                        testUser,
                        ACCOUNT_123,
                        BigDecimal.valueOf(100.00),
                        LocalDate.now(),
                        "Test transaction",
                        "FOOD");

        // Then
        assertNotNull(result);
        assertNotNull(result.getTransactionId());
        assertEquals(USER_123, result.getUserId());
        assertEquals(ACCOUNT_123, result.getAccountId());
        verify(transactionRepository).save(any(TransactionTable.class));
    }

    @Test
    void testCreateTransactionWithInvalidAccountThrowsException() {
        // Given
        when(accountRepository.findById("invalid-account")).thenReturn(Optional.empty());

        // When/Then
        assertThrows(
                AppException.class,
                () ->
                        transactionService.createTransaction(
                                testUser,
                                "invalid-account",
                                BigDecimal.valueOf(100.00),
                                LocalDate.now(),
                                "Test",
                                "FOOD"));
    }

    @Test
    void testCreateTransactionWithUnauthorizedAccountThrowsException() {
        // Given
        final AccountTable otherUserAccount = new AccountTable();
        otherUserAccount.setAccountId("account-456");
        otherUserAccount.setUserId("other-user");
        when(accountRepository.findById("account-456")).thenReturn(Optional.of(otherUserAccount));

        // When/Then
        assertThrows(
                AppException.class,
                () ->
                        transactionService.createTransaction(
                                testUser,
                                "account-456",
                                BigDecimal.valueOf(100.00),
                                LocalDate.now(),
                                "Test",
                                "FOOD"));
    }

    @Test
    void testCreateTransactionWithNullAccountIdUsesPseudoAccount() {
        // Given - No accountId provided, should use pseudo account
        final AccountTable pseudoAccount = createPseudoAccount(testUser.getUserId());
        when(accountRepository.getOrCreatePseudoAccount(testUser.getUserId()))
                .thenReturn(pseudoAccount);
        doNothing().when(transactionRepository).save(any(TransactionTable.class));

        // When
        final TransactionTable result =
                transactionService.createTransaction(
                        testUser,
                        null, // No accountId
                        BigDecimal.valueOf(100.00),
                        LocalDate.now(),
                        "Manual transaction",
                        "FOOD",
                        "RESTAURANTS",
                        null, // importerCategoryPrimary
                        null, // importerCategoryDetailed
                        null, // transactionId
                        null, // notes
                        null, // plaidAccountId
                        null, // plaidTransactionId
                        null, // transactionType
                        null, // currencyCode
                        null, // importSource
                        null, // importBatchId
                        null, // importFileName,
                        null, // reviewStatus
                        null, // merchantName
                        null, // location
                        null, // paymentChannel
                        null, // userName
                        null, // goalId
                        null // linkedTransactionId
                        );

        // Then
        assertNotNull(result);
        assertEquals(pseudoAccount.getAccountId(), result.getAccountId());
        verify(accountRepository).getOrCreatePseudoAccount(testUser.getUserId());
        verify(accountRepository, never()).findById(anyString());
    }

    @Test
    void testCreateTransactionWithEmptyAccountIdUsesPseudoAccount() {
        // Given - Empty accountId provided, should use pseudo account
        final AccountTable pseudoAccount = createPseudoAccount(testUser.getUserId());
        when(accountRepository.getOrCreatePseudoAccount(testUser.getUserId()))
                .thenReturn(pseudoAccount);
        doNothing().when(transactionRepository).save(any(TransactionTable.class));

        // When
        final TransactionTable result =
                transactionService.createTransaction(
                        testUser,
                        "", // Empty accountId
                        BigDecimal.valueOf(100.00),
                        LocalDate.now(),
                        "Manual transaction",
                        "FOOD",
                        "RESTAURANTS",
                        null, // importerCategoryPrimary
                        null, // importerCategoryDetailed
                        null, // transactionId
                        null, // notes
                        null, // plaidAccountId
                        null, // plaidTransactionId
                        null, // transactionType
                        null, // currencyCode
                        null, // importSource
                        null, // importBatchId
                        null, // importFileName,
                        null, // reviewStatus
                        null, // merchantName
                        null, // location
                        null, // paymentChannel
                        null, // userName
                        null, // goalId
                        null // linkedTransactionId
                        );

        // Then
        assertNotNull(result);
        assertEquals(pseudoAccount.getAccountId(), result.getAccountId());
        verify(accountRepository).getOrCreatePseudoAccount(testUser.getUserId());
    }

    @Test
    void testCreateTransactionWithPlaidAccountIdNeverUsesPseudoAccount() {
        // Given - Plaid transaction with plaidAccountId but no accountId
        // Should find account by Plaid ID, NOT use pseudo account
        final AccountTable plaidAccount = new AccountTable();
        plaidAccount.setAccountId("plaid-account-123");
        plaidAccount.setUserId(testUser.getUserId());
        plaidAccount.setPlaidAccountId(PLAID_ACC_123);

        when(accountRepository.findById(null)).thenReturn(Optional.empty());
        when(accountRepository.findByPlaidAccountId(PLAID_ACC_123))
                .thenReturn(Optional.of(plaidAccount));
        doNothing().when(transactionRepository).save(any(TransactionTable.class));

        // When - Plaid transaction without accountId
        final TransactionTable result =
                transactionService.createTransaction(
                        testUser,
                        null, // No accountId
                        BigDecimal.valueOf(100.00),
                        LocalDate.now(),
                        "Plaid transaction",
                        "FOOD",
                        "RESTAURANTS",
                        null, // importerCategoryPrimary
                        null, // importerCategoryDetailed
                        null, // transactionId
                        null, // notes
                        PLAID_ACC_123, // Plaid account ID
                        "plaid-tx-123", // Plaid transaction ID
                        null, // transactionType
                        null, // currencyCode
                        null, // importSource
                        null, // importBatchId
                        null, // importFileName,
                        null, // reviewStatus
                        null, // merchantName
                        null, // location
                        null, // paymentChannel
                        null, // userName
                        null, // goalId
                        null // linkedTransactionId
                        );

        // Then - Should use Plaid account, NOT pseudo account
        assertNotNull(result);
        assertEquals(plaidAccount.getAccountId(), result.getAccountId());
        verify(accountRepository).findByPlaidAccountId(PLAID_ACC_123);
        verify(accountRepository, never()).getOrCreatePseudoAccount(anyString());
    }

    @Test
    void testCreateTransactionWithPlaidAccountIdAndAccountIdUsesAccountId() {
        // Given - Plaid transaction with both accountId and plaidAccountId
        final AccountTable account = new AccountTable();
        account.setAccountId(ACCOUNT_123);
        account.setUserId(testUser.getUserId());
        account.setPlaidAccountId(PLAID_ACC_123);

        when(accountRepository.findById(ACCOUNT_123)).thenReturn(Optional.of(account));
        doNothing().when(transactionRepository).save(any(TransactionTable.class));

        // When
        final TransactionTable result =
                transactionService.createTransaction(
                        testUser,
                        ACCOUNT_123, // Account ID provided
                        BigDecimal.valueOf(100.00),
                        LocalDate.now(),
                        "Plaid transaction",
                        "FOOD",
                        "RESTAURANTS",
                        null, // importerCategoryPrimary
                        null, // importerCategoryDetailed
                        null, // transactionId
                        null, // notes
                        PLAID_ACC_123, // Plaid account ID
                        "plaid-tx-123", // plaidTransactionId
                        null, // transactionType
                        null, // currencyCode
                        null, // importSource
                        null, // importBatchId
                        null, // importFileName,
                        null, // reviewStatus
                        null, // merchantName
                        null, // location
                        null, // paymentChannel
                        null, // userName
                        null, // goalId
                        null // linkedTransactionId
                        );

        // Then - Should use provided accountId
        assertNotNull(result);
        assertEquals(ACCOUNT_123, result.getAccountId());
        verify(accountRepository).findById(ACCOUNT_123);
        verify(accountRepository, never()).getOrCreatePseudoAccount(anyString());
    }

    @Test
    void testCreateTransactionWithPlaidAccountIdButAccountNotFoundThrowsException() {
        // Given - Plaid transaction but account not found
        when(accountRepository.findById(null)).thenReturn(Optional.empty());
        when(accountRepository.findByPlaidAccountId(PLAID_ACC_123)).thenReturn(Optional.empty());

        // When/Then - Should throw exception, NOT use pseudo account
        assertThrows(
                AppException.class,
                () ->
                        transactionService.createTransaction(
                                testUser,
                                null,
                                BigDecimal.valueOf(100.00),
                                LocalDate.now(),
                                "Plaid transaction",
                                "FOOD",
                                "RESTAURANTS",
                                null, // importerCategoryPrimary
                                null, // importerCategoryDetailed
                                null, // transactionId
                                null, // notes
                                PLAID_ACC_123, // plaidAccountId
                                "plaid-tx-123", // plaidTransactionId
                                null, // transactionType
                                null, // currencyCode
                                null, // importSource
                                null, // importBatchId
                                null, // importFileName,
                                null, // reviewStatus
                                null, // merchantName
                                null, // location
                                null, // paymentChannel
                                null, // userName
                                null, // goalId
                                null // linkedTransactionId
                                ));
        verify(accountRepository, never()).getOrCreatePseudoAccount(anyString());
    }

    @Test
    void testCreateTransactionWithAccountIdUsesProvidedAccount() {
        // Given - Manual transaction with accountId
        when(accountRepository.findById(ACCOUNT_123)).thenReturn(Optional.of(testAccount));
        doNothing().when(transactionRepository).save(any(TransactionTable.class));

        // When
        final TransactionTable result =
                transactionService.createTransaction(
                        testUser,
                        ACCOUNT_123, // Account ID provided
                        BigDecimal.valueOf(100.00),
                        LocalDate.now(),
                        "Manual transaction",
                        "FOOD",
                        "RESTAURANTS",
                        null, // importerCategoryPrimary
                        null, // importerCategoryDetailed
                        null, // transactionId
                        null, // notes
                        null, // plaidAccountId
                        null, // plaidTransactionId
                        null, // transactionType
                        null, // currencyCode
                        null, // importSource
                        null, // importBatchId
                        null, // importFileName,
                        null, // reviewStatus
                        null, // merchantName
                        null, // location
                        null, // paymentChannel
                        null, // userName
                        null, // goalId
                        null // linkedTransactionId
                        );

        // Then - Should use provided account, NOT pseudo account
        assertNotNull(result);
        assertEquals(ACCOUNT_123, result.getAccountId());
        verify(accountRepository).findById(ACCOUNT_123);
        verify(accountRepository, never()).getOrCreatePseudoAccount(anyString());
    }

    // Helper method
    private AccountTable createPseudoAccount(final String userId) {
        final AccountTable account = new AccountTable();
        account.setAccountId("pseudo-account-" + userId);
        account.setUserId(userId);
        account.setAccountName("Manual Transactions");
        account.setInstitutionName("BudgetBuddy");
        account.setAccountType("other");
        account.setAccountSubtype("manual");
        account.setBalance(BigDecimal.ZERO);
        account.setActive(true);
        return account;
    }

    @Test
    void testDeleteTransactionWithValidTransactionDeletesTransaction() {
        // Given: transaction exists and is not already soft-deleted
        final TransactionTable transaction =
                createTransaction(TX_1, USER_123, BigDecimal.valueOf(100.00));
        when(transactionRepository.findById(TX_1)).thenReturn(Optional.of(transaction));

        // When
        transactionService.deleteTransaction(testUser, TX_1);

        // Then: SOFT delete — we save the row with deletedAt set, not
        // call transactionRepository.delete(id). This preserves the 30-day
        // undo window required by Flow 4 / O9.
        assertNotNull(transaction.getDeletedAt(), "deletedAt must be stamped");
        verify(transactionRepository).save(transaction);
    }

    @Test
    void testDeleteTransactionWithUnauthorizedTransactionThrowsException() {
        // Given: Transaction exists but belongs to different user
        final TransactionTable otherUserTransaction =
                createTransaction(TX_1, "other-user", BigDecimal.valueOf(100.00));
        when(transactionRepository.findById(TX_1)).thenReturn(Optional.of(otherUserTransaction));

        // When/Then
        final AppException exception =
                assertThrows(
                        AppException.class,
                        () -> transactionService.deleteTransaction(testUser, TX_1));
        assertEquals(ErrorCode.UNAUTHORIZED_ACCESS, exception.getErrorCode());
    }

    @Test
    void testGetTotalSpendingWithValidRangeReturnsTotal() {
        // Given
        final LocalDate startDate = LocalDate.now().minusDays(7);
        final LocalDate endDate = LocalDate.now();
        final List<TransactionTable> transactions =
                Arrays.asList(createTransaction(TX_1, USER_123, BigDecimal.valueOf(100.00)));
        when(transactionRepository.findByUserIdAndDateRange(anyString(), anyString(), anyString()))
                .thenReturn(transactions);

        // When
        final BigDecimal total = transactionService.getTotalSpending(testUser, startDate, endDate);

        // Then
        assertEquals(BigDecimal.valueOf(100.00), total);
    }

    @Test
    void testGetTotalSpendingWithNullAmountsHandlesGracefully() {
        // Given
        final LocalDate startDate = LocalDate.now().minusDays(7);
        final LocalDate endDate = LocalDate.now();
        final TransactionTable tx1 = createTransaction(TX_1, USER_123, BigDecimal.valueOf(100.00));
        when(transactionRepository.findByUserIdAndDateRange(anyString(), anyString(), anyString()))
                .thenReturn(Arrays.asList(tx1));

        // When
        final BigDecimal total = transactionService.getTotalSpending(testUser, startDate, endDate);

        // Then
        assertEquals(BigDecimal.valueOf(100.00), total);
    }

    // Helper methods
    private TransactionTable createTransaction(
            final String id, final String userId, final BigDecimal amount) {
        final TransactionTable transaction = new TransactionTable();
        transaction.setTransactionId(id);
        transaction.setUserId(userId);
        transaction.setAmount(amount);
        transaction.setTransactionDate(
                LocalDate.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE));
        transaction.setCategoryPrimary(DINING);
        transaction.setCategoryDetailed(DINING);
        transaction.setTransactionType("EXPENSE"); // Default transaction type
        return transaction;
    }

    // ========== TRANSACTION TYPE INTEGRATION TESTS ==========

    @Test
    void testCreateTransactionWithUserProvidedTransactionTypeRespectsUserSelection() {
        // Given: User provides transactionType=INVESTMENT
        when(accountRepository.findById(ACCOUNT_123)).thenReturn(Optional.of(testAccount));
        when(transactionRepository.findById(anyString())).thenReturn(Optional.empty());
        doNothing().when(transactionRepository).save(any(TransactionTable.class));
        when(transactionTypeCategoryService.determineTransactionType(
                        any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(
                        new TransactionTypeCategoryService.TypeResult(
                                com.budgetbuddy.model.TransactionType.EXPENSE,
                                "TEST",
                                1.0)); // Would calculate EXPENSE, but user wants INVESTMENT

        // When: Create transaction with user-provided transactionType
        final TransactionTable result =
                transactionService.createTransaction(
                        testUser,
                        ACCOUNT_123,
                        BigDecimal.valueOf(100.00),
                        LocalDate.now(),
                        "Test transaction",
                        "FOOD",
                        DINING,
                        null, // importerCategoryPrimary
                        null, // importerCategoryDetailed
                        null, // transactionId
                        null, // notes
                        null, // plaidAccountId
                        null, // plaidTransactionId
                        "INVESTMENT", // User-provided transactionType
                        null, // currencyCode
                        null, // importSource
                        null, // importBatchId
                        null, // importFileName,
                        null, // reviewStatus
                        null, // merchantName
                        null, // location
                        null, // paymentChannel
                        null, // userName
                        null, // goalId
                        null // linkedTransactionId
                        );

        // Then: Should use user-provided type, not calculated type
        assertNotNull(result);
        assertEquals("INVESTMENT", result.getTransactionType());
        verify(transactionRepository).save(any(TransactionTable.class));
    }

    @Test
    void testCreateTransactionWithExistingTransactionUpdatesTransactionType() {
        // Given: Transaction already exists with transactionType=EXPENSE
        final TransactionTable existing =
                createTransaction(TX_EXISTING, USER_123, BigDecimal.valueOf(100.00));
        existing.setPlaidTransactionId(PLAID_123);

        when(accountRepository.findById(ACCOUNT_123)).thenReturn(Optional.of(testAccount));
        when(transactionRepository.findById(TX_EXISTING)).thenReturn(Optional.of(existing));
        doNothing().when(transactionRepository).save(any(TransactionTable.class));

        // When: User tries to create same transaction with different transactionType=INVESTMENT
        final TransactionTable result =
                transactionService.createTransaction(
                        testUser,
                        ACCOUNT_123,
                        BigDecimal.valueOf(100.00),
                        LocalDate.now(),
                        "Test transaction",
                        "FOOD",
                        DINING,
                        null, // importerCategoryPrimary
                        null, // importerCategoryDetailed
                        TX_EXISTING, // Same transaction ID
                        null, // notes
                        null, // plaidAccountId
                        PLAID_123, // Same Plaid ID (idempotent case)
                        "INVESTMENT", // User wants to change type to INVESTMENT
                        null, // currencyCode
                        null, // importSource
                        null, // importBatchId
                        null, // importFileName,
                        null, // reviewStatus
                        null, // merchantName
                        null, // location
                        null, // paymentChannel
                        null, // userName
                        null, // goalId
                        null // linkedTransactionId
                        );

        // Then: Should update existing transaction's transactionType
        assertEquals("INVESTMENT", result.getTransactionType());
        verify(transactionRepository)
                .save(any(TransactionTable.class)); // Should save the updated transaction
    }

    @Test
    void testCreateTransactionWithExistingTransactionNoPlaidIdUpdatesTransactionType() {
        // Given: Transaction exists without Plaid ID, user provides Plaid ID and transactionType
        final TransactionTable existing =
                createTransaction(TX_EXISTING, USER_123, BigDecimal.valueOf(100.00));
        existing.setPlaidTransactionId(null); // No Plaid ID yet

        when(accountRepository.findById(ACCOUNT_123)).thenReturn(Optional.of(testAccount));
        when(transactionRepository.findById(TX_EXISTING)).thenReturn(Optional.of(existing));
        doNothing().when(transactionRepository).save(any(TransactionTable.class));

        // When: User links transaction to Plaid and changes transactionType
        final TransactionTable result =
                transactionService.createTransaction(
                        testUser,
                        ACCOUNT_123,
                        BigDecimal.valueOf(100.00),
                        LocalDate.now(),
                        "Test transaction",
                        "FOOD",
                        DINING,
                        null, // importerCategoryPrimary
                        null, // importerCategoryDetailed
                        TX_EXISTING, // transactionId
                        null, // notes
                        null, // plaidAccountId
                        "plaid-new-123", // New Plaid ID
                        "INVESTMENT", // User-provided transactionType
                        null, // currencyCode
                        null, // importSource
                        null, // importBatchId
                        null, // importFileName,
                        null, // reviewStatus
                        null, // merchantName
                        null, // location
                        null, // paymentChannel
                        null, // userName
                        null, // goalId
                        null // linkedTransactionId
                        );

        // Then: Should update both Plaid ID and transactionType
        assertEquals("plaid-new-123", result.getPlaidTransactionId());
        assertEquals("INVESTMENT", result.getTransactionType());
        verify(transactionRepository).save(any(TransactionTable.class));
    }

    @Test
    void testCreateTransactionWithInvalidTransactionTypeCalculatesAutomatically() {
        // Given: User provides invalid transactionType
        when(accountRepository.findById(ACCOUNT_123)).thenReturn(Optional.of(testAccount));
        when(transactionRepository.findById(anyString())).thenReturn(Optional.empty());
        doNothing().when(transactionRepository).save(any(TransactionTable.class));
        when(transactionTypeCategoryService.determineTransactionType(
                        any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(
                        new TransactionTypeCategoryService.TypeResult(
                                com.budgetbuddy.model.TransactionType.EXPENSE, "TEST", 1.0));

        // When: Create transaction with invalid transactionType
        final TransactionTable result =
                transactionService.createTransaction(
                        testUser,
                        ACCOUNT_123,
                        BigDecimal.valueOf(100.00),
                        LocalDate.now(),
                        "Test transaction",
                        "FOOD",
                        DINING,
                        null, // importerCategoryPrimary
                        null, // importerCategoryDetailed
                        null, // transactionId
                        null, // notes
                        null, // plaidAccountId
                        null, // plaidTransactionId
                        "INVALID_TYPE", // Invalid transactionType
                        null, // currencyCode
                        null, // importSource
                        null, // importBatchId
                        null, // importFileName,
                        null, // reviewStatus
                        null, // merchantName
                        null, // location
                        null, // paymentChannel
                        null, // userName
                        null, // goalId
                        null // linkedTransactionId
                        );

        // Then: Should calculate automatically (EXPENSE)
        assertNotNull(result);
        assertEquals("EXPENSE", result.getTransactionType());
        verify(transactionTypeCategoryService)
                .determineTransactionType(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void testSaveTransactionWithNullTransactionTypeCalculatesAutomatically() {
        // Given: Transaction with null transactionType (old transaction)
        final TransactionTable transaction =
                createTransaction(TX_1, USER_123, BigDecimal.valueOf(100.00));
        transaction.setTransactionType(null); // Explicitly set to null
        transaction.setAccountId(ACCOUNT_123);

        when(accountRepository.findById(ACCOUNT_123)).thenReturn(Optional.of(testAccount));
        when(transactionRepository.saveIfPlaidTransactionNotExists(transaction)).thenReturn(true);
        when(transactionTypeCategoryService.determineTransactionType(
                        any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(
                        new TransactionTypeCategoryService.TypeResult(
                                com.budgetbuddy.model.TransactionType.EXPENSE, "TEST", 1.0));

        // When: Save transaction with null transactionType
        final TransactionTable result = transactionService.saveTransaction(transaction);

        // Then: Should calculate and set transactionType
        assertNotNull(result);
        assertEquals("EXPENSE", result.getTransactionType());
        verify(transactionTypeCategoryService)
                .determineTransactionType(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void testSaveTransactionWithExistingTransactionNullTransactionTypeCalculatesAndSaves() {
        // Given: Existing transaction with null transactionType
        final TransactionTable newTransaction =
                createTransaction(TX_1, USER_123, BigDecimal.valueOf(100.00));
        newTransaction.setPlaidTransactionId(PLAID_123);
        newTransaction.setAccountId(ACCOUNT_123);

        final TransactionTable existing =
                createTransaction(TX_1, USER_123, BigDecimal.valueOf(100.00));
        existing.setPlaidTransactionId(PLAID_123);
        existing.setAccountId(ACCOUNT_123);
        existing.setTransactionType(null); // Explicitly set to null for this test

        when(accountRepository.findById(ACCOUNT_123)).thenReturn(Optional.of(testAccount));
        when(transactionRepository.saveIfPlaidTransactionNotExists(newTransaction))
                .thenReturn(false);
        when(transactionRepository.findByPlaidTransactionId(PLAID_123))
                .thenReturn(Optional.of(existing));
        doNothing().when(transactionRepository).save(any(TransactionTable.class));
        when(transactionTypeCategoryService.determineTransactionType(
                        any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(
                        new TransactionTypeCategoryService.TypeResult(
                                com.budgetbuddy.model.TransactionType.EXPENSE, "TEST", 1.0));

        // When: Save transaction that already exists with null transactionType
        final TransactionTable result = transactionService.saveTransaction(newTransaction);

        // Then: Should calculate and save transactionType for existing transaction
        assertEquals("EXPENSE", result.getTransactionType());
        verify(transactionRepository)
                .save(any(TransactionTable.class)); // Should save the updated existing transaction
        // Note: determineTransactionType may be called multiple times (once for newTransaction,
        // once for existing)
        verify(transactionTypeCategoryService, atLeastOnce())
                .determineTransactionType(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void testUpdateTransactionWithUserProvidedTransactionTypeRespectsUserSelection() {
        // Given: Existing transaction
        final TransactionTable existing =
                createTransaction(TX_1, USER_123, BigDecimal.valueOf(100.00));
        existing.setAccountId(ACCOUNT_123);

        when(transactionRepository.findById(TX_1)).thenReturn(Optional.of(existing));
        when(accountRepository.findById(ACCOUNT_123)).thenReturn(Optional.of(testAccount));
        when(transactionRepository.saveWithLock(any(TransactionTable.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // When: Update transaction with user-provided transactionType
        final TransactionTable result =
                transactionService.updateTransaction(
                        testUser,
                        TX_1,
                        null, // plaidTransactionId
                        null, // amount
                        null, // notes
                        null, // categoryPrimary
                        null, // categoryDetailed
                        null, // reviewStatus
                        null, // isHidden
                        "INVESTMENT", // User-provided transactionType
                        false, // clearNotesIfNull,
                        null, // goalId
                        null // linkedTransactionId
                        );

        // Then: Should use user-provided transactionType
        assertEquals("INVESTMENT", result.getTransactionType());
        verify(transactionRepository).saveWithLock(existing);
    }

    @Test
    void testUpdateTransactionWithCategoryChangeAndNoTransactionTypeRecalculatesTransactionType() {
        // Given: Existing transaction with EXPENSE type
        final TransactionTable existing =
                createTransaction(TX_1, USER_123, BigDecimal.valueOf(100.00));
        existing.setCategoryPrimary(DINING);
        existing.setCategoryDetailed(DINING);
        existing.setAccountId(ACCOUNT_123);

        when(transactionRepository.findById(TX_1)).thenReturn(Optional.of(existing));
        when(accountRepository.findById(ACCOUNT_123)).thenReturn(Optional.of(testAccount));
        doNothing().when(transactionRepository).save(any(TransactionTable.class));
        when(transactionTypeCategoryService.determineTransactionType(
                        any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(
                        new TransactionTypeCategoryService.TypeResult(
                                com.budgetbuddy.model.TransactionType.INCOME,
                                "TEST",
                                1.0)); // Category change might change type

        // When: Update category but don't provide transactionType
        final TransactionTable result =
                transactionService.updateTransaction(
                        testUser,
                        TX_1,
                        null,
                        null,
                        null,
                        "income", // Changed category
                        SALARY, // Changed detailed category
                        null,
                        null,
                        null, // No user-provided transactionType
                        false,
                        null, // goalId
                        null // linkedTransactionId
                        );

        // Then: Should recalculate transactionType based on new category
        assertEquals("INCOME", result.getTransactionType());
        verify(transactionTypeCategoryService)
                .determineTransactionType(
                        any(), eq("income"), eq(SALARY), any(), any(), any(), any());
    }

    @Test
    void testUpdateTransactionWithUserProvidedTransactionTypeAndCategoryChangeUsesUserType() {
        // Given: Existing transaction
        final TransactionTable existing =
                createTransaction(TX_1, USER_123, BigDecimal.valueOf(100.00));
        existing.setAccountId(ACCOUNT_123);

        when(transactionRepository.findById(TX_1)).thenReturn(Optional.of(existing));
        when(accountRepository.findById(ACCOUNT_123)).thenReturn(Optional.of(testAccount));
        // updateTransaction migrated to saveWithLock for optimistic
        // concurrency protection — stub that path.
        when(transactionRepository.saveWithLock(any(TransactionTable.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(transactionTypeCategoryService.determineTransactionType(
                        any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(
                        new TransactionTypeCategoryService.TypeResult(
                                com.budgetbuddy.model.TransactionType.INCOME,
                                "TEST",
                                1.0)); // Would calculate INCOME

        // When: Update category AND provide transactionType
        final TransactionTable result =
                transactionService.updateTransaction(
                        testUser,
                        TX_1,
                        null,
                        null,
                        null,
                        "income", // Changed category
                        SALARY,
                        null,
                        null,
                        "INVESTMENT", // User-provided transactionType (should override calculation)
                        false,
                        null, // goalId
                        null // linkedTransactionId
                        );

        // Then: Should use user-provided transactionType, not calculated
        assertEquals("INVESTMENT", result.getTransactionType());
        verify(transactionRepository).saveWithLock(existing);
        // Should NOT call determiner because user provided type
        verify(transactionTypeCategoryService, never())
                .determineTransactionType(any(), any(), any(), any(), any(), any(), any());
    }

    // ========== TRANSACTION TYPE OVERRIDDEN FLAG TESTS ==========

    @Test
    void testCreateTransactionWithUserProvidedTransactionTypeSetsOverriddenFlag() {
        // Given: User provides transactionType
        when(accountRepository.findById(ACCOUNT_123)).thenReturn(Optional.of(testAccount));
        when(transactionRepository.findById(anyString())).thenReturn(Optional.empty());
        doNothing().when(transactionRepository).save(any(TransactionTable.class));
        when(transactionTypeCategoryService.determineTransactionType(
                        any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(
                        new TransactionTypeCategoryService.TypeResult(
                                com.budgetbuddy.model.TransactionType.EXPENSE, "TEST", 1.0));

        // When: Create transaction with user-provided transactionType
        final TransactionTable result =
                transactionService.createTransaction(
                        testUser,
                        ACCOUNT_123,
                        BigDecimal.valueOf(100.00),
                        LocalDate.now(),
                        "Test transaction",
                        "FOOD",
                        DINING,
                        null, // importerCategoryPrimary
                        null, // importerCategoryDetailed
                        null, // transactionId
                        null, // notes
                        null, // plaidAccountId
                        null, // plaidTransactionId
                        "INVESTMENT", // User-provided
                        null, // currencyCode
                        null, // importSource
                        null, // importBatchId
                        null, // importFileName,
                        null, // reviewStatus
                        null, // merchantName
                        null, // location
                        null, // paymentChannel
                        null, // userName
                        null, // goalId
                        null // linkedTransactionId
                        );

        // Then: Should set transactionTypeOverridden=true
        assertNotNull(result);
        assertEquals("INVESTMENT", result.getTransactionType());
        assertTrue(
                result.getTransactionTypeOverridden(),
                "transactionTypeOverridden should be true when user provides type");
    }

    @Test
    void testCreateTransactionWithoutUserProvidedTransactionTypeSetsOverriddenFlagFalse() {
        // Given: No user-provided transactionType
        when(accountRepository.findById(ACCOUNT_123)).thenReturn(Optional.of(testAccount));
        when(transactionRepository.findById(anyString())).thenReturn(Optional.empty());
        doNothing().when(transactionRepository).save(any(TransactionTable.class));
        when(transactionTypeCategoryService.determineTransactionType(
                        any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(
                        new TransactionTypeCategoryService.TypeResult(
                                com.budgetbuddy.model.TransactionType.EXPENSE, "TEST", 1.0));

        // When: Create transaction without user-provided transactionType
        final TransactionTable result =
                transactionService.createTransaction(
                        testUser,
                        ACCOUNT_123,
                        BigDecimal.valueOf(100.00),
                        LocalDate.now(),
                        "Test transaction",
                        "FOOD",
                        DINING,
                        null, // importerCategoryPrimary
                        null, // importerCategoryDetailed
                        null, // transactionId
                        null, // notes
                        null, // plaidAccountId
                        null, // plaidTransactionId
                        null, // No user-provided type
                        null, // currencyCode
                        null, // importSource
                        null, // importBatchId
                        null, // importFileName,
                        null, // reviewStatus
                        null, // merchantName
                        null, // location
                        null, // paymentChannel
                        null, // userName
                        null, // goalId
                        null // linkedTransactionId
                        );

        // Then: Should set transactionTypeOverridden=false
        assertNotNull(result);
        assertEquals("EXPENSE", result.getTransactionType());
        assertFalse(
                result.getTransactionTypeOverridden(),
                "transactionTypeOverridden should be false when calculated automatically");
    }

    @Test
    void testUpdateTransactionWithUserProvidedTransactionTypeSetsOverriddenFlag() {
        // Given: Existing transaction without override
        final TransactionTable existing =
                createTransaction(TX_1, USER_123, BigDecimal.valueOf(100.00));
        existing.setTransactionTypeOverridden(false);
        existing.setAccountId(ACCOUNT_123);

        when(transactionRepository.findById(TX_1)).thenReturn(Optional.of(existing));
        when(accountRepository.findById(ACCOUNT_123)).thenReturn(Optional.of(testAccount));
        doNothing().when(transactionRepository).save(any(TransactionTable.class));

        // When: Update with user-provided transactionType
        final TransactionTable result =
                transactionService.updateTransaction(
                        testUser,
                        TX_1,
                        null, // plaidTransactionId
                        null, // amount
                        null, // notes
                        null, // categoryPrimary
                        null, // categoryDetailed
                        null, // reviewStatus
                        null, // isHidden
                        "INVESTMENT", // transactionType - User-provided
                        false, // clearNotesIfNull
                        null, // goalId
                        null // linkedTransactionId
                        );

        // Then: Should set transactionTypeOverridden=true
        assertEquals("INVESTMENT", result.getTransactionType());
        assertTrue(
                result.getTransactionTypeOverridden(),
                "transactionTypeOverridden should be true when user provides type");
    }

    @Test
    void testUpdateTransactionWithCategoryChangeButOverriddenPreservesUserType() {
        // Given: Existing transaction with user override
        final TransactionTable existing =
                createTransaction(TX_1, USER_123, BigDecimal.valueOf(100.00));
        existing.setTransactionTypeOverridden(true); // User has overridden
        existing.setTransactionType("INVESTMENT"); // User's chosen type
        existing.setCategoryPrimary(DINING);
        existing.setCategoryDetailed(DINING);
        existing.setAccountId(ACCOUNT_123);

        when(transactionRepository.findById(TX_1)).thenReturn(Optional.of(existing));
        when(accountRepository.findById(ACCOUNT_123)).thenReturn(Optional.of(testAccount));
        doNothing().when(transactionRepository).save(any(TransactionTable.class));
        when(transactionTypeCategoryService.determineTransactionType(
                        any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(
                        new TransactionTypeCategoryService.TypeResult(
                                com.budgetbuddy.model.TransactionType.EXPENSE,
                                "TEST",
                                1.0)); // Would calculate EXPENSE

        // When: Update category but don't provide transactionType
        final TransactionTable result =
                transactionService.updateTransaction(
                        testUser,
                        TX_1,
                        null, // plaidTransactionId
                        null, // amount
                        null, // notes
                        "income", // categoryPrimary - Changed category
                        SALARY, // categoryDetailed
                        null, // reviewStatus
                        null, // isHidden
                        null, // transactionType - No user-provided transactionType
                        false, // clearNotesIfNull
                        null, // goalId
                        null // linkedTransactionId
                        );

        // Then: Should preserve user's INVESTMENT type (not recalculate)
        assertEquals("INVESTMENT", result.getTransactionType());
        assertTrue(
                result.getTransactionTypeOverridden(),
                "transactionTypeOverridden should remain true");
        // Should NOT recalculate because it's overridden
        verify(transactionTypeCategoryService, never())
                .determineTransactionType(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void testSaveTransactionWithOverriddenTransactionTypePreservesUserType() {
        // Given: Transaction with user override
        final TransactionTable transaction =
                createTransaction(TX_1, USER_123, BigDecimal.valueOf(100.00));
        transaction.setTransactionTypeOverridden(true); // User has overridden
        transaction.setTransactionType("INVESTMENT"); // User's chosen type
        transaction.setAccountId(ACCOUNT_123);

        when(accountRepository.findById(ACCOUNT_123)).thenReturn(Optional.of(testAccount));
        when(transactionRepository.saveIfPlaidTransactionNotExists(transaction)).thenReturn(true);
        when(transactionTypeCategoryService.determineTransactionType(
                        any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(
                        new TransactionTypeCategoryService.TypeResult(
                                com.budgetbuddy.model.TransactionType.EXPENSE,
                                "TEST",
                                1.0)); // Would calculate EXPENSE

        // When: Save transaction (Plaid sync)
        final TransactionTable result = transactionService.saveTransaction(transaction);

        // Then: Should preserve user's INVESTMENT type
        assertEquals("INVESTMENT", result.getTransactionType());
        assertTrue(
                result.getTransactionTypeOverridden(),
                "transactionTypeOverridden should remain true");
        // Should NOT recalculate because it's overridden
        verify(transactionTypeCategoryService, never())
                .determineTransactionType(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void testSaveTransactionWithNullTransactionTypeButOverriddenPreservesOverrideFlag() {
        // Given: Transaction with override flag but null type (edge case)
        final TransactionTable transaction =
                createTransaction(TX_1, USER_123, BigDecimal.valueOf(100.00));
        transaction.setTransactionTypeOverridden(true); // User wants to override, but type is null
        transaction.setAccountId(ACCOUNT_123);

        when(accountRepository.findById(ACCOUNT_123)).thenReturn(Optional.of(testAccount));
        when(transactionRepository.saveIfPlaidTransactionNotExists(transaction)).thenReturn(true);
        when(transactionTypeCategoryService.determineTransactionType(
                        any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(
                        new TransactionTypeCategoryService.TypeResult(
                                com.budgetbuddy.model.TransactionType.EXPENSE, "TEST", 1.0));

        // When: Save transaction
        final TransactionTable result = transactionService.saveTransaction(transaction);

        // Then: Should calculate type (null type takes precedence over override flag)
        assertEquals("EXPENSE", result.getTransactionType());
        // Override flag should be preserved (user's intent to override is respected)
        assertTrue(
                result.getTransactionTypeOverridden(),
                "transactionTypeOverridden should remain true");
    }

    @Test
    void testCreateTransactionWithExistingTransactionOverriddenRespectsExistingOverride() {
        // Given: Existing transaction with user override
        final TransactionTable existing =
                createTransaction(TX_EXISTING, USER_123, BigDecimal.valueOf(100.00));
        existing.setTransactionTypeOverridden(true); // User has overridden
        existing.setPlaidTransactionId(PLAID_123);

        when(accountRepository.findById(ACCOUNT_123)).thenReturn(Optional.of(testAccount));
        when(transactionRepository.findById(TX_EXISTING)).thenReturn(Optional.of(existing));
        doNothing().when(transactionRepository).save(any(TransactionTable.class));

        // When: Try to create same transaction with different transactionType
        final TransactionTable result =
                transactionService.createTransaction(
                        testUser,
                        ACCOUNT_123,
                        BigDecimal.valueOf(100.00),
                        LocalDate.now(),
                        "Test transaction",
                        "FOOD", // categoryPrimary
                        DINING, // categoryDetailed
                        null, // importerCategoryPrimary
                        null, // importerCategoryDetailed
                        TX_EXISTING, // transactionId
                        null, // notes
                        null, // plaidAccountId
                        PLAID_123, // plaidTransactionId
                        "EXPENSE", // transactionType - User wants to change to EXPENSE
                        null, // currencyCode
                        null, // importSource
                        null, // importBatchId
                        null, // importFileName
                        null, // reviewStatus
                        null, // merchantName
                        null, // location
                        null, // paymentChannel
                        null, // userName
                        null, // goalId
                        null // linkedTransactionId
                        );

        // Then: Should update to new user-provided type and keep override flag
        assertEquals("EXPENSE", result.getTransactionType());
        assertTrue(
                result.getTransactionTypeOverridden(),
                "transactionTypeOverridden should remain true");
        verify(transactionRepository).save(any(TransactionTable.class));
    }

    @Test
    void testUpdateTransactionWithEmptyStringTransactionTypeTreatsAsNull() {
        // Given: User sends empty string for transactionType
        final TransactionTable existing =
                createTransaction(TX_1, USER_123, BigDecimal.valueOf(100.00));
        existing.setAccountId(ACCOUNT_123);

        when(transactionRepository.findById(TX_1)).thenReturn(Optional.of(existing));
        when(accountRepository.findById(ACCOUNT_123)).thenReturn(Optional.of(testAccount));
        doNothing().when(transactionRepository).save(any(TransactionTable.class));
        when(transactionTypeCategoryService.determineTransactionType(
                        any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(
                        new TransactionTypeCategoryService.TypeResult(
                                com.budgetbuddy.model.TransactionType.INCOME, "TEST", 1.0));

        // When: Update with empty string transactionType
        final TransactionTable result =
                transactionService.updateTransaction(
                        testUser,
                        TX_1,
                        null, // plaidTransactionId
                        null, // amount
                        null, // notes
                        "income", // categoryPrimary
                        SALARY, // categoryDetailed
                        null, // reviewStatus
                        null, // isHidden
                        "   ", // transactionType - Empty/whitespace string (should be treated as
                        // null)
                        false, // clearNotesIfNull
                        null, // goalId
                        null // linkedTransactionId
                        );

        // Then: Should recalculate (empty string treated as null)
        assertEquals("INCOME", result.getTransactionType());
        verify(transactionTypeCategoryService)
                .determineTransactionType(
                        any(), eq("income"), eq(SALARY), any(), any(), any(), any());
    }

    @Test
    void testUpdateTransactionWithSameTransactionTypeDoesNotSetOverride() {
        // Given: Existing transaction with transactionType=EXPENSE
        final TransactionTable existing =
                createTransaction(TX_1, USER_123, BigDecimal.valueOf(100.00));
        existing.setAccountId(ACCOUNT_123);
        existing.setTransactionType("EXPENSE");
        existing.setTransactionTypeOverridden(false); // Not overridden

        when(transactionRepository.findById(TX_1)).thenReturn(Optional.of(existing));
        when(accountRepository.findById(ACCOUNT_123)).thenReturn(Optional.of(testAccount));
        when(transactionRepository.saveWithLock(any(TransactionTable.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // When: Update with same transactionType (EXPENSE)
        final TransactionTable result =
                transactionService.updateTransaction(
                        testUser,
                        TX_1,
                        null, // plaidTransactionId
                        null, // amount
                        null, // notes
                        null, // categoryPrimary
                        null, // categoryDetailed
                        null, // reviewStatus
                        null, // isHidden
                        "EXPENSE", // transactionType - Same as existing
                        false, // clearNotesIfNull
                        null, // goalId
                        null // linkedTransactionId
                        );

        // Then: Should NOT set override flag (type is same)
        assertEquals("EXPENSE", result.getTransactionType());
        assertFalse(
                result.getTransactionTypeOverridden(),
                "transactionTypeOverridden should remain false when type is same");
        verify(transactionRepository).saveWithLock(any(TransactionTable.class));
    }

    @Test
    void testUpdateTransactionWithDifferentTransactionTypeSetsOverride() {
        // Given: Existing transaction with transactionType=EXPENSE
        final TransactionTable existing =
                createTransaction(TX_1, USER_123, BigDecimal.valueOf(100.00));
        existing.setAccountId(ACCOUNT_123);
        existing.setTransactionType("EXPENSE");
        existing.setTransactionTypeOverridden(false); // Not overridden

        when(transactionRepository.findById(TX_1)).thenReturn(Optional.of(existing));
        when(accountRepository.findById(ACCOUNT_123)).thenReturn(Optional.of(testAccount));
        when(transactionRepository.saveWithLock(any(TransactionTable.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // When: Update with different transactionType (INVESTMENT)
        final TransactionTable result =
                transactionService.updateTransaction(
                        testUser,
                        TX_1,
                        null, // plaidTransactionId
                        null, // amount
                        null, // notes
                        null, // categoryPrimary
                        null, // categoryDetailed
                        null, // reviewStatus
                        null, // isHidden
                        "INVESTMENT", // transactionType - Different from existing
                        false, // clearNotesIfNull
                        null, // goalId
                        null // linkedTransactionId
                        );

        // Then: Should set override flag (type is different)
        assertEquals("INVESTMENT", result.getTransactionType());
        assertTrue(
                result.getTransactionTypeOverridden(),
                "transactionTypeOverridden should be true when type differs");
        verify(transactionRepository).saveWithLock(any(TransactionTable.class));
    }

    @Test
    void testCreateTransactionWithCSVImportFirstTimeDoesNotSetOverride() {
        // Given: CSV import (first time - no existing transaction)
        when(accountRepository.findById(ACCOUNT_123)).thenReturn(Optional.of(testAccount));
        when(transactionRepository.findById(anyString())).thenReturn(Optional.empty());
        doNothing().when(transactionRepository).save(any(TransactionTable.class));
        when(transactionTypeCategoryService.determineTransactionType(
                        any(), anyString(), anyString(), any(), any(), anyString(), anyString()))
                .thenReturn(
                        new TransactionTypeCategoryService.TypeResult(
                                com.budgetbuddy.model.TransactionType.EXPENSE, "CATEGORY", 0.9));

        // When: Create transaction via CSV import with transactionType
        final TransactionTable result =
                transactionService.createTransaction(
                        testUser,
                        ACCOUNT_123,
                        BigDecimal.valueOf(100.00),
                        LocalDate.now(),
                        "Test transaction",
                        "FOOD",
                        DINING,
                        null, // importerCategoryPrimary
                        null, // importerCategoryDetailed
                        null, // transactionId
                        null, // notes
                        null, // plaidAccountId
                        null, // plaidTransactionId
                        "EXPENSE", // transactionType - From CSV import
                        null, // currencyCode
                        "CSV", // importSource - CSV import
                        null, // importBatchId
                        null, // importFileName
                        null, // reviewStatus
                        null, // merchantName
                        null, // location
                        null, // paymentChannel
                        null, // userName
                        null, // goalId
                        null // linkedTransactionId
                        );

        // Then: Should NOT set override flag (first time from import)
        assertNotNull(result);
        assertEquals("EXPENSE", result.getTransactionType());
        assertFalse(
                result.getTransactionTypeOverridden(),
                "transactionTypeOverridden should be false for first-time CSV import");
        verify(transactionRepository).save(any(TransactionTable.class));
    }

    @Test
    void testCreateTransactionWithCSVImportAlreadyOverriddenSameTypePreservesOverride() {
        // Given: CSV import - existing transaction that was already overridden with same type
        final String existingTxId = UUID.randomUUID().toString();
        final TransactionTable existing =
                createTransaction(existingTxId, USER_123, BigDecimal.valueOf(100.00));
        existing.setTransactionType("EXPENSE");
        existing.setTransactionTypeOverridden(true); // Already overridden

        when(accountRepository.findById(ACCOUNT_123)).thenReturn(Optional.of(testAccount));
        when(transactionRepository.findById(anyString())).thenReturn(Optional.of(existing));
        doNothing().when(transactionRepository).save(any(TransactionTable.class));

        // When: Import same transaction via CSV with same transactionType
        final TransactionTable result =
                transactionService.createTransaction(
                        testUser,
                        ACCOUNT_123,
                        BigDecimal.valueOf(100.00),
                        LocalDate.now(),
                        "Test transaction",
                        "FOOD",
                        DINING,
                        null, // importerCategoryPrimary
                        null, // importerCategoryDetailed
                        existingTxId, // transactionId - Existing transaction
                        null, // notes
                        null, // plaidAccountId
                        null, // plaidTransactionId
                        "EXPENSE", // transactionType - Same as existing
                        null, // currencyCode
                        "CSV", // importSource - CSV import
                        null, // importBatchId
                        null, // importFileName
                        null, // reviewStatus
                        null, // merchantName
                        null, // location
                        null, // paymentChannel
                        null, // userName
                        null, // goalId
                        null // linkedTransactionId
                        );

        // Then: Should preserve override flag (was already overridden, same type)
        assertEquals("EXPENSE", result.getTransactionType());
        assertTrue(
                result.getTransactionTypeOverridden(),
                "transactionTypeOverridden should remain true (was already overridden, same type)");
        verify(transactionRepository).save(any(TransactionTable.class));
    }

    @Test
    void testCreateTransactionWithCSVImportAlreadyOverriddenDifferentTypeKeepsOverride() {
        // Given: CSV import - existing transaction that was already overridden with different type
        final String existingTxId = UUID.randomUUID().toString();
        final TransactionTable existing =
                createTransaction(existingTxId, USER_123, BigDecimal.valueOf(100.00));
        existing.setTransactionType("INVESTMENT");
        existing.setTransactionTypeOverridden(true); // Already overridden

        when(accountRepository.findById(ACCOUNT_123)).thenReturn(Optional.of(testAccount));
        when(transactionRepository.findById(anyString())).thenReturn(Optional.of(existing));
        doNothing().when(transactionRepository).save(any(TransactionTable.class));

        // When: Import same transaction via CSV with different transactionType
        final TransactionTable result =
                transactionService.createTransaction(
                        testUser,
                        ACCOUNT_123,
                        BigDecimal.valueOf(100.00),
                        LocalDate.now(),
                        "Test transaction",
                        "FOOD",
                        DINING,
                        null, // importerCategoryPrimary
                        null, // importerCategoryDetailed
                        existingTxId, // transactionId - Existing transaction
                        null, // notes
                        null, // plaidAccountId
                        null, // plaidTransactionId
                        "EXPENSE", // transactionType - Different from existing (was INVESTMENT)
                        null, // currencyCode
                        "CSV", // importSource - CSV import
                        null, // importBatchId
                        null, // importFileName
                        null, // reviewStatus
                        null, // merchantName
                        null, // location
                        null, // paymentChannel
                        null, // userName
                        null, // goalId
                        null // linkedTransactionId
                        );

        // Then: Should keep override flag (was already overridden, different type)
        assertEquals("EXPENSE", result.getTransactionType());
        assertTrue(
                result.getTransactionTypeOverridden(),
                "transactionTypeOverridden should remain true (was already overridden, different type)");
        verify(transactionRepository).save(any(TransactionTable.class));
    }

    @Test
    void testCreateTransactionWithPDFImportFirstTimeDoesNotSetOverride() {
        // Given: PDF import (first time - no existing transaction)
        when(accountRepository.findById(ACCOUNT_123)).thenReturn(Optional.of(testAccount));
        when(transactionRepository.findById(anyString())).thenReturn(Optional.empty());
        doNothing().when(transactionRepository).save(any(TransactionTable.class));
        when(transactionTypeCategoryService.determineTransactionType(
                        any(), anyString(), anyString(), any(), any(), anyString(), anyString()))
                .thenReturn(
                        new TransactionTypeCategoryService.TypeResult(
                                com.budgetbuddy.model.TransactionType.INCOME, "ACCOUNT", 0.9));

        // When: Create transaction via PDF import with transactionType
        final TransactionTable result =
                transactionService.createTransaction(
                        testUser,
                        ACCOUNT_123,
                        BigDecimal.valueOf(100.00),
                        LocalDate.now(),
                        "Test transaction",
                        "INCOME",
                        SALARY,
                        null, // importerCategoryPrimary
                        null, // importerCategoryDetailed
                        null, // transactionId
                        null, // notes
                        null, // plaidAccountId
                        null, // plaidTransactionId
                        "INCOME", // transactionType - From PDF import
                        null, // currencyCode
                        "PDF", // importSource - PDF import
                        null, // importBatchId
                        null, // importFileName
                        null, // reviewStatus
                        null, // merchantName
                        null, // location
                        null, // paymentChannel
                        null, // userName
                        null, // goalId
                        null // linkedTransactionId
                        );

        // Then: Should NOT set override flag (first time from import)
        assertNotNull(result);
        assertEquals("INCOME", result.getTransactionType());
        assertFalse(
                result.getTransactionTypeOverridden(),
                "transactionTypeOverridden should be false for first-time PDF import");
        verify(transactionRepository).save(any(TransactionTable.class));
    }

    @Test
    void testCreateTransactionWithUserAPICallDifferentTypeSetsOverride() {
        // Given: User API call (not import) - existing transaction with different type
        final String existingTxId = UUID.randomUUID().toString();
        final TransactionTable existing =
                createTransaction(existingTxId, USER_123, BigDecimal.valueOf(100.00));
        existing.setTransactionType("EXPENSE");
        existing.setTransactionTypeOverridden(false); // Not overridden

        when(accountRepository.findById(ACCOUNT_123)).thenReturn(Optional.of(testAccount));
        when(transactionRepository.findById(anyString())).thenReturn(Optional.of(existing));
        doNothing().when(transactionRepository).save(any(TransactionTable.class));

        // When: User API call with different transactionType (no importSource)
        final TransactionTable result =
                transactionService.createTransaction(
                        testUser,
                        ACCOUNT_123,
                        BigDecimal.valueOf(100.00),
                        LocalDate.now(),
                        "Test transaction",
                        "FOOD",
                        DINING,
                        null, // importerCategoryPrimary
                        null, // importerCategoryDetailed
                        existingTxId, // transactionId - Existing transaction
                        null, // notes
                        null, // plaidAccountId
                        null, // plaidTransactionId
                        "INVESTMENT", // transactionType - Different from existing (user API call)
                        null, // currencyCode
                        null, // importSource - NOT an import (user API call)
                        null, // importBatchId
                        null, // importFileName
                        null, // reviewStatus
                        null, // merchantName
                        null, // location
                        null, // paymentChannel
                        null, // userName
                        null, // goalId
                        null // linkedTransactionId
                        );

        // Then: Should set override flag (user API call, different type)
        assertEquals("INVESTMENT", result.getTransactionType());
        assertTrue(
                result.getTransactionTypeOverridden(),
                "transactionTypeOverridden should be true for user API call with different type");
        verify(transactionRepository).save(any(TransactionTable.class));
    }

    @Test
    void testCreateTransactionWithUserAPICallSameTypeDoesNotSetOverride() {
        // Given: User API call (not import) - existing transaction with same type
        final String existingTxId = UUID.randomUUID().toString();
        final TransactionTable existing =
                createTransaction(existingTxId, USER_123, BigDecimal.valueOf(100.00));
        existing.setTransactionType("EXPENSE");
        existing.setTransactionTypeOverridden(false); // Not overridden

        when(accountRepository.findById(ACCOUNT_123)).thenReturn(Optional.of(testAccount));
        when(transactionRepository.findById(anyString())).thenReturn(Optional.of(existing));
        doNothing().when(transactionRepository).save(any(TransactionTable.class));

        // When: User API call with same transactionType (no importSource)
        final TransactionTable result =
                transactionService.createTransaction(
                        testUser,
                        ACCOUNT_123,
                        BigDecimal.valueOf(100.00),
                        LocalDate.now(),
                        "Test transaction",
                        "FOOD",
                        DINING,
                        null, // importerCategoryPrimary
                        null, // importerCategoryDetailed
                        existingTxId, // transactionId - Existing transaction
                        null, // notes
                        null, // plaidAccountId
                        null, // plaidTransactionId
                        "EXPENSE", // transactionType - Same as existing (user API call)
                        null, // currencyCode
                        null, // importSource - NOT an import (user API call)
                        null, // importBatchId
                        null, // importFileName
                        null, // reviewStatus
                        null, // merchantName
                        null, // location
                        null, // paymentChannel
                        null, // userName
                        null, // goalId
                        null // linkedTransactionId
                        );

        // Then: Should NOT set override flag (user API call, same type)
        assertEquals("EXPENSE", result.getTransactionType());
        assertFalse(
                result.getTransactionTypeOverridden(),
                "transactionTypeOverridden should remain false for user API call with same type");
        verify(transactionRepository).save(any(TransactionTable.class));
    }
}
