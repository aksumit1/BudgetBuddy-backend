package com.budgetbuddy.service;

import com.budgetbuddy.service.pdf.profile.StatementParsingUtilities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * End-to-end fixture coverage for a U.S. Bank credit card statement (Reflect /
 * Smartly Visa Signature variants). Distinct from Wells / Chase / Citi because USB:
 *
 * <ul>
 *   <li><b>Decorates section-total rows with sign indicators</b> like
 *       {@code Previous Balance + $X} (positive, decorative) and {@code Payments - $X}
 *       (negative, semantic). We absorb both via dedicated label patterns.
 *   <li><b>Uses different label wording</b> for credit-related fields:
 *       {@code Revolving Line of Credit} (=credit limit),
 *       {@code Revolving Line Available} (=available credit),
 *       {@code Reward Center Balance} (=points balance),
 *       {@code Earned This Statement} (=points earned this period).
 *   <li><b>Prints billing days with the count AFTER the label</b>
 *       ({@code Days in Billing Period 33}) instead of {@code N Days in Billing Period}.
 *   <li><b>AutoPay disclosure</b> uses two phrasings:
 *       {@code An automatic payment of $X will be deducted from your account on MM/DD/YY}
 *       and {@code Your payment of $X will be automatically deducted ...}.
 *   <li><b>APR rows are end-of-line</b>: {@code **PURCHASES $X $X $X 18.49% MM/YYYY} —
 *       the APR appears after multiple balance columns, not directly after the label.
 *   <li><b>Statement period header</b>: {@code Open Date: MM/DD/YYYY Closing Date: MM/DD/YYYY}.
 * </ul>
 *
 * <p>All values are synthetic — no real PII or real account numbers.
 */
class USBankReflectStatementFixtureTest {

    private static final String STATEMENT_FIXTURE =
            String.join(
                    "\n",
                    "Open Date: 12/06/2025 Closing Date: 01/07/2026 Account Ending in: #### #### #### 1234",
                    "Page 1 of 3",
                    "An automatic payment of $213.00 will be",
                    "deducted from your account on 02/02/26. If",
                    "you choose to make additional payments",
                    "please write your account number on your",
                    "check and mail to:",
                    "U.S. Bank",
                    "P.O. Box 790408",
                    "St. Louis, MO  63179-0408",
                    "1-800-285-8585",
                    "TEST CARDHOLDER",
                    "Previous Balance + $1,420.32",
                    "Payments - $215.00",
                    "Other Credits $0.00",
                    "Purchases $0.00",
                    "Balance Transfers $0.00",
                    "Advances $0.00",
                    "Other Debits $0.00",
                    "Fees Charged $0.00",
                    "Interest Charged $0.00",
                    "Revolving Line of Credit $22,100.00",
                    "Revolving Line Available $894.68",
                    "Days in Billing Period 33",
                    "Earned This Statement 25",
                    "Reward Center Balance 12,345",
                    "as of 01/06/2026",
                    "New Balance $1,205.32",
                    "Minimum Payment Due $213.00",
                    "Payment Due Date 02/03/2026",
                    "Past Due $0.00",
                    "TOTAL THIS PERIOD $215.00",
                    "01/02 01/02 MTC PAYMENT   THANK YOU $215.00",
                    "Total Fees Charged in 2026 $0.00",
                    "Total Interest Charged in 2026 $0.00",
                    "**BALANCE TRANSFER $0.00 $0.00 $0.00 0.00%",
                    "**PURCHASES $1,205.32 $1,381.22 $0.00 0.00% 04/2026",
                    "**ADVANCES $0.00 $0.00 YES $0.00 30.49%");

    @Test
    void usbFixture_extractsCoreSummaryFields() {
        final String[] lines = STATEMENT_FIXTURE.split("\\n");
        assertEquals(0,
                new BigDecimal("1205.32").compareTo(StatementParsingUtilities.extractNewBalance(lines)));
        // "Previous Balance + $X" — the leading + is decoration; value is positive.
        assertEquals(0,
                new BigDecimal("1420.32")
                        .compareTo(StatementParsingUtilities.extractPreviousBalance(lines)));
        // "Revolving Line of Credit" / "Revolving Line Available" are USB's labels.
        assertEquals(0,
                new BigDecimal("22100.00")
                        .compareTo(StatementParsingUtilities.extractCreditLimit(lines)));
        assertEquals(0,
                new BigDecimal("894.68")
                        .compareTo(StatementParsingUtilities.extractAvailableCredit(lines)));
        assertEquals(0,
                BigDecimal.ZERO.compareTo(StatementParsingUtilities.extractPastDueAmount(lines)));
    }

    @Test
    void usbFixture_extractsSectionTotals() {
        final String[] lines = STATEMENT_FIXTURE.split("\\n");
        assertEquals(0,
                BigDecimal.ZERO.compareTo(StatementParsingUtilities.extractPurchasesTotal(lines)));
        // "TOTAL THIS PERIOD $215.00" — USB transaction-detail section total.
        assertEquals(0,
                new BigDecimal("215.00")
                        .compareTo(StatementParsingUtilities.extractPaymentsAndCreditsTotal(lines)));
        assertEquals(0,
                BigDecimal.ZERO.compareTo(StatementParsingUtilities.extractFeesChargedTotal(lines)));
        assertEquals(0,
                BigDecimal.ZERO.compareTo(StatementParsingUtilities.extractInterestChargedTotal(lines)));
    }

    @Test
    void usbFixture_extractsAprsAndBillingInfo() {
        final String[] lines = STATEMENT_FIXTURE.split("\\n");
        // End-of-row APR after `**LABEL $X $X $X N.NN%` columns.
        assertEquals(0,
                BigDecimal.ZERO.compareTo(StatementParsingUtilities.extractPurchaseApr(lines)));
        assertEquals(0,
                new BigDecimal("30.49")
                        .compareTo(StatementParsingUtilities.extractCashAdvanceApr(lines)));
        assertEquals(0,
                BigDecimal.ZERO.compareTo(StatementParsingUtilities.extractBalanceTransferApr(lines)));
        assertNull(StatementParsingUtilities.extractPenaltyApr(lines));
        // "Days in Billing Period 33" — number trailing the label.
        assertEquals(33, StatementParsingUtilities.extractBillingDays(lines).intValue());
        // Closing Date from the "Open Date / Closing Date" header.
        assertEquals(LocalDate.of(2026, 1, 7),
                StatementParsingUtilities.extractStatementDate(lines, 2026, true));
    }

    @Test
    void usbFixture_extractsAutoPay() {
        final String[] lines = STATEMENT_FIXTURE.split("\\n");
        // "An automatic payment of $X will be deducted from your account on MM/DD/YY"
        // is the on-marker and amount source.
        assertTrue(StatementParsingUtilities.extractAutoPayEnabled(lines));
        assertEquals(0,
                new BigDecimal("213.00")
                        .compareTo(StatementParsingUtilities.extractNextAutoPayAmount(lines)));
    }

    @Test
    void usbFixture_extractsRewardCenterBalanceAndEarnedThisStatement() {
        final String[] lines = STATEMENT_FIXTURE.split("\\n");
        assertEquals(12_345L, StatementParsingUtilities.extractPointsBalance(lines).longValue());
        assertEquals(25L,
                StatementParsingUtilities.extractPointsEarnedThisPeriod(lines).longValue());
    }

    @Test
    void usbFixture_extractsYtdFeesAndInterest() {
        final String[] lines = STATEMENT_FIXTURE.split("\\n");
        assertEquals(0,
                BigDecimal.ZERO.compareTo(StatementParsingUtilities.extractYtdFeesCharged(lines)));
        assertEquals(0,
                BigDecimal.ZERO.compareTo(StatementParsingUtilities.extractYtdInterestCharged(lines)));
    }

    // ---- Randomized property-based coverage ----

    @Test
    void usbAutoPay_randomized_capturesEveryAmountShape() {
        final java.util.Random rng = new java.util.Random(0x05B_A07L);
        for (int i = 0; i < 200; i++) {
            final int dollars = rng.nextInt(99_999);
            final int cents = rng.nextInt(100);
            final String amount = String.format("%,d.%02d", dollars, cents);
            final String[] lines = {
                "An automatic payment of $" + amount + " will be",
                "deducted from your account on 02/02/26."
            };
            assertTrue(StatementParsingUtilities.extractAutoPayEnabled(lines),
                    "Iter " + i + ": ON must fire for USB phrasing");
            final BigDecimal extracted = StatementParsingUtilities.extractNextAutoPayAmount(lines);
            final BigDecimal expected = new BigDecimal(amount.replace(",", ""));
            assertNotNull(extracted, "Iter " + i + ": amount must extract");
            assertEquals(0, expected.compareTo(extracted),
                    "Iter " + i + ": expected " + expected + " got " + extracted);
        }
    }

    @Test
    void usbCreditLimitAndAvailable_randomized_alwaysExtracted() {
        final java.util.Random rng = new java.util.Random(0xC2E5_DECAEL);
        for (int i = 0; i < 100; i++) {
            final int limit = 1_000 + rng.nextInt(99_000);
            final int avail = rng.nextInt(limit);
            final String[] lines = {
                "Revolving Line of Credit $" + String.format("%,d.00", limit),
                "Revolving Line Available $" + String.format("%,d.00", avail),
            };
            assertEquals(0, new BigDecimal(limit + ".00")
                    .compareTo(StatementParsingUtilities.extractCreditLimit(lines)),
                    "Iter " + i + ": credit limit");
            assertEquals(0, new BigDecimal(avail + ".00")
                    .compareTo(StatementParsingUtilities.extractAvailableCredit(lines)),
                    "Iter " + i + ": available credit");
        }
    }

    @Test
    void usbBillingDays_randomized_acceptsTrailingNumberForm() {
        final java.util.Random rng = new java.util.Random(0x0DA50CCL);
        for (int i = 0; i < 60; i++) {
            final int days = 25 + rng.nextInt(15);
            final String[] lines = {"Days in Billing Period " + days};
            assertEquals(days, StatementParsingUtilities.extractBillingDays(lines).intValue());
        }
    }

    @Test
    void usbApr_randomized_extractsTrailingPercentFromAsteriskedRow() {
        final java.util.Random rng = new java.util.Random(0xA95L);
        for (int i = 0; i < 100; i++) {
            final int whole = rng.nextInt(30);
            final int decimal = rng.nextInt(100);
            final String aprStr = String.format("%d.%02d", whole, decimal);
            final String[] lines = {
                "**PURCHASES $1,205.32 $1,381.22 $0.00 " + aprStr + "% 04/2026"
            };
            assertEquals(0,
                    new BigDecimal(aprStr)
                            .compareTo(StatementParsingUtilities.extractPurchaseApr(lines)),
                    "Iter " + i + ": expected " + aprStr + "% got "
                            + StatementParsingUtilities.extractPurchaseApr(lines));
        }
    }
}
