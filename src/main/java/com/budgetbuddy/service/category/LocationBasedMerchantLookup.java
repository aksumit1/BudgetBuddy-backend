package com.budgetbuddy.service.category;

import com.budgetbuddy.service.TransactionTypeCategoryService.CategoryResult;
import org.springframework.stereotype.Service;

/**
 * Look up a merchant's category using its name + geographic context
 * (city / state / country). Designed as a pluggable interface so the
 * production deployment can swap in a live backend without touching
 * the cascade.
 *
 * <h3>Available open / public backends (production options)</h3>
 *
 * <ul>
 *   <li><strong>OpenStreetMap (Nominatim + Overpass)</strong> — free,
 *       no API key, ~1 req/sec rate limit. Query Overpass for
 *       {@code node[name~"^Starbucks$"][shop=*]} near a bounding box;
 *       returns OSM tags like {@code amenity=cafe}, {@code shop=supermarket},
 *       {@code tourism=hotel}. Convert OSM tag → internal category.
 *   <li><strong>Foursquare Places API</strong> — free tier 1K calls/day;
 *       returns venue categories aligned to a clean taxonomy.
 *   <li><strong>Google Places API</strong> — paid; high coverage,
 *       authoritative {@code types[]} field.
 *   <li><strong>Mapbox Geocoding</strong> — paid; POI types.
 *   <li><strong>Yelp Fusion API</strong> — free tier; categories aligned
 *       to consumer expectations.
 * </ul>
 *
 * <h3>Why this is an interface and not a hardcoded HTTP call</h3>
 *
 * Live lookups add latency and rate-limit risk to the import path. The
 * production deployment should pick ONE backend, with results cached
 * to a DynamoDB table keyed by (normalised merchant + city + state) so
 * repeat lookups are O(1). Caching strategy is a deployment concern,
 * not a categoriser concern.
 *
 * <h3>Current implementation</h3>
 *
 * {@link OfflineStub} returns null for everything — meaning the cascade
 * falls through to L7 (fuzzy) / L8 (ML). That's deliberate: until a
 * cached or live backend is wired in, this layer adds latency without
 * adding signal. The stub exists so the cascade contract is in place
 * and adding a backend later is one Spring {@code @Primary} away.
 *
 * <h3>Caching contract (when a live backend is added)</h3>
 *
 * <ol>
 *   <li>Normalise merchant + city + state to a single cache key.
 *   <li>Check DynamoDB {@code MerchantGeocodeCache} table — return
 *       cached result if present.
 *   <li>Otherwise: call the backend, parse, store, return.
 *   <li>Cache forever (merchant→category at a given location is
 *       essentially immutable; a refresh job can revalidate).
 * </ol>
 */
public interface LocationBasedMerchantLookup {

    /**
     * Look up category by merchant + city/state/country. Return null if
     * no result is found — the cascade falls through.
     *
     * @param merchantName extracted merchant name (already cleaned)
     * @param city city or null
     * @param state state or null (US two-letter, or international name)
     * @param country country code or null
     */
    CategoryResult lookup(String merchantName, String city, String state, String country);

    /**
     * Default offline implementation — returns null for everything.
     * Used only when {@link ChainedLocationLookup} is absent (e.g. test
     * setups that don't wire any external source). The
     * {@code ChainedLocationLookup} bean is {@code @Primary}, so in
     * production it overrides this stub automatically.
     */
    @Service
    final class OfflineStub implements LocationBasedMerchantLookup {
        @Override
        public CategoryResult lookup(
                final String merchantName,
                final String city,
                final String state,
                final String country) {
            return null;
        }
    }
}
