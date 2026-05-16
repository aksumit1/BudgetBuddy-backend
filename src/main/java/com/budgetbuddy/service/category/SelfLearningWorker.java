package com.budgetbuddy.service.category;

import com.budgetbuddy.service.TransactionTypeCategoryService.CategoryResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Background worker that drains the {@link UncategorisedReviewQueue},
 * asks the {@link LlmCategorySuggester} to categorise each fall-through
 * row, and writes high-confidence suggestions to the
 * {@link MerchantEnrichmentStore} so subsequent imports of the same
 * merchant hit the cache and skip the LLM entirely.
 *
 * <h3>Self-learning loop end-to-end</h3>
 *
 * <pre>
 *   1. Transaction "MYSTERY THAI HOUSE BELLEVUE WA" arrives
 *   2. L0-L8 cascade returns null (no rule, no merchant DB match, etc.)
 *   3. CategoryCascade submits it to UncategorisedReviewQueue
 *   4. SelfLearningWorker fires on @Scheduled cadence
 *   5. Worker drains the queue → LLM suggests {dining, conf=0.92}
 *   6. Confidence ≥ threshold → write to MerchantEnrichmentStore
 *   7. Next import of "MYSTERY THAI HOUSE" → cache hit → dining
 *      (no LLM call, deterministic from this point on)
 * </pre>
 *
 * <h3>Configuration</h3>
 *
 * <ul>
 *   <li>{@code app.category.self-learning.enabled} — opt-in, default false.
 *       Off-default because activating this calls a paid LLM.
 *   <li>{@code app.category.self-learning.batch-size} — items per run.
 *   <li>{@code app.category.self-learning.confidence-threshold} — below
 *       this, suggestions are dropped (would be flagged for human review
 *       in a richer impl).
 *   <li>{@code app.category.self-learning.fixed-rate-ms} — how often the
 *       worker fires. Defaults to 60s.
 * </ul>
 */
@Service
@ConditionalOnProperty(name = "app.category.self-learning.enabled", havingValue = "true")
public class SelfLearningWorker {

    private static final Logger LOGGER = LoggerFactory.getLogger(SelfLearningWorker.class);

    private final UncategorisedReviewQueue queue;
    private final LlmCategorySuggester suggester;
    private final MerchantEnrichmentStore store;
    private final int batchSize;
    private final double confidenceThreshold;

    public SelfLearningWorker(
            final UncategorisedReviewQueue queue,
            final LlmCategorySuggester suggester,
            final MerchantEnrichmentStore store,
            @Value("${app.category.self-learning.batch-size:50}") final int batchSize,
            @Value("${app.category.self-learning.confidence-threshold:0.75}")
                    final double confidenceThreshold) {
        this.queue = queue;
        this.suggester = suggester;
        this.store = store;
        this.batchSize = batchSize;
        this.confidenceThreshold = confidenceThreshold;
    }

    @Scheduled(fixedRateString = "${app.category.self-learning.fixed-rate-ms:60000}")
    public void drainAndLearn() {
        int processed = 0, accepted = 0, rejected = 0;
        for (final LlmCategorySuggester.SuggestionContext ctx : queue.drain(batchSize)) {
            if (ctx == null) {
                continue;
            }
            processed++;
            final CategoryResult suggestion;
            try {
                suggestion = suggester.suggest(ctx);
            } catch (RuntimeException ex) {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug(
                            "LLM suggestion threw for '{}': {}",
                            ctx.merchantName, ex.getMessage());
                }
                continue;
            }
            if (suggestion == null || suggestion.getConfidence() < confidenceThreshold) {
                rejected++;
                continue;
            }
            // Auto-promote into the learning store. The next import sees
            // the merchant as cached at L4 — no LLM call needed.
            store.put(
                    ctx.merchantName, ctx.city, ctx.state, ctx.country,
                    new CategoryResult(
                            suggestion.getCategoryPrimary(),
                            suggestion.getCategoryDetailed(),
                            "SELF_LEARNED_LLM",
                            suggestion.getConfidence()));
            accepted++;
        }
        if (processed > 0 && LOGGER.isInfoEnabled()) {
            LOGGER.info(
                    "SelfLearningWorker: processed={}, accepted={}, rejected_low_confidence={}",
                    processed, accepted, rejected);
        }
    }
}
