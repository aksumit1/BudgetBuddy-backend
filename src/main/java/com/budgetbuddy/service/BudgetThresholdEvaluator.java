package com.budgetbuddy.service;

import com.budgetbuddy.model.dynamodb.BudgetTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.notification.DataChangeNotificationService;
import com.budgetbuddy.repository.dynamodb.BudgetRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.service.correctness.UserClock;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Flow 5 / O8 — server-side threshold evaluator.
 *
 * <p>Runs after a transaction ingest (upsert, PUT, import) for the affected user. For every budget
 * whose category matches, it computes the current-cycle spend, derives the highest crossed
 * threshold (50/75/90/100), and pushes a notification through {@link DataChangeNotificationService}
 * if it's higher than what we've already alerted on ({@code lastAlertedThreshold}). That dedupes
 * repeat spends within the same bracket, so one grocery run at 72% doesn't fire the 50% alert
 * twice.
 *
 * <p>The iOS {@code BudgetNotificationService} polling loop was doing the same job client-side on a
 * 5-minute timer. That's unreliable when the app is suspended — a late Thursday-night spend
 * wouldn't alert until Saturday. Server-side evaluation removes the dependency on the client being
 * awake.
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
@SuppressWarnings({"PMD.LawOfDemeter", "PMD.AvoidCatchingGenericException", "PMD.OnlyOneReturn"})
@Service
public class BudgetThresholdEvaluator {

    private static final Logger LOGGER = LoggerFactory.getLogger(BudgetThresholdEvaluator.class);
    private static final int[] THRESHOLDS = {50, 75, 90, 100};
    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;
    // Precomputed BigDecimal thresholds so the hot-path comparison stays in
    // exact arithmetic. Avoids float drift at the 50/75/90/100% boundaries
    // (e.g. $750.00 / $1000.00 must cross 75%, not 74.9999999…).
    private static final BigDecimal[] THRESHOLD_BD = {
        BigDecimal.valueOf(50), BigDecimal.valueOf(75),
        BigDecimal.valueOf(90), BigDecimal.valueOf(100)
    };
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    private final BudgetRepository budgetRepository;
    private final TransactionRepository transactionRepository;
    private final DataChangeNotificationService notificationService;
    private final UserClock userClock;

    public BudgetThresholdEvaluator(
            final BudgetRepository budgetRepository,
            final TransactionRepository transactionRepository,
            final DataChangeNotificationService notificationService,
            final UserClock userClock) {
        this.budgetRepository = budgetRepository;
        this.transactionRepository = transactionRepository;
        this.notificationService = notificationService;
        this.userClock = userClock;
    }

    /**
     * Evaluate every budget for {@code userId} that matches one of the provided categories. Call
     * after ingesting a single transaction or a batch — pass the set of categories the ingest
     * touched to avoid scanning unaffected rows.
     */
    public void evaluate(final String userId, final java.util.Set<String> touchedCategories) {
        if (userId == null
                || userId.isEmpty()
                || touchedCategories == null
                || touchedCategories.isEmpty()) {
            return;
        }
        try {
            final List<BudgetTable> userBudgets = budgetRepository.findByUserId(userId);
            for (final BudgetTable budget : userBudgets) {
                if (!touchedCategories.contains(budget.getCategory())) {
                    continue;
                }
                evaluateSingle(budget);
            }
        } catch (Exception e) {
            LOGGER.warn("Threshold evaluation failed for user {}: {}", userId, e.getMessage());
        }
    }

    private void evaluateSingle(final BudgetTable budget) {
        if (budget.getMonthlyLimit() == null || budget.getMonthlyLimit().signum() <= 0) {
            return;
        }
        // Compute current month spend in this category using the *user's*
        // local day/month, not the server's. On a PT user the month boundary
        // is at midnight Pacific, not midnight UTC — without this an 11pm
        // grocery run on the last day of the month would land in next
        // month's budget and nuke the threshold evaluation.
        final LocalDate today = userClock.today(budget.getUserId());
        final LocalDate start = today.withDayOfMonth(1);
        final LocalDate end = today;
        final List<TransactionTable> rows =
                transactionRepository.findByUserIdAndDateRange(
                        budget.getUserId(), start.format(DATE), end.format(DATE));
        BigDecimal spent = BigDecimal.ZERO;
        for (final TransactionTable t : rows) {
            if (t == null || t.getAmount() == null) {
                continue;
            }
            if (!(budget.getCategory().equals(t.getCategoryPrimary())
                    || budget.getCategory().equals(t.getCategoryDetailed()))) {
                continue;
            }
            // Skip cross-currency transactions — a 100 EUR purchase must not move a
            // 100 USD budget by the same notional amount.
            // BudgetRolloverService.matchesBudgetCurrency
            // owns the comparison rule so all budget aggregators stay in lockstep.
            if (!BudgetRolloverService.matchesBudgetCurrency(budget, t)) {
                continue;
            }
            if (t.getAmount().signum() < 0) {
                spent = spent.add(t.getAmount().abs());
            }
        }
        BigDecimal effectiveLimit = budget.getMonthlyLimit();
        if (budget.getCarriedAmount() != null) {
            effectiveLimit = effectiveLimit.add(budget.getCarriedAmount());
        }
        if (effectiveLimit.signum() <= 0) {
            return;
        }

        // Exact-arithmetic threshold crossing. Scale 4 keeps a fractional-percent
        // tail for logging/display but is more than enough for 50/75/90/100 bucket
        // decisions — any extra digit is either below 0.01% (negligible) or would
        // round away under HALF_UP anyway.
        final BigDecimal percent =
                spent.multiply(ONE_HUNDRED).divide(effectiveLimit, 4, RoundingMode.HALF_UP);
        int highestCrossed = 0;
        for (int i = 0; i < THRESHOLDS.length; i++) {
            if (percent.compareTo(THRESHOLD_BD[i]) >= 0) {
                highestCrossed = THRESHOLDS[i];
            }
        }
        final int alreadyAlerted =
                budget.getLastAlertedThreshold() == null ? 0 : budget.getLastAlertedThreshold();

        if (highestCrossed > alreadyAlerted) {
            notificationService.notifyBudgetThresholdCrossed(
                    budget.getUserId(),
                    budget.getBudgetId(),
                    budget.getCategory(),
                    highestCrossed,
                    percent.doubleValue());
            budget.setLastAlertedThreshold(highestCrossed);
            budget.setUpdatedAt(java.time.Instant.now());
            // Optimistic write. If the user updated the budget limit in the
            // same millisecond this cron fired, we lose the race — that's
            // fine: the next ingest will re-evaluate with the new limit and
            // alert (or not) correctly. Silently skip this notification
            // rather than clobber the user's new limit.
            try {
                budgetRepository.saveWithLock(budget);
            } catch (
                    com.budgetbuddy.repository.dynamodb.OptimisticLockHelper.OptimisticLockException
                            conflict) {
                LOGGER.info(
                        "Budget {} was edited concurrently — skipping this threshold notification; next ingest will re-evaluate",
                        budget.getBudgetId());
                return;
            }
            LOGGER.info(
                    "Pushed {}% threshold alert to user {} for budget {} (category={}, percent={})",
                    highestCrossed,
                    budget.getUserId(),
                    budget.getBudgetId(),
                    budget.getCategory(),
                    percent);
        }
    }
}
