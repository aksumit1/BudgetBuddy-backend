package com.budgetbuddy.service.category;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * LLM-backed classifier that decides whether a recurring-cadence merchant
 * is a real subscription or just per-use spend that happens to repeat.
 *
 * <h3>What this replaces</h3>
 *
 * {@code SubscriptionService.consolidateMultiPriceSubscriptions} today
 * uses heuristics:
 *   - 3+ distinct prices AND max/min &gt; 1.5× → drop as usage
 *   - non-subscription-merchants.yaml allowlist → drop by pattern
 *
 * Both rules have known false negatives:
 *   - Verizon Wireless bills swing $80–$220 month over month (5.5×
 *     spread); the heuristic drops it, but it IS a subscription.
 *   - A neighborhood coffee shop visited daily at $5.50 looks like a
 *     subscription to the cadence rule; it isn't.
 *
 * An LLM with the merchant + amount series + cadence can answer these
 * with much higher accuracy than the count/spread rule.
 *
 * <h3>When to call</h3>
 *
 * Only when the deterministic rules are ambiguous. Most subscriptions
 * (Netflix at flat $15.99) and most non-subscriptions (gas station with
 * 50× spread) don't need the LLM. The candidate set is small (~10
 * borderline merchants per import), so cost is bounded.
 *
 * <h3>Implementation status</h3>
 *
 * Interface ships; Anthropic impl follows the same pattern as
 * {@link AnthropicLlmCategorySuggester}. Wire-up into SubscriptionService
 * is deferred to a separate change so consolidation behaviour isn't
 * silently shifted on enable.
 */
public interface LlmSubscriptionClassifier {

    enum Verdict {
        SUBSCRIPTION,
        VARIABLE_BILL,           // recurring service with fluctuating price (cell, electric, …)
        REPEAT_SPEND,            // per-use spend that happens to cluster (daily coffee)
        UNCERTAIN                // LLM not confident; caller should default to current heuristic
    }

    final class Decision {
        public final Verdict verdict;
        public final double confidence;
        public final String reasoning;

        public Decision(final Verdict verdict, final double confidence, final String reasoning) {
            this.verdict = verdict;
            this.confidence = confidence;
            this.reasoning = reasoning;
        }
    }

    final class Series {
        public final String merchantName;
        public final List<BigDecimal> amounts;
        public final List<LocalDate> dates;
        public final String cadenceHint; // "monthly", "weekly", "quarterly", or null
        public final String category;    // "transportation", "dining", "subscriptions", or null

        public Series(
                final String merchantName,
                final List<BigDecimal> amounts,
                final List<LocalDate> dates,
                final String cadenceHint,
                final String category) {
            this.merchantName = merchantName;
            this.amounts = amounts;
            this.dates = dates;
            this.cadenceHint = cadenceHint;
            this.category = category;
        }
    }

    /**
     * Classify the series. Returns {@link Verdict#UNCERTAIN} when the LLM
     * is below confidence threshold so callers can fall back to the
     * existing heuristic.
     */
    Decision classify(Series series);
}
