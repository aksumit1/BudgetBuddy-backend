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
 * five years). The {@link InProcess} default does the same job in-memory
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

    /**
     * Remember that NO external source could resolve this merchant +
     * location. Lets us skip the L6 chain on the next import for the
     * same merchant instead of re-burning the per-request budget on a
     * known-failing lookup. Stored with a 5-year TTL (positive entries
     * are stored forever — see {@link #put}). The asymmetry: a confirmed
     * positive almost never needs to be re-validated; a negative might
     * eventually gain coverage upstream and is worth retrying after a
     * few years. If you need to force a re-query before TTL expiry, the
     * L0 user-override layer can do it for a specific merchant without
     * invalidating the whole table.
     *
     * <p>Default is no-op so {@link InProcess} and any future impl that
     * doesn't want to track negatives can ignore it.
     */
    default void putNegative(final String merchantName, final String city,
                             final String state, final String country) {
        // no-op by default
    }

    /**
     * Has a previous lookup for this (merchant, location) been recorded
     * as failing? Used by {@link ChainedLocationLookup} so the chain
     * skips known-bad lookups instead of re-walking every source.
     */
    default boolean isKnownNegative(final String merchantName, final String city,
                                    final String state, final String country) {
        return false;
    }

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
