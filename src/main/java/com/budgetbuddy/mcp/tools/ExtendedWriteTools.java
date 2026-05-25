package com.budgetbuddy.mcp.tools;

import com.budgetbuddy.mcp.McpSession;
import com.budgetbuddy.mcp.McpTool;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.service.TransactionService;
import com.budgetbuddy.service.UserService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Second-wave write tools — same shape as {@link WriteTools}, every
 * tool is in the {@link McpTool.Category#MONEY_MOVING} category and
 * requires consent. Tools here cover transactions CRUD (create/delete)
 * and user-level preferences the AI agent might be asked to flip.
 *
 * <p>Bean only wires when {@code app.mcp.enabled=true}.
 */
@Configuration
@ConditionalOnProperty(name = "app.mcp.enabled", havingValue = "true")
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Spring beans — services are shared by design")
@SuppressWarnings({"PMD.LawOfDemeter", "PMD.AvoidCatchingGenericException"})
public class ExtendedWriteTools {

    private final ObjectMapper mapper;

    public ExtendedWriteTools(final ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Bean
    public McpTool createTransactionTool(final TransactionService transactionService) {
        final ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        final ObjectNode props = schema.putObject("properties");
        addString(props, "accountId", "Account to attach the transaction to.");
        addNumber(props, "amount", "Amount in user's currency. Negative for expense.");
        addString(props, "transactionDate", "ISO-8601 date (YYYY-MM-DD).");
        addString(props, "description", "Free-text description / merchant.");
        addString(props, "categoryPrimary", "Primary category (taxonomy entry).");
        addString(props, "categoryDetailed", "Optional detailed category.");
        schema.putArray("required")
                .add("accountId").add("amount").add("transactionDate").add("description")
                .add("categoryPrimary");
        return new SimpleTool(
                "create_transaction",
                "Create a transaction on one of the user's accounts.",
                schema,
                McpTool.Category.MONEY_MOVING,
                (args, user, session) -> {
                    final var tx = transactionService.createTransaction(
                            user,
                            args.path("accountId").asText(),
                            new BigDecimal(args.path("amount").asText("0")),
                            LocalDate.parse(args.path("transactionDate").asText()),
                            args.path("description").asText(),
                            args.path("categoryPrimary").asText(),
                            nullable(args.path("categoryDetailed")));
                    return mapper.valueToTree(tx);
                });
    }

    @Bean
    public McpTool deleteTransactionTool(final TransactionService transactionService) {
        final ObjectNode schema = singleStringSchema(
                "transactionId", "Transaction ID to delete (soft-delete).");
        return new SimpleTool(
                "delete_transaction",
                "Soft-delete a transaction (sets deletedAt; reversible from server side).",
                schema,
                McpTool.Category.MONEY_MOVING,
                (args, user, session) -> {
                    transactionService.deleteTransaction(user, args.path("transactionId").asText());
                    final ObjectNode out = mapper.createObjectNode();
                    out.put("deleted", args.path("transactionId").asText());
                    return out;
                });
    }

    @Bean
    public McpTool setAnomalySensitivityTool(final UserService userService) {
        final ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        final ObjectNode props = schema.putObject("properties");
        final ObjectNode level = props.putObject("level");
        level.put("type", "string");
        level.put("description", "loose | normal | strict");
        schema.putArray("required").add("level");
        return new SimpleTool(
                "set_anomaly_sensitivity",
                "Update the user's anomaly-detector sensitivity (loose / normal / strict).",
                schema,
                McpTool.Category.MONEY_MOVING,
                (args, user, session) -> {
                    final String level1 = args.path("level").asText("normal").toLowerCase();
                    if (!"loose".equals(level1) && !"normal".equals(level1)
                            && !"strict".equals(level1)) {
                        final ObjectNode err = mapper.createObjectNode();
                        err.put("error", "level must be loose | normal | strict");
                        return err;
                    }
                    user.setAnomalySensitivity(level1);
                    userService.updateUser(user);
                    final ObjectNode out = mapper.createObjectNode();
                    out.put("anomalySensitivity", level1);
                    return out;
                });
    }

    @Bean
    public McpTool revokeMoneyMovingConsentTool(final UserService userService) {
        return new SimpleTool(
                "revoke_money_moving_consent",
                "Revoke money-moving consent for this session AND clear the persistent flag "
                        + "on the user record.",
                emptySchema(),
                McpTool.Category.MONEY_MOVING,
                (args, user, session) -> {
                    session.revokeMoneyMovingConsent();
                    user.setMcpMoneyMovingConsent(Boolean.FALSE);
                    user.setMcpConsentGrantedAt(null);
                    userService.updateUser(user);
                    final ObjectNode out = mapper.createObjectNode();
                    out.put("revoked", true);
                    return out;
                });
    }

    private ObjectNode emptySchema() {
        final ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties");
        return schema;
    }

    private static void addString(final ObjectNode props, final String name, final String desc) {
        final ObjectNode field = props.putObject(name);
        field.put("type", "string");
        field.put("description", desc);
    }

    private static void addNumber(final ObjectNode props, final String name, final String desc) {
        final ObjectNode field = props.putObject(name);
        field.put("type", "number");
        field.put("description", desc);
    }

    private ObjectNode singleStringSchema(final String field, final String desc) {
        final ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        addString(schema.putObject("properties"), field, desc);
        schema.putArray("required").add(field);
        return schema;
    }

    private static String nullable(final JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        final String s = node.asText("");
        return s.isEmpty() ? null : s;
    }

    private static final class SimpleTool implements McpTool {
        private final String name;
        private final String description;
        private final ObjectNode inputSchema;
        private final Category category;
        private final Handler handler;

        SimpleTool(
                final String name,
                final String description,
                final ObjectNode inputSchema,
                final Category category,
                final Handler handler) {
            this.name = name;
            this.description = description;
            this.inputSchema = inputSchema;
            this.category = category;
            this.handler = handler;
        }

        @Override public String name() { return name; }
        @Override public String description() { return description; }
        @Override public ObjectNode inputSchema() { return inputSchema; }
        @Override public Category category() { return category; }
        @Override
        public JsonNode call(final JsonNode args, final UserTable user, final McpSession session)
                throws Exception {
            return handler.call(args, user, session);
        }

        @FunctionalInterface
        interface Handler {
            JsonNode call(JsonNode args, UserTable user, McpSession session) throws Exception;
        }
    }
}
