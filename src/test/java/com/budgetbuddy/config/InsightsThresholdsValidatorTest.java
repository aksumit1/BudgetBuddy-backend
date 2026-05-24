package com.budgetbuddy.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.junit.jupiter.api.Test;

/**
 * RISK-2: pins the validator's behavior on every category of misconfigured
 * threshold. Each test sets a known-bad value, runs the validator, and
 * asserts that the field has been clamped back to a sane default — that
 * way a typo in {@code application.yml} can't silently sabotage one
 * detector while the others stay safe.
 */
@SuppressFBWarnings(
        value = "THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION",
        justification = "JUnit idiom")
final class InsightsThresholdsValidatorTest {

    @Test
    void anomalyZScoreOutOfRangeIsClamped() {
        final InsightsThresholds t = new InsightsThresholds();
        t.getAnomaly().setZScoreThreshold(99.0); // absurd
        new InsightsThresholdsValidator(t).validate();
        assertEquals(2.5, t.getAnomaly().getZScoreThreshold(), 0.0001,
                "Out-of-range z-score must clamp to default");
    }

    @Test
    void anomalyHistoricalNotWiderThanAnalysisRaisesHistorical() {
        final InsightsThresholds t = new InsightsThresholds();
        t.getAnomaly().setAnalysisWindowDays(120);
        t.getAnomaly().setHistoricalWindowDays(60); // narrower than analysis
        new InsightsThresholdsValidator(t).validate();
        assertTrue(
                t.getAnomaly().getHistoricalWindowDays() > t.getAnomaly().getAnalysisWindowDays(),
                "Historical window must exceed analysis after validation");
    }

    @Test
    void anomalyMinTransactionsBelowFloorIsClamped() {
        final InsightsThresholds t = new InsightsThresholds();
        t.getAnomaly().setMinTransactionsForAnalysis(1); // too tiny → noisy z-scores
        new InsightsThresholdsValidator(t).validate();
        assertEquals(10, t.getAnomaly().getMinTransactionsForAnalysis(),
                "minTransactionsForAnalysis must clamp to default when below floor");
    }

    @Test
    void highInterestVeryHighBelowHighRaisesVeryHigh() {
        final InsightsThresholds t = new InsightsThresholds();
        t.getHighInterest().setHighRateThreshold(0.20);
        t.getHighInterest().setVeryHighRateThreshold(0.10); // inverted
        new InsightsThresholdsValidator(t).validate();
        assertTrue(
                t.getHighInterest().getVeryHighRateThreshold()
                        > t.getHighInterest().getHighRateThreshold(),
                "veryHigh must exceed high after validation");
    }

    @Test
    void financialGoalsIdealBelowMinIsRaised() {
        final InsightsThresholds t = new InsightsThresholds();
        t.getFinancialGoals().setMinSavingsRate(0.20);
        t.getFinancialGoals().setIdealSavingsRate(0.10); // below min
        new InsightsThresholdsValidator(t).validate();
        assertTrue(
                t.getFinancialGoals().getIdealSavingsRate()
                        >= t.getFinancialGoals().getMinSavingsRate(),
                "Ideal savings rate must reach minimum after validation");
    }

    @Test
    void creditCardHighBelowWarningIsRaised() {
        final InsightsThresholds t = new InsightsThresholds();
        t.getCreditCard().setUtilizationWarningThreshold(0.50);
        t.getCreditCard().setUtilizationHighThreshold(0.40); // inverted
        new InsightsThresholdsValidator(t).validate();
        assertTrue(
                t.getCreditCard().getUtilizationHighThreshold()
                        > t.getCreditCard().getUtilizationWarningThreshold(),
                "High utilization must exceed warning after validation");
    }

    @Test
    void allDefaultsPassUnchanged() {
        // Sanity: a freshly-constructed thresholds object (defaults) should
        // survive validation untouched. Otherwise the defaults themselves
        // are broken — which would be a real regression to catch.
        final InsightsThresholds t = new InsightsThresholds();
        final double zBefore = t.getAnomaly().getZScoreThreshold();
        final int analysisBefore = t.getAnomaly().getAnalysisWindowDays();
        final double idealBefore = t.getFinancialGoals().getIdealSavingsRate();
        new InsightsThresholdsValidator(t).validate();
        assertEquals(zBefore, t.getAnomaly().getZScoreThreshold(), 0.0001);
        assertEquals(analysisBefore, t.getAnomaly().getAnalysisWindowDays());
        assertEquals(idealBefore, t.getFinancialGoals().getIdealSavingsRate(), 0.0001);
    }
}
