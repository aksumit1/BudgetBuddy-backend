package com.budgetbuddy.service.category;


import java.util.Locale;
import java.util.Set;

/**
 * Single source of truth for the "does this transaction read like a payment?" heuristic. Previously
 * three services each had their own copy of the phrase list:
 *
 * <ul>
 *   <li>{@code TransactionTypeCategoryService.looksLikePayment}
 *   <li>{@code MerchantCategoryDataService.containsPaymentActionPhrase}
 *   <li>{@code SemanticMatchingService.hasPaymentPhrase}
 * </ul>
 *
 * When the lists drifted (and they did — one had "credit card bill" that the others didn't)
 * categorisation forked depending on which code path ran. Adding a new phrase meant hunting three
 * files.
 *
 * <p>Keep this tight. The phrase list is the whitelist of "yes this is really a payment" signals —
 * overbroad entries (like a bare "payment" substring) are appropriate because they're usually
 * preceded by an issuer/account name in real descriptions.
 */
// PMD's OnlyOneReturn fights guard-clause idiom — the codebase intentionally
// uses early returns for clarity (validation guards, fail-fast patterns).
@SuppressWarnings("PMD.OnlyOneReturn")
public final class PaymentPhrases {

    /**
     * Substrings in a merchant or description that reliably mean the transaction is a payment of
     * some bill or debt.
     */
    public static final Set<String> PAYMENT_ACTION =
            Set.of(
                    "payment",
                    "autopay",
                    "auto pay",
                    "auto-pay",
                    "bill pay",
                    "bill payment",
                    "monthly payment",
                    "direct debit",
                    "credit card bill",
                    "pmt");

    private PaymentPhrases() {}

    /**
     * True when the given text (merchant name, description, or either concatenated) contains any
     * payment-action phrase. Case-insensitive. Null- and empty-safe.
     */
    public static boolean isPaymentish(final String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        final String lower = text.toLowerCase(Locale.ROOT);
        for (final String phrase : PAYMENT_ACTION) {
            if (lower.contains(phrase)) {
                return true;
            }
        }
        return false;
    }
}
