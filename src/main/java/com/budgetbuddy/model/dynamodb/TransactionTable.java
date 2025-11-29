package com.budgetbuddy.model.dynamodb;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondarySortKey;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * DynamoDB table for Transactions
 * Optimized with GSI for user queries and date range filtering
 */
@DynamoDbBean
public class TransactionTable {

    private String transactionId; // Partition key
    private String userId; // GSI partition key
    private String accountId;
    private BigDecimal amount;
    private String description;
    private String merchantName;
    private String categoryPrimary; // Primary category (from Plaid or user override)
    private String categoryDetailed; // Detailed category (from Plaid or user override)
    private String plaidCategoryPrimary; // Plaid's original primary personal finance category (e.g., "FOOD_AND_DRINK")
    private String plaidCategoryDetailed; // Plaid's original detailed personal finance category (e.g., "RESTAURANTS")
    private Boolean categoryOverridden; // Whether user has overridden Plaid's category
    private String transactionDate; // GSI sort key (YYYY-MM-DD format)
    private String currencyCode;
    private String plaidTransactionId; // GSI for deduplication
    private Boolean pending;
    private String paymentChannel; // online, in_store, ach, etc.
    private String notes; // User notes for the transaction
    private Instant createdAt;
    private Instant updatedAt;

    @DynamoDbPartitionKey
    @DynamoDbAttribute("transactionId")
    public String getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(final String transactionId) {
        this.transactionId = transactionId;
    }

    @DynamoDbSecondaryPartitionKey(indexNames = "UserIdDateIndex")
    @DynamoDbAttribute("userId")
    public String getUserId() {
        return userId;
    }

    public void setUserId(final String userId) {
        this.userId = userId;
    }

    @DynamoDbSecondarySortKey(indexNames = "UserIdDateIndex")
    @DynamoDbAttribute("transactionDate")
    public String getTransactionDate() {
        return transactionDate;
    }

    public void setTransactionDate(final String transactionDate) {
        this.transactionDate = transactionDate;
    }

    @DynamoDbAttribute("accountId")
    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(final String accountId) {
        this.accountId = accountId;
    }

    @DynamoDbAttribute("amount")
    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(final BigDecimal amount) {
        this.amount = amount;
    }

    @DynamoDbAttribute("description")
    public String getDescription() {
        return description;
    }

    public void setDescription(final String description) {
        this.description = description;
    }

    @DynamoDbAttribute("merchantName")
    public String getMerchantName() {
        return merchantName;
    }

    public void setMerchantName(final String merchantName) {
        this.merchantName = merchantName;
    }

    @DynamoDbAttribute("categoryPrimary")
    public String getCategoryPrimary() {
        return categoryPrimary;
    }

    public void setCategoryPrimary(final String categoryPrimary) {
        this.categoryPrimary = categoryPrimary;
    }

    @DynamoDbAttribute("categoryDetailed")
    public String getCategoryDetailed() {
        return categoryDetailed;
    }

    public void setCategoryDetailed(final String categoryDetailed) {
        this.categoryDetailed = categoryDetailed;
    }

    @DynamoDbAttribute("plaidCategoryPrimary")
    public String getPlaidCategoryPrimary() {
        return plaidCategoryPrimary;
    }

    public void setPlaidCategoryPrimary(final String plaidCategoryPrimary) {
        this.plaidCategoryPrimary = plaidCategoryPrimary;
    }

    @DynamoDbAttribute("plaidCategoryDetailed")
    public String getPlaidCategoryDetailed() {
        return plaidCategoryDetailed;
    }

    public void setPlaidCategoryDetailed(final String plaidCategoryDetailed) {
        this.plaidCategoryDetailed = plaidCategoryDetailed;
    }

    @DynamoDbAttribute("categoryOverridden")
    public Boolean getCategoryOverridden() {
        return categoryOverridden;
    }

    public void setCategoryOverridden(final Boolean categoryOverridden) {
        this.categoryOverridden = categoryOverridden;
    }

    @DynamoDbAttribute("currencyCode")
    public String getCurrencyCode() {
        return currencyCode;
    }

    public void setCurrencyCode(final String currencyCode) {
        this.currencyCode = currencyCode;
    }

    @DynamoDbSecondaryPartitionKey(indexNames = "PlaidTransactionIdIndex")
    @DynamoDbAttribute("plaidTransactionId")
    public String getPlaidTransactionId() {
        return plaidTransactionId;
    }

    public void setPlaidTransactionId(final String plaidTransactionId) {
        this.plaidTransactionId = plaidTransactionId;
    }

    @DynamoDbAttribute("pending")
    public Boolean getPending() {
        return pending;
    }

    public void setPending(final Boolean pending) {
        this.pending = pending;
    }

    @DynamoDbAttribute("paymentChannel")
    public String getPaymentChannel() {
        return paymentChannel;
    }

    public void setPaymentChannel(final String paymentChannel) {
        this.paymentChannel = paymentChannel;
    }

    @DynamoDbAttribute("notes")
    public String getNotes() {
        return notes;
    }

    public void setNotes(final String notes) {
        this.notes = notes;
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
}

