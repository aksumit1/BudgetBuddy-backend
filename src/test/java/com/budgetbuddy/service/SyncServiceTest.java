package com.budgetbuddy.service;

import com.budgetbuddy.dto.IncrementalSyncResponse;
import com.budgetbuddy.dto.SyncAllResponse;
import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.*;
import com.budgetbuddy.repository.dynamodb.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit Tests for SyncService
 */
@ExtendWith(MockitoExtension.class)
class SyncServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private BudgetRepository budgetRepository;

    @Mock
    private GoalRepository goalRepository;

    @Mock
    private TransactionActionRepository transactionActionRepository;

    @InjectMocks
    private SyncService syncService;

    private String testUserId;
    private AccountTable testAccount;
    private TransactionTable testTransaction;
    private BudgetTable testBudget;
    private GoalTable testGoal;
    private TransactionActionTable testAction;

    @BeforeEach
    void setUp() {
        testUserId = "test-user-123";

        // Create test account
        testAccount = new AccountTable();
        testAccount.setAccountId("account-1");
        testAccount.setUserId(testUserId);
        testAccount.setAccountName("Test Account");
        testAccount.setBalance(new BigDecimal("1000.00"));
        testAccount.setCreatedAt(Instant.now().minusSeconds(86400)); // 1 day ago
        testAccount.setUpdatedAt(Instant.now().minusSeconds(3600)); // 1 hour ago

        // Create test transaction
        testTransaction = new TransactionTable();
        testTransaction.setTransactionId("transaction-1");
        testTransaction.setUserId(testUserId);
        testTransaction.setAccountId("account-1");
        testTransaction.setAmount(new BigDecimal("50.00"));
        testTransaction.setTransactionDate("2024-01-15");
        testTransaction.setCreatedAt(Instant.now().minusSeconds(7200)); // 2 hours ago
        testTransaction.setUpdatedAt(Instant.now().minusSeconds(1800)); // 30 minutes ago

        // Create test budget
        testBudget = new BudgetTable();
        testBudget.setBudgetId("budget-1");
        testBudget.setUserId(testUserId);
        testBudget.setCategory("FOOD");
        testBudget.setMonthlyLimit(new BigDecimal("500.00"));
        testBudget.setCreatedAt(Instant.now().minusSeconds(172800)); // 2 days ago
        testBudget.setUpdatedAt(Instant.now().minusSeconds(7200)); // 2 hours ago

        // Create test goal
        testGoal = new GoalTable();
        testGoal.setGoalId("goal-1");
        testGoal.setUserId(testUserId);
        testGoal.setName("Test Goal");
        testGoal.setTargetAmount(new BigDecimal("10000.00"));
        testGoal.setCreatedAt(Instant.now().minusSeconds(259200)); // 3 days ago
        testGoal.setUpdatedAt(Instant.now().minusSeconds(10800)); // 3 hours ago

        // Create test action
        testAction = new TransactionActionTable();
        testAction.setActionId("action-1");
        testAction.setUserId(testUserId);
        testAction.setTransactionId("transaction-1");
        testAction.setTitle("Test Action");
        testAction.setCreatedAt(Instant.now().minusSeconds(3600)); // 1 hour ago
        testAction.setUpdatedAt(Instant.now().minusSeconds(900)); // 15 minutes ago
    }

    @Test
    void getAllData_Success() {
        // Given
        List<AccountTable> accounts = Arrays.asList(testAccount);
        List<TransactionTable> transactions = Arrays.asList(testTransaction);
        List<BudgetTable> budgets = Arrays.asList(testBudget);
        List<GoalTable> goals = Arrays.asList(testGoal);
        List<TransactionActionTable> actions = Arrays.asList(testAction);

        when(accountRepository.findByUserId(testUserId)).thenReturn(accounts);
        when(transactionRepository.findByUserId(testUserId, 0, Integer.MAX_VALUE)).thenReturn(transactions);
        when(budgetRepository.findByUserId(testUserId)).thenReturn(budgets);
        when(goalRepository.findByUserId(testUserId)).thenReturn(goals);
        when(transactionActionRepository.findByUserId(testUserId)).thenReturn(actions);

        // When
        SyncAllResponse response = syncService.getAllData(testUserId);

        // Then
        assertNotNull(response);
        assertEquals(1, response.getAccounts().size());
        assertEquals(1, response.getTransactions().size());
        assertEquals(1, response.getBudgets().size());
        assertEquals(1, response.getGoals().size());
        assertEquals(1, response.getActions().size());
        assertNotNull(response.getSyncTimestamp());

        verify(accountRepository).findByUserId(testUserId);
        verify(transactionRepository).findByUserId(testUserId, 0, Integer.MAX_VALUE);
        verify(budgetRepository).findByUserId(testUserId);
        verify(goalRepository).findByUserId(testUserId);
        verify(transactionActionRepository).findByUserId(testUserId);
    }

    @Test
    void getAllData_EmptyData() {
        // Given
        when(accountRepository.findByUserId(testUserId)).thenReturn(Collections.emptyList());
        when(transactionRepository.findByUserId(testUserId, 0, Integer.MAX_VALUE)).thenReturn(Collections.emptyList());
        when(budgetRepository.findByUserId(testUserId)).thenReturn(Collections.emptyList());
        when(goalRepository.findByUserId(testUserId)).thenReturn(Collections.emptyList());
        when(transactionActionRepository.findByUserId(testUserId)).thenReturn(Collections.emptyList());

        // When
        SyncAllResponse response = syncService.getAllData(testUserId);

        // Then
        assertNotNull(response);
        assertTrue(response.getAccounts().isEmpty());
        assertTrue(response.getTransactions().isEmpty());
        assertTrue(response.getBudgets().isEmpty());
        assertTrue(response.getGoals().isEmpty());
        assertTrue(response.getActions().isEmpty());
    }

    @Test
    void getAllData_InvalidUserId() {
        // When/Then
        AppException exception = assertThrows(AppException.class, () -> {
            syncService.getAllData(null);
        });

        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void getIncrementalChanges_Success() {
        // Given
        Instant now = Instant.now();
        Instant sinceInstant = now.minusSeconds(7200); // 2 hours ago
        Long sinceTimestamp = sinceInstant.getEpochSecond();

        // Create test data with timestamps relative to 'since'
        // Items updated AFTER sinceTimestamp should be included
        AccountTable recentAccount = new AccountTable();
        recentAccount.setAccountId("account-recent");
        recentAccount.setUserId(testUserId);
        recentAccount.setAccountName("Recent Account");
        recentAccount.setBalance(new BigDecimal("2000.00"));
        recentAccount.setCreatedAt(now.minusSeconds(86400)); // 1 day ago
        recentAccount.setUpdatedAt(now.minusSeconds(1800)); // 30 minutes ago (AFTER sinceTimestamp)

        TransactionTable recentTransaction = new TransactionTable();
        recentTransaction.setTransactionId("transaction-recent");
        recentTransaction.setUserId(testUserId);
        recentTransaction.setAccountId("account-recent");
        recentTransaction.setAmount(new BigDecimal("100.00"));
        recentTransaction.setTransactionDate("2024-01-16");
        recentTransaction.setCategoryPrimary("groceries");
        recentTransaction.setCategoryDetailed("groceries");
        recentTransaction.setCreatedAt(now.minusSeconds(3600)); // 1 hour ago
        recentTransaction.setUpdatedAt(now.minusSeconds(900)); // 15 minutes ago (AFTER sinceTimestamp)

        // Items updated BEFORE sinceTimestamp should NOT be included
        BudgetTable oldBudget = new BudgetTable();
        oldBudget.setBudgetId("budget-old");
        oldBudget.setUserId(testUserId);
        oldBudget.setCategory("FOOD");
        oldBudget.setMonthlyLimit(new BigDecimal("500.00"));
        oldBudget.setCreatedAt(now.minusSeconds(172800)); // 2 days ago
        oldBudget.setUpdatedAt(now.minusSeconds(10800)); // 3 hours ago (BEFORE sinceTimestamp)

        List<AccountTable> allAccounts = Arrays.asList(recentAccount);
        List<TransactionTable> allTransactions = Arrays.asList(recentTransaction);
        List<BudgetTable> allBudgets = Arrays.asList(oldBudget);
        List<GoalTable> allGoals = Collections.emptyList(); // No goals
        List<TransactionActionTable> allActions = Collections.emptyList(); // No actions

        when(accountRepository.findByUserId(testUserId)).thenReturn(allAccounts);
        when(transactionRepository.findByUserId(testUserId, 0, Integer.MAX_VALUE)).thenReturn(allTransactions);
        when(budgetRepository.findByUserId(testUserId)).thenReturn(allBudgets);
        when(goalRepository.findByUserId(testUserId)).thenReturn(allGoals);
        when(transactionActionRepository.findByUserId(testUserId)).thenReturn(allActions);

        // When
        IncrementalSyncResponse response = syncService.getIncrementalChanges(testUserId, sinceTimestamp);

        // Then
        assertNotNull(response);
        // Transaction was updated 15 minutes ago (after sinceTimestamp), so it should be included
        assertFalse(response.getTransactions().isEmpty(), "Transaction updated after sinceTimestamp should be included");
        // Account was updated 30 minutes ago (after sinceTimestamp), so it should be included
        assertFalse(response.getAccounts().isEmpty(), "Account updated after sinceTimestamp should be included");
        // Budget was updated 3 hours ago (before sinceTimestamp), so it should NOT be included
        assertTrue(response.getBudgets().isEmpty(), "Budget updated before sinceTimestamp should NOT be included");
        assertNotNull(response.getSyncTimestamp());
        assertFalse(response.isHasMore());
    }

    @Test
    void getIncrementalChanges_NoChanges() {
        // Given
        Long sinceTimestamp = Instant.now().minusSeconds(300).getEpochSecond(); // 5 minutes ago

        List<AccountTable> allAccounts = Arrays.asList(testAccount);
        List<TransactionTable> allTransactions = Arrays.asList(testTransaction);
        List<BudgetTable> allBudgets = Arrays.asList(testBudget);
        List<GoalTable> allGoals = Arrays.asList(testGoal);
        List<TransactionActionTable> allActions = Arrays.asList(testAction);

        when(accountRepository.findByUserId(testUserId)).thenReturn(allAccounts);
        when(transactionRepository.findByUserId(testUserId, 0, Integer.MAX_VALUE)).thenReturn(allTransactions);
        when(budgetRepository.findByUserId(testUserId)).thenReturn(allBudgets);
        when(goalRepository.findByUserId(testUserId)).thenReturn(allGoals);
        when(transactionActionRepository.findByUserId(testUserId)).thenReturn(allActions);

        // When
        IncrementalSyncResponse response = syncService.getIncrementalChanges(testUserId, sinceTimestamp);

        // Then
        assertNotNull(response);
        // All items were updated before sinceTimestamp, so all should be empty
        assertTrue(response.getAccounts().isEmpty());
        assertTrue(response.getTransactions().isEmpty());
        assertTrue(response.getBudgets().isEmpty());
        assertTrue(response.getGoals().isEmpty());
        assertTrue(response.getActions().isEmpty());
    }

    @Test
    void getIncrementalChanges_InvalidTimestamp_FallbackToFullSync() {
        // Given
        Long invalidTimestamp = null;

        List<AccountTable> accounts = Arrays.asList(testAccount);
        List<TransactionTable> transactions = Arrays.asList(testTransaction);
        List<BudgetTable> budgets = Arrays.asList(testBudget);
        List<GoalTable> goals = Arrays.asList(testGoal);
        List<TransactionActionTable> actions = Arrays.asList(testAction);

        when(accountRepository.findByUserId(testUserId)).thenReturn(accounts);
        when(transactionRepository.findByUserId(testUserId, 0, Integer.MAX_VALUE)).thenReturn(transactions);
        when(budgetRepository.findByUserId(testUserId)).thenReturn(budgets);
        when(goalRepository.findByUserId(testUserId)).thenReturn(goals);
        when(transactionActionRepository.findByUserId(testUserId)).thenReturn(actions);

        // When
        IncrementalSyncResponse response = syncService.getIncrementalChanges(testUserId, invalidTimestamp);

        // Then
        assertNotNull(response);
        // Should return all data (fallback to full sync)
        assertEquals(1, response.getAccounts().size());
        assertEquals(1, response.getTransactions().size());
        assertEquals(1, response.getBudgets().size());
        assertEquals(1, response.getGoals().size());
        assertEquals(1, response.getActions().size());
    }

    @Test
    void getIncrementalChanges_InvalidUserId() {
        // When/Then
        AppException exception = assertThrows(AppException.class, () -> {
            syncService.getIncrementalChanges(null, Instant.now().getEpochSecond());
        });

        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }
}

