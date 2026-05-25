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
        // Nominatim's free-text search rewards SHORT queries. Passing all
        // four fields ("merchant, city, state, country") produces empty
        // results because the matcher is over-constrained. Empirically
        // "merchant, city" (or "merchant, state" when city is missing)
        // returns the most hits. Only fall back to a longer query when
        // the merchant token is very short (≤4 chars) and could be
        // ambiguous without the state code.
        final StringBuilder q = new StringBuilder(merchantName);
        if (city != null && !city.isBlank()) {
            q.append(", ").append(city);
            if (merchantName.length() <= 4 && state != null && !state.isBlank()) {
                q.append(", ").append(state);
            }
        } else if (state != null && !state.isBlank()) {
            q.append(", ").append(state);
        }
        // Country-aware: for non-US merchants, include the country in the
        // query so Nominatim doesn't default-pick the US match. Also pass
        // the official countrycodes= filter for hard scoping (ISO alpha-2).
        // For US merchants we leave countrycodes= off — adding it doesn't
        // help and slightly slows the query.
        final String countryCode = normalizeCountryToAlpha2(country);
        if (countryCode != null && !"US".equals(countryCode)) {
            q.append(", ").append(countryCode);
        }
        final String url =
                apiUrl
                        + "?q=" + URLEncoder.encode(q.toString(), StandardCharsets.UTF_8)
                        + (countryCode != null && !"US".equals(countryCode)
                                ? "&countrycodes="
                                        + countryCode.toLowerCase(java.util.Locale.ROOT)
                                : "")
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
                // Jackson 2.21 deprecated JsonNode.fields() in favour of
                // properties() (Set<Map.Entry> rather than Iterator).
                for (final Map.Entry<String, JsonNode> e : extra.properties()) {
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

    /**
     * Normalise a country input (could be ISO alpha-2 "GB", alpha-3 "GBR",
     * or a country name "United Kingdom") into the alpha-2 form Nominatim
     * expects for {@code countrycodes=}. Returns null for blank or
     * unrecognised inputs (caller then leaves the country off the query).
     *
     * <p>The set below mirrors what
     * {@link com.budgetbuddy.service.ml.MerchantLocationSplitter}
     * recognises so the lookup chain stays consistent with the upstream
     * splitter's country detection.
     */
    private static String normalizeCountryToAlpha2(final String country) {
        if (country == null) return null;
        final String s = country.trim().toUpperCase(Locale.ROOT);
        if (s.isEmpty()) return null;
        // Already alpha-2
        if (s.length() == 2) return s;
        // Alpha-3 → alpha-2 mapping for the codes the splitter recognises.
        // Only the most common ones — Nominatim is forgiving for others.
        switch (s) {
            case "USA": return "US";
            case "GBR": return "GB";
            case "UK":  return "GB";
            case "CAN": return "CA";
            case "AUS": return "AU";
            case "DEU": return "DE";
            case "FRA": return "FR";
            case "ITA": return "IT";
            case "ESP": return "ES";
            case "NLD": return "NL";
            case "IND": return "IN";
            case "SGP": return "SG";
            case "JPN": return "JP";
            case "CHN": return "CN";
            case "KOR": return "KR";
            case "BRA": return "BR";
            case "MEX": return "MX";
            case "NZL": return "NZ";
            case "IRL": return "IE";
            case "CHE": return "CH";
            case "SWE": return "SE";
            case "NOR": return "NO";
            case "DNK": return "DK";
            case "FIN": return "FI";
            case "BEL": return "BE";
            case "AUT": return "AT";
            case "POL": return "PL";
            case "CZE": return "CZ";
            case "PRT": return "PT";
            case "GRC": return "GR";
            case "ZAF": return "ZA";
            case "ARE": return "AE";
            case "SAU": return "SA";
            case "QAT": return "QA";
            case "ISR": return "IL";
            case "TUR": return "TR";
            case "HKG": return "HK";
            case "TWN": return "TW";
            case "THA": return "TH";
            case "VNM": return "VN";
            case "IDN": return "ID";
            case "MYS": return "MY";
            case "PHL": return "PH";
            case "ARG": return "AR";
            case "CHL": return "CL";
            case "COL": return "CO";
            case "PER": return "PE";
            // Country names → alpha-2 (a few common variants)
            case "UNITED STATES":    return "US";
            case "UNITED KINGDOM":   return "GB";
            case "INDIA":            return "IN";
            case "JAPAN":            return "JP";
            case "CANADA":           return "CA";
            case "AUSTRALIA":        return "AU";
            case "GERMANY":          return "DE";
            case "FRANCE":           return "FR";
            default: return null;
        }
    }
}
