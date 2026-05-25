package com.budgetbuddy.service.insights.forecast;

import com.budgetbuddy.config.FinancialInsightsPredictionProperties;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.service.ml.FinancialInsightsPredictionService.PredictedAnomaly;
import com.budgetbuddy.service.ml.FinancialInsightsPredictionService.TrendAnalysis;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Forecasts transaction-anomaly risk for the upcoming window. Extracted
 * verbatim from {@code FinancialInsightsPredictionService.predictAnomalies}
 * so output is identical to the pre-split behaviour.
 *
 * <p>Two prediction families:
 * <ol>
 *   <li><b>Category spike</b> — when a category's spend trend rises with
 *       high confidence and the predicted next-period amount exceeds
 *       {@code categorySpikeMultiplier × historicalAverage}.</li>
 *   <li><b>Amount threshold</b> — when the overall amount trend is rising
 *       and the predicted next amount exceeds the Tukey upper-fence
 *       ({@code Q3 + iqrMultiplier × IQR}).</li>
 * </ol>
 */
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Spring constructor injection — beans are shared by design")
@Service
public class AnomalyForecaster {

    private static final Logger LOGGER = LoggerFactory.getLogger(AnomalyForecaster.class);

    private final FinancialInsightsPredictionProperties props;

    public AnomalyForecaster(final FinancialInsightsPredictionProperties props) {
        // Defensive default so Mockito @InjectMocks paths work without
        // a separate @Mock FinancialInsightsPredictionProperties.
        this.props = props != null ? props : new FinancialInsightsPredictionProperties();
    }

    /**
     * @param historicalTransactions all known transactions for the user
     * @param daysAhead              days into the future to forecast
     * @return predicted anomalies sorted by confidence (desc)
     */
    public List<PredictedAnomaly> forecast(
            final List<TransactionTable> historicalTransactions, final int daysAhead) {
        LOGGER.info("Forecasting anomalies for next {} days", daysAhead);

        final List<PredictedAnomaly> predictions = new ArrayList<>();
        if (historicalTransactions == null
                || historicalTransactions.size() < props.getMinDataPoints()) {
            return predictions;
        }

        predictions.addAll(categorySpikePredictions(historicalTransactions, daysAhead));
        predictions.addAll(amountThresholdPredictions(historicalTransactions));

        return predictions.stream()
                .sorted(Comparator.comparing(PredictedAnomaly::getConfidence).reversed())
                .collect(Collectors.toList());
    }

    // --- category spike ---

    private List<PredictedAnomaly> categorySpikePredictions(
            final List<TransactionTable> historicalTransactions, final int daysAhead) {
        final List<PredictedAnomaly> out = new ArrayList<>();
        final Map<String, List<TransactionTable>> byCategory =
                historicalTransactions.stream()
                        .filter(tx -> tx.getAmount() != null
                                && tx.getAmount().compareTo(BigDecimal.ZERO) < 0)
                        .filter(tx -> tx.getCategoryPrimary() != null
                                && !tx.getCategoryPrimary().isEmpty())
                        .collect(Collectors.groupingBy(TransactionTable::getCategoryPrimary));

        for (final Map.Entry<String, List<TransactionTable>> entry : byCategory.entrySet()) {
            final List<TransactionTable> categoryTx = entry.getValue();
            if (categoryTx.size() < 3) {
                continue;
            }
            final List<BigDecimal> amounts = categoryTx.stream()
                    .sorted(Comparator.comparing(TransactionTable::getTransactionDate))
                    .map(tx -> tx.getAmount() != null
                            ? tx.getAmount().abs() : BigDecimal.ZERO)
                    .collect(Collectors.toList());
            final TrendAnalysis trend = ForecastMath.analyzeAmountTrend(amounts);
            if (!(trend.slope > 0 && trend.confidence > props.getMediumConfidence())) {
                continue;
            }
            final BigDecimal average = ForecastMath.average(amounts);
            final BigDecimal predicted =
                    ForecastMath.predictNext(amounts.size(), average.doubleValue(), trend.slope);
            if (predicted.compareTo(
                            average.multiply(BigDecimal.valueOf(
                                    props.getCategorySpikeMultiplier())))
                    > 0) {
                final double confidence = Math.min(0.9, trend.confidence * 1.1);
                out.add(new PredictedAnomaly(
                        entry.getKey(),
                        predicted,
                        average,
                        confidence,
                        "Predicted category spike based on increasing trend",
                        LocalDate.now().plusDays(daysAhead)));
            }
        }
        return out;
    }

    // --- amount threshold ---

    private List<PredictedAnomaly> amountThresholdPredictions(
            final List<TransactionTable> historicalTransactions) {
        final List<PredictedAnomaly> out = new ArrayList<>();
        final List<BigDecimal> amounts = historicalTransactions.stream()
                .filter(tx -> tx.getAmount() != null
                        && tx.getAmount().compareTo(BigDecimal.ZERO) < 0)
                .map(tx -> tx.getAmount().abs())
                .sorted()
                .collect(Collectors.toList());

        if (amounts.size() < props.getMinDataPoints()) {
            return out;
        }
        final BigDecimal median = ForecastMath.median(amounts);
        final BigDecimal q3 = ForecastMath.percentile(amounts, 0.75);
        final BigDecimal iqr = q3.subtract(ForecastMath.percentile(amounts, 0.25));
        final BigDecimal threshold =
                q3.add(iqr.multiply(BigDecimal.valueOf(props.getIqrMultiplier())));

        final TrendAnalysis amountTrend = ForecastMath.analyzeAmountTrend(amounts);
        if (!(amountTrend.slope > 0 && amountTrend.confidence > props.getMediumConfidence())) {
            return out;
        }
        final BigDecimal average = ForecastMath.average(amounts);
        final BigDecimal predicted = ForecastMath.predictNext(
                amounts.size(), average.doubleValue(), amountTrend.slope);
        if (predicted.compareTo(threshold) > 0) {
            out.add(new PredictedAnomaly(
                    "Amount Threshold",
                    predicted,
                    median,
                    amountTrend.confidence,
                    "Predicted unusually high transaction based on increasing trend",
                    LocalDate.now().plusDays(7)));
        }
        return out;
    }
}
