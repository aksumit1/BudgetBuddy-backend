package com.budgetbuddy.service;

import com.budgetbuddy.model.dynamodb.BudgetTable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;

/**
 * Single source of truth for the "what calendar window does this budget's
 * spend math care about, and how do I compare its limit to a monthly
 * income?" questions.
 *
 * <p>Before this class existed, four separate services
 * ({@link BudgetService}, {@link BudgetThresholdEvaluator},
 * {@link BudgetToGoalFlowService}, {@code WeeklyDigestService}) each
 * hardcoded {@code today.withDayOfMonth(1)} and silently broke
 * weekly/biweekly budgets — the limit was per-week but the spend math
 * accumulated month-to-date.
 *
 * <p>Every consumer of {@link BudgetTable#getPeriod()} that needs to
 * compute spend or compare against income MUST go through this class.
 * An architecture test (see {@code BudgetCycleMathArchitectureTest})
 * fails the build if any service in the {@code service.budget} or
 * {@code service} package reintroduces the calendar-month idiom in a
 * file that imports {@link BudgetTable}.
 */
public final class BudgetCycleMath {

    /** Approximate days per month — same constant the iOS BudgetEngine uses. */
    public static final BigDecimal DAYS_PER_MONTH = new BigDecimal("30.4375");
    private static final BigDecimal WEEK_DAYS = new BigDecimal("7");
    private static final BigDecimal BIWEEK_DAYS = new BigDecimal("14");

    private BudgetCycleMath() {}

    /**
     * Inclusive [start, end] cycle window for a budget's period. Mirrors
     * {@link BudgetSummaryService#cycleWindow} which we keep in lockstep —
     * this class is the public surface; the summary service delegates here.
     */
    public static LocalDate[] cycleWindow(final String period, final LocalDate today) {
        final String p = period == null ? "monthly" : period.toLowerCase(Locale.ROOT);
        switch (p) {
            case "weekly":
                final LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                return new LocalDate[] {monday, monday.plusDays(6)};
            case "biweekly":
                final LocalDate epoch = LocalDate.of(2024, 1, 1);
                final long days = ChronoUnit.DAYS.between(epoch, today);
                final long cycles = days / 14;
                final LocalDate start = epoch.plusDays(cycles * 14);
                return new LocalDate[] {start, start.plusDays(13)};
            case "monthly":
            default:
                final LocalDate s2 = today.withDayOfMonth(1);
                return new LocalDate[] {s2, s2.plusMonths(1).minusDays(1)};
        }
    }

    /**
     * Convenience overload taking the BudgetTable directly so callers don't
     * have to remember the null-defaulting rule.
     */
    public static LocalDate[] cycleWindow(final BudgetTable budget, final LocalDate today) {
        return cycleWindow(budget == null ? "monthly" : budget.getPeriod(), today);
    }

    /**
     * Convert a budget's {@code monthlyLimit} (which is actually a
     * <em>period limit</em> — the column name predates the period field) to
     * a monthly-equivalent rate. A $50 weekly limit returns ~$217.41;
     * a $200 biweekly limit returns ~$434.82.
     *
     * <p>Used by zero-based-budgeting allocation math where every budget
     * must be compared on the same monthly axis as income.
     */
    public static BigDecimal monthlyEquivalent(final BigDecimal limit, final String period) {
        if (limit == null) return BigDecimal.ZERO;
        final String p = period == null ? "monthly" : period.toLowerCase(Locale.ROOT);
        return switch (p) {
            case "weekly" -> limit.multiply(DAYS_PER_MONTH).divide(WEEK_DAYS, 2, RoundingMode.HALF_UP);
            case "biweekly" -> limit.multiply(DAYS_PER_MONTH).divide(BIWEEK_DAYS, 2, RoundingMode.HALF_UP);
            default -> limit;
        };
    }

    public static BigDecimal monthlyEquivalent(final BudgetTable budget) {
        return budget == null
                ? BigDecimal.ZERO
                : monthlyEquivalent(budget.getMonthlyLimit(), budget.getPeriod());
    }
}
