package com.budgetbuddy.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Coverage for Chase Freedom / Freedom Unlimited / Freedom Flex rewards formats.
 * These cards have a different rewards layout than the Amazon Visa fixture covers,
 * and one of the lines (the rotating quarterly bonus) is unique to this product
 * family.
 *
 * <p>Three line shapes observed across the 8 real Freedom statements:
 *
 * <ol>
 *   <li>Classic Freedom base: {@code + 1% (1 Pt)/$1 earned on all purchases NN}
 *   <li>Freedom Unlimited variants:
 *       {@code + 1% (1 Pts)/$1 on all purchases NN}  (drops "earned")
 *       {@code + 2%(2 Pts)/$1 addl. on Dining purchases NN}
 *       {@code 4%(4 Pts)/$1 addl on Chase Travel NN}  (no leading "+")
 *   <li>Quarterly rotating bonus:
 *       {@code + Bonus from 1Q 5% cat: Grocery Stores NN}
 *   <li>Next-quarter activation prose:
 *       {@code Get 5% cash back on up to $1,500 in combined purchases in this}
 *       {@code quarter's bonus categories from 4/1/25 - 6/30/25.}
 * </ol>
 *
 * <p>Math invariant: on every real statement we tested,
 * {@code previousPointsBalance + extractedEarned == pointsBalance}. Pin that.
 */
class ChaseFreedomStatementFixtureTest {

    /**
     * Synthetic Freedom Unlimited statement with the multi-tier permanent-category
     * rewards layout (Chase Travel 4% + Dining 2% + Drugstore 2% + base 1%).
     */
    private static final String FREEDOM_UNLIMITED_FIXTURE =
            String.join(
                    "\n",
                    "Cash Access Line $4,000",
                    "Available for Cash $4,000",
                    "Previous points balance 14,084",
                    "4%(4 Pts)/$1 addl on Chase Travel 0",
                    "+ 2%(2 Pts)/$1 addl. on Dining purchases 0",
                    "+ 2%(2 Pts)/$1 addl. on Drugstore purchases 0",
                    "+ 1% (1 Pts)/$1 on all purchases 9",
                    "Total points available for",
                    "redemption 14,093");

    /**
     * Synthetic classic Freedom statement with the rotating quarterly bonus.
     */
    private static final String FREEDOM_QUARTERLY_FIXTURE =
            String.join(
                    "\n",
                    "Previous points balance 9,262",
                    "+ 1% (1 Pt)/$1 earned on all purchases 37",
                    "+ Bonus from 1Q 5% cat: Grocery Stores 148",
                    "Total points available for",
                    "redemption 9,447",
                    "Get 5% cash back on up to $1,500 in combined purchases in",
                    "this quarter s bonus categories from 4/1/25 - 6/30/25.");

    // ============================================================
    //  Earned-this-period sums across all line variants
    // ============================================================

    @Test
    void freedomUnlimited_earnedSumsAllTierLines() {
        final String[] lines = FREEDOM_UNLIMITED_FIXTURE.split("\\n");
        final Long earned = PDFImportService.extractPointsEarnedThisPeriod(lines);
        assertNotNull(earned, "Multi-tier Freedom rewards block must produce a value");
        // 0 (Chase Travel) + 0 (Dining) + 0 (Drugstore) + 9 (all purchases) = 9
        assertEquals(9L, earned.longValue());
    }

    @Test
    void freedomClassic_earnedSumsBaseAndQuarterlyBonus() {
        final String[] lines = FREEDOM_QUARTERLY_FIXTURE.split("\\n");
        final Long earned = PDFImportService.extractPointsEarnedThisPeriod(lines);
        assertNotNull(earned);
        // 37 (base) + 148 (1Q grocery bonus) = 185
        assertEquals(185L, earned.longValue(),
                "Base + quarterly-bonus tiers must sum together");
    }

    @Test
    void mathReconciles_previousPlusEarnedEqualsCurrent_classicFreedom() {
        final String[] lines = FREEDOM_QUARTERLY_FIXTURE.split("\\n");
        final long previous =
                PDFImportService.extractPreviousPointsBalance(lines).longValue();
        final long earned =
                PDFImportService.extractPointsEarnedThisPeriod(lines).longValue();
        final long current =
                PDFImportService.extractPointsBalance(lines).longValue();
        assertEquals(current, previous + earned,
                "previous (9,262) + earned (185) must equal current (9,447)");
    }

    @Test
    void mathReconciles_previousPlusEarnedEqualsCurrent_freedomUnlimited() {
        final String[] lines = FREEDOM_UNLIMITED_FIXTURE.split("\\n");
        final long previous =
                PDFImportService.extractPreviousPointsBalance(lines).longValue();
        final long earned =
                PDFImportService.extractPointsEarnedThisPeriod(lines).longValue();
        final long current =
                PDFImportService.extractPointsBalance(lines).longValue();
        assertEquals(current, previous + earned,
                "previous (14,084) + earned (9) must equal current (14,093)");
    }

    // ============================================================
    //  Reward multipliers — all line variants and bonus suffix
    // ============================================================

    @Test
    void freedomUnlimited_capturesAllPermanentCategoryMultipliers() {
        final String[] lines = FREEDOM_UNLIMITED_FIXTURE.split("\\n");
        final Map<String, BigDecimal> mults =
                PDFImportService.extractRewardMultipliersFromPdf(lines);
        assertEquals(0, new BigDecimal("4").compareTo(mults.get("chase travel")),
                "4% Chase Travel tier must capture (and note: no leading '+' in fixture)");
        assertEquals(0, new BigDecimal("2").compareTo(mults.get("dining purchases")));
        assertEquals(0, new BigDecimal("2").compareTo(mults.get("drugstore purchases")));
        assertEquals(0, new BigDecimal("1").compareTo(mults.get("all purchases")));
        assertEquals(4, mults.size(), "Exactly 4 tiers on this fixture, no extras");
    }

    @Test
    void freedomClassic_capturesBaseAndQuarterlyBonusSeparately() {
        // The rotating-bonus tier is suffixed with " (1q bonus)" so a UI can tell it
        // apart from a permanent multiplier (next quarter the bonus category rotates;
        // we don't want it overwriting a permanent entry).
        final String[] lines = FREEDOM_QUARTERLY_FIXTURE.split("\\n");
        final Map<String, BigDecimal> mults =
                PDFImportService.extractRewardMultipliersFromPdf(lines);
        assertEquals(0, new BigDecimal("1").compareTo(mults.get("all purchases")));
        assertEquals(0, new BigDecimal("5").compareTo(mults.get("grocery stores (1q bonus)")),
                "Quarterly bonus must be suffixed with quarter (so a permanent grocery"
                        + " entry on a different card wouldn't collide)");
    }

    // ============================================================
    //  Current-quarter bonus tier as a structured field
    // ============================================================

    @Test
    void currentQuarterBonus_capturesQuarterRateAndCategory() {
        final String[] lines = FREEDOM_QUARTERLY_FIXTURE.split("\\n");
        final PDFImportService.QuarterlyBonus bonus =
                PDFImportService.extractCurrentQuarterBonus(lines);
        assertNotNull(bonus, "Statement with rotating bonus must produce a value");
        assertEquals("1Q", bonus.quarter());
        assertEquals(0, new BigDecimal("5").compareTo(bonus.rate()));
        assertEquals("Grocery Stores", bonus.category());
    }

    @Test
    void currentQuarterBonus_returnsNull_onFreedomUnlimited_thatHasNoRotatingBonus() {
        // Freedom Unlimited has permanent categories — no quarterly rotation. Must
        // return null, NOT misinterpret a permanent line as a bonus tier.
        final String[] lines = FREEDOM_UNLIMITED_FIXTURE.split("\\n");
        assertNull(PDFImportService.extractCurrentQuarterBonus(lines));
    }

    // ============================================================
    //  Next-quarter activation window
    // ============================================================

    @Test
    void nextQuarterBonus_capturesRateCapAndWindow() {
        final String[] lines = FREEDOM_QUARTERLY_FIXTURE.split("\\n");
        final PDFImportService.NextQuarterBonus next =
                PDFImportService.extractNextQuarterBonus(lines, 2025, true);
        assertNotNull(next);
        assertEquals(0, new BigDecimal("5").compareTo(next.rate()));
        assertEquals(0, new BigDecimal("1500").compareTo(next.capAmount()));
        assertEquals(LocalDate.of(2025, 4, 1), next.windowStart());
        assertEquals(LocalDate.of(2025, 6, 30), next.windowEnd());
    }

    @Test
    void nextQuarterBonus_returnsNull_whenDisclosureNotPresent() {
        final String[] lines = FREEDOM_UNLIMITED_FIXTURE.split("\\n");
        assertNull(PDFImportService.extractNextQuarterBonus(lines, 2025, true));
    }

    // ============================================================
    //  Boundary / null safety
    // ============================================================

    @Test
    void extractors_returnSafeDefaults_forEmptyLines() {
        final String[] empty = new String[0];
        assertNull(PDFImportService.extractPointsEarnedThisPeriod(empty));
        assertTrue(PDFImportService.extractRewardMultipliersFromPdf(empty).isEmpty());
        assertNull(PDFImportService.extractCurrentQuarterBonus(empty));
        assertNull(PDFImportService.extractNextQuarterBonus(empty, 2025, true));
    }

    @Test
    void freedomBaseLine_withoutLeadingPlus_stillCaptures() {
        // Pin the load-bearing optional "+" — the first tier line in Freedom
        // Unlimited statements omits the leading "+". Without the optional "+", we'd
        // miss the highest-rate tier (4% Chase Travel) on every Freedom Unlimited
        // statement.
        final String[] lines = {"4%(4 Pts)/$1 addl on Chase Travel 0"};
        final Map<String, BigDecimal> mults =
                PDFImportService.extractRewardMultipliersFromPdf(lines);
        assertEquals(0, new BigDecimal("4").compareTo(mults.get("chase travel")),
                "First tier without leading '+' must still capture");
    }

    @Test
    void freedomBaseLine_acceptsAllConnectorVariants_earnedOnAndAddlOn() {
        // Three variants of the connector between rate and category. All must parse.
        final String[] cases = {
            "+ 1% (1 Pt)/$1 earned on all purchases 100",  // classic
            "+ 1% (1 Pts)/$1 on all purchases 100",         // drops "earned"
            "+ 2%(2 Pts)/$1 addl. on Dining purchases 100", // "addl."
            "4%(4 Pts)/$1 addl on Chase Travel 100"         // no "+", no "addl."
        };
        for (final String line : cases) {
            final Long earned =
                    PDFImportService.extractPointsEarnedThisPeriod(new String[] {line});
            assertNotNull(earned,
                    "Line variant must produce earned value: '" + line + "'");
            assertEquals(100L, earned.longValue(),
                    "Line '" + line + "' must capture 100 as earned");
        }
    }
}
