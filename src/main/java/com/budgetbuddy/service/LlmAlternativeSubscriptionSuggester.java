package com.budgetbuddy.service;

import com.budgetbuddy.model.Subscription;
import java.math.BigDecimal;
import java.util.List;

/**
 * LLM-backed alternative-subscription suggester.
 *
 * <p>{@link SubscriptionAdvancedService#suggestAlternatives} today returns
 * results from a hardcoded mapping (Netflix → Hulu, Apple Music → Spotify,
 * etc.). That works for the top 20 mainstream services but stops cold the
 * instant a user has a long-tail subscription (Substack, Patreon,
 * niche-software-X).
 *
 * <p>An LLM prompt of the form:
 *
 * <pre>
 *   "User pays $22.04/mo for OpenAI ChatGPT Plus. Suggest 2 cheaper or
 *    equivalent-feature alternatives in JSON: name, monthly_price,
 *    one_sentence_pitch."
 * </pre>
 *
 * generalizes naturally to any merchant. The interface keeps the prompt +
 * provider out of the call-site so we can swap models without touching
 * SubscriptionAdvancedService.
 *
 * <h3>Status</h3>
 *
 * Interface ships; production impl follows the same shape as
 * {@code AnthropicLlmCategorySuggester}. Wire-up into
 * SubscriptionAdvancedService is gated on a feature flag so the existing
 * hardcoded results stay the default until the LLM impl is validated.
 */
public interface LlmAlternativeSubscriptionSuggester {

    final class Alternative {
        public final String name;
        public final BigDecimal monthlyPrice;
        public final String pitch;

        public Alternative(final String name, final BigDecimal monthlyPrice, final String pitch) {
            this.name = name;
            this.monthlyPrice = monthlyPrice;
            this.pitch = pitch;
        }
    }

    /**
     * Returns up to {@code maxResults} alternatives for the given
     * subscription. Empty list on any LLM failure — callers should
     * gracefully degrade to the hardcoded mapping.
     */
    List<Alternative> suggest(Subscription subscription, int maxResults);
}
