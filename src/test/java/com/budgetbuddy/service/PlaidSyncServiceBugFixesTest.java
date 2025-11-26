package com.budgetbuddy.service;

import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.plaid.PlaidService;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.plaid.client.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for bug fixes implemented today:
 * 1. Account sync sets active = true for new accounts
 * 2. AccountRepository includes null-active accounts
 * 3. Transaction sync sets correct date format (YYYY-MM-DD)
 * 4. Transaction sync handles null category (defaults to "Other")
 * 5. Individual item failures don't block entire load
 * 
 */
@ExtendWith(MockitoExtension.class)
class PlaidSyncServiceBugFixesTest {

    @Mock
    private PlaidService plaidService;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @InjectMocks
    private PlaidSyncService plaidSyncService;

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
    }

    @Test
    void testSyncAccounts_SetsActiveToTrue_ForNewAccounts() {
        // Given - New account from Plaid
        AccountBase plaidAccount = createMockPlaidAccount("plaid-account-1", "Test Account", 1000.0);
        AccountsGetResponse accountsResponse = new AccountsGetResponse();
        accountsResponse.setAccounts(Collections.singletonList(plaidAccount));

        when(plaidService.getAccounts(testAccessToken)).thenReturn(accountsResponse);
        when(accountRepository.findByPlaidAccountId("plaid-account-1")).thenReturn(Optional.empty());
        when(accountRepository.saveIfNotExists(any(AccountTable.class))).thenReturn(true);

        // When
        plaidSyncService.syncAccounts(testUser, testAccessToken);

        // Then - Verify active is set to true
        ArgumentCaptor<AccountTable> accountCaptor = ArgumentCaptor.forClass(AccountTable.class);
        verify(accountRepository).saveIfNotExists(accountCaptor.capture());
        AccountTable savedAccount = accountCaptor.getValue();
        assertTrue(savedAccount.getActive(), "New accounts should have active = true");
        assertNotNull(savedAccount.getCreatedAt(), "CreatedAt should be set");
        assertNotNull(savedAccount.getUpdatedAt(), "UpdatedAt should be set");
    }

    @Test
    void testSyncAccounts_PreservesActiveStatus_ForExistingAccounts() {
        // Given - Existing account with active = true
        AccountBase plaidAccount = createMockPlaidAccount("plaid-account-1", "Test Account", 1500.0);
        AccountsGetResponse accountsResponse = new AccountsGetResponse();
        accountsResponse.setAccounts(Collections.singletonList(plaidAccount));

        AccountTable existingAccount = new AccountTable();
        existingAccount.setAccountId(UUID.randomUUID().toString());
        existingAccount.setUserId(testUserId);
        existingAccount.setPlaidAccountId("plaid-account-1");
        existingAccount.setActive(true);

        when(plaidService.getAccounts(testAccessToken)).thenReturn(accountsResponse);
        when(accountRepository.findByPlaidAccountId("plaid-account-1")).thenReturn(Optional.of(existingAccount));

        // When
        plaidSyncService.syncAccounts(testUser, testAccessToken);

        // Then - Verify active status is preserved
        ArgumentCaptor<AccountTable> accountCaptor = ArgumentCaptor.forClass(AccountTable.class);
        verify(accountRepository).save(accountCaptor.capture());
        AccountTable savedAccount = accountCaptor.getValue();
        assertTrue(savedAccount.getActive(), "Active status should be preserved");
    }

    @Test
    void testSyncTransactions_SetsCorrectDateFormat_YYYYMMDD() {
        // Given - Transaction with date
        Transaction plaidTransaction = createMockPlaidTransaction(
                "plaid-tx-1",
                "Test Transaction",
                50.0,
                LocalDate.of(2025, 11, 26),
                null // null category - should default to "Other"
        );
        TransactionsGetResponse transactionsResponse = new TransactionsGetResponse();
        transactionsResponse.setTransactions(Collections.singletonList(plaidTransaction));
        transactionsResponse.setTotalTransactions(1);

        when(plaidService.getTransactions(eq(testAccessToken), anyString(), anyString()))
                .thenReturn(transactionsResponse);
        when(transactionRepository.findByPlaidTransactionId("plaid-tx-1")).thenReturn(Optional.empty());
        when(transactionRepository.saveIfPlaidTransactionNotExists(any(TransactionTable.class))).thenReturn(true);

        // When
        plaidSyncService.syncTransactions(testUser, testAccessToken);

        // Then - Verify date format is YYYY-MM-DD
        ArgumentCaptor<TransactionTable> transactionCaptor = ArgumentCaptor.forClass(TransactionTable.class);
        verify(transactionRepository).saveIfPlaidTransactionNotExists(transactionCaptor.capture());
        TransactionTable savedTransaction = transactionCaptor.getValue();
        assertEquals("2025-11-26", savedTransaction.getTransactionDate(), 
                "Transaction date should be in YYYY-MM-DD format");
    }

    @Test
    void testSyncTransactions_HandlesNullCategory_DefaultsToOther() {
        // Given - Transaction with null category (BUG FIX: This was causing iOS app failures)
        Transaction plaidTransaction = createMockPlaidTransaction(
                "plaid-tx-1",
                "Uber 072515 SF**POOL**",
                6.33,
                LocalDate.of(2025, 10, 29),
                null // null category - actual backend behavior
        );
        TransactionsGetResponse transactionsResponse = new TransactionsGetResponse();
        transactionsResponse.setTransactions(Collections.singletonList(plaidTransaction));
        transactionsResponse.setTotalTransactions(1);

        when(plaidService.getTransactions(eq(testAccessToken), anyString(), anyString()))
                .thenReturn(transactionsResponse);
        when(transactionRepository.findByPlaidTransactionId("plaid-tx-1")).thenReturn(Optional.empty());
        when(transactionRepository.saveIfPlaidTransactionNotExists(any(TransactionTable.class))).thenReturn(true);

        // When
        plaidSyncService.syncTransactions(testUser, testAccessToken);

        // Then - Verify category defaults to "Other"
        ArgumentCaptor<TransactionTable> transactionCaptor = ArgumentCaptor.forClass(TransactionTable.class);
        verify(transactionRepository).saveIfPlaidTransactionNotExists(transactionCaptor.capture());
        TransactionTable savedTransaction = transactionCaptor.getValue();
        assertNotNull(savedTransaction.getCategory(), "Category should not be null");
        assertEquals("Other", savedTransaction.getCategory(), 
                "Null category should default to 'Other'");
    }

    @Test
    void testSyncTransactions_HandlesPartialFailures_Gracefully() {
        // Given - Multiple transactions, one with invalid data
        Transaction validTransaction = createMockPlaidTransaction(
                "plaid-tx-1",
                "Valid Transaction",
                100.0,
                LocalDate.of(2025, 11, 26),
                Arrays.asList("Food", "Restaurants")
        );
        Transaction invalidTransaction = createMockPlaidTransaction(
                "plaid-tx-2",
                "Invalid Transaction",
                null, // null amount - should be handled
                null, // null date - should be handled
                null
        );
        TransactionsGetResponse transactionsResponse = new TransactionsGetResponse();
        transactionsResponse.setTransactions(Arrays.asList(validTransaction, invalidTransaction));
        transactionsResponse.setTotalTransactions(2);

        when(plaidService.getTransactions(eq(testAccessToken), anyString(), anyString()))
                .thenReturn(transactionsResponse);
        when(transactionRepository.findByPlaidTransactionId("plaid-tx-1")).thenReturn(Optional.empty());
        when(transactionRepository.findByPlaidTransactionId("plaid-tx-2")).thenReturn(Optional.empty());
        when(transactionRepository.saveIfPlaidTransactionNotExists(any(TransactionTable.class)))
                .thenReturn(true);

        // When - Should not throw exception even if one transaction fails
        assertDoesNotThrow(() -> plaidSyncService.syncTransactions(testUser, testAccessToken));

        // Then - At least one transaction should be saved
        verify(transactionRepository, atLeastOnce()).saveIfPlaidTransactionNotExists(any(TransactionTable.class));
    }

    @Test
    void testSyncAccounts_HandlesPartialFailures_Gracefully() {
        // Given - Multiple accounts, one with invalid data
        AccountBase validAccount = createMockPlaidAccount("plaid-account-1", "Valid Account", 1000.0);
        AccountBase invalidAccount = createMockPlaidAccount(null, null, null); // Invalid account
        
        AccountsGetResponse accountsResponse = new AccountsGetResponse();
        accountsResponse.setAccounts(Arrays.asList(validAccount, invalidAccount));

        when(plaidService.getAccounts(testAccessToken)).thenReturn(accountsResponse);
        when(accountRepository.findByPlaidAccountId("plaid-account-1")).thenReturn(Optional.empty());
        when(accountRepository.saveIfNotExists(any(AccountTable.class))).thenReturn(true);

        // When - Should not throw exception even if one account fails
        assertDoesNotThrow(() -> plaidSyncService.syncAccounts(testUser, testAccessToken));

        // Then - At least one account should be saved
        verify(accountRepository, atLeastOnce()).saveIfNotExists(any(AccountTable.class));
    }

    // Helper methods
    private AccountBase createMockPlaidAccount(String accountId, String name, Double balance) {
        AccountBase account = new AccountBase();
        if (accountId != null) {
            account.accountId(accountId);
        }
        if (name != null) {
            account.name(name);
        }
        if (balance != null) {
            AccountBalance accountBalance = new AccountBalance();
            accountBalance.current(balance);
            accountBalance.available(balance);
            accountBalance.isoCurrencyCode("USD");
            account.balances(accountBalance);
        }
        account.type(AccountType.DEPOSITORY);
        account.subtype(AccountSubtype.CHECKING);
        return account;
    }

    private Transaction createMockPlaidTransaction(String transactionId, String name, Double amount, 
                                                   LocalDate date, List<String> category) {
        Transaction transaction = new Transaction();
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
        transaction.isoCurrencyCode("USD");
        transaction.pending(false);
        return transaction;
    }
}

