package com.budgetbuddy.service.category;

import com.budgetbuddy.service.TransactionTypeCategoryService.CategoryResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

/**
 * OpenStreetMap Nominatim merchant lookup — the simpler, friendlier
 * counterpart to Overpass for cases where we just need "what kind of
 * place is this". Free, no API key, ~1 req/sec on the public instance.
 *
 * <h3>Why both Overpass AND Nominatim?</h3>
 *
 * Overpass returns rich tag sets (every OSM tag on the matched element)
 * and is great when we need that detail. Nominatim's friendlier
 * structured search is better for "find this merchant near this city"
 * with a small response. Running both in the chain catches different
 * shapes of merchant — Overpass tends to hit small local businesses
 * tagged by OSM editors; Nominatim hits well-known POIs with rich
 * metadata.
 *
 * <h3>Endpoint</h3>
 *
 * {@code GET https://nominatim.openstreetmap.org/search?q={name},+{city},+{state}&format=json&addressdetails=1&extratags=1}
 *
 * <h3>Rate limit etiquette</h3>
 *
 * OSM Nominatim's usage policy requires:
 * <ul>
 *   <li>A meaningful User-Agent identifying the application
 *   <li>≤ 1 request/second sustained
 *   <li>Cache results aggressively
 * </ul>
 * We comply via the {@link MerchantEnrichmentStore} cache + the
 * {@link #MIN_INTERVAL_MS} pacing field.
 */
@Service
@Order(15)
@ConditionalOnProperty(name = "app.category.nominatim.enabled", havingValue = "true")
public class NominatimMerchantLookup implements ChainedLocationLookup.ExternalCategorySource {

    private static final Logger LOGGER = LoggerFactory.getLogger(NominatimMerchantLookup.class);

    /** OSM usage policy: ~1 req/sec. We over-comply at 1.2s. */
    private static final long MIN_INTERVAL_MS = 1200;
    private static final double NOMINATIM_CONFIDENCE = 0.87;

    private final String apiUrl;
    private final String userAgent;
    private final HttpClient http;
    private final ObjectMapper jsonMapper = new ObjectMapper();
    private long lastRequestMs;

    public NominatimMerchantLookup(
            @Value("${app.category.nominatim.url:https://nominatim.openstreetmap.org/search}")
                    final String apiUrl,
            @Value("${app.category.nominatim.user-agent:BudgetBuddy/1.0 (merchant-categorisation)}")
                    final String userAgent,
            @Value("${app.category.nominatim.timeout-seconds:5}") final int timeoutSeconds) {
        this.apiUrl = apiUrl;
        this.userAgent = userAgent;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .build();
    }

    @Override
    public CategoryResult lookup(
            final String merchantName, final String city, final String state,
            final String country) {
        // Caching handled by ChainedLocationLookup via MerchantEnrichmentStore.
        // Per-source caching would double-cache and not survive restart;
        // the chain's persistent store is the single source of truth.
        if (merchantName == null || merchantName.isBlank()) {
            return null;
        }
        try {
            pace();
            return query(merchantName, city, state, country);
        } catch (Exception ex) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(
                        "Nominatim lookup failed for '{}' ({}, {}): {}",
                        merchantName, city, state, ex.getMessage());
            }
            return null;
        }
    }

    /** Honour OSM Nominatim's ~1 req/sec rate limit. */
    private synchronized void pace() throws InterruptedException {
        final long now = System.currentTimeMillis();
        final long since = now - lastRequestMs;
        if (since < MIN_INTERVAL_MS) {
            Thread.sleep(MIN_INTERVAL_MS - since);
        }
        lastRequestMs = System.currentTimeMillis();
    }

    private CategoryResult query(
            final String merchantName, final String city, final String state,
            final String country) throws Exception {
        final StringBuilder q = new StringBuilder(merchantName);
        if (city != null && !city.isBlank()) q.append(", ").append(city);
        if (state != null && !state.isBlank()) q.append(", ").append(state);
        if (country != null && !country.isBlank()) q.append(", ").append(country);
        final String url =
                apiUrl
                        + "?q=" + URLEncoder.encode(q.toString(), StandardCharsets.UTF_8)
                        + "&format=json&limit=3&addressdetails=0&extratags=1&namedetails=0";

        final HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", userAgent)
                .header("Accept", "application/json")
                .GET()
                .build();
        final HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            return null;
        }
        final JsonNode root = jsonMapper.readTree(resp.body());
        if (!root.isArray()) {
            return null;
        }
        // Nominatim returns {"class":"amenity","type":"restaurant",...} per result.
        // class+type roughly maps to OSM key+value, so we can reuse OsmTagMapper.
        for (final JsonNode result : root) {
            final String klass = result.path("class").asText(null);
            final String type = result.path("type").asText(null);
            if (klass == null || type == null) {
                continue;
            }
            final Map<String, String> tags = new HashMap<>();
            tags.put(klass.toLowerCase(Locale.ROOT), type.toLowerCase(Locale.ROOT));
            // Fold extratags in too — Nominatim sometimes returns extra OSM tags.
            final JsonNode extra = result.path("extratags");
            if (extra.isObject()) {
                final Iterator<Map.Entry<String, JsonNode>> it = extra.fields();
                while (it.hasNext()) {
                    final Map.Entry<String, JsonNode> e = it.next();
                    tags.put(
                            e.getKey().toLowerCase(Locale.ROOT),
                            e.getValue().asText().toLowerCase(Locale.ROOT));
                }
            }
            final CategoryResult mapped = OsmTagMapper.fromTags(tags);
            if (mapped != null) {
                // OsmTagMapper sets confidence — override slightly so callers
                // can distinguish Nominatim vs Overpass provenance.
                return new CategoryResult(
                        mapped.getCategoryPrimary(),
                        mapped.getCategoryDetailed(),
                        "NOMINATIM:" + klass + "=" + type,
                        NOMINATIM_CONFIDENCE);
            }
        }
        return null;
    }

    private static String cacheKey(
            final String merchant, final String city, final String state, final String country) {
        return safe(merchant) + "|" + safe(city) + "|" + safe(state) + "|" + safe(country);
    }

    private static String safe(final String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).trim();
    }
}
