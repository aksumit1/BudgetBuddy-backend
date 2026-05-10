package com.budgetbuddy.model.dynamodb;

import java.math.BigDecimal;
import java.time.Instant;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondarySortKey;

/** DynamoDB table for Budgets */
@DynamoDbBean
public class BudgetTable {

    private String budgetId; // Partition key
    private String userId; // GSI partition key
    private String category;
    private BigDecimal monthlyLimit;
    private BigDecimal currentSpent;
    private String currencyCode;
    private Boolean rolloverEnabled; // Whether budget rollover/carryover is enabled
    private BigDecimal
            carriedAmount; // Amount carried from previous month (positive = surplus, negative =
    // deficit)
    private String goalId; // Optional: ID of the goal this budget is linked to

    /** Flow 5 user ask: portion of `monthlyLimit` earmarked for the linked `goalId`. */
    private BigDecimal goalAllocation;

    /** Flow 5 / O3: "weekly" | "biweekly" | "monthly". Null on legacy rows = treat as monthly. */
    private String period;

    /**
     * Flow 5 / O8: highest threshold (50/75/90/100) already alerted this cycle. Reset by the
     * rollover job. Null = nothing alerted yet.
     */
    private Integer lastAlertedThreshold;

    /**
     * Flow 6 / O5: cumulative dollar amount already flowed from this budget to its linked goal
     * during the current cycle. Prevents double-crediting. Reset monthly.
     */
    private BigDecimal lastGoalFunded;

    private Instant createdAt;
    private Instant updatedAt;
    private Long updatedAtTimestamp; // GSI sort key (epoch seconds) for incremental sync

    /**
     * Monotonic version counter for optimistic concurrency. Every write increments this column
     * under a conditional expression that matches the previously-read value, so a concurrent
     * sync-job write and user edit cannot silently clobber each other. Null on legacy rows → treat
     * as 0 on first write.
     */
    private Long version;

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

    @DynamoDbAttribute("goalAllocation")
    public BigDecimal getGoalAllocation() {
        return goalAllocation;
    }

    public void setGoalAllocation(final BigDecimal goalAllocation) {
        this.goalAllocation = goalAllocation;
    }

    @DynamoDbAttribute("period")
    public String getPeriod() {
        return period;
    }

    public void setPeriod(final String period) {
        this.period = period;
    }

    @DynamoDbAttribute("lastAlertedThreshold")
    public Integer getLastAlertedThreshold() {
        return lastAlertedThreshold;
    }

    public void setLastAlertedThreshold(final Integer lastAlertedThreshold) {
        this.lastAlertedThreshold = lastAlertedThreshold;
    }

    @DynamoDbAttribute("lastGoalFunded")
    public BigDecimal getLastGoalFunded() {
        return lastGoalFunded;
    }

    public void setLastGoalFunded(final BigDecimal lastGoalFunded) {
        this.lastGoalFunded = lastGoalFunded;
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

    @DynamoDbAttribute("version")
    public Long getVersion() {
        return version;
    }

    public void setVersion(final Long version) {
        this.version = version;
    }
}
