package com.budgetbuddy.service;

import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.plaid.PlaidService;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.repository.dynamodb.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for Plaid deduplication logic
 * Verifies that accounts and transactions are properly deduplicated using plaidAccountId and plaidTransactionId
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class PlaidDeduplicationTest {

    @Mock
    private PlaidService plaidService;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private PlaidSyncService plaidSyncService;

    private UserTable testUser;
    private String testUserId;
    private String testPlaidAccountId;
    private String testPlaidTransactionId;

    @BeforeEach
    void setUp() {
        testUserId = UUID.randomUUID().toString();
        testPlaidAccountId = "plaid-account-123";
        testPlaidTransactionId = "plaid-transaction-456";

        testUser = new UserTable();
        testUser.setUserId(testUserId);
        testUser.setEmail("test@example.com");
    }

    @Test
    void testSyncAccounts_WithDuplicatePlaidAccountId_UpdatesExistingAccount() {
        // Given - Account already exists with same plaidAccountId
        AccountTable existingAccount = new AccountTable();
        existingAccount.setAccountId(UUID.randomUUID().toString());
        existingAccount.setUserId(testUserId);
        existingAccount.setPlaidAccountId(testPlaidAccountId);
        existingAccount.setAccountName("Existing Account");
        existingAccount.setBalance(new BigDecimal("1000.00"));

        when(accountRepository.findByPlaidAccountId(testPlaidAccountId))
                .thenReturn(Optional.of(existingAccount));

        // Mock Plaid response with same account
        com.plaid.client.model.AccountsGetResponse accountsResponse = new com.plaid.client.model.AccountsGetResponse();
        var plaidAccount = mock(com.plaid.client.model.AccountBase.class);
        when(plaidAccount.getAccountId()).thenReturn(testPlaidAccountId);
        when(plaidAccount.getName()).thenReturn("Updated Account Name");
        when(plaidAccount.getType()).thenReturn(com.plaid.client.model.AccountType.DEPOSITORY);
        when(plaidAccount.getSubtype()).thenReturn(com.plaid.client.model.AccountSubtype.CHECKING);
        when(plaidAccount.getBalances()).thenReturn(new com.plaid.client.model.AccountBalance());
        
        var balances = new com.plaid.client.model.AccountBalance();
        balances.setAvailable(2000.0);
        balances.setCurrent(2000.0);
        when(plaidAccount.getBalances()).thenReturn(balances);

        accountsResponse.setAccounts(Collections.singletonList(plaidAccount));
        accountsResponse.setItem(new com.plaid.client.model.Item());

        when(plaidService.getAccounts(anyString())).thenReturn(accountsResponse);

        // When - Sync accounts
        plaidSyncService.syncAccounts(testUser, "test-access-token");

        // Then - Account should be updated, not duplicated
        verify(accountRepository, times(1)).findByPlaidAccountId(testPlaidAccountId);
        verify(accountRepository, never()).saveIfNotExists(any(AccountTable.class));
        verify(accountRepository, atLeastOnce()).save(any(AccountTable.class));
    }

    @Test
    void testSyncAccounts_WithNewPlaidAccountId_CreatesNewAccount() {
        // Given - No existing account with this plaidAccountId
        when(accountRepository.findByPlaidAccountId(testPlaidAccountId))
                .thenReturn(Optional.empty());

        // Mock Plaid response
        com.plaid.client.model.AccountsGetResponse accountsResponse = new com.plaid.client.model.AccountsGetResponse();
        var plaidAccount = mock(com.plaid.client.model.AccountBase.class);
        when(plaidAccount.getAccountId()).thenReturn(testPlaidAccountId);
        when(plaidAccount.getName()).thenReturn("New Account");
        when(plaidAccount.getType()).thenReturn(com.plaid.client.model.AccountType.DEPOSITORY);
        when(plaidAccount.getSubtype()).thenReturn(com.plaid.client.model.AccountSubtype.CHECKING);
        
        var balances = new com.plaid.client.model.AccountBalance();
        balances.setAvailable(1000.0);
        balances.setCurrent(1000.0);
        when(plaidAccount.getBalances()).thenReturn(balances);

        accountsResponse.setAccounts(Collections.singletonList(plaidAccount));
        accountsResponse.setItem(new com.plaid.client.model.Item());

        when(plaidService.getAccounts(anyString())).thenReturn(accountsResponse);
        when(accountRepository.saveIfNotExists(any(AccountTable.class))).thenReturn(true);

        // When - Sync accounts
        plaidSyncService.syncAccounts(testUser, "test-access-token");

        // Then - New account should be created
        verify(accountRepository, times(1)).findByPlaidAccountId(testPlaidAccountId);
        verify(accountRepository, times(1)).saveIfNotExists(any(AccountTable.class));
    }

    @Test
    void testSyncTransactions_WithDuplicatePlaidTransactionId_UpdatesExistingTransaction() {
        // Given - Transaction already exists with same plaidTransactionId
        TransactionTable existingTransaction = new TransactionTable();
        existingTransaction.setTransactionId(UUID.randomUUID().toString());
        existingTransaction.setUserId(testUserId);
        existingTransaction.setPlaidTransactionId(testPlaidTransactionId);
        existingTransaction.setDescription("Existing Transaction");
        existingTransaction.setAmount(new BigDecimal("100.00"));

        when(transactionRepository.findByPlaidTransactionId(testPlaidTransactionId))
                .thenReturn(Optional.of(existingTransaction));

        // Mock Plaid response with same transaction
        com.plaid.client.model.TransactionsGetResponse transactionsResponse = 
                new com.plaid.client.model.TransactionsGetResponse();
        com.plaid.client.model.Transaction transaction = new com.plaid.client.model.Transaction();
        transaction.setTransactionId(testPlaidTransactionId);
        transaction.setAccountId(testPlaidAccountId);
        transaction.setAmount(200.0);
        transaction.setName("Updated Transaction");
        transaction.setDate(java.time.LocalDate.now());
        transaction.setCategory(Collections.singletonList("Food and Drink"));
        transactionsResponse.setTransactions(Collections.singletonList(transaction));
        transactionsResponse.setTotalTransactions(1);

        when(plaidService.getTransactions(anyString(), any(), any())).thenReturn(transactionsResponse);
        
        // Mock account lookup
        AccountTable account = new AccountTable();
        account.setAccountId(UUID.randomUUID().toString());
        account.setPlaidAccountId(testPlaidAccountId);
        when(accountRepository.findByPlaidAccountId(testPlaidAccountId))
                .thenReturn(Optional.of(account));
        when(accountRepository.findByUserId(testUserId)).thenReturn(Collections.singletonList(account));

        // When - Sync transactions
        plaidSyncService.syncTransactions(testUser, "test-access-token");

        // Then - Transaction should be updated, not duplicated
        verify(transactionRepository, atLeastOnce()).findByPlaidTransactionId(testPlaidTransactionId);
        verify(transactionRepository, never()).saveIfPlaidTransactionNotExists(any(TransactionTable.class));
        verify(transactionRepository, atLeastOnce()).save(any(TransactionTable.class));
    }

    @Test
    void testSyncTransactions_WithNewPlaidTransactionId_CreatesNewTransaction() {
        // Given - No existing transaction with this plaidTransactionId
        when(transactionRepository.findByPlaidTransactionId(testPlaidTransactionId))
                .thenReturn(Optional.empty());

        // Mock Plaid response
        com.plaid.client.model.TransactionsGetResponse transactionsResponse = 
                new com.plaid.client.model.TransactionsGetResponse();
        var plaidTransaction = mock(com.plaid.client.model.TransactionBase.class);
        when(plaidTransaction.getTransactionId()).thenReturn(testPlaidTransactionId);
        when(plaidTransaction.getAccountId()).thenReturn(testPlaidAccountId);
        when(plaidTransaction.getAmount()).thenReturn(100.0);
        when(plaidTransaction.getName()).thenReturn("New Transaction");
        when(plaidTransaction.getDate()).thenReturn(java.time.LocalDate.now());
        when(plaidTransaction.getCategory()).thenReturn(Collections.singletonList("Food and Drink"));

        // Convert TransactionBase to Transaction for response
        com.plaid.client.model.Transaction transaction = new com.plaid.client.model.Transaction();
        transaction.setTransactionId(testPlaidTransactionId);
        transaction.setAccountId(testPlaidAccountId);
        transaction.setAmount(200.0);
        transaction.setName("Updated Transaction");
        transaction.setDate(java.time.LocalDate.now());
        transaction.setCategory(Collections.singletonList("Food and Drink"));
        transactionsResponse.setTransactions(Collections.singletonList(transaction));
        transactionsResponse.setTotalTransactions(1);

        when(plaidService.getTransactions(anyString(), any(), any())).thenReturn(transactionsResponse);
        
        // Mock account lookup
        AccountTable account = new AccountTable();
        account.setAccountId(UUID.randomUUID().toString());
        account.setPlaidAccountId(testPlaidAccountId);
        when(accountRepository.findByPlaidAccountId(testPlaidAccountId))
                .thenReturn(Optional.of(account));
        when(accountRepository.findByUserId(testUserId)).thenReturn(Collections.singletonList(account));
        when(transactionRepository.saveIfPlaidTransactionNotExists(any(TransactionTable.class))).thenReturn(true);

        // When - Sync transactions
        plaidSyncService.syncTransactions(testUser, "test-access-token");

        // Then - New transaction should be created
        verify(transactionRepository, atLeastOnce()).findByPlaidTransactionId(testPlaidTransactionId);
        verify(transactionRepository, times(1)).saveIfPlaidTransactionNotExists(any(TransactionTable.class));
    }

    @Test
    void testSyncAccounts_WithMultiplePlaidCalls_DoesNotCreateDuplicates() {
        // Given - Account already exists
        AccountTable existingAccount = new AccountTable();
        existingAccount.setAccountId(UUID.randomUUID().toString());
        existingAccount.setUserId(testUserId);
        existingAccount.setPlaidAccountId(testPlaidAccountId);
        existingAccount.setAccountName("Existing Account");

        when(accountRepository.findByPlaidAccountId(testPlaidAccountId))
                .thenReturn(Optional.of(existingAccount));

        // Mock Plaid response
        com.plaid.client.model.AccountsGetResponse accountsResponse = new com.plaid.client.model.AccountsGetResponse();
        var plaidAccount = mock(com.plaid.client.model.AccountBase.class);
        when(plaidAccount.getAccountId()).thenReturn(testPlaidAccountId);
        when(plaidAccount.getName()).thenReturn("Account Name");
        when(plaidAccount.getType()).thenReturn(com.plaid.client.model.AccountType.DEPOSITORY);
        when(plaidAccount.getSubtype()).thenReturn(com.plaid.client.model.AccountSubtype.CHECKING);
        
        var balances = new com.plaid.client.model.AccountBalance();
        balances.setAvailable(1000.0);
        balances.setCurrent(1000.0);
        when(plaidAccount.getBalances()).thenReturn(balances);

        accountsResponse.setAccounts(Collections.singletonList(plaidAccount));
        accountsResponse.setItem(new com.plaid.client.model.Item());

        when(plaidService.getAccounts(anyString())).thenReturn(accountsResponse);

        // When - Sync accounts multiple times
        plaidSyncService.syncAccounts(testUser, "test-access-token");
        plaidSyncService.syncAccounts(testUser, "test-access-token");
        plaidSyncService.syncAccounts(testUser, "test-access-token");

        // Then - Account should only be updated, never duplicated
        verify(accountRepository, times(3)).findByPlaidAccountId(testPlaidAccountId);
        verify(accountRepository, never()).saveIfNotExists(any(AccountTable.class));
        verify(accountRepository, atLeastOnce()).save(any(AccountTable.class));
    }

    @Test
    void testSyncTransactions_WithMultiplePlaidCalls_DoesNotCreateDuplicates() {
        // Given - Transaction already exists
        TransactionTable existingTransaction = new TransactionTable();
        existingTransaction.setTransactionId(UUID.randomUUID().toString());
        existingTransaction.setUserId(testUserId);
        existingTransaction.setPlaidTransactionId(testPlaidTransactionId);
        existingTransaction.setDescription("Existing Transaction");

        when(transactionRepository.findByPlaidTransactionId(testPlaidTransactionId))
                .thenReturn(Optional.of(existingTransaction));

        // Mock Plaid response
        com.plaid.client.model.TransactionsGetResponse transactionsResponse = 
                new com.plaid.client.model.TransactionsGetResponse();
        var plaidTransaction = mock(com.plaid.client.model.TransactionBase.class);
        when(plaidTransaction.getTransactionId()).thenReturn(testPlaidTransactionId);
        when(plaidTransaction.getAccountId()).thenReturn(testPlaidAccountId);
        when(plaidTransaction.getAmount()).thenReturn(100.0);
        when(plaidTransaction.getName()).thenReturn("Transaction");
        when(plaidTransaction.getDate()).thenReturn(java.time.LocalDate.now());
        when(plaidTransaction.getCategory()).thenReturn(Collections.singletonList("Food and Drink"));

        // Convert TransactionBase to Transaction for response
        com.plaid.client.model.Transaction transaction = new com.plaid.client.model.Transaction();
        transaction.setTransactionId(testPlaidTransactionId);
        transaction.setAccountId(testPlaidAccountId);
        transaction.setAmount(200.0);
        transaction.setName("Updated Transaction");
        transaction.setDate(java.time.LocalDate.now());
        transaction.setCategory(Collections.singletonList("Food and Drink"));
        transactionsResponse.setTransactions(Collections.singletonList(transaction));
        transactionsResponse.setTotalTransactions(1);

        when(plaidService.getTransactions(anyString(), any(), any())).thenReturn(transactionsResponse);
        
        // Mock account lookup
        AccountTable account = new AccountTable();
        account.setAccountId(UUID.randomUUID().toString());
        account.setPlaidAccountId(testPlaidAccountId);
        when(accountRepository.findByPlaidAccountId(testPlaidAccountId))
                .thenReturn(Optional.of(account));
        when(accountRepository.findByUserId(testUserId)).thenReturn(Collections.singletonList(account));

        // When - Sync transactions multiple times
        plaidSyncService.syncTransactions(testUser, "test-access-token");
        plaidSyncService.syncTransactions(testUser, "test-access-token");
        plaidSyncService.syncTransactions(testUser, "test-access-token");

        // Then - Transaction should only be updated, never duplicated
        verify(transactionRepository, atLeast(3)).findByPlaidTransactionId(testPlaidTransactionId);
        verify(transactionRepository, never()).saveIfPlaidTransactionNotExists(any(TransactionTable.class));
        verify(transactionRepository, atLeastOnce()).save(any(TransactionTable.class));
    }
}

