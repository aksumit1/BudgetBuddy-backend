package com.budgetbuddy.service.insights.ai;

import com.budgetbuddy.service.insights.ai.InsightsChatService.ChatMode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.EnumMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Versioned registry of prompt templates, keyed by chat mode. Lets us:
 * <ul>
 *   <li>Diff prompt changes in git as code, not magic strings sprinkled
 *       across services.</li>
 *   <li>Track which prompt version was used in logs/metrics, so
 *       "regression after prompt change" is bisectable.</li>
 *   <li>Roll back via a single annotation bump if a new prompt
 *       performs worse.</li>
 *   <li>Lay the groundwork for A/B testing prompts in production
 *       (future: pick template by user-bucket or feature flag).</li>
 * </ul>
 *
 * <p>Today only the "system" template per mode is registered. The
 * privacy + format-envelope rules are factored out as a shared header
 * so a mode template stays focused on the FOCUS instruction.
 */
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Stateless service — no fields to defensive-copy")
@Service
public class PromptRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(PromptRegistry.class);

    /** Bump when ANY mode template changes, so observability can group runs. */
    public static final String REGISTRY_VERSION = "2026.05.23-1";

    /** Per-mode template. Each carries its own version for granular rollback. */
    public record PromptTemplate(ChatMode mode, String version, String focusInstruction) {}

    private final Map<ChatMode, PromptTemplate> templates;
    private final ObjectMapper mapper = new ObjectMapper();

    public PromptRegistry() {
        templates = new EnumMap<>(ChatMode.class);
        templates.put(ChatMode.GENERAL, new PromptTemplate(
                ChatMode.GENERAL, "v1",
                "Answer the user's question using whichever section of the snapshot is most "
                        + "relevant. Default to the spending breakdown when ambiguous."));
        templates.put(ChatMode.SPENDING, new PromptTemplate(
                ChatMode.SPENDING, "v1",
                "Focus on the spending breakdown (spendingByCategory90d, spendingByMonth, "
                        + "spendingByKnownMerchant90d). Help the user understand WHERE their "
                        + "money goes and surface the largest 1-3 categories or merchants."));
        templates.put(ChatMode.BUDGET, new PromptTemplate(
                ChatMode.BUDGET, "v1",
                "Focus on the budgets section. Highlight categories over 80%/100% of limit, "
                        + "compare to spending in spendingByCategory90d, and suggest realistic "
                        + "adjustments. If no budgets are set, gently suggest creating one."));
        templates.put(ChatMode.GOAL, new PromptTemplate(
                ChatMode.GOAL, "v1",
                "Focus on goal progress signals (transactionCount90d, monthsCovered, spending "
                        + "vs income ratio). Encourage realistic monthly milestones rather "
                        + "than perfectionism."));
        templates.put(ChatMode.SUBSCRIPTION, new PromptTemplate(
                ChatMode.SUBSCRIPTION, "v1",
                "Focus on the subscriptions section. Surface duplicates, anonymised "
                        + "subscriptions (subscription_N) the user may have forgotten about, "
                        + "or high-cost subs that dwarf the rest. Respect user autonomy on "
                        + "what to cancel — don't be preachy."));
        templates.put(ChatMode.ANOMALY, new PromptTemplate(
                ChatMode.ANOMALY, "v1",
                "Focus on recentAnomalies. Explain what's unusual about each and suggest "
                        + "whether the user should investigate. Be explicit that an anomaly "
                        + "is statistical, not evidence of fraud."));
    }

    /** Look up the template for a mode. Falls back to GENERAL if unknown. */
    public PromptTemplate templateFor(final ChatMode mode) {
        return templates.getOrDefault(mode, templates.get(ChatMode.GENERAL));
    }

    /**
     * Build the full system prompt for one turn — combines the
     * mode-specific focus, the shared privacy/format header, and the
     * sanitised snapshot. This is what the LLM sees as `system`.
     */
    public String buildSystemPrompt(
            final ChatMode mode,
            final PrivacyPreservingExtractor.SanitizedSnapshot snapshot) {
        final PromptTemplate tmpl = templateFor(mode);
        final String snapshotJson;
        try {
            snapshotJson = mapper.writeValueAsString(snapshot);
        } catch (final Exception e) {
            throw new RuntimeException("Failed to serialize snapshot", e);
        }
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(
                    "PromptRegistry: building system prompt mode={} tmplVersion={} registry={}",
                    mode, tmpl.version(), REGISTRY_VERSION);
        }
        return "You are BudgetBuddy's financial-insights assistant. You answer the user's "
                + "questions about THEIR spending and finances using ONLY the JSON snapshot "
                + "below.\n\n"
                + "Mode focus: " + tmpl.focusInstruction() + "\n\n"
                + "Hard rules — never break:\n"
                + " - Never invent specific transactions, merchants, or amounts not present "
                + "in the snapshot. If asked about something not there, say so plainly.\n"
                + " - Quote dollar figures only as found in the snapshot. Round naturally.\n"
                + " - Keep the reply under 120 words. Be specific, actionable.\n"
                + " - No emoji, no markdown headers. Plain sentences.\n"
                + " - You don't know the user's name, email, or any personal identifier.\n"
                + " - Currency: " + snapshot.currency() + "\n\n"
                + "Output format — return ONLY a JSON object, nothing else:\n"
                + "{\n"
                + "  \"reply\": \"<your one-paragraph reply, under 120 words>\",\n"
                + "  \"followUps\": [\n"
                + "    \"<follow-up question 1, short, user POV>\",\n"
                + "    \"<follow-up question 2>\",\n"
                + "    \"<follow-up question 3, optional>\"\n"
                + "  ]\n"
                + "}\n\n"
                + "Data snapshot (all monetary values in " + snapshot.currency() + "):\n"
                + snapshotJson;
    }
}
