package com.budgetbuddy.mcp.prompts;

import com.budgetbuddy.mcp.McpPrompt;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The shipped MCP prompt library. Each prompt is a curated workflow
 * the AI client can fetch via {@code prompts/get} and replay as the
 * opening chat message. They exist so users don't have to remember the
 * right way to ask Claude/etc. for a "weekly review" or "subscription
 * audit" — the prompt frames the task, names the tools to use, and
 * sets the tone.
 *
 * <p>All prompts are pure — they never query backend services. Heavy
 * data they need lives behind tools and resources; the prompt's job is
 * to teach the AI <em>how</em> to gather and present that data.
 *
 * <p>Bean only wires when {@code app.mcp.enabled=true}.
 */
@Configuration
@ConditionalOnProperty(name = "app.mcp.enabled", havingValue = "true")
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Spring beans — mapper is shared by design")
public class CuratedPrompts {

    private final ObjectMapper mapper;

    public CuratedPrompts(final ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Bean
    public McpPrompt weeklyReviewPrompt() {
        return new SimplePrompt(
                "weekly_review",
                "Walks the user through a weekly money check-in: budgets, "
                        + "subscriptions, and one action item.",
                List.of(new McpPrompt.Argument(
                        "focus_category",
                        "Optional. Limit the review to a single budget category.",
                        false)),
                (args, user) -> {
                    final String focus = args.path("focus_category").asText("");
                    final String focusClause = focus.isBlank()
                            ? ""
                            : " Limit the discussion to the '" + focus + "' category.";
                    return List.of(
                            chatMessage("system",
                                    "You are BudgetBuddy's weekly-review coach. Be concise, "
                                            + "concrete, and end with exactly one action item."),
                            chatMessage("user",
                                    "Run my weekly money review. Use the `list_budgets`, "
                                            + "`budget_exhaustion`, `list_subscriptions`, and "
                                            + "`list_anomalies` tools to gather data; then give "
                                            + "me: (1) what looks healthy, (2) what's drifting, "
                                            + "(3) one specific thing I should do this week."
                                            + focusClause));
                });
    }

    @Bean
    public McpPrompt subscriptionAuditPrompt() {
        return new SimplePrompt(
                "subscription_audit",
                "Audits the user's subscription portfolio — duplicates, "
                        + "low engagement, upcoming renewals, cheaper alternatives.",
                List.of(),
                (args, user) -> List.of(
                        chatMessage("system",
                                "You are a subscription auditor. Be skeptical: flag anything "
                                        + "the user is paying for but barely using."),
                        chatMessage("user",
                                "Audit my subscriptions. Use `list_subscriptions`, "
                                        + "`upcoming_renewals`, `engagement_scores`, "
                                        + "`subscription_health`, and "
                                        + "`cancellation_recommendations`. Group findings into "
                                        + "(1) cancel now, (2) consider cancelling, (3) keep — "
                                        + "and for each cancel-now entry, name a concrete "
                                        + "alternative if one exists.")));
    }

    @Bean
    public McpPrompt goalCoachingPrompt() {
        return new SimplePrompt(
                "goal_coaching",
                "Coaches the user on goal progress: realistic ETA, "
                        + "monthly contribution needed, and one nudge.",
                List.of(new McpPrompt.Argument(
                        "goal_id", "Optional. Coach on a single goal instead of all.", false)),
                (args, user) -> {
                    final String goalId = args.path("goal_id").asText("");
                    final String scope = goalId.isBlank()
                            ? "Use `list_goals` and `goal_projection` to cover every active goal."
                            : "Use `goal_projection` for goal id '" + goalId
                                    + "'; ignore other goals.";
                    return List.of(
                            chatMessage("system",
                                    "You are a calm goal coach. Lead with progress, then the "
                                            + "honest reality of the timeline, then one nudge."),
                            chatMessage("user",
                                    "Coach me on my goal progress. " + scope
                                            + " For each goal, tell me: (1) what % I've hit, "
                                            + "(2) the realistic ETA at my current pace, "
                                            + "(3) the monthly contribution that would hit my "
                                            + "target date, (4) one specific nudge."));
                });
    }

    @Bean
    public McpPrompt anomalyInvestigationPrompt() {
        return new SimplePrompt(
                "anomaly_investigation",
                "Walks through recent transaction anomalies with the user "
                        + "and decides what to do about each.",
                List.of(),
                (args, user) -> List.of(
                        chatMessage("system",
                                "You are an anomaly triage assistant. For each anomaly, name "
                                        + "the merchant, the size of the deviation, and the "
                                        + "single most likely explanation."),
                        chatMessage("user",
                                "Walk me through my recent anomalies. Use `list_anomalies` and, "
                                        + "for any merchant that looks suspicious, "
                                        + "`merchant_trend` to see the 8-week history. For each "
                                        + "anomaly: explain why it was flagged, suggest whether "
                                        + "I should dismiss / investigate / dispute, and stop.")));
    }

    @Bean
    public McpPrompt budgetPlanningPrompt() {
        return new SimplePrompt(
                "budget_planning",
                "Drafts a budget from the user's recent spending and helps "
                        + "them commit to a realistic version.",
                List.of(new McpPrompt.Argument(
                        "monthly_income",
                        "Optional. Override the income figure used in planning.",
                        false)),
                (args, user) -> {
                    final String income = args.path("monthly_income").asText("");
                    final String incomeClause = income.isBlank()
                            ? ""
                            : " Assume monthly income of $" + income + ".";
                    return List.of(
                            chatMessage("system",
                                    "You are a budget planner. Draft numbers grounded in the "
                                            + "user's actual spending; never invent categories."),
                            chatMessage("user",
                                    "Help me plan next month's budget." + incomeClause
                                            + " Use `list_budgets`, `allocation_status`, "
                                            + "`budget_suggestions`, and "
                                            + "`expense_recommendations` to ground the numbers. "
                                            + "Propose a category-by-category plan, flag any "
                                            + "category where my history suggests the proposal "
                                            + "is unrealistic, then ask me which one to commit "
                                            + "to first."));
                });
    }

    private ObjectNode chatMessage(final String role, final String text) {
        final ObjectNode msg = mapper.createObjectNode();
        msg.put("role", role);
        final ObjectNode content = msg.putObject("content");
        content.put("type", "text");
        content.put("text", text);
        return msg;
    }

    /** Functional-interface prompt. */
    private static final class SimplePrompt implements McpPrompt {
        private final String name;
        private final String description;
        private final List<Argument> arguments;
        private final Renderer renderer;

        SimplePrompt(
                final String name,
                final String description,
                final List<Argument> arguments,
                final Renderer renderer) {
            this.name = name;
            this.description = description;
            this.arguments = List.copyOf(arguments);
            this.renderer = renderer;
        }

        @Override public String name() { return name; }
        @Override public String description() { return description; }
        @Override public List<Argument> arguments() { return arguments; }
        @Override
        public List<ObjectNode> render(final JsonNode args, final UserTable user) {
            final JsonNode safe = args == null ? new ObjectMapper().createObjectNode() : args;
            return renderer.render(safe, user);
        }

        @FunctionalInterface
        interface Renderer {
            List<ObjectNode> render(JsonNode args, UserTable user);
        }
    }

}
