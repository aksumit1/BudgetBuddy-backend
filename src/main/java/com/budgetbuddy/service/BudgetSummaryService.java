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
    private static final Set<String> INCOME_OR_SAVINGS =
            Set.of("income", "salary", "investment", "savings", "interest");

    private final BudgetRepository budgetRepository;
    private final TransactionRepository transactionRepository;

    public BudgetSummaryService(
            final BudgetRepository budgetRepository,
            final TransactionRepository transactionRepository) {
        this.budgetRepository = budgetRepository;
        this.transactionRepository = transactionRepository;
    }

    public List<BudgetSummaryDto> buildSummaries(final UserTable user) {
        final List<BudgetTable> budgets = budgetRepository.findByUserId(user.getUserId());
        final List<BudgetSummaryDto> out = new ArrayList<>();
        for (final BudgetTable b : budgets) {
            out.add(buildOne(user, b, LocalDate.now()));
        }
        return out;
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

        final boolean isIncomeOrSavings =
                b.getCategory() != null
                        && INCOME_OR_SAVINGS.contains(b.getCategory().toLowerCase(Locale.ROOT));

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
            // Cross-currency transactions must not inflate this budget's spent/goal totals —
            // a 100 EUR purchase otherwise gets summed dollar-for-dollar into a USD budget.
            if (!BudgetRolloverService.matchesBudgetCurrency(b, t)) {
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
                if (t.getAmount().signum() < 0) {
                    spent = spent.add(t.getAmount().abs());
                }
            }
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

    private LocalDate[] cycleWindow(final String period, final LocalDate today) {
        switch (period.toLowerCase(Locale.ROOT)) {
            case "weekly":
                final LocalDate monday =
                        today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                return new LocalDate[] {monday, monday.plusDays(6)};
            case "biweekly":
                // Fixed epoch Monday so every client and the server agree on the window.
                final LocalDate epoch = LocalDate.of(2024, 1, 1);
                final long days = java.time.temporal.ChronoUnit.DAYS.between(epoch, today);
                final long cycles = days / 14;
                final LocalDate start = epoch.plusDays(cycles * 14);
                return new LocalDate[] {start, start.plusDays(13)};
            case "monthly":
            default:
                final LocalDate start2 = today.withDayOfMonth(1);
                final LocalDate end2 = start2.plusMonths(1).minusDays(1);
                return new LocalDate[] {start2, end2};
        }
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
    }
}
