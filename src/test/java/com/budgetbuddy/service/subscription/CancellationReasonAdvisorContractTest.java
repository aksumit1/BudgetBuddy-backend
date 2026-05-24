package com.budgetbuddy.service.subscription;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.budgetbuddy.model.Subscription;
import com.budgetbuddy.service.SubscriptionInsightsService.CancellationRecommendation;
import com.budgetbuddy.service.SubscriptionInsightsService.CancellationRecommendation.Priority;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * AI-5 contract: the Anthropic cancellation-reason advisor MUST
 * short-circuit when the API key is blank (graceful degradation in
 * tests / dev). The deterministic reason must remain authoritative.
 */
@SuppressFBWarnings(
        value = "THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION",
        justification = "JUnit idiom")
final class CancellationReasonAdvisorContractTest {

    @Test
    void annotateReturnsInputUnchangedWhenApiKeyBlank() {
        final AnthropicCancellationReasonAdvisor advisor =
                new AnthropicCancellationReasonAdvisor(
                        "https://api.anthropic.com/v1/messages",
                        "",
                        "claude-haiku-4-5-20251001",
                        5);
        final CancellationRecommendation rec = recommendation();
        final List<CancellationRecommendation> input = List.of(rec);
        final List<CancellationRecommendation> out = advisor.annotate(input);
        assertSame(input, out, "Blank-key path must short-circuit to the same list reference");
        assertNull(out.get(0).getHumanMessage(),
                "No annotation must be applied without an API key");
        assertEquals("Unused for 90 days", out.get(0).getReason(),
                "Deterministic reason must stay untouched");
    }

    @Test
    void annotateHandlesEmptyInput() {
        final AnthropicCancellationReasonAdvisor advisor =
                new AnthropicCancellationReasonAdvisor(
                        "https://api.anthropic.com/v1/messages",
                        "",
                        "claude-haiku-4-5-20251001",
                        5);
        assertEquals(0, advisor.annotate(List.of()).size());
    }

    private static CancellationRecommendation recommendation() {
        final Subscription s = new Subscription();
        s.setSubscriptionId("sub-1");
        s.setUserId("u1");
        s.setMerchantName("HBO Max");
        s.setAmount(new BigDecimal("15.99"));
        return new CancellationRecommendation(
                s, "Unused for 90 days", new BigDecimal("191.88"), Priority.HIGH);
    }
}
