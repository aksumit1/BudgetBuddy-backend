package com.budgetbuddy.service.insights.ai;

import com.budgetbuddy.service.TransactionAnomalyService.TransactionAnomaly;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.net.URI;
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
import org.springframework.stereotype.Service;

/**
 * Rewrites the canned per-anomaly reason string ("Predicted category
 * spike based on increasing trend") into a one-sentence, user-friendly
 * explanation that mentions the actual merchant + amount + comparison.
 *
 * <p>This is an augmentation, not a replacement: when the LLM is
 * disabled / unreachable / low-confidence, {@link #explain} returns
 * {@code null} and the caller keeps the deterministic reason.
 *
 * <p>Activation: {@code app.insights.ai-anomaly-explain.enabled=true}
 * plus a non-blank API key. Off by default so production behaviour is
 * unchanged until ops opts in.
 */
@Service
@ConditionalOnProperty(
        name = "app.insights.ai-anomaly-explain.enabled",
        havingValue = "true",
        matchIfMissing = false)
public class AiAnomalyExplainer {

    private static final Logger LOGGER = LoggerFactory.getLogger(AiAnomalyExplainer.class);
    private static final String ANTHROPIC_API = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    @Value("${app.insights.ai-anomaly-explain.api-key:}")
    private String apiKey;

    @Value("${app.insights.ai-anomaly-explain.model:claude-haiku-4-5-20251001}")
    private String model;

    @Value("${app.insights.ai-anomaly-explain.timeout-seconds:8}")
    private int timeoutSeconds;

    /**
     * Cache key is the canonical anomaly fingerprint (merchant + category
     * + amount bucket) — same buckets the dismiss-suppression layer uses,
     * so two anomalies that look the same to the user produce one LLM
     * call. Sentinel empty string means "LLM gave up; don't retry".
     */
    private final Map<String, String> sessionCache = new ConcurrentHashMap<>();

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient client =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    /**
     * @return a one-sentence explanation suitable for direct display, or
     *         {@code null} if the LLM is disabled, the input is invalid,
     *         the call failed, or the model returned a low-confidence
     *         answer. Caller must keep the deterministic reason as
     *         fallback.
     */
    @Nullable
    public String explain(@Nullable final TransactionAnomaly anomaly) {
        if (anomaly == null) {
            return null;
        }
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }
        final String key = cacheKey(anomaly);
        final String cached = sessionCache.get(key);
        if (cached != null) {
            return cached.isEmpty() ? null : cached;
        }
        try {
            final String explanation = callAnthropic(anomaly);
            sessionCache.put(key, explanation == null ? "" : explanation);
            return explanation;
        } catch (final Exception e) {
            LOGGER.debug(
                    "ai-anomaly-explain failed for {}: {}",
                    anomaly.getMerchantName(),
                    e.getMessage());
            // Cache the failure so we don't hammer a sick LLM on a hot path.
            sessionCache.put(key, "");
            return null;
        }
    }

    static String cacheKey(final TransactionAnomaly a) {
        final String merchant =
                a.getMerchantName() == null
                        ? ""
                        : a.getMerchantName().trim().toLowerCase(Locale.ROOT);
        final String category =
                a.getCategory() == null
                        ? ""
                        : a.getCategory().trim().toLowerCase(Locale.ROOT);
        final String amountBucket =
                a.getAmount() == null
                        ? "0"
                        : a.getAmount()
                                .abs()
                                .divide(
                                        new java.math.BigDecimal("20"),
                                        0,
                                        java.math.RoundingMode.HALF_UP)
                                .multiply(new java.math.BigDecimal("20"))
                                .toPlainString();
        return merchant + "|" + category + "|" + amountBucket;
    }

    private String callAnthropic(final TransactionAnomaly anomaly) throws Exception {
        final ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", 120);
        body.put(
                "system",
                "You explain a flagged transaction anomaly to the cardholder in ONE "
                        + "short sentence (under 22 words). Mention the merchant and amount. "
                        + "No filler, no emoji, no advice. Return JSON: "
                        + "{\"text\":\"<sentence>\",\"confidence\":\"high|medium|low\"} or "
                        + "{\"text\":null} if the input is too sparse to explain confidently.");
        final ArrayNode messages = body.putArray("messages");
        final ObjectNode userMsg = messages.addObject();
        userMsg.put("role", "user");
        userMsg.put(
                "content",
                "Anomaly type: "
                        + anomaly.getReason()
                        + "\nMerchant: "
                        + nullSafe(anomaly.getMerchantName())
                        + "\nCategory: "
                        + nullSafe(anomaly.getCategory())
                        + "\nAmount: "
                        + (anomaly.getAmount() == null ? "?" : anomaly.getAmount().toPlainString())
                        + "\nSeverity: "
                        + anomaly.getSeverity());

        final HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create(ANTHROPIC_API))
                        .header("x-api-key", apiKey)
                        .header("anthropic-version", ANTHROPIC_VERSION)
                        .header("content-type", "application/json")
                        .timeout(Duration.ofSeconds(timeoutSeconds))
                        .POST(
                                HttpRequest.BodyPublishers.ofString(
                                        body.toString(), StandardCharsets.UTF_8))
                        .build();

        final HttpResponse<String> resp =
                client.send(request, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            return null;
        }
        final JsonNode root = mapper.readTree(resp.body());
        final String raw = root.path("content").path(0).path("text").asText("");
        final int start = raw.indexOf('{');
        final int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        final JsonNode parsed = mapper.readTree(raw.substring(start, end + 1));
        final JsonNode text = parsed.path("text");
        final String conf = parsed.path("confidence").asText("low");
        // Same policy as AiMerchantCanonicalizer: only high-confidence
        // output gets surfaced; medium/low → fall back to deterministic.
        if (text.isNull() || text.isMissingNode() || !"high".equals(conf)) {
            return null;
        }
        final String clean = text.asText("").trim();
        return clean.isEmpty() ? null : clean;
    }

    private static String nullSafe(final String s) {
        return s == null ? "" : s;
    }
}
