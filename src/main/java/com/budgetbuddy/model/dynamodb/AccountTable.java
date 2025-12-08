package com.budgetbuddy.model.dynamodb;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondarySortKey;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * DynamoDB table for Accounts
 */
@DynamoDbBean
public class AccountTable {

    private String accountId; // Partition key
    private String userId; // GSI partition key
    private String accountName;
    private String institutionName;
    private String accountType;
    private String accountSubtype;
    private BigDecimal balance;
    private String currencyCode;
    private String plaidAccountId; // GSI for Plaid lookup
    private String plaidItemId; // GSI for Plaid item lookup
    private String accountNumber; // Account number/mask (last 4 digits) for deduplication
    private Boolean active;
    private Instant lastSyncedAt;
    private Instant createdAt;
    private Instant updatedAt;
    private Long updatedAtTimestamp; // GSI sort key (epoch seconds) for incremental sync

    @DynamoDbPartitionKey
    @DynamoDbAttribute("accountId")
    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(final String accountId) {
        this.accountId = accountId;
    }

    @DynamoDbSecondaryPartitionKey(indexNames = {"UserIdIndex", "UserIdUpdatedAtIndex"})
    @DynamoDbAttribute("userId")
    public String getUserId() {
        return userId;
    }

    public void setUserId(final String userId) {
        this.userId = userId;
    }

    @DynamoDbSecondaryPartitionKey(indexNames = "PlaidAccountIdIndex")
    @DynamoDbAttribute("plaidAccountId")
    public String getPlaidAccountId() {
        return plaidAccountId;
    }

    public void setPlaidAccountId(final String plaidAccountId) {
        this.plaidAccountId = plaidAccountId;
    }

    @DynamoDbSecondaryPartitionKey(indexNames = "PlaidItemIdIndex")
    @DynamoDbAttribute("plaidItemId")
    public String getPlaidItemId() {
        return plaidItemId;
    }

    public void setPlaidItemId(final String plaidItemId) {
        this.plaidItemId = plaidItemId;
    }

    @DynamoDbAttribute("accountName")
    public String getAccountName() {
        return accountName;
    }

    public void setAccountName(final String accountName) {
        this.accountName = accountName;
    }

    @DynamoDbAttribute("institutionName")
    public String getInstitutionName() {
        return institutionName;
    }

    public void setInstitutionName(final String institutionName) {
        this.institutionName = institutionName;
    }

    @DynamoDbAttribute("accountType")
    public String getAccountType() {
        return accountType;
    }

    public void setAccountType(final String accountType) {
        this.accountType = accountType;
    }

    @DynamoDbAttribute("accountSubtype")
    public String getAccountSubtype() {
        return accountSubtype;
    }

    public void setAccountSubtype(final String accountSubtype) {
        this.accountSubtype = accountSubtype;
    }

    @DynamoDbAttribute("balance")
    public BigDecimal getBalance() {
        return balance;
    }

    public void setBalance(final BigDecimal balance) {
        this.balance = balance;
    }

    @DynamoDbAttribute("currencyCode")
    public String getCurrencyCode() {
        return currencyCode;
    }

    public void setCurrencyCode(final String currencyCode) {
        this.currencyCode = currencyCode;
    }

    @DynamoDbAttribute("accountNumber")
    public String getAccountNumber() {
        return accountNumber;
    }

    public void setAccountNumber(final String accountNumber) {
        this.accountNumber = accountNumber;
    }

    @DynamoDbAttribute("active")
    public Boolean getActive() {
        return active;
    }

    public void setActive(final Boolean active) {
        this.active = active;
    }

    @DynamoDbAttribute("lastSyncedAt")
    public Instant getLastSyncedAt() {
        return lastSyncedAt;
    }

    public void setLastSyncedAt(final Instant lastSyncedAt) {
        this.lastSyncedAt = lastSyncedAt;
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
        // Auto-populate timestamp for GSI sort key
        this.updatedAtTimestamp = updatedAt != null ? updatedAt.getEpochSecond() : null;
    }

    @DynamoDbSecondarySortKey(indexNames = "UserIdUpdatedAtIndex")
    @DynamoDbAttribute("updatedAtTimestamp")
    public Long getUpdatedAtTimestamp() {
        return updatedAtTimestamp;
    }

    public void setUpdatedAtTimestamp(final Long updatedAtTimestamp) {
        this.updatedAtTimestamp = updatedAtTimestamp;
    }
}

