package com.budgetbuddy.service.goal;

import com.budgetbuddy.service.llm.AnthropicMessagesClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * G-AI-1: Anthropic-backed goal suggestion advisor. Activates when
 * {@code app.goal-suggestions.anthropic.enabled=true}.
 *
 * <p>Hallucination guard: the response is parsed as a strict JSON
 * array. Any object missing fields or with non-positive targetAmount /
 * targetMonths is dropped silently. The fallback contract is "return
 * empty list" — the deterministic recommendation service stays
 * authoritative when this advisor is degraded.
 */
@Service
@ConditionalOnProperty(name = "app.goal-suggestions.anthropic.enabled", havingValue = "true")
public class AnthropicGoalLlmSuggestionAdvisor implements GoalLlmSuggestionAdvisor {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(AnthropicGoalLlmSuggestionAdvisor.class);

    private static final String SYSTEM_PROMPT =
            "You suggest realistic financial goals for a user given their "
                    + "monthly income, expenses, liquid assets, debt, and "
                    + "estimated disposable income. Respond with ONLY a JSON "
                    + "array (no markdown, no commentary) of objects:\n"
                    + "[ {\"goal_type\": \"EMERGENCY_FUND|VACATION|DOWN_PAYMENT|"
                    + "DEBT_PAYOFF|RETIREMENT|OTHER\", "
                    + "\"name\": \"<short name>\", "
                    + "\"target_amount\": <positive USD number>, "
                    + "\"target_months\": <positive integer ≤ 60>, "
                    + "\"reasoning\": \"<one sentence, ≤ 25 words>\"} ]\n\n"
                    + "Rules: never suggest a target the user can't fund within "
                    + "target_months given their disposable income. Return []  "
                    + "if no realistic goal applies (e.g., expenses exceed income).";

    private final AnthropicMessagesClient client;

    public AnthropicGoalLlmSuggestionAdvisor(
            @Value("${app.goal-suggestions.anthropic.url:https://api.anthropic.com/v1/messages}")
                    final String apiUrl,
            @Value("${app.goal-suggestions.anthropic.api-key:${app.category.anthropic.api-key:}}")
                    final String apiKey,
            @Value("${app.goal-suggestions.anthropic.model:claude-haiku-4-5-20251001}")
                    final String model,
            @Value("${app.goal-suggestions.anthropic.timeout-seconds:10}") final int timeoutSeconds) {
        this.client = new AnthropicMessagesClient(
                apiUrl, apiKey, model, SYSTEM_PROMPT, /*maxTokens=*/500, timeoutSeconds);
    }

    @Override
    public List<SuggestedGoal> suggest(final SpendSnapshot snapshot) {
        if (snapshot == null || !client.isConfigured()) return List.of();
        final String reply = client.complete(buildPrompt(snapshot));
        if (reply == null) return List.of();
        final int start = reply.indexOf('[');
        final int end = reply.lastIndexOf(']');
        if (start < 0 || end <= start) return List.of();
        try {
            final JsonNode arr = new ObjectMapper().readTree(reply.substring(start, end + 1));
            if (!arr.isArray()) return List.of();
            final List<SuggestedGoal> out = new ArrayList<>(arr.size());
            for (final JsonNode node : arr) {
                final String type = node.path("goal_type").asText(null);
                final String name = node.path("name").asText(null);
                final double amount = node.path("target_amount").asDouble(0);
                final int months = node.path("target_months").asInt(0);
                final String reasoning = node.path("reasoning").asText("");
                if (type == null || name == null || amount <= 0 || months <= 0 || months > 60) {
                    continue;
                }
                out.add(
                        new SuggestedGoal(
                                type, name, BigDecimal.valueOf(amount), months, reasoning));
            }
            return out;
        } catch (Exception ex) {
            LOGGER.debug("GoalLlmSuggestionAdvisor: parse failed: {}", ex.getMessage());
            return List.of();
        }
    }

    private static String buildPrompt(final SpendSnapshot s) {
        return String.format(
                "User snapshot:\n"
                        + "- monthly_income: $%s\n"
                        + "- monthly_expenses: $%s\n"
                        + "- liquid_assets: $%s\n"
                        + "- total_debt: $%s\n"
                        + "- estimated_disposable_per_month: $%s\n"
                        + "Suggest 2-4 realistic goals.",
                fmt(s.monthlyIncome()),
                fmt(s.monthlyExpenses()),
                fmt(s.liquidAssets()),
                fmt(s.totalDebt()),
                fmt(s.estimatedDisposable()));
    }

    private static String fmt(final BigDecimal v) {
        return v == null ? "0" : v.toPlainString();
    }
}
