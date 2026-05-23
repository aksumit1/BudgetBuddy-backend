package com.budgetbuddy.service.goal;

import com.budgetbuddy.service.llm.AnthropicMessagesClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * G-AI-2: Anthropic-backed parser for natural-language goal text.
 * Activates when {@code app.goal-nlp.anthropic.enabled=true}.
 *
 * <p>The parsed result is REQUIRED to be confirmed by the user — the
 * controller does not auto-create the goal. The advisor's contract is
 * strictly "parse + suggest"; the deterministic createGoal flow then
 * applies its own validation rules.
 */
@Service
@ConditionalOnProperty(name = "app.goal-nlp.anthropic.enabled", havingValue = "true")
public class AnthropicGoalNaturalLanguageParser implements GoalNaturalLanguageParser {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(AnthropicGoalNaturalLanguageParser.class);

    private static final String SYSTEM_PROMPT =
            "Extract structured goal fields from a user's natural-language "
                    + "description of a financial goal. Resolve relative dates "
                    + "(\"next June\", \"in 18 months\") against the supplied "
                    + "today's date. Respond with ONLY a JSON object:\n"
                    + "{\"name\": \"<short name>\", "
                    + "\"goal_type\": \"EMERGENCY_FUND|VACATION|DOWN_PAYMENT|"
                    + "DEBT_PAYOFF|RETIREMENT|EDUCATION|OTHER\", "
                    + "\"target_amount\": <positive USD number>, "
                    + "\"target_date\": \"YYYY-MM-DD\", "
                    + "\"currency_code\": \"<ISO 4217>\", "
                    + "\"reasoning\": \"<one short sentence>\"}\n\n"
                    + "Rules: if any required field can't be inferred, return "
                    + "{} (empty object). target_date must be in the future. "
                    + "Never invent an amount the user didn't specify or imply.";

    private final AnthropicMessagesClient client;

    public AnthropicGoalNaturalLanguageParser(
            @Value("${app.goal-nlp.anthropic.url:https://api.anthropic.com/v1/messages}")
                    final String apiUrl,
            @Value("${app.goal-nlp.anthropic.api-key:${app.category.anthropic.api-key:}}")
                    final String apiKey,
            @Value("${app.goal-nlp.anthropic.model:claude-haiku-4-5-20251001}")
                    final String model,
            @Value("${app.goal-nlp.anthropic.timeout-seconds:10}") final int timeoutSeconds) {
        this.client = new AnthropicMessagesClient(
                apiUrl, apiKey, model, SYSTEM_PROMPT, /*maxTokens=*/300, timeoutSeconds);
    }

    @Override
    public Optional<ParsedGoal> parse(final String text) {
        if (text == null || text.isBlank() || !client.isConfigured()) return Optional.empty();
        final String prompt = "Today is " + LocalDate.now() + "\nDescription: " + text.trim();
        final String reply = client.complete(prompt);
        if (reply == null) return Optional.empty();
        final int start = reply.indexOf('{');
        final int end = reply.lastIndexOf('}');
        if (start < 0 || end <= start) return Optional.empty();
        try {
            final JsonNode node = new ObjectMapper().readTree(reply.substring(start, end + 1));
            if (node.size() == 0) return Optional.empty();
            final String name = node.path("name").asText(null);
            final String type = node.path("goal_type").asText(null);
            final double amount = node.path("target_amount").asDouble(0);
            final String dateStr = node.path("target_date").asText(null);
            final String currency = node.path("currency_code").asText("USD");
            final String reasoning = node.path("reasoning").asText("");
            if (name == null || type == null || amount <= 0 || dateStr == null) {
                return Optional.empty();
            }
            final LocalDate date;
            try {
                date = LocalDate.parse(dateStr);
            } catch (Exception ex) {
                return Optional.empty();
            }
            if (!date.isAfter(LocalDate.now())) return Optional.empty();
            return Optional.of(
                    new ParsedGoal(name, type, BigDecimal.valueOf(amount), date, currency, reasoning));
        } catch (Exception ex) {
            LOGGER.debug("GoalNLP: parse failed: {}", ex.getMessage());
            return Optional.empty();
        }
    }
}
