package com.budgetbuddy.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.BudgetTable;
import com.budgetbuddy.model.dynamodb.GoalTable;
import com.budgetbuddy.model.dynamodb.TransactionActionTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.BudgetRepository;
import com.budgetbuddy.repository.dynamodb.GoalRepository;
import com.budgetbuddy.repository.dynamodb.TransactionActionRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for GSI-based incremental sync methods */
@ExtendWith(MockitoExtension.class)
class GSIIncrementalSyncTest {

    @Mock private AccountRepository accountRepository;

    @Mock private TransactionRepository transactionRepository;

    @Mock private BudgetRepository budgetRepository;

    @Mock private GoalRepository goalRepository;

    @Mock private TransactionActionRepository transactionActionRepository;

    private String testUserId;
    private Long testTimestamp;

    @BeforeEach
    void setUp() {
        testUserId = UUID.randomUUID().toString();
        testTimestamp = Instant.now().minusSeconds(3600).getEpochSecond(); // 1 hour ago
    }

    @Test
    void testAccountRepositoryFindByUserIdAndUpdatedAfter() {
        // Mock response
        final AccountTable account = createTestAccount();
        account.setUpdatedAt(Instant.now());
        when(accountRepository.findByUserIdAndUpdatedAfter(testUserId, testTimestamp))
                .thenReturn(List.of(account));

        // Test
        final List<AccountTable> results =
                accountRepository.findByUserIdAndUpdatedAfter(testUserId, testTimestamp);

        assertNotNull(results);
        assertEquals(1, results.size());
        verify(accountRepository, times(1)).findByUserIdAndUpdatedAfter(testUserId, testTimestamp);
    }

    @Test
    void testTransactionRepositoryFindByUserIdAndUpdatedAfter() {
        // Mock response
        final TransactionTable transaction = createTestTransaction();
        transaction.setUpdatedAt(Instant.now());
        when(transactionRepository.findByUserIdAndUpdatedAfter(testUserId, testTimestamp, 50))
                .thenReturn(List.of(transaction));

        // Test
        final List<TransactionTable> results =
                transactionRepository.findByUserIdAndUpdatedAfter(testUserId, testTimestamp, 50);

        assertNotNull(results);
        assertEquals(1, results.size());
        verify(transactionRepository, times(1))
                .findByUserIdAndUpdatedAfter(testUserId, testTimestamp, 50);
    }

    @Test
    void testBudgetRepositoryFindByUserIdAndUpdatedAfter() {
        // Mock response
        final BudgetTable budget = createTestBudget();
        budget.setUpdatedAt(Instant.now());
        when(budgetRepository.findByUserIdAndUpdatedAfter(testUserId, testTimestamp))
                .thenReturn(List.of(budget));

        // Test
        final List<BudgetTable> results =
                budgetRepository.findByUserIdAndUpdatedAfter(testUserId, testTimestamp);

        assertNotNull(results);
        assertEquals(1, results.size());
        verify(budgetRepository, times(1)).findByUserIdAndUpdatedAfter(testUserId, testTimestamp);
    }

    @Test
    void testGoalRepositoryFindByUserIdAndUpdatedAfter() {
        // Mock response
        final GoalTable goal = createTestGoal();
        goal.setUpdatedAt(Instant.now());
        when(goalRepository.findByUserIdAndUpdatedAfter(testUserId, testTimestamp))
                .thenReturn(List.of(goal));

        // Test
        final List<GoalTable> results =
                goalRepository.findByUserIdAndUpdatedAfter(testUserId, testTimestamp);

        assertNotNull(results);
        assertEquals(1, results.size());
        verify(goalRepository, times(1)).findByUserIdAndUpdatedAfter(testUserId, testTimestamp);
    }

    @Test
    void testTransactionActionRepositoryFindByUserIdAndUpdatedAfter() {
        // Mock response
        final TransactionActionTable action = createTestAction();
        action.setUpdatedAt(Instant.now());
        when(transactionActionRepository.findByUserIdAndUpdatedAfter(testUserId, testTimestamp))
                .thenReturn(List.of(action));

        // Test
        final List<TransactionActionTable> results =
                transactionActionRepository.findByUserIdAndUpdatedAfter(testUserId, testTimestamp);

        assertNotNull(results);
        assertEquals(1, results.size());
        verify(transactionActionRepository, times(1))
                .findByUserIdAndUpdatedAfter(testUserId, testTimestamp);
    }

    @Test
    void testAccountRepositoryFindByPlaidItemIdUsesGSI() {
        final String plaidItemId = "item-123";
        final AccountTable account = createTestAccount();
        account.setPlaidItemId(plaidItemId);
        when(accountRepository.findByPlaidItemId(plaidItemId)).thenReturn(List.of(account));

        // Test
        final List<AccountTable> results = accountRepository.findByPlaidItemId(plaidItemId);

        assertNotNull(results);
        assertEquals(1, results.size());
        verify(accountRepository, times(1)).findByPlaidItemId(plaidItemId);
    }

    // Helper methods
    private AccountTable createTestAccount() {
        final AccountTable account = new AccountTable();
        account.setAccountId(UUID.randomUUID().toString());
        account.setUserId(testUserId);
        account.setAccountName("Test Account");
        account.setAccountType("checking");
        account.setBalance(BigDecimal.valueOf(1000.00));
        account.setActive(true);
        account.setCreatedAt(Instant.now());
        account.setUpdatedAt(Instant.now());
        return account;
    }

    private TransactionTable createTestTransaction() {
        final TransactionTable transaction = new TransactionTable();
        transaction.setTransactionId(UUID.randomUUID().toString());
        transaction.setUserId(testUserId);
        transaction.setAccountId(UUID.randomUUID().toString());
        transaction.setAmount(BigDecimal.valueOf(-50.00));
        transaction.setDescription("Test Transaction");
        transaction.setTransactionDate("2024-12-01");
        transaction.setCreatedAt(Instant.now());
        transaction.setUpdatedAt(Instant.now());
        return transaction;
    }

    private BudgetTable createTestBudget() {
        final BudgetTable budget = new BudgetTable();
        budget.setBudgetId(UUID.randomUUID().toString());
        budget.setUserId(testUserId);
        budget.setCategory("Food");
        budget.setMonthlyLimit(BigDecimal.valueOf(500.00));
        budget.setCurrentSpent(BigDecimal.valueOf(100.00));
        budget.setCreatedAt(Instant.now());
        budget.setUpdatedAt(Instant.now());
        return budget;
    }

    private GoalTable createTestGoal() {
        final GoalTable goal = new GoalTable();
        goal.setGoalId(UUID.randomUUID().toString());
        goal.setUserId(testUserId);
        goal.setName("Test Goal");
        goal.setTargetAmount(BigDecimal.valueOf(10000.00));
        goal.setCurrentAmount(BigDecimal.valueOf(1000.00));
        goal.setTargetDate("2025-12-31");
        goal.setActive(true);
        goal.setCreatedAt(Instant.now());
        goal.setUpdatedAt(Instant.now());
        return goal;
    }

    private TransactionActionTable createTestAction() {
        final TransactionActionTable action = new TransactionActionTable();
        action.setActionId(UUID.randomUUID().toString());
        action.setUserId(testUserId);
        action.setTransactionId(UUID.randomUUID().toString());
        action.setTitle("Test Action");
        action.setIsCompleted(false);
        action.setCreatedAt(Instant.now());
        action.setUpdatedAt(Instant.now());
        return action;
    }
}
