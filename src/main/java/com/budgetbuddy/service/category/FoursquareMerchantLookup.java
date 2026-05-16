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
 * Foursquare Places API merchant lookup. Foursquare's category taxonomy
 * is the cleanest of the free / freemium consumer-place APIs — they
 * publish a structured tree where every category has a stable numeric
 * ID (e.g. {@code 13065} = "Pizza Place"). Free tier is 1K requests/day
 * (sufficient for typical bulk-import bursts when combined with the
 * persistent {@link MerchantEnrichmentStore}).
 *
 * <h3>Activation</h3>
 *
 * Opt-in via {@code app.category.foursquare.enabled=true} +
 * {@code app.category.foursquare.api-key=<key>}. Foursquare requires an
 * API key — sign up at https://developer.foursquare.com (free).
 *
 * <h3>Endpoint</h3>
 *
 * {@code GET https://api.foursquare.com/v3/places/search?query={name}&near={city,state}}
 * Returns up to 10 places; we take the first match with a known
 * top-level category and map to internal.
 */
@Service
@Order(30)
@ConditionalOnProperty(name = "app.category.foursquare.enabled", havingValue = "true")
public class FoursquareMerchantLookup implements ChainedLocationLookup.ExternalCategorySource {

    private static final Logger LOGGER = LoggerFactory.getLogger(FoursquareMerchantLookup.class);
    private static final double FSQ_CONFIDENCE = 0.90;

    /**
     * Map Foursquare's top-level category IDs (their <code>level=1</code>
     * categories) to internal category names. Full list:
     * https://docs.foursquare.com/data-products/docs/categories
     */
    private static final Map<String, String> FSQ_CATEGORY_TO_INTERNAL = Map.ofEntries(
            Map.entry("Dining and Drinking", "dining"),
            Map.entry("Food", "dining"),
            Map.entry("Restaurant", "dining"),
            Map.entry("Cafes, Coffee, and Tea Houses", "dining"),
            Map.entry("Coffee Shop", "dining"),
            Map.entry("Bars", "dining"),
            Map.entry("Retail", "shopping"),
            Map.entry("Department Store", "shopping"),
            Map.entry("Clothing Store", "shopping"),
            Map.entry("Electronics Store", "tech"),
            Map.entry("Grocery Store", "groceries"),
            Map.entry("Supermarket", "groceries"),
            Map.entry("Convenience Store", "groceries"),
            Map.entry("Hotel", "travel"),
            Map.entry("Lodging", "travel"),
            Map.entry("Resort", "travel"),
            Map.entry("Bed and Breakfast", "travel"),
            Map.entry("Hostel", "travel"),
            Map.entry("Gas Station", "transportation"),
            Map.entry("Service Station", "transportation"),
            Map.entry("Automotive Service", "transportation"),
            Map.entry("Travel and Transportation", "transportation"),
            Map.entry("Airport", "travel"),
            Map.entry("Pharmacy", "health"),
            Map.entry("Drugstore", "health"),
            Map.entry("Medical Center", "health"),
            Map.entry("Hospital", "health"),
            Map.entry("Fitness", "health"),
            Map.entry("Gym", "health"),
            Map.entry("Salon / Barbershop", "health"),
            Map.entry("Spa", "health"),
            Map.entry("Movie Theater", "entertainment"),
            Map.entry("Theater", "entertainment"),
            Map.entry("Amusement Park", "entertainment"),
            Map.entry("Museum", "entertainment"),
            Map.entry("Arts and Entertainment", "entertainment"),
            Map.entry("Home Improvement", "home improvement"),
            Map.entry("Furniture Store", "home improvement"),
            Map.entry("Pet Store", "pet"),
            Map.entry("Veterinarian", "pet"),
            Map.entry("School", "education"),
            Map.entry("College and University", "education"),
            Map.entry("Library", "education")
    );

    private final String apiUrl;
    private final String apiKey;
    private final HttpClient http;
    private final ObjectMapper jsonMapper = new ObjectMapper();

    public FoursquareMerchantLookup(
            @Value("${app.category.foursquare.url:https://api.foursquare.com/v3/places/search}")
                    final String apiUrl,
            @Value("${app.category.foursquare.api-key:}") final String apiKey,
            @Value("${app.category.foursquare.timeout-seconds:5}") final int timeoutSeconds) {
        this.apiUrl = apiUrl;
        this.apiKey = apiKey;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .build();
        if (apiKey == null || apiKey.isBlank()) {
            LOGGER.warn(
                    "FoursquareMerchantLookup activated but app.category.foursquare.api-key is "
                            + "empty — lookups will skip");
        }
    }

    @Override
    public CategoryResult lookup(
            final String merchantName, final String city, final String state,
            final String country) {
        // Caching handled by ChainedLocationLookup via MerchantEnrichmentStore.
        if (apiKey == null || apiKey.isBlank()
                || merchantName == null || merchantName.isBlank()) {
            return null;
        }
        // Need at least a location to constrain the search; Foursquare
        // expects "city, state" or geocoords.
        final String near = buildNear(city, state, country);
        if (near == null) {
            return null;
        }
        try {
            return query(merchantName, near);
        } catch (Exception ex) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(
                        "Foursquare lookup failed for '{}' near '{}': {}",
                        merchantName, near, ex.getMessage());
            }
            return null;
        }
    }

    private CategoryResult query(final String merchantName, final String near) throws Exception {
        final String url =
                apiUrl
                        + "?query=" + URLEncoder.encode(merchantName, StandardCharsets.UTF_8)
                        + "&near=" + URLEncoder.encode(near, StandardCharsets.UTF_8)
                        + "&limit=5";
        final HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", apiKey)
                .header("Accept", "application/json")
                .GET()
                .build();
        final HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            return null;
        }
        final JsonNode root = jsonMapper.readTree(resp.body());
        final JsonNode results = root.path("results");
        if (!results.isArray()) {
            return null;
        }
        for (final JsonNode place : results) {
            final JsonNode categories = place.path("categories");
            if (!categories.isArray()) {
                continue;
            }
            for (final JsonNode cat : categories) {
                final String name = cat.path("name").asText(null);
                if (name == null) {
                    continue;
                }
                final String mapped = FSQ_CATEGORY_TO_INTERNAL.get(name);
                if (mapped != null) {
                    return new CategoryResult(
                            mapped, mapped, "FOURSQUARE:" + name, FSQ_CONFIDENCE);
                }
            }
        }
        return null;
    }

    private static String buildNear(final String city, final String state, final String country) {
        if (city != null && !city.isBlank() && state != null && !state.isBlank()) {
            return city + ", " + state;
        }
        if (city != null && !city.isBlank()) {
            return city;
        }
        return null;
    }
}
