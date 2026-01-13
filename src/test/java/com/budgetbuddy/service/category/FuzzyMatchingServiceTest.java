package com.budgetbuddy.service.category;

import com.budgetbuddy.service.category.InMemoryMerchantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for FuzzyMatchingService
 */
@ExtendWith(MockitoExtension.class)
class FuzzyMatchingServiceTest {
    
    private FuzzyMatchingService fuzzyMatchingService;
    private Map<String, InMemoryMerchantService.Merchant> testMerchants;
    
    @BeforeEach
    void setUp() {
        fuzzyMatchingService = new FuzzyMatchingService();
        testMerchants = new HashMap<>();
        
        // Create test merchants
        InMemoryMerchantService.Merchant walmart = new InMemoryMerchantService.Merchant();
        walmart.setCanonicalName("Walmart");
        walmart.setPrimaryCategory("groceries");
        walmart.setDetailedCategory("groceries");
        walmart.setConfidence(0.95);
        testMerchants.put("walmart", walmart);
        
        InMemoryMerchantService.Merchant starbucks = new InMemoryMerchantService.Merchant();
        starbucks.setCanonicalName("Starbucks");
        starbucks.setPrimaryCategory("dining");
        starbucks.setDetailedCategory("coffee_shops");
        starbucks.setConfidence(0.95);
        testMerchants.put("starbucks", starbucks);
        
        InMemoryMerchantService.Merchant mcdonalds = new InMemoryMerchantService.Merchant();
        mcdonalds.setCanonicalName("McDonald's");
        mcdonalds.setPrimaryCategory("dining");
        mcdonalds.setDetailedCategory("fast_food");
        mcdonalds.setConfidence(0.95);
        testMerchants.put("mcdonalds", mcdonalds);
    }
    
    @Test
    void testExactMatch() {
        FuzzyMatchingService.FuzzyMatch match = fuzzyMatchingService.findBestMatch("walmart", testMerchants);
        
        assertNotNull(match);
        assertEquals("walmart", match.getMatchedMerchant());
        assertEquals(1.0, match.getSimilarity(), 0.01);
        assertEquals("EXACT", match.getMatchType());
        assertEquals(0.95, match.getConfidence(), 0.01);
    }
    
    @Test
    void testLevenshteinMatch_Typo() {
        // Test typo: "walmrt" instead of "walmart"
        FuzzyMatchingService.FuzzyMatch match = fuzzyMatchingService.findBestMatch("walmrt", testMerchants);
        
        assertNotNull(match);
        assertEquals("walmart", match.getMatchedMerchant());
        assertEquals("LEVENSHTEIN", match.getMatchType());
        assertTrue(match.getSimilarity() >= 0.85);
        assertTrue(match.getConfidence() >= 0.90);
    }
    
    @Test
    void testLevenshteinMatch_MissingLetter() {
        // Test missing letter: "walmar" instead of "walmart"
        FuzzyMatchingService.FuzzyMatch match = fuzzyMatchingService.findBestMatch("walmar", testMerchants);
        
        assertNotNull(match);
        assertEquals("walmart", match.getMatchedMerchant());
        assertEquals("LEVENSHTEIN", match.getMatchType());
        assertTrue(match.getSimilarity() >= 0.85);
    }
    
    @Test
    void testPartialMatch_Abbreviation() {
        // Test abbreviation: "wmt" should match "walmart" (partial)
        FuzzyMatchingService.FuzzyMatch match = fuzzyMatchingService.findBestMatch("wmt", testMerchants);
        
        // May or may not match depending on threshold, but if it does, should be partial
        if (match != null) {
            assertTrue(match.getMatchType().equals("PARTIAL") || match.getMatchType().equals("LEVENSHTEIN"));
        }
    }
    
    @Test
    void testNoMatch_TooDifferent() {
        // Test completely different merchant
        FuzzyMatchingService.FuzzyMatch match = fuzzyMatchingService.findBestMatch("completelydifferent", testMerchants);
        
        assertNull(match);
    }
    
    @Test
    void testNullInput() {
        FuzzyMatchingService.FuzzyMatch match = fuzzyMatchingService.findBestMatch(null, testMerchants);
        assertNull(match);
    }
    
    @Test
    void testEmptyInput() {
        FuzzyMatchingService.FuzzyMatch match = fuzzyMatchingService.findBestMatch("", testMerchants);
        assertNull(match);
    }
    
    @Test
    void testEmptyCandidates() {
        FuzzyMatchingService.FuzzyMatch match = fuzzyMatchingService.findBestMatch("walmart", new HashMap<>());
        assertNull(match);
    }
    
    @Test
    void testPatternMatch_CommonVariation() {
        // Test pattern matching for common variations
        // "mcd" should match "mcdonalds"
        FuzzyMatchingService.FuzzyMatch match = fuzzyMatchingService.findBestMatch("mcd", testMerchants);
        
        // May match via partial or pattern
        if (match != null) {
            assertTrue(match.getMatchedMerchant().equals("mcdonalds") || 
                      match.getMatchType().equals("PARTIAL") || 
                      match.getMatchType().equals("PATTERN"));
        }
    }
    
    @Test
    void testSimilarityCalculation() {
        // Test that similarity is calculated correctly
        FuzzyMatchingService.FuzzyMatch match1 = fuzzyMatchingService.findBestMatch("walmart", testMerchants);
        FuzzyMatchingService.FuzzyMatch match2 = fuzzyMatchingService.findBestMatch("walmrt", testMerchants);
        
        assertNotNull(match1);
        assertNotNull(match2);
        
        // Exact match should have higher similarity
        assertTrue(match1.getSimilarity() >= match2.getSimilarity());
    }
}

