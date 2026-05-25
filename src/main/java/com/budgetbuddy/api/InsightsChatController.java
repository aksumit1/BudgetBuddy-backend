package com.budgetbuddy.api;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.ChatMessageTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.ChatMessageRepository;
import com.budgetbuddy.service.UserService;
import com.budgetbuddy.service.insights.ai.ChatMetrics;
import com.budgetbuddy.service.insights.ai.ChatRateLimiter;
import com.budgetbuddy.service.insights.ai.InsightsChatService;
import com.budgetbuddy.service.insights.ai.InsightsChatStreamingService;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI chat endpoint. Wraps {@link InsightsChatService} for the iOS
 * client. Feature-flagged via {@code app.insights.chat.enabled} — when
 * disabled (default), the service bean isn't wired and every endpoint
 * returns 503 with a clear "feature not enabled" error so the iOS
 * client can render a graceful "AI chat not available" state.
 */
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Spring constructor injection — beans are shared by design")
@RestController
@RequestMapping("/api/insights/chat")
public class InsightsChatController {

    private static final Logger LOGGER = LoggerFactory.getLogger(InsightsChatController.class);
    private static final String USER_NOT_AUTH = "User not authenticated";
    private static final String USER_NOT_FOUND = "User not found";

    private final UserService userService;
    private final ChatMessageRepository chatRepo;
    private final ChatRateLimiter rateLimiter;
    private final ChatMetrics metrics;

    /**
     * Optional. Null when the chat feature flag is off — the endpoint
     * then returns 503 instead of constructing a partial dependency tree.
     */
    @Autowired(required = false)
    private InsightsChatService chatService;

    /** Optional — present only when the streaming bean is wired (feature-flagged on). */
    @Autowired(required = false)
    private InsightsChatStreamingService chatStreamingService;

    public InsightsChatController(
            final UserService userService,
            final ChatMessageRepository chatRepo,
            final ChatRateLimiter rateLimiter,
            final ChatMetrics metrics) {
        this.userService = userService;
        this.chatRepo = chatRepo;
        this.rateLimiter = rateLimiter;
        this.metrics = metrics;
    }

    /** POST /api/insights/chat — one chat turn (user message → assistant reply). */
    @PostMapping
    public ResponseEntity<ChatTurnResponse> chat(
            @AuthenticationPrincipal final UserDetails userDetails,
            @RequestBody final ChatTurnRequest req) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, USER_NOT_AUTH);
        }
        if (chatService == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new ChatTurnResponse(
                            null,
                            "AI chat is not enabled in this environment.",
                            java.util.List.of(),
                            Instant.now()));
        }
        final UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, USER_NOT_FOUND));

        if (req == null || req.message() == null || req.message().isBlank()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "message is required");
        }
        if (req.message().length() > 2_000) {
            throw new AppException(ErrorCode.INVALID_INPUT,
                    "message too long (max 2000 chars)");
        }
        if (!rateLimiter.tryAcquire(user.getUserId())) {
            metrics.recordRateLimited();
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", String.valueOf(rateLimiter.retryAfterSeconds()))
                    .body(new ChatTurnResponse(
                            req.conversationId(),
                            "You're sending messages too quickly. Please wait a moment.",
                            List.of(),
                            Instant.now()));
        }

        try {
            final InsightsChatService.ChatTurnResult result =
                    chatService.chat(new InsightsChatService.ChatTurnRequest(
                            user.getUserId(),
                            req.conversationId(),
                            req.message(),
                            req.mode()));
            return ResponseEntity.ok(
                    new ChatTurnResponse(
                            result.conversationId(),
                            result.reply(),
                            result.followUps(),
                            result.timestamp()));
        } catch (final IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new ChatTurnResponse(null, e.getMessage(),
                            List.of(), Instant.now()));
        } catch (final RuntimeException e) {
            LOGGER.warn("Chat turn failed for user: {}", e.getMessage());
            throw new AppException(ErrorCode.EXTERNAL_API_ERROR,
                    "AI chat request failed — please retry");
        }
    }

    /**
     * GET /api/insights/chat/stream — server-sent-event variant of
     * the chat POST. Streams the assistant's reply token-by-token so
     * the iOS client can render incrementally. Same rate limit + auth
     * rules as the non-streaming endpoint.
     *
     * <p>Implemented as GET with query params so SSE clients (e.g.
     * iOS URLSession) can use the simpler streaming-task API instead
     * of body-encoded POSTs.
     */
    @GetMapping(value = "/stream", produces = "text/event-stream")
    public SseEmitter stream(
            @AuthenticationPrincipal final UserDetails userDetails,
            @RequestParam(required = false) final String conversationId,
            @RequestParam final String message,
            @RequestParam(required = false, defaultValue = "general") final String mode) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, USER_NOT_AUTH);
        }
        final UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, USER_NOT_FOUND));
        if (chatStreamingService == null) {
            throw new AppException(ErrorCode.EXTERNAL_API_ERROR,
                    "Streaming chat is not enabled in this environment.");
        }
        if (message == null || message.isBlank()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "message is required");
        }
        if (message.length() > 2_000) {
            throw new AppException(ErrorCode.INVALID_INPUT, "message too long (max 2000 chars)");
        }
        if (!rateLimiter.tryAcquire(user.getUserId())) {
            metrics.recordRateLimited();
            throw new AppException(ErrorCode.RATE_LIMIT_EXCEEDED,
                    "You're sending messages too quickly. Please wait a moment.");
        }
        // 5-minute server-side timeout — long enough for any reasonable
        // chat turn; client should reconnect if it hits this.
        final SseEmitter emitter = new SseEmitter(5L * 60 * 1000);
        // Run the LLM call on a separate thread so the HTTP thread is
        // released immediately and the emitter can stream back.
        new Thread(() -> {
            try {
                chatStreamingService.stream(
                        user.getUserId(), conversationId, message, mode, emitter);
            } catch (final RuntimeException e) {
                LOGGER.warn("Streaming worker died: {}", e.getMessage());
                emitter.completeWithError(e);
            }
        }, "insights-chat-stream-" + user.getUserId()).start();
        return emitter;
    }

    /**
     * GET /api/insights/chat/conversations — list a user's recent
     * conversations (most-recent first), capped at 50.
     */
    @GetMapping("/conversations")
    public ResponseEntity<Map<String, Object>> conversations(
            @AuthenticationPrincipal final UserDetails userDetails) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, USER_NOT_AUTH);
        }
        final UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, USER_NOT_FOUND));
        final List<ChatMessageRepository.ConversationSummary> summaries =
                chatRepo.listConversations(user.getUserId(), 50);
        final List<Map<String, Object>> serialised = summaries.stream()
                .map(s -> Map.<String, Object>of(
                        "conversationId", s.conversationId(),
                        "firstMessageAt", Instant.ofEpochMilli(s.firstMessageEpochMillis()),
                        "lastMessageAt", Instant.ofEpochMilli(s.lastMessageEpochMillis()),
                        "messageCount", s.messageCount(),
                        "lastMessagePreview", s.lastMessagePreview()))
                .collect(Collectors.toList());
        return ResponseEntity.ok(Map.of("conversations", serialised));
    }

    /** GET /api/insights/chat/{conversationId} — load full conversation history. */
    @GetMapping("/{conversationId}")
    public ResponseEntity<Map<String, Object>> history(
            @AuthenticationPrincipal final UserDetails userDetails,
            @PathVariable final String conversationId) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, USER_NOT_AUTH);
        }
        final UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, USER_NOT_FOUND));

        final List<ChatMessageTable> rows = chatRepo.loadConversation(conversationId);
        // Defensive: reject cross-user access. A user should never see
        // another user's conversation even if they guess the id.
        if (!rows.isEmpty() && !user.getUserId().equals(rows.get(0).getUserId())) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS,
                    "Not your conversation");
        }
        final List<Map<String, Object>> messages = rows.stream()
                .map(m -> Map.<String, Object>of(
                        "messageId", m.getMessageId(),
                        "role", m.getRole(),
                        "content", m.getContent(),
                        "createdAt", Instant.ofEpochMilli(m.getCreatedAt())))
                .collect(Collectors.toList());
        return ResponseEntity.ok(Map.of(
                "conversationId", conversationId,
                "messages", messages));
    }

    // -----------------------------------------------------------------
    // DTOs
    // -----------------------------------------------------------------

    public record ChatTurnRequest(
            String conversationId,
            String message,
            /** Optional. One of: general, spending, budget, goal, subscription, anomaly. */
            String mode) {}

    public record ChatTurnResponse(
            String conversationId,
            String reply,
            List<String> followUps,
            Instant timestamp) {}
}
