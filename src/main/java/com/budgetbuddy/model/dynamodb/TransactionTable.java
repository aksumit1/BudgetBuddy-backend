package com.budgetbuddy.model.dynamodb;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * DynamoDB table for Transactions
 * Optimized with GSI for user queries and date range filtering
 */
@DynamoDbBean
@DynamoDbTable(tableName = "BudgetBuddy-Transactions")
public class TransactionTable {

    private String transactionId; // Partition key
    private String userId; // GSI partition key
    private String accountId;
    private BigDecimal amount;
    private String description;
    private String merchantName;
    private String category;
    private String transactionDate; // GSI sort key (YYYY-MM-DD format)
    private String currencyCode;
    private String plaidTransactionId; // GSI for deduplication
    private Boolean pending;
    private Instant createdAt;
    private Instant updatedAt;

    @DynamoDbPartitionKey
    @DynamoDbAttribute("transactionId")
    public String getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(String transactionId) {
        this.transactionId = transactionId;
    }

    @DynamoDbSecondaryPartitionKey(indexNames = "UserIdDateIndex")
    @DynamoDbAttribute("userId")
    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    @DynamoDbSecondarySortKey(indexNames = "UserIdDateIndex")
    @DynamoDbAttribute("transactionDate")
    public String getTransactionDate() {
        return transactionDate;
    }

    public void setTransactionDate(String transactionDate) {
        this.transactionDate = transactionDate;
    }

    @DynamoDbAttribute("accountId")
    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    @DynamoDbAttribute("amount")
    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    @DynamoDbAttribute("description")
    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    @DynamoDbAttribute("merchantName")
    public String getMerchantName() {
        return merchantName;
    }

    public void setMerchantName(String merchantName) {
        this.merchantName = merchantName;
    }

    @DynamoDbAttribute("category")
    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    @DynamoDbAttribute("currencyCode")
    public String getCurrencyCode() {
        return currencyCode;
    }

    public void setCurrencyCode(String currencyCode) {
        this.currencyCode = currencyCode;
    }

    @DynamoDbSecondaryPartitionKey(indexNames = "PlaidTransactionIdIndex")
    @DynamoDbAttribute("plaidTransactionId")
    public String getPlaidTransactionId() {
        return plaidTransactionId;
    }

    public void setPlaidTransactionId(String plaidTransactionId) {
        this.plaidTransactionId = plaidTransactionId;
    }

    @DynamoDbAttribute("pending")
    public Boolean getPending() {
        return pending;
    }

    public void setPending(Boolean pending) {
        this.pending = pending;
    }

    @DynamoDbAttribute("createdAt")
    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    @DynamoDbAttribute("updatedAt")
    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}

