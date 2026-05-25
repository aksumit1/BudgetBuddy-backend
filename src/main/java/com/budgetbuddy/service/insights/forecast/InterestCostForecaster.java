package com.budgetbuddy.service.insights.forecast;

import com.budgetbuddy.config.FinancialInsightsPredictionProperties;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.service.ml.FinancialInsightsPredictionService.AccountData;
import com.budgetbuddy.service.ml.FinancialInsightsPredictionService.PredictedInterestCost;
import com.budgetbuddy.service.ml.FinancialInsightsPredictionService.TrendAnalysis;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Forecasts 12-month interest-cost trajectory for each account based
 * on its balance trend. Predictions discount confidence by 30% when
 * fewer than {@code minDataPoints} balance observations exist.
 */
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Spring constructor injection — beans are shared by design")
@Service
public class InterestCostForecaster {

    private static final Logger LOGGER = LoggerFactory.getLogger(InterestCostForecaster.class);
    private final FinancialInsightsPredictionProperties props;

    public InterestCostForecaster(final FinancialInsightsPredictionProperties props) {
        // Defensive default for Mockito @InjectMocks paths.
        this.props = props != null ? props : new FinancialInsightsPredictionProperties();
    }

    public List<PredictedInterestCost> forecast(
            final Map<String, AccountData> accounts,
            final List<TransactionTable> historicalTransactions) {
        LOGGER.info("Forecasting interest costs");
        final List<PredictedInterestCost> predictions = new ArrayList<>();
        if (accounts == null || accounts.isEmpty()) {
            return predictions;
        }
        final List<TransactionTable> txs = historicalTransactions == null
                ? new ArrayList<>() : historicalTransactions;

        for (final Map.Entry<String, AccountData> entry : accounts.entrySet()) {
            final String accountId = entry.getKey();
            final AccountData account = entry.getValue();
            if (account.interestRate <= 0
                    || account.balance.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }

            final List<BigDecimal> balances = ForecastMath.extractBalanceHistory(txs, accountId);
            final TrendAnalysis balanceTrend = ForecastMath.analyzeAmountTrend(balances);

            BigDecimal predictedBalance =
                    ForecastMath.predictNextAmountFromTrend(balances, balanceTrend);
            if (predictedBalance.compareTo(BigDecimal.ZERO) <= 0) {
                predictedBalance = account.balance;
            }
            final BigDecimal monthlyInterest =
                    predictedBalance.multiply(BigDecimal.valueOf(account.interestRate / 12));
            final BigDecimal annualInterest = monthlyInterest.multiply(BigDecimal.valueOf(12));
            final BigDecimal currentAnnualInterest =
                    account.balance.multiply(BigDecimal.valueOf(account.interestRate));
            final BigDecimal potentialSavings = currentAnnualInterest.subtract(annualInterest);

            double confidence = balanceTrend.confidence;
            if (balances.size() < props.getMinDataPoints()) {
                confidence *= 0.7;
            }
            predictions.add(new PredictedInterestCost(
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
}
