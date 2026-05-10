package com.budgetbuddy.model.dynamodb;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.List;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondarySortKey;

/**
 * DynamoDB table for user-defined custom merchant/category mappings Allows users to set custom
 * categories for specific merchants
 */
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP",
        justification =
                "JSON DTO / DynamoDB entity getters expose lists by reference; "
                        + "the design is value-semantic and Jackson creates fresh instances")
@DynamoDbBean
public class CustomMerchantMappingTable {

    private String mappingId; // Partition key (UUID)
    private String userId; // GSI partition key
    private String merchantName; // Normalized merchant name (user's input)
    private List<String> aliases; // Alternative names/patterns for this merchant
    private String categoryPrimary; // User-defined primary category
    private String categoryDetailed; // User-defined detailed category
    private String transactionType; // Optional: user-defined transaction type
    private Boolean isActive; // Whether this mapping is active
    private Integer usageCount; // How many times this mapping was applied
    private Instant lastUsedAt; // Last time this mapping was used
    private Instant createdAt;
    private Instant updatedAt;

    @DynamoDbPartitionKey
    @DynamoDbAttribute("mappingId")
    public String getMappingId() {
        return mappingId;
    }

    public void setMappingId(final String mappingId) {
        this.mappingId = mappingId;
    }

    @DynamoDbSecondaryPartitionKey(indexNames = {"UserIdMerchantIndex", "UserIdActiveIndex"})
    @DynamoDbAttribute("userId")
    public String getUserId() {
        return userId;
    }

    public void setUserId(final String userId) {
        this.userId = userId;
    }

    @DynamoDbSecondarySortKey(indexNames = {"UserIdMerchantIndex", "UserIdActiveIndex"})
    @DynamoDbAttribute("merchantName")
    public String getMerchantName() {
        return merchantName;
    }

    public void setMerchantName(final String merchantName) {
        this.merchantName = merchantName;
    }

    @DynamoDbAttribute("aliases")
    public List<String> getAliases() {
        return aliases;
    }

    public void setAliases(final List<String> aliases) {
        this.aliases = aliases;
    }

    @DynamoDbAttribute("categoryPrimary")
    public String getCategoryPrimary() {
        return categoryPrimary;
    }

    public void setCategoryPrimary(final String categoryPrimary) {
        this.categoryPrimary = categoryPrimary;
    }

    @DynamoDbAttribute("categoryDetailed")
    public String getCategoryDetailed() {
        return categoryDetailed;
    }

    public void setCategoryDetailed(final String categoryDetailed) {
        this.categoryDetailed = categoryDetailed;
    }

    @DynamoDbAttribute("transactionType")
    public String getTransactionType() {
        return transactionType;
    }

    public void setTransactionType(final String transactionType) {
        this.transactionType = transactionType;
    }

    @DynamoDbAttribute("isActive")
    public Boolean getIsActive() {
        return isActive;
    }

    public void setIsActive(final Boolean isActive) {
        this.isActive = isActive;
    }

    @DynamoDbAttribute("usageCount")
    public Integer getUsageCount() {
        return usageCount;
    }

    public void setUsageCount(final Integer usageCount) {
        this.usageCount = usageCount;
    }

    @DynamoDbAttribute("lastUsedAt")
    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public void setLastUsedAt(final Instant lastUsedAt) {
        this.lastUsedAt = lastUsedAt;
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
