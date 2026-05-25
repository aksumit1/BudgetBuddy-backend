package com.budgetbuddy.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Pin every default in {@link InsightsThresholds} to the exact value the
 * service had before externalization. Without this test, an accidental
 * default change would shift alert volume across the entire insights
 * surface in a way that no behavioural test would catch.
 */
class InsightsThresholdsTest {

    @Test
    void anomalyDefaults_matchOriginalConstants() {
        final InsightsThresholds.Anomaly a = new InsightsThresholds().getAnomaly();
        assertEquals(2.5, a.getZScoreThreshold());
        assertEquals(3.0, a.getCategorySpikeMultiplier());
        assertEquals(5.0, a.getAmountThresholdMultiplier());
        assertEquals(10, a.getMinTransactionsForAnalysis());
        assertEquals(90, a.getAnalysisWindowDays());
        assertEquals(180, a.getHistoricalWindowDays());
    }

    @Test
    void highInterestDefaults_matchOriginalConstants() {
        final InsightsThresholds.HighInterest h = new InsightsThresholds().getHighInterest();
        assertEquals(0.15, h.getHighRateThreshold());
        assertEquals(0.25, h.getVeryHighRateThreshold());
        assertEquals(50.0, h.getMinMonthlyInterestPayment());
        assertEquals(90, h.getAnalysisWindowDays());
    }

    @Test
    void expenseReductionDefaults_matchOriginalConstants() {
        final InsightsThresholds.ExpenseReduction e =
                new InsightsThresholds().getExpenseReduction();
        assertEquals(90, e.getAnalysisWindowDays());
        assertEquals(10.0, e.getMinRecommendationAmount());
    }

    @Test
    void financialGoalsDefaults_matchOriginalConstants() {
        final InsightsThresholds.FinancialGoals f =
                new InsightsThresholds().getFinancialGoals();
        assertEquals(6, f.getEmergencyFundMonths());
        assertEquals(0.15, f.getMinSavingsRate());
        assertEquals(0.20, f.getIdealSavingsRate());
        assertEquals(0.20, f.getWantsBudgetPercent());
        assertEquals(0.36, f.getDebtToIncomeThreshold());
    }

    @Test
    void creditCardDefaults_matchOriginalConstants() {
        final InsightsThresholds.CreditCard c = new InsightsThresholds().getCreditCard();
        assertEquals(0.30, c.getUtilizationWarningThreshold());
        assertEquals(0.70, c.getUtilizationHighThreshold());
        assertEquals(30, c.getAnnualFeeWarningWindowDays());
    }

    @Test
    void setters_overrideDefaults() {
        // Sanity check Spring's binding contract — properties must be
        // settable via JavaBean conventions for @ConfigurationProperties
        // to populate them from application.yml.
        final InsightsThresholds t = new InsightsThresholds();
        t.getAnomaly().setZScoreThreshold(3.5);
        t.getCreditCard().setUtilizationWarningThreshold(0.40);
        assertEquals(3.5, t.getAnomaly().getZScoreThreshold());
        assertEquals(0.40, t.getCreditCard().getUtilizationWarningThreshold());
    }
}
