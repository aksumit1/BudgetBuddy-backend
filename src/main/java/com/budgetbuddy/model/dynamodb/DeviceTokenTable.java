package com.budgetbuddy.model.dynamodb;

import java.time.Instant;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondarySortKey;

/**
 * DynamoDB table for Device Tokens Stores device tokens for push notifications
 *
 * <p>Partition Key: userId Sort Key: deviceToken GSI: userId-index (for querying all devices for a
 * user)
 */
@DynamoDbBean
public class DeviceTokenTable {

    private String userId; // Partition key
    private String deviceToken; // Sort key
    private String platform; // "ios" or "android"
    private String endpointArn; // AWS SNS endpoint ARN
    private Instant createdAt;
    private Instant updatedAt;
    private Instant lastUsedAt; // Last time this device received a notification
    private Boolean enabled; // Whether notifications are enabled for this device

    @DynamoDbPartitionKey
    @DynamoDbAttribute("userId")
    public String getUserId() {
        return userId;
    }

    public void setUserId(final String userId) {
        this.userId = userId;
    }

    // `deviceToken` is both the table's primary sort key (composite with
    // userId) and a secondary sort key on the userId-index GSI. The missing
    // `@DynamoDbSortKey` meant the Enhanced Client built GetItem/PutItem
    // requests with only a partition key, which DynamoDB rejected because
    // the real table schema (created by initializeTable()) has composite
    // keys. Result: every `findByUserIdAndDeviceToken` silently returned
    // empty and every save silently failed with "schema mismatch" that
    // the catch-block swallowed. Tests had to assumeTrue-away to stay
    // green, masking the real bug.
    @software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey
    @DynamoDbSecondarySortKey(indexNames = "userId-index")
    @DynamoDbAttribute("deviceToken")
    public String getDeviceToken() {
        return deviceToken;
    }

    public void setDeviceToken(final String deviceToken) {
        this.deviceToken = deviceToken;
    }

    @DynamoDbAttribute("platform")
    public String getPlatform() {
        return platform;
    }

    public void setPlatform(final String platform) {
        this.platform = platform;
    }

    @DynamoDbAttribute("endpointArn")
    public String getEndpointArn() {
        return endpointArn;
    }

    public void setEndpointArn(final String endpointArn) {
        this.endpointArn = endpointArn;
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

    @DynamoDbAttribute("lastUsedAt")
    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public void setLastUsedAt(final Instant lastUsedAt) {
        this.lastUsedAt = lastUsedAt;
    }

    @DynamoDbAttribute("enabled")
    public Boolean getEnabled() {
        return enabled != null ? enabled : true; // Default to enabled
    }

    public void setEnabled(final Boolean enabled) {
        this.enabled = enabled;
    }
}
