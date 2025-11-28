package com.budgetbuddy.model.dynamodb;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

import java.time.Instant;

/**
 * DynamoDB table for storing device PIN hashes
 * Partition key: userId
 * Sort key: deviceId
 * 
 * Security: Stores one-way hashed PIN (SHA-256) with device ID
 */
@DynamoDbBean
public class DevicePinTable {

    private String userId; // Partition key
    private String deviceId; // Sort key
    private String pinHash; // SHA-256 hash of 6-digit PIN
    private Instant createdAt;
    private Instant updatedAt;
    private Instant lastVerifiedAt;
    private Integer failedAttempts; // Track failed attempts for rate limiting
    private Instant lockedUntil; // Lock device PIN after too many failures

    @DynamoDbPartitionKey
    @DynamoDbAttribute("userId")
    public String getUserId() {
        return userId;
    }

    public void setUserId(final String userId) {
        this.userId = userId;
    }

    @DynamoDbSortKey
    @DynamoDbAttribute("deviceId")
    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(final String deviceId) {
        this.deviceId = deviceId;
    }

    @DynamoDbAttribute("pinHash")
    public String getPinHash() {
        return pinHash;
    }

    public void setPinHash(final String pinHash) {
        this.pinHash = pinHash;
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

    @DynamoDbAttribute("lastVerifiedAt")
    public Instant getLastVerifiedAt() {
        return lastVerifiedAt;
    }

    public void setLastVerifiedAt(final Instant lastVerifiedAt) {
        this.lastVerifiedAt = lastVerifiedAt;
    }

    @DynamoDbAttribute("failedAttempts")
    public Integer getFailedAttempts() {
        return failedAttempts;
    }

    public void setFailedAttempts(final Integer failedAttempts) {
        this.failedAttempts = failedAttempts;
    }

    @DynamoDbAttribute("lockedUntil")
    public Instant getLockedUntil() {
        return lockedUntil;
    }

    public void setLockedUntil(final Instant lockedUntil) {
        this.lockedUntil = lockedUntil;
    }
}

