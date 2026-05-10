package com.budgetbuddy.model.dynamodb;

import java.time.Instant;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondarySortKey;

/**
 * DynamoDB table for tracking user category corrections Used for learning and improving merchant
 * categorization
 */
@DynamoDbBean
public class UserCorrectionTable {

    private String correctionId; // Partition key (UUID)
    private String userId; // GSI partition key
    private String transactionId; // Transaction that was corrected
    private String merchantName; // Normalized merchant name
    private String originalCategoryPrimary; // Original category before correction
    private String originalCategoryDetailed;
    private String correctedCategoryPrimary; // User's correction
    private String correctedCategoryDetailed;
    private String originalTransactionType; // Original transaction type
    private String correctedTransactionType; // User's correction (if any)
    private String description; // Transaction description for context
    private Integer correctionCount; // How many times this merchant was corrected to this category
    private Instant correctedAt; // When correction was made
    private Instant createdAt;
    private Instant updatedAt;

    @DynamoDbPartitionKey
    @DynamoDbAttribute("correctionId")
    public String getCorrectionId() {
        return correctionId;
    }

    public void setCorrectionId(final String correctionId) {
        this.correctionId = correctionId;
    }

    @DynamoDbSecondaryPartitionKey(indexNames = {"UserIdMerchantIndex", "UserIdDateIndex"})
    @DynamoDbAttribute("userId")
    public String getUserId() {
        return userId;
    }

    public void setUserId(final String userId) {
        this.userId = userId;
    }

    @DynamoDbSecondarySortKey(indexNames = "UserIdDateIndex")
    @DynamoDbAttribute("correctedAtTimestamp")
    public Long getCorrectedAtTimestamp() {
        return correctedAt != null ? correctedAt.getEpochSecond() : null;
    }

    public void setCorrectedAtTimestamp(final Long timestamp) {
        this.correctedAt = timestamp != null ? Instant.ofEpochSecond(timestamp) : null;
    }

    @DynamoDbAttribute("correctedAt")
    public Instant getCorrectedAt() {
        return correctedAt;
    }

    public void setCorrectedAt(final Instant correctedAt) {
        this.correctedAt = correctedAt;
    }

    @DynamoDbAttribute("transactionId")
    public String getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(final String transactionId) {
        this.transactionId = transactionId;
    }

    @DynamoDbSecondarySortKey(indexNames = "UserIdMerchantIndex")
    @DynamoDbAttribute("merchantName")
    public String getMerchantName() {
        return merchantName;
    }

    public void setMerchantName(final String merchantName) {
        this.merchantName = merchantName;
    }

    @DynamoDbAttribute("originalCategoryPrimary")
    public String getOriginalCategoryPrimary() {
        return originalCategoryPrimary;
    }

    public void setOriginalCategoryPrimary(final String originalCategoryPrimary) {
        this.originalCategoryPrimary = originalCategoryPrimary;
    }

    @DynamoDbAttribute("originalCategoryDetailed")
    public String getOriginalCategoryDetailed() {
        return originalCategoryDetailed;
    }

    public void setOriginalCategoryDetailed(final String originalCategoryDetailed) {
        this.originalCategoryDetailed = originalCategoryDetailed;
    }

    @DynamoDbAttribute("correctedCategoryPrimary")
    public String getCorrectedCategoryPrimary() {
        return correctedCategoryPrimary;
    }

    public void setCorrectedCategoryPrimary(final String correctedCategoryPrimary) {
        this.correctedCategoryPrimary = correctedCategoryPrimary;
    }

    @DynamoDbAttribute("correctedCategoryDetailed")
    public String getCorrectedCategoryDetailed() {
        return correctedCategoryDetailed;
    }

    public void setCorrectedCategoryDetailed(final String correctedCategoryDetailed) {
        this.correctedCategoryDetailed = correctedCategoryDetailed;
    }

    @DynamoDbAttribute("originalTransactionType")
    public String getOriginalTransactionType() {
        return originalTransactionType;
    }

    public void setOriginalTransactionType(final String originalTransactionType) {
        this.originalTransactionType = originalTransactionType;
    }

    @DynamoDbAttribute("correctedTransactionType")
    public String getCorrectedTransactionType() {
        return correctedTransactionType;
    }

    public void setCorrectedTransactionType(final String correctedTransactionType) {
        this.correctedTransactionType = correctedTransactionType;
    }

    @DynamoDbAttribute("description")
    public String getDescription() {
        return description;
    }

    public void setDescription(final String description) {
        this.description = description;
    }

    @DynamoDbAttribute("correctionCount")
    public Integer getCorrectionCount() {
        return correctionCount;
    }

    public void setCorrectionCount(final Integer correctionCount) {
        this.correctionCount = correctionCount;
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
