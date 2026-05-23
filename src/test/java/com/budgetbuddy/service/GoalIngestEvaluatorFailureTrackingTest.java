package com.budgetbuddy.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.budgetbuddy.model.dynamodb.GoalTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.notification.DataChangeNotificationService;
import com.budgetbuddy.repository.dynamodb.GoalRepository;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * G-RISK-3: per-goal evaluation failures are tracked with a
 * consecutive-failure streak so ops can surface persistently broken
 * goals (vs one-off transients).
 */
@SuppressFBWarnings(
        value = "THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION",
        justification = "JUnit idiom")
final class GoalIngestEvaluatorFailureTrackingTest {

    @Test
    void streakIncrementsAcrossConsecutiveEvaluateCalls() {
        final GoalRepository goalRepo = mock(GoalRepository.class);
        final GoalProgressService progressService = mock(GoalProgressService.class);
        final DataChangeNotificationService notifier = mock(DataChangeNotificationService.class);
        final GoalTable goal = goal("g1");
        when(goalRepo.findByUserId("u1")).thenReturn(List.of(goal));
        when(progressService.calculateAndUpdateProgress(any(UserTable.class), anyString()))
                .thenThrow(new RuntimeException("ddb conditional check failed"));

        final GoalIngestEvaluator evaluator =
                new GoalIngestEvaluator(goalRepo, progressService, notifier);
        evaluator.evaluate(user("u1"));
        evaluator.evaluate(user("u1"));
        evaluator.evaluate(user("u1"));

        final Map<String, GoalIngestEvaluator.FailureInfo> snapshot = evaluator.failingGoalsSnapshot();
        assertTrue(snapshot.containsKey("g1"));
        assertEquals(3, snapshot.get("g1").consecutiveFailures());
        assertNotNull(snapshot.get("g1").lastFailedAt());
    }

    @Test
    void successClearsPriorFailureEntry() {
        final GoalRepository goalRepo = mock(GoalRepository.class);
        final GoalProgressService progressService = mock(GoalProgressService.class);
        final DataChangeNotificationService notifier = mock(DataChangeNotificationService.class);
        final GoalTable goal = goal("g1");
        when(goalRepo.findByUserId("u1")).thenReturn(List.of(goal));
        // First call throws, second call succeeds.
        when(progressService.calculateAndUpdateProgress(any(UserTable.class), anyString()))
                .thenThrow(new RuntimeException("transient"))
                .thenReturn(goal);

        final GoalIngestEvaluator evaluator =
                new GoalIngestEvaluator(goalRepo, progressService, notifier);
        evaluator.evaluate(user("u1"));
        assertTrue(evaluator.failingGoalsSnapshot().containsKey("g1"));
        evaluator.evaluate(user("u1"));
        assertFalse(
                evaluator.failingGoalsSnapshot().containsKey("g1"),
                "A successful evaluation must clear the prior failure entry");
    }

    private static GoalTable goal(final String id) {
        final GoalTable g = new GoalTable();
        g.setGoalId(id);
        g.setUserId("u1");
        g.setActive(true);
        return g;
    }

    private static UserTable user(final String id) {
        final UserTable u = new UserTable();
        u.setUserId(id);
        return u;
    }
}
