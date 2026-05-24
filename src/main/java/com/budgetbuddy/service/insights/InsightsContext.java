package com.budgetbuddy.service.insights;

import com.budgetbuddy.model.Subscription;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.BudgetTable;
import com.budgetbuddy.model.dynamodb.TransactionActionTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Snapshot of every piece of user data the insights services consume,
 * pre-fetched once per request. Eliminates the 5×-amplification that
 * occurs when {@code /summary} (and any future summary-like endpoint)
 * invokes every detector — each detector would otherwise re-fetch
 * overlapping transaction windows from DynamoDB.
 *
 * <p>The transaction list covers the longest window any detector needs
 * ({@code transactionsAsOf - 180d → transactionsAsOf}). Detectors that
 * want a shorter window must filter the list themselves; that's cheap
 * (in-memory) compared to the DynamoDB round-trip they would have made.
 *
 * <p>Use {@link com.budgetbuddy.service.insights.InsightsContextFactory}
 * to build instances — the factory centralises the longest-window
 * decision so it can be tuned without touching every consumer.
 *
 * <p>This type is immutable and thread-safe. Lists are wrapped with
 * {@link Collections#unmodifiableList(List)} at construction so a
 * detector can't accidentally mutate the shared snapshot.
 */
public final class InsightsContext {

    private final String userId;
    private final LocalDate asOf;
    private final List<TransactionTable> transactions;
    private final List<AccountTable> accounts;
    private final List<Subscription> subscriptions;
    private final List<TransactionActionTable> transactionActions;
    private final List<BudgetTable> budgets;

    public InsightsContext(
            final String userId,
            final LocalDate asOf,
            final List<TransactionTable> transactions,
            final List<AccountTable> accounts,
            final List<Subscription> subscriptions,
            final List<TransactionActionTable> transactionActions,
            final List<BudgetTable> budgets) {
        this.userId = Objects.requireNonNull(userId, "userId");
        this.asOf = Objects.requireNonNull(asOf, "asOf");
        this.transactions = Collections.unmodifiableList(
                Objects.requireNonNullElse(transactions, List.of()));
        this.accounts = Collections.unmodifiableList(
                Objects.requireNonNullElse(accounts, List.of()));
        this.subscriptions = Collections.unmodifiableList(
                Objects.requireNonNullElse(subscriptions, List.of()));
        this.transactionActions = Collections.unmodifiableList(
                Objects.requireNonNullElse(transactionActions, List.of()));
        this.budgets = Collections.unmodifiableList(
                Objects.requireNonNullElse(budgets, List.of()));
    }

    /**
     * Backwards-compat constructor for callers (mostly tests) that
     * pre-date the {@code budgets} field. Defaults to an empty list —
     * the budget-exhaustion forecaster falls back to a repo fetch when
     * it sees an empty budget list, so old tests keep working.
     */
    public InsightsContext(
            final String userId,
            final LocalDate asOf,
            final List<TransactionTable> transactions,
            final List<AccountTable> accounts,
            final List<Subscription> subscriptions,
            final List<TransactionActionTable> transactionActions) {
        this(userId, asOf, transactions, accounts, subscriptions, transactionActions, List.of());
    }

    /**
     * Backwards-compat constructor for callers (mostly tests) that
     * pre-date the {@code transactionActions} field. Defaults to empty.
     */
    public InsightsContext(
            final String userId,
            final LocalDate asOf,
            final List<TransactionTable> transactions,
            final List<AccountTable> accounts,
            final List<Subscription> subscriptions) {
        this(userId, asOf, transactions, accounts, subscriptions, List.of(), List.of());
    }

    public String userId() { return userId; }
    public LocalDate asOf() { return asOf; }
    public List<TransactionTable> transactions() { return transactions; }
    public List<AccountTable> accounts() { return accounts; }
    public List<Subscription> subscriptions() { return subscriptions; }
    public List<TransactionActionTable> transactionActions() { return transactionActions; }
    public List<BudgetTable> budgets() { return budgets; }
}
