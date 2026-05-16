package com.budgetbuddy.service.pdf.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.budgetbuddy.service.pdf.profile.IssuerProfile.ExtractionContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * Fixture for a Bank of America Customized Cash Rewards statement. All values synthetic.
 *
 * <p>This is the canonical test for the NEW issuer-profile architecture. BoA wasn't
 * supported by the legacy generic extractors, so the per-issuer overrides are the only
 * source of these values — a great regression target.
 */
class BankOfAmericaIssuerProfileTest {

    private final BankOfAmericaIssuerProfile profile = new BankOfAmericaIssuerProfile();
    private final ExtractionContext ctx = new ExtractionContext(2026, true);

    private static final String STATEMENT_FIXTURE =
            String.join(
                    "\n",
                    "Bank of America Customer Service",
                    "TEST CARDHOLDER",
                    "Customized Cash Rewards Visa Platinum Plus",
                    "bankofamerica.com",
                    "Statement Closing Date: 04/15/2026",
                    "Account # ending in 1234",
                    "Payment Due Date: 05/12/2026",
                    "Minimum Payment Due: $35.00",
                    "New Balance Total: $1,234.56",
                    "Total Credit Line: $15,000.00",
                    "Total Credit Line Available: $13,765.44",
                    "Previous Balance: $2,500.00",
                    "Payments and Other Credits: -$2,500.00",
                    "Purchases and Adjustments: +$1,234.56",
                    "Interest Charged: +$0.00",
                    "Fees Charged: +$0.00",
                    "Total Reward Cash Balance: $42.83",
                    "Your monthly payment of $1,234.56 will automatically debit your"
                            + " account on 05/12/2026",
                    "APR for Purchases: 19.24%",
                    "APR for Cash Advances: 29.49%");

    @Test
    void boaProfile_matchesHeaderText() {
        assertTrue(profile.matches("Bank of America Customer Service\nbankofamerica.com"));
        assertTrue(profile.matches("Sent from BofA, our identity protection ..."));
    }

    @Test
    void boaProfile_doesNotMatch_chaseStatement() {
        final boolean matches = profile.matches(
                "JPMorgan Chase Bank, N.A.\nwww.chase.com");
        // BoA pattern shouldn't false-positive on Chase headers — "BofA" abbrev would,
        // but Chase doesn't say it.
        assertEquals(false, matches);
    }

    @Test
    void boaProfile_detectsBrandsFromHeader() {
        assertEquals("customized-cash-rewards",
                profile.detectBrand("Customized Cash Rewards Visa Platinum Plus"));
        assertEquals("travel-rewards",
                profile.detectBrand("Bank of America Travel Rewards"));
        assertEquals("premium-rewards",
                profile.detectBrand("Bank of America Premium Rewards"));
        assertEquals("unlimited-cash-rewards",
                profile.detectBrand("Unlimited Cash Rewards"));
    }

    @Test
    void boaProfile_extractsCreditLineAndAvailable() {
        final String[] lines = STATEMENT_FIXTURE.split("\\n");
        assertEquals(0,
                new BigDecimal("15000.00").compareTo(profile.extractCreditLimit(lines, ctx)),
                "Total Credit Line maps to creditLimit");
        assertEquals(0,
                new BigDecimal("13765.44").compareTo(profile.extractAvailableCredit(lines, ctx)),
                "Total Credit Line Available maps to availableCredit");
    }

    @Test
    void boaProfile_extractsStatementClosingDate() {
        final String[] lines = STATEMENT_FIXTURE.split("\\n");
        assertEquals(LocalDate.of(2026, 4, 15),
                profile.extractStatementDate(lines, ctx),
                "Statement Closing Date label is BoA's analogue of Chase's Statement Date");
    }

    @Test
    void boaProfile_extractsRewardCashBalance_intoCashBackField() {
        final String[] lines = STATEMENT_FIXTURE.split("\\n");
        assertEquals(0,
                new BigDecimal("42.83").compareTo(profile.extractCashBackBalance(lines, ctx)),
                "Total Reward Cash Balance is dollar cash-back — maps to cashBackBalance, "
                        + "the same field used by Wells Fargo Active Cash and Citi Costco");
    }

    @Test
    void boaProfile_extractsAutoPayFromMonthlyDebitSentence() {
        final String[] lines = STATEMENT_FIXTURE.split("\\n");
        assertTrue(profile.extractAutoPayEnabled(lines, ctx),
                "BoA prints 'monthly payment of $X will automatically debit' only when enrolled");
        assertEquals(0,
                new BigDecimal("1234.56")
                        .compareTo(profile.extractNextAutoPayAmount(lines, ctx)));
    }

    // ---- Randomized property tests ----

    @Test
    void boaCreditLine_randomized_alwaysExtractsAmount() {
        final java.util.Random rng = new java.util.Random(0xB0A_C2EDL);
        for (int i = 0; i < 100; i++) {
            final int limit = 1_000 + rng.nextInt(99_000);
            final int avail = rng.nextInt(limit);
            final String[] lines = {
                "Total Credit Line: $" + String.format("%,d.00", limit),
                "Total Credit Line Available: $" + String.format("%,d.00", avail),
            };
            assertEquals(0, new BigDecimal(limit + ".00")
                    .compareTo(profile.extractCreditLimit(lines, ctx)),
                    "Iter " + i + ": credit limit");
            assertEquals(0, new BigDecimal(avail + ".00")
                    .compareTo(profile.extractAvailableCredit(lines, ctx)),
                    "Iter " + i + ": available");
        }
    }

    @Test
    void boaRewardCashBalance_randomized_amountVariations() {
        final java.util.Random rng = new java.util.Random(0xB0A_BACL);
        for (int i = 0; i < 100; i++) {
            final int dollars = rng.nextInt(9_999);
            final int cents = rng.nextInt(100);
            final String amount = String.format("%,d.%02d", dollars, cents);
            final String[] lines = {"Total Reward Cash Balance: $" + amount};
            final BigDecimal extracted = profile.extractCashBackBalance(lines, ctx);
            assertNotNull(extracted,
                    "Iter " + i + ": must extract $" + amount);
            assertEquals(0,
                    new BigDecimal(amount.replace(",", "")).compareTo(extracted),
                    "Iter " + i + ": expected " + amount);
        }
    }

    @Test
    void boaAutoPayAmount_randomized_disclosureSentence() {
        final java.util.Random rng = new java.util.Random(0xB0A_A170L);
        for (int i = 0; i < 100; i++) {
            final int dollars = rng.nextInt(99_999);
            final int cents = rng.nextInt(100);
            final String amount = String.format("%,d.%02d", dollars, cents);
            final String[] lines = {
                "Your monthly payment of $" + amount + " will automatically debit"
                        + " your account on 05/12/2026",
            };
            assertTrue(profile.extractAutoPayEnabled(lines, ctx),
                    "Iter " + i + ": ON must fire");
            assertEquals(0,
                    new BigDecimal(amount.replace(",", ""))
                            .compareTo(profile.extractNextAutoPayAmount(lines, ctx)),
                    "Iter " + i + ": amount");
        }
    }

    @Test
    void boaStatementDate_randomized_acceptsBothSlashedDateForms() {
        final java.util.Random rng = new java.util.Random(0xB0A_DA7EL);
        for (int i = 0; i < 60; i++) {
            final int year = 2020 + rng.nextInt(10);
            final int month = 1 + rng.nextInt(12);
            final int day = 1 + rng.nextInt(28);
            final java.time.LocalDate expected = java.time.LocalDate.of(year, month, day);
            final String[] mmddYYYY = {
                "Statement Closing Date: " + String.format("%02d/%02d/%04d", month, day, year),
            };
            assertEquals(expected, profile.extractStatementDate(mmddYYYY, ctx),
                    "Iter " + i + " MM/DD/YYYY: expected " + expected);
            final String[] mmddYY = {
                "Statement Closing Date: " + String.format("%02d/%02d/%02d", month, day, year % 100),
            };
            assertEquals(expected, profile.extractStatementDate(mmddYY, ctx),
                    "Iter " + i + " MM/DD/YY: expected " + expected);
        }
    }
}
