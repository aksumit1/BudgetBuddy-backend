package com.budgetbuddy.model.dynamodb;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondarySortKey;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * DynamoDB table for Budgets
 */
@DynamoDbBean
public class BudgetTable {

    private String budgetId; // Partition key
    private String userId; // GSI partition key
    private String category;
    private BigDecimal monthlyLimit;
    private BigDecimal currentSpent;
    private String currencyCode;
    private Boolean rolloverEnabled; // Whether budget rollover/carryover is enabled
    private BigDecimal carriedAmount; // Amount carried from previous month (positive = surplus, negative = deficit)
    private String goalId; // Optional: ID of the goal this budget is linked to
    private Instant createdAt;
    private Instant updatedAt;
    private Long updatedAtTimestamp; // GSI sort key (epoch seconds) for incremental sync

    @DynamoDbPartitionKey
    @DynamoDbAttribute("budgetId")
    public String getBudgetId() {
        return budgetId;
    }

    public void setBudgetId(final String budgetId) {
        this.budgetId = budgetId;
    }

    @DynamoDbSecondaryPartitionKey(indexNames = {"UserIdIndex", "UserIdUpdatedAtIndex"})
    @DynamoDbAttribute("userId")
    public String getUserId() {
        return userId;
    }

    public void setUserId(final String userId) {
        this.userId = userId;
    }

    @DynamoDbAttribute("category")
    public String getCategory() {
        return category;
    }

    public void setCategory(final String category) {
        this.category = category;
    }

    @DynamoDbAttribute("monthlyLimit")
    public BigDecimal getMonthlyLimit() {
        return monthlyLimit;
    }

    public void setMonthlyLimit(final BigDecimal monthlyLimit) {
        this.monthlyLimit = monthlyLimit;
    }

    @DynamoDbAttribute("currentSpent")
    public BigDecimal getCurrentSpent() {
        return currentSpent;
    }

    public void setCurrentSpent(final BigDecimal currentSpent) {
        this.currentSpent = currentSpent;
    }

    @DynamoDbAttribute("currencyCode")
    public String getCurrencyCode() {
        return currencyCode;
    }

    public void setCurrencyCode(final String currencyCode) {
        this.currencyCode = currencyCode;
    }

    @DynamoDbAttribute("rolloverEnabled")
    public Boolean getRolloverEnabled() {
        return rolloverEnabled;
    }

    public void setRolloverEnabled(final Boolean rolloverEnabled) {
        this.rolloverEnabled = rolloverEnabled;
    }

    @DynamoDbAttribute("carriedAmount")
    public BigDecimal getCarriedAmount() {
        return carriedAmount;
    }

    public void setCarriedAmount(final BigDecimal carriedAmount) {
        this.carriedAmount = carriedAmount;
    }

    @DynamoDbAttribute("goalId")
    public String getGoalId() {
        return goalId;
    }

    public void setGoalId(final String goalId) {
        this.goalId = goalId;
    }

    @DynamoDbAttribute("createdAt")
    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(final Instant createdAt) {
        this.createdAt = createdAt;
    }

    @DynamoDbAttribute("updatedAt")
    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(final Instant updatedAt) {
        this.updatedAt = updatedAt;
        // Auto-populate timestamp for GSI sort key
        this.updatedAtTimestamp = updatedAt != null ? updatedAt.getEpochSecond() : null;
    }

    @DynamoDbSecondarySortKey(indexNames = "UserIdUpdatedAtIndex")
    @DynamoDbAttribute("updatedAtTimestamp")
    public Long getUpdatedAtTimestamp() {
        return updatedAtTimestamp;
    }

    public void setUpdatedAtTimestamp(final Long updatedAtTimestamp) {
        this.updatedAtTimestamp = updatedAtTimestamp;
    }
}

