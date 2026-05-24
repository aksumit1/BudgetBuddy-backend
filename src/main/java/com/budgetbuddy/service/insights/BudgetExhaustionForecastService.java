package com.budgetbuddy.service.insights;

import com.budgetbuddy.model.dynamodb.BudgetTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.repository.dynamodb.BudgetRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.service.BudgetCategoryClassifier;
import com.budgetbuddy.service.BudgetCycleMath;
import com.budgetbuddy.service.BudgetRolloverService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * Proactive "which budgets are about to exhaust?" forecaster.
 *
 * <p>Per-budget overrun risk lives on {@link com.budgetbuddy.service.BudgetSummaryService},
 * but it's per-row — the user has to scroll the whole list to find the
 * trouble spots. This service surfaces the top-N budgets ranked by
 * <em>days-until-exhausted</em> at current pace, so the iOS surface can
 * show a one-glance "Budgets running low" card.
 *
 * <p>For each expense budget (income/savings excluded) we compute:
 * <ul>
 *   <li>spent so far this cycle (with refund netting)
 *   <li>pace = spent / daysElapsed
 *   <li>daysUntilExhausted = (effectiveLimit - spent) / pace
 *   <li>willOverrunBeforeCycleEnd = daysUntilExhausted &lt; daysRemainingInCycle
 * </ul>
 *
 * <p>Only budgets that <em>will</em> exhaust before cycle end are returned.
 * Limit is the constructor-overridable {@code MAX_RESULTS} (top 5 by
 * urgency) so the API stays cheap for users with 30+ budgets.
 */
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Spring constructor injection — beans are shared by design")
@SuppressWarnings({"PMD.LawOfDemeter", "PMD.DataClass", "PMD.OnlyOneReturn"})
@Service
public class BudgetExhaustionForecastService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final int MAX_RESULTS = 5;

    private final BudgetRepository budgetRepository;
    private final TransactionRepository transactionRepository;

    public BudgetExhaustionForecastService(
            final BudgetRepository budgetRepository,
            final TransactionRepository transactionRepository) {
        this.budgetRepository = budgetRepository;
        this.transactionRepository = transactionRepository;
    }

    public List<ExhaustionAlert> forecast(final String userId) {
        return forecast(userId, LocalDate.now());
    }

    /**
     * RISK-1 context-aware overload: reuses the budget list + transaction
     * window the {@link InsightsContext} already fetched. Falls back to
     * the repo-fetch path when budgets aren't in the context (which
     * happens in unit tests that build an InsightsContext directly).
     */
    public List<ExhaustionAlert> forecast(final InsightsContext ctx) {
        if (ctx == null) return List.of();
        // Only fall back to a repo fetch when budgets WEREN'T authoritatively
        // loaded (legacy InsightsContext construction in tests). An empty
        // list from the factory means "user has no budgets" — refetching
        // would be a bug, and SummaryRepoFanOutTest catches it.
        if (!ctx.budgetsAvailable()) {
            return forecast(ctx.userId(), ctx.asOf());
        }
        return forecastFromInputs(ctx.budgets(), ctx.transactions(), ctx.asOf());
    }

    public List<ExhaustionAlert> forecast(final String userId, final LocalDate today) {
        if (userId == null || userId.isEmpty()) return List.of();
        final List<BudgetTable> budgets;
        try {
            budgets = budgetRepository.findByUserId(userId);
        } catch (Exception e) {
            return List.of();
        }
        return forecastFromInputs(budgets, /*preFetchedTransactions=*/null, today);
    }

    private List<ExhaustionAlert> forecastFromInputs(
            final List<BudgetTable> budgets,
            final List<TransactionTable> preFetchedTransactions,
            final LocalDate today) {
        final List<ExhaustionAlert> alerts = new ArrayList<>();
        for (final BudgetTable b : budgets) {
            if (b == null || b.getMonthlyLimit() == null || b.getMonthlyLimit().signum() <= 0)
                continue;
            if (BudgetCategoryClassifier.isIncomeOrSavings(b.getCategory())) continue;

            final LocalDate[] window = BudgetCycleMath.cycleWindow(b, today);
            final LocalDate cycleStart = window[0];
            final LocalDate cycleEnd = window[1];
            // Clamp end at today so future-dated rows aren't summed.
            final LocalDate endForQuery = cycleEnd.isAfter(today) ? today : cycleEnd;
            if (cycleStart.isAfter(endForQuery)) continue;

            final long totalDays = ChronoUnit.DAYS.between(cycleStart, cycleEnd) + 1;
            final long daysElapsed = ChronoUnit.DAYS.between(cycleStart, today) + 1;
            final long daysRemaining = Math.max(0, totalDays - daysElapsed);
            if (daysElapsed <= 0) continue; // cycle hasn't started

            BigDecimal spent = BigDecimal.ZERO;
            final List<TransactionTable> cycleTx;
            if (preFetchedTransactions != null) {
                // Context path: filter the shared snapshot down to this
                // budget's cycle window in-memory. No DDB call.
                final String startStr = cycleStart.format(DATE);
                final String endStr = endForQuery.format(DATE);
                cycleTx =
                        preFetchedTransactions.stream()
                                .filter(
                                        tx ->
                                                tx != null
                                                        && tx.getTransactionDate() != null
                                                        && tx.getTransactionDate().compareTo(startStr) >= 0
                                                        && tx.getTransactionDate().compareTo(endStr) <= 0)
                                .toList();
            } else {
                try {
                    cycleTx =
                            transactionRepository.findByUserIdAndDateRange(
                                    b.getUserId(),
                                    cycleStart.format(DATE),
                                    endForQuery.format(DATE));
                } catch (Exception e) {
                    continue;
                }
            }
            for (final TransactionTable t : cycleTx) {
                if (t == null || t.getAmount() == null || t.getDeletedAt() != null) continue;
                if (!Objects.equals(t.getCategoryPrimary(), b.getCategory())
                        && !Objects.equals(t.getCategoryDetailed(), b.getCategory())) {
                    continue;
                }
                if (!BudgetRolloverService.countsTowardBudget(b, t)) continue;
                // Refund-netting matches BudgetThresholdEvaluator.
                if (t.getAmount().signum() < 0) {
                    spent = spent.add(t.getAmount().abs());
                } else {
                    spent = spent.subtract(t.getAmount());
                }
            }
            if (spent.signum() < 0) spent = BigDecimal.ZERO;

            final BigDecimal effectiveLimit =
                    b.getMonthlyLimit()
                            .add(
                                    Boolean.TRUE.equals(b.getRolloverEnabled())
                                                    && b.getCarriedAmount() != null
                                            ? b.getCarriedAmount()
                                            : BigDecimal.ZERO);
            final BigDecimal remaining = effectiveLimit.subtract(spent);

            // Already overshot — daysUntilExhausted = 0, classify as "exhausted today".
            if (remaining.signum() <= 0) {
                alerts.add(
                        buildAlert(
                                b,
                                spent,
                                effectiveLimit,
                                /*daysUntilExhausted=*/0,
                                (int) daysRemaining,
                                totalDays));
                continue;
            }
            if (spent.signum() == 0) continue; // no spend yet → nothing to project

            final BigDecimal pace =
                    spent.divide(BigDecimal.valueOf(daysElapsed), 4, RoundingMode.HALF_UP);
            if (pace.signum() <= 0) continue;
            final BigDecimal daysToExhaust =
                    remaining.divide(pace, 0, RoundingMode.HALF_UP);
            final long projectedDays = daysToExhaust.longValueExact();
            if (projectedDays >= daysRemaining) continue; // safe — exhausts after cycle end

            alerts.add(
                    buildAlert(
                            b,
                            spent,
                            effectiveLimit,
                            (int) Math.max(0, projectedDays),
                            (int) daysRemaining,
                            totalDays));
        }
        alerts.sort(Comparator.comparingInt(a -> a.daysUntilExhausted));
        return alerts.size() > MAX_RESULTS ? alerts.subList(0, MAX_RESULTS) : alerts;
    }

    private static ExhaustionAlert buildAlert(
            final BudgetTable b,
            final BigDecimal spent,
            final BigDecimal effectiveLimit,
            final int daysUntilExhausted,
            final int daysRemainingInCycle,
            final long totalDays) {
        final ExhaustionAlert a = new ExhaustionAlert();
        a.budgetId = b.getBudgetId();
        a.category = b.getCategory();
        a.period = b.getPeriod() == null ? "monthly" : b.getPeriod();
        a.spent = spent.setScale(2, RoundingMode.HALF_UP);
        a.effectiveLimit = effectiveLimit.setScale(2, RoundingMode.HALF_UP);
        a.remaining = effectiveLimit.subtract(spent).setScale(2, RoundingMode.HALF_UP);
        a.daysUntilExhausted = daysUntilExhausted;
        a.daysRemainingInCycle = daysRemainingInCycle;
        a.totalDaysInCycle = (int) totalDays;
        if (a.remaining.signum() <= 0) {
            a.severity = "EXHAUSTED";
            a.message = String.format(
                    "Already past the limit by %s. Pause or replan this category.",
                    a.remaining.abs().setScale(0, RoundingMode.HALF_UP).toPlainString());
        } else if (daysUntilExhausted <= 1) {
            a.severity = "CRITICAL";
            a.message = "Likely to exhaust within a day at current pace.";
        } else if (daysUntilExhausted <= 3) {
            a.severity = "HIGH";
            a.message = String.format(
                    "Likely to exhaust in about %d days; %d days remain in the cycle.",
                    daysUntilExhausted, daysRemainingInCycle);
        } else {
            a.severity = "MEDIUM";
            a.message = String.format(
                    "Pace will exhaust this budget in %d days, %d days before cycle end.",
                    daysUntilExhausted, daysRemainingInCycle - daysUntilExhausted);
        }
        return a;
    }

    @SuppressFBWarnings(
            value = {"URF_UNREAD_PUBLIC_OR_PROTECTED_FIELD", "UWF_FIELD_NOT_INITIALIZED_IN_CONSTRUCTOR"},
            justification = "DTO — fields read by Jackson via reflection")
    public static class ExhaustionAlert {
        public String budgetId;
        public String category;
        public String period;
        public BigDecimal spent = BigDecimal.ZERO;
        public BigDecimal effectiveLimit = BigDecimal.ZERO;
        public BigDecimal remaining = BigDecimal.ZERO;
        public int daysUntilExhausted;
        public int daysRemainingInCycle;
        public int totalDaysInCycle;
        /** EXHAUSTED | CRITICAL | HIGH | MEDIUM */
        public String severity = "MEDIUM";
        public String message = "";
    }
}
