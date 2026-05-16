package com.budgetbuddy.service.category;

import com.budgetbuddy.service.TransactionTypeCategoryService.CategoryResult;

/**
 * Last-resort categoriser for transactions the deterministic pipeline
 * (L0-L8) couldn't categorise. Designed as an interface so production
 * can plug in any LLM provider (Anthropic, OpenAI, Bedrock, self-hosted)
 * without changing the rest of the cascade.
 *
 * <h3>Why an interface, not an implementation</h3>
 *
 * Calling an LLM on the import critical path is generally a bad idea:
 * latency + cost + rate limits. The production architecture should:
 *
 * <ol>
 *   <li>NOT call the LLM during import.
 *   <li>Drop uncategorised rows to {@link UncategorisedReviewQueue}.
 *   <li>A background {@link SelfLearningWorker} drains the queue in
 *       batches, calls the LLM once per batch, and writes the resulting
 *       category suggestions to {@link MerchantEnrichmentStore}.
 *   <li>The NEXT import sees a cache hit. The system gets smarter
 *       without slowing imports down.
 * </ol>
 *
 * <h3>Suggested prompt shape</h3>
 *
 * <pre>
 *   System: "You categorise credit-card transactions into one of these
 *            categories: dining, groceries, travel, transportation,
 *            entertainment, shopping, payment, fees, tech, insurance,
 *            pet, utilities, health, charity, home improvement,
 *            subscriptions, education, other.
 *            Return JSON: {category, confidence (0.0-1.0), reasoning}."
 *   User:    "Merchant: 'AplPay XI'AN NOODLES SEATTLE WA'
 *             Amount: -23.45
 *             Statement issuer: Citi"
 * </pre>
 *
 * <h3>Confidence threshold</h3>
 *
 * Only suggestions with {@code confidence >= 0.75} should auto-write to
 * the enrichment store. Below that, flag for human review. This avoids
 * the LLM hallucinating its way into the cache.
 *
 * <h3>No-op default</h3>
 *
 * The {@link NoOp} default returns null for everything. The cascade
 * compiles and runs without an LLM provider configured. To enable, plug
 * in a {@code @Primary} implementation that calls your provider.
 */
public interface LlmCategorySuggester {

    /**
     * Ask the LLM to categorise this transaction context. Return null if
     * the LLM declines or returns a low-confidence guess.
     */
    CategoryResult suggest(SuggestionContext context);

    /**
     * Bundle of everything an LLM needs to make a good suggestion. The
     * caller fills what it knows; the implementation extracts what's
     * useful.
     */
    final class SuggestionContext {
        public final String merchantName;
        public final String description;
        public final String city;
        public final String state;
        public final String country;
        public final java.math.BigDecimal amount;
        public final String issuerName;
        public final String accountType;

        public SuggestionContext(
                final String merchantName, final String description,
                final String city, final String state, final String country,
                final java.math.BigDecimal amount,
                final String issuerName, final String accountType) {
            this.merchantName = merchantName;
            this.description = description;
            this.city = city;
            this.state = state;
            this.country = country;
            this.amount = amount;
            this.issuerName = issuerName;
            this.accountType = accountType;
        }
    }

    /** Default no-op — enable an implementation in production to activate. */
    @org.springframework.stereotype.Service
    final class NoOp implements LlmCategorySuggester {
        @Override
        public CategoryResult suggest(final SuggestionContext context) {
            return null;
        }
    }
}
