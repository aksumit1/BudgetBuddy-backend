package com.budgetbuddy.service.category;

import com.budgetbuddy.service.TransactionTypeCategoryService.CategoryResult;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
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
    /**
     * Shadow mode: when true, the worker still drains the queue and asks
     * the LLM, but writes are logged INSTEAD of persisted to the
     * MerchantEnrichmentStore. Use this to verify suggestion quality on
     * real production traffic before flipping self-learning to true. Costs
     * the same as normal mode (LLM is called); buys observability without
     * risk of bad suggestions reaching the cache.
     */
    private final boolean shadowMode;
    /**
     * Per-day suggestion budget. A pure ceiling on `suggester.suggest(...)`
     * calls per UTC day — no $ math, just a count. At Haiku rates a 25k
     * call/day cap is ~$25/day worst case (one paragraph in, one short JSON
     * out per call); operators can tune via env. Once the cap is hit, the
     * worker logs once and short-circuits subsequent fires until UTC
     * midnight. This protects against a queue-explosion runaway when the
     * upstream cascade suddenly stops resolving (e.g. merchants.json
     * deploy regression dumps thousands of merchants into L9).
     */
    private final int maxSuggestionsPerDay;
    private final AtomicInteger suggestionsToday = new AtomicInteger(0);
    private volatile LocalDate countingDay = LocalDate.now(java.time.ZoneOffset.UTC);

    public SelfLearningWorker(
            final UncategorisedReviewQueue queue,
            final LlmCategorySuggester suggester,
            final MerchantEnrichmentStore store,
            @Value("${app.category.self-learning.batch-size:50}") final int batchSize,
            @Value("${app.category.self-learning.confidence-threshold:0.75}")
                    final double confidenceThreshold,
            @Value("${app.category.self-learning.max-suggestions-per-day:25000}")
                    final int maxSuggestionsPerDay,
            @Value("${app.category.self-learning.shadow-mode:false}")
                    final boolean shadowMode) {
        this.queue = queue;
        this.suggester = suggester;
        this.store = store;
        this.batchSize = batchSize;
        this.confidenceThreshold = confidenceThreshold;
        this.maxSuggestionsPerDay = maxSuggestionsPerDay;
        this.shadowMode = shadowMode;
        if (shadowMode) {
            LOGGER.warn(
                    "SelfLearningWorker: SHADOW MODE active — suggestions will be LOGGED, not persisted");
        }
    }

    private static final class SuggestPair {
        final LlmCategorySuggester.SuggestionContext ctx;
        final CategoryResult result;

        SuggestPair(final LlmCategorySuggester.SuggestionContext ctx, final CategoryResult result) {
            this.ctx = ctx;
            this.result = result;
        }
    }

    private boolean dailyBudgetExhausted() {
        final LocalDate today = LocalDate.now(java.time.ZoneOffset.UTC);
        if (!today.equals(countingDay)) {
            countingDay = today;
            suggestionsToday.set(0);
        }
        return suggestionsToday.get() >= maxSuggestionsPerDay;
    }

    @Scheduled(fixedRateString = "${app.category.self-learning.fixed-rate-ms:60000}")
    public void drainAndLearn() {
        if (dailyBudgetExhausted()) {
            LOGGER.warn(
                    "SelfLearningWorker: daily suggestion budget exhausted ({}/{}); skipping until UTC midnight",
                    suggestionsToday.get(), maxSuggestionsPerDay);
            return;
        }

        // Drain into a list so we can launch parallel suggest() calls. The
        // suggester is HTTP-bound; serial calls left wall-clock throughput
        // at one merchant per round-trip (~500ms each). A parallelism cap
        // of 8 brings 50-merchant batches from ~25s down to ~3s without
        // blowing past Anthropic's per-key rate limit (default 50 RPS for
        // Haiku tier).
        final List<LlmCategorySuggester.SuggestionContext> ctxs = new ArrayList<>();
        for (final LlmCategorySuggester.SuggestionContext ctx : queue.drain(batchSize)) {
            if (ctx == null) continue;
            ctxs.add(ctx);
        }
        if (ctxs.isEmpty()) {
            return;
        }

        int processed = 0, accepted = 0, rejected = 0;
        final ExecutorService pool = Executors.newFixedThreadPool(Math.min(8, ctxs.size()));
        final List<Future<SuggestPair>> futures = new ArrayList<>(ctxs.size());
        for (final LlmCategorySuggester.SuggestionContext ctx : ctxs) {
            if (dailyBudgetExhausted()) break;
            suggestionsToday.incrementAndGet();
            futures.add(pool.submit(() -> {
                try {
                    return new SuggestPair(ctx, suggester.suggest(ctx));
                } catch (RuntimeException ex) {
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("LLM suggestion threw for '{}': {}",
                                ctx.merchantName, ex.getMessage());
                    }
                    return new SuggestPair(ctx, null);
                }
            }));
        }
        pool.shutdown();

        for (final Future<SuggestPair> f : futures) {
            final SuggestPair pair;
            try {
                pair = f.get();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (java.util.concurrent.ExecutionException ee) {
                continue;
            }
            processed++;
            final LlmCategorySuggester.SuggestionContext ctx = pair.ctx;
            final CategoryResult suggestion = pair.result;
            if (suggestion == null || suggestion.getConfidence() < confidenceThreshold) {
                rejected++;
                continue;
            }
            if (shadowMode) {
                // Shadow mode — log the suggestion, skip the store write.
                // Tagged distinctively so it's easy to grep + diff against
                // any later real run.
                LOGGER.info(
                        "[SHADOW] would-write: merchant='{}' city='{}' state='{}' → category={} confidence={}",
                        ctx.merchantName, ctx.city, ctx.state,
                        suggestion.getCategoryPrimary(), suggestion.getConfidence());
                accepted++;
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
