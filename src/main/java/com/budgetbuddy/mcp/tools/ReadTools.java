package com.budgetbuddy.mcp.tools;

import com.budgetbuddy.mcp.McpSession;
import com.budgetbuddy.mcp.McpTool;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.BudgetRepository;
import com.budgetbuddy.repository.dynamodb.GoalRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.service.MerchantSpendTrendService;
import com.budgetbuddy.service.SubscriptionRenewalForecastService;
import com.budgetbuddy.service.SubscriptionService;
import com.budgetbuddy.service.TransactionAnomalyService;
import com.budgetbuddy.service.budget.BudgetForecastService;
import com.budgetbuddy.service.insights.BudgetExhaustionForecastService;
import com.budgetbuddy.service.insights.CashFlowForecastService;
import com.budgetbuddy.service.insights.SubscriptionCreepForecastService;
import com.budgetbuddy.service.subscription.SubscriptionEngagementScorer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Bean factory for every read-only MCP tool. One @Bean method per tool
 * — Spring picks them up automatically because the tool registry
 * autowires {@code List<McpTool>}.
 *
 * <p>Each tool is a thin lambda over an existing service. The point of
 * MCP is to expose what we've already built and tested through a
 * different access surface — there's no new business logic here.
 *
 * <p>Wired only when {@code app.mcp.enabled=true} so dev/test contexts
 * that don't want the surface skip every bean.
 */
@Configuration
@ConditionalOnProperty(name = "app.mcp.enabled", havingValue = "true")
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Spring beans — services are shared by design")
@SuppressWarnings({"PMD.LawOfDemeter", "PMD.AvoidCatchingGenericException"})
public class ReadTools {

    private final ObjectMapper mapper;

    public ReadTools(final ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Bean
    public McpTool listBudgetsTool(final BudgetRepository budgetRepository) {
        return new SimpleTool(
                "list_budgets",
                "Return the user's active budgets with category, period, monthly limit, and "
                        + "carried-over amount. Read-only.",
                emptySchema(),
                McpTool.Category.READ,
                (args, user, session) ->
                        mapper.valueToTree(budgetRepository.findByUserId(user.getUserId())));
    }

    @Bean
    public McpTool listGoalsTool(final GoalRepository goalRepository) {
        return new SimpleTool(
                "list_goals",
                "Return every goal the user has created, active or completed.",
                emptySchema(),
                McpTool.Category.READ,
                (args, user, session) ->
                        mapper.valueToTree(goalRepository.findByUserId(user.getUserId())));
    }

    @Bean
    public McpTool listSubscriptionsTool(final SubscriptionService subscriptionService) {
        return new SimpleTool(
                "list_subscriptions",
                "Return the user's detected subscriptions (active + inactive).",
                emptySchema(),
                McpTool.Category.READ,
                (args, user, session) ->
                        mapper.valueToTree(subscriptionService.getSubscriptions(user.getUserId())));
    }

    @Bean
    public McpTool upcomingRenewalsTool(
            final SubscriptionRenewalForecastService renewalForecast) {
        final ObjectNode schema = emptySchema();
        final ObjectNode props = (ObjectNode) schema.get("properties");
        final ObjectNode windowDays = props.putObject("windowDays");
        windowDays.put("type", "integer");
        windowDays.put("description", "Days of forward window (default 30).");
        return new SimpleTool(
                "upcoming_renewals",
                "Return subscriptions scheduled to renew within the next N days.",
                schema,
                McpTool.Category.READ,
                (args, user, session) -> {
                    final int window = args.path("windowDays").asInt(30);
                    return mapper.valueToTree(
                            renewalForecast.renewalCalendar(
                                    user.getUserId(), window, java.time.LocalDate.now()));
                });
    }

    @Bean
    public McpTool cashFlowForecastTool(final CashFlowForecastService svc) {
        return new SimpleTool(
                "cash_flow_forecast",
                "Return the user's cash runway in days plus 30/60/90-day projected balances.",
                emptySchema(),
                McpTool.Category.READ,
                (args, user, session) -> mapper.valueToTree(svc.forecast(user.getUserId())));
    }

    @Bean
    public McpTool subscriptionCreepTool(final SubscriptionCreepForecastService svc) {
        return new SimpleTool(
                "subscription_creep",
                "Return month-over-month subscription portfolio change with status: "
                        + "SPIKING, CREEPING, STABLE, or SHRINKING.",
                emptySchema(),
                McpTool.Category.READ,
                (args, user, session) -> mapper.valueToTree(svc.forecast(user.getUserId())));
    }

    @Bean
    public McpTool budgetExhaustionTool(final BudgetExhaustionForecastService svc) {
        return new SimpleTool(
                "budget_exhaustion",
                "Return budgets projected to exhaust before their cycle ends, ranked by "
                        + "days-until.",
                emptySchema(),
                McpTool.Category.READ,
                (args, user, session) -> mapper.valueToTree(svc.forecast(user.getUserId())));
    }

    @Bean
    public McpTool listAnomaliesTool(final TransactionAnomalyService anomalyService) {
        return new SimpleTool(
                "list_anomalies",
                "Return detected transaction anomalies with severity and reason.",
                emptySchema(),
                McpTool.Category.READ,
                (args, user, session) -> {
                    final List<TransactionAnomalyService.TransactionAnomaly> anomalies =
                            anomalyService.detectAnomalies(user.getUserId());
                    return mapper.valueToTree(anomalies);
                });
    }

    @Bean
    public McpTool merchantTrendTool(final MerchantSpendTrendService svc) {
        final ObjectNode schema = emptySchema();
        final ObjectNode props = (ObjectNode) schema.get("properties");
        final ObjectNode merchant = props.putObject("merchant");
        merchant.put("type", "string");
        merchant.put("description", "Merchant name to chart (case-insensitive).");
        final ObjectNode weeks = props.putObject("weeks");
        weeks.put("type", "integer");
        weeks.put("description", "Number of weeks of history (default 52, max 156).");
        schema.putArray("required").add("merchant");
        return new SimpleTool(
                "merchant_trend",
                "Return a weekly spend series for a merchant — sparkline data for the iOS "
                        + "subscription/merchant cards.",
                schema,
                McpTool.Category.READ,
                (args, user, session) -> {
                    final String name = args.path("merchant").asText();
                    final int weeksParam = args.path("weeks").asInt(52);
                    return mapper.valueToTree(svc.trend(user.getUserId(), name, weeksParam));
                });
    }

    @Bean
    public McpTool engagementScoresTool(
            final SubscriptionEngagementScorer scorer,
            final SubscriptionService subscriptionService) {
        return new SimpleTool(
                "engagement_scores",
                "Return a composite engagement score (0-100) and tier "
                        + "(ACTIVE/AT_RISK/DORMANT) for every active subscription.",
                emptySchema(),
                McpTool.Category.READ,
                (args, user, session) -> {
                    final var subs = subscriptionService.getActiveSubscriptions(user.getUserId());
                    final var scored = subs.stream().map(scorer::score).toList();
                    return mapper.valueToTree(scored);
                });
    }

    @Bean
    public McpTool searchTransactionsTool(final TransactionRepository txRepository) {
        final ObjectNode schema = emptySchema();
        final ObjectNode props = (ObjectNode) schema.get("properties");
        final ObjectNode startDate = props.putObject("startDate");
        startDate.put("type", "string");
        startDate.put("description", "ISO-8601 start date (YYYY-MM-DD), inclusive.");
        final ObjectNode endDate = props.putObject("endDate");
        endDate.put("type", "string");
        endDate.put("description", "ISO-8601 end date (YYYY-MM-DD), inclusive.");
        final ObjectNode category = props.putObject("category");
        category.put("type", "string");
        category.put("description",
                "Optional category filter — exact match on categoryPrimary or categoryDetailed.");
        final ObjectNode merchant = props.putObject("merchant");
        merchant.put("type", "string");
        merchant.put("description", "Optional merchant-name substring filter (case-insensitive).");
        schema.putArray("required").add("startDate").add("endDate");
        return new SimpleTool(
                "search_transactions",
                "Search the user's transactions by date range + optional category/merchant. "
                        + "Returns the matching rows.",
                schema,
                McpTool.Category.READ,
                (args, user, session) -> {
                    final String start = args.path("startDate").asText();
                    final String end = args.path("endDate").asText();
                    final String cat = args.path("category").asText("");
                    final String merchantNeedle =
                            args.path("merchant").asText("").toLowerCase(java.util.Locale.ROOT);
                    final var rows =
                            txRepository.findByUserIdAndDateRange(user.getUserId(), start, end);
                    final var filtered =
                            rows.stream()
                                    .filter(t -> t != null && t.getDeletedAt() == null)
                                    .filter(
                                            t ->
                                                    cat.isEmpty()
                                                            || cat.equals(t.getCategoryPrimary())
                                                            || cat.equals(t.getCategoryDetailed()))
                                    .filter(
                                            t ->
                                                    merchantNeedle.isEmpty()
                                                            || (t.getMerchantName() != null
                                                                    && t.getMerchantName()
                                                                            .toLowerCase(
                                                                                    java.util.Locale
                                                                                            .ROOT)
                                                                            .contains(
                                                                                    merchantNeedle)))
                                    .toList();
                    return mapper.valueToTree(filtered);
                });
    }

    @Bean
    public McpTool goalProjectionTool(
            final BudgetForecastService budgetForecast,
            final com.budgetbuddy.service.GoalAnalyticsService goalAnalytics,
            final com.budgetbuddy.repository.dynamodb.GoalRepository goalRepository) {
        final ObjectNode schema = emptySchema();
        final ObjectNode props = (ObjectNode) schema.get("properties");
        final ObjectNode goalId = props.putObject("goalId");
        goalId.put("type", "string");
        goalId.put("description", "Goal ID to project.");
        schema.putArray("required").add("goalId");
        return new SimpleTool(
                "goal_projection",
                "Project a goal's likely completion date plus p50/p90 confidence bands.",
                schema,
                McpTool.Category.READ,
                (args, user, session) -> {
                    final String id = args.path("goalId").asText("");
                    final var goalOpt = goalRepository.findById(id);
                    if (goalOpt.isEmpty()) {
                        final ObjectNode err = mapper.createObjectNode();
                        err.put("error", "goal not found: " + id);
                        return err;
                    }
                    return mapper.valueToTree(
                            goalAnalytics.calculateProjection(goalOpt.get(), user.getUserId()));
                });
    }

    private ObjectNode emptySchema() {
        final ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties");
        return schema;
    }

    /** Functional-interface wrapper so each tool is a one-line bean. */
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
