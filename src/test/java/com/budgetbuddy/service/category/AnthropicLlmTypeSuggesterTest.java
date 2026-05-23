package com.budgetbuddy.service.category;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * E2E coverage of {@link AnthropicLlmTypeSuggester} against a JDK
 * HttpServer that mimics the Anthropic Messages API. Pins:
 *
 * <ul>
 *   <li>Valid response → parsed TypeSuggestion with correct enum + confidence
 *   <li>Hallucinated `type` value (not in the enum) → null
 *   <li>Out-of-range confidence (e.g. 1.5) → null
 *   <li>Non-200 HTTP → null (graceful degrade, caller falls back to base)
 *   <li>Empty api-key → null (no network call)
 * </ul>
 */
class AnthropicLlmTypeSuggesterTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<String> nextBody = new AtomicReference<>("{}");
    private final AtomicReference<Integer> nextStatus = new AtomicReference<>(200);

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            final byte[] body = nextBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(nextStatus.get(), body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/messages";
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static String wrapAnthropic(final String inner) {
        // Anthropic's content[] envelope wrapping a single text block.
        return "{\"content\":[{\"type\":\"text\",\"text\":" + JsonString.q(inner) + "}]}";
    }

    @Test
    void validResponseParsesType() {
        nextBody.set(wrapAnthropic(
                "{\"type\":\"PAYMENT\",\"confidence\":0.92,\"reasoning\":\"explicit CC payment\"}"));
        final var sut = new AnthropicLlmTypeSuggester(baseUrl, "test-key", "model-test", 5);
        final var ctx = new LlmTypeSuggester.TypeContext(
                "Chase Card", "PAYMENT THANK YOU", "credit", "credit card",
                new BigDecimal("-500.00"), "online");
        final var s = sut.suggest(ctx);
        assertNotNull(s);
        assertEquals(LlmTypeSuggester.SuggestedType.PAYMENT, s.type);
        assertEquals(0.92, s.confidence, 0.001);
        assertTrue(s.reasoning.contains("payment"));
    }

    @Test
    void hallucinatedTypeRejected() {
        nextBody.set(wrapAnthropic(
                "{\"type\":\"UNKNOWN\",\"confidence\":0.95,\"reasoning\":\"made up\"}"));
        final var sut = new AnthropicLlmTypeSuggester(baseUrl, "test-key", "m", 5);
        assertNull(sut.suggest(new LlmTypeSuggester.TypeContext(
                "m", "d", "at", null, BigDecimal.ONE, null)));
    }

    @Test
    void outOfRangeConfidenceRejected() {
        nextBody.set(wrapAnthropic(
                "{\"type\":\"EXPENSE\",\"confidence\":1.5,\"reasoning\":\"x\"}"));
        final var sut = new AnthropicLlmTypeSuggester(baseUrl, "test-key", "m", 5);
        assertNull(sut.suggest(new LlmTypeSuggester.TypeContext(
                "m", "d", "at", null, BigDecimal.ONE, null)));
    }

    @Test
    void non200ReturnsNull() {
        nextStatus.set(500);
        nextBody.set("{\"error\":\"oops\"}");
        final var sut = new AnthropicLlmTypeSuggester(baseUrl, "test-key", "m", 5);
        assertNull(sut.suggest(new LlmTypeSuggester.TypeContext(
                "m", "d", "at", null, BigDecimal.ONE, null)));
    }

    @Test
    void emptyApiKeySkipsCall() {
        final var sut = new AnthropicLlmTypeSuggester(baseUrl, "", "m", 5);
        assertNull(sut.suggest(new LlmTypeSuggester.TypeContext(
                "m", "d", "at", null, BigDecimal.ONE, null)));
    }

    /** Tiny JSON-string escaper for embedding text into a JSON envelope. */
    private static final class JsonString {
        static String q(final String s) {
            return "\""
                    + s.replace("\\", "\\\\").replace("\"", "\\\"")
                    + "\"";
        }
    }
}
