package com.budgetbuddy.service.category;

import com.budgetbuddy.service.TransactionTypeCategoryService.CategoryResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/**
 * Anthropic-backed {@link LlmCategorySuggester}. Categorises transactions
 * that fell through every deterministic layer (L0-L8) of the cascade.
 *
 * <h3>Wired in by</h3>
 *
 * {@link SelfLearningWorker} — a background {@code @Scheduled} job that
 * drains the {@link UncategorisedReviewQueue}. The worker never blocks
 * the import path; it runs out-of-band so users never wait on the LLM.
 *
 * <h3>How calls are constrained</h3>
 *
 * <ul>
 *   <li>{@code low}-tier model by default (haiku) — fastest, cheapest.
 *   <li>Forces JSON output via the structured prompt + retries on parse fail.
 *   <li>1000-token max output — categorisation needs ~30 tokens.
 *   <li>Hard timeout on the HTTP call.
 *   <li>Confidence threshold enforced by the worker before promotion.
 * </ul>
 *
 * <h3>Activation</h3>
 *
 * <pre>
 *   app:
 *     category:
 *       anthropic:
 *         enabled: true
 *         api-key: ${ANTHROPIC_API_KEY}
 *         model: claude-haiku-4-5-20251001
 * </pre>
 *
 * <p>Disabled by default. When disabled, the {@code @Primary}
 * {@link LlmCategorySuggester.NoOp} stays the active bean and the
 * self-learning loop reads no suggestions — categorisation still
 * works, the system just doesn't learn from LLM until activated.
 */
@Service
@Primary
@ConditionalOnProperty(name = "app.category.anthropic.enabled", havingValue = "true")
public class AnthropicLlmCategorySuggester implements LlmCategorySuggester {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(AnthropicLlmCategorySuggester.class);

    /**
     * The categories the LLM is allowed to choose from. Keep in lock-step
     * with {@code TransactionTypeCategoryService.EXPENSE_CATEGORIES} so
     * a hallucinated category never slips through into the database.
     */
    private static final java.util.Set<String> ALLOWED_CATEGORIES = java.util.Set.of(
            "dining", "groceries", "travel", "transportation", "education",
            "entertainment", "shopping", "fees", "tech", "insurance",
            "pet", "utilities", "health", "charity", "home improvement",
            "subscriptions", "payment", "other");

    private static final String SYSTEM_PROMPT =
            "You are a financial transaction categoriser. Given a credit-card "
                    + "transaction's merchant name, description, and location, "
                    + "respond with ONLY a JSON object (no prose, no markdown) "
                    + "of the shape:\n"
                    + "{\n"
                    + "  \"category\": \"<one of: dining, groceries, travel, "
                    + "transportation, education, entertainment, shopping, fees, "
                    + "tech, insurance, pet, utilities, health, charity, "
                    + "home improvement, subscriptions, payment, other>\",\n"
                    + "  \"confidence\": <0.0 to 1.0>,\n"
                    + "  \"reasoning\": \"<one short sentence>\"\n"
                    + "}\n"
                    + "Use confidence 0.95+ only when the merchant is unambiguous. "
                    + "Use 0.7-0.9 when reasonably confident from context. "
                    + "Use < 0.7 when guessing — the system will reject low-confidence "
                    + "suggestions, so be honest about uncertainty.";

    private final String apiUrl;
    private final String apiKey;
    private final String model;
    private final HttpClient http;
    private final ObjectMapper jsonMapper = new ObjectMapper();

    public AnthropicLlmCategorySuggester(
            @Value("${app.category.anthropic.url:https://api.anthropic.com/v1/messages}")
                    final String apiUrl,
            @Value("${app.category.anthropic.api-key:}") final String apiKey,
            @Value("${app.category.anthropic.model:claude-haiku-4-5-20251001}")
                    final String model,
            @Value("${app.category.anthropic.timeout-seconds:10}") final int timeoutSeconds) {
        this.apiUrl = apiUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .build();
        if (apiKey == null || apiKey.isBlank()) {
            LOGGER.warn(
                    "AnthropicLlmCategorySuggester activated but "
                            + "app.category.anthropic.api-key is empty — suggestions will be skipped");
        }
    }

    @Override
    public CategoryResult suggest(final SuggestionContext context) {
        if (apiKey == null || apiKey.isBlank() || context == null) {
            return null;
        }
        if (context.merchantName == null && context.description == null) {
            return null;
        }
        try {
            final String body = buildRequestBody(context);
            final HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .timeout(Duration.ofSeconds(15))
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            final HttpResponse<String> resp =
                    http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                if (LOGGER.isWarnEnabled()) {
                    LOGGER.warn(
                            "Anthropic returned status {} for '{}': {}",
                            resp.statusCode(), context.merchantName,
                            truncate(resp.body(), 200));
                }
                return null;
            }
            return parseSuggestion(resp.body(), context.merchantName);
        } catch (Exception ex) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(
                        "Anthropic suggest failed for '{}': {}",
                        context.merchantName, ex.getMessage());
            }
            return null;
        }
    }

    /** Build the Messages-API request payload. */
    private String buildRequestBody(final SuggestionContext ctx) throws Exception {
        final ObjectNode root = jsonMapper.createObjectNode();
        root.put("model", model);
        root.put("max_tokens", 200);
        root.put("system", SYSTEM_PROMPT);
        final ArrayNode messages = root.putArray("messages");
        final ObjectNode userMsg = messages.addObject();
        userMsg.put("role", "user");
        userMsg.put("content", buildUserPrompt(ctx));
        return jsonMapper.writeValueAsString(root);
    }

    private static String buildUserPrompt(final SuggestionContext ctx) {
        final StringBuilder sb = new StringBuilder(256);
        sb.append("Categorise this transaction.\n");
        if (ctx.merchantName != null && !ctx.merchantName.isBlank()) {
            sb.append("Merchant: ").append(ctx.merchantName).append('\n');
        }
        if (ctx.description != null && !ctx.description.isBlank()) {
            sb.append("Description: ").append(ctx.description).append('\n');
        }
        if (ctx.city != null && !ctx.city.isBlank()) {
            sb.append("City: ").append(ctx.city).append('\n');
        }
        if (ctx.state != null && !ctx.state.isBlank()) {
            sb.append("State: ").append(ctx.state).append('\n');
        }
        if (ctx.country != null && !ctx.country.isBlank()) {
            sb.append("Country: ").append(ctx.country).append('\n');
        }
        if (ctx.amount != null) {
            sb.append("Amount: ").append(ctx.amount.toPlainString()).append('\n');
        }
        if (ctx.issuerName != null && !ctx.issuerName.isBlank()) {
            sb.append("Card issuer: ").append(ctx.issuerName).append('\n');
        }
        if (ctx.accountType != null && !ctx.accountType.isBlank()) {
            sb.append("Account type: ").append(ctx.accountType).append('\n');
        }
        return sb.toString();
    }

    /**
     * Parse the Anthropic response body and return a CategoryResult.
     * Returns null on any parse failure, hallucinated category, or
     * malformed confidence.
     */
    CategoryResult parseSuggestion(final String responseBody, final String merchantName) {
        try {
            final JsonNode root = jsonMapper.readTree(responseBody);
            final JsonNode content = root.path("content");
            if (!content.isArray() || content.size() == 0) {
                return null;
            }
            // Messages API returns content[] as a list of blocks; first
            // text block has the JSON object we asked for.
            String text = null;
            for (final JsonNode block : content) {
                if ("text".equals(block.path("type").asText())) {
                    text = block.path("text").asText();
                    break;
                }
            }
            if (text == null || text.isBlank()) {
                return null;
            }
            // LLMs sometimes prefix/suffix the JSON with prose despite
            // the system prompt — locate the JSON object by brace match.
            final int start = text.indexOf('{');
            final int end = text.lastIndexOf('}');
            if (start < 0 || end <= start) {
                return null;
            }
            final String json = text.substring(start, end + 1);
            final JsonNode obj = jsonMapper.readTree(json);
            final String category = obj.path("category").asText(null);
            final double confidence = obj.path("confidence").asDouble(0);
            final String reasoning = obj.path("reasoning").asText("");
            if (category == null || !ALLOWED_CATEGORIES.contains(category)) {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug(
                            "Rejecting LLM category for '{}': '{}' not in allowed set",
                            merchantName, category);
                }
                return null;
            }
            if (confidence <= 0 || confidence > 1) {
                return null;
            }
            return new CategoryResult(
                    category,
                    category,
                    "LLM_ANTHROPIC: " + truncate(reasoning, 100),
                    confidence);
        } catch (Exception ex) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(
                        "Failed to parse Anthropic response for '{}': {}",
                        merchantName, ex.getMessage());
            }
            return null;
        }
    }

    private static String truncate(final String s, final int n) {
        return s == null || s.length() <= n ? s : s.substring(0, n) + "...";
    }
}
