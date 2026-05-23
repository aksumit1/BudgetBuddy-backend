package com.budgetbuddy.service.category;

import com.budgetbuddy.model.dynamodb.CustomMerchantMappingTable;
import com.budgetbuddy.service.CategoryLearningService;
import com.budgetbuddy.service.PlaidCategoryMapper;
import com.budgetbuddy.service.TransactionTypeCategoryService.CategoryResult;
import com.budgetbuddy.service.ml.EnhancedCategoryDetectionService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Explicit, layered merchant→category cascade. ONE place defines the
 * order in which every categorisation source is consulted. ONE place
 * defines tie-breaks. Adding a new source (e.g. a future open-DB
 * lookup) means adding a tier here — every consumer benefits.
 *
 * <h3>Layer order (lowest level = highest priority)</h3>
 *
 * <pre>
 *   L0  USER_OVERRIDE       per-user fixes in CustomMerchantMappingTable
 *   L1  ORG_OVERRIDE        admin-set mappings (same table, ORG scope)
 *   L2  MCC_CODE            ISO 18245 MCC from the transaction itself
 *   L3  MERCHANT_DB         data/merchants.json (~10K curated entries with MCC + aliases)
 *   L4  PLAID_TAXONOMY      Plaid's category taxonomy (when transaction came via Plaid)
 *   L5  CURATED_RULES       category-rules-v2.yaml — operator-editable keyword rules
 *   L6  LOCATION_LOOKUP     open-DB lookups by merchant + city/state (OSM/Foursquare; stub today)
 *   L7  FUZZY               Levenshtein / partial / pattern matchers against known merchants
 *   L8  ML_CLASSIFIER       EnhancedCategoryDetectionService — BERT + statistical
 *   L9  KEYWORD_FALLBACK    in-code keyword passes in CSVImportService (legacy)
 * </pre>
 *
 * <h3>Tie-breaks within the same layer</h3>
 *
 * <ol>
 *   <li>Specificity: longer match string wins (so {@code "costco gas"} beats
 *       {@code "costco"} without any author having to set priority numbers).
 *   <li>Confidence: higher reported confidence wins.
 * </ol>
 *
 * <h3>Wiring contract</h3>
 *
 * Callers ({@code CSVImportService}, {@code TransactionTypeCategoryService},
 * etc.) call {@link #classify} once. The cascade walks layers L0→L9 and
 * returns the first non-null result. Every result is a
 * {@link CategoryResult} carrying the category, the source layer it came
 * from, and the confidence — so callers / UI can show "we're 95% sure
 * this is dining (matched MERCHANT_DB)".
 *
 * <h3>What this consolidates</h3>
 *
 * Before this class, the PDF/CSV import path called
 * {@link EnhancedCategoryDetectionService} but never touched
 * {@link InMemoryMerchantService}, leaving the 10K-merchant DB and the
 * MCC mapper effectively unused on the import path. The
 * {@link com.budgetbuddy.service.CSVImportService} keyword passes filled
 * the gap heuristically. Cascade explicitly composes everything so the
 * DB-backed layers are queried first and the keyword passes are the
 * true fallback they were always meant to be.
 */
@SuppressFBWarnings(
        value = {"EI_EXPOSE_REP2"},
        justification = "Spring constructor injection — beans are shared by design")
@SuppressWarnings({
    "PMD.LawOfDemeter",
    "PMD.AvoidCatchingGenericException",
    "PMD.OnlyOneReturn"
})
@Service
public class CategoryCascade {

    private static final Logger LOGGER = LoggerFactory.getLogger(CategoryCascade.class);

    private final CategoryLearningService learningService;
    private final MCCCodeMapper mccMapper;
    private final InMemoryMerchantService merchantDb;
    private final PlaidCategoryMapper plaidMapper;
    private final MerchantCategoryRules curatedRules;
    private final LocationBasedMerchantLookup locationLookup;
    private final EnhancedCategoryDetectionService mlDetector;
    private final UncategorisedReviewQueue reviewQueue;
    /**
     * Optional per-merchant BERT-embedding index. When present, slots in
     * as L7 between L5 (curated YAML) and L6 (location lookup). Null when
     * {@code app.category.merchant-embedding-index.enabled=false}.
     */
    private final com.budgetbuddy.service.ml.MerchantEmbeddingIndex merchantEmbeddingIndex;

    @Autowired
    public CategoryCascade(
            final CategoryLearningService learningService,
            final MCCCodeMapper mccMapper,
            final InMemoryMerchantService merchantDb,
            final PlaidCategoryMapper plaidMapper,
            final LocationBasedMerchantLookup locationLookup,
            final EnhancedCategoryDetectionService mlDetector,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
                    final com.budgetbuddy.service.ml.MerchantEmbeddingIndex merchantEmbeddingIndex,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
                    final UncategorisedReviewQueue reviewQueue) {
        this.learningService = learningService;
        this.mccMapper = mccMapper;
        this.merchantDb = merchantDb;
        this.plaidMapper = plaidMapper;
        this.curatedRules = new MerchantCategoryRules("category-rules-v2.yaml");
        this.locationLookup = locationLookup;
        this.mlDetector = mlDetector;
        this.merchantEmbeddingIndex = merchantEmbeddingIndex;
        this.reviewQueue = reviewQueue;
    }

    /**
     * Run a transaction through the layered cascade. Returns the first
     * non-null match, or null if every layer returns null. The returned
     * {@link CategoryResult} carries provenance — {@link CategoryResult#getSource()}
     * is one of {@link Layer} names.
     *
     * @param ctx accumulated transaction context (user, merchant, description,
     *     MCC, location, Plaid category). Caller passes whatever it has —
     *     the cascade skips layers that need data they don't have.
     */
    public CategoryResult classify(final Context ctx) {
        if (ctx == null) {
            return null;
        }
        CategoryResult r;

        // L0 — User override (per-user CustomMerchantMappingTable)
        r = tryUserOverride(ctx);
        if (r != null) {
            log("L0_USER_OVERRIDE", ctx, r);
            return r;
        }

        // L2 — MCC code (when available from the transaction stream)
        r = tryMccCode(ctx);
        if (r != null) {
            log("L2_MCC", ctx, r);
            return r;
        }

        // L2.5 — Issuer-provided category trailer (Amex "RESTAURANT",
        // Discover "Restaurants", etc.). The card network already
        // classified the transaction; we'd be foolish to ignore that.
        // Sits BETWEEN MCC and merchant DB because the trailer text is a
        // strong signal but slightly less canonical than a 4-digit MCC.
        r = tryIssuerCategory(ctx);
        if (r != null) {
            log("L2_5_ISSUER", ctx, r);
            return r;
        }

        // L3 — Merchant DB (10K curated, exact + alias + prefix)
        r = tryMerchantDb(ctx);
        if (r != null) {
            log("L3_MERCHANT_DB", ctx, r);
            return r;
        }

        // L4 — Plaid taxonomy (only when transaction came via Plaid)
        r = tryPlaidTaxonomy(ctx);
        if (r != null) {
            log("L4_PLAID", ctx, r);
            return r;
        }

        // L5 — Curated YAML rules (operator-editable)
        r = tryCuratedRules(ctx);
        if (r != null) {
            log("L5_CURATED", ctx, r);
            return r;
        }

        // L5.5 — Per-merchant BERT embedding nearest-neighbor. Catches
        // long-tail spelling variants (e.g. "WMT PLUS SEP" ≈ "Walmart+
        // Member") without hardcoded aliases. Only fires when the
        // embedding-index bean is configured (opt-in via
        // app.category.merchant-embedding-index.enabled).
        r = tryMerchantEmbedding(ctx);
        if (r != null) {
            log("L5_5_EMBEDDING", ctx, r);
            return r;
        }

        // L6 — Location-based open-DB lookup (offline stub today;
        //      pluggable backend for OSM Nominatim / Foursquare / etc.)
        r = tryLocationLookup(ctx);
        if (r != null) {
            log("L6_LOCATION", ctx, r);
            return r;
        }

        // L7+L8 — Fuzzy + ML (EnhancedCategoryDetectionService does both)
        r = tryMlDetector(ctx);
        if (r != null) {
            log("L8_ML", ctx, r);
            return r;
        }

        // L9 — submit to self-learning queue. A background worker drains
        // this queue, calls the LLM suggester, and writes high-confidence
        // results back to MerchantEnrichmentStore so the NEXT import sees
        // the merchant at L4 with no LLM call. Fire-and-forget; the
        // current import still falls through to the legacy keyword chain.
        submitForSelfLearning(ctx);
        return null;
    }

    /** Capture an L0-L8 fall-through for the LLM-backed self-learning loop. */
    private void submitForSelfLearning(final Context ctx) {
        if (reviewQueue == null) {
            return;
        }
        if (ctx.merchantName == null || ctx.merchantName.isBlank()) {
            return;
        }
        try {
            reviewQueue.submit(new LlmCategorySuggester.SuggestionContext(
                    ctx.merchantName,
                    ctx.description,
                    ctx.city,
                    ctx.state,
                    ctx.country,
                    null,
                    null,
                    null));
        } catch (RuntimeException ex) {
            // Never let the queue impl affect the import path.
            LOGGER.debug("Failed to submit '{}' to review queue: {}",
                    ctx.merchantName, ex.getMessage());
        }
    }

    // -------------------------------------------------------------------
    // Per-layer attempts (each isolates its dependency + null-safety)
    // -------------------------------------------------------------------

    private CategoryResult tryUserOverride(final Context ctx) {
        if (learningService == null || ctx.userId == null
                || ctx.merchantName == null || ctx.merchantName.isBlank()) {
            return null;
        }
        try {
            final CustomMerchantMappingTable m =
                    learningService.getCustomMapping(ctx.userId, ctx.merchantName);
            if (m == null) {
                return null;
            }
            return new CategoryResult(
                    m.getCategoryPrimary(),
                    m.getCategoryDetailed() != null
                            ? m.getCategoryDetailed()
                            : m.getCategoryPrimary(),
                    "USER_OVERRIDE",
                    1.0);
        } catch (Exception ex) {
            LOGGER.debug("CategoryCascade L0: lookup failed for user={} merchant={}: {}",
                    ctx.userId, ctx.merchantName, ex.getMessage());
            return null;
        }
    }

    private CategoryResult tryMccCode(final Context ctx) {
        if (mccMapper == null || ctx.mccCode == null || ctx.mccCode.isBlank()) {
            return null;
        }
        final MCCCodeMapper.CategoryMapping m = mccMapper.getCategoryFromMCC(ctx.mccCode);
        if (m == null) {
            return null;
        }
        return new CategoryResult(
                m.getPrimaryCategory(),
                m.getDetailedCategory(),
                "MCC_CODE",
                m.getConfidence());
    }

    /**
     * L2.5: Map the issuer's own category trailer to an internal category.
     *
     * <h3>Field vs trailer-scan paths</h3>
     *
     * The Context exposes {@code importerCategoryPrimary/Detailed}, but
     * historically the PDF parser writes our OWN cascade output back into
     * those fields (after-classification snapshot), not the issuer's
     * pre-classification trailer. So the {@code IssuerCategoryMapper.map}
     * call against those fields is a near-no-op today — they don't carry
     * the strings the mapper recognises (RESTAURANT, MISC/SPECIALTY RETAIL,
     * etc.). The check is kept so the code is correct WHEN the parser
     * eventually exposes a dedicated trailer field.
     *
     * <p>The path that actually fires today is the description-scan fallback:
     * {@link IssuerCategoryMapper#scan(String)} looks for known trailer
     * phrases in the (still-uncleaned) description. Amex and Discover
     * concatenate "RESTAURANT", "LODGING", etc. onto the merchant line, so
     * the scan finds them reliably.
     *
     * <p>See also: {@link com.budgetbuddy.service.ml.MerchantLocationSplitter}
     * which is the canonical splitter for merchant vs location. It does NOT
     * extract the trailer phrase — that remains in the description, which
     * is what the scan path consumes.
     */
    private CategoryResult tryIssuerCategory(final Context ctx) {
        if (ctx == null) return null;

        // 1. PREFERRED: dedicated parsed trailer field. The parser captures
        //    the issuer's raw trailer text BEFORE classification, so this is
        //    a clean pre-classification signal — won't get polluted by the
        //    cascade's own output the way importerCategory* fields do.
        String mapped = IssuerCategoryMapper.map(ctx.parsedIssuerTrailer);
        if (mapped != null) {
            return new CategoryResult(
                    mapped, mapped, "ISSUER_CATEGORY_FIELD",
                    IssuerCategoryMapper.TRAILER_CONFIDENCE);
        }

        // 2. LEGACY: importer category fields (now ambiguous — see field
        //    javadoc). Kept as a transitional fallback. New parsers should
        //    populate parsedIssuerTrailer instead.
        mapped = IssuerCategoryMapper.map(ctx.importerCategoryDetailed);
        if (mapped == null) mapped = IssuerCategoryMapper.map(ctx.importerCategoryPrimary);
        if (mapped != null) {
            return new CategoryResult(
                    mapped, mapped, "ISSUER_CATEGORY_FIELD_LEGACY",
                    IssuerCategoryMapper.TRAILER_CONFIDENCE);
        }

        // 3. FALLBACK: scan the uncleaned description for an embedded
        //    trailer. Amex/Discover concatenate the trailer onto the
        //    merchant line; the scan catches that until the parser is
        //    upgraded to emit parsedIssuerTrailer.
        final String desc =
                ctx.normalizedDescription != null
                        ? ctx.normalizedDescription
                        : ctx.description;
        if (desc != null) {
            mapped = IssuerCategoryMapper.scan(desc.toLowerCase(java.util.Locale.ROOT));
            if (mapped != null) {
                return new CategoryResult(
                        mapped, mapped, "ISSUER_CATEGORY_TRAILER",
                        IssuerCategoryMapper.TRAILER_CONFIDENCE);
            }
        }
        return null;
    }

    private CategoryResult tryMerchantDb(final Context ctx) {
        if (merchantDb == null) {
            return null;
        }
        return merchantDb.detectCategory(ctx.merchantName, ctx.description, ctx.mccCode);
    }

    private CategoryResult tryPlaidTaxonomy(final Context ctx) {
        if (plaidMapper == null || ctx.plaidCategory == null) {
            return null;
        }
        final PlaidCategoryMapper.CategoryMapping m =
                plaidMapper.mapPlaidCategory(
                        ctx.plaidCategory,
                        ctx.plaidDetailedCategory,
                        ctx.merchantName,
                        ctx.description);
        if (m == null || m.getPrimary() == null) {
            return null;
        }
        return new CategoryResult(
                m.getPrimary(),
                m.getDetailed() != null ? m.getDetailed() : m.getPrimary(),
                "PLAID_TAXONOMY",
                0.90);
    }

    private CategoryResult tryCuratedRules(final Context ctx) {
        if (curatedRules == null || curatedRules.size() == 0) {
            return null;
        }
        final String desc = ctx.description == null ? null : ctx.description.toLowerCase(Locale.ROOT);
        final String norm = ctx.normalizedDescription == null
                ? desc
                : ctx.normalizedDescription.toLowerCase(Locale.ROOT);
        final MerchantCategoryRules.MatchResult m = curatedRules.matchWithDetails(desc, norm);
        if (m == null) {
            return null;
        }
        return new CategoryResult(m.category, m.category, "CURATED_RULES", m.confidence);
    }

    /**
     * L5.5 — per-merchant embedding nearest-neighbor. Returns null when
     * the index isn't configured, when no merchant name is available, or
     * when no neighbor clears the similarity threshold.
     */
    private CategoryResult tryMerchantEmbedding(final Context ctx) {
        if (merchantEmbeddingIndex == null
                || !merchantEmbeddingIndex.isAvailable()
                || ctx.merchantName == null
                || ctx.merchantName.isBlank()) {
            return null;
        }
        final com.budgetbuddy.service.ml.MerchantEmbeddingIndex.Match match =
                merchantEmbeddingIndex.match(ctx.merchantName);
        if (match == null || match.category == null) return null;
        return new CategoryResult(
                match.category, match.category, "MERCHANT_EMBEDDING", match.similarity);
    }

    private CategoryResult tryLocationLookup(final Context ctx) {
        if (locationLookup == null) {
            return null;
        }
        return locationLookup.lookup(ctx.merchantName, ctx.city, ctx.state, ctx.country);
    }

    private CategoryResult tryMlDetector(final Context ctx) {
        if (mlDetector == null || ctx.description == null) {
            return null;
        }
        try {
            // ML/Fuzzy is bundled here so callers don't need to know which fired.
            final EnhancedCategoryDetectionService.DetectionResult result =
                    mlDetector.detectCategory(
                            ctx.merchantName, ctx.description, null, null, null);
            if (result == null) {
                return null;
            }
            // DetectionResult exposes its own confidence + category via known getters
            // — coerce to CategoryResult for the cascade's unified return shape.
            final String cat = extractCategory(result);
            if (cat == null || cat.isBlank()) {
                return null;
            }
            return new CategoryResult(cat, cat, "ML_FUZZY", 0.75);
        } catch (Exception ex) {
            LOGGER.debug("CategoryCascade L8: ML detect failed: {}", ex.getMessage());
            return null;
        }
    }

    /**
     * Best-effort extraction of the category string from an
     * {@link EnhancedCategoryDetectionService.DetectionResult}. The class
     * is opaque to us here; we go via reflection-style getter lookup
     * for {@code getCategoryPrimary} / {@code getCategory} so this layer
     * doesn't lock us into a specific ML-side rename.
     */
    private static String extractCategory(
            final EnhancedCategoryDetectionService.DetectionResult r) {
        try {
            return (String) r.getClass().getMethod("getCategoryPrimary").invoke(r);
        } catch (Exception ignored1) {
            try {
                return (String) r.getClass().getMethod("getCategory").invoke(r);
            } catch (Exception ignored2) {
                return null;
            }
        }
    }

    private void log(final String layer, final Context ctx, final CategoryResult r) {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(
                    "CategoryCascade {} → {} (confidence={}) for merchant='{}' desc='{}'",
                    layer,
                    r.getCategoryPrimary(),
                    r.getConfidence(),
                    ctx.merchantName,
                    ctx.description);
        }
    }

    // -------------------------------------------------------------------
    // Input + layer enum
    // -------------------------------------------------------------------

    /**
     * Bundle of everything the cascade can use. Callers fill what they
     * have; layers that need missing data skip themselves. Use the
     * {@link #builder()} for readability.
     */
    public static final class Context {
        public final String userId;
        public final String merchantName;
        public final String description;
        public final String normalizedDescription;
        public final String mccCode;
        public final String plaidCategory;
        public final String plaidDetailedCategory;
        public final String city;
        public final String state;
        public final String country;
        /**
         * The issuer's own MCC-style category text, when the PDF parser
         * extracted it (e.g., Amex "RESTAURANT", Discover "Restaurants").
         * Consumed by L2.5 {@code tryIssuerCategory} — this is one of the
         * highest-confidence signals in the cascade since it comes straight
         * from the card network's classification.
         */
        public final String importerCategoryPrimary;
        public final String importerCategoryDetailed;
        /**
         * Raw issuer-provided trailer text extracted by the parser BEFORE any
         * categorisation step has run. e.g., Amex emits a line like
         * "RESTAURANT" / "MISC/SPECIALTY RETAIL" between the date row and the
         * amount; the parser captures it verbatim into this field. Strictly
         * a pre-classification input — the cascade may then route it through
         * {@link IssuerCategoryMapper}, but it MUST NOT be overwritten with
         * the cascade's own output (the bug the importerCategory* fields
         * used to have).
         */
        public final String parsedIssuerTrailer;

        private Context(final Builder b) {
            this.userId = b.userId;
            this.merchantName = b.merchantName;
            this.description = b.description;
            this.normalizedDescription = b.normalizedDescription;
            this.mccCode = b.mccCode;
            this.plaidCategory = b.plaidCategory;
            this.plaidDetailedCategory = b.plaidDetailedCategory;
            this.city = b.city;
            this.state = b.state;
            this.country = b.country;
            this.importerCategoryPrimary = b.importerCategoryPrimary;
            this.importerCategoryDetailed = b.importerCategoryDetailed;
            this.parsedIssuerTrailer = b.parsedIssuerTrailer;
        }

        public static Builder builder() {
            return new Builder();
        }

        public static final class Builder {
            private String userId;
            private String merchantName;
            private String description;
            private String normalizedDescription;
            private String mccCode;
            private String plaidCategory;
            private String plaidDetailedCategory;
            private String city;
            private String state;
            private String country;
            private String importerCategoryPrimary;
            private String importerCategoryDetailed;
            private String parsedIssuerTrailer;

            public Builder userId(final String v) { this.userId = v; return this; }
            public Builder merchantName(final String v) { this.merchantName = v; return this; }
            public Builder description(final String v) { this.description = v; return this; }
            public Builder normalizedDescription(final String v) {
                this.normalizedDescription = v; return this;
            }
            public Builder mccCode(final String v) { this.mccCode = v; return this; }
            public Builder plaidCategory(final String v) { this.plaidCategory = v; return this; }
            public Builder plaidDetailedCategory(final String v) {
                this.plaidDetailedCategory = v; return this;
            }
            public Builder city(final String v) { this.city = v; return this; }
            public Builder state(final String v) { this.state = v; return this; }
            public Builder country(final String v) { this.country = v; return this; }
            public Builder importerCategoryPrimary(final String v) {
                this.importerCategoryPrimary = v; return this;
            }
            public Builder importerCategoryDetailed(final String v) {
                this.importerCategoryDetailed = v; return this;
            }
            public Builder parsedIssuerTrailer(final String v) {
                this.parsedIssuerTrailer = v; return this;
            }
            public Context build() { return new Context(this); }
        }
    }

    /** Layer names as returned in {@link CategoryResult#getSource()}. */
    public enum Layer {
        L0_USER_OVERRIDE, L1_ORG_OVERRIDE, L2_MCC, L2_5_ISSUER,
        L3_MERCHANT_DB, L4_PLAID,
        L5_CURATED, L6_LOCATION, L7_FUZZY, L8_ML, L9_KEYWORD_FALLBACK
    }
}
