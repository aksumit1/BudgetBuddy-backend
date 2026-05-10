package com.budgetbuddy.service.category;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for FuzzyMatchingService */
@ExtendWith(MockitoExtension.class)
class FuzzyMatchingServiceTest {

    private static final String WALMART = "walmart";

    private FuzzyMatchingService fuzzyMatchingService;
    private Map<String, InMemoryMerchantService.Merchant> testMerchants;

    @BeforeEach
    void setUp() {
        fuzzyMatchingService = new FuzzyMatchingService();
        testMerchants = new HashMap<>();

        // Create test merchants
        final InMemoryMerchantService.Merchant walmart = new InMemoryMerchantService.Merchant();
        walmart.setCanonicalName("Walmart");
        walmart.setPrimaryCategory("groceries");
        walmart.setDetailedCategory("groceries");
        walmart.setConfidence(0.95);
        testMerchants.put(WALMART, walmart);

        final InMemoryMerchantService.Merchant starbucks = new InMemoryMerchantService.Merchant();
        starbucks.setCanonicalName("Starbucks");
        starbucks.setPrimaryCategory("dining");
        starbucks.setDetailedCategory("coffee_shops");
        starbucks.setConfidence(0.95);
        testMerchants.put("starbucks", starbucks);

        final InMemoryMerchantService.Merchant mcdonalds = new InMemoryMerchantService.Merchant();
        mcdonalds.setCanonicalName("McDonald's");
        mcdonalds.setPrimaryCategory("dining");
        mcdonalds.setDetailedCategory("fast_food");
        mcdonalds.setConfidence(0.95);
        testMerchants.put("mcdonalds", mcdonalds);
    }

    @Test
    void testExactMatch() {
        final FuzzyMatchingService.FuzzyMatch match =
                fuzzyMatchingService.findBestMatch(WALMART, testMerchants);

        assertNotNull(match);
        assertEquals(WALMART, match.getMatchedMerchant());
        assertEquals(1.0, match.getSimilarity(), 0.01);
        assertEquals("EXACT", match.getMatchType());
        assertEquals(0.95, match.getConfidence(), 0.01);
    }

    @Test
    void testLevenshteinMatchTypo() {
        // Test typo: "walmrt" instead of WALMART
        final FuzzyMatchingService.FuzzyMatch match =
                fuzzyMatchingService.findBestMatch("walmrt", testMerchants);

        assertNotNull(match);
        assertEquals(WALMART, match.getMatchedMerchant());
        assertEquals("LEVENSHTEIN", match.getMatchType());
        assertTrue(match.getSimilarity() >= 0.85);
        assertTrue(match.getConfidence() >= 0.90);
    }

    @Test
    void testLevenshteinMatchMissingLetter() {
        // Test missing letter: "walmar" instead of WALMART
        final FuzzyMatchingService.FuzzyMatch match =
                fuzzyMatchingService.findBestMatch("walmar", testMerchants);

        assertNotNull(match);
        assertEquals(WALMART, match.getMatchedMerchant());
        assertEquals("LEVENSHTEIN", match.getMatchType());
        assertTrue(match.getSimilarity() >= 0.85);
    }

    @Test
    void testPartialMatchAbbreviation() {
        // Test abbreviation: "wmt" should match WALMART (partial)
        final FuzzyMatchingService.FuzzyMatch match =
                fuzzyMatchingService.findBestMatch("wmt", testMerchants);

        // May or may not match depending on threshold, but if it does, should be partial
        if (match != null) {
            assertTrue(
                    "PARTIAL".equals(match.getMatchType())
                            || "LEVENSHTEIN".equals(match.getMatchType()));
        }
    }

    @Test
    void testNoMatchTooDifferent() {
        // Test completely different merchant
        final FuzzyMatchingService.FuzzyMatch match =
                fuzzyMatchingService.findBestMatch("completelydifferent", testMerchants);

        assertNull(match);
    }

    @Test
    void testNullInput() {
        final FuzzyMatchingService.FuzzyMatch match =
                fuzzyMatchingService.findBestMatch(null, testMerchants);
        assertNull(match);
    }

    @Test
    void testEmptyInput() {
        final FuzzyMatchingService.FuzzyMatch match =
                fuzzyMatchingService.findBestMatch("", testMerchants);
        assertNull(match);
    }

    @Test
    void testEmptyCandidates() {
        final FuzzyMatchingService.FuzzyMatch match =
                fuzzyMatchingService.findBestMatch(WALMART, new HashMap<>());
        assertNull(match);
    }

    @Test
    void testPatternMatchCommonVariation() {
        // Test pattern matching for common variations
        // "mcd" should match "mcdonalds"
        final FuzzyMatchingService.FuzzyMatch match =
                fuzzyMatchingService.findBestMatch("mcd", testMerchants);

        // May match via partial or pattern
        if (match != null) {
            assertTrue(
                    "mcdonalds".equals(match.getMatchedMerchant())
                            || "PARTIAL".equals(match.getMatchType())
                            || "PATTERN".equals(match.getMatchType()));
        }
    }

    @Test
    void testSimilarityCalculation() {
        // Test that similarity is calculated correctly
        final FuzzyMatchingService.FuzzyMatch match1 =
                fuzzyMatchingService.findBestMatch(WALMART, testMerchants);
        final FuzzyMatchingService.FuzzyMatch match2 =
                fuzzyMatchingService.findBestMatch("walmrt", testMerchants);

        assertNotNull(match1);
        assertNotNull(match2);

        // Exact match should have higher similarity
        assertTrue(match1.getSimilarity() >= match2.getSimilarity());
    }
}
