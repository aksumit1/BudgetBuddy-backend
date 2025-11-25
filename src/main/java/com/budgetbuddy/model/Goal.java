package com.budgetbuddy.model;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Goal entity representing a financial goal
 * Note: This is a domain model. For DynamoDB persistence, use GoalTable.
 */
public class Goal {

    private Long id;

    @NotNull
    private User user;

    private String name;

    private String description;

    private BigDecimal targetAmount;

    private BigDecimal currentAmount = BigDecimal.ZERO;

    private LocalDate targetDate;

    private BigDecimal monthlyContribution;

    private GoalType goalType;

    private String currencyCode = "USD";

    private Boolean active = true;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    // Constructors
    public Goal() {
    }

    public Goal(final User user, final String name, final BigDecimal targetAmount, final LocalDate targetDate) {
        this.user = user;
        this.name = name;
        this.targetAmount = targetAmount;
        this.targetDate = targetDate;
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

    public String getName() {
        return name;
    }

    public void setName(final String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(final String description) {
        this.description = description;
    }

    public BigDecimal getTargetAmount() {
        return targetAmount;
    }

    public void setTargetAmount(final BigDecimal targetAmount) {
        this.targetAmount = targetAmount;
    }

    public BigDecimal getCurrentAmount() {
        return currentAmount;
    }

    public void setCurrentAmount(final BigDecimal currentAmount) {
        this.currentAmount = currentAmount;
    }

    public LocalDate getTargetDate() {
        return targetDate;
    }

    public void setTargetDate(final LocalDate targetDate) {
        this.targetDate = targetDate;
    }

    public BigDecimal getMonthlyContribution() {
        return monthlyContribution;
    }

    public void setMonthlyContribution(final BigDecimal monthlyContribution) {
        this.monthlyContribution = monthlyContribution;
    }

    public GoalType getGoalType() {
        return goalType;
    }

    public void setGoalType(final GoalType goalType) {
        this.goalType = goalType;
    }

    public String getCurrencyCode() {
        return currencyCode;
    }

    public void setCurrencyCode(final String currencyCode) {
        this.currencyCode = currencyCode;
    }

    public Boolean getActive() {
        return active;
    }

    public void setActive(final Boolean active) {
        this.active = active;
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

    public enum GoalType {
        EMERGENCY_FUND,
        VACATION,
        HOUSE_DOWN_PAYMENT,
        CAR_PURCHASE,
        DEBT_PAYOFF,
        RETIREMENT,
        EDUCATION,
        CUSTOM
    }
}

