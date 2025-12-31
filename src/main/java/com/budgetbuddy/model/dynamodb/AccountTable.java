package com.budgetbuddy.model.dynamodb;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondarySortKey;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * DynamoDB table for Accounts
 * CRITICAL: @JsonInclude ensures null fields are included in JSON responses for iOS
 */
@DynamoDbBean
@JsonInclude(JsonInclude.Include.ALWAYS)
public class AccountTable {

    private String accountId; // Partition key
    private String userId; // GSI partition key
    private String accountName;
    private String institutionName;
    private String accountType;
    private String accountSubtype;
    private BigDecimal balance;
    private LocalDate balanceDate; // Date of the transaction from which balance was extracted (for date comparison)
    private String currencyCode;
    private String plaidAccountId; // GSI for Plaid lookup
    private String plaidItemId; // GSI for Plaid item lookup
    private String accountNumber; // Account number/mask (last 4 digits) for deduplication
    private Boolean active;
    private Instant lastSyncedAt;
    private Instant createdAt;
    private Instant updatedAt;
    private Long updatedAtTimestamp; // GSI sort key (epoch seconds) for incremental sync
    
    // Credit card statement metadata (from PDF imports)
    private LocalDate paymentDueDate; // Latest payment due date from statements
    private BigDecimal minimumPaymentDue; // Minimum payment due (from statement with latest payment due date)
    private Long rewardPoints; // Reward points (from statement with latest payment due date, 0 to 10 million)

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

    @DynamoDbAttribute("balanceDate")
    public LocalDate getBalanceDate() {
        return balanceDate;
    }

    public void setBalanceDate(final LocalDate balanceDate) {
        this.balanceDate = balanceDate;
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
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", timezone = "UTC")
    public Instant getLastSyncedAt() {
        return lastSyncedAt;
    }

    public void setLastSyncedAt(final Instant lastSyncedAt) {
        this.lastSyncedAt = lastSyncedAt;
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
    
    @DynamoDbAttribute("paymentDueDate")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    @JsonInclude(JsonInclude.Include.ALWAYS) // Always include in JSON, even if null
    public LocalDate getPaymentDueDate() {
        return paymentDueDate;
    }
    
    public void setPaymentDueDate(final LocalDate paymentDueDate) {
        this.paymentDueDate = paymentDueDate;
    }
    
    @DynamoDbAttribute("minimumPaymentDue")
    @JsonInclude(JsonInclude.Include.ALWAYS) // Always include in JSON, even if null
    public BigDecimal getMinimumPaymentDue() {
        return minimumPaymentDue;
    }
    
    public void setMinimumPaymentDue(final BigDecimal minimumPaymentDue) {
        this.minimumPaymentDue = minimumPaymentDue;
    }
    
    @DynamoDbAttribute("rewardPoints")
    @JsonInclude(JsonInclude.Include.ALWAYS) // Always include in JSON, even if null
    public Long getRewardPoints() {
        return rewardPoints;
    }
    
    public void setRewardPoints(final Long rewardPoints) {
        this.rewardPoints = rewardPoints;
    }
}

