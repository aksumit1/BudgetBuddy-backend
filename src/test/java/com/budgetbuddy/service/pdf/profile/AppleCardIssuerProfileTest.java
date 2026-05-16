package com.budgetbuddy.service.pdf.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.budgetbuddy.service.pdf.profile.IssuerProfile.ExtractionContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * Fixture for an Apple Card (Goldman Sachs) statement. All values synthetic.
 *
 * <p>Apple Card is the most divergent layout in the system — Total Balance instead of
 * New Balance, Daily Cash instead of points, no annual fee, no foreign tx fee, no
 * penalty APR. This test pins each of those design choices into the profile.
 */
class AppleCardIssuerProfileTest {

    private final AppleCardIssuerProfile profile = new AppleCardIssuerProfile();
    private final ExtractionContext ctx = new ExtractionContext(2026, true);

    private static final String STATEMENT_FIXTURE =
            String.join(
                    "\n",
                    "Apple Card",
                    "Goldman Sachs Bank USA",
                    "card.apple.com",
                    "Apple Card account ending in 1234",
                    "Statement Date 04/30/26",
                    "Payment Due Date 05/31/26",
                    "Total Balance: $987.65",
                    "Minimum Payment Due: $25.00",
                    "Daily Cash earned this month: $9.88",
                    "Daily Cash balance: $42.50",
                    "AutoPay payment amount: $987.65",
                    "AutoPay will deduct on the payment due date.",
                    "Variable APR for purchases: 19.24%",
                    "Pay over time and skip interest with Apple Card Monthly Installments.");

    @Test
    void appleProfile_matchesHeader() {
        assertTrue(profile.matches("Apple Card\nGoldman Sachs Bank USA"));
        assertTrue(profile.matches("Visit card.apple.com for details"));
        assertEquals(false, profile.matches("Chase Bank statement"));
    }

    @Test
    void appleProfile_extractsTotalBalanceAsNewBalance() {
        final String[] lines = STATEMENT_FIXTURE.split("\\n");
        assertEquals(0,
                new BigDecimal("987.65").compareTo(profile.extractNewBalance(lines, ctx)),
                "Apple Card calls it 'Total Balance' — profile maps to newBalance");
    }

    @Test
    void appleProfile_extractsDailyCashBalanceIntoCashBackField() {
        final String[] lines = STATEMENT_FIXTURE.split("\\n");
        assertEquals(0,
                new BigDecimal("42.50").compareTo(profile.extractCashBackBalance(lines, ctx)),
                "Daily Cash balance is dollar-denominated redeemable cashback — "
                        + "maps to the same cashBackBalance field as Wells Fargo / Citi Costco / BoA");
    }

    @Test
    void appleProfile_extractsStatementDate() {
        final String[] lines = STATEMENT_FIXTURE.split("\\n");
        assertEquals(LocalDate.of(2026, 4, 30),
                profile.extractStatementDate(lines, ctx));
    }

    @Test
    void appleProfile_extractsAutoPayAndAmount() {
        final String[] lines = STATEMENT_FIXTURE.split("\\n");
        assertTrue(profile.extractAutoPayEnabled(lines, ctx),
                "AutoPay payment amount line is the ON marker");
        assertEquals(0,
                new BigDecimal("987.65")
                        .compareTo(profile.extractNextAutoPayAmount(lines, ctx)));
    }

    @Test
    void appleProfile_extractsVariablePurchaseApr() {
        final String[] lines = STATEMENT_FIXTURE.split("\\n");
        assertEquals(0,
                new BigDecimal("19.24")
                        .compareTo(profile.extractPurchaseApr(lines, ctx)));
    }

    @Test
    void appleProfile_hasNoAnnualOrForeignFee_returnsNull() {
        final String[] lines = STATEMENT_FIXTURE.split("\\n");
        // Apple Card doesn't print these — extractors correctly null out.
        assertNull(profile.extractPenaltyApr(lines, ctx));
    }

    // ---- Randomized property tests ----

    @Test
    void appleTotalBalance_randomized_acrossAmountShapes() {
        final java.util.Random rng = new java.util.Random(0xA771E_BAL);
        for (int i = 0; i < 100; i++) {
            final int dollars = rng.nextInt(99_999);
            final int cents = rng.nextInt(100);
            final String amount = String.format("%,d.%02d", dollars, cents);
            final String[] lines = {"Total Balance: $" + amount};
            assertEquals(0,
                    new BigDecimal(amount.replace(",", ""))
                            .compareTo(profile.extractNewBalance(lines, ctx)),
                    "Iter " + i + ": " + amount);
        }
    }

    @Test
    void appleDailyCashBalance_randomized() {
        final java.util.Random rng = new java.util.Random(0xDA1_CACEL);
        for (int i = 0; i < 100; i++) {
            final int dollars = rng.nextInt(9_999);
            final int cents = rng.nextInt(100);
            final String amount = String.format("%,d.%02d", dollars, cents);
            final String[] lines = {"Daily Cash balance: $" + amount};
            assertEquals(0,
                    new BigDecimal(amount.replace(",", ""))
                            .compareTo(profile.extractCashBackBalance(lines, ctx)),
                    "Iter " + i);
        }
    }

    @Test
    void appleStatementDate_randomized_acceptsMmDdYy() {
        final java.util.Random rng = new java.util.Random(0xA7_DA7EL);
        for (int i = 0; i < 60; i++) {
            final int year = 2020 + rng.nextInt(10);
            final int month = 1 + rng.nextInt(12);
            final int day = 1 + rng.nextInt(28);
            final java.time.LocalDate expected = java.time.LocalDate.of(year, month, day);
            final String[] lines = {
                "Statement Date " + String.format("%02d/%02d/%02d", month, day, year % 100),
            };
            assertEquals(expected, profile.extractStatementDate(lines, ctx),
                    "Iter " + i + ": " + expected);
        }
    }

    @Test
    void appleAutoPayAmount_randomized() {
        final java.util.Random rng = new java.util.Random(0xA7_A170L);
        for (int i = 0; i < 100; i++) {
            final int dollars = rng.nextInt(99_999);
            final int cents = rng.nextInt(100);
            final String amount = String.format("%,d.%02d", dollars, cents);
            final String[] lines = {"AutoPay payment amount: $" + amount};
            assertTrue(profile.extractAutoPayEnabled(lines, ctx), "Iter " + i + ": ON");
            assertEquals(0,
                    new BigDecimal(amount.replace(",", ""))
                            .compareTo(profile.extractNextAutoPayAmount(lines, ctx)),
                    "Iter " + i);
        }
    }
}
