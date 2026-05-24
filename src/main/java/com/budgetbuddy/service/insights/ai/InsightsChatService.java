package com.budgetbuddy.service.insights.ai;

import com.budgetbuddy.model.dynamodb.ChatMessageTable;
import com.budgetbuddy.repository.dynamodb.ChatMessageRepository;
import com.budgetbuddy.service.insights.InsightsContext;
import com.budgetbuddy.service.insights.InsightsContextFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Orchestrates a chat turn:
 * <ol>
 *   <li>Load conversation history from {@link ChatMessageRepository}.</li>
 *   <li>Build a sanitised data snapshot via {@link PrivacyPreservingExtractor}
 *       — this is the ONLY user data sent to the LLM.</li>
 *   <li>Compose system prompt + history + new user turn + snapshot.</li>
 *   <li>Call Anthropic, get assistant reply.</li>
 *   <li>Persist user turn + assistant reply.</li>
 * </ol>
 *
 * <p>Feature-flagged off by default ({@code app.insights.chat.enabled}).
 * When disabled, the endpoint returns 503 and the iOS client falls back
 * to the deterministic insights surface.
 *
 * <p>Privacy contract — see {@link PrivacyPreservingExtractor} javadoc.
 * The LLM never sees: userId, email, name, account numbers, transaction
 * descriptions, exact dates, or any merchant outside the brand allowlist.
 * Conversation history IS sent to the LLM as additional context (so the
 * model can answer follow-up questions); the user's text inputs are
 * theirs and they chose to send them.
 */
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Spring constructor injection — beans are shared by design")
@Service
@ConditionalOnProperty(
        name = "app.insights.chat.enabled",
        havingValue = "true",
        matchIfMissing = false)
public class InsightsChatService {

    private static final Logger LOGGER = LoggerFactory.getLogger(InsightsChatService.class);
    private static final String ANTHROPIC_API = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final long TTL_SECONDS = 90L * 24 * 60 * 60;
    private static final int MAX_HISTORY_TURNS = 20;

    private final ChatMessageRepository chatRepo;
    private final InsightsContextFactory contextFactory;
    private final PrivacyPreservingExtractor extractor;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Value("${app.insights.chat.api-key:}")
    private String apiKey;

    @Value("${app.insights.chat.model:claude-sonnet-4-6}")
    private String model;

    @Value("${app.insights.chat.timeout-seconds:25}")
    private int timeoutSeconds;

    @Value("${app.insights.chat.max-output-tokens:600}")
    private int maxOutputTokens;

    public InsightsChatService(
            final ChatMessageRepository chatRepo,
            final InsightsContextFactory contextFactory,
            final PrivacyPreservingExtractor extractor) {
        this.chatRepo = chatRepo;
        this.contextFactory = contextFactory;
        this.extractor = extractor;
    }

    /**
     * Process one chat turn. Returns the assistant's reply + suggested
     * follow-up prompts (already persisted alongside the user turn).
     * Throws on misconfiguration (no API key) or LLM failure; caller
     * maps to 503 / 5xx as appropriate.
     */
    public ChatTurnResult chat(final ChatTurnRequest req) {
        if (req == null || req.userId() == null || req.message() == null
                || req.message().isBlank()) {
            throw new IllegalArgumentException("userId and non-empty message are required");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "Chat misconfigured: app.insights.chat.api-key not set");
        }

        final ChatMode mode = ChatMode.parse(req.mode());
        final String conversationId = req.conversationId() == null || req.conversationId().isBlank()
                ? UUID.randomUUID().toString()
                : req.conversationId();

        // 1. Load prior turns (trimmed to a recency window so prompt size stays bounded).
        final List<ChatMessageTable> history = chatRepo.loadConversation(conversationId);
        final List<ChatMessageTable> recentHistory = history.size() > MAX_HISTORY_TURNS
                ? history.subList(history.size() - MAX_HISTORY_TURNS, history.size())
                : history;

        // 2. Build sanitised data snapshot — what the LLM gets to see.
        final InsightsContext ctx = contextFactory.buildFor(req.userId());
        final PrivacyPreservingExtractor.SanitizedSnapshot snapshot = extractor.extract(ctx);

        // 3. Call LLM (asks for structured JSON: reply + 2-3 follow-ups).
        final LlmResponse llmResponse;
        try {
            llmResponse = callLlm(req.message(), recentHistory, snapshot, mode);
        } catch (final Exception e) {
            LOGGER.warn("InsightsChatService: LLM call failed: {}", e.getMessage());
            throw new RuntimeException("chat-llm-call-failed", e);
        }

        // 4. Persist both turns (user first so chronological order is preserved).
        // Follow-ups are NOT persisted — they're per-turn suggestions, not history.
        final long now = System.currentTimeMillis();
        persist(conversationId, req.userId(), ChatMessageTable.ROLE_USER, req.message(), now);
        persist(conversationId, req.userId(), ChatMessageTable.ROLE_ASSISTANT,
                llmResponse.reply(), now + 1);

        return new ChatTurnResult(
                conversationId,
                llmResponse.reply(),
                llmResponse.followUps(),
                Instant.ofEpochMilli(now + 1));
    }

    // -----------------------------------------------------------------
    // LLM call
    // -----------------------------------------------------------------

    private LlmResponse callLlm(
            final String userMessage,
            final List<ChatMessageTable> history,
            final PrivacyPreservingExtractor.SanitizedSnapshot snapshot,
            final ChatMode mode) throws Exception {
        final ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", maxOutputTokens);
        body.put("system", buildSystemPrompt(snapshot, mode));

        final ArrayNode messages = body.putArray("messages");
        for (final ChatMessageTable m : history) {
            final ObjectNode msg = messages.addObject();
            msg.put("role", ChatMessageTable.ROLE_ASSISTANT.equals(m.getRole())
                    ? "assistant" : "user");
            msg.put("content", m.getContent());
        }
        final ObjectNode currentTurn = messages.addObject();
        currentTurn.put("role", "user");
        currentTurn.put("content", userMessage);

        final HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(ANTHROPIC_API))
                .header("x-api-key", apiKey)
                .header("anthropic-version", ANTHROPIC_VERSION)
                .header("content-type", "application/json")
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();

        final HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("Anthropic HTTP " + resp.statusCode() + ": " + resp.body());
        }
        final String raw = mapper.readTree(resp.body())
                .path("content").path(0).path("text").asText("").trim();
        return parseLlmJson(raw);
    }

    /**
     * Parse the LLM's JSON envelope {@code {"reply":"...","followUps":["..."]}}.
     * The model is instructed to return this shape; defensively handles
     * the case where the model returns prose without JSON (extract a
     * JSON object substring; on failure, fall back to plain reply +
     * empty follow-ups).
     */
    LlmResponse parseLlmJson(final String raw) {
        if (raw == null || raw.isBlank()) {
            return new LlmResponse("", List.of());
        }
        final int start = raw.indexOf('{');
        final int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return new LlmResponse(raw.trim(), List.of());
        }
        try {
            final var node = mapper.readTree(raw.substring(start, end + 1));
            final String reply = node.path("reply").asText("").trim();
            if (reply.isEmpty()) {
                return new LlmResponse(raw.trim(), List.of());
            }
            final List<String> followUps = new java.util.ArrayList<>();
            final var fu = node.path("followUps");
            if (fu.isArray()) {
                fu.forEach(n -> {
                    final String s = n.asText("").trim();
                    if (!s.isEmpty() && followUps.size() < 3) {
                        followUps.add(s);
                    }
                });
            }
            return new LlmResponse(reply, followUps);
        } catch (final Exception ignored) {
            return new LlmResponse(raw.trim(), List.of());
        }
    }

    /**
     * Build the system prompt for the given mode. Includes the
     * sanitised snapshot as JSON and a mode-specific focus statement.
     * The model is instructed to return a JSON envelope with a
     * concise reply + 2-3 suggested follow-up questions.
     */
    /**
     * Single-arg overload used by tests that don't need to vary the mode.
     * Defaults to {@link ChatMode#GENERAL} so the focus line in the
     * prompt stays neutral.
     */
    String buildSystemPrompt(final PrivacyPreservingExtractor.SanitizedSnapshot snapshot) {
        return buildSystemPrompt(snapshot, ChatMode.GENERAL);
    }

    String buildSystemPrompt(
            final PrivacyPreservingExtractor.SanitizedSnapshot snapshot,
            final ChatMode mode) {
        final String snapshotJson;
        try {
            snapshotJson = mapper.writeValueAsString(snapshot);
        } catch (final Exception e) {
            throw new RuntimeException("Failed to serialize snapshot", e);
        }
        return "You are BudgetBuddy's financial-insights assistant. You answer the user's "
                + "questions about THEIR spending and finances using ONLY the JSON snapshot "
                + "below.\n\n"
                + "Mode focus: " + mode.focusInstruction() + "\n\n"
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

    /** Chat focus modes — each tailors the system prompt without changing privacy rules. */
    public enum ChatMode {
        GENERAL("Answer the user's question using whichever section of the snapshot is most relevant."),
        SPENDING("Focus on the spending breakdown (categories, months, known merchants). Help the user understand WHERE their money goes."),
        BUDGET("Focus on the budgets section. Flag categories over the limit; suggest realistic adjustments. Be honest when no budget is set."),
        GOAL("Focus on goal progress (savings rate, account balances if relevant). Encourage realistic milestones."),
        SUBSCRIPTION("Focus on the subscriptions section. Surface duplicates, unused, or high-cost subs; respect the user's autonomy on what to cancel."),
        ANOMALY("Focus on recentAnomalies. Explain what's unusual + suggest whether the user should investigate.");

        private final String focus;

        ChatMode(final String focus) { this.focus = focus; }

        public String focusInstruction() { return focus; }

        public static ChatMode parse(final String raw) {
            if (raw == null || raw.isBlank()) {
                return GENERAL;
            }
            try {
                return ChatMode.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (final IllegalArgumentException e) {
                return GENERAL;
            }
        }
    }

    private void persist(
            final String conversationId,
            final String userId,
            final String role,
            final String content,
            final long createdAt) {
        final ChatMessageTable msg = new ChatMessageTable();
        msg.setConversationId(conversationId);
        msg.setMessageId(UUID.randomUUID().toString());
        msg.setUserId(userId);
        msg.setRole(role);
        msg.setContent(content);
        msg.setCreatedAt(createdAt);
        msg.setTtl(Instant.ofEpochMilli(createdAt).getEpochSecond() + TTL_SECONDS);
        chatRepo.save(msg);
    }

    // -----------------------------------------------------------------
    // DTOs
    // -----------------------------------------------------------------

    public record ChatTurnRequest(
            String userId, String conversationId, String message, String mode) {

        /** Backwards-compat: tests that pre-date the {@code mode} parameter. */
        public ChatTurnRequest(
                final String userId, final String conversationId, final String message) {
            this(userId, conversationId, message, null);
        }
    }

    public record ChatTurnResult(
            String conversationId,
            String reply,
            List<String> followUps,
            Instant timestamp) {}

    /** Internal — what the LLM call returns before persistence. */
    record LlmResponse(String reply, List<String> followUps) {}
}
