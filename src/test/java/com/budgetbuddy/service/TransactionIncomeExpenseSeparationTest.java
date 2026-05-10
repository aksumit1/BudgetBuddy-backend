package com.budgetbuddy.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
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
import com.plaid.client.model.Transaction;
import com.plaid.client.model.TransactionsGetResponse;
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
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit Tests for PlaidSyncService - Income/Expense Separation Tests that transactions are stored
 * with correct amounts and categories so the iOS app can properly separate income and expenses
 */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
@SuppressWarnings({"PMD.LawOfDemeter", "PMD.AvoidCatchingGenericException"})
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class TransactionIncomeExpenseSeparationTest {

    @Mock private PlaidService plaidService;

    @Mock private AccountRepository accountRepository;

    @Mock private TransactionRepository transactionRepository;

    @Mock private com.budgetbuddy.service.PlaidCategoryMapper categoryMapper;

    @Mock private com.budgetbuddy.service.plaid.PlaidDataExtractor dataExtractor;

    @Mock private PlaidSyncOrchestrator syncOrchestrator;

    @InjectMocks private PlaidSyncService plaidSyncService;

    private UserTable testUser;
    private AccountTable creditCardAccount;
    private AccountTable checkingAccount;

    @BeforeEach
    void setUp() {
        testUser = new UserTable();
        testUser.setUserId(UUID.randomUUID().toString());
        testUser.setEmail("test@example.com");

        // Create credit card account
        creditCardAccount = new AccountTable();
        creditCardAccount.setAccountId(UUID.randomUUID().toString());
        creditCardAccount.setUserId(testUser.getUserId());
        creditCardAccount.setAccountName("Chase Credit Card");
        creditCardAccount.setAccountType("creditCard");
        creditCardAccount.setBalance(new BigDecimal("-500.00"));
        creditCardAccount.setPlaidAccountId(
                "plaid-cc-account-1"); // CRITICAL: Set plaidAccountId for transaction grouping
        creditCardAccount.setLastSyncedAt(null); // Ensure sync isn't skipped
        creditCardAccount.setActive(true);

        // Create checking account
        checkingAccount = new AccountTable();
        checkingAccount.setAccountId(UUID.randomUUID().toString());
        checkingAccount.setUserId(testUser.getUserId());
        checkingAccount.setAccountName("Chase Checking");
        checkingAccount.setPlaidAccountId(
                "plaid-checking-account-1"); // CRITICAL: Set plaidAccountId for transaction
        // grouping
        checkingAccount.setAccountType("checking");
        checkingAccount.setBalance(new BigDecimal("1000.00"));
        checkingAccount.setLastSyncedAt(null); // Ensure sync isn't skipped
        checkingAccount.setActive(true);

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
        doAnswer(
                        invocation -> {
                            realOrchestrator.syncAccountsOnly(
                                    invocation.getArgument(0),
                                    invocation.getArgument(1),
                                    invocation.getArgument(2));
                            return null;
                        })
                .when(syncOrchestrator)
                .syncAccountsOnly(any(UserTable.class), anyString(), nullable(String.class));

        doAnswer(
                        invocation -> {
                            realOrchestrator.syncTransactionsOnly(
                                    invocation.getArgument(0), invocation.getArgument(1));
                            return null;
                        })
                .when(syncOrchestrator)
                .syncTransactionsOnly(any(UserTable.class), anyString());
    }

    @Test
    void testSyncTransactionsIncomeTransactionStoredWithPositiveAmount() throws Exception {
        // Given - Income transaction (salary deposit)
        // Plaid sends income as negative, but we store it as-is
        final Transaction plaidTransaction =
                createMockPlaidTransaction(
                        "txn-income-123",
                        "EMPLOYER CORP",
                        "Direct Deposit - Salary",
                        new BigDecimal("-5000.00"), // Negative = income in Plaid
                        Arrays.asList("Transfer", "Deposit"),
                        checkingAccount.getPlaidAccountId() // Use plaidAccountId for grouping
                );

        final TransactionsGetResponse mockResponse = new TransactionsGetResponse();
        mockResponse.setTransactions(Arrays.asList(plaidTransaction));
        mockResponse.setTotalTransactions(1);

        when(accountRepository.findByUserId(testUser.getUserId()))
                .thenReturn(Collections.singletonList(checkingAccount));
        when(plaidService.getTransactions(anyString(), anyString(), anyString()))
                .thenReturn(mockResponse);
        // Mock dataExtractor to return account ID for transaction grouping and transaction ID
        when(dataExtractor.extractAccountIdFromTransaction(any()))
                .thenAnswer(
                        invocation -> {
                            final Object transaction = invocation.getArgument(0);
                            if (transaction instanceof Transaction) {
                                return ((Transaction) transaction).getAccountId();
                            }
                            return null;
                        });
        when(dataExtractor.extractTransactionId(any()))
                .thenAnswer(
                        invocation -> {
                            final Object transaction = invocation.getArgument(0);
                            if (transaction instanceof Transaction) {
                                return ((Transaction) transaction).getTransactionId();
                            }
                            return null;
                        });
        // Mock updateTransactionFromPlaid to actually populate transaction fields
        doAnswer(
                        invocation -> {
                            final TransactionTable txTable = invocation.getArgument(0);
                            final Object plaidTxObj = invocation.getArgument(1);
                            if (plaidTxObj instanceof Transaction) {
                                final Transaction plaidTx = (Transaction) plaidTxObj;
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
        when(accountRepository.findByPlaidAccountId(anyString()))
                .thenReturn(Optional.of(checkingAccount));
        when(transactionRepository.findByPlaidTransactionId(anyString()))
                .thenReturn(Optional.empty());
        when(transactionRepository.saveIfPlaidTransactionNotExists(any(TransactionTable.class)))
                .thenReturn(true);

        // When
        plaidSyncService.syncTransactions(testUser, "access-token");

        // Then - Verify amount is stored correctly (iOS app will convert to positive)
        final ArgumentCaptor<TransactionTable> captor = ArgumentCaptor.forClass(TransactionTable.class);
        verify(transactionRepository, atLeastOnce())
                .saveIfPlaidTransactionNotExists(captor.capture());

        final TransactionTable savedTransaction = captor.getValue();
        assertNotNull(savedTransaction);
        // Amount should be stored as-is from Plaid (negative for income)
        assertTrue(savedTransaction.getAmount().compareTo(new BigDecimal("-5000.00")) == 0);
        assertEquals("EMPLOYER CORP", savedTransaction.getMerchantName());
    }

    @Test
    void testSyncTransactionsExpenseTransactionStoredWithPositiveAmount() throws Exception {
        // Given - Expense transaction (coffee purchase)
        // Plaid sends expenses as positive
        final Transaction plaidTransaction =
                createMockPlaidTransaction(
                        "txn-expense-123",
                        "Starbucks",
                        "Coffee Purchase",
                        new BigDecimal("5.50"), // Positive = expense in Plaid
                        Arrays.asList("Food and Drink", "Restaurants"),
                        checkingAccount.getPlaidAccountId() // Use plaidAccountId for grouping
                );

        final TransactionsGetResponse mockResponse = new TransactionsGetResponse();
        mockResponse.setTransactions(Arrays.asList(plaidTransaction));
        mockResponse.setTotalTransactions(1);

        when(accountRepository.findByUserId(testUser.getUserId()))
                .thenReturn(Collections.singletonList(checkingAccount));
        when(plaidService.getTransactions(anyString(), anyString(), anyString()))
                .thenReturn(mockResponse);
        // Mock dataExtractor to return account ID for transaction grouping and transaction ID
        when(dataExtractor.extractAccountIdFromTransaction(any()))
                .thenAnswer(
                        invocation -> {
                            final Object transaction = invocation.getArgument(0);
                            if (transaction instanceof Transaction) {
                                return ((Transaction) transaction).getAccountId();
                            }
                            return null;
                        });
        when(dataExtractor.extractTransactionId(any()))
                .thenAnswer(
                        invocation -> {
                            final Object transaction = invocation.getArgument(0);
                            if (transaction instanceof Transaction) {
                                return ((Transaction) transaction).getTransactionId();
                            }
                            return null;
                        });
        // Mock updateTransactionFromPlaid to actually populate transaction fields
        doAnswer(
                        invocation -> {
                            final TransactionTable txTable = invocation.getArgument(0);
                            final Object plaidTxObj = invocation.getArgument(1);
                            if (plaidTxObj instanceof Transaction) {
                                final Transaction plaidTx = (Transaction) plaidTxObj;
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
        when(accountRepository.findByPlaidAccountId(anyString()))
                .thenReturn(Optional.of(checkingAccount));
        when(transactionRepository.findByPlaidTransactionId(anyString()))
                .thenReturn(Optional.empty());
        when(transactionRepository.saveIfPlaidTransactionNotExists(any(TransactionTable.class)))
                .thenReturn(true);

        // When
        plaidSyncService.syncTransactions(testUser, "access-token");

        // Then - Verify amount is stored correctly (iOS app will convert to negative)
        final ArgumentCaptor<TransactionTable> captor = ArgumentCaptor.forClass(TransactionTable.class);
        verify(transactionRepository, atLeastOnce())
                .saveIfPlaidTransactionNotExists(captor.capture());

        final TransactionTable savedTransaction = captor.getValue();
        assertNotNull(savedTransaction);
        // Amount should be stored as-is from Plaid (positive for expense)
        assertTrue(savedTransaction.getAmount().compareTo(new BigDecimal("5.50")) == 0);
        assertEquals("Starbucks", savedTransaction.getMerchantName());
    }

    @Test
    void testSyncTransactionsCreditCardPaymentStoredWithPositiveAmount() throws Exception {
        // Given - Credit card payment (positive amount on credit card account)
        final Transaction plaidTransaction =
                createMockPlaidTransaction(
                        "txn-payment-123",
                        "Payment",
                        "Payment - Credit Card",
                        new BigDecimal("200.00"), // Positive = payment to credit card
                        Arrays.asList("Transfer"),
                        creditCardAccount.getPlaidAccountId() // Use plaidAccountId for grouping
                );

        final TransactionsGetResponse mockResponse = new TransactionsGetResponse();
        mockResponse.setTransactions(Arrays.asList(plaidTransaction));
        mockResponse.setTotalTransactions(1);

        when(accountRepository.findByUserId(testUser.getUserId()))
                .thenReturn(Collections.singletonList(creditCardAccount));
        when(plaidService.getTransactions(anyString(), anyString(), anyString()))
                .thenReturn(mockResponse);
        // Mock dataExtractor to return account ID for transaction grouping and transaction ID
        when(dataExtractor.extractAccountIdFromTransaction(any()))
                .thenAnswer(
                        invocation -> {
                            final Object plaidTxObj = invocation.getArgument(0);
                            if (plaidTxObj instanceof Transaction) {
                                return ((Transaction) plaidTxObj).getAccountId();
                            }
                            return null;
                        });
        when(dataExtractor.extractTransactionId(any()))
                .thenAnswer(
                        invocation -> {
                            final Object plaidTxObj = invocation.getArgument(0);
                            if (plaidTxObj instanceof Transaction) {
                                return ((Transaction) plaidTxObj).getTransactionId();
                            }
                            return null;
                        });
        // Mock updateTransactionFromPlaid to actually populate transaction fields
        doAnswer(
                        invocation -> {
                            final TransactionTable txTable = invocation.getArgument(0);
                            final Object plaidTxObj = invocation.getArgument(1);
                            if (plaidTxObj instanceof Transaction) {
                                final Transaction plaidTx = (Transaction) plaidTxObj;
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
                                    final var personalFinanceCategory =
                                            plaidTx.getPersonalFinanceCategory();
                                    if (personalFinanceCategory != null) {
                                        plaidCategoryPrimary = personalFinanceCategory.getPrimary();
                                        plaidCategoryDetailed =
                                                personalFinanceCategory.getDetailed();
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
        when(accountRepository.findByPlaidAccountId(anyString()))
                .thenReturn(Optional.of(creditCardAccount));
        when(transactionRepository.findByPlaidTransactionId(anyString()))
                .thenReturn(Optional.empty());
        when(transactionRepository.saveIfPlaidTransactionNotExists(any(TransactionTable.class)))
                .thenReturn(true);

        // When
        plaidSyncService.syncTransactions(testUser, "access-token");

        // Then - Verify payment is stored (iOS app will exclude it from expenses)
        final ArgumentCaptor<TransactionTable> captor = ArgumentCaptor.forClass(TransactionTable.class);
        verify(transactionRepository, atLeastOnce())
                .saveIfPlaidTransactionNotExists(captor.capture());

        final TransactionTable savedTransaction = captor.getValue();
        assertNotNull(savedTransaction);
        // Amount should be stored as-is (positive for credit card payment)
        assertTrue(savedTransaction.getAmount().compareTo(new BigDecimal("200.00")) == 0);
        assertEquals("Payment", savedTransaction.getMerchantName());
        // iOS app will check account type and exclude this from expenses
    }

    @Test
    void testSyncTransactionsCreditCardExpenseStoredWithPositiveAmount() throws Exception {
        // Given - Expense on credit card (spending, not payment)
        final Transaction plaidTransaction =
                createMockPlaidTransaction(
                        "txn-cc-expense-123",
                        "Coffee Shop",
                        "Coffee Purchase",
                        new BigDecimal("10.00"), // Positive = expense in Plaid
                        Arrays.asList("Food and Drink", "Restaurants"),
                        creditCardAccount.getPlaidAccountId() // Use plaidAccountId for grouping
                );

        final TransactionsGetResponse mockResponse = new TransactionsGetResponse();
        mockResponse.setTransactions(Arrays.asList(plaidTransaction));
        mockResponse.setTotalTransactions(1);

        when(accountRepository.findByUserId(testUser.getUserId()))
                .thenReturn(Collections.singletonList(creditCardAccount));
        when(plaidService.getTransactions(anyString(), anyString(), anyString()))
                .thenReturn(mockResponse);
        // Mock dataExtractor to return account ID for transaction grouping and transaction ID
        when(dataExtractor.extractAccountIdFromTransaction(any()))
                .thenAnswer(
                        invocation -> {
                            final Object plaidTxObj = invocation.getArgument(0);
                            if (plaidTxObj instanceof Transaction) {
                                return ((Transaction) plaidTxObj).getAccountId();
                            }
                            return null;
                        });
        when(dataExtractor.extractTransactionId(any()))
                .thenAnswer(
                        invocation -> {
                            final Object plaidTxObj = invocation.getArgument(0);
                            if (plaidTxObj instanceof Transaction) {
                                return ((Transaction) plaidTxObj).getTransactionId();
                            }
                            return null;
                        });
        // Mock updateTransactionFromPlaid to actually populate transaction fields
        doAnswer(
                        invocation -> {
                            final TransactionTable txTable = invocation.getArgument(0);
                            final Object plaidTxObj = invocation.getArgument(1);
                            if (plaidTxObj instanceof Transaction) {
                                final Transaction plaidTx = (Transaction) plaidTxObj;
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
                                    final var personalFinanceCategory =
                                            plaidTx.getPersonalFinanceCategory();
                                    if (personalFinanceCategory != null) {
                                        plaidCategoryPrimary = personalFinanceCategory.getPrimary();
                                        plaidCategoryDetailed =
                                                personalFinanceCategory.getDetailed();
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
        when(accountRepository.findByPlaidAccountId(anyString()))
                .thenReturn(Optional.of(creditCardAccount));
        when(transactionRepository.findByPlaidTransactionId(anyString()))
                .thenReturn(Optional.empty());
        when(transactionRepository.saveIfPlaidTransactionNotExists(any(TransactionTable.class)))
                .thenReturn(true);

        // When
        plaidSyncService.syncTransactions(testUser, "access-token");

        // Then - Verify expense is stored (iOS app will include it in expenses)
        final ArgumentCaptor<TransactionTable> captor = ArgumentCaptor.forClass(TransactionTable.class);
        verify(transactionRepository, atLeastOnce())
                .saveIfPlaidTransactionNotExists(captor.capture());

        final TransactionTable savedTransaction = captor.getValue();
        assertNotNull(savedTransaction);
        // Amount should be stored as-is (positive for expense)
        assertTrue(savedTransaction.getAmount().compareTo(new BigDecimal("10.00")) == 0);
        assertEquals("Coffee Shop", savedTransaction.getMerchantName());
        // iOS app will include this in expenses (negative amount on credit card = spending)
    }

    @Test
    void testSyncTransactionsAllTransactionTypesStoredCorrectly() throws Exception {
        // Given - Mix of income, expenses, and credit card transactions
        final Transaction income =
                createMockPlaidTransaction(
                        "txn-income-1",
                        "EMPLOYER",
                        "Salary",
                        new BigDecimal("-5000.00"),
                        Arrays.asList("Transfer", "Deposit"),
                        checkingAccount.getPlaidAccountId() // Use plaidAccountId for grouping
                );

        final Transaction expense =
                createMockPlaidTransaction(
                        "txn-expense-1",
                        "Grocery Store",
                        "Groceries",
                        new BigDecimal("100.00"),
                        Arrays.asList("Food and Drink", "Groceries"),
                        checkingAccount.getPlaidAccountId() // Use plaidAccountId for grouping
                );

        final Transaction ccPayment =
                createMockPlaidTransaction(
                        "txn-cc-payment-1",
                        "Payment",
                        "Payment - Credit Card",
                        new BigDecimal("200.00"),
                        Arrays.asList("Transfer"),
                        creditCardAccount.getPlaidAccountId() // Use plaidAccountId for grouping
                );

        final Transaction ccExpense =
                createMockPlaidTransaction(
                        "txn-cc-expense-1",
                        "Restaurant",
                        "Dinner",
                        new BigDecimal("50.00"),
                        Arrays.asList("Food and Drink", "Restaurants"),
                        creditCardAccount.getPlaidAccountId() // Use plaidAccountId for grouping
                );

        final TransactionsGetResponse mockResponse = new TransactionsGetResponse();
        mockResponse.setTransactions(Arrays.asList(income, expense, ccPayment, ccExpense));
        mockResponse.setTotalTransactions(4);

        when(accountRepository.findByUserId(testUser.getUserId()))
                .thenReturn(Arrays.asList(checkingAccount, creditCardAccount));
        when(plaidService.getTransactions(anyString(), anyString(), anyString()))
                .thenReturn(mockResponse);
        // Mock dataExtractor to return account ID for transaction grouping and transaction ID
        when(dataExtractor.extractAccountIdFromTransaction(any()))
                .thenAnswer(
                        invocation -> {
                            final Object transaction = invocation.getArgument(0);
                            if (transaction instanceof Transaction) {
                                return ((Transaction) transaction).getAccountId();
                            }
                            return null;
                        });
        when(dataExtractor.extractTransactionId(any()))
                .thenAnswer(
                        invocation -> {
                            final Object plaidTxObj = invocation.getArgument(0);
                            if (plaidTxObj instanceof Transaction) {
                                return ((Transaction) plaidTxObj).getTransactionId();
                            }
                            return null;
                        });
        // Mock updateTransactionFromPlaid to actually populate transaction fields
        doAnswer(
                        invocation -> {
                            final TransactionTable txTable = invocation.getArgument(0);
                            final Object plaidTxObj = invocation.getArgument(1);
                            if (plaidTxObj instanceof Transaction) {
                                final Transaction plaidTx = (Transaction) plaidTxObj;
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
                                    final var personalFinanceCategory =
                                            plaidTx.getPersonalFinanceCategory();
                                    if (personalFinanceCategory != null) {
                                        plaidCategoryPrimary = personalFinanceCategory.getPrimary();
                                        plaidCategoryDetailed =
                                                personalFinanceCategory.getDetailed();
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
                                txTable.setCategoryOverridden(categoryMapping.isOverridden());
                            }
                            return null;
                        })
                .when(dataExtractor)
                .updateTransactionFromPlaid(any(TransactionTable.class), any());
        when(accountRepository.findByPlaidAccountId(anyString()))
                .thenReturn(Optional.of(checkingAccount))
                .thenReturn(Optional.of(checkingAccount))
                .thenReturn(Optional.of(creditCardAccount))
                .thenReturn(Optional.of(creditCardAccount));
        when(transactionRepository.findByPlaidTransactionId(anyString()))
                .thenReturn(Optional.empty());
        when(transactionRepository.saveIfPlaidTransactionNotExists(any(TransactionTable.class)))
                .thenReturn(true);

        // When
        plaidSyncService.syncTransactions(testUser, "access-token");

        // Then - Verify all transactions are stored with correct amounts
        // Note: Since we have 2 accounts, transactions may be processed multiple times
        // We verify that at least the expected transactions are saved
        final ArgumentCaptor<TransactionTable> captor = ArgumentCaptor.forClass(TransactionTable.class);
        verify(transactionRepository, atLeast(4)).saveIfPlaidTransactionNotExists(captor.capture());

        final List<TransactionTable> savedTransactions = captor.getAllValues();
        assertTrue(savedTransactions.size() >= 4, "At least 4 transactions should be saved");

        // Verify amounts are stored correctly (iOS app will handle sign conversion)
        assertTrue(
                savedTransactions.stream()
                        .anyMatch(
                                t ->
                                        "txn-income-1".equals(t.getPlaidTransactionId())
                                                && t.getAmount() != null
                                                && t.getAmount()
                                                                .compareTo(
                                                                        new BigDecimal("-5000.00"))
                                                        == 0));

        assertTrue(
                savedTransactions.stream()
                        .anyMatch(
                                t ->
                                        "txn-expense-1".equals(t.getPlaidTransactionId())
                                                && t.getAmount() != null
                                                && t.getAmount().compareTo(new BigDecimal("100.00"))
                                                        == 0));

        assertTrue(
                savedTransactions.stream()
                        .anyMatch(
                                t ->
                                        "txn-cc-payment-1".equals(t.getPlaidTransactionId())
                                                && t.getAmount() != null
                                                && t.getAmount().compareTo(new BigDecimal("200.00"))
                                                        == 0));

        assertTrue(
                savedTransactions.stream()
                        .anyMatch(
                                t ->
                                        "txn-cc-expense-1".equals(t.getPlaidTransactionId())
                                                && t.getAmount() != null
                                                && t.getAmount().compareTo(new BigDecimal("50.00"))
                                                        == 0));
    }

    /** Helper method to create a mock Plaid transaction */
    private Transaction createMockPlaidTransaction(
            final String transactionId,
            final String merchantName,
            final String name,
            final BigDecimal amount,
            final List<String> categories,
            final String accountId) {
        final Transaction transaction = new Transaction();
        transaction.setTransactionId(transactionId);
        transaction.setMerchantName(merchantName);
        transaction.setName(name);
        transaction.setAmount(amount.doubleValue()); // Convert BigDecimal to Double
        transaction.setCategory(categories);
        transaction.setDate(LocalDate.now());
        transaction.setAccountId(accountId);
        transaction.setPending(false);
        return transaction;
    }
}
