package com.budgetbuddy.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import com.budgetbuddy.model.dynamodb.GoalTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.notification.DataChangeNotificationService;
import com.budgetbuddy.repository.dynamodb.GoalRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Flow 6 / O3 — post-transaction-ingest goal evaluator.
 *
 * <p>Before this service, goal progress only updated on an explicit {@code POST
 * /api/goals/{id}/recalculate}. Users rarely hit that endpoint, so the numbers in the goals tab
 * drifted from reality. Completion was even worse: a user could pass 100% on a Friday and the app
 * wouldn't notice until they opened the recalc flow Monday morning.
 *
 * <p>This evaluator is called after every transaction mutation (update, create, delete) for the
 * affected user. It walks the user's active goals, runs them through {@link
 * GoalProgressService#calculateAndUpdateProgress}, then checks whether the fresh progress just
 * crossed a milestone band (25 / 50 / 75 / 100). Newly crossed bands emit a milestone push via
 * {@link DataChangeNotificationService#notifyGoalMilestoneReached}. 100 also flips {@code
 * completed}.
 *
 * <p>Rate-limited by persisting {@code lastMilestoneReached} per goal. So a user whose spend
 * oscillates between 72 % and 78 % doesn't get spammed with "75 % reached" alerts.
 */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
// SpotBugs flags constructor-injected Spring beans as EI_EXPOSE_REP2,
// but Spring's IoC container intentionally shares the same bean across
// callers — defensive-copying it would break dependency injection.
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Spring constructor injection — beans are shared by design")
@SuppressWarnings({"PMD.LawOfDemeter", "PMD.AvoidCatchingGenericException"})
@Service
public class GoalIngestEvaluator {

    private static final Logger LOGGER = LoggerFactory.getLogger(GoalIngestEvaluator.class);
    private static final int[] MILESTONES = {25, 50, 75, 100};

    private final GoalRepository goalRepository;
    private final GoalProgressService goalProgressService;
    private final DataChangeNotificationService notificationService;

    public GoalIngestEvaluator(
            final GoalRepository goalRepository,
            final GoalProgressService goalProgressService,
            final DataChangeNotificationService notificationService) {
        this.goalRepository = goalRepository;
        this.goalProgressService = goalProgressService;
        this.notificationService = notificationService;
    }

    /**
     * Walk every live goal for the user and recompute + emit milestone notifications. Swallows
     * per-goal exceptions — one bad goal shouldn't derail the whole pass.
     */
    public void evaluate(final UserTable user) {
        if (user == null || user.getUserId() == null) {
            return;
        }
        try {
            final List<GoalTable> goals = goalRepository.findByUserId(user.getUserId());
            for (final GoalTable goal : goals) {
                if (Boolean.FALSE.equals(goal.getActive())) {
                    continue;
                }
                if (goal.getDeletedAt() != null) {
                    continue;
                }
                if ("manual".equalsIgnoreCase(goal.getProgressMode())) {
                    continue;
                }

                try {
                    final GoalTable updated =
                            goalProgressService.calculateAndUpdateProgress(user, goal.getGoalId());
                    emitMilestoneIfCrossed(updated);
                } catch (Exception e) {
                    LOGGER.warn(
                            "Goal evaluate failed for goal {}: {}",
                            goal.getGoalId(),
                            e.getMessage());
                }
            }
        } catch (Exception e) {
            LOGGER.warn(
                    "Goal ingest evaluation failed for user {}: {}",
                    user.getUserId(),
                    e.getMessage());
        }
    }

    private void emitMilestoneIfCrossed(final GoalTable goal) {
        final BigDecimal current =
                goal.getCurrentAmount() == null ? BigDecimal.ZERO : goal.getCurrentAmount();
        final BigDecimal target = goal.getTargetAmount();
        if (target == null || target.signum() <= 0) {
            return;
        }

        final double percent = current.doubleValue() / target.doubleValue() * 100.0;
        int highestCrossed = 0;
        for (final int m : MILESTONES) {
            if (percent >= m) {
                highestCrossed = m;
            }
        }
        final int alreadyPushed =
                goal.getLastMilestoneReached() == null ? 0 : goal.getLastMilestoneReached();
        if (highestCrossed <= alreadyPushed) {
            return;
        }

        final boolean justCompleted = highestCrossed == 100;
        notificationService.notifyGoalMilestoneReached(
                goal.getUserId(), goal.getGoalId(), goal.getName(), highestCrossed, justCompleted);
        goal.setLastMilestoneReached(highestCrossed);
        goal.setUpdatedAt(Instant.now());
        goalRepository.save(goal);
        LOGGER.info(
                "Pushed milestone {} for goal {} (user {})",
                highestCrossed,
                goal.getGoalId(),
                goal.getUserId());
    }
}
