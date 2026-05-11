package com.budgetbuddy.service.category;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Fuzzy matching service for merchant name matching
 *
 * <p>Supports: - Levenshtein distance for typos - Partial string matching for abbreviations -
 * Pattern-based matching for variations
 */
// PMD's DataClass fires on Request/Response/Config DTOs by design —
// they're intentionally data-only; behaviour belongs in the controller/service.
@SuppressWarnings({"PMD.DataClass", "PMD.OnlyOneReturn"})
@Service
public class FuzzyMatchingService {

    private static final Logger LOGGER = LoggerFactory.getLogger(FuzzyMatchingService.class);

    // Configuration
    private static final double LEVENSHTEIN_THRESHOLD = 0.85; // 85% similarity
    private static final int MAX_LEVENSHTEIN_DISTANCE = 3; // Max edit distance for short strings
    private static final double PARTIAL_MATCH_THRESHOLD = 0.70; // 70% of string must match
    private static final int MIN_PARTIAL_LENGTH = 4; // Minimum length for partial matching

    /** Fuzzy match result */
    public static class FuzzyMatch {
        private final String matchedMerchant;
        private final double similarity;
        private final String matchType; // "EXACT", "LEVENSHTEIN", "PARTIAL", "PATTERN"
        private final double confidence;

        public FuzzyMatch(
                final String matchedMerchant,
                final double similarity,
                final String matchType,
                final double confidence) {
            this.matchedMerchant = matchedMerchant;
            this.similarity = similarity;
            this.matchType = matchType;
            this.confidence = confidence;
        }

        public String getMatchedMerchant() {
            return matchedMerchant;
        }

        public double getSimilarity() {
            return similarity;
        }

        public String getMatchType() {
            return matchType;
        }

        public double getConfidence() {
            return confidence;
        }
    }

    /**
     * Find best fuzzy match for a merchant name
     *
     * @param query Merchant name to match
     * @param candidates Map of normalized merchant names to merchant objects
     * @return Best match or null if no good match found
     */
    public FuzzyMatch findBestMatch(
            final String query, final Map<String, InMemoryMerchantService.Merchant> candidates) {
        if (query == null || query.isBlank() || candidates == null || candidates.isEmpty()) {
            return null;
        }

        final String normalizedQuery = normalize(query);
        if (normalizedQuery.length() < 2) {
            return null; // Too short for meaningful matching
        }

        final List<FuzzyMatch> matches = new ArrayList<>();

        // Try exact match first (fastest)
        if (candidates.containsKey(normalizedQuery)) {
            return new FuzzyMatch(normalizedQuery, 1.0, "EXACT", 0.95);
        }

        // Try Levenshtein distance matching
        for (final Map.Entry<String, InMemoryMerchantService.Merchant> entry :
                candidates.entrySet()) {
            final String candidate = entry.getKey();

            // Skip if lengths are too different (performance optimization)
            if (Math.abs(normalizedQuery.length() - candidate.length())
                    > MAX_LEVENSHTEIN_DISTANCE * 2) {
                continue;
            }

            final double similarity = calculateSimilarity(normalizedQuery, candidate);

            if (similarity >= LEVENSHTEIN_THRESHOLD) {
                // Calculate confidence based on similarity
                final double confidence =
                        0.90 + (similarity - LEVENSHTEIN_THRESHOLD) * 0.1; // 90-95% confidence
                matches.add(new FuzzyMatch(candidate, similarity, "LEVENSHTEIN", confidence));
            }
        }

        // Try partial matching (for abbreviations)
        if (normalizedQuery.length() >= MIN_PARTIAL_LENGTH) {
            for (final Map.Entry<String, InMemoryMerchantService.Merchant> entry :
                    candidates.entrySet()) {
                final String candidate = entry.getKey();

                final double partialSimilarity =
                        calculatePartialSimilarity(normalizedQuery, candidate);

                if (partialSimilarity >= PARTIAL_MATCH_THRESHOLD) {
                    // Check if this is better than existing matches
                    final boolean isBetter =
                            matches.stream()
                                    .noneMatch(
                                            m ->
                                                    m.getMatchedMerchant().equals(candidate)
                                                            && m.getSimilarity()
                                                                    >= partialSimilarity);

                    if (isBetter) {
                        final double confidence =
                                0.85
                                        + (partialSimilarity - PARTIAL_MATCH_THRESHOLD)
                                                * 0.15; // 85-90% confidence
                        matches.add(
                                new FuzzyMatch(
                                        candidate, partialSimilarity, "PARTIAL", confidence));
                    }
                }
            }
        }

        // Try pattern-based matching (common variations)
        final FuzzyMatch patternMatch = findPatternMatch(normalizedQuery, candidates);
        if (patternMatch != null) {
            matches.add(patternMatch);
        }

        // Return best match
        if (matches.isEmpty()) {
            return null;
        }

        // Sort by similarity (descending), then by confidence
        matches.sort(
                (a, b) -> {
                    final int similarityCompare =
                            Double.compare(b.getSimilarity(), a.getSimilarity());
                    if (similarityCompare != 0) {
                        return similarityCompare;
                    }
                    return Double.compare(b.getConfidence(), a.getConfidence());
                });

        final FuzzyMatch bestMatch = matches.get(0);
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(
                    "Fuzzy match found: '{}' → '{}' (similarity: {:.2f}, type: {}, confidence: {:.2f})",
                    query,
                    bestMatch.getMatchedMerchant(),
                    bestMatch.getSimilarity(),
                    bestMatch.getMatchType(),
                    bestMatch.getConfidence());
        }

        return bestMatch;
    }

    /** Calculate similarity using Levenshtein distance */
    private double calculateSimilarity(final String s1, final String s2) {
        if (s1.equals(s2)) {
            return 1.0;
        }
        if (s1.isEmpty() || s2.isEmpty()) {
            return 0.0;
        }

        final int maxLen = Math.max(s1.length(), s2.length());
        final int distance = levenshteinDistance(s1, s2);

        // Normalize to 0-1 range
        return 1.0 - ((double) distance / maxLen);
    }

    /** Calculate Levenshtein distance (edit distance) */
    private int levenshteinDistance(final String s1, final String s2) {
        final int len1 = s1.length();
        final int len2 = s2.length();

        // Early exit for very different lengths
        if (Math.abs(len1 - len2) > MAX_LEVENSHTEIN_DISTANCE) {
            return MAX_LEVENSHTEIN_DISTANCE + 1; // Too different
        }

        final int[][] dp = new int[len1 + 1][len2 + 1];

        // Initialize base cases
        for (int i = 0; i <= len1; i++) {
            dp[i][0] = i;
        }
        for (int j = 0; j <= len2; j++) {
            dp[0][j] = j;
        }

        // Fill the DP table
        for (int i = 1; i <= len1; i++) {
            for (int j = 1; j <= len2; j++) {
                if (s1.charAt(i - 1) == s2.charAt(j - 1)) {
                    dp[i][j] = dp[i - 1][j - 1];
                } else {
                    dp[i][j] =
                            Math.min(
                                    Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                                    dp[i - 1][j - 1] + 1);
                }
            }
        }

        return dp[len1][len2];
    }

    /**
     * Calculate partial similarity (for abbreviations) Checks if one string is a significant
     * substring of another
     */
    private double calculatePartialSimilarity(final String query, final String candidate) {
        // Check if query is contained in candidate (abbreviation)
        if (candidate.contains(query) && query.length() >= MIN_PARTIAL_LENGTH) {
            return (double) query.length() / candidate.length();
        }

        // Check if candidate is contained in query (expansion)
        if (query.contains(candidate) && candidate.length() >= MIN_PARTIAL_LENGTH) {
            return (double) candidate.length() / query.length();
        }

        // Check for common prefix/suffix
        final int commonPrefix = commonPrefixLength(query, candidate);
        final int commonSuffix = commonSuffixLength(query, candidate);
        final int maxCommon = Math.max(commonPrefix, commonSuffix);

        if (maxCommon >= MIN_PARTIAL_LENGTH) {
            return (double) maxCommon / Math.max(query.length(), candidate.length());
        }

        return 0.0;
    }

    /** Find pattern-based matches (common variations) */
    private FuzzyMatch findPatternMatch(
            final String query, final Map<String, InMemoryMerchantService.Merchant> candidates) {
        // Common patterns to try
        final List<String> patterns = generatePatterns(query);

        for (final String pattern : patterns) {
            for (final Map.Entry<String, InMemoryMerchantService.Merchant> entry :
                    candidates.entrySet()) {
                final String candidate = entry.getKey();

                if (candidate.contains(pattern) || pattern.contains(candidate)) {
                    final double similarity = calculateSimilarity(query, candidate);
                    if (similarity >= 0.75) { // Lower threshold for pattern matching
                        return new FuzzyMatch(
                                candidate, similarity, "PATTERN", 0.88); // 88% confidence
                    }
                }
            }
        }

        return null;
    }

    /** Generate common patterns from a merchant name */
    private List<String> generatePatterns(final String name) {
        final List<String> patterns = new ArrayList<>();

        // Remove common suffixes
        final String[] suffixes = {"inc", "llc", "corp", "ltd", "co", "store", "shop", "market"};
        String base = name;
        for (final String suffix : suffixes) {
            if (base.endsWith(suffix) && base.length() > suffix.length() + 2) {
                base = base.substring(0, base.length() - suffix.length());
                patterns.add(base);
            }
        }

        // Extract first word (for chains like "Walmart Supercenter" → "Walmart")
        if (name.length() > 4) {
            final int spaceIndex = name.indexOf(' ');
            if (spaceIndex > 0 && spaceIndex < name.length() - 1) {
                patterns.add(name.substring(0, spaceIndex));
            }
        }

        return patterns;
    }

    /** Calculate common prefix length */
    private int commonPrefixLength(final String s1, final String s2) {
        final int minLen = Math.min(s1.length(), s2.length());
        for (int i = 0; i < minLen; i++) {
            if (s1.charAt(i) != s2.charAt(i)) {
                return i;
            }
        }
        return minLen;
    }

    /** Calculate common suffix length */
    private int commonSuffixLength(final String s1, final String s2) {
        final int minLen = Math.min(s1.length(), s2.length());
        for (int i = 0; i < minLen; i++) {
            if (s1.charAt(s1.length() - 1 - i) != s2.charAt(s2.length() - 1 - i)) {
                return i;
            }
        }
        return minLen;
    }

    /** Normalize string for matching */
    private String normalize(final String str) {
        if (str == null) {
            return "";
        }
        return str.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]", "")
                .replaceAll("\\s+", "")
                .trim();
    }
}
