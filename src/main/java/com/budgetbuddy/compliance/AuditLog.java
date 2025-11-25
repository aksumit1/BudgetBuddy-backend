package com.budgetbuddy.compliance;

import java.time.LocalDateTime;

/**
 * Audit Log entity for compliance tracking
 * Note: This is a domain model. For DynamoDB persistence, use AuditLogTable.
 */
public class AuditLog {

    private Long id;

    private Long userId;

    private String action;

    private String resourceType;

    private String resourceId;

    private String details;

    private String ipAddress;

    private String userAgent;

    private LocalDateTime createdAt;

    // Getters and setters
    public Long getId() { return id; }
    public void setId(final Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(final Long userId) { this.userId = userId; }
    public String getAction() { return action; }
    public void setAction(final String action) { this.action = action; }
    public String getResourceType() { return resourceType; }
    public void setResourceType(final String resourceType) { this.resourceType = resourceType; }
    public String getResourceId() { return resourceId; }
    public void setResourceId(final String resourceId) { this.resourceId = resourceId; }
    public String getDetails() { return details; }
    public void setDetails(final String details) { this.details = details; }
    public String getIpAddress() { return ipAddress; }
    public void setIpAddress(final String ipAddress) { this.ipAddress = ipAddress; }
    public String getUserAgent() { return userAgent; }
    public void setUserAgent(final String userAgent) { this.userAgent = userAgent; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(final LocalDateTime createdAt) { this.createdAt = createdAt; }
}

