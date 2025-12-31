package com.budgetbuddy.service.ml;

import org.apache.commons.text.similarity.JaroWinklerSimilarity;
import org.apache.commons.text.similarity.LevenshteinDistance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Sophisticated Fuzzy Matching Service
 * Uses multiple string similarity algorithms for robust merchant name matching
 * 
 * Features:
 * - Jaro-Winkler similarity (best for names, handles typos)
 * - Levenshtein distance (edit distance)
 * - Token-based matching (handles word order variations)
 * - Normalization (handles case, punctuation, abbreviations)
 */
@Service
public class FuzzyMatchingService {
    
    private static final Logger logger = LoggerFactory.getLogger(FuzzyMatchingService.class);
    
    private final JaroWinklerSimilarity jaroWinkler = new JaroWinklerSimilarity();
    private final LevenshteinDistance levenshtein = new LevenshteinDistance();
    
    // Similarity thresholds
    private static final double HIGH_CONFIDENCE_THRESHOLD = 0.85;
    private static final double MEDIUM_CONFIDENCE_THRESHOLD = 0.70;
    private static final double LOW_CONFIDENCE_THRESHOLD = 0.50; // Lowered from 0.55 to 0.50 for better word order matching
    
    /**
     * Find best matching merchant from a list of known merchants
     * Returns match with confidence score
     * 
     * @param query Merchant name to match
     * @param candidates List of known merchant names
     * @return MatchResult with best match and confidence score, or null if no good match
     */
    public MatchResult findBestMatch(String query, List<String> candidates) {
        if (query == null || query.trim().isEmpty() || candidates == null || candidates.isEmpty()) {
            return null;
        }
        
        // CRITICAL: Validate threshold values are reasonable
        if (LOW_CONFIDENCE_THRESHOLD < 0 || LOW_CONFIDENCE_THRESHOLD > 1.0) {
            logger.error("Invalid LOW_CONFIDENCE_THRESHOLD: {}", LOW_CONFIDENCE_THRESHOLD);
            return null;
        }
        
        // CRITICAL: Handle very large candidate lists (performance protection)
        if (candidates.size() > 10000) {
            logger.warn("Candidate list is very large ({}), limiting to first 10000", candidates.size());
            candidates = candidates.subList(0, 10000);
        }
        
        String normalizedQuery = normalizeForMatching(query);
        
        // CRITICAL: If normalization resulted in empty string, can't match
        if (normalizedQuery.isEmpty()) {
            return null;
        }
        
        List<MatchResult> allMatches = new ArrayList<>();
        
        for (String candidate : candidates) {
            if (candidate == null || candidate.trim().isEmpty()) {
                continue;
            }
            
            String normalizedCandidate = normalizeForMatching(candidate);
            
            // Calculate multiple similarity scores
            // CRITICAL: Wrap in try-catch to handle any exceptions from similarity calculations
            double jaroWinklerScore = 0.0;
            double levenshteinScore = 0.0;
            try {
                jaroWinklerScore = jaroWinkler.apply(normalizedQuery, normalizedCandidate);
                int levenshteinDistance = levenshtein.apply(normalizedQuery, normalizedCandidate);
                double maxLength = Math.max(normalizedQuery.length(), normalizedCandidate.length());
                levenshteinScore = maxLength > 0 ? 1.0 - (levenshteinDistance / maxLength) : 0.0;
                
                // CRITICAL: Validate scores are in valid range [0, 1]
                jaroWinklerScore = Math.max(0.0, Math.min(1.0, jaroWinklerScore));
                levenshteinScore = Math.max(0.0, Math.min(1.0, levenshteinScore));
            } catch (Exception e) {
                logger.warn("Error calculating similarity scores for '{}' vs '{}': {}", 
                        normalizedQuery, normalizedCandidate, e.getMessage());
                continue; // Skip this candidate
            }
            
            // Token-based similarity (handles word order variations)
            double tokenSimilarity = calculateTokenSimilarity(normalizedQuery, normalizedCandidate);
            
            // Weighted combination of scores
            // Jaro-Winkler: 40% (best for names)
            // Levenshtein: 30% (edit distance)
            // Token: 30% (word order independence)
            double combinedScore = (jaroWinklerScore * 0.4) + (levenshteinScore * 0.3) + (tokenSimilarity * 0.3);
            
            // IMPROVEMENT: If token similarity is perfect (1.0), boost combined score
            // This handles word order variations like "STORE SAFEWAY" vs "SAFEWAY STORE"
            if (tokenSimilarity >= 1.0) {
                // Boost to ensure it passes the threshold (minimum 0.70)
                combinedScore = Math.max(combinedScore, 0.75); // Ensure at least 0.75 for perfect token match
            }
            
            allMatches.add(new MatchResult(candidate, combinedScore, jaroWinklerScore, levenshteinScore, tokenSimilarity));
        }
        
        // Sort by combined score (descending)
        allMatches.sort((a, b) -> Double.compare(b.combinedScore, a.combinedScore));
        
        if (allMatches.isEmpty()) {
            return null;
        }
        
        MatchResult bestMatch = allMatches.get(0);
        
        // Only return matches above low confidence threshold
        if (bestMatch.combinedScore >= LOW_CONFIDENCE_THRESHOLD) {
            return bestMatch;
        }
        
        return null;
    }
    
    /**
     * Find all matches above a threshold, ranked by confidence
     * 
     * @param query Merchant name to match
     * @param candidates List of known merchant names
     * @param threshold Minimum confidence threshold
     * @return List of matches sorted by confidence (descending)
     */
    public List<MatchResult> findAllMatches(String query, List<String> candidates, double threshold) {
        if (query == null || query.trim().isEmpty() || candidates == null || candidates.isEmpty()) {
            return Collections.emptyList();
        }
        
        String normalizedQuery = normalizeForMatching(query);
        List<MatchResult> matches = new ArrayList<>();
        
        for (String candidate : candidates) {
            if (candidate == null || candidate.trim().isEmpty()) {
                continue;
            }
            
            String normalizedCandidate = normalizeForMatching(candidate);
            
            double jaroWinklerScore = jaroWinkler.apply(normalizedQuery, normalizedCandidate);
            int levenshteinDistance = levenshtein.apply(normalizedQuery, normalizedCandidate);
            double maxLength = Math.max(normalizedQuery.length(), normalizedCandidate.length());
            double levenshteinScore = maxLength > 0 ? 1.0 - (levenshteinDistance / maxLength) : 0.0;
            double tokenSimilarity = calculateTokenSimilarity(normalizedQuery, normalizedCandidate);
            
            double combinedScore = (jaroWinklerScore * 0.4) + (levenshteinScore * 0.3) + (tokenSimilarity * 0.3);
            
            if (combinedScore >= threshold) {
                matches.add(new MatchResult(candidate, combinedScore, jaroWinklerScore, levenshteinScore, tokenSimilarity));
            }
        }
        
        matches.sort((a, b) -> Double.compare(b.combinedScore, a.combinedScore));
        return matches;
    }
    
    /**
     * Calculate token-based similarity (handles word order variations)
     * Example: "SAFEWAY STORE #123" vs "STORE SAFEWAY" should have high similarity
     * IMPROVED: Boost similarity when all tokens match (perfect word order independence)
     */
    private double calculateTokenSimilarity(String str1, String str2) {
        if (str1 == null || str2 == null) {
            return 0.0;
        }
        
        String[] tokens1 = str1.toLowerCase().split("\\s+");
        String[] tokens2 = str2.toLowerCase().split("\\s+");
        
        Set<String> set1 = new HashSet<>(Arrays.asList(tokens1));
        Set<String> set2 = new HashSet<>(Arrays.asList(tokens2));
        
        // Jaccard similarity (intersection over union)
        Set<String> intersection = new HashSet<>(set1);
        intersection.retainAll(set2);
        
        Set<String> union = new HashSet<>(set1);
        union.addAll(set2);
        
        if (union.isEmpty()) {
            return 0.0;
        }
        
        double jaccard = (double) intersection.size() / union.size();
        
        // IMPROVEMENT: If all tokens match (perfect word order independence), boost to 1.0
        // This handles cases like "STORE SAFEWAY" vs "SAFEWAY STORE"
        if (intersection.size() == set1.size() && intersection.size() == set2.size() && 
            set1.size() == set2.size() && set1.size() > 0) {
            return 1.0; // Perfect match when all tokens are the same (word order independent)
        }
        
        return jaccard;
    }
    
    /**
     * Normalize string for matching
     * - Convert to lowercase
     * - Remove special characters
     * - Remove common prefixes/suffixes
     * - Normalize abbreviations
     */
    private String normalizeForMatching(String input) {
        if (input == null) {
            return "";
        }
        
        // CRITICAL: Handle very long strings (performance protection)
        if (input.length() > 10000) {
            logger.warn("Input string is very long ({} chars), truncating to 10000", input.length());
            input = input.substring(0, 10000);
        }
        
        String normalized = input.trim().toLowerCase();
        
        // CRITICAL: If normalization results in empty string, return original (normalized)
        if (normalized.isEmpty()) {
            return "";
        }
        
        // Remove common prefixes
        normalized = normalized.replaceAll("^(the|a|an)\\s+", "");
        
        // Remove store numbers and IDs
        normalized = normalized.replaceAll("\\s*#\\d+", "");
        normalized = normalized.replaceAll("\\s*store\\s*#?\\d+", "");
        normalized = normalized.replaceAll("\\s*loc\\s*#?\\d+", "");
        normalized = normalized.replaceAll("\\s*\\d{4,}", ""); // Remove long numeric IDs
        
        // Remove common suffixes
        normalized = normalized.replaceAll("\\s+(inc|llc|corp|ltd|limited|company|co)\\.?$", "");
        normalized = normalized.replaceAll("\\s+\\.com$", "");
        normalized = normalized.replaceAll("\\s+\\.co\\.uk$", "");
        
        // Normalize common abbreviations
        normalized = normalized.replaceAll("\\b(\\w)\\.(\\w)\\.", "$1$2"); // "S.A." -> "SA"
        normalized = normalized.replaceAll("\\s+", " "); // Multiple spaces to single
        
        // Remove punctuation (except spaces)
        normalized = normalized.replaceAll("[^a-z0-9\\s]", "");
        
        String result = normalized.trim();
        
        // CRITICAL: If normalization resulted in empty string, return a placeholder
        // This prevents matching issues with strings like "#1234"
        if (result.isEmpty()) {
            return "";
        }
        
        return result;
    }
    
    /**
     * Check if similarity score indicates high confidence
     */
    public boolean isHighConfidence(double score) {
        return score >= HIGH_CONFIDENCE_THRESHOLD;
    }
    
    /**
     * Check if similarity score indicates medium confidence
     */
    public boolean isMediumConfidence(double score) {
        return score >= MEDIUM_CONFIDENCE_THRESHOLD && score < HIGH_CONFIDENCE_THRESHOLD;
    }
    
    /**
     * Check if similarity score indicates low confidence
     */
    public boolean isLowConfidence(double score) {
        return score >= LOW_CONFIDENCE_THRESHOLD && score < MEDIUM_CONFIDENCE_THRESHOLD;
    }
    
    /**
     * Match result with confidence scores
     */
    public static class MatchResult {
        public final String original;
        public final double combinedScore;
        public final double jaroWinklerScore;
        public final double levenshteinScore;
        public final double tokenSimilarity;
        
        public MatchResult(String original, double combinedScore, double jaroWinklerScore, 
                          double levenshteinScore, double tokenSimilarity) {
            this.original = original;
            this.combinedScore = combinedScore;
            this.jaroWinklerScore = jaroWinklerScore;
            this.levenshteinScore = levenshteinScore;
            this.tokenSimilarity = tokenSimilarity;
        }
        
        public String getConfidenceLevel() {
            if (combinedScore >= 0.85) return "HIGH";
            if (combinedScore >= 0.70) return "MEDIUM";
            if (combinedScore >= 0.55) return "LOW";
            return "VERY_LOW";
        }
        
        @Override
        public String toString() {
            return String.format("MatchResult{original='%s', combined=%.2f, jaroWinkler=%.2f, levenshtein=%.2f, token=%.2f, confidence=%s}",
                    original, combinedScore, jaroWinklerScore, levenshteinScore, tokenSimilarity, getConfidenceLevel());
        }
    }
}

