package com.budgetbuddy.model.dynamodb;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondarySortKey;

import java.time.Instant;

/**
 * DynamoDB table for Transaction Actions/Reminders
 * GSI: TransactionIdIndex (transactionId as partition key) for querying actions by transaction
 * GSI: UserIdIndex (userId as partition key) for querying actions by user
 * GSI: ReminderDateIndex (reminderDatePartition as partition key, reminderDate as sort key) for querying reminders by date
 */
@DynamoDbBean
public class TransactionActionTable {

    private String actionId; // Partition key
    private String transactionId; // GSI partition key
    private String userId; // GSI partition key for UserIdIndex
    private String title;
    private String description;
    private String dueDate; // ISO date string (YYYY-MM-DD) or ISO datetime
    private String reminderDate; // ISO datetime - GSI sort key for ReminderDateIndex
    private String reminderDatePartition; // Partition key for ReminderDateIndex (YYYY-MM-DD format for distribution)
    private Boolean isCompleted;
    private String priority; // LOW, MEDIUM, HIGH
    private String notificationId; // For tracking scheduled notifications
    private Instant createdAt;
    private Instant updatedAt;
    private Long updatedAtTimestamp; // GSI sort key (epoch seconds) for incremental sync

    @DynamoDbPartitionKey
    @DynamoDbAttribute("actionId")
    public String getActionId() {
        return actionId;
    }

    public void setActionId(final String actionId) {
        this.actionId = actionId;
    }

    @DynamoDbSecondaryPartitionKey(indexNames = "TransactionIdIndex")
    @DynamoDbAttribute("transactionId")
    public String getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(final String transactionId) {
        this.transactionId = transactionId;
    }

    @DynamoDbSecondaryPartitionKey(indexNames = {"UserIdIndex", "UserIdUpdatedAtIndex"})
    @DynamoDbAttribute("userId")
    public String getUserId() {
        return userId;
    }

    public void setUserId(final String userId) {
        this.userId = userId;
    }

    @DynamoDbAttribute("title")
    public String getTitle() {
        return title;
    }

    public void setTitle(final String title) {
        this.title = title;
    }

    @DynamoDbAttribute("description")
    public String getDescription() {
        return description;
    }

    public void setDescription(final String description) {
        this.description = description;
    }

    @DynamoDbAttribute("dueDate")
    public String getDueDate() {
        return dueDate;
    }

    public void setDueDate(final String dueDate) {
        this.dueDate = dueDate;
    }

    @DynamoDbAttribute("reminderDate")
    @DynamoDbSecondarySortKey(indexNames = "ReminderDateIndex")
    public String getReminderDate() {
        return reminderDate;
    }

    public void setReminderDate(final String reminderDate) {
        this.reminderDate = reminderDate;
        // Auto-set partition key from reminder date (YYYY-MM-DD format)
        if (reminderDate != null && !reminderDate.isEmpty()) {
            try {
                // Extract date part from ISO datetime (e.g., "2024-12-30T10:00:00Z" -> "2024-12-30")
                if (reminderDate.contains("T")) {
                    this.reminderDatePartition = reminderDate.substring(0, reminderDate.indexOf("T"));
                } else {
                    // Already a date string
                    this.reminderDatePartition = reminderDate.substring(0, Math.min(10, reminderDate.length()));
                }
            } catch (Exception e) {
                // If parsing fails, use a default partition
                this.reminderDatePartition = "DEFAULT";
            }
        } else {
            this.reminderDatePartition = null;
        }
    }
    
    @DynamoDbSecondaryPartitionKey(indexNames = "ReminderDateIndex")
    @DynamoDbAttribute("reminderDatePartition")
    public String getReminderDatePartition() {
        return reminderDatePartition;
    }
    
    public void setReminderDatePartition(final String reminderDatePartition) {
        this.reminderDatePartition = reminderDatePartition;
    }

    @DynamoDbAttribute("isCompleted")
    public Boolean getIsCompleted() {
        return isCompleted;
    }

    public void setIsCompleted(final Boolean isCompleted) {
        this.isCompleted = isCompleted;
    }

    @DynamoDbAttribute("priority")
    public String getPriority() {
        return priority;
    }

    public void setPriority(final String priority) {
        this.priority = priority;
    }

    @DynamoDbAttribute("notificationId")
    public String getNotificationId() {
        return notificationId;
    }

    public void setNotificationId(final String notificationId) {
        this.notificationId = notificationId;
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

