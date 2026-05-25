package com.budgetbuddy.service.insights.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.budgetbuddy.service.insights.ai.InsightsChatService.ChatMode;
import com.budgetbuddy.service.insights.ai.PromptEvaluationHarness.PromptScore;
import org.junit.jupiter.api.Test;

/**
 * Pins the prompt evaluator's scoring rules so a future change that
 * accidentally drops a privacy guardrail or balloons prompt size is
 * caught here, before it ships.
 */
class PromptEvaluationHarnessTest {

    private final PromptEvaluationHarness harness = new PromptEvaluationHarness();

    @Test
    void scoresWellFormedPrompt_aboveThreshold() {
        // A prompt that has the guardrails, focus, and JSON envelope.
        final String prompt = """
                You are an assistant. Use ONLY the snapshot below.
                Never invent transactions. You don't know the user's name.
                Mode focus: spending breakdown.
                Output as JSON with "reply" and "followUps" fields.
                """;
        final PromptScore score = harness.score(prompt, ChatMode.SPENDING);
        assertTrue(score.composite() >= 0.95,
                "Well-formed prompt should score >= 0.95, got " + score.composite());
        assertEquals(1.0, score.guardrailCoverage());
        assertEquals(1.0, score.envelopePresent());
    }

    @Test
    void scoresDropWhenGuardrailsMissing() {
        // Prompt missing privacy rules — major score penalty.
        final String prompt = "Answer the user's question about spending. Use the JSON snapshot. "
                + "Output \"reply\" and \"followUps\".";
        final PromptScore score = harness.score(prompt, ChatMode.SPENDING);
        assertTrue(score.guardrailCoverage() < 1.0,
                "Missing guardrails should drop coverage below 1.0");
        assertTrue(score.composite() < 0.8,
                "Prompt without guardrails should not score near perfect; got "
                        + score.composite());
        assertTrue(score.issues().stream().anyMatch(i -> i.startsWith("missing-guardrail")),
                "Issues should call out missing guardrails");
    }

    @Test
    void scoresDropWhenJsonEnvelopeMissing() {
        // Has guardrails + focus but no JSON envelope instruction.
        final String prompt = "You are an assistant. Never invent. Use only snapshot. "
                + "You don't know the user's name. Focus on spending.";
        final PromptScore score = harness.score(prompt, ChatMode.SPENDING);
        assertEquals(0.0, score.envelopePresent(),
                "No JSON envelope should be detected");
        assertTrue(score.issues().contains("missing-json-envelope"));
    }

    @Test
    void penalisesOversizedPrompts() {
        // Same content as the well-formed prompt, padded out beyond 3k chars.
        final StringBuilder huge = new StringBuilder("""
                You are an assistant. Use ONLY the snapshot below.
                Never invent transactions. You don't know the user's name.
                Mode focus: spending breakdown.
                Output as JSON with "reply" and "followUps" fields.
                """);
        for (int i = 0; i < 200; i++) {
            huge.append("Extra padding for size testing. ");
        }
        final PromptScore score = harness.score(huge.toString(), ChatMode.SPENDING);
        assertTrue(score.composite() < 1.0,
                "Oversized prompt should incur a size penalty");
        assertTrue(score.estimatedTokens() > 800,
                "Estimated tokens should reflect the bloat");
    }

    @Test
    void emptyPrompt_returnsZeroScore() {
        final PromptScore score = harness.score("", ChatMode.GENERAL);
        assertEquals(0.0, score.composite());
        assertTrue(score.issues().contains("empty-prompt"));
    }

    @Test
    void registryPrompts_scoreWell_forAllModes() {
        // The shipped PromptRegistry should produce well-scoring
        // prompts for every mode — sanity check that we shipped a
        // properly-formed baseline.
        final PromptRegistry registry = new PromptRegistry();
        final PrivacyPreservingExtractor.SanitizedSnapshot empty =
                new PrivacyPreservingExtractor.SanitizedSnapshot(
                        java.util.Map.of(), java.util.Map.of(), java.util.Map.of(),
                        java.util.List.of(), java.util.List.of(),
                        java.util.List.of(), java.util.List.of(),
                        0, 0, "USD",
                        java.math.BigDecimal.ZERO,
                        java.math.BigDecimal.ZERO,
                        java.math.BigDecimal.ZERO);
        for (final ChatMode mode : ChatMode.values()) {
            final String prompt = registry.buildSystemPrompt(mode, empty);
            final PromptScore score = harness.score(prompt, mode);
            assertTrue(score.composite() >= 0.85,
                    "Mode " + mode + " baseline prompt should score >= 0.85, got "
                            + score.composite() + "; issues: " + score.issues());
        }
    }

    @Test
    void compare_picksHigherScoringPrompt() {
        final PromptRegistry registry = new PromptRegistry();
        final PrivacyPreservingExtractor.SanitizedSnapshot empty =
                new PrivacyPreservingExtractor.SanitizedSnapshot(
                        java.util.Map.of(), java.util.Map.of(), java.util.Map.of(),
                        java.util.List.of(), java.util.List.of(),
                        java.util.List.of(), java.util.List.of(),
                        0, 0, "USD",
                        java.math.BigDecimal.ZERO,
                        java.math.BigDecimal.ZERO,
                        java.math.BigDecimal.ZERO);
        final String good = registry.buildSystemPrompt(ChatMode.SPENDING, empty);
        final String bad = "Answer questions about spending."; // missing everything
        final var comparison = harness.compare(
                new PromptRegistry.PromptTemplate(ChatMode.SPENDING, "good", ""),
                good,
                new PromptRegistry.PromptTemplate(ChatMode.SPENDING, "bad", ""),
                bad,
                ChatMode.SPENDING);
        assertNotNull(comparison);
        assertEquals("good", comparison.winner());
    }
}
