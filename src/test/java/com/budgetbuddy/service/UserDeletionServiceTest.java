package com.budgetbuddy.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyList;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.budgetbuddy.compliance.AuditLogService;
import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.BudgetTable;
import com.budgetbuddy.model.dynamodb.FIDO2CredentialTable;
import com.budgetbuddy.model.dynamodb.GoalTable;
import com.budgetbuddy.model.dynamodb.SubscriptionTable;
import com.budgetbuddy.model.dynamodb.TransactionActionTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.AnomalyFeedbackRepository;
import com.budgetbuddy.repository.dynamodb.AuditLogRepository;
import com.budgetbuddy.repository.dynamodb.BudgetRepository;
import com.budgetbuddy.repository.dynamodb.CustomMerchantMappingRepository;
import com.budgetbuddy.repository.dynamodb.DeviceTokenRepository;
import com.budgetbuddy.repository.dynamodb.FIDO2CredentialRepository;
import com.budgetbuddy.repository.dynamodb.GoalRepository;
import com.budgetbuddy.repository.dynamodb.NetWorthSnapshotRepository;
import com.budgetbuddy.repository.dynamodb.PlaidAccessTokenRepository;
import com.budgetbuddy.repository.dynamodb.SubscriptionRepository;
import com.budgetbuddy.repository.dynamodb.TransactionActionRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.repository.dynamodb.UserCorrectionRepository;
import com.budgetbuddy.repository.dynamodb.UserPreferencesRepository;
import com.budgetbuddy.repository.dynamodb.UserRepository;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCache;

/**
 * Comprehensive tests for UserDeletionService Tests deletion of all entities, cache invalidation,
 * and edge cases
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class UserDeletionServiceTest {

    @Mock private UserRepository userRepository;

    @Mock private AccountRepository accountRepository;

    @Mock private TransactionRepository transactionRepository;

    @Mock private TransactionActionRepository actionRepository;

    @Mock private SubscriptionRepository subscriptionRepository;

    @Mock private FIDO2CredentialRepository fido2CredentialRepository;

    @Mock private BudgetRepository budgetRepository;

    @Mock private GoalRepository goalRepository;

    @Mock private AuditLogRepository auditLogRepository;

    @Mock private AuditLogService auditLogService;

    @Mock private CacheManager cacheManager;

    @Mock private DeviceTokenRepository deviceTokenRepository;

    @Mock private AnomalyFeedbackRepository anomalyFeedbackRepository;

    @Mock private NetWorthSnapshotRepository netWorthSnapshotRepository;

    @Mock private UserPreferencesRepository userPreferencesRepository;

    @Mock private UserCorrectionRepository userCorrectionRepository;

    @Mock private CustomMerchantMappingRepository customMerchantMappingRepository;

    @Mock private PlaidAccessTokenRepository plaidAccessTokenRepository;

    @InjectMocks private UserDeletionService userDeletionService;

    private String testUserId;
    private ConcurrentMapCache mockCache;

    @BeforeEach
    void setUp() {
        testUserId = "user-123";
        mockCache = new ConcurrentMapCache("testCache");
        // Mock audit log repository methods that are called during deletion
        when(auditLogRepository.findByUserIdAndDateRange(anyString(), anyLong(), anyLong()))
                .thenReturn(new ArrayList<>());
        // Mock audit log service
        doNothing().when(auditLogService).logDataDeletion(anyString());
        doNothing()
                .when(auditLogService)
                .logAction(anyString(), anyString(), anyString(), any(), any(), any(), any());
    }

    // MARK: - Delete All User Data Tests

    @Test
    void testDeleteAllUserDataDeletesAllEntities() {
        // Given
        final List<AccountTable> accounts =
                Arrays.asList(createAccount("acc-1"), createAccount("acc-2"));
        final List<TransactionTable> transactions =
                Arrays.asList(createTransaction("tx-1"), createTransaction("tx-2"));
        final List<TransactionActionTable> actions =
                Arrays.asList(createAction("action-1"), createAction("action-2"));
        final List<SubscriptionTable> subscriptions =
                Arrays.asList(createSubscription("sub-1"), createSubscription("sub-2"));
        final List<BudgetTable> budgets =
                Arrays.asList(createBudget("budget-1"), createBudget("budget-2"));
        final List<GoalTable> goals = Arrays.asList(createGoal("goal-1"), createGoal("goal-2"));

        when(accountRepository.findByUserId(testUserId)).thenReturn(accounts);
        // Mock pagination for transactions (batchSize is 100, matching UserDeletionService)
        when(transactionRepository.findByUserId(testUserId, 0, 100)).thenReturn(transactions);
        when(transactionRepository.findByUserId(testUserId, 2, 100)).thenReturn(new ArrayList<>());
        when(actionRepository.findByUserId(testUserId)).thenReturn(actions);
        when(subscriptionRepository.findByUserId(testUserId)).thenReturn(subscriptions);
        when(budgetRepository.findByUserId(testUserId)).thenReturn(budgets);
        when(goalRepository.findByUserId(testUserId)).thenReturn(goals);

        // Mock batch delete methods (they might throw, so we'll use individual delete as fallback)
        doThrow(new RuntimeException("Batch delete not supported in test"))
                .when(transactionRepository)
                .batchDelete(anyList());
        doThrow(new RuntimeException("Batch delete not supported in test"))
                .when(accountRepository)
                .batchDelete(anyList());

        // Mock account save for Plaid item removal (void method, use doNothing)
        doNothing().when(accountRepository).save(any(AccountTable.class));

        // When
        assertDoesNotThrow(() -> userDeletionService.deleteAllUserData(testUserId));

        // Then
        verify(accountRepository, atLeastOnce()).delete(anyString());
        verify(transactionRepository, atLeastOnce()).delete(anyString());
        verify(actionRepository, times(actions.size())).delete(anyString());
        verify(subscriptionRepository, times(subscriptions.size())).delete(anyString());
        verify(budgetRepository, times(budgets.size())).delete(anyString());
        verify(goalRepository, times(goals.size())).delete(anyString());
    }

    @Test
    void testDeleteAllUserDataDeletesTransactionActions() {
        // Given
        final List<TransactionActionTable> actions =
                Arrays.asList(
                        createAction("action-1"),
                        createAction("action-2"),
                        createAction("action-3"));
        when(actionRepository.findByUserId(testUserId)).thenReturn(actions);
        when(accountRepository.findByUserId(testUserId)).thenReturn(new ArrayList<>());
        // Mock pagination for transactions (batchSize is 100, matching UserDeletionService)
        when(transactionRepository.findByUserId(testUserId, 0, 100)).thenReturn(new ArrayList<>());
        when(subscriptionRepository.findByUserId(testUserId)).thenReturn(new ArrayList<>());
        when(budgetRepository.findByUserId(testUserId)).thenReturn(new ArrayList<>());
        when(goalRepository.findByUserId(testUserId)).thenReturn(new ArrayList<>());
        // Mock batch delete to throw so it falls back to individual delete
        doThrow(new RuntimeException("Batch delete not supported"))
                .when(transactionRepository)
                .batchDelete(anyList());
        doThrow(new RuntimeException("Batch delete not supported"))
                .when(accountRepository)
                .batchDelete(anyList());

        // When
        userDeletionService.deleteAllUserData(testUserId);

        // Then
        verify(actionRepository, times(3)).delete(anyString());
    }

    @Test
    void testDeleteAllUserDataDeletesSubscriptions() {
        // Given
        final List<SubscriptionTable> subscriptions =
                Arrays.asList(createSubscription("sub-1"), createSubscription("sub-2"));
        when(subscriptionRepository.findByUserId(testUserId)).thenReturn(subscriptions);
        when(accountRepository.findByUserId(testUserId)).thenReturn(new ArrayList<>());
        // Mock pagination for transactions (batchSize is 100, matching UserDeletionService)
        when(transactionRepository.findByUserId(testUserId, 0, 100)).thenReturn(new ArrayList<>());
        when(actionRepository.findByUserId(testUserId)).thenReturn(new ArrayList<>());
        when(budgetRepository.findByUserId(testUserId)).thenReturn(new ArrayList<>());
        when(goalRepository.findByUserId(testUserId)).thenReturn(new ArrayList<>());
        // Mock batch delete to throw so it falls back to individual delete
        doThrow(new RuntimeException("Batch delete not supported"))
                .when(transactionRepository)
                .batchDelete(anyList());
        doThrow(new RuntimeException("Batch delete not supported"))
                .when(accountRepository)
                .batchDelete(anyList());

        // When
        userDeletionService.deleteAllUserData(testUserId);

        // Then
        verify(subscriptionRepository, times(2)).delete(anyString());
    }

    @Test
    void testDeleteAllUserDataEvictsCaches() {
        // Given
        when(accountRepository.findByUserId(testUserId)).thenReturn(new ArrayList<>());
        // Mock pagination for transactions (batchSize is 100, matching UserDeletionService)
        when(transactionRepository.findByUserId(testUserId, 0, 100)).thenReturn(new ArrayList<>());
        when(actionRepository.findByUserId(testUserId)).thenReturn(new ArrayList<>());
        when(subscriptionRepository.findByUserId(testUserId)).thenReturn(new ArrayList<>());
        when(budgetRepository.findByUserId(testUserId)).thenReturn(new ArrayList<>());
        when(goalRepository.findByUserId(testUserId)).thenReturn(new ArrayList<>());
        // Mock cache manager
        when(cacheManager.getCache(anyString())).thenReturn(mockCache);
        // Mock batch delete to throw so it falls back to individual delete
        doThrow(new RuntimeException("Batch delete not supported"))
                .when(transactionRepository)
                .batchDelete(anyList());
        doThrow(new RuntimeException("Batch delete not supported"))
                .when(accountRepository)
                .batchDelete(anyList());

        // When
        userDeletionService.deleteAllUserData(testUserId);

        // Then - verify all caches are evicted
        verify(cacheManager, atLeastOnce()).getCache("users");
        verify(cacheManager, atLeastOnce()).getCache("accounts");
        verify(cacheManager, atLeastOnce()).getCache("transactions");
        verify(cacheManager, atLeastOnce()).getCache("transactionActions");
        verify(cacheManager, atLeastOnce()).getCache("subscriptions");
        verify(cacheManager, atLeastOnce()).getCache("budgets");
        verify(cacheManager, atLeastOnce()).getCache("goals");
    }

    @Test
    void testDeleteAllUserDataWithNullUserIdThrowsException() {
        // When/Then - no stubbing needed for these tests as they should throw before any repository
        // calls
        assertThrows(AppException.class, () -> userDeletionService.deleteAllUserData(null));
        assertThrows(AppException.class, () -> userDeletionService.deleteAllUserData(""));
    }

    @Test
    void testDeleteAllUserDataWithEmptyDataHandlesGracefully() {
        // Given
        when(accountRepository.findByUserId(testUserId)).thenReturn(new ArrayList<>());
        // Mock pagination for transactions (batchSize is 100, matching UserDeletionService)
        when(transactionRepository.findByUserId(testUserId, 0, 100)).thenReturn(new ArrayList<>());
        when(actionRepository.findByUserId(testUserId)).thenReturn(new ArrayList<>());
        when(subscriptionRepository.findByUserId(testUserId)).thenReturn(new ArrayList<>());
        when(budgetRepository.findByUserId(testUserId)).thenReturn(new ArrayList<>());
        when(goalRepository.findByUserId(testUserId)).thenReturn(new ArrayList<>());
        // Mock batch delete to throw so it falls back to individual delete
        doThrow(new RuntimeException("Batch delete not supported"))
                .when(transactionRepository)
                .batchDelete(anyList());
        doThrow(new RuntimeException("Batch delete not supported"))
                .when(accountRepository)
                .batchDelete(anyList());

        // When/Then - should not throw
        assertDoesNotThrow(() -> userDeletionService.deleteAllUserData(testUserId));
    }

    @Test
    void testDeleteAllUserDataWithLargeDataSetDeletesInBatches() {
        // Given - large number of transactions
        final List<TransactionTable> transactions = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            transactions.add(createTransaction("tx-" + i));
        }

        // Mock pagination: first batch returns 100 transactions (matching UserDeletionService batch
        // size), second batch returns empty
        // Note: UserDeletionService uses batch size 100, not 1000
        final List<TransactionTable> firstBatch =
                transactions.subList(0, Math.min(100, transactions.size()));
        final List<TransactionTable> remainingTransactions =
                transactions.size() > 100
                        ? transactions.subList(100, transactions.size())
                        : new ArrayList<>();
        when(transactionRepository.findByUserId(testUserId, 0, 100)).thenReturn(firstBatch);
        when(transactionRepository.findByUserId(testUserId, 100, 100))
                .thenReturn(
                        remainingTransactions.size() > 0
                                ? remainingTransactions.subList(
                                        0, Math.min(100, remainingTransactions.size()))
                                : new ArrayList<>());
        when(transactionRepository.findByUserId(testUserId, 200, 100))
                .thenReturn(new ArrayList<>());
        when(accountRepository.findByUserId(testUserId)).thenReturn(new ArrayList<>());
        when(actionRepository.findByUserId(testUserId)).thenReturn(new ArrayList<>());
        when(subscriptionRepository.findByUserId(testUserId)).thenReturn(new ArrayList<>());
        when(budgetRepository.findByUserId(testUserId)).thenReturn(new ArrayList<>());
        when(goalRepository.findByUserId(testUserId)).thenReturn(new ArrayList<>());
        // Mock batch delete to throw so it falls back to individual delete
        doThrow(new RuntimeException("Batch delete not supported"))
                .when(transactionRepository)
                .batchDelete(anyList());
        doThrow(new RuntimeException("Batch delete not supported"))
                .when(accountRepository)
                .batchDelete(anyList());

        // When
        userDeletionService.deleteAllUserData(testUserId);

        // Then - should delete all transactions
        verify(transactionRepository, atLeastOnce()).delete(anyString());
    }

    // MARK: - Delete Account Completely Tests

    @Test
    void testDeleteAccountCompletelyDeletesFIDO2Credentials() {
        // Given
        final List<FIDO2CredentialTable> credentials =
                Arrays.asList(createFIDO2Credential("cred-1"), createFIDO2Credential("cred-2"));
        when(fido2CredentialRepository.findByUserId(testUserId)).thenReturn(credentials);
        when(accountRepository.findByUserId(testUserId)).thenReturn(new ArrayList<>());
        // Mock pagination for transactions (batchSize is 100, matching UserDeletionService)
        when(transactionRepository.findByUserId(testUserId, 0, 100)).thenReturn(new ArrayList<>());
        when(actionRepository.findByUserId(testUserId)).thenReturn(new ArrayList<>());
        when(subscriptionRepository.findByUserId(testUserId)).thenReturn(new ArrayList<>());
        when(budgetRepository.findByUserId(testUserId)).thenReturn(new ArrayList<>());
        when(goalRepository.findByUserId(testUserId)).thenReturn(new ArrayList<>());
        // Mock batch delete to throw so it falls back to individual delete
        doThrow(new RuntimeException("Batch delete not supported"))
                .when(transactionRepository)
                .batchDelete(anyList());
        doThrow(new RuntimeException("Batch delete not supported"))
                .when(accountRepository)
                .batchDelete(anyList());
        // Mock user repository delete
        doNothing().when(userRepository).delete(testUserId);

        // When
        userDeletionService.deleteAccountCompletely(testUserId);

        // Then
        verify(fido2CredentialRepository, times(2)).delete(anyString());
        verify(userRepository, times(1)).delete(testUserId);
    }

    @Test
    void testDeleteAccountCompletelyDeletesUser() {
        // Given
        when(accountRepository.findByUserId(testUserId)).thenReturn(new ArrayList<>());
        // Mock pagination for transactions (batchSize is 100, matching UserDeletionService)
        when(transactionRepository.findByUserId(testUserId, 0, 100)).thenReturn(new ArrayList<>());
        when(actionRepository.findByUserId(testUserId)).thenReturn(new ArrayList<>());
        when(subscriptionRepository.findByUserId(testUserId)).thenReturn(new ArrayList<>());
        when(budgetRepository.findByUserId(testUserId)).thenReturn(new ArrayList<>());
        when(goalRepository.findByUserId(testUserId)).thenReturn(new ArrayList<>());
        when(fido2CredentialRepository.findByUserId(testUserId)).thenReturn(new ArrayList<>());
        // Mock batch delete to throw so it falls back to individual delete
        doThrow(new RuntimeException("Batch delete not supported"))
                .when(transactionRepository)
                .batchDelete(anyList());
        doThrow(new RuntimeException("Batch delete not supported"))
                .when(accountRepository)
                .batchDelete(anyList());
        // Mock user repository delete
        doNothing().when(userRepository).delete(testUserId);

        // When
        userDeletionService.deleteAccountCompletely(testUserId);

        // Then
        verify(userRepository, times(1)).delete(testUserId);
    }

    // MARK: - Extended GDPR sweep coverage
    //
    // These tests assert that deleteAllUserData() actually invokes every repository in the
    // sweep list — not just transactions / accounts / budgets / goals which the older tests
    // covered. The gap they close is the silent regression where someone adds a new
    // PII-bearing table, mocks it in setUp, but forgets to wire its delete call into the
    // deletion service — the service still passes the older tests but leaks user data.

    @Test
    void deleteAllUserData_invokesEverySweepHook_evenWhenAllReposReturnEmpty() {
        // Empty data — we don't care what comes back; we only care that the sweep CALLED
        // each repository's delete-by-user method.
        when(accountRepository.findByUserId(testUserId)).thenReturn(new ArrayList<>());
        when(transactionRepository.findByUserId(testUserId, 0, 100)).thenReturn(new ArrayList<>());
        when(actionRepository.findByUserId(testUserId)).thenReturn(new ArrayList<>());
        when(subscriptionRepository.findByUserId(testUserId)).thenReturn(new ArrayList<>());
        when(budgetRepository.findByUserId(testUserId)).thenReturn(new ArrayList<>());
        when(goalRepository.findByUserId(testUserId)).thenReturn(new ArrayList<>());
        when(deviceTokenRepository.findByUserId(testUserId)).thenReturn(new ArrayList<>());

        userDeletionService.deleteAllUserData(testUserId);

        // Each must be called exactly once per deletion. If a new GDPR-relevant repo is added
        // and not wired in, this assertion catches it (it would still pass) — but the
        // *missing* call would fail to verify. So extend this test when you add a new repo.
        verify(plaidAccessTokenRepository, times(1)).deleteByUserId(testUserId);
        verify(anomalyFeedbackRepository, times(1)).deleteByUserId(testUserId);
        verify(netWorthSnapshotRepository, times(1)).deleteByUserId(testUserId);
        verify(userCorrectionRepository, times(1)).deleteByUserId(testUserId);
        verify(customMerchantMappingRepository, times(1)).deleteByUserId(testUserId);
        verify(userPreferencesRepository, times(1)).deleteByUserId(testUserId);
    }

    @Test
    void deleteAllUserData_continues_whenPlaidAccessTokenSweepThrows() {
        // Resilience: if the Plaid sweep blows up (DDB throttle, IAM hiccup), the rest of
        // the GDPR deletion MUST still complete. Otherwise a single transient AWS error
        // would leave the user's category-learning rows and net-worth snapshots un-deleted.
        when(accountRepository.findByUserId(testUserId)).thenReturn(new ArrayList<>());
        when(transactionRepository.findByUserId(testUserId, 0, 100)).thenReturn(new ArrayList<>());
        when(actionRepository.findByUserId(testUserId)).thenReturn(new ArrayList<>());
        when(subscriptionRepository.findByUserId(testUserId)).thenReturn(new ArrayList<>());
        when(budgetRepository.findByUserId(testUserId)).thenReturn(new ArrayList<>());
        when(goalRepository.findByUserId(testUserId)).thenReturn(new ArrayList<>());
        when(deviceTokenRepository.findByUserId(testUserId)).thenReturn(new ArrayList<>());
        when(plaidAccessTokenRepository.deleteByUserId(anyString()))
                .thenThrow(new RuntimeException("DDB throttle"));

        // Must not propagate — the catch in deletePlaidAccessTokensForUser is the safety net.
        assertDoesNotThrow(() -> userDeletionService.deleteAllUserData(testUserId));

        // And the downstream sweeps must still have run.
        verify(userPreferencesRepository, times(1)).deleteByUserId(testUserId);
        verify(userCorrectionRepository, times(1)).deleteByUserId(testUserId);
        verify(customMerchantMappingRepository, times(1)).deleteByUserId(testUserId);
    }

    @Test
    void deleteAllUserData_isIdempotent_secondCallStillInvokesSweeps() {
        // Idempotency: running deleteAllUserData twice in a row (retry after a partial
        // failure, GDPR cron sweeping the same user twice) must call every sweep again
        // safely — the repos themselves are expected to no-op on empty rows.
        when(accountRepository.findByUserId(testUserId)).thenReturn(new ArrayList<>());
        when(transactionRepository.findByUserId(testUserId, 0, 100)).thenReturn(new ArrayList<>());
        when(actionRepository.findByUserId(testUserId)).thenReturn(new ArrayList<>());
        when(subscriptionRepository.findByUserId(testUserId)).thenReturn(new ArrayList<>());
        when(budgetRepository.findByUserId(testUserId)).thenReturn(new ArrayList<>());
        when(goalRepository.findByUserId(testUserId)).thenReturn(new ArrayList<>());
        when(deviceTokenRepository.findByUserId(testUserId)).thenReturn(new ArrayList<>());

        userDeletionService.deleteAllUserData(testUserId);
        userDeletionService.deleteAllUserData(testUserId);

        verify(plaidAccessTokenRepository, times(2)).deleteByUserId(testUserId);
        verify(anomalyFeedbackRepository, times(2)).deleteByUserId(testUserId);
        verify(netWorthSnapshotRepository, times(2)).deleteByUserId(testUserId);
    }

    @Test
    void deletePlaidIntegration_doesNotTouchBackendAccessTokenStore() {
        // Important separation: deletePlaidIntegration only un-links the iOS-side connection
        // (clears Plaid IDs on accounts, deletes transactions). The BACKEND-side
        // PlaidAccessToken store should ONLY be wiped on full data deletion, not on the
        // "I want to disconnect Plaid but keep my history" flow. Otherwise users would lose
        // their scheduled-sync access token even though they kept their accounts.
        when(accountRepository.findByUserId(testUserId)).thenReturn(new ArrayList<>());
        when(transactionRepository.findByUserId(testUserId, 0, 100)).thenReturn(new ArrayList<>());
        when(actionRepository.findByUserId(testUserId)).thenReturn(new ArrayList<>());

        userDeletionService.deletePlaidIntegration(testUserId);

        verify(plaidAccessTokenRepository, org.mockito.Mockito.never()).deleteByUserId(anyString());
    }

    // MARK: - Helper Methods

    private AccountTable createAccount(final String id) {
        final AccountTable account = new AccountTable();
        account.setAccountId(id);
        account.setUserId(testUserId);
        account.setAccountName("Test Account");
        return account;
    }

    private TransactionTable createTransaction(final String id) {
        final TransactionTable tx = new TransactionTable();
        tx.setTransactionId(id);
        tx.setUserId(testUserId);
        return tx;
    }

    private TransactionActionTable createAction(final String id) {
        final TransactionActionTable action = new TransactionActionTable();
        action.setActionId(id);
        action.setUserId(testUserId);
        return action;
    }

    private SubscriptionTable createSubscription(final String id) {
        final SubscriptionTable subscription = new SubscriptionTable();
        subscription.setSubscriptionId(id);
        subscription.setUserId(testUserId);
        return subscription;
    }

    private BudgetTable createBudget(final String id) {
        final BudgetTable budget = new BudgetTable();
        budget.setBudgetId(id);
        budget.setUserId(testUserId);
        return budget;
    }

    private GoalTable createGoal(final String id) {
        final GoalTable goal = new GoalTable();
        goal.setGoalId(id);
        goal.setUserId(testUserId);
        return goal;
    }

    private FIDO2CredentialTable createFIDO2Credential(final String id) {
        final FIDO2CredentialTable credential = new FIDO2CredentialTable();
        credential.setCredentialId(id);
        credential.setUserId(testUserId);
        return credential;
    }
}
