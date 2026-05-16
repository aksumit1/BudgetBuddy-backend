package com.budgetbuddy.service.category;

import com.budgetbuddy.service.TransactionTypeCategoryService.CategoryResult;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Persistent learning cache for merchant→category mappings discovered
 * by external lookups (OSM, Wikidata, Foursquare, etc.) and by the LLM
 * self-review loop.
 *
 * <p>This is the runtime augmentation of layer L3 (Merchant DB). When
 * any external source resolves a merchant for the first time, the
 * result is written here so the NEXT import sees a cache hit and never
 * calls the external API again.
 *
 * <p>The interface is intentionally small — just {@link #get} and
 * {@link #put}. Production deployments back it with DynamoDB (one row
 * per (normalised merchant, city, state, country) with TTL of, say,
 * one year). The {@link InProcess} default does the same job in-memory
 * so the cascade compiles + runs without new infrastructure.
 *
 * <h3>Why this matters</h3>
 *
 * Without write-back, every external lookup re-queries the same APIs
 * for the same merchants every import. Three months of imports for
 * 100 users could easily burn the entire daily Overpass / Foursquare
 * free quota. With write-back, the system gets <em>permanently better</em>
 * the more it sees — that's the "augment learning" the architecture
 * was missing.
 */
public interface MerchantEnrichmentStore {

    /**
     * Look up a previously-learned categorisation for this merchant +
     * location. Returns empty if no cached entry.
     */
    Optional<CategoryResult> get(String merchantName, String city, String state, String country);

    /**
     * Remember that this merchant + location maps to this category.
     * Idempotent — repeated puts are fine and may refresh the entry.
     */
    void put(String merchantName, String city, String state, String country,
             CategoryResult result);

    /** Build a stable key for cache / store lookups. */
    static String key(
            final String merchant, final String city, final String state, final String country) {
        return safe(merchant) + "|" + safe(city) + "|" + safe(state) + "|" + safe(country);
    }

    private static String safe(final String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).trim();
    }

    /**
     * Process-lifetime cache. Safe default — no new infrastructure needed.
     * Production should replace via a {@code @Primary} DynamoDB-backed
     * implementation that persists across restarts and across pods.
     */
    @Service
    final class InProcess implements MerchantEnrichmentStore {
        private static final Logger LOGGER = LoggerFactory.getLogger(InProcess.class);
        private final ConcurrentMap<String, CategoryResult> cache = new ConcurrentHashMap<>();

        @Override
        public Optional<CategoryResult> get(
                final String merchantName, final String city, final String state,
                final String country) {
            if (merchantName == null || merchantName.isBlank()) {
                return Optional.empty();
            }
            return Optional.ofNullable(cache.get(key(merchantName, city, state, country)));
        }

        @Override
        public void put(
                final String merchantName, final String city, final String state,
                final String country, final CategoryResult result) {
            if (merchantName == null || merchantName.isBlank() || result == null) {
                return;
            }
            cache.put(key(merchantName, city, state, country), result);
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(
                        "MerchantEnrichmentStore: learned '{}' ({}, {}) → {} (source={}, conf={})",
                        merchantName, city, state,
                        result.getCategoryPrimary(), result.getSource(), result.getConfidence());
            }
        }
    }
}
