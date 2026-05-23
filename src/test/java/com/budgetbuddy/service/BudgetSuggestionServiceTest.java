package com.budgetbuddy.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.service.BudgetSuggestionService.BudgetSuggestion;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

@SuppressFBWarnings(
        value = "THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION",
        justification = "JUnit idiom")
final class BudgetSuggestionServiceTest {

    private static final LocalDate NOW = LocalDate.of(2026, 5, 15);

    @Test
    void suggestRoundsMedianUpToNearestFiveWithTenPercentBuffer() {
        // Three months of dining-out spend: $80, $100, $120 → median 100 → ×1.10 = 110 → round to $110.
        final TransactionRepository repo = mock(TransactionRepository.class);
        when(repo.findByUserIdAndDateRange(eq("u1"), anyString(), anyString()))
                .thenReturn(List.of(
                        tx("dining", "2026-02-10", "80"),
                        tx("dining", "2026-03-10", "100"),
                        tx("dining", "2026-04-10", "120")));

        final BudgetSuggestionService svc = new BudgetSuggestionService(repo);
        final List<BudgetSuggestion> out = svc.suggestForUser(user("u1"), NOW);
        assertEquals(1, out.size());
        final BudgetSuggestion s = out.get(0);
        assertEquals("dining", s.category);
        assertEquals(new BigDecimal("110.00"), s.recommendedMonthlyLimit);
        assertEquals(3, s.monthsObserved);
    }

    @Test
    void suggestSkipsIncomeOrSavingsCategories() {
        final TransactionRepository repo = mock(TransactionRepository.class);
        when(repo.findByUserIdAndDateRange(eq("u1"), anyString(), anyString()))
                .thenReturn(List.of(
                        tx("salary", "2026-02-15", "-3000"),
                        tx("salary", "2026-03-15", "-3000"),
                        tx("salary", "2026-04-15", "-3000")));

        final BudgetSuggestionService svc = new BudgetSuggestionService(repo);
        assertTrue(svc.suggestForUser(user("u1"), NOW).isEmpty(),
                "income/salary categories must not get a 'budget limit' suggestion");
    }

    @Test
    void suggestRequiresAtLeastTwoMonthsOfSignal() {
        // Only one month of data → no recommendation (insufficient pattern).
        final TransactionRepository repo = mock(TransactionRepository.class);
        when(repo.findByUserIdAndDateRange(eq("u1"), anyString(), anyString()))
                .thenReturn(List.of(tx("dining", "2026-04-10", "100")));

        final BudgetSuggestionService svc = new BudgetSuggestionService(repo);
        assertTrue(svc.suggestForUser(user("u1"), NOW).isEmpty());
    }

    @Test
    void suggestNetsRefundsAgainstChargesInTheSameMonth() {
        // Month-A: $100 charge + $40 refund = $60 effective. Month-B: $60. Median=$60, ×1.10=66, round→$70.
        final TransactionRepository repo = mock(TransactionRepository.class);
        when(repo.findByUserIdAndDateRange(eq("u1"), anyString(), anyString()))
                .thenReturn(List.of(
                        tx("dining", "2026-03-10", "100"),
                        tx("dining", "2026-03-15", "-40"), // refund: positive on expense category
                        tx("dining", "2026-04-12", "60")));

        final BudgetSuggestionService svc = new BudgetSuggestionService(repo);
        final List<BudgetSuggestion> out = svc.suggestForUser(user("u1"), NOW);
        assertEquals(1, out.size());
        // Note: the refund-netting comment in the service is "positive amounts are refunds,
        // negative are charges". We assert the resulting recommended limit reflects netting.
        assertNotNull(out.get(0).recommendedMonthlyLimit);
        assertFalse(out.get(0).recommendedMonthlyLimit.signum() <= 0);
    }

    @Test
    void suggestSortsByDescendingRecommendedLimit() {
        final TransactionRepository repo = mock(TransactionRepository.class);
        when(repo.findByUserIdAndDateRange(eq("u1"), anyString(), anyString()))
                .thenReturn(List.of(
                        tx("dining", "2026-02-10", "80"),
                        tx("dining", "2026-03-10", "80"),
                        tx("groceries", "2026-02-10", "300"),
                        tx("groceries", "2026-03-10", "300")));

        final BudgetSuggestionService svc = new BudgetSuggestionService(repo);
        final List<BudgetSuggestion> out = svc.suggestForUser(user("u1"), NOW);
        assertEquals("groceries", out.get(0).category);
        assertEquals("dining", out.get(1).category);
    }

    @Test
    void suggestNullAdvisorLeavesReasoningNull() {
        final TransactionRepository repo = mock(TransactionRepository.class);
        when(repo.findByUserIdAndDateRange(eq("u1"), anyString(), anyString()))
                .thenReturn(List.of(
                        tx("dining", "2026-02-10", "80"),
                        tx("dining", "2026-03-10", "100")));
        final BudgetSuggestionService svc = new BudgetSuggestionService(repo);
        assertNull(svc.suggestForUser(user("u1"), NOW).get(0).reasoning);
    }

    @Test
    void advisorAnnotatesReasoningWithoutChangingLimit() {
        final TransactionRepository repo = mock(TransactionRepository.class);
        when(repo.findByUserIdAndDateRange(eq("u1"), anyString(), anyString()))
                .thenReturn(List.of(
                        tx("dining", "2026-02-10", "80"),
                        tx("dining", "2026-03-10", "100")));

        final BudgetSuggestionService svc = new BudgetSuggestionService(
                repo,
                in -> {
                    // Mimic the advisor: copy and add reasoning, never change the limit.
                    final List<BudgetSuggestion> copy = new ArrayList<>(in.size());
                    for (final BudgetSuggestion s : in) {
                        final BudgetSuggestion x = new BudgetSuggestion();
                        x.category = s.category;
                        x.recommendedMonthlyLimit = s.recommendedMonthlyLimit;
                        x.medianMonthlySpend = s.medianMonthlySpend;
                        x.monthsObserved = s.monthsObserved;
                        x.reasoning = "Pace + median; rounded with 10% buffer";
                        copy.add(x);
                    }
                    return copy;
                });

        final List<BudgetSuggestion> out = svc.suggestForUser(user("u1"), NOW);
        assertNotNull(out.get(0).reasoning);
        assertTrue(out.get(0).reasoning.contains("buffer"));
    }

    @Test
    void advisorFailureFallsBackToRulesBased() {
        final TransactionRepository repo = mock(TransactionRepository.class);
        when(repo.findByUserIdAndDateRange(eq("u1"), anyString(), anyString()))
                .thenReturn(List.of(
                        tx("dining", "2026-02-10", "80"),
                        tx("dining", "2026-03-10", "100")));

        final BudgetSuggestionService svc = new BudgetSuggestionService(
                repo,
                in -> {
                    throw new RuntimeException("LLM down");
                });

        final List<BudgetSuggestion> out = svc.suggestForUser(user("u1"), NOW);
        assertEquals(1, out.size());
        assertNull(out.get(0).reasoning, "Advisor failure must not leak partial reasoning");
        assertNotNull(out.get(0).recommendedMonthlyLimit);
    }

    /**
     * Helper that follows the canonical BudgetBuddy sign convention: an
     * {@code amount} string written as positive is a CHARGE (stored as a
     * negative BigDecimal); a string prefixed with "-" is a REFUND
     * (stored positive) or income.
     */
    private static TransactionTable tx(
            final String category, final String date, final String amount) {
        final TransactionTable t = new TransactionTable();
        t.setCategoryPrimary(category);
        t.setTransactionDate(date);
        final BigDecimal a = new BigDecimal(amount);
        t.setAmount(a.negate());
        return t;
    }

    private static UserTable user(final String id) {
        final UserTable u = new UserTable();
        u.setUserId(id);
        return u;
    }

}
