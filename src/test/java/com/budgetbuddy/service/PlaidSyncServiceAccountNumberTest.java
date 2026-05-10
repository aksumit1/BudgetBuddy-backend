package com.budgetbuddy.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.nullable;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.plaid.PlaidService;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.plaid.client.model.AccountBalance;
import com.plaid.client.model.AccountBase;
import com.plaid.client.model.AccountSubtype;
import com.plaid.client.model.AccountType;
import com.plaid.client.model.AccountsGetResponse;
import java.util.Collections;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Tests for PlaidSyncService account number extraction and deduplication */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class PlaidSyncServiceAccountNumberTest {

    @Mock private PlaidService plaidService;

    @Mock private AccountRepository accountRepository;

    @Mock private TransactionRepository transactionRepository;

    @Mock private com.budgetbuddy.service.PlaidCategoryMapper categoryMapper;

    @Mock private com.budgetbuddy.service.plaid.PlaidDataExtractor dataExtractor;

    @Mock private com.budgetbuddy.service.plaid.PlaidSyncOrchestrator syncOrchestrator;

    @InjectMocks private PlaidSyncService plaidSyncService;

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

        final AccountBalance balance = new AccountBalance();
        balance.setAvailable(1000.0);
        balance.setCurrent(1000.0);
        balance.setIsoCurrencyCode("USD");
        lenient().when(mockPlaidAccount.getBalances()).thenReturn(balance);

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
    void testSyncAccountsExtractsAndStoresAccountNumber() {
        // Given - No existing account, Plaid returns account with mask
        final AccountsGetResponse accountsResponse = new AccountsGetResponse();
        accountsResponse.setAccounts(Collections.singletonList(mockPlaidAccount));

        // Create Item with institution name
        final com.plaid.client.model.Item item = new com.plaid.client.model.Item();
        item.setInstitutionId("ins_test_bank");
        accountsResponse.setItem(item);

        when(plaidService.getAccounts(anyString())).thenReturn(accountsResponse);
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
        // Mock updateAccountFromPlaid to actually set accountNumber from mask
        doAnswer(
                        invocation -> {
                            final AccountTable account = invocation.getArgument(0);
                            final Object plaidAccount = invocation.getArgument(1);
                            if (plaidAccount instanceof AccountBase) {
                                final AccountBase accountBase = (AccountBase) plaidAccount;
                                final String mask = accountBase.getMask();
                                if (mask != null && !mask.isEmpty()) {
                                    account.setAccountNumber(mask);
                                }
                                account.setUpdatedAt(java.time.Instant.now());
                            }
                            return null;
                        })
                .when(dataExtractor)
                .updateAccountFromPlaid(any(AccountTable.class), any());
        // OPTIMIZATION: Service now loads all accounts once via findByUserId instead of per-account
        // queries
        when(accountRepository.findByUserId(testUser.getUserId()))
                .thenReturn(Collections.emptyList());
        when(accountRepository.saveIfNotExists(any(AccountTable.class))).thenReturn(true);

        // When - Sync accounts
        plaidSyncService.syncAccounts(testUser, "test-access-token", null);

        // Then - Account number should be extracted and stored
        verify(accountRepository)
                .saveIfNotExists(
                        argThat(
                                account -> {
                                    return "1234".equals(account.getAccountNumber());
                                }));
    }

    @Test
    void testSyncAccountsWithAccountNumberDeduplicatesByAccountNumber() {
        // Given - Existing account with same account number and institution
        final String accountNumber = "1234";
        final String institutionName = "ins_test_bank"; // Use institution ID format
        final String plaidAccountId = "plaid-account-123";

        final AccountTable existingAccount = new AccountTable();
        existingAccount.setAccountId(UUID.randomUUID().toString());
        existingAccount.setUserId(testUser.getUserId());
        existingAccount.setAccountNumber(accountNumber);
        existingAccount.setInstitutionName(institutionName);
        existingAccount.setPlaidAccountId(null); // Missing Plaid ID (old account)

        final AccountsGetResponse accountsResponse = new AccountsGetResponse();
        accountsResponse.setAccounts(Collections.singletonList(mockPlaidAccount));

        // Create Item with institution name matching existing account
        final com.plaid.client.model.Item item = new com.plaid.client.model.Item();
        item.setInstitutionId(institutionName);
        accountsResponse.setItem(item);

        when(plaidService.getAccounts(anyString())).thenReturn(accountsResponse);
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
        // Mock updateAccountFromPlaid to actually set accountNumber from mask
        doAnswer(
                        invocation -> {
                            final AccountTable account = invocation.getArgument(0);
                            final Object plaidAccount = invocation.getArgument(1);
                            if (plaidAccount instanceof AccountBase) {
                                final AccountBase accountBase = (AccountBase) plaidAccount;
                                final String mask = accountBase.getMask();
                                if (mask != null && !mask.isEmpty()) {
                                    account.setAccountNumber(mask);
                                }
                                account.setUpdatedAt(java.time.Instant.now());
                            }
                            return null;
                        })
                .when(dataExtractor)
                .updateAccountFromPlaid(any(AccountTable.class), any());
        // OPTIMIZATION: Service now loads all accounts once via findByUserId instead of per-account
        // queries
        // The existing account should be in the list returned by findByUserId
        when(accountRepository.findByUserId(testUser.getUserId()))
                .thenReturn(Collections.singletonList(existingAccount));
        doNothing().when(accountRepository).save(any(AccountTable.class));

        // When - Sync accounts
        plaidSyncService.syncAccounts(testUser, "test-access-token", null);

        // Then - Should update existing account instead of creating duplicate
        // Account may be saved multiple times: once for plaidAccountId, once for institutionName,
        // and once for other updates
        verify(accountRepository, never()).saveIfNotExists(any(AccountTable.class));
        verify(accountRepository, atLeastOnce())
                .saveWithLock(
                        argThat(
                                account ->
                                        account.getAccountId()
                                                        .equals(existingAccount.getAccountId())
                                                && accountNumber.equals(
                                                        account.getAccountNumber())));
    }

    @Test
    void testSyncAccountsWithAccountNumberUpdatesAccountNumberIfMissing() {
        // Given - Existing account without account number
        final String plaidAccountId = "plaid-account-123";

        final AccountTable existingAccount = new AccountTable();
        existingAccount.setAccountId(UUID.randomUUID().toString());
        existingAccount.setUserId(testUser.getUserId());
        existingAccount.setPlaidAccountId(plaidAccountId);
        existingAccount.setAccountNumber(null); // Missing account number

        final AccountsGetResponse accountsResponse = new AccountsGetResponse();
        accountsResponse.setAccounts(Collections.singletonList(mockPlaidAccount));

        // Create Item with institution ID
        final com.plaid.client.model.Item item = new com.plaid.client.model.Item();
        item.setInstitutionId("ins_test_bank");
        accountsResponse.setItem(item);

        when(plaidService.getAccounts(anyString())).thenReturn(accountsResponse);
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
        // Mock updateAccountFromPlaid to actually set accountNumber from mask
        doAnswer(
                        invocation -> {
                            final AccountTable account = invocation.getArgument(0);
                            final Object plaidAccount = invocation.getArgument(1);
                            if (plaidAccount instanceof AccountBase) {
                                final AccountBase accountBase = (AccountBase) plaidAccount;
                                final String mask = accountBase.getMask();
                                if (mask != null && !mask.isEmpty()) {
                                    account.setAccountNumber(mask);
                                }
                                account.setUpdatedAt(java.time.Instant.now());
                            }
                            return null;
                        })
                .when(dataExtractor)
                .updateAccountFromPlaid(any(AccountTable.class), any());
        // OPTIMIZATION: Service now loads all accounts once via findByUserId instead of per-account
        // queries
        // The existing account should be in the list returned by findByUserId
        when(accountRepository.findByUserId(testUser.getUserId()))
                .thenReturn(Collections.singletonList(existingAccount));
        doNothing().when(accountRepository).save(any(AccountTable.class));

        // When - Sync accounts
        plaidSyncService.syncAccounts(testUser, "test-access-token", null);

        // Then - Account number should be updated
        verify(accountRepository)
                .saveWithLock(argThat(account -> "1234".equals(account.getAccountNumber())));
    }
}
