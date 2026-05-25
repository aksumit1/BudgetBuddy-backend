package com.budgetbuddy.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
 * Protocol-shape tests for the prompts + resources capabilities and
 * the persistent-consent hand-off on {@code initialize}.
 *
 * <p>Mirrors {@link McpProtocolHandlerTest} — hand-built registries
 * with stand-in implementations, no Spring context.
 */
@SuppressFBWarnings(
        value = "THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION",
        justification = "JUnit idiom")
final class McpProtocolPromptsResourcesTest {

    private ObjectMapper mapper;
    private McpSessionRegistry sessionRegistry;
    private McpToolRegistry toolRegistry;
    private McpPromptRegistry promptRegistry;
    private McpResourceRegistry resourceRegistry;
    private McpProtocolHandler handler;
    private UserTable user;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        sessionRegistry = new McpSessionRegistry();
        toolRegistry = new McpToolRegistry(List.of());
        promptRegistry = new McpPromptRegistry(List.of(testPrompt()));
        resourceRegistry = new McpResourceRegistry(List.of(testResource()));
        handler = new McpProtocolHandler(
                toolRegistry, sessionRegistry, mapper, promptRegistry, resourceRegistry);
        user = new UserTable();
        user.setUserId("u1");
        user.setEmail("u1@example.com");
    }

    @Test
    void initializeHonoursPersistentConsent() {
        user.setMcpMoneyMovingConsent(Boolean.TRUE);
        final ObjectNode resp = handler.handle(req("initialize", 1), user, null);
        final JsonNode result = resp.path("result");
        assertTrue(result.path("persistentMoneyMovingConsent").asBoolean(),
                "initialize must echo persistent-consent flag");
        final String sessionId = result.path("sessionId").asText();
        final McpSession created = sessionRegistry.get(sessionId).orElseThrow();
        assertTrue(created.moneyMovingConsent(),
                "Session must start with consent granted when user has persistent flag");
    }

    @Test
    void initializeAdvertisesPromptsAndResourcesCapabilities() {
        final ObjectNode resp = handler.handle(req("initialize", 1), user, null);
        final JsonNode caps = resp.path("result").path("capabilities");
        assertNotNull(caps.get("prompts"), "capabilities.prompts must be present");
        assertNotNull(caps.get("resources"), "capabilities.resources must be present");
    }

    @Test
    void promptsListReturnsRegisteredPrompts() {
        final McpSession session = sessionRegistry.create("u1");
        final JsonNode resp = handler.handle(req("prompts/list", 2), user, session);
        final JsonNode prompts = resp.path("result").path("prompts");
        assertEquals(1, prompts.size());
        assertEquals("test_prompt", prompts.get(0).path("name").asText());
    }

    @Test
    void promptsGetRendersMessages() {
        final McpSession session = sessionRegistry.create("u1");
        final ObjectNode req = req("prompts/get", 3);
        final ObjectNode params = req.putObject("params");
        params.put("name", "test_prompt");
        final JsonNode resp = handler.handle(req, user, session);
        final JsonNode messages = resp.path("result").path("messages");
        assertEquals(1, messages.size());
        assertEquals("user", messages.get(0).path("role").asText());
    }

    @Test
    void resourcesListReturnsRegisteredResources() {
        final McpSession session = sessionRegistry.create("u1");
        final JsonNode resp = handler.handle(req("resources/list", 4), user, session);
        final JsonNode resources = resp.path("result").path("resources");
        assertEquals(1, resources.size());
        assertEquals("bb://test/snapshot", resources.get(0).path("uri").asText());
    }

    @Test
    void resourcesReadReturnsContent() {
        final McpSession session = sessionRegistry.create("u1");
        final ObjectNode req = req("resources/read", 5);
        final ObjectNode params = req.putObject("params");
        params.put("uri", "bb://test/snapshot");
        final JsonNode resp = handler.handle(req, user, session);
        final JsonNode contents = resp.path("result").path("contents");
        assertEquals(1, contents.size());
        assertEquals("application/json", contents.get(0).path("mimeType").asText());
        assertTrue(contents.get(0).path("text").asText().contains("u1"),
                "Resource read must include the user-scoped payload");
    }

    @Test
    void unknownPromptReturnsInvalidParams() {
        final McpSession session = sessionRegistry.create("u1");
        final ObjectNode req = req("prompts/get", 6);
        req.putObject("params").put("name", "missing");
        final JsonNode resp = handler.handle(req, user, session);
        assertEquals(McpJsonRpc.INVALID_PARAMS, resp.path("error").path("code").asInt());
    }

    @Test
    void unknownResourceReturnsInvalidParams() {
        final McpSession session = sessionRegistry.create("u1");
        final ObjectNode req = req("resources/read", 7);
        req.putObject("params").put("uri", "bb://nope");
        final JsonNode resp = handler.handle(req, user, session);
        assertEquals(McpJsonRpc.INVALID_PARAMS, resp.path("error").path("code").asInt());
    }

    private ObjectNode req(final String method, final int id) {
        final ObjectNode r = mapper.createObjectNode();
        r.put("jsonrpc", "2.0");
        r.put("id", id);
        r.put("method", method);
        return r;
    }

    private McpPrompt testPrompt() {
        return new McpPrompt() {
            @Override public String name() { return "test_prompt"; }
            @Override public String description() { return "Test prompt"; }
            @Override public List<Argument> arguments() { return List.of(); }
            @Override
            public List<ObjectNode> render(final JsonNode args, final UserTable user) {
                final ObjectNode msg = mapper.createObjectNode();
                msg.put("role", "user");
                final ObjectNode content = msg.putObject("content");
                content.put("type", "text");
                content.put("text", "hello " + user.getUserId());
                return List.of(msg);
            }
        };
    }

    private McpResource testResource() {
        return new McpResource() {
            @Override public String uri() { return "bb://test/snapshot"; }
            @Override public String name() { return "Test snapshot"; }
            @Override public String description() { return "for tests"; }
            @Override
            public JsonNode read(final UserTable u) {
                final ObjectNode body = mapper.createObjectNode();
                body.put("userId", u.getUserId());
                return body;
            }
        };
    }
}
