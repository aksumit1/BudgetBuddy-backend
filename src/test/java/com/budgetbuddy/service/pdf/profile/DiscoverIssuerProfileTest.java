package com.budgetbuddy.service.pdf.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.budgetbuddy.service.pdf.profile.IssuerProfile.ExtractionContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * Fixture for a Discover it statement. Synthetic data only — Discover statements I
 * don't have access to in this codebase, so this is the canonical "extension-by-
 * world-knowledge" test pattern. The shape mirrors public Discover statement layouts.
 */
class DiscoverIssuerProfileTest {

    private final DiscoverIssuerProfile profile = new DiscoverIssuerProfile();
    private final ExtractionContext ctx = new ExtractionContext(2026, true);

    private static final String STATEMENT_FIXTURE =
            String.join(
                    "\n",
                    "Discover it Card",
                    "TEST CARDHOLDER",
                    "Cardmember Services www.discover.com",
                    "Account Number Ending in 1234",
                    "Statement Closing Date: 04/30/2026",
                    "Payment Due Date: 05/25/2026",
                    "Minimum Payment Due: $25.00",
                    "New Balance: $456.78",
                    "Previous Balance: $200.00",
                    "Payments and Credits: -$200.00",
                    "Purchases: +$456.78",
                    "Cash Advances: +$0.00",
                    "Fees Charged: +$0.00",
                    "Interest Charged: +$0.00",
                    "Credit Line: $5,000",
                    "Available Credit: $4,543",
                    "Total Cashback Bonus: $42.50",
                    "Total Cashback Bonus YTD: $115.20",
                    "Your AutoPay amount of $456.78 will be deducted on 05/25/2026.",
                    "5% Cashback Bonus on Restaurants this quarter",
                    "Days in Billing Period: 30",
                    "Standard Purchase APR 18.24% (V)");

    @Test
    void discoverProfile_matchesHeader() {
        assertTrue(profile.matches("Discover it Card\nwww.discover.com"));
        assertTrue(profile.matches("Discover Bank Cardmember Services"));
        assertEquals(false, profile.matches("Chase Bank statement\nchase.com"));
    }

    @Test
    void discoverProfile_detectsBrands() {
        assertEquals("discover-it",
                profile.detectBrand("Discover it Card"));
        assertEquals("discover-it-miles",
                profile.detectBrand("Discover it Miles"));
        assertEquals("discover-it-cash-back",
                profile.detectBrand("Discover it Cash Back"));
        assertEquals("discover-it-chrome",
                profile.detectBrand("Discover it Chrome"));
        assertEquals("discover-it-student",
                profile.detectBrand("Discover it Student Cash Back"));
        assertEquals("discover-it-secured",
                profile.detectBrand("Discover it Secured"));
        assertEquals("discover-it-business",
                profile.detectBrand("Discover it Business"));
        assertEquals("nhl-discover-it",
                profile.detectBrand("NHL Discover it Card"));
    }

    @Test
    void discoverProfile_extractsCreditLineAndAvailable() {
        final String[] lines = STATEMENT_FIXTURE.split("\\n");
        assertEquals(0,
                new BigDecimal("5000").compareTo(profile.extractCreditLimit(lines, ctx)),
                "'Credit Line: $X' maps to creditLimit (Discover-specific label)");
        assertEquals(0,
                new BigDecimal("4543")
                        .compareTo(profile.extractAvailableCredit(lines, ctx)),
                "'Available Credit' uses the legacy generic pattern (label is standard)");
    }

    @Test
    void discoverProfile_extractsStatementClosingDate() {
        final String[] lines = STATEMENT_FIXTURE.split("\\n");
        assertEquals(LocalDate.of(2026, 4, 30),
                profile.extractStatementDate(lines, ctx));
    }

    @Test
    void discoverProfile_extractsCashbackBalance_preferringCurrentOverYtd() {
        final String[] lines = STATEMENT_FIXTURE.split("\\n");
        // Current "Total Cashback Bonus" wins over the YTD form — that's the redeemable
        // balance the cardmember can actually spend.
        assertEquals(0,
                new BigDecimal("42.50")
                        .compareTo(profile.extractCashBackBalance(lines, ctx)));
    }

    @Test
    void discoverProfile_fallsBackToYtdWhenCurrentBalanceMissing() {
        final String[] lines = {
            "Total Cashback Bonus YTD: $115.20",
        };
        assertEquals(0,
                new BigDecimal("115.20")
                        .compareTo(profile.extractCashBackBalance(lines, ctx)),
                "YTD form is the fallback when current-balance row isn't printed");
    }

    @Test
    void discoverProfile_extractsAutoPayFromDisclosureSentence() {
        final String[] lines = STATEMENT_FIXTURE.split("\\n");
        assertTrue(profile.extractAutoPayEnabled(lines, ctx),
                "'Your AutoPay amount of $X will be deducted' is the ON marker");
        assertEquals(0,
                new BigDecimal("456.78")
                        .compareTo(profile.extractNextAutoPayAmount(lines, ctx)));
    }

    // ---- Randomized property tests ----

    @Test
    void discoverCreditLine_randomized() {
        final java.util.Random rng = new java.util.Random(0xD15C0_C2EDL);
        for (int i = 0; i < 100; i++) {
            final int limit = 1_000 + rng.nextInt(99_000);
            final String[] lines = {
                "Credit Line: $" + String.format("%,d", limit),
            };
            assertEquals(0, new BigDecimal(limit)
                    .compareTo(profile.extractCreditLimit(lines, ctx)),
                    "Iter " + i + ": expected " + limit);
        }
    }

    @Test
    void discoverCashbackBonus_randomized_currentBalanceForm() {
        final java.util.Random rng = new java.util.Random(0xD15C0_BABEL);
        for (int i = 0; i < 100; i++) {
            final int dollars = rng.nextInt(9_999);
            final int cents = rng.nextInt(100);
            final String amount = String.format("%,d.%02d", dollars, cents);
            final String[] lines = {"Total Cashback Bonus: $" + amount};
            assertEquals(0,
                    new BigDecimal(amount.replace(",", ""))
                            .compareTo(profile.extractCashBackBalance(lines, ctx)),
                    "Iter " + i);
        }
    }

    @Test
    void discoverAutoPayAmount_randomized() {
        final java.util.Random rng = new java.util.Random(0xD15C0_A170L);
        for (int i = 0; i < 100; i++) {
            final int dollars = rng.nextInt(99_999);
            final int cents = rng.nextInt(100);
            final String amount = String.format("%,d.%02d", dollars, cents);
            final String[] lines = {
                "Your AutoPay amount of $" + amount + " will be deducted on 05/25/2026.",
            };
            assertTrue(profile.extractAutoPayEnabled(lines, ctx),
                    "Iter " + i + ": ON must fire");
            assertEquals(0,
                    new BigDecimal(amount.replace(",", ""))
                            .compareTo(profile.extractNextAutoPayAmount(lines, ctx)),
                    "Iter " + i);
        }
    }

    @Test
    void discoverStatementClosingDate_randomized() {
        final java.util.Random rng = new java.util.Random(0xD15C0_DA7EL);
        for (int i = 0; i < 60; i++) {
            final int year = 2020 + rng.nextInt(10);
            final int month = 1 + rng.nextInt(12);
            final int day = 1 + rng.nextInt(28);
            final LocalDate expected = LocalDate.of(year, month, day);
            // MM/DD/YYYY form.
            assertEquals(expected,
                    profile.extractStatementDate(new String[] {
                        "Statement Closing Date: " + String.format("%02d/%02d/%04d",
                                month, day, year),
                    }, ctx),
                    "Iter " + i + " MM/DD/YYYY");
            // Bare "Closing Date" form (older Discover statements).
            assertEquals(expected,
                    profile.extractStatementDate(new String[] {
                        "Closing Date: " + String.format("%02d/%02d/%02d",
                                month, day, year % 100),
                    }, ctx),
                    "Iter " + i + " Closing Date MM/DD/YY");
        }
    }

    @Test
    void discoverProfile_doesNotMatchUnrelatedDiscoverMention() {
        // "Discover" in disclosure prose (e.g. "Discover more at our website") should
        // not match — the header pattern requires "Discover" preceded/followed by a
        // card-related noun.
        assertEquals(false, profile.matches("Visit us at example.com to discover more"));
        assertEquals(false, profile.matches("Discover offers from our partners"));
    }
}
