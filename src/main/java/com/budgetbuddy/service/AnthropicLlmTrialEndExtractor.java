package com.budgetbuddy.service;

import com.budgetbuddy.service.llm.AnthropicMessagesClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Anthropic-backed {@link LlmTrialEndExtractor}. Activates when
 * {@code app.trial-end-extractor.anthropic.enabled=true}.
 */
@Service
@ConditionalOnProperty(
        name = "app.trial-end-extractor.anthropic.enabled",
        havingValue = "true")
public class AnthropicLlmTrialEndExtractor implements LlmTrialEndExtractor {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(AnthropicLlmTrialEndExtractor.class);

    private static final String SYSTEM_PROMPT =
            "You extract trial-end dates from credit-card transaction descriptions.\n"
                    + "Most descriptions have NO trial info — return null in that case.\n"
                    + "When a trial-end / first-billing date IS present (\"FREE TRIAL ENDS 03/15\","
                    + " \"first charge 4/4\", \"trial ends April\"), respond with ONLY a JSON object:\n"
                    + "{\n"
                    + "  \"trial_ends\": \"YYYY-MM-DD\" or null,\n"
                    + "  \"confidence\": <0.0 to 1.0>,\n"
                    + "  \"source\": \"<the short snippet you read it from>\"\n"
                    + "}\n"
                    + "Use confidence 0.95+ only when the date is explicit. Use null + 0 when "
                    + "there's no signal — the system will simply skip the prediction.";

    private final AnthropicMessagesClient client;

    public AnthropicLlmTrialEndExtractor(
            @Value("${app.trial-end-extractor.anthropic.url:https://api.anthropic.com/v1/messages}")
                    final String apiUrl,
            @Value("${app.trial-end-extractor.anthropic.api-key:${app.category.anthropic.api-key:}}")
                    final String apiKey,
            @Value("${app.trial-end-extractor.anthropic.model:claude-haiku-4-5-20251001}")
                    final String model,
            @Value("${app.trial-end-extractor.anthropic.timeout-seconds:8}")
                    final int timeoutSeconds) {
        this.client = new AnthropicMessagesClient(
                apiUrl, apiKey, model, SYSTEM_PROMPT, /*maxTokens=*/150, timeoutSeconds);
    }

    @Override
    public TrialEndPrediction extract(final String merchantName, final String description) {
        if (!client.isConfigured()) return null;
        if ((merchantName == null || merchantName.isBlank())
                && (description == null || description.isBlank())) {
            return null;
        }
        final String reply = client.complete(
                "Merchant: " + (merchantName == null ? "" : merchantName)
                        + "\nDescription: " + (description == null ? "" : description));
        if (reply == null) return null;
        final JsonNode obj = client.parseEmbeddedJson(reply);
        if (obj == null) return null;
        try {
            final String dateStr = obj.path("trial_ends").asText(null);
            final double conf = obj.path("confidence").asDouble(0);
            final String source = obj.path("source").asText("");
            if (dateStr == null || "null".equalsIgnoreCase(dateStr) || conf <= 0) return null;
            final LocalDate trialEnds;
            try {
                trialEnds = LocalDate.parse(dateStr);
            } catch (java.time.format.DateTimeParseException ex) {
                LOGGER.warn("LlmTrialEnd: bad date format '{}' from LLM", dateStr);
                return null;
            }
            return new TrialEndPrediction(trialEnds, conf, source);
        } catch (Exception ex) {
            LOGGER.debug("LlmTrialEnd: parse failed: {}", ex.getMessage());
            return null;
        }
    }
}
