package com.budgetbuddy.api;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.mcp.McpProtocolHandler;
import com.budgetbuddy.mcp.McpSession;
import com.budgetbuddy.mcp.McpSessionRegistry;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.security.JwtTokenProvider;
import com.budgetbuddy.service.UserService;
import java.time.Duration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Model Context Protocol (MCP) HTTP transport. Exposes BudgetBuddy's
 * insights/forecasts/mutations as MCP tools so an external AI session
 * (Claude Desktop, Claude Code, an MCP-aware editor, …) can plan + call
 * them on behalf of the authenticated user.
 *
 * <p>Wire shape: single {@code POST /mcp} endpoint accepting a JSON-RPC
 * 2.0 envelope. Per MCP's Streamable HTTP transport:
 *
 * <ul>
 *   <li>Auth: {@code Authorization: Bearer <JWT>} (same as the rest of
 *       the API). No per-request token rotation.
 *   <li>Session: server issues a session ID on {@code initialize};
 *       client echoes it back in {@code Mcp-Session-Id} on every call.
 *   <li>Notifications: 202 Accepted with empty body.
 *   <li>Responses: 200 OK with the JSON-RPC envelope.
 *   <li>Streaming: v1 returns single JSON responses; the controller
 *       doesn't upgrade to SSE. Tools are bounded and return fast.
 * </ul>
 *
 * <p>{@code DELETE /mcp} terminates a session by id — used by clients
 * that disconnect cleanly. Not required; idle sessions evict on their
 * own.
 *
 * <p>Feature-flagged on {@code app.mcp.enabled}. The bean only wires
 * when the flag is true so dev environments can leave the surface off.
 */
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Spring constructor injection — beans are shared by design")
@RestController
@RequestMapping("/mcp")
@ConditionalOnProperty(name = "app.mcp.enabled", havingValue = "true")
public class McpController {

    private static final Logger LOGGER = LoggerFactory.getLogger(McpController.class);
    private static final String SESSION_HEADER = "Mcp-Session-Id";

    private static final Duration CONNECTION_TOKEN_TTL = Duration.ofHours(24);

    private final UserService userService;
    private final McpProtocolHandler protocolHandler;
    private final McpSessionRegistry sessionRegistry;
    private final ObjectMapper mapper;
    private final JwtTokenProvider jwtTokenProvider;

    public McpController(
            final UserService userService,
            final McpProtocolHandler protocolHandler,
            final McpSessionRegistry sessionRegistry,
            final ObjectMapper mapper,
            final JwtTokenProvider jwtTokenProvider) {
        this.userService = userService;
        this.protocolHandler = protocolHandler;
        this.sessionRegistry = sessionRegistry;
        this.mapper = mapper;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @PostMapping(consumes = "application/json")
    public ResponseEntity<?> dispatch(
            @AuthenticationPrincipal final UserDetails userDetails,
            @RequestHeader(value = SESSION_HEADER, required = false) final String sessionIdHeader,
            @RequestHeader(value = "Accept", required = false) final String acceptHeader,
            @RequestBody final JsonNode request) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }
        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        // Resolve session — required for everything except initialize.
        // We let the protocol handler decide whether the absent session
        // is a real error or normal (initialize creates one).
        final McpSession session =
                sessionIdHeader == null
                        ? null
                        : sessionRegistry.get(sessionIdHeader).orElse(null);

        final ObjectNode response = protocolHandler.handle(request, user, session);
        if (response == null) {
            // Notification — 202 Accepted, no body.
            return ResponseEntity.accepted().build();
        }

        // SSE upgrade: per MCP Streamable HTTP, clients indicate they
        // can consume an event stream with Accept: text/event-stream.
        // Even though our tool calls are synchronous (no incremental
        // chunks), advertising SSE lets the client share a single
        // long-lived connection for many tool calls. We frame the
        // final response as one SSE event so the wire shape is right.
        final boolean wantsSse = acceptHeader != null
                && acceptHeader.toLowerCase(java.util.Locale.ROOT).contains("text/event-stream");

        // Surface the session id back to the client on initialize so it
        // can echo it on every subsequent call.
        final String method = request.path("method").asText("");
        if ("initialize".equals(method)) {
            final JsonNode result = response.path("result");
            final String newId = result.path("sessionId").asText(null);
            if (wantsSse) {
                return ResponseEntity.ok()
                        .header(SESSION_HEADER, newId)
                        .contentType(MediaType.TEXT_EVENT_STREAM)
                        .body(sseFrame(response));
            }
            if (newId != null) {
                return ResponseEntity.ok()
                        .header(SESSION_HEADER, newId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(response);
            }
        }
        if (wantsSse) {
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .body(sseFrame(response));
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(response);
    }

    /**
     * Wrap a single JSON-RPC response as one SSE {@code message} event.
     * Newlines in the JSON are stripped because SSE uses {@code \n} as
     * the event delimiter; the JSON is single-line by construction
     * (ObjectNode.toString()) so this is a defence-in-depth strip.
     */
    private static String sseFrame(final ObjectNode response) {
        final String json = response.toString().replace("\n", " ");
        return "event: message\ndata: " + json + "\n\n";
    }

    /**
     * Optional graceful-close from the client. Not required — idle
     * sessions evict themselves on their own.
     *
     * <p>Ownership check: refuses to terminate a session that belongs
     * to a different user, even if the caller knows the session id.
     * Sessions are short-lived UUIDs, so the realistic attack is a
     * stale id leaking in logs; cheap to guard against.
     */
    @DeleteMapping
    public ResponseEntity<?> terminate(
            @AuthenticationPrincipal final UserDetails userDetails,
            @RequestHeader(value = SESSION_HEADER, required = false) final String sessionIdHeader) {
        final UserTable user = requireUser(userDetails);
        if (sessionIdHeader == null) {
            return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
        }
        final var existing = sessionRegistry.get(sessionIdHeader).orElse(null);
        if (existing == null) {
            // Already gone — idempotent.
            return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
        }
        if (!user.getUserId().equals(existing.userId())) {
            return ResponseEntity.notFound().build();
        }
        sessionRegistry.terminate(sessionIdHeader);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    /**
     * List the user's live MCP sessions — the iOS Settings screen calls
     * this to render "active connections" with a per-row revoke.
     */
    @GetMapping("/sessions")
    public ResponseEntity<List<Map<String, Object>>> listSessions(
            @AuthenticationPrincipal final UserDetails userDetails) {
        final UserTable user = requireUser(userDetails);
        final List<Map<String, Object>> out = sessionRegistry.sessionsForUser(user.getUserId())
                .stream()
                .map(s -> {
                    final Map<String, Object> row = new LinkedHashMap<>();
                    row.put("sessionId", s.sessionId());
                    row.put("clientName", s.clientName());
                    row.put("clientVersion", s.clientVersion());
                    row.put("createdAt", s.createdAt().toString());
                    sessionRegistry.lastUsedAt(s.sessionId())
                            .ifPresent(t -> row.put("lastUsedAt", t.toString()));
                    row.put("moneyMovingConsent", s.moneyMovingConsent());
                    row.put("toolCallCount", s.toolCallCount());
                    return row;
                })
                .toList();
        return ResponseEntity.ok(out);
    }

    /** Per-session revoke — used by iOS "End session" button. */
    @DeleteMapping("/sessions/{sessionId}")
    public ResponseEntity<Void> revokeSession(
            @AuthenticationPrincipal final UserDetails userDetails,
            @PathVariable final String sessionId) {
        final UserTable user = requireUser(userDetails);
        final var existing = sessionRegistry.get(sessionId).orElse(null);
        if (existing == null || !user.getUserId().equals(existing.userId())) {
            return ResponseEntity.notFound().build();
        }
        sessionRegistry.terminate(sessionId);
        return ResponseEntity.noContent().build();
    }

    /** Read the user's persistent-consent state for the Settings screen. */
    @GetMapping("/consent")
    public ResponseEntity<Map<String, Object>> getConsent(
            @AuthenticationPrincipal final UserDetails userDetails) {
        final UserTable user = requireUser(userDetails);
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("persistent", Boolean.TRUE.equals(user.getMcpMoneyMovingConsent()));
        body.put("grantedAt",
                user.getMcpConsentGrantedAt() == null
                        ? null : user.getMcpConsentGrantedAt().toString());
        return ResponseEntity.ok(body);
    }

    /**
     * Set the persistent-consent flag. iOS calls this when the user
     * flips the "Always allow money-moving tools" toggle in Settings.
     */
    @PutMapping("/consent")
    public ResponseEntity<Map<String, Object>> setConsent(
            @AuthenticationPrincipal final UserDetails userDetails,
            @RequestBody final Map<String, Object> body) {
        final UserTable user = requireUser(userDetails);
        final Object raw = body.get("persistent");
        if (!(raw instanceof Boolean)) {
            throw new AppException(ErrorCode.INVALID_INPUT, "'persistent' must be a boolean");
        }
        final boolean persistent = (Boolean) raw;
        user.setMcpMoneyMovingConsent(persistent);
        user.setMcpConsentGrantedAt(persistent ? Instant.now() : null);
        userService.updateUser(user);
        return getConsent(userDetails);
    }

    /**
     * Mint a short-lived JWT scoped for MCP clients. The iOS Settings
     * screen calls this when the user taps "Generate connection token",
     * then renders the URL + token for copy-paste into Claude Desktop /
     * Cursor / any MCP-aware client.
     *
     * <p>Why a separate token rather than reusing the user's app JWT:
     * the app JWT is held inside the iOS keychain and lives for the
     * session lifetime. Surfacing it for copy-paste would invite users
     * to leak it into third-party config files. The MCP connection
     * token lives 24h and carries a {@code scope=mcp} claim for audit;
     * users can revoke effectively by waiting it out.
     */
    @PostMapping("/connection-token")
    public ResponseEntity<Map<String, Object>> issueConnectionToken(
            @AuthenticationPrincipal final UserDetails userDetails) {
        final UserTable user = requireUser(userDetails);
        final String token =
                jwtTokenProvider.generateMcpConnectionToken(
                        userDetails.getUsername(), CONNECTION_TOKEN_TTL.toMillis());
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("token", token);
        body.put("expiresInSeconds", CONNECTION_TOKEN_TTL.toSeconds());
        body.put("userId", user.getUserId());
        return ResponseEntity.ok(body);
    }

    private UserTable requireUser(final UserDetails userDetails) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }
        return userService
                .findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));
    }
}
