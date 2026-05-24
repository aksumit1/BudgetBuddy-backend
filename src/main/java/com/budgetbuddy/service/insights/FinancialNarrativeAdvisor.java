package com.budgetbuddy.service.insights;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * AI-4: optional advisor that converts the user's aggregate financial
 * snapshot into a one-paragraph natural-language narrative.
 *
 * <p>The deterministic /summary surface exposes counts ("3 anomalies,
 * 5 expense reductions") which iOS renders as cards. Helpful for power
 * users; impersonal for everyone else. The advisor produces something
 * like:
 *
 * <blockquote>
 * "You're spending 12% more than last month, with most of the increase in
 * dining. Cash runway is healthy at 4 months, and you're on track with your
 * vacation goal — but two subscriptions you stopped using last quarter
 * are still billing. Worth a 10-minute review."
 * </blockquote>
 *
 * <p>Off by default. When disabled, callers receive a {@code null} narrative
 * and render their existing card stack instead. The narrative MUST NOT be
 * the only source of truth for any number — it's a humanising layer over
 * data the user can also see in detail elsewhere.
 */
public interface FinancialNarrativeAdvisor {

    Narrative narrate(SummarySnapshot snapshot);

    final class SummarySnapshot {
        public String userId;
        public int anomalyCount;
        public int expenseReductionCount;
        public int missedPaymentCount;
        public int highInterestCount;
        public int activeBudgetsCount;
        public int activeGoalsCount;
        public BigDecimal liquidAssets;
        public BigDecimal monthlyIncome;
        public BigDecimal monthlyExpenses;
        public Integer cashRunwayDays;
        public String cashFlowStatus;
        public String subscriptionCreepStatus;
        public Integer budgetsExhaustingSoonCount;
    }

    final class Narrative {
        public String narrative;
        public String tone; // "POSITIVE" | "NEUTRAL" | "CAUTIOUS"

        public Narrative() {}

        public Narrative(final String narrative, final String tone) {
            this.narrative = narrative;
            this.tone = tone;
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) return true;
            if (!(o instanceof Narrative that)) return false;
            return Objects.equals(narrative, that.narrative) && Objects.equals(tone, that.tone);
        }

        @Override
        public int hashCode() {
            return Objects.hash(narrative, tone);
        }
    }
}
