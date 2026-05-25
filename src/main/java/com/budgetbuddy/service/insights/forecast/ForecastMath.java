package com.budgetbuddy.service.insights.forecast;

import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.service.ml.FinancialInsightsPredictionService;
import com.budgetbuddy.service.ml.FinancialInsightsPredictionService.TrendAnalysis;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Pure statistical helpers extracted from the original
 * {@code FinancialInsightsPredictionService}. Stateless, side-effect-free,
 * shared across every forecaster in {@link com.budgetbuddy.service.insights.forecast}.
 *
 * <p>Why a utility class and not a Spring bean: these are deterministic
 * math operations with zero dependencies. Inlining them into each
 * forecaster would duplicate code; making them a bean would imply
 * lifecycle and injection complexity that isn't needed for arithmetic.
 *
 * <p>Wraps {@link FinancialInsightsPredictionService#linearRegressionPrediction}
 * (kept on the original class for back-compat) so all forecasters can
 * reach the regression math through the same façade.
 */
public final class ForecastMath {

    private ForecastMath() {
        throw new AssertionError("utility class — do not instantiate");
    }

    /**
     * Fit a least-squares line through {@code amounts}, treating index
     * (0..n-1) as x. Returns slope + R²-derived confidence. Identical
     * output to the original {@code analyzeAmountTrend} so callers can
     * be migrated transparently.
     */
    public static FinancialInsightsPredictionService.TrendAnalysis analyzeAmountTrend(
            final List<BigDecimal> amounts) {
        if (amounts.size() < 2) {
            return new FinancialInsightsPredictionService.TrendAnalysis(0.0, 0.0);
        }
        final int n = amounts.size();
        double sumX = 0;
        double sumY = 0;
        double sumXY = 0;
        double sumX2 = 0;
        for (int i = 0; i < n; i++) {
            final double x = i;
            final double y = amounts.get(i).doubleValue();
            sumX += x;
            sumY += y;
            sumXY += x * y;
            sumX2 += x * x;
        }
        final double denominator = n * sumX2 - sumX * sumX;
        if (Math.abs(denominator) < 0.0001) {
            return new FinancialInsightsPredictionService.TrendAnalysis(0.0, 0.5);
        }
        final double slope = (n * sumXY - sumX * sumY) / denominator;
        final double intercept = (sumY - slope * sumX) / n;
        final double meanY = sumY / n;
        double ssRes = 0;
        double ssTot = 0;
        for (int i = 0; i < n; i++) {
            final double y = amounts.get(i).doubleValue();
            final double predicted = slope * i + intercept;
            ssRes += Math.pow(y - predicted, 2);
            ssTot += Math.pow(y - meanY, 2);
        }
        final double rSquared = ssTot > 0 ? 1.0 - (ssRes / ssTot) : 0.0;
        final double confidence = Math.max(0.0, Math.min(1.0, rSquared));
        return new FinancialInsightsPredictionService.TrendAnalysis(slope, confidence);
    }

    /**
     * Forecast the next observation at index n, given n prior
     * observations. Delegates to the regression formula on the original
     * service so the bug-fix that pinned the (n-1)/2 mean-x stays
     * authoritative in one place.
     */
    public static BigDecimal predictNext(
            final int observationCount, final double meanY, final double slope) {
        return FinancialInsightsPredictionService.linearRegressionPrediction(
                observationCount, meanY, slope);
    }

    public static BigDecimal average(final List<BigDecimal> amounts) {
        if (amounts.isEmpty()) {
            return BigDecimal.ZERO;
        }
        final BigDecimal sum = amounts.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(amounts.size()), 2, RoundingMode.HALF_UP);
    }

    public static BigDecimal median(final List<BigDecimal> amounts) {
        if (amounts.isEmpty()) {
            return BigDecimal.ZERO;
        }
        final List<BigDecimal> sorted = new ArrayList<>(amounts);
        Collections.sort(sorted);
        final int size = sorted.size();
        if (size % 2 == 0) {
            return sorted.get(size / 2 - 1)
                    .add(sorted.get(size / 2))
                    .divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
        }
        return sorted.get(size / 2);
    }

    /**
     * Nearest-rank percentile (the same definition the original service
     * used). Index = ceil(p × n) − 1, clamped to [0, n-1].
     */
    public static BigDecimal percentile(final List<BigDecimal> amounts, final double p) {
        if (amounts.isEmpty()) {
            return BigDecimal.ZERO;
        }
        final List<BigDecimal> sorted = new ArrayList<>(amounts);
        Collections.sort(sorted);
        int index = (int) Math.ceil(p * sorted.size()) - 1;
        index = Math.max(0, Math.min(index, sorted.size() - 1));
        return sorted.get(index);
    }

    // -----------------------------------------------------------------
    // Transaction-aware variants — useful for the forecasters that
    // operate on TransactionTable streams directly.
    // -----------------------------------------------------------------

    /**
     * Sort transactions by date and analyse the trend of their absolute
     * amounts. Returns a zero-slope, zero-confidence result for &lt; 2
     * transactions.
     */
    public static TrendAnalysis analyzeTrend(final List<TransactionTable> transactions) {
        if (transactions.size() < 2) {
            return new TrendAnalysis(0.0, 0.0);
        }
        final List<BigDecimal> amounts = transactions.stream()
                .sorted(Comparator.comparing(TransactionTable::getTransactionDate))
                .map(tx -> tx.getAmount() != null
                        ? tx.getAmount().abs() : BigDecimal.ZERO)
                .collect(Collectors.toList());
        return analyzeAmountTrend(amounts);
    }

    /**
     * Group transactions by YYYY-MM bucket, count per month, then run
     * the trend analysis on those counts. Yields "is the user using
     * this service more or less often?".
     */
    public static TrendAnalysis analyzeUsageFrequency(final List<TransactionTable> transactions) {
        if (transactions.size() < 3) {
            return new TrendAnalysis(0.0, 0.0);
        }
        final Map<String, Integer> monthlyCounts = new HashMap<>();
        for (final TransactionTable tx : transactions) {
            final String date = tx.getTransactionDate();
            if (date != null && date.length() >= 7) {
                final String month = date.substring(0, 7);
                monthlyCounts.merge(month, 1, Integer::sum);
            }
        }
        final List<String> sortedMonths = monthlyCounts.keySet().stream()
                .sorted().collect(Collectors.toList());
        if (sortedMonths.size() < 2) {
            return new TrendAnalysis(0.0, 0.0);
        }
        final List<BigDecimal> counts = sortedMonths.stream()
                .map(m -> BigDecimal.valueOf(monthlyCounts.get(m)))
                .collect(Collectors.toList());
        return analyzeAmountTrend(counts);
    }

    /** Average of the absolute transaction amounts. Zero for empty input. */
    public static BigDecimal averageOfTransactions(final List<TransactionTable> transactions) {
        if (transactions.isEmpty()) {
            return BigDecimal.ZERO;
        }
        final BigDecimal sum = transactions.stream()
                .map(tx -> tx.getAmount() != null ? tx.getAmount().abs() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(transactions.size()), 2, RoundingMode.HALF_UP);
    }

    /**
     * Forecast the next transaction-amount observation using
     * least-squares regression. Treats each transaction as one
     * timestep regardless of {@code daysAhead}; the parameter is kept
     * for API compatibility with the prior implementation but does not
     * scale the prediction.
     */
    public static BigDecimal predictNextAmount(
            final List<TransactionTable> transactions, final int daysAhead) {
        final TrendAnalysis trend = analyzeTrend(transactions);
        final BigDecimal average = averageOfTransactions(transactions);
        return predictNext(transactions.size(), average.doubleValue(), trend.slope);
    }

    /**
     * Mean monthly income from positive-amount transactions, computed
     * as sum of per-month totals divided by month count. Zero when no
     * income transactions exist or none have a parseable date.
     */
    public static BigDecimal calculateMonthlyIncome(final List<TransactionTable> transactions) {
        return calculateMonthlyForFilter(transactions, true);
    }

    /**
     * Mean monthly expenses from negative-amount transactions.
     * Mirror of {@link #calculateMonthlyIncome} for the opposite sign.
     */
    public static BigDecimal calculateMonthlyExpenses(final List<TransactionTable> transactions) {
        return calculateMonthlyForFilter(transactions, false);
    }

    /**
     * Month-over-month savings consistency, expressed as
     * {@code 1 − coefficient_of_variation}. 0 = wildly inconsistent, 1
     * = identical every month. Returns 0.5 (neutral) when there's not
     * enough data to compute.
     */
    public static double calculateSavingsConsistency(
            final List<TransactionTable> transactions) {
        final BigDecimal monthlyIncome = calculateMonthlyIncome(transactions);
        if (monthlyIncome.compareTo(BigDecimal.ZERO) <= 0) {
            return 0.0;
        }
        final Map<String, BigDecimal> monthlyIncomeMap = new HashMap<>();
        final Map<String, BigDecimal> monthlyExpensesMap = new HashMap<>();
        for (final TransactionTable tx : transactions) {
            final String date = tx.getTransactionDate();
            if (date == null || date.length() < 7 || tx.getAmount() == null) {
                continue;
            }
            final String month = date.substring(0, 7);
            if (tx.getAmount().compareTo(BigDecimal.ZERO) > 0) {
                monthlyIncomeMap.merge(month, tx.getAmount(), BigDecimal::add);
            } else {
                monthlyExpensesMap.merge(month, tx.getAmount().abs(), BigDecimal::add);
            }
        }
        final Set<String> months = new HashSet<>(monthlyIncomeMap.keySet());
        months.addAll(monthlyExpensesMap.keySet());
        if (months.size() < 3) {
            return 0.5;
        }
        final List<Double> savingsRates = new ArrayList<>();
        for (final String month : months) {
            final BigDecimal income = monthlyIncomeMap.getOrDefault(month, BigDecimal.ZERO);
            final BigDecimal expenses = monthlyExpensesMap.getOrDefault(month, BigDecimal.ZERO);
            if (income.compareTo(BigDecimal.ZERO) > 0) {
                final BigDecimal savings = income.subtract(expenses);
                final double rate = savings.divide(income, 4, RoundingMode.HALF_UP).doubleValue();
                savingsRates.add(rate);
            }
        }
        if (savingsRates.size() < 2) {
            return 0.5;
        }
        final double mean = savingsRates.stream()
                .mapToDouble(Double::doubleValue).average().orElse(0.0);
        final double variance = savingsRates.stream()
                .mapToDouble(r -> Math.pow(r - mean, 2)).average().orElse(0.0);
        final double stdDev = Math.sqrt(variance);
        final double cv = mean != 0 ? stdDev / Math.abs(mean) : 1.0;
        return Math.max(0.0, Math.min(1.0, 1.0 - cv));
    }

    /**
     * How regularly the spacing between payments matches the expected
     * cadence. 1 = perfectly regular, 0 = chaotic. Returns 0.5 when
     * there are fewer than 2 payments (no intervals to measure).
     */
    public static double calculatePaymentConsistency(
            final List<TransactionTable> payments, final int expectedIntervalDays) {
        if (payments.size() < 2) {
            return 0.5;
        }
        final List<Long> intervals = new ArrayList<>();
        for (int i = 1; i < payments.size(); i++) {
            final LocalDate d1 = LocalDate.parse(payments.get(i - 1).getTransactionDate());
            final LocalDate d2 = LocalDate.parse(payments.get(i).getTransactionDate());
            intervals.add(ChronoUnit.DAYS.between(d1, d2));
        }
        if (intervals.isEmpty()) {
            return 0.5;
        }
        final double meanInterval = intervals.stream()
                .mapToLong(Long::longValue).average().orElse(expectedIntervalDays);
        final double variance = intervals.stream()
                .mapToDouble(i -> Math.pow(i - meanInterval, 2)).average().orElse(0.0);
        final double stdDev = Math.sqrt(variance);
        final double cv = meanInterval != 0 ? stdDev / meanInterval : 1.0;
        return Math.max(0.0, Math.min(1.0, 1.0 - cv));
    }

    /**
     * Last 12 transactions' absolute amounts for the given account.
     * Used by the interest-cost forecaster as a proxy for balance
     * history (the system doesn't store true daily balance snapshots).
     */
    public static List<BigDecimal> extractBalanceHistory(
            final List<TransactionTable> transactions, final String accountId) {
        return transactions.stream()
                .filter(tx -> accountId.equals(tx.getAccountId()))
                .map(tx -> tx.getAmount() != null ? tx.getAmount().abs() : BigDecimal.ZERO)
                .limit(12)
                .collect(Collectors.toList());
    }

    /**
     * Forecast the next observation from a fitted regression line over
     * the supplied amounts. Empty input returns zero. Caller can use
     * this when they already have the trend computed (avoids the
     * second regression pass that {@link #predictNextAmount} would do).
     */
    public static BigDecimal predictNextAmountFromTrend(
            final List<BigDecimal> amounts, final TrendAnalysis trend) {
        if (amounts.isEmpty()) {
            return BigDecimal.ZERO;
        }
        return predictNext(amounts.size(), average(amounts).doubleValue(), trend.slope);
    }

    private static BigDecimal calculateMonthlyForFilter(
            final List<TransactionTable> transactions, final boolean positive) {
        final Map<String, BigDecimal> monthly = new HashMap<>();
        for (final TransactionTable tx : transactions) {
            if (tx.getAmount() == null) {
                continue;
            }
            final int sign = tx.getAmount().compareTo(BigDecimal.ZERO);
            if (positive ? sign <= 0 : sign >= 0) {
                continue;
            }
            final String date = tx.getTransactionDate();
            if (date == null || date.length() < 7) {
                continue;
            }
            final String month = date.substring(0, 7);
            monthly.merge(month, tx.getAmount().abs(), BigDecimal::add);
        }
        if (monthly.isEmpty()) {
            return BigDecimal.ZERO;
        }
        final BigDecimal sum = monthly.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(monthly.size()), 2, RoundingMode.HALF_UP);
    }
}
