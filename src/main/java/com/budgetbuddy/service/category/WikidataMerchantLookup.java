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
 * Look up a merchant's category via Wikidata SPARQL — free, no API key,
 * no signup. Wikidata entities tag well-known businesses with industry
 * codes (NAICS, NACE, ISIC) plus {@code instance of} statements
 * ({@code Q43229} business, {@code Q1043234} restaurant chain, etc.) we
 * can map to internal categories.
 *
 * <h3>Coverage</h3>
 *
 * Wikidata covers well-known brands well (Starbucks, Walmart, Carrefour,
 * Lufthansa, etc.) but is sparse for small local businesses. Run AFTER
 * OSM in the chain — OSM has better small-business coverage; Wikidata
 * is the fallback for international chains that OSM misses.
 *
 * <h3>Query shape</h3>
 *
 * <pre>
 *   SELECT ?item ?industryLabel ?instanceLabel WHERE {
 *     ?item rdfs:label "Starbucks"@en ;
 *           wdt:P31 ?instance .
 *     OPTIONAL { ?item wdt:P452 ?industry }
 *     SERVICE wikibase:label { bd:serviceParam wikibase:language "en" }
 *   } LIMIT 5
 * </pre>
 *
 * <h3>Enable</h3>
 *
 * Opt-in via {@code app.category.wikidata.enabled=true}. Off by default;
 * synchronous network calls are a deployment decision.
 */
@Service
@Order(20)
@ConditionalOnProperty(name = "app.category.wikidata.enabled", havingValue = "true")
public class WikidataMerchantLookup implements ChainedLocationLookup.ExternalCategorySource {

    private static final Logger LOGGER = LoggerFactory.getLogger(WikidataMerchantLookup.class);

    /** Maps common Wikidata instance/industry strings to internal categories. */
    private static final Map<String, String> WIKIDATA_LABEL_TO_CATEGORY = Map.ofEntries(
            Map.entry("restaurant chain", "dining"),
            Map.entry("restaurant", "dining"),
            Map.entry("fast food restaurant chain", "dining"),
            Map.entry("fast food restaurant", "dining"),
            Map.entry("coffeehouse chain", "dining"),
            Map.entry("coffeehouse", "dining"),
            Map.entry("cafe", "dining"),
            Map.entry("hotel", "travel"),
            Map.entry("hotel chain", "travel"),
            Map.entry("hotel group", "travel"),
            Map.entry("airline", "travel"),
            Map.entry("low-cost carrier", "travel"),
            Map.entry("flag carrier", "travel"),
            Map.entry("supermarket", "groceries"),
            Map.entry("supermarket chain", "groceries"),
            Map.entry("grocery store", "groceries"),
            Map.entry("hypermarket", "groceries"),
            Map.entry("convenience store", "groceries"),
            Map.entry("department store", "shopping"),
            Map.entry("retail chain", "shopping"),
            Map.entry("clothing retailer", "shopping"),
            Map.entry("clothing brand", "shopping"),
            Map.entry("e-commerce", "shopping"),
            Map.entry("online marketplace", "shopping"),
            Map.entry("petrol station", "transportation"),
            Map.entry("gas station", "transportation"),
            Map.entry("filling station", "transportation"),
            Map.entry("rideshare company", "transportation"),
            Map.entry("car rental company", "transportation"),
            Map.entry("automobile manufacturer", "transportation"),
            Map.entry("pharmacy chain", "health"),
            Map.entry("pharmacy", "health"),
            Map.entry("drugstore", "health"),
            Map.entry("gym chain", "health"),
            Map.entry("fitness club", "health"),
            Map.entry("video streaming service", "tech"),
            Map.entry("streaming service", "tech"),
            Map.entry("subscription music service", "tech"),
            Map.entry("software company", "tech"),
            Map.entry("cloud service provider", "tech"),
            Map.entry("insurance company", "insurance"),
            Map.entry("home improvement retailer", "home improvement"),
            Map.entry("hardware store", "home improvement"),
            Map.entry("furniture retailer", "home improvement"),
            Map.entry("cinema chain", "entertainment"),
            Map.entry("movie theater chain", "entertainment"),
            Map.entry("amusement park", "entertainment"),
            Map.entry("museum", "entertainment")
    );

    private static final double WIKIDATA_CONFIDENCE = 0.85;
    /** How long to skip Wikidata after a 429 rate-limit response. */
    private static final long RATE_LIMIT_COOLDOWN_MS = 60 * 60 * 1000L; // 1 hour

    private final String wikidataUrl;
    private final HttpClient http;
    private final ObjectMapper jsonMapper = new ObjectMapper();
    /**
     * Wall-clock instant (ms since epoch) until which we skip Wikidata
     * because the SPARQL endpoint returned 429. Atomically read/written so
     * concurrent requests don't pile up after a single rate-limit event.
     */
    private final java.util.concurrent.atomic.AtomicLong rateLimitedUntilMs =
            new java.util.concurrent.atomic.AtomicLong();

    public WikidataMerchantLookup(
            @Value("${app.category.wikidata.url:https://query.wikidata.org/sparql}")
                    final String wikidataUrl,
            @Value("${app.category.wikidata.timeout-seconds:5}") final int timeoutSeconds) {
        this.wikidataUrl = wikidataUrl;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .build();
    }

    @Override
    public CategoryResult lookup(
            final String merchantName, final String city, final String state,
            final String country) {
        // Caching handled by ChainedLocationLookup via MerchantEnrichmentStore.
        if (merchantName == null || merchantName.isBlank()) {
            return null;
        }
        // Wikidata public SPARQL is aggressively rate-limited per IP. If we
        // got a 429 recently, skip every subsequent call for an hour so we
        // don't burn the cascade's wall-clock budget on guaranteed-failing
        // queries. The cooldown resets automatically.
        final long until = rateLimitedUntilMs.get();
        if (until > 0 && System.currentTimeMillis() < until) {
            return null;
        }
        try {
            return querySparql(merchantName);
        } catch (Exception ex) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Wikidata lookup failed for '{}': {}", merchantName, ex.getMessage());
            }
            return null;
        }
    }

    private CategoryResult querySparql(final String merchantName) throws Exception {
        final String escaped = merchantName.replace("\"", "\\\"");
        // Look up by English label. Pull both wdt:P31 (instance of) and
        // wdt:P452 (industry) labels — either may carry the categorical signal.
        final String sparql =
                "SELECT ?industryLabel ?instanceLabel WHERE {"
                        + "  ?item rdfs:label \"" + escaped + "\"@en . "
                        + "  OPTIONAL { ?item wdt:P31 ?instance . } "
                        + "  OPTIONAL { ?item wdt:P452 ?industry . } "
                        + "  SERVICE wikibase:label { bd:serviceParam wikibase:language \"en\". } "
                        + "} LIMIT 5";
        final String url =
                wikidataUrl + "?format=json&query="
                        + URLEncoder.encode(sparql, StandardCharsets.UTF_8);
        final HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/sparql-results+json")
                .header("User-Agent", "BudgetBuddy/1.0 (merchant-categorisation)")
                .GET()
                .build();
        final HttpResponse<String> resp =
                http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 429) {
            // Public SPARQL endpoint is rate-limiting. Trip the cooldown so
            // subsequent calls in this hour return null immediately rather
            // than waiting on a 429 round-trip. Logged at INFO since this
            // is operationally meaningful (the L6 chain effectively loses
            // this source until the cooldown expires).
            final long until = System.currentTimeMillis() + RATE_LIMIT_COOLDOWN_MS;
            rateLimitedUntilMs.set(until);
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info(
                        "Wikidata SPARQL returned 429 — skipping for {} minutes",
                        RATE_LIMIT_COOLDOWN_MS / 60_000);
            }
            return null;
        }
        if (resp.statusCode() != 200) {
            return null;
        }
        final JsonNode root = jsonMapper.readTree(resp.body());
        final JsonNode bindings = root.path("results").path("bindings");
        if (!bindings.isArray()) {
            return null;
        }
        for (final JsonNode binding : bindings) {
            final String industryLabel =
                    binding.path("industryLabel").path("value").asText(null);
            final String instanceLabel =
                    binding.path("instanceLabel").path("value").asText(null);
            final String mapped =
                    classify(industryLabel) != null
                            ? classify(industryLabel)
                            : classify(instanceLabel);
            if (mapped != null) {
                return new CategoryResult(
                        mapped, mapped,
                        "WIKIDATA:" + (industryLabel != null ? industryLabel : instanceLabel),
                        WIKIDATA_CONFIDENCE);
            }
        }
        return null;
    }

    private static String classify(final String label) {
        if (label == null || label.isBlank()) {
            return null;
        }
        return WIKIDATA_LABEL_TO_CATEGORY.get(label.toLowerCase(Locale.ROOT).trim());
    }
}
