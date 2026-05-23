package com.budgetbuddy.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.budgetbuddy.model.dynamodb.GoalTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.repository.dynamodb.GoalRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.junit.jupiter.api.Test;

/** G-BUG-1: getRoundUpTotal must actually sum round-up contribution rows. */
@SuppressFBWarnings(
        value = "THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION",
        justification = "JUnit idiom")
final class GoalRoundUpTotalTest {

    private static final String USER = "u1";
    private static final String GOAL_ID = "g1";

    @Test
    void sumsOnlyRoundUpContributionsTaggedWithSourceTxId() {
        final TransactionRepository txRepo = mock(TransactionRepository.class);
        final GoalRepository goalRepo = mock(GoalRepository.class);
        when(txRepo.findByUserIdAndGoalId(eq(USER), eq(GOAL_ID)))
                .thenReturn(
                        List.of(
                                roundUp("0.35", LocalDate.now().minusDays(5)),
                                roundUp("0.50", LocalDate.now().minusDays(10)),
                                // Manual contribution to the same goal — must be excluded.
                                manual("25.00", LocalDate.now().minusDays(2)),
                                // Soft-deleted round-up — must be excluded.
                                softDeletedRoundUp("0.10", LocalDate.now().minusDays(3))));

        final GoalTable goal = goal();
        final GoalRoundUpService svc =
                new GoalRoundUpService(goalRepo, txRepo, mock(GoalProgressService.class), mock(TransactionService.class));

        assertEquals(new BigDecimal("0.85"), svc.getRoundUpTotal(goal, USER, 30));
    }

    @Test
    void excludesContributionsOutsideTheWindow() {
        final TransactionRepository txRepo = mock(TransactionRepository.class);
        final GoalRepository goalRepo = mock(GoalRepository.class);
        when(txRepo.findByUserIdAndGoalId(eq(USER), eq(GOAL_ID)))
                .thenReturn(
                        List.of(
                                roundUp("0.50", LocalDate.now().minusDays(5)),
                                roundUp("1.00", LocalDate.now().minusDays(100))));
        final GoalRoundUpService svc =
                new GoalRoundUpService(goalRepo, txRepo, mock(GoalProgressService.class), mock(TransactionService.class));
        // Only the 5-days-ago row falls within the 30-day window.
        assertEquals(new BigDecimal("0.50"), svc.getRoundUpTotal(goal(), USER, 30));
    }

    @Test
    void zeroForEmptyInputs() {
        final TransactionRepository txRepo = mock(TransactionRepository.class);
        final GoalRepository goalRepo = mock(GoalRepository.class);
        when(txRepo.findByUserIdAndGoalId(eq(USER), eq(GOAL_ID))).thenReturn(List.of());
        final GoalRoundUpService svc =
                new GoalRoundUpService(goalRepo, txRepo, mock(GoalProgressService.class), mock(TransactionService.class));
        assertEquals(BigDecimal.ZERO.setScale(2), svc.getRoundUpTotal(goal(), USER, 30));
    }

    private static TransactionTable roundUp(final String amount, final LocalDate date) {
        final TransactionTable t = new TransactionTable();
        t.setAmount(new BigDecimal(amount));
        t.setTransactionDate(date.format(DateTimeFormatter.ISO_LOCAL_DATE));
        t.setRoundUpSourceTransactionId("source-" + amount);
        return t;
    }

    private static TransactionTable softDeletedRoundUp(final String amount, final LocalDate date) {
        final TransactionTable t = roundUp(amount, date);
        t.setDeletedAt(java.time.Instant.now());
        return t;
    }

    private static TransactionTable manual(final String amount, final LocalDate date) {
        final TransactionTable t = new TransactionTable();
        t.setAmount(new BigDecimal(amount));
        t.setTransactionDate(date.format(DateTimeFormatter.ISO_LOCAL_DATE));
        // No roundUpSourceTransactionId → not a round-up contribution.
        return t;
    }

    private static GoalTable goal() {
        final GoalTable g = new GoalTable();
        g.setGoalId(GOAL_ID);
        return g;
    }
}
