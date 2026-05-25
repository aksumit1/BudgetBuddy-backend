package com.budgetbuddy.service.ml;

import com.budgetbuddy.config.FinancialInsightsPredictionProperties;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.service.insights.forecast.AnomalyForecaster;
import com.budgetbuddy.service.insights.forecast.ExpenseReductionForecaster;
import com.budgetbuddy.service.insights.forecast.GoalAchievementForecaster;
import com.budgetbuddy.service.insights.forecast.InterestCostForecaster;
import com.budgetbuddy.service.insights.forecast.MissedPaymentForecaster;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Thin facade over the five extracted forecaster beans in
 * {@link com.budgetbuddy.service.insights.forecast}. Each {@code
 * predict*} method delegates to the corresponding forecaster — no
 * inline implementation lives here anymore.
 *
 * <p>This class previously housed the full prediction logic (~1,200
 * LOC of regression, percentile, and consistency math) but the
 * monolith split (#183, #185) moved everything into focused beans.
 * Kept as a facade for backwards compatibility — existing callers
 * (FinancialInsightsController, tests) continue to depend on it
 * unchanged.
 *
 * <p>Constructor takes the 5 forecasters as optional parameters with
 * defensive defaults: production Spring wiring supplies all five;
 * direct {@code new FinancialInsightsPredictionService()} construction
 * (used in unit tests via @InjectMocks) builds fresh forecaster
 * instances on demand so the service is functional in any context.
 *
 * <p>Inner result types ({@code PredictedAnomaly}, {@code
 * TrendAnalysis}, {@code GoalData}, etc.) are preserved verbatim
 * because they form the public API that controllers and forecasters
 * exchange. Moving them would have forced every caller to update
 * imports for no behavioural gain.
 */
@Service
public class FinancialInsightsPredictionService {

    private final FinancialInsightsPredictionProperties props;
    private final AnomalyForecaster anomalyForecaster;
    private final ExpenseReductionForecaster expenseReductionForecaster;
    private final GoalAchievementForecaster goalAchievementForecaster;
    private final MissedPaymentForecaster missedPaymentForecaster;
    private final InterestCostForecaster interestCostForecaster;

    @Autowired
    public FinancialInsightsPredictionService(
            final FinancialInsightsPredictionProperties props,
            final AnomalyForecaster anomalyForecaster,
            final ExpenseReductionForecaster expenseReductionForecaster,
            final GoalAchievementForecaster goalAchievementForecaster,
            final MissedPaymentForecaster missedPaymentForecaster,
            final InterestCostForecaster interestCostForecaster) {
        // Defensive defaults: every dependency is built on demand if
        // the caller passes null. Lets @InjectMocks construct a fully
        // functional service even when nothing is mocked.
        this.props = props != null ? props : new FinancialInsightsPredictionProperties();
        this.anomalyForecaster = anomalyForecaster != null
                ? anomalyForecaster : new AnomalyForecaster(this.props);
        this.expenseReductionForecaster = expenseReductionForecaster != null
                ? expenseReductionForecaster : new ExpenseReductionForecaster(this.props);
        this.goalAchievementForecaster = goalAchievementForecaster != null
                ? goalAchievementForecaster : new GoalAchievementForecaster();
        this.missedPaymentForecaster = missedPaymentForecaster != null
                ? missedPaymentForecaster : new MissedPaymentForecaster();
        this.interestCostForecaster = interestCostForecaster != null
                ? interestCostForecaster : new InterestCostForecaster(this.props);
    }

    /** Backwards-compat constructor for tests that wire only properties. */
    public FinancialInsightsPredictionService(final FinancialInsightsPredictionProperties props) {
        this(props, null, null, null, null, null);
    }

    /** Backwards-compat constructor for tests that don't wire anything. */
    public FinancialInsightsPredictionService() {
        this(null, null, null, null, null, null);
    }

    // -----------------------------------------------------------------
    // Public API — each method is a one-line delegate to its forecaster
    // -----------------------------------------------------------------

    public List<PredictedAnomaly> predictAnomalies(
            final List<TransactionTable> historicalTransactions, final int daysAhead) {
        return anomalyForecaster.forecast(historicalTransactions, daysAhead);
    }

    public List<PredictedExpenseReduction> predictExpenseReductions(
            final List<TransactionTable> historicalTransactions,
            final Map<String, BigDecimal> currentSubscriptions) {
        return expenseReductionForecaster.forecast(historicalTransactions, currentSubscriptions);
    }

    public List<PredictedGoalAchievement> predictGoalAchievements(
            final Map<String, GoalData> goals,
            final List<TransactionTable> historicalTransactions) {
        return goalAchievementForecaster.forecast(goals, historicalTransactions);
    }

    public List<PredictedMissedPayment> predictMissedPayments(
            final List<TransactionTable> historicalTransactions,
            final Map<String, PaymentPattern> paymentPatterns) {
        return missedPaymentForecaster.forecast(historicalTransactions, paymentPatterns);
    }

    public List<PredictedInterestCost> predictInterestCosts(
            final Map<String, AccountData> accounts,
            final List<TransactionTable> historicalTransactions) {
        return interestCostForecaster.forecast(accounts, historicalTransactions);
    }

    /**
     * Closed-form linear-regression forecast. Kept on this class
     * (instead of moving to ForecastMath) because the regression
     * intercept bug-fix that pinned the (n-1)/2 mean-x lives here as
     * the authoritative implementation — ForecastMath delegates to it.
     */
    public static BigDecimal linearRegressionPrediction(
            final int observationCount, final double meanY, final double slope) {
        final double meanX = (observationCount - 1) / 2.0;
        final double intercept = meanY - slope * meanX;
        final double predicted = slope * observationCount + intercept;
        return BigDecimal.valueOf(Math.max(0.0, predicted));
    }

    // -----------------------------------------------------------------
    // Result types — preserved verbatim as the public API exchange
    // shape. {@link com.budgetbuddy.service.insights.forecast}
    // forecasters construct these; controllers consume them.
    // -----------------------------------------------------------------

    public static class TrendAnalysis {
        public final double slope;
        public final double confidence;

        public TrendAnalysis(final double slope, final double confidence) {
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

        public String getCategory() { return category; }
        public BigDecimal getPredictedAmount() { return predictedAmount; }
        public BigDecimal getHistoricalAverage() { return historicalAverage; }
        public double getConfidence() { return confidence; }
        public String getReason() { return reason; }
        public LocalDate getPredictedDate() { return predictedDate; }
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

        public String getExpenseName() { return expenseName; }
        public BigDecimal getMonthlySavings() { return monthlySavings; }
        public BigDecimal getAnnualSavings() { return annualSavings; }
        public double getProbability() { return probability; }
        public String getReason() { return reason; }
        public LocalDate getPredictedDate() { return predictedDate; }
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

        public String getGoalId() { return goalId; }
        public String getGoalName() { return goalName; }
        public BigDecimal getCurrentAmount() { return currentAmount; }
        public BigDecimal getTargetAmount() { return targetAmount; }
        public LocalDate getPredictedDate() { return predictedDate; }
        public double getAchievementProbability() { return achievementProbability; }
        public BigDecimal getMonthlySavings() { return monthlySavings; }
        public BigDecimal getRemaining() { return remaining; }
        public double getSavingsRate() { return savingsRate; }
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

        public String getPaymentName() { return paymentName; }
        public LocalDate getDueDate() { return dueDate; }
        public BigDecimal getAmount() { return amount; }
        public double getRiskProbability() { return riskProbability; }
        public String getReason() { return reason; }
        public long getDaysUntilDue() { return daysUntilDue; }
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

        public String getAccountId() { return accountId; }
        public String getAccountName() { return accountName; }
        public double getInterestRate() { return interestRate; }
        public BigDecimal getCurrentBalance() { return currentBalance; }
        public BigDecimal getPredictedBalance() { return predictedBalance; }
        public BigDecimal getMonthlyInterest() { return monthlyInterest; }
        public BigDecimal getAnnualInterest() { return annualInterest; }
        public BigDecimal getPotentialSavings() { return potentialSavings; }
        public double getConfidence() { return confidence; }
        public String getTrend() { return trend; }
    }

    public static class GoalData {
        public final String name;
        public final BigDecimal currentAmount;
        public final BigDecimal targetAmount;

        public GoalData(
                final String name, final BigDecimal currentAmount, final BigDecimal targetAmount) {
            this.name = name;
            this.currentAmount = currentAmount;
            this.targetAmount = targetAmount;
        }
    }

    public static class PaymentPattern {
        public final BigDecimal amount;
        public final int expectedIntervalDays;

        public PaymentPattern(final BigDecimal amount, final int expectedIntervalDays) {
            this.amount = amount;
            this.expectedIntervalDays = expectedIntervalDays;
        }
    }

    public static class AccountData {
        public final String accountName;
        public final BigDecimal balance;
        public final double interestRate;

        public AccountData(
                final String accountName, final BigDecimal balance, final double interestRate) {
            this.accountName = accountName;
            this.balance = balance;
            this.interestRate = interestRate;
        }
    }
}
