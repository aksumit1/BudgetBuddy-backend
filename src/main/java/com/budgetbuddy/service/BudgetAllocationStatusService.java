package com.budgetbuddy.service;

import com.budgetbuddy.model.dynamodb.BudgetTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.BudgetRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * B-ZBB-1: backend authority for the "remaining to allocate" math the
 * zero-based-budgeting UI banner needs.
 *
 * <p>Computes:
 *
 * <ul>
 *   <li>{@code estimatedMonthlyIncome} — median monthly income over the last
 *       3 months, derived from income-category transactions.
 *   <li>{@code totalAllocated} — sum of all active budgets' {@code
 *       monthlyLimit}, normalised to a monthly rate so weekly budgets
 *       multiply by ~4.33 and biweekly by ~2.17.
 *   <li>{@code remainingToAllocate} — income minus allocated. Negative
 *       values are "over-allocated" — the user has assigned more dollars
 *       to budgets than they actually take home.
 * </ul>
 *
 * <p>iOS reads this once per Budgets-screen render to drive the banner.
 * Income is derived, not user-entered, so the math stays honest even when
 * the user forgets to set an explicit income value.
 */
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Spring constructor injection — beans are shared by design")
@SuppressWarnings({"PMD.LawOfDemeter", "PMD.DataClass", "PMD.OnlyOneReturn"})
@Service
public class BudgetAllocationStatusService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;
    /** Approximate days per month — same constant the iOS BudgetEngine uses. */
    private static final BigDecimal DAYS_PER_MONTH = new BigDecimal("30.4375");
    private static final BigDecimal WEEK_DAYS = new BigDecimal("7");
    private static final BigDecimal BIWEEK_DAYS = new BigDecimal("14");

    private final BudgetRepository budgetRepository;
    private final TransactionRepository transactionRepository;

    public BudgetAllocationStatusService(
            final BudgetRepository budgetRepository,
            final TransactionRepository transactionRepository) {
        this.budgetRepository = budgetRepository;
        this.transactionRepository = transactionRepository;
    }

    public AllocationStatus compute(final UserTable user) {
        return compute(user, LocalDate.now());
    }

    public AllocationStatus compute(final UserTable user, final LocalDate today) {
        if (user == null || user.getUserId() == null) {
            return new AllocationStatus();
        }
        final List<BudgetTable> budgets = budgetRepository.findByUserId(user.getUserId());

        BigDecimal totalAllocatedMonthly = BigDecimal.ZERO;
        int activeBudgets = 0;
        for (final BudgetTable b : budgets) {
            if (b == null) continue;
            if (b.getMonthlyLimit() == null || b.getMonthlyLimit().signum() <= 0) continue;
            // Income-category budgets aren't a spending allocation — they're
            // an inflow target. Don't count them against allocatable income.
            if (BudgetCategoryClassifier.isIncomeOrSavings(b.getCategory())) continue;
            totalAllocatedMonthly =
                    totalAllocatedMonthly.add(normaliseToMonthly(b.getMonthlyLimit(), b.getPeriod()));
            activeBudgets++;
        }
        totalAllocatedMonthly = totalAllocatedMonthly.setScale(2, RoundingMode.HALF_UP);

        final BigDecimal income = medianMonthlyIncome(user.getUserId(), today);
        final BigDecimal remaining = income.subtract(totalAllocatedMonthly).setScale(2, RoundingMode.HALF_UP);

        final AllocationStatus out = new AllocationStatus();
        out.estimatedMonthlyIncome = income;
        out.totalAllocatedMonthly = totalAllocatedMonthly;
        out.remainingToAllocate = remaining;
        out.activeBudgetCount = activeBudgets;
        out.status =
                remaining.signum() == 0
                        ? "BALANCED"
                        : remaining.signum() > 0 ? "UNDER_ALLOCATED" : "OVER_ALLOCATED";
        return out;
    }

    private BigDecimal normaliseToMonthly(final BigDecimal limit, final String period) {
        if (limit == null) return BigDecimal.ZERO;
        if (period == null) return limit;
        return switch (period.toLowerCase(java.util.Locale.ROOT)) {
            case "weekly" ->
                    limit.multiply(DAYS_PER_MONTH).divide(WEEK_DAYS, 2, RoundingMode.HALF_UP);
            case "biweekly" ->
                    limit.multiply(DAYS_PER_MONTH).divide(BIWEEK_DAYS, 2, RoundingMode.HALF_UP);
            default -> limit;
        };
    }

    private BigDecimal medianMonthlyIncome(final String userId, final LocalDate today) {
        final LocalDate start = today.minusMonths(3).withDayOfMonth(1);
        final LocalDate end = today;
        final List<TransactionTable> rows;
        try {
            rows = transactionRepository.findByUserIdAndDateRange(userId, start.format(DATE), end.format(DATE));
        } catch (Exception ignored) {
            return BigDecimal.ZERO;
        }
        // Tally income-category positives per calendar month and take the median.
        final java.util.Map<String, BigDecimal> perMonth = new java.util.HashMap<>();
        for (final TransactionTable t : rows) {
            if (t == null || t.getAmount() == null || t.getDeletedAt() != null) continue;
            if (t.getAmount().signum() <= 0) continue;
            if (!BudgetCategoryClassifier.isIncomeOrSavings(
                    Objects.requireNonNullElse(t.getCategoryPrimary(), t.getCategoryDetailed()))) {
                continue;
            }
            final LocalDate d;
            try {
                d = LocalDate.parse(t.getTransactionDate());
            } catch (Exception ex) {
                continue;
            }
            final String key = String.format("%04d-%02d", d.getYear(), d.getMonthValue());
            perMonth.merge(key, t.getAmount(), BigDecimal::add);
        }
        if (perMonth.isEmpty()) return BigDecimal.ZERO;
        final List<BigDecimal> sorted = new java.util.ArrayList<>(perMonth.values());
        sorted.sort(BigDecimal::compareTo);
        final int n = sorted.size();
        if ((n & 1) == 1) return sorted.get(n / 2).setScale(2, RoundingMode.HALF_UP);
        return sorted.get(n / 2 - 1)
                .add(sorted.get(n / 2))
                .divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
    }

    @SuppressFBWarnings(
            value = {"URF_UNREAD_PUBLIC_OR_PROTECTED_FIELD", "UWF_FIELD_NOT_INITIALIZED_IN_CONSTRUCTOR"},
            justification = "DTO — fields read by Jackson via reflection")
    public static class AllocationStatus {
        public BigDecimal estimatedMonthlyIncome = BigDecimal.ZERO;
        public BigDecimal totalAllocatedMonthly = BigDecimal.ZERO;
        public BigDecimal remainingToAllocate = BigDecimal.ZERO;
        public int activeBudgetCount;
        /** "UNDER_ALLOCATED" | "BALANCED" | "OVER_ALLOCATED" */
        public String status = "UNDER_ALLOCATED";
    }
}
