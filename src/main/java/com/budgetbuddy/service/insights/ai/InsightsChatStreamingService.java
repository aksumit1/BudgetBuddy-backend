package com.budgetbuddy.service.insights.ai;

import com.budgetbuddy.model.dynamodb.ChatMessageTable;
import com.budgetbuddy.repository.dynamodb.ChatMessageRepository;
import com.budgetbuddy.service.insights.InsightsContext;
import com.budgetbuddy.service.insights.InsightsContextFactory;
import com.budgetbuddy.service.insights.ai.InsightsChatService.ChatMode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Streaming counterpart to {@link InsightsChatService}. Feeds the
 * assistant's reply to the iOS client token-by-token via Server-Sent
 * Events, so the UI can render words as they arrive instead of waiting
 * for the full turn.
 *
 * <p>Wire shape — three event types over the SSE channel:
 * <ul>
 *   <li>{@code conversationId} — sent first; iOS persists for follow-ups.</li>
 *   <li>{@code delta} — incremental text chunk; iOS appends to the
 *       in-flight message bubble.</li>
 *   <li>{@code done} — sent last with the full reply text + follow-up
 *       array; iOS finalises the bubble and renders follow-up chips.</li>
 *   <li>{@code error} — terminal error; iOS shows a friendly message.</li>
 * </ul>
 *
 * <p>The full reply is collected server-side and persisted to DynamoDB
 * after the stream completes, mirroring the non-streaming path's
 * persistence contract. Token-by-token persistence would cause N
 * writes per turn — wasteful.
 *
 * <p>Activation: same {@code app.insights.chat.enabled} flag as the
 * non-streaming service. Both endpoints stay available so clients can
 * choose per-request.
 */
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Spring constructor injection — beans are shared by design")
@Service
@ConditionalOnProperty(
        name = "app.insights.chat.enabled",
        havingValue = "true",
        matchIfMissing = false)
public class InsightsChatStreamingService {

    private static final Logger LOGGER = LoggerFactory.getLogger(InsightsChatStreamingService.class);
    private static final String ANTHROPIC_API = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final long TTL_SECONDS = 90L * 24 * 60 * 60;
    private static final int MAX_HISTORY_TURNS = 20;

    private final ChatMessageRepository chatRepo;
    private final InsightsContextFactory contextFactory;
    private final PrivacyPreservingExtractor extractor;
    private final PromptRegistry promptRegistry;
    private final ChatMetrics metrics;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Value("${app.insights.chat.api-key:}")
    private String apiKey;
    @Value("${app.insights.chat.model:claude-sonnet-4-6}")
    private String model;
    @Value("${app.insights.chat.timeout-seconds:60}")
    private int timeoutSeconds;
    @Value("${app.insights.chat.max-output-tokens:600}")
    private int maxOutputTokens;

    public InsightsChatStreamingService(
            final ChatMessageRepository chatRepo,
            final InsightsContextFactory contextFactory,
            final PrivacyPreservingExtractor extractor,
            final PromptRegistry promptRegistry,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
                    final ChatMetrics metrics) {
        this.chatRepo = chatRepo;
        this.contextFactory = contextFactory;
        this.extractor = extractor;
        this.promptRegistry = promptRegistry;
        this.metrics = metrics;
    }

    /**
     * Stream a chat turn into {@code emitter}. Runs on a separate
     * thread so the controller's HTTP thread is released immediately
     * — the SseEmitter pushes events back to the client as the
     * Anthropic stream produces them.
     *
     * <p>Caller is responsible for completing the emitter on
     * client-side disconnect; this method completes it on natural
     * end-of-stream or terminal error.
     */
    public void stream(
            final String userId,
            final String conversationIdOrNull,
            final String message,
            final String modeStr,
            final SseEmitter emitter) {
        if (apiKey == null || apiKey.isBlank()) {
            safeSendError(emitter, "Chat misconfigured: api-key not set.");
            return;
        }
        final ChatMode mode = ChatMode.parse(modeStr);
        final String conversationId = conversationIdOrNull == null
                        || conversationIdOrNull.isBlank()
                ? UUID.randomUUID().toString()
                : conversationIdOrNull;
        safeSend(emitter, "conversationId", conversationId);

        // Load history + sanitised snapshot up front (same contract as
        // non-streaming service).
        final List<ChatMessageTable> history = chatRepo.loadConversation(conversationId);
        final List<ChatMessageTable> recentHistory = history.size() > MAX_HISTORY_TURNS
                ? history.subList(history.size() - MAX_HISTORY_TURNS, history.size())
                : history;
        final InsightsContext ctx = contextFactory.buildFor(userId);
        final PrivacyPreservingExtractor.SanitizedSnapshot snapshot = extractor.extract(ctx);
        final String systemPrompt = promptRegistry.buildSystemPrompt(mode, snapshot);

        final long started = System.currentTimeMillis();
        final StringBuilder fullReply = new StringBuilder();
        try {
            callAnthropicStreaming(
                    systemPrompt, recentHistory, message,
                    delta -> {
                        fullReply.append(delta);
                        safeSend(emitter, "delta", delta);
                    });
        } catch (final Exception e) {
            if (metrics != null) {
                metrics.recordFailure(model, mode.name(), "stream-failure");
            }
            LOGGER.warn("Streaming chat failed: {}", e.getMessage());
            safeSendError(emitter, "AI chat failed mid-stream. Please retry.");
            return;
        }
        if (metrics != null) {
            metrics.recordTurn(
                    model, mode.name(),
                    System.currentTimeMillis() - started,
                    /* input tokens unknown in streaming path */ -1,
                    /* output tokens approximated by chars/4 */
                    Math.max(1, fullReply.length() / 4));
        }

        // Parse the structured envelope from the accumulated reply so we
        // can send follow-up chips alongside the final event. If the
        // model returned plain text, follow-ups are empty.
        final InsightsChatService.LlmResponse parsed = parseEnvelope(fullReply.toString());
        final ObjectNode done = mapper.createObjectNode();
        done.put("reply", parsed.reply());
        final ArrayNode fu = done.putArray("followUps");
        parsed.followUps().forEach(fu::add);
        safeSend(emitter, "done", done.toString());

        // Persist both turns (user first; chronological).
        final long now = System.currentTimeMillis();
        persist(conversationId, userId, ChatMessageTable.ROLE_USER, message, now);
        persist(conversationId, userId, ChatMessageTable.ROLE_ASSISTANT, parsed.reply(), now + 1);

        emitter.complete();
    }

    // -----------------------------------------------------------------
    // Anthropic streaming client
    // -----------------------------------------------------------------

    /**
     * Open a streaming POST to Anthropic's messages endpoint and feed
     * each text delta to {@code onDelta}. Uses the SSE event format
     * Anthropic returns ({@code event: content_block_delta} + JSON
     * body with {@code delta.text}). Skips ping/start/end events.
     */
    private void callAnthropicStreaming(
            final String systemPrompt,
            final List<ChatMessageTable> history,
            final String userMessage,
            final Consumer<String> onDelta) throws Exception {
        final ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", maxOutputTokens);
        body.put("stream", true);
        body.put("system", systemPrompt);
        final ArrayNode messages = body.putArray("messages");
        for (final ChatMessageTable m : history) {
            final ObjectNode msg = messages.addObject();
            msg.put("role", ChatMessageTable.ROLE_ASSISTANT.equals(m.getRole())
                    ? "assistant" : "user");
            msg.put("content", m.getContent());
        }
        final ObjectNode current = messages.addObject();
        current.put("role", "user");
        current.put("content", userMessage);

        final HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(ANTHROPIC_API))
                .header("x-api-key", apiKey)
                .header("anthropic-version", ANTHROPIC_VERSION)
                .header("content-type", "application/json")
                .header("accept", "text/event-stream")
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(
                        body.toString(), StandardCharsets.UTF_8))
                .build();

        final HttpResponse<java.io.InputStream> resp = http.send(
                req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("Anthropic stream HTTP " + resp.statusCode());
        }
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                final String payload = line.substring(5).trim();
                if (payload.isEmpty() || "[DONE]".equals(payload)) {
                    continue;
                }
                try {
                    final var node = mapper.readTree(payload);
                    final String type = node.path("type").asText("");
                    if ("content_block_delta".equals(type)) {
                        final String text = node.path("delta").path("text").asText("");
                        if (!text.isEmpty()) {
                            onDelta.accept(text);
                        }
                    }
                } catch (final Exception ignored) {
                    // Anthropic sends keep-alives and a few non-JSON
                    // marker lines; skip silently.
                }
            }
        }
    }

    /**
     * Best-effort parse of the structured envelope from the model's
     * full response. Reuses the same forgiving logic as the
     * non-streaming service.
     */
    private InsightsChatService.LlmResponse parseEnvelope(final String raw) {
        if (raw == null || raw.isBlank()) {
            return new InsightsChatService.LlmResponse("", List.of());
        }
        final int start = raw.indexOf('{');
        final int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return new InsightsChatService.LlmResponse(raw.trim(), List.of());
        }
        try {
            final var node = mapper.readTree(raw.substring(start, end + 1));
            final String reply = node.path("reply").asText("").trim();
            if (reply.isEmpty()) {
                return new InsightsChatService.LlmResponse(raw.trim(), List.of());
            }
            final java.util.List<String> followUps = new java.util.ArrayList<>();
            final var fu = node.path("followUps");
            if (fu.isArray()) {
                fu.forEach(n -> {
                    final String s = n.asText("").trim();
                    if (!s.isEmpty() && followUps.size() < 3) {
                        followUps.add(s);
                    }
                });
            }
            return new InsightsChatService.LlmResponse(reply, followUps);
        } catch (final Exception ignored) {
            return new InsightsChatService.LlmResponse(raw.trim(), List.of());
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

    private void safeSend(final SseEmitter emitter, final String eventName, final String data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
        } catch (final IOException e) {
            LOGGER.debug("SSE send failed (client probably disconnected): {}", e.getMessage());
        }
    }

    private void safeSendError(final SseEmitter emitter, final String message) {
        try {
            emitter.send(SseEmitter.event().name("error").data(message));
        } catch (final IOException ignored) {
            // best-effort
        } finally {
            emitter.complete();
        }
    }
}
