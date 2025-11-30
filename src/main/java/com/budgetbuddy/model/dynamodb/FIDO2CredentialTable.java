package com.budgetbuddy.model.dynamodb;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;

import java.time.Instant;

/**
 * DynamoDB table for FIDO2/WebAuthn credentials
 * Stores passkey credentials for users
 */
@DynamoDbBean
public class FIDO2CredentialTable {

    private String credentialId; // Partition key (Base64 URL encoded)
    private String userId; // GSI partition key
    private String userHandle; // Base64 URL encoded user handle
    private String publicKeyCose; // Base64 encoded COSE public key
    private Long signatureCount; // Signature counter for replay protection
    private String credentialName; // User-friendly name for the credential
    private Instant createdAt;
    private Instant lastUsedAt;
    private Boolean enabled;

    @DynamoDbPartitionKey
    @DynamoDbAttribute("credentialId")
    public String getCredentialId() {
        return credentialId;
    }

    public void setCredentialId(final String credentialId) {
        this.credentialId = credentialId;
    }

    @DynamoDbSecondaryPartitionKey(indexNames = "UserIdIndex")
    @DynamoDbAttribute("userId")
    public String getUserId() {
        return userId;
    }

    public void setUserId(final String userId) {
        this.userId = userId;
    }

    @DynamoDbAttribute("userHandle")
    public String getUserHandle() {
        return userHandle;
    }

    public void setUserHandle(final String userHandle) {
        this.userHandle = userHandle;
    }

    @DynamoDbAttribute("publicKeyCose")
    public String getPublicKeyCose() {
        return publicKeyCose;
    }

    public void setPublicKeyCose(final String publicKeyCose) {
        this.publicKeyCose = publicKeyCose;
    }

    @DynamoDbAttribute("signatureCount")
    public Long getSignatureCount() {
        return signatureCount;
    }

    public void setSignatureCount(final Long signatureCount) {
        this.signatureCount = signatureCount;
    }

    @DynamoDbAttribute("credentialName")
    public String getCredentialName() {
        return credentialName;
    }

    public void setCredentialName(final String credentialName) {
        this.credentialName = credentialName;
    }

    @DynamoDbAttribute("createdAt")
    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(final Instant createdAt) {
        this.createdAt = createdAt;
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
        return enabled;
    }

    public void setEnabled(final Boolean enabled) {
        this.enabled = enabled;
    }
}

