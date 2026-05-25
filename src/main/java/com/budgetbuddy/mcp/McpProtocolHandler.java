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
    private final McpPromptRegistry promptRegistry;
    private final McpResourceRegistry resourceRegistry;

    public McpProtocolHandler(
            final McpToolRegistry registry,
            final McpSessionRegistry sessionRegistry,
            final ObjectMapper mapper) {
        // Back-compat constructor for tests that don't care about
        // prompts / resources — they still see a working tools surface.
        this(registry, sessionRegistry, mapper, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public McpProtocolHandler(
            final McpToolRegistry registry,
            final McpSessionRegistry sessionRegistry,
            final ObjectMapper mapper,
            final McpPromptRegistry promptRegistry,
            final McpResourceRegistry resourceRegistry) {
        this.registry = registry;
        this.sessionRegistry = sessionRegistry;
        this.mapper = mapper;
        this.promptRegistry = promptRegistry;
        this.resourceRegistry = resourceRegistry;
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
                case "prompts/list":
                    requireSession(session);
                    return handlePromptsList(id);
                case "prompts/get":
                    requireSession(session);
                    return handlePromptsGet(request, user, id);
                case "resources/list":
                    requireSession(session);
                    return handleResourcesList(id);
                case "resources/read":
                    requireSession(session);
                    return handleResourcesRead(request, user, id);
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
        // Honour the user's persistent money-moving consent flag, set
        // from the iOS Settings → MCP screen. When true, the new
        // session starts with consent already granted so AI clients
        // that reconnect don't bug the user every time.
        final boolean persistentConsent = Boolean.TRUE.equals(user.getMcpMoneyMovingConsent());
        final McpSession session = sessionRegistry.create(user.getUserId(), persistentConsent);

        // Per MCP spec, the client identifies itself in
        // params.clientInfo.{name,version}. We capture it so the iOS
        // Settings screen can render "Claude Desktop" instead of a
        // raw session UUID.
        final JsonNode clientInfo = request.path("params").path("clientInfo");
        if (clientInfo.isObject()) {
            session.setClientInfo(
                    clientInfo.path("name").asText(""),
                    clientInfo.path("version").asText(""));
        }

        final ObjectNode result = mapper.createObjectNode();
        result.put("protocolVersion", PROTOCOL_VERSION);

        // Capabilities — tools + prompts + resources.
        final ObjectNode capabilities = result.putObject("capabilities");
        final ObjectNode tools = capabilities.putObject("tools");
        tools.put("listChanged", false);
        final ObjectNode prompts = capabilities.putObject("prompts");
        prompts.put("listChanged", false);
        final ObjectNode resources = capabilities.putObject("resources");
        resources.put("subscribe", false);
        resources.put("listChanged", false);

        // Server info — identifies us to the client.
        final ObjectNode serverInfo = result.putObject("serverInfo");
        serverInfo.put("name", "budgetbuddy-mcp");
        serverInfo.put("version", "1.1.0");

        // Session id — client echoes this in `Mcp-Session-Id` header.
        result.put("sessionId", session.sessionId());
        // Surface persistent-consent state so iOS can render an
        // accurate "consent already granted" badge on first connect.
        result.put("persistentMoneyMovingConsent", persistentConsent);
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

        // Audit: log every tool dispatch with a structured payload so
        // ops can answer "what did the AI just do for user X?" without
        // grepping. MONEY_MOVING tools log at INFO so they show up in
        // default-prod log ingest; READ tools log at DEBUG to avoid
        // drowning the stream. Underlying services still write their
        // own audit-trail rows via MutationAuditInterceptor — this is
        // the access-surface log, not the data-mutation log.
        final long startNanos = System.nanoTime();
        final boolean isMoneyMoving = tool.category() == McpTool.Category.MONEY_MOVING;
        try {
            final JsonNode toolResult = tool.call(args, user, session);
            logToolCall(name, tool.category(), session, user, startNanos, /*ok=*/true,
                    isMoneyMoving);

            final ObjectNode result = mapper.createObjectNode();
            final ArrayNode content = result.putArray("content");
            final ObjectNode block = content.addObject();
            block.put("type", "text");
            block.put("text", toolResult == null ? "{}" : toolResult.toString());
            result.set("structuredContent", toolResult);
            return McpJsonRpc.success(mapper, id, result);
        } catch (final Exception e) {
            logToolCall(name, tool.category(), session, user, startNanos, /*ok=*/false,
                    isMoneyMoving);
            throw e;
        }
    }

    private void logToolCall(
            final String toolName,
            final McpTool.Category category,
            final McpSession session,
            final UserTable user,
            final long startNanos,
            final boolean ok,
            final boolean isMoneyMoving) {
        final long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
        // sessionId is logged in full because it's a per-session UUID,
        // not a credential; userId is the same id already in app logs.
        if (isMoneyMoving) {
            LOGGER.info(
                    "MCP tools/call audit | tool={} category={} sessionId={} userId={} "
                            + "ok={} elapsedMs={}",
                    toolName, category, session.sessionId(), user.getUserId(),
                    ok, elapsedMs);
        } else if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(
                    "MCP tools/call audit | tool={} category={} sessionId={} userId={} "
                            + "ok={} elapsedMs={}",
                    toolName, category, session.sessionId(), user.getUserId(),
                    ok, elapsedMs);
        }
    }

    private ObjectNode handlePromptsList(final JsonNode id) {
        final ObjectNode result = mapper.createObjectNode();
        final ArrayNode list = result.putArray("prompts");
        if (promptRegistry != null) {
            for (final McpPrompt p : promptRegistry.all()) {
                final ObjectNode entry = list.addObject();
                entry.put("name", p.name());
                entry.put("description", p.description());
                final ArrayNode argsNode = entry.putArray("arguments");
                for (final McpPrompt.Argument arg : p.arguments()) {
                    final ObjectNode a = argsNode.addObject();
                    a.put("name", arg.name());
                    a.put("description", arg.description());
                    a.put("required", arg.required());
                }
            }
        }
        return McpJsonRpc.success(mapper, id, result);
    }

    private ObjectNode handlePromptsGet(
            final JsonNode request, final UserTable user, final JsonNode id) {
        if (promptRegistry == null) {
            throw new InvalidParamsException("prompts capability is not available");
        }
        final JsonNode params = request.path("params");
        final String name = params.path("name").asText("");
        if (name.isEmpty()) throw new InvalidParamsException("missing prompt name");
        final McpPrompt prompt = promptRegistry.get(name)
                .orElseThrow(() -> new InvalidParamsException("unknown prompt: " + name));
        final JsonNode args = params.has("arguments")
                ? params.get("arguments") : mapper.createObjectNode();

        final ObjectNode result = mapper.createObjectNode();
        result.put("description", prompt.description());
        final ArrayNode messages = result.putArray("messages");
        for (final ObjectNode msg : prompt.render(args, user)) {
            messages.add(msg);
        }
        return McpJsonRpc.success(mapper, id, result);
    }

    private ObjectNode handleResourcesList(final JsonNode id) {
        final ObjectNode result = mapper.createObjectNode();
        final ArrayNode list = result.putArray("resources");
        if (resourceRegistry != null) {
            for (final McpResource r : resourceRegistry.all()) {
                final ObjectNode entry = list.addObject();
                entry.put("uri", r.uri());
                entry.put("name", r.name());
                entry.put("description", r.description());
                entry.put("mimeType", r.mimeType());
            }
        }
        return McpJsonRpc.success(mapper, id, result);
    }

    private ObjectNode handleResourcesRead(
            final JsonNode request, final UserTable user, final JsonNode id) throws Exception {
        if (resourceRegistry == null) {
            throw new InvalidParamsException("resources capability is not available");
        }
        final JsonNode params = request.path("params");
        final String uri = params.path("uri").asText("");
        if (uri.isEmpty()) throw new InvalidParamsException("missing resource uri");
        final McpResource resource = resourceRegistry.get(uri)
                .orElseThrow(() -> new InvalidParamsException("unknown resource: " + uri));

        final ObjectNode result = mapper.createObjectNode();
        final ArrayNode contents = result.putArray("contents");
        final ObjectNode entry = contents.addObject();
        entry.put("uri", resource.uri());
        entry.put("mimeType", resource.mimeType());
        final JsonNode body = resource.read(user);
        entry.put("text", body == null ? "{}" : body.toString());
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
