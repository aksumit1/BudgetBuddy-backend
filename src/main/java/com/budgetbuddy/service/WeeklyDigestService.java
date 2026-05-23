package com.budgetbuddy.service;

import com.budgetbuddy.model.dynamodb.BudgetTable;
import com.budgetbuddy.model.dynamodb.GoalTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.notification.DataChangeNotificationService;
import com.budgetbuddy.repository.dynamodb.BudgetRepository;
import com.budgetbuddy.repository.dynamodb.GoalRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.time.temporal.ChronoUnit;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
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
// SpotBugs flags constructor-injected Spring beans as EI_EXPOSE_REP2,
// but Spring's IoC container intentionally shares the same bean across
// callers — defensive-copying it would break dependency injection.
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Spring constructor injection — beans are shared by design")
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
    private final DistributedLockService distributedLock;
    private final com.budgetbuddy.observability.ScanRateLimiter scanRateLimiter;

    public WeeklyDigestService(
            final DynamoDbEnhancedClient enhancedClient,
            @org.springframework.beans.factory.annotation.Value(
                            "${app.aws.dynamodb.table-prefix:BudgetBuddy}")
                    final String tablePrefix,
            final TransactionRepository transactionRepository,
            final BudgetRepository budgetRepository,
            final GoalRepository goalRepository,
            final DataChangeNotificationService notificationService,
            final DistributedLockService distributedLock,
            final com.budgetbuddy.observability.ScanRateLimiter scanRateLimiter) {
        this.userTable =
                enhancedClient.table(tablePrefix + "-Users", TableSchema.fromBean(UserTable.class));
        this.transactionRepository = transactionRepository;
        this.budgetRepository = budgetRepository;
        this.goalRepository = goalRepository;
        this.notificationService = notificationService;
        this.distributedLock = distributedLock;
        this.scanRateLimiter = scanRateLimiter;
    }

    /**
     * Monday 09:00 UTC. Per-user-timezone delivery is a later concern. Distributed-lock guarded so
     * we don't push N duplicate notifications per user when ECS is scaled out.
     */
    @Scheduled(cron = "0 0 9 ? * MON", zone = "UTC")
    public void weeklyDigest() {
        final LocalDate today = LocalDate.now(java.time.ZoneOffset.UTC);
        final String lockKey = "weeklyDigest:" + today;
        distributedLock.runOnce(lockKey, 60, this::weeklyDigestInner);
    }

    private void weeklyDigestInner() {
        if (!scanRateLimiter.acquire()) {
            LOGGER.warn("Weekly digest skipped — DynamoDB scan rate limiter rejected the permit");
            return;
        }
        try {
            int count = 0;
            final var pages = userTable.scan();
            for (final var page : pages) {
                for (final UserTable user : page.items()) {
                    try {
                        pushFor(user);
                        count++;
                    } catch (Exception e) {
                        if (LOGGER.isWarnEnabled()) {
                            LOGGER.warn(
                                    "Weekly digest failed for {}: {}",
                                    user.getUserId(),
                                    e.getMessage());
                        }
                    }
                }
            }
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info("Weekly digest sent to {} users", count);
            }
        } catch (Exception e) {
            if (LOGGER.isErrorEnabled()) {
                LOGGER.error("Weekly digest pass failed: {}", e.getMessage(), e);
            }
        } finally {
            scanRateLimiter.release();
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
        // Over-budget count: a budget is over-budget when cycle-to-date spend in its category
        // already exceeds the proportional slice of its period limit (e.g. on day 5 of a 7-day
        // weekly budget, expecting <= 71% of the limit). The previous version was hardcoded to
        // calendar-month and silently mis-classified every weekly/biweekly budget.
        int overBudget = 0;
        final LocalDate today = LocalDate.now(java.time.ZoneOffset.UTC);
        for (final BudgetTable b : budgetRepository.findByUserId(user.getUserId())) {
            if (b.getMonthlyLimit() == null || b.getCategory() == null) {
                continue;
            }
            // B-BUG-9: per-budget cycle window via the shared helper.
            final LocalDate[] window = BudgetCycleMath.cycleWindow(b, today);
            final LocalDate cycleStart = window[0];
            final LocalDate cycleEnd = window[1].isAfter(today) ? today : window[1];
            final long totalDays = ChronoUnit.DAYS.between(window[0], window[1]) + 1;
            final long elapsed = ChronoUnit.DAYS.between(cycleStart, today) + 1;
            final BigDecimal proportionalShare =
                    BigDecimal.valueOf(Math.max(1, Math.min(totalDays, elapsed)))
                            .divide(BigDecimal.valueOf(totalDays), 4, java.math.RoundingMode.HALF_UP);

            final List<TransactionTable> cycleTx =
                    transactionRepository.findByUserIdAndDateRange(
                            user.getUserId(), cycleStart.format(DATE), cycleEnd.format(DATE));
            BigDecimal categorySpend = BigDecimal.ZERO;
            for (final TransactionTable t : cycleTx) {
                if (t == null || t.getAmount() == null || t.getDeletedAt() != null) {
                    continue;
                }
                if (!(b.getCategory().equals(t.getCategoryPrimary())
                        || b.getCategory().equals(t.getCategoryDetailed()))) {
                    continue;
                }
                // Reuse the shared currency + posted rule — keeps the digest count in step
                // with what BudgetThresholdEvaluator alerts on, so the user can't see "1 over
                // budget" in the digest without a matching alert.
                if (!BudgetRolloverService.countsTowardBudget(b, t)) {
                    continue;
                }
                if (t.getAmount().signum() < 0) {
                    categorySpend = categorySpend.add(t.getAmount().abs());
                }
            }
            final BigDecimal expectedCeiling = b.getMonthlyLimit().multiply(proportionalShare);
            if (categorySpend.compareTo(expectedCeiling) > 0) {
                overBudget++;
            }
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

        // Stats (spent / income / overBudget / goalsOnTrack) are computed for the
        // digest but the current DataChangeNotificationService only exposes a
        // milestone API that takes a label. A purpose-built digest payload is a
        // future step (Flow 7 / O14 follow-up); for now we ship the label only.
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(
                    "Weekly digest summary for {}: spent={} income={} overBudget={} goalsOnTrack={}",
                    user.getUserId(),
                    spent,
                    income,
                    overBudget,
                    goalsOnTrack);
        }
        notificationService.notifyGoalMilestoneReached(
                user.getUserId(),
                "weekly-digest-" + LocalDate.now(),
                "Your week at a glance",
                0,
                false);
    }
}
