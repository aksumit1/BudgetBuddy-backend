package com.budgetbuddy.service.insights.ai;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.budgetbuddy.service.insights.ai.PrivacyPreservingExtractor.SanitizedAnomaly;
import com.budgetbuddy.service.insights.ai.PrivacyPreservingExtractor.SanitizedBudget;
import com.budgetbuddy.service.insights.ai.PrivacyPreservingExtractor.SanitizedGoal;
import com.budgetbuddy.service.insights.ai.PrivacyPreservingExtractor.SanitizedSnapshot;
import com.budgetbuddy.service.insights.ai.PrivacyPreservingExtractor.SanitizedSubscription;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pins the structure of the rendered markdown the chat LLM receives.
 * Adding a new field to {@link SanitizedSnapshot} should also extend
 * this test so a silent regression in the renderer doesn't quietly
 * drop a section from the system prompt.
 */
@SuppressFBWarnings(
        value = "THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION",
        justification = "JUnit idiom")
final class ContextMarkdownRendererTest {

    @Test
    void rendersEverySectionEvenWhenSomeAreEmpty() {
        final SanitizedSnapshot snapshot = new SanitizedSnapshot(
                Map.of("groceries", new BigDecimal("450")),
                Map.of("2026-04", new BigDecimal("1200"), "2026-05", new BigDecimal("1340")),
                Map.of("netflix", new BigDecimal("45")),
                List.of(new SanitizedSubscription("Netflix", new BigDecimal("15.49"), "monthly")),
                List.of(new SanitizedBudget("groceries", new BigDecimal("500"),
                        new BigDecimal("450"), 90.0)),
                List.of(new SanitizedGoal("Emergency fund", new BigDecimal("10000"),
                        new BigDecimal("4500"), 45.0, LocalDate.parse("2026-12-01"),
                        "EMERGENCY_FUND", false)),
                List.of(new SanitizedAnomaly("merchant_redacted", "dining",
                        new BigDecimal("75"), "MEDIUM", "unusual_amount")),
                3, 142, "USD",
                new BigDecimal("12450"),
                new BigDecimal("4200"),
                new BigDecimal("5200"));

        final ContextMarkdownRenderer renderer = new ContextMarkdownRenderer();
        final String md = renderer.render(snapshot);

        // Every top-level section must appear so the LLM can route by heading.
        assertTrue(md.contains("# Portfolio snapshot"), "header missing");
        assertTrue(md.contains("## Income & Net Worth"), "income section missing");
        assertTrue(md.contains("## Spending (last 90 days)"), "spending section missing");
        assertTrue(md.contains("## Active budgets"), "budgets section missing");
        assertTrue(md.contains("## Goals"), "goals section missing");
        assertTrue(md.contains("## Active subscriptions"), "subscriptions section missing");
        assertTrue(md.contains("## Recent anomalies"), "anomalies section missing");

        // Goals: name, progress, target date all surfaced
        assertTrue(md.contains("Emergency fund"));
        assertTrue(md.contains("$4500 / $10000"));
        assertTrue(md.contains("2026-12-01"));

        // Income + net worth surfaced
        assertTrue(md.contains("$5200"), "estimated income missing");
        assertTrue(md.contains("$12450"), "net worth missing");
        assertTrue(md.contains("$4200"), "liquid assets missing");

        // Spending table present
        assertTrue(md.contains("| Category | Amount |"));
        assertTrue(md.contains("| groceries | $450 |"));

        // Budgets table present
        assertTrue(md.contains("| Category | Limit | Spent | % used | Status |"));
        assertTrue(md.contains("approaching limit"));

        // Anomalies table present
        assertTrue(md.contains("merchant_redacted"));
    }

    @Test
    void emptySnapshotStillEmitsAllSections() {
        final SanitizedSnapshot snapshot = new SanitizedSnapshot(
                Map.of(), Map.of(), Map.of(),
                List.of(), List.of(), List.of(), List.of(),
                0, 0, "USD",
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        final String md = new ContextMarkdownRenderer().render(snapshot);

        // Sections appear even when empty so the LLM never wonders if
        // the section was omitted or just not in scope.
        assertTrue(md.contains("## Active budgets\n(none)"));
        assertTrue(md.contains("## Goals\n(none)"));
        assertTrue(md.contains("## Active subscriptions\n(none detected)"));
        assertTrue(md.contains("## Recent anomalies\n(none recent)"));
    }

    @Test
    void completedGoalRendersAsCompleted() {
        final SanitizedSnapshot snapshot = new SanitizedSnapshot(
                Map.of(), Map.of(), Map.of(),
                List.of(), List.of(),
                List.of(new SanitizedGoal("Vacation", new BigDecimal("3000"),
                        new BigDecimal("3000"), 100.0, null, "VACATION", true)),
                List.of(),
                0, 0, "USD",
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        final String md = new ContextMarkdownRenderer().render(snapshot);
        assertTrue(md.contains("Vacation"));
        assertTrue(md.contains("completed"));
    }
}
