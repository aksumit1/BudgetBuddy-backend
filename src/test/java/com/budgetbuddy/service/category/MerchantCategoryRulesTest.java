package com.budgetbuddy.service.category;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies the YAML-driven category rules engine behaves as documented:
 * priority ordering, word-boundary matching, contains matching, graceful
 * absence, and case-insensitivity.
 */
class MerchantCategoryRulesTest {

    @Nested
    @DisplayName("Loading + graceful absence")
    class Loading {
        @Test
        @DisplayName("Missing resource → empty engine, never throws")
        void missingResourceIsEmpty() {
            final MerchantCategoryRules engine =
                    new MerchantCategoryRules("does-not-exist.yaml");
            assertEquals(0, engine.size());
            assertNull(engine.match("starbucks", "starbucks"));
        }

        @Test
        @DisplayName("Null path → empty engine")
        void nullPathIsEmpty() {
            final MerchantCategoryRules engine = new MerchantCategoryRules(null);
            assertEquals(0, engine.size());
        }

        @Test
        @DisplayName("Real production rules file loads non-empty")
        void productionRulesLoaded() {
            final MerchantCategoryRules engine =
                    new MerchantCategoryRules("category-rules-v2.yaml");
            assertTrue(engine.size() > 10, "expected at least 10 rules from production YAML");
        }
    }

    @Nested
    @DisplayName("Match dispatch via production rules")
    class Matching {
        private final MerchantCategoryRules engine =
                new MerchantCategoryRules("category-rules-v2.yaml");

        @Test
        @DisplayName("Whole-word dining brand (Starbucks)")
        void starbucksDining() {
            assertEquals("dining",
                    engine.match("aplpay starbucks store 4936 newcastle wa",
                            "aplpay starbucks store newcastle wa"));
        }

        @Test
        @DisplayName("Contains-mode multi-word (Whole Foods)")
        void wholeFoodsGroceries() {
            assertEquals("groceries",
                    engine.match("whole foods market bellevue wa",
                            "whole foods market bellevue wa"));
        }

        @Test
        @DisplayName("Costco Gas wins over generic costco (priority)")
        void costcoGasIsTransportation() {
            assertEquals("transportation",
                    engine.match("costco gas #0110 issaquah wa",
                            "costco gas issaquah wa"));
        }

        @Test
        @DisplayName("Costco Wholesale falls back to groceries")
        void costcoWholesaleIsGroceries() {
            assertEquals("groceries",
                    engine.match("costco whse #0006 tukwila wa",
                            "costco whse tukwila wa"));
        }

        @Test
        @DisplayName("Foreign transaction fee → fees (highest priority)")
        void foreignTxnFee() {
            assertEquals("fees",
                    engine.match("foreign transaction fee .40 trg holdings",
                            "foreign transaction fee trg holdings"));
        }

        @Test
        @DisplayName("Word-boundary 'att' does NOT match inside 'seattle'")
        void seattleNotUtilities() {
            final String r =
                    engine.match("seattle wa city", "seattle wa city");
            assertTrue(r == null || !"utilities".equals(r),
                    "seattle should not resolve to utilities; got: " + r);
        }

        @Test
        @DisplayName("Word-boundary 'ta' does NOT match inside 'starbucks'")
        void starbucksNotGasStation() {
            final String r = engine.match("starbucks #1234", "starbucks");
            assertEquals("dining", r);
        }

        @Test
        @DisplayName("Hotel brand (Hyatt) → travel")
        void hyattTravel() {
            assertEquals("travel",
                    engine.match("hyatt reg lke wshgtn renton wa",
                            "hyatt reg lke wshgtn renton wa"));
        }

        @Test
        @DisplayName("Streaming brand (Spotify) → tech")
        void spotifyTech() {
            assertEquals("tech",
                    engine.match("spotify usa new york ny",
                            "spotify usa new york ny"));
        }

        @Test
        @DisplayName("Insurance brand (Geico) word-boundary → insurance")
        void geicoInsurance() {
            assertEquals("insurance",
                    engine.match("geico auto pay washington",
                            "geico auto pay washington"));
        }

        @Test
        @DisplayName("Charity (Girl Scouts) → charity")
        void girlScoutsCharity() {
            assertEquals("charity",
                    engine.match("girl scouts of the uni ny",
                            "girl scouts of the uni ny"));
        }

        @Test
        @DisplayName("Unknown merchant → null (caller falls back to in-code passes)")
        void unknownReturnsNull() {
            assertNull(
                    engine.match(
                            "mysteryco unknown 12345 anytown az",
                            "mysteryco unknown anytown az"));
        }

        @Test
        @DisplayName("Case-insensitive match")
        void caseInsensitive() {
            assertEquals("dining",
                    engine.match("STARBUCKS COFFEE BELLEVUE WA",
                            "STARBUCKS COFFEE BELLEVUE WA"));
        }

        @Test
        @DisplayName("Null inputs → null result, no crash")
        void nullInputs() {
            assertNull(engine.match(null, null));
        }
    }
}
