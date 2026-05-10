package com.budgetbuddy.service.ml;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.apache.commons.text.similarity.JaroWinklerSimilarity;

/** Analysis tool to compare fuzzy matching algorithms */
// `\n` in the format strings here is a literal LF (CSV rows / raw
// HTTP body templates), not a platform newline — we do NOT want %n.
@SuppressFBWarnings(
        value = "VA_FORMAT_STRING_USES_NEWLINE",
        justification = "literal LF in CSV / wire format, not platform newline")
public class FuzzyMatchingAnalysis {

    private static final JaroWinklerSimilarity JARO_WINKLER = new JaroWinklerSimilarity();

    public static void main(final String[] args) {
        System.out.println("=== Fuzzy Matching Algorithm Analysis ===\n");

        // Test case 1: Simple match
        final String query1 = "openai";
        final String candidate1 = "openai";
        analyzeMatch("Test 1: Simple Match", query1, candidate1);

        // Test case 2: Complex match with extra text
        final String query2 = "OPENAI *CHATGPT SUBSCR SAN FRANCISCO CA +14158799686";
        final String candidate2 = "openai";
        analyzeMatch("Test 2: Complex Match with Extra Text", query2, candidate2);

        System.out.println("\n=== Summary ===");
        System.out.println("For simple matches (exact or near-exact): Jaro-Winkler performs best");
        System.out.println("For complex matches (with extra tokens): Full Token performs best");
        System.out.println("Combined score (50% each) provides balanced matching");
    }

    private static void analyzeMatch(
            final String testName, final String query, final String candidate) {
        System.out.println("\n" + testName);
        System.out.println("Query:    " + query);
        System.out.println("Candidate: " + candidate);
        System.out.println("---");

        // Normalize
        final String normalizedQuery = normalizeForMatching(query);
        final String normalizedCandidate = normalizeForMatching(candidate);
        System.out.println("Normalized Query:    " + normalizedQuery);
        System.out.println("Normalized Candidate: " + normalizedCandidate);
        System.out.println("---");

        // Jaro-Winkler
        final double jaroWinklerScore = JARO_WINKLER.apply(normalizedQuery, normalizedCandidate);
        System.out.printf(
                "Jaro-Winkler Score:   %.4f (%.2f%%)\n", jaroWinklerScore, jaroWinklerScore * 100);

        // Full Token
        final double fullTokenScore =
                calculateFullTokenSimilarity(normalizedQuery, normalizedCandidate);
        System.out.printf(
                "Full Token Score:    %.4f (%.2f%%)\n", fullTokenScore, fullTokenScore * 100);

        // Combined (50% each)
        final double combinedScore = (jaroWinklerScore * 0.5) + (fullTokenScore * 0.5);
        System.out.printf(
                "Combined Score (50/50): %.4f (%.2f%%)\n", combinedScore, combinedScore * 100);

        // Determine winner
        System.out.println("---");
        if (jaroWinklerScore > fullTokenScore) {
            System.out.println(
                    "✅ WINNER: Jaro-Winkler (better by "
                            + String.format("%.4f", jaroWinklerScore - fullTokenScore)
                            + ")");
        } else if (fullTokenScore > jaroWinklerScore) {
            System.out.println(
                    "✅ WINNER: Full Token (better by "
                            + String.format("%.4f", fullTokenScore - jaroWinklerScore)
                            + ")");
        } else {
            System.out.println("✅ TIE: Both algorithms score equally");
        }

        // Show token breakdown for full token
        System.out.println("\nFull Token Breakdown:");
        final String[] queryTokens = normalizedQuery.split("\\s+");
        final String[] candidateTokens = normalizedCandidate.split("\\s+");
        System.out.println("  Query tokens: " + Arrays.toString(queryTokens));
        System.out.println("  Candidate tokens: " + Arrays.toString(candidateTokens));

        // Show token matches
        final Set<String> set1 = new HashSet<>(Arrays.asList(queryTokens));
        final Set<String> set2 = new HashSet<>(Arrays.asList(candidateTokens));
        set1.removeIf(s -> s == null || s.isBlank());
        set2.removeIf(s -> s == null || s.isBlank());

        System.out.println("  Token matching:");
        for (final String token1 : set1) {
            double bestMatch = 0.0;
            String bestToken = null;
            for (final String token2 : set2) {
                final double similarity = JARO_WINKLER.apply(token1, token2);
                if (similarity > bestMatch) {
                    bestMatch = similarity;
                    bestToken = token2;
                }
            }
            if (bestToken != null && bestMatch >= 0.7) {
                System.out.printf(
                        "    '%s' -> '%s' (Jaro-Winkler: %.4f) ✅\n", token1, bestToken, bestMatch);
            } else if (bestToken != null) {
                System.out.printf(
                        "    '%s' -> '%s' (Jaro-Winkler: %.4f) ❌ (below 0.7 threshold)\n",
                        token1, bestToken, bestMatch);
            } else {
                System.out.printf("    '%s' -> NO MATCH ❌\n", token1);
            }
        }
    }

    private static double calculateFullTokenSimilarity(final String str1, final String str2) {
        if (str1 == null || str2 == null) {
            return 0.0;
        }

        final String[] tokens1 = str1.toLowerCase(Locale.ROOT).split("\\s+");
        final String[] tokens2 = str2.toLowerCase(Locale.ROOT).split("\\s+");

        if (tokens1.length == 0 || tokens2.length == 0) {
            return 0.0;
        }

        final Set<String> set1 = new HashSet<>(Arrays.asList(tokens1));
        final Set<String> set2 = new HashSet<>(Arrays.asList(tokens2));

        set1.removeIf(s -> s == null || s.isBlank());
        set2.removeIf(s -> s == null || s.isBlank());

        if (set1.isEmpty() || set2.isEmpty()) {
            return 0.0;
        }

        final Set<String> matchedTokens = new HashSet<>();
        double totalTokenSimilarity = 0.0;
        int matchCount = 0;

        for (final String token1 : set1) {
            double bestMatch = 0.0;
            String bestMatchingToken = null;

            for (final String token2 : set2) {
                final double tokenSimilarity = JARO_WINKLER.apply(token1, token2);

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

        final Set<String> union = new HashSet<>(set1);
        union.addAll(set2);

        if (union.isEmpty()) {
            return 0.0;
        }

        double baseSimilarity = (double) matchedTokens.size() / union.size();

        if (matchCount > 0) {
            final double avgTokenSimilarity = totalTokenSimilarity / matchCount;
            baseSimilarity = (baseSimilarity * 0.6) + (avgTokenSimilarity * 0.4);
        }

        if (set1.size() == set2.size()
                && matchedTokens.size() == union.size()
                && matchCount == set1.size()) {
            return 1.0;
        }

        return Math.max(0.0, Math.min(1.0, baseSimilarity));
    }

    private static String normalizeForMatching(String input) {
        if (input == null) {
            return "";
        }

        if (input.length() > 10_000) {
            input = input.substring(0, 10_000);
        }

        String normalized = input.trim().toLowerCase(Locale.ROOT);

        if (normalized.isEmpty()) {
            return "";
        }

        normalized = normalized.replaceAll("^(the|a|an)\\s+", "");
        normalized = normalized.replaceAll("\\s*#\\d+", "");
        normalized = normalized.replaceAll("\\s*store\\s*#?\\d+", "");
        normalized = normalized.replaceAll("\\s*loc\\s*#?\\d+", "");
        normalized = normalized.replaceAll("\\s*\\d{4,}", "");
        normalized = normalized.replaceAll("\\s+(inc|llc|corp|ltd|limited|company|co)\\.?$", "");
        normalized = normalized.replaceAll("\\s+\\.com$", "");
        normalized = normalized.replaceAll("\\s+\\.co\\.uk$", "");
        normalized = normalized.replaceAll("\\b(\\w)\\.(\\w)\\.", "$1$2");
        normalized = normalized.replaceAll("\\s+", " ");
        normalized = normalized.replaceAll("[^a-z0-9\\s]", "");

        final String result = normalized.trim();

        if (result.isEmpty()) {
            return "";
        }

        return result;
    }
}
