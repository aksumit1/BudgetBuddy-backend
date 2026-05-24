package com.budgetbuddy.service.insights;

import com.budgetbuddy.service.llm.AnthropicMessagesClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * AI-3: Anthropic-backed personaliser for anomaly messages. Activates
 * when {@code app.anomaly-messaging.anthropic.enabled=true}.
 *
 * <p>Hallucination guard: the model can ONLY produce a message string per
 * known anomalyId. Any anomalies the model didn't return are left with
 * their deterministic reason. Numeric fields (amount, severity, type)
 * are never read from the LLM response.
 */
@Service
@ConditionalOnProperty(name = "app.anomaly-messaging.anthropic.enabled", havingValue = "true")
public class AnthropicAnomalyMessageAdvisor implements AnomalyMessageAdvisor {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(AnthropicAnomalyMessageAdvisor.class);

    private static final String SYSTEM_PROMPT =
            "Rewrite each anomaly reason into a concise, human-friendly "
                    + "explanation a non-technical user can act on. Respond with "
                    + "ONLY a JSON array of objects:\n"
                    + "[ {\"anomaly_id\": \"<id>\", \"message\": \"<≤25 word "
                    + "sentence>\"} ]\n\n"
                    + "Rules: never invent facts the input didn't supply. Don't "
                    + "claim the user did or didn't do something — describe the "
                    + "transaction shape. Avoid finger-wagging tone. If you can't "
                    + "improve on the input, omit that anomaly_id entirely.";

    private final AnthropicMessagesClient client;

    public AnthropicAnomalyMessageAdvisor(
            @Value("${app.anomaly-messaging.anthropic.url:https://api.anthropic.com/v1/messages}")
                    final String apiUrl,
            @Value("${app.anomaly-messaging.anthropic.api-key:${app.category.anthropic.api-key:}}")
                    final String apiKey,
            @Value("${app.anomaly-messaging.anthropic.model:claude-haiku-4-5-20251001}")
                    final String model,
            @Value("${app.anomaly-messaging.anthropic.timeout-seconds:8}") final int timeoutSeconds) {
        this.client = new AnthropicMessagesClient(
                apiUrl, apiKey, model, SYSTEM_PROMPT, /*maxTokens=*/600, timeoutSeconds);
    }

    @Override
    public List<AnomalyContext> annotate(final List<AnomalyContext> alerts) {
        if (alerts == null || alerts.isEmpty() || !client.isConfigured()) return alerts;
        final String reply = client.complete(buildPrompt(alerts));
        if (reply == null) return alerts;
        final int start = reply.indexOf('[');
        final int end = reply.lastIndexOf(']');
        if (start < 0 || end <= start) return alerts;
        try {
            final JsonNode arr = new ObjectMapper().readTree(reply.substring(start, end + 1));
            if (!arr.isArray()) return alerts;
            final Map<String, String> byId = new HashMap<>();
            for (final JsonNode n : arr) {
                final String id = n.path("anomaly_id").asText(null);
                final String msg = n.path("message").asText(null);
                if (id != null && msg != null && !msg.isBlank()) byId.put(id, msg.trim());
            }
            for (final AnomalyContext a : alerts) {
                final String personalised = byId.get(a.anomalyId);
                if (personalised != null) a.humanMessage = personalised;
            }
            return alerts;
        } catch (Exception ex) {
            LOGGER.debug("AnomalyMessageAdvisor parse failed: {}", ex.getMessage());
            return alerts;
        }
    }

    private static String buildPrompt(final List<AnomalyContext> alerts) {
        final StringBuilder sb = new StringBuilder(512);
        sb.append("Anomalies to rewrite:\n");
        for (final AnomalyContext a : alerts) {
            sb.append("- anomaly_id=").append(a.anomalyId)
                    .append(" type=").append(safe(a.type))
                    .append(" severity=").append(safe(a.severity))
                    .append(" category=").append(safe(a.category))
                    .append(" merchant=").append(safe(a.merchantName))
                    .append(" amount=").append(a.amount)
                    .append(" historical_avg=").append(a.historicalAverage)
                    .append(" date=").append(a.transactionDate)
                    .append(" deterministic_reason=\"").append(safe(a.deterministicReason))
                    .append("\"\n");
        }
        return sb.toString();
    }

    private static String safe(final String s) {
        return s == null ? "" : s.replace('"', '\'');
    }
}
