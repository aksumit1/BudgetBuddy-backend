package com.budgetbuddy.service.category;

import com.budgetbuddy.service.TransactionTypeCategoryService.CategoryResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Live merchant-category lookup against the OpenStreetMap Overpass API.
 *
 * <p>For each (merchant, city, state, country), we issue a small Overpass
 * query that finds nodes/ways tagged with that name in or near the city
 * and returns their OSM tags. {@link OsmTagMapper} converts the tags to
 * an internal category.
 *
 * <h3>Caching</h3>
 *
 * Network round-trips on the credit-card import critical path would
 * add seconds per row. So:
 * <ul>
 *   <li>Every successful lookup is cached in-process (process-lifetime
 *       {@link ConcurrentHashMap}) so a single import job pays the
 *       round-trip at most once per distinct merchant.
 *   <li>For multi-instance / restart durability, a DynamoDB
 *       {@code MerchantGeocodeCache} table is the natural next step —
 *       the in-process cache here is a safe baseline that doesn't
 *       require new infrastructure to land.
 *   <li>Negative results (no match) are also cached so we don't retry
 *       the same dead lookup on every import.
 * </ul>
 *
 * <h3>Rate limiting / failure handling</h3>
 *
 * Overpass public instances rate-limit aggressive callers (~10K queries
 * per day per IP). On any HTTP failure, timeout, or rate-limit, we log
 * at DEBUG and return null — the cascade falls through to fuzzy/ML so
 * the import never blocks on Overpass being slow or down.
 *
 * <h3>Enable / disable</h3>
 *
 * Activated by {@code app.category.overpass.enabled=true} (default
 * false). When disabled, the bean isn't constructed and
 * {@link LocationBasedMerchantLookup.OfflineStub} takes its place.
 * Why opt-in: synchronous network calls are a deployment decision, not
 * a code default.
 *
 * <h3>Production upgrade path</h3>
 *
 * <ol>
 *   <li>Add DynamoDB {@code MerchantGeocodeCache} table; back this
 *       service with it instead of in-process.
 *   <li>Move the actual Overpass call to a background worker that
 *       drains a queue of "category-unknown" merchants; the import
 *       path becomes pure cache lookup.
 *   <li>Self-host Overpass for unlimited query budget.
 * </ol>
 */
@Service
@org.springframework.core.annotation.Order(10)
@ConditionalOnProperty(name = "app.category.overpass.enabled", havingValue = "true")
public class OverpassMerchantLookup implements ChainedLocationLookup.ExternalCategorySource {

    private static final Logger LOGGER = LoggerFactory.getLogger(OverpassMerchantLookup.class);

    private final String overpassUrl;
    private final HttpClient http;
    private final ObjectMapper jsonMapper = new ObjectMapper();

    public OverpassMerchantLookup(
            @Value("${app.category.overpass.url:https://overpass-api.de/api/interpreter}")
                    final String overpassUrl,
            @Value("${app.category.overpass.timeout-seconds:5}") final int timeoutSeconds) {
        this.overpassUrl = overpassUrl;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .build();
    }

    @Override
    public CategoryResult lookup(
            final String merchantName,
            final String city,
            final String state,
            final String country) {
        // Caching is handled at the chain level by ChainedLocationLookup
        // via the persistent MerchantEnrichmentStore — no per-source
        // in-process cache here. That avoids double-caching and ensures
        // any successful lookup is durable across pod restarts.
        if (merchantName == null || merchantName.isBlank()) {
            return null;
        }
        try {
            return queryOverpass(merchantName, city, state, country);
        } catch (Exception ex) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(
                        "Overpass lookup failed for '{}' ({}, {}): {}",
                        merchantName, city, state, ex.getMessage());
            }
            return null;
        }
    }

    private CategoryResult queryOverpass(
            final String merchantName,
            final String city,
            final String state,
            final String country) throws Exception {
        // Build an Overpass QL query that finds nodes/ways named like the
        // merchant within an admin area matching the city/state. We use
        // ~name regex (case-insensitive contains) so abbreviations and
        // statement noise tokens don't blow up the match.
        //
        // Example query:
        //   [out:json][timeout:5];
        //   area["name"="Bellevue"]->.searchArea;
        //   ( node["name"~"safeway",i](area.searchArea);
        //     way["name"~"safeway",i](area.searchArea); );
        //   out tags 5;
        final String areaClause = buildAreaClause(city, state, country);
        if (areaClause == null) {
            return null; // can't constrain — refuse to flood Overpass with global queries
        }
        final String nameRegex = escapeOverpass(merchantName);
        final String query =
                "[out:json][timeout:5];"
                        + areaClause
                        + "(node[\"name\"~\"" + nameRegex + "\",i](area.searchArea);"
                        + "way[\"name\"~\"" + nameRegex + "\",i](area.searchArea););"
                        + "out tags 5;";

        final HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(overpassUrl))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("User-Agent", "BudgetBuddy/1.0 (merchant-categorisation)")
                .POST(HttpRequest.BodyPublishers.ofString("data=" + java.net.URLEncoder.encode(query,
                        java.nio.charset.StandardCharsets.UTF_8)))
                .build();

        final HttpResponse<String> resp =
                http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(
                        "Overpass returned status {} for '{}': {}",
                        resp.statusCode(), merchantName, truncate(resp.body(), 200));
            }
            return null;
        }
        final JsonNode root = jsonMapper.readTree(resp.body());
        final JsonNode elements = root.get("elements");
        if (elements == null || !elements.isArray() || elements.size() == 0) {
            return null;
        }
        // Take the first element's tags; OSM Overpass orders by ID but
        // for our purposes any matching name in the right area is fine.
        for (final JsonNode el : elements) {
            final JsonNode tags = el.get("tags");
            if (tags == null) {
                continue;
            }
            final Map<String, String> tagMap = new HashMap<>();
            final Iterator<Map.Entry<String, JsonNode>> it = tags.fields();
            while (it.hasNext()) {
                final Map.Entry<String, JsonNode> e = it.next();
                tagMap.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue().asText());
            }
            final CategoryResult mapped = OsmTagMapper.fromTags(tagMap);
            if (mapped != null) {
                return mapped;
            }
        }
        return null;
    }

    /**
     * Build an Overpass area clause from city/state/country. Returns null
     * when we can't pin location enough to query safely — Overpass
     * doesn't appreciate global name searches and rate-limits them
     * aggressively.
     */
    private static String buildAreaClause(
            final String city, final String state, final String country) {
        if (city != null && !city.isBlank()) {
            return "area[\"name\"=\"" + escapeOverpass(city) + "\"]->.searchArea;";
        }
        if (state != null && !state.isBlank()) {
            return "area[\"name\"=\"" + escapeOverpass(state) + "\"]->.searchArea;";
        }
        return null;
    }

    private static String escapeOverpass(final String s) {
        return s.replace("\"", "").replace("\\", "");
    }

    private static String truncate(final String s, final int n) {
        return s == null || s.length() <= n ? s : s.substring(0, n) + "...";
    }
}
