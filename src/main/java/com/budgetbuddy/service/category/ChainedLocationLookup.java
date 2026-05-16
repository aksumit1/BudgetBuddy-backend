package com.budgetbuddy.service.category;

import com.budgetbuddy.service.TransactionTypeCategoryService.CategoryResult;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public ChainedLocationLookup(
            final List<ExternalCategorySource> sources,
            final MerchantEnrichmentStore store) {
        this.sources = sources == null ? Collections.emptyList() : sources;
        this.store = store;
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info(
                    "ChainedLocationLookup: {} external source(s) registered: {}",
                    this.sources.size(),
                    this.sources.stream()
                            .map(s -> s.getClass().getSimpleName())
                            .toList());
        }
    }

    @Override
    public CategoryResult lookup(
            final String merchantName, final String city, final String state,
            final String country) {
        if (merchantName == null || merchantName.isBlank()) {
            return null;
        }
        // Cache check FIRST — avoid every external call for repeat merchants.
        final var cached = store.get(merchantName, city, state, country);
        if (cached.isPresent()) {
            return cached.get();
        }
        // Walk the chain, first hit wins, write back to learning store.
        for (final ExternalCategorySource source : sources) {
            try {
                final CategoryResult r = source.lookup(merchantName, city, state, country);
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
        return null;
    }

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
