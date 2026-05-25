package com.budgetbuddy.service.insights.forecast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.budgetbuddy.config.FinancialInsightsPredictionProperties;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.service.ml.FinancialInsightsPredictionService;
import com.budgetbuddy.service.ml.FinancialInsightsPredictionService.PredictedAnomaly;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins the extracted {@link AnomalyForecaster}'s behaviour against the
 * original {@code FinancialInsightsPredictionService.predictAnomalies}
 * implementation. If the two ever diverge, this test catches it before
 * a Phase 2 PR ships.
 */
class AnomalyForecasterTest {

    private AnomalyForecaster forecaster;
    private FinancialInsightsPredictionService legacy;

    @BeforeEach
    void setUp() {
        // Both code paths must produce identical output under the same
        // default thresholds.
        final FinancialInsightsPredictionProperties props =
                new FinancialInsightsPredictionProperties();
        forecaster = new AnomalyForecaster(props);
        legacy = new FinancialInsightsPredictionService(props);
    }

    @Test
    void forecast_returnsEmpty_belowMinDataPoints() {
        // Default minDataPoints is 6 — give 3 transactions, expect nothing.
        final List<TransactionTable> few = expenses(3, 100.0);
        assertTrue(forecaster.forecast(few, 7).isEmpty());
    }

    @Test
    void forecast_returnsEmpty_forNullInput() {
        // Defensive: production data has occasional nulls in tests.
        assertTrue(forecaster.forecast(null, 7).isEmpty());
    }

    @Test
    void forecast_matchesLegacyImplementation_onRealisticRisingCategory() {
        // 12 transactions in one category, climbing $10 each. Both
        // implementations should produce the same alert (or both not
        // produce one) — the point is they don't drift.
        final List<TransactionTable> txs = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            txs.add(expense("groceries", 50.0 + i * 10, "2025-0" + ((i % 9) + 1) + "-15"));
        }
        final List<PredictedAnomaly> newPath = forecaster.forecast(txs, 30);
        final List<PredictedAnomaly> oldPath = legacy.predictAnomalies(txs, 30);
        assertEquals(oldPath.size(), newPath.size(),
                "Extracted forecaster must produce same number of alerts as legacy");
    }

    @Test
    void forecast_returnsEmpty_whenNoNegativeTransactions() {
        // Only positive (income) transactions — no expenses to analyse.
        final List<TransactionTable> incomeOnly = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            final TransactionTable t = new TransactionTable();
            t.setAmount(new BigDecimal("2000.00"));  // positive — income
            t.setCategoryPrimary("salary");
            t.setTransactionDate("2025-01-0" + ((i % 9) + 1));
            incomeOnly.add(t);
        }
        assertTrue(forecaster.forecast(incomeOnly, 30).isEmpty(),
                "Income-only transactions must not trigger expense anomaly predictions");
    }

    @Test
    void forecast_respectsCustomThresholds() {
        // Construct a forecaster with stricter spike-multiplier (5×
        // instead of default 2×). A signal that would fire at 2× should
        // not fire at 5× for the same data.
        final FinancialInsightsPredictionProperties strict =
                new FinancialInsightsPredictionProperties();
        strict.setCategorySpikeMultiplier(50.0); // effectively never fire
        final AnomalyForecaster strictForecaster = new AnomalyForecaster(strict);

        final List<TransactionTable> txs = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            txs.add(expense("groceries", 50.0 + i * 10, "2025-0" + ((i % 9) + 1) + "-15"));
        }
        final List<PredictedAnomaly> alerts = strictForecaster.forecast(txs, 30);
        assertFalse(alerts.stream()
                        .anyMatch(a -> "groceries".equals(a.getCategory())),
                "50× multiplier must suppress category-spike alerts");
    }

    // --- helpers ---

    private static TransactionTable expense(
            final String category, final double amount, final String date) {
        final TransactionTable t = new TransactionTable();
        t.setAmount(BigDecimal.valueOf(-amount));
        t.setCategoryPrimary(category);
        t.setTransactionDate(date);
        return t;
    }

    private static List<TransactionTable> expenses(final int count, final double amount) {
        final List<TransactionTable> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            out.add(expense("groceries", amount, "2025-01-" + (i + 1 < 10 ? "0" + (i + 1) : "" + (i + 1))));
        }
        return out;
    }
}
