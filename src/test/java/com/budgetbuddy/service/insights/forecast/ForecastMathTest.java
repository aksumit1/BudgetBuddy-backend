package com.budgetbuddy.service.insights.forecast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.budgetbuddy.service.ml.FinancialInsightsPredictionService.TrendAnalysis;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link ForecastMath} against well-known inputs. These are pure
 * math; if the helpers ever drift, every forecaster that uses them
 * drifts too — so we pin the helpers themselves rather than the
 * services downstream.
 */
class ForecastMathTest {

    @Test
    void analyzeAmountTrend_linearRise_producesPositiveSlope_high_R2() {
        // Perfect line: 100, 200, 300, 400, 500 → slope 100, R²=1.0.
        final List<BigDecimal> amounts = List.of(
                new BigDecimal("100"), new BigDecimal("200"),
                new BigDecimal("300"), new BigDecimal("400"),
                new BigDecimal("500"));
        final TrendAnalysis t = ForecastMath.analyzeAmountTrend(amounts);
        assertEquals(100.0, t.slope, 1e-9);
        assertEquals(1.0, t.confidence, 1e-9);
    }

    @Test
    void analyzeAmountTrend_flatSeries_returnsZeroSlope() {
        final List<BigDecimal> flat = List.of(
                new BigDecimal("50"), new BigDecimal("50"),
                new BigDecimal("50"), new BigDecimal("50"));
        final TrendAnalysis t = ForecastMath.analyzeAmountTrend(flat);
        assertEquals(0.0, t.slope, 1e-9);
    }

    @Test
    void analyzeAmountTrend_singleObservation_returnsZeroSlope() {
        // n < 2 → no trend extractable.
        final TrendAnalysis t = ForecastMath.analyzeAmountTrend(
                List.of(new BigDecimal("100")));
        assertEquals(0.0, t.slope);
        assertEquals(0.0, t.confidence);
    }

    @Test
    void median_evenSize_isAverageOfMiddleTwo() {
        // Even: median of {10, 20, 30, 40} = (20 + 30) / 2 = 25.
        final BigDecimal m = ForecastMath.median(List.of(
                new BigDecimal("10"), new BigDecimal("20"),
                new BigDecimal("30"), new BigDecimal("40")));
        assertEquals(0, m.compareTo(new BigDecimal("25.00")));
    }

    @Test
    void median_oddSize_isMiddleElement() {
        // Odd: median of {10, 20, 30} = 20.
        final BigDecimal m = ForecastMath.median(List.of(
                new BigDecimal("10"), new BigDecimal("20"), new BigDecimal("30")));
        assertEquals(0, m.compareTo(new BigDecimal("20")));
    }

    @Test
    void percentile_nearestRankDefinition() {
        // {10, 20, 30, 40, 50, 60, 70, 80, 90, 100} — 75th percentile.
        // ceil(0.75 × 10) − 1 = 7 → element at index 7 → 80.
        final BigDecimal p = ForecastMath.percentile(
                List.of(new BigDecimal("10"), new BigDecimal("20"),
                        new BigDecimal("30"), new BigDecimal("40"),
                        new BigDecimal("50"), new BigDecimal("60"),
                        new BigDecimal("70"), new BigDecimal("80"),
                        new BigDecimal("90"), new BigDecimal("100")),
                0.75);
        assertEquals(0, p.compareTo(new BigDecimal("80")));
    }

    @Test
    void average_isSumOverCount() {
        final BigDecimal avg = ForecastMath.average(List.of(
                new BigDecimal("10"), new BigDecimal("20"), new BigDecimal("30")));
        assertEquals(0, avg.compareTo(new BigDecimal("20.00")));
    }

    @Test
    void emptyInputs_returnZero_neverNpe() {
        // Defensive: forecasters call these on filtered transaction lists
        // that can legitimately be empty.
        assertTrue(ForecastMath.average(List.of()).compareTo(BigDecimal.ZERO) == 0);
        assertTrue(ForecastMath.median(List.of()).compareTo(BigDecimal.ZERO) == 0);
        assertTrue(ForecastMath.percentile(List.of(), 0.5).compareTo(BigDecimal.ZERO) == 0);
    }

    @Test
    void predictNext_delegatesToCanonicalRegressionFormula() {
        // 6 observations rising from 500 → 1000 at +100/step.
        // mean_y = 750, expected forecast at index 6 = 1100.
        final BigDecimal pred = ForecastMath.predictNext(6, 750.0, 100.0);
        assertEquals(0, pred.compareTo(new BigDecimal("1100.0")));
    }
}
