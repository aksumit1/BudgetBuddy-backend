package com.budgetbuddy.service.insights.ai;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.budgetbuddy.model.Subscription;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.repository.dynamodb.ChatMessageRepository;
import com.budgetbuddy.service.insights.InsightsContext;
import com.budgetbuddy.service.insights.InsightsContextFactory;
import com.budgetbuddy.service.insights.ai.InsightsChatService.ChatTurnRequest;
import com.budgetbuddy.service.insights.ai.PrivacyPreservingExtractor.SanitizedSnapshot;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link InsightsChatService}. Network-touching paths
 * are intentionally not exercised (no real LLM calls in unit tests).
 * The tests focus on:
 * <ul>
 *   <li>Misconfiguration handling (no API key → IllegalStateException)</li>
 *   <li>Input validation (null/blank message rejected)</li>
 *   <li>System-prompt construction privacy contract — the snapshot
 *       JSON is what the LLM sees, so anything that ends up in the
 *       system prompt must respect the privacy rules.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class InsightsChatServiceTest {

    @Mock private ChatMessageRepository chatRepo;
    @Mock private InsightsContextFactory contextFactory;
    private final PrivacyPreservingExtractor extractor = new PrivacyPreservingExtractor();

    private InsightsChatService svc;

    @BeforeEach
    void setUp() {
        svc = new InsightsChatService(chatRepo, contextFactory, extractor);
        lenient().when(contextFactory.buildFor("u1")).thenReturn(
                new InsightsContext("u1", LocalDate.parse("2026-05-23"),
                        List.of(), List.of(), List.of()));
    }

    @Test
    void chat_rejectsNullRequest() {
        assertThrows(IllegalArgumentException.class, () -> svc.chat(null));
    }

    @Test
    void chat_rejectsNullUserId() {
        assertThrows(IllegalArgumentException.class,
                () -> svc.chat(new ChatTurnRequest(null, "conv-1", "hi")));
    }

    @Test
    void chat_rejectsBlankMessage() {
        assertThrows(IllegalArgumentException.class,
                () -> svc.chat(new ChatTurnRequest("u1", null, "   ")));
    }

    @Test
    void chat_rejectsWhenApiKeyMissing() throws Exception {
        // Without an API key, the service must fail-fast rather than
        // attempt an unsigned LLM call.
        setField("apiKey", "");
        assertThrows(IllegalStateException.class,
                () -> svc.chat(new ChatTurnRequest("u1", null, "hello")));
    }

    @Test
    void systemPrompt_includesSnapshotJson_andPrivacyRules() throws Exception {
        // The system prompt is what the LLM sees on every turn. Verify
        // the privacy-rule statements are present so the model
        // understands its constraints.
        final InsightsContext ctx = new InsightsContext(
                "u1", LocalDate.parse("2026-05-23"),
                List.of(tx("Netflix", "streaming", "-15", "2026-05-01")),
                List.of(), List.of());
        final SanitizedSnapshot snap = extractor.extract(ctx);
        final String prompt = svc.buildSystemPrompt(snap);

        assertNotNull(prompt);
        assertTrue(prompt.contains("Never invent"),
                "Prompt must instruct LLM not to fabricate transactions");
        assertTrue(prompt.contains("snapshot"),
                "Prompt must reference the snapshot as the source of truth");
        // The snapshot JSON should be embedded.
        assertTrue(prompt.contains("netflix") || prompt.contains("\"netflix\""),
                "Snapshot JSON should be embedded in the prompt");
        // No raw user identifier should be in the prompt — the
        // snapshot doesn't carry userId either.
        assertFalse(prompt.contains("\"u1\""),
                "User id must not leak into the system prompt");
    }

    @Test
    void systemPrompt_neverContainsPrivateMerchantNames() throws Exception {
        // Verify the prompt-builder doesn't bypass the extractor.
        final InsightsContext ctx = new InsightsContext(
                "u1", LocalDate.parse("2026-05-23"),
                List.of(
                        tx("Dr. Smith Family Practice", "health", "-200", "2026-05-01"),
                        tx("Bob's Local Diner", "dining", "-30", "2026-05-02")),
                List.of(), List.of(privateSub()));
        final SanitizedSnapshot snap = extractor.extract(ctx);
        final String prompt = svc.buildSystemPrompt(snap);
        for (final String leak : List.of("Smith", "Bob's", "Anchor", "Counseling")) {
            assertFalse(prompt.contains(leak),
                    "System prompt leaked private merchant/subscription name: " + leak);
        }
    }

    // ---- helpers ----

    private TransactionTable tx(
            final String merchant, final String category,
            final String amount, final String date) {
        final TransactionTable t = new TransactionTable();
        t.setMerchantName(merchant);
        t.setCategoryPrimary(category);
        t.setAmount(new BigDecimal(amount));
        t.setTransactionDate(date);
        return t;
    }

    private Subscription privateSub() {
        final Subscription s = new Subscription();
        s.setActive(Boolean.TRUE);
        s.setMerchantName("Anchor Counseling Services");
        s.setAmount(new BigDecimal("180"));
        s.setFrequency(Subscription.SubscriptionFrequency.MONTHLY);
        return s;
    }

    private void setField(final String name, final Object value) throws Exception {
        final Field f = InsightsChatService.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(svc, value);
    }
}
