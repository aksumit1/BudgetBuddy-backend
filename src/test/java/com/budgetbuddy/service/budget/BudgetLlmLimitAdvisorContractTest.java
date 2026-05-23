package com.budgetbuddy.service.budget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.budgetbuddy.service.BudgetSuggestionService.BudgetSuggestion;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * B-AI-1 contract: when the Anthropic API key is blank (the test
 * environment never has one), {@link AnthropicBudgetLlmLimitAdvisor}
 * must short-circuit and return the input list unchanged. This is the
 * graceful-degradation path that keeps the deterministic suggestion
 * service authoritative.
 */
@SuppressFBWarnings(
        value = "THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION",
        justification = "JUnit idiom")
final class BudgetLlmLimitAdvisorContractTest {

    @Test
    void annotateReturnsInputUnchangedWhenApiKeyBlank() {
        final AnthropicBudgetLlmLimitAdvisor advisor =
                new AnthropicBudgetLlmLimitAdvisor(
                        "https://api.anthropic.com/v1/messages",
                        "", // blank key → graceful degrade
                        "claude-haiku-4-5-20251001",
                        5);
        final List<BudgetSuggestion> input = List.of(suggestion("dining", "100"));
        final List<BudgetSuggestion> out = advisor.annotate(input);
        assertSame(input, out, "Blank-key path must short-circuit to the same list reference");
        assertNull(out.get(0).reasoning, "No annotation must be applied without an API key");
        assertEquals(new BigDecimal("100"), out.get(0).recommendedMonthlyLimit);
    }

    @Test
    void annotateHandlesEmptyInput() {
        final AnthropicBudgetLlmLimitAdvisor advisor =
                new AnthropicBudgetLlmLimitAdvisor(
                        "https://api.anthropic.com/v1/messages",
                        "",
                        "claude-haiku-4-5-20251001",
                        5);
        assertEquals(0, advisor.annotate(List.of()).size());
    }

    private static BudgetSuggestion suggestion(final String cat, final String limit) {
        final BudgetSuggestion s = new BudgetSuggestion();
        s.category = cat;
        s.recommendedMonthlyLimit = new BigDecimal(limit);
        s.medianMonthlySpend = new BigDecimal(limit);
        s.monthsObserved = 3;
        return s;
    }
}
