package com.budgetbuddy.service;

import com.budgetbuddy.service.pdf.profile.StatementParsingUtilities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * Fixture for an American Express Platinum (Morgan Stanley) charge-card statement.
 * Amex is the most layout-heavy issuer the parser handles:
 *
 * <ul>
 *   <li><b>"Closing Date MM/DD/YY"</b> in the header is the statement date. We add a
 *       negative lookbehind on {@code Opening/Closing Date X - Y} (Chase) so Chase's
 *       combined-range form correctly extracts END date instead of START.
 *   <li><b>Stacked-label layout</b>: {@code Pay Over Time Limit} and {@code Available
 *       Pay Over Time Limit} appear on two consecutive lines with both values on the
 *       next two lines. The stacked extractor maps them in document order so
 *       availableCredit doesn't accidentally pick up creditLimit's value.
 *   <li><b>Disclosure-sentence AutoPay</b>: {@code we will debit your bank account for
 *       your payment of $X on MM/DD/YY} — fires only when AutoPay is enrolled.
 *   <li><b>Membership Rewards points</b>: three-line layout (heading, "as of" line,
 *       integer) handled by the bare-label-with-N-line-scan strategy.
 * </ul>
 *
 * <p>All values are synthetic — no real PII or real account numbers.
 */
class AmexPlatinumStatementFixtureTest {

    private static final String STATEMENT_FIXTURE =
            String.join(
                    "\n",
                    "Morgan Stanley Platinum Card®",
                    "TEST CARDHOLDER",
                    "Closing Date 05/13/26",
                    "Account Ending 8-12345",
                    "Customer Care: 1-800-525-3355",
                    "Website: americanexpress.com",
                    "Payment Due Date",
                    "06/07/26",
                    "New Balance",
                    "$978.49",
                    "AutoPay Amount",
                    "$978.49",
                    "We will debit your bank account for your payment of $978.49 on 05/28/26.",
                    "New Balance $978.49",
                    "Minimum Payment Due $40.00",
                    "Payment Due Date 06/07/26",
                    "Membership Rewards® Points",
                    "Available and Pending as of 04/30/26",
                    "           89,096  ",
                    "For up to date point balance and full program",
                    "details, visit membershiprewards.com",
                    "Account Summary",
                    "Account Total",
                    "Previous Balance",
                    "Payments/Credits",
                    "$2,581.31",
                    "-$2,628.73",
                    "+$1,025.91",
                    "+$0.00",
                    "+$0.00",
                    "+$0.00",
                    "New Balance",
                    "Minimum Payment Due",
                    "$978.49",
                    "$40.00",
                    "Pay Over Time Limit",
                    "Available Pay Over Time Limit",
                    "$35,000.00",
                    "$34,021.51",
                    "Pay Over Time Limit: $35,000.00",
                    "p. 1/11");

    @Test
    void amexPlatinumFixture_extractsCoreFields() {
        final String[] lines = STATEMENT_FIXTURE.split("\\n");
        assertEquals(0,
                new BigDecimal("978.49")
                        .compareTo(StatementParsingUtilities.extractNewBalance(lines)));
        // Closing date is the statement date.
        assertEquals(LocalDate.of(2026, 5, 13),
                StatementParsingUtilities.extractStatementDate(lines, 2026, true));
    }

    @Test
    void amexPlatinumFixture_stackedPayOverTimeLabels_mapToDifferentValues() {
        final String[] lines = STATEMENT_FIXTURE.split("\\n");
        // Stacked-label layout: Pay Over Time Limit and Available Pay Over Time Limit
        // appear on consecutive lines with two values following. The dedicated mapper
        // assigns the SECOND value to the SECOND label, not both to the first.
        assertEquals(0,
                new BigDecimal("35000.00")
                        .compareTo(StatementParsingUtilities.extractCreditLimit(lines)),
                "creditLimit = Pay Over Time Limit (first value)");
        assertEquals(0,
                new BigDecimal("34021.51")
                        .compareTo(StatementParsingUtilities.extractAvailableCredit(lines)),
                "availableCredit = Available Pay Over Time Limit (second value) "
                        + "— must NOT inherit creditLimit's value");
    }

    @Test
    void amexPlatinumFixture_autoPay_inferredFromDisclosureSentence_amountExtracted() {
        final String[] lines = STATEMENT_FIXTURE.split("\\n");
        assertTrue(StatementParsingUtilities.extractAutoPayEnabled(lines),
                "AutoPay must be inferred from 'debit your bank account for your payment of $X'");
        assertEquals(0,
                new BigDecimal("978.49")
                        .compareTo(StatementParsingUtilities.extractNextAutoPayAmount(lines)));
    }

    @Test
    void amexPlatinumFixture_extractsMembershipRewardsPoints_threeLine() {
        final String[] lines = STATEMENT_FIXTURE.split("\\n");
        assertEquals(89_096L,
                StatementParsingUtilities.extractPointsBalance(lines).longValue(),
                "Three-line layout: heading, 'Available and Pending as of' disclosure, "
                        + "integer on the third non-blank line");
    }

    @Test
    void chaseOpeningClosingRange_stillExtractsEndDate_notStart() {
        // Regression guard: when Amex's CLOSING_DATE_PATTERN was added, Chase's
        // "Opening/Closing Date X - Y" form needed a negative lookbehind so the
        // Chase end date kept winning over Amex's plain "Closing Date X" match.
        final String[] chaseLines = {
            "Opening/Closing Date 05/13/26 - 06/12/26",
        };
        assertEquals(LocalDate.of(2026, 6, 12),
                StatementParsingUtilities.extractStatementDate(chaseLines, 2026, true),
                "Chase end-of-range must win over the bare 'Closing Date' match");
    }

    // ---- Randomized property-based coverage ----

    @Test
    void amexAutoPay_randomized_acrossAmountVariations() {
        final java.util.Random rng = new java.util.Random(0xA_EE_0FACEL);
        for (int i = 0; i < 200; i++) {
            final int dollars = rng.nextInt(99_999);
            final int cents = rng.nextInt(100);
            final String amount = String.format("%,d.%02d", dollars, cents);
            final String[] lines = {
                "We will debit your bank account for your payment of $" + amount
                        + " on 05/28/26.",
            };
            assertTrue(StatementParsingUtilities.extractAutoPayEnabled(lines),
                    "Iter " + i + ": Amex AutoPay disclosure must fire ON");
            final BigDecimal extracted = StatementParsingUtilities.extractNextAutoPayAmount(lines);
            assertNotNull(extracted, "Iter " + i + ": amount must extract");
            assertEquals(0, new BigDecimal(amount.replace(",", "")).compareTo(extracted),
                    "Iter " + i + ": expected " + amount + " got " + extracted);
        }
    }

    @Test
    void amexStackedPotLabels_randomized_alwaysMapInDocumentOrder() {
        final java.util.Random rng = new java.util.Random(0x5AC_FEEDL);
        for (int i = 0; i < 50; i++) {
            final int limit = 5_000 + rng.nextInt(95_000);
            final int avail = rng.nextInt(limit);
            final String[] lines = {
                "Pay Over Time Limit",
                "Available Pay Over Time Limit",
                "$" + String.format("%,d.00", limit),
                "$" + String.format("%,d.00", avail),
            };
            assertEquals(0, new BigDecimal(limit + ".00")
                    .compareTo(StatementParsingUtilities.extractCreditLimit(lines)),
                    "Iter " + i + ": creditLimit");
            assertEquals(0, new BigDecimal(avail + ".00")
                    .compareTo(StatementParsingUtilities.extractAvailableCredit(lines)),
                    "Iter " + i + ": availableCredit (must be DIFFERENT from creditLimit)");
        }
    }

    @Test
    void amexMembershipRewards_randomized_threeLinePointsBalance() {
        final java.util.Random rng = new java.util.Random(0xA_E_EE_DAB);
        for (int i = 0; i < 50; i++) {
            final long pts = rng.nextInt(2_000_000);
            final String[] lines = {
                "Membership Rewards® Points",
                "Available and Pending as of 04/30/26",
                "           " + String.format("%,d", pts) + "  ",
            };
            assertEquals(pts,
                    StatementParsingUtilities.extractPointsBalance(lines).longValue(),
                    "Iter " + i + ": expected " + pts);
        }
    }
}
