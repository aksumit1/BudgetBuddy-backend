package com.budgetbuddy.model.dynamodb;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondarySortKey;

import java.time.Instant;
import java.util.Set;

/**
 * DynamoDB table for Users
 * Optimized for cost: on-demand billing, GSI for email lookup
 *
 * Security: Stores server-side hashed passwords with server salt
 */
@DynamoDbBean
public class UserTable {

    private String userId; // Partition key
    private String email; // GSI partition key
    private String passwordHash; // Server-side PBKDF2 hash
    private String serverSalt; // Server-side salt for password hashing
    // BREAKING CHANGE: clientSalt removed - Zero Trust architecture
    private String firstName;
    private String lastName;
    private String phoneNumber;
    private Boolean enabled;
    private Boolean emailVerified;
    private Boolean twoFactorEnabled;
    private String preferredCurrency;
    private String timezone;
    private Set<String> roles;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant lastLoginAt;
    private Long lastLoginAtTimestamp; // GSI sort key (epoch seconds) for finding active users
    private String activeStatus; // GSI partition key: "ACTIVE" or "INACTIVE" (computed from enabled)
    private Instant passwordChangedAt;

    @DynamoDbPartitionKey
    @DynamoDbAttribute("userId")
    public String getUserId() {
        return userId;
    }

    public void setUserId(final String userId) {
        this.userId = userId;
    }

    @DynamoDbSecondaryPartitionKey(indexNames = "EmailIndex")
    @DynamoDbAttribute("email")
    public String getEmail() {
        return email;
    }

    public void setEmail(final String email) {
        this.email = email;
    }

    @DynamoDbAttribute("passwordHash")
    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(final String passwordHash) {
        this.passwordHash = passwordHash;
    }

    @DynamoDbAttribute("serverSalt")
    public String getServerSalt() {
        return serverSalt;
    }

    public void setServerSalt(final String serverSalt) {
        this.serverSalt = serverSalt;
    }

    // BREAKING CHANGE: clientSalt removed - Zero Trust architecture
    // Client salt is no longer stored on backend

    @DynamoDbAttribute("firstName")
    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(final String firstName) {
        this.firstName = firstName;
    }

    @DynamoDbAttribute("lastName")
    public String getLastName() {
        return lastName;
    }

    public void setLastName(final String lastName) {
        this.lastName = lastName;
    }

    @DynamoDbAttribute("phoneNumber")
    public String getPhoneNumber() {
        return phoneNumber;
    }

    public void setPhoneNumber(final String phoneNumber) {
        this.phoneNumber = phoneNumber;
    }

    @DynamoDbAttribute("enabled")
    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(final Boolean enabled) {
        this.enabled = enabled;
        // Auto-populate activeStatus for GSI partition key
        this.activeStatus = (enabled != null && enabled) ? "ACTIVE" : "INACTIVE";
    }

    @DynamoDbAttribute("emailVerified")
    public Boolean getEmailVerified() {
        return emailVerified;
    }

    public void setEmailVerified(final Boolean emailVerified) {
        this.emailVerified = emailVerified;
    }

    @DynamoDbAttribute("twoFactorEnabled")
    public Boolean getTwoFactorEnabled() {
        return twoFactorEnabled;
    }

    public void setTwoFactorEnabled(final Boolean twoFactorEnabled) {
        this.twoFactorEnabled = twoFactorEnabled;
    }

    @DynamoDbAttribute("preferredCurrency")
    public String getPreferredCurrency() {
        return preferredCurrency;
    }

    public void setPreferredCurrency(final String preferredCurrency) {
        this.preferredCurrency = preferredCurrency;
    }

    @DynamoDbAttribute("timezone")
    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(final String timezone) {
        this.timezone = timezone;
    }

    @DynamoDbAttribute("roles")
    public Set<String> getRoles() {
        return roles;
    }

    public void setRoles(final Set<String> roles) {
        this.roles = roles;
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

    @DynamoDbAttribute("lastLoginAt")
    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    public void setLastLoginAt(final Instant lastLoginAt) {
        this.lastLoginAt = lastLoginAt;
        // Auto-populate timestamp for GSI sort key
        this.lastLoginAtTimestamp = lastLoginAt != null ? lastLoginAt.getEpochSecond() : null;
    }

    @DynamoDbSecondaryPartitionKey(indexNames = "ActiveUsersIndex")
    @DynamoDbAttribute("activeStatus")
    public String getActiveStatus() {
        // Auto-compute from enabled if not set
        if (activeStatus == null && enabled != null) {
            activeStatus = enabled ? "ACTIVE" : "INACTIVE";
        }
        return activeStatus;
    }

    public void setActiveStatus(final String activeStatus) {
        this.activeStatus = activeStatus;
    }

    @DynamoDbSecondarySortKey(indexNames = "ActiveUsersIndex")
    @DynamoDbAttribute("lastLoginAtTimestamp")
    public Long getLastLoginAtTimestamp() {
        return lastLoginAtTimestamp;
    }

    public void setLastLoginAtTimestamp(final Long lastLoginAtTimestamp) {
        this.lastLoginAtTimestamp = lastLoginAtTimestamp;
    }

    @DynamoDbAttribute("passwordChangedAt")
    public Instant getPasswordChangedAt() {
        return passwordChangedAt;
    }

    public void setPasswordChangedAt(final Instant passwordChangedAt) {
        this.passwordChangedAt = passwordChangedAt;
    }
}
