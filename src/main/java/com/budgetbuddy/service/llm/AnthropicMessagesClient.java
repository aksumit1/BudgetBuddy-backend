package com.budgetbuddy.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thin shared client for the Anthropic Messages API. Used by every
 * suggester (LlmType / LlmSubscription / LlmAlternative / LlmTrialEnd /
 * the existing AnthropicLlmCategorySuggester) so they all share one
 * transport, one timeout, one retry policy, one telemetry surface.
 *
 * <p>Not a Spring service — instances are constructed by each suggester
 * with its own API key + model + system prompt. The suggester layer is
 * where prompt design lives; this class only owns HTTP + JSON envelope
 * handling.
 *
 * <h3>Hallucination guard</h3>
 *
 * Each suggester whitelist-checks the model's structured output. This
 * class doesn't enforce that — it just returns the raw JSON object for
 * the suggester to parse.
 *
 * <h3>Failure modes</h3>
 *
 * Returns null on:
 *   - blank API key (graceful degrade in dev/local)
 *   - non-200 response
 *   - network / parse failure
 *
 * The caller's behaviour on null is "fall back to the deterministic
 * path" — never blocking the user request.
 */
public final class AnthropicMessagesClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(AnthropicMessagesClient.class);
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private final String apiUrl;
    private final String apiKey;
    private final String model;
    private final String systemPrompt;
    private final int maxTokens;
    private final HttpClient http;
    private final ObjectMapper jsonMapper = new ObjectMapper();

    public AnthropicMessagesClient(
            final String apiUrl,
            final String apiKey,
            final String model,
            final String systemPrompt,
            final int maxTokens,
            final int connectTimeoutSeconds) {
        this.apiUrl = apiUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.systemPrompt = systemPrompt;
        this.maxTokens = maxTokens;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
                .build();
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * Send a single user-message prompt; return the first text block from
     * the response (the model's reply) as a string. Returns null on any
     * failure path so callers can degrade gracefully.
     */
    public String complete(final String userPrompt) {
        if (!isConfigured() || userPrompt == null || userPrompt.isBlank()) {
            return null;
        }
        try {
            final ObjectNode root = jsonMapper.createObjectNode();
            root.put("model", model);
            root.put("max_tokens", maxTokens);
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                root.put("system", systemPrompt);
            }
            final ArrayNode messages = root.putArray("messages");
            final ObjectNode userMsg = messages.addObject();
            userMsg.put("role", "user");
            userMsg.put("content", userPrompt);
            final HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .timeout(Duration.ofSeconds(20))
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", ANTHROPIC_VERSION)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            jsonMapper.writeValueAsString(root), StandardCharsets.UTF_8))
                    .build();
            final HttpResponse<String> resp =
                    http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                LOGGER.warn("Anthropic returned status {}: {}",
                        resp.statusCode(), truncate(resp.body()));
                return null;
            }
            return extractFirstTextBlock(resp.body());
        } catch (Exception ex) {
            LOGGER.debug("Anthropic complete failed: {}", ex.getMessage());
            return null;
        }
    }

    /**
     * Extract the first text-block content from a Messages-API response.
     * The Anthropic response shape is:
     *   {"content": [{"type":"text","text":"…"}], …}
     */
    private String extractFirstTextBlock(final String body) throws Exception {
        final JsonNode root = jsonMapper.readTree(body);
        final JsonNode content = root.path("content");
        if (!content.isArray() || content.isEmpty()) return null;
        for (final JsonNode block : content) {
            final String type = block.path("type").asText("");
            if ("text".equals(type)) {
                final String text = block.path("text").asText(null);
                if (text != null && !text.isBlank()) return text;
            }
        }
        return null;
    }

    /**
     * Convenience: extract a JSON object from a model reply. Models
     * occasionally wrap JSON in ```json fences or add prose; this finds
     * the first {…} substring and parses it.
     */
    public JsonNode parseEmbeddedJson(final String reply) {
        if (reply == null || reply.isBlank()) return null;
        final int start = reply.indexOf('{');
        final int end = reply.lastIndexOf('}');
        if (start < 0 || end < start) return null;
        try {
            return jsonMapper.readTree(reply.substring(start, end + 1));
        } catch (Exception ex) {
            LOGGER.debug("Embedded-JSON parse failed: {}", ex.getMessage());
            return null;
        }
    }

    private static String truncate(final String s) {
        if (s == null) return "";
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }
}
