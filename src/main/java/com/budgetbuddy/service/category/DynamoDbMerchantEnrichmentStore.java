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
 * Positive entries are written with NO TTL (kept forever). Negative
 * entries get a 5-year (1825-day) TTL. The table has DynamoDB TTL
 * configured to auto-delete expired entries; we set the field, AWS
 * handles eviction.
 */
@Service
@Primary
public class DynamoDbMerchantEnrichmentStore implements MerchantEnrichmentStore {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(DynamoDbMerchantEnrichmentStore.class);

    /**
     * Positive cache: NO TTL — confirmed merchant→category mappings are
     * effectively permanent. A cafe stays a cafe. We rely on the L0
     * user-override layer for the rare merchant that genuinely needs
     * re-categorisation; nothing else should invalidate a confirmed match.
     *
     * Negative cache: 90-day TTL — the 5-year value used here previously was
     * a "set and forget" choice that turned out to be too aggressive: stores
     * close, chains rebrand, OSM/Wikidata gain coverage in months, not
     * years. 90 days re-burns the chain budget at most quarterly per
     * missing merchant, which is acceptable cost for letting newly-indexed
     * merchants resolve naturally.
     */
    private static final long NEGATIVE_TTL_DAYS = 90;
    /** Sentinel category written for negative cache rows. Never returned to callers. */
    private static final String NEGATIVE_SENTINEL = "__L6_NEGATIVE__";

    private final MerchantEnrichmentCacheRepository repo;
    private final ConcurrentMap<String, CategoryResult> hot = new ConcurrentHashMap<>();
    /** Process-local negative-cache mirror so we don't hit Dynamo for every miss. */
    private final java.util.Set<String> hotNegative =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());

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
            final var row = repo.get(key);
            if (row.isPresent()) {
                final CategoryResult r = fromRow(row.get());
                // Filter out negative-sentinel rows from the positive-result API.
                if (NEGATIVE_SENTINEL.equals(r.getCategoryPrimary())) {
                    hotNegative.add(key);
                    return Optional.empty();
                }
                hot.put(key, r);
                return Optional.of(r);
            }
            return Optional.empty();
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
    public boolean isKnownNegative(
            final String merchantName, final String city, final String state,
            final String country) {
        if (merchantName == null || merchantName.isBlank()) {
            return false;
        }
        final String key = MerchantEnrichmentStore.key(merchantName, city, state, country);
        if (hotNegative.contains(key)) {
            return true;
        }
        try {
            final var row = repo.get(key);
            if (row.isPresent() && NEGATIVE_SENTINEL.equals(row.get().getCategoryPrimary())) {
                hotNegative.add(key);
                return true;
            }
        } catch (RuntimeException ex) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(
                        "isKnownNegative lookup failed for '{}': {}", key, ex.getMessage());
            }
        }
        return false;
    }

    @Override
    public void putNegative(
            final String merchantName, final String city, final String state,
            final String country) {
        if (merchantName == null || merchantName.isBlank()) {
            return;
        }
        final String key = MerchantEnrichmentStore.key(merchantName, city, state, country);
        hotNegative.add(key);
        try {
            final Instant now = Instant.now();
            final MerchantEnrichmentCacheTable row = new MerchantEnrichmentCacheTable();
            row.setCacheKey(key);
            row.setCategoryPrimary(NEGATIVE_SENTINEL);
            row.setCategoryDetailed(NEGATIVE_SENTINEL);
            row.setSource("L6_CHAIN_NULL");
            row.setConfidence(0.0);
            row.setMatchedKeyword(NEGATIVE_SENTINEL);
            row.setCreatedAt(now);
            row.setUpdatedAt(now);
            row.setTtl(now.plus(NEGATIVE_TTL_DAYS, ChronoUnit.DAYS).getEpochSecond());
            repo.put(row);
        } catch (RuntimeException ex) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(
                        "putNegative failed for '{}': {} (hot-only)",
                        key, ex.getMessage());
            }
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
            // No TTL on positive rows — confirmed merchant→category is permanent.
            // DynamoDB only auto-deletes rows with a TTL value set; leaving
            // the field null keeps the row indefinitely.
            row.setTtl(null);
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
