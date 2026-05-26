package com.budgetbuddy.service.insights.forecast;

import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.service.ml.FinancialInsightsPredictionService.PaymentPattern;
import com.budgetbuddy.service.ml.FinancialInsightsPredictionService.PredictedMissedPayment;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
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
 * Predicts which recurring payments are at risk of being missed in
 * the next 7 days. A payment is "at risk" when:
 * <ul>
 *   <li>its historical spacing is inconsistent (consistency &lt; 0.7)
 *       — i.e. the user has been skipping payments or paying late;</li>
 *   <li>AND the next expected date falls within the next 7 days from
 *       today.</li>
 * </ul>
 *
 * <p>Risk probability is {@code 1 − consistency}; sorted high-to-low.
 */
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Spring constructor injection — beans are shared by design")
@Service
public class MissedPaymentForecaster {

    private static final Logger LOGGER = LoggerFactory.getLogger(MissedPaymentForecaster.class);

    public List<PredictedMissedPayment> forecast(
            final List<TransactionTable> historicalTransactions,
            final Map<String, PaymentPattern> paymentPatterns) {
        LOGGER.info("Forecasting missed payments");
        final List<PredictedMissedPayment> predictions = new ArrayList<>();
        if (historicalTransactions == null || paymentPatterns == null) {
            return predictions;
        }

        for (final Map.Entry<String, PaymentPattern> entry : paymentPatterns.entrySet()) {
            final String paymentName = entry.getKey();
            final PaymentPattern pattern = entry.getValue();

            final List<TransactionTable> payments = historicalTransactions.stream()
                    .filter(tx -> {
                        final String desc = tx.getDescription() != null
                                ? tx.getDescription().toLowerCase(Locale.ROOT) : "";
                        final String merchant = tx.getMerchantName() != null
                                ? tx.getMerchantName().toLowerCase(Locale.ROOT) : "";
                        final String name = paymentName.toLowerCase(Locale.ROOT);
                        return desc.contains(name) || merchant.contains(name);
                    })
                    .sorted(Comparator.comparing(TransactionTable::getTransactionDate))
                    .toList();
            if (payments.size() < 3) {
                continue;
            }

            final double consistency = ForecastMath.calculatePaymentConsistency(
                    payments, pattern.expectedIntervalDays);
            if (consistency >= 0.7) {
                continue;
            }
            final LocalDate lastPayment = LocalDate.parse(
                    payments.get(payments.size() - 1).getTransactionDate());
            final LocalDate nextExpected = lastPayment.plusDays(pattern.expectedIntervalDays);
            final LocalDate today = LocalDate.now();
            if (!nextExpected.isBefore(today.plusDays(7))) {
                continue;
            }
            final double riskProbability = 1.0 - consistency;
            predictions.add(new PredictedMissedPayment(
                    paymentName,
                    nextExpected,
                    pattern.amount,
                    riskProbability,
                    "Payment pattern shows inconsistency - high risk of missing next payment",
                    ChronoUnit.DAYS.between(today, nextExpected)));
        }
        return predictions.stream()
                .sorted(Comparator.comparing(PredictedMissedPayment::getRiskProbability).reversed())
                .toList();
    }
}
