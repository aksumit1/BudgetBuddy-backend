package com.budgetbuddy.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * RISK-2: validates {@link InsightsThresholds} on startup so a config
 * typo doesn't silently mis-tune one detector while the others stay on
 * their defaults.
 *
 * <p>The validator runs in {@code @PostConstruct} and:
 *
 * <ul>
 *   <li>Logs a {@code WARN} for any value outside its plausible range
 *       (e.g. analysisWindowDays of 0 or 2000).
 *   <li>Clamps the offending field back to a safe default in-place so
 *       the running service keeps working; ops is expected to fix the
 *       config and roll the deployment after seeing the warning.
 *   <li>Cross-checks that overlapping windows are consistent — e.g. an
 *       anomaly historical window shorter than its analysis window would
 *       break the z-score math.
 * </ul>
 *
 * <p>Style: every clamp logs the original + clamped value so the operator
 * has the receipts. Never throws — a misconfigured threshold is bad, but
 * crashing the application is worse.
 */
@Component
public class InsightsThresholdsValidator {

    private static final Logger LOGGER = LoggerFactory.getLogger(InsightsThresholdsValidator.class);

    private final InsightsThresholds thresholds;

    public InsightsThresholdsValidator(final InsightsThresholds thresholds) {
        this.thresholds = thresholds;
    }

    @PostConstruct
    public void validate() {
        validateAnomaly();
        validateHighInterest();
        validateExpenseReduction();
        validateFinancialGoals();
        validateCreditCard();
    }

    private void validateAnomaly() {
        final InsightsThresholds.Anomaly a = thresholds.getAnomaly();
        a.setZScoreThreshold(clampDouble("anomaly.zScoreThreshold", a.getZScoreThreshold(), 1.0, 6.0, 2.5));
        a.setCategorySpikeMultiplier(
                clampDouble("anomaly.categorySpikeMultiplier", a.getCategorySpikeMultiplier(), 1.0, 20.0, 3.0));
        a.setAmountThresholdMultiplier(
                clampDouble("anomaly.amountThresholdMultiplier", a.getAmountThresholdMultiplier(), 1.0, 50.0, 5.0));
        a.setMinTransactionsForAnalysis(
                clampInt("anomaly.minTransactionsForAnalysis", a.getMinTransactionsForAnalysis(), 3, 200, 10));
        a.setAnalysisWindowDays(
                clampInt("anomaly.analysisWindowDays", a.getAnalysisWindowDays(), 7, 365, 90));
        a.setHistoricalWindowDays(
                clampInt("anomaly.historicalWindowDays", a.getHistoricalWindowDays(), 14, 730, 180));
        // Cross-window check: historical MUST be wider than analysis or
        // the z-score baseline is computed from the same rows that are
        // being scored — false negatives across the board.
        if (a.getHistoricalWindowDays() <= a.getAnalysisWindowDays()) {
            final int prevHistorical = a.getHistoricalWindowDays();
            final int corrected = Math.max(a.getAnalysisWindowDays() * 2, 60);
            LOGGER.warn(
                    "InsightsThresholds: anomaly.historicalWindowDays ({}) must exceed analysisWindowDays ({}); "
                            + "raising historicalWindowDays to {} to preserve z-score validity.",
                    prevHistorical,
                    a.getAnalysisWindowDays(),
                    corrected);
            a.setHistoricalWindowDays(corrected);
        }
    }

    private void validateHighInterest() {
        final InsightsThresholds.HighInterest h = thresholds.getHighInterest();
        h.setHighRateThreshold(
                clampDouble("highInterest.highRateThreshold", h.getHighRateThreshold(), 0.01, 0.5, 0.15));
        h.setVeryHighRateThreshold(
                clampDouble("highInterest.veryHighRateThreshold", h.getVeryHighRateThreshold(), 0.05, 0.99, 0.25));
        if (h.getVeryHighRateThreshold() <= h.getHighRateThreshold()) {
            final double prev = h.getVeryHighRateThreshold();
            final double corrected = h.getHighRateThreshold() + 0.05;
            LOGGER.warn(
                    "InsightsThresholds: highInterest.veryHighRateThreshold ({}) must exceed highRateThreshold ({}); "
                            + "raising veryHighRateThreshold to {}.",
                    prev,
                    h.getHighRateThreshold(),
                    corrected);
            h.setVeryHighRateThreshold(corrected);
        }
        h.setMinMonthlyInterestPayment(
                clampDouble("highInterest.minMonthlyInterestPayment", h.getMinMonthlyInterestPayment(), 1.0, 1000.0, 50.0));
        h.setAnalysisWindowDays(
                clampInt("highInterest.analysisWindowDays", h.getAnalysisWindowDays(), 30, 365, 90));
    }

    private void validateExpenseReduction() {
        final InsightsThresholds.ExpenseReduction e = thresholds.getExpenseReduction();
        e.setAnalysisWindowDays(
                clampInt("expenseReduction.analysisWindowDays", e.getAnalysisWindowDays(), 30, 365, 90));
        e.setMinRecommendationAmount(
                clampDouble("expenseReduction.minRecommendationAmount", e.getMinRecommendationAmount(), 1.0, 1000.0, 25.0));
    }

    private void validateFinancialGoals() {
        final InsightsThresholds.FinancialGoals f = thresholds.getFinancialGoals();
        f.setEmergencyFundMonths(clampInt("financialGoals.emergencyFundMonths", f.getEmergencyFundMonths(), 1, 12, 6));
        f.setMinSavingsRate(clampDouble("financialGoals.minSavingsRate", f.getMinSavingsRate(), 0.0, 1.0, 0.10));
        f.setIdealSavingsRate(clampDouble("financialGoals.idealSavingsRate", f.getIdealSavingsRate(), 0.0, 1.0, 0.20));
        if (f.getIdealSavingsRate() < f.getMinSavingsRate()) {
            LOGGER.warn(
                    "InsightsThresholds: financialGoals.idealSavingsRate ({}) below minSavingsRate ({}); "
                            + "raising to match minSavingsRate + 0.05.",
                    f.getIdealSavingsRate(),
                    f.getMinSavingsRate());
            f.setIdealSavingsRate(f.getMinSavingsRate() + 0.05);
        }
        f.setWantsBudgetPercent(clampDouble("financialGoals.wantsBudgetPercent", f.getWantsBudgetPercent(), 0.0, 1.0, 0.30));
        f.setDebtToIncomeThreshold(clampDouble("financialGoals.debtToIncomeThreshold", f.getDebtToIncomeThreshold(), 0.0, 2.0, 0.36));
    }

    private void validateCreditCard() {
        final InsightsThresholds.CreditCard c = thresholds.getCreditCard();
        c.setUtilizationWarningThreshold(
                clampDouble("creditCard.utilizationWarningThreshold", c.getUtilizationWarningThreshold(), 0.05, 1.0, 0.30));
        c.setUtilizationHighThreshold(
                clampDouble("creditCard.utilizationHighThreshold", c.getUtilizationHighThreshold(), 0.10, 1.0, 0.70));
        if (c.getUtilizationHighThreshold() <= c.getUtilizationWarningThreshold()) {
            LOGGER.warn(
                    "InsightsThresholds: creditCard.utilizationHighThreshold ({}) must exceed warning threshold ({}); "
                            + "raising to warning + 0.10.",
                    c.getUtilizationHighThreshold(),
                    c.getUtilizationWarningThreshold());
            c.setUtilizationHighThreshold(c.getUtilizationWarningThreshold() + 0.10);
        }
        c.setAnnualFeeWarningWindowDays(
                clampInt("creditCard.annualFeeWarningWindowDays", c.getAnnualFeeWarningWindowDays(), 7, 90, 30));
    }

    private double clampDouble(
            final String name,
            final double v,
            final double min,
            final double max,
            final double fallback) {
        if (Double.isNaN(v) || Double.isInfinite(v) || v < min || v > max) {
            LOGGER.warn(
                    "InsightsThresholds: {}={} outside [{},{}] — clamping to default {}",
                    name, v, min, max, fallback);
            return fallback;
        }
        return v;
    }

    private int clampInt(
            final String name,
            final int v,
            final int min,
            final int max,
            final int fallback) {
        if (v < min || v > max) {
            LOGGER.warn(
                    "InsightsThresholds: {}={} outside [{},{}] — clamping to default {}",
                    name, v, min, max, fallback);
            return fallback;
        }
        return v;
    }
}
