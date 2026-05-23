package com.budgetbuddy.service.ml;

import jakarta.annotation.PostConstruct;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Per-merchant embedding index used by the L7 cascade layer to find
 * close-cosine candidates for an unseen merchant string. Replaces (or
 * augments — controlled by config) the Levenshtein-based FuzzyMatchingService
 * with semantic similarity over the BERT embedding space.
 *
 * <h3>Why a separate index from {@link BertCategoryMatcher}</h3>
 *
 * BertCategoryMatcher embeds CATEGORY PROTOTYPES (one vector per category,
 * built from cluster keywords). That answers "what category does this
 * unknown string smell like?".
 *
 * This index embeds INDIVIDUAL MERCHANTS (one vector per merchant in the
 * curated DB). That answers "is this unknown string a near-neighbor of a
 * merchant we already know?" — useful when a chain has many spelling
 * variants ("WMT PLUS SEP 2025" / "Walmart+ Member 05/26" / "WAL-MART
 * #3098") that should all map to the same canonical merchant + category.
 *
 * <h3>Status</h3>
 *
 * Opt-in via {@code app.category.merchant-embedding-index.enabled=true}.
 * Disabled by default — building 10K embeddings at startup is a ~30s warm-up
 * cost and ~15MB of resident memory (10K × 384 floats), neither of which we
 * want to pay before this feature is wired into the cascade.
 *
 * <h3>Wire-up (deferred)</h3>
 *
 * When activated, {@code EnhancedCategoryDetectionService} should consult
 * this index after the YAML-cluster fuzzy match but before the category-
 * prototype BERT match. A score above {@link #SIMILARITY_THRESHOLD}
 * returns the matched merchant's curated category directly, bypassing the
 * downstream chain. The wire-up is intentionally not yet done — turning
 * this on without a soak test would shift L7 boundaries app-wide.
 */
@Service
@ConditionalOnProperty(
        name = "app.category.merchant-embedding-index.enabled",
        havingValue = "true")
public class MerchantEmbeddingIndex {

    private static final Logger LOGGER = LoggerFactory.getLogger(MerchantEmbeddingIndex.class);

    /** Cosine-similarity floor for a match to count as confident. */
    public static final double SIMILARITY_THRESHOLD = 0.82;

    private final BertEmbeddingService embeddingService;
    private final MerchantCategoryDataService merchantCategoryDataService;

    /** Soft cap on indexed merchants — protects startup time + heap. */
    private final int maxMerchants;

    /** merchant-name → vector. LinkedHashMap so iteration order is stable. */
    private Map<String, float[]> merchantVectors = Collections.emptyMap();
    /** merchant-name → curated category (for the match-result payload). */
    private Map<String, String> merchantCategories = Collections.emptyMap();

    public MerchantEmbeddingIndex(
            final BertEmbeddingService embeddingService,
            final MerchantCategoryDataService merchantCategoryDataService,
            @Value("${app.category.merchant-embedding-index.max-merchants:10000}")
                    final int maxMerchants) {
        this.embeddingService = embeddingService;
        this.merchantCategoryDataService = merchantCategoryDataService;
        this.maxMerchants = maxMerchants;
    }

    @PostConstruct
    public void buildIndex() {
        if (!embeddingService.isAvailable()) {
            LOGGER.info("MerchantEmbeddingIndex: embedding service unavailable — index disabled.");
            return;
        }
        // Pull merchant→category map from the existing data service.
        // Today MerchantCategoryDataService exposes category→keywords (used
        // by BertCategoryMatcher). Inverting that gives us the per-merchant
        // map. The set of "merchants" here is really the curated keyword
        // set per category — close enough for L7 since the keyword IS the
        // canonical merchant string in most cases.
        final Map<String, Set<String>> clusters =
                merchantCategoryDataService.getCategoryToKeywordsMap();
        if (clusters == null || clusters.isEmpty()) {
            LOGGER.warn("MerchantEmbeddingIndex: no clusters available — index disabled.");
            return;
        }
        final Map<String, float[]> vectors = new LinkedHashMap<>();
        final Map<String, String> categories = new LinkedHashMap<>();
        int built = 0;
        outer:
        for (final Map.Entry<String, Set<String>> entry : clusters.entrySet()) {
            final String category = entry.getKey();
            if (category == null || category.isBlank() || entry.getValue() == null) continue;
            for (final String merchant : entry.getValue()) {
                if (merchant == null || merchant.isBlank()) continue;
                if (built >= maxMerchants) {
                    LOGGER.info(
                            "MerchantEmbeddingIndex: max-merchants cap ({}) reached — index truncated",
                            maxMerchants);
                    break outer;
                }
                final float[] vec = embeddingService.embed(merchant);
                if (vec == null) continue;
                vectors.put(merchant, vec);
                categories.put(merchant, category);
                built++;
            }
        }
        this.merchantVectors = Collections.unmodifiableMap(vectors);
        this.merchantCategories = Collections.unmodifiableMap(categories);
        LOGGER.info(
                "MerchantEmbeddingIndex: built {} merchant embeddings (embedding dim {})",
                built, embeddingService.getEmbeddingDim());
    }

    public boolean isAvailable() {
        return !merchantVectors.isEmpty();
    }

    /**
     * Find the closest indexed merchant to {@code query} by cosine
     * similarity. Returns null if the index is empty or no candidate
     * clears {@link #SIMILARITY_THRESHOLD}.
     */
    public Match match(final String query) {
        if (!isAvailable() || query == null || query.isBlank()) return null;
        final float[] q = embeddingService.embed(query);
        if (q == null) return null;
        String best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (final Map.Entry<String, float[]> e : merchantVectors.entrySet()) {
            final double s = BertEmbeddingService.cosineSimilarity(q, e.getValue());
            if (s > bestScore) {
                bestScore = s;
                best = e.getKey();
            }
        }
        if (best == null || bestScore < SIMILARITY_THRESHOLD) return null;
        return new Match(best, merchantCategories.get(best), bestScore);
    }

    /** Single match result. */
    public static final class Match {
        public final String matchedMerchant;
        public final String category;
        public final double similarity;

        public Match(final String matchedMerchant, final String category, final double similarity) {
            this.matchedMerchant = matchedMerchant;
            this.category = category;
            this.similarity = similarity;
        }
    }
}
