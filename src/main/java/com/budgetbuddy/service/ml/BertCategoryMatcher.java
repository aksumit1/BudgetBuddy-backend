package com.budgetbuddy.service.ml;


import java.util.Locale;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Category matcher that uses sentence embeddings from {@link BertEmbeddingService} to compare a
 * merchant description against a small set of precomputed "category prototype" embeddings.
 *
 * <p>This is the piece that makes semantic matching actually <em>semantic</em> — "NETCARE MEDICAL"
 * and "DR. PATEL DENTISTRY" will both land near the "healthcare" prototype even though neither
 * shares surface tokens with the keyword clusters.
 *
 * <h3>Prototype construction</h3>
 *
 * For each category in {@link MerchantCategoryDataService#getCategoryToKeywordsMap()} we build a
 * short descriptive sentence from the category name plus a handful of its most representative
 * keywords. One prototype per category is sufficient — the model's learned similarity does the
 * heavy lifting, and one embedding per category keeps match-time cost to a single cosine-similarity
 * scan.
 *
 * <h3>Match-time contract</h3>
 *
 * {@link #match(String)} returns {@code null} if:
 *
 * <ul>
 *   <li>The embedding service is not loaded.
 *   <li>The input embeds to an empty/null vector.
 *   <li>No category clears {@link #MIN_SIMILARITY}.
 * </ul>
 *
 * Otherwise returns the top category and the cosine similarity.
 */
@Service
public class BertCategoryMatcher {

    private static final Logger LOGGER = LoggerFactory.getLogger(BertCategoryMatcher.class);

    /**
     * Floor below which we ignore a top match — prevents very weak embeddings from categorizing.
     */
    private static final double MIN_SIMILARITY = 0.35;

    /** Keep prototype descriptions short so a single embedding represents the category. */
    private static final int MAX_KEYWORDS_PER_PROTOTYPE = 8;

    private final BertEmbeddingService embeddingService;
    private final MerchantCategoryDataService merchantCategoryDataService;

    @Value("${bert.prototype.override:}")
    private String prototypeOverrides;

    // category -> normalized prototype embedding. Populated once in @PostConstruct.
    private Map<String, float[]> categoryPrototypes = Collections.emptyMap();

    public BertCategoryMatcher(
            final BertEmbeddingService embeddingService,
            final MerchantCategoryDataService merchantCategoryDataService) {
        this.embeddingService = embeddingService;
        this.merchantCategoryDataService = merchantCategoryDataService;
    }

    @PostConstruct
    public void init() {
        if (!embeddingService.isAvailable()) {
            LOGGER.info("BertCategoryMatcher: embedding service unavailable — matcher disabled.");
            return;
        }

        final Map<String, Set<String>> clusters = merchantCategoryDataService.getCategoryToKeywordsMap();
        if (clusters == null || clusters.isEmpty()) {
            LOGGER.warn(
                    "BertCategoryMatcher: MerchantCategoryDataService returned no clusters — matcher disabled.");
            return;
        }

        final Map<String, String> overrides = parseOverrides(prototypeOverrides);

        final Map<String, float[]> built = new LinkedHashMap<>();
        int skipped = 0;
        for (final Map.Entry<String, Set<String>> entry : clusters.entrySet()) {
            final String category = entry.getKey();
            if (category == null || category.isBlank()) {
                continue;
            }
            final String prototypeText =
                    overrides.getOrDefault(
                            category, buildPrototypeText(category, entry.getValue()));
            final float[] embedding = embeddingService.embed(prototypeText);
            if (embedding == null) {
                skipped++;
                continue;
            }
            built.put(category, embedding);
        }

        this.categoryPrototypes = Collections.unmodifiableMap(built);
        LOGGER.info(
                "BertCategoryMatcher: built {} category prototypes (skipped={}, embeddingDim={})",
                built.size(),
                skipped,
                embeddingService.getEmbeddingDim());
    }

    public boolean isAvailable() {
        return embeddingService.isAvailable() && !categoryPrototypes.isEmpty();
    }

    /**
     * Compare {@code text} against every category prototype and return the best match. Returns
     * {@code null} if BERT is unavailable or nothing clears {@link #MIN_SIMILARITY}.
     */
    public Match match(final String text) {
        if (!isAvailable()) {
            return null;
        }
        final float[] query = embeddingService.embed(text);
        if (query == null) {
            return null;
        }

        String bestCategory = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (final Map.Entry<String, float[]> entry : categoryPrototypes.entrySet()) {
            final double score = BertEmbeddingService.cosineSimilarity(query, entry.getValue());
            if (score > bestScore) {
                bestScore = score;
                bestCategory = entry.getKey();
            }
        }

        if (bestCategory == null || bestScore < MIN_SIMILARITY) {
            return null;
        }
        return new Match(bestCategory, bestScore);
    }

    /**
     * Build a short natural-language prototype from the category name and a handful of its most
     * representative keywords. Keywords are sorted by length-desc as a cheap proxy for specificity
     * — multi-word phrases like "grocery store" are more discriminative than single tokens like
     * "store".
     */
    private static String buildPrototypeText(final String category, final Set<String> keywords) {
        final StringBuilder sb = new StringBuilder();
        sb.append(humanizeCategory(category));

        if (keywords != null && !keywords.isEmpty()) {
            final List<String> sorted = new ArrayList<>(keywords);
            sorted.removeIf(k -> k == null || k.isBlank());
            sorted.sort((a, b) -> Integer.compare(b.length(), a.length()));
            final int limit = Math.min(MAX_KEYWORDS_PER_PROTOTYPE, sorted.size());
            if (limit > 0) {
                sb.append(": ");
                for (int i = 0; i < limit; i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    sb.append(sorted.get(i));
                }
            }
        }
        return sb.toString();
    }

    private static String humanizeCategory(final String category) {
        // "homeImprovement" / "home_improvement" / "home-improvement" → "home improvement"
        final String s =
                category.replaceAll("([a-z])([A-Z])", "$1 $2")
                        .replace('_', ' ')
                        .replace('-', ' ')
                        .toLowerCase(Locale.ROOT)
                        .trim();
        return s.isEmpty() ? category : s;
    }

    /**
     * Parse a comma-separated list of {@code category=prototype text} pairs, letting ops tune
     * individual prototypes without a code change. Example: {@code
     * bert.prototype.override=dining=Restaurant or cafe,utilities=Electric water internet}
     */
    private static Map<String, String> parseOverrides(final String raw) {
        if (raw == null || raw.isBlank()) {
            return Collections.emptyMap();
        }
        final Map<String, String> out = new HashMap<>();
        for (final String entry : raw.split(",")) {
            final int eq = entry.indexOf('=');
            if (eq <= 0 || eq == entry.length() - 1) {
                continue;
            }
            final String key = entry.substring(0, eq).trim();
            final String value = entry.substring(eq + 1).trim();
            if (!key.isEmpty() && !value.isEmpty()) {
                out.put(key, value);
            }
        }
        return out;
    }

    public static final class Match {
        public final String category;
        public final double similarity;

        public Match(final String category, final double similarity) {
            this.category = category;
            this.similarity = similarity;
        }

        @Override
        public String toString() {
            return "BertMatch{category='"
                    + category
                    + "', similarity="
                    + String.format("%.3f", similarity)
                    + "}";
        }
    }
}
