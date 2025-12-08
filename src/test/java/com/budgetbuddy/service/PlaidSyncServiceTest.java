package com.budgetbuddy.service;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.plaid.PlaidService;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.service.plaid.PlaidSyncOrchestrator;
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

    @Mock
    private com.budgetbuddy.service.PlaidCategoryMapper categoryMapper;

    @Mock
    private com.budgetbuddy.service.plaid.PlaidDataExtractor dataExtractor;

    @Mock
    private PlaidSyncOrchestrator syncOrchestrator;

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

        // Create real services with mocked dependencies so the actual sync logic runs
        com.budgetbuddy.service.plaid.PlaidAccountSyncService accountSyncService = 
            new com.budgetbuddy.service.plaid.PlaidAccountSyncService(
                plaidService, accountRepository, categoryMapper, dataExtractor);
        com.budgetbuddy.service.plaid.PlaidTransactionSyncService transactionSyncService = 
            new com.budgetbuddy.service.plaid.PlaidTransactionSyncService(
                plaidService, accountRepository, transactionRepository, dataExtractor);
        com.budgetbuddy.service.plaid.PlaidSyncOrchestrator realOrchestrator = 
            new com.budgetbuddy.service.plaid.PlaidSyncOrchestrator(accountSyncService, transactionSyncService);
        
        // Use doAnswer to call the real orchestrator methods so repository calls are made
        // Use nullable() for itemId since it can be null
        // Use lenient stubbing to avoid issues with tests that throw exceptions early
        // Use lenient stubbing to avoid unnecessary stubbing warnings for tests that throw exceptions early
        lenient().doAnswer(invocation -> {
            realOrchestrator.syncAccountsOnly(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2));
            return null;
        }).when(syncOrchestrator).syncAccountsOnly(any(UserTable.class), anyString(), nullable(String.class));
        
        lenient().doAnswer(invocation -> {
            realOrchestrator.syncTransactionsOnly(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(syncOrchestrator).syncTransactionsOnly(any(UserTable.class), anyString());
    }

    @Test
    void testSyncAccounts_WithValidData_CreatesNewAccounts() {
        // Given
        AccountsGetResponse accountsResponse = createMockAccountsResponse();
        when(plaidService.getAccounts(testAccessToken)).thenReturn(accountsResponse);
        // Mock dataExtractor to extract account IDs and update accounts
        when(dataExtractor.extractAccountId(any())).thenAnswer(invocation -> {
            Object account = invocation.getArgument(0);
            if (account instanceof AccountBase) {
                return ((AccountBase) account).getAccountId();
            }
            return null;
        });
        // Mock updateAccountFromPlaid to actually set updatedAt
        doAnswer(invocation -> {
            AccountTable account = invocation.getArgument(0);
            account.setUpdatedAt(java.time.Instant.now());
            return null;
        }).when(dataExtractor).updateAccountFromPlaid(any(AccountTable.class), any());
        when(accountRepository.findByPlaidAccountId(anyString())).thenReturn(Optional.empty());
        when(accountRepository.saveIfNotExists(any(AccountTable.class))).thenReturn(true);

        // When
        assertDoesNotThrow(() -> plaidSyncService.syncAccounts(testUser, testAccessToken, null));

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
        // Mock dataExtractor to extract account IDs and update accounts
        when(dataExtractor.extractAccountId(any())).thenAnswer(invocation -> {
            Object account = invocation.getArgument(0);
            if (account instanceof AccountBase) {
                return ((AccountBase) account).getAccountId();
            }
            return null;
        });
        // Mock updateAccountFromPlaid to actually set updatedAt
        doAnswer(invocation -> {
            AccountTable account = invocation.getArgument(0);
            account.setUpdatedAt(java.time.Instant.now());
            return null;
        }).when(dataExtractor).updateAccountFromPlaid(any(AccountTable.class), any());
        when(accountRepository.findByPlaidAccountId("plaid-account-1")).thenReturn(Optional.of(existingAccount));
        doNothing().when(accountRepository).save(any(AccountTable.class));

        // When
        assertDoesNotThrow(() -> plaidSyncService.syncAccounts(testUser, testAccessToken, null));

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
        // Mock dataExtractor to extract account IDs and update accounts
        when(dataExtractor.extractAccountId(any())).thenAnswer(invocation -> {
            Object account = invocation.getArgument(0);
            if (account instanceof AccountBase) {
                return ((AccountBase) account).getAccountId();
            }
            return null;
        });
        // Mock updateAccountFromPlaid to actually set updatedAt
        doAnswer(invocation -> {
            AccountTable account = invocation.getArgument(0);
            account.setUpdatedAt(java.time.Instant.now());
            return null;
        }).when(dataExtractor).updateAccountFromPlaid(any(AccountTable.class), any());
        when(accountRepository.findByPlaidAccountId(anyString())).thenReturn(Optional.empty());
        when(accountRepository.saveIfNotExists(any(AccountTable.class))).thenReturn(true);

        // When
        plaidSyncService.syncAccounts(testUser, testAccessToken, null);

        // Then
        ArgumentCaptor<AccountTable> accountCaptor = ArgumentCaptor.forClass(AccountTable.class);
        verify(accountRepository, atLeastOnce()).saveIfNotExists(accountCaptor.capture());
        AccountTable savedAccount = accountCaptor.getValue();
        assertTrue(savedAccount.getActive(), "New accounts should have active = true");
        assertNotNull(savedAccount.getCreatedAt(), "CreatedAt should be set");
    }

    @Test
    void testSyncAccounts_WithNullUser_ThrowsException() {
        // When/Then - Exception thrown before orchestrator is called, so no stubbing needed
        AppException exception = assertThrows(AppException.class, () -> {
            plaidSyncService.syncAccounts(null, testAccessToken, null);
        });
        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testSyncAccounts_WithNullAccessToken_ThrowsException() {
        // When/Then - Exception thrown before orchestrator is called, so no stubbing needed
        AppException exception = assertThrows(AppException.class, () -> {
            plaidSyncService.syncAccounts(testUser, null, null);
        });
        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testSyncAccounts_WithEmptyAccessToken_ThrowsException() {
        // When/Then - Exception thrown before orchestrator is called, so no stubbing needed
        AppException exception = assertThrows(AppException.class, () -> {
            plaidSyncService.syncAccounts(testUser, "", null);
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
        assertDoesNotThrow(() -> plaidSyncService.syncAccounts(testUser, testAccessToken, null));
        verify(accountRepository, never()).save(any());
        verify(accountRepository, never()).saveIfNotExists(any());
    }

    @Test
    void testSyncTransactions_WithValidData_CreatesTransactions() {
        // Given
        // First, create a test account (required for per-account sync)
        AccountTable testAccount = new AccountTable();
        testAccount.setAccountId(UUID.randomUUID().toString());
        testAccount.setUserId(testUser.getUserId());
        testAccount.setPlaidAccountId("plaid-account-1");
        testAccount.setLastSyncedAt(null); // First sync - ensure sync isn't skipped
        testAccount.setActive(true);
        
        TransactionsGetResponse transactionsResponse = createMockTransactionsResponse();
        when(accountRepository.findByUserId(testUser.getUserId()))
                .thenReturn(Collections.singletonList(testAccount));
        when(plaidService.getTransactions(eq(testAccessToken), anyString(), anyString()))
                .thenReturn(transactionsResponse);
        // Mock dataExtractor to return account ID for transaction grouping and transaction ID
        when(dataExtractor.extractAccountIdFromTransaction(any()))
                .thenReturn("plaid-account-1");
        when(dataExtractor.extractTransactionId(any()))
                .thenAnswer(invocation -> {
                    Object transaction = invocation.getArgument(0);
                    if (transaction instanceof com.plaid.client.model.Transaction) {
                        return ((com.plaid.client.model.Transaction) transaction).getTransactionId();
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
        verify(plaidService, atLeastOnce()).getTransactions(eq(testAccessToken), anyString(), anyString());
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
        transaction1.setAccountId("plaid-account-1"); // CRITICAL: Set accountId for grouping
        transaction1.setAmount(100.0);
        transaction1.setDate(java.time.LocalDate.now());
        transaction1.setName("Test Transaction 1");
        transaction1.setMerchantName("Test Merchant");
        transactions.add(transaction1);

        Transaction transaction2 = new Transaction();
        transaction2.setTransactionId("plaid-transaction-2");
        transaction2.setAccountId("plaid-account-1"); // CRITICAL: Set accountId for grouping
        transaction2.setAmount(-50.0);
        transaction2.setDate(java.time.LocalDate.now().minusDays(1));
        transaction2.setName("Test Transaction 2");
        transactions.add(transaction2);

        response.setTransactions(transactions);
        response.setTotalTransactions(transactions.size());
        return response;
    }
}

