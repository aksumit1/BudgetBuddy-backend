package com.budgetbuddy.compliance;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondarySortKey;

/**
 * DynamoDB table for Audit Logs
 */
@DynamoDbBean
public class AuditLogTable {

    private String auditLogId; // Partition key
    private String userId; // GSI partition key
    private String action;
    private String resourceType;
    private String resourceId;
    private String details;
    private String ipAddress;
    private String userAgent;
    private Long createdAt; // Unix timestamp for GSI sort key

    @DynamoDbPartitionKey
    @DynamoDbAttribute("auditLogId")
    public String getAuditLogId() {
        return auditLogId;
    }

    public void setAuditLogId(final String auditLogId) {
        this.auditLogId = auditLogId;
    }

    @DynamoDbSecondaryPartitionKey(indexNames = "UserIdCreatedAtIndex")
    @DynamoDbAttribute("userId")
    public String getUserId() {
        return userId;
    }

    public void setUserId(final String userId) {
        this.userId = userId;
    }

    @DynamoDbSecondarySortKey(indexNames = "UserIdCreatedAtIndex")
    @DynamoDbAttribute("createdAt")
    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(final Long createdAt) {
        this.createdAt = createdAt;
    }

    @DynamoDbAttribute("action")
    public String getAction() {
        return action;
    }

    public void setAction(final String action) {
        this.action = action;
    }

    @DynamoDbAttribute("resourceType")
    public String getResourceType() {
        return resourceType;
    }

    public void setResourceType(final String resourceType) {
        this.resourceType = resourceType;
    }

    @DynamoDbAttribute("resourceId")
    public String getResourceId() {
        return resourceId;
    }

    public void setResourceId(final String resourceId) {
        this.resourceId = resourceId;
    }

    @DynamoDbAttribute("details")
    public String getDetails() {
        return details;
    }

    public void setDetails(final String details) {
        this.details = details;
    }

    @DynamoDbAttribute("ipAddress")
    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(final String ipAddress) {
        this.ipAddress = ipAddress;
    }

    @DynamoDbAttribute("userAgent")
    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(final String userAgent) {
        this.userAgent = userAgent;
    }
}

