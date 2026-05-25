package com.budgetbuddy.service.insights.ai;

import com.budgetbuddy.service.insights.ai.PrivacyPreservingExtractor.SanitizedAnomaly;
import com.budgetbuddy.service.insights.ai.PrivacyPreservingExtractor.SanitizedBudget;
import com.budgetbuddy.service.insights.ai.PrivacyPreservingExtractor.SanitizedSnapshot;
import com.budgetbuddy.service.insights.ai.PrivacyPreservingExtractor.SanitizedSubscription;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Defines + executes the tools the chat LLM can invoke during a
 * conversation turn. Each tool is a narrow, read-only accessor over
 * the already-sanitised {@link SanitizedSnapshot} — the LLM can
 * "drill down" without our backend exposing any new PII surface.
 *
 * <p>Tools shipped:
 * <ul>
 *   <li>{@code drill_into_category(category)} — returns the merchants
 *       within a category that the LLM hasn't already been shown.</li>
 *   <li>{@code list_subscriptions_over(amount)} — filters subscriptions
 *       whose monthly cost exceeds the threshold.</li>
 *   <li>{@code list_budgets_over_percent(percent)} — budgets where
 *       % used exceeds the threshold.</li>
 *   <li>{@code list_recent_anomalies_by_severity(severity)} — anomalies
 *       matching the requested severity tag.</li>
 * </ul>
 *
 * <p>Privacy contract — every tool reads from the already-sanitised
 * snapshot. There's no path here that re-fetches raw user data. The
 * LLM cannot use tool-use to escape the privacy filter.
 */
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Stateless service — no external mutables")
@Service
public class ChatToolRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChatToolRegistry.class);
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Return the Anthropic-format tool definitions for inclusion in
     * the chat request. The LLM sees these schemas and decides
     * whether to invoke any.
     */
    public ArrayNode toolDefinitions() {
        final ArrayNode tools = mapper.createArrayNode();
        tools.add(tool(
                "drill_into_category",
                "Return the known-brand merchants and total spending within a single category.",
                "category", "string", "Category name (lowercase, e.g. \"dining\")"));
        tools.add(tool(
                "list_subscriptions_over",
                "Return subscriptions whose monthly cost exceeds a dollar threshold.",
                "amount", "number", "Minimum monthly cost in dollars"));
        tools.add(tool(
                "list_budgets_over_percent",
                "Return budgets where % used exceeds a threshold.",
                "percent", "number", "Minimum percent used (0-100)"));
        tools.add(tool(
                "list_recent_anomalies_by_severity",
                "Return anomalies matching the given severity (LOW|MEDIUM|HIGH).",
                "severity", "string", "Severity level: LOW, MEDIUM, or HIGH"));
        return tools;
    }

    /**
     * Execute a tool the LLM requested. Returns a JSON string that
     * gets fed back into the conversation as the tool-result message.
     * Always returns a structured payload (never throws to the
     * caller) so the conversation loop can continue even on bad
     * input.
     */
    public String executeTool(
            final String toolName,
            final JsonNode input,
            final SanitizedSnapshot snapshot) {
        try {
            return switch (toolName) {
                case "drill_into_category" -> drillIntoCategory(input, snapshot);
                case "list_subscriptions_over" -> listSubscriptionsOver(input, snapshot);
                case "list_budgets_over_percent" -> listBudgetsOverPercent(input, snapshot);
                case "list_recent_anomalies_by_severity" ->
                        listAnomaliesBySeverity(input, snapshot);
                default -> errorPayload("unknown_tool: " + toolName);
            };
        } catch (final RuntimeException e) {
            LOGGER.warn("Tool '{}' threw: {}", toolName, e.getMessage());
            return errorPayload("tool_error: " + e.getMessage());
        }
    }

    /** Test helper — visible so unit tests don't need a full chat loop. */
    String drillIntoCategory(final JsonNode input, final SanitizedSnapshot snapshot) {
        final String category = input.path("category").asText("").toLowerCase(Locale.ROOT);
        if (category.isBlank()) {
            return errorPayload("missing required field: category");
        }
        final BigDecimal total =
                snapshot.spendingByCategory90d().getOrDefault(category, BigDecimal.ZERO);
        final ObjectNode out = mapper.createObjectNode();
        out.put("category", category);
        out.put("total_90d", total);
        // Show the known-brand merchants — these are already
        // allowlist-filtered in the snapshot, so this leaks no new
        // PII.
        final ObjectNode merchants = out.putObject("known_merchants");
        snapshot.spendingByKnownMerchant90d()
                .forEach((m, amt) -> merchants.put(m, amt));
        return out.toString();
    }

    String listSubscriptionsOver(final JsonNode input, final SanitizedSnapshot snapshot) {
        final double threshold = input.path("amount").asDouble(0.0);
        final List<SanitizedSubscription> matching = snapshot.subscriptions().stream()
                .filter(s -> s.monthlyCost() != null
                        && s.monthlyCost().doubleValue() >= threshold)
                .toList();
        final ObjectNode out = mapper.createObjectNode();
        out.put("threshold", threshold);
        out.put("count", matching.size());
        final ArrayNode items = out.putArray("subscriptions");
        for (final SanitizedSubscription s : matching) {
            final ObjectNode row = items.addObject();
            row.put("name", s.displayName());
            row.put("monthly_cost", s.monthlyCost());
            row.put("cycle", s.billingCycle());
        }
        return out.toString();
    }

    String listBudgetsOverPercent(final JsonNode input, final SanitizedSnapshot snapshot) {
        final double threshold = input.path("percent").asDouble(0.0);
        final List<SanitizedBudget> matching = snapshot.budgets().stream()
                .filter(b -> b.percentUsed() >= threshold)
                .toList();
        final ObjectNode out = mapper.createObjectNode();
        out.put("threshold_percent", threshold);
        out.put("count", matching.size());
        final ArrayNode items = out.putArray("budgets");
        for (final SanitizedBudget b : matching) {
            final ObjectNode row = items.addObject();
            row.put("category", b.category());
            row.put("limit", b.limit());
            row.put("spent", b.spent());
            row.put("percent_used", b.percentUsed());
        }
        return out.toString();
    }

    String listAnomaliesBySeverity(final JsonNode input, final SanitizedSnapshot snapshot) {
        final String severity =
                input.path("severity").asText("").toUpperCase(Locale.ROOT);
        if (severity.isBlank()) {
            return errorPayload("missing required field: severity");
        }
        final List<SanitizedAnomaly> matching = snapshot.recentAnomalies().stream()
                .filter(a -> severity.equals(a.severity()))
                .toList();
        final ObjectNode out = mapper.createObjectNode();
        out.put("severity", severity);
        out.put("count", matching.size());
        final ArrayNode items = out.putArray("anomalies");
        for (final SanitizedAnomaly a : matching) {
            final ObjectNode row = items.addObject();
            row.put("merchant", a.merchant());
            row.put("category", a.category());
            row.put("amount", a.amount());
            row.put("type", a.type());
        }
        return out.toString();
    }

    /** Tool definition as Anthropic expects — name, description, input_schema. */
    private ObjectNode tool(
            final String name, final String description,
            final String paramName, final String paramType, final String paramDescription) {
        final ObjectNode tool = mapper.createObjectNode();
        tool.put("name", name);
        tool.put("description", description);
        final ObjectNode schema = tool.putObject("input_schema");
        schema.put("type", "object");
        final ObjectNode properties = schema.putObject("properties");
        final ObjectNode p = properties.putObject(paramName);
        p.put("type", paramType);
        p.put("description", paramDescription);
        final ArrayNode required = schema.putArray("required");
        required.add(paramName);
        return tool;
    }

    private String errorPayload(final String message) {
        final ObjectNode err = mapper.createObjectNode();
        err.put("error", message);
        return err.toString();
    }
}
