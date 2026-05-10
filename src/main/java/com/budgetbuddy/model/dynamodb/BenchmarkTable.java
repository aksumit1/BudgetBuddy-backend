package com.budgetbuddy.model.dynamodb;

import java.math.BigDecimal;
import java.time.Instant;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

/**
 * DynamoDB persistence for computed community-benchmark percentile bands.
 *
 * <p>Composite key: (bucketId, category). One row per (bucket, category) pair. Bucket ids are
 * normalised strings like {@code "inc-75-150k-hh-1-2"} — the aggregation job produces the full set
 * on each pass.
 *
 * <p>{@code lastComputedAt} carries the freshness stamp; iOS displays it so users know whether
 * they're looking at a fresh read or stale placeholder.
 */
@DynamoDbBean
public class BenchmarkTable {

    private String bucketId; // partition key
    private String category; // sort key
    private BigDecimal medianMonthly;
    private BigDecimal p25;
    private BigDecimal p75;
    private String bucketLabel; // human-readable "Income $75–150K · 1–2 people"
    private Integer sampleSize; // count of users contributing to this bucket
    private Instant lastComputedAt;

    @DynamoDbPartitionKey
    @DynamoDbAttribute("bucketId")
    public String getBucketId() {
        return bucketId;
    }

    public void setBucketId(final String bucketId) {
        this.bucketId = bucketId;
    }

    @DynamoDbSortKey
    @DynamoDbAttribute("category")
    public String getCategory() {
        return category;
    }

    public void setCategory(final String category) {
        this.category = category;
    }

    @DynamoDbAttribute("medianMonthly")
    public BigDecimal getMedianMonthly() {
        return medianMonthly;
    }

    public void setMedianMonthly(final BigDecimal v) {
        this.medianMonthly = v;
    }

    @DynamoDbAttribute("p25")
    public BigDecimal getP25() {
        return p25;
    }

    public void setP25(final BigDecimal v) {
        this.p25 = v;
    }

    @DynamoDbAttribute("p75")
    public BigDecimal getP75() {
        return p75;
    }

    public void setP75(final BigDecimal v) {
        this.p75 = v;
    }

    @DynamoDbAttribute("bucketLabel")
    public String getBucketLabel() {
        return bucketLabel;
    }

    public void setBucketLabel(final String v) {
        this.bucketLabel = v;
    }

    @DynamoDbAttribute("sampleSize")
    public Integer getSampleSize() {
        return sampleSize;
    }

    public void setSampleSize(final Integer v) {
        this.sampleSize = v;
    }

    @DynamoDbAttribute("lastComputedAt")
    public Instant getLastComputedAt() {
        return lastComputedAt;
    }

    public void setLastComputedAt(final Instant v) {
        this.lastComputedAt = v;
    }
}
