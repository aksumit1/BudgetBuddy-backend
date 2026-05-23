package com.budgetbuddy.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.budgetbuddy.model.dynamodb.BudgetTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.BudgetRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.service.BudgetAllocationStatusService.AllocationStatus;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** B-ZBB-1: remaining-to-allocate math. */
@SuppressFBWarnings(
        value = "THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION",
        justification = "JUnit idiom")
final class BudgetAllocationStatusServiceTest {

    private static final LocalDate NOW = LocalDate.of(2026, 5, 15);
    private static final String USER = "u1";

    @Test
    void underAllocatedWhenAllocationBelowIncome() {
        // Income $4000/month median, allocated $2500 → remaining $1500.
        final BudgetRepository budgetRepo = mock(BudgetRepository.class);
        final TransactionRepository txRepo = mock(TransactionRepository.class);
        when(budgetRepo.findByUserId(USER))
                .thenReturn(List.of(budget("Groceries", "500", "monthly"), budget("Rent", "2000", "monthly")));
        when(txRepo.findByUserIdAndDateRange(eq(USER), anyString(), anyString()))
                .thenReturn(List.of(
                        income("salary", "2026-02-15", "4000"),
                        income("salary", "2026-03-15", "4000"),
                        income("salary", "2026-04-15", "4000")));

        final AllocationStatus s =
                new BudgetAllocationStatusService(budgetRepo, txRepo).compute(user(), NOW);
        assertEquals(new BigDecimal("4000.00"), s.estimatedMonthlyIncome);
        assertEquals(new BigDecimal("2500.00"), s.totalAllocatedMonthly);
        assertEquals(new BigDecimal("1500.00"), s.remainingToAllocate);
        assertEquals("UNDER_ALLOCATED", s.status);
        assertEquals(2, s.activeBudgetCount);
    }

    @Test
    void overAllocatedFlagsNegativeRemaining() {
        final BudgetRepository budgetRepo = mock(BudgetRepository.class);
        final TransactionRepository txRepo = mock(TransactionRepository.class);
        when(budgetRepo.findByUserId(USER))
                .thenReturn(List.of(budget("Rent", "3500", "monthly")));
        when(txRepo.findByUserIdAndDateRange(eq(USER), anyString(), anyString()))
                .thenReturn(List.of(
                        income("salary", "2026-03-15", "2000"),
                        income("salary", "2026-04-15", "2000")));

        final AllocationStatus s =
                new BudgetAllocationStatusService(budgetRepo, txRepo).compute(user(), NOW);
        assertEquals("OVER_ALLOCATED", s.status);
        assertTrue(s.remainingToAllocate.signum() < 0);
    }

    @Test
    void weeklyBudgetIsScaledToMonthly() {
        // A $50/week budget should count as ~$217/month (50 × 4.345…).
        final BudgetRepository budgetRepo = mock(BudgetRepository.class);
        final TransactionRepository txRepo = mock(TransactionRepository.class);
        when(budgetRepo.findByUserId(USER))
                .thenReturn(List.of(budget("Coffee", "50", "weekly")));
        when(txRepo.findByUserIdAndDateRange(eq(USER), anyString(), anyString()))
                .thenReturn(List.of(income("salary", "2026-04-15", "3000")));

        final AllocationStatus s =
                new BudgetAllocationStatusService(budgetRepo, txRepo).compute(user(), NOW);
        // 50 × (30.4375 / 7) ≈ 217.41
        assertTrue(s.totalAllocatedMonthly.compareTo(new BigDecimal("200")) > 0,
                "Weekly $50 should normalise above $200/mo, got " + s.totalAllocatedMonthly);
        assertTrue(s.totalAllocatedMonthly.compareTo(new BigDecimal("220")) < 0,
                "Weekly $50 should normalise below $220/mo, got " + s.totalAllocatedMonthly);
    }

    @Test
    void incomeCategoryBudgetsDoNotCountAsAllocation() {
        // An "Income" category budget is a target, not a spending allocation.
        final BudgetRepository budgetRepo = mock(BudgetRepository.class);
        final TransactionRepository txRepo = mock(TransactionRepository.class);
        when(budgetRepo.findByUserId(USER))
                .thenReturn(List.of(
                        budget("salary", "5000", "monthly"),
                        budget("Groceries", "400", "monthly")));
        when(txRepo.findByUserIdAndDateRange(eq(USER), anyString(), anyString()))
                .thenReturn(List.of(income("salary", "2026-04-15", "5000")));

        final AllocationStatus s =
                new BudgetAllocationStatusService(budgetRepo, txRepo).compute(user(), NOW);
        assertEquals(new BigDecimal("400.00"), s.totalAllocatedMonthly,
                "Income-category budgets must be excluded from total allocation");
    }

    @Test
    void zeroIncomeProducesBalancedStatusWhenNoBudgetsTooAreAllocated() {
        final BudgetRepository budgetRepo = mock(BudgetRepository.class);
        final TransactionRepository txRepo = mock(TransactionRepository.class);
        when(budgetRepo.findByUserId(USER)).thenReturn(List.of());
        when(txRepo.findByUserIdAndDateRange(eq(USER), anyString(), anyString())).thenReturn(List.of());

        final AllocationStatus s =
                new BudgetAllocationStatusService(budgetRepo, txRepo).compute(user(), NOW);
        assertEquals("BALANCED", s.status);
        assertEquals(0, s.activeBudgetCount);
    }

    private static BudgetTable budget(final String cat, final String limit, final String period) {
        final BudgetTable b = new BudgetTable();
        b.setCategory(cat);
        b.setMonthlyLimit(new BigDecimal(limit));
        b.setPeriod(period);
        return b;
    }

    private static TransactionTable income(
            final String cat, final String date, final String amount) {
        final TransactionTable t = new TransactionTable();
        t.setCategoryPrimary(cat);
        t.setTransactionDate(date);
        t.setAmount(new BigDecimal(amount));
        return t;
    }

    private static UserTable user() {
        final UserTable u = new UserTable();
        u.setUserId(USER);
        return u;
    }
}
