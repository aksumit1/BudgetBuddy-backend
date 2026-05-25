package com.budgetbuddy.service.insights.ai;

import com.budgetbuddy.service.insights.ai.InsightsChatService.ChatMode;
import com.budgetbuddy.service.insights.ai.PromptRegistry.PromptTemplate;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Offline A/B prompt evaluation. Lets the team run two candidate
 * prompt templates against the same fixture inputs (sample
 * conversations + sample snapshots) and compute deterministic
 * differential metrics without a real LLM round-trip.
 *
 * <p>What this measures (deterministic, no LLM):
 * <ul>
 *   <li>Token-budget proxy — character count of the system prompt
 *       (Anthropic tokens ≈ chars/4). Cheaper prompt = lower cost.</li>
 *   <li>Rules-coverage — whether the prompt contains the privacy
 *       guardrails the privacy contract requires.</li>
 *   <li>Mode-focus match — whether the prompt mentions the mode's
 *       expected focus areas (categories / budgets / etc.).</li>
 *   <li>JSON-format instruction — whether the structured-output
 *       envelope is present so the parser doesn't fall back to plain text.</li>
 * </ul>
 *
 * <p>What this DOES NOT measure (needs real LLM, separate harness):
 * faithfulness, helpfulness, hallucination rate. Those require
 * human-in-the-loop or a judge-model pipeline — both belong in
 * dedicated infrastructure, not this unit-testable harness.
 *
 * <p>Used from tests: provides a stable score function so a prompt
 * change that drops privacy guardrails or balloons size is caught
 * before it merges.
 */
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Stateless service — no external mutables")
@Service
public class PromptEvaluationHarness {

    private static final Logger LOGGER = LoggerFactory.getLogger(PromptEvaluationHarness.class);

    /** Critical privacy guardrails the system prompt MUST include. */
    private static final List<Pattern> REQUIRED_GUARDRAILS = List.of(
            Pattern.compile("never invent", Pattern.CASE_INSENSITIVE),
            Pattern.compile("only.*snapshot", Pattern.CASE_INSENSITIVE),
            Pattern.compile("don't know.*name", Pattern.CASE_INSENSITIVE));

    /** Mode-specific focus keywords expected in the system prompt. */
    private static final java.util.Map<ChatMode, List<String>> MODE_FOCUS_KEYWORDS =
            java.util.Map.of(
                    ChatMode.SPENDING, List.of("spending"),
                    ChatMode.BUDGET, List.of("budget"),
                    ChatMode.GOAL, List.of("goal"),
                    ChatMode.SUBSCRIPTION, List.of("subscription"),
                    ChatMode.ANOMALY, List.of("anomal"));

    /** Score one prompt — used to compare A vs B. Higher = better. */
    public PromptScore score(
            final String renderedPrompt,
            final ChatMode mode) {
        if (renderedPrompt == null || renderedPrompt.isBlank()) {
            return new PromptScore(0, 0, 0, 0, 0, List.of("empty-prompt"));
        }

        final List<String> issues = new ArrayList<>();

        final int charCount = renderedPrompt.length();
        final int estimatedTokens = (charCount + 3) / 4;

        int guardrailHits = 0;
        for (final Pattern p : REQUIRED_GUARDRAILS) {
            if (p.matcher(renderedPrompt).find()) {
                guardrailHits++;
            } else {
                issues.add("missing-guardrail:" + p.pattern());
            }
        }
        final double guardrailCoverage =
                (double) guardrailHits / REQUIRED_GUARDRAILS.size();

        int focusHits = 0;
        final List<String> expected =
                MODE_FOCUS_KEYWORDS.getOrDefault(mode, List.of());
        for (final String kw : expected) {
            if (renderedPrompt.toLowerCase(Locale.ROOT).contains(kw)) {
                focusHits++;
            } else {
                issues.add("missing-focus:" + kw);
            }
        }
        final double focusCoverage =
                expected.isEmpty() ? 1.0 : (double) focusHits / expected.size();

        final boolean hasJsonEnvelope = renderedPrompt.contains("\"reply\"")
                && renderedPrompt.contains("\"followUps\"");
        if (!hasJsonEnvelope) {
            issues.add("missing-json-envelope");
        }

        // Composite score: weight privacy + format heavily; size as a
        // soft penalty (every 1k chars beyond 2k = -0.05).
        double composite = guardrailCoverage * 0.5
                + focusCoverage * 0.2
                + (hasJsonEnvelope ? 0.3 : 0.0);
        if (charCount > 2_000) {
            composite -= Math.min(0.2, (charCount - 2_000) / 1_000.0 * 0.05);
        }
        composite = Math.max(0.0, Math.min(1.0, composite));

        return new PromptScore(
                Math.round(composite * 1000) / 1000.0,
                estimatedTokens,
                Math.round(guardrailCoverage * 1000) / 1000.0,
                Math.round(focusCoverage * 1000) / 1000.0,
                hasJsonEnvelope ? 1.0 : 0.0,
                issues);
    }

    /**
     * Compare two prompt versions side-by-side. Logs the winner +
     * delta so a CI/dev workflow can refuse to merge a regression.
     */
    public ABComparison compare(
            final PromptTemplate a, final String renderedA,
            final PromptTemplate b, final String renderedB,
            final ChatMode mode) {
        final PromptScore scoreA = score(renderedA, mode);
        final PromptScore scoreB = score(renderedB, mode);
        final String winner;
        if (scoreA.composite() > scoreB.composite()) {
            winner = a.version();
        } else if (scoreB.composite() > scoreA.composite()) {
            winner = b.version();
        } else {
            winner = "tie";
        }
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info(
                    "Prompt A/B mode={}: {} ({:.3f}) vs {} ({:.3f}) → winner: {}",
                    mode, a.version(), scoreA.composite(),
                    b.version(), scoreB.composite(), winner);
        }
        return new ABComparison(scoreA, scoreB, winner);
    }

    /** Score components for one prompt rendering. */
    public record PromptScore(
            double composite,
            int estimatedTokens,
            double guardrailCoverage,
            double focusCoverage,
            double envelopePresent,
            List<String> issues) {}

    /** Output of comparing two prompts. */
    public record ABComparison(PromptScore a, PromptScore b, String winner) {}
}
