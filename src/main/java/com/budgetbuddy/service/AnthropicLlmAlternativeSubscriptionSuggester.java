package com.budgetbuddy.service;

import com.budgetbuddy.model.Subscription;
import com.budgetbuddy.service.llm.AnthropicMessagesClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Anthropic-backed {@link LlmAlternativeSubscriptionSuggester}. Activates
 * when {@code app.subscription-alternatives.anthropic.enabled=true}.
 */
@Service
@ConditionalOnProperty(
        name = "app.subscription-alternatives.anthropic.enabled",
        havingValue = "true")
public class AnthropicLlmAlternativeSubscriptionSuggester
        implements LlmAlternativeSubscriptionSuggester {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(AnthropicLlmAlternativeSubscriptionSuggester.class);

    private static final String SYSTEM_PROMPT =
            "You suggest cheaper or equivalent-feature alternatives to a paid "
                    + "subscription service. Respond with ONLY a JSON array, no markdown:\n"
                    + "[\n"
                    + "  {\"name\": \"<service name>\", \"monthly_price\": <USD number>, \"pitch\": \"<one short sentence>\"},\n"
                    + "  ...\n"
                    + "]\n\n"
                    + "Only suggest real, widely-available services. Skip if you don't "
                    + "know of a credible alternative. Return [] in that case.";

    private final AnthropicMessagesClient client;

    public AnthropicLlmAlternativeSubscriptionSuggester(
            @Value("${app.subscription-alternatives.anthropic.url:https://api.anthropic.com/v1/messages}")
                    final String apiUrl,
            @Value("${app.subscription-alternatives.anthropic.api-key:${app.category.anthropic.api-key:}}")
                    final String apiKey,
            @Value("${app.subscription-alternatives.anthropic.model:claude-haiku-4-5-20251001}")
                    final String model,
            @Value("${app.subscription-alternatives.anthropic.timeout-seconds:10}")
                    final int timeoutSeconds) {
        this.client = new AnthropicMessagesClient(
                apiUrl, apiKey, model, SYSTEM_PROMPT, /*maxTokens=*/300, timeoutSeconds);
    }

    @Override
    public List<Alternative> suggest(final Subscription subscription, final int maxResults) {
        if (subscription == null || !client.isConfigured()) return List.of();
        final String reply = client.complete(buildPrompt(subscription, maxResults));
        if (reply == null) return List.of();
        // Find the JSON array in the reply
        final int start = reply.indexOf('[');
        final int end = reply.lastIndexOf(']');
        if (start < 0 || end < start) return List.of();
        try {
            final JsonNode arr = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(reply.substring(start, end + 1));
            if (!arr.isArray()) return List.of();
            final List<Alternative> out = new ArrayList<>(arr.size());
            for (final JsonNode node : arr) {
                if (out.size() >= maxResults) break;
                final String name = node.path("name").asText(null);
                final double price = node.path("monthly_price").asDouble(0);
                final String pitch = node.path("pitch").asText("");
                if (name == null || price <= 0) continue;
                out.add(new Alternative(name, BigDecimal.valueOf(price), pitch));
            }
            return out;
        } catch (Exception ex) {
            LOGGER.debug("LlmAlternative: parse failed for '{}': {}",
                    subscription.getMerchantName(), ex.getMessage());
            return List.of();
        }
    }

    private static String buildPrompt(final Subscription s, final int maxResults) {
        return "Suggest up to " + maxResults + " alternatives to:\n"
                + "Merchant: " + (s.getMerchantName() == null ? "" : s.getMerchantName()) + "\n"
                + "Current price: $"
                + (s.getAmount() == null ? "?" : s.getAmount().abs().toPlainString()) + "/"
                + (s.getFrequency() == null ? "month" : s.getFrequency().name().toLowerCase()) + "\n"
                + (s.getSubscriptionType() == null ? "" : "Type: " + s.getSubscriptionType() + "\n");
    }
}
