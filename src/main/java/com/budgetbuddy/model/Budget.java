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

    public Budget(User user, Transaction.TransactionCategory category, BigDecimal monthlyLimit) {
        this.user = user;
        this.category = category;
        this.monthlyLimit = monthlyLimit;
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public Transaction.TransactionCategory getCategory() {
        return category;
    }

    public void setCategory(Transaction.TransactionCategory category) {
        this.category = category;
    }

    public BigDecimal getMonthlyLimit() {
        return monthlyLimit;
    }

    public void setMonthlyLimit(BigDecimal monthlyLimit) {
        this.monthlyLimit = monthlyLimit;
    }

    public BigDecimal getCurrentSpent() {
        return currentSpent;
    }

    public void setCurrentSpent(BigDecimal currentSpent) {
        this.currentSpent = currentSpent;
    }

    public String getCurrencyCode() {
        return currencyCode;
    }

    public void setCurrencyCode(String currencyCode) {
        this.currencyCode = currencyCode;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}

