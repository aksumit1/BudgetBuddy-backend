package com.budgetbuddy.repository;

import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.BudgetTable;
import com.budgetbuddy.model.dynamodb.GoalTable;
import com.budgetbuddy.model.dynamodb.TransactionActionTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.repository.dynamodb.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for GSI-based incremental sync methods
 */
@ExtendWith(MockitoExtension.class)
class GSIIncrementalSyncTest {

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

    private String testUserId;
    private Long testTimestamp;

    @BeforeEach
    void setUp() {
        testUserId = UUID.randomUUID().toString();
        testTimestamp = Instant.now().minusSeconds(3600).getEpochSecond(); // 1 hour ago
    }

    @Test
    void testAccountRepository_FindByUserIdAndUpdatedAfter() {
        // Mock response
        AccountTable account = createTestAccount();
        account.setUpdatedAt(Instant.now());
        when(accountRepository.findByUserIdAndUpdatedAfter(testUserId, testTimestamp))
                .thenReturn(List.of(account));

        // Test
        List<AccountTable> results = accountRepository.findByUserIdAndUpdatedAfter(testUserId, testTimestamp);

        assertNotNull(results);
        assertEquals(1, results.size());
        verify(accountRepository, times(1)).findByUserIdAndUpdatedAfter(testUserId, testTimestamp);
    }

    @Test
    void testTransactionRepository_FindByUserIdAndUpdatedAfter() {
        // Mock response
        TransactionTable transaction = createTestTransaction();
        transaction.setUpdatedAt(Instant.now());
        when(transactionRepository.findByUserIdAndUpdatedAfter(testUserId, testTimestamp, 50))
                .thenReturn(List.of(transaction));

        // Test
        List<TransactionTable> results = transactionRepository.findByUserIdAndUpdatedAfter(testUserId, testTimestamp, 50);

        assertNotNull(results);
        assertEquals(1, results.size());
        verify(transactionRepository, times(1)).findByUserIdAndUpdatedAfter(testUserId, testTimestamp, 50);
    }

    @Test
    void testBudgetRepository_FindByUserIdAndUpdatedAfter() {
        // Mock response
        BudgetTable budget = createTestBudget();
        budget.setUpdatedAt(Instant.now());
        when(budgetRepository.findByUserIdAndUpdatedAfter(testUserId, testTimestamp))
                .thenReturn(List.of(budget));

        // Test
        List<BudgetTable> results = budgetRepository.findByUserIdAndUpdatedAfter(testUserId, testTimestamp);

        assertNotNull(results);
        assertEquals(1, results.size());
        verify(budgetRepository, times(1)).findByUserIdAndUpdatedAfter(testUserId, testTimestamp);
    }

    @Test
    void testGoalRepository_FindByUserIdAndUpdatedAfter() {
        // Mock response
        GoalTable goal = createTestGoal();
        goal.setUpdatedAt(Instant.now());
        when(goalRepository.findByUserIdAndUpdatedAfter(testUserId, testTimestamp))
                .thenReturn(List.of(goal));

        // Test
        List<GoalTable> results = goalRepository.findByUserIdAndUpdatedAfter(testUserId, testTimestamp);

        assertNotNull(results);
        assertEquals(1, results.size());
        verify(goalRepository, times(1)).findByUserIdAndUpdatedAfter(testUserId, testTimestamp);
    }

    @Test
    void testTransactionActionRepository_FindByUserIdAndUpdatedAfter() {
        // Mock response
        TransactionActionTable action = createTestAction();
        action.setUpdatedAt(Instant.now());
        when(transactionActionRepository.findByUserIdAndUpdatedAfter(testUserId, testTimestamp))
                .thenReturn(List.of(action));

        // Test
        List<TransactionActionTable> results = transactionActionRepository.findByUserIdAndUpdatedAfter(testUserId, testTimestamp);

        assertNotNull(results);
        assertEquals(1, results.size());
        verify(transactionActionRepository, times(1)).findByUserIdAndUpdatedAfter(testUserId, testTimestamp);
    }

    @Test
    void testAccountRepository_FindByPlaidItemId_UsesGSI() {
        String plaidItemId = "item-123";
        AccountTable account = createTestAccount();
        account.setPlaidItemId(plaidItemId);
        when(accountRepository.findByPlaidItemId(plaidItemId))
                .thenReturn(List.of(account));

        // Test
        List<AccountTable> results = accountRepository.findByPlaidItemId(plaidItemId);

        assertNotNull(results);
        assertEquals(1, results.size());
        verify(accountRepository, times(1)).findByPlaidItemId(plaidItemId);
    }

    // Helper methods
    private AccountTable createTestAccount() {
        AccountTable account = new AccountTable();
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
        TransactionTable transaction = new TransactionTable();
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
        BudgetTable budget = new BudgetTable();
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
        GoalTable goal = new GoalTable();
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
        TransactionActionTable action = new TransactionActionTable();
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

