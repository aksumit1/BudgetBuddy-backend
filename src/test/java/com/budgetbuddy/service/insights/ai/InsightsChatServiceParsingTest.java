package com.budgetbuddy.service.insights.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.budgetbuddy.repository.dynamodb.ChatMessageRepository;
import com.budgetbuddy.service.insights.InsightsContextFactory;
import com.budgetbuddy.service.insights.ai.InsightsChatService.ChatMode;
import com.budgetbuddy.service.insights.ai.InsightsChatService.LlmResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Pins the chat service's JSON-envelope parsing + mode resolution.
 * These don't need real LLM calls and exercise the most-likely-to-
 * break-silently parts of the pipeline.
 */
@ExtendWith(MockitoExtension.class)
class InsightsChatServiceParsingTest {

    @Mock private ChatMessageRepository chatRepo;
    @Mock private InsightsContextFactory contextFactory;
    private final PrivacyPreservingExtractor extractor = new PrivacyPreservingExtractor();
    private InsightsChatService svc;

    @BeforeEach
    void setUp() {
        svc = new InsightsChatService(chatRepo, contextFactory, extractor);
    }

    // ------------------------------------------------------------------
    // LLM JSON envelope parsing
    // ------------------------------------------------------------------

    @Test
    void parsesStructuredJson_withReplyAndFollowUps() {
        final String raw =
                "{\"reply\":\"You spent $1200 on dining last month.\","
                + "\"followUps\":[\"What about subscriptions?\",\"Compare to last quarter?\"]}";
        final LlmResponse out = svc.parseLlmJson(raw);
        assertEquals("You spent $1200 on dining last month.", out.reply());
        assertEquals(2, out.followUps().size());
        assertEquals("What about subscriptions?", out.followUps().getFirst());
    }

    @Test
    void parsesStructuredJson_evenWhenWrappedInProse() {
        // The model occasionally returns prose around the JSON despite
        // the system prompt; we extract the {…} substring.
        final String raw = "Sure! Here you go:\n{\"reply\":\"OK\",\"followUps\":[\"Next?\"]}\nLet me know if that helps.";
        final LlmResponse out = svc.parseLlmJson(raw);
        assertEquals("OK", out.reply());
        assertEquals(1, out.followUps().size());
    }

    @Test
    void fallsBackToPlainReply_whenNoJsonFound() {
        // If the LLM ignores the format instruction entirely, we
        // gracefully treat the whole thing as the reply with no
        // follow-ups, instead of erroring out.
        final String raw = "Just a plain text answer with no JSON anywhere.";
        final LlmResponse out = svc.parseLlmJson(raw);
        assertEquals(raw, out.reply());
        assertTrue(out.followUps().isEmpty());
    }

    @Test
    void capsFollowUpsAtThree() {
        // Even if the LLM returns 10 suggestions, the UI only renders
        // 3. Trim at the parser to keep the wire shape bounded.
        final String raw = "{\"reply\":\"x\","
                + "\"followUps\":[\"a\",\"b\",\"c\",\"d\",\"e\"]}";
        final LlmResponse out = svc.parseLlmJson(raw);
        assertEquals(3, out.followUps().size());
    }

    @Test
    void ignoresBlankFollowUps() {
        // Defensive: a half-completed suggestion shouldn't show up as
        // a tappable chip.
        final String raw = "{\"reply\":\"x\","
                + "\"followUps\":[\"  \",\"valid\",\"\"]}";
        final LlmResponse out = svc.parseLlmJson(raw);
        assertEquals(1, out.followUps().size());
        assertEquals("valid", out.followUps().getFirst());
    }

    @Test
    void emptyAndNullInputs_returnEmptyResponse() {
        assertEquals("", svc.parseLlmJson(null).reply());
        assertEquals("", svc.parseLlmJson("").reply());
    }

    // ------------------------------------------------------------------
    // ChatMode parsing
    // ------------------------------------------------------------------

    @Test
    void chatMode_defaultsToGeneral_forUnknown() {
        assertEquals(ChatMode.GENERAL, ChatMode.parse(null));
        assertEquals(ChatMode.GENERAL, ChatMode.parse(""));
        assertEquals(ChatMode.GENERAL, ChatMode.parse("not-a-mode"));
    }

    @Test
    void chatMode_parsesAllValues_caseInsensitively() {
        assertEquals(ChatMode.SPENDING, ChatMode.parse("spending"));
        assertEquals(ChatMode.BUDGET, ChatMode.parse("Budget"));
        assertEquals(ChatMode.GOAL, ChatMode.parse("GOAL"));
        assertEquals(ChatMode.SUBSCRIPTION, ChatMode.parse("Subscription"));
        assertEquals(ChatMode.ANOMALY, ChatMode.parse("anomaly"));
    }

    @Test
    void chatMode_focusInstructionIsNonEmpty_forEveryValue() {
        for (final ChatMode m : ChatMode.values()) {
            assertTrue(m.focusInstruction() != null && !m.focusInstruction().isBlank(),
                    "Mode " + m + " is missing focus instruction");
        }
    }
}
