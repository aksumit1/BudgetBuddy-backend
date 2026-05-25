package com.budgetbuddy.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.budgetbuddy.mcp.McpSession;
import com.budgetbuddy.mcp.McpTool;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.service.TransactionService;
import com.budgetbuddy.service.UserService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Per-tool tests for the new write-side MCP tools. Each tool is
 * exercised in isolation: we instantiate the bean factory, fish the
 * tool out by name, invoke its handler with a representative payload,
 * and verify the side-effect on the user record / transaction
 * service / session.
 */
@SuppressFBWarnings(
        value = "THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION",
        justification = "JUnit idiom")
final class ExtendedWriteToolsTest {

    private ObjectMapper mapper;
    private ExtendedWriteTools tools;
    private UserService userService;
    private TransactionService transactionService;
    private UserTable user;
    private McpSession session;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        tools = new ExtendedWriteTools(mapper);
        userService = mock(UserService.class);
        when(userService.updateUser(any(UserTable.class))).thenAnswer(inv -> inv.getArgument(0));
        transactionService = mock(TransactionService.class);
        user = new UserTable();
        user.setUserId("u1");
        user.setEmail("u1@example.com");
        session = new McpSession("u1");
        session.grantMoneyMovingConsent();
    }

    @Test
    void createTransactionToolDelegatesToService() throws Exception {
        final TransactionTable saved = new TransactionTable();
        saved.setTransactionId("tx-123");
        when(transactionService.createTransaction(
                any(UserTable.class), anyString(), any(BigDecimal.class),
                any(LocalDate.class), anyString(), anyString(), any()))
                .thenReturn(saved);

        final McpTool tool = tools.createTransactionTool(transactionService);
        assertEquals(McpTool.Category.MONEY_MOVING, tool.category());

        final ObjectNode args = mapper.createObjectNode();
        args.put("accountId", "acct-1");
        args.put("amount", "-42.50");
        args.put("transactionDate", "2026-05-15");
        args.put("description", "Coffee");
        args.put("categoryPrimary", "FOOD_AND_DRINK");

        final JsonNode result = tool.call(args, user, session);
        assertEquals("tx-123", result.path("transactionId").asText());
        verify(transactionService).createTransaction(
                any(UserTable.class), anyString(), any(BigDecimal.class),
                any(LocalDate.class), anyString(), anyString(), any());
    }

    @Test
    void deleteTransactionToolEchoesId() throws Exception {
        final McpTool tool = tools.deleteTransactionTool(transactionService);
        final ObjectNode args = mapper.createObjectNode();
        args.put("transactionId", "tx-7");
        final JsonNode result = tool.call(args, user, session);
        assertEquals("tx-7", result.path("deleted").asText());
        verify(transactionService).deleteTransaction(user, "tx-7");
    }

    @Test
    void setAnomalySensitivityToolPersistsLevel() throws Exception {
        final McpTool tool = tools.setAnomalySensitivityTool(userService);
        final ObjectNode args = mapper.createObjectNode();
        args.put("level", "strict");
        final JsonNode result = tool.call(args, user, session);
        assertEquals("strict", result.path("anomalySensitivity").asText());
        assertEquals("strict", user.getAnomalySensitivity());
        verify(userService).updateUser(user);
    }

    @Test
    void setAnomalySensitivityToolRejectsInvalidLevel() throws Exception {
        final McpTool tool = tools.setAnomalySensitivityTool(userService);
        final ObjectNode args = mapper.createObjectNode();
        args.put("level", "very-extra-strict");
        final JsonNode result = tool.call(args, user, session);
        assertTrue(result.has("error"),
                "invalid level must surface an error in the structured payload");
        assertNull(user.getAnomalySensitivity(),
                "user record must not be mutated when level is rejected");
    }

    @Test
    void revokeMoneyMovingConsentToolClearsSessionAndUser() throws Exception {
        user.setMcpMoneyMovingConsent(Boolean.TRUE);
        user.setMcpConsentGrantedAt(Instant.now());

        final McpTool tool = tools.revokeMoneyMovingConsentTool(userService);
        final JsonNode result = tool.call(mapper.createObjectNode(), user, session);
        assertTrue(result.path("revoked").asBoolean());
        assertEquals(Boolean.FALSE, user.getMcpMoneyMovingConsent());
        assertNull(user.getMcpConsentGrantedAt());
        assertEquals(false, session.moneyMovingConsent(),
                "session consent must be revoked too");
        verify(userService).updateUser(user);
    }

    @Test
    void everyWriteToolBeanDeclaresMoneyMovingCategory() {
        // Lock the invariant — adding a write tool that's not MONEY_MOVING
        // would silently route around the consent gate.
        assertEquals(McpTool.Category.MONEY_MOVING,
                tools.createTransactionTool(transactionService).category());
        assertEquals(McpTool.Category.MONEY_MOVING,
                tools.deleteTransactionTool(transactionService).category());
        assertEquals(McpTool.Category.MONEY_MOVING,
                tools.setAnomalySensitivityTool(userService).category());
        assertEquals(McpTool.Category.MONEY_MOVING,
                tools.revokeMoneyMovingConsentTool(userService).category());
    }

    @Test
    void writeToolsAdvertiseStableSchemas() {
        final McpTool create = tools.createTransactionTool(transactionService);
        final JsonNode schema = create.inputSchema();
        assertNotNull(schema.get("properties"));
        assertEquals("object", schema.path("type").asText());
        assertTrue(schema.path("required").toString().contains("amount"));
    }
}
