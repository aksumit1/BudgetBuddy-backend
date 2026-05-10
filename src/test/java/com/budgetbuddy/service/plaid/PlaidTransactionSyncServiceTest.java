package com.budgetbuddy.service.plaid;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
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
import com.budgetbuddy.service.PlaidCategoryMapper;
import com.plaid.client.model.Transaction;
import com.plaid.client.model.TransactionsGetResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** Unit Tests for PlaidTransactionSyncService Tests transaction synchronization logic */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PlaidTransactionSyncServiceTest {

    @Mock private PlaidService plaidService;

    @Mock private AccountRepository accountRepository;

    @Mock private TransactionRepository transactionRepository;

    @Mock private PlaidCategoryMapper categoryMapper;

    private PlaidDataExtractor dataExtractor;
    private PlaidTransactionSyncService transactionSyncService;

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

        // Create real PlaidDataExtractor instance (can't be mocked due to Spring @Component)
        dataExtractor =
                new PlaidDataExtractor(
                        accountRepository,
                        org.mockito.Mockito.mock(
                                com.budgetbuddy.service.TransactionTypeCategoryService.class));

        // Create PlaidTransactionSyncService with real dataExtractor
        // Note: PlaidTransactionSyncService constructor doesn't include PlaidCategoryMapper
        transactionSyncService =
                new PlaidTransactionSyncService(
                        plaidService, accountRepository, transactionRepository, dataExtractor);
    }

    @Test
    void testSyncTransactionsWithValidDataCreatesTransactions() {
        // Given
        final AccountTable testAccount = new AccountTable();
        testAccount.setAccountId(UUID.randomUUID().toString());
        testAccount.setUserId(testUserId);
        testAccount.setPlaidAccountId("plaid-account-1");
        testAccount.setLastSyncedAt(null); // First sync

        final TransactionsGetResponse transactionsResponse = createMockTransactionsResponse();
        when(accountRepository.findByUserId(testUserId))
                .thenReturn(Collections.singletonList(testAccount));
        when(plaidService.getTransactions(eq(testAccessToken), anyString(), anyString()))
                .thenReturn(transactionsResponse);
        when(transactionRepository.findByPlaidTransactionId(anyString()))
                .thenReturn(Optional.empty());
        when(transactionRepository.saveIfPlaidTransactionNotExists(any(TransactionTable.class)))
                .thenReturn(true);

        // Mock category mapper to return a valid CategoryMapping
        final PlaidCategoryMapper.CategoryMapping mockMapping =
                new PlaidCategoryMapper.CategoryMapping("other", "other", false);
        when(categoryMapper.mapPlaidCategory(any(), any(), any(), any(), any(), any()))
                .thenReturn(mockMapping);
        when(categoryMapper.mapPlaidCategory(any(), any(), any(), any())).thenReturn(mockMapping);

        // When
        assertDoesNotThrow(
                () -> transactionSyncService.syncTransactions(testUser, testAccessToken));

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
                            transactionSyncService.syncTransactions(null, testAccessToken);
                        });

        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("User cannot be null"));
    }

    @Test
    void testSyncTransactionsWithNullAccessTokenThrowsException() {
        // When/Then
        final AppException exception =
                assertThrows(
                        AppException.class,
                        () -> {
                            transactionSyncService.syncTransactions(testUser, null);
                        });

        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("Access token cannot be null or empty"));
    }

    @Test
    void testSyncTransactionsWithEmptyAccessTokenThrowsException() {
        // When/Then
        final AppException exception =
                assertThrows(
                        AppException.class,
                        () -> {
                            transactionSyncService.syncTransactions(testUser, "");
                        });

        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("Access token cannot be null or empty"));
    }

    @Test
    void testSyncTransactionsWithNoAccountsDoesNotCallPlaid() {
        // Given
        when(accountRepository.findByUserId(testUserId)).thenReturn(Collections.emptyList());

        // When
        assertDoesNotThrow(
                () -> transactionSyncService.syncTransactions(testUser, testAccessToken));

        // Then
        verify(plaidService, never()).getTransactions(anyString(), anyString(), anyString());
    }

    @Test
    void testSyncTransactionsWithRecentlySyncedAccountSkipsSync() {
        // Given
        final AccountTable testAccount = new AccountTable();
        testAccount.setAccountId(UUID.randomUUID().toString());
        testAccount.setUserId(testUserId);
        testAccount.setPlaidAccountId("plaid-account-1");
        testAccount.setLastSyncedAt(Instant.now().minusSeconds(60)); // Synced 1 minute ago

        when(accountRepository.findByUserId(testUserId))
                .thenReturn(Collections.singletonList(testAccount));
        // User HAS transactions — this is the steady-state cooldown case, not post-link recovery.
        final TransactionTable existing = new TransactionTable();
        existing.setTransactionId(UUID.randomUUID().toString());
        when(transactionRepository.findByUserId(eq(testUserId), anyInt(), anyInt()))
                .thenReturn(Collections.singletonList(existing));

        // When
        assertDoesNotThrow(
                () -> transactionSyncService.syncTransactions(testUser, testAccessToken));

        // Then
        verify(plaidService, never()).getTransactions(anyString(), anyString(), anyString());
    }

    /**
     * Regression guard for ska@yahoo.com bug: after Plaid link, accounts get `lastSyncedAt` set
     * immediately, then the iOS-side post-link retry kicks in within seconds. Without this bypass,
     * that retry hits the 5-minute cooldown and silently returns — so transactions never load until
     * the user uninstalls or waits 5 min.
     *
     * <p>The bypass is keyed on "user has zero transactions in DB" — a clean proxy for the
     * post-link recovery window.
     */
    @Test
    void testSyncTransactionsPostLinkRecoveryBypassesCooldownWhenNoTransactionsYet() {
        // Given: an account synced 1 minute ago (would normally be skipped)
        final AccountTable testAccount = new AccountTable();
        testAccount.setAccountId(UUID.randomUUID().toString());
        testAccount.setUserId(testUserId);
        testAccount.setPlaidAccountId("plaid-account-fresh");
        testAccount.setLastSyncedAt(Instant.now().minusSeconds(60));

        when(accountRepository.findByUserId(testUserId))
                .thenReturn(Collections.singletonList(testAccount));
        // User has NO transactions in DB — post-link recovery state.
        when(transactionRepository.findByUserId(eq(testUserId), anyInt(), anyInt()))
                .thenReturn(Collections.emptyList());

        // Plaid returns transactions when actually called.
        final TransactionsGetResponse plaidResponse = createMockTransactionsResponse();
        when(plaidService.getTransactions(eq(testAccessToken), anyString(), anyString()))
                .thenReturn(plaidResponse);
        when(transactionRepository.findByPlaidTransactionId(anyString()))
                .thenReturn(Optional.empty());
        when(transactionRepository.saveIfPlaidTransactionNotExists(any(TransactionTable.class)))
                .thenReturn(true);

        // When
        assertDoesNotThrow(
                () -> transactionSyncService.syncTransactions(testUser, testAccessToken));

        // Then: cooldown was bypassed and Plaid was actually queried
        verify(plaidService, atLeastOnce())
                .getTransactions(eq(testAccessToken), anyString(), anyString());
    }

    private TransactionsGetResponse createMockTransactionsResponse() {
        final TransactionsGetResponse response = new TransactionsGetResponse();

        final Transaction transaction = new Transaction();
        transaction.setTransactionId("plaid-txn-1");
        transaction.setAccountId("plaid-account-1");
        transaction.setAmount(100.0);
        transaction.setName("Test Transaction");
        transaction.setDate(LocalDate.now());
        transaction.setIsoCurrencyCode("USD");

        response.setTransactions(Collections.singletonList(transaction));
        response.setTotalTransactions(1);

        return response;
    }
}
