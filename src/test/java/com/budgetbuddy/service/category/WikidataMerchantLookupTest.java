package com.budgetbuddy.service.category;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.budgetbuddy.service.TransactionTypeCategoryService.CategoryResult;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the live-network surface of {@link WikidataMerchantLookup} using
 * a JDK in-process HTTP server — no WireMock dependency, no real Wikidata
 * round-trip. Pinned behaviours:
 *
 * <ul>
 *   <li>Successful SPARQL response with an {@code instanceLabel} matching
 *       the local taxonomy returns the right category.
 *   <li>A 429 (Too Many Requests) response trips a cooldown so subsequent
 *       calls return null IMMEDIATELY without re-hitting the network.
 *   <li>200 response with no matching label returns null but does NOT
 *       trip the cooldown (so we keep trying other merchants).
 *   <li>Empty or malformed SPARQL JSON returns null without throwing.
 * </ul>
 */
class WikidataMerchantLookupTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicInteger requestCount = new AtomicInteger();
    private final AtomicReference<Integer> nextStatusCode =
            new AtomicReference<>(200);
    private final AtomicReference<String> nextBody =
            new AtomicReference<>("{}");

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/sparql", exchange -> {
            requestCount.incrementAndGet();
            final int status = nextStatusCode.get();
            final byte[] body = nextBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add(
                    "Content-Type", "application/sparql-results+json");
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/sparql";
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    @DisplayName("Successful SPARQL response with restaurant chain → dining")
    void successfulResponseReturnsCategory() {
        nextStatusCode.set(200);
        nextBody.set(
                "{\"results\":{\"bindings\":["
                        + "{\"instanceLabel\":{\"value\":\"restaurant chain\"}}"
                        + "]}}");
        WikidataMerchantLookup lookup = new WikidataMerchantLookup(baseUrl, 5);

        CategoryResult r = lookup.lookup("Starbucks", null, null, "US");

        assertNotNull(r, "successful SPARQL result with mapped label should return CategoryResult");
        assertEquals("dining", r.getCategoryPrimary());
        assertTrue(r.getSource().startsWith("WIKIDATA:"),
                "provenance should record the matched label, got " + r.getSource());
    }

    @Test
    @DisplayName("429 response trips cooldown — subsequent calls skip the network")
    void rateLimitTripsCooldown() {
        nextStatusCode.set(429);
        nextBody.set("");
        WikidataMerchantLookup lookup = new WikidataMerchantLookup(baseUrl, 5);

        // First call hits the server and gets 429 back.
        CategoryResult first = lookup.lookup("Starbucks", null, null, "US");
        assertNull(first);
        int requestsAfterFirst = requestCount.get();
        assertEquals(1, requestsAfterFirst, "first call must reach the server");

        // Second call should be short-circuited by the cooldown — no HTTP traffic.
        CategoryResult second = lookup.lookup("Walmart", null, null, "US");
        assertNull(second);
        assertEquals(requestsAfterFirst, requestCount.get(),
                "during cooldown, subsequent calls MUST NOT touch the server "
                        + "(would burn the per-source budget on guaranteed-failing calls)");

        // Third call for yet another merchant — still skipped.
        lookup.lookup("Costco", null, null, "US");
        assertEquals(requestsAfterFirst, requestCount.get(),
                "cooldown applies process-wide, not per-merchant");
    }

    @Test
    @DisplayName("200 with no matching label → null but cooldown NOT tripped")
    void noMatchDoesNotTripCooldown() {
        nextStatusCode.set(200);
        nextBody.set("{\"results\":{\"bindings\":[]}}");
        WikidataMerchantLookup lookup = new WikidataMerchantLookup(baseUrl, 5);

        assertNull(lookup.lookup("UnknownMerchant", null, null, "US"));
        assertEquals(1, requestCount.get());

        // Second call must still reach the server (cooldown only trips on 429,
        // not on legitimate empty results).
        assertNull(lookup.lookup("AnotherMerchant", null, null, "US"));
        assertEquals(2, requestCount.get(),
                "empty results should not trip cooldown — keep trying other merchants");
    }

    @Test
    @DisplayName("Malformed JSON response → null, no throw")
    void malformedJsonReturnsNull() {
        nextStatusCode.set(200);
        nextBody.set("not even json");
        WikidataMerchantLookup lookup = new WikidataMerchantLookup(baseUrl, 5);

        // Should not throw — categorisation must tolerate upstream garbage.
        assertNull(lookup.lookup("Whatever", null, null, "US"));
    }

    @Test
    @DisplayName("Null/blank merchant short-circuits — no HTTP call")
    void emptyMerchantSkipsNetwork() {
        WikidataMerchantLookup lookup = new WikidataMerchantLookup(baseUrl, 5);
        assertNull(lookup.lookup(null, null, null, null));
        assertNull(lookup.lookup("   ", null, null, null));
        assertEquals(0, requestCount.get(),
                "empty inputs MUST NOT generate network traffic");
    }

    @Test
    @DisplayName("500 server error → null, cooldown NOT tripped")
    void serverErrorReturnsNullKeepsTrying() {
        nextStatusCode.set(500);
        nextBody.set("internal error");
        WikidataMerchantLookup lookup = new WikidataMerchantLookup(baseUrl, 5);

        assertNull(lookup.lookup("M1", null, null, "US"));
        assertEquals(1, requestCount.get());

        nextStatusCode.set(500);
        assertNull(lookup.lookup("M2", null, null, "US"));
        assertEquals(2, requestCount.get(),
                "5xx must not trip the 429-specific cooldown");
    }
}
