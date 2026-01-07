package com.budgetbuddy.service;

import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.GoalTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.GoalRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive tests for GoalProgressService
 * Tests goal progress calculation, completion detection, and edge cases
 */
@ExtendWith(MockitoExtension.class)
class GoalProgressServiceTest {

    @Mock
    private GoalRepository goalRepository;

    @Mock
    private GoalService goalService;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private AccountRepository accountRepository;

    @InjectMocks
    private GoalProgressService goalProgressService;

    private UserTable testUser;
    private GoalTable testGoal;
    private String goalId;
    private String userId;

    @BeforeEach
    void setUp() {
        userId = "test-user-id";
        goalId = "test-goal-id";

        testUser = new UserTable();
        testUser.setUserId(userId);

        testGoal = new GoalTable();
        testGoal.setGoalId(goalId);
        testGoal.setUserId(userId);
        testGoal.setName("Test Goal");
        testGoal.setTargetAmount(new BigDecimal("1000.00"));
        testGoal.setCurrentAmount(new BigDecimal("0.00"));
        testGoal.setActive(true);
        testGoal.setCompleted(false);
        testGoal.setAccountIds(new ArrayList<>());
    }

    @Test
    void testCalculateAndUpdateProgress_WithAssignedTransactions() {
        // Given: Goal with assigned transactions
        TransactionTable tx1 = createTransaction("tx1", goalId, new BigDecimal("100.00"));
        TransactionTable tx2 = createTransaction("tx2", goalId, new BigDecimal("200.00"));
        List<TransactionTable> assignedTransactions = Arrays.asList(tx1, tx2);

        when(goalService.getGoal(testUser, goalId)).thenReturn(testGoal);
        when(transactionRepository.findByUserIdAndGoalId(userId, goalId)).thenReturn(assignedTransactions);
        when(accountRepository.findByUserId(userId)).thenReturn(new ArrayList<>());
        when(goalService.updateGoalProgress(eq(testUser), eq(goalId), any(BigDecimal.class)))
                .thenReturn(testGoal);

        // When: Calculate progress
        GoalTable result = goalProgressService.calculateAndUpdateProgress(testUser, goalId);

        // Then: Progress should be updated with transaction contributions
        verify(goalService).updateGoalProgress(eq(testUser), eq(goalId), eq(new BigDecimal("300.00")));
        assertNotNull(result);
    }

    @Test
    void testCalculateAndUpdateProgress_WithNoAssignedTransactions_UsesIncomeFallback() {
        // Given: Goal with no assigned transactions, but has income transactions
        List<TransactionTable> noAssignedTransactions = new ArrayList<>();
        TransactionTable incomeTx1 = createTransaction("income1", null, new BigDecimal("5000.00"));
        incomeTx1.setTransactionType("INCOME");
        TransactionTable incomeTx2 = createTransaction("income2", null, new BigDecimal("3000.00"));
        incomeTx2.setTransactionType("INCOME");
        List<TransactionTable> allTransactions = Arrays.asList(incomeTx1, incomeTx2);

        when(goalService.getGoal(testUser, goalId)).thenReturn(testGoal);
        when(transactionRepository.findByUserIdAndGoalId(userId, goalId)).thenReturn(noAssignedTransactions);
        when(transactionRepository.findByUserId(userId, 0, Integer.MAX_VALUE)).thenReturn(allTransactions);
        when(accountRepository.findByUserId(userId)).thenReturn(new ArrayList<>());
        when(goalService.updateGoalProgress(eq(testUser), eq(goalId), any(BigDecimal.class)))
                .thenReturn(testGoal);

        // When: Calculate progress
        GoalTable result = goalProgressService.calculateAndUpdateProgress(testUser, goalId);

        // Then: Progress should use 10% of income (800.00)
        verify(goalService).updateGoalProgress(eq(testUser), eq(goalId), eq(new BigDecimal("800.00")));
        assertNotNull(result);
    }

    @Test
    void testCalculateAndUpdateProgress_WithGoalAssociatedAccounts() {
        // Given: Goal with associated accounts
        // Increase target amount so it's not capped
        testGoal.setTargetAmount(new BigDecimal("5000.00"));
        AccountTable account1 = createAccount("acc1", new BigDecimal("2000.00"));
        AccountTable account2 = createAccount("acc2", new BigDecimal("1000.00"));
        testGoal.setAccountIds(Arrays.asList("acc1", "acc2"));

        TransactionTable tx1 = createTransaction("tx1", goalId, new BigDecimal("100.00"));
        List<TransactionTable> assignedTransactions = Arrays.asList(tx1);

        when(goalService.getGoal(testUser, goalId)).thenReturn(testGoal);
        when(transactionRepository.findByUserIdAndGoalId(userId, goalId)).thenReturn(assignedTransactions);
        when(accountRepository.findById("acc1")).thenReturn(Optional.of(account1));
        when(accountRepository.findById("acc2")).thenReturn(Optional.of(account2));
        when(goalService.updateGoalProgress(eq(testUser), eq(goalId), any(BigDecimal.class)))
                .thenReturn(testGoal);

        // When: Calculate progress
        GoalTable result = goalProgressService.calculateAndUpdateProgress(testUser, goalId);

        // Then: Progress should include full account balances (100 + 2000 + 1000 = 3100)
        verify(goalService).updateGoalProgress(eq(testUser), eq(goalId), eq(new BigDecimal("3100.00")));
        assertNotNull(result);
    }

    @Test
    void testCalculateAndUpdateProgress_WithNoAssociatedAccounts_Uses10PercentFallback() {
        // Given: Goal with no associated accounts
        AccountTable account1 = createAccount("acc1", new BigDecimal("5000.00"));
        List<AccountTable> allAccounts = Arrays.asList(account1);

        TransactionTable tx1 = createTransaction("tx1", goalId, new BigDecimal("100.00"));
        List<TransactionTable> assignedTransactions = Arrays.asList(tx1);

        when(goalService.getGoal(testUser, goalId)).thenReturn(testGoal);
        when(transactionRepository.findByUserIdAndGoalId(userId, goalId)).thenReturn(assignedTransactions);
        when(accountRepository.findByUserId(userId)).thenReturn(allAccounts);
        when(goalService.updateGoalProgress(eq(testUser), eq(goalId), any(BigDecimal.class)))
                .thenReturn(testGoal);

        // When: Calculate progress
        GoalTable result = goalProgressService.calculateAndUpdateProgress(testUser, goalId);

        // Then: Progress should use 10% of account balance (100 + 500 = 600)
        verify(goalService).updateGoalProgress(eq(testUser), eq(goalId), eq(new BigDecimal("600.00")));
        assertNotNull(result);
    }

    @Test
    void testCalculateAndUpdateProgress_CapsAtTargetAmount() {
        // Given: Goal with progress exceeding target
        testGoal.setTargetAmount(new BigDecimal("1000.00"));
        TransactionTable tx1 = createTransaction("tx1", goalId, new BigDecimal("5000.00"));
        List<TransactionTable> assignedTransactions = Arrays.asList(tx1);

        when(goalService.getGoal(testUser, goalId)).thenReturn(testGoal);
        when(transactionRepository.findByUserIdAndGoalId(userId, goalId)).thenReturn(assignedTransactions);
        when(accountRepository.findByUserId(userId)).thenReturn(new ArrayList<>());
        when(goalService.updateGoalProgress(eq(testUser), eq(goalId), any(BigDecimal.class)))
                .thenReturn(testGoal);

        // When: Calculate progress
        GoalTable result = goalProgressService.calculateAndUpdateProgress(testUser, goalId);

        // Then: Progress should be capped at target amount (1000.00)
        verify(goalService).updateGoalProgress(eq(testUser), eq(goalId), eq(new BigDecimal("1000.00")));
        assertNotNull(result);
    }

    @Test
    void testCalculateAndUpdateProgress_FiltersNegativeAmounts() {
        // Given: Goal with both positive and negative transactions
        TransactionTable tx1 = createTransaction("tx1", goalId, new BigDecimal("100.00"));
        TransactionTable tx2 = createTransaction("tx2", goalId, new BigDecimal("-50.00")); // Negative
        TransactionTable tx3 = createTransaction("tx3", goalId, new BigDecimal("200.00"));
        List<TransactionTable> assignedTransactions = Arrays.asList(tx1, tx2, tx3);

        when(goalService.getGoal(testUser, goalId)).thenReturn(testGoal);
        when(transactionRepository.findByUserIdAndGoalId(userId, goalId)).thenReturn(assignedTransactions);
        when(accountRepository.findByUserId(userId)).thenReturn(new ArrayList<>());
        when(goalService.updateGoalProgress(eq(testUser), eq(goalId), any(BigDecimal.class)))
                .thenReturn(testGoal);

        // When: Calculate progress
        GoalTable result = goalProgressService.calculateAndUpdateProgress(testUser, goalId);

        // Then: Only positive amounts should count (100 + 200 = 300)
        verify(goalService).updateGoalProgress(eq(testUser), eq(goalId), eq(new BigDecimal("300.00")));
        assertNotNull(result);
    }

    @Test
    void testCalculateAndUpdateProgress_WithInactiveGoal() {
        // Given: Inactive goal
        testGoal.setActive(false);

        when(goalService.getGoal(testUser, goalId)).thenReturn(testGoal);

        // When: Calculate progress
        GoalTable result = goalProgressService.calculateAndUpdateProgress(testUser, goalId);

        // Then: Should return goal without updating progress
        verify(goalService, never()).updateGoalProgress(any(), any(), any());
        assertNotNull(result);
        assertFalse(result.getActive());
    }

    @Test
    void testCalculateAndUpdateProgress_WithNullUser() {
        // When/Then: Should throw exception
        assertThrows(Exception.class, () -> {
            goalProgressService.calculateAndUpdateProgress(null, goalId);
        });
    }

    @Test
    void testCalculateAndUpdateProgress_WithNullGoalId() {
        // When/Then: Should throw exception
        assertThrows(Exception.class, () -> {
            goalProgressService.calculateAndUpdateProgress(testUser, null);
        });
    }

    @Test
    void testRecalculateAllGoals() {
        // Given: Multiple active goals with different current amounts to trigger updates
        GoalTable goal1 = createGoal("goal1", true);
        goal1.setCurrentAmount(new BigDecimal("100.00")); // Different from calculated
        GoalTable goal2 = createGoal("goal2", true);
        goal2.setCurrentAmount(new BigDecimal("200.00")); // Different from calculated
        GoalTable goal3 = createGoal("goal3", false); // Inactive
        List<GoalTable> allGoals = Arrays.asList(goal1, goal2, goal3);

        when(goalRepository.findByUserId(userId)).thenReturn(allGoals);
        // Return the specific goal for each call
        when(goalService.getGoal(eq(testUser), eq("goal1"))).thenReturn(goal1);
        when(goalService.getGoal(eq(testUser), eq("goal2"))).thenReturn(goal2);
        when(transactionRepository.findByUserIdAndGoalId(anyString(), anyString())).thenReturn(new ArrayList<>());
        when(accountRepository.findByUserId(anyString())).thenReturn(new ArrayList<>());
        when(goalService.updateGoalProgress(any(), anyString(), any(BigDecimal.class))).thenReturn(testGoal);

        // When: Recalculate all goals
        goalProgressService.recalculateAllGoals(testUser);

        // Then: Should recalculate only active goals (goal1, goal2)
        // Note: updateGoalProgress is only called if calculated amount differs from current amount
        // Since we're using empty transactions and accounts, calculated will be 0, which differs from 100 and 200
        verify(goalService, times(2)).updateGoalProgress(any(), anyString(), any(BigDecimal.class));
    }

    // Helper methods
    private TransactionTable createTransaction(String transactionId, String goalId, BigDecimal amount) {
        TransactionTable tx = new TransactionTable();
        tx.setTransactionId(transactionId);
        tx.setUserId(userId);
        tx.setGoalId(goalId);
        tx.setAmount(amount);
        tx.setTransactionDate(LocalDate.now().toString());
        return tx;
    }

    private AccountTable createAccount(String accountId, BigDecimal balance) {
        AccountTable account = new AccountTable();
        account.setAccountId(accountId);
        account.setUserId(userId);
        account.setBalance(balance);
        return account;
    }

    private GoalTable createGoal(String goalId, boolean active) {
        GoalTable goal = new GoalTable();
        goal.setGoalId(goalId);
        goal.setUserId(userId);
        goal.setActive(active);
        goal.setTargetAmount(new BigDecimal("1000.00"));
        goal.setCurrentAmount(new BigDecimal("0.00"));
        return goal;
    }
}

