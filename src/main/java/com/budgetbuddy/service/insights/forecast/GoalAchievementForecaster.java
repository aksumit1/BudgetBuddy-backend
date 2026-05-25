package com.budgetbuddy.service.insights.forecast;

import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.service.ml.FinancialInsightsPredictionService.GoalData;
import com.budgetbuddy.service.ml.FinancialInsightsPredictionService.PredictedGoalAchievement;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
 * Forecasts goal achievement timeline + probability based on the
 * user's recent savings rate and how consistent that rate has been.
 *
 * <p>Probability formula (preserved verbatim from the original
 * implementation so output is unchanged):
 * {@code min(0.95, savingsRate × consistency × 10)}, halved when the
 * savings rate is below 10%. Goals with no positive monthly savings
 * get a flat 0.1 probability and a 999-month timeline.
 */
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Spring constructor injection — beans are shared by design")
@Service
public class GoalAchievementForecaster {

    private static final Logger LOGGER = LoggerFactory.getLogger(GoalAchievementForecaster.class);

    public List<PredictedGoalAchievement> forecast(
            final Map<String, GoalData> goals,
            final List<TransactionTable> historicalTransactions) {
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("Forecasting goal achievement for {} goals",
                    goals == null ? 0 : goals.size());
        }
        final List<PredictedGoalAchievement> predictions = new ArrayList<>();
        if (goals == null || goals.isEmpty() || historicalTransactions == null) {
            return predictions;
        }

        final BigDecimal monthlyIncome = ForecastMath.calculateMonthlyIncome(historicalTransactions);
        final BigDecimal monthlyExpenses =
                ForecastMath.calculateMonthlyExpenses(historicalTransactions);
        final BigDecimal monthlySavings = monthlyIncome.subtract(monthlyExpenses);
        final double savingsRate = monthlyIncome.compareTo(BigDecimal.ZERO) > 0
                ? monthlySavings.divide(monthlyIncome, 4, RoundingMode.HALF_UP).doubleValue()
                : 0.0;

        for (final Map.Entry<String, GoalData> entry : goals.entrySet()) {
            final String goalId = entry.getKey();
            final GoalData goal = entry.getValue();
            final BigDecimal remaining = goal.targetAmount.subtract(goal.currentAmount);
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            int predictedMonths;
            double achievementProbability;
            if (monthlySavings.compareTo(BigDecimal.ZERO) > 0) {
                predictedMonths = remaining.divide(monthlySavings, 0, RoundingMode.UP).intValue();
                final double consistency =
                        ForecastMath.calculateSavingsConsistency(historicalTransactions);
                achievementProbability = Math.min(0.95, savingsRate * consistency * 10);
                if (savingsRate < 0.1) {
                    achievementProbability *= 0.5;
                }
            } else {
                achievementProbability = 0.1;
                predictedMonths = 999;
            }
            final LocalDate predictedDate = LocalDate.now().plusMonths(predictedMonths);
            predictions.add(new PredictedGoalAchievement(
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
                .sorted(Comparator.comparing(
                        PredictedGoalAchievement::getAchievementProbability).reversed())
                .collect(Collectors.toList());
    }
}
