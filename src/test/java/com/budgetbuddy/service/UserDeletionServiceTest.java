package com.budgetbuddy.service;

import com.budgetbuddy.compliance.AuditLogService;
import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.model.dynamodb.*;
import com.budgetbuddy.repository.dynamodb.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCache;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive tests for UserDeletionService
 * Tests deletion of all entities, cache invalidation, and edge cases
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class UserDeletionServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private TransactionActionRepository actionRepository;

    @Mock
    private SubscriptionRepository subscriptionRepository;

    @Mock
    private FIDO2CredentialRepository fido2CredentialRepository;

    @Mock
    private BudgetRepository budgetRepository;

    @Mock
    private GoalRepository goalRepository;

    @Mock
    private AuditLogRepository auditLogRepository;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private CacheManager cacheManager;

    @InjectMocks
    private UserDeletionService userDeletionService;

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
        doNothing().when(auditLogService).logAction(anyString(), anyString(), anyString(), any(), any(), any(), any());
    }

    // MARK: - Delete All User Data Tests

    @Test
    void testDeleteAllUserData_DeletesAllEntities() {
        // Given
        List<AccountTable> accounts = Arrays.asList(createAccount("acc-1"), createAccount("acc-2"));
        List<TransactionTable> transactions = Arrays.asList(createTransaction("tx-1"), createTransaction("tx-2"));
        List<TransactionActionTable> actions = Arrays.asList(createAction("action-1"), createAction("action-2"));
        List<SubscriptionTable> subscriptions = Arrays.asList(createSubscription("sub-1"), createSubscription("sub-2"));
        List<BudgetTable> budgets = Arrays.asList(createBudget("budget-1"), createBudget("budget-2"));
        List<GoalTable> goals = Arrays.asList(createGoal("goal-1"), createGoal("goal-2"));

        when(accountRepository.findByUserId(testUserId)).thenReturn(accounts);
        // Mock pagination for transactions (batchSize is 100, matching UserDeletionService)
        when(transactionRepository.findByUserId(testUserId, 0, 100)).thenReturn(transactions);
        when(transactionRepository.findByUserId(testUserId, 2, 100)).thenReturn(new ArrayList<>());
        when(actionRepository.findByUserId(testUserId)).thenReturn(actions);
        when(subscriptionRepository.findByUserId(testUserId)).thenReturn(subscriptions);
        when(budgetRepository.findByUserId(testUserId)).thenReturn(budgets);
        when(goalRepository.findByUserId(testUserId)).thenReturn(goals);
        
        // Mock batch delete methods (they might throw, so we'll use individual delete as fallback)
        doThrow(new RuntimeException("Batch delete not supported in test")).when(transactionRepository).batchDelete(anyList());
        doThrow(new RuntimeException("Batch delete not supported in test")).when(accountRepository).batchDelete(anyList());
        
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
    void testDeleteAllUserData_DeletesTransactionActions() {
        // Given
        List<TransactionActionTable> actions = Arrays.asList(
                createAction("action-1"),
                createAction("action-2"),
                createAction("action-3")
        );
        when(actionRepository.findByUserId(testUserId)).thenReturn(actions);
        when(accountRepository.findByUserId(testUserId)).thenReturn(new ArrayList<>());
        // Mock pagination for transactions (batchSize is 100, matching UserDeletionService)
        when(transactionRepository.findByUserId(testUserId, 0, 100)).thenReturn(new ArrayList<>());
        when(subscriptionRepository.findByUserId(testUserId)).thenReturn(new ArrayList<>());
        when(budgetRepository.findByUserId(testUserId)).thenReturn(new ArrayList<>());
        when(goalRepository.findByUserId(testUserId)).thenReturn(new ArrayList<>());
        // Mock batch delete to throw so it falls back to individual delete
        doThrow(new RuntimeException("Batch delete not supported")).when(transactionRepository).batchDelete(anyList());
        doThrow(new RuntimeException("Batch delete not supported")).when(accountRepository).batchDelete(anyList());

        // When
        userDeletionService.deleteAllUserData(testUserId);

        // Then
        verify(actionRepository, times(3)).delete(anyString());
    }

    @Test
    void testDeleteAllUserData_DeletesSubscriptions() {
        // Given
        List<SubscriptionTable> subscriptions = Arrays.asList(
                createSubscription("sub-1"),
                createSubscription("sub-2")
        );
        when(subscriptionRepository.findByUserId(testUserId)).thenReturn(subscriptions);
        when(accountRepository.findByUserId(testUserId)).thenReturn(new ArrayList<>());
        // Mock pagination for transactions (batchSize is 100, matching UserDeletionService)
        when(transactionRepository.findByUserId(testUserId, 0, 100)).thenReturn(new ArrayList<>());
        when(actionRepository.findByUserId(testUserId)).thenReturn(new ArrayList<>());
        when(budgetRepository.findByUserId(testUserId)).thenReturn(new ArrayList<>());
        when(goalRepository.findByUserId(testUserId)).thenReturn(new ArrayList<>());
        // Mock batch delete to throw so it falls back to individual delete
        doThrow(new RuntimeException("Batch delete not supported")).when(transactionRepository).batchDelete(anyList());
        doThrow(new RuntimeException("Batch delete not supported")).when(accountRepository).batchDelete(anyList());

        // When
        userDeletionService.deleteAllUserData(testUserId);

        // Then
        verify(subscriptionRepository, times(2)).delete(anyString());
    }

    @Test
    void testDeleteAllUserData_EvictsCaches() {
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
        doThrow(new RuntimeException("Batch delete not supported")).when(transactionRepository).batchDelete(anyList());
        doThrow(new RuntimeException("Batch delete not supported")).when(accountRepository).batchDelete(anyList());

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
    void testDeleteAllUserData_WithNullUserId_ThrowsException() {
        // When/Then - no stubbing needed for these tests as they should throw before any repository calls
        assertThrows(AppException.class, () -> userDeletionService.deleteAllUserData(null));
        assertThrows(AppException.class, () -> userDeletionService.deleteAllUserData(""));
    }

    @Test
    void testDeleteAllUserData_WithEmptyData_HandlesGracefully() {
        // Given
        when(accountRepository.findByUserId(testUserId)).thenReturn(new ArrayList<>());
        // Mock pagination for transactions (batchSize is 100, matching UserDeletionService)
        when(transactionRepository.findByUserId(testUserId, 0, 100)).thenReturn(new ArrayList<>());
        when(actionRepository.findByUserId(testUserId)).thenReturn(new ArrayList<>());
        when(subscriptionRepository.findByUserId(testUserId)).thenReturn(new ArrayList<>());
        when(budgetRepository.findByUserId(testUserId)).thenReturn(new ArrayList<>());
        when(goalRepository.findByUserId(testUserId)).thenReturn(new ArrayList<>());
        // Mock batch delete to throw so it falls back to individual delete
        doThrow(new RuntimeException("Batch delete not supported")).when(transactionRepository).batchDelete(anyList());
        doThrow(new RuntimeException("Batch delete not supported")).when(accountRepository).batchDelete(anyList());

        // When/Then - should not throw
        assertDoesNotThrow(() -> userDeletionService.deleteAllUserData(testUserId));
    }

    @Test
    void testDeleteAllUserData_WithLargeDataSet_DeletesInBatches() {
        // Given - large number of transactions
        List<TransactionTable> transactions = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            transactions.add(createTransaction("tx-" + i));
        }

        // Mock pagination: first batch returns 100 transactions (matching UserDeletionService batch size), second batch returns empty
        // Note: UserDeletionService uses batch size 100, not 1000
        List<TransactionTable> firstBatch = transactions.subList(0, Math.min(100, transactions.size()));
        List<TransactionTable> remainingTransactions = transactions.size() > 100 ? transactions.subList(100, transactions.size()) : new ArrayList<>();
        when(transactionRepository.findByUserId(testUserId, 0, 100)).thenReturn(firstBatch);
        when(transactionRepository.findByUserId(testUserId, 100, 100)).thenReturn(remainingTransactions.size() > 0 ? remainingTransactions.subList(0, Math.min(100, remainingTransactions.size())) : new ArrayList<>());
        when(transactionRepository.findByUserId(testUserId, 200, 100)).thenReturn(new ArrayList<>());
        when(accountRepository.findByUserId(testUserId)).thenReturn(new ArrayList<>());
        when(actionRepository.findByUserId(testUserId)).thenReturn(new ArrayList<>());
        when(subscriptionRepository.findByUserId(testUserId)).thenReturn(new ArrayList<>());
        when(budgetRepository.findByUserId(testUserId)).thenReturn(new ArrayList<>());
        when(goalRepository.findByUserId(testUserId)).thenReturn(new ArrayList<>());
        // Mock batch delete to throw so it falls back to individual delete
        doThrow(new RuntimeException("Batch delete not supported")).when(transactionRepository).batchDelete(anyList());
        doThrow(new RuntimeException("Batch delete not supported")).when(accountRepository).batchDelete(anyList());

        // When
        userDeletionService.deleteAllUserData(testUserId);

        // Then - should delete all transactions
        verify(transactionRepository, atLeastOnce()).delete(anyString());
    }

    // MARK: - Delete Account Completely Tests

    @Test
    void testDeleteAccountCompletely_DeletesFIDO2Credentials() {
        // Given
        List<FIDO2CredentialTable> credentials = Arrays.asList(
                createFIDO2Credential("cred-1"),
                createFIDO2Credential("cred-2")
        );
        when(fido2CredentialRepository.findByUserId(testUserId)).thenReturn(credentials);
        when(accountRepository.findByUserId(testUserId)).thenReturn(new ArrayList<>());
        // Mock pagination for transactions (batchSize is 100, matching UserDeletionService)
        when(transactionRepository.findByUserId(testUserId, 0, 100)).thenReturn(new ArrayList<>());
        when(actionRepository.findByUserId(testUserId)).thenReturn(new ArrayList<>());
        when(subscriptionRepository.findByUserId(testUserId)).thenReturn(new ArrayList<>());
        when(budgetRepository.findByUserId(testUserId)).thenReturn(new ArrayList<>());
        when(goalRepository.findByUserId(testUserId)).thenReturn(new ArrayList<>());
        // Mock batch delete to throw so it falls back to individual delete
        doThrow(new RuntimeException("Batch delete not supported")).when(transactionRepository).batchDelete(anyList());
        doThrow(new RuntimeException("Batch delete not supported")).when(accountRepository).batchDelete(anyList());
        // Mock user repository delete
        doNothing().when(userRepository).delete(testUserId);

        // When
        userDeletionService.deleteAccountCompletely(testUserId);

        // Then
        verify(fido2CredentialRepository, times(2)).delete(anyString());
        verify(userRepository, times(1)).delete(testUserId);
    }

    @Test
    void testDeleteAccountCompletely_DeletesUser() {
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
        doThrow(new RuntimeException("Batch delete not supported")).when(transactionRepository).batchDelete(anyList());
        doThrow(new RuntimeException("Batch delete not supported")).when(accountRepository).batchDelete(anyList());
        // Mock user repository delete
        doNothing().when(userRepository).delete(testUserId);

        // When
        userDeletionService.deleteAccountCompletely(testUserId);

        // Then
        verify(userRepository, times(1)).delete(testUserId);
    }

    // MARK: - Helper Methods

    private AccountTable createAccount(String id) {
        AccountTable account = new AccountTable();
        account.setAccountId(id);
        account.setUserId(testUserId);
        account.setAccountName("Test Account");
        return account;
    }

    private TransactionTable createTransaction(String id) {
        TransactionTable tx = new TransactionTable();
        tx.setTransactionId(id);
        tx.setUserId(testUserId);
        return tx;
    }

    private TransactionActionTable createAction(String id) {
        TransactionActionTable action = new TransactionActionTable();
        action.setActionId(id);
        action.setUserId(testUserId);
        return action;
    }

    private SubscriptionTable createSubscription(String id) {
        SubscriptionTable subscription = new SubscriptionTable();
        subscription.setSubscriptionId(id);
        subscription.setUserId(testUserId);
        return subscription;
    }

    private BudgetTable createBudget(String id) {
        BudgetTable budget = new BudgetTable();
        budget.setBudgetId(id);
        budget.setUserId(testUserId);
        return budget;
    }

    private GoalTable createGoal(String id) {
        GoalTable goal = new GoalTable();
        goal.setGoalId(id);
        goal.setUserId(testUserId);
        return goal;
    }

    private FIDO2CredentialTable createFIDO2Credential(String id) {
        FIDO2CredentialTable credential = new FIDO2CredentialTable();
        credential.setCredentialId(id);
        credential.setUserId(testUserId);
        return credential;
    }
}
