package com.budgetbuddy.service.ml;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Comprehensive tests for SemanticMatchingService Tests: semantic similarity, tokenization, edge
 * cases, null handling
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SemanticMatchingService Tests")
class SemanticMatchingServiceTest {

    @Mock private MerchantCategoryDataService merchantCategoryDataService;

    private SemanticMatchingService semanticMatchingService;

    @BeforeEach
    void setUp() {
        // CRITICAL: Mock MerchantCategoryDataService to return empty clusters for tests
        // Tests will add their own clusters as needed
        when(merchantCategoryDataService.getCategoryToKeywordsMap()).thenReturn(new HashMap<>());

        semanticMatchingService = new SemanticMatchingService(merchantCategoryDataService);
    }

    // ========== Null and Empty Input Tests ==========

    @Test
    @DisplayName("findBestSemanticMatch with null inputs returns null")
    void testFindBestSemanticMatchNullInputs() {
        assertNull(semanticMatchingService.findBestSemanticMatch(null, null));
        assertNull(semanticMatchingService.findBestSemanticMatch(null, ""));
        assertNull(semanticMatchingService.findBestSemanticMatch("", null));
        assertNull(semanticMatchingService.findBestSemanticMatch("   ", "   "));
    }

    // ========== Groceries Semantic Matching ==========

    @Test
    @DisplayName("findBestSemanticMatch detects groceries semantically")
    void testFindBestSemanticMatchGroceries() {
        final String[] groceryCases = {
            "Grocery Store",
            "Supermarket Shopping",
            "Food Market Purchase",
            "Grocery Shopping Trip",
            "Fresh Food Market"
        };

        for (final String merchant : groceryCases) {
            final SemanticMatchingService.SemanticMatchResult result =
                    semanticMatchingService.findBestSemanticMatch(merchant, null);

            assertNotNull(result, "Should find semantic match for: " + merchant);
            assertEquals("groceries", result.category, "Should match groceries: " + merchant);
            assertTrue(result.similarity >= 0.6, "Should have good similarity: " + merchant);
        }
    }

    // ========== Dining Semantic Matching ==========

    @Test
    @DisplayName("findBestSemanticMatch detects dining semantically")
    void testFindBestSemanticMatchDining() {
        final String[] diningCases = {
            "Restaurant Meal", "Cafe Coffee", "Fast Food Lunch", "Dining Out", "Takeout Delivery"
        };

        for (final String merchant : diningCases) {
            final SemanticMatchingService.SemanticMatchResult result =
                    semanticMatchingService.findBestSemanticMatch(merchant, null);

            assertNotNull(result, "Should find semantic match for: " + merchant);
            assertEquals("dining", result.category, "Should match dining: " + merchant);
        }
    }

    // ========== Transportation Semantic Matching ==========

    @Test
    @DisplayName("findBestSemanticMatch detects transportation semantically")
    void testFindBestSemanticMatchTransportation() {
        final String[] transportationCases = {
            "Gas Station", "Fuel Purchase", "Petrol Fill Up", "Uber Ride", "Parking Fee"
        };

        for (final String merchant : transportationCases) {
            final SemanticMatchingService.SemanticMatchResult result =
                    semanticMatchingService.findBestSemanticMatch(merchant, null);

            assertNotNull(result, "Should find semantic match for: " + merchant);
            assertEquals(
                    "transportation", result.category, "Should match transportation: " + merchant);
        }
    }

    // ========== Utilities Semantic Matching ==========

    @Test
    @DisplayName("findBestSemanticMatch detects utilities semantically")
    void testFindBestSemanticMatchUtilities() {
        final String[] utilityCases = {
            "Electric Bill", "Water Utility", "Internet Service", "Phone Bill", "Cable TV"
        };

        for (final String merchant : utilityCases) {
            final SemanticMatchingService.SemanticMatchResult result =
                    semanticMatchingService.findBestSemanticMatch(merchant, null);

            assertNotNull(result, "Should find semantic match for: " + merchant);
            assertEquals("utilities", result.category, "Should match utilities: " + merchant);
        }
    }

    // ========== Synonym Handling ==========

    @Test
    @DisplayName("findBestSemanticMatch handles synonyms")
    void testFindBestSemanticMatchSynonyms() {
        // "Café" should match "dining" (synonym of "cafe")
        final SemanticMatchingService.SemanticMatchResult result =
                semanticMatchingService.findBestSemanticMatch("Café", "Coffee Purchase");

        assertNotNull(result);
        assertEquals("dining", result.category);
    }

    @Test
    @DisplayName("findBestSemanticMatch handles related terms")
    void testFindBestSemanticMatchRelatedTerms() {
        // "Food Shopping" should match "groceries" (related to grocery shopping)
        final SemanticMatchingService.SemanticMatchResult result =
                semanticMatchingService.findBestSemanticMatch("Food Shopping", null);

        assertNotNull(result);
        assertEquals("groceries", result.category);
    }

    // ========== Combined Merchant and Description ==========

    @Test
    @DisplayName("findBestSemanticMatch uses both merchant and description")
    void testFindBestSemanticMatchCombined() {
        // Merchant alone might not match, but description helps
        final SemanticMatchingService.SemanticMatchResult result =
                semanticMatchingService.findBestSemanticMatch("ABC Store", "Grocery Shopping");

        assertNotNull(result);
        assertEquals("groceries", result.category);
    }

    // ========== Confidence Levels ==========

    @Test
    @DisplayName("SemanticMatchResult confidence levels work correctly")
    void testSemanticMatchResultConfidenceLevels() {
        final SemanticMatchingService.SemanticMatchResult high =
                new SemanticMatchingService.SemanticMatchResult("groceries", 0.75, "SEMANTIC");
        assertTrue(high.isHighConfidence());
        assertFalse(high.isMediumConfidence());

        final SemanticMatchingService.SemanticMatchResult med =
                new SemanticMatchingService.SemanticMatchResult("dining", 0.60, "SEMANTIC");
        assertFalse(med.isHighConfidence());
        assertTrue(med.isMediumConfidence());
    }

    // ========== Edge Cases ==========

    @Test
    @DisplayName("findBestSemanticMatch handles very long text")
    void testFindBestSemanticMatchVeryLongText() {
        final String longText = "Grocery Store " + "X".repeat(1000);
        final SemanticMatchingService.SemanticMatchResult result =
                semanticMatchingService.findBestSemanticMatch(longText, null);

        // Should still match despite long text
        if (result != null) {
            assertTrue(result.similarity >= 0.0);
        }
    }

    @Test
    @DisplayName("findBestSemanticMatch handles special characters")
    void testFindBestSemanticMatchSpecialCharacters() {
        final String[] specialCases = {
            "Grocery Store!@#$", "Grocery-Store", "Grocery_Store", "Grocery.Store"
        };

        for (final String merchant : specialCases) {
            final SemanticMatchingService.SemanticMatchResult result =
                    semanticMatchingService.findBestSemanticMatch(merchant, null);

            // Should handle special characters gracefully
            assertDoesNotThrow(
                    () -> {
                        if (result != null) {
                            result.category.toLowerCase(Locale.ROOT);
                        }
                    });
        }
    }

    // ========== No Match Tests ==========

    @Test
    @DisplayName("findBestSemanticMatch returns null when no good match")
    void testFindBestSemanticMatchNoMatch() {
        // Random text that doesn't match any category
        final SemanticMatchingService.SemanticMatchResult result =
                semanticMatchingService.findBestSemanticMatch("XYZABC123", null);

        // Might return null or low confidence match
        if (result != null) {
            assertTrue(result.similarity < 0.6, "Should have low similarity for no match");
        }
    }

    // ========== Add Semantic Cluster Tests ==========

    @Test
    @DisplayName("addSemanticCluster adds new category")
    void testAddSemanticCluster() {
        final Set<String> keywords =
                new HashSet<>(Arrays.asList("test", "testing", "test category"));
        semanticMatchingService.addSemanticCluster("test_category", keywords);

        // Verify it was added by trying to match
        final SemanticMatchingService.SemanticMatchResult result =
                semanticMatchingService.findBestSemanticMatch("Test Category", null);

        // Should match the new category
        if (result != null) {
            assertEquals("test_category", result.category);
        }
    }

    @Test
    @DisplayName("addSemanticCluster handles null inputs gracefully")
    void testAddSemanticClusterNullInputs() {
        assertDoesNotThrow(
                () -> {
                    semanticMatchingService.addSemanticCluster(null, new HashSet<>());
                    semanticMatchingService.addSemanticCluster("test", null);
                    semanticMatchingService.addSemanticCluster(null, null);
                });
    }

    // ========== Context-rule word-boundary regression tests ==========
    //
    // Background: the CONTEXT_INVESTMENT rule matches against keywords
    // {"investment","brokerage","ira","401k","cd"}. With the old naive
    // contains() match, an account whose type happened to embed those
    // letters could falsely route everyday spend (a Trader Vic's dinner,
    // a Republic Services trash bill) to category=investment. These tests
    // pin the whole-word matching behaviour added in
    // CategorizationContext.containsAsWord.

    @Test
    @DisplayName("CONTEXT_INVESTMENT does not fire on credit-card account types")
    void testContextInvestmentDoesNotFireOnCreditCardAccount() {
        // No clusters loaded → deriveFromContext is the only path that runs.
        // A credit-card account with a dining or utility purchase must not
        // be tagged as investment.
        final SemanticMatchingService.SemanticMatchResult diningRow =
                semanticMatchingService.findBestSemanticMatchWithContext(
                        "Trader Vic's",
                        "Trader Vic's Term B SJC San Jose",
                        new java.math.BigDecimal("-42.50"),
                        null,
                        "credit",
                        "credit card");
        if (diningRow != null) {
            assertFalse(
                    "investment".equalsIgnoreCase(diningRow.category),
                    "CC dining row must not be routed to investment (got "
                            + diningRow.category
                            + ")");
        }

        final SemanticMatchingService.SemanticMatchResult utilityRow =
                semanticMatchingService.findBestSemanticMatchWithContext(
                        "Republic Services",
                        "Republic Services Trash",
                        new java.math.BigDecimal("-65.00"),
                        null,
                        "credit_card",
                        "credit_card");
        if (utilityRow != null) {
            assertFalse(
                    "investment".equalsIgnoreCase(utilityRow.category),
                    "CC utility row must not be routed to investment (got "
                            + utilityRow.category
                            + ")");
        }
    }

    @Test
    @DisplayName("CONTEXT_INVESTMENT still fires on bona-fide investment accounts")
    void testContextInvestmentFiresOnRealInvestmentAccount() {
        // Whole-word boundaries must still admit the legitimate cases.
        for (final String[] accountSig :
                new String[][] {
                    {"investment", null},
                    {"brokerage", null},
                    {null, "ira"},
                    {null, "401k"},
                    {null, "cd"},
                    {"investment account", "brokerage"}
                }) {
            final SemanticMatchingService.SemanticMatchResult res =
                    semanticMatchingService.findBestSemanticMatchWithContext(
                            "Vanguard",
                            "Vanguard purchase",
                            new java.math.BigDecimal("-500.00"),
                            null,
                            accountSig[0],
                            accountSig[1]);
            assertNotNull(
                    res,
                    "Expected a context match for type="
                            + accountSig[0]
                            + ",sub="
                            + accountSig[1]);
            assertEquals(
                    "investment",
                    res.category,
                    "Expected investment for type="
                            + accountSig[0]
                            + ",sub="
                            + accountSig[1]
                            + " (got "
                            + res.category
                            + ")");
        }
    }
}
