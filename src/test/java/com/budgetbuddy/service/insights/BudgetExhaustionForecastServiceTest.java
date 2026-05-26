package com.budgetbuddy.service.insights;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.budgetbuddy.model.dynamodb.BudgetTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.repository.dynamodb.BudgetRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

@SuppressFBWarnings(
        value = "THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION",
        justification = "JUnit idiom")
final class BudgetExhaustionForecastServiceTest {

    private static final String USER = "u1";

    @Test
    void surfacesBudgetsExhaustingBeforeCycleEnd() {
        // Day 10 of a 30-day cycle. $500 spent against $1000 limit.
        // Pace $50/day → 10 more days to exhaustion, but 20 days remain → trip alert.
        final BudgetRepository budgets = mock(BudgetRepository.class);
        final TransactionRepository tx = mock(TransactionRepository.class);
        when(budgets.findByUserId(USER))
                .thenReturn(List.of(budget("b1", "dining", "1000", "monthly")));
        when(tx.findByUserIdAndDateRange(eq(USER), anyString(), anyString()))
                .thenReturn(List.of(expense("dining", "500")));

        final List<BudgetExhaustionForecastService.ExhaustionAlert> alerts =
                new BudgetExhaustionForecastService(budgets, tx)
                        .forecast(USER, LocalDate.of(2026, 5, 10));

        assertEquals(1, alerts.size());
        final BudgetExhaustionForecastService.ExhaustionAlert a = alerts.getFirst();
        assertEquals("dining", a.category);
        assertTrue(a.daysUntilExhausted < a.daysRemainingInCycle);
    }

    @Test
    void exhaustedBudgetGetsExhaustedSeverity() {
        final BudgetRepository budgets = mock(BudgetRepository.class);
        final TransactionRepository tx = mock(TransactionRepository.class);
        when(budgets.findByUserId(USER))
                .thenReturn(List.of(budget("b1", "dining", "100", "monthly")));
        when(tx.findByUserIdAndDateRange(eq(USER), anyString(), anyString()))
                .thenReturn(List.of(expense("dining", "200")));
        final BudgetExhaustionForecastService.ExhaustionAlert a =
                new BudgetExhaustionForecastService(budgets, tx)
                        .forecast(USER, LocalDate.of(2026, 5, 15))
                        .getFirst();
        assertEquals("EXHAUSTED", a.severity);
        assertTrue(a.remaining.signum() <= 0);
    }

    @Test
    void incomeCategoriesAreNeverFlagged() {
        final BudgetRepository budgets = mock(BudgetRepository.class);
        final TransactionRepository tx = mock(TransactionRepository.class);
        when(budgets.findByUserId(USER))
                .thenReturn(List.of(budget("b1", "salary", "5000", "monthly")));
        when(tx.findByUserIdAndDateRange(eq(USER), anyString(), anyString()))
                .thenReturn(List.of(expense("salary", "10000"))); // wouldn't make sense, but defensive
        assertTrue(
                new BudgetExhaustionForecastService(budgets, tx)
                        .forecast(USER, LocalDate.of(2026, 5, 15))
                        .isEmpty(),
                "Income categories must not appear in exhaustion forecasts");
    }

    @Test
    void onlyTopFiveByUrgencyAreReturned() {
        final BudgetRepository budgets = mock(BudgetRepository.class);
        final TransactionRepository tx = mock(TransactionRepository.class);
        final java.util.List<BudgetTable> manyBudgets = new java.util.ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            manyBudgets.add(budget("b" + i, "cat" + i, "100", "monthly"));
        }
        when(budgets.findByUserId(USER)).thenReturn(manyBudgets);
        // Each category has spent its entire limit → all 8 are EXHAUSTED.
        final java.util.List<TransactionTable> rows = new java.util.ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            rows.add(expense("cat" + i, "150"));
        }
        when(tx.findByUserIdAndDateRange(eq(USER), anyString(), anyString())).thenReturn(rows);
        final List<BudgetExhaustionForecastService.ExhaustionAlert> alerts =
                new BudgetExhaustionForecastService(budgets, tx)
                        .forecast(USER, LocalDate.of(2026, 5, 15));
        assertEquals(5, alerts.size(), "Service must cap at top 5 by urgency");
    }

    @Test
    void slowBudgetThatExhaustsAfterCycleEndIsNotSurfaced() {
        // Day 10 of a 30-day cycle. $50 spent against $1000 limit.
        // Pace $5/day → 190 more days. Cycle has 20 days left → safe.
        final BudgetRepository budgets = mock(BudgetRepository.class);
        final TransactionRepository tx = mock(TransactionRepository.class);
        when(budgets.findByUserId(USER))
                .thenReturn(List.of(budget("b1", "dining", "1000", "monthly")));
        when(tx.findByUserIdAndDateRange(eq(USER), anyString(), anyString()))
                .thenReturn(List.of(expense("dining", "50")));
        assertFalse(
                new BudgetExhaustionForecastService(budgets, tx)
                        .forecast(USER, LocalDate.of(2026, 5, 10))
                        .stream()
                        .anyMatch(a -> "dining".equals(a.category)),
                "Slow-pace budgets must NOT trip the forecast");
    }

    private static BudgetTable budget(
            final String id, final String cat, final String limit, final String period) {
        final BudgetTable b = new BudgetTable();
        b.setBudgetId(id);
        b.setUserId(USER);
        b.setCategory(cat);
        b.setMonthlyLimit(new BigDecimal(limit));
        b.setPeriod(period);
        return b;
    }

    private static TransactionTable expense(final String cat, final String amount) {
        final TransactionTable t = new TransactionTable();
        t.setCategoryPrimary(cat);
        t.setAmount(new BigDecimal(amount).negate());
        t.setTransactionDate("2026-05-08");
        return t;
    }
}
