package com.budgetbuddy.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.nullable;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.plaid.PlaidService;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.service.plaid.PlaidSyncOrchestrator;
import com.plaid.client.model.AccountBalance;
import com.plaid.client.model.AccountBase;
import com.plaid.client.model.AccountsGetResponse;
import com.plaid.client.model.Transaction;
import com.plaid.client.model.TransactionsGetResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit Tests for PlaidSyncService Tests account and transaction synchronization logic */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
@SuppressWarnings("PMD.LawOfDemeter")
@ExtendWith(MockitoExtension.class)
class PlaidSyncServiceTest {

    private static final String PLAID_ACCOUNT_1 = "plaid-account-1";

    @Mock private PlaidService plaidService;

    @Mock private AccountRepository accountRepository;

    @Mock private TransactionRepository transactionRepository;

    @Mock private com.budgetbuddy.service.PlaidCategoryMapper categoryMapper;

    @Mock private com.budgetbuddy.service.plaid.PlaidDataExtractor dataExtractor;

    @Mock private PlaidSyncOrchestrator syncOrchestrator;

    @InjectMocks private PlaidSyncService plaidSyncService;

    private UserTable testUser;
    private String testAccessToken;
    private String testUserId;

    @BeforeEach
    void setUp() {
        testUserId = UUID.randomUUID().toString();
        testUser = new UserTable();
        testUser.setUserId(testUserId);
        testUser.setEmail("test@example.com");
        testAccessToken = "test-access-token";

        // Create real services with mocked dependencies so the actual sync logic runs
        final com.budgetbuddy.service.plaid.PlaidAccountSyncService accountSyncService =
                new com.budgetbuddy.service.plaid.PlaidAccountSyncService(
                        plaidService,
                        accountRepository,
                        categoryMapper,
                        dataExtractor,
                        org.mockito.Mockito.mock(
                                com.budgetbuddy.service.correctness.BalanceReconciliationService
                                        .class));
        final com.budgetbuddy.service.plaid.PlaidTransactionSyncService transactionSyncService =
                new com.budgetbuddy.service.plaid.PlaidTransactionSyncService(
                        plaidService, accountRepository, transactionRepository, dataExtractor);
        final com.budgetbuddy.service.plaid.PlaidSyncOrchestrator realOrchestrator =
                new com.budgetbuddy.service.plaid.PlaidSyncOrchestrator(
                        accountSyncService, transactionSyncService);

        // Use doAnswer to call the real orchestrator methods so repository calls are made
        // Use nullable() for itemId since it can be null
        // Use lenient stubbing to avoid issues with tests that throw exceptions early
        // Use lenient stubbing to avoid unnecessary stubbing warnings for tests that throw
        // exceptions early
        lenient()
                .doAnswer(
                        invocation -> {
                            realOrchestrator.syncAccountsOnly(
                                    invocation.getArgument(0),
                                    invocation.getArgument(1),
                                    invocation.getArgument(2));
                            return null;
                        })
                .when(syncOrchestrator)
                .syncAccountsOnly(any(UserTable.class), anyString(), nullable(String.class));

        lenient()
                .doAnswer(
                        invocation -> {
                            realOrchestrator.syncTransactionsOnly(
                                    invocation.getArgument(0), invocation.getArgument(1));
                            return null;
                        })
                .when(syncOrchestrator)
                .syncTransactionsOnly(any(UserTable.class), anyString());
    }

    @Test
    void testSyncAccountsWithValidDataCreatesNewAccounts() {
        // Given
        final AccountsGetResponse accountsResponse = createMockAccountsResponse();
        when(plaidService.getAccounts(testAccessToken)).thenReturn(accountsResponse);
        // Mock dataExtractor to extract account IDs and update accounts
        when(dataExtractor.extractAccountId(any()))
                .thenAnswer(
                        invocation -> {
                            final Object account = invocation.getArgument(0);
                            if (account instanceof AccountBase) {
                                return ((AccountBase) account).getAccountId();
                            }
                            return null;
                        });
        // Mock updateAccountFromPlaid to actually set updatedAt
        doAnswer(
                        invocation -> {
                            final AccountTable account = invocation.getArgument(0);
                            account.setUpdatedAt(java.time.Instant.now());
                            return null;
                        })
                .when(dataExtractor)
                .updateAccountFromPlaid(any(AccountTable.class), any());
        // OPTIMIZATION: Service now loads all accounts once via findByUserId instead of per-account
        // queries
        when(accountRepository.findByUserId(testUserId)).thenReturn(Collections.emptyList());
        when(accountRepository.saveIfNotExists(any(AccountTable.class))).thenReturn(true);

        // When
        assertDoesNotThrow(() -> plaidSyncService.syncAccounts(testUser, testAccessToken, null));

        // Then
        verify(plaidService, times(1)).getAccounts(testAccessToken);
        verify(accountRepository, atLeastOnce()).saveIfNotExists(any(AccountTable.class));
    }

    @Test
    void testSyncAccountsWithExistingAccountUpdatesAccount() {
        // Given
        final AccountsGetResponse accountsResponse = createMockAccountsResponse();
        final AccountTable existingAccount = new AccountTable();
        existingAccount.setAccountId(UUID.randomUUID().toString());
        existingAccount.setUserId(testUserId);
        existingAccount.setPlaidAccountId(PLAID_ACCOUNT_1);
        existingAccount.setActive(true);

        when(plaidService.getAccounts(testAccessToken)).thenReturn(accountsResponse);
        // Mock dataExtractor to extract account IDs and update accounts
        when(dataExtractor.extractAccountId(any()))
                .thenAnswer(
                        invocation -> {
                            final Object account = invocation.getArgument(0);
                            if (account instanceof AccountBase) {
                                return ((AccountBase) account).getAccountId();
                            }
                            return null;
                        });
        // Mock updateAccountFromPlaid to actually set updatedAt
        doAnswer(
                        invocation -> {
                            final AccountTable account = invocation.getArgument(0);
                            account.setUpdatedAt(java.time.Instant.now());
                            return null;
                        })
                .when(dataExtractor)
                .updateAccountFromPlaid(any(AccountTable.class), any());
        // OPTIMIZATION: Service now loads all accounts once via findByUserId instead of per-account
        // queries
        when(accountRepository.findByUserId(testUserId))
                .thenReturn(Collections.singletonList(existingAccount));
        when(accountRepository.saveWithLock(any(AccountTable.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // When
        assertDoesNotThrow(() -> plaidSyncService.syncAccounts(testUser, testAccessToken, null));

        // Then — existing accounts persist via saveWithLock (optimistic concurrency).
        verify(accountRepository, times(1)).saveWithLock(existingAccount);
        final ArgumentCaptor<AccountTable> accountCaptor =
                ArgumentCaptor.forClass(AccountTable.class);
        verify(accountRepository).saveWithLock(accountCaptor.capture());
        final AccountTable savedAccount = accountCaptor.getValue();
        assertTrue(savedAccount.getActive(), "Account should be marked as active");
        assertNotNull(savedAccount.getUpdatedAt(), "UpdatedAt should be set");
    }

    @Test
    void testSyncAccountsSetsActiveToTrue() {
        // Given
        final AccountsGetResponse accountsResponse = createMockAccountsResponse();
        when(plaidService.getAccounts(testAccessToken)).thenReturn(accountsResponse);
        // Mock dataExtractor to extract account IDs and update accounts
        when(dataExtractor.extractAccountId(any()))
                .thenAnswer(
                        invocation -> {
                            final Object account = invocation.getArgument(0);
                            if (account instanceof AccountBase) {
                                return ((AccountBase) account).getAccountId();
                            }
                            return null;
                        });
        // Mock updateAccountFromPlaid to actually set updatedAt
        doAnswer(
                        invocation -> {
                            final AccountTable account = invocation.getArgument(0);
                            account.setUpdatedAt(java.time.Instant.now());
                            return null;
                        })
                .when(dataExtractor)
                .updateAccountFromPlaid(any(AccountTable.class), any());
        // OPTIMIZATION: Service now loads all accounts once via findByUserId instead of per-account
        // queries
        when(accountRepository.findByUserId(testUserId)).thenReturn(Collections.emptyList());
        when(accountRepository.saveIfNotExists(any(AccountTable.class))).thenReturn(true);

        // When
        plaidSyncService.syncAccounts(testUser, testAccessToken, null);

        // Then
        final ArgumentCaptor<AccountTable> accountCaptor =
                ArgumentCaptor.forClass(AccountTable.class);
        verify(accountRepository, atLeastOnce()).saveIfNotExists(accountCaptor.capture());
        final AccountTable savedAccount = accountCaptor.getValue();
        assertTrue(savedAccount.getActive(), "New accounts should have active = true");
        assertNotNull(savedAccount.getCreatedAt(), "CreatedAt should be set");
    }

    @Test
    void testSyncAccountsWithNullUserThrowsException() {
        // When/Then - Exception thrown before orchestrator is called, so no stubbing needed
        final AppException exception =
                assertThrows(
                        AppException.class,
                        () -> {
                            plaidSyncService.syncAccounts(null, testAccessToken, null);
                        });
        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testSyncAccountsWithNullAccessTokenThrowsException() {
        // When/Then - Exception thrown before orchestrator is called, so no stubbing needed
        final AppException exception =
                assertThrows(
                        AppException.class,
                        () -> {
                            plaidSyncService.syncAccounts(testUser, null, null);
                        });
        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testSyncAccountsWithEmptyAccessTokenThrowsException() {
        // When/Then - Exception thrown before orchestrator is called, so no stubbing needed
        final AppException exception =
                assertThrows(
                        AppException.class,
                        () -> {
                            plaidSyncService.syncAccounts(testUser, "", null);
                        });
        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testSyncAccountsWithNoAccountsFromPlaidDoesNotThrow() {
        // Given
        final AccountsGetResponse accountsResponse = new AccountsGetResponse();
        accountsResponse.setAccounts(Collections.emptyList());
        when(plaidService.getAccounts(testAccessToken)).thenReturn(accountsResponse);

        // When/Then
        assertDoesNotThrow(() -> plaidSyncService.syncAccounts(testUser, testAccessToken, null));
        verify(accountRepository, never()).save(any());
        verify(accountRepository, never()).saveIfNotExists(any());
    }

    @Test
    void testSyncTransactionsWithValidDataCreatesTransactions() {
        // Given
        // First, create a test account (required for per-account sync)
        final AccountTable testAccount = new AccountTable();
        testAccount.setAccountId(UUID.randomUUID().toString());
        testAccount.setUserId(testUser.getUserId());
        testAccount.setPlaidAccountId(PLAID_ACCOUNT_1);
        testAccount.setLastSyncedAt(null); // First sync - ensure sync isn't skipped
        testAccount.setActive(true);

        final TransactionsGetResponse transactionsResponse = createMockTransactionsResponse();
        when(accountRepository.findByUserId(testUser.getUserId()))
                .thenReturn(Collections.singletonList(testAccount));
        when(plaidService.getTransactions(eq(testAccessToken), anyString(), anyString()))
                .thenReturn(transactionsResponse);
        // Mock dataExtractor to return account ID for transaction grouping and transaction ID
        when(dataExtractor.extractAccountIdFromTransaction(any())).thenReturn(PLAID_ACCOUNT_1);
        when(dataExtractor.extractTransactionId(any()))
                .thenAnswer(
                        invocation -> {
                            final Object transaction = invocation.getArgument(0);
                            if (transaction instanceof com.plaid.client.model.Transaction) {
                                return ((com.plaid.client.model.Transaction) transaction)
                                        .getTransactionId();
                            }
                            return null;
                        });
        when(transactionRepository.findByPlaidTransactionId(anyString()))
                .thenReturn(Optional.empty());
        when(transactionRepository.saveIfPlaidTransactionNotExists(any(TransactionTable.class)))
                .thenReturn(true);

        // When
        assertDoesNotThrow(() -> plaidSyncService.syncTransactions(testUser, testAccessToken));

        // Then
        verify(plaidService, atLeastOnce())
                .getTransactions(eq(testAccessToken), anyString(), anyString());
        verify(transactionRepository, atLeastOnce())
                .saveIfPlaidTransactionNotExists(any(TransactionTable.class));
    }

    @Test
    void testSyncTransactionsWithNullUserThrowsException() {
        // When/Then
        final AppException exception =
                assertThrows(
                        AppException.class,
                        () -> {
                            plaidSyncService.syncTransactions(null, testAccessToken);
                        });
        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testSyncTransactionsWithNullAccessTokenThrowsException() {
        // When/Then
        final AppException exception =
                assertThrows(
                        AppException.class,
                        () -> {
                            plaidSyncService.syncTransactions(testUser, null);
                        });
        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    // Helper methods to create mock Plaid responses
    private AccountsGetResponse createMockAccountsResponse() {
        final AccountsGetResponse response = new AccountsGetResponse();
        final List<AccountBase> accounts = new ArrayList<>();

        final AccountBase account1 = new AccountBase();
        account1.setAccountId(PLAID_ACCOUNT_1);
        account1.setName("Test Account 1");
        account1.setOfficialName("Official Test Account 1");
        // Note: Type and Subtype are enums in Plaid SDK - using string representation for testing
        // In real implementation, these would be set via Plaid SDK methods

        final AccountBalance balance1 = new AccountBalance();
        balance1.setAvailable(1000.0);
        balance1.setCurrent(1000.0);
        balance1.setIsoCurrencyCode("USD");
        account1.setBalances(balance1);
        accounts.add(account1);

        final AccountBase account2 = new AccountBase();
        account2.setAccountId("plaid-account-2");
        account2.setName("Test Account 2");
        // Note: Type and Subtype are enums in Plaid SDK - using string representation for testing

        final AccountBalance balance2 = new AccountBalance();
        balance2.setAvailable(5000.0);
        balance2.setCurrent(5000.0);
        balance2.setIsoCurrencyCode("USD");
        account2.setBalances(balance2);
        accounts.add(account2);

        response.setAccounts(accounts);
        return response;
    }

    private TransactionsGetResponse createMockTransactionsResponse() {
        final TransactionsGetResponse response = new TransactionsGetResponse();
        final List<Transaction> transactions = new ArrayList<>();

        final Transaction transaction1 = new Transaction();
        transaction1.setTransactionId("plaid-transaction-1");
        transaction1.setAccountId(PLAID_ACCOUNT_1); // CRITICAL: Set accountId for grouping
        transaction1.setAmount(100.0);
        transaction1.setDate(java.time.LocalDate.now());
        transaction1.setName("Test Transaction 1");
        transaction1.setMerchantName("Test Merchant");
        transactions.add(transaction1);

        final Transaction transaction2 = new Transaction();
        transaction2.setTransactionId("plaid-transaction-2");
        transaction2.setAccountId(PLAID_ACCOUNT_1); // CRITICAL: Set accountId for grouping
        transaction2.setAmount(-50.0);
        transaction2.setDate(java.time.LocalDate.now().minusDays(1));
        transaction2.setName("Test Transaction 2");
        transactions.add(transaction2);

        response.setTransactions(transactions);
        response.setTotalTransactions(transactions.size());
        return response;
    }
}
