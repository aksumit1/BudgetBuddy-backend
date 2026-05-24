package com.budgetbuddy.service.insights;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

@SuppressFBWarnings(
        value = "THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION",
        justification = "JUnit idiom")
final class CashFlowForecastServiceTest {

    private static final LocalDate NOW = LocalDate.of(2026, 5, 15);
    private static final String USER = "u1";

    @Test
    void criticalWhenRunwayUnderThirtyDays() {
        final AccountRepository accounts = mock(AccountRepository.class);
        final TransactionRepository tx = mock(TransactionRepository.class);
        when(accounts.findByUserId(USER)).thenReturn(List.of(checking("500")));
        when(tx.findByUserIdAndDateRange(eq(USER), anyString(), anyString()))
                .thenReturn(List.of(
                        income("2026-02-15", "2000"),
                        income("2026-03-15", "2000"),
                        income("2026-04-15", "2000"),
                        expense("2026-02-20", "3000"),
                        expense("2026-03-20", "3000"),
                        expense("2026-04-20", "3000")));

        final CashFlowForecastService svc = new CashFlowForecastService(tx, accounts);
        final CashFlowForecastService.Forecast f = svc.forecast(USER, NOW);
        // burn $1000/month; $500 assets → ~15 days runway → CRITICAL
        assertEquals("CRITICAL", f.status);
        assertTrue(f.runwayDays < 30, "Expected runway < 30, got " + f.runwayDays);
        assertNotNull(f.message);
        assertTrue(f.message.toLowerCase().contains("run out"));
    }

    @Test
    void surplusWhenIncomeExceedsExpenses() {
        final AccountRepository accounts = mock(AccountRepository.class);
        final TransactionRepository tx = mock(TransactionRepository.class);
        when(accounts.findByUserId(USER)).thenReturn(List.of(checking("5000")));
        when(tx.findByUserIdAndDateRange(eq(USER), anyString(), anyString()))
                .thenReturn(List.of(
                        income("2026-02-15", "5000"),
                        income("2026-03-15", "5000"),
                        expense("2026-02-20", "3000"),
                        expense("2026-03-20", "3000")));

        final CashFlowForecastService.Forecast f =
                new CashFlowForecastService(tx, accounts).forecast(USER, NOW);
        assertEquals("SURPLUS", f.status);
        assertTrue(f.projectedCashAt30Days.compareTo(f.liquidAssets) > 0,
                "Surplus path should grow cash, not shrink it");
    }

    @Test
    void healthyWhenRunwayExceedsNinetyDays() {
        final AccountRepository accounts = mock(AccountRepository.class);
        final TransactionRepository tx = mock(TransactionRepository.class);
        when(accounts.findByUserId(USER)).thenReturn(List.of(checking("10000")));
        when(tx.findByUserIdAndDateRange(eq(USER), anyString(), anyString()))
                .thenReturn(List.of(
                        income("2026-02-15", "2000"),
                        income("2026-03-15", "2000"),
                        expense("2026-02-20", "2500"),
                        expense("2026-03-20", "2500")));

        final CashFlowForecastService.Forecast f =
                new CashFlowForecastService(tx, accounts).forecast(USER, NOW);
        assertEquals("HEALTHY", f.status);
        assertTrue(f.runwayDays >= 90, "Expected >=90 days runway, got " + f.runwayDays);
    }

    @Test
    void onlyCheckingAndSavingsCountAsLiquid() {
        final AccountRepository accounts = mock(AccountRepository.class);
        final TransactionRepository tx = mock(TransactionRepository.class);
        when(accounts.findByUserId(USER))
                .thenReturn(List.of(
                        account("checking", "1000"),
                        account("savings", "500"),
                        account("creditCard", "-200"), // negative — anyway shouldn't count
                        account("brokerage", "50000"))); // not liquid
        when(tx.findByUserIdAndDateRange(eq(USER), anyString(), anyString())).thenReturn(List.of());

        final CashFlowForecastService.Forecast f =
                new CashFlowForecastService(tx, accounts).forecast(USER, NOW);
        assertEquals(new BigDecimal("1500.00"), f.liquidAssets);
    }

    private static AccountTable checking(final String balance) {
        return account("checking", balance);
    }

    private static AccountTable account(final String type, final String balance) {
        final AccountTable a = new AccountTable();
        a.setAccountType(type);
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
}
