package com.budgetbuddy.service;

import com.budgetbuddy.model.dynamodb.BudgetTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.BudgetRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Flow 5 / O12 — server-side per-budget summary computation.
 *
 * <p>iOS has its own {@code BudgetEngine.buildBudgetSummaries} that does almost the same work, but
 * future web/watch/CLI clients shouldn't have to re-implement it. This service consolidates the
 * math. Match the fields of {@code BudgetSummary} on iOS so the client decoder is trivial.
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
@SuppressWarnings({"PMD.LawOfDemeter", "PMD.DataClass", "PMD.OnlyOneReturn"})
@Service
public class BudgetSummaryService {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;
    // Classification moved to BudgetCategoryClassifier so this and
    // BudgetRolloverService share one source of truth.

    private final BudgetRepository budgetRepository;
    private final TransactionRepository transactionRepository;
    /** Optional — drives B-AI-2 risk fields on the DTO when present. */
    private com.budgetbuddy.service.budget.BudgetForecastService forecastService;

    /**
     * Per-user short-TTL cache for buildSummaries. The iOS Budgets screen
     * hits this endpoint on every render — one call walks every budget
     * and does a findByUserIdAndDateRange per row. 30s TTL is the same
     * shape we used for SubscriptionInsightsService; it's short enough
     * that fresh imports surface quickly but long enough to absorb
     * back-to-back renders during navigation.
     */
    private static final long SUMMARY_CACHE_TTL_MS = 30_000;
    /** Hard ceiling on cached users — same pattern as
     *  SubscriptionInsightsService.TX_CACHE_MAX_USERS. */
    private static final int SUMMARY_CACHE_MAX_USERS = 1_000;
    private final java.util.concurrent.ConcurrentMap<String, SummaryCacheEntry> summaryCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static final class SummaryCacheEntry {
        final long expiresAt;
        final List<BudgetSummaryDto> result;

        SummaryCacheEntry(final long expiresAt, final List<BudgetSummaryDto> result) {
            this.expiresAt = expiresAt;
            this.result = result;
        }
    }

    public BudgetSummaryService(
            final BudgetRepository budgetRepository,
            final TransactionRepository transactionRepository) {
        this.budgetRepository = budgetRepository;
        this.transactionRepository = transactionRepository;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setForecastService(
            final com.budgetbuddy.service.budget.BudgetForecastService forecastService) {
        this.forecastService = forecastService;
    }

    public List<BudgetSummaryDto> buildSummaries(final UserTable user) {
        final SummaryCacheEntry hit = summaryCache.get(user.getUserId());
        final long now = System.currentTimeMillis();
        if (hit != null && hit.expiresAt > now) {
            return hit.result;
        }
        final List<BudgetTable> budgets = budgetRepository.findByUserId(user.getUserId());
        final List<BudgetSummaryDto> out = new ArrayList<>();
        for (final BudgetTable b : budgets) {
            out.add(buildOne(user, b, LocalDate.now()));
        }
        ensureCacheCapacity();
        summaryCache.put(user.getUserId(),
                new SummaryCacheEntry(now + SUMMARY_CACHE_TTL_MS, out));
        return out;
    }

    private void ensureCacheCapacity() {
        if (summaryCache.size() < SUMMARY_CACHE_MAX_USERS) return;
        final long now = System.currentTimeMillis();
        summaryCache.entrySet().removeIf(e -> e.getValue().expiresAt <= now);
        if (summaryCache.size() < SUMMARY_CACHE_MAX_USERS) return;
        summaryCache.entrySet().stream()
                .min(java.util.Map.Entry.comparingByValue(
                        java.util.Comparator.comparingLong(e -> e.expiresAt)))
                .ifPresent(e -> summaryCache.remove(e.getKey(), e.getValue()));
    }

    /**
     * Invalidate the cache for a user — call this after writes that change
     * budget state ({@code createOrUpdateBudget}, {@code deleteBudget},
     * rollover, transaction ingest that touches a budget category).
     * Callers that don't bother will eventually see stale data for up to
     * {@code SUMMARY_CACHE_TTL_MS}, which is the documented contract.
     */
    public void invalidateUser(final String userId) {
        if (userId != null) summaryCache.remove(userId);
    }

    /**
     * Testing overload that pins "now". Kept public so the cross-platform parity fixture test can
     * drive the same deterministic inputs as the iOS side.
     */
    public BudgetSummaryDto buildOneAt(
            final UserTable user,
            final BudgetTable b,
            final List<TransactionTable> txRows,
            final LocalDate now) {
        return computeFromTransactions(user, b, txRows, now);
    }

    private BudgetSummaryDto buildOne(
            final UserTable user, final BudgetTable b, final LocalDate now) {
        final String period = b.getPeriod() == null ? "monthly" : b.getPeriod();
        final LocalDate[] window = cycleWindow(period, now);
        final LocalDate start = window[0], end = window[1];

        final List<TransactionTable> txRows =
                transactionRepository.findByUserIdAndDateRange(
                        user.getUserId(), start.format(DATE), end.format(DATE));
        return computeFromTransactions(user, b, txRows, now);
    }

    private BudgetSummaryDto computeFromTransactions(
            final UserTable user,
            final BudgetTable b,
            final List<TransactionTable> txRows,
            final LocalDate now) {
        final String period = b.getPeriod() == null ? "monthly" : b.getPeriod();
        final LocalDate[] window = cycleWindow(period, now);
        final LocalDate start = window[0], end = window[1];

        final boolean isIncomeOrSavings = BudgetCategoryClassifier.isIncomeOrSavings(b.getCategory());

        BigDecimal spent = BigDecimal.ZERO;
        BigDecimal goalContribution = BigDecimal.ZERO;
        for (final TransactionTable t : txRows) {
            if (t == null || t.getAmount() == null) {
                continue;
            }
            // Cross-flow audit fix: previously soft-deleted transactions (Flow 4 / O9)
            // inflated budget spend numbers here. They must stay out of every roll-up
            // that treats "what the user actually spent" as input.
            if (t.getDeletedAt() != null) {
                continue;
            }
            // Filter to the current cycle window. The DB-driven path (buildOne) hands us
            // a pre-filtered list, but buildOneAt — used by the parity fixture and any
            // future caller that already has rows in memory — doesn't, and silently
            // counted prior-month transactions until this guard was added.
            final LocalDate txDate = parseTxDate(t.getTransactionDate());
            if (txDate == null || txDate.isBefore(start) || txDate.isAfter(end)) {
                continue;
            }
            final boolean categoryMatch =
                    Objects.equals(t.getCategoryPrimary(), b.getCategory())
                            || Objects.equals(t.getCategoryDetailed(), b.getCategory());
            if (!categoryMatch) {
                continue;
            }
            // Cross-currency or pending transactions must not inflate this budget's spent/goal
            // totals. See BudgetRolloverService.countsTowardBudget for the rule.
            if (!BudgetRolloverService.countsTowardBudget(b, t)) {
                continue;
            }

            if (b.getGoalId() != null
                    && Objects.equals(t.getGoalId(), b.getGoalId())
                    && t.getAmount().signum() > 0) {
                goalContribution = goalContribution.add(t.getAmount());
            }

            if (isIncomeOrSavings) {
                if (t.getAmount().signum() > 0) {
                    if (b.getGoalId() != null && Objects.equals(t.getGoalId(), b.getGoalId())) {
                        // Don't double-count goal contributions as generic income spend.
                        continue;
                    }
                    spent = spent.add(t.getAmount());
                }
            } else {
                // Same refund-netting fix as BudgetThresholdEvaluator:
                // refunds (positive amounts on expense categories) decrement
                // spend. A $200 charge + $200 refund must show 0 net spend.
                if (t.getAmount().signum() < 0) {
                    spent = spent.add(t.getAmount().abs());
                } else {
                    spent = spent.subtract(t.getAmount());
                }
            }
        }
        if (!isIncomeOrSavings && spent.signum() < 0) {
            spent = BigDecimal.ZERO;
        }

        final BigDecimal limit =
                b.getMonthlyLimit() == null ? BigDecimal.ZERO : b.getMonthlyLimit();
        final BigDecimal carried =
                Boolean.TRUE.equals(b.getRolloverEnabled()) && b.getCarriedAmount() != null
                        ? b.getCarriedAmount()
                        : BigDecimal.ZERO;
        final BigDecimal effectiveLimit = limit.add(carried);
        final BigDecimal remaining =
                isIncomeOrSavings
                        ? effectiveLimit.subtract(spent).max(BigDecimal.ZERO)
                        : effectiveLimit.subtract(spent);
        final BigDecimal adjustedRemaining = remaining.add(goalContribution);

        final BudgetSummaryDto dto = new BudgetSummaryDto();
        dto.budgetId = b.getBudgetId();
        dto.category = b.getCategory();
        dto.period = period;
        dto.currencyCode = b.getCurrencyCode() == null ? "USD" : b.getCurrencyCode();
        dto.periodLimit = limit;
        dto.carriedAmount = carried;
        dto.effectiveLimit = effectiveLimit;
        dto.spent = spent;
        dto.remaining = remaining;
        dto.adjustedRemaining = adjustedRemaining;
        dto.rolloverEnabled = Boolean.TRUE.equals(b.getRolloverEnabled());
        dto.goalId = b.getGoalId();
        dto.goalAllocation =
                b.getGoalAllocation() == null ? BigDecimal.ZERO : b.getGoalAllocation();
        dto.goalContributedSoFar = goalContribution;
        dto.cycleStart = start.format(DATE);
        // Align with iOS contract: cycleEnd is *exclusive* (first day of next period).
        // The DB query internally uses the inclusive form; the DTO exposes the
        // exclusive-end form so [start, end) is the canonical public interface.
        dto.cycleEnd = end.plusDays(1).format(DATE);

        // B-OPP-1 / B-OPP-2: pace-based forecast fields. Income/savings budgets
        // don't project an "overrun" — leave the forecast fields null so the
        // client treats them as "not applicable" rather than mis-rendering.
        if (!isIncomeOrSavings && effectiveLimit.signum() > 0) {
            final LocalDate clampedNow = now.isBefore(start) ? start : (now.isAfter(end) ? end : now);
            final long totalDays = java.time.temporal.ChronoUnit.DAYS.between(start, end) + 1;
            final long daysElapsed = java.time.temporal.ChronoUnit.DAYS.between(start, clampedNow) + 1;
            final long daysRemaining = Math.max(1L, totalDays - daysElapsed);

            final BigDecimal elapsed = BigDecimal.valueOf(Math.max(1L, daysElapsed));
            final BigDecimal totalDaysBd = BigDecimal.valueOf(totalDays);
            final BigDecimal dailyBurn =
                    spent.divide(elapsed, 2, java.math.RoundingMode.HALF_UP);
            final BigDecimal projected =
                    dailyBurn.multiply(totalDaysBd).setScale(2, java.math.RoundingMode.HALF_UP);
            final BigDecimal remainingForAllowance = remaining.max(BigDecimal.ZERO);
            final BigDecimal recommendedDaily =
                    remainingForAllowance.divide(
                            BigDecimal.valueOf(daysRemaining), 2, java.math.RoundingMode.HALF_UP);

            dto.dailyBurnRate = dailyBurn;
            dto.projectedSpend = projected;
            dto.recommendedDailyAllowance = recommendedDaily;
            dto.daysElapsedInCycle = (int) daysElapsed;
            dto.daysRemainingInCycle = (int) Math.max(0L, totalDays - daysElapsed);

            // B-AI-2: predictive overrun warning. Only populated when the
            // forecast service is wired (it always is in the running app, but
            // unit tests that construct this service directly may skip it).
            if (forecastService != null) {
                try {
                    final com.budgetbuddy.service.budget.BudgetForecastService.Forecast f =
                            forecastService.forecast(
                                    user.getUserId(),
                                    b.getCategory(),
                                    effectiveLimit,
                                    spent,
                                    start,
                                    end,
                                    now);
                    if (f != null) {
                        dto.overrunRisk = f.risk == null ? null : f.risk.name();
                        dto.overrunReason = f.reason;
                        dto.forecastedSpend = f.predictedSpend;
                    }
                } catch (Exception ignored) {
                    // Forecast failures must never break summary rendering.
                }
            }
        }
        return dto;
    }

    private static LocalDate parseTxDate(final String date) {
        if (date == null || date.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(date, DATE);
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    /**
     * Package-visible + static so {@link BudgetService} and the threshold
     * evaluator can share the same window math. Period-aware: weekly,
     * biweekly, monthly. Anything else → monthly.
     *
     * <p>Delegates to {@link BudgetCycleMath} — that class is the
     * canonical source of truth and what new code should depend on.
     */
    static LocalDate[] cycleWindow(final String period, final LocalDate today) {
        return BudgetCycleMath.cycleWindow(period, today);
    }

    /** Matches the shape of iOS {@code BudgetSummary}. */
    @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
            value = {
                "URF_UNREAD_FIELD",
                "URF_UNREAD_PUBLIC_OR_PROTECTED_FIELD",
                "UWF_UNWRITTEN_PUBLIC_OR_PROTECTED_FIELD",
                "UWF_FIELD_NOT_INITIALIZED_IN_CONSTRUCTOR"
            },
            justification = "DTO — fields are read/written by Jackson via reflection")
    public static class BudgetSummaryDto {
        public String budgetId;
        public String category;
        public String period;
        public String currencyCode;
        public BigDecimal periodLimit;
        public BigDecimal carriedAmount;
        public BigDecimal effectiveLimit;
        public BigDecimal spent;
        public BigDecimal remaining;
        public BigDecimal adjustedRemaining;
        public boolean rolloverEnabled;
        public String goalId;
        public BigDecimal goalAllocation;
        public BigDecimal goalContributedSoFar;
        public String cycleStart;
        public String cycleEnd;

        // B-OPP-1 / B-OPP-2: forecast fields. Null on income/savings budgets
        // and on rows with no limit configured.
        public BigDecimal projectedSpend;
        public BigDecimal dailyBurnRate;
        public BigDecimal recommendedDailyAllowance;
        public Integer daysElapsedInCycle;
        public Integer daysRemainingInCycle;

        // B-AI-2: predictive overrun risk. Null unless the forecast service is
        // wired (which it is in production).
        public String overrunRisk; // "LOW" | "MEDIUM" | "HIGH"
        public String overrunReason;
        public BigDecimal forecastedSpend;
    }
}
