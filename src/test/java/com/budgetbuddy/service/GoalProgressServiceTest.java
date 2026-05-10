package com.budgetbuddy.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.GoalTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.GoalRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Comprehensive tests for GoalProgressService Tests goal progress calculation, completion
 * detection, and edge cases
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class GoalProgressServiceTest {

    @Mock private GoalRepository goalRepository;

    @Mock private GoalService goalService;

    @Mock private TransactionRepository transactionRepository;

    @Mock private AccountRepository accountRepository;

    @InjectMocks private GoalProgressService goalProgressService;

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
    void testCalculateAndUpdateProgressWithAssignedTransactions() {
        // Given: Goal with assigned transactions
        final TransactionTable tx1 = createTransaction("tx1", goalId, new BigDecimal("100.00"));
        final TransactionTable tx2 = createTransaction("tx2", goalId, new BigDecimal("200.00"));
        final List<TransactionTable> assignedTransactions = Arrays.asList(tx1, tx2);

        when(goalService.getGoal(testUser, goalId)).thenReturn(testGoal);
        when(transactionRepository.findByUserIdAndGoalId(userId, goalId))
                .thenReturn(assignedTransactions);
        when(accountRepository.findByUserId(userId)).thenReturn(new ArrayList<>());
        when(goalService.updateGoalProgress(eq(testUser), eq(goalId), any(BigDecimal.class)))
                .thenReturn(testGoal);

        // When: Calculate progress
        final GoalTable result = goalProgressService.calculateAndUpdateProgress(testUser, goalId);

        // Then: Progress should be updated with transaction contributions
        verify(goalService)
                .updateGoalProgress(eq(testUser), eq(goalId), eq(new BigDecimal("300.00")));
        assertNotNull(result);
    }

    @Test
    void testCalculateAndUpdateProgressWithNoAssignedTransactionsReportsZero() {
        // Given: Goal with no assigned transactions.
        // Post-O13, we no longer fabricate a "10% of income" fallback — the
        // prior behaviour credited phantom progress the user hadn't earned.
        // With no tagged transactions and no linked accounts, progress is 0.
        final List<TransactionTable> noAssignedTransactions = new ArrayList<>();
        final TransactionTable incomeTx1 = createTransaction("income1", null, new BigDecimal("5000.00"));
        incomeTx1.setTransactionType("INCOME");
        final TransactionTable incomeTx2 = createTransaction("income2", null, new BigDecimal("3000.00"));
        incomeTx2.setTransactionType("INCOME");
        final List<TransactionTable> allTransactions = Arrays.asList(incomeTx1, incomeTx2);

        when(goalService.getGoal(testUser, goalId)).thenReturn(testGoal);
        when(transactionRepository.findByUserIdAndGoalId(userId, goalId))
                .thenReturn(noAssignedTransactions);
        when(transactionRepository.findByUserId(userId, 0, Integer.MAX_VALUE))
                .thenReturn(allTransactions);
        when(accountRepository.findByUserId(userId)).thenReturn(new ArrayList<>());
        when(goalService.updateGoalProgress(eq(testUser), eq(goalId), any(BigDecimal.class)))
                .thenReturn(testGoal);

        // When: Calculate progress
        final GoalTable result = goalProgressService.calculateAndUpdateProgress(testUser, goalId);

        // Then: no updateGoalProgress call — calculated progress (0) equals
        // existing currentAmount (0), so the service short-circuits.
        verify(goalService, org.mockito.Mockito.never())
                .updateGoalProgress(eq(testUser), eq(goalId), any(BigDecimal.class));
        assertNotNull(result);
    }

    @Test
    void testCalculateAndUpdateProgressWithGoalAssociatedAccounts() {
        // Given: Goal with associated accounts
        // Increase target amount so it's not capped
        testGoal.setTargetAmount(new BigDecimal("5000.00"));
        final AccountTable account1 = createAccount("acc1", new BigDecimal("2000.00"));
        final AccountTable account2 = createAccount("acc2", new BigDecimal("1000.00"));
        testGoal.setAccountIds(Arrays.asList("acc1", "acc2"));

        final TransactionTable tx1 = createTransaction("tx1", goalId, new BigDecimal("100.00"));
        final List<TransactionTable> assignedTransactions = Arrays.asList(tx1);

        when(goalService.getGoal(testUser, goalId)).thenReturn(testGoal);
        when(transactionRepository.findByUserIdAndGoalId(userId, goalId))
                .thenReturn(assignedTransactions);
        when(accountRepository.findById("acc1")).thenReturn(Optional.of(account1));
        when(accountRepository.findById("acc2")).thenReturn(Optional.of(account2));
        when(goalService.updateGoalProgress(eq(testUser), eq(goalId), any(BigDecimal.class)))
                .thenReturn(testGoal);

        // When: Calculate progress
        final GoalTable result = goalProgressService.calculateAndUpdateProgress(testUser, goalId);

        // Then: Progress should include full account balances (100 + 2000 + 1000 = 3100)
        verify(goalService)
                .updateGoalProgress(eq(testUser), eq(goalId), eq(new BigDecimal("3100.00")));
        assertNotNull(result);
    }

    @Test
    void testCalculateAndUpdateProgressWithNoAssociatedAccountsUsesTaggedTransactionsOnly() {
        // Given: Goal with no associated accounts, one tagged transaction.
        // Post-O13, with no linked accounts we no longer apply a "10% of
        // unassociated account balances" fallback — progress is exactly the
        // sum of tagged transactions (100.00).
        final AccountTable account1 = createAccount("acc1", new BigDecimal("5000.00"));
        final List<AccountTable> allAccounts = Arrays.asList(account1);

        final TransactionTable tx1 = createTransaction("tx1", goalId, new BigDecimal("100.00"));
        final List<TransactionTable> assignedTransactions = Arrays.asList(tx1);

        when(goalService.getGoal(testUser, goalId)).thenReturn(testGoal);
        when(transactionRepository.findByUserIdAndGoalId(userId, goalId))
                .thenReturn(assignedTransactions);
        when(accountRepository.findByUserId(userId)).thenReturn(allAccounts);
        when(goalService.updateGoalProgress(eq(testUser), eq(goalId), any(BigDecimal.class)))
                .thenReturn(testGoal);

        // When: Calculate progress
        final GoalTable result = goalProgressService.calculateAndUpdateProgress(testUser, goalId);

        // Then: Progress should equal the sum of assigned transactions only
        verify(goalService)
                .updateGoalProgress(eq(testUser), eq(goalId), eq(new BigDecimal("100.00")));
        assertNotNull(result);
    }

    @Test
    void testCalculateAndUpdateProgressCapsAtTargetAmount() {
        // Given: Goal with progress exceeding target
        testGoal.setTargetAmount(new BigDecimal("1000.00"));
        final TransactionTable tx1 = createTransaction("tx1", goalId, new BigDecimal("5000.00"));
        final List<TransactionTable> assignedTransactions = Arrays.asList(tx1);

        when(goalService.getGoal(testUser, goalId)).thenReturn(testGoal);
        when(transactionRepository.findByUserIdAndGoalId(userId, goalId))
                .thenReturn(assignedTransactions);
        when(accountRepository.findByUserId(userId)).thenReturn(new ArrayList<>());
        when(goalService.updateGoalProgress(eq(testUser), eq(goalId), any(BigDecimal.class)))
                .thenReturn(testGoal);

        // When: Calculate progress
        final GoalTable result = goalProgressService.calculateAndUpdateProgress(testUser, goalId);

        // Then: Progress should be capped at target amount (1000.00)
        verify(goalService)
                .updateGoalProgress(eq(testUser), eq(goalId), eq(new BigDecimal("1000.00")));
        assertNotNull(result);
    }

    @Test
    void testCalculateAndUpdateProgressFiltersNegativeAmounts() {
        // Given: Goal with both positive and negative transactions
        final TransactionTable tx1 = createTransaction("tx1", goalId, new BigDecimal("100.00"));
        final TransactionTable tx2 =
                createTransaction("tx2", goalId, new BigDecimal("-50.00")); // Negative
        final TransactionTable tx3 = createTransaction("tx3", goalId, new BigDecimal("200.00"));
        final List<TransactionTable> assignedTransactions = Arrays.asList(tx1, tx2, tx3);

        when(goalService.getGoal(testUser, goalId)).thenReturn(testGoal);
        when(transactionRepository.findByUserIdAndGoalId(userId, goalId))
                .thenReturn(assignedTransactions);
        when(accountRepository.findByUserId(userId)).thenReturn(new ArrayList<>());
        when(goalService.updateGoalProgress(eq(testUser), eq(goalId), any(BigDecimal.class)))
                .thenReturn(testGoal);

        // When: Calculate progress
        final GoalTable result = goalProgressService.calculateAndUpdateProgress(testUser, goalId);

        // Then: Only positive amounts should count (100 + 200 = 300)
        verify(goalService)
                .updateGoalProgress(eq(testUser), eq(goalId), eq(new BigDecimal("300.00")));
        assertNotNull(result);
    }

    @Test
    void testCalculateAndUpdateProgressWithInactiveGoal() {
        // Given: Inactive goal
        testGoal.setActive(false);

        when(goalService.getGoal(testUser, goalId)).thenReturn(testGoal);

        // When: Calculate progress
        final GoalTable result = goalProgressService.calculateAndUpdateProgress(testUser, goalId);

        // Then: Should return goal without updating progress
        verify(goalService, never()).updateGoalProgress(any(), any(), any());
        assertNotNull(result);
        assertFalse(result.getActive());
    }

    @Test
    void testCalculateAndUpdateProgressWithNullUser() {
        // When/Then: Should throw exception
        assertThrows(
                Exception.class,
                () -> {
                    goalProgressService.calculateAndUpdateProgress(null, goalId);
                });
    }

    @Test
    void testCalculateAndUpdateProgressWithNullGoalId() {
        // When/Then: Should throw exception
        assertThrows(
                Exception.class,
                () -> {
                    goalProgressService.calculateAndUpdateProgress(testUser, null);
                });
    }

    @Test
    void testRecalculateAllGoals() {
        // Given: Multiple active goals with different current amounts to trigger updates
        final GoalTable goal1 = createGoal("goal1", true);
        goal1.setCurrentAmount(new BigDecimal("100.00")); // Different from calculated
        final GoalTable goal2 = createGoal("goal2", true);
        goal2.setCurrentAmount(new BigDecimal("200.00")); // Different from calculated
        final GoalTable goal3 = createGoal("goal3", false); // Inactive
        final List<GoalTable> allGoals = Arrays.asList(goal1, goal2, goal3);

        when(goalRepository.findByUserId(userId)).thenReturn(allGoals);
        // Return the specific goal for each call
        when(goalService.getGoal(eq(testUser), eq("goal1"))).thenReturn(goal1);
        when(goalService.getGoal(eq(testUser), eq("goal2"))).thenReturn(goal2);
        when(transactionRepository.findByUserIdAndGoalId(anyString(), anyString()))
                .thenReturn(new ArrayList<>());
        when(accountRepository.findByUserId(anyString())).thenReturn(new ArrayList<>());
        when(goalService.updateGoalProgress(any(), anyString(), any(BigDecimal.class)))
                .thenReturn(testGoal);

        // When: Recalculate all goals
        goalProgressService.recalculateAllGoals(testUser);

        // Then: Should recalculate only active goals (goal1, goal2)
        // Note: updateGoalProgress is only called if calculated amount differs from current amount
        // Since we're using empty transactions and accounts, calculated will be 0, which differs
        // from 100 and 200
        verify(goalService, times(2)).updateGoalProgress(any(), anyString(), any(BigDecimal.class));
    }

    // Helper methods
    private TransactionTable createTransaction(
            final String transactionId, final String goalId, final BigDecimal amount) {
        final TransactionTable tx = new TransactionTable();
        tx.setTransactionId(transactionId);
        tx.setUserId(userId);
        tx.setGoalId(goalId);
        tx.setAmount(amount);
        tx.setTransactionDate(LocalDate.now().toString());
        return tx;
    }

    private AccountTable createAccount(final String accountId, final BigDecimal balance) {
        final AccountTable account = new AccountTable();
        account.setAccountId(accountId);
        account.setUserId(userId);
        account.setBalance(balance);
        return account;
    }

    private GoalTable createGoal(final String goalId, final boolean active) {
        final GoalTable goal = new GoalTable();
        goal.setGoalId(goalId);
        goal.setUserId(userId);
        goal.setActive(active);
        goal.setTargetAmount(new BigDecimal("1000.00"));
        goal.setCurrentAmount(new BigDecimal("0.00"));
        return goal;
    }
}
