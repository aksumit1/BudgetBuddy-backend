package com.budgetbuddy.mcp.tools;

import com.budgetbuddy.mcp.McpSession;
import com.budgetbuddy.mcp.McpTool;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.service.BudgetSuggestionService;
import com.budgetbuddy.service.ExpenseReductionService;
import com.budgetbuddy.service.FinancialGoalsRecommendationService;
import com.budgetbuddy.service.HighInterestDetectionService;
import com.budgetbuddy.service.MissedPaymentDetectionService;
import com.budgetbuddy.service.SubscriptionAdvancedService;
import com.budgetbuddy.service.SubscriptionInsightsService;
import com.budgetbuddy.service.SubscriptionService;
import com.budgetbuddy.service.BudgetAllocationStatusService;
import com.budgetbuddy.service.subscription.TaxDeductibilityClassifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Second-wave read tools. These cover the surface the user asked for
 * beyond v1: full transaction CRUD reads, accounts, missed payments,
 * high-interest alerts, recommendations, allocation status,
 * subscription advanced views, and a financial-insights summary.
 *
 * <p>Same shape as {@link ReadTools} — every tool is a thin lambda
 * over an existing service. No new business logic here.
 *
 * <p>Bean only wires when {@code app.mcp.enabled=true}.
 */
@Configuration
@ConditionalOnProperty(name = "app.mcp.enabled", havingValue = "true")
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Spring beans — services are shared by design")
@SuppressWarnings({"PMD.LawOfDemeter", "PMD.AvoidCatchingGenericException", "PMD.TooManyMethods"})
public class ExtendedReadTools {

    private final ObjectMapper mapper;

    public ExtendedReadTools(final ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Bean
    public McpTool getUserProfileTool() {
        return new SimpleTool(
                "get_user_profile",
                "Return the user's profile fields (id, email, name, currency, timezone) "
                        + "and MCP-related preferences (persistent consent flag).",
                emptySchema(),
                McpTool.Category.READ,
                (args, user, session) -> {
                    final ObjectNode out = mapper.createObjectNode();
                    out.put("userId", user.getUserId());
                    out.put("email", user.getEmail());
                    out.put("firstName", user.getFirstName());
                    out.put("lastName", user.getLastName());
                    out.put("preferredCurrency", user.getPreferredCurrency());
                    out.put("timezone", user.getTimezone());
                    out.put("anomalySensitivity", user.getAnomalySensitivity());
                    out.put("mcpMoneyMovingConsent",
                            Boolean.TRUE.equals(user.getMcpMoneyMovingConsent()));
                    if (user.getMcpConsentGrantedAt() != null) {
                        out.put("mcpConsentGrantedAt",
                                user.getMcpConsentGrantedAt().toString());
                    }
                    return out;
                });
    }

    @Bean
    public McpTool listAccountsTool(final AccountRepository accountRepository) {
        return new SimpleTool(
                "list_accounts",
                "List the user's linked accounts with type, mask, balance, and institution.",
                emptySchema(),
                McpTool.Category.READ,
                (args, user, session) ->
                        mapper.valueToTree(accountRepository.findByUserId(user.getUserId())));
    }

    @Bean
    public McpTool listTransactionsTool(final TransactionRepository txRepository) {
        final ObjectNode schema = emptySchema();
        final ObjectNode props = (ObjectNode) schema.get("properties");
        addString(props, "startDate", "ISO-8601 start date (YYYY-MM-DD), inclusive.");
        addString(props, "endDate", "ISO-8601 end date (YYYY-MM-DD), inclusive.");
        addInt(props, "limit", "Max rows to return (default 100, max 500).");
        schema.putArray("required").add("startDate").add("endDate");
        return new SimpleTool(
                "list_transactions",
                "Return transactions in a date range (non-deleted only). Higher-cap variant "
                        + "of search_transactions without filters.",
                schema,
                McpTool.Category.READ,
                (args, user, session) -> {
                    final var rows = txRepository.findByUserIdAndDateRange(
                            user.getUserId(),
                            args.path("startDate").asText(),
                            args.path("endDate").asText());
                    final int cap = Math.min(args.path("limit").asInt(100), 500);
                    final var filtered = rows.stream()
                            .filter(t -> t != null && t.getDeletedAt() == null)
                            .limit(cap)
                            .toList();
                    return mapper.valueToTree(filtered);
                });
    }

    @Bean
    public McpTool missedPaymentsTool(final MissedPaymentDetectionService svc) {
        return new SimpleTool(
                "missed_payments",
                "Detect bills/subscriptions that look like they should have charged but "
                        + "haven't — returns alerts with severity.",
                emptySchema(),
                McpTool.Category.READ,
                (args, user, session) ->
                        mapper.valueToTree(svc.detectMissedPayments(user.getUserId())));
    }

    @Bean
    public McpTool highInterestAlertsTool(final HighInterestDetectionService svc) {
        return new SimpleTool(
                "high_interest_alerts",
                "Detect transactions on high-interest accounts (credit cards / payday loans) "
                        + "that suggest the user is carrying expensive debt.",
                emptySchema(),
                McpTool.Category.READ,
                (args, user, session) ->
                        mapper.valueToTree(svc.detectHighInterest(user.getUserId())));
    }

    @Bean
    public McpTool expenseRecommendationsTool(final ExpenseReductionService svc) {
        return new SimpleTool(
                "expense_recommendations",
                "Return ranked expense-reduction recommendations by category.",
                emptySchema(),
                McpTool.Category.READ,
                (args, user, session) ->
                        mapper.valueToTree(svc.getRecommendations(user.getUserId())));
    }

    @Bean
    public McpTool goalSuggestionsTool(final FinancialGoalsRecommendationService svc) {
        return new SimpleTool(
                "goal_suggestions",
                "Suggest goals appropriate for the user's spending patterns + life-stage hints.",
                emptySchema(),
                McpTool.Category.READ,
                (args, user, session) ->
                        mapper.valueToTree(svc.getRecommendations(user.getUserId())));
    }

    @Bean
    public McpTool budgetSuggestionsTool(final BudgetSuggestionService svc) {
        return new SimpleTool(
                "budget_suggestions",
                "Suggest budget limits per category based on the user's historical spend.",
                emptySchema(),
                McpTool.Category.READ,
                (args, user, session) -> mapper.valueToTree(svc.suggestForUser(user)));
    }

    @Bean
    public McpTool allocationStatusTool(final BudgetAllocationStatusService svc) {
        return new SimpleTool(
                "allocation_status",
                "Return zero-based-budget allocation status: estimated income, totals "
                        + "allocated, remaining, and OVER/UNDER/BALANCED tag.",
                emptySchema(),
                McpTool.Category.READ,
                (args, user, session) -> mapper.valueToTree(svc.compute(user)));
    }

    @Bean
    public McpTool cancellationRecommendationsTool(final SubscriptionInsightsService svc) {
        return new SimpleTool(
                "cancellation_recommendations",
                "Return subscriptions the system recommends cancelling, with priority + reason.",
                emptySchema(),
                McpTool.Category.READ,
                (args, user, session) ->
                        mapper.valueToTree(svc.getCancellationRecommendations(user.getUserId())));
    }

    @Bean
    public McpTool subscriptionAlternativesTool(final SubscriptionAdvancedService svc) {
        return new SimpleTool(
                "subscription_alternatives",
                "Suggest cheaper alternatives for the user's current subscriptions.",
                emptySchema(),
                McpTool.Category.READ,
                (args, user, session) ->
                        mapper.valueToTree(svc.suggestAlternatives(user.getUserId())));
    }

    @Bean
    public McpTool subscriptionHealthTool(
            final SubscriptionAdvancedService svc,
            final SubscriptionService subscriptionService) {
        return new SimpleTool(
                "subscription_health",
                "Return per-subscription health scores (0-100) and issue flags.",
                emptySchema(),
                McpTool.Category.READ,
                (args, user, session) -> {
                    final var subs = subscriptionService.getActiveSubscriptions(user.getUserId());
                    final var scored = subs.stream()
                            .map(s -> svc.calculateHealthScore(user.getUserId(), s))
                            .toList();
                    return mapper.valueToTree(scored);
                });
    }

    @Bean
    public McpTool taxDeductibilityTool(
            final TaxDeductibilityClassifier classifier,
            final SubscriptionService subscriptionService) {
        return new SimpleTool(
                "tax_deductibility",
                "Classify each active subscription's tax-deductibility tier "
                        + "(FULL / PARTIAL / NONE).",
                emptySchema(),
                McpTool.Category.READ,
                (args, user, session) -> {
                    final var subs = subscriptionService.getActiveSubscriptions(user.getUserId());
                    final List<Map<String, Object>> rows = subs.stream()
                            .map(s -> {
                                final Map<String, Object> row = new LinkedHashMap<>();
                                row.put("subscriptionId", s.getSubscriptionId());
                                row.put("name", s.getMerchantName());
                                row.put("amount", s.getAmount());
                                row.put("deductibility", classifier.classify(s).name());
                                return row;
                            })
                            .toList();
                    return mapper.valueToTree(rows);
                });
    }

    @Bean
    public McpTool financialInsightsSummaryTool(
            final MissedPaymentDetectionService missed,
            final HighInterestDetectionService highInterest,
            final SubscriptionInsightsService insights,
            final ExpenseReductionService expense) {
        return new SimpleTool(
                "financial_insights_summary",
                "One-shot rollup the AI can use to brief the user: missed payments count, "
                        + "high-interest alerts count, cancellation recommendation count, and "
                        + "top-3 expense-reduction picks.",
                emptySchema(),
                McpTool.Category.READ,
                (args, user, session) -> {
                    final ObjectNode out = mapper.createObjectNode();
                    out.put("missedPayments",
                            missed.detectMissedPayments(user.getUserId()).size());
                    out.put("highInterestAlerts",
                            highInterest.detectHighInterest(user.getUserId()).size());
                    out.put("cancellationRecommendations",
                            insights.getCancellationRecommendations(user.getUserId()).size());
                    final var topExpense = expense.getRecommendations(user.getUserId())
                            .stream()
                            .limit(3)
                            .toList();
                    out.set("topExpenseRecommendations", mapper.valueToTree(topExpense));
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

    private static void addInt(final ObjectNode props, final String name, final String desc) {
        final ObjectNode field = props.putObject(name);
        field.put("type", "integer");
        field.put("description", desc);
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
