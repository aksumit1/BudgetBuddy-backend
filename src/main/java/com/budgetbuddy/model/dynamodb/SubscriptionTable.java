package com.budgetbuddy.model.dynamodb;

import com.fasterxml.jackson.annotation.JsonFormat;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * DynamoDB table for Subscriptions
 * Optimized with GSI for user queries
 */
@DynamoDbBean
public class SubscriptionTable {

    private String subscriptionId; // Partition key
    private String userId; // GSI partition key
    private String accountId;
    private String merchantName;
    private String description;
    private BigDecimal amount;
    private String frequency; // MONTHLY, QUARTERLY, SEMI_ANNUAL, ANNUAL
    private String startDate; // YYYY-MM-DD format
    private String nextPaymentDate; // YYYY-MM-DD format
    private String lastPaymentDate; // YYYY-MM-DD format
    private String category;
    private Boolean active;
    private String plaidTransactionId;
    private Instant createdAt;
    private Instant updatedAt;

    @DynamoDbPartitionKey
    @DynamoDbAttribute("subscriptionId")
    public String getSubscriptionId() {
        return subscriptionId;
    }

    public void setSubscriptionId(final String subscriptionId) {
        this.subscriptionId = subscriptionId;
    }

    @DynamoDbSecondaryPartitionKey(indexNames = "UserIdIndex")
    @DynamoDbAttribute("userId")
    public String getUserId() {
        return userId;
    }

    public void setUserId(final String userId) {
        this.userId = userId;
    }

    @DynamoDbAttribute("accountId")
    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(final String accountId) {
        this.accountId = accountId;
    }

    @DynamoDbAttribute("merchantName")
    public String getMerchantName() {
        return merchantName;
    }

    public void setMerchantName(final String merchantName) {
        this.merchantName = merchantName;
    }

    @DynamoDbAttribute("description")
    public String getDescription() {
        return description;
    }

    public void setDescription(final String description) {
        this.description = description;
    }

    @DynamoDbAttribute("amount")
    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(final BigDecimal amount) {
        this.amount = amount;
    }

    @DynamoDbAttribute("frequency")
    public String getFrequency() {
        return frequency;
    }

    public void setFrequency(final String frequency) {
        this.frequency = frequency;
    }

    @DynamoDbAttribute("startDate")
    public String getStartDate() {
        return startDate;
    }

    public void setStartDate(final String startDate) {
        this.startDate = startDate;
    }

    @DynamoDbAttribute("nextPaymentDate")
    public String getNextPaymentDate() {
        return nextPaymentDate;
    }

    public void setNextPaymentDate(final String nextPaymentDate) {
        this.nextPaymentDate = nextPaymentDate;
    }

    @DynamoDbAttribute("lastPaymentDate")
    public String getLastPaymentDate() {
        return lastPaymentDate;
    }

    public void setLastPaymentDate(final String lastPaymentDate) {
        this.lastPaymentDate = lastPaymentDate;
    }

    @DynamoDbAttribute("category")
    public String getCategory() {
        return category;
    }

    public void setCategory(final String category) {
        this.category = category;
    }

    @DynamoDbAttribute("active")
    public Boolean getActive() {
        return active;
    }

    public void setActive(final Boolean active) {
        this.active = active;
    }

    @DynamoDbAttribute("plaidTransactionId")
    public String getPlaidTransactionId() {
        return plaidTransactionId;
    }

    public void setPlaidTransactionId(final String plaidTransactionId) {
        this.plaidTransactionId = plaidTransactionId;
    }

    @DynamoDbAttribute("createdAt")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", timezone = "UTC")
    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(final Instant createdAt) {
        this.createdAt = createdAt;
    }

    @DynamoDbAttribute("updatedAt")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", timezone = "UTC")
    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(final Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}

