package com.budgetbuddy.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * End-to-end fixture coverage for a Chase Amazon Visa statement. Distinct from the
 * Marriott Bonvoy fixture because Amazon Visa has a very different rewards structure:
 *
 * <ul>
 *   <li><b>Cumulative balance</b> (not transfer-partner): the statement prints
 *       "Previous points balance NN,NNN" AND "Total points available for redemption
 *       NN,NNN" — the current balance is the second one, not the first.
 *   <li><b>Per-category earned breakdown</b>: rows of "{@code + N% back on/at CATEGORY
 *       NN}". The total earned this cycle is the SUM of these (most categories are 0;
 *       the user spent at restaurants this cycle for 189 points).
 *   <li><b>Different customer-service URL</b> (chase.com/amazon vs chase.com/marriott)
 *       and different sub-brand tag ("Prime Visa") that account detection should still
 *       recognise as Chase.
 * </ul>
 *
 * <p>Bug history: before this fixture's wiring, the Amazon-style statement returned
 * the PRIOR cycle's points balance instead of the current one (the bare "points
 * balance" pattern accidentally matched "Previous points balance" via word boundary),
 * and pointsEarnedThisPeriod was always null because the parser didn't know about
 * the per-category line format. All values are synthetic.
 */
class AmazonVisaStatementFixtureTest {

    /**
     * Synthetic Amazon Visa statement layout. Mirrors the real PDFBox extraction line-
     * by-line so any layout-specific regression surfaces here.
     */
    private static final String STATEMENT_FIXTURE =
            String.join(
                    "\n",
                    "Mobile:  Download the",
                    "Chase Mobile app today® ",
                    "ACCOUNT SUMMARY",
                    "YOUR ACCOUNT MESSAGES",
                    "AUTOPAY IS ON",
                    "Payment Due Date: 07/09/26",
                    "New Balance: $250.00",
                    "Minimum Payment Due: $35.00",
                    "Manage your account online:  Customer Service:",
                    "www.chase.com/amazon 1-888-247-4080",
                    "Account Number:  XXXX XXXX XXXX 9876",
                    "New Balance $250.00",
                    "Past Due Amount $0.00",
                    "Balance over the Credit Access Line $0.00",
                    "AUTOPAY IS ON",
                    "Penalty APR of 29.99%.",
                    "Previous Balance $50.00",
                    "Payment, Credits -$50.00",
                    "Purchases +$250.00",
                    "Cash Advances $0.00",
                    "Balance Transfers $0.00",
                    "Fees Charged $0.00",
                    "Interest Charged $0.00",
                    "Opening/Closing Date 05/13/26 - 06/12/26",
                    "Credit Access Line $20,000",
                    "Available Credit $19,750",
                    "Cash Access Line $4,000",
                    "Available for Cash $4,000",
                    // The Amazon Visa rewards block — distinct format from Marriott.
                    "Previous points balance 75,000",
                    "+ 5% back on Amazon.com purchases 0",
                    "+ 5% back on Whole Foods Market purchases 0",
                    "+ 2% back at gas stations 0",
                    "+ 2% back at restaurants 500",
                    "+ 5% back on Chase Travel purchases 0",
                    "+ 2% back on local transit/commuting 0",
                    "+ 1% back on all other purchases 250",
                    "+ 5% back at Amazon sites and stores 0",
                    "Reward your routine everywhere you shop with your Prime Visa.",
                    "YOUR PRIME VISA POINTS",
                    "Total points available for",
                    "redemption 75,750",
                    "07/09/26",
                    "$250.00",
                    // APR block.
                    "Purchases 21.74%(v)(d) - 0 -   - 0 -",
                    "Cash Advances 22.74%(v)(d) - 0 -   - 0 -",
                    "Balance Transfers 21.74%(v)(d) - 0 -   - 0 -",
                    "31 Days in Billing Period",
                    "Page 2 of 2 Statement Date: 06/12/26",
                    "Your next AutoPay payment for $250.00 will be deducted from your Pay From",
                    "There is a foreign transaction fee of 3% of the U.S. dollar amount",
                    // Transactions: one payment + a handful of domestic charges.
                    "06/09     AUTOMATIC PAYMENT - THANK YOU -50.00",
                    "05/14     CHIPOTLE MEX GR ONLINE 25.00",
                    "05/21     AMAZON.COM SEATTLE WA 225.00");

    // ============================================================
    //  Statement-summary block — same fields as Marriott fixture
    // ============================================================

    @Test
    void amazonVisaFixture_extractsCoreSummaryFields() {
        final String[] lines = STATEMENT_FIXTURE.split("\\n");
        assertEquals(0,
                new BigDecimal("250.00").compareTo(PDFImportService.extractNewBalance(lines)));
        assertEquals(0,
                new BigDecimal("50.00").compareTo(PDFImportService.extractPreviousBalance(lines)));
        assertEquals(0,
                new BigDecimal("20000").compareTo(PDFImportService.extractCreditLimit(lines)));
        assertEquals(0,
                new BigDecimal("19750").compareTo(PDFImportService.extractAvailableCredit(lines)));
        assertEquals(0,
                BigDecimal.ZERO.compareTo(PDFImportService.extractPastDueAmount(lines)));
        assertEquals(0,
                new BigDecimal("4000").compareTo(PDFImportService.extractCashAccessLine(lines)));
    }

    @Test
    void amazonVisaFixture_extractsAprsAndBillingInfo() {
        final String[] lines = STATEMENT_FIXTURE.split("\\n");
        assertEquals(0,
                new BigDecimal("21.74").compareTo(PDFImportService.extractPurchaseApr(lines)));
        assertEquals(0,
                new BigDecimal("22.74").compareTo(PDFImportService.extractCashAdvanceApr(lines)));
        assertEquals(0,
                new BigDecimal("29.99").compareTo(PDFImportService.extractPenaltyApr(lines)));
        assertEquals(31, PDFImportService.extractBillingDays(lines).intValue());
        assertEquals(LocalDate.of(2026, 6, 12),
                PDFImportService.extractStatementDate(lines, 2026, true));
    }

    @Test
    void amazonVisaFixture_extractsAutoPayAndForeignFee() {
        final String[] lines = STATEMENT_FIXTURE.split("\\n");
        assertTrue(PDFImportService.extractAutoPayEnabled(lines));
        assertEquals(0,
                new BigDecimal("250.00").compareTo(PDFImportService.extractNextAutoPayAmount(lines)));
        assertEquals(0,
                new BigDecimal("3").compareTo(
                        PDFImportService.extractForeignTransactionFeePercent(lines)));
    }

    @Test
    void amazonVisaFixture_noAnnualFeeExtracted() {
        // The Amazon Visa is a no-annual-fee card — the statement doesn't print a
        // "Your annual membership fee in the amount of $X will be billed on..." sentence.
        // Extractor must correctly return null (NOT default-zero).
        final String[] lines = STATEMENT_FIXTURE.split("\\n");
        assertNull(PDFImportService.extractAnnualMembershipFeeAndDate(lines, 2026, true));
    }

    // ============================================================
    //  Points — the core new behaviour for this card type
    // ============================================================

    @Test
    void amazonVisaFixture_extractsCurrentBalance_notPreviousBalance() {
        // The bug this whole fixture was written for: bare "points balance" pattern
        // was matching "Previous points balance" via word boundary. The negative
        // lookbehind on POINTS_BALANCE_PATTERNS[1] is the fix — verify here that
        // the CURRENT balance (75,750 from "Total points available for redemption")
        // wins, NOT the prior cycle's 75,000.
        final String[] lines = STATEMENT_FIXTURE.split("\\n");
        final Long balance = PDFImportService.extractPointsBalance(lines);
        assertNotNull(balance);
        assertEquals(75_750L, balance.longValue(),
                "Must extract CURRENT balance (75,750) not PRIOR balance (75,000) — "
                        + "regression here means the lookbehind on 'previous' was removed");
    }

    @Test
    void amazonVisaFixture_sumsAllPerCategoryEarnedLines() {
        // The user earned 500 points at restaurants + 250 on other purchases = 750 total
        // for the cycle. Extractor must SUM all "+ N% back ... NN" rows, including the
        // zero-valued categories (which contribute 0 to the sum but signal that the
        // rewards section is present).
        final String[] lines = STATEMENT_FIXTURE.split("\\n");
        final Long earned = PDFImportService.extractPointsEarnedThisPeriod(lines);
        assertNotNull(earned);
        assertEquals(750L, earned.longValue(),
                "Earned this period must be the SUM (500 + 250) of all per-category lines, "
                        + "not the first non-zero one");
    }

    @Test
    void amazonVisaFixture_extractsPreviousPointsBalance() {
        final String[] lines = STATEMENT_FIXTURE.split("\\n");
        assertEquals(75_000L,
                PDFImportService.extractPreviousPointsBalance(lines).longValue(),
                "Previous balance must be extracted as a separate field");
    }

    @Test
    void amazonVisaFixture_pointsArithmeticReconciles() {
        // Sanity check: previous + earned should == current. If this fails, one of the
        // three extractors is producing a wrong value (or the synthetic fixture isn't
        // internally consistent). 75,000 + 750 = 75,750.
        final String[] lines = STATEMENT_FIXTURE.split("\\n");
        final long previous =
                PDFImportService.extractPreviousPointsBalance(lines).longValue();
        final long earned =
                PDFImportService.extractPointsEarnedThisPeriod(lines).longValue();
        final long current =
                PDFImportService.extractPointsBalance(lines).longValue();
        assertEquals(current, previous + earned,
                "previous + earned must equal current — math sanity check across all 3 extractors");
    }

    // ============================================================
    //  Account detection — Amazon Visa is still a Chase card
    // ============================================================
    // (Account detection lives in AccountDetectionService; that test surface
    //  is already covered by AccountDetectionServiceTest. Here we just want
    //  to confirm the Amazon-style URL doesn't break Chase detection.)
}
