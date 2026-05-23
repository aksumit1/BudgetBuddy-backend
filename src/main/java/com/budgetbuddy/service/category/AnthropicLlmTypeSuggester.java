package com.budgetbuddy.service.category;

import com.budgetbuddy.service.llm.AnthropicMessagesClient;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Anthropic-backed {@link LlmTypeSuggester} implementation. Activates
 * when {@code app.type-classifier.anthropic.enabled=true}.
 */
@Service
@ConditionalOnProperty(name = "app.type-classifier.anthropic.enabled", havingValue = "true")
public class AnthropicLlmTypeSuggester implements LlmTypeSuggester {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(AnthropicLlmTypeSuggester.class);

    private static final String SYSTEM_PROMPT =
            "You are a financial transaction type classifier. Given a "
                    + "transaction's merchant name, description, amount, "
                    + "account type, and payment channel, decide what kind "
                    + "of money movement this is.\n\n"
                    + "Respond with ONLY a JSON object (no markdown, no prose):\n"
                    + "{\n"
                    + "  \"type\": \"<one of: PAYMENT, EXPENSE, INVESTMENT, INCOME>\",\n"
                    + "  \"confidence\": <0.0 to 1.0>,\n"
                    + "  \"reasoning\": \"<one short sentence>\"\n"
                    + "}\n\n"
                    + "PAYMENT = paying off a credit card or loan balance.\n"
                    + "EXPENSE = spending on goods or services.\n"
                    + "INVESTMENT = buying/selling securities, contributions to retirement.\n"
                    + "INCOME = money inflow (payroll, refund, dividend, interest).\n\n"
                    + "Use confidence 0.95+ only when unambiguous. Below 0.7 the "
                    + "system rejects your answer — be honest about uncertainty.";

    private final AnthropicMessagesClient client;

    public AnthropicLlmTypeSuggester(
            @Value("${app.type-classifier.anthropic.url:https://api.anthropic.com/v1/messages}")
                    final String apiUrl,
            @Value("${app.type-classifier.anthropic.api-key:${app.category.anthropic.api-key:}}")
                    final String apiKey,
            @Value("${app.type-classifier.anthropic.model:claude-haiku-4-5-20251001}")
                    final String model,
            @Value("${app.type-classifier.anthropic.timeout-seconds:10}")
                    final int timeoutSeconds) {
        this.client = new AnthropicMessagesClient(
                apiUrl, apiKey, model, SYSTEM_PROMPT, /*maxTokens=*/150, timeoutSeconds);
        if (!client.isConfigured()) {
            LOGGER.warn("AnthropicLlmTypeSuggester activated but api-key is empty — calls will be skipped");
        }
    }

    @Override
    public TypeSuggestion suggest(final TypeContext context) {
        if (context == null || !client.isConfigured()) return null;
        final String userPrompt = buildPrompt(context);
        final String reply = client.complete(userPrompt);
        if (reply == null) return null;
        final JsonNode obj = client.parseEmbeddedJson(reply);
        if (obj == null) return null;
        try {
            final String typeStr = obj.path("type").asText(null);
            final double confidence = obj.path("confidence").asDouble(0);
            final String reasoning = obj.path("reasoning").asText("");
            if (typeStr == null) return null;
            final SuggestedType type;
            try {
                type = SuggestedType.valueOf(typeStr);
            } catch (IllegalArgumentException ex) {
                LOGGER.warn("LlmType: rejecting hallucinated type '{}'", typeStr);
                return null;
            }
            if (confidence <= 0 || confidence > 1) return null;
            return new TypeSuggestion(type, confidence, reasoning);
        } catch (Exception ex) {
            LOGGER.debug("LlmType: parse failed for '{}': {}", context.merchantName, ex.getMessage());
            return null;
        }
    }

    private static String buildPrompt(final TypeContext ctx) {
        final StringBuilder sb = new StringBuilder(200);
        sb.append("Classify this transaction.\n");
        if (ctx.merchantName != null) sb.append("Merchant: ").append(ctx.merchantName).append('\n');
        if (ctx.description != null) sb.append("Description: ").append(ctx.description).append('\n');
        if (ctx.amount != null) sb.append("Amount: ").append(ctx.amount.toPlainString()).append('\n');
        if (ctx.accountType != null) sb.append("Account type: ").append(ctx.accountType).append('\n');
        if (ctx.accountSubtype != null) sb.append("Account subtype: ").append(ctx.accountSubtype).append('\n');
        if (ctx.paymentChannel != null) sb.append("Payment channel: ").append(ctx.paymentChannel).append('\n');
        return sb.toString();
    }
}
