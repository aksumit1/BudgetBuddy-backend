package com.budgetbuddy.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Tunable thresholds for the insights subsystem. One class with nested
 * sections per service rather than five separate {@code @Configuration}
 * classes — easier for ops to see every dial in one place
 * ({@code financial.insights.*}) than to discover them service by
 * service.
 *
 * <p>Every default preserves the previous hardcoded value exactly, so
 * binding this class is a no-op until an operator explicitly overrides
 * something. Changes here ship without recompile.
 *
 * <p>What this class does NOT cover: prediction-service thresholds live
 * in {@link FinancialInsightsPredictionProperties} (separate prefix so
 * they can be tuned independently of the operational alerts), and the
 * already-externalized {@code app.subscription.price-change-alert-
 * threshold-pct} stays on {@code SubscriptionInsightsService} so the
 * existing override paths keep working.
 */
@Configuration
@ConfigurationProperties(prefix = "financial.insights")
public class InsightsThresholds {

    private Anomaly anomaly = new Anomaly();
    private HighInterest highInterest = new HighInterest();
    private ExpenseReduction expenseReduction = new ExpenseReduction();
    private FinancialGoals financialGoals = new FinancialGoals();
    private CreditCard creditCard = new CreditCard();

    public Anomaly getAnomaly() { return anomaly; }
    public void setAnomaly(final Anomaly a) { this.anomaly = a; }

    public HighInterest getHighInterest() { return highInterest; }
    public void setHighInterest(final HighInterest h) { this.highInterest = h; }

    public ExpenseReduction getExpenseReduction() { return expenseReduction; }
    public void setExpenseReduction(final ExpenseReduction e) { this.expenseReduction = e; }

    public FinancialGoals getFinancialGoals() { return financialGoals; }
    public void setFinancialGoals(final FinancialGoals f) { this.financialGoals = f; }

    public CreditCard getCreditCard() { return creditCard; }
    public void setCreditCard(final CreditCard c) { this.creditCard = c; }

    /** {@code financial.insights.anomaly.*} */
    public static class Anomaly {
        private double zScoreThreshold = 2.5;
        private double categorySpikeMultiplier = 3.0;
        private double amountThresholdMultiplier = 5.0;
        private int minTransactionsForAnalysis = 10;
        private int analysisWindowDays = 90;
        private int historicalWindowDays = 180;

        public double getZScoreThreshold() { return zScoreThreshold; }
        public void setZScoreThreshold(final double v) { this.zScoreThreshold = v; }

        public double getCategorySpikeMultiplier() { return categorySpikeMultiplier; }
        public void setCategorySpikeMultiplier(final double v) { this.categorySpikeMultiplier = v; }

        public double getAmountThresholdMultiplier() { return amountThresholdMultiplier; }
        public void setAmountThresholdMultiplier(final double v) {
            this.amountThresholdMultiplier = v;
        }

        public int getMinTransactionsForAnalysis() { return minTransactionsForAnalysis; }
        public void setMinTransactionsForAnalysis(final int v) {
            this.minTransactionsForAnalysis = v;
        }

        public int getAnalysisWindowDays() { return analysisWindowDays; }
        public void setAnalysisWindowDays(final int v) { this.analysisWindowDays = v; }

        public int getHistoricalWindowDays() { return historicalWindowDays; }
        public void setHistoricalWindowDays(final int v) { this.historicalWindowDays = v; }
    }

    /** {@code financial.insights.high-interest.*} */
    public static class HighInterest {
        private double highRateThreshold = 0.15;
        private double veryHighRateThreshold = 0.25;
        private double minMonthlyInterestPayment = 50.0;
        private int analysisWindowDays = 90;

        public double getHighRateThreshold() { return highRateThreshold; }
        public void setHighRateThreshold(final double v) { this.highRateThreshold = v; }

        public double getVeryHighRateThreshold() { return veryHighRateThreshold; }
        public void setVeryHighRateThreshold(final double v) { this.veryHighRateThreshold = v; }

        public double getMinMonthlyInterestPayment() { return minMonthlyInterestPayment; }
        public void setMinMonthlyInterestPayment(final double v) {
            this.minMonthlyInterestPayment = v;
        }

        public int getAnalysisWindowDays() { return analysisWindowDays; }
        public void setAnalysisWindowDays(final int v) { this.analysisWindowDays = v; }
    }

    /** {@code financial.insights.expense-reduction.*} */
    public static class ExpenseReduction {
        private int analysisWindowDays = 90;
        private double minRecommendationAmount = 10.0;

        public int getAnalysisWindowDays() { return analysisWindowDays; }
        public void setAnalysisWindowDays(final int v) { this.analysisWindowDays = v; }

        public double getMinRecommendationAmount() { return minRecommendationAmount; }
        public void setMinRecommendationAmount(final double v) {
            this.minRecommendationAmount = v;
        }
    }

    /** {@code financial.insights.financial-goals.*} */
    public static class FinancialGoals {
        private int emergencyFundMonths = 6;
        private double minSavingsRate = 0.15;
        private double idealSavingsRate = 0.20;
        private double wantsBudgetPercent = 0.20;
        private double debtToIncomeThreshold = 0.36;

        public int getEmergencyFundMonths() { return emergencyFundMonths; }
        public void setEmergencyFundMonths(final int v) { this.emergencyFundMonths = v; }

        public double getMinSavingsRate() { return minSavingsRate; }
        public void setMinSavingsRate(final double v) { this.minSavingsRate = v; }

        public double getIdealSavingsRate() { return idealSavingsRate; }
        public void setIdealSavingsRate(final double v) { this.idealSavingsRate = v; }

        public double getWantsBudgetPercent() { return wantsBudgetPercent; }
        public void setWantsBudgetPercent(final double v) { this.wantsBudgetPercent = v; }

        public double getDebtToIncomeThreshold() { return debtToIncomeThreshold; }
        public void setDebtToIncomeThreshold(final double v) { this.debtToIncomeThreshold = v; }
    }

    /** {@code financial.insights.credit-card.*} */
    public static class CreditCard {
        private double utilizationWarningThreshold = 0.30;
        private double utilizationHighThreshold = 0.70;
        private int annualFeeWarningWindowDays = 30;

        public double getUtilizationWarningThreshold() { return utilizationWarningThreshold; }
        public void setUtilizationWarningThreshold(final double v) {
            this.utilizationWarningThreshold = v;
        }

        public double getUtilizationHighThreshold() { return utilizationHighThreshold; }
        public void setUtilizationHighThreshold(final double v) {
            this.utilizationHighThreshold = v;
        }

        public int getAnnualFeeWarningWindowDays() { return annualFeeWarningWindowDays; }
        public void setAnnualFeeWarningWindowDays(final int v) {
            this.annualFeeWarningWindowDays = v;
        }
    }
}
