package com.budgetbuddy.service.category;

import com.budgetbuddy.model.dynamodb.MerchantEnrichmentCacheTable;
import com.budgetbuddy.repository.dynamodb.MerchantEnrichmentCacheRepository;
import com.budgetbuddy.service.TransactionTypeCategoryService.CategoryResult;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/**
 * Persistent, multi-pod merchant→category learning store.
 *
 * <p>Reads come from a process-local cache first (in-memory hot path),
 * then fall through to DynamoDB. Writes go to both — the in-memory
 * cache for this pod and the DynamoDB table for every other pod and
 * every restart.
 *
 * <h3>Why two levels</h3>
 *
 * <ul>
 *   <li><strong>In-memory hot path</strong>: a typical import re-queries
 *       the same merchant dozens of times. We don't want to round-trip
 *       to DynamoDB for every one. The map gives O(1) within a process.
 *   <li><strong>DynamoDB durable</strong>: process restarts and
 *       horizontal scale-out lose nothing. A merchant learned on pod-1
 *       last week is instantly known to pod-7 today.
 * </ul>
 *
 * <h3>Source provenance</h3>
 *
 * Every cached row carries its origin ({@code source} field — e.g.
 * {@code "OSM_TAG:shop=supermarket"}, {@code "SELF_LEARNED_LLM"}). When
 * we re-read, we surface that back via {@link CategoryResult#getSource()}
 * so audit / UI / debugging can trace the decision.
 *
 * <h3>TTL</h3>
 *
 * Entries get a 365-day TTL on write. The table has DynamoDB TTL
 * configured to auto-delete expired entries; we set the field, AWS
 * handles eviction.
 */
@Service
@Primary
public class DynamoDbMerchantEnrichmentStore implements MerchantEnrichmentStore {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(DynamoDbMerchantEnrichmentStore.class);

    private static final long TTL_DAYS = 365;

    private final MerchantEnrichmentCacheRepository repo;
    private final ConcurrentMap<String, CategoryResult> hot = new ConcurrentHashMap<>();

    public DynamoDbMerchantEnrichmentStore(final MerchantEnrichmentCacheRepository repo) {
        this.repo = repo;
    }

    @Override
    public Optional<CategoryResult> get(
            final String merchantName, final String city, final String state,
            final String country) {
        if (merchantName == null || merchantName.isBlank()) {
            return Optional.empty();
        }
        final String key = MerchantEnrichmentStore.key(merchantName, city, state, country);
        final CategoryResult fromMemory = hot.get(key);
        if (fromMemory != null) {
            return Optional.of(fromMemory);
        }
        try {
            return repo.get(key).map(this::fromRow).map(r -> {
                hot.put(key, r);
                return r;
            });
        } catch (RuntimeException ex) {
            // DynamoDB hiccup must never break categorisation. Fall back
            // to "no learned entry" — the cascade then tries the next layer.
            if (LOGGER.isWarnEnabled()) {
                LOGGER.warn(
                        "DynamoDbMerchantEnrichmentStore.get failed for '{}': {}",
                        key, ex.getMessage());
            }
            return Optional.empty();
        }
    }

    @Override
    public void put(
            final String merchantName, final String city, final String state,
            final String country, final CategoryResult result) {
        if (merchantName == null || merchantName.isBlank() || result == null) {
            return;
        }
        final String key = MerchantEnrichmentStore.key(merchantName, city, state, country);
        hot.put(key, result);
        try {
            final Instant now = Instant.now();
            final MerchantEnrichmentCacheTable row = new MerchantEnrichmentCacheTable();
            row.setCacheKey(key);
            row.setCategoryPrimary(result.getCategoryPrimary());
            row.setCategoryDetailed(result.getCategoryDetailed());
            row.setSource(result.getSource());
            row.setConfidence(result.getConfidence());
            row.setMatchedKeyword(result.getCategoryPrimary());
            row.setCreatedAt(now);
            row.setUpdatedAt(now);
            row.setTtl(now.plus(TTL_DAYS, ChronoUnit.DAYS).getEpochSecond());
            repo.put(row);
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(
                        "Persisted merchant '{}' ({}, {}) → {} (source={}, conf={})",
                        merchantName, city, state,
                        result.getCategoryPrimary(), result.getSource(), result.getConfidence());
            }
        } catch (RuntimeException ex) {
            if (LOGGER.isWarnEnabled()) {
                LOGGER.warn(
                        "DynamoDbMerchantEnrichmentStore.put failed for '{}': {}",
                        key, ex.getMessage());
            }
        }
    }

    private CategoryResult fromRow(final MerchantEnrichmentCacheTable row) {
        return new CategoryResult(
                row.getCategoryPrimary(),
                row.getCategoryDetailed() != null
                        ? row.getCategoryDetailed()
                        : row.getCategoryPrimary(),
                row.getSource() != null ? row.getSource() : "LEARNED_CACHE",
                row.getConfidence() != null ? row.getConfidence() : 0.85);
    }
}
