package com.budgetbuddy.service.subscription;

import com.budgetbuddy.service.SubscriptionInsightsService.CancellationRecommendation;
import com.budgetbuddy.service.llm.AnthropicMessagesClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * AI-5: Anthropic-backed cancellation-reason advisor. Activates when
 * {@code app.cancellation-reason.anthropic.enabled=true}.
 *
 * <p>The deterministic detector produces messages like
 * "Unused subscription - no recent activity for 60 days" or
 * "Duplicate subscription (Netflix appears twice)". Helpful, but
 * impersonal. An LLM rewrite makes the next-step explicit:
 * "You haven't been charged for HBO Max in two cycles — looks like
 * it cancelled itself. Worth removing from your list."
 *
 * <p>Hallucination guard: the model can ONLY produce a {@code message}
 * keyed by the subscription's id. Anything else returned is dropped.
 * The advisor never modifies {@code reason}, {@code priority}, or
 * {@code potentialSavings}.
 */
@Service
@ConditionalOnProperty(name = "app.cancellation-reason.anthropic.enabled", havingValue = "true")
public class AnthropicCancellationReasonAdvisor implements CancellationReasonAdvisor {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(AnthropicCancellationReasonAdvisor.class);

    private static final String SYSTEM_PROMPT =
            "Rewrite each subscription-cancellation reason into a short, "
                    + "actionable explanation (≤25 words). Respond with ONLY "
                    + "a JSON array of objects:\n"
                    + "[ {\"subscription_id\": \"<id>\", \"message\": \"<sentence>\"} ]\n\n"
                    + "Rules: never invent facts the input didn't supply. Don't "
                    + "scold the user. If you can't improve on the input, omit "
                    + "that subscription_id entirely so the deterministic "
                    + "reason wins.";

    private final AnthropicMessagesClient client;

    public AnthropicCancellationReasonAdvisor(
            @Value("${app.cancellation-reason.anthropic.url:https://api.anthropic.com/v1/messages}")
                    final String apiUrl,
            @Value("${app.cancellation-reason.anthropic.api-key:${app.category.anthropic.api-key:}}")
                    final String apiKey,
            @Value("${app.cancellation-reason.anthropic.model:claude-haiku-4-5-20251001}")
                    final String model,
            @Value("${app.cancellation-reason.anthropic.timeout-seconds:8}")
                    final int timeoutSeconds) {
        this.client = new AnthropicMessagesClient(
                apiUrl, apiKey, model, SYSTEM_PROMPT, /*maxTokens=*/600, timeoutSeconds);
    }

    @Override
    public List<CancellationRecommendation> annotate(
            final List<CancellationRecommendation> recommendations) {
        if (recommendations == null || recommendations.isEmpty() || !client.isConfigured()) {
            return recommendations;
        }
        final String reply = client.complete(buildPrompt(recommendations));
        if (reply == null) return recommendations;
        final int start = reply.indexOf('[');
        final int end = reply.lastIndexOf(']');
        if (start < 0 || end <= start) return recommendations;
        try {
            final JsonNode arr = new ObjectMapper().readTree(reply.substring(start, end + 1));
            if (!arr.isArray()) return recommendations;
            final Map<String, String> byId = new HashMap<>();
            for (final JsonNode n : arr) {
                final String id = n.path("subscription_id").asText(null);
                final String msg = n.path("message").asText(null);
                if (id != null && msg != null && !msg.isBlank()) byId.put(id, msg.trim());
            }
            for (final CancellationRecommendation r : recommendations) {
                if (r == null || r.getSubscription() == null) continue;
                final String personalised = byId.get(r.getSubscription().getSubscriptionId());
                if (personalised != null) r.setHumanMessage(personalised);
            }
            return recommendations;
        } catch (Exception ex) {
            LOGGER.debug("CancellationReasonAdvisor parse failed: {}", ex.getMessage());
            return recommendations;
        }
    }

    private static String buildPrompt(final List<CancellationRecommendation> recs) {
        final StringBuilder sb = new StringBuilder(512);
        sb.append("Cancellation candidates:\n");
        for (final CancellationRecommendation r : recs) {
            if (r == null || r.getSubscription() == null) continue;
            sb.append("- subscription_id=").append(r.getSubscription().getSubscriptionId())
                    .append(" merchant=").append(safe(r.getSubscription().getMerchantName()))
                    .append(" frequency=")
                    .append(r.getSubscription().getFrequency() == null
                            ? "null"
                            : r.getSubscription().getFrequency().name())
                    .append(" amount=").append(amount(r.getSubscription().getAmount()))
                    .append(" priority=").append(r.getPriority().name())
                    .append(" deterministic_reason=\"").append(safe(r.getReason())).append("\"\n");
        }
        return sb.toString();
    }

    private static String safe(final String s) {
        return s == null ? "" : s.replace('"', '\'');
    }

    private static String amount(final BigDecimal v) {
        return v == null ? "0" : v.toPlainString();
    }
}
