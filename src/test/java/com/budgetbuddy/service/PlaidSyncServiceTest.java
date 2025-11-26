package com.budgetbuddy.service;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
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

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit Tests for PlaidSyncService
 * Tests account and transaction synchronization logic
 */
@ExtendWith(MockitoExtension.class)
class PlaidSyncServiceTest {

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
    void testSyncAccounts_WithValidData_CreatesNewAccounts() {
        // Given
        AccountsGetResponse accountsResponse = createMockAccountsResponse();
        when(plaidService.getAccounts(testAccessToken)).thenReturn(accountsResponse);
        when(accountRepository.findByPlaidAccountId(anyString())).thenReturn(Optional.empty());
        when(accountRepository.saveIfNotExists(any(AccountTable.class))).thenReturn(true);

        // When
        assertDoesNotThrow(() -> plaidSyncService.syncAccounts(testUser, testAccessToken));

        // Then
        verify(plaidService, times(1)).getAccounts(testAccessToken);
        verify(accountRepository, atLeastOnce()).saveIfNotExists(any(AccountTable.class));
    }

    @Test
    void testSyncAccounts_WithExistingAccount_UpdatesAccount() {
        // Given
        AccountsGetResponse accountsResponse = createMockAccountsResponse();
        AccountTable existingAccount = new AccountTable();
        existingAccount.setAccountId(UUID.randomUUID().toString());
        existingAccount.setUserId(testUserId);
        existingAccount.setPlaidAccountId("plaid-account-1");
        existingAccount.setActive(true);

        when(plaidService.getAccounts(testAccessToken)).thenReturn(accountsResponse);
        when(accountRepository.findByPlaidAccountId("plaid-account-1")).thenReturn(Optional.of(existingAccount));

        // When
        assertDoesNotThrow(() -> plaidSyncService.syncAccounts(testUser, testAccessToken));

        // Then
        verify(accountRepository, times(1)).save(existingAccount);
        ArgumentCaptor<AccountTable> accountCaptor = ArgumentCaptor.forClass(AccountTable.class);
        verify(accountRepository).save(accountCaptor.capture());
        AccountTable savedAccount = accountCaptor.getValue();
        assertTrue(savedAccount.getActive(), "Account should be marked as active");
        assertNotNull(savedAccount.getUpdatedAt(), "UpdatedAt should be set");
    }

    @Test
    void testSyncAccounts_SetsActiveToTrue() {
        // Given
        AccountsGetResponse accountsResponse = createMockAccountsResponse();
        when(plaidService.getAccounts(testAccessToken)).thenReturn(accountsResponse);
        when(accountRepository.findByPlaidAccountId(anyString())).thenReturn(Optional.empty());
        when(accountRepository.saveIfNotExists(any(AccountTable.class))).thenReturn(true);

        // When
        plaidSyncService.syncAccounts(testUser, testAccessToken);

        // Then
        ArgumentCaptor<AccountTable> accountCaptor = ArgumentCaptor.forClass(AccountTable.class);
        verify(accountRepository, atLeastOnce()).saveIfNotExists(accountCaptor.capture());
        AccountTable savedAccount = accountCaptor.getValue();
        assertTrue(savedAccount.getActive(), "New accounts should have active = true");
        assertNotNull(savedAccount.getCreatedAt(), "CreatedAt should be set");
    }

    @Test
    void testSyncAccounts_WithNullUser_ThrowsException() {
        // When/Then
        AppException exception = assertThrows(AppException.class, () -> {
            plaidSyncService.syncAccounts(null, testAccessToken);
        });
        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testSyncAccounts_WithNullAccessToken_ThrowsException() {
        // When/Then
        AppException exception = assertThrows(AppException.class, () -> {
            plaidSyncService.syncAccounts(testUser, null);
        });
        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testSyncAccounts_WithEmptyAccessToken_ThrowsException() {
        // When/Then
        AppException exception = assertThrows(AppException.class, () -> {
            plaidSyncService.syncAccounts(testUser, "");
        });
        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testSyncAccounts_WithNoAccountsFromPlaid_DoesNotThrow() {
        // Given
        AccountsGetResponse accountsResponse = new AccountsGetResponse();
        accountsResponse.setAccounts(Collections.emptyList());
        when(plaidService.getAccounts(testAccessToken)).thenReturn(accountsResponse);

        // When/Then
        assertDoesNotThrow(() -> plaidSyncService.syncAccounts(testUser, testAccessToken));
        verify(accountRepository, never()).save(any());
        verify(accountRepository, never()).saveIfNotExists(any());
    }

    @Test
    void testSyncTransactions_WithValidData_CreatesTransactions() {
        // Given
        TransactionsGetResponse transactionsResponse = createMockTransactionsResponse();
        when(plaidService.getTransactions(eq(testAccessToken), anyString(), anyString()))
                .thenReturn(transactionsResponse);
        when(transactionRepository.saveIfPlaidTransactionNotExists(any(TransactionTable.class)))
                .thenReturn(true);

        // When
        assertDoesNotThrow(() -> plaidSyncService.syncTransactions(testUser, testAccessToken));

        // Then
        verify(plaidService, times(1)).getTransactions(eq(testAccessToken), anyString(), anyString());
        verify(transactionRepository, atLeastOnce()).saveIfPlaidTransactionNotExists(any(TransactionTable.class));
    }

    @Test
    void testSyncTransactions_WithNullUser_ThrowsException() {
        // When/Then
        AppException exception = assertThrows(AppException.class, () -> {
            plaidSyncService.syncTransactions(null, testAccessToken);
        });
        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testSyncTransactions_WithNullAccessToken_ThrowsException() {
        // When/Then
        AppException exception = assertThrows(AppException.class, () -> {
            plaidSyncService.syncTransactions(testUser, null);
        });
        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    // Helper methods to create mock Plaid responses
    private AccountsGetResponse createMockAccountsResponse() {
        AccountsGetResponse response = new AccountsGetResponse();
        List<AccountBase> accounts = new ArrayList<>();

        AccountBase account1 = new AccountBase();
        account1.setAccountId("plaid-account-1");
        account1.setName("Test Account 1");
        account1.setOfficialName("Official Test Account 1");
        // Note: Type and Subtype are enums in Plaid SDK - using string representation for testing
        // In real implementation, these would be set via Plaid SDK methods

        AccountBalance balance1 = new AccountBalance();
        balance1.setAvailable(1000.0);
        balance1.setCurrent(1000.0);
        balance1.setIsoCurrencyCode("USD");
        account1.setBalances(balance1);
        accounts.add(account1);

        AccountBase account2 = new AccountBase();
        account2.setAccountId("plaid-account-2");
        account2.setName("Test Account 2");
        // Note: Type and Subtype are enums in Plaid SDK - using string representation for testing

        AccountBalance balance2 = new AccountBalance();
        balance2.setAvailable(5000.0);
        balance2.setCurrent(5000.0);
        balance2.setIsoCurrencyCode("USD");
        account2.setBalances(balance2);
        accounts.add(account2);

        response.setAccounts(accounts);
        return response;
    }

    private TransactionsGetResponse createMockTransactionsResponse() {
        TransactionsGetResponse response = new TransactionsGetResponse();
        List<Transaction> transactions = new ArrayList<>();

        Transaction transaction1 = new Transaction();
        transaction1.setTransactionId("plaid-transaction-1");
        transaction1.setAmount(100.0);
        transaction1.setDate(java.time.LocalDate.now());
        transaction1.setName("Test Transaction 1");
        transaction1.setMerchantName("Test Merchant");
        transactions.add(transaction1);

        Transaction transaction2 = new Transaction();
        transaction2.setTransactionId("plaid-transaction-2");
        transaction2.setAmount(-50.0);
        transaction2.setDate(java.time.LocalDate.now().minusDays(1));
        transaction2.setName("Test Transaction 2");
        transactions.add(transaction2);

        response.setTransactions(transactions);
        response.setTotalTransactions(transactions.size());
        return response;
    }
}

