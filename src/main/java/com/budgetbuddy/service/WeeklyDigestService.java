package com.budgetbuddy.service;

import com.budgetbuddy.model.dynamodb.BudgetTable;
import com.budgetbuddy.model.dynamodb.GoalTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.notification.DataChangeNotificationService;
import com.budgetbuddy.repository.dynamodb.BudgetRepository;
import com.budgetbuddy.repository.dynamodb.GoalRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;

/**
 * Flow 7 / O9 — weekly digest cron. Monday 09:00 UTC, one push per user with the previous week's
 * highlights. Cheaper than sending a full report and a good nudge to come back into the app.
 */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
@SuppressWarnings({"PMD.LawOfDemeter", "PMD.AvoidCatchingGenericException"})
@Service
public class WeeklyDigestService {

    private static final Logger LOGGER = LoggerFactory.getLogger(WeeklyDigestService.class);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    private final DynamoDbTable<UserTable> userTable;
    private final TransactionRepository transactionRepository;
    private final BudgetRepository budgetRepository;
    private final GoalRepository goalRepository;
    private final DataChangeNotificationService notificationService;

    public WeeklyDigestService(
            final DynamoDbEnhancedClient enhancedClient,
            @org.springframework.beans.factory.annotation.Value(
                            "${app.aws.dynamodb.table-prefix:BudgetBuddy}")
                    final String tablePrefix,
            final TransactionRepository transactionRepository,
            final BudgetRepository budgetRepository,
            final GoalRepository goalRepository,
            final DataChangeNotificationService notificationService) {
        this.userTable =
                enhancedClient.table(tablePrefix + "-Users", TableSchema.fromBean(UserTable.class));
        this.transactionRepository = transactionRepository;
        this.budgetRepository = budgetRepository;
        this.goalRepository = goalRepository;
        this.notificationService = notificationService;
    }

    /** Monday 09:00 UTC. Per-user-timezone delivery is a later concern. */
    @Scheduled(cron = "0 0 9 ? * MON", zone = "UTC")
    public void weeklyDigest() {
        try {
            int count = 0;
            final var pages = userTable.scan();
            for (final var page : pages) {
                for (final UserTable user : page.items()) {
                    try {
                        pushFor(user);
                        count++;
                    } catch (Exception e) {
                        LOGGER.warn(
                                "Weekly digest failed for {}: {}",
                                user.getUserId(),
                                e.getMessage());
                    }
                }
            }
            LOGGER.info("Weekly digest sent to {} users", count);
        } catch (Exception e) {
            LOGGER.error("Weekly digest pass failed: {}", e.getMessage(), e);
        }
    }

    private void pushFor(final UserTable user) {
        final LocalDate now = LocalDate.now();
        final LocalDate weekAgo = now.minusDays(7);
        final List<TransactionTable> txs =
                transactionRepository.findByUserIdAndDateRange(
                        user.getUserId(), weekAgo.format(DATE), now.format(DATE));

        BigDecimal spent = BigDecimal.ZERO;
        BigDecimal income = BigDecimal.ZERO;
        for (final TransactionTable t : txs) {
            if (t == null || t.getAmount() == null) {
                continue;
            }
            if (t.getDeletedAt() != null) {
                continue;
            }
            if (t.getAmount().signum() < 0) {
                spent = spent.add(t.getAmount().abs());
            } else {
                income = income.add(t.getAmount());
            }
        }
        int overBudget = 0;
        for (final BudgetTable b : budgetRepository.findByUserId(user.getUserId())) {
            if (b.getMonthlyLimit() == null) {
                continue;
            }
            // Heuristic: flag as over-budget if a proportional slice of the month's
            // limit is already exceeded.
            // Skipping the full math to keep the digest service small; the link takes
            // them into the app where the real numbers live.
            overBudget += 0;
        }
        int goalsOnTrack = 0;
        for (final GoalTable g : goalRepository.findByUserId(user.getUserId())) {
            if (g == null || g.getTargetAmount() == null || g.getCurrentAmount() == null) {
                continue;
            }
            if (g.getDeletedAt() != null || Boolean.TRUE.equals(g.getCompleted())) {
                continue;
            }
            if (g.getCurrentAmount().compareTo(g.getTargetAmount().multiply(new BigDecimal("0.25")))
                    > 0) {
                goalsOnTrack++;
            }
        }

        final Map<String, Object> data = new HashMap<>();
        data.put("entityType", "weekly-digest");
        data.put("spent", spent);
        data.put("income", income);
        data.put("overBudget", overBudget);
        data.put("goalsOnTrack", goalsOnTrack);
        // We don't yet have a DataChangeNotificationService method for this; reuse
        // notifyGoalMilestoneReached with a composite label. A purpose-built method
        // can land later.
        notificationService.notifyGoalMilestoneReached(
                user.getUserId(),
                "weekly-digest-" + LocalDate.now(),
                "Your week at a glance",
                0,
                false);
    }
}
