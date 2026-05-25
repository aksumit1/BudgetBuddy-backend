package com.budgetbuddy.service.category;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.budgetbuddy.service.TransactionTypeCategoryService.CategoryResult;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the live-network surface of {@link NominatimMerchantLookup}
 * with focus on the query-shape rule introduced in the recent fix:
 *
 * <ul>
 *   <li>Long merchant (> 4 chars) + city → query is "{merchant}, {city}"
 *       (state and country are intentionally dropped — they over-constrain
 *       Nominatim's free-text search and produce empty results).
 *   <li>Short merchant (≤ 4 chars) + city + state → state IS included
 *       (short merchants need disambiguation).
 *   <li>Missing city, present state → query falls back to
 *       "{merchant}, {state}".
 *   <li>Successful response with class/type maps via OsmTagMapper.
 *   <li>landuse=retail in extratags now maps to shopping (post-expansion).
 * </ul>
 */
class NominatimMerchantLookupTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<String> lastQ = new AtomicReference<>();
    private final AtomicReference<String> lastRawQuery = new AtomicReference<>();
    private final AtomicReference<String> nextBody =
            new AtomicReference<>("[]");

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/search", exchange -> {
            // Capture both the q= parameter and the raw query so tests
            // can assert the country-aware extra params.
            final String rawQuery = exchange.getRequestURI().getQuery();
            lastRawQuery.set(rawQuery);
            String q = null;
            if (rawQuery != null) {
                for (String pair : rawQuery.split("&")) {
                    final int eq = pair.indexOf('=');
                    if (eq > 0 && pair.substring(0, eq).equals("q")) {
                        q = URLDecoder.decode(
                                pair.substring(eq + 1), StandardCharsets.UTF_8);
                        break;
                    }
                }
            }
            lastQ.set(q);
            final byte[] body = nextBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/search";
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    @DisplayName("Long merchant + city + state → query is just 'merchant, city' (state dropped)")
    void longMerchantDropsState() {
        nextBody.set("[]");
        NominatimMerchantLookup lookup = newLookup();
        lookup.lookup("Whole Foods Market", "Bellevue", "WA", "US");
        assertEquals("Whole Foods Market, Bellevue", lastQ.get(),
                "Long merchant name must NOT include state (over-constrains the matcher)");
    }

    @Test
    @DisplayName("Short merchant (≤4 chars) + city + state → query includes state")
    void shortMerchantKeepsStateForDisambiguation() {
        nextBody.set("[]");
        NominatimMerchantLookup lookup = newLookup();
        lookup.lookup("CVS", "Bellevue", "WA", "US");
        assertEquals("CVS, Bellevue, WA", lastQ.get(),
                "Short 3-letter brand needs state disambiguation");
    }

    @Test
    @DisplayName("Missing city + present state → query is 'merchant, state'")
    void missingCityFallsBackToState() {
        nextBody.set("[]");
        NominatimMerchantLookup lookup = newLookup();
        lookup.lookup("Whole Foods", null, "WA", "US");
        assertEquals("Whole Foods, WA", lastQ.get(),
                "Without a city, the state is the next-best disambiguator");
    }

    @Test
    @DisplayName("Successful response: class=amenity, type=cafe → dining")
    void successfulCafeResponse() {
        nextBody.set(
                "[{\"class\":\"amenity\",\"type\":\"cafe\","
                        + "\"name\":\"Starbucks\",\"extratags\":null}]");
        NominatimMerchantLookup lookup = newLookup();
        CategoryResult r = lookup.lookup("Starbucks", "Seattle", "WA", "US");
        assertNotNull(r);
        assertEquals("dining", r.getCategoryPrimary());
        assertTrue(r.getSource().contains("amenity=cafe"));
    }

    @Test
    @DisplayName("landuse=retail in response → shopping (post-OsmTagMapper expansion)")
    void landuseRetailMapsToShopping() {
        nextBody.set(
                "[{\"class\":\"landuse\",\"type\":\"retail\","
                        + "\"name\":\"Whole Foods Market\",\"extratags\":null}]");
        NominatimMerchantLookup lookup = newLookup();
        CategoryResult r = lookup.lookup("Whole Foods", "Bellevue", "WA", "US");
        assertNotNull(r,
                "landuse=retail row must be mapped now that OsmTagMapper covers landuse");
        assertEquals("shopping", r.getCategoryPrimary());
    }

    @Test
    @DisplayName("Empty result list → null (no match)")
    void emptyResultsReturnNull() {
        nextBody.set("[]");
        NominatimMerchantLookup lookup = newLookup();
        assertNull(lookup.lookup("UnknownLocalShop", "Bellevue", "WA", "US"));
    }

    @Test
    @DisplayName("Malformed JSON → null, no throw")
    void malformedJsonIsSafe() {
        nextBody.set("not json");
        NominatimMerchantLookup lookup = newLookup();
        assertNull(lookup.lookup("Anything", "Bellevue", "WA", "US"));
    }

    @Test
    @DisplayName("Null/blank merchant → null without any HTTP call")
    void blankMerchantShortCircuits() {
        NominatimMerchantLookup lookup = newLookup();
        assertNull(lookup.lookup(null, "Bellevue", "WA", "US"));
        assertNull(lookup.lookup("   ", "Bellevue", "WA", "US"));
        assertNull(lastQ.get(), "no HTTP call should have been made");
    }

    @Test
    @DisplayName("Non-US merchant: country alpha-2 appended to query + countrycodes= filter")
    void nonUsCountryAddedToQueryAndFilter() {
        nextBody.set("[]");
        NominatimMerchantLookup lookup = newLookup();
        lookup.lookup("Pret A Manger", "London", null, "GB");
        assertEquals("Pret A Manger, London, GB", lastQ.get(),
                "Non-US country code must be included in the q= param");
        assertTrue(lastUrlContainsCountryCodes("gb"),
                "Non-US lookups must pass countrycodes= filter to Nominatim");
    }

    @Test
    @DisplayName("US merchant: country NOT appended (queries are simpler + faster)")
    void usCountryStaysOff() {
        nextBody.set("[]");
        NominatimMerchantLookup lookup = newLookup();
        lookup.lookup("Whole Foods", "Bellevue", "WA", "US");
        assertEquals("Whole Foods, Bellevue", lastQ.get(),
                "US lookups should NOT append the country (default behaviour)");
        assertFalse(lastUrlContainsCountryCodes("us"),
                "US lookups don't need countrycodes= — Nominatim picks correctly without it");
    }

    @Test
    @DisplayName("Alpha-3 country code is normalised to alpha-2 (GBR → GB)")
    void alpha3CountryNormalised() {
        nextBody.set("[]");
        NominatimMerchantLookup lookup = newLookup();
        lookup.lookup("Pret A Manger", "London", null, "GBR");
        assertEquals("Pret A Manger, London, GB", lastQ.get(),
                "GBR alpha-3 must be normalised to GB alpha-2 for Nominatim");
    }

    @Test
    @DisplayName("Indian merchant lookup carries IN country code")
    void indiaCountryCode() {
        nextBody.set("[]");
        NominatimMerchantLookup lookup = newLookup();
        lookup.lookup("Haldiram", "Delhi", null, "IND");
        assertEquals("Haldiram, Delhi, IN", lastQ.get());
        assertTrue(lastUrlContainsCountryCodes("in"));
    }

    /**
     * Helper: did the captured raw query string contain a
     * {@code countrycodes=<value>} parameter (case-insensitive)?
     */
    private boolean lastUrlContainsCountryCodes(final String expected) {
        final String raw = lastRawQuery.get();
        if (raw == null) return false;
        return raw.toLowerCase(java.util.Locale.ROOT)
                .contains("countrycodes=" + expected.toLowerCase(java.util.Locale.ROOT));
    }

    private NominatimMerchantLookup newLookup() {
        return new NominatimMerchantLookup(baseUrl, "BudgetBuddy/test", 5);
    }
}
