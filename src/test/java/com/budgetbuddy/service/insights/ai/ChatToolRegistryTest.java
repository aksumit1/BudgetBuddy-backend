package com.budgetbuddy.service.insights.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.budgetbuddy.service.insights.ai.PrivacyPreservingExtractor.SanitizedAnomaly;
import com.budgetbuddy.service.insights.ai.PrivacyPreservingExtractor.SanitizedBudget;
import com.budgetbuddy.service.insights.ai.PrivacyPreservingExtractor.SanitizedSnapshot;
import com.budgetbuddy.service.insights.ai.PrivacyPreservingExtractor.SanitizedSubscription;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pins the chat tool registry's contract:
 * <ul>
 *   <li>Each tool definition exposes the schema the LLM needs.</li>
 *   <li>Each tool executor returns valid JSON readable as input on
 *       the next turn.</li>
 *   <li>The tools surface only data that's already in the
 *       sanitised snapshot — the LLM cannot use them to escape the
 *       privacy filter.</li>
 *   <li>Unknown tools + bad input fail soft (error payload) instead
 *       of throwing.</li>
 * </ul>
 */
class ChatToolRegistryTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ChatToolRegistry registry = new ChatToolRegistry();

    @Test
    void toolDefinitions_includeFourExpectedTools() {
        final ArrayNode tools = registry.toolDefinitions();
        assertEquals(4, tools.size());
        for (final JsonNode tool : tools) {
            assertNotNull(tool.path("name").asText());
            assertNotNull(tool.path("description").asText());
            assertTrue(tool.path("input_schema").isObject());
        }
    }

    @Test
    void drillIntoCategory_returnsCategoryTotalAndMerchants() throws Exception {
        final SanitizedSnapshot snap = snapshotWith(
                Map.of("dining", new BigDecimal("250"), "groceries", new BigDecimal("400")),
                Map.of("netflix", new BigDecimal("16"), "uber one", new BigDecimal("10")),
                List.of(), List.of(), List.of());

        final ObjectNode input = mapper.createObjectNode();
        input.put("category", "dining");
        final String out = registry.drillIntoCategory(input, snap);

        final JsonNode parsed = mapper.readTree(out);
        assertEquals("dining", parsed.path("category").asText());
        assertEquals(250, parsed.path("total_90d").asInt());
        assertNotNull(parsed.path("known_merchants"));
    }

    @Test
    void drillIntoCategory_missingCategory_returnsErrorPayload() throws Exception {
        final SanitizedSnapshot snap = emptySnapshot();
        final String out = registry.drillIntoCategory(
                mapper.createObjectNode(), snap);
        final JsonNode parsed = mapper.readTree(out);
        assertTrue(parsed.has("error"));
    }

    @Test
    void listSubscriptionsOver_filtersAboveThreshold() throws Exception {
        final SanitizedSnapshot snap = snapshotWith(
                Map.of(), Map.of(), List.of(),
                List.of(
                        new SanitizedSubscription("netflix", new BigDecimal("16"), "monthly"),
                        new SanitizedSubscription("spotify", new BigDecimal("10"), "monthly"),
                        new SanitizedSubscription("adobe",   new BigDecimal("60"), "monthly")),
                List.of());

        final ObjectNode input = mapper.createObjectNode();
        input.put("amount", 15);
        final JsonNode parsed = mapper.readTree(
                registry.listSubscriptionsOver(input, snap));
        assertEquals(15.0, parsed.path("threshold").asDouble());
        // Two subs ≥ $15: netflix ($16) and adobe ($60).
        assertEquals(2, parsed.path("count").asInt());
    }

    @Test
    void listBudgetsOverPercent_filtersAboveThreshold() throws Exception {
        final SanitizedSnapshot snap = snapshotWith(
                Map.of(), Map.of(),
                List.of(
                        new SanitizedBudget("dining", new BigDecimal("500"),
                                new BigDecimal("400"), 80.0),
                        new SanitizedBudget("groceries", new BigDecimal("400"),
                                new BigDecimal("100"), 25.0)),
                List.of(),
                List.of());

        final ObjectNode input = mapper.createObjectNode();
        input.put("percent", 50);
        final JsonNode parsed = mapper.readTree(
                registry.listBudgetsOverPercent(input, snap));
        assertEquals(1, parsed.path("count").asInt());
        assertEquals("dining", parsed.path("budgets").get(0).path("category").asText());
    }

    @Test
    void listRecentAnomalies_filtersBySeverity() throws Exception {
        final SanitizedSnapshot snap = snapshotWith(
                Map.of(), Map.of(), List.of(),
                List.of(),
                List.of(
                        new SanitizedAnomaly("netflix", "streaming",
                                new BigDecimal("16"), "LOW", "STATISTICAL_OUTLIER"),
                        new SanitizedAnomaly("merchant_redacted", "dining",
                                new BigDecimal("250"), "HIGH", "AMOUNT_THRESHOLD")));

        final ObjectNode input = mapper.createObjectNode();
        input.put("severity", "high");
        final JsonNode parsed = mapper.readTree(
                registry.listAnomaliesBySeverity(input, snap));
        assertEquals("HIGH", parsed.path("severity").asText());
        assertEquals(1, parsed.path("count").asInt());
    }

    @Test
    void executeTool_unknownTool_returnsErrorPayload() throws Exception {
        final String out = registry.executeTool(
                "no_such_tool", mapper.createObjectNode(), emptySnapshot());
        assertTrue(mapper.readTree(out).has("error"));
    }

    // ---- helpers ----

    private SanitizedSnapshot emptySnapshot() {
        return new SanitizedSnapshot(
                Map.of(), Map.of(), Map.of(),
                List.of(), List.of(), List.of(), List.of(), 0, 0, "USD",
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    /** Snapshot factory used by tests — records are final so we build directly. */
    private SanitizedSnapshot snapshotWith(
            final Map<String, BigDecimal> byCategory,
            final Map<String, BigDecimal> byKnownMerchant,
            final List<SanitizedBudget> budgets,
            final List<SanitizedSubscription> subs,
            final List<SanitizedAnomaly> anomalies) {
        return new SanitizedSnapshot(
                byCategory, Map.of(), byKnownMerchant, subs,
                budgets, List.of(), anomalies, 0, 0, "USD",
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }
}
