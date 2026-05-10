package com.budgetbuddy.service.plaid;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.plaid.PlaidService;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.service.PlaidCategoryMapper;
import com.plaid.client.model.AccountBalance;
import com.plaid.client.model.AccountBase;
import com.plaid.client.model.AccountsGetResponse;
import com.plaid.client.model.Item;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** Unit Tests for PlaidAccountSyncService Tests account synchronization logic */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PlaidAccountSyncServiceTest {

    @Mock private PlaidService plaidService;

    @Mock private AccountRepository accountRepository;

    @Mock private PlaidCategoryMapper categoryMapper;

    private PlaidDataExtractor dataExtractor;
    private PlaidAccountSyncService plaidAccountSyncService;

    private UserTable testUser;
    private String testAccessToken;
    private String testUserId;
    private String testItemId;

    @BeforeEach
    void setUp() {
        testUserId = UUID.randomUUID().toString();
        testUser = new UserTable();
        testUser.setUserId(testUserId);
        testUser.setEmail("test@example.com");
        testAccessToken = "test-access-token";
        testItemId = "test-item-id";

        // Create real PlaidDataExtractor instance (can't be mocked due to Spring @Component)
        // Use the same accountRepository mock for both service and extractor
        dataExtractor =
                new PlaidDataExtractor(
                        accountRepository,
                        org.mockito.Mockito.mock(
                                com.budgetbuddy.service.TransactionTypeCategoryService.class));

        // Create PlaidAccountSyncService with real dataExtractor
        plaidAccountSyncService =
                new PlaidAccountSyncService(
                        plaidService,
                        accountRepository,
                        categoryMapper,
                        dataExtractor,
                        org.mockito.Mockito.mock(
                                com.budgetbuddy.service.correctness.BalanceReconciliationService
                                        .class));
    }

    @Test
    void testSyncAccountsWithValidDataCreatesNewAccounts() {
        // Given
        final AccountsGetResponse accountsResponse = createMockAccountsResponse();
        when(plaidService.getAccounts(testAccessToken)).thenReturn(accountsResponse);
        when(accountRepository.findByPlaidItemId(testItemId)).thenReturn(Collections.emptyList());
        // OPTIMIZATION: Service now loads all accounts once via findByUserId instead of per-account
        // queries
        when(accountRepository.findByUserId(testUserId)).thenReturn(Collections.emptyList());
        when(accountRepository.saveIfNotExists(any(AccountTable.class))).thenReturn(true);

        // When
        assertDoesNotThrow(
                () -> plaidAccountSyncService.syncAccounts(testUser, testAccessToken, testItemId));

        // Then
        verify(plaidService, times(1)).getAccounts(testAccessToken);
        // OPTIMIZATION: Verify findByUserId is called once (not per account)
        verify(accountRepository, times(1)).findByUserId(testUserId);
        verify(accountRepository, atLeastOnce()).saveIfNotExists(any(AccountTable.class));
    }

    @Test
    void testSyncAccountsWithExistingAccountUpdatesAccount() {
        // Given
        final AccountsGetResponse accountsResponse = createMockAccountsResponse();
        final AccountTable existingAccount = new AccountTable();
        existingAccount.setAccountId(UUID.randomUUID().toString());
        existingAccount.setUserId(testUserId);
        existingAccount.setPlaidAccountId("plaid-account-1");
        existingAccount.setActive(true);

        when(plaidService.getAccounts(testAccessToken)).thenReturn(accountsResponse);
        when(accountRepository.findByPlaidItemId(testItemId)).thenReturn(Collections.emptyList());
        // OPTIMIZATION: Service now loads all accounts once via findByUserId instead of per-account
        // queries
        // The existing account should be in the list returned by findByUserId
        when(accountRepository.findByUserId(testUserId))
                .thenReturn(Collections.singletonList(existingAccount));
        when(accountRepository.saveWithLock(any(AccountTable.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // When
        assertDoesNotThrow(
                () -> plaidAccountSyncService.syncAccounts(testUser, testAccessToken, testItemId));

        // Then
        // OPTIMIZATION: Verify findByUserId is called once (not per account)
        verify(accountRepository, times(1)).findByUserId(testUserId);
        // Existing accounts are persisted via saveWithLock (optimistic concurrency)
        // — see PlaidAccountSyncService.persistWithConflictRetry. The legacy save()
        // path is reserved for new accounts created via saveIfNotExists.
        verify(accountRepository, atLeastOnce()).saveWithLock(any(AccountTable.class));
        final ArgumentCaptor<AccountTable> accountCaptor = ArgumentCaptor.forClass(AccountTable.class);
        verify(accountRepository, atLeastOnce()).saveWithLock(accountCaptor.capture());
        final AccountTable savedAccount =
                accountCaptor.getAllValues().get(accountCaptor.getAllValues().size() - 1);
        assertTrue(savedAccount.getActive(), "Account should be marked as active");
    }

    @Test
    void testSyncAccountsWithNullUserThrowsException() {
        // When/Then
        final AppException exception =
                assertThrows(
                        AppException.class,
                        () -> {
                            plaidAccountSyncService.syncAccounts(null, testAccessToken, testItemId);
                        });

        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("User cannot be null"));
    }

    @Test
    void testSyncAccountsWithNullAccessTokenThrowsException() {
        // When/Then
        final AppException exception =
                assertThrows(
                        AppException.class,
                        () -> {
                            plaidAccountSyncService.syncAccounts(testUser, null, testItemId);
                        });

        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("Access token cannot be null or empty"));
    }

    @Test
    void testSyncAccountsWithEmptyAccessTokenThrowsException() {
        // When/Then
        final AppException exception =
                assertThrows(
                        AppException.class,
                        () -> {
                            plaidAccountSyncService.syncAccounts(testUser, "", testItemId);
                        });

        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("Access token cannot be null or empty"));
    }

    @Test
    void testSyncAccountsWithExistingPlaidItemIdChecksBeforeApiCall() {
        // Given
        final AccountTable existingAccount = new AccountTable();
        existingAccount.setPlaidItemId(testItemId);
        existingAccount.setPlaidAccountId("plaid-account-1");
        existingAccount.setUserId(testUserId);
        when(accountRepository.findByPlaidItemId(testItemId)).thenReturn(List.of(existingAccount));
        final AccountsGetResponse accountsResponse = createMockAccountsResponse();
        when(plaidService.getAccounts(testAccessToken)).thenReturn(accountsResponse);
        // OPTIMIZATION: Service now loads all accounts once via findByUserId instead of per-account
        // queries
        when(accountRepository.findByUserId(testUserId)).thenReturn(List.of(existingAccount));
        doNothing().when(accountRepository).save(any(AccountTable.class));

        // When
        assertDoesNotThrow(
                () -> plaidAccountSyncService.syncAccounts(testUser, testAccessToken, testItemId));

        // Then
        verify(accountRepository, times(1)).findByPlaidItemId(testItemId);
        verify(plaidService, times(1)).getAccounts(testAccessToken);
        // OPTIMIZATION: Verify findByUserId is called once (not per account)
        verify(accountRepository, times(1)).findByUserId(testUserId);
    }

    private AccountsGetResponse createMockAccountsResponse() {
        final AccountsGetResponse response = new AccountsGetResponse();

        final AccountBase account = new AccountBase();
        account.setAccountId("plaid-account-1");
        account.setName("Test Account");
        account.setOfficialName("Test Account Official");
        account.setMask("1234");
        // Note: TypeEnum and SubtypeEnum may not exist in the Plaid SDK version
        // Using reflection or setting directly if available
        try {
            account.getClass().getMethod("setType", com.plaid.client.model.AccountType.class);
            // If method exists, we can set it, otherwise skip
        } catch (NoSuchMethodException e) {
            // Type enum not available in this SDK version - that's OK
        }

        final AccountBalance balance = new AccountBalance();
        balance.setAvailable(1000.0);
        balance.setCurrent(1000.0);
        balance.setIsoCurrencyCode("USD");
        account.setBalances(balance);

        response.setAccounts(Collections.singletonList(account));

        final Item item = new Item();
        item.setItemId("test-item-id");
        item.setInstitutionId("test-bank");
        response.setItem(item);

        return response;
    }
}
