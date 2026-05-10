package com.budgetbuddy.model.dynamodb;

import java.time.Instant;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;

/**
 * DynamoDB persistence for partner-sharing invitations + preferences. Partition key is the
 * inviter's userId — one household per user.
 *
 * <p>The GSI on {@code inviteeEmail} lets us look up pending invitations when the invitee accepts
 * via the signed-token endpoint (not in this commit).
 */
@DynamoDbBean
public class HouseholdTable {

    private String userId; // inviter — partition key
    private String inviteeEmail; // target (GSI partition key)
    private Instant sentAt;
    private Instant acceptedAt; // null until accepted

    // Sharing preferences
    private Boolean shareNetWorth;
    private Boolean shareGoals;
    private Boolean shareBudgets;
    private Boolean shareTransactions;

    private Instant createdAt;
    private Instant updatedAt;

    /** Monotonically-incrementing row version used for optimistic concurrency. */
    private Long version;

    @DynamoDbPartitionKey
    @DynamoDbAttribute("userId")
    public String getUserId() {
        return userId;
    }

    public void setUserId(final String userId) {
        this.userId = userId;
    }

    @DynamoDbSecondaryPartitionKey(indexNames = {"InviteeEmailIndex"})
    @DynamoDbAttribute("inviteeEmail")
    public String getInviteeEmail() {
        return inviteeEmail;
    }

    public void setInviteeEmail(final String inviteeEmail) {
        this.inviteeEmail = inviteeEmail;
    }

    @DynamoDbAttribute("sentAt")
    public Instant getSentAt() {
        return sentAt;
    }

    public void setSentAt(final Instant sentAt) {
        this.sentAt = sentAt;
    }

    @DynamoDbAttribute("acceptedAt")
    public Instant getAcceptedAt() {
        return acceptedAt;
    }

    public void setAcceptedAt(final Instant acceptedAt) {
        this.acceptedAt = acceptedAt;
    }

    @DynamoDbAttribute("shareNetWorth")
    public Boolean getShareNetWorth() {
        return shareNetWorth;
    }

    public void setShareNetWorth(final Boolean shareNetWorth) {
        this.shareNetWorth = shareNetWorth;
    }

    @DynamoDbAttribute("shareGoals")
    public Boolean getShareGoals() {
        return shareGoals;
    }

    public void setShareGoals(final Boolean shareGoals) {
        this.shareGoals = shareGoals;
    }

    @DynamoDbAttribute("shareBudgets")
    public Boolean getShareBudgets() {
        return shareBudgets;
    }

    public void setShareBudgets(final Boolean shareBudgets) {
        this.shareBudgets = shareBudgets;
    }

    @DynamoDbAttribute("shareTransactions")
    public Boolean getShareTransactions() {
        return shareTransactions;
    }

    public void setShareTransactions(final Boolean shareTransactions) {
        this.shareTransactions = shareTransactions;
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
    }

    @DynamoDbAttribute("version")
    public Long getVersion() {
        return version;
    }

    public void setVersion(final Long version) {
        this.version = version;
    }
}
