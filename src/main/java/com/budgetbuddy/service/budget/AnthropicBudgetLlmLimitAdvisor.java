package com.budgetbuddy.service.budget;

import com.budgetbuddy.service.BudgetSuggestionService.BudgetSuggestion;
import com.budgetbuddy.service.llm.AnthropicMessagesClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * B-AI-1: Anthropic-backed reasoning annotator for budget suggestions.
 * Activates when {@code app.budget-suggestions.anthropic.enabled=true}.
 *
 * <p>The rule-based {@link BudgetSuggestion#recommendedMonthlyLimit} stays
 * authoritative — the LLM only adds a short human-readable explanation
 * (e.g. "Median of $73 over 6 months; rounded up to $80 with a 10% buffer
 * to absorb the dining-out spike in March"). On any failure the suggestion
 * is returned unannotated rather than hidden.
 */
@Service
@ConditionalOnProperty(name = "app.budget-suggestions.anthropic.enabled", havingValue = "true")
public class AnthropicBudgetLlmLimitAdvisor implements BudgetLlmLimitAdvisor {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(AnthropicBudgetLlmLimitAdvisor.class);

    private static final String SYSTEM_PROMPT =
            "You explain budget recommendations in one short sentence. "
                    + "Given a category, the median monthly spend, the months "
                    + "observed, and the recommended limit, write ONE plain "
                    + "sentence (<25 words) the user will see when reviewing "
                    + "the recommendation. Respond with ONLY a JSON array of "
                    + "{\"category\": ..., \"reasoning\": ...}. Do NOT change "
                    + "the limit; do NOT add any other fields.";

    private final AnthropicMessagesClient client;

    public AnthropicBudgetLlmLimitAdvisor(
            @Value("${app.budget-suggestions.anthropic.url:https://api.anthropic.com/v1/messages}")
                    final String apiUrl,
            @Value("${app.budget-suggestions.anthropic.api-key:${app.category.anthropic.api-key:}}")
                    final String apiKey,
            @Value("${app.budget-suggestions.anthropic.model:claude-haiku-4-5-20251001}")
                    final String model,
            @Value("${app.budget-suggestions.anthropic.timeout-seconds:8}")
                    final int timeoutSeconds) {
        this.client = new AnthropicMessagesClient(
                apiUrl, apiKey, model, SYSTEM_PROMPT, /*maxTokens=*/400, timeoutSeconds);
    }

    @Override
    public List<BudgetSuggestion> annotate(final List<BudgetSuggestion> rulesBased) {
        if (rulesBased == null || rulesBased.isEmpty() || !client.isConfigured()) {
            return rulesBased;
        }
        final String reply = client.complete(buildPrompt(rulesBased));
        if (reply == null) return rulesBased;
        final int start = reply.indexOf('[');
        final int end = reply.lastIndexOf(']');
        if (start < 0 || end <= start) return rulesBased;
        try {
            final JsonNode arr = new ObjectMapper().readTree(reply.substring(start, end + 1));
            if (!arr.isArray()) return rulesBased;
            final java.util.Map<String, String> byCat = new java.util.HashMap<>();
            for (final JsonNode node : arr) {
                final String c = node.path("category").asText(null);
                final String r = node.path("reasoning").asText(null);
                if (c != null && r != null && !r.isBlank()) byCat.put(c, r.trim());
            }
            // Don't mutate the input list — return a new list with reasoning
            // applied. Mutation would surprise tests pinning the rule-based
            // output, and the BudgetSuggestion type is intentionally a public
            // DTO so equality semantics matter.
            final List<BudgetSuggestion> out = new ArrayList<>(rulesBased.size());
            for (final BudgetSuggestion s : rulesBased) {
                final BudgetSuggestion copy = new BudgetSuggestion();
                copy.category = s.category;
                copy.recommendedMonthlyLimit = s.recommendedMonthlyLimit;
                copy.medianMonthlySpend = s.medianMonthlySpend;
                copy.monthsObserved = s.monthsObserved;
                copy.reasoning = byCat.getOrDefault(s.category, s.reasoning);
                out.add(copy);
            }
            return out;
        } catch (Exception ex) {
            LOGGER.debug("BudgetLlmLimitAdvisor: parse failed: {}", ex.getMessage());
            return rulesBased;
        }
    }

    private static String buildPrompt(final List<BudgetSuggestion> in) {
        final StringBuilder sb = new StringBuilder(256);
        sb.append("Suggestions to explain:\n");
        for (final BudgetSuggestion s : in) {
            sb.append("- category=").append(s.category)
                    .append(", median_monthly_spend=$").append(s.medianMonthlySpend)
                    .append(", months_observed=").append(s.monthsObserved)
                    .append(", recommended_limit=$").append(s.recommendedMonthlyLimit)
                    .append('\n');
        }
        return sb.toString();
    }
}
