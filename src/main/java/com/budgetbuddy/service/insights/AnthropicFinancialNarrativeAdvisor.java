package com.budgetbuddy.service.insights;

import com.budgetbuddy.service.llm.AnthropicMessagesClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * AI-4: Anthropic-backed financial narrative. Activates when
 * {@code app.financial-narrative.anthropic.enabled=true}.
 *
 * <p>Returns null when the API key isn't configured or the LLM call
 * degrades — every iOS surface that consumes this is responsible for
 * rendering its fallback (the existing card stack) on a null narrative.
 */
@Service
@ConditionalOnProperty(name = "app.financial-narrative.anthropic.enabled", havingValue = "true")
public class AnthropicFinancialNarrativeAdvisor implements FinancialNarrativeAdvisor {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(AnthropicFinancialNarrativeAdvisor.class);

    private static final String SYSTEM_PROMPT =
            "Write a one-paragraph (3-5 sentence, <80 word) summary of a "
                    + "user's current financial health from the snapshot below. "
                    + "Be honest but warm — no scolding, no marketing tone. "
                    + "Cite at most one concrete dollar number; let the cards "
                    + "carry the rest. Respond with ONLY a JSON object:\n"
                    + "{\"narrative\": \"<paragraph>\", \"tone\": "
                    + "\"POSITIVE|NEUTRAL|CAUTIOUS\"}\n"
                    + "Rules: never invent numbers the snapshot didn't supply. "
                    + "If the snapshot is empty or impossible to interpret, "
                    + "return {}.";

    private final AnthropicMessagesClient client;

    public AnthropicFinancialNarrativeAdvisor(
            @Value("${app.financial-narrative.anthropic.url:https://api.anthropic.com/v1/messages}")
                    final String apiUrl,
            @Value("${app.financial-narrative.anthropic.api-key:${app.category.anthropic.api-key:}}")
                    final String apiKey,
            @Value("${app.financial-narrative.anthropic.model:claude-haiku-4-5-20251001}")
                    final String model,
            @Value("${app.financial-narrative.anthropic.timeout-seconds:10}")
                    final int timeoutSeconds) {
        this.client = new AnthropicMessagesClient(
                apiUrl, apiKey, model, SYSTEM_PROMPT, /*maxTokens=*/300, timeoutSeconds);
    }

    @Override
    public Narrative narrate(final SummarySnapshot snapshot) {
        if (snapshot == null || !client.isConfigured()) return null;
        final String reply = client.complete(buildPrompt(snapshot));
        if (reply == null) return null;
        final int start = reply.indexOf('{');
        final int end = reply.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        try {
            final JsonNode node = new ObjectMapper().readTree(reply.substring(start, end + 1));
            if (node.size() == 0) return null;
            final String text = node.path("narrative").asText(null);
            final String tone = node.path("tone").asText("NEUTRAL");
            if (text == null || text.isBlank()) return null;
            return new Narrative(text.trim(), tone);
        } catch (Exception ex) {
            LOGGER.debug("FinancialNarrativeAdvisor parse failed: {}", ex.getMessage());
            return null;
        }
    }

    private static String buildPrompt(final SummarySnapshot s) {
        return String.format(
                "Snapshot:\n"
                        + "- monthly_income: %s\n"
                        + "- monthly_expenses: %s\n"
                        + "- liquid_assets: %s\n"
                        + "- cash_runway_days: %s\n"
                        + "- cash_flow_status: %s\n"
                        + "- active_budgets: %d (exhausting_soon: %s)\n"
                        + "- active_goals: %d\n"
                        + "- anomaly_count: %d\n"
                        + "- expense_reductions: %d\n"
                        + "- missed_payments: %d\n"
                        + "- high_interest_alerts: %d\n"
                        + "- subscription_creep: %s",
                String.valueOf(s.monthlyIncome),
                String.valueOf(s.monthlyExpenses),
                String.valueOf(s.liquidAssets),
                String.valueOf(s.cashRunwayDays),
                String.valueOf(s.cashFlowStatus),
                s.activeBudgetsCount,
                String.valueOf(s.budgetsExhaustingSoonCount),
                s.activeGoalsCount,
                s.anomalyCount,
                s.expenseReductionCount,
                s.missedPaymentCount,
                s.highInterestCount,
                String.valueOf(s.subscriptionCreepStatus));
    }
}
