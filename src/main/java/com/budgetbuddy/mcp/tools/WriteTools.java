package com.budgetbuddy.mcp.tools;

import com.budgetbuddy.mcp.McpSession;
import com.budgetbuddy.mcp.McpTool;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.service.BudgetService;
import com.budgetbuddy.service.GoalService;
import com.budgetbuddy.service.SubscriptionService;
import com.budgetbuddy.service.UserService;
import java.time.Instant;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Money-moving + state-changing MCP tools. Every tool here is in the
 * {@link McpTool.Category#MONEY_MOVING} category — the protocol handler
 * refuses to invoke any of them until the session has explicit consent
 * via {@code enable_money_moving_consent}.
 *
 * <p>Each tool delegates to an existing application service that's
 * already tested for the same validation rules and side-effects (audit
 * trail, optimistic locking, cascade deletes, idempotency keys). MCP
 * is a second access surface — no new business logic.
 *
 * <p>Bean only wires when {@code app.mcp.enabled=true}.
 */
@Configuration
@ConditionalOnProperty(name = "app.mcp.enabled", havingValue = "true")
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Spring beans — services are shared by design")
@SuppressWarnings({"PMD.LawOfDemeter", "PMD.AvoidCatchingGenericException"})
public class WriteTools {

    private final ObjectMapper mapper;

    public WriteTools(final ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Consent toggle. The AI calls this first when it intends to mutate
     * user state; the iOS app surfaces a one-time confirmation dialog
     * and only then sets {@code confirmation=true} on the call. We
     * never auto-grant — explicit confirmation per session.
     */
    @Bean
    public McpTool enableMoneyMovingConsentTool(final UserService userService) {
        final ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        final ObjectNode props = schema.putObject("properties");
        final ObjectNode confirm = props.putObject("confirmation");
        confirm.put("type", "boolean");
        confirm.put(
                "description",
                "Must be true. Caller should have obtained explicit user "
                        + "confirmation via the iOS app before setting this.");
        final ObjectNode persistent = props.putObject("persistent");
        persistent.put("type", "boolean");
        persistent.put(
                "description",
                "Optional. When true the consent is saved on the user "
                        + "record so future sessions don't have to re-grant. "
                        + "Defaults to false (per-session consent).");
        schema.putArray("required").add("confirmation");
        // Category is WRITE so it doesn't recursively require consent
        // to grant consent (chicken-and-egg). The protocol handler
        // special-cases this tool name in the consent gate so the
        // first call to it goes through even with consent=false.
        return new SimpleTool(
                "enable_money_moving_consent",
                "Grant the session permission to call money-moving tools. "
                        + "Pass confirmation=true after the iOS app has shown "
                        + "the user a confirmation dialog. Pass persistent=true "
                        + "to save consent on the user record for future sessions.",
                schema,
                McpTool.Category.WRITE,
                (args, user, session) -> {
                    final boolean confirmation = args.path("confirmation").asBoolean(false);
                    if (!confirmation) {
                        final ObjectNode result = mapper.createObjectNode();
                        result.put("granted", false);
                        result.put("reason", "confirmation=true is required");
                        return result;
                    }
                    session.grantMoneyMovingConsent();
                    final boolean persistentFlag = args.path("persistent").asBoolean(false);
                    if (persistentFlag) {
                        user.setMcpMoneyMovingConsent(Boolean.TRUE);
                        user.setMcpConsentGrantedAt(Instant.now());
                        userService.updateUser(user);
                    }
                    final ObjectNode result = mapper.createObjectNode();
                    result.put("granted", true);
                    result.put("persistent", persistentFlag);
                    return result;
                });
    }

    @Bean
    public McpTool createOrUpdateBudgetTool(final BudgetService budgetService) {
        final ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        final ObjectNode props = schema.putObject("properties");
        addString(props, "category", "Category name (lowercase, must match existing taxonomy).");
        addNumber(props, "monthlyLimit", "Limit per period in user's currency.");
        addString(props, "period", "weekly | biweekly | monthly. Defaults to monthly.");
        addString(props, "currencyCode", "ISO-4217. Defaults to USD.");
        addString(props, "budgetId", "Optional — set to update an existing row.");
        addString(props, "goalId", "Optional — link this budget's allocation to a goal.");
        addNumber(props, "goalAllocation", "Optional — portion of limit earmarked for the goal.");
        schema.putArray("required").add("category").add("monthlyLimit");
        return new SimpleTool(
                "create_or_update_budget",
                "Create a budget for a category, or update one if budgetId is provided.",
                schema,
                McpTool.Category.MONEY_MOVING,
                (args, user, session) ->
                        mapper.valueToTree(
                                budgetService.createOrUpdateBudget(
                                        user,
                                        args.path("category").asText(),
                                        decimalOrZero(args.path("monthlyLimit")),
                                        nullable(args.path("budgetId")),
                                        /*rolloverEnabled=*/null,
                                        /*carriedAmount=*/null,
                                        nullable(args.path("goalId")),
                                        args.has("goalAllocation")
                                                ? decimalOrZero(args.path("goalAllocation"))
                                                : null,
                                        nullable(args.path("period")),
                                        nullable(args.path("currencyCode")))));
    }

    @Bean
    public McpTool deleteBudgetTool(final BudgetService budgetService) {
        final ObjectNode schema = singleStringSchema("budgetId", "Budget ID to delete.");
        return new SimpleTool(
                "delete_budget",
                "Delete a budget by id.",
                schema,
                McpTool.Category.MONEY_MOVING,
                (args, user, session) -> {
                    budgetService.deleteBudget(user, args.path("budgetId").asText());
                    final ObjectNode out = mapper.createObjectNode();
                    out.put("deleted", args.path("budgetId").asText());
                    return out;
                });
    }

    @Bean
    public McpTool createGoalTool(final GoalService goalService) {
        final ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        final ObjectNode props = schema.putObject("properties");
        addString(props, "name", "Human-readable goal name (required).");
        addString(props, "description", "Optional description.");
        addNumber(props, "targetAmount", "Target amount in user's currency.");
        addString(props, "targetDate", "ISO-8601 (YYYY-MM-DD). Optional but recommended.");
        addString(props, "goalType",
                "SAVINGS | EMERGENCY_FUND | VACATION | DOWN_PAYMENT | DEBT_PAYOFF | "
                        + "RETIREMENT | EDUCATION | OTHER");
        addNumber(props, "currentAmount", "Optional starting balance (defaults to 0).");
        schema.putArray("required").add("name").add("targetAmount");
        return new SimpleTool(
                "create_goal",
                "Create a new goal.",
                schema,
                McpTool.Category.MONEY_MOVING,
                (args, user, session) ->
                        mapper.valueToTree(
                                goalService.createGoal(
                                        user,
                                        args.path("name").asText(),
                                        args.path("description").asText(""),
                                        decimalOrZero(args.path("targetAmount")),
                                        parseDate(args.path("targetDate").asText("")),
                                        args.path("goalType").asText("SAVINGS"),
                                        /*goalId=*/null,
                                        args.has("currentAmount")
                                                ? decimalOrZero(args.path("currentAmount"))
                                                : null,
                                        List.of())));
    }

    @Bean
    public McpTool markGoalCompleteTool(final GoalService goalService) {
        final ObjectNode schema = singleStringSchema("goalId", "Goal ID to mark complete.");
        return new SimpleTool(
                "mark_goal_complete",
                "Manually mark a goal as completed (sets completedAt = now).",
                schema,
                McpTool.Category.MONEY_MOVING,
                (args, user, session) ->
                        mapper.valueToTree(
                                goalService.manualMarkComplete(user, args.path("goalId").asText())));
    }

    @Bean
    public McpTool restoreGoalTool(final GoalService goalService) {
        final ObjectNode schema = singleStringSchema("goalId", "Goal ID to restore.");
        return new SimpleTool(
                "restore_goal",
                "Restore a soft-deleted goal (clears deletedAt).",
                schema,
                McpTool.Category.MONEY_MOVING,
                (args, user, session) ->
                        mapper.valueToTree(
                                goalService.restoreGoal(user, args.path("goalId").asText())));
    }

    @Bean
    public McpTool deleteGoalTool(final GoalService goalService) {
        final ObjectNode schema = singleStringSchema("goalId", "Goal ID to delete.");
        return new SimpleTool(
                "delete_goal",
                "Soft-delete a goal (cascades to clear goalId on linked transactions/budgets).",
                schema,
                McpTool.Category.MONEY_MOVING,
                (args, user, session) -> {
                    goalService.deleteGoal(user, args.path("goalId").asText());
                    final ObjectNode out = mapper.createObjectNode();
                    out.put("deleted", args.path("goalId").asText());
                    return out;
                });
    }

    @Bean
    public McpTool deleteSubscriptionTool(final SubscriptionService subscriptionService) {
        final ObjectNode schema =
                singleStringSchema("subscriptionId", "Subscription ID to delete.");
        return new SimpleTool(
                "delete_subscription",
                "Delete a subscription by id (hard delete — the row is removed).",
                schema,
                McpTool.Category.MONEY_MOVING,
                (args, user, session) -> {
                    subscriptionService.deleteSubscription(
                            args.path("subscriptionId").asText());
                    final ObjectNode out = mapper.createObjectNode();
                    out.put("deleted", args.path("subscriptionId").asText());
                    return out;
                });
    }

    // ---------------- schema helpers ----------------

    private void addString(final ObjectNode props, final String name, final String description) {
        final ObjectNode field = props.putObject(name);
        field.put("type", "string");
        field.put("description", description);
    }

    private void addNumber(final ObjectNode props, final String name, final String description) {
        final ObjectNode field = props.putObject(name);
        field.put("type", "number");
        field.put("description", description);
    }

    private ObjectNode singleStringSchema(final String field, final String description) {
        final ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        final ObjectNode props = schema.putObject("properties");
        addString(props, field, description);
        schema.putArray("required").add(field);
        return schema;
    }

    private static String nullable(final JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        final String s = node.asText("");
        return s.isEmpty() ? null : s;
    }

    private static BigDecimal decimalOrZero(final JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return BigDecimal.ZERO;
        if (node.isNumber()) return node.decimalValue();
        try {
            return new BigDecimal(node.asText("0"));
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    private static LocalDate parseDate(final String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return LocalDate.parse(raw);
        } catch (Exception e) {
            return null;
        }
    }

    /** Functional-interface tool, same as ReadTools.SimpleTool. */
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

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return description;
        }

        @Override
        public ObjectNode inputSchema() {
            return inputSchema;
        }

        @Override
        public Category category() {
            return category;
        }

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
