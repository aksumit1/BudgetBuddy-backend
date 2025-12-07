package com.budgetbuddy.dto;

import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.BudgetTable;
import com.budgetbuddy.model.dynamodb.GoalTable;
import com.budgetbuddy.model.dynamodb.TransactionActionTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;

import java.util.List;

/**
 * Response DTO for /api/sync/all endpoint
 * Returns all user data in a single response for first sync or force refresh
 */
public class SyncAllResponse {
    private List<AccountTable> accounts;
    private List<TransactionTable> transactions;
    private List<BudgetTable> budgets;
    private List<GoalTable> goals;
    private List<TransactionActionTable> actions;
    private Long syncTimestamp; // Epoch seconds when sync was performed

    public SyncAllResponse() {
    }

    public SyncAllResponse(
            final List<AccountTable> accounts,
            final List<TransactionTable> transactions,
            final List<BudgetTable> budgets,
            final List<GoalTable> goals,
            final List<TransactionActionTable> actions,
            final Long syncTimestamp) {
        this.accounts = accounts;
        this.transactions = transactions;
        this.budgets = budgets;
        this.goals = goals;
        this.actions = actions;
        this.syncTimestamp = syncTimestamp;
    }

    public List<AccountTable> getAccounts() {
        return accounts;
    }

    public void setAccounts(final List<AccountTable> accounts) {
        this.accounts = accounts;
    }

    public List<TransactionTable> getTransactions() {
        return transactions;
    }

    public void setTransactions(final List<TransactionTable> transactions) {
        this.transactions = transactions;
    }

    public List<BudgetTable> getBudgets() {
        return budgets;
    }

    public void setBudgets(final List<BudgetTable> budgets) {
        this.budgets = budgets;
    }

    public List<GoalTable> getGoals() {
        return goals;
    }

    public void setGoals(final List<GoalTable> goals) {
        this.goals = goals;
    }

    public List<TransactionActionTable> getActions() {
        return actions;
    }

    public void setActions(final List<TransactionActionTable> actions) {
        this.actions = actions;
    }

    public Long getSyncTimestamp() {
        return syncTimestamp;
    }

    public void setSyncTimestamp(final Long syncTimestamp) {
        this.syncTimestamp = syncTimestamp;
    }
}

