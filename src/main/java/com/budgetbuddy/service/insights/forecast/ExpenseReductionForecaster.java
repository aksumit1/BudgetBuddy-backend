package com.budgetbuddy.service.insights.forecast;

import com.budgetbuddy.config.FinancialInsightsPredictionProperties;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.service.ml.FinancialInsightsPredictionService.PredictedExpenseReduction;
import com.budgetbuddy.service.ml.FinancialInsightsPredictionService.TrendAnalysis;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Predicts which subscriptions and categories are likely to become
 * cuttable in the near future. Extracted from
 * {@code FinancialInsightsPredictionService.predictExpenseReductions}.
 *
 * <p>Two prediction families:
 * <ul>
 *   <li><b>Subscription cancellation</b> — usage frequency trending
 *       downward (slope &lt; -0.1) with high confidence.</li>
 *   <li><b>Category overspending</b> — predicted next-month spend
 *       exceeds 1.2× the current category average.</li>
 * </ul>
 */
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Spring constructor injection — beans are shared by design")
@Service
public class ExpenseReductionForecaster {

    private static final Logger LOGGER = LoggerFactory.getLogger(ExpenseReductionForecaster.class);
    private final FinancialInsightsPredictionProperties props;

    public ExpenseReductionForecaster(final FinancialInsightsPredictionProperties props) {
        // Defensive default for Mockito @InjectMocks paths.
        this.props = props != null ? props : new FinancialInsightsPredictionProperties();
    }

    public List<PredictedExpenseReduction> forecast(
            final List<TransactionTable> historicalTransactions,
            final Map<String, BigDecimal> currentSubscriptions) {
        LOGGER.info("Forecasting expense-reduction opportunities");

        if (historicalTransactions == null) {
            return new ArrayList<>();
        }
        final List<PredictedExpenseReduction> predictions = new ArrayList<>();

        if (currentSubscriptions != null) {
            predictions.addAll(subscriptionPredictions(historicalTransactions, currentSubscriptions));
        }
        predictions.addAll(categoryOverspendingPredictions(historicalTransactions));

        return predictions.stream()
                .sorted(Comparator.comparing(PredictedExpenseReduction::getProbability).reversed())
                .collect(Collectors.toList());
    }

    private List<PredictedExpenseReduction> subscriptionPredictions(
            final List<TransactionTable> historicalTransactions,
            final Map<String, BigDecimal> currentSubscriptions) {
        final List<PredictedExpenseReduction> out = new ArrayList<>();
        for (final Map.Entry<String, BigDecimal> entry : currentSubscriptions.entrySet()) {
            final String subscriptionName = entry.getKey();
            final BigDecimal monthlyCost = entry.getValue();

            final List<TransactionTable> subscriptionTx = historicalTransactions.stream()
                    .filter(tx -> {
                        final String desc = tx.getDescription() != null
                                ? tx.getDescription().toLowerCase(Locale.ROOT) : "";
                        final String merchant = tx.getMerchantName() != null
                                ? tx.getMerchantName().toLowerCase(Locale.ROOT) : "";
                        final String name = subscriptionName.toLowerCase(Locale.ROOT);
                        return desc.contains(name) || merchant.contains(name);
                    })
                    .sorted(Comparator.comparing(TransactionTable::getTransactionDate).reversed())
                    .collect(Collectors.toList());

            if (subscriptionTx.size() < 3) {
                continue;
            }
            final TrendAnalysis usageTrend = ForecastMath.analyzeUsageFrequency(subscriptionTx);
            if (usageTrend.slope < -0.1 && usageTrend.confidence > props.getMediumConfidence()) {
                final double cancellationProbability =
                        Math.min(0.9, (Math.abs(usageTrend.slope) * 10) * usageTrend.confidence);
                out.add(new PredictedExpenseReduction(
                        subscriptionName,
                        monthlyCost,
                        monthlyCost.multiply(BigDecimal.valueOf(12)),
                        cancellationProbability,
                        "Usage frequency decreasing - likely to become unused",
                        LocalDate.now().plusDays(30)));
            }
        }
        return out;
    }

    private List<PredictedExpenseReduction> categoryOverspendingPredictions(
            final List<TransactionTable> historicalTransactions) {
        final List<PredictedExpenseReduction> out = new ArrayList<>();
        final Map<String, List<TransactionTable>> byCategory = historicalTransactions.stream()
                .filter(tx -> tx.getAmount() != null
                        && tx.getAmount().compareTo(BigDecimal.ZERO) < 0)
                .filter(tx -> tx.getCategoryPrimary() != null
                        && !tx.getCategoryPrimary().isEmpty())
                .collect(Collectors.groupingBy(TransactionTable::getCategoryPrimary));

        for (final Map.Entry<String, List<TransactionTable>> entry : byCategory.entrySet()) {
            final List<TransactionTable> categoryTx = entry.getValue();
            if (categoryTx.size() < props.getMinDataPoints()) {
                continue;
            }
            final TrendAnalysis trend = ForecastMath.analyzeTrend(categoryTx);
            if (trend.slope > 0 && trend.confidence > props.getMediumConfidence()) {
                final BigDecimal predictedNextMonth = ForecastMath.predictNextAmount(categoryTx, 30);
                final BigDecimal currentAverage = ForecastMath.averageOfTransactions(categoryTx);
                if (predictedNextMonth.compareTo(currentAverage.multiply(BigDecimal.valueOf(1.2)))
                        > 0) {
                    final BigDecimal potentialSavings =
                            predictedNextMonth.subtract(currentAverage);
                    out.add(new PredictedExpenseReduction(
                            entry.getKey() + " Spending",
                            potentialSavings,
                            potentialSavings.multiply(BigDecimal.valueOf(12)),
                            trend.confidence,
                            "Predicted overspending - reduce by 20% to stay on track",
                            LocalDate.now().plusDays(30)));
                }
            }
        }
        return out;
    }
}
