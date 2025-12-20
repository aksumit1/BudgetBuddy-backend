package com.budgetbuddy.service;

import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.plaid.PlaidService;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.plaid.client.model.AccountBase;
import com.plaid.client.model.AccountBalance;
import com.plaid.client.model.AccountType;
import com.plaid.client.model.AccountsGetResponse;
import com.plaid.client.model.AccountSubtype;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

/**
 * Tests for PlaidSyncService account number extraction and deduplication
 */
@ExtendWith(MockitoExtension.class)
class PlaidSyncServiceAccountNumberTest {

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
    private com.budgetbuddy.service.plaid.PlaidSyncOrchestrator syncOrchestrator;

    @InjectMocks
    private PlaidSyncService plaidSyncService;

    private UserTable testUser;
    private AccountBase mockPlaidAccount;

    @BeforeEach
    void setUp() {
        testUser = new UserTable();
        testUser.setUserId(UUID.randomUUID().toString());
        testUser.setEmail("test@example.com");

        // Create mock Plaid account with account number (mask)
        mockPlaidAccount = mock(AccountBase.class);
        // Use lenient stubbing to avoid unnecessary stubbing warnings
        lenient().when(mockPlaidAccount.getAccountId()).thenReturn("plaid-account-123");
        lenient().when(mockPlaidAccount.getName()).thenReturn("Checking Account");
        lenient().when(mockPlaidAccount.getOfficialName()).thenReturn(null);
        lenient().when(mockPlaidAccount.getMask()).thenReturn("1234"); // Account number/mask
        lenient().when(mockPlaidAccount.getType()).thenReturn(AccountType.DEPOSITORY);
        lenient().when(mockPlaidAccount.getSubtype()).thenReturn(AccountSubtype.CHECKING);
        
        AccountBalance balance = new AccountBalance();
        balance.setAvailable(1000.0);
        balance.setCurrent(1000.0);
        balance.setIsoCurrencyCode("USD");
        lenient().when(mockPlaidAccount.getBalances()).thenReturn(balance);

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
    void testSyncAccounts_ExtractsAndStoresAccountNumber() {
        // Given - No existing account, Plaid returns account with mask
        AccountsGetResponse accountsResponse = new AccountsGetResponse();
        accountsResponse.setAccounts(Collections.singletonList(mockPlaidAccount));
        
        // Create Item with institution name
        com.plaid.client.model.Item item = new com.plaid.client.model.Item();
        item.setInstitutionId("ins_test_bank");
        accountsResponse.setItem(item);

        when(plaidService.getAccounts(anyString())).thenReturn(accountsResponse);
        // Mock dataExtractor to extract account IDs and update accounts
        when(dataExtractor.extractAccountId(any())).thenAnswer(invocation -> {
            Object account = invocation.getArgument(0);
            if (account instanceof AccountBase) {
                return ((AccountBase) account).getAccountId();
            }
            return null;
        });
        // Mock updateAccountFromPlaid to actually set accountNumber from mask
        doAnswer(invocation -> {
            AccountTable account = invocation.getArgument(0);
            Object plaidAccount = invocation.getArgument(1);
            if (plaidAccount instanceof AccountBase) {
                AccountBase accountBase = (AccountBase) plaidAccount;
                String mask = accountBase.getMask();
                if (mask != null && !mask.isEmpty()) {
                    account.setAccountNumber(mask);
                }
                account.setUpdatedAt(java.time.Instant.now());
            }
            return null;
        }).when(dataExtractor).updateAccountFromPlaid(any(AccountTable.class), any());
        // OPTIMIZATION: Service now loads all accounts once via findByUserId instead of per-account queries
        when(accountRepository.findByUserId(testUser.getUserId())).thenReturn(Collections.emptyList());
        when(accountRepository.saveIfNotExists(any(AccountTable.class))).thenReturn(true);

        // When - Sync accounts
        plaidSyncService.syncAccounts(testUser, "test-access-token", null);

        // Then - Account number should be extracted and stored
        verify(accountRepository).saveIfNotExists(argThat(account -> {
            return "1234".equals(account.getAccountNumber());
        }));
    }

    @Test
    void testSyncAccounts_WithAccountNumber_DeduplicatesByAccountNumber() {
        // Given - Existing account with same account number and institution
        String accountNumber = "1234";
        String institutionName = "ins_test_bank"; // Use institution ID format
        String plaidAccountId = "plaid-account-123";
        
        AccountTable existingAccount = new AccountTable();
        existingAccount.setAccountId(UUID.randomUUID().toString());
        existingAccount.setUserId(testUser.getUserId());
        existingAccount.setAccountNumber(accountNumber);
        existingAccount.setInstitutionName(institutionName);
        existingAccount.setPlaidAccountId(null); // Missing Plaid ID (old account)
        
        AccountsGetResponse accountsResponse = new AccountsGetResponse();
        accountsResponse.setAccounts(Collections.singletonList(mockPlaidAccount));
        
        // Create Item with institution name matching existing account
        com.plaid.client.model.Item item = new com.plaid.client.model.Item();
        item.setInstitutionId(institutionName);
        accountsResponse.setItem(item);

        when(plaidService.getAccounts(anyString())).thenReturn(accountsResponse);
        // Mock dataExtractor to extract account IDs and update accounts
        when(dataExtractor.extractAccountId(any())).thenAnswer(invocation -> {
            Object account = invocation.getArgument(0);
            if (account instanceof AccountBase) {
                return ((AccountBase) account).getAccountId();
            }
            return null;
        });
        // Mock updateAccountFromPlaid to actually set accountNumber from mask
        doAnswer(invocation -> {
            AccountTable account = invocation.getArgument(0);
            Object plaidAccount = invocation.getArgument(1);
            if (plaidAccount instanceof AccountBase) {
                AccountBase accountBase = (AccountBase) plaidAccount;
                String mask = accountBase.getMask();
                if (mask != null && !mask.isEmpty()) {
                    account.setAccountNumber(mask);
                }
                account.setUpdatedAt(java.time.Instant.now());
            }
            return null;
        }).when(dataExtractor).updateAccountFromPlaid(any(AccountTable.class), any());
        // OPTIMIZATION: Service now loads all accounts once via findByUserId instead of per-account queries
        // The existing account should be in the list returned by findByUserId
        when(accountRepository.findByUserId(testUser.getUserId())).thenReturn(Collections.singletonList(existingAccount));
        doNothing().when(accountRepository).save(any(AccountTable.class));

        // When - Sync accounts
        plaidSyncService.syncAccounts(testUser, "test-access-token", null);

        // Then - Should update existing account instead of creating duplicate
        // Account may be saved multiple times: once for plaidAccountId, once for institutionName, and once for other updates
        verify(accountRepository, never()).saveIfNotExists(any(AccountTable.class));
        verify(accountRepository, atLeastOnce()).save(argThat(account -> 
                account.getAccountId().equals(existingAccount.getAccountId()) &&
                accountNumber.equals(account.getAccountNumber())
        ));
    }

    @Test
    void testSyncAccounts_WithAccountNumber_UpdatesAccountNumberIfMissing() {
        // Given - Existing account without account number
        String plaidAccountId = "plaid-account-123";
        
        AccountTable existingAccount = new AccountTable();
        existingAccount.setAccountId(UUID.randomUUID().toString());
        existingAccount.setUserId(testUser.getUserId());
        existingAccount.setPlaidAccountId(plaidAccountId);
        existingAccount.setAccountNumber(null); // Missing account number
        
        AccountsGetResponse accountsResponse = new AccountsGetResponse();
        accountsResponse.setAccounts(Collections.singletonList(mockPlaidAccount));
        
        // Create Item with institution ID
        com.plaid.client.model.Item item = new com.plaid.client.model.Item();
        item.setInstitutionId("ins_test_bank");
        accountsResponse.setItem(item);

        when(plaidService.getAccounts(anyString())).thenReturn(accountsResponse);
        // Mock dataExtractor to extract account IDs and update accounts
        when(dataExtractor.extractAccountId(any())).thenAnswer(invocation -> {
            Object account = invocation.getArgument(0);
            if (account instanceof AccountBase) {
                return ((AccountBase) account).getAccountId();
            }
            return null;
        });
        // Mock updateAccountFromPlaid to actually set accountNumber from mask
        doAnswer(invocation -> {
            AccountTable account = invocation.getArgument(0);
            Object plaidAccount = invocation.getArgument(1);
            if (plaidAccount instanceof AccountBase) {
                AccountBase accountBase = (AccountBase) plaidAccount;
                String mask = accountBase.getMask();
                if (mask != null && !mask.isEmpty()) {
                    account.setAccountNumber(mask);
                }
                account.setUpdatedAt(java.time.Instant.now());
            }
            return null;
        }).when(dataExtractor).updateAccountFromPlaid(any(AccountTable.class), any());
        // OPTIMIZATION: Service now loads all accounts once via findByUserId instead of per-account queries
        // The existing account should be in the list returned by findByUserId
        when(accountRepository.findByUserId(testUser.getUserId())).thenReturn(Collections.singletonList(existingAccount));
        doNothing().when(accountRepository).save(any(AccountTable.class));

        // When - Sync accounts
        plaidSyncService.syncAccounts(testUser, "test-access-token", null);

        // Then - Account number should be updated
        verify(accountRepository).save(argThat(account -> 
                "1234".equals(account.getAccountNumber())
        ));
    }
}

