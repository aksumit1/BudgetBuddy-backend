package com.budgetbuddy.service.category;

import com.budgetbuddy.service.llm.AnthropicMessagesClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Anthropic-backed {@link LlmSubscriptionClassifier} impl. Activates when
 * {@code app.subscription-classifier.anthropic.enabled=true}.
 */
@Service
@ConditionalOnProperty(
        name = "app.subscription-classifier.anthropic.enabled",
        havingValue = "true")
public class AnthropicLlmSubscriptionClassifier implements LlmSubscriptionClassifier {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(AnthropicLlmSubscriptionClassifier.class);

    private static final String SYSTEM_PROMPT =
            "You decide whether a recurring-cadence spend pattern is a real "
                    + "subscription that the user could cancel, or just per-use "
                    + "spend that happens to fall on a regular cadence.\n\n"
                    + "Respond with ONLY a JSON object (no markdown, no prose):\n"
                    + "{\n"
                    + "  \"verdict\": \"<SUBSCRIPTION | VARIABLE_BILL | REPEAT_SPEND | UNCERTAIN>\",\n"
                    + "  \"confidence\": <0.0 to 1.0>,\n"
                    + "  \"reasoning\": \"<one short sentence>\"\n"
                    + "}\n\n"
                    + "SUBSCRIPTION = fixed-price recurring service (Netflix, gym, Spotify).\n"
                    + "VARIABLE_BILL = recurring service with variable amount (cell bill, electric).\n"
                    + "REPEAT_SPEND = per-use spend that happens to cluster (daily coffee, weekly groceries).\n"
                    + "UNCERTAIN = not enough signal — the system will fall back to its heuristic.\n\n"
                    + "Use confidence below 0.7 generously when the merchant is ambiguous — "
                    + "below threshold the system rejects your answer.";

    private final AnthropicMessagesClient client;

    public AnthropicLlmSubscriptionClassifier(
            @Value("${app.subscription-classifier.anthropic.url:https://api.anthropic.com/v1/messages}")
                    final String apiUrl,
            @Value("${app.subscription-classifier.anthropic.api-key:${app.category.anthropic.api-key:}}")
                    final String apiKey,
            @Value("${app.subscription-classifier.anthropic.model:claude-haiku-4-5-20251001}")
                    final String model,
            @Value("${app.subscription-classifier.anthropic.timeout-seconds:10}")
                    final int timeoutSeconds) {
        this.client = new AnthropicMessagesClient(
                apiUrl, apiKey, model, SYSTEM_PROMPT, /*maxTokens=*/200, timeoutSeconds);
        if (!client.isConfigured()) {
            LOGGER.warn("AnthropicLlmSubscriptionClassifier active but api-key empty — calls skipped");
        }
    }

    @Override
    public Decision classify(final Series series) {
        if (series == null || !client.isConfigured()) return null;
        final String reply = client.complete(buildPrompt(series));
        if (reply == null) return null;
        final JsonNode obj = client.parseEmbeddedJson(reply);
        if (obj == null) return null;
        try {
            final String verdictStr = obj.path("verdict").asText(null);
            if (verdictStr == null) return null;
            final Verdict verdict;
            try {
                verdict = Verdict.valueOf(verdictStr);
            } catch (IllegalArgumentException ex) {
                LOGGER.warn("LlmSubClass: rejecting hallucinated verdict '{}'", verdictStr);
                return null;
            }
            final double confidence = obj.path("confidence").asDouble(0);
            if (confidence <= 0 || confidence > 1) return null;
            return new Decision(verdict, confidence, obj.path("reasoning").asText(""));
        } catch (Exception ex) {
            LOGGER.debug("LlmSubClass: parse failed: {}", ex.getMessage());
            return null;
        }
    }

    private static String buildPrompt(final Series s) {
        final StringBuilder sb = new StringBuilder(400);
        sb.append("Merchant: ").append(s.merchantName).append('\n');
        if (s.category != null) sb.append("Category: ").append(s.category).append('\n');
        if (s.cadenceHint != null) sb.append("Cadence: ").append(s.cadenceHint).append('\n');
        if (s.amounts != null && !s.amounts.isEmpty()) {
            sb.append("Amounts: ");
            for (int i = 0; i < s.amounts.size(); i++) {
                if (i > 0) sb.append(", ");
                final BigDecimal a = s.amounts.get(i);
                sb.append('$').append(a == null ? "?" : a.abs().toPlainString());
            }
            sb.append('\n');
        }
        if (s.dates != null && !s.dates.isEmpty()) {
            sb.append("Dates: ");
            for (int i = 0; i < s.dates.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(s.dates.get(i));
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
