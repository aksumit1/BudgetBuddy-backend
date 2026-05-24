package com.budgetbuddy.mcp;

import com.budgetbuddy.model.dynamodb.UserTable;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Dispatches a parsed JSON-RPC request to the right MCP method handler.
 * Lives between the HTTP controller (which owns auth + transport) and
 * the tool registry (which owns the actual work).
 *
 * <p>Supported methods (per MCP spec):
 *
 * <ul>
 *   <li>{@code initialize} — capability handshake. Returns the server's
 *       protocol version, advertised capabilities, and the
 *       session id the client must echo on subsequent calls.
 *   <li>{@code tools/list} — returns every registered tool with its
 *       inputSchema. The client's AI uses this to plan tool calls.
 *   <li>{@code tools/call} — invokes a tool by name. Args are validated
 *       lightly (presence, types) before dispatch.
 *   <li>{@code ping} — health check.
 *   <li>{@code notifications/initialized} — fire-and-forget notification
 *       from the client. We accept-and-ignore.
 * </ul>
 *
 * <p>Anything else returns the standard {@code -32601 method not found}.
 */
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Spring constructor injection — beans are shared by design")
@Component
public class McpProtocolHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(McpProtocolHandler.class);

    /** Latest MCP protocol revision we conform to. */
    private static final String PROTOCOL_VERSION = "2024-11-05";

    private final McpToolRegistry registry;
    private final McpSessionRegistry sessionRegistry;
    private final ObjectMapper mapper;

    public McpProtocolHandler(
            final McpToolRegistry registry,
            final McpSessionRegistry sessionRegistry,
            final ObjectMapper mapper) {
        this.registry = registry;
        this.sessionRegistry = sessionRegistry;
        this.mapper = mapper;
    }

    /**
     * Dispatch a parsed JSON-RPC request.
     *
     * @param request parsed request object — must already have a
     *     "method" field; ids are forwarded back unchanged.
     * @param user authenticated user
     * @param session existing session — null on {@code initialize},
     *     non-null on every other method
     * @return response envelope to send back; null for notifications
     *     (the controller turns this into a 202 Accepted)
     */
    public ObjectNode handle(
            final JsonNode request, final UserTable user, final McpSession session) {
        final JsonNode id = request.path("id");
        final String method = request.path("method").asText("");
        if (method.isEmpty()) {
            return McpJsonRpc.error(
                    mapper, id, McpJsonRpc.INVALID_REQUEST, "missing method");
        }
        try {
            switch (method) {
                case "initialize":
                    return handleInitialize(request, user, id);
                case "tools/list":
                    requireSession(session);
                    return handleToolsList(id);
                case "tools/call":
                    requireSession(session);
                    return handleToolsCall(request, user, session, id);
                case "ping":
                    return McpJsonRpc.success(mapper, id, mapper.createObjectNode());
                case "notifications/initialized":
                case "notifications/cancelled":
                    // Fire-and-forget; no response.
                    return null;
                default:
                    return McpJsonRpc.error(
                            mapper, id, McpJsonRpc.METHOD_NOT_FOUND,
                            "Unknown method: " + method);
            }
        } catch (final InvalidRequestException e) {
            return McpJsonRpc.error(mapper, id, McpJsonRpc.INVALID_REQUEST, e.getMessage());
        } catch (final InvalidParamsException e) {
            return McpJsonRpc.error(mapper, id, McpJsonRpc.INVALID_PARAMS, e.getMessage());
        } catch (final ConsentRequiredException e) {
            return McpJsonRpc.error(mapper, id, McpJsonRpc.CONSENT_REQUIRED, e.getMessage());
        } catch (final Exception e) {
            LOGGER.warn("MCP dispatch failed for method {}: {}", method, e.getMessage());
            return McpJsonRpc.error(
                    mapper, id, McpJsonRpc.INTERNAL_ERROR,
                    "Internal error executing " + method);
        }
    }

    private ObjectNode handleInitialize(
            final JsonNode request, final UserTable user, final JsonNode id) {
        final McpSession session = sessionRegistry.create(user.getUserId());
        final ObjectNode result = mapper.createObjectNode();
        result.put("protocolVersion", PROTOCOL_VERSION);

        // Capabilities — we advertise tools only (no resources/prompts in v1).
        final ObjectNode capabilities = result.putObject("capabilities");
        final ObjectNode tools = capabilities.putObject("tools");
        tools.put("listChanged", false);

        // Server info — identifies us to the client.
        final ObjectNode serverInfo = result.putObject("serverInfo");
        serverInfo.put("name", "budgetbuddy-mcp");
        serverInfo.put("version", "1.0.0");

        // Session id — client echoes this in `Mcp-Session-Id` header.
        result.put("sessionId", session.sessionId());
        return McpJsonRpc.success(mapper, id, result);
    }

    private ObjectNode handleToolsList(final JsonNode id) {
        final ObjectNode result = mapper.createObjectNode();
        final ArrayNode list = result.putArray("tools");
        for (final McpTool t : registry.all()) {
            final ObjectNode entry = list.addObject();
            entry.put("name", t.name());
            entry.put("description", t.description());
            entry.set("inputSchema", t.inputSchema());
        }
        return McpJsonRpc.success(mapper, id, result);
    }

    private ObjectNode handleToolsCall(
            final JsonNode request,
            final UserTable user,
            final McpSession session,
            final JsonNode id) throws Exception {
        final JsonNode params = request.path("params");
        final String name = params.path("name").asText("");
        if (name.isEmpty()) {
            throw new InvalidParamsException("missing tool name");
        }
        final McpTool tool = registry.get(name)
                .orElseThrow(() -> new InvalidParamsException("unknown tool: " + name));

        // Consent gate. Read tools always pass; write/money-moving tools
        // require the session to have explicit money-moving consent.
        // The consent-grant tool itself bypasses the gate (chicken-and-egg):
        // it IS the entrypoint that grants the permission.
        final boolean bypassesGate = "enable_money_moving_consent".equals(name);
        if (!bypassesGate
                && tool.category() != McpTool.Category.READ
                && !session.moneyMovingConsent()) {
            throw new ConsentRequiredException(
                    "Tool '" + name + "' requires money-moving consent. "
                            + "Call 'enable_money_moving_consent' first and have "
                            + "the user approve in the iOS app.");
        }

        session.incrementToolCallCount();
        final JsonNode args = params.has("arguments")
                ? params.get("arguments")
                : mapper.createObjectNode();
        final JsonNode toolResult = tool.call(args, user, session);

        // Wrap result per MCP spec: tools/call returns {content: [...]}.
        final ObjectNode result = mapper.createObjectNode();
        final ArrayNode content = result.putArray("content");
        final ObjectNode block = content.addObject();
        block.put("type", "text");
        block.put("text", toolResult == null ? "{}" : toolResult.toString());
        // Also include the structured object so clients that prefer
        // typed access don't have to re-parse the embedded JSON string.
        result.set("structuredContent", toolResult);
        return McpJsonRpc.success(mapper, id, result);
    }

    private void requireSession(final McpSession session) {
        if (session == null) {
            throw new InvalidRequestException(
                    "session not initialised — call 'initialize' first");
        }
    }

    /** -32600 mapping. */
    public static class InvalidRequestException extends RuntimeException {
        public InvalidRequestException(final String message) {
            super(message);
        }
    }

    /** -32602 mapping. */
    public static class InvalidParamsException extends RuntimeException {
        public InvalidParamsException(final String message) {
            super(message);
        }
    }

    /** Custom -32001 mapping (see McpJsonRpc.CONSENT_REQUIRED). */
    public static class ConsentRequiredException extends RuntimeException {
        public ConsentRequiredException(final String message) {
            super(message);
        }
    }
}
