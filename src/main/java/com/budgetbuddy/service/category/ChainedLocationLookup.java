package com.budgetbuddy.service.category;

import com.budgetbuddy.service.TransactionTypeCategoryService.CategoryResult;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

/**
 * Composes every registered {@link ExternalCategorySource} into a single
 * chained lookup. Iterates in {@link Order} priority; first non-null
 * result wins. Every successful lookup is written back to
 * {@link MerchantEnrichmentStore} so subsequent imports hit the cache
 * and skip the network entirely.
 *
 * <h3>Adding a new free database</h3>
 *
 * <ol>
 *   <li>Implement {@link ExternalCategorySource}.
 *   <li>Annotate {@code @Service} (or {@code @Component}) with an
 *       {@code @Order} value. Lower number = tried earlier.
 *   <li>Spring auto-discovers it; this class auto-injects the new bean
 *       into the chain.
 * </ol>
 *
 * No edits to this class. No edits to {@link CategoryCascade}. New free
 * databases plug in via DI alone.
 *
 * <h3>Caching contract</h3>
 *
 * Before consulting any external source, we check the
 * {@link MerchantEnrichmentStore}. After any source returns a result,
 * we write it to the store. So the steady state for a given merchant is:
 *
 * <pre>
 *   first import:   external API → store
 *   subsequent:     store hit, no external call
 * </pre>
 */
@Service
@Primary
public class ChainedLocationLookup implements LocationBasedMerchantLookup {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChainedLocationLookup.class);

    private final List<ExternalCategorySource> sources;
    private final MerchantEnrichmentStore store;
    private final long perRequestBudgetMs;
    private final long perSourceBudgetMs;
    /** Negative-cache flag so a single hard miss isn't re-queried mid-import. */
    private final java.util.Set<String> negativeCache =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
    private final AtomicLong totalLookups = new AtomicLong();
    private final AtomicLong budgetExceededCount = new AtomicLong();

    public ChainedLocationLookup(
            final List<ExternalCategorySource> sources,
            final MerchantEnrichmentStore store,
            @Value("${app.category.location.per-request-budget-ms:1500}")
                    final long perRequestBudgetMs,
            @Value("${app.category.location.per-source-budget-ms:800}")
                    final long perSourceBudgetMs) {
        this.sources = sources == null ? Collections.emptyList() : sources;
        this.store = store;
        this.perRequestBudgetMs = perRequestBudgetMs;
        this.perSourceBudgetMs = perSourceBudgetMs;
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info(
                    "ChainedLocationLookup: {} external source(s) registered: {} "
                            + "(per-request budget={}ms, per-source budget={}ms)",
                    this.sources.size(),
                    this.sources.stream()
                            .map(s -> s.getClass().getSimpleName())
                            .toList(),
                    perRequestBudgetMs,
                    perSourceBudgetMs);
        }
    }

    @Override
    public CategoryResult lookup(
            final String merchantName, final String city, final String state,
            final String country) {
        if (merchantName == null || merchantName.isBlank()) {
            return null;
        }
        totalLookups.incrementAndGet();
        // Cache check FIRST — avoid every external call for repeat merchants.
        final var cached = store.get(merchantName, city, state, country);
        if (cached.isPresent()) {
            return cached.get();
        }
        // Persistent negative cache (DynamoDB-backed via the store impl).
        // Survives backend restarts so we don't re-burn the chain budget on
        // the same uncategorizable merchants every container rebuild. Stored
        // with a 5-year TTL; positive entries are kept forever. Most
        // uncategorisable strings (ACH descriptors, autopay codes) stay
        // uncategorisable, so the 5-year window is just a generous safety
        // net for upstream OSM/Wikidata gaining coverage someday.
        if (store.isKnownNegative(merchantName, city, state, country)) {
            return null;
        }
        // Process-local negative cache for same-request dedupe — cheaper
        // than DynamoDB on the hot path when many tx in one import share a
        // merchant we just tried and failed.
        final String negKey = key(merchantName, city, state, country);
        if (negativeCache.contains(negKey)) {
            return null;
        }
        // Walk the chain. Per-request wall-clock budget + per-source budget
        // protect against any single slow source stalling the whole import.
        final long deadline = System.currentTimeMillis() + perRequestBudgetMs;
        for (final ExternalCategorySource source : sources) {
            final long now = System.currentTimeMillis();
            if (now >= deadline) {
                budgetExceededCount.incrementAndGet();
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug(
                            "Per-request budget exhausted before {} for '{}'",
                            source.getClass().getSimpleName(), merchantName);
                }
                break;
            }
            try {
                final CategoryResult r = callWithBudget(source, merchantName, city, state, country);
                if (r != null) {
                    store.put(merchantName, city, state, country, r);
                    return r;
                }
            } catch (RuntimeException ex) {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug(
                            "Source {} threw during lookup of '{}': {}",
                            source.getClass().getSimpleName(), merchantName, ex.getMessage());
                }
            }
        }
        // Persist the negative result (7-day TTL) so subsequent imports and
        // backend restarts skip the chain for this merchant. The same-JVM
        // negative set is kept as a hot-path optimisation.
        store.putNegative(merchantName, city, state, country);
        negativeCache.add(negKey);
        return null;
    }

    /**
     * Invoke {@code source.lookup(...)} on a daemon thread with a per-source
     * time budget. If the source doesn't return within {@link #perSourceBudgetMs}
     * the thread is left orphaned (it will finish later — its result is
     * discarded) and we move on. This bounds the chain's worst-case latency
     * even when an external API is unresponsive.
     */
    private CategoryResult callWithBudget(
            final ExternalCategorySource source,
            final String merchantName, final String city, final String state,
            final String country) {
        final java.util.concurrent.CompletableFuture<CategoryResult> future =
                new java.util.concurrent.CompletableFuture<>();
        final Thread t = new Thread(
                () -> {
                    try {
                        future.complete(
                                source.lookup(merchantName, city, state, country));
                    } catch (RuntimeException ex) {
                        future.completeExceptionally(ex);
                    }
                },
                "loc-lookup-" + source.getClass().getSimpleName());
        t.setDaemon(true);
        t.start();
        try {
            return future.get(perSourceBudgetMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException te) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(
                        "Source {} exceeded {}ms budget for '{}' — abandoning",
                        source.getClass().getSimpleName(), perSourceBudgetMs, merchantName);
            }
            return null;
        } catch (java.util.concurrent.ExecutionException ee) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(
                        "Source {} failed for '{}': {}",
                        source.getClass().getSimpleName(),
                        merchantName,
                        ee.getCause() == null ? ee.getMessage() : ee.getCause().getMessage());
            }
            return null;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static String key(
            final String m, final String c, final String s, final String cn) {
        return (m == null ? "" : m.toLowerCase(java.util.Locale.ROOT))
                + "|" + (c == null ? "" : c.toLowerCase(java.util.Locale.ROOT))
                + "|" + (s == null ? "" : s.toLowerCase(java.util.Locale.ROOT))
                + "|" + (cn == null ? "" : cn.toLowerCase(java.util.Locale.ROOT));
    }

    /** Diagnostics: how many lookups, and how many bailed on budget. */
    public long getTotalLookups() { return totalLookups.get(); }

    public long getBudgetExceededCount() { return budgetExceededCount.get(); }

    /**
     * Marker interface for free / open / paid databases that can resolve
     * a merchant + location to a category. Each implementation owns its
     * own caching policy, rate-limit handling, and credentials.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@link OverpassMerchantLookup} — OSM Overpass API (free, no key)
     *   <li>{@code WikidataMerchantLookup} — Wikidata SPARQL (free, no key)
     *   <li>{@code FoursquareMerchantLookup} — Foursquare Places (free tier
     *       1K/day, API key required)
     *   <li>{@code OpenCorporatesLookup} — UK/US registered business SIC
     *       codes (free, no key)
     *   <li>{@code GooglePlacesMerchantLookup} — Google Places (paid)
     * </ul>
     */
    public interface ExternalCategorySource {
        CategoryResult lookup(String merchantName, String city, String state, String country);
    }
}
