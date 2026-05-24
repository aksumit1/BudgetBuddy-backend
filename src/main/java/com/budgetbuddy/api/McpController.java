package com.budgetbuddy.api;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.mcp.McpProtocolHandler;
import com.budgetbuddy.mcp.McpSession;
import com.budgetbuddy.mcp.McpSessionRegistry;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.service.UserService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
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

    private final UserService userService;
    private final McpProtocolHandler protocolHandler;
    private final McpSessionRegistry sessionRegistry;
    private final ObjectMapper mapper;

    public McpController(
            final UserService userService,
            final McpProtocolHandler protocolHandler,
            final McpSessionRegistry sessionRegistry,
            final ObjectMapper mapper) {
        this.userService = userService;
        this.protocolHandler = protocolHandler;
        this.sessionRegistry = sessionRegistry;
        this.mapper = mapper;
    }

    @PostMapping(consumes = "application/json", produces = "application/json")
    public ResponseEntity<?> dispatch(
            @AuthenticationPrincipal final UserDetails userDetails,
            @RequestHeader(value = SESSION_HEADER, required = false) final String sessionIdHeader,
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

        // Surface the session id back to the client on initialize so it
        // can echo it on every subsequent call.
        final String method = request.path("method").asText("");
        if ("initialize".equals(method)) {
            final JsonNode result = response.path("result");
            final String newId = result.path("sessionId").asText(null);
            if (newId != null) {
                return ResponseEntity.ok().header(SESSION_HEADER, newId).body(response);
            }
        }
        return ResponseEntity.ok(response);
    }

    /**
     * Optional graceful-close from the client. Not required — idle
     * sessions evict themselves on their own.
     */
    @DeleteMapping
    public ResponseEntity<?> terminate(
            @AuthenticationPrincipal final UserDetails userDetails,
            @RequestHeader(value = SESSION_HEADER, required = false) final String sessionIdHeader) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }
        sessionRegistry.terminate(sessionIdHeader);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }
}
