package com.budgetbuddy.model.dynamodb;

import java.math.BigDecimal;
import java.time.Instant;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondarySortKey;

/**
 * Flow 7 / O8 — daily snapshot of the user's net worth.
 *
 * <p>Primary key is {@code snapshotId = userId|YYYY-MM-DD} so re-running the nightly job for a date
 * replaces the previous value rather than duplicating it. Secondary GSI on {@code userId} + {@code
 * snapshotDate} gives us "last 12 months of snapshots" in one cheap range query for the trend
 * chart.
 */
@DynamoDbBean
public class NetWorthSnapshotTable {

    private String snapshotId; // "userId|YYYY-MM-DD"
    private String userId; // GSI partition
    private String snapshotDate; // ISO date, GSI sort
    private BigDecimal assetsTotal;
    private BigDecimal liabilitiesTotal;
    private BigDecimal netWorth;
    private String currencyCode;
    private Instant createdAt;

    @DynamoDbPartitionKey
    @DynamoDbAttribute("snapshotId")
    public String getSnapshotId() {
        return snapshotId;
    }

    public void setSnapshotId(final String snapshotId) {
        this.snapshotId = snapshotId;
    }

    @DynamoDbSecondaryPartitionKey(indexNames = "UserIdSnapshotDateIndex")
    @DynamoDbAttribute("userId")
    public String getUserId() {
        return userId;
    }

    public void setUserId(final String userId) {
        this.userId = userId;
    }

    @DynamoDbSecondarySortKey(indexNames = "UserIdSnapshotDateIndex")
    @DynamoDbAttribute("snapshotDate")
    public String getSnapshotDate() {
        return snapshotDate;
    }

    public void setSnapshotDate(final String snapshotDate) {
        this.snapshotDate = snapshotDate;
    }

    @DynamoDbAttribute("assetsTotal")
    public BigDecimal getAssetsTotal() {
        return assetsTotal;
    }

    public void setAssetsTotal(final BigDecimal assetsTotal) {
        this.assetsTotal = assetsTotal;
    }

    @DynamoDbAttribute("liabilitiesTotal")
    public BigDecimal getLiabilitiesTotal() {
        return liabilitiesTotal;
    }

    public void setLiabilitiesTotal(final BigDecimal liabilitiesTotal) {
        this.liabilitiesTotal = liabilitiesTotal;
    }

    @DynamoDbAttribute("netWorth")
    public BigDecimal getNetWorth() {
        return netWorth;
    }

    public void setNetWorth(final BigDecimal netWorth) {
        this.netWorth = netWorth;
    }

    @DynamoDbAttribute("currencyCode")
    public String getCurrencyCode() {
        return currencyCode;
    }

    public void setCurrencyCode(final String currencyCode) {
        this.currencyCode = currencyCode;
    }

    @DynamoDbAttribute("createdAt")
    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(final Instant createdAt) {
        this.createdAt = createdAt;
    }
}
