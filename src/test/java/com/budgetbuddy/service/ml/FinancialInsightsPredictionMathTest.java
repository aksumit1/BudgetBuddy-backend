package com.budgetbuddy.service.ml;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Pins the linear-regression forecast math after fixing the off-by-0.5
 * intercept bug. With indices 0..n-1 the correct mean is (n-1)/2; the
 * prior code used n/2, which caused rising-trend forecasts to be
 * understated by slope×0.5 (and falling-trend forecasts overstated by
 * the same amount). For a category trending up at $20/period across 12
 * months, that was a $10 understatement on every spike forecast.
 */
class FinancialInsightsPredictionMathTest {

    @Test
    void prediction_matchesClosedFormLinearRegression() {
        // 6 points climbing by $100/period from a $500 baseline:
        // 500, 600, 700, 800, 900, 1000. Best-fit line is y = 100x + 500.
        // Forecast at x=6 should be exactly 1100.
        final double meanY = (500 + 600 + 700 + 800 + 900 + 1000) / 6.0; // 750
        final double slope = 100.0;
        final BigDecimal pred =
                FinancialInsightsPredictionService.linearRegressionPrediction(6, meanY, slope);
        assertEquals(0, pred.compareTo(new BigDecimal("1100.0")),
                "Forecast must match closed-form regression");
    }

    @Test
    void prediction_isExactlyHalfOfSlopeHigherThanBuggyVersion() {
        // The bug's signature: buggy prediction = slope*n + (meanY - slope*n/2)
        //                                       = meanY + slope * n/2
        // correct prediction = meanY + slope * (n+1)/2
        // Difference = slope * 0.5. Pin that.
        final int n = 12;
        final double meanY = 1000.0;
        final double slope = 50.0;

        final double correct = meanY + slope * (n + 1) / 2.0;          // 1325
        final double buggy = meanY + slope * n / 2.0;                  // 1300
        assertEquals(25.0, correct - buggy, 1e-9,
                "Pin the magnitude of the bug — half of slope");

        final BigDecimal pred =
                FinancialInsightsPredictionService.linearRegressionPrediction(n, meanY, slope);
        assertEquals(correct, pred.doubleValue(), 1e-9,
                "Fixed code must produce the correct (not the buggy) forecast");
    }

    @Test
    void prediction_clampsAtZero_doesNotReturnNegativeMoney() {
        // Steep downtrend that mathematically projects below zero —
        // dollars-spent can't be negative so we clamp.
        final BigDecimal pred =
                FinancialInsightsPredictionService.linearRegressionPrediction(6, 100.0, -500.0);
        assertEquals(0, pred.compareTo(BigDecimal.ZERO),
                "Negative predictions must be clamped to 0");
    }

    @Test
    void prediction_flatSeries_returnsTheMean() {
        // No trend (slope=0) → the forecast must equal the mean.
        final BigDecimal pred =
                FinancialInsightsPredictionService.linearRegressionPrediction(10, 250.0, 0.0);
        assertEquals(0, pred.compareTo(new BigDecimal("250.0")));
    }

    @Test
    void prediction_singleObservation_returnsThatObservation() {
        // n=1 → meanX = 0, predicted = slope*1 + meanY - 0 = meanY + slope.
        // Pinned behaviour: with no data points to fit a slope (slope must
        // be 0 in this case from analyzeAmountTrend), prediction is meanY.
        final BigDecimal pred =
                FinancialInsightsPredictionService.linearRegressionPrediction(1, 42.0, 0.0);
        assertEquals(0, pred.compareTo(new BigDecimal("42.0")));
    }
}
