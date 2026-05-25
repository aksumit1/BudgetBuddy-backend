package com.budgetbuddy.mcp;

import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Tests the audit log emitted by {@link McpProtocolHandler} on every
 * tools/call dispatch. Pins:
 *
 * <ul>
 *   <li>MONEY_MOVING tools log at INFO so prod log ingest captures them
 *   <li>READ tools log at DEBUG so the stream isn't flooded
 *   <li>Failed dispatches log too — auditors need failure visibility
 *   <li>Every audit line carries tool / category / sessionId / userId / ok
 * </ul>
 */
@SuppressFBWarnings(
        value = "THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION",
        justification = "JUnit idiom")
final class McpProtocolAuditLoggingTest {

    private ObjectMapper mapper;
    private McpSessionRegistry sessionRegistry;
    private McpProtocolHandler handler;
    private UserTable user;
    private Logger handlerLogger;
    private Level originalLevel;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        sessionRegistry = new McpSessionRegistry();
        final McpToolRegistry tools = new McpToolRegistry(List.of(
                readTool(), moneyMovingTool(), throwingTool()));
        handler = new McpProtocolHandler(tools, sessionRegistry, mapper);

        user = new UserTable();
        user.setUserId("u1");

        handlerLogger = (Logger) LoggerFactory.getLogger(McpProtocolHandler.class);
        originalLevel = handlerLogger.getLevel();
        handlerLogger.setLevel(Level.DEBUG);
        appender = new ListAppender<>();
        appender.start();
        handlerLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        handlerLogger.detachAppender(appender);
        handlerLogger.setLevel(originalLevel);
    }

    @Test
    void readToolLogsAtDebug() {
        final McpSession session = sessionRegistry.create("u1");
        callTool(session, "read_echo");
        final ILoggingEvent audit = findAudit("read_echo");
        org.junit.jupiter.api.Assertions.assertEquals(Level.DEBUG, audit.getLevel());
        assertTrue(audit.getFormattedMessage().contains("u1"));
        assertTrue(audit.getFormattedMessage().contains(session.sessionId()));
        assertTrue(audit.getFormattedMessage().contains("ok=true"));
    }

    @Test
    void moneyMovingToolLogsAtInfo() {
        final McpSession session = sessionRegistry.create("u1");
        session.grantMoneyMovingConsent();
        callTool(session, "money_echo");
        final ILoggingEvent audit = findAudit("money_echo");
        org.junit.jupiter.api.Assertions.assertEquals(Level.INFO, audit.getLevel());
        assertTrue(audit.getFormattedMessage().contains("category=MONEY_MOVING"));
        assertTrue(audit.getFormattedMessage().contains("ok=true"));
    }

    @Test
    void failingToolLogsErrorPath() {
        final McpSession session = sessionRegistry.create("u1");
        session.grantMoneyMovingConsent();
        callTool(session, "throws");
        final ILoggingEvent audit = findAudit("throws");
        assertTrue(audit.getFormattedMessage().contains("ok=false"),
                "failed dispatch must still be audited");
    }

    private void callTool(final McpSession session, final String name) {
        final ObjectNode req = mapper.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("id", 1);
        req.put("method", "tools/call");
        final ObjectNode params = req.putObject("params");
        params.put("name", name);
        handler.handle(req, user, session);
    }

    private ILoggingEvent findAudit(final String toolName) {
        return appender.list.stream()
                .filter(e -> e.getFormattedMessage().contains("MCP tools/call audit"))
                .filter(e -> e.getFormattedMessage().contains("tool=" + toolName))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "No audit log for " + toolName + ". Captured: "
                                + appender.list.stream()
                                        .map(ILoggingEvent::getFormattedMessage)
                                        .toList()));
    }

    private McpTool readTool() {
        return functional("read_echo", McpTool.Category.READ,
                (args, u, s) -> mapper.createObjectNode());
    }

    private McpTool moneyMovingTool() {
        return functional("money_echo", McpTool.Category.MONEY_MOVING,
                (args, u, s) -> mapper.createObjectNode());
    }

    private McpTool throwingTool() {
        return functional("throws", McpTool.Category.MONEY_MOVING,
                (args, u, s) -> {
                    throw new IllegalStateException("boom");
                });
    }

    private McpTool functional(final String name, final McpTool.Category cat, final Body body) {
        return new McpTool() {
            @Override public String name() { return name; }
            @Override public String description() { return name; }
            @Override
            public ObjectNode inputSchema() {
                final ObjectNode s = mapper.createObjectNode();
                s.put("type", "object");
                return s;
            }
            @Override public Category category() { return cat; }
            @Override
            public JsonNode call(final JsonNode args, final UserTable u, final McpSession s)
                    throws Exception {
                return body.call(args, u, s);
            }
        };
    }

    @FunctionalInterface
    private interface Body {
        JsonNode call(JsonNode args, UserTable user, McpSession session) throws Exception;
    }
}
