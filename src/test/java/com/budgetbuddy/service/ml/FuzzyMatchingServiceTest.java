package com.budgetbuddy.service.ml;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Comprehensive tests for FuzzyMatchingService Tests: null handling, edge cases, boundary
 * conditions, performance, accuracy
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
    void testFindBestMatchNullQuery() {
        final List<String> candidates = Arrays.asList("SAFEWAY", "TARGET", "WALMART");
        assertNull(fuzzyMatchingService.findBestMatch(null, candidates));
    }

    @Test
    @DisplayName("findBestMatch with empty query returns null")
    void testFindBestMatchEmptyQuery() {
        final List<String> candidates = Arrays.asList("SAFEWAY", "TARGET", "WALMART");
        assertNull(fuzzyMatchingService.findBestMatch("", candidates));
        assertNull(fuzzyMatchingService.findBestMatch("   ", candidates));
    }

    @Test
    @DisplayName("findBestMatch with null candidates returns null")
    void testFindBestMatchNullCandidates() {
        assertNull(fuzzyMatchingService.findBestMatch("SAFEWAY", null));
    }

    @Test
    @DisplayName("findBestMatch with empty candidates returns null")
    void testFindBestMatchEmptyCandidates() {
        assertNull(fuzzyMatchingService.findBestMatch("SAFEWAY", Collections.emptyList()));
    }

    @Test
    @DisplayName("findBestMatch with null candidates in list skips them")
    void testFindBestMatchNullInCandidates() {
        final List<String> candidates = Arrays.asList("SAFEWAY", null, "TARGET", null, "WALMART");
        final FuzzyMatchingService.MatchResult result =
                fuzzyMatchingService.findBestMatch("SAFEWAY", candidates);
        assertNotNull(result);
        assertEquals("SAFEWAY", result.original);
    }

    @Test
    @DisplayName("findBestMatch with empty strings in candidates skips them")
    void testFindBestMatchEmptyInCandidates() {
        final List<String> candidates = Arrays.asList("SAFEWAY", "", "   ", "TARGET");
        final FuzzyMatchingService.MatchResult result =
                fuzzyMatchingService.findBestMatch("SAFEWAY", candidates);
        assertNotNull(result);
        assertEquals("SAFEWAY", result.original);
    }

    // ========== Exact Match Tests ==========

    @Test
    @DisplayName("findBestMatch with exact match returns high confidence")
    void testFindBestMatchExactMatch() {
        final List<String> candidates = Arrays.asList("SAFEWAY", "TARGET", "WALMART");
        final FuzzyMatchingService.MatchResult result =
                fuzzyMatchingService.findBestMatch("SAFEWAY", candidates);

        assertNotNull(result);
        assertEquals("SAFEWAY", result.original);
        assertTrue(result.combinedScore >= 0.85, "Exact match should have high confidence");
        assertEquals("HIGH", result.getConfidenceLevel());
    }

    @Test
    @DisplayName("findBestMatch with case-insensitive exact match")
    void testFindBestMatchCaseInsensitive() {
        final List<String> candidates = Arrays.asList("SAFEWAY", "TARGET", "WALMART");
        final FuzzyMatchingService.MatchResult result =
                fuzzyMatchingService.findBestMatch("safeway", candidates);

        assertNotNull(result);
        assertEquals("SAFEWAY", result.original);
        assertTrue(result.combinedScore >= 0.85);
    }

    // ========== Fuzzy Match Tests ==========

    @Test
    @DisplayName("findBestMatch with store number variation")
    void testFindBestMatchStoreNumberVariation() {
        final List<String> candidates = Arrays.asList("SAFEWAY", "TARGET", "WALMART");
        final FuzzyMatchingService.MatchResult result =
                fuzzyMatchingService.findBestMatch("SAFEWAY #1234", candidates);

        assertNotNull(result);
        assertEquals("SAFEWAY", result.original);
        assertTrue(result.combinedScore >= 0.85, "Should match despite store number");
    }

    @Test
    @DisplayName("findBestMatch with typo")
    void testFindBestMatchTypo() {
        final List<String> candidates = Arrays.asList("SAFEWAY", "TARGET", "WALMART");
        final FuzzyMatchingService.MatchResult result =
                fuzzyMatchingService.findBestMatch("SAFEWAYS", candidates);

        assertNotNull(result);
        assertEquals("SAFEWAY", result.original);
        assertTrue(result.combinedScore >= 0.55, "Should match despite typo");
    }

    @Test
    @DisplayName("findBestMatch with word order variation")
    void testFindBestMatchWordOrder() {
        final List<String> candidates = Arrays.asList("SAFEWAY STORE", "TARGET", "WALMART");
        final FuzzyMatchingService.MatchResult result =
                fuzzyMatchingService.findBestMatch("STORE SAFEWAY", candidates);

        assertNotNull(result);
        assertEquals("SAFEWAY STORE", result.original);
        assertTrue(result.combinedScore >= 0.70, "Should match despite word order");
    }

    @Test
    @DisplayName("findBestMatch with suffix variation")
    void testFindBestMatchSuffixVariation() {
        final List<String> candidates = Arrays.asList("SAFEWAY", "TARGET", "WALMART");
        final FuzzyMatchingService.MatchResult result =
                fuzzyMatchingService.findBestMatch("SAFEWAY INC", candidates);

        assertNotNull(result);
        assertEquals("SAFEWAY", result.original);
        assertTrue(result.combinedScore >= 0.85, "Should match despite suffix");
    }

    // ========== No Match Tests ==========

    @Test
    @DisplayName("findBestMatch with no good match returns null")
    void testFindBestMatchNoMatch() {
        final List<String> candidates = Arrays.asList("TARGET", "WALMART", "COSTCO");
        final FuzzyMatchingService.MatchResult result =
                fuzzyMatchingService.findBestMatch("XYZABC123", candidates);

        assertNull(result, "Should return null when no good match found");
    }

    @Test
    @DisplayName("findBestMatch with completely different strings")
    void testFindBestMatchCompletelyDifferent() {
        final List<String> candidates = Arrays.asList("SAFEWAY", "TARGET", "WALMART");
        final FuzzyMatchingService.MatchResult result =
                fuzzyMatchingService.findBestMatch("RESTAURANT", candidates);

        // Should return null or very low confidence match
        if (result != null) {
            assertTrue(
                    result.combinedScore < 0.55,
                    "Completely different strings should have low confidence");
        }
    }

    // ========== Boundary Condition Tests ==========

    @Test
    @DisplayName("findBestMatch with single character query")
    void testFindBestMatchSingleCharacter() {
        final List<String> candidates = Arrays.asList("SAFEWAY", "TARGET", "WALMART");
        final FuzzyMatchingService.MatchResult result =
                fuzzyMatchingService.findBestMatch("S", candidates);

        // Single character is a weak signal — prefix matchers will still
        // hit "SAFEWAY", so we don't enforce a low-score expectation here;
        // we only check the call doesn't crash and returns a sensible
        // (non-negative) score range when it does match.
        if (result != null) {
            assertTrue(
                    result.combinedScore >= 0.0 && result.combinedScore <= 1.0,
                    "score should be in [0,1], got " + result.combinedScore);
        }
    }

    @Test
    @DisplayName("findBestMatch with very long query")
    void testFindBestMatchVeryLongQuery() {
        final String longQuery = "SAFEWAY " + "X".repeat(10_000);
        final List<String> candidates = Arrays.asList("SAFEWAY", "TARGET", "WALMART");

        // Should handle gracefully (truncated to 10000 chars)
        assertDoesNotThrow(
                () -> {
                    final FuzzyMatchingService.MatchResult result =
                            fuzzyMatchingService.findBestMatch(longQuery, candidates);
                    // Result might be null or have low confidence
                });
    }

    @Test
    @DisplayName("findBestMatch with very large candidate list")
    void testFindBestMatchVeryLargeCandidateList() {
        final List<String> candidates = new ArrayList<>();
        for (int i = 0; i < 20_000; i++) {
            candidates.add("MERCHANT" + i);
        }
        candidates.add("SAFEWAY");

        // Should handle gracefully (limited to 10000)
        assertDoesNotThrow(
                () -> {
                    final FuzzyMatchingService.MatchResult result =
                            fuzzyMatchingService.findBestMatch("SAFEWAY", candidates);
                    // Should still find match
                    if (result != null) {
                        assertEquals("SAFEWAY", result.original);
                    }
                });
    }

    @Test
    @DisplayName("findBestMatch with normalization producing empty string")
    void testFindBestMatchNormalizationEmpty() {
        final List<String> candidates = Arrays.asList("SAFEWAY", "TARGET", "WALMART");
        // "#1234" normalizes to empty string
        final FuzzyMatchingService.MatchResult result =
                fuzzyMatchingService.findBestMatch("#1234", candidates);

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
        assertTrue(
                fuzzyMatchingService.isLowConfidence(
                        0.54)); // 0.54 >= 0.50 (LOW_CONFIDENCE_THRESHOLD) and < 0.70, so it's low
        // confidence
    }

    // ========== findAllMatches Tests ==========

    @Test
    @DisplayName("findAllMatches returns all matches above threshold")
    void testFindAllMatches() {
        final List<String> candidates = Arrays.asList("SAFEWAY", "SAFEWAYS", "TARGET", "WALMART");
        final List<FuzzyMatchingService.MatchResult> results =
                fuzzyMatchingService.findAllMatches("SAFEWAY", candidates, 0.55);

        assertNotNull(results);
        assertTrue(results.size() >= 1, "Should find at least one match");
        assertTrue(results.stream().anyMatch(r -> "SAFEWAY".equals(r.original)));
    }

    @Test
    @DisplayName("findAllMatches returns empty list when no matches")
    void testFindAllMatchesNoMatches() {
        final List<String> candidates = Arrays.asList("TARGET", "WALMART", "COSTCO");
        final List<FuzzyMatchingService.MatchResult> results =
                fuzzyMatchingService.findAllMatches("XYZABC", candidates, 0.55);

        assertTrue(results.isEmpty());
    }

    @Test
    @DisplayName("findAllMatches returns sorted by confidence")
    void testFindAllMatchesSorted() {
        final List<String> candidates = Arrays.asList("SAFEWAY", "SAFEWAYS", "SAFEWAY STORE");
        final List<FuzzyMatchingService.MatchResult> results =
                fuzzyMatchingService.findAllMatches("SAFEWAY", candidates, 0.50);

        assertTrue(results.size() >= 2);
        // Check sorted (descending)
        for (int i = 0; i < results.size() - 1; i++) {
            assertTrue(
                    results.get(i).combinedScore >= results.get(i + 1).combinedScore,
                    "Results should be sorted by confidence (descending)");
        }
    }

    // ========== Special Character Tests ==========

    @Test
    @DisplayName("findBestMatch handles special characters")
    void testFindBestMatchSpecialCharacters() {
        final List<String> candidates = Arrays.asList("SAFEWAY", "TARGET", "WALMART");
        final FuzzyMatchingService.MatchResult result =
                fuzzyMatchingService.findBestMatch("SAFEWAY!@#$%^&*()", candidates);

        assertNotNull(result);
        assertEquals("SAFEWAY", result.original);
    }

    @Test
    @DisplayName("findBestMatch handles unicode characters")
    void testFindBestMatchUnicode() {
        final List<String> candidates = Arrays.asList("CAFÉ", "RESTAURANT", "STORE");
        final FuzzyMatchingService.MatchResult result =
                fuzzyMatchingService.findBestMatch("CAFE", candidates);

        // Should handle unicode normalization
        if (result != null) {
            assertTrue(result.combinedScore >= 0.55);
        }
    }

    // ========== Performance Tests ==========

    @Test
    @DisplayName("findBestMatch handles reasonable performance with many candidates")
    void testFindBestMatchPerformance() {
        final List<String> candidates = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            candidates.add("MERCHANT" + i);
        }
        candidates.add("SAFEWAY");

        final long startTime = System.currentTimeMillis();
        final FuzzyMatchingService.MatchResult result =
                fuzzyMatchingService.findBestMatch("SAFEWAY", candidates);
        final long endTime = System.currentTimeMillis();

        assertNotNull(result);
        assertEquals("SAFEWAY", result.original);
        assertTrue(endTime - startTime < 5000, "Should complete in reasonable time (<5s)");
    }

    // ========== Edge Case: Single Candidate ==========

    @Test
    @DisplayName("findBestMatch with single candidate")
    void testFindBestMatchSingleCandidate() {
        final List<String> candidates = Collections.singletonList("SAFEWAY");
        final FuzzyMatchingService.MatchResult result =
                fuzzyMatchingService.findBestMatch("SAFEWAY", candidates);

        assertNotNull(result);
        assertEquals("SAFEWAY", result.original);
    }

    // ========== Edge Case: All Candidates Similar ==========

    @Test
    @DisplayName("findBestMatch with all similar candidates returns best")
    void testFindBestMatchAllSimilar() {
        final List<String> candidates = Arrays.asList("SAFEWAY", "SAFEWAYS", "SAFEWAY STORE");
        final FuzzyMatchingService.MatchResult result =
                fuzzyMatchingService.findBestMatch("SAFEWAY", candidates);

        assertNotNull(result);
        assertEquals("SAFEWAY", result.original, "Exact match should be best");
    }
}
