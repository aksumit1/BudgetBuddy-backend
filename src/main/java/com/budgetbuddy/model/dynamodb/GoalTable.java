package com.budgetbuddy.model.dynamodb;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.Instant;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondarySortKey;

/**
 * DynamoDB table for Goals CRITICAL: @JsonInclude ensures null fields (especially accountIds) are
 * included in JSON responses for iOS
 */
@DynamoDbBean
@JsonInclude(JsonInclude.Include.ALWAYS)
public class GoalTable {

    private String goalId; // Partition key
    private String userId; // GSI partition key
    private String name;
    private String description;
    private BigDecimal targetAmount;
    private BigDecimal currentAmount;
    private String targetDate; // ISO date string
    private BigDecimal monthlyContribution;
    private String goalType;
    private String currencyCode;
    private Boolean active;
    private Boolean completed; // Whether goal has been completed (currentAmount >= targetAmount)
    private java.util.List<String>
            accountIds; // List of account IDs associated with this goal for savings tracking
    private Instant completedAt; // Timestamp when goal was completed
    private Boolean roundUpEnabled; // Whether round-up transactions are enabled for this goal

    /**
     * Flow 6: "short_term" | "medium_term" | "long_term". Persists the user's bucket choice so the
     * UI can group goals and the feasibility service can pick sensible defaults. Null on legacy
     * rows — service infers from targetDate.
     */
    private String horizon;

    /**
     * Flow 6 / O2: "manual" | "auto". Determines whether server-side recalc overwrites
     * currentAmount on transaction ingest. Default "auto" matches prior behaviour.
     */
    private String progressMode;

    /** Flow 6 / O9: ordering in the goals tab. Lower = higher in list. */
    private Integer priority;

    /**
     * Flow 6 / O4: soft-delete stamp. Non-null hides the goal from every query while keeping orphan
     * cleanup easy.
     */
    private Instant deletedAt;

    /**
     * Flow 6 / O3: highest milestone percent (25/50/75/100) we've already notified the user about.
     * Prevents repeat pushes as progress oscillates.
     */
    private Integer lastMilestoneReached;

    private Instant createdAt;
    private Instant updatedAt;
    private Long updatedAtTimestamp; // GSI sort key (epoch seconds) for incremental sync

    /** Optimistic-concurrency counter — see {@code BudgetTable.version}. */
    private Long version;

    // TO DO: Add the amount reserved in the account Ids for this goal

    @DynamoDbPartitionKey
    @DynamoDbAttribute("goalId")
    public String getGoalId() {
        return goalId;
    }

    public void setGoalId(final String goalId) {
        this.goalId = goalId;
    }

    @DynamoDbSecondaryPartitionKey(indexNames = {"UserIdIndex", "UserIdUpdatedAtIndex"})
    @DynamoDbAttribute("userId")
    public String getUserId() {
        return userId;
    }

    public void setUserId(final String userId) {
        this.userId = userId;
    }

    @DynamoDbAttribute("name")
    public String getName() {
        return name;
    }

    public void setName(final String name) {
        this.name = name;
    }

    @DynamoDbAttribute("description")
    public String getDescription() {
        return description;
    }

    public void setDescription(final String description) {
        this.description = description;
    }

    @DynamoDbAttribute("targetAmount")
    public BigDecimal getTargetAmount() {
        return targetAmount;
    }

    public void setTargetAmount(final BigDecimal targetAmount) {
        this.targetAmount = targetAmount;
    }

    @DynamoDbAttribute("currentAmount")
    public BigDecimal getCurrentAmount() {
        return currentAmount;
    }

    public void setCurrentAmount(final BigDecimal currentAmount) {
        this.currentAmount = currentAmount;
    }

    @DynamoDbAttribute("targetDate")
    public String getTargetDate() {
        return targetDate;
    }

    public void setTargetDate(final String targetDate) {
        this.targetDate = targetDate;
    }

    @DynamoDbAttribute("monthlyContribution")
    public BigDecimal getMonthlyContribution() {
        return monthlyContribution;
    }

    public void setMonthlyContribution(final BigDecimal monthlyContribution) {
        this.monthlyContribution = monthlyContribution;
    }

    @DynamoDbAttribute("goalType")
    public String getGoalType() {
        return goalType;
    }

    public void setGoalType(final String goalType) {
        this.goalType = goalType;
    }

    @DynamoDbAttribute("currencyCode")
    public String getCurrencyCode() {
        return currencyCode;
    }

    public void setCurrencyCode(final String currencyCode) {
        this.currencyCode = currencyCode;
    }

    @DynamoDbAttribute("active")
    public Boolean getActive() {
        return active;
    }

    public void setActive(final Boolean active) {
        this.active = active;
    }

    @DynamoDbAttribute("completed")
    public Boolean getCompleted() {
        return completed;
    }

    public void setCompleted(final Boolean completed) {
        this.completed = completed;
    }

    @DynamoDbAttribute("accountIds")
    @JsonInclude(JsonInclude.Include.ALWAYS) // Always include in JSON, even if null or empty
    public java.util.List<String> getAccountIds() {
        return accountIds;
    }

    public void setAccountIds(final java.util.List<String> accountIds) {
        this.accountIds = accountIds;
    }

    @DynamoDbAttribute("completedAt")
    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(final Instant completedAt) {
        this.completedAt = completedAt;
    }

    @DynamoDbAttribute("roundUpEnabled")
    public Boolean getRoundUpEnabled() {
        return roundUpEnabled;
    }

    public void setRoundUpEnabled(final Boolean roundUpEnabled) {
        this.roundUpEnabled = roundUpEnabled;
    }

    @DynamoDbAttribute("horizon")
    public String getHorizon() {
        return horizon;
    }

    public void setHorizon(final String horizon) {
        this.horizon = horizon;
    }

    @DynamoDbAttribute("progressMode")
    public String getProgressMode() {
        return progressMode;
    }

    public void setProgressMode(final String progressMode) {
        this.progressMode = progressMode;
    }

    @DynamoDbAttribute("priority")
    public Integer getPriority() {
        return priority;
    }

    public void setPriority(final Integer priority) {
        this.priority = priority;
    }

    @DynamoDbAttribute("deletedAt")
    public Instant getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(final Instant deletedAt) {
        this.deletedAt = deletedAt;
    }

    @DynamoDbAttribute("lastMilestoneReached")
    public Integer getLastMilestoneReached() {
        return lastMilestoneReached;
    }

    public void setLastMilestoneReached(final Integer lastMilestoneReached) {
        this.lastMilestoneReached = lastMilestoneReached;
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
