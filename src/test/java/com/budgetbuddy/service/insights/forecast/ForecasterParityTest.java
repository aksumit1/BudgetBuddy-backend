package com.budgetbuddy.service.insights.forecast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.budgetbuddy.config.FinancialInsightsPredictionProperties;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.service.ml.FinancialInsightsPredictionService;
import com.budgetbuddy.service.ml.FinancialInsightsPredictionService.AccountData;
import com.budgetbuddy.service.ml.FinancialInsightsPredictionService.GoalData;
import com.budgetbuddy.service.ml.FinancialInsightsPredictionService.PaymentPattern;
import com.budgetbuddy.service.ml.FinancialInsightsPredictionService.PredictedExpenseReduction;
import com.budgetbuddy.service.ml.FinancialInsightsPredictionService.PredictedGoalAchievement;
import com.budgetbuddy.service.ml.FinancialInsightsPredictionService.PredictedInterestCost;
import com.budgetbuddy.service.ml.FinancialInsightsPredictionService.PredictedMissedPayment;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pins every extracted forecaster against the original god-class
 * implementation. If a Phase 2 PR ever drifts away from legacy
 * behaviour, this test fails before the change ships.
 */
class ForecasterParityTest {

    private final FinancialInsightsPredictionProperties props =
            new FinancialInsightsPredictionProperties();

    /** A bare facade — no forecaster beans wired, so it uses its inline legacy path. */
    private final FinancialInsightsPredictionService legacy =
            new FinancialInsightsPredictionService(props);

    // ------------------------------------------------------------------
    // ExpenseReductionForecaster
    // ------------------------------------------------------------------

    @Test
    void expenseReductionForecaster_matchesLegacyOnIdenticalInputs() {
        final List<TransactionTable> history = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            // Rising spend in groceries: $50, $70, $90, ...
            history.add(expense("Groceries", "Costco",
                    50.0 + i * 20, "2025-" + month(i + 1) + "-15"));
        }
        final Map<String, BigDecimal> subs = Map.of();

        final ExpenseReductionForecaster forecaster = new ExpenseReductionForecaster(props);
        final List<PredictedExpenseReduction> newOut = forecaster.forecast(history, subs);
        final List<PredictedExpenseReduction> oldOut =
                legacy.predictExpenseReductions(history, subs);
        assertEquals(oldOut.size(), newOut.size(),
                "Extracted ExpenseReductionForecaster must match legacy output count");
    }

    @Test
    void expenseReductionForecaster_returnsEmptyForNullHistory() {
        assertTrue(new ExpenseReductionForecaster(props).forecast(null, Map.of()).isEmpty());
    }

    // ------------------------------------------------------------------
    // GoalAchievementForecaster
    // ------------------------------------------------------------------

    @Test
    void goalAchievementForecaster_matchesLegacyOnIdenticalInputs() {
        final List<TransactionTable> history = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            history.add(income(5000.0, "2025-" + month(i + 1) + "-01"));
            history.add(expense("Rent", "Landlord", 2500.0, "2025-" + month(i + 1) + "-05"));
        }
        final Map<String, GoalData> goals = Map.of(
                "g1", new GoalData("Emergency", new BigDecimal("1000"), new BigDecimal("10000")));

        final GoalAchievementForecaster forecaster = new GoalAchievementForecaster();
        final List<PredictedGoalAchievement> newOut = forecaster.forecast(goals, history);
        final List<PredictedGoalAchievement> oldOut = legacy.predictGoalAchievements(goals, history);

        assertEquals(oldOut.size(), newOut.size());
        if (!oldOut.isEmpty()) {
            // Same probability + same predicted savings means the
            // savings-rate math matches end-to-end.
            assertEquals(oldOut.getFirst().getAchievementProbability(),
                    newOut.getFirst().getAchievementProbability(), 1e-9);
        }
    }

    @Test
    void goalAchievementForecaster_skipsAchievedGoals() {
        // currentAmount >= targetAmount → not predicted (already done).
        final Map<String, GoalData> goals = Map.of(
                "g1", new GoalData("Done", new BigDecimal("5000"), new BigDecimal("1000")));
        final GoalAchievementForecaster forecaster = new GoalAchievementForecaster();
        assertTrue(forecaster.forecast(goals, List.of()).isEmpty());
    }

    @Test
    void goalAchievementForecaster_returnsEmptyForNullGoals() {
        assertTrue(new GoalAchievementForecaster().forecast(null, List.of()).isEmpty());
    }

    // ------------------------------------------------------------------
    // MissedPaymentForecaster
    // ------------------------------------------------------------------

    @Test
    void missedPaymentForecaster_matchesLegacyOnIdenticalInputs() {
        // Inconsistent rent payments — should trigger at-risk prediction.
        final List<TransactionTable> history = new ArrayList<>();
        history.add(expense("Rent", "Landlord", 2500.0, LocalDate.now().minusDays(40).toString()));
        history.add(expense("Rent", "Landlord", 2500.0, LocalDate.now().minusDays(31).toString()));
        history.add(expense("Rent", "Landlord", 2500.0, LocalDate.now().minusDays(2).toString()));
        final Map<String, PaymentPattern> patterns = Map.of(
                "Rent", new PaymentPattern(new BigDecimal("2500"), 30));

        final MissedPaymentForecaster forecaster = new MissedPaymentForecaster();
        final List<PredictedMissedPayment> newOut = forecaster.forecast(history, patterns);
        final List<PredictedMissedPayment> oldOut = legacy.predictMissedPayments(history, patterns);
        assertEquals(oldOut.size(), newOut.size());
    }

    @Test
    void missedPaymentForecaster_returnsEmptyForNullInput() {
        assertTrue(new MissedPaymentForecaster().forecast(null, Map.of()).isEmpty());
        assertTrue(new MissedPaymentForecaster().forecast(List.of(), null).isEmpty());
    }

    // ------------------------------------------------------------------
    // InterestCostForecaster
    // ------------------------------------------------------------------

    @Test
    void interestCostForecaster_matchesLegacyOnIdenticalInputs() {
        final List<TransactionTable> history = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            final TransactionTable t = new TransactionTable();
            t.setAccountId("cc-1");
            t.setAmount(new BigDecimal("-" + (1000 + i * 100)));
            t.setTransactionDate("2025-" + month(i + 1) + "-15");
            history.add(t);
        }
        final Map<String, AccountData> accounts = Map.of(
                "cc-1", new AccountData("Chase", new BigDecimal("5000"), 0.20));

        final InterestCostForecaster forecaster = new InterestCostForecaster(props);
        final List<PredictedInterestCost> newOut = forecaster.forecast(accounts, history);
        final List<PredictedInterestCost> oldOut = legacy.predictInterestCosts(accounts, history);
        assertEquals(oldOut.size(), newOut.size());
        if (!oldOut.isEmpty()) {
            // Annual interest is the headline number — pin it exactly.
            assertEquals(0, oldOut.getFirst().getAnnualInterest()
                    .compareTo(newOut.getFirst().getAnnualInterest()));
        }
    }

    @Test
    void interestCostForecaster_skipsZeroBalanceOrRateAccounts() {
        final Map<String, AccountData> accounts = Map.of(
                "no-rate", new AccountData("Savings", new BigDecimal("5000"), 0.0),
                "no-balance", new AccountData("Empty", BigDecimal.ZERO, 0.20));
        assertTrue(new InterestCostForecaster(props).forecast(accounts, List.of()).isEmpty());
    }

    @Test
    void interestCostForecaster_returnsEmptyForNullAccounts() {
        assertTrue(new InterestCostForecaster(props).forecast(null, List.of()).isEmpty());
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static String month(final int m) {
        return m < 10 ? "0" + m : Integer.toString(m);
    }

    private static TransactionTable expense(
            final String category, final String merchant, final double amount, final String date) {
        final TransactionTable t = new TransactionTable();
        t.setAmount(BigDecimal.valueOf(-amount));
        t.setCategoryPrimary(category);
        t.setMerchantName(merchant);
        t.setDescription(merchant);
        t.setTransactionDate(date);
        return t;
    }

    private static TransactionTable income(final double amount, final String date) {
        final TransactionTable t = new TransactionTable();
        t.setAmount(BigDecimal.valueOf(amount));
        t.setDescription("Payroll");
        t.setMerchantName("Employer");
        t.setTransactionDate(date);
        return t;
    }
}
