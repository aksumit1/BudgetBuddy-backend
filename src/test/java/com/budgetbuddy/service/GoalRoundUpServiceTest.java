package com.budgetbuddy.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.budgetbuddy.model.dynamodb.GoalTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.repository.dynamodb.GoalRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Tests for GoalRoundUpService */
@ExtendWith(MockitoExtension.class)
class GoalRoundUpServiceTest {

    @Mock private GoalRepository goalRepository;

    @Mock private TransactionRepository transactionRepository;

    @Mock private GoalProgressService goalProgressService;

    @Mock private TransactionService transactionService;

    private GoalRoundUpService roundUpService;
    private GoalTable testGoal;
    private TransactionTable testTransaction;

    @BeforeEach
    void setUp() {
        roundUpService =
                new GoalRoundUpService(
                        goalRepository,
                        transactionRepository,
                        goalProgressService,
                        transactionService);

        testGoal = new GoalTable();
        testGoal.setGoalId("test-goal-id");

        testTransaction = new TransactionTable();
        testTransaction.setTransactionId("test-tx-id");
    }

    @Test
    void testCalculateRoundUpExactDollar() {
        final BigDecimal amount = new BigDecimal("-5.00");
        final BigDecimal roundUp = roundUpService.calculateRoundUp(amount);

        assertTrue(roundUp.compareTo(BigDecimal.ZERO) == 0);
    }

    @Test
    void testCalculateRoundUpNeedsRoundUp() {
        final BigDecimal amount = new BigDecimal("-4.23");
        final BigDecimal roundUp = roundUpService.calculateRoundUp(amount);

        assertTrue(roundUp.compareTo(new BigDecimal("0.77")) == 0);
    }

    @Test
    void testCalculateRoundUpLargeAmount() {
        final BigDecimal amount = new BigDecimal("-123.45");
        final BigDecimal roundUp = roundUpService.calculateRoundUp(amount);

        assertTrue(roundUp.compareTo(new BigDecimal("0.55")) == 0);
    }

    @Test
    void testCalculateRoundUpPositiveAmount() {
        final BigDecimal amount = new BigDecimal("4.23");
        final BigDecimal roundUp = roundUpService.calculateRoundUp(amount);

        assertTrue(roundUp.compareTo(BigDecimal.ZERO) == 0); // Only round up expenses
    }

    @Test
    void testCalculateRoundUpNullAmount() {
        final BigDecimal roundUp = roundUpService.calculateRoundUp(null);

        assertTrue(roundUp.compareTo(BigDecimal.ZERO) == 0);
    }

    @Test
    void testProcessRoundUpValidTransaction() {
        testTransaction.setAmount(new BigDecimal("-4.23"));
        testTransaction.setGoalId("test-goal-id");

        when(goalRepository.findById("test-goal-id")).thenReturn(Optional.of(testGoal));

        // Should not throw exception
        assertDoesNotThrow(() -> roundUpService.processRoundUp(testTransaction, "test-goal-id"));
    }

    @Test
    void testProcessRoundUpNullTransaction() {
        assertDoesNotThrow(() -> roundUpService.processRoundUp(null, "test-goal-id"));
    }

    @Test
    void testProcessRoundUpNullGoalId() {
        assertDoesNotThrow(() -> roundUpService.processRoundUp(testTransaction, null));
    }

    @Test
    void testProcessRoundUpGoalNotFound() {
        testTransaction.setAmount(new BigDecimal("-4.23"));

        when(goalRepository.findById("test-goal-id")).thenReturn(Optional.empty());

        assertDoesNotThrow(() -> roundUpService.processRoundUp(testTransaction, "test-goal-id"));
    }

    @Test
    void testEnableRoundUp() {
        when(goalRepository.findById("test-goal-id")).thenReturn(Optional.of(testGoal));
        doNothing().when(goalRepository).save(any(GoalTable.class));

        assertDoesNotThrow(() -> roundUpService.enableRoundUp("test-goal-id"));

        verify(goalRepository).save(any(GoalTable.class));
    }

    @Test
    void testEnableRoundUpGoalNotFound() {
        when(goalRepository.findById("test-goal-id")).thenReturn(Optional.empty());

        assertThrows(
                IllegalArgumentException.class, () -> roundUpService.enableRoundUp("test-goal-id"));
    }

    @Test
    void testDisableRoundUp() {
        when(goalRepository.findById("test-goal-id")).thenReturn(Optional.of(testGoal));
        doNothing().when(goalRepository).save(any(GoalTable.class));

        assertDoesNotThrow(() -> roundUpService.disableRoundUp("test-goal-id"));

        verify(goalRepository).save(any(GoalTable.class));
    }

    @Test
    void testGetRoundUpTotal() {
        when(transactionRepository.findByUserIdAndGoalId("user-id", "test-goal-id"))
                .thenReturn(java.util.Collections.emptyList());

        final BigDecimal total = roundUpService.getRoundUpTotal(testGoal, "user-id", 30);

        assertTrue(total.compareTo(BigDecimal.ZERO) == 0);
    }
}
