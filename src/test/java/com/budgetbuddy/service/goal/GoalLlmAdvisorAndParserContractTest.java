package com.budgetbuddy.service.goal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.budgetbuddy.service.goal.GoalLlmSuggestionAdvisor.SpendSnapshot;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * G-AI-1 + G-AI-2 contract: both Anthropic-backed services must
 * gracefully short-circuit when no API key is configured so the
 * deterministic paths stay authoritative in the test environment.
 */
@SuppressFBWarnings(
        value = "THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION",
        justification = "JUnit idiom")
final class GoalLlmAdvisorAndParserContractTest {

    @Test
    void suggestionAdvisorReturnsEmptyWhenKeyBlank() {
        final AnthropicGoalLlmSuggestionAdvisor advisor =
                new AnthropicGoalLlmSuggestionAdvisor(
                        "https://api.anthropic.com/v1/messages",
                        "",
                        "claude-haiku-4-5-20251001",
                        5);
        assertEquals(
                0,
                advisor.suggest(
                                new SpendSnapshot(
                                        "u1",
                                        new BigDecimal("4000"),
                                        new BigDecimal("3000"),
                                        new BigDecimal("8000"),
                                        new BigDecimal("0"),
                                        new BigDecimal("1000")))
                        .size(),
                "Blank-key path must return an empty list, never throw");
    }

    @Test
    void nlpParserReturnsEmptyWhenKeyBlank() {
        final AnthropicGoalNaturalLanguageParser parser =
                new AnthropicGoalNaturalLanguageParser(
                        "https://api.anthropic.com/v1/messages",
                        "",
                        "claude-haiku-4-5-20251001",
                        5);
        final Optional<GoalNaturalLanguageParser.ParsedGoal> result =
                parser.parse("save $5000 for a vacation next March");
        assertTrue(result.isEmpty(), "No API key → must produce empty Optional, not crash");
    }

    @Test
    void nlpParserRejectsBlankInput() {
        final AnthropicGoalNaturalLanguageParser parser =
                new AnthropicGoalNaturalLanguageParser(
                        "https://api.anthropic.com/v1/messages",
                        "configured-but-irrelevant",
                        "claude-haiku-4-5-20251001",
                        5);
        assertTrue(parser.parse("").isEmpty());
        assertTrue(parser.parse(null).isEmpty());
        assertTrue(parser.parse("   ").isEmpty());
    }
}
