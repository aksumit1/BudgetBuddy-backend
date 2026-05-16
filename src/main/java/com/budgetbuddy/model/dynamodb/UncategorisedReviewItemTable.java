package com.budgetbuddy.model.dynamodb;

import java.math.BigDecimal;
import java.time.Instant;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;

/**
 * Durable queue item for the LLM self-review loop. Backs
 * {@link com.budgetbuddy.service.category.UncategorisedReviewQueue.DynamoDbBacked}
 * — every transaction that fell through L0-L8 of the cascade lands in
 * this table for the worker to process later.
 *
 * <p>Why a table not SQS: the rest of the platform already uses DynamoDB
 * and the queue volume is modest (≤ thousands of items/day). A DynamoDB
 * table is operationally simpler — no new IAM roles, no new dead-letter
 * pipeline, and items get a TTL so stale ones evict themselves.
 *
 * <p>Key: random UUID — sort by {@code submittedAt} via scan with limit.
 * For the volume here scan is fine; if growth demands it, add a GSI
 * keyed by status + submittedAt for indexed pagination.
 */
@DynamoDbBean
public class UncategorisedReviewItemTable {

    private String itemId;          // partition key (UUID)
    private String merchantName;
    private String description;
    private String city;
    private String state;
    private String country;
    private BigDecimal amount;
    private String issuerName;
    private String accountType;
    private Instant submittedAt;
    private Long ttl;               // epoch seconds — stale items evict after 7 days

    @DynamoDbPartitionKey
    @DynamoDbAttribute("itemId")
    public String getItemId() { return itemId; }
    public void setItemId(final String v) { this.itemId = v; }

    @DynamoDbAttribute("merchantName")
    public String getMerchantName() { return merchantName; }
    public void setMerchantName(final String v) { this.merchantName = v; }

    @DynamoDbAttribute("description")
    public String getDescription() { return description; }
    public void setDescription(final String v) { this.description = v; }

    @DynamoDbAttribute("city")
    public String getCity() { return city; }
    public void setCity(final String v) { this.city = v; }

    @DynamoDbAttribute("state")
    public String getState() { return state; }
    public void setState(final String v) { this.state = v; }

    @DynamoDbAttribute("country")
    public String getCountry() { return country; }
    public void setCountry(final String v) { this.country = v; }

    @DynamoDbAttribute("amount")
    public BigDecimal getAmount() { return amount; }
    public void setAmount(final BigDecimal v) { this.amount = v; }

    @DynamoDbAttribute("issuerName")
    public String getIssuerName() { return issuerName; }
    public void setIssuerName(final String v) { this.issuerName = v; }

    @DynamoDbAttribute("accountType")
    public String getAccountType() { return accountType; }
    public void setAccountType(final String v) { this.accountType = v; }

    @DynamoDbAttribute("submittedAt")
    public Instant getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(final Instant v) { this.submittedAt = v; }

    @DynamoDbAttribute("ttl")
    public Long getTtl() { return ttl; }
    public void setTtl(final Long v) { this.ttl = v; }
}
