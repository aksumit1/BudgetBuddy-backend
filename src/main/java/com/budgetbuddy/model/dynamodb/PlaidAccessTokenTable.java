package com.budgetbuddy.model.dynamodb;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

/**
 * Server-side storage for Plaid access tokens, gated behind KMS at the table level (see the
 * matching CloudFormation resource). Previously the backend relied on the iOS client to keep the
 * access token in its keychain and re-present it on every API call, which made the
 * {@code @Scheduled} sync path on the backend impossible — the token wasn't available outside a
 * user-initiated request.
 *
 * <p>Schema:
 *
 * <ul>
 *   <li><b>userId</b> partition key — one row per (user, Plaid item) pair, scoped to user so
 *       per-user deletion is a single GSI walk.
 *   <li><b>plaidItemId</b> sort key — a user can have multiple Plaid items (Chase + Citi); this
 *       disambiguates them.
 *   <li><b>accessToken</b> attribute — KMS-encrypted-at-rest via DynamoDB table-level CMK. Never
 *       returned to the client; backend-only.
 *   <li><b>institutionId / institutionName</b> — denormalised for display without a Plaid
 *       round-trip.
 *   <li><b>PlaidItemIdIndex</b> GSI — lookup by item_id only, used by the webhook handler.
 * </ul>
 */
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP",
        justification = "DynamoDB entity getters; value-semantic with Jackson")
@DynamoDbBean
public class PlaidAccessTokenTable {

    private String userId;
    private String plaidItemId;
    private String accessToken;
    private String institutionId;
    private String institutionName;
    private Instant createdAt;
    private Instant updatedAt;

    /** Optimistic-concurrency version column for safe re-link / rotate. */
    private Long version;

    @DynamoDbPartitionKey
    @DynamoDbAttribute("userId")
    public String getUserId() {
        return userId;
    }

    public void setUserId(final String userId) {
        this.userId = userId;
    }

    @DynamoDbSortKey
    @DynamoDbSecondaryPartitionKey(indexNames = "PlaidItemIdIndex")
    @DynamoDbAttribute("plaidItemId")
    public String getPlaidItemId() {
        return plaidItemId;
    }

    public void setPlaidItemId(final String plaidItemId) {
        this.plaidItemId = plaidItemId;
    }

    @DynamoDbAttribute("accessToken")
    public String getAccessToken() {
        return accessToken;
    }

    public void setAccessToken(final String accessToken) {
        this.accessToken = accessToken;
    }

    @DynamoDbAttribute("institutionId")
    public String getInstitutionId() {
        return institutionId;
    }

    public void setInstitutionId(final String institutionId) {
        this.institutionId = institutionId;
    }

    @DynamoDbAttribute("institutionName")
    public String getInstitutionName() {
        return institutionName;
    }

    public void setInstitutionName(final String institutionName) {
        this.institutionName = institutionName;
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

    @DynamoDbAttribute("version")
    public Long getVersion() {
        return version;
    }

    public void setVersion(final Long version) {
        this.version = version;
    }
}
