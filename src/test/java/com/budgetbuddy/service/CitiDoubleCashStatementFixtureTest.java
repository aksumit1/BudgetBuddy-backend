package com.budgetbuddy.service;

import com.budgetbuddy.service.pdf.profile.StatementParsingUtilities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * End-to-end fixture for a Citi Double Cash® Card statement. Distinct from Chase/Wells/
 * USB because Citi:
 *
 * <ul>
 *   <li><b>Billing Period</b> instead of "Statement Period": {@code Billing Period:
 *       MM/DD/YY-MM/DD/YY} (no spaces around the range dash, two-digit year).
 *   <li><b>Bare "Payments" label with sign-attached amount</b>: {@code Payments -$X}
 *       — no "Credits" trailer like Chase has.
 *   <li><b>"Standard Purch" / "Standard Adv" sub-labels</b> for APR rows under the
 *       PURCHASES / ADVANCES section headers.
 *   <li><b>ThankYou Points balance as a multi-line block</b>: bare label on one line,
 *       value on the next ({@code Total ThankYou Points Balance:} \n {@code 25,519}).
 *   <li><b>"Your next AutoPay payment of $X will be deducted ..."</b> sentence — the
 *       "next autopay payment" anchor IS the on-marker (Citi doesn't print this row
 *       unless AutoPay is enrolled).
 * </ul>
 *
 * <p>All values are synthetic — no real PII or real account numbers.
 */
class CitiDoubleCashStatementFixtureTest {

    private static final String STATEMENT_FIXTURE =
            String.join(
                    "\n",
                    "TEST CARDHOLDER",
                    "Citi Double Cash® Card",
                    "Member Since 2015. Account number ending in: 1111",
                    "Billing Inquiries and Customer Service",
                    "BOX 6500 SIOUX FALLS, SD 57117",
                    "www.citicards.com",
                    "APRIL STATEMENT",
                    "Billing Period: 03/04/26-04/02/26",
                    "New balance as of 04/02/26:  $1,038.02",
                    "Minimum payment due:  $41.00",
                    "Payment due date:  04/28/26",
                    "Late Payment Warning: If we do not receive your Minimum Payment",
                    " by the date listed above, you may have to pay a late fee of up to $41",
                    " and your APRs may be increased up to the Penalty APR of 29.99%.",
                    "Total Available  ThankYou®  Points:",
                    "21,371  as of 03/31/26",
                    "Your next AutoPay payment of $1,038.02 will be",
                    " deducted from your bank account on 04/28/2026.",
                    "Account Summary",
                    "Previous balance  $3,110.20",
                    "Payments -$3,110.20",
                    "Credits -$0.00",
                    "Purchases  +$1,038.02",
                    "Cash advances  +$0.00",
                    "Fees  +$0.00",
                    "Interest  +$0.00",
                    "New balance  $1,038.02",
                    "Credit Limit",
                    "Credit limit  $18,500",
                    "Includes $4,100 cash advance limit",
                    "Available credit  $17,461",
                    "Includes $4,100  available for cash advances",
                    "Total fees charged in this billing period  $0.00",
                    "Total interest charged in this billing period  $0.00",
                    "2026 totals year-to-date",
                    "Total fees charged in 2026  $0.00",
                    "Total interest charged in 2026  $0.00",
                    "Interest charge calculation Days in billing cycle: 30",
                    " PURCHASES",
                    " Standard Purch 19.49% (V) $0.00 (D) $0.00",
                    " ADVANCES",
                    " Standard Adv 29.74% (V) $0.00 (D) $0.00",
                    "Total ThankYou Points Balance:",
                    "21,371",
                    "ThankYou Points Earned:  1,038");

    @Test
    void citiDoubleCashFixture_extractsCoreSummary() {
        final String[] lines = STATEMENT_FIXTURE.split("\\n");
        assertEquals(0,
                new BigDecimal("1038.02").compareTo(StatementParsingUtilities.extractNewBalance(lines)));
        assertEquals(0,
                new BigDecimal("3110.20")
                        .compareTo(StatementParsingUtilities.extractPreviousBalance(lines)));
        assertEquals(0,
                new BigDecimal("18500").compareTo(StatementParsingUtilities.extractCreditLimit(lines)));
        assertEquals(0,
                new BigDecimal("17461")
                        .compareTo(StatementParsingUtilities.extractAvailableCredit(lines)));
    }

    @Test
    void citiDoubleCashFixture_extractsSectionTotalsWithSignedAmounts() {
        final String[] lines = STATEMENT_FIXTURE.split("\\n");
        // Purchases " +$1,038.02" — leading + is captured as positive.
        assertEquals(0,
                new BigDecimal("1038.02")
                        .compareTo(StatementParsingUtilities.extractPurchasesTotal(lines)));
        // Payments "-$3,110.20" — sign-attached minus is preserved.
        assertEquals(0,
                new BigDecimal("-3110.20")
                        .compareTo(StatementParsingUtilities.extractPaymentsAndCreditsTotal(lines)));
    }

    @Test
    void citiDoubleCashFixture_extractsAprAndBillingInfo() {
        final String[] lines = STATEMENT_FIXTURE.split("\\n");
        assertEquals(0,
                new BigDecimal("19.49")
                        .compareTo(StatementParsingUtilities.extractPurchaseApr(lines)));
        assertEquals(0,
                new BigDecimal("29.74")
                        .compareTo(StatementParsingUtilities.extractCashAdvanceApr(lines)));
        assertEquals(0,
                new BigDecimal("29.99")
                        .compareTo(StatementParsingUtilities.extractPenaltyApr(lines)));
        assertEquals(30, StatementParsingUtilities.extractBillingDays(lines).intValue());
        // Billing Period end date is the statement date.
        assertEquals(LocalDate.of(2026, 4, 2),
                StatementParsingUtilities.extractStatementDate(lines, 2026, true));
    }

    @Test
    void citiDoubleCashFixture_autoPay_inferredFromNextAutoPayPaymentSentence() {
        final String[] lines = STATEMENT_FIXTURE.split("\\n");
        assertTrue(StatementParsingUtilities.extractAutoPayEnabled(lines),
                "Citi prints 'Your next AutoPay payment of $X' only when enrolled");
        assertEquals(0,
                new BigDecimal("1038.02")
                        .compareTo(StatementParsingUtilities.extractNextAutoPayAmount(lines)));
    }

    @Test
    void citiDoubleCashFixture_extractsThankYouPointsBalanceFromMultiLineLabel() {
        final String[] lines = STATEMENT_FIXTURE.split("\\n");
        assertEquals(21_371L,
                StatementParsingUtilities.extractPointsBalance(lines).longValue(),
                "Bare 'Total ThankYou Points Balance:' label with value on next line");
        assertEquals(1_038L,
                StatementParsingUtilities.extractPointsEarnedThisPeriod(lines).longValue(),
                "ThankYou Points Earned: line matches existing 'points earned' pattern");
    }

    @Test
    void citiDoubleCashFixture_extractsYtdAndBillingPeriod() {
        final String[] lines = STATEMENT_FIXTURE.split("\\n");
        assertEquals(0,
                BigDecimal.ZERO.compareTo(StatementParsingUtilities.extractYtdFeesCharged(lines)));
        assertEquals(0,
                BigDecimal.ZERO.compareTo(StatementParsingUtilities.extractYtdInterestCharged(lines)));
    }

    // ---- Randomized property-based coverage ----

    @Test
    void citiBillingPeriod_randomized_alwaysExtractsEndDateAsStatementDate() {
        final java.util.Random rng = new java.util.Random(0xC171_BL);
        for (int i = 0; i < 100; i++) {
            final int year = 2020 + rng.nextInt(15);
            final int yy = year % 100;
            final int sm = 1 + rng.nextInt(11);
            final int sd = 1 + rng.nextInt(20);
            final java.time.LocalDate start = java.time.LocalDate.of(year, sm, sd);
            final java.time.LocalDate end = start.plusDays(28 + rng.nextInt(5));
            final String[] lines = {
                "Billing Period: " + String.format("%02d/%02d/%02d", sm, sd, yy)
                        + "-"
                        + String.format("%02d/%02d/%02d",
                                end.getMonthValue(), end.getDayOfMonth(), end.getYear() % 100),
            };
            assertEquals(end,
                    StatementParsingUtilities.extractStatementDate(lines, year, true),
                    "Iter " + i + ": expected " + end);
        }
    }

    @Test
    void profileDispatch_normalizesPaymentsAndCreditsToPositive() {
        // Closes the cross-issuer sign-convention gap: Citi prints "Payments -$X"
        // (sign attached → legacy returns negative), Wells Fargo prints "TOTAL
        // PAYMENTS FOR THIS PERIOD $X" (no sign → legacy returns positive).
        // Going through the profile normalizes to POSITIVE (absolute value), so
        // downstream consumers see one convention regardless of issuer.
        final var citi = new com.budgetbuddy.service.pdf.profile.CitiIssuerProfile();
        final var ctx = new com.budgetbuddy.service.pdf.profile.IssuerProfile.ExtractionContext(
                2026, true);
        final String[] signAttached = {"Payments -$1,234.56"};
        final BigDecimal extracted = citi.extractPaymentsAndCreditsTotal(signAttached, ctx);
        assertNotNull(extracted);
        assertEquals(0, new BigDecimal("1234.56").compareTo(extracted),
                "Profile must normalize Citi's sign-attached -$X to positive");
        // Wells Fargo's already-positive form also passes through unchanged.
        final var wells = new com.budgetbuddy.service.pdf.profile.WellsFargoIssuerProfile();
        final String[] alreadyPositive = {"TOTAL PAYMENTS FOR THIS PERIOD $1,234.56"};
        assertEquals(0, new BigDecimal("1234.56")
                .compareTo(wells.extractPaymentsAndCreditsTotal(alreadyPositive, ctx)),
                "Wells Fargo positive form must stay positive after normalization");
    }

    @Test
    void citiPaymentsAmount_randomized_legacyStaticPreservesNegativeSign() {
        // Tests the LEGACY static extractor directly — by design it returns the raw
        // sign as printed on the statement ("-$X" → -X). Production code goes
        // through the profile (see profileDispatch_normalizesPaymentsAndCreditsToPositive)
        // which normalizes to positive. Both contracts coexist.
        final java.util.Random rng = new java.util.Random(0xCA17_DECL);
        for (int i = 0; i < 100; i++) {
            final int dollars = rng.nextInt(50_000);
            final int cents = rng.nextInt(100);
            final String amount = String.format("%,d.%02d", dollars, cents);
            final String[] lines = {"Payments -$" + amount};
            final BigDecimal extracted =
                    StatementParsingUtilities.extractPaymentsAndCreditsTotal(lines);
            assertNotNull(extracted, "Iter " + i + ": must extract");
            assertTrue(extracted.signum() <= 0,
                    "Iter " + i + ": payments must be <= 0, got " + extracted);
            assertEquals(0, new BigDecimal("-" + amount.replace(",", ""))
                    .compareTo(extracted),
                    "Iter " + i + ": expected -" + amount + " got " + extracted);
        }
    }

    @Test
    void citiThankYouPointsBalance_randomized_multiLineCapture() {
        final java.util.Random rng = new java.util.Random(0xC171_FEEDL);
        for (int i = 0; i < 50; i++) {
            final int pts = rng.nextInt(1_000_000);
            final String[] lines = {
                "Total ThankYou Points Balance:",
                String.format("%,d", pts),
            };
            assertEquals(pts,
                    StatementParsingUtilities.extractPointsBalance(lines).longValue(),
                    "Iter " + i + ": expected " + pts);
        }
    }
}
