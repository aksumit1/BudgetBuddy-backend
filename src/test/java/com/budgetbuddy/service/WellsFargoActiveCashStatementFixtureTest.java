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
 * End-to-end fixture coverage for a Wells Fargo Active Cash Visa Signature statement.
 * Distinct from the Chase fixtures because Wells Fargo uses a completely different
 * statement layout:
 *
 * <ul>
 *   <li><b>Cash-back rewards</b> as a dollar value ("Rewards balance as of: DATE
 *       $XX.XX"), not integer points. Mapped to the new {@code cashBackBalance}
 *       BigDecimal field — mutually exclusive with {@code pointsBalance}.
 *   <li><b>"Total Available Credit"</b> wording for the headroom column.
 *   <li><b>"Cash Advance Limit"</b> and <b>"Available for Cash Advances"</b> for the
 *       cash-advance sub-limits.
 *   <li><b>AutoPay is implicit</b> from "$X - $Y will be deducted ... credited as
 *       your automatic payment on MM/DD/YY". Wells Fargo doesn't print a discrete
 *       "AUTOPAY IS ON" line.
 *   <li><b>YTD totals</b> have the label on one line ("Total Fees Charged in 2026")
 *       and the dollar amount two lines later — different from Chase's same-line form.
 *   <li><b>Statement Period</b> replaces "Statement Date" — the second date in the
 *       range is the close date.
 *   <li><b>Section totals</b> are surfaced via the "TOTAL X FOR THIS PERIOD" rows in
 *       the transaction-detail block, since the Account Summary version wraps the
 *       label across multiple lines.
 * </ul>
 *
 * <p>All values are synthetic — no real PII or real account numbers in this file.
 */
class WellsFargoActiveCashStatementFixtureTest {

    /**
     * Synthetic Wells Fargo Active Cash statement layout. Mirrors the real PDFBox
     * extraction line-by-line so any layout-specific regression surfaces here.
     */
    private static final String STATEMENT_FIXTURE =
            String.join(
                    "\n",
                    "NOTICE:  SEE REVERSE SIDE FOR IMPORTANT INFORMATION ABOUT YOUR ACCOUNT",
                    "Wells Fargo Online®: wellsfargo.com",
                    "24-hour Customer Service: 1-866-229-6633",
                    "Payment ",
                    "Payment Due Date 05/12/2026",
                    "Minimum Payment $137.00",
                    "New Balance $1,234.56",
                    "WELLS FARGO ACTIVE CASH VISA SIGNATURE® CARD",
                    "Account ending in 1234",
                    "Statement Period 03/19/2026 to 04/17/2026",
                    "Page 1 of 3",
                    // AutoPay clue — Wells phrasing.
                    " $0 - $1,234.56 will be deducted from your account and credited"
                            + " as your automatic payment on 05/12/26.",
                    "Account Summary",
                    "Previous Balance $500.00",
                    "- Payments $500.00",
                    "- Other Credits $0.00",
                    "+ Cash Advances $0.00",
                    "+ Purchases, Balance Transfers &",
                    "Other Charges",
                    "$1,234.56",
                    "+ Fees Charged $0.00",
                    "+ Interest Charged $0.00",
                    "= New Balance $1,234.56",
                    "Total Credit Limit $10,000",
                    "Cash Advance Limit $2,000",
                    "Total Available Credit $8,765",
                    "Available for Cash Advances $2,000",
                    "Wells Fargo Rewards Summary",
                    "Rewards balance as of: 03/31/2026      $42.50",
                    "Transactions",
                    "Payments",
                    "04/12 04/12 F353100FNXXXCHGDDA AUTOMATIC PAYMENT - THANK YOU 500.00",
                    "TOTAL PAYMENTS FOR THIS PERIOD $500.00",
                    "Purchases, Balance Transfers & Other Charges",
                    "1234 03/18 03/19 2494166EX8ASR99D0 GROCERY STORE BELLEVUE  WA 116.59",
                    "1234 03/29 03/29 2424052F9L9XPN1A4 GAS STATION RENTON  WA 58.20",
                    "1234 04/01 04/01 2469216FB2XPSN2XR INTERNET PROVIDER 888-555-0100 PA 184.86",
                    "1234 04/16 04/16 2413746FSEJG588FM MERCHANT XYZ 888-555-0199 TN 874.91",
                    "TOTAL PURCHASES, BALANCE TRANSFERS & OTHER CHARGES FOR THIS PERIOD $1,234.56",
                    "Fees Charged",
                    "TOTAL FEES CHARGED FOR THIS PERIOD $0.00",
                    "Interest Charged",
                    "INTEREST CHARGE ON PURCHASES 0.00",
                    "INTEREST CHARGE ON CASH ADVANCES 0.00",
                    "TOTAL INTEREST CHARGED FOR THIS PERIOD $0.00",
                    "2026 Totals Year-to-Date",
                    "TOTAL FEES CHARGED IN 2026  ",
                    "TOTAL INTEREST CHARGED IN 2026",
                    "$0.00",
                    "$0.00",
                    "Interest Charge Calculation",
                    "Type of Balance",
                    "Annual",
                    "Percentage Rate",
                    " (APR)",
                    "PURCHASES 18.49% variable $0.00 30 $0.00 $1,234.56",
                    "CASH ADVANCES 29.49% variable $0.00 30 $0.00 $0.00");

    @Test
    void wellsFargoFixture_extractsCoreSummaryFields() {
        final String[] lines = STATEMENT_FIXTURE.split("\\n");
        assertEquals(0,
                new BigDecimal("1234.56").compareTo(StatementParsingUtilities.extractNewBalance(lines)));
        assertEquals(0,
                new BigDecimal("500.00").compareTo(StatementParsingUtilities.extractPreviousBalance(lines)));
        assertEquals(0,
                new BigDecimal("10000").compareTo(StatementParsingUtilities.extractCreditLimit(lines)));
        assertEquals(0,
                new BigDecimal("8765").compareTo(StatementParsingUtilities.extractAvailableCredit(lines)));
        assertEquals(0,
                new BigDecimal("2000").compareTo(StatementParsingUtilities.extractCashAccessLine(lines)));
        assertEquals(0,
                new BigDecimal("2000").compareTo(StatementParsingUtilities.extractAvailableForCash(lines)));
    }

    @Test
    void wellsFargoFixture_extractsSectionTotals_fromPeriodLines() {
        final String[] lines = STATEMENT_FIXTURE.split("\\n");
        // Wells uses the per-period transaction-section totals as the canonical source.
        assertEquals(0,
                new BigDecimal("1234.56").compareTo(StatementParsingUtilities.extractPurchasesTotal(lines)));
        assertEquals(0,
                new BigDecimal("500.00")
                        .compareTo(StatementParsingUtilities.extractPaymentsAndCreditsTotal(lines)));
        assertEquals(0,
                BigDecimal.ZERO.compareTo(StatementParsingUtilities.extractFeesChargedTotal(lines)));
        assertEquals(0,
                BigDecimal.ZERO.compareTo(StatementParsingUtilities.extractInterestChargedTotal(lines)));
    }

    @Test
    void wellsFargoFixture_extractsAprsAndBillingInfo() {
        final String[] lines = STATEMENT_FIXTURE.split("\\n");
        assertEquals(0,
                new BigDecimal("18.49").compareTo(StatementParsingUtilities.extractPurchaseApr(lines)));
        assertEquals(0,
                new BigDecimal("29.49").compareTo(StatementParsingUtilities.extractCashAdvanceApr(lines)));
        // Active Cash card has no balance-transfer or penalty APRs printed.
        assertNull(StatementParsingUtilities.extractBalanceTransferApr(lines));
        assertNull(StatementParsingUtilities.extractPenaltyApr(lines));
        // Billing days are computed from the Statement Period range (Mar 19 → Apr 17 = 30 days).
        assertEquals(30, StatementParsingUtilities.extractBillingDays(lines).intValue());
        // Statement date is the second date in the period range.
        assertEquals(LocalDate.of(2026, 4, 17),
                StatementParsingUtilities.extractStatementDate(lines, 2026, true));
    }

    @Test
    void wellsFargoFixture_autoPayInferredFromSentence_amountIsUpperBound() {
        final String[] lines = STATEMENT_FIXTURE.split("\\n");
        assertTrue(StatementParsingUtilities.extractAutoPayEnabled(lines),
                "AutoPay must be implied by the 'credited as your automatic payment' sentence");
        assertEquals(0,
                new BigDecimal("1234.56")
                        .compareTo(StatementParsingUtilities.extractNextAutoPayAmount(lines)),
                "Next AutoPay amount must be the UPPER bound of the '$X - $Y' range");
    }

    @Test
    void wellsFargoFixture_extractsCashBackBalance_notPoints() {
        final String[] lines = STATEMENT_FIXTURE.split("\\n");
        // Active Cash is a flat 2% cash-back card — rewards are a dollar value, not a
        // point count. cashBackBalance is set; pointsBalance is null.
        assertEquals(0,
                new BigDecimal("42.50")
                        .compareTo(StatementParsingUtilities.extractCashBackBalance(lines)));
        assertNull(StatementParsingUtilities.extractPointsBalance(lines));
        assertNull(StatementParsingUtilities.extractPointsEarnedThisPeriod(lines));
    }

    @Test
    void wellsFargoFixture_extractsYtdFeesAndInterest_fromBareLabelLines() {
        final String[] lines = STATEMENT_FIXTURE.split("\\n");
        // Wells lays YTD as label-only lines followed by amount-only lines.
        assertEquals(0,
                BigDecimal.ZERO.compareTo(StatementParsingUtilities.extractYtdFeesCharged(lines)));
        assertEquals(0,
                BigDecimal.ZERO.compareTo(StatementParsingUtilities.extractYtdInterestCharged(lines)));
    }

    @Test
    void wellsFargoFixture_noAnnualFeeExtracted() {
        // Active Cash is a no-annual-fee card — the statement doesn't print an annual-fee
        // sentence. Extractor must correctly return null (NOT default-zero).
        final String[] lines = STATEMENT_FIXTURE.split("\\n");
        assertNull(StatementParsingUtilities.extractAnnualMembershipFeeAndDate(lines, 2026, true));
    }

    @Test
    void wellsFargoFixture_noPointsRewardMultipliers() {
        // Flat 2% cash-back card has no per-category multiplier block — empty map.
        final String[] lines = STATEMENT_FIXTURE.split("\\n");
        final var multipliers = StatementParsingUtilities.extractRewardMultipliersFromPdf(lines);
        assertNotNull(multipliers);
        assertTrue(multipliers.isEmpty(),
                "Active Cash has no per-category breakdown — multipliers map must be empty");
    }

    @Test
    void cashBackBalance_returnsNull_whenLineAbsent() {
        final String[] lines = {"New Balance $100", "Payment Due Date 05/01/26"};
        assertNull(StatementParsingUtilities.extractCashBackBalance(lines),
                "Absent rewards-balance line must yield null, not zero");
    }

    @Test
    void cashBackBalance_acceptsRewardsBalanceWithoutAsOfDate() {
        // Some issuer variants print just "Rewards balance $XX.XX" without "as of:".
        final String[] lines = {"Rewards balance $25.10"};
        assertEquals(0,
                new BigDecimal("25.10")
                        .compareTo(StatementParsingUtilities.extractCashBackBalance(lines)));
    }

    // ============================================================
    //  Randomized / property-based coverage
    //
    //  We don't want the parser to be tightly coupled to one specific date /
    //  amount / reference-number. These property tests synthesize many random
    //  variations of the same statement shape and assert that the extractors
    //  always recover the same logical values.
    // ============================================================

    @Test
    void cashBackBalance_randomized_extractsDollarAmountAcrossManyShapes() {
        final java.util.Random rng = new java.util.Random(0xCA5BACL);
        for (int i = 0; i < 200; i++) {
            // Random dollar amount with up to 5 integer digits, always 2 decimal places.
            final int dollars = rng.nextInt(99_999);
            final int cents = rng.nextInt(100);
            final String amountStr = String.format("%,d.%02d", dollars, cents);
            final String dateStr = String.format(
                    "%02d/%02d/%04d",
                    rng.nextInt(12) + 1, rng.nextInt(28) + 1, 2020 + rng.nextInt(15));
            final String[] lines = {
                "Wells Fargo Rewards Summary",
                "Rewards balance as of: " + dateStr + "      $" + amountStr,
            };
            final BigDecimal extracted = StatementParsingUtilities.extractCashBackBalance(lines);
            final BigDecimal expected =
                    new BigDecimal(amountStr.replace(",", ""));
            assertNotNull(extracted,
                    "Iter " + i + ": parser returned null for amount $" + amountStr);
            assertEquals(0, expected.compareTo(extracted),
                    "Iter " + i + ": expected " + expected + " got " + extracted
                            + " from line with amount $" + amountStr);
        }
    }

    @Test
    void autoPayAmount_randomized_alwaysPicksUpperBoundOfRange() {
        // The "$X - $Y will be deducted ... automatic payment" pattern must always
        // return Y (the high end of the range), even when both ends are equal.
        final java.util.Random rng = new java.util.Random(0xA7_0FACEL);
        for (int i = 0; i < 200; i++) {
            final int upperDollars = rng.nextInt(99_999);
            final int upperCents = rng.nextInt(100);
            final int lowerDollars = rng.nextInt(upperDollars + 1);
            final int lowerCents = rng.nextInt(100);
            final String upper = String.format("%,d.%02d", upperDollars, upperCents);
            final String lower = String.format("%,d.%02d", lowerDollars, lowerCents);
            final String[] lines = {
                " $" + lower + " - $" + upper
                        + " will be deducted from your account and credited as"
                        + " your automatic payment on 05/12/26.",
            };
            assertTrue(StatementParsingUtilities.extractAutoPayEnabled(lines),
                    "Iter " + i + ": AutoPay must be detected from Wells sentence");
            final BigDecimal extracted = StatementParsingUtilities.extractNextAutoPayAmount(lines);
            final BigDecimal expected = new BigDecimal(upper.replace(",", ""));
            assertNotNull(extracted, "Iter " + i + ": amount must not be null");
            assertEquals(0, expected.compareTo(extracted),
                    "Iter " + i + ": expected upper bound " + expected + " got " + extracted);
        }
    }

    @Test
    void statementPeriod_randomized_extractsEndDateAsStatementDate() {
        final java.util.Random rng = new java.util.Random(0x57A7E_DE);
        for (int i = 0; i < 100; i++) {
            final int year = 2020 + rng.nextInt(15);
            final int startMonth = 1 + rng.nextInt(11); // leave room for end > start
            final int startDay = 1 + rng.nextInt(20);
            final java.time.LocalDate start =
                    java.time.LocalDate.of(year, startMonth, startDay);
            final java.time.LocalDate end =
                    start.plusDays(28 + rng.nextInt(5)); // ~28-32 day cycle
            final String[] lines = {
                "WELLS FARGO ACTIVE CASH VISA SIGNATURE® CARD",
                "Statement Period " + start.format(
                        java.time.format.DateTimeFormatter.ofPattern("MM/dd/yyyy"))
                        + " to "
                        + end.format(
                                java.time.format.DateTimeFormatter.ofPattern("MM/dd/yyyy")),
            };
            final java.time.LocalDate parsed =
                    StatementParsingUtilities.extractStatementDate(lines, year, true);
            assertEquals(end, parsed,
                    "Iter " + i + ": expected statement date " + end + " got " + parsed);
            final Integer days = StatementParsingUtilities.extractBillingDays(lines);
            assertNotNull(days, "Iter " + i + ": billing days must be inferred");
            final long expected =
                    java.time.temporal.ChronoUnit.DAYS.between(start, end) + 1;
            assertEquals(expected, days.intValue(),
                    "Iter " + i + ": expected " + expected + " days got " + days);
        }
    }

    @Test
    void availableCredit_randomized_acceptsBothLabelVariants() {
        // Both "Available Credit" (Chase wording) and "Total Available Credit"
        // (Wells wording) should yield the same extracted value. Pin against
        // randomized amounts to make sure we don't lose a digit.
        final java.util.Random rng = new java.util.Random(0xAC_C5);
        for (int i = 0; i < 100; i++) {
            final int n = rng.nextInt(150_000);
            final String label = (i % 2 == 0) ? "Available Credit" : "Total Available Credit";
            final String amountStr = String.format("%,d", n);
            final String[] lines = {label + " $" + amountStr};
            final BigDecimal extracted = StatementParsingUtilities.extractAvailableCredit(lines);
            assertNotNull(extracted,
                    "Iter " + i + ": '" + label + " $" + amountStr + "' must parse");
            assertEquals(0,
                    new BigDecimal(n).compareTo(extracted),
                    "Iter " + i + ": expected " + n + " got " + extracted);
        }
    }

    @Test
    void wellsFargoPaymentPattern_detected_acrossRandomReferenceCodes() throws Exception {
        // The sign-handling logic for Wells payments hinges on
        // isWellsFargoPaymentPattern matching the description. Pattern requires:
        //   <16+ alphanumeric all-caps ref code> <PAYMENT_TYPE> PAYMENT - THANK YOU
        // Drive that with synthetic reference codes so a regex regression here
        // surfaces immediately. Reflection because the method is package-private
        // on an instance — but no Spring context is required because the helper
        // is purely string-pattern based.
        final java.lang.reflect.Method matcher =
                PDFImportService.class.getDeclaredMethod(
                        "isWellsFargoPaymentPattern", String.class);
        matcher.setAccessible(true);
        final java.lang.reflect.Constructor<?>[] ctors =
                PDFImportService.class.getDeclaredConstructors();
        java.lang.reflect.Constructor<?> ctor = ctors[0];
        for (final java.lang.reflect.Constructor<?> c : ctors) {
            if (c.getParameterCount() < ctor.getParameterCount()) {
                ctor = c;
            }
        }
        ctor.setAccessible(true);
        final Object instance = ctor.newInstance(new Object[ctor.getParameterCount()]);

        final String[] paymentTypes = {
            "AUTOMATIC", "CHECK", "CASH", "TRANSFER", "PHONE", "CALL", "RECEIVED"
        };
        final java.util.Random rng = new java.util.Random(0xBEEFL);
        for (int i = 0; i < 200; i++) {
            final int refLen = 16 + rng.nextInt(8); // 16–23 chars
            final StringBuilder ref = new StringBuilder(refLen);
            for (int j = 0; j < refLen; j++) {
                final int kind = rng.nextInt(2);
                if (kind == 0) {
                    ref.append((char) ('A' + rng.nextInt(26)));
                } else {
                    ref.append((char) ('0' + rng.nextInt(10)));
                }
            }
            final String type = paymentTypes[rng.nextInt(paymentTypes.length)];
            final String desc = ref + " " + type + " PAYMENT - THANK YOU";
            final Boolean matched = (Boolean) matcher.invoke(instance, desc);
            assertTrue(matched,
                    "Iter " + i + ": '" + desc + "' must match Wells payment pattern");
        }

        // Negative cases: same shape but missing critical pieces — must NOT match.
        assertEquals(false, matcher.invoke(instance,
                "F353100FN00CHGDDA AUTOMATIC PAYMENT"),
                "Missing '- THANK YOU' tail must NOT match");
        assertEquals(false, matcher.invoke(instance,
                "SHORT AUTOMATIC PAYMENT - THANK YOU"),
                "Reference code under 16 chars must NOT match");
        assertEquals(false, matcher.invoke(instance,
                "f353100fn00chgdda automatic payment - thank you"),
                "Lowercase must NOT match — Wells statements are all-caps");
    }

    @Test
    void cashBackBalance_doesNotFalsePositive_onAvailableCreditLine() {
        // Defensive: a line like "Total Available Credit $1,234.56" must not be
        // misread as a cash-back balance by the rewards regex.
        final String[] lines = {"Total Available Credit $1,234.56"};
        assertNull(StatementParsingUtilities.extractCashBackBalance(lines));
    }
}
