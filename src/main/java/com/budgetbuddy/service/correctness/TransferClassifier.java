package com.budgetbuddy.service.correctness;

import com.budgetbuddy.model.dynamodb.TransactionTable;
import java.util.Locale;
import java.util.Set;

/**
 * Identifies inter-account transfers so they can be excluded from income, expense, and cash-flow
 * aggregates.
 *
 * <p>Background: Plaid's personal-finance taxonomy categorises "I moved money from checking to
 * savings" as {@code TRANSFER_IN}/{@code TRANSFER_OUT}. Our ingest maps those onto an internal
 * taxonomy that doesn't preserve the distinction, but it <em>does</em> preserve the raw Plaid
 * category in {@code importerCategoryPrimary} / {@code importerCategoryDetailed}. This classifier
 * checks the raw category first (authoritative) and falls back to string heuristics only for
 * non-Plaid rows (CSV / manual imports).
 *
 * <p>The classifier errs on the side of <em>not</em> flagging borderline cases — a missed transfer
 * mildly inflates a projection, but a paycheck mis-classified as a transfer would silently drop it
 * from cash-flow and destroy user trust in a more visible way.
 */
public final class TransferClassifier {

    /**
     * Plaid's raw primary categories that definitively indicate a transfer. These come from the
     * `personal_finance_category.primary` field before mapping, stored in {@code
     * importerCategoryPrimary}.
     */
    private static final Set<String> PLAID_TRANSFER_PRIMARIES =
            Set.of("TRANSFER_IN", "TRANSFER_OUT", "LOAN_PAYMENTS");

    /**
     * Plaid's raw detailed categories. {@code LOAN_PAYMENTS_CREDIT_CARD_PAYMENT} is the canonical
     * one for credit-card payments — the classic transfer that double-counts if naively summed.
     */
    private static final Set<String> PLAID_TRANSFER_DETAILS =
            Set.of(
                    "LOAN_PAYMENTS_CREDIT_CARD_PAYMENT",
                    "LOAN_PAYMENTS_CAR_PAYMENT",
                    "LOAN_PAYMENTS_MORTGAGE_PAYMENT",
                    "LOAN_PAYMENTS_PERSONAL_LOAN_PAYMENT",
                    "LOAN_PAYMENTS_STUDENT_LOAN_PAYMENT",
                    "LOAN_PAYMENTS_OTHER_PAYMENT",
                    "TRANSFER_IN_ACCOUNT_TRANSFER",
                    "TRANSFER_IN_CASH_ADVANCES_AND_LOANS",
                    "TRANSFER_IN_DEPOSIT",
                    "TRANSFER_IN_SAVINGS",
                    "TRANSFER_OUT_ACCOUNT_TRANSFER",
                    "TRANSFER_OUT_SAVINGS",
                    "TRANSFER_OUT_WITHDRAWAL");

    /**
     * Substrings indicating a transfer when they appear in a mapped category (fallback for
     * non-Plaid imports without raw category data).
     */
    private static final Set<String> TRANSFER_CATEGORIES =
            Set.of(
                    "transfer",
                    "transfers",
                    "account transfer",
                    "internal transfer",
                    "bank transfer",
                    "credit card payment",
                    "cc payment",
                    "card payment",
                    "loan payment",
                    "investment transfer",
                    "brokerage transfer");

    /** Substrings in merchant / description that indicate a transfer. */
    private static final Set<String> TRANSFER_PHRASES =
            Set.of(
                    "transfer from",
                    "transfer to",
                    "xfer from",
                    "xfer to",
                    "zelle",
                    "venmo cashout",
                    "venmo to bank",
                    "ach transfer",
                    "ach pull",
                    "ach push",
                    "internal transfer",
                    "payment to credit card",
                    "credit card payment",
                    "auto pay credit",
                    "wire transfer");

    private TransferClassifier() {}

    /** Returns {@code true} if this transaction is most likely a self-transfer. */
    public static boolean isTransfer(final TransactionTable t) {
        if (t == null) {
            return false;
        }
        // Authoritative path: raw Plaid category preserved on ingest.
        if (exactMatch(t.getImporterCategoryPrimary(), PLAID_TRANSFER_PRIMARIES)) {
            return true;
        }
        if (exactMatch(t.getImporterCategoryDetailed(), PLAID_TRANSFER_DETAILS)) {
            return true;
        }
        // Heuristic fallback for rows without raw Plaid category (CSV, PDF,
        // manual entry, legacy rows pre-importerCategory storage).
        if (matches(t.getCategoryPrimary(), TRANSFER_CATEGORIES)) {
            return true;
        }
        if (matches(t.getCategoryDetailed(), TRANSFER_CATEGORIES)) {
            return true;
        }
        if (matches(t.getMerchantName(), TRANSFER_PHRASES)) {
            return true;
        }
        if (matches(t.getDescription(), TRANSFER_PHRASES)) {
            return true;
        }
        return false;
    }

    private static boolean exactMatch(final String value, final Set<String> set) {
        return value != null && set.contains(value);
    }

    private static boolean matches(final String value, final Set<String> needles) {
        if (value == null || value.isBlank()) {
            return false;
        }
        final String lower = value.toLowerCase(Locale.ROOT);
        for (final String n : needles) {
            if (lower.contains(n)) {
                return true;
            }
        }
        return false;
    }
}
