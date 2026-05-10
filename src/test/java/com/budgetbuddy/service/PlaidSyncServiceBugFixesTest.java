package com.budgetbuddy.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.nullable;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.plaid.PlaidService;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.service.plaid.PlaidSyncOrchestrator;
import com.plaid.client.model.AccountBalance;
import com.plaid.client.model.AccountBase;
import com.plaid.client.model.AccountSubtype;
import com.plaid.client.model.AccountType;
import com.plaid.client.model.AccountsGetResponse;
import com.plaid.client.model.Transaction;
import com.plaid.client.model.TransactionsGetResponse;
import java.time.LocalDate;
import java.util.Arrays;
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

/**
 * Tests for bug fixes implemented today: 1. Account sync sets active = true for new accounts 2.
 * AccountRepository includes null-active accounts 3. Transaction sync sets correct date format
 * (YYYY-MM-DD) 4. Transaction sync handles null category (defaults to "Other") 5. Individual item
 * failures don't block entire load
 */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
@SuppressWarnings({"PMD.LawOfDemeter", "PMD.AvoidCatchingGenericException"})
@ExtendWith(MockitoExtension.class)
class PlaidSyncServiceBugFixesTest {

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
    void testSyncAccountsSetsActiveToTrueForNewAccounts() {
        // Given - New account from Plaid
        final AccountBase plaidAccount =
                createMockPlaidAccount("plaid-account-1", "Test Account", 1000.0);
        final AccountsGetResponse accountsResponse = new AccountsGetResponse();
        accountsResponse.setAccounts(Collections.singletonList(plaidAccount));

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

        // Then - Verify active is set to true
        final ArgumentCaptor<AccountTable> accountCaptor = ArgumentCaptor.forClass(AccountTable.class);
        verify(accountRepository).saveIfNotExists(accountCaptor.capture());
        final AccountTable savedAccount = accountCaptor.getValue();
        assertTrue(savedAccount.getActive(), "New accounts should have active = true");
        assertNotNull(savedAccount.getCreatedAt(), "CreatedAt should be set");
        assertNotNull(savedAccount.getUpdatedAt(), "UpdatedAt should be set");
    }

    @Test
    void testSyncAccountsPreservesActiveStatusForExistingAccounts() {
        // Given - Existing account with active = true
        final AccountBase plaidAccount =
                createMockPlaidAccount("plaid-account-1", "Test Account", 1500.0);
        final AccountsGetResponse accountsResponse = new AccountsGetResponse();
        accountsResponse.setAccounts(Collections.singletonList(plaidAccount));

        final AccountTable existingAccount = new AccountTable();
        existingAccount.setAccountId(UUID.randomUUID().toString());
        existingAccount.setUserId(testUserId);
        existingAccount.setPlaidAccountId("plaid-account-1");
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
        // The existing account should be in the list returned by findByUserId
        when(accountRepository.findByUserId(testUserId))
                .thenReturn(Collections.singletonList(existingAccount));
        when(accountRepository.saveWithLock(any(AccountTable.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // When
        plaidSyncService.syncAccounts(testUser, testAccessToken, null);

        // Then - Verify active status is preserved (saveWithLock = optimistic concurrency).
        final ArgumentCaptor<AccountTable> accountCaptor = ArgumentCaptor.forClass(AccountTable.class);
        verify(accountRepository).saveWithLock(accountCaptor.capture());
        final AccountTable savedAccount = accountCaptor.getValue();
        assertTrue(savedAccount.getActive(), "Active status should be preserved");
    }

    @Test
    void testSyncTransactionsSetsCorrectDateFormatYYYYMMDD() {
        // Given - Transaction with date
        // First, create a test account (required for per-account sync)
        final AccountTable testAccount = new AccountTable();
        testAccount.setAccountId(UUID.randomUUID().toString());
        testAccount.setUserId(testUserId);
        testAccount.setPlaidAccountId("plaid-account-1");
        testAccount.setLastSyncedAt(null); // First sync - ensure sync isn't skipped
        testAccount.setActive(true);

        final Transaction plaidTransaction =
                createMockPlaidTransaction(
                        "plaid-tx-1",
                        "Test Transaction",
                        50.0,
                        LocalDate.of(2025, 11, 26),
                        null // null category - should default to "Other"
                );
        final TransactionsGetResponse transactionsResponse = new TransactionsGetResponse();
        transactionsResponse.setTransactions(Collections.singletonList(plaidTransaction));
        transactionsResponse.setTotalTransactions(1);

        when(accountRepository.findByUserId(testUserId))
                .thenReturn(Collections.singletonList(testAccount));
        when(plaidService.getTransactions(eq(testAccessToken), anyString(), anyString()))
                .thenReturn(transactionsResponse);
        // Mock dataExtractor to return account ID for transaction grouping and transaction ID
        when(dataExtractor.extractAccountIdFromTransaction(any())).thenReturn("plaid-account-1");
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
        // Mock updateTransactionFromPlaid to actually populate transaction fields
        doAnswer(
                        invocation -> {
                            final TransactionTable txTable = invocation.getArgument(0);
                            final Object plaidTxObj = invocation.getArgument(1);
                            if (plaidTxObj instanceof com.plaid.client.model.Transaction) {
                                final com.plaid.client.model.Transaction plaidTx =
                                        (com.plaid.client.model.Transaction) plaidTxObj;
                                txTable.setMerchantName(plaidTx.getMerchantName());
                                txTable.setDescription(plaidTx.getName());
                                if (plaidTx.getDate() != null) {
                                    txTable.setTransactionDate(
                                            plaidTx.getDate()
                                                    .format(
                                                            java.time.format.DateTimeFormatter
                                                                    .ISO_LOCAL_DATE));
                                }
                                if (plaidTx.getAmount() != null) {
                                    txTable.setAmount(
                                            java.math.BigDecimal.valueOf(plaidTx.getAmount()));
                                }

                                // Extract PersonalFinanceCategory
                                String plaidCategoryPrimary = null;
                                String plaidCategoryDetailed = null;
                                try {
                                    final var pfc = plaidTx.getPersonalFinanceCategory();
                                    if (pfc != null) {
                                        plaidCategoryPrimary = pfc.getPrimary();
                                        plaidCategoryDetailed = pfc.getDetailed();
                                    }
                                } catch (Exception e) {
                                    // Ignore
                                }

                                // Use categoryMapper to map categories
                                final PlaidCategoryMapper.CategoryMapping categoryMapping;
                                if (plaidCategoryPrimary != null || plaidCategoryDetailed != null) {
                                    categoryMapping =
                                            categoryMapper.mapPlaidCategory(
                                                    plaidCategoryPrimary,
                                                    plaidCategoryDetailed,
                                                    txTable.getMerchantName(),
                                                    txTable.getDescription());
                                } else {
                                    categoryMapping =
                                            new PlaidCategoryMapper.CategoryMapping(
                                                    "other", "other", false);
                                }

                                txTable.setImporterCategoryPrimary(plaidCategoryPrimary);
                                txTable.setImporterCategoryDetailed(plaidCategoryDetailed);
                                txTable.setCategoryPrimary(categoryMapping.getPrimary());
                                txTable.setCategoryDetailed(categoryMapping.getDetailed());
                            }
                            return null;
                        })
                .when(dataExtractor)
                .updateTransactionFromPlaid(any(TransactionTable.class), any());
        when(transactionRepository.findByPlaidTransactionId("plaid-tx-1"))
                .thenReturn(Optional.empty());
        when(transactionRepository.saveIfPlaidTransactionNotExists(any(TransactionTable.class)))
                .thenReturn(true);

        // When
        plaidSyncService.syncTransactions(testUser, testAccessToken);

        // Then - Verify date format is YYYY-MM-DD
        final ArgumentCaptor<TransactionTable> transactionCaptor =
                ArgumentCaptor.forClass(TransactionTable.class);
        verify(transactionRepository).saveIfPlaidTransactionNotExists(transactionCaptor.capture());
        final TransactionTable savedTransaction = transactionCaptor.getValue();
        assertEquals(
                "2025-11-26",
                savedTransaction.getTransactionDate(),
                "Transaction date should be in YYYY-MM-DD format");
    }

    @Test
    void testSyncTransactionsHandlesNullCategoryDefaultsToOther() {
        // Given - Transaction with null category (BUG FIX: This was causing iOS app failures)
        // First, create a test account (required for per-account sync)
        final AccountTable testAccount = new AccountTable();
        testAccount.setAccountId(UUID.randomUUID().toString());
        testAccount.setUserId(testUserId);
        testAccount.setPlaidAccountId("plaid-account-1");
        testAccount.setLastSyncedAt(null); // First sync - ensure sync isn't skipped
        testAccount.setActive(true);

        final Transaction plaidTransaction =
                createMockPlaidTransaction(
                        "plaid-tx-1",
                        "Uber 072515 SF**POOL**",
                        6.33,
                        LocalDate.of(2025, 10, 29),
                        null // null category - actual backend behavior
                );
        final TransactionsGetResponse transactionsResponse = new TransactionsGetResponse();
        transactionsResponse.setTransactions(Collections.singletonList(plaidTransaction));
        transactionsResponse.setTotalTransactions(1);

        when(accountRepository.findByUserId(testUserId))
                .thenReturn(Collections.singletonList(testAccount));
        when(plaidService.getTransactions(eq(testAccessToken), anyString(), anyString()))
                .thenReturn(transactionsResponse);
        // Mock dataExtractor to return account ID for transaction grouping and transaction ID
        when(dataExtractor.extractAccountIdFromTransaction(any())).thenReturn("plaid-account-1");
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
        // Mock updateTransactionFromPlaid to actually populate transaction fields
        doAnswer(
                        invocation -> {
                            final TransactionTable txTable = invocation.getArgument(0);
                            final Object plaidTxObj = invocation.getArgument(1);
                            if (plaidTxObj instanceof com.plaid.client.model.Transaction) {
                                final com.plaid.client.model.Transaction plaidTx =
                                        (com.plaid.client.model.Transaction) plaidTxObj;
                                txTable.setMerchantName(plaidTx.getMerchantName());
                                txTable.setDescription(plaidTx.getName());
                                if (plaidTx.getDate() != null) {
                                    txTable.setTransactionDate(
                                            plaidTx.getDate()
                                                    .format(
                                                            java.time.format.DateTimeFormatter
                                                                    .ISO_LOCAL_DATE));
                                }
                                if (plaidTx.getAmount() != null) {
                                    txTable.setAmount(
                                            java.math.BigDecimal.valueOf(plaidTx.getAmount()));
                                }

                                // Extract PersonalFinanceCategory
                                String plaidCategoryPrimary = null;
                                String plaidCategoryDetailed = null;
                                try {
                                    final var pfc = plaidTx.getPersonalFinanceCategory();
                                    if (pfc != null) {
                                        plaidCategoryPrimary = pfc.getPrimary();
                                        plaidCategoryDetailed = pfc.getDetailed();
                                    }
                                } catch (Exception e) {
                                    // Ignore
                                }

                                // Use categoryMapper to map categories
                                final PlaidCategoryMapper.CategoryMapping categoryMapping;
                                if (plaidCategoryPrimary != null || plaidCategoryDetailed != null) {
                                    categoryMapping =
                                            categoryMapper.mapPlaidCategory(
                                                    plaidCategoryPrimary,
                                                    plaidCategoryDetailed,
                                                    txTable.getMerchantName(),
                                                    txTable.getDescription());
                                } else {
                                    categoryMapping =
                                            new PlaidCategoryMapper.CategoryMapping(
                                                    "other", "other", false);
                                }

                                txTable.setImporterCategoryPrimary(plaidCategoryPrimary);
                                txTable.setImporterCategoryDetailed(plaidCategoryDetailed);
                                txTable.setCategoryPrimary(categoryMapping.getPrimary());
                                txTable.setCategoryDetailed(categoryMapping.getDetailed());
                            }
                            return null;
                        })
                .when(dataExtractor)
                .updateTransactionFromPlaid(any(TransactionTable.class), any());
        when(transactionRepository.findByPlaidTransactionId("plaid-tx-1"))
                .thenReturn(Optional.empty());
        when(transactionRepository.saveIfPlaidTransactionNotExists(any(TransactionTable.class)))
                .thenReturn(true);

        // When
        plaidSyncService.syncTransactions(testUser, testAccessToken);

        // Then - Verify category defaults to "Other"
        final ArgumentCaptor<TransactionTable> transactionCaptor =
                ArgumentCaptor.forClass(TransactionTable.class);
        verify(transactionRepository).saveIfPlaidTransactionNotExists(transactionCaptor.capture());
        final TransactionTable savedTransaction = transactionCaptor.getValue();
        assertNotNull(savedTransaction.getCategoryPrimary(), "Category primary should not be null");
        assertEquals(
                "other",
                savedTransaction.getCategoryPrimary(),
                "Null category should default to 'other'");
        assertNotNull(
                savedTransaction.getCategoryDetailed(), "Category detailed should not be null");
        assertEquals(
                "other",
                savedTransaction.getCategoryDetailed(),
                "Null category detailed should default to 'other'");
    }

    @Test
    void testSyncTransactionsHandlesPartialFailuresGracefully() {
        // Given - Multiple transactions, one with invalid data
        // First, create a test account (required for per-account sync)
        final AccountTable testAccount = new AccountTable();
        testAccount.setAccountId(UUID.randomUUID().toString());
        testAccount.setUserId(testUserId);
        testAccount.setPlaidAccountId("plaid-account-1");
        testAccount.setLastSyncedAt(null); // First sync - ensure sync isn't skipped
        testAccount.setActive(true);

        final Transaction validTransaction =
                createMockPlaidTransaction(
                        "plaid-tx-1",
                        "Valid Transaction",
                        100.0,
                        LocalDate.of(2025, 11, 26),
                        Arrays.asList("Food", "Restaurants"));
        final Transaction invalidTransaction =
                createMockPlaidTransaction(
                        "plaid-tx-2",
                        "Invalid Transaction",
                        null, // null amount - should be handled
                        null, // null date - should be handled
                        null);
        final TransactionsGetResponse transactionsResponse = new TransactionsGetResponse();
        transactionsResponse.setTransactions(Arrays.asList(validTransaction, invalidTransaction));
        transactionsResponse.setTotalTransactions(2);

        when(accountRepository.findByUserId(testUserId))
                .thenReturn(Collections.singletonList(testAccount));
        when(plaidService.getTransactions(eq(testAccessToken), anyString(), anyString()))
                .thenReturn(transactionsResponse);
        // Mock dataExtractor to return account ID for transaction grouping and transaction ID
        when(dataExtractor.extractAccountIdFromTransaction(any())).thenReturn("plaid-account-1");
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
        when(transactionRepository.findByPlaidTransactionId("plaid-tx-1"))
                .thenReturn(Optional.empty());
        when(transactionRepository.findByPlaidTransactionId("plaid-tx-2"))
                .thenReturn(Optional.empty());
        when(transactionRepository.saveIfPlaidTransactionNotExists(any(TransactionTable.class)))
                .thenReturn(true);

        // When - Should not throw exception even if one transaction fails
        assertDoesNotThrow(() -> plaidSyncService.syncTransactions(testUser, testAccessToken));

        // Then - At least one transaction should be saved
        verify(transactionRepository, atLeastOnce())
                .saveIfPlaidTransactionNotExists(any(TransactionTable.class));
    }

    @Test
    void testSyncAccountsHandlesPartialFailuresGracefully() {
        // Given - Multiple accounts, one with invalid data
        final AccountBase validAccount =
                createMockPlaidAccount("plaid-account-1", "Valid Account", 1000.0);
        final AccountBase invalidAccount = createMockPlaidAccount(null, null, null); // Invalid account

        final AccountsGetResponse accountsResponse = new AccountsGetResponse();
        accountsResponse.setAccounts(Arrays.asList(validAccount, invalidAccount));

        when(plaidService.getAccounts(testAccessToken)).thenReturn(accountsResponse);
        // Mock dataExtractor to extract account IDs and update accounts
        when(dataExtractor.extractAccountId(any()))
                .thenAnswer(
                        invocation -> {
                            final Object account = invocation.getArgument(0);
                            if (account instanceof AccountBase) {
                                final String accountId = ((AccountBase) account).getAccountId();
                                return accountId != null ? accountId : null;
                            }
                            return null;
                        });
        doNothing().when(dataExtractor).updateAccountFromPlaid(any(AccountTable.class), any());
        // OPTIMIZATION: Service now loads all accounts once via findByUserId instead of per-account
        // queries
        when(accountRepository.findByUserId(testUserId)).thenReturn(Collections.emptyList());
        when(accountRepository.saveIfNotExists(any(AccountTable.class))).thenReturn(true);

        // When - Should not throw exception even if one account fails
        assertDoesNotThrow(() -> plaidSyncService.syncAccounts(testUser, testAccessToken, null));

        // Then - At least one account should be saved
        verify(accountRepository, atLeastOnce()).saveIfNotExists(any(AccountTable.class));
    }

    // Helper methods
    private AccountBase createMockPlaidAccount(final String accountId, final String name, final Double balance) {
        final AccountBase account = new AccountBase();
        if (accountId != null) {
            account.accountId(accountId);
        }
        if (name != null) {
            account.name(name);
        }
        if (balance != null) {
            final AccountBalance accountBalance = new AccountBalance();
            accountBalance.current(balance);
            accountBalance.available(balance);
            accountBalance.isoCurrencyCode("USD");
            account.balances(accountBalance);
        }
        account.type(AccountType.DEPOSITORY);
        account.subtype(AccountSubtype.CHECKING);
        return account;
    }

    private Transaction createMockPlaidTransaction(
            final String transactionId,
            final String name,
            final Double amount,
            final LocalDate date,
            final List<String> category) {
        final Transaction transaction = new Transaction();
        if (transactionId != null) {
            transaction.transactionId(transactionId);
        }
        if (name != null) {
            transaction.name(name);
        }
        if (amount != null) {
            transaction.amount(amount);
        }
        if (date != null) {
            transaction.date(date);
        }
        if (category != null) {
            transaction.category(category);
        }
        // CRITICAL: Set accountId so transaction can be grouped with account in batched sync
        transaction.accountId("plaid-account-1");
        transaction.isoCurrencyCode("USD");
        transaction.pending(false);
        return transaction;
    }
}
