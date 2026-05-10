package com.budgetbuddy.model.dynamodb;

import java.time.Instant;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;

/**
 * Flow 7 / O1 — persistent record of the user's reaction to an anomaly detection.
 *
 * <p>We compute a stable <em>fingerprint</em> from (merchant, category, rounded amount) rather than
 * storing the transaction id, because the same pattern keeps surfacing across new transactions. A
 * single "dismiss $180 at Costco, that's my normal weekly run" should shut the noise up
 * permanently, not just for one row.
 */
@DynamoDbBean
public class AnomalyFeedbackTable {

    private String feedbackId;
    private String userId;
    private String fingerprint;
    private String verdict; // NORMAL | CONFIRMED | DISMISSED
    private String anomalyId;
    private Instant createdAt;
    private Instant updatedAt;

    @DynamoDbPartitionKey
    @DynamoDbAttribute("feedbackId")
    public String getFeedbackId() {
        return feedbackId;
    }

    public void setFeedbackId(final String feedbackId) {
        this.feedbackId = feedbackId;
    }

    @DynamoDbSecondaryPartitionKey(indexNames = "UserIdIndex")
    @DynamoDbAttribute("userId")
    public String getUserId() {
        return userId;
    }

    public void setUserId(final String userId) {
        this.userId = userId;
    }

    @DynamoDbAttribute("fingerprint")
    public String getFingerprint() {
        return fingerprint;
    }

    public void setFingerprint(final String fingerprint) {
        this.fingerprint = fingerprint;
    }

    @DynamoDbAttribute("verdict")
    public String getVerdict() {
        return verdict;
    }

    public void setVerdict(final String verdict) {
        this.verdict = verdict;
    }

    @DynamoDbAttribute("anomalyId")
    public String getAnomalyId() {
        return anomalyId;
    }

    public void setAnomalyId(final String anomalyId) {
        this.anomalyId = anomalyId;
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
}
