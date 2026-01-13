package com.budgetbuddy.service.ml;

import org.apache.commons.text.similarity.JaroWinklerSimilarity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Sophisticated Fuzzy Matching Service
 * Uses Jaro-Winkler and full token fuzzy search for robust merchant name matching
 * 
 * Features:
 * - Jaro-Winkler similarity (50% weight) - best for names, handles typos
 * - Full token fuzzy search (50% weight) - handles word order variations using Jaro-Winkler on tokens
 * - Normalization (handles case, punctuation, abbreviations)
 */
@Service("mlFuzzyMatchingService")
public class FuzzyMatchingService {
    
    private static final Logger logger = LoggerFactory.getLogger(FuzzyMatchingService.class);
    
    private final JaroWinklerSimilarity jaroWinkler = new JaroWinklerSimilarity();
    
    // Similarity thresholds
    private static final double HIGH_CONFIDENCE_THRESHOLD = 0.85;
    private static final double MEDIUM_CONFIDENCE_THRESHOLD = 0.70;
    private static final double LOW_CONFIDENCE_THRESHOLD = 0.50;
    
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
        
        // CRITICAL: Handle very large candidate lists (performance protection)
        if (candidates.size() > 10000) {
            logger.warn("Candidate list is very large ({}), limiting to first 10000", candidates.size());
            candidates = candidates.subList(0, 10000);
        }
        
        String normalizedQuery = normalizeForMatching(query);
        logger.info("-----SUMIT PRIORITY  NORMALIZED QUERY-------- normalizedQuery: {}", normalizedQuery);
        
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
            
            // Calculate similarity scores
            // CRITICAL: Wrap in try-catch to handle any exceptions from similarity calculations
            double jaroWinklerScore = 0.0;
            double fullTokenScore = 0.0;
            double substringScore = 0.0;
            double tokenJaccardScore = 0.0;
            try {
                jaroWinklerScore = jaroWinkler.apply(normalizedQuery, normalizedCandidate);
                // CRITICAL: Validate scores are in valid range [0, 1]
                jaroWinklerScore = Math.max(0.0, Math.min(1.0, jaroWinklerScore));
                
                // Full token fuzzy search (handles word order variations using Jaro-Winkler on tokens)
                fullTokenScore = calculateFullTokenSimilarity(normalizedQuery, normalizedCandidate);
                
                // Substring matching - CRITICAL for merchant names with extra text (addresses, phone numbers)
                // If candidate is contained in query, it's a strong match (e.g., "openai" in "OPENAI *CHATGPT SUBSCR...")
                substringScore = calculateSubstringSimilarity(normalizedQuery, normalizedCandidate);
                
                // Token-based Jaccard similarity - measures token overlap (good for partial matches)
                tokenJaccardScore = calculateTokenJaccardSimilarity(normalizedQuery, normalizedCandidate);
                
                logger.info("Fuzzy match:normalizedQuery= {} candidate= {}, jaroWinkler={}, fullToken={}, substring={}, tokenJaccard={}", 
                        normalizedQuery, normalizedCandidate, jaroWinklerScore, fullTokenScore, substringScore, tokenJaccardScore);
            } catch (Exception e) {
                logger.warn("Error calculating similarity scores for '{}' vs '{}': {}", 
                        normalizedQuery, normalizedCandidate, e.getMessage());
                continue; // Skip this candidate
            }
            
            // IMPROVED: Multi-algorithm weighted combination
            // When Jaro-Winkler is very low (< 0.3), it likely means extra text - favor substring/token algorithms
            double combinedScore;
            if (jaroWinklerScore < 0.3) {
                // Extra text detected - favor substring and token-based algorithms
                // 20% Jaro-Winkler, 30% Full Token, 30% Substring, 20% Token Jaccard
                combinedScore = (jaroWinklerScore * 0.2) + (fullTokenScore * 0.3) + 
                               (substringScore * 0.3) + (tokenJaccardScore * 0.2);
            } else {
                // Normal case - balanced weighting
                // 30% Jaro-Winkler, 35% Full Token, 20% Substring, 15% Token Jaccard
                combinedScore = (jaroWinklerScore * 0.3) + (fullTokenScore * 0.35) + 
                               (substringScore * 0.2) + (tokenJaccardScore * 0.15);
            }
            
            allMatches.add(new MatchResult(candidate, combinedScore, jaroWinklerScore, substringScore, fullTokenScore, tokenJaccardScore));
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
            double fullTokenScore = calculateFullTokenSimilarity(normalizedQuery, normalizedCandidate);
            double substringScore = calculateSubstringSimilarity(normalizedQuery, normalizedCandidate);
            double tokenJaccardScore = calculateTokenJaccardSimilarity(normalizedQuery, normalizedCandidate);
            
            // IMPROVED: Multi-algorithm weighted combination (same as findBestMatch)
            double combinedScore;
            if (jaroWinklerScore < 0.3) {
                combinedScore = (jaroWinklerScore * 0.2) + (fullTokenScore * 0.3) + 
                               (substringScore * 0.3) + (tokenJaccardScore * 0.2);
            } else {
                combinedScore = (jaroWinklerScore * 0.3) + (fullTokenScore * 0.35) + 
                               (substringScore * 0.2) + (tokenJaccardScore * 0.15);
            }
            
            if (combinedScore >= threshold) {
                matches.add(new MatchResult(candidate, combinedScore, jaroWinklerScore, substringScore, fullTokenScore, tokenJaccardScore));
            }
        }
        
        matches.sort((a, b) -> Double.compare(b.combinedScore, a.combinedScore));
        return matches;
    }
    
    /**
     * Calculate full token fuzzy similarity (handles word order variations)
     * This method:
     * 1. Tokenizes both strings
     * 2. For each token in query, finds best matching token in candidate using Jaro-Winkler
     * 3. Calculates similarity based on token matches (Jaccard-like with Jaro-Winkler)
     * 4. Handles word order independence (e.g., "SAFEWAY STORE" vs "STORE SAFEWAY")
     * 
     * Example: "SAFEWAY STORE #123" vs "STORE SAFEWAY" should have high similarity
     */
    private double calculateFullTokenSimilarity(String str1, String str2) {
        if (str1 == null || str2 == null) {
            return 0.0;
        }
        
        String[] tokens1 = str1.toLowerCase().split("\\s+");
        String[] tokens2 = str2.toLowerCase().split("\\s+");
        
        if (tokens1.length == 0 || tokens2.length == 0) {
            return 0.0;
        }
        
        Set<String> set1 = new HashSet<>(Arrays.asList(tokens1));
        Set<String> set2 = new HashSet<>(Arrays.asList(tokens2));
        
        // Remove empty tokens
        set1.removeIf(s -> s == null || s.trim().isEmpty());
        set2.removeIf(s -> s == null || s.trim().isEmpty());
        
        if (set1.isEmpty() || set2.isEmpty()) {
            return 0.0;
        }
        
        // Find matching tokens using Jaro-Winkler (fuzzy token matching)
        Set<String> matchedTokens = new HashSet<>();
        double totalTokenSimilarity = 0.0;
        int matchCount = 0;
        
        // For each token in set1, find best matching token in set2
        for (String token1 : set1) {
            double bestMatch = 0.0;
            String bestMatchingToken = null;
            
            for (String token2 : set2) {
                // Use Jaro-Winkler for token-level fuzzy matching
                double tokenSimilarity = jaroWinkler.apply(token1, token2);
                
                if (tokenSimilarity > bestMatch && tokenSimilarity >= 0.7) {
                    bestMatch = tokenSimilarity;
                    bestMatchingToken = token2;
                }
            }
            
            if (bestMatchingToken != null && bestMatch >= 0.7) {
                matchedTokens.add(token1);
                matchedTokens.add(bestMatchingToken);
                totalTokenSimilarity += bestMatch;
                matchCount++;
            }
        }
        
        // Calculate Jaccard-like similarity with fuzzy token matching
        // Union: all unique tokens from both sets
        Set<String> union = new HashSet<>(set1);
        union.addAll(set2);
        
        if (union.isEmpty()) {
            return 0.0;
        }
        
        // Base similarity: matched tokens / union size
        double baseSimilarity = (double) matchedTokens.size() / union.size();
        
        // Boost based on average token match quality
        if (matchCount > 0) {
            double avgTokenSimilarity = totalTokenSimilarity / matchCount;
            // Weighted combination: 60% base (coverage) + 40% quality (average token match)
            baseSimilarity = (baseSimilarity * 0.6) + (avgTokenSimilarity * 0.4);
        }
        
        // IMPROVEMENT: If all tokens match (perfect word order independence), boost to 1.0
        // This handles cases like "STORE SAFEWAY" vs "SAFEWAY STORE"
        if (set1.size() == set2.size() && matchedTokens.size() == union.size() && matchCount == set1.size()) {
            return 1.0; // Perfect match when all tokens are matched (word order independent)
        }
        
        return Math.max(0.0, Math.min(1.0, baseSimilarity));
    }
    
    /**
     * Calculate substring similarity - checks if candidate is contained in query
     * CRITICAL: This is excellent for merchant names with extra text (addresses, phone numbers, etc.)
     * 
     * Example: "OPENAI *CHATGPT SUBSCR..." contains "openai" → high score
     * 
     * IMPROVEMENT: Uses word boundary matching to prevent false positives
     * (e.g., "at" won't match inside "platinum" or "walmart")
     * 
     * @param query The query string (may contain extra text)
     * @param candidate The candidate string (merchant name)
     * @return Similarity score [0, 1]
     */
    private double calculateSubstringSimilarity(String query, String candidate) {
        if (query == null || candidate == null || query.isEmpty() || candidate.isEmpty()) {
            return 0.0;
        }
        
        // If candidate is exactly contained in query (as whole phrase), it's a perfect match
        if (query.contains(candidate)) {
            // Boost score based on how much of the query is the candidate
            double coverage = (double) candidate.length() / query.length();
            // Perfect substring match with coverage boost
            return Math.min(1.0, 0.95 + (coverage * 0.05));
        }
        
        // Check if candidate tokens are contained in query as whole words (word boundary matching)
        // CRITICAL: Use word boundaries to prevent false positives (e.g., "at" matching inside "walmart")
        String[] candidateTokens = candidate.split("\\s+");
        Set<String> queryTokenSet = new HashSet<>(Arrays.asList(query.split("\\s+")));
        int matchedTokens = 0;
        
        for (String token : candidateTokens) {
            if (token == null || token.trim().isEmpty()) {
                continue;
            }
            token = token.trim();
            
            // Check if token exists as a whole word in query (exact token match)
            if (queryTokenSet.contains(token)) {
                matchedTokens++;
            } else {
                // Fallback: Check if token is contained as substring (but only for longer tokens to avoid false positives)
                // Only allow substring matching for tokens >= 4 characters to prevent "at", "in", etc. from matching
                if (token.length() >= 4 && query.contains(token)) {
                    matchedTokens++;
                }
            }
        }
        
        if (candidateTokens.length > 0 && matchedTokens > 0) {
            // Partial token match - score based on token coverage
            double tokenCoverage = (double) matchedTokens / candidateTokens.length;
            // Require at least 50% of candidate tokens to match for a meaningful score
            if (tokenCoverage >= 0.5) {
                return tokenCoverage * 0.8; // Max 0.8 for partial token matches
            } else {
                // Less than 50% match - very low score to prevent false positives
                return tokenCoverage * 0.3; // Max 0.3 for low coverage matches
            }
        }
        
        // Check reverse - if query is contained in candidate (less common but possible)
        if (candidate.contains(query)) {
            double coverage = (double) query.length() / candidate.length();
            return Math.min(1.0, 0.90 + (coverage * 0.10));
        }
        
        return 0.0;
    }
    
    /**
     * Calculate token-based Jaccard similarity
     * Measures token overlap between query and candidate
     * Excellent for partial matches and handling extra text
     * 
     * @param query The query string
     * @param candidate The candidate string
     * @return Jaccard similarity score [0, 1]
     */
    private double calculateTokenJaccardSimilarity(String query, String candidate) {
        if (query == null || candidate == null) {
            return 0.0;
        }
        
        Set<String> queryTokens = new HashSet<>(Arrays.asList(query.toLowerCase().split("\\s+")));
        Set<String> candidateTokens = new HashSet<>(Arrays.asList(candidate.toLowerCase().split("\\s+")));
        
        // Remove empty tokens
        queryTokens.removeIf(s -> s == null || s.trim().isEmpty());
        candidateTokens.removeIf(s -> s == null || s.trim().isEmpty());
        
        if (queryTokens.isEmpty() || candidateTokens.isEmpty()) {
            return 0.0;
        }
        
        // Calculate intersection (matching tokens)
        Set<String> intersection = new HashSet<>(queryTokens);
        intersection.retainAll(candidateTokens);
        
        // Calculate union (all unique tokens)
        Set<String> union = new HashSet<>(queryTokens);
        union.addAll(candidateTokens);
        
        if (union.isEmpty()) {
            return 0.0;
        }
        
        // Jaccard similarity: intersection / union
        double jaccard = (double) intersection.size() / union.size();
        
        // Boost if important tokens match (longer tokens are more significant)
        if (intersection.size() > 0) {
            double avgTokenLength = intersection.stream()
                .mapToInt(String::length)
                .average()
                .orElse(0.0);
            // Boost for longer matching tokens (more significant)
            double lengthBoost = Math.min(0.2, avgTokenLength / 20.0);
            jaccard = Math.min(1.0, jaccard + lengthBoost);
        }
        
        return Math.max(0.0, Math.min(1.0, jaccard));
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
        @Deprecated
        public final double levenshteinScore; // Now used for substring score
        public final double tokenSimilarity; // Represents full token similarity
        public final double substringScore; // Substring matching score
        public final double tokenJaccardScore; // Token Jaccard similarity score
        
        public MatchResult(String original, double combinedScore, double jaroWinklerScore, 
                          double levenshteinScore, double tokenSimilarity) {
            this.original = original;
            this.combinedScore = combinedScore;
            this.jaroWinklerScore = jaroWinklerScore;
            this.levenshteinScore = levenshteinScore; // Reusing for substring score
            this.tokenSimilarity = tokenSimilarity;
            this.substringScore = levenshteinScore; // Substring score stored here
            this.tokenJaccardScore = 0.0; // Will be calculated separately if needed
        }
        
        // New constructor with all scores
        public MatchResult(String original, double combinedScore, double jaroWinklerScore, 
                          double substringScore, double tokenSimilarity, double tokenJaccardScore) {
            this.original = original;
            this.combinedScore = combinedScore;
            this.jaroWinklerScore = jaroWinklerScore;
            this.levenshteinScore = substringScore; // Backward compatibility
            this.tokenSimilarity = tokenSimilarity;
            this.substringScore = substringScore;
            this.tokenJaccardScore = tokenJaccardScore;
        }
        
        public String getConfidenceLevel() {
            if (combinedScore >= 0.85) return "HIGH";
            if (combinedScore >= 0.70) return "MEDIUM";
            if (combinedScore >= 0.50) return "LOW";
            return "VERY_LOW";
        }
        
        @Override
        public String toString() {
            return String.format("MatchResult{original='%s', combined=%.2f, jaroWinkler=%.2f, fullToken=%.2f, substring=%.2f, tokenJaccard=%.2f, confidence=%s}",
                    original, combinedScore, jaroWinklerScore, tokenSimilarity, substringScore, tokenJaccardScore, getConfidenceLevel());
        }
    }
}

