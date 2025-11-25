package com.budgetbuddy.model;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Budget entity representing a monthly budget for a category
 * Note: This is a domain model. For DynamoDB persistence, use BudgetTable.
 */
public class Budget {

    private Long id;

    @NotNull
    private User user;

    private Transaction.TransactionCategory category;

    private BigDecimal monthlyLimit;

    private BigDecimal currentSpent = BigDecimal.ZERO;

    private String currencyCode = "USD";

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    // Constructors
    public Budget() {
    }

    public Budget(final User user, final Transaction.TransactionCategory category,
                  final BigDecimal monthlyLimit) {
        this.user = user;
        this.category = category;
        this.monthlyLimit = monthlyLimit;
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(final Long id) {
        this.id = id;
    }

    public User getUser() {
        return user;
    }

    public void setUser(final User user) {
        this.user = user;
    }

    public Transaction.TransactionCategory getCategory() {
        return category;
    }

    public void setCategory(final Transaction.TransactionCategory category) {
        this.category = category;
    }

    public BigDecimal getMonthlyLimit() {
        return monthlyLimit;
    }

    public void setMonthlyLimit(final BigDecimal monthlyLimit) {
        this.monthlyLimit = monthlyLimit;
    }

    public BigDecimal getCurrentSpent() {
        return currentSpent;
    }

    public void setCurrentSpent(final BigDecimal currentSpent) {
        this.currentSpent = currentSpent;
    }

    public String getCurrencyCode() {
        return currencyCode;
    }

    public void setCurrencyCode(final String currencyCode) {
        this.currencyCode = currencyCode;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(final LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(final LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}

