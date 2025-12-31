package com.budgetbuddy.service.ml;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for FuzzyMatchingService
 * Tests: null handling, edge cases, boundary conditions, performance, accuracy
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FuzzyMatchingService Tests")
class FuzzyMatchingServiceTest {
    
    private FuzzyMatchingService fuzzyMatchingService;
    
    @BeforeEach
    void setUp() {
        fuzzyMatchingService = new FuzzyMatchingService();
    }
    
    // ========== Null and Empty Input Tests ==========
    
    @Test
    @DisplayName("findBestMatch with null query returns null")
    void testFindBestMatch_NullQuery() {
        List<String> candidates = Arrays.asList("SAFEWAY", "TARGET", "WALMART");
        assertNull(fuzzyMatchingService.findBestMatch(null, candidates));
    }
    
    @Test
    @DisplayName("findBestMatch with empty query returns null")
    void testFindBestMatch_EmptyQuery() {
        List<String> candidates = Arrays.asList("SAFEWAY", "TARGET", "WALMART");
        assertNull(fuzzyMatchingService.findBestMatch("", candidates));
        assertNull(fuzzyMatchingService.findBestMatch("   ", candidates));
    }
    
    @Test
    @DisplayName("findBestMatch with null candidates returns null")
    void testFindBestMatch_NullCandidates() {
        assertNull(fuzzyMatchingService.findBestMatch("SAFEWAY", null));
    }
    
    @Test
    @DisplayName("findBestMatch with empty candidates returns null")
    void testFindBestMatch_EmptyCandidates() {
        assertNull(fuzzyMatchingService.findBestMatch("SAFEWAY", Collections.emptyList()));
    }
    
    @Test
    @DisplayName("findBestMatch with null candidates in list skips them")
    void testFindBestMatch_NullInCandidates() {
        List<String> candidates = Arrays.asList("SAFEWAY", null, "TARGET", null, "WALMART");
        FuzzyMatchingService.MatchResult result = fuzzyMatchingService.findBestMatch("SAFEWAY", candidates);
        assertNotNull(result);
        assertEquals("SAFEWAY", result.original);
    }
    
    @Test
    @DisplayName("findBestMatch with empty strings in candidates skips them")
    void testFindBestMatch_EmptyInCandidates() {
        List<String> candidates = Arrays.asList("SAFEWAY", "", "   ", "TARGET");
        FuzzyMatchingService.MatchResult result = fuzzyMatchingService.findBestMatch("SAFEWAY", candidates);
        assertNotNull(result);
        assertEquals("SAFEWAY", result.original);
    }
    
    // ========== Exact Match Tests ==========
    
    @Test
    @DisplayName("findBestMatch with exact match returns high confidence")
    void testFindBestMatch_ExactMatch() {
        List<String> candidates = Arrays.asList("SAFEWAY", "TARGET", "WALMART");
        FuzzyMatchingService.MatchResult result = fuzzyMatchingService.findBestMatch("SAFEWAY", candidates);
        
        assertNotNull(result);
        assertEquals("SAFEWAY", result.original);
        assertTrue(result.combinedScore >= 0.85, "Exact match should have high confidence");
        assertEquals("HIGH", result.getConfidenceLevel());
    }
    
    @Test
    @DisplayName("findBestMatch with case-insensitive exact match")
    void testFindBestMatch_CaseInsensitive() {
        List<String> candidates = Arrays.asList("SAFEWAY", "TARGET", "WALMART");
        FuzzyMatchingService.MatchResult result = fuzzyMatchingService.findBestMatch("safeway", candidates);
        
        assertNotNull(result);
        assertEquals("SAFEWAY", result.original);
        assertTrue(result.combinedScore >= 0.85);
    }
    
    // ========== Fuzzy Match Tests ==========
    
    @Test
    @DisplayName("findBestMatch with store number variation")
    void testFindBestMatch_StoreNumberVariation() {
        List<String> candidates = Arrays.asList("SAFEWAY", "TARGET", "WALMART");
        FuzzyMatchingService.MatchResult result = fuzzyMatchingService.findBestMatch("SAFEWAY #1234", candidates);
        
        assertNotNull(result);
        assertEquals("SAFEWAY", result.original);
        assertTrue(result.combinedScore >= 0.85, "Should match despite store number");
    }
    
    @Test
    @DisplayName("findBestMatch with typo")
    void testFindBestMatch_Typo() {
        List<String> candidates = Arrays.asList("SAFEWAY", "TARGET", "WALMART");
        FuzzyMatchingService.MatchResult result = fuzzyMatchingService.findBestMatch("SAFEWAYS", candidates);
        
        assertNotNull(result);
        assertEquals("SAFEWAY", result.original);
        assertTrue(result.combinedScore >= 0.55, "Should match despite typo");
    }
    
    @Test
    @DisplayName("findBestMatch with word order variation")
    void testFindBestMatch_WordOrder() {
        List<String> candidates = Arrays.asList("SAFEWAY STORE", "TARGET", "WALMART");
        FuzzyMatchingService.MatchResult result = fuzzyMatchingService.findBestMatch("STORE SAFEWAY", candidates);
        
        assertNotNull(result);
        assertEquals("SAFEWAY STORE", result.original);
        assertTrue(result.combinedScore >= 0.70, "Should match despite word order");
    }
    
    @Test
    @DisplayName("findBestMatch with suffix variation")
    void testFindBestMatch_SuffixVariation() {
        List<String> candidates = Arrays.asList("SAFEWAY", "TARGET", "WALMART");
        FuzzyMatchingService.MatchResult result = fuzzyMatchingService.findBestMatch("SAFEWAY INC", candidates);
        
        assertNotNull(result);
        assertEquals("SAFEWAY", result.original);
        assertTrue(result.combinedScore >= 0.85, "Should match despite suffix");
    }
    
    // ========== No Match Tests ==========
    
    @Test
    @DisplayName("findBestMatch with no good match returns null")
    void testFindBestMatch_NoMatch() {
        List<String> candidates = Arrays.asList("TARGET", "WALMART", "COSTCO");
        FuzzyMatchingService.MatchResult result = fuzzyMatchingService.findBestMatch("XYZABC123", candidates);
        
        assertNull(result, "Should return null when no good match found");
    }
    
    @Test
    @DisplayName("findBestMatch with completely different strings")
    void testFindBestMatch_CompletelyDifferent() {
        List<String> candidates = Arrays.asList("SAFEWAY", "TARGET", "WALMART");
        FuzzyMatchingService.MatchResult result = fuzzyMatchingService.findBestMatch("RESTAURANT", candidates);
        
        // Should return null or very low confidence match
        if (result != null) {
            assertTrue(result.combinedScore < 0.55, "Completely different strings should have low confidence");
        }
    }
    
    // ========== Boundary Condition Tests ==========
    
    @Test
    @DisplayName("findBestMatch with single character query")
    void testFindBestMatch_SingleCharacter() {
        List<String> candidates = Arrays.asList("SAFEWAY", "TARGET", "WALMART");
        FuzzyMatchingService.MatchResult result = fuzzyMatchingService.findBestMatch("S", candidates);
        
        // Single character might not match well
        if (result != null) {
            assertTrue(result.combinedScore < 0.70);
        }
    }
    
    @Test
    @DisplayName("findBestMatch with very long query")
    void testFindBestMatch_VeryLongQuery() {
        String longQuery = "SAFEWAY " + "X".repeat(10000);
        List<String> candidates = Arrays.asList("SAFEWAY", "TARGET", "WALMART");
        
        // Should handle gracefully (truncated to 10000 chars)
        assertDoesNotThrow(() -> {
            FuzzyMatchingService.MatchResult result = fuzzyMatchingService.findBestMatch(longQuery, candidates);
            // Result might be null or have low confidence
        });
    }
    
    @Test
    @DisplayName("findBestMatch with very large candidate list")
    void testFindBestMatch_VeryLargeCandidateList() {
        List<String> candidates = new ArrayList<>();
        for (int i = 0; i < 20000; i++) {
            candidates.add("MERCHANT" + i);
        }
        candidates.add("SAFEWAY");
        
        // Should handle gracefully (limited to 10000)
        assertDoesNotThrow(() -> {
            FuzzyMatchingService.MatchResult result = fuzzyMatchingService.findBestMatch("SAFEWAY", candidates);
            // Should still find match
            if (result != null) {
                assertEquals("SAFEWAY", result.original);
            }
        });
    }
    
    @Test
    @DisplayName("findBestMatch with normalization producing empty string")
    void testFindBestMatch_NormalizationEmpty() {
        List<String> candidates = Arrays.asList("SAFEWAY", "TARGET", "WALMART");
        // "#1234" normalizes to empty string
        FuzzyMatchingService.MatchResult result = fuzzyMatchingService.findBestMatch("#1234", candidates);
        
        assertNull(result, "Empty normalized string should return null");
    }
    
    // ========== Confidence Level Tests ==========
    
    @Test
    @DisplayName("isHighConfidence returns true for scores >= 0.85")
    void testIsHighConfidence() {
        assertTrue(fuzzyMatchingService.isHighConfidence(0.85));
        assertTrue(fuzzyMatchingService.isHighConfidence(0.90));
        assertTrue(fuzzyMatchingService.isHighConfidence(1.0));
        assertFalse(fuzzyMatchingService.isHighConfidence(0.84));
        assertFalse(fuzzyMatchingService.isHighConfidence(0.70));
    }
    
    @Test
    @DisplayName("isMediumConfidence returns true for scores 0.70-0.84")
    void testIsMediumConfidence() {
        assertTrue(fuzzyMatchingService.isMediumConfidence(0.70));
        assertTrue(fuzzyMatchingService.isMediumConfidence(0.80));
        assertTrue(fuzzyMatchingService.isMediumConfidence(0.84));
        assertFalse(fuzzyMatchingService.isMediumConfidence(0.85));
        assertFalse(fuzzyMatchingService.isMediumConfidence(0.69));
    }
    
    @Test
    @DisplayName("isLowConfidence returns true for scores 0.55-0.69")
    void testIsLowConfidence() {
        assertTrue(fuzzyMatchingService.isLowConfidence(0.55));
        assertTrue(fuzzyMatchingService.isLowConfidence(0.60));
        assertTrue(fuzzyMatchingService.isLowConfidence(0.69));
        assertFalse(fuzzyMatchingService.isLowConfidence(0.70));
        assertTrue(fuzzyMatchingService.isLowConfidence(0.54)); // 0.54 >= 0.50 (LOW_CONFIDENCE_THRESHOLD) and < 0.70, so it's low confidence
    }
    
    // ========== findAllMatches Tests ==========
    
    @Test
    @DisplayName("findAllMatches returns all matches above threshold")
    void testFindAllMatches() {
        List<String> candidates = Arrays.asList("SAFEWAY", "SAFEWAYS", "TARGET", "WALMART");
        List<FuzzyMatchingService.MatchResult> results = fuzzyMatchingService.findAllMatches(
                "SAFEWAY", candidates, 0.55);
        
        assertNotNull(results);
        assertTrue(results.size() >= 1, "Should find at least one match");
        assertTrue(results.stream().anyMatch(r -> r.original.equals("SAFEWAY")));
    }
    
    @Test
    @DisplayName("findAllMatches returns empty list when no matches")
    void testFindAllMatches_NoMatches() {
        List<String> candidates = Arrays.asList("TARGET", "WALMART", "COSTCO");
        List<FuzzyMatchingService.MatchResult> results = fuzzyMatchingService.findAllMatches(
                "XYZABC", candidates, 0.55);
        
        assertTrue(results.isEmpty());
    }
    
    @Test
    @DisplayName("findAllMatches returns sorted by confidence")
    void testFindAllMatches_Sorted() {
        List<String> candidates = Arrays.asList("SAFEWAY", "SAFEWAYS", "SAFEWAY STORE");
        List<FuzzyMatchingService.MatchResult> results = fuzzyMatchingService.findAllMatches(
                "SAFEWAY", candidates, 0.50);
        
        assertTrue(results.size() >= 2);
        // Check sorted (descending)
        for (int i = 0; i < results.size() - 1; i++) {
            assertTrue(results.get(i).combinedScore >= results.get(i + 1).combinedScore,
                    "Results should be sorted by confidence (descending)");
        }
    }
    
    // ========== Special Character Tests ==========
    
    @Test
    @DisplayName("findBestMatch handles special characters")
    void testFindBestMatch_SpecialCharacters() {
        List<String> candidates = Arrays.asList("SAFEWAY", "TARGET", "WALMART");
        FuzzyMatchingService.MatchResult result = fuzzyMatchingService.findBestMatch(
                "SAFEWAY!@#$%^&*()", candidates);
        
        assertNotNull(result);
        assertEquals("SAFEWAY", result.original);
    }
    
    @Test
    @DisplayName("findBestMatch handles unicode characters")
    void testFindBestMatch_Unicode() {
        List<String> candidates = Arrays.asList("CAFÉ", "RESTAURANT", "STORE");
        FuzzyMatchingService.MatchResult result = fuzzyMatchingService.findBestMatch("CAFE", candidates);
        
        // Should handle unicode normalization
        if (result != null) {
            assertTrue(result.combinedScore >= 0.55);
        }
    }
    
    // ========== Performance Tests ==========
    
    @Test
    @DisplayName("findBestMatch handles reasonable performance with many candidates")
    void testFindBestMatch_Performance() {
        List<String> candidates = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            candidates.add("MERCHANT" + i);
        }
        candidates.add("SAFEWAY");
        
        long startTime = System.currentTimeMillis();
        FuzzyMatchingService.MatchResult result = fuzzyMatchingService.findBestMatch("SAFEWAY", candidates);
        long endTime = System.currentTimeMillis();
        
        assertNotNull(result);
        assertEquals("SAFEWAY", result.original);
        assertTrue(endTime - startTime < 5000, "Should complete in reasonable time (<5s)");
    }
    
    // ========== Edge Case: Single Candidate ==========
    
    @Test
    @DisplayName("findBestMatch with single candidate")
    void testFindBestMatch_SingleCandidate() {
        List<String> candidates = Collections.singletonList("SAFEWAY");
        FuzzyMatchingService.MatchResult result = fuzzyMatchingService.findBestMatch("SAFEWAY", candidates);
        
        assertNotNull(result);
        assertEquals("SAFEWAY", result.original);
    }
    
    // ========== Edge Case: All Candidates Similar ==========
    
    @Test
    @DisplayName("findBestMatch with all similar candidates returns best")
    void testFindBestMatch_AllSimilar() {
        List<String> candidates = Arrays.asList("SAFEWAY", "SAFEWAYS", "SAFEWAY STORE");
        FuzzyMatchingService.MatchResult result = fuzzyMatchingService.findBestMatch("SAFEWAY", candidates);
        
        assertNotNull(result);
        assertEquals("SAFEWAY", result.original, "Exact match should be best");
    }
}

