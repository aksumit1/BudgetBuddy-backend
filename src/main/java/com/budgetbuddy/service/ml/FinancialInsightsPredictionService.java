package com.budgetbuddy.service.ml;

import com.budgetbuddy.model.dynamodb.TransactionTable;
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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * ML-Based Financial Insights Prediction Service
 *
 * <p>Uses machine learning techniques (linear regression, time series analysis, pattern
 * recognition) to predict future financial events and provide proactive insights.
 */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
@SuppressWarnings({"PMD.LawOfDemeter", "PMD.DataClass", "PMD.OnlyOneReturn"})
@Service
public class FinancialInsightsPredictionService {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(FinancialInsightsPredictionService.class);

    // Prediction confidence thresholds
    private static final double MEDIUM_CONFIDENCE = 0.50;

    // Minimum data points for reliable predictions
    private static final int MIN_DATA_POINTS = 6; // 6 months of data

    /**
     * Predict future transaction anomalies Uses pattern recognition to identify likely anomalies
     * before they occur
     */
    public List<PredictedAnomaly> predictAnomalies(
            final List<TransactionTable> historicalTransactions, final int daysAhead) {

        LOGGER.info("Predicting anomalies for next {} days", daysAhead);

        final List<PredictedAnomaly> predictions = new ArrayList<>();

        if (historicalTransactions.size() < MIN_DATA_POINTS) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(
                        "Insufficient data for anomaly prediction: {} transactions",
                        historicalTransactions.size());
            }
            return predictions;
        }

        // Analyze spending patterns by category
        final Map<String, List<TransactionTable>> byCategory =
                historicalTransactions.stream()
                        .filter(
                                tx ->
                                        tx.getAmount() != null
                                                && tx.getAmount().compareTo(BigDecimal.ZERO) < 0)
                        .filter(
                                tx ->
                                        tx.getCategoryPrimary() != null
                                                && !tx.getCategoryPrimary().isEmpty())
                        .collect(Collectors.groupingBy(TransactionTable::getCategoryPrimary));

        // Predict category spikes
        for (final Map.Entry<String, List<TransactionTable>> entry : byCategory.entrySet()) {
            final String category = entry.getKey();
            final List<TransactionTable> categoryTx = entry.getValue();

            if (categoryTx.size() < 3) {
                continue;
            }

            // Calculate trend
            final TrendAnalysis trend = analyzeTrend(categoryTx);

            // Predict if category will spike
            if (trend.slope > 0 && trend.confidence > MEDIUM_CONFIDENCE) {
                final BigDecimal predictedAmount = predictNextAmount(categoryTx, daysAhead);
                final BigDecimal historicalAverage = calculateAverage(categoryTx);

                // If predicted amount is significantly higher than average
                if (predictedAmount.compareTo(historicalAverage.multiply(BigDecimal.valueOf(2)))
                        > 0) {
                    final double confidence = Math.min(0.9, trend.confidence * 1.1);
                    predictions.add(
                            new PredictedAnomaly(
                                    category,
                                    predictedAmount,
                                    historicalAverage,
                                    confidence,
                                    "Predicted category spike based on increasing trend",
                                    LocalDate.now().plusDays(daysAhead)));
                }
            }
        }

        // Predict amount threshold anomalies
        final List<BigDecimal> amounts =
                historicalTransactions.stream()
                        .filter(
                                tx ->
                                        tx.getAmount() != null
                                                && tx.getAmount().compareTo(BigDecimal.ZERO) < 0)
                        .map(tx -> tx.getAmount().abs())
                        .sorted()
                        .collect(Collectors.toList());

        if (amounts.size() >= MIN_DATA_POINTS) {
            final BigDecimal median = calculateMedian(amounts);
            final BigDecimal q3 = calculatePercentile(amounts, 0.75);
            final BigDecimal iqr = q3.subtract(calculatePercentile(amounts, 0.25));
            final BigDecimal threshold = q3.add(iqr.multiply(BigDecimal.valueOf(1.5)));

            // Predict if next transaction might exceed threshold
            final TrendAnalysis amountTrend = analyzeAmountTrend(amounts);
            if (amountTrend.slope > 0 && amountTrend.confidence > MEDIUM_CONFIDENCE) {
                final BigDecimal predictedNext = predictNextAmountFromTrend(amounts, amountTrend);
                if (predictedNext.compareTo(threshold) > 0) {
                    predictions.add(
                            new PredictedAnomaly(
                                    "Amount Threshold",
                                    predictedNext,
                                    median,
                                    amountTrend.confidence,
                                    "Predicted unusually high transaction based on increasing trend",
                                    LocalDate.now().plusDays(7) // Predict for next week
                                    ));
                }
            }
        }

        return predictions.stream()
                .sorted(Comparator.comparing(PredictedAnomaly::getConfidence).reversed())
                .collect(Collectors.toList());
    }

    /**
     * Predict future expense reduction opportunities Identifies expenses likely to become
     * unnecessary or reducible
     */
    public List<PredictedExpenseReduction> predictExpenseReductions(
            final List<TransactionTable> historicalTransactions,
            final Map<String, BigDecimal> currentSubscriptions) {

        LOGGER.info("Predicting expense reduction opportunities");

        final List<PredictedExpenseReduction> predictions = new ArrayList<>();

        // Analyze subscription usage patterns
        for (final Map.Entry<String, BigDecimal> entry : currentSubscriptions.entrySet()) {
            final String subscriptionName = entry.getKey();
            final BigDecimal monthlyCost = entry.getValue();

            // Find transactions for this subscription
            final List<TransactionTable> subscriptionTx =
                    historicalTransactions.stream()
                            .filter(
                                    tx -> {
                                        final String desc =
                                                tx.getDescription() != null
                                                        ? tx.getDescription()
                                                                .toLowerCase(Locale.ROOT)
                                                        : "";
                                        final String merchant =
                                                tx.getMerchantName() != null
                                                        ? tx.getMerchantName()
                                                                .toLowerCase(Locale.ROOT)
                                                        : "";
                                        final String name =
                                                subscriptionName.toLowerCase(Locale.ROOT);
                                        return desc.contains(name) || merchant.contains(name);
                                    })
                            .sorted(
                                    Comparator.comparing(TransactionTable::getTransactionDate)
                                            .reversed())
                            .collect(Collectors.toList());

            if (subscriptionTx.size() < 3) {
                continue;
            }

            // Analyze usage frequency trend
            final TrendAnalysis usageTrend = analyzeUsageFrequency(subscriptionTx);

            // Predict cancellation if usage is decreasing
            if (usageTrend.slope < -0.1 && usageTrend.confidence > MEDIUM_CONFIDENCE) {
                final double cancellationProbability =
                        Math.min(0.9, (Math.abs(usageTrend.slope) * 10) * usageTrend.confidence);

                predictions.add(
                        new PredictedExpenseReduction(
                                subscriptionName,
                                monthlyCost,
                                monthlyCost.multiply(BigDecimal.valueOf(12)),
                                cancellationProbability,
                                "Usage frequency decreasing - likely to become unused",
                                LocalDate.now().plusDays(30)));
            }
        }

        // Predict category overspending
        final Map<String, List<TransactionTable>> byCategory =
                historicalTransactions.stream()
                        .filter(
                                tx ->
                                        tx.getAmount() != null
                                                && tx.getAmount().compareTo(BigDecimal.ZERO) < 0)
                        .filter(
                                tx ->
                                        tx.getCategoryPrimary() != null
                                                && !tx.getCategoryPrimary().isEmpty())
                        .collect(Collectors.groupingBy(TransactionTable::getCategoryPrimary));

        for (final Map.Entry<String, List<TransactionTable>> entry : byCategory.entrySet()) {
            final String category = entry.getKey();
            final List<TransactionTable> categoryTx = entry.getValue();

            if (categoryTx.size() < MIN_DATA_POINTS) {
                continue;
            }

            final TrendAnalysis trend = analyzeTrend(categoryTx);

            // Predict if category will exceed budget
            if (trend.slope > 0 && trend.confidence > MEDIUM_CONFIDENCE) {
                final BigDecimal predictedNextMonth = predictNextAmount(categoryTx, 30);
                final BigDecimal currentAverage = calculateAverage(categoryTx);

                if (predictedNextMonth.compareTo(currentAverage.multiply(BigDecimal.valueOf(1.2)))
                        > 0) {
                    final BigDecimal potentialSavings = predictedNextMonth.subtract(currentAverage);

                    predictions.add(
                            new PredictedExpenseReduction(
                                    category + " Spending",
                                    potentialSavings,
                                    potentialSavings.multiply(BigDecimal.valueOf(12)),
                                    trend.confidence,
                                    "Predicted overspending - reduce by 20% to stay on track",
                                    LocalDate.now().plusDays(30)));
                }
            }
        }

        return predictions.stream()
                .sorted(Comparator.comparing(PredictedExpenseReduction::getProbability).reversed())
                .collect(Collectors.toList());
    }

    /** Predict goal achievement likelihood and timeline */
    public List<PredictedGoalAchievement> predictGoalAchievements(
            final Map<String, GoalData> goals,
            final List<TransactionTable> historicalTransactions) {

        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("Predicting goal achievement for {} goals", goals.size());
        }

        final List<PredictedGoalAchievement> predictions = new ArrayList<>();

        // Calculate historical savings rate
        final BigDecimal monthlyIncome = calculateMonthlyIncome(historicalTransactions);
        final BigDecimal monthlyExpenses = calculateMonthlyExpenses(historicalTransactions);
        final BigDecimal monthlySavings = monthlyIncome.subtract(monthlyExpenses);
        final double savingsRate =
                monthlyIncome.compareTo(BigDecimal.ZERO) > 0
                        ? monthlySavings
                                .divide(monthlyIncome, 4, RoundingMode.HALF_UP)
                                .doubleValue()
                        : 0.0;

        for (final Map.Entry<String, GoalData> entry : goals.entrySet()) {
            final String goalId = entry.getKey();
            final GoalData goal = entry.getValue();

            final BigDecimal remaining = goal.targetAmount.subtract(goal.currentAmount);
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                continue; // Goal already achieved
            }

            // Predict based on current savings rate
            int predictedMonths = 0;
            double achievementProbability = 0.0;

            if (monthlySavings.compareTo(BigDecimal.ZERO) > 0) {
                predictedMonths = remaining.divide(monthlySavings, 0, RoundingMode.UP).intValue();

                // Adjust probability based on savings rate consistency
                final double consistency = calculateSavingsConsistency(historicalTransactions);
                achievementProbability = Math.min(0.95, savingsRate * consistency * 10);

                // If savings rate is too low, reduce probability
                if (savingsRate < 0.1) {
                    achievementProbability *= 0.5;
                }
            } else {
                // No savings - goal unlikely
                achievementProbability = 0.1;
                predictedMonths = 999; // Very long time
            }

            final LocalDate predictedDate = LocalDate.now().plusMonths(predictedMonths);

            predictions.add(
                    new PredictedGoalAchievement(
                            goalId,
                            goal.name,
                            goal.currentAmount,
                            goal.targetAmount,
                            predictedDate,
                            achievementProbability,
                            monthlySavings,
                            remaining,
                            savingsRate));
        }

        return predictions.stream()
                .sorted(
                        Comparator.comparing(PredictedGoalAchievement::getAchievementProbability)
                                .reversed())
                .collect(Collectors.toList());
    }

    /** Predict likely missed payments Uses pattern recognition to identify payments at risk */
    public List<PredictedMissedPayment> predictMissedPayments(
            final List<TransactionTable> historicalTransactions,
            final Map<String, PaymentPattern> paymentPatterns) {

        LOGGER.info("Predicting missed payments");

        final List<PredictedMissedPayment> predictions = new ArrayList<>();

        for (final Map.Entry<String, PaymentPattern> entry : paymentPatterns.entrySet()) {
            final String paymentName = entry.getKey();
            final PaymentPattern pattern = entry.getValue();

            // Find historical payments
            final List<TransactionTable> payments =
                    historicalTransactions.stream()
                            .filter(
                                    tx -> {
                                        final String desc =
                                                tx.getDescription() != null
                                                        ? tx.getDescription()
                                                                .toLowerCase(Locale.ROOT)
                                                        : "";
                                        final String merchant =
                                                tx.getMerchantName() != null
                                                        ? tx.getMerchantName()
                                                                .toLowerCase(Locale.ROOT)
                                                        : "";
                                        final String name = paymentName.toLowerCase(Locale.ROOT);
                                        return desc.contains(name) || merchant.contains(name);
                                    })
                            .sorted(Comparator.comparing(TransactionTable::getTransactionDate))
                            .collect(Collectors.toList());

            if (payments.size() < 3) {
                continue;
            }

            // Analyze payment consistency
            final double consistency =
                    calculatePaymentConsistency(payments, pattern.expectedIntervalDays);

            // Predict missed payment if consistency is low
            if (consistency < 0.7) {
                final LocalDate lastPayment =
                        LocalDate.parse(payments.get(payments.size() - 1).getTransactionDate());
                final LocalDate nextExpected = lastPayment.plusDays(pattern.expectedIntervalDays);
                final LocalDate today = LocalDate.now();

                if (nextExpected.isBefore(today.plusDays(7))) {
                    final double riskProbability = 1.0 - consistency;

                    predictions.add(
                            new PredictedMissedPayment(
                                    paymentName,
                                    nextExpected,
                                    pattern.amount,
                                    riskProbability,
                                    "Payment pattern shows inconsistency - high risk of missing next payment",
                                    ChronoUnit.DAYS.between(today, nextExpected)));
                }
            }
        }

        return predictions.stream()
                .sorted(Comparator.comparing(PredictedMissedPayment::getRiskProbability).reversed())
                .collect(Collectors.toList());
    }

    /** Predict future interest costs Forecasts interest payments based on balance trends */
    public List<PredictedInterestCost> predictInterestCosts(
            final Map<String, AccountData> accounts,
            final List<TransactionTable> historicalTransactions) {

        LOGGER.info("Predicting interest costs");

        final List<PredictedInterestCost> predictions = new ArrayList<>();

        for (final Map.Entry<String, AccountData> entry : accounts.entrySet()) {
            final String accountId = entry.getKey();
            final AccountData account = entry.getValue();

            if (account.interestRate <= 0 || account.balance.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }

            // Find interest charges for this account
            historicalTransactions.stream()
                    .filter(tx -> accountId.equals(tx.getAccountId()))
                    .filter(
                            tx -> {
                                final String desc =
                                        tx.getDescription() != null
                                                ? tx.getDescription().toLowerCase(Locale.ROOT)
                                                : "";
                                return desc.contains("interest") || desc.contains("finance charge");
                            })
                    .sorted(Comparator.comparing(TransactionTable::getTransactionDate))
                    .collect(Collectors.toList());

            // Analyze balance trend
            final List<BigDecimal> balances =
                    extractBalanceHistory(historicalTransactions, accountId);
            final TrendAnalysis balanceTrend = analyzeAmountTrend(balances);

            // Predict future interest based on balance trend
            BigDecimal predictedBalance = predictNextAmountFromTrend(balances, balanceTrend);
            if (predictedBalance.compareTo(BigDecimal.ZERO) <= 0) {
                predictedBalance = account.balance; // Use current if prediction is negative
            }

            final BigDecimal monthlyInterest =
                    predictedBalance.multiply(BigDecimal.valueOf(account.interestRate / 12));
            final BigDecimal annualInterest = monthlyInterest.multiply(BigDecimal.valueOf(12));

            // Calculate potential savings if balance is reduced
            final BigDecimal currentAnnualInterest =
                    account.balance.multiply(BigDecimal.valueOf(account.interestRate));
            final BigDecimal potentialSavings = currentAnnualInterest.subtract(annualInterest);

            double confidence = balanceTrend.confidence;
            if (balances.size() < MIN_DATA_POINTS) {
                confidence *= 0.7; // Lower confidence with less data
            }

            predictions.add(
                    new PredictedInterestCost(
                            accountId,
                            account.accountName,
                            account.interestRate,
                            account.balance,
                            predictedBalance,
                            monthlyInterest,
                            annualInterest,
                            potentialSavings,
                            confidence,
                            balanceTrend.slope < 0
                                    ? "Balance decreasing - interest costs will reduce"
                                    : "Balance increasing - interest costs will increase"));
        }

        return predictions.stream()
                .sorted(Comparator.comparing(PredictedInterestCost::getAnnualInterest).reversed())
                .collect(Collectors.toList());
    }

    // MARK: - Helper Methods for ML Analysis

    /** Analyze trend in transaction amounts */
    private TrendAnalysis analyzeTrend(final List<TransactionTable> transactions) {
        if (transactions.size() < 2) {
            return new TrendAnalysis(0.0, 0.0);
        }

        // Sort by date
        final List<TransactionTable> sorted =
                transactions.stream()
                        .sorted(Comparator.comparing(TransactionTable::getTransactionDate))
                        .collect(Collectors.toList());

        // Extract amounts over time
        final List<Double> amounts =
                sorted.stream()
                        .map(
                                tx ->
                                        tx.getAmount() != null
                                                ? tx.getAmount().abs().doubleValue()
                                                : 0.0)
                        .collect(Collectors.toList());

        return analyzeAmountTrend(
                amounts.stream().map(BigDecimal::valueOf).collect(Collectors.toList()));
    }

    /** Analyze trend in amount list using linear regression */
    private TrendAnalysis analyzeAmountTrend(final List<BigDecimal> amounts) {
        if (amounts.size() < 2) {
            return new TrendAnalysis(0.0, 0.0);
        }

        final int n = amounts.size();
        double sumX = 0.0;
        double sumY = 0.0;
        double sumXY = 0.0;
        double sumX2 = 0.0;

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
            return new TrendAnalysis(0.0, 0.5); // No trend
        }

        final double slope = (n * sumXY - sumX * sumY) / denominator;
        final double intercept = (sumY - slope * sumX) / n;

        // Calculate R-squared for confidence
        final double meanY = sumY / n;
        double ssRes = 0.0;
        double ssTot = 0.0;

        for (int i = 0; i < n; i++) {
            final double x = i;
            final double y = amounts.get(i).doubleValue();
            final double predicted = slope * x + intercept;
            ssRes += Math.pow(y - predicted, 2);
            ssTot += Math.pow(y - meanY, 2);
        }

        final double rSquared = ssTot > 0 ? 1.0 - (ssRes / ssTot) : 0.0;
        final double confidence = Math.max(0.0, Math.min(1.0, rSquared));

        return new TrendAnalysis(slope, confidence);
    }

    /** Analyze usage frequency trend */
    private TrendAnalysis analyzeUsageFrequency(final List<TransactionTable> transactions) {
        if (transactions.size() < 3) {
            return new TrendAnalysis(0.0, 0.0);
        }

        // Group by month and count transactions per month
        final Map<String, Integer> monthlyCounts = new HashMap<>();
        for (final TransactionTable tx : transactions) {
            final String date = tx.getTransactionDate();
            if (date != null && date.length() >= 7) {
                final String month = date.substring(0, 7); // YYYY-MM
                monthlyCounts.put(month, monthlyCounts.getOrDefault(month, 0) + 1);
            }
        }

        final List<String> sortedMonths =
                monthlyCounts.keySet().stream().sorted().collect(Collectors.toList());
        if (sortedMonths.size() < 2) {
            return new TrendAnalysis(0.0, 0.0);
        }

        final List<BigDecimal> counts =
                sortedMonths.stream()
                        .map(month -> BigDecimal.valueOf(monthlyCounts.get(month)))
                        .collect(Collectors.toList());

        return analyzeAmountTrend(counts);
    }

    /** Predict next amount using linear regression */
    private BigDecimal predictNextAmount(
            final List<TransactionTable> transactions, final int daysAhead) {
        final TrendAnalysis trend = analyzeTrend(transactions);
        final BigDecimal average = calculateAverage(transactions);

        // Predict based on trend
        final int nextIndex = transactions.size();
        final double predicted =
                trend.slope * nextIndex
                        + (average.doubleValue() - trend.slope * (transactions.size() / 2.0));

        return BigDecimal.valueOf(Math.max(0, predicted));
    }

    /** Predict next amount from trend */
    private BigDecimal predictNextAmountFromTrend(
            final List<BigDecimal> amounts, final TrendAnalysis trend) {
        if (amounts.isEmpty()) {
            return BigDecimal.ZERO;
        }

        final BigDecimal average = calculateAverageFromList(amounts);
        final int nextIndex = amounts.size();
        final double predicted =
                trend.slope * nextIndex
                        + (average.doubleValue() - trend.slope * (amounts.size() / 2.0));

        return BigDecimal.valueOf(Math.max(0, predicted));
    }

    /** Calculate average transaction amount */
    private BigDecimal calculateAverage(final List<TransactionTable> transactions) {
        if (transactions.isEmpty()) {
            return BigDecimal.ZERO;
        }

        final BigDecimal sum =
                transactions.stream()
                        .map(tx -> tx.getAmount() != null ? tx.getAmount().abs() : BigDecimal.ZERO)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

        return sum.divide(BigDecimal.valueOf(transactions.size()), 2, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateAverageFromList(final List<BigDecimal> amounts) {
        if (amounts.isEmpty()) {
            return BigDecimal.ZERO;
        }

        final BigDecimal sum = amounts.stream().reduce(BigDecimal.ZERO, BigDecimal::add);

        return sum.divide(BigDecimal.valueOf(amounts.size()), 2, RoundingMode.HALF_UP);
    }

    /** Calculate median */
    private BigDecimal calculateMedian(final List<BigDecimal> amounts) {
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
        } else {
            return sorted.get(size / 2);
        }
    }

    /** Calculate percentile */
    private BigDecimal calculatePercentile(
            final List<BigDecimal> amounts, final double percentile) {
        if (amounts.isEmpty()) {
            return BigDecimal.ZERO;
        }

        final List<BigDecimal> sorted = new ArrayList<>(amounts);
        Collections.sort(sorted);

        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        index = Math.max(0, Math.min(index, sorted.size() - 1));

        return sorted.get(index);
    }

    /** Calculate monthly income */
    private BigDecimal calculateMonthlyIncome(final List<TransactionTable> transactions) {
        final List<TransactionTable> income =
                transactions.stream()
                        .filter(
                                tx ->
                                        tx.getAmount() != null
                                                && tx.getAmount().compareTo(BigDecimal.ZERO) > 0)
                        .collect(Collectors.toList());

        if (income.isEmpty()) {
            return BigDecimal.ZERO;
        }

        // Group by month and calculate average
        final Map<String, BigDecimal> monthlyIncome = new HashMap<>();
        for (final TransactionTable tx : income) {
            final String date = tx.getTransactionDate();
            if (date != null && date.length() >= 7) {
                final String month = date.substring(0, 7);
                final BigDecimal amount = tx.getAmount();
                monthlyIncome.put(
                        month, monthlyIncome.getOrDefault(month, BigDecimal.ZERO).add(amount));
            }
        }

        if (monthlyIncome.isEmpty()) {
            return BigDecimal.ZERO;
        }

        final BigDecimal sum =
                monthlyIncome.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);

        return sum.divide(BigDecimal.valueOf(monthlyIncome.size()), 2, RoundingMode.HALF_UP);
    }

    /** Calculate monthly expenses */
    private BigDecimal calculateMonthlyExpenses(final List<TransactionTable> transactions) {
        final List<TransactionTable> expenses =
                transactions.stream()
                        .filter(
                                tx ->
                                        tx.getAmount() != null
                                                && tx.getAmount().compareTo(BigDecimal.ZERO) < 0)
                        .collect(Collectors.toList());

        if (expenses.isEmpty()) {
            return BigDecimal.ZERO;
        }

        // Group by month and calculate average
        final Map<String, BigDecimal> monthlyExpenses = new HashMap<>();
        for (final TransactionTable tx : expenses) {
            final String date = tx.getTransactionDate();
            if (date != null && date.length() >= 7) {
                final String month = date.substring(0, 7);
                final BigDecimal amount = tx.getAmount().abs();
                monthlyExpenses.put(
                        month, monthlyExpenses.getOrDefault(month, BigDecimal.ZERO).add(amount));
            }
        }

        if (monthlyExpenses.isEmpty()) {
            return BigDecimal.ZERO;
        }

        final BigDecimal sum =
                monthlyExpenses.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);

        return sum.divide(BigDecimal.valueOf(monthlyExpenses.size()), 2, RoundingMode.HALF_UP);
    }

    /** Calculate savings consistency (how consistent savings are month-to-month) */
    private double calculateSavingsConsistency(final List<TransactionTable> transactions) {
        final BigDecimal monthlyIncome = calculateMonthlyIncome(transactions);
        final BigDecimal monthlyExpenses = calculateMonthlyExpenses(transactions);

        if (monthlyIncome.compareTo(BigDecimal.ZERO) <= 0) {
            return 0.0;
        }

        // Group by month and calculate savings rate per month
        final Map<String, BigDecimal> monthlyIncomeMap = new HashMap<>();
        final Map<String, BigDecimal> monthlyExpensesMap = new HashMap<>();

        for (final TransactionTable tx : transactions) {
            final String date = tx.getTransactionDate();
            if (date != null && date.length() >= 7) {
                final String month = date.substring(0, 7);
                if (tx.getAmount() != null) {
                    if (tx.getAmount().compareTo(BigDecimal.ZERO) > 0) {
                        monthlyIncomeMap.put(
                                month,
                                monthlyIncomeMap
                                        .getOrDefault(month, BigDecimal.ZERO)
                                        .add(tx.getAmount()));
                    } else {
                        monthlyExpensesMap.put(
                                month,
                                monthlyExpensesMap
                                        .getOrDefault(month, BigDecimal.ZERO)
                                        .add(tx.getAmount().abs()));
                    }
                }
            }
        }

        final Set<String> months = new HashSet<>(monthlyIncomeMap.keySet());
        months.addAll(monthlyExpensesMap.keySet());

        if (months.size() < 3) {
            return 0.5; // Default consistency
        }

        // Calculate coefficient of variation for savings rates
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

        final double mean =
                savingsRates.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        final double variance =
                savingsRates.stream().mapToDouble(r -> Math.pow(r - mean, 2)).average().orElse(0.0);
        final double stdDev = Math.sqrt(variance);

        // Consistency is inverse of coefficient of variation
        final double cv = mean != 0 ? stdDev / Math.abs(mean) : 1.0;
        return Math.max(0.0, Math.min(1.0, 1.0 - cv));
    }

    /** Calculate payment consistency */
    private double calculatePaymentConsistency(
            final List<TransactionTable> payments, final int expectedIntervalDays) {
        if (payments.size() < 2) {
            return 0.5; // Default consistency
        }

        final List<Long> intervals = new ArrayList<>();
        for (int i = 1; i < payments.size(); i++) {
            final LocalDate date1 = LocalDate.parse(payments.get(i - 1).getTransactionDate());
            final LocalDate date2 = LocalDate.parse(payments.get(i).getTransactionDate());
            final long days = ChronoUnit.DAYS.between(date1, date2);
            intervals.add(days);
        }

        if (intervals.isEmpty()) {
            return 0.5;
        }

        final double meanInterval =
                intervals.stream()
                        .mapToLong(Long::longValue)
                        .average()
                        .orElse(expectedIntervalDays);
        final double variance =
                intervals.stream()
                        .mapToDouble(i -> Math.pow(i - meanInterval, 2))
                        .average()
                        .orElse(0.0);
        final double stdDev = Math.sqrt(variance);

        // Consistency based on how close intervals are to expected
        final double cv = meanInterval != 0 ? stdDev / meanInterval : 1.0;
        return Math.max(0.0, Math.min(1.0, 1.0 - cv));
    }

    /** Extract balance history from transactions */
    private List<BigDecimal> extractBalanceHistory(
            final List<TransactionTable> transactions, final String accountId) {
        // This is a simplified version - in reality, you'd track account balances over time
        // For now, we'll use transaction amounts as a proxy
        return transactions.stream()
                .filter(tx -> accountId.equals(tx.getAccountId()))
                .map(tx -> tx.getAmount() != null ? tx.getAmount().abs() : BigDecimal.ZERO)
                .limit(12) // Last 12 transactions
                .collect(Collectors.toList());
    }

    // MARK: - Model Classes

    public static class TrendAnalysis {
        /* default */ final double slope; // Positive = increasing, Negative = decreasing
        /* default */ final double confidence; // 0.0 to 1.0

        /* default */ TrendAnalysis(final double slope, final double confidence) {
            this.slope = slope;
            this.confidence = confidence;
        }
    }

    public static class PredictedAnomaly {
        private final String category;
        private final BigDecimal predictedAmount;
        private final BigDecimal historicalAverage;
        private final double confidence;
        private final String reason;
        private final LocalDate predictedDate;

        public PredictedAnomaly(
                final String category,
                final BigDecimal predictedAmount,
                final BigDecimal historicalAverage,
                final double confidence,
                final String reason,
                final LocalDate predictedDate) {
            this.category = category;
            this.predictedAmount = predictedAmount;
            this.historicalAverage = historicalAverage;
            this.confidence = confidence;
            this.reason = reason;
            this.predictedDate = predictedDate;
        }

        public String getCategory() {
            return category;
        }

        public BigDecimal getPredictedAmount() {
            return predictedAmount;
        }

        public BigDecimal getHistoricalAverage() {
            return historicalAverage;
        }

        public double getConfidence() {
            return confidence;
        }

        public String getReason() {
            return reason;
        }

        public LocalDate getPredictedDate() {
            return predictedDate;
        }
    }

    public static class PredictedExpenseReduction {
        private final String expenseName;
        private final BigDecimal monthlySavings;
        private final BigDecimal annualSavings;
        private final double probability;
        private final String reason;
        private final LocalDate predictedDate;

        public PredictedExpenseReduction(
                final String expenseName,
                final BigDecimal monthlySavings,
                final BigDecimal annualSavings,
                final double probability,
                final String reason,
                final LocalDate predictedDate) {
            this.expenseName = expenseName;
            this.monthlySavings = monthlySavings;
            this.annualSavings = annualSavings;
            this.probability = probability;
            this.reason = reason;
            this.predictedDate = predictedDate;
        }

        public String getExpenseName() {
            return expenseName;
        }

        public BigDecimal getMonthlySavings() {
            return monthlySavings;
        }

        public BigDecimal getAnnualSavings() {
            return annualSavings;
        }

        public double getProbability() {
            return probability;
        }

        public String getReason() {
            return reason;
        }

        public LocalDate getPredictedDate() {
            return predictedDate;
        }
    }

    public static class PredictedGoalAchievement {
        private final String goalId;
        private final String goalName;
        private final BigDecimal currentAmount;
        private final BigDecimal targetAmount;
        private final LocalDate predictedDate;
        private final double achievementProbability;
        private final BigDecimal monthlySavings;
        private final BigDecimal remaining;
        private final double savingsRate;

        public PredictedGoalAchievement(
                final String goalId,
                final String goalName,
                final BigDecimal currentAmount,
                final BigDecimal targetAmount,
                final LocalDate predictedDate,
                final double achievementProbability,
                final BigDecimal monthlySavings,
                final BigDecimal remaining,
                final double savingsRate) {
            this.goalId = goalId;
            this.goalName = goalName;
            this.currentAmount = currentAmount;
            this.targetAmount = targetAmount;
            this.predictedDate = predictedDate;
            this.achievementProbability = achievementProbability;
            this.monthlySavings = monthlySavings;
            this.remaining = remaining;
            this.savingsRate = savingsRate;
        }

        public String getGoalId() {
            return goalId;
        }

        public String getGoalName() {
            return goalName;
        }

        public BigDecimal getCurrentAmount() {
            return currentAmount;
        }

        public BigDecimal getTargetAmount() {
            return targetAmount;
        }

        public LocalDate getPredictedDate() {
            return predictedDate;
        }

        public double getAchievementProbability() {
            return achievementProbability;
        }

        public BigDecimal getMonthlySavings() {
            return monthlySavings;
        }

        public BigDecimal getRemaining() {
            return remaining;
        }

        public double getSavingsRate() {
            return savingsRate;
        }
    }

    public static class PredictedMissedPayment {
        private final String paymentName;
        private final LocalDate dueDate;
        private final BigDecimal amount;
        private final double riskProbability;
        private final String reason;
        private final long daysUntilDue;

        public PredictedMissedPayment(
                final String paymentName,
                final LocalDate dueDate,
                final BigDecimal amount,
                final double riskProbability,
                final String reason,
                final long daysUntilDue) {
            this.paymentName = paymentName;
            this.dueDate = dueDate;
            this.amount = amount;
            this.riskProbability = riskProbability;
            this.reason = reason;
            this.daysUntilDue = daysUntilDue;
        }

        public String getPaymentName() {
            return paymentName;
        }

        public LocalDate getDueDate() {
            return dueDate;
        }

        public BigDecimal getAmount() {
            return amount;
        }

        public double getRiskProbability() {
            return riskProbability;
        }

        public String getReason() {
            return reason;
        }

        public long getDaysUntilDue() {
            return daysUntilDue;
        }
    }

    public static class PredictedInterestCost {
        private final String accountId;
        private final String accountName;
        private final double interestRate;
        private final BigDecimal currentBalance;
        private final BigDecimal predictedBalance;
        private final BigDecimal monthlyInterest;
        private final BigDecimal annualInterest;
        private final BigDecimal potentialSavings;
        private final double confidence;
        private final String trend;

        public PredictedInterestCost(
                final String accountId,
                final String accountName,
                final double interestRate,
                final BigDecimal currentBalance,
                final BigDecimal predictedBalance,
                final BigDecimal monthlyInterest,
                final BigDecimal annualInterest,
                final BigDecimal potentialSavings,
                final double confidence,
                final String trend) {
            this.accountId = accountId;
            this.accountName = accountName;
            this.interestRate = interestRate;
            this.currentBalance = currentBalance;
            this.predictedBalance = predictedBalance;
            this.monthlyInterest = monthlyInterest;
            this.annualInterest = annualInterest;
            this.potentialSavings = potentialSavings;
            this.confidence = confidence;
            this.trend = trend;
        }

        public String getAccountId() {
            return accountId;
        }

        public String getAccountName() {
            return accountName;
        }

        public double getInterestRate() {
            return interestRate;
        }

        public BigDecimal getCurrentBalance() {
            return currentBalance;
        }

        public BigDecimal getPredictedBalance() {
            return predictedBalance;
        }

        public BigDecimal getMonthlyInterest() {
            return monthlyInterest;
        }

        public BigDecimal getAnnualInterest() {
            return annualInterest;
        }

        public BigDecimal getPotentialSavings() {
            return potentialSavings;
        }

        public double getConfidence() {
            return confidence;
        }

        public String getTrend() {
            return trend;
        }
    }

    // Data transfer objects for predictions
    public static class GoalData {
        /* default */ final String name;
        /* default */ final BigDecimal currentAmount;
        /* default */ final BigDecimal targetAmount;

        public GoalData(
                final String name, final BigDecimal currentAmount, final BigDecimal targetAmount) {
            this.name = name;
            this.currentAmount = currentAmount;
            this.targetAmount = targetAmount;
        }
    }

    public static class PaymentPattern {
        /* default */ final BigDecimal amount;
        /* default */ final int expectedIntervalDays; // e.g., 30 for monthly

        public PaymentPattern(final BigDecimal amount, final int expectedIntervalDays) {
            this.amount = amount;
            this.expectedIntervalDays = expectedIntervalDays;
        }
    }

    public static class AccountData {
        /* default */ final String accountName;
        /* default */ final BigDecimal balance;
        /* default */ final double interestRate;

        public AccountData(
                final String accountName, final BigDecimal balance, final double interestRate) {
            this.accountName = accountName;
            this.balance = balance;
            this.interestRate = interestRate;
        }
    }
}
