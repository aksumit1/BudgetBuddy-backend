package com.budgetbuddy.service.budget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.service.budget.BudgetForecastService.Forecast;
import com.budgetbuddy.service.budget.BudgetForecastService.Risk;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

@SuppressFBWarnings(
        value = "THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION",
        justification = "JUnit idiom")
final class BudgetForecastServiceTest {

    @Test
    void highRiskWhenPaceProjectsAboveOnePointOneTimesLimit() {
        // Day 10 of 30, spent $500 → pace projects $1500 against $1000 limit → HIGH.
        final TransactionRepository repo = mock(TransactionRepository.class);
        when(repo.findByUserIdAndDateRange(anyString(), anyString(), anyString()))
                .thenReturn(List.of());
        final BudgetForecastService svc = new BudgetForecastService(repo);

        final Forecast f = svc.forecast(
                "u1",
                "dining",
                new BigDecimal("1000"),
                new BigDecimal("500"),
                LocalDate.of(2026, 5, 1),
                LocalDate.of(2026, 5, 30),
                LocalDate.of(2026, 5, 10));
        assertNotNull(f);
        assertEquals(Risk.HIGH, f.risk);
        assertTrue(f.reason.contains("overrun"));
    }

    @Test
    void lowRiskWhenPaceProjectsWellUnderLimit() {
        final TransactionRepository repo = mock(TransactionRepository.class);
        when(repo.findByUserIdAndDateRange(anyString(), anyString(), anyString()))
                .thenReturn(List.of());
        final BudgetForecastService svc = new BudgetForecastService(repo);

        final Forecast f = svc.forecast(
                "u1",
                "dining",
                new BigDecimal("1000"),
                new BigDecimal("100"),
                LocalDate.of(2026, 5, 1),
                LocalDate.of(2026, 5, 30),
                LocalDate.of(2026, 5, 10));
        assertEquals(Risk.LOW, f.risk);
    }

    @Test
    void historicalBaselineRaisesRiskOnSlowStart() {
        // Pace alone would say LOW ($50 by day 10 → projects $150 against $1000),
        // but past 3 months historically end at $1100. Predicted should jump to
        // $1100 → ratio 1.10 → HIGH.
        final TransactionRepository repo = mock(TransactionRepository.class);
        when(repo.findByUserIdAndDateRange(anyString(), anyString(), anyString()))
                .thenReturn(List.of(
                        tx("dining", "2026-02-15", "1100"),
                        tx("dining", "2026-03-15", "1100"),
                        tx("dining", "2026-04-15", "1100")));
        final BudgetForecastService svc = new BudgetForecastService(repo);

        final Forecast f = svc.forecast(
                "u1",
                "dining",
                new BigDecimal("1000"),
                new BigDecimal("50"),
                LocalDate.of(2026, 5, 1),
                LocalDate.of(2026, 5, 30),
                LocalDate.of(2026, 5, 10));
        assertNotNull(f);
        assertEquals(Risk.HIGH, f.risk,
                "Historical pattern of always-overrun must surface as HIGH even on slow start");
        assertNotNull(f.historicalMedianSpend);
    }

    @Test
    void incomeOrSavingsCategoriesGetNoForecast() {
        final TransactionRepository repo = mock(TransactionRepository.class);
        final BudgetForecastService svc = new BudgetForecastService(repo);
        assertNull(
                svc.forecast(
                        "u1",
                        "salary",
                        new BigDecimal("5000"),
                        BigDecimal.ZERO,
                        LocalDate.of(2026, 5, 1),
                        LocalDate.of(2026, 5, 30),
                        LocalDate.of(2026, 5, 10)),
                "Income/savings categories don't have an overrun concept");
    }

    @Test
    void zeroLimitReturnsNullForecast() {
        final TransactionRepository repo = mock(TransactionRepository.class);
        final BudgetForecastService svc = new BudgetForecastService(repo);
        assertNull(
                svc.forecast(
                        "u1",
                        "dining",
                        BigDecimal.ZERO,
                        new BigDecimal("100"),
                        LocalDate.of(2026, 5, 1),
                        LocalDate.of(2026, 5, 30),
                        LocalDate.of(2026, 5, 10)));
    }

    @Test
    void historicalQueryFailureDoesNotBreakForecast() {
        final TransactionRepository repo = mock(TransactionRepository.class);
        when(repo.findByUserIdAndDateRange(anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("DDB transient"));
        final BudgetForecastService svc = new BudgetForecastService(repo);

        // Pace forecast still works without history.
        final Forecast f = svc.forecast(
                "u1",
                "dining",
                new BigDecimal("1000"),
                new BigDecimal("100"),
                LocalDate.of(2026, 5, 1),
                LocalDate.of(2026, 5, 30),
                LocalDate.of(2026, 5, 10));
        assertNotNull(f);
        assertNull(f.historicalMedianSpend);
        assertFalse(f.risk == Risk.HIGH); // pace alone is safe
    }

    private static TransactionTable tx(
            final String category, final String date, final String amount) {
        final TransactionTable t = new TransactionTable();
        t.setCategoryPrimary(category);
        t.setTransactionDate(date);
        // Charges are negative in the canonical sign convention used elsewhere.
        t.setAmount(new BigDecimal(amount).negate());
        return t;
    }
}
