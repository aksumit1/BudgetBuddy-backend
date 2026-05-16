package com.budgetbuddy.model.dynamodb;

import java.time.Instant;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;

/**
 * Persistent learning cache for merchant→category lookups discovered by
 * external sources (OSM, Wikidata, Foursquare, …) and by the LLM
 * self-review loop. Backs
 * {@link com.budgetbuddy.service.category.MerchantEnrichmentStore}.
 *
 * <h3>Why this exists</h3>
 *
 * The in-process default loses learning on every pod restart. With this
 * table, learning is permanent — once any source resolves a merchant,
 * every future request (any pod, any user) hits the cache.
 *
 * <h3>Key</h3>
 *
 * {@code cacheKey = normalizedMerchant|city|state|country} —
 * deterministic across processes. We don't put userId in the key
 * because a merchant's category is the same for everyone (an
 * <em>override</em> for one user is a different concern, handled by
 * {@code CustomMerchantMappingTable}).
 *
 * <h3>TTL</h3>
 *
 * Entries expire after one year by default. A merchant's category is
 * mostly stable, but businesses do convert (a cafe becomes a bar). Yearly
 * refresh keeps stale entries from sticking forever.
 */
@DynamoDbBean
public class MerchantEnrichmentCacheTable {

    private String cacheKey;        // partition key
    private String categoryPrimary;
    private String categoryDetailed;
    private String source;          // "OSM_TAG:shop=supermarket", "WIKIDATA:supermarket chain", etc.
    private Double confidence;
    private String matchedKeyword;  // human-readable hint about what matched
    private Instant createdAt;
    private Instant updatedAt;
    private Long ttl;               // epoch seconds — DynamoDB TTL field

    @DynamoDbPartitionKey
    @DynamoDbAttribute("cacheKey")
    public String getCacheKey() { return cacheKey; }
    public void setCacheKey(final String v) { this.cacheKey = v; }

    @DynamoDbAttribute("categoryPrimary")
    public String getCategoryPrimary() { return categoryPrimary; }
    public void setCategoryPrimary(final String v) { this.categoryPrimary = v; }

    @DynamoDbAttribute("categoryDetailed")
    public String getCategoryDetailed() { return categoryDetailed; }
    public void setCategoryDetailed(final String v) { this.categoryDetailed = v; }

    @DynamoDbAttribute("source")
    public String getSource() { return source; }
    public void setSource(final String v) { this.source = v; }

    @DynamoDbAttribute("confidence")
    public Double getConfidence() { return confidence; }
    public void setConfidence(final Double v) { this.confidence = v; }

    @DynamoDbAttribute("matchedKeyword")
    public String getMatchedKeyword() { return matchedKeyword; }
    public void setMatchedKeyword(final String v) { this.matchedKeyword = v; }

    @DynamoDbAttribute("createdAt")
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(final Instant v) { this.createdAt = v; }

    @DynamoDbAttribute("updatedAt")
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(final Instant v) { this.updatedAt = v; }

    @DynamoDbAttribute("ttl")
    public Long getTtl() { return ttl; }
    public void setTtl(final Long v) { this.ttl = v; }
}
