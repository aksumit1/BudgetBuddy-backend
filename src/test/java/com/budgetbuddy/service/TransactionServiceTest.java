package com.budgetbuddy.service;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit Tests for TransactionService
 * 
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class TransactionServiceTest {

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private AccountRepository accountRepository;

    @InjectMocks
    private TransactionService transactionService;

    private UserTable testUser;
    private AccountTable testAccount;

    @BeforeEach
    void setUp() {
        testUser = new UserTable();
        testUser.setUserId("user-123");
        testUser.setEmail("test@example.com");
        testUser.setPreferredCurrency("USD");

        testAccount = new AccountTable();
        testAccount.setAccountId("account-123");
        testAccount.setUserId("user-123");
        testAccount.setAccountName("Test Account");
    }

    @Test
    void testGetTransactions_WithValidUser_ReturnsTransactions() {
        // Given
        List<TransactionTable> mockTransactions = Arrays.asList(
                createTransaction("tx-1", "user-123", BigDecimal.valueOf(100.00)),
                createTransaction("tx-2", "user-123", BigDecimal.valueOf(50.00))
        );
        when(transactionRepository.findByUserId("user-123", 0, 50)).thenReturn(mockTransactions);

        // When
        List<TransactionTable> result = transactionService.getTransactions(testUser, 0, 50);

        // Then
        assertNotNull(result);
        assertEquals(2, result.size());
        verify(transactionRepository).findByUserId("user-123", 0, 50);
    }

    @Test
    void testGetTransactions_WithNullUser_ThrowsException() {
        // When/Then
        AppException exception = assertThrows(AppException.class,
                () -> transactionService.getTransactions(null, 0, 50));
        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testGetTransactions_WithInvalidPagination_AdjustsLimits() {
        // Given
        when(transactionRepository.findByUserId(anyString(), anyInt(), anyInt()))
                .thenReturn(Collections.emptyList());

        // When - negative skip (should be adjusted to 0)
        transactionService.getTransactions(testUser, -5, 50);
        verify(transactionRepository).findByUserId("user-123", 0, 50);

        // Reset mock
        reset(transactionRepository);
        when(transactionRepository.findByUserId(anyString(), anyInt(), anyInt()))
                .thenReturn(Collections.emptyList());

        // When - limit too high (should be defaulted to 50, not 100)
        transactionService.getTransactions(testUser, 0, 200);
        verify(transactionRepository).findByUserId("user-123", 0, 50);
    }

    @Test
    void testGetTransactionsInRange_WithValidDates_ReturnsTransactions() {
        // Given
        LocalDate startDate = LocalDate.now().minusDays(7);
        LocalDate endDate = LocalDate.now();
        List<TransactionTable> mockTransactions = Arrays.asList(
                createTransaction("tx-1", "user-123", BigDecimal.valueOf(100.00))
        );
        when(transactionRepository.findByUserIdAndDateRange(anyString(), anyString(), anyString()))
                .thenReturn(mockTransactions);

        // When
        List<TransactionTable> result = transactionService.getTransactionsInRange(testUser, startDate, endDate);

        // Then
        assertNotNull(result);
        assertEquals(1, result.size());
    }

    @Test
    void testGetTransactionsInRange_WithInvalidDateRange_ThrowsException() {
        // Given
        LocalDate startDate = LocalDate.now();
        LocalDate endDate = LocalDate.now().minusDays(7);

        // When/Then
        AppException exception = assertThrows(AppException.class,
                () -> transactionService.getTransactionsInRange(testUser, startDate, endDate));
        assertEquals(ErrorCode.INVALID_DATE_RANGE, exception.getErrorCode());
    }

    @Test
    void testCreateTransaction_WithValidData_CreatesTransaction() {
        // Given
        when(accountRepository.findById("account-123")).thenReturn(Optional.of(testAccount));
        doNothing().when(transactionRepository).save(any(TransactionTable.class));

        // When
        TransactionTable result = transactionService.createTransaction(
                testUser,
                "account-123",
                BigDecimal.valueOf(100.00),
                LocalDate.now(),
                "Test transaction",
                "FOOD"
        );

        // Then
        assertNotNull(result);
        assertNotNull(result.getTransactionId());
        assertEquals("user-123", result.getUserId());
        assertEquals("account-123", result.getAccountId());
        verify(transactionRepository).save(any(TransactionTable.class));
    }

    @Test
    void testCreateTransaction_WithInvalidAccount_ThrowsException() {
        // Given
        when(accountRepository.findById("invalid-account")).thenReturn(Optional.empty());

        // When/Then
        AppException exception = assertThrows(AppException.class,
                () -> transactionService.createTransaction(
                        testUser,
                        "invalid-account",
                        BigDecimal.valueOf(100.00),
                        LocalDate.now(),
                        "Test",
                        "FOOD"
                ));
        assertEquals(ErrorCode.ACCOUNT_NOT_FOUND, exception.getErrorCode());
    }

    @Test
    void testCreateTransaction_WithUnauthorizedAccount_ThrowsException() {
        // Given
        AccountTable otherUserAccount = new AccountTable();
        otherUserAccount.setAccountId("account-456");
        otherUserAccount.setUserId("other-user");
        when(accountRepository.findById("account-456")).thenReturn(Optional.of(otherUserAccount));

        // When/Then
        AppException exception = assertThrows(AppException.class,
                () -> transactionService.createTransaction(
                        testUser,
                        "account-456",
                        BigDecimal.valueOf(100.00),
                        LocalDate.now(),
                        "Test",
                        "FOOD"
                ));
        assertEquals(ErrorCode.UNAUTHORIZED_ACCESS, exception.getErrorCode());
    }

    @Test
    void testCreateTransaction_WithNullAccountId_UsesPseudoAccount() {
        // Given - No accountId provided, should use pseudo account
        AccountTable pseudoAccount = createPseudoAccount(testUser.getUserId());
        when(accountRepository.getOrCreatePseudoAccount(testUser.getUserId())).thenReturn(pseudoAccount);
        doNothing().when(transactionRepository).save(any(TransactionTable.class));

        // When
        TransactionTable result = transactionService.createTransaction(
                testUser,
                null, // No accountId
                BigDecimal.valueOf(100.00),
                LocalDate.now(),
                "Manual transaction",
                "FOOD",
                "RESTAURANTS",
                null,
                null,
                null,
                null
        );

        // Then
        assertNotNull(result);
        assertEquals(pseudoAccount.getAccountId(), result.getAccountId());
        verify(accountRepository).getOrCreatePseudoAccount(testUser.getUserId());
        verify(accountRepository, never()).findById(anyString());
    }

    @Test
    void testCreateTransaction_WithEmptyAccountId_UsesPseudoAccount() {
        // Given - Empty accountId provided, should use pseudo account
        AccountTable pseudoAccount = createPseudoAccount(testUser.getUserId());
        when(accountRepository.getOrCreatePseudoAccount(testUser.getUserId())).thenReturn(pseudoAccount);
        doNothing().when(transactionRepository).save(any(TransactionTable.class));

        // When
        TransactionTable result = transactionService.createTransaction(
                testUser,
                "", // Empty accountId
                BigDecimal.valueOf(100.00),
                LocalDate.now(),
                "Manual transaction",
                "FOOD",
                "RESTAURANTS",
                null,
                null,
                null,
                null
        );

        // Then
        assertNotNull(result);
        assertEquals(pseudoAccount.getAccountId(), result.getAccountId());
        verify(accountRepository).getOrCreatePseudoAccount(testUser.getUserId());
    }

    @Test
    void testCreateTransaction_WithPlaidAccountId_NeverUsesPseudoAccount() {
        // Given - Plaid transaction with plaidAccountId but no accountId
        // Should find account by Plaid ID, NOT use pseudo account
        AccountTable plaidAccount = new AccountTable();
        plaidAccount.setAccountId("plaid-account-123");
        plaidAccount.setUserId(testUser.getUserId());
        plaidAccount.setPlaidAccountId("plaid-acc-123");
        
        when(accountRepository.findById(null)).thenReturn(Optional.empty());
        when(accountRepository.findByPlaidAccountId("plaid-acc-123")).thenReturn(Optional.of(plaidAccount));
        doNothing().when(transactionRepository).save(any(TransactionTable.class));

        // When - Plaid transaction without accountId
        TransactionTable result = transactionService.createTransaction(
                testUser,
                null, // No accountId
                BigDecimal.valueOf(100.00),
                LocalDate.now(),
                "Plaid transaction",
                "FOOD",
                "RESTAURANTS",
                null,
                null,
                "plaid-acc-123", // Plaid account ID
                "plaid-tx-123"   // Plaid transaction ID
        );

        // Then - Should use Plaid account, NOT pseudo account
        assertNotNull(result);
        assertEquals(plaidAccount.getAccountId(), result.getAccountId());
        verify(accountRepository).findByPlaidAccountId("plaid-acc-123");
        verify(accountRepository, never()).getOrCreatePseudoAccount(anyString());
    }

    @Test
    void testCreateTransaction_WithPlaidAccountIdAndAccountId_UsesAccountId() {
        // Given - Plaid transaction with both accountId and plaidAccountId
        AccountTable account = new AccountTable();
        account.setAccountId("account-123");
        account.setUserId(testUser.getUserId());
        account.setPlaidAccountId("plaid-acc-123");
        
        when(accountRepository.findById("account-123")).thenReturn(Optional.of(account));
        doNothing().when(transactionRepository).save(any(TransactionTable.class));

        // When
        TransactionTable result = transactionService.createTransaction(
                testUser,
                "account-123", // Account ID provided
                BigDecimal.valueOf(100.00),
                LocalDate.now(),
                "Plaid transaction",
                "FOOD",
                "RESTAURANTS",
                null,
                null,
                "plaid-acc-123", // Plaid account ID
                "plaid-tx-123"
        );

        // Then - Should use provided accountId
        assertNotNull(result);
        assertEquals("account-123", result.getAccountId());
        verify(accountRepository).findById("account-123");
        verify(accountRepository, never()).getOrCreatePseudoAccount(anyString());
    }

    @Test
    void testCreateTransaction_WithPlaidAccountIdButAccountNotFound_ThrowsException() {
        // Given - Plaid transaction but account not found
        when(accountRepository.findById(null)).thenReturn(Optional.empty());
        when(accountRepository.findByPlaidAccountId("plaid-acc-123")).thenReturn(Optional.empty());

        // When/Then - Should throw exception, NOT use pseudo account
        AppException exception = assertThrows(AppException.class,
                () -> transactionService.createTransaction(
                        testUser,
                        null,
                        BigDecimal.valueOf(100.00),
                        LocalDate.now(),
                        "Plaid transaction",
                        "FOOD",
                        "RESTAURANTS",
                        null,
                        null,
                        "plaid-acc-123",
                        "plaid-tx-123"
                ));
        assertEquals(ErrorCode.ACCOUNT_NOT_FOUND, exception.getErrorCode());
        verify(accountRepository, never()).getOrCreatePseudoAccount(anyString());
    }

    @Test
    void testCreateTransaction_WithAccountId_UsesProvidedAccount() {
        // Given - Manual transaction with accountId
        when(accountRepository.findById("account-123")).thenReturn(Optional.of(testAccount));
        doNothing().when(transactionRepository).save(any(TransactionTable.class));

        // When
        TransactionTable result = transactionService.createTransaction(
                testUser,
                "account-123", // Account ID provided
                BigDecimal.valueOf(100.00),
                LocalDate.now(),
                "Manual transaction",
                "FOOD",
                "RESTAURANTS",
                null,
                null,
                null,
                null
        );

        // Then - Should use provided account, NOT pseudo account
        assertNotNull(result);
        assertEquals("account-123", result.getAccountId());
        verify(accountRepository).findById("account-123");
        verify(accountRepository, never()).getOrCreatePseudoAccount(anyString());
    }

    // Helper method
    private AccountTable createPseudoAccount(String userId) {
        AccountTable account = new AccountTable();
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
    void testDeleteTransaction_WithValidTransaction_DeletesTransaction() {
        // Given
        TransactionTable transaction = createTransaction("tx-1", "user-123", BigDecimal.valueOf(100.00));
        when(transactionRepository.findById("tx-1")).thenReturn(Optional.of(transaction));
        doNothing().when(transactionRepository).delete("tx-1");

        // When
        transactionService.deleteTransaction(testUser, "tx-1");

        // Then
        verify(transactionRepository).delete("tx-1");
    }

    @Test
    void testDeleteTransaction_WithUnauthorizedTransaction_ThrowsException() {
        // Given
        TransactionTable otherUserTransaction = createTransaction("tx-1", "other-user", BigDecimal.valueOf(100.00));
        when(transactionRepository.findById("tx-1")).thenReturn(Optional.of(otherUserTransaction));

        // When/Then
        AppException exception = assertThrows(AppException.class,
                () -> transactionService.deleteTransaction(testUser, "tx-1"));
        assertEquals(ErrorCode.UNAUTHORIZED_ACCESS, exception.getErrorCode());
    }

    @Test
    void testGetTotalSpending_WithValidRange_ReturnsTotal() {
        // Given
        LocalDate startDate = LocalDate.now().minusDays(7);
        LocalDate endDate = LocalDate.now();
        List<TransactionTable> transactions = Arrays.asList(
                createTransaction("tx-1", "user-123", BigDecimal.valueOf(100.00)),
                createTransaction("tx-2", "user-123", BigDecimal.valueOf(50.00))
        );
        when(transactionRepository.findByUserIdAndDateRange(anyString(), anyString(), anyString()))
                .thenReturn(transactions);

        // When
        BigDecimal total = transactionService.getTotalSpending(testUser, startDate, endDate);

        // Then
        assertEquals(BigDecimal.valueOf(150.00), total);
    }

    @Test
    void testGetTotalSpending_WithNullAmounts_HandlesGracefully() {
        // Given
        LocalDate startDate = LocalDate.now().minusDays(7);
        LocalDate endDate = LocalDate.now();
        TransactionTable tx1 = createTransaction("tx-1", "user-123", BigDecimal.valueOf(100.00));
        TransactionTable tx2 = createTransaction("tx-2", "user-123", null);
        when(transactionRepository.findByUserIdAndDateRange(anyString(), anyString(), anyString()))
                .thenReturn(Arrays.asList(tx1, tx2));

        // When
        BigDecimal total = transactionService.getTotalSpending(testUser, startDate, endDate);

        // Then
        assertEquals(BigDecimal.valueOf(100.00), total);
    }

    // Helper methods
    private TransactionTable createTransaction(final String id, final String userId, final BigDecimal amount) {
        TransactionTable transaction = new TransactionTable();
        transaction.setTransactionId(id);
        transaction.setUserId(userId);
        transaction.setAmount(amount);
        transaction.setTransactionDate(LocalDate.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE));
        transaction.setCategoryPrimary("dining");
        transaction.setCategoryDetailed("dining");
        return transaction;
    }
}

