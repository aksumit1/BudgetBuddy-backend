package com.budgetbuddy.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * Regression coverage for {@link RewardExtractor}'s multi-line scan false
 * positives.
 *
 * <p>Before the fix, {@code MULTI_LINE_REWARD_LINE1} accepted any line
 * containing the bare substring "total" or "available" — which fires on
 * every "Total Payments and Credits" line, and the value below is a dollar
 * amount, not a points balance. On the Jan-Feb Amex Blue Business Cash
 * statement this surfaced as {@code rewardPoints: 1396} (matching the
 * payment amount {@code -$1,396.00}) on a cash-back card with no Membership
 * Rewards points.
 *
 * <p>The fix tightens the heading regex to require an actual points-domain
 * keyword (points/pts/rewards/miles, or available/total qualified by one
 * of those), and adds {@code HAS_CURRENCY_SYMBOL} to the per-line value
 * guard so a {@code $} on the candidate number line auto-rejects it.
 */
class RewardExtractorFalsePositiveTest {

    private final RewardExtractor rex = new RewardExtractor();

    @Test
    void doesNotMatchTotalPaymentsLine_asRewardPoints() {
        // The bug input: the "Total" label fires a multi-line scan, and the
        // dollar amount on the next line gets read as points.
        final String[] lines = {
            "Payments and Credits",
            "Summary",
            "Total",
            "Payments -$1,396.00",
            "Credits ",
            "Total Payments and Credits -$1,424.36",
        };
        assertNull(rex.extractRewardPoints(lines),
                "A line containing 'total' or 'available' must NOT trigger the "
                        + "multi-line points scan unless paired with a points-domain keyword");
    }

    @Test
    void doesNotMatchAvailableCreditLine_asRewardPoints() {
        // "Total Available Credit" on Wells Fargo statements was another
        // false-positive source pre-fix.
        final String[] lines = {
            "Total Credit Limit $30,000",
            "Total Available Credit",
            "$16,200",
        };
        assertNull(rex.extractRewardPoints(lines),
                "'Total Available Credit' must not be interpreted as a points line");
    }

    @Test
    void rejectsLinesContainingDollarSymbol_asPointsValue() {
        // Even with a real "points" or "rewards" label, the value line must
        // not contain a currency symbol — dollars aren't points.
        final String[] lines = {
            "Total rewards balance",
            "$42.50",
        };
        assertNull(rex.extractRewardPoints(lines),
                "Value lines with $ symbol must be rejected as not-a-points-value");
    }

    @Test
    void stillExtractsRealPointsBalance() {
        // Sanity check: an actual points line with a clean integer value
        // still extracts correctly after the tightening.
        final String[] lines = {
            "Membership Rewards® Points",
            "Available and Pending as of 04/30/26",
            "           89,096  ",
        };
        assertEquals(89_096L, rex.extractRewardPoints(lines).longValue());
    }

    @Test
    void doesNotMatchPaymentDollarValue_evenWithTotalPrefix() {
        // Real bug case from the Amex Blue Business Cash statement.
        final String[] lines = {
            "Total Payments and Credits -$1,424.36",
            "Detail",
            "Payments Amount",
            "02/03/26* AUTOPAY PAYMENT RECEIVED",
            "-$1,396.00",
        };
        assertNull(rex.extractRewardPoints(lines),
                "Amex Blue Business Cash has no points — must return null instead "
                        + "of 1396 picked up from the AutoPay amount");
    }
}
