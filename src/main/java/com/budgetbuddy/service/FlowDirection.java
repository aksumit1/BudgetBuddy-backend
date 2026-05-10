package com.budgetbuddy.service;

import java.math.BigDecimal;

/**
 * Money-flow direction for a single transaction row.
 *
 * <p>Independent of the amount sign so downstream consumers don't have to re-derive direction from
 * the sign every time they render a list, compute a budget, or run categorization. Historically
 * each consumer had its own inference ("negative = outflow for checking, positive = outflow for
 * credit cards") and they drifted out of sync.
 *
 * <h3>Storage convention</h3>
 *
 * Amount sign in our storage is always:
 *
 * <ul>
 *   <li>negative for money <b>out</b> of the user
 *   <li>positive for money <b>in</b> to the user
 * </ul>
 *
 * That convention is applied at parse time (importers invert when needed — e.g. a credit-card
 * statement that renders purchases as positive gets flipped before storage). {@link FlowDirection}
 * is the pre-applied label of what the parser decided.
 */
// PMD's OnlyOneReturn fights guard-clause idiom — the codebase intentionally
// uses early returns for clarity (validation guards, fail-fast patterns).
@SuppressWarnings("PMD.OnlyOneReturn")
public enum FlowDirection {
    /** Money out of the user. Purchases, fees, ATM withdrawals, utility bills. */
    DEBIT,
    /**
     * Money in to the user. Refunds, payroll, interest, received transfers, credit-card payments
     * (received by the bank, reducing the CC balance).
     */
    CREDIT;

    /**
     * Infer direction from a signed amount after sign-convention has been applied. Zero is treated
     * as DEBIT (rare; happens on statement correction lines).
     */
    public static FlowDirection fromSignedAmount(final BigDecimal signedAmount) {
        if (signedAmount == null) {
            return DEBIT;
        }
        return signedAmount.signum() >= 0 ? CREDIT : DEBIT;
    }
}
