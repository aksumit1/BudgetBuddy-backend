package com.budgetbuddy.mcp.resources;

import com.budgetbuddy.mcp.McpResource;
import com.budgetbuddy.repository.dynamodb.BudgetRepository;
import com.budgetbuddy.repository.dynamodb.GoalRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.service.SubscriptionService;
import com.budgetbuddy.service.TransactionAnomalyService;
import com.budgetbuddy.service.insights.BudgetExhaustionForecastService;
import com.budgetbuddy.service.insights.CashFlowForecastService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The shipped MCP resource library — addressable read-only snapshots
 * AI clients can pin into their conversation context. Resources are
 * cheap reads (no tool-call ceremony) over the same data the
 * corresponding tools expose, so clients can refresh context every
 * turn without burning consent prompts.
 *
 * <p>URI scheme is {@code bb://<area>/<slice>}. Each resource is a
 * thin delegate over a service that's already tested; this class adds
 * no business logic.
 *
 * <p>Bean only wires when {@code app.mcp.enabled=true}.
 */
@Configuration
@ConditionalOnProperty(name = "app.mcp.enabled", havingValue = "true")
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Spring beans — services are shared by design")
public class CuratedResources {

    private final ObjectMapper mapper;

    public CuratedResources(final ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Bean
    public McpResource budgetsCurrentResource(final BudgetRepository budgetRepository) {
        return new SimpleResource(
                "bb://budgets/current",
                "Current budgets",
                "Active budgets for the user, including limits and carried amounts.",
                user -> mapper.valueToTree(budgetRepository.findByUserId(user.getUserId())));
    }

    @Bean
    public McpResource goalsActiveResource(final GoalRepository goalRepository) {
        return new SimpleResource(
                "bb://goals/active",
                "Active goals",
                "Every goal the user has, active or completed, with progress fields.",
                user -> mapper.valueToTree(goalRepository.findByUserId(user.getUserId())));
    }

    @Bean
    public McpResource forecastsSummaryResource(
            final CashFlowForecastService cashFlow,
            final BudgetExhaustionForecastService exhaustion) {
        return new SimpleResource(
                "bb://forecasts/summary",
                "Forecast summary",
                "Cash-flow runway plus budgets projected to exhaust this cycle.",
                user -> {
                    final ObjectNode out = mapper.createObjectNode();
                    out.set("cashFlow", mapper.valueToTree(cashFlow.forecast(user.getUserId())));
                    out.set("budgetExhaustion",
                            mapper.valueToTree(exhaustion.forecast(user.getUserId())));
                    return out;
                });
    }

    @Bean
    public McpResource anomaliesRecentResource(final TransactionAnomalyService anomalyService) {
        return new SimpleResource(
                "bb://insights/anomalies/recent",
                "Recent anomalies",
                "Recent detected transaction anomalies with severity and reason.",
                user -> mapper.valueToTree(anomalyService.detectAnomalies(user.getUserId())));
    }

    @Bean
    public McpResource subscriptionsActiveResource(final SubscriptionService subscriptionService) {
        return new SimpleResource(
                "bb://subscriptions/active",
                "Active subscriptions",
                "Active subscriptions detected for the user, with cadence + amount.",
                user -> mapper.valueToTree(
                        subscriptionService.getActiveSubscriptions(user.getUserId())));
    }

    @Bean
    public McpResource transactionsRecentResource(final TransactionRepository txRepository) {
        return new SimpleResource(
                "bb://transactions/recent",
                "Recent transactions",
                "The last 60 days of non-deleted transactions for the user.",
                user -> {
                    final java.time.LocalDate end = java.time.LocalDate.now();
                    final java.time.LocalDate start = end.minusDays(60);
                    final var rows = txRepository.findByUserIdAndDateRange(
                            user.getUserId(), start.toString(), end.toString());
                    final var filtered = rows.stream()
                            .filter(t -> t != null && t.getDeletedAt() == null)
                            .toList();
                    return mapper.valueToTree(filtered);
                });
    }

    private static final class SimpleResource implements McpResource {
        private final String uri;
        private final String name;
        private final String description;
        private final Reader reader;

        SimpleResource(
                final String uri,
                final String name,
                final String description,
                final Reader reader) {
            this.uri = uri;
            this.name = name;
            this.description = description;
            this.reader = reader;
        }

        @Override public String uri() { return uri; }
        @Override public String name() { return name; }
        @Override public String description() { return description; }
        @Override
        public JsonNode read(final com.budgetbuddy.model.dynamodb.UserTable user) throws Exception {
            return reader.read(user);
        }

        @FunctionalInterface
        interface Reader {
            JsonNode read(com.budgetbuddy.model.dynamodb.UserTable user) throws Exception;
        }
    }
}
