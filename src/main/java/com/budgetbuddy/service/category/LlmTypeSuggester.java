package com.budgetbuddy.service.category;

/**
 * Companion to {@link LlmCategorySuggester} that resolves the
 * {@link com.budgetbuddy.model.TransactionType} for ambiguous transactions
 * (PAYMENT vs EXPENSE vs INVESTMENT vs INCOME) where the deterministic
 * substring-keyword chain in {@code TransactionTypeCategoryService} can't
 * confidently decide.
 *
 * <h3>Why a separate interface from category suggestion</h3>
 *
 * Type classification is a much smaller decision (4 options vs 18), so the
 * prompt is shorter and cheaper, and the confidence floor for accepting
 * the LLM's answer can be higher. Keeping the interfaces separate also
 * lets ops dial type and category suggestion independently (e.g. enable
 * type suggestion only, while keeping category suggestion shadow-only).
 *
 * <h3>When to call</h3>
 *
 * NOT on every transaction. The deterministic path resolves ~99% of cases
 * correctly. Call this only when:
 *   1. account-type-based inference returned confidence &lt; 0.7, AND
 *   2. the description contains conflicting signals
 *     (e.g. "PAYMENT THANK YOU - INVESTMENT ACCT XFER")
 *
 * <h3>Implementation status</h3>
 *
 * Interface ships; production-grade Anthropic impl follows the same
 * pattern as {@link AnthropicLlmCategorySuggester} but with the type
 * whitelist + shorter prompt. Wire-up into TransactionTypeCategoryService
 * is intentionally deferred — turning it on without a soak test would
 * shift type-derivation behavior across all imports.
 */
public interface LlmTypeSuggester {

    enum SuggestedType {
        PAYMENT,
        EXPENSE,
        INVESTMENT,
        INCOME
    }

    final class TypeSuggestion {
        public final SuggestedType type;
        public final double confidence;
        public final String reasoning;

        public TypeSuggestion(
                final SuggestedType type, final double confidence, final String reasoning) {
            this.type = type;
            this.confidence = confidence;
            this.reasoning = reasoning;
        }
    }

    final class TypeContext {
        public final String merchantName;
        public final String description;
        public final String accountType;
        public final String accountSubtype;
        public final java.math.BigDecimal amount;
        public final String paymentChannel;

        public TypeContext(
                final String merchantName,
                final String description,
                final String accountType,
                final String accountSubtype,
                final java.math.BigDecimal amount,
                final String paymentChannel) {
            this.merchantName = merchantName;
            this.description = description;
            this.accountType = accountType;
            this.accountSubtype = accountSubtype;
            this.amount = amount;
            this.paymentChannel = paymentChannel;
        }
    }

    /**
     * Ask the LLM to resolve the type. Returns null on any failure or
     * when the LLM doesn't return a recognised enum value.
     */
    TypeSuggestion suggest(TypeContext context);
}
