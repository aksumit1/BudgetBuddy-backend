package com.budgetbuddy.service.insights;

import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.service.BudgetCategoryClassifier;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Cash-flow runway forecaster.
 *
 * <p>"Given my current liquid assets and how much I net per month, how long
 * before I run out of cash?" The deterministic answer no other detector
 * surfaces — anomaly/expense-reduction/missed-payment are all reactive,
 * while this is the proactive "you've got X days at current pace" signal
 * that lets the user course-correct before a problem.
 *
 * <p>Inputs (all derived, never user-entered):
 * <ul>
 *   <li>{@code liquidAssets} — sum of positive balances on checking + savings.
 *   <li>{@code monthlyIncome} / {@code monthlyExpenses} — 3-month median.
 * </ul>
 *
 * <p>Outputs:
 * <ul>
 *   <li>{@code runwayDays} — months × 30.4375, capped at 365.
 *   <li>{@code projectedCashAt30/60/90Days} — pace-extrapolated balances.
 *   <li>{@code status} — HEALTHY (>=90d) | TIGHT (60-89d) | LOW (30-59d) |
 *       CRITICAL (<30d) | SURPLUS (net positive → infinite runway).
 * </ul>
 */
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Spring constructor injection — beans are shared by design")
@SuppressWarnings({"PMD.LawOfDemeter", "PMD.DataClass", "PMD.OnlyOneReturn"})
@Service
public class CashFlowForecastService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final BigDecimal DAYS_PER_MONTH = new BigDecimal("30.4375");
    private static final int MAX_RUNWAY_DAYS = 365;

    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;

    public CashFlowForecastService(
            final TransactionRepository transactionRepository,
            final AccountRepository accountRepository) {
        this.transactionRepository = transactionRepository;
        this.accountRepository = accountRepository;
    }

    public Forecast forecast(final String userId) {
        return forecast(userId, LocalDate.now());
    }

    public Forecast forecast(final String userId, final LocalDate today) {
        if (userId == null || userId.isEmpty()) {
            return new Forecast();
        }
        final BigDecimal liquidAssets = sumLiquidAssets(userId);
        final BigDecimal[] incomeExpense = medianMonthlyIncomeAndExpense(userId, today);
        final BigDecimal monthlyIncome = incomeExpense[0];
        final BigDecimal monthlyExpenses = incomeExpense[1];
        final BigDecimal netMonthly = monthlyIncome.subtract(monthlyExpenses);

        final Forecast f = new Forecast();
        f.liquidAssets = liquidAssets;
        f.monthlyIncome = monthlyIncome;
        f.monthlyExpenses = monthlyExpenses;
        f.netMonthly = netMonthly;

        // Surplus path: net positive → infinite runway. Pace forward 30/60/90.
        if (netMonthly.signum() >= 0) {
            f.runwayDays = MAX_RUNWAY_DAYS;
            f.projectedCashAt30Days =
                    liquidAssets
                            .add(netMonthly.multiply(daysFactor(30)))
                            .setScale(2, RoundingMode.HALF_UP);
            f.projectedCashAt60Days =
                    liquidAssets
                            .add(netMonthly.multiply(daysFactor(60)))
                            .setScale(2, RoundingMode.HALF_UP);
            f.projectedCashAt90Days =
                    liquidAssets
                            .add(netMonthly.multiply(daysFactor(90)))
                            .setScale(2, RoundingMode.HALF_UP);
            f.status = "SURPLUS";
            f.message =
                    String.format(
                            "Net %s/month: cash position is growing. Consider redirecting some surplus to goals.",
                            usd(netMonthly));
            return f;
        }

        // Deficit path: every month draws down liquidAssets by |netMonthly|.
        final BigDecimal monthlyBurn = netMonthly.abs();
        final double burn = monthlyBurn.doubleValue();
        final double assets = liquidAssets.doubleValue();
        final long days =
                burn <= 0
                        ? MAX_RUNWAY_DAYS
                        : Math.min(MAX_RUNWAY_DAYS, (long) Math.floor((assets / burn) * 30.4375));
        f.runwayDays = (int) days;
        f.projectedCashAt30Days =
                liquidAssets
                        .subtract(monthlyBurn.multiply(daysFactor(30)))
                        .setScale(2, RoundingMode.HALF_UP);
        f.projectedCashAt60Days =
                liquidAssets
                        .subtract(monthlyBurn.multiply(daysFactor(60)))
                        .setScale(2, RoundingMode.HALF_UP);
        f.projectedCashAt90Days =
                liquidAssets
                        .subtract(monthlyBurn.multiply(daysFactor(90)))
                        .setScale(2, RoundingMode.HALF_UP);

        if (days < 30) {
            f.status = "CRITICAL";
            f.message =
                    String.format(
                            "At current pace you'll run out of cash in %d days. Review the largest expense categories now.",
                            (int) days);
        } else if (days < 60) {
            f.status = "LOW";
            f.message =
                    String.format(
                            "Cash on hand will last about %d days. Consider trimming variable spend or pausing subscriptions.",
                            (int) days);
        } else if (days < 90) {
            f.status = "TIGHT";
            f.message =
                    String.format(
                            "About %d days of runway. You're not in danger yet — but the trend is downward.",
                            (int) days);
        } else {
            f.status = "HEALTHY";
            f.message =
                    String.format("Comfortable runway — about %d days at current burn.", (int) days);
        }
        return f;
    }

    private static BigDecimal daysFactor(final int days) {
        return BigDecimal.valueOf(days).divide(DAYS_PER_MONTH, 6, RoundingMode.HALF_UP);
    }

    private BigDecimal sumLiquidAssets(final String userId) {
        BigDecimal sum = BigDecimal.ZERO;
        try {
            for (final AccountTable a : accountRepository.findByUserId(userId)) {
                if (a == null || a.getBalance() == null) continue;
                if (a.getAccountType() == null) continue;
                final String t = a.getAccountType().toLowerCase(java.util.Locale.ROOT);
                if (!("checking".equals(t) || "savings".equals(t))) continue;
                if (a.getBalance().signum() > 0) {
                    sum = sum.add(a.getBalance());
                }
            }
        } catch (Exception ignored) {
            // Treat repo failures as "no visible assets" rather than fail the forecast.
        }
        return sum.setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal[] medianMonthlyIncomeAndExpense(final String userId, final LocalDate today) {
        final LocalDate start = today.minusMonths(3).withDayOfMonth(1);
        final List<TransactionTable> rows;
        try {
            rows =
                    transactionRepository.findByUserIdAndDateRange(
                            userId, start.format(DATE), today.format(DATE));
        } catch (Exception ignored) {
            return new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO};
        }
        final Map<YearMonth, BigDecimal> incomePerMonth = new HashMap<>();
        final Map<YearMonth, BigDecimal> expensePerMonth = new HashMap<>();
        for (final TransactionTable t : rows) {
            if (t == null || t.getAmount() == null || t.getDeletedAt() != null) continue;
            final LocalDate d;
            try {
                d = LocalDate.parse(t.getTransactionDate());
            } catch (Exception e) {
                continue;
            }
            final YearMonth ym = YearMonth.from(d);
            final String cat = t.getCategoryPrimary();
            if (t.getAmount().signum() > 0 && BudgetCategoryClassifier.isIncomeOrSavings(cat)) {
                incomePerMonth.merge(ym, t.getAmount(), BigDecimal::add);
            } else if (t.getAmount().signum() < 0) {
                expensePerMonth.merge(ym, t.getAmount().abs(), BigDecimal::add);
            }
        }
        return new BigDecimal[] {medianOf(incomePerMonth.values()), medianOf(expensePerMonth.values())};
    }

    private static BigDecimal medianOf(final java.util.Collection<BigDecimal> values) {
        if (values.isEmpty()) return BigDecimal.ZERO;
        final List<BigDecimal> sorted = new ArrayList<>(values);
        sorted.sort(BigDecimal::compareTo);
        final int n = sorted.size();
        if ((n & 1) == 1) return sorted.get(n / 2).setScale(2, RoundingMode.HALF_UP);
        return sorted.get(n / 2 - 1)
                .add(sorted.get(n / 2))
                .divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
    }

    private static String usd(final BigDecimal v) {
        return "$" + v.setScale(0, RoundingMode.HALF_UP);
    }

    @SuppressFBWarnings(
            value = {"URF_UNREAD_PUBLIC_OR_PROTECTED_FIELD", "UWF_FIELD_NOT_INITIALIZED_IN_CONSTRUCTOR"},
            justification = "DTO — fields read by Jackson via reflection")
    public static class Forecast {
        public BigDecimal liquidAssets = BigDecimal.ZERO;
        public BigDecimal monthlyIncome = BigDecimal.ZERO;
        public BigDecimal monthlyExpenses = BigDecimal.ZERO;
        public BigDecimal netMonthly = BigDecimal.ZERO;
        public int runwayDays;
        public BigDecimal projectedCashAt30Days = BigDecimal.ZERO;
        public BigDecimal projectedCashAt60Days = BigDecimal.ZERO;
        public BigDecimal projectedCashAt90Days = BigDecimal.ZERO;
        /** SURPLUS | HEALTHY | TIGHT | LOW | CRITICAL */
        public String status = "HEALTHY";
        public String message = "";
    }
}
