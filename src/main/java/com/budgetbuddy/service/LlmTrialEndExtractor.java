package com.budgetbuddy.service;

import java.time.LocalDate;

/**
 * LLM-backed extractor of trial-end dates from transaction descriptions.
 *
 * <p>Statement descriptions occasionally embed trial information:
 *
 * <pre>
 *   "AMZN PRIME *FREE TRIAL ENDS 03/15"
 *   "Netflix trial - first charge 02/28"
 *   "Apple TV+ free week — billing starts Apr 4"
 * </pre>
 *
 * <p>Regex extraction is brittle (a dozen patterns and counting). An LLM
 * call returning a {date, confidence, source-snippet} structure works for
 * any phrasing the model has seen — well-aligned with current LLM strengths
 * (single short text classification with structured output).
 *
 * <p>Powers the iOS "trial ending in 3 days — cancel before $14.99 hits"
 * banner. Current {@link SubscriptionAdvancedService#detectTrialExpirations}
 * does pattern-based detection on subscription category/type; this would
 * augment it with description-based extraction.
 *
 * <h3>Status</h3>
 *
 * Interface ships. Production impl is a small Anthropic call; wire-up
 * into the import pipeline (write extracted date to a new
 * {@code Subscription.trialEndsAt} field) is the follow-up.
 */
public interface LlmTrialEndExtractor {

    final class TrialEndPrediction {
        public final LocalDate trialEndsAt;
        public final double confidence;
        public final String sourceSnippet;

        public TrialEndPrediction(
                final LocalDate trialEndsAt, final double confidence, final String sourceSnippet) {
            this.trialEndsAt = trialEndsAt;
            this.confidence = confidence;
            this.sourceSnippet = sourceSnippet;
        }
    }

    /**
     * Returns null if the description contains no extractable trial date
     * or the LLM is below confidence threshold.
     */
    TrialEndPrediction extract(String merchantName, String description);
}
