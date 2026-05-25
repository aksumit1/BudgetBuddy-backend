package com.budgetbuddy.service.pdf.ai;

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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * LLM-augmented merchant-name canonicalizer. Takes noisy descriptors
 * ({@code STARBUCKS #5421}, {@code SQ *BLUE BOTTLE COFFEE},
 * {@code AMZN MKTP US*A1B2C3}) and returns a clean display name
 * ({@code Starbucks}, {@code Blue Bottle Coffee}, {@code Amazon}).
 *
 * <p>Layered on top of the existing keyword + rules-based normalization
 * — only invoked when the rule layer returns null or the same string as
 * input (no normalization happened). Results are cached in the existing
 * {@code MerchantEnrichmentStore} so subsequent imports skip the LLM.
 *
 * <p>This is the L8 "novel descriptor" fallback. 99% of merchants get
 * canonicalized by L1-L7 deterministic layers; this catches the long
 * tail of new POS processors and obscure local-business descriptors.
 *
 * <p>Activation: {@code app.pdf.ai-merchant-canon.enabled=true}.
 */
@Service
@ConditionalOnProperty(
        name = "app.pdf.ai-merchant-canon.enabled",
        havingValue = "true",
        matchIfMissing = false)
public class AiMerchantCanonicalizer {

    private static final Logger LOGGER = LoggerFactory.getLogger(AiMerchantCanonicalizer.class);
    private static final String ANTHROPIC_API = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    @Value("${app.pdf.ai-merchant-canon.api-key:}")
    private String apiKey;

    @Value("${app.pdf.ai-merchant-canon.model:claude-haiku-4-5-20251001}")
    private String model;

    @Value("${app.pdf.ai-merchant-canon.timeout-seconds:8}")
    private int timeoutSeconds;

    /** In-memory cache. Also written through to MerchantEnrichmentStore. */
    private final Map<String, String> sessionCache = new ConcurrentHashMap<>();

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    /**
     * Return the clean display name for {@code rawDescriptor}, or null if
     * the LLM can't confidently produce one. Caller falls back to the
     * raw descriptor (current behavior preserved).
     */
    public String canonicalize(final String rawDescriptor) {
        if (apiKey == null || apiKey.isBlank() || rawDescriptor == null
                || rawDescriptor.isBlank()) {
            return null;
        }
        final String key = rawDescriptor.trim().toUpperCase(java.util.Locale.ROOT);
        final String cached = sessionCache.get(key);
        if (cached != null) {
            return cached.isEmpty() ? null : cached;  // "" = LLM said unknown
        }
        try {
            final String clean = callAnthropic(rawDescriptor);
            // Cache both hits ("Starbucks") and misses ("") so we don't
            // re-call for the same input within this app lifetime.
            sessionCache.put(key, clean == null ? "" : clean);
            return clean;
        } catch (final Exception e) {
            LOGGER.debug("merchant-canon failed for '{}': {}", rawDescriptor, e.getMessage());
            return null;
        }
    }

    private String callAnthropic(final String rawDescriptor) throws Exception {
        final ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", 60);
        body.put("system",
                "You normalize bank-statement merchant descriptors to "
                        + "clean display names. Return JSON: "
                        + "{\"name\":\"<clean>\",\"confidence\":\"high|medium|low\"} "
                        + "or {\"name\":null} if the descriptor is non-merchant "
                        + "(ACH, transfer, internal) or unrecognizable.");
        final ArrayNode messages = body.putArray("messages");
        final ObjectNode userMsg = messages.addObject();
        userMsg.put("role", "user");
        userMsg.put("content", "Descriptor: " + rawDescriptor);

        final HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(ANTHROPIC_API))
                .header("x-api-key", apiKey)
                .header("anthropic-version", ANTHROPIC_VERSION)
                .header("content-type", "application/json")
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(),
                        StandardCharsets.UTF_8))
                .build();

        final HttpResponse<String> resp = client.send(
                request, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) return null;
        final JsonNode root = mapper.readTree(resp.body());
        final String text = root.path("content").path(0).path("text").asText("");
        // Find the JSON object in the response (the model sometimes wraps
        // it in prose despite the system prompt).
        final int start = text.indexOf('{');
        final int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        final JsonNode parsed = mapper.readTree(text.substring(start, end + 1));
        final JsonNode name = parsed.path("name");
        final String conf = parsed.path("confidence").asText("low");
        // Only return high-confidence canonicalizations. Medium/low go
        // back to raw because they'd cause more user confusion than help.
        if (name.isNull() || name.isMissingNode() || !"high".equals(conf)) return null;
        final String clean = name.asText("").trim();
        return clean.isEmpty() ? null : clean;
    }
}
