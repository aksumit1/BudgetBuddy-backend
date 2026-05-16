package com.budgetbuddy.service;

import com.budgetbuddy.service.pdf.profile.StatementParsingUtilities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Fixture for a Costco Anywhere Visa® Card by Citi. Shares most of the Citi
 * Double Cash layout, but adds:
 *
 * <ul>
 *   <li><b>Cash-back rewards expressed in dollars</b>: {@code Total Costco Cash Back
 *       Rewards Balance: $X.XX} (single-line on April-style statements) or
 *       {@code Total Costco Cash Back Rewards Balance} \n {@code Year to Date :  $X}
 *       (two-line wrap on earlier statements). Both map to the SAME
 *       {@code cashBackBalance} field used by Wells Fargo Active Cash — no parallel
 *       field is created.
 *   <li><b>"Available Credit Limit"</b> wording (Citi's variant of "Available Credit").
 * </ul>
 *
 * <p>All values are synthetic — no real PII or real account numbers.
 */
class CitiCostcoStatementFixtureTest {

    private static final String STATEMENT_FIXTURE =
            String.join(
                    "\n",
                    "TEST CARDHOLDER",
                    "Costco Anywhere Visa® Card by Citi",
                    "Member Since 2007. Account number ending in: 5050",
                    "www.citicards.com",
                    "APRIL STATEMENT",
                    "Billing Period: 03/21/26-04/21/26",
                    "New balance as of 04/21/26:  $1,958.02",
                    "Minimum payment due:  $41.00",
                    "Payment due date:  05/17/26",
                    "Penalty APR of 29.99%.",
                    "Costco Cash Back Rewards Summary",
                    "Total Cashback: ",
                    "$262.04 as of 04/21/26",
                    "Your next AutoPay payment of $1,958.02 will be",
                    " deducted from your bank account on 05/17/2026.",
                    "Account Summary",
                    "Previous balance  $2,039.46",
                    "Payments -$2,039.46",
                    "Purchases  +$1,958.02",
                    "Cash advances  +$0.00",
                    "New balance  $1,958.02",
                    "Credit Limit",
                    "Credit Limit  $15,000",
                    "Available Credit Limit  $13,041",
                    "Interest charge calculation Days in billing cycle: 32",
                    " Standard Purch 18.74% (V) $0.00 (D) $0.00",
                    " Standard Adv 28.74% (V) $0.00 (D) $0.00",
                    "Total Costco Cash Back Rewards Balance: $262.04");

    @Test
    void costcoFixture_extractsCoreSummary() {
        final String[] lines = STATEMENT_FIXTURE.split("\\n");
        assertEquals(0,
                new BigDecimal("1958.02").compareTo(StatementParsingUtilities.extractNewBalance(lines)));
        assertEquals(0,
                new BigDecimal("2039.46")
                        .compareTo(StatementParsingUtilities.extractPreviousBalance(lines)));
        assertEquals(0,
                new BigDecimal("15000").compareTo(StatementParsingUtilities.extractCreditLimit(lines)));
        // "Available Credit Limit" — Citi Costco's wording for available credit.
        assertEquals(0,
                new BigDecimal("13041")
                        .compareTo(StatementParsingUtilities.extractAvailableCredit(lines)));
    }

    @Test
    void costcoFixture_extractsCashBackBalance_intoSameFieldAsWellsFargo() {
        final String[] lines = STATEMENT_FIXTURE.split("\\n");
        // Single-line form: "Total Costco Cash Back Rewards Balance: $X.XX".
        assertEquals(0,
                new BigDecimal("262.04")
                        .compareTo(StatementParsingUtilities.extractCashBackBalance(lines)));
    }

    @Test
    void costcoFixture_extractsCashBackBalance_fromMultiLineWrappedLabel() {
        // Earlier-month statements wrap the label onto two lines. Same field must
        // populate from this variant.
        final String[] lines = {
            "Total Costco Cash Back Rewards Balance",
            " Year to Date :  $163.64",
        };
        assertEquals(0,
                new BigDecimal("163.64")
                        .compareTo(StatementParsingUtilities.extractCashBackBalance(lines)));
    }

    @Test
    void costcoFixture_aprAndPaymentsSection() {
        final String[] lines = STATEMENT_FIXTURE.split("\\n");
        assertEquals(0,
                new BigDecimal("18.74")
                        .compareTo(StatementParsingUtilities.extractPurchaseApr(lines)));
        assertEquals(0,
                new BigDecimal("28.74")
                        .compareTo(StatementParsingUtilities.extractCashAdvanceApr(lines)));
        assertEquals(0,
                new BigDecimal("29.99")
                        .compareTo(StatementParsingUtilities.extractPenaltyApr(lines)));
        assertEquals(32, StatementParsingUtilities.extractBillingDays(lines).intValue());
        assertEquals(0,
                new BigDecimal("-2039.46")
                        .compareTo(StatementParsingUtilities.extractPaymentsAndCreditsTotal(lines)));
    }

    @Test
    void costcoCashBack_randomized_acrossSingleAndMultiLineForms() {
        final java.util.Random rng = new java.util.Random(0xC057C0_BBL);
        for (int i = 0; i < 100; i++) {
            final int dollars = rng.nextInt(9_999);
            final int cents = rng.nextInt(100);
            final String amount = String.format("%,d.%02d", dollars, cents);
            final BigDecimal expected = new BigDecimal(amount.replace(",", ""));
            // Single-line form.
            final String[] single = {
                "Total Costco Cash Back Rewards Balance: $" + amount,
            };
            assertEquals(0, expected.compareTo(StatementParsingUtilities.extractCashBackBalance(single)),
                    "Iter " + i + " single-line: expected " + expected);
            // Multi-line wrapped form (common on quarterly-credit statements).
            final String[] multi = {
                "Total Costco Cash Back Rewards Balance",
                " Year to Date :  $" + amount,
            };
            assertEquals(0, expected.compareTo(StatementParsingUtilities.extractCashBackBalance(multi)),
                    "Iter " + i + " multi-line: expected " + expected);
        }
    }
}
