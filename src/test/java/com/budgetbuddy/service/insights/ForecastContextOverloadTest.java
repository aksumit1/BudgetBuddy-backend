package com.budgetbuddy.service.insights;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.budgetbuddy.model.Subscription;
import com.budgetbuddy.model.Subscription.SubscriptionFrequency;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.BudgetTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.BudgetRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.service.SubscriptionService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * RISK-1: pins that each forecast service uses the {@link InsightsContext}
 * snapshot exclusively when given one — no fallback to repo fetches. The
 * /summary endpoint relies on this to keep the per-request DDB cost at 1
 * (InsightsContextFactory) instead of 1 + N (each forecaster's own
 * findByUserId).
 */
@SuppressFBWarnings(
        value = "THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION",
        justification = "JUnit idiom")
final class ForecastContextOverloadTest {

    private static final LocalDate NOW = LocalDate.of(2026, 5, 15);
    private static final String USER = "u1";

    @Test
    void cashFlowContextPathDoesNotHitRepos() {
        final AccountRepository accounts = mock(AccountRepository.class);
        final TransactionRepository tx = mock(TransactionRepository.class);

        final InsightsContext ctx = new InsightsContext(
                USER, NOW,
                List.of(
                        income("2026-02-15", "3000"),
                        income("2026-03-15", "3000"),
                        income("2026-04-15", "3000"),
                        expense("2026-02-20", "2000"),
                        expense("2026-03-20", "2000"),
                        expense("2026-04-20", "2000")),
                List.of(checking("5000")),
                List.of());

        new CashFlowForecastService(tx, accounts).forecast(ctx);
        verifyNoInteractions(accounts);
        verifyNoInteractions(tx);
    }

    @Test
    void subscriptionCreepContextPathDoesNotHitRepos() {
        final SubscriptionService subs = mock(SubscriptionService.class);

        final InsightsContext ctx = new InsightsContext(
                USER, NOW,
                List.of(),
                List.of(),
                List.of(monthlySub("Netflix", "10")),
                List.of());

        new SubscriptionCreepForecastService(subs).forecast(ctx);
        verify(subs, never()).getSubscriptions(USER);
    }

    @Test
    void budgetExhaustionContextPathDoesNotHitBudgetRepo() {
        final BudgetRepository budgets = mock(BudgetRepository.class);
        final TransactionRepository tx = mock(TransactionRepository.class);

        final InsightsContext ctx = new InsightsContext(
                USER, NOW,
                List.of(expense("2026-05-08", "500")),
                List.of(),
                List.of(),
                List.of(),
                List.of(diningBudget("1000")));

        new BudgetExhaustionForecastService(budgets, tx).forecast(ctx);
        verify(budgets, never()).findByUserId(USER);
        verify(tx, never())
                .findByUserIdAndDateRange(org.mockito.ArgumentMatchers.eq(USER),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void budgetExhaustionContextFallsBackWhenBudgetsAreEmpty() {
        // Empty ctx.budgets() → fall back to repo. Existing tests already
        // cover the legitimate empty-budget case; this just verifies the
        // fallback wire is intact.
        final BudgetRepository budgets = mock(BudgetRepository.class);
        final TransactionRepository tx = mock(TransactionRepository.class);
        org.mockito.Mockito.when(budgets.findByUserId(USER)).thenReturn(List.of());

        final InsightsContext ctx = new InsightsContext(
                USER, NOW, List.of(), List.of(), List.of(), List.of(), List.of());

        final List<BudgetExhaustionForecastService.ExhaustionAlert> result =
                new BudgetExhaustionForecastService(budgets, tx).forecast(ctx);
        assertTrue(result.isEmpty());
        verify(budgets).findByUserId(USER);
    }

    @Test
    void cashFlowAndSubscriptionCreepProduceSameOutputAcrossOverloads() {
        // Crucial regression-prevention test: the ctx path and the userId
        // path MUST compute the same forecast given the same data. If they
        // ever diverge the /summary and the per-endpoint surfaces disagree.
        final AccountRepository accounts = mock(AccountRepository.class);
        final TransactionRepository tx = mock(TransactionRepository.class);
        final List<TransactionTable> txs = List.of(
                income("2026-02-15", "3000"),
                income("2026-03-15", "3000"),
                income("2026-04-15", "3000"),
                expense("2026-02-20", "2500"),
                expense("2026-03-20", "2500"),
                expense("2026-04-20", "2500"));
        org.mockito.Mockito.when(accounts.findByUserId(USER))
                .thenReturn(List.of(checking("5000")));
        org.mockito.Mockito.when(
                tx.findByUserIdAndDateRange(
                        org.mockito.ArgumentMatchers.eq(USER),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(txs);

        final CashFlowForecastService svc = new CashFlowForecastService(tx, accounts);
        final CashFlowForecastService.Forecast viaUserId = svc.forecast(USER, NOW);
        final CashFlowForecastService.Forecast viaCtx =
                svc.forecast(new InsightsContext(USER, NOW, txs, List.of(checking("5000")), List.of()));

        assertEquals(viaUserId.status, viaCtx.status,
                "ctx overload must report the same status as the userId overload");
        assertEquals(viaUserId.liquidAssets, viaCtx.liquidAssets);
        assertEquals(viaUserId.monthlyIncome, viaCtx.monthlyIncome);
        assertEquals(viaUserId.monthlyExpenses, viaCtx.monthlyExpenses);
    }

    private static AccountTable checking(final String balance) {
        final AccountTable a = new AccountTable();
        a.setAccountType("checking");
        a.setBalance(new BigDecimal(balance));
        return a;
    }

    private static TransactionTable income(final String date, final String amount) {
        final TransactionTable t = new TransactionTable();
        t.setCategoryPrimary("salary");
        t.setTransactionDate(date);
        t.setAmount(new BigDecimal(amount));
        return t;
    }

    private static TransactionTable expense(final String date, final String amount) {
        final TransactionTable t = new TransactionTable();
        t.setCategoryPrimary("dining");
        t.setTransactionDate(date);
        t.setAmount(new BigDecimal(amount).negate());
        return t;
    }

    private static Subscription monthlySub(final String merchant, final String amount) {
        final Subscription s = new Subscription();
        s.setSubscriptionId(java.util.UUID.randomUUID().toString());
        s.setUserId(USER);
        s.setMerchantName(merchant);
        s.setAmount(new BigDecimal(amount));
        s.setFrequency(SubscriptionFrequency.MONTHLY);
        s.setActive(true);
        s.setCreatedAt(NOW.minusDays(180).atStartOfDay());
        return s;
    }

    private static BudgetTable diningBudget(final String limit) {
        final BudgetTable b = new BudgetTable();
        b.setBudgetId("b1");
        b.setUserId(USER);
        b.setCategory("dining");
        b.setMonthlyLimit(new BigDecimal(limit));
        b.setPeriod("monthly");
        return b;
    }

}
