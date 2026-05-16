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
 * Look up a merchant's category via OpenCorporates — the world's largest
 * open database of companies. Returns SIC codes (Standard Industrial
 * Classification, ISO 3166-2) we can map to internal categories.
 *
 * <h3>Coverage</h3>
 *
 * Best for registered businesses with public filings (US, UK, EU). Less
 * useful for sole proprietors and small local shops. Slots in AFTER
 * OSM/Nominatim in the chain because OSM is better for "is this a
 * cafe?" while OpenCorporates is better for "is BLACKROCK INC an
 * investment firm?".
 *
 * <h3>API key</h3>
 *
 * OpenCorporates' free tier permits limited unauthenticated calls but
 * recommends an API token for production. Configure via
 * {@code app.category.opencorporates.api-token=<token>}. Without one
 * the service still works at lower rate limits.
 */
@Service
@Order(40)
@ConditionalOnProperty(name = "app.category.opencorporates.enabled", havingValue = "true")
public class OpenCorporatesMerchantLookup
        implements ChainedLocationLookup.ExternalCategorySource {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(OpenCorporatesMerchantLookup.class);
    private static final double OC_CONFIDENCE = 0.86;

    /**
     * Coarse mapping from SIC-code prefixes / industry text to internal
     * category. SIC codes are 4 digits with hierarchical meaning (first
     * 2 digits = major group). Sources: US SIC and UK SIC 2007.
     */
    private static final Map<String, String> SIC_PREFIX_TO_CATEGORY = Map.ofEntries(
            // Retail / shopping (52, 53, 54)
            Map.entry("52", "shopping"),
            Map.entry("53", "shopping"),
            Map.entry("54", "shopping"),
            // Food retail (5411 etc.)
            Map.entry("5411", "groceries"),
            Map.entry("5499", "groceries"),
            // Restaurants (5812)
            Map.entry("5812", "dining"),
            Map.entry("5813", "dining"),
            // Hotels (70)
            Map.entry("70", "travel"),
            Map.entry("7011", "travel"),
            // Travel agencies (4724)
            Map.entry("4724", "travel"),
            // Airlines (4512)
            Map.entry("4512", "travel"),
            // Auto / gas (55)
            Map.entry("5541", "transportation"),
            Map.entry("55", "transportation"),
            // Healthcare (80)
            Map.entry("80", "health"),
            Map.entry("8011", "health"),
            Map.entry("8021", "health"),
            // Insurance (63, 64)
            Map.entry("63", "insurance"),
            Map.entry("64", "insurance"),
            Map.entry("6311", "insurance"),
            // Education (82)
            Map.entry("82", "education"),
            // Entertainment (78, 79)
            Map.entry("78", "entertainment"),
            Map.entry("79", "entertainment"),
            // Utilities (49)
            Map.entry("49", "utilities"),
            // Telecom (48)
            Map.entry("48", "utilities"),
            // Charity / non-profit (8399)
            Map.entry("8399", "charity"),
            // Software / tech (737, 7372)
            Map.entry("737", "tech"),
            Map.entry("7372", "tech")
    );

    private final String apiUrl;
    private final String apiToken;
    private final HttpClient http;
    private final ObjectMapper jsonMapper = new ObjectMapper();

    public OpenCorporatesMerchantLookup(
            @Value("${app.category.opencorporates.url:https://api.opencorporates.com/v0.4/companies/search}")
                    final String apiUrl,
            @Value("${app.category.opencorporates.api-token:}") final String apiToken,
            @Value("${app.category.opencorporates.timeout-seconds:5}") final int timeoutSeconds) {
        this.apiUrl = apiUrl;
        this.apiToken = apiToken;
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
        try {
            return query(merchantName, country);
        } catch (Exception ex) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(
                        "OpenCorporates lookup failed for '{}': {}",
                        merchantName, ex.getMessage());
            }
            return null;
        }
    }

    private CategoryResult query(final String merchantName, final String country) throws Exception {
        final StringBuilder url = new StringBuilder(apiUrl)
                .append("?q=").append(URLEncoder.encode(merchantName, StandardCharsets.UTF_8))
                .append("&per_page=5");
        if (country != null && !country.isBlank()) {
            url.append("&country_code=")
                    .append(URLEncoder.encode(country.toLowerCase(Locale.ROOT),
                            StandardCharsets.UTF_8));
        }
        if (apiToken != null && !apiToken.isBlank()) {
            url.append("&api_token=").append(URLEncoder.encode(apiToken, StandardCharsets.UTF_8));
        }
        final HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url.toString()))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", "BudgetBuddy/1.0 (merchant-categorisation)")
                .header("Accept", "application/json")
                .GET()
                .build();
        final HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            return null;
        }
        final JsonNode root = jsonMapper.readTree(resp.body());
        final JsonNode companies = root.path("results").path("companies");
        if (!companies.isArray()) {
            return null;
        }
        for (final JsonNode entry : companies) {
            final JsonNode company = entry.path("company");
            // industry_codes is an array of {industry_code: {code, scheme_name, description}}
            final JsonNode industryCodes = company.path("industry_codes");
            if (!industryCodes.isArray()) {
                continue;
            }
            for (final JsonNode wrapper : industryCodes) {
                final String code = wrapper.path("industry_code").path("code").asText(null);
                if (code == null || code.isBlank()) {
                    continue;
                }
                final String mapped = lookupByCode(code);
                if (mapped != null) {
                    return new CategoryResult(
                            mapped, mapped,
                            "OPENCORPORATES_SIC:" + code,
                            OC_CONFIDENCE);
                }
            }
        }
        return null;
    }

    /** Lookup a SIC code by longest matching prefix in the table. */
    private static String lookupByCode(final String code) {
        // Try 4-digit, then 3-digit, then 2-digit prefix.
        for (int len = Math.min(4, code.length()); len >= 2; len--) {
            final String prefix = code.substring(0, len);
            final String mapped = SIC_PREFIX_TO_CATEGORY.get(prefix);
            if (mapped != null) {
                return mapped;
            }
        }
        return null;
    }
}
