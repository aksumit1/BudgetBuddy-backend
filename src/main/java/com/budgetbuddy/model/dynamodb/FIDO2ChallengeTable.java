package com.budgetbuddy.model.dynamodb;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;

import java.time.Instant;

/**
 * DynamoDB table for FIDO2/WebAuthn challenges
 * Stores registration and authentication challenges with TTL
 * TTL ensures challenges expire automatically
 */
@DynamoDbBean
public class FIDO2ChallengeTable {

    private String challengeKey; // Partition key (userId:type, e.g., "userId:registration")
    private String challenge; // Base64 URL encoded challenge
    private String challengeType; // "registration" or "authentication"
    private String userId;
    private Instant expiresAt;
    private Long ttl; // Time to live (Unix timestamp for DynamoDB TTL)

    @DynamoDbPartitionKey
    @DynamoDbAttribute("challengeKey")
    public String getChallengeKey() {
        return challengeKey;
    }

    public void setChallengeKey(final String challengeKey) {
        this.challengeKey = challengeKey;
    }

    @DynamoDbAttribute("challenge")
    public String getChallenge() {
        return challenge;
    }

    public void setChallenge(final String challenge) {
        this.challenge = challenge;
    }

    @DynamoDbAttribute("challengeType")
    public String getChallengeType() {
        return challengeType;
    }

    public void setChallengeType(final String challengeType) {
        this.challengeType = challengeType;
    }

    @DynamoDbAttribute("userId")
    public String getUserId() {
        return userId;
    }

    public void setUserId(final String userId) {
        this.userId = userId;
    }

    @DynamoDbAttribute("expiresAt")
    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(final Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    @DynamoDbAttribute("ttl")
    public Long getTtl() {
        return ttl;
    }

    public void setTtl(final Long ttl) {
        this.ttl = ttl;
    }
}

