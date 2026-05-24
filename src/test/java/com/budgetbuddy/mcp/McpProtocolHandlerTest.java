package com.budgetbuddy.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.budgetbuddy.model.dynamodb.UserTable;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Protocol-shape tests for {@link McpProtocolHandler}. Verifies:
 *
 * <ul>
 *   <li>initialize creates a session and returns the server's capabilities
 *   <li>tools/list returns the registered tools with their schemas
 *   <li>tools/call invokes the matching tool's handler
 *   <li>method-not-found returns -32601
 *   <li>missing session returns -32600 (invalid request)
 *   <li>consent gate blocks money-moving tools until granted
 *   <li>notifications return null (no response body)
 * </ul>
 *
 * <p>These run with a hand-built tool registry + in-memory session
 * registry — no Spring context needed.
 */
@SuppressFBWarnings(
        value = "THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION",
        justification = "JUnit idiom")
final class McpProtocolHandlerTest {

    private ObjectMapper mapper;
    private McpSessionRegistry sessionRegistry;
    private McpToolRegistry toolRegistry;
    private McpProtocolHandler handler;
    private UserTable user;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        sessionRegistry = new McpSessionRegistry();
        toolRegistry = new McpToolRegistry(List.of(echoReadTool(), echoWriteTool()));
        handler = new McpProtocolHandler(toolRegistry, sessionRegistry, mapper);
        user = new UserTable();
        user.setUserId("u1");
        user.setEmail("u1@example.com");
    }

    @Test
    void initializeReturnsServerInfoAndCreatesSession() throws Exception {
        final ObjectNode response = handler.handle(req("initialize", null, 1), user, null);
        assertNotNull(response);
        final JsonNode result = response.get("result");
        assertEquals("2024-11-05", result.path("protocolVersion").asText());
        assertEquals("budgetbuddy-mcp", result.path("serverInfo").path("name").asText());
        final String sessionId = result.path("sessionId").asText();
        assertTrue(sessionRegistry.get(sessionId).isPresent(),
                "initialize must register the session");
    }

    @Test
    void toolsListReturnsRegisteredTools() throws Exception {
        final McpSession session = sessionRegistry.create("u1");
        final JsonNode response = handler.handle(req("tools/list", null, 2), user, session);
        final JsonNode tools = response.path("result").path("tools");
        assertEquals(2, tools.size());
        // The TreeMap-backed registry sorts alphabetically so the
        // order is deterministic — easier to assert on.
        assertEquals("echo_read", tools.get(0).path("name").asText());
        assertEquals("echo_write", tools.get(1).path("name").asText());
    }

    @Test
    void toolsCallInvokesMatchingHandler() throws Exception {
        final McpSession session = sessionRegistry.create("u1");
        final ObjectNode args = mapper.createObjectNode();
        args.put("message", "hi");
        final ObjectNode req = req("tools/call", null, 3);
        final ObjectNode params = req.putObject("params");
        params.put("name", "echo_read");
        params.set("arguments", args);

        final JsonNode response = handler.handle(req, user, session);
        final JsonNode result = response.path("result");
        // tools/call wraps the tool result in {content: [{type:text, text:...}], structuredContent}.
        assertEquals("hi",
                result.path("structuredContent").path("echoed").asText(),
                "echo_read must reflect the supplied message");
    }

    @Test
    void unknownToolReturnsInvalidParams() throws Exception {
        final McpSession session = sessionRegistry.create("u1");
        final ObjectNode req = req("tools/call", null, 4);
        final ObjectNode params = req.putObject("params");
        params.put("name", "definitely_unknown");

        final JsonNode response = handler.handle(req, user, session);
        assertEquals(McpJsonRpc.INVALID_PARAMS, response.path("error").path("code").asInt());
    }

    @Test
    void unknownMethodReturnsMethodNotFound() throws Exception {
        final McpSession session = sessionRegistry.create("u1");
        final JsonNode response = handler.handle(req("nonsense/method", null, 5), user, session);
        assertEquals(McpJsonRpc.METHOD_NOT_FOUND, response.path("error").path("code").asInt());
    }

    @Test
    void writeToolBlockedUntilConsentGranted() throws Exception {
        final McpSession session = sessionRegistry.create("u1");
        final ObjectNode req = req("tools/call", null, 6);
        final ObjectNode params = req.putObject("params");
        params.put("name", "echo_write");

        final JsonNode response = handler.handle(req, user, session);
        assertEquals(McpJsonRpc.CONSENT_REQUIRED, response.path("error").path("code").asInt());
        assertTrue(response.path("error").path("message").asText().contains("consent"));
    }

    @Test
    void writeToolPassesAfterConsentGranted() throws Exception {
        final McpSession session = sessionRegistry.create("u1");
        session.grantMoneyMovingConsent();
        final ObjectNode req = req("tools/call", null, 7);
        final ObjectNode params = req.putObject("params");
        params.put("name", "echo_write");

        final JsonNode response = handler.handle(req, user, session);
        assertNull(response.get("error"),
                "Write tool must succeed after money-moving consent is granted");
        assertNotNull(response.get("result"));
    }

    @Test
    void notificationsReturnNullResponse() {
        final McpSession session = sessionRegistry.create("u1");
        final JsonNode response =
                handler.handle(req("notifications/initialized", null, null), user, session);
        assertNull(response,
                "Notifications must return null so the HTTP layer responds 202 Accepted");
    }

    @Test
    void toolsCallWithoutSessionRejected() throws Exception {
        // tools/call requires an existing session — protocol handler
        // refuses with INVALID_REQUEST when none was supplied.
        final ObjectNode req = req("tools/call", null, 8);
        final ObjectNode params = req.putObject("params");
        params.put("name", "echo_read");
        final JsonNode response = handler.handle(req, user, null);
        assertEquals(McpJsonRpc.INVALID_REQUEST, response.path("error").path("code").asInt());
    }

    @Test
    void consentToolBypassesGateOnFirstCall() throws Exception {
        // The consent tool is itself categorised WRITE but the handler
        // special-cases its name so granting consent doesn't require
        // consent first.
        final McpSession session = sessionRegistry.create("u1");
        final McpTool consent = consentBypassTool();
        toolRegistry = new McpToolRegistry(List.of(echoReadTool(), echoWriteTool(), consent));
        handler = new McpProtocolHandler(toolRegistry, sessionRegistry, mapper);

        final ObjectNode req = req("tools/call", null, 9);
        final ObjectNode params = req.putObject("params");
        params.put("name", "enable_money_moving_consent");
        final ObjectNode args = params.putObject("arguments");
        args.put("confirmation", true);

        final JsonNode response = handler.handle(req, user, session);
        assertNull(response.get("error"),
                "enable_money_moving_consent must bypass its own consent gate");
        assertTrue(session.moneyMovingConsent(),
                "Calling the consent tool with confirmation=true must grant the session");
    }

    private ObjectNode req(final String method, final Object params, final Integer id) {
        final ObjectNode r = mapper.createObjectNode();
        r.put("jsonrpc", "2.0");
        if (id != null) r.put("id", id);
        r.put("method", method);
        if (params != null) r.set("params", mapper.valueToTree(params));
        return r;
    }

    private McpTool echoReadTool() {
        return new TestTool(
                "echo_read",
                "Read-only echo for tests",
                McpTool.Category.READ,
                (args, u, s) -> {
                    final ObjectNode out = new ObjectMapper().createObjectNode();
                    out.put("echoed", args.path("message").asText(""));
                    return out;
                });
    }

    private McpTool echoWriteTool() {
        return new TestTool(
                "echo_write",
                "Write-class echo for consent tests",
                McpTool.Category.WRITE,
                (args, u, s) -> {
                    final ObjectNode out = new ObjectMapper().createObjectNode();
                    out.put("wroteFor", u.getUserId());
                    return out;
                });
    }

    private McpTool consentBypassTool() {
        return new TestTool(
                "enable_money_moving_consent",
                "Test consent tool",
                McpTool.Category.WRITE,
                (args, u, s) -> {
                    if (args.path("confirmation").asBoolean(false)) s.grantMoneyMovingConsent();
                    final ObjectNode out = new ObjectMapper().createObjectNode();
                    out.put("granted", s.moneyMovingConsent());
                    return out;
                });
    }

    private static final class TestTool implements McpTool {
        private final String name;
        private final String description;
        private final Category category;
        private final Body body;

        TestTool(final String name, final String description, final Category category, final Body body) {
            this.name = name;
            this.description = description;
            this.category = category;
            this.body = body;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return description;
        }

        @Override
        public com.fasterxml.jackson.databind.node.ObjectNode inputSchema() {
            final com.fasterxml.jackson.databind.node.ObjectNode schema =
                    new ObjectMapper().createObjectNode();
            schema.put("type", "object");
            return schema;
        }

        @Override
        public Category category() {
            return category;
        }

        @Override
        public JsonNode call(final JsonNode args, final UserTable user, final McpSession session)
                throws Exception {
            return body.call(args, user, session);
        }

        @FunctionalInterface
        interface Body {
            JsonNode call(JsonNode args, UserTable user, McpSession session) throws Exception;
        }
    }
}
