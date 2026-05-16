package com.budgetbuddy.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

/**
 * Deep regression tests for the merchant- and description-based category
 * detector in {@link CSVImportService}. Covers:
 *
 * <ul>
 *   <li>Each major category (dining, groceries, tech, travel, transportation,
 *       entertainment, health, education, subscriptions, home improvement,
 *       pet, utilities) with real-world POS strings.
 *   <li>The credit-card-payment short-circuit on an issuer's own statement.
 *   <li>False-positive guards introduced by the word-boundary fix
 *       (SEATTLE / STARBUCKS / HYATT / RESPONSE / DAY 1-CAFE should NOT
 *       resolve to utilities or transportation).
 *   <li>The merchant-name-non-null fallthrough bug — when the strategy
 *       manager has no match, the description-based detector must still
 *       run instead of short-circuiting to OTHER.
 * </ul>
 */
class CategoryDetectionDeepTest {

    private CSVImportService csv;

    @BeforeEach
    void setUp() {
        csv = new CSVImportService(
                Mockito.mock(AccountDetectionService.class),
                Mockito.mock(com.budgetbuddy.service.ml.EnhancedCategoryDetectionService.class),
                null,
                Mockito.mock(
                        com.budgetbuddy.service.category.strategy.CategoryDetectionManager.class));
    }

    /** All call sites use the same shape: credit-card account, expense amount. */
    private String detect(final String description, final String merchantName) {
        return csv.parseCategory(
                null,
                description,
                merchantName,
                new BigDecimal("-25.00"),
                null,
                null,
                null,
                "credit",
                "credit card");
    }

    // ------------------------------------------------------------------
    // Per-category positive coverage
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Dining chains")
    class DiningChains {
        @ParameterizedTest(name = "[{index}] {0} → dining")
        @ValueSource(
                strings = {
                    "AplPay STARBUCKS STORE 4936 NEWCASTLE WA",
                    "STARBUCKS #12345 SEATTLE WA",
                    "SBUX RESERVE BELLEVUE WA",
                    "CHIPOTLE MEX GR ONLINE NEWPORT BEACH CA",
                    "CHIPOTLE MEXICAN GRILL #1234 NEWPORT BEACH CA",
                    "MCDONALD'S F#1234 SEATTLE WA",
                    "MCDONALDS #1234 BELLEVUE WA",
                    "DOMINOS 7027 BELLEVUE WA",
                    "DOMINO'S PIZZA 4567 RENTON WA",
                    "TST*OX BURGER Seattle WA",
                    "SQ *REGENT CAKES & BAK Bellevue WA",
                    "RBL* SOMETHING ELSE",
                    "BURGER KING #4321 BELLEVUE WA",
                    "TACO BELL #999 RENTON WA",
                    "CHICK-FIL-A #543 BELLEVUE WA",
                    "PANERA BREAD #678 BELLEVUE WA",
                    "QDOBA MEXICAN EATS BELLEVUE WA",
                    "BUFFALO WILD WINGS RENTON WA",
                    "SHAKE SHACK BELLEVUE WA",
                    "PIZZA HUT #888 BELLEVUE WA",
                    "DUNKIN' #1234 BOSTON MA",
                    "DUTCH BROS COFFEE BELLEVUE WA",
                    "85C BAKERY CAFE USA BELLEVUE WA",
                    "DAY 1-CAFE QPS SEATTLE WA",
                    "PHO 99 BELLEVUE WA",
                    "MIYABI SUSHI BELLEVUE WA"
                })
        void chainsResolveToDining(final String desc) {
            assertEquals("dining", detect(desc, desc));
        }
    }

    @Nested
    @DisplayName("Groceries / warehouse / convenience")
    class Groceries {
        @ParameterizedTest(name = "[{index}] {0} → groceries")
        @ValueSource(
                strings = {
                    "SAFEWAY #1444 BELLEVUE WA",
                    "SAFEWAY.COM #1444 BELLEVUE WA",
                    "QFC #5822 BELLEVUE WA",
                    "COSTCO WHSE #0110 ISSAQUAH WA",
                    "TRADER JOE S #131 BELLEVUE WA",
                    "WHOLE FOODS MARKET BELLEVUE WA",
                    "KROGER #1234 PORTLAND OR",
                    "FRED MEYER #555 SEATTLE WA",
                    "TOWN & COUNTRY MARKET BELLEVUE WA",
                    "ALDI #234 SEATTLE WA",
                    "PUBLIX #789 ORLANDO FL",
                    "WEGMANS #321 ROCHESTER NY",
                    "H-E-B #999 AUSTIN TX",
                    "7-ELEVEN #4321 SEATTLE WA",
                    "CIRCLE K #234 BELLEVUE WA",
                    "INDIA SUPERMARKET BELLEVUE WA",
                    "INDIA METRO HYPER BELLEVUE WA",
                    "APNA BAZAR BELLEVUE BELLEVUE WA"
                })
        void chainsResolveToGroceries(final String desc) {
            assertEquals("groceries", detect(desc, desc));
        }

        @Test
        @DisplayName("Costco Wholesale (warehouse) is groceries even with WHSE token")
        void costcoWarehouse() {
            assertEquals("groceries", detect("COSTCO WHSE #0006 TUKWILA WA", "COSTCO WHSE #0006"));
        }

        @Test
        @DisplayName("COSTCO GAS is transportation (gas-station detector wins, by design)")
        void costcoGasIsTransportation() {
            assertEquals(
                    "transportation",
                    detect("COSTCO GAS #0110 ISSAQUAH WA", "COSTCO GAS #0110"));
        }
    }

    @Nested
    @DisplayName("Tech / SaaS / streaming")
    class Tech {
        @ParameterizedTest(name = "[{index}] {0} → tech")
        @ValueSource(
                strings = {
                    "NETFLIX SUBSCRIPTION",
                    "SPOTIFY USA NEW YORK NY",
                    "ANTHROPIC SAN FRANCISCO CA",
                    "HUGGINGFACE BROOKLYN NY",
                    "VERCEL DOMAINS COVINA CA",
                    "GITHUB.COM SAN FRANCISCO CA",
                    "ADOBE CREATIVE CLOUD SAN JOSE CA",
                    "CLAUDE.AI SUBSCRIPTION SAN FRANCISCO CA",
                    "GOOGLE *YOUTUBE MUSIC G.CO/HELPPAY# CA",
                    "AplPay BIRD APP* SUBSCRIPTION MIAMI FL",
                    "OPENAI CHATGPT PLUS SAN FRANCISCO CA",
                    "Walmart+ Member BENTONVILLE AR",
                    "AUDIBLE*MEMBERSHIP NEWARK NJ",
                    "AMAZON PRIME VIDEO SEATTLE WA",
                    "DROPBOX*SUBSCRIPTION SAN FRANCISCO CA",
                    "MICROSOFT 365 RENEWAL REDMOND WA",
                    "GOOGLE ONE STORAGE MOUNTAIN VIEW CA",
                    "TESLA SUBSCRIPTION US PALO ALTO CA"
                })
        void techBrandsResolveToTech(final String desc) {
            assertEquals("tech", detect(desc, desc));
        }
    }

    @Nested
    @DisplayName("Travel / hotels")
    class Travel {
        @ParameterizedTest(name = "[{index}] {0} → travel")
        @ValueSource(
                strings = {
                    "ALOFT SAN JOSE CA",
                    "MOUNTAIN VIEW ALOFT MOUNTAIN VIEW CA",
                    "HYATT REG LKE WSHGTN F&B SEARL RENTON WA",
                    "AplPay HYATT REG LKE WSHGTN F&B SEARL RENTON WA",
                    "MARRIOTT BONVOY BETHESDA MD",
                    "COURTYARD BY MARRIOTT BELLEVUE WA",
                    "HAMPTON INN SEATTLE WA",
                    "HILTON HONORS MCLEAN VA",
                    "DOUBLETREE SUITES BELLEVUE WA",
                    "WESTIN BELLEVUE WA",
                    "SHERATON SEATTLE WA",
                    "RITZ-CARLTON SAN FRANCISCO CA",
                    "AIRBNB INC SAN FRANCISCO CA",
                    "EXPEDIA TRAVEL FEE BELLEVUE WA",
                    "BOOKING.COM AMSTERDAM",
                    "DELTA AIR LINES ATLANTA GA",
                    "UNITED AIRLINES CHICAGO IL",
                    "VIRGIN ATLANTIC AIRWAYS NORWALK CT"
                })
        void travelBrandsResolveToTravel(final String desc) {
            assertEquals("travel", detect(desc, desc));
        }
    }

    @Nested
    @DisplayName("Transportation / parking / gas / rideshare")
    class Transportation {
        @ParameterizedTest(name = "[{index}] {0} → transportation")
        @ValueSource(
                strings = {
                    "CHEVRON 0095482/CHEVRON SAN JOSE CA",
                    "SHELL OIL 57444033104 BELLEVUE WA",
                    "76 PRODUCTS BELLEVUE WA",
                    "ARCO #2345 BELLEVUE WA",
                    "EXXON #4567 HOUSTON TX",
                    "BP #789 CHICAGO IL",
                    "MOBIL #234 NEW YORK NY",
                    "TESLA SUPERCHARGER FREMONT CA",
                    "SDOT PAYBYPHONE PARKIN SEATTLE WA",
                    "ACE PARKING 3277 BELLEVUE WA",
                    "UBER TRIP HELP.UBER.COM CA",
                    "LYFT *RIDE BELLEVUE WA"
                })
        void transportationResolves(final String desc) {
            assertEquals("transportation", detect(desc, desc));
        }
    }

    @Nested
    @DisplayName("Entertainment / streaming venues")
    class Entertainment {
        @ParameterizedTest(name = "[{index}] {0} → entertainment")
        @ValueSource(
                strings = {
                    "AMC 2434 FACTORIA 8 BELLEVUE WA",
                    "AMC 9640 ONLINE LEAWOOD KS",
                    "REGAL CINEMAS CROSSR BELLEVUE WA",
                    "AplPay REGAL CINEMAS CROSSR BELLEVUE WA"
                })
        void entertainmentResolves(final String desc) {
            assertEquals("entertainment", detect(desc, desc));
        }
    }

    @Nested
    @DisplayName("Home improvement")
    class HomeImprovement {
        @ParameterizedTest(name = "[{index}] {0} → home improvement")
        @ValueSource(
                strings = {
                    "THE HOME DEPOT #4704 ISSAQUAH WA",
                    "THE HOME DEPOT #4711 BELLEVUE WA",
                    "LOWES #00907* WILKESBORO NC"
                })
        void homeImprovementResolves(final String desc) {
            assertEquals("home improvement", detect(desc, desc));
        }
    }

    @Nested
    @DisplayName("Pet")
    class Pet {
        @ParameterizedTest(name = "[{index}] {0} → pet")
        @ValueSource(
                strings = {
                    "PETSMART # 0374 ISSAQUAH WA",
                    "Pet Supplies Plus 4445 Bellevue WA",
                    "SP FARMERS FETCH BONES JUNCTION CITY OR"
                })
        void petResolves(final String desc) {
            assertEquals("pet", detect(desc, desc));
        }
    }

    // ------------------------------------------------------------------
    // Credit-card payment short-circuit (Step 0)
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Credit-card payment short-circuit (Step 0)")
    class PaymentShortCircuit {
        @ParameterizedTest(name = "[{index}] {0} on credit card → payment")
        @ValueSource(
                strings = {
                    "AUTOPAY 999990000012756RAUTOPAY AUTO-PMT",
                    "AUTOMATIC PAYMENT - THANK YOU",
                    "ELECTRONIC PAYMENT - THANK YOU",
                    "MOBILE PAYMENT - THANK YOU",
                    "PAYMENT - THANK YOU",
                    "ONLINE PAYMENT - THANK YOU"
                })
        void creditCardPaymentRowsResolveToPayment(final String desc) {
            final String cat = csv.parseCategory(
                    null,
                    desc,
                    null,
                    new BigDecimal("-1500.00"),
                    null,
                    null,
                    null,
                    "credit",
                    "credit card");
            assertEquals("payment", cat);
        }
    }

    // ------------------------------------------------------------------
    // Word-boundary false-positive guards
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Word-boundary false positives — bare substring tokens must NOT match")
    class WordBoundaryGuards {
        @Test
        @DisplayName("'att' inside SEATTLE must NOT trigger utilities")
        void seattleNotAtt() {
            assertNotEquals(
                    "utilities", detect("SDOT PAYBYPHONE PARKIN SEATTLE WA", "SDOT PAYBYPHONE"));
        }

        @Test
        @DisplayName("'att' inside HYATT must NOT trigger utilities")
        void hyattNotAtt() {
            assertNotEquals(
                    "utilities",
                    detect("HYATT REG LKE WSHGTN F&B SEARL RENTON WA", "HYATT REG LKE WSHGTN"));
        }

        @Test
        @DisplayName("'ta' inside STARBUCKS must NOT trigger transportation (gas station)")
        void starbucksNotGasStation() {
            assertNotEquals(
                    "transportation",
                    detect("AplPay STARBUCKS STORE 4936 NEWCASTLE WA", "STARBUCKS STORE 4936"));
        }

        @Test
        @DisplayName("DAY 1-CAFE must NOT be utilities (was substring-colliding before fix)")
        void day1CafeIsDining() {
            assertEquals("dining", detect("DAY 1-CAFE QPS SEATTLE WA", "DAY 1-CAFE"));
        }

        @Test
        @DisplayName("'TST*' POS prefix must resolve dining, not utilities")
        void tstPosIsDining() {
            assertEquals("dining", detect("TST*OX BURGER Seattle WA", "TST*OX BURGER"));
        }

        @Test
        @DisplayName("UNIVERSITY DISTRICT PA must NOT be utilities (was substring on 'pa')")
        void universityDistrictNotUtilities() {
            assertNotEquals(
                    "utilities",
                    detect("UNIVERSITY DISTRICT PA SEATTLE WA", "UNIVERSITY DISTRICT PA"));
        }
    }

    // ------------------------------------------------------------------
    // detectCategoryFromMerchantName fallthrough fix
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Merchant-name fallthrough — non-null merchant + no strategy match")
    class MerchantNameFallthrough {
        /**
         * Pre-fix: when merchantName was non-null and the CategoryDetectionManager
         * returned null, detectCategoryFromMerchantName fell back to OTHER and
         * short-circuited the entire pipeline, skipping detectCategoryFromDescription
         * (which holds all the chain-name passes). Result: every transaction with a
         * non-null merchant that wasn't in the strategy manager's narrow list
         * landed in "other".
         *
         * <p>The fix changed that fallback to return null, so the description-based
         * detector runs. These cases exercise that exact path.
         */
        @ParameterizedTest(name = "[{index}] desc + merchant → {1}")
        @CsvSource({
            "'CHIPOTLE MEX GR ONLINE NEWPORT BEACH CA','dining'",
            "'SAFEWAY #1444 BELLEVUE WA','groceries'",
            "'NETFLIX SUBSCRIPTION','tech'",
            "'ALOFT SAN JOSE CA','travel'",
            "'AMC 2434 FACTORIA 8 BELLEVUE WA','entertainment'",
            "'THE HOME DEPOT #4711 BELLEVUE WA','home improvement'"
        })
        void merchantNonNullStillResolves(final String desc, final String expected) {
            assertEquals(expected, detect(desc, desc));
        }
    }

    // ------------------------------------------------------------------
    // containsKeywordAsWord direct unit coverage
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("containsKeywordAsWord helper")
    class WordBoundaryHelper {
        @Test
        @DisplayName("Matches keyword as standalone word")
        void matchesWholeWord() {
            assertTrue(CSVImportService.containsKeywordAsWord("at&t bill payment", "at&t"));
            assertTrue(CSVImportService.containsKeywordAsWord("starbucks coffee", "starbucks"));
            assertTrue(
                    CSVImportService.containsKeywordAsWord("verizon wireless bill", "verizon"));
        }

        @Test
        @DisplayName("Does NOT match keyword embedded inside another word")
        void rejectsSubstringInsideWord() {
            assertTrue(!CSVImportService.containsKeywordAsWord("seattle", "att"));
            assertTrue(!CSVImportService.containsKeywordAsWord("hyatt regency", "att"));
            assertTrue(!CSVImportService.containsKeywordAsWord("starbucks", "ta"));
            assertTrue(!CSVImportService.containsKeywordAsWord("response", "pse"));
        }

        @Test
        @DisplayName("Case-insensitive")
        void caseInsensitive() {
            assertTrue(CSVImportService.containsKeywordAsWord("STARBUCKS COFFEE", "starbucks"));
            assertTrue(CSVImportService.containsKeywordAsWord("starbucks coffee", "STARBUCKS"));
        }

        @Test
        @DisplayName("Handles punctuation boundaries (hyphens, slashes, commas)")
        void handlesPunctuationBoundaries() {
            assertTrue(CSVImportService.containsKeywordAsWord("chick-fil-a #543", "chick-fil-a"));
            assertTrue(CSVImportService.containsKeywordAsWord("chevron/76 station", "chevron"));
        }

        @Test
        @DisplayName("Null/empty inputs are safe")
        void nullEmptyInputsSafe() {
            assertTrue(!CSVImportService.containsKeywordAsWord(null, "starbucks"));
            assertTrue(!CSVImportService.containsKeywordAsWord("starbucks", null));
            assertTrue(!CSVImportService.containsKeywordAsWord("starbucks", ""));
        }
    }
}
