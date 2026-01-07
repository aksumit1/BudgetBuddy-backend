package com.budgetbuddy.model.dynamodb;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
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
    private String userName; // Card/account user name (family member who made the transaction)
    private String categoryPrimary; // Primary category (internal, always used for display)
    private String categoryDetailed; // Detailed category (internal, always used for display)
    private String importerCategoryPrimary; // Importer's original primary category (Plaid, CSV parser, etc.)
    private String importerCategoryDetailed; // Importer's original detailed category (Plaid, CSV parser, etc.)
    private Boolean categoryOverridden; // Whether user has overridden the category
    private Boolean transactionTypeOverridden; // Whether user has explicitly overridden transactionType (prevents Plaid sync from recalculating)
    private String transactionDate; // GSI sort key (YYYY-MM-DD format)
    private String currencyCode;
    private String plaidTransactionId; // GSI for deduplication
    private Boolean pending;
    private String paymentChannel; // online, in_store, ach, etc.
    private String notes; // User notes for the transaction
    private String reviewStatus; // Review status: "none", "flagged", "reviewed", "error"
    private Boolean isHidden; // Whether transaction is hidden from view
    private String transactionType; // Transaction type: INCOME, INVESTMENT, LOAN, or EXPENSE
    private String importSource; // Import source: "CSV", "EXCEL", "PDF", "PLAID", "MANUAL"
    private String importBatchId; // UUID for grouping imports
    private String importFileName; // Original file name for imports
    private Instant importedAt; // When transaction was imported
    private String goalId; // Optional: Goal this transaction contributes to
    private String linkedTransactionId; // Optional: ID of linked transaction (e.g., credit card payment linked to checking payment)
    private Instant createdAt;
    private Instant updatedAt;
    private Long updatedAtTimestamp; // GSI sort key (epoch seconds) for incremental sync

    @DynamoDbPartitionKey
    @DynamoDbAttribute("transactionId")
    public String getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(final String transactionId) {
        this.transactionId = transactionId;
    }

    @DynamoDbSecondaryPartitionKey(indexNames = {"UserIdDateIndex", "UserIdUpdatedAtIndex", "UserIdGoalIdIndex"})
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

    @DynamoDbAttribute("userName")
    public String getUserName() {
        return userName;
    }

    public void setUserName(final String userName) {
        this.userName = userName;
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
    
    /**
     * Get category for backward compatibility with iOS app
     * Returns categoryPrimary if available, otherwise categoryDetailed
     */
    @JsonProperty("category")
    public String getCategory() {
        if (categoryPrimary != null && !categoryPrimary.isEmpty()) {
            return categoryPrimary;
        }
        return categoryDetailed;
    }

    @DynamoDbAttribute("importerCategoryPrimary")
    public String getImporterCategoryPrimary() {
        return importerCategoryPrimary;
    }

    public void setImporterCategoryPrimary(final String importerCategoryPrimary) {
        this.importerCategoryPrimary = importerCategoryPrimary;
    }

    @DynamoDbAttribute("importerCategoryDetailed")
    public String getImporterCategoryDetailed() {
        return importerCategoryDetailed;
    }

    public void setImporterCategoryDetailed(final String importerCategoryDetailed) {
        this.importerCategoryDetailed = importerCategoryDetailed;
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

    @DynamoDbAttribute("reviewStatus")
    public String getReviewStatus() {
        return reviewStatus;
    }

    public void setReviewStatus(final String reviewStatus) {
        this.reviewStatus = reviewStatus;
    }

    @DynamoDbAttribute("isHidden")
    public Boolean getIsHidden() {
        return isHidden;
    }

    public void setIsHidden(final Boolean isHidden) {
        this.isHidden = isHidden;
    }

    @DynamoDbAttribute("transactionType")
    public String getTransactionType() {
        return transactionType;
    }

    public void setTransactionType(final String transactionType) {
        this.transactionType = transactionType;
    }

    @DynamoDbAttribute("transactionTypeOverridden")
    public Boolean getTransactionTypeOverridden() {
        return transactionTypeOverridden;
    }

    public void setTransactionTypeOverridden(final Boolean transactionTypeOverridden) {
        this.transactionTypeOverridden = transactionTypeOverridden;
    }

    @DynamoDbAttribute("importSource")
    public String getImportSource() {
        return importSource;
    }

    public void setImportSource(final String importSource) {
        this.importSource = importSource;
    }

    @DynamoDbAttribute("importBatchId")
    public String getImportBatchId() {
        return importBatchId;
    }

    public void setImportBatchId(final String importBatchId) {
        this.importBatchId = importBatchId;
    }

    @DynamoDbAttribute("importFileName")
    public String getImportFileName() {
        return importFileName;
    }

    public void setImportFileName(final String importFileName) {
        this.importFileName = importFileName;
    }

    @DynamoDbAttribute("importedAt")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", timezone = "UTC")
    public Instant getImportedAt() {
        return importedAt;
    }

    public void setImportedAt(final Instant importedAt) {
        this.importedAt = importedAt;
    }

    @DynamoDbSecondarySortKey(indexNames = "UserIdGoalIdIndex")
    @DynamoDbAttribute("goalId")
    public String getGoalId() {
        return goalId;
    }

    public void setGoalId(final String goalId) {
        this.goalId = goalId;
    }

    @DynamoDbAttribute("linkedTransactionId")
    public String getLinkedTransactionId() {
        return linkedTransactionId;
    }

    public void setLinkedTransactionId(final String linkedTransactionId) {
        this.linkedTransactionId = linkedTransactionId;
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

