package com.budgetbuddy.service.pdf.v2;

import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sanity bounds for extracted metadata fields. When a value comes back
 * outside its plausible range, log WARN so the developer notices a regex
 * over-matching (e.g. capturing a transaction amount as the credit limit, or
 * grabbing a payment-due date as an APR rate).
 *
 * <p>Bounds are deliberately wide — true outliers exist (a $1M credit line, a
 * 29.99% penalty APR) and we don't want false-alarm noise on the legitimate
 * tail. The goal is to catch order-of-magnitude misses, not nudge tight
 * numerics. Out-of-range values stay set on the result; the WARN is the only
 * effect.
 *
 * <p>Per-field bounds live as constants here, not in YAML, because they're
 * universal across issuers — a credit limit of $50 makes no sense regardless
 * of who issued the card.
 */
public final class FieldBounds {

    private static final Logger LOGGER = LoggerFactory.getLogger(FieldBounds.class);

    // BALANCE / LIMIT bounds — generous to accommodate high-net-worth limits.
    private static final BigDecimal MIN_LIMIT = new BigDecimal("100");
    private static final BigDecimal MAX_LIMIT = new BigDecimal("10000000");
    // BALANCE ranges can go negative (credit-balance after refund), so the
    // lower bound is a sanity check, not a hard floor. Allow up to $1M owed.
    private static final BigDecimal MIN_BALANCE = new BigDecimal("-100000");
    private static final BigDecimal MAX_BALANCE = new BigDecimal("1000000");
    // APR percentage — penalty APRs go up to 30-ish; we allow up to 50% as a
    // very generous ceiling. Anything 60%+ is almost certainly a parser error.
    private static final BigDecimal MIN_APR = BigDecimal.ZERO;
    private static final BigDecimal MAX_APR = new BigDecimal("50");
    private static final long MIN_POINTS = 0L;
    private static final long MAX_POINTS = 10_000_000L;
    private static final int MIN_BILLING_DAYS = 20;
    private static final int MAX_BILLING_DAYS = 45;

    private FieldBounds() { }

    public static void checkAll(final PdfTemplateV2Evaluator.MetadataResult r) {
        if (r == null) return;
        checkAmount("new_balance", r.newBalance, MIN_BALANCE, MAX_BALANCE);
        checkAmount("previous_balance", r.previousBalance, MIN_BALANCE, MAX_BALANCE);
        checkAmount("credit_limit", r.creditLimit, MIN_LIMIT, MAX_LIMIT);
        checkAmount("available_credit", r.availableCredit, BigDecimal.ZERO, MAX_LIMIT);
        checkAmount("purchase_apr", r.purchaseApr, MIN_APR, MAX_APR);
        checkAmount("cash_advance_apr", r.cashAdvanceApr, MIN_APR, MAX_APR);
        checkAmount("balance_transfer_apr", r.balanceTransferApr, MIN_APR, MAX_APR);
        checkAmount("penalty_apr", r.penaltyApr, MIN_APR, MAX_APR);
        checkAmount("foreign_tx_fee_percent", r.foreignTxFeePercent,
                BigDecimal.ZERO, new BigDecimal("10"));
        checkAmount("annual_fee", r.annualFee, BigDecimal.ZERO, new BigDecimal("10000"));
        checkLong("points_balance", r.pointsBalance, MIN_POINTS, MAX_POINTS);
        checkLong("points_earned", r.pointsEarned, MIN_POINTS, MAX_POINTS);
        checkLong("previous_points_balance", r.previousPointsBalance, MIN_POINTS, MAX_POINTS);
        checkInt("billing_days", r.billingDays, MIN_BILLING_DAYS, MAX_BILLING_DAYS);
    }

    private static void checkAmount(final String field, final BigDecimal v,
            final BigDecimal lo, final BigDecimal hi) {
        if (v == null) return;
        if (v.compareTo(lo) < 0 || v.compareTo(hi) > 0) {
            LOGGER.warn("v2 field '{}' out of bounds: value={} expected in [{}, {}]",
                    field, v, lo, hi);
        }
    }

    private static void checkLong(final String field, final Long v, final long lo, final long hi) {
        if (v == null) return;
        if (v < lo || v > hi) {
            LOGGER.warn("v2 field '{}' out of bounds: value={} expected in [{}, {}]",
                    field, v, lo, hi);
        }
    }

    private static void checkInt(final String field, final Integer v, final int lo, final int hi) {
        if (v == null) return;
        if (v < lo || v > hi) {
            LOGGER.warn("v2 field '{}' out of bounds: value={} expected in [{}, {}]",
                    field, v, lo, hi);
        }
    }
}
