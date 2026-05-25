package com.budgetbuddy.service.insights.ai;

import com.budgetbuddy.service.insights.ai.PrivacyPreservingExtractor.SanitizedAnomaly;
import com.budgetbuddy.service.insights.ai.PrivacyPreservingExtractor.SanitizedBudget;
import com.budgetbuddy.service.insights.ai.PrivacyPreservingExtractor.SanitizedForecasts;
import com.budgetbuddy.service.insights.ai.PrivacyPreservingExtractor.SanitizedGoal;
import com.budgetbuddy.service.insights.ai.PrivacyPreservingExtractor.SanitizedSnapshot;
import com.budgetbuddy.service.insights.ai.PrivacyPreservingExtractor.SanitizedSubscription;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Renders a {@link SanitizedSnapshot} into a structured markdown
 * document. Used as the system-prompt body for the chat LLM, replacing
 * a raw JSON dump.
 *
 * <p>Why markdown rather than JSON: Claude (and most LLMs) parse
 * markdown headings + tables more accurately for natural-language
 * narration. Headings act as section anchors so the model knows
 * exactly which slice of context to draw from when the user asks
 * about goals vs subscriptions vs spending. Tables collapse
 * tabular data into compact rows that beat row-per-line JSON arrays
 * for long lists.
 *
 * <p>Privacy contract: this class is a pure formatter — it never
 * introduces new fields beyond what the SanitizedSnapshot already
 * exposes. Everything the LLM sees has already passed through
 * {@link PrivacyPreservingExtractor}'s allowlist + bucketing.
 *
 * <p>Format pinned by {@link ContextMarkdownRendererTest}; future
 * field additions should update both.
 */
@Component
public class ContextMarkdownRenderer {

    /**
     * Render the snapshot. Sections appear in stable order:
     * Income & Net Worth, Spending, Budgets, Goals, Subscriptions,
     * Recent Anomalies. Empty sections render as "(none)" so the
     * LLM knows the data was checked, not omitted.
     */
    public String render(final SanitizedSnapshot s) {
        if (s == null) return "# Portfolio\n\n(no data available)";
        final StringBuilder out = new StringBuilder(2048);
        out.append("# Portfolio snapshot\n\n");
        out.append("Currency: **").append(s.currency()).append("**  \n");
        out.append("Accounts: ").append(s.accountCount()).append("  \n");
        out.append("Transactions (last 90 days): ").append(s.transactionCount90d()).append("\n\n");

        renderIncomeAndNetWorth(out, s);
        renderForecasts(out, s);
        renderSpending(out, s);
        renderBudgets(out, s);
        renderGoals(out, s);
        renderSubscriptions(out, s);
        renderAnomalies(out, s);

        return out.toString();
    }

    private void renderIncomeAndNetWorth(final StringBuilder out, final SanitizedSnapshot s) {
        out.append("## Income & Net Worth\n");
        out.append("- Estimated monthly income: ").append(money(s.estimatedMonthlyIncome())).append('\n');
        out.append("- Net worth: ").append(money(s.netWorth())).append('\n');
        out.append("- Liquid assets (checking + savings + cash): ")
                .append(money(s.liquidAssets())).append("\n\n");
    }

    private void renderSpending(final StringBuilder out, final SanitizedSnapshot s) {
        out.append("## Spending (last 90 days)\n");

        // Total
        final BigDecimal total = s.spendingByCategory90d().values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        out.append("Total: ").append(money(total)).append("\n\n");

        // By category — top 10, sorted desc
        if (!s.spendingByCategory90d().isEmpty()) {
            out.append("### By category\n");
            out.append("| Category | Amount |\n|---|---|\n");
            s.spendingByCategory90d().entrySet().stream()
                    .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed())
                    .limit(10)
                    .forEach(e -> out.append("| ").append(e.getKey()).append(" | ")
                            .append(money(e.getValue())).append(" |\n"));
            out.append('\n');
        }

        // By month — chronological
        if (!s.spendingByMonth().isEmpty()) {
            out.append("### By month\n");
            out.append("| Month | Spent |\n|---|---|\n");
            s.spendingByMonth().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(e -> out.append("| ").append(e.getKey()).append(" | ")
                            .append(money(e.getValue())).append(" |\n"));
            out.append('\n');
        }

        // Top known merchants — only allowlisted brands; everything else is collapsed
        if (!s.spendingByKnownMerchant90d().isEmpty()) {
            out.append("### Top known merchants\n");
            final String merchants = s.spendingByKnownMerchant90d().entrySet().stream()
                    .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed())
                    .limit(5)
                    .map(e -> e.getKey() + " (" + money(e.getValue()) + ")")
                    .collect(Collectors.joining(", "));
            out.append(merchants).append("\n\n");
        }
    }

    private void renderBudgets(final StringBuilder out, final SanitizedSnapshot s) {
        out.append("## Active budgets\n");
        final List<SanitizedBudget> budgets = s.budgets();
        if (budgets == null || budgets.isEmpty()) {
            out.append("(none)\n\n");
            return;
        }
        out.append("| Category | Limit | Spent | % used | Status |\n|---|---|---|---|---|\n");
        for (final SanitizedBudget b : budgets) {
            final String status = b.percentUsed() >= 100 ? "OVER"
                    : b.percentUsed() >= 80 ? "approaching limit" : "on track";
            out.append("| ").append(b.category())
                    .append(" | ").append(money(b.limit()))
                    .append(" | ").append(money(b.spent()))
                    .append(" | ").append(String.format("%.0f%%", b.percentUsed()))
                    .append(" | ").append(status).append(" |\n");
        }
        out.append('\n');
    }

    private void renderGoals(final StringBuilder out, final SanitizedSnapshot s) {
        out.append("## Goals\n");
        final List<SanitizedGoal> goals = s.goals();
        if (goals == null || goals.isEmpty()) {
            out.append("(none)\n\n");
            return;
        }
        for (final SanitizedGoal g : goals) {
            out.append("- **").append(g.name()).append("** (").append(g.goalType()).append(")");
            if (g.completed()) {
                out.append(" — ✅ completed\n");
                continue;
            }
            out.append(": ").append(money(g.currentAmount()))
                    .append(" / ").append(money(g.targetAmount()))
                    .append(String.format(" (%.0f%%)", g.percentComplete()));
            if (g.targetDate() != null) {
                out.append(", target ").append(g.targetDate());
            }
            out.append('\n');
        }
        out.append('\n');
    }

    private void renderSubscriptions(final StringBuilder out, final SanitizedSnapshot s) {
        out.append("## Active subscriptions\n");
        final List<SanitizedSubscription> subs = s.subscriptions();
        if (subs == null || subs.isEmpty()) {
            out.append("(none detected)\n\n");
            return;
        }
        final BigDecimal monthlyTotal = subs.stream()
                .map(sub -> sub.monthlyCost() == null ? BigDecimal.ZERO : sub.monthlyCost())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        out.append("Total monthly: ").append(money(monthlyTotal)).append("\n\n");
        out.append("| Name | Monthly | Cycle |\n|---|---|---|\n");
        for (final SanitizedSubscription sub : subs) {
            out.append("| ").append(sub.displayName())
                    .append(" | ").append(money(sub.monthlyCost()))
                    .append(" | ").append(sub.billingCycle() == null ? "monthly" : sub.billingCycle())
                    .append(" |\n");
        }
        out.append('\n');
    }

    private void renderForecasts(final StringBuilder out, final SanitizedSnapshot s) {
        final SanitizedForecasts f = s.forecasts();
        if (f == null || (f.runwayDays() < 0
                && f.budgetsExhaustingThisCycle() == 0
                && "UNKNOWN".equals(f.subscriptionCreepStatus()))) {
            return;  // No forecast surface available — silently omit.
        }
        out.append("## Forecasts\n");
        if (f.runwayDays() >= 0) {
            out.append("- Cash-flow runway: **").append(f.runwayDays()).append(" days** (")
                    .append(f.cashFlowStatus()).append(")");
            if (f.cashFlowMessage() != null && !f.cashFlowMessage().isBlank()) {
                out.append(" — ").append(f.cashFlowMessage());
            }
            out.append('\n');
            out.append("- Projected balance: 30d ").append(money(f.projectedCashAt30Days()))
                    .append(", 60d ").append(money(f.projectedCashAt60Days()))
                    .append(", 90d ").append(money(f.projectedCashAt90Days())).append('\n');
        }
        if (f.budgetsExhaustingThisCycle() > 0) {
            out.append("- Budgets projected to exhaust this cycle: ")
                    .append(f.budgetsExhaustingThisCycle());
            if (!f.budgetsExhaustingCategories().isEmpty()) {
                out.append(" (").append(String.join(", ", f.budgetsExhaustingCategories()))
                        .append(")");
            }
            out.append('\n');
        }
        if (!"UNKNOWN".equals(f.subscriptionCreepStatus())) {
            out.append("- Subscription portfolio: ").append(f.subscriptionCreepStatus());
            if (f.subscriptionCreepMessage() != null
                    && !f.subscriptionCreepMessage().isBlank()) {
                out.append(" — ").append(f.subscriptionCreepMessage());
            }
            out.append('\n');
        }
        out.append('\n');
    }

    private void renderAnomalies(final StringBuilder out, final SanitizedSnapshot s) {
        out.append("## Recent anomalies\n");
        final List<SanitizedAnomaly> anomalies = s.recentAnomalies();
        if (anomalies == null || anomalies.isEmpty()) {
            out.append("(none recent)\n\n");
            return;
        }
        out.append("| Merchant | Category | Amount | Severity | Type |\n|---|---|---|---|---|\n");
        for (final SanitizedAnomaly a : anomalies) {
            out.append("| ").append(a.merchant())
                    .append(" | ").append(a.category())
                    .append(" | ").append(money(a.amount()))
                    .append(" | ").append(a.severity())
                    .append(" | ").append(a.type()).append(" |\n");
        }
        out.append('\n');
    }

    private static String money(final BigDecimal v) {
        if (v == null) return "$0";
        return "$" + v.setScale(0, java.math.RoundingMode.HALF_UP).toPlainString();
    }
}
