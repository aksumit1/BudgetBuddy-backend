package com.budgetbuddy.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.budgetbuddy.model.dynamodb.BudgetTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Coverage for the three small predicates added to BudgetRolloverService that gate the
 * cross-currency + pending-transaction filtering across every budget aggregator
 * (BudgetRollover, BudgetThreshold, BudgetToGoalFlow, BudgetSummary, WeeklyDigest).
 *
 * <p>These are pure functions over BudgetTable + TransactionTable, so unit-testing them in
 * isolation is the highest-signal coverage we can get for the rule: every aggregator stays
 * in lockstep because they all defer to {@code countsTowardBudget}.
 *
 * <p>Why these tests exist: a regression in the predicate would silently break budget math
 * for any multi-currency user (cross-currency falsely summed in) and would re-introduce the
 * pending-amount-reversal bug (threshold alerts firing then un-firing as pending posts).
 */
class BudgetRolloverServicePredicatesTest {

    // ---------- matchesBudgetCurrency ----------

    @Test
    void matchesBudgetCurrency_returnsTrueWhenBothNull() {
        final BudgetTable b = budget(null);
        final TransactionTable t = txn(BigDecimal.valueOf(-10), null, false);
        assertTrue(BudgetRolloverService.matchesBudgetCurrency(b, t));
    }

    @Test
    void matchesBudgetCurrency_returnsTrueWhenBudgetCurrencyNull_legacyFallback() {
        final BudgetTable b = budget(null);
        final TransactionTable t = txn(BigDecimal.valueOf(-10), "USD", false);
        assertTrue(BudgetRolloverService.matchesBudgetCurrency(b, t));
    }

    @Test
    void matchesBudgetCurrency_returnsTrueWhenTransactionCurrencyNull_legacyFallback() {
        final BudgetTable b = budget("USD");
        final TransactionTable t = txn(BigDecimal.valueOf(-10), null, false);
        assertTrue(BudgetRolloverService.matchesBudgetCurrency(b, t));
    }

    @Test
    void matchesBudgetCurrency_returnsTrueOnExactCaseMatch() {
        final BudgetTable b = budget("USD");
        final TransactionTable t = txn(BigDecimal.valueOf(-10), "USD", false);
        assertTrue(BudgetRolloverService.matchesBudgetCurrency(b, t));
    }

    @Test
    void matchesBudgetCurrency_returnsTrueOnCaseInsensitiveMatch() {
        // Some import paths normalize to uppercase, some preserve client casing.
        final BudgetTable b = budget("USD");
        final TransactionTable t = txn(BigDecimal.valueOf(-10), "usd", false);
        assertTrue(BudgetRolloverService.matchesBudgetCurrency(b, t));
    }

    @Test
    void matchesBudgetCurrency_returnsFalseOnCurrencyMismatch_theMainBug() {
        // The bug this whole filter was added for: a 100 EUR purchase landing in a USD
        // Groceries budget. Must NOT count.
        final BudgetTable b = budget("USD");
        final TransactionTable t = txn(BigDecimal.valueOf(-100), "EUR", false);
        assertFalse(BudgetRolloverService.matchesBudgetCurrency(b, t));
    }

    // ---------- isPosted ----------

    @Test
    void isPosted_returnsTrueWhenPendingFlagNull() {
        // Legacy / non-Plaid rows have no pending flag — assume posted.
        assertTrue(BudgetRolloverService.isPosted(txn(BigDecimal.valueOf(-10), "USD", null)));
    }

    @Test
    void isPosted_returnsTrueWhenPendingFlagFalse() {
        assertTrue(BudgetRolloverService.isPosted(txn(BigDecimal.valueOf(-10), "USD", false)));
    }

    @Test
    void isPosted_returnsFalseWhenPendingFlagTrue() {
        assertFalse(BudgetRolloverService.isPosted(txn(BigDecimal.valueOf(-10), "USD", true)));
    }

    // ---------- countsTowardBudget (the combined predicate the aggregators use) ----------

    @Test
    void countsTowardBudget_acceptsPostedSameCurrency() {
        final BudgetTable b = budget("USD");
        final TransactionTable t = txn(BigDecimal.valueOf(-50), "USD", false);
        assertTrue(BudgetRolloverService.countsTowardBudget(b, t));
    }

    @Test
    void countsTowardBudget_rejectsPendingEvenIfSameCurrency() {
        // The pending-amount-reversal bug: an alert at 50% based on pending amount that later
        // posts at a different value would reverse-then-re-fire to the user.
        final BudgetTable b = budget("USD");
        final TransactionTable t = txn(BigDecimal.valueOf(-50), "USD", true);
        assertFalse(BudgetRolloverService.countsTowardBudget(b, t));
    }

    @Test
    void countsTowardBudget_rejectsCrossCurrencyEvenIfPosted() {
        final BudgetTable b = budget("USD");
        final TransactionTable t = txn(BigDecimal.valueOf(-50), "EUR", false);
        assertFalse(BudgetRolloverService.countsTowardBudget(b, t));
    }

    @Test
    void countsTowardBudget_rejectsPendingCrossCurrency() {
        final BudgetTable b = budget("USD");
        final TransactionTable t = txn(BigDecimal.valueOf(-50), "EUR", true);
        assertFalse(BudgetRolloverService.countsTowardBudget(b, t));
    }

    @Test
    void countsTowardBudget_acceptsLegacyRowsWithEitherCurrencyMissing() {
        // Single-currency users predating the currencyCode column shouldn't regress: the
        // null-on-either-side check returns true, and the legacy row carries no pending flag.
        final BudgetTable b = budget(null);
        final TransactionTable t = txn(BigDecimal.valueOf(-50), null, null);
        assertTrue(BudgetRolloverService.countsTowardBudget(b, t));
    }

    // ---------- test helpers ----------

    private static BudgetTable budget(final String currencyCode) {
        final BudgetTable b = new BudgetTable();
        b.setCategory("groceries");
        b.setMonthlyLimit(BigDecimal.valueOf(500));
        if (currencyCode != null) {
            b.setCurrencyCode(currencyCode);
        }
        return b;
    }

    private static TransactionTable txn(
            final BigDecimal amount, final String currencyCode, final Boolean pending) {
        final TransactionTable t = new TransactionTable();
        t.setAmount(amount);
        t.setCategoryPrimary("groceries");
        if (currencyCode != null) {
            t.setCurrencyCode(currencyCode);
        }
        if (pending != null) {
            t.setPending(pending);
        }
        return t;
    }
}
