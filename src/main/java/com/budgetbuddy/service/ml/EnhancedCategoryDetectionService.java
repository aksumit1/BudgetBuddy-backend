package com.budgetbuddy.service.ml;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Enhanced Category Detection Service Combines rule-based, fuzzy matching, and ML approaches for
 * best accuracy
 *
 * <p>Strategy: 1. Rule-based detection (fast, deterministic) 2. Fuzzy matching (handles variations)
 * 3. ML prediction (learns from data) 4. Confidence-weighted combination
 */
// SDK / Spring integration — the underlying APIs (AWS SDK, Plaid SDK,
// Spring services, reflection) throw arbitrary RuntimeException subtypes
// that can't reasonably be enumerated. Broad catches log + recover (or
// translate to AppException). Suppress at class level since narrowing
// here would mean catch (RuntimeException) which PMD flags identically.
// SpotBugs flags constructor-injected Spring beans as EI_EXPOSE_REP2,
// but Spring's IoC container intentionally shares the same bean across
// callers — defensive-copying it would break dependency injection.
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Spring constructor injection — beans are shared by design")
@SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.OnlyOneReturn"})
@Service
public class EnhancedCategoryDetectionService {

    private static final String BERT_EMBEDDING = "BERT_EMBEDDING";

    private static final String DINING = "dining";

    private static final String ENTERTAINMENT = "entertainment";

    private static final String GROCERIES = "groceries";

    private static final String INCOME = "income";

    private static final String INVESTMENT = "investment";

    private static final String PAYMENT = "payment";

    private static final String SHOPPING = "shopping";

    private static final String TRANSFER = "transfer";

    private static final String UTILITIES = "utilities";

    private static final Logger LOGGER =
            LoggerFactory.getLogger(EnhancedCategoryDetectionService.class);

    private final FuzzyMatchingService fuzzyMatchingService;
    private final CategoryClassificationModel mlModel;
    private final SemanticMatchingService semanticMatchingService;
    private final MerchantCategoryDataService merchantCategoryDataService;
    private final BertCategoryMatcher bertCategoryMatcher;

    // Known merchant categories for fuzzy matching
    // CRITICAL: Loaded from MerchantCategoryDataService (single source of truth)
    private final Map<String, String> knownMerchants = new HashMap<>();

    // Spring ambiguity guard: two constructors exist (the 5-arg production
    // one here and the legacy 4-arg below for older unit tests). Without
    // @Autowired, Spring Boot 4.3+ auto-detection throws "No default
    // constructor found" because it sees two candidates and can't pick.
    // Marking this one explicitly tells Spring which to use — the legacy
    // ctor only exists for manual test instantiation.
    @Autowired
    public EnhancedCategoryDetectionService(
            final FuzzyMatchingService fuzzyMatchingService,
            final CategoryClassificationModel mlModel,
            final SemanticMatchingService semanticMatchingService,
            final MerchantCategoryDataService merchantCategoryDataService,
            final BertCategoryMatcher bertCategoryMatcher) {
        this.fuzzyMatchingService = fuzzyMatchingService;
        this.mlModel = mlModel;
        this.semanticMatchingService = semanticMatchingService;
        this.merchantCategoryDataService = merchantCategoryDataService;
        this.bertCategoryMatcher = bertCategoryMatcher;
        // CRITICAL: Load from shared service instead of initializing locally
        loadMerchantsFromSharedService();
    }

    /**
     * Legacy 4-arg constructor for unit tests predating the BERT matcher. Delegates with {@code
     * null} for the BERT matcher — that branch is already null-guarded at the call site.
     */
    public EnhancedCategoryDetectionService(
            final FuzzyMatchingService fuzzyMatchingService,
            final CategoryClassificationModel mlModel,
            final SemanticMatchingService semanticMatchingService,
            final MerchantCategoryDataService merchantCategoryDataService) {
        this(
                fuzzyMatchingService,
                mlModel,
                semanticMatchingService,
                merchantCategoryDataService,
                null);
    }

    @PostConstruct
    public void init() {
        mlModel.loadModel();
        // All semantic categories and hard-coded strings are now statically merged in
        // initializeKnownMerchants()
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info(
                    "EnhancedCategoryDetectionService initialized with {} known merchants",
                    knownMerchants.size());
        }
    }

    /**
     * Load merchants from shared MerchantCategoryDataService CRITICAL: Thread-safe, error handling,
     * boundary checks, race condition prevention This replaces the old initializeKnownMerchants()
     * method
     */
    private void loadMerchantsFromSharedService() {
        try {
            // CRITICAL: Thread-safe initialization
            synchronized (knownMerchants) {
                knownMerchants.clear();

                // CRITICAL: Null check for shared service
                if (merchantCategoryDataService == null) {
                    LOGGER.error(
                            "MerchantCategoryDataService is null. Cannot load merchants. Service will not function correctly.");
                    return;
                }

                // CRITICAL: Load from shared service with error handling
                Map<String, String> sharedMerchants =
                        merchantCategoryDataService.getMerchantToCategoryMap();

                if (sharedMerchants == null || sharedMerchants.isEmpty()) {
                    LOGGER.warn(
                            "MerchantCategoryDataService returned empty map. Retrying after delay...");
                    // CRITICAL: Retry logic for race conditions (service might not be initialized
                    // yet)
                    try {
                        Thread.sleep(100); // Brief delay for service initialization
                        sharedMerchants = merchantCategoryDataService.getMerchantToCategoryMap();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        LOGGER.warn("Interrupted while waiting for MerchantCategoryDataService");
                    }
                }

                if (sharedMerchants == null || sharedMerchants.isEmpty()) {
                    LOGGER.error(
                            "MerchantCategoryDataService returned empty map after retry. Service will not function correctly.");
                    return;
                }

                // CRITICAL: Boundary check - prevent memory issues
                if (sharedMerchants.size() > 100_000) {
                    if (LOGGER.isErrorEnabled()) {
                        LOGGER.error(
                                "Shared merchant map size ({}) exceeds safety limit. Loading empty map.",
                                sharedMerchants.size());
                    }
                    return;
                }

                // CRITICAL: Load merchants with validation
                int loadedCount = 0;
                for (final Map.Entry<String, String> entry : sharedMerchants.entrySet()) {
                    final String merchant = entry.getKey();
                    final String category = entry.getValue();

                    // CRITICAL: Null checks
                    if (merchant == null || category == null) {
                        LOGGER.warn("Skipping null merchant or category from shared service");
                        continue;
                    }

                    knownMerchants.put(merchant, category);
                    loadedCount++;
                }

                LOGGER.info(
                        "Loaded {} merchants from MerchantCategoryDataService into EnhancedCategoryDetectionService",
                        loadedCount);

                // CRITICAL: Validate loaded data
                if (loadedCount == 0) {
                    LOGGER.error(
                            "No merchants loaded from shared service. EnhancedCategoryDetectionService will not function correctly.");
                } else if (loadedCount < 100) {
                    LOGGER.warn(
                            "Only {} merchants loaded. Expected at least 100. Service may not function correctly.",
                            loadedCount);
                }
            }
        } catch (OutOfMemoryError e) {
            LOGGER.error("Out of memory loading merchants from shared service", e);
            knownMerchants.clear();
        } catch (Exception e) {
            if (LOGGER.isErrorEnabled()) {
                LOGGER.error("Error loading merchants from shared service: {}", e.getMessage(), e);
            }
            knownMerchants.clear();
        }
    }

    /**
     * Detect category using all available methods
     *
     * @param merchantName Merchant name
     * @param description Transaction description
     * @param amount Transaction amount
     * @param paymentChannel Payment channel
     * @param categoryString Category string from CSV (if available)
     * @return DetectionResult with category, confidence, and method used
     */
    public DetectionResult detectCategory(
            final String merchantName,
            final String description,
            final BigDecimal amount,
            final String paymentChannel,
            final String categoryString) {
        return detectCategoryWithContext(
                merchantName, description, amount, paymentChannel, categoryString, null, null);
    }

    /**
     * Detect category using all available methods with context-aware matching
     *
     * @param merchantName Merchant name
     * @param description Transaction description
     * @param amount Transaction amount
     * @param paymentChannel Payment channel
     * @param categoryString Category string from CSV (if available)
     * @param accountType Account type (checking, savings, investment, loan, credit, etc.)
     * @param accountSubtype Account subtype (more specific account type)
     * @return DetectionResult with category, confidence, and method used
     */
    public DetectionResult detectCategoryWithContext(
            final String merchantName,
            final String description,
            BigDecimal amount,
            final String paymentChannel,
            final String categoryString,
            final String accountType,
            final String accountSubtype) {
        try {
            LOGGER.info(
                    "EnhancedCategoryDetection (context-aware): merchant='{}', description='{}', amount={}, channel='{}', accountType='{}', accountSubtype='{}'",
                    merchantName,
                    description,
                    amount,
                    paymentChannel,
                    accountType,
                    accountSubtype);

            // CRITICAL: Validate BigDecimal amount
            if (amount != null
                    && (amount.compareTo(BigDecimal.valueOf(1_000_000_000)) > 0
                            || amount.compareTo(BigDecimal.valueOf(-1_000_000_000)) < 0)) {
                LOGGER.warn("Amount out of reasonable range: {}", amount);
                // Continue processing but use null for amount string
                amount = null;
            }

            final List<DetectionResult> results = new ArrayList<>();

            // Method 1: Rule-based detection (if we have a rule-based service)
            // This would call the existing parseCategory method
            // For now, we'll skip this as it's handled in CSVImportService

            // Method 1.5: MCC directory lookup. Highest-confidence deterministic
            // signal short of a custom user mapping — if we recognise the merchant
            // keyword (Starbucks → 5814 → dining), that's a network-authoritative
            // category label. Runs first so fuzzy + semantic don't get to wiggle
            // us off a known-good MCC answer. See MccDirectory / MCC_PLAN.md.
            final String mccSource =
                    merchantName != null && !merchantName.isBlank() ? merchantName : description;
            if (mccSource != null && !mccSource.isBlank()) {
                final java.util.Optional<String> mccCategory =
                        MccDirectory.categoryForMerchant(mccSource);
                if (mccCategory.isPresent()) {
                    final java.util.Optional<String> mcc = MccDirectory.mccForMerchant(mccSource);
                    results.add(
                            new DetectionResult(
                                    mccCategory.get(),
                                    0.95, // High confidence — MCC is network-authoritative.
                                    "MCC_DIRECTORY",
                                    "merchantKeyword→MCC="
                                            + mcc.orElse("?")
                                            + "→"
                                            + mccCategory.get()));
                }
            }

            // Method 2: Fuzzy matching on known merchants (FIRST - before semantic)
            // CRITICAL FIX: Also check description when merchantName is null (common in PDF/CSV
            // imports)
            // Many imports have merchant info in description field, not merchantName field
            String fuzzyMatchText = null;
            if (merchantName != null && !merchantName.isBlank()) {
                fuzzyMatchText = merchantName;
            } else if (description != null && !description.isBlank()) {
                // Use description for fuzzy matching when merchantName is null
                fuzzyMatchText = description;
                LOGGER.debug(
                        "EnhancedCategoryDetection: Using description for fuzzy matching (merchantName is null): '{}'",
                        description);
            }

            // Strip payment-processor prefixes, ACH/POS boilerplate, ZIPs, state abbrevs, etc.
            // so the fuzzy matcher sees the actual merchant signal rather than the envelope.
            if (fuzzyMatchText != null) {
                final String cleaned = TextNormalizer.cleanMerchantText(fuzzyMatchText);
                if (!cleaned.isEmpty()) {
                    fuzzyMatchText = cleaned;
                }
            }

            double fuzzyScore = 0.0;
            boolean fuzzyMatchFound = false;

            if (fuzzyMatchText != null) {
                final FuzzyMatchingService.MatchResult fuzzyMatch =
                        fuzzyMatchingService.findBestMatch(
                                fuzzyMatchText, new ArrayList<>(knownMerchants.keySet()));
                LOGGER.info(
                        "EnhancedCategoryDetection: Fuzzy match result for '{}': {}",
                        fuzzyMatchText,
                        fuzzyMatch);

                if (fuzzyMatch != null) {
                    fuzzyScore = fuzzyMatch.combinedScore;
                    final String matchedMerchant = fuzzyMatch.original;
                    final String category = knownMerchants.get(matchedMerchant);
                    // CRITICAL: Check for null category (merchant might have been removed)
                    if (category != null) {
                        final String fuzzyTextLower = fuzzyMatchText.toLowerCase(Locale.ROOT);

                        // CRITICAL FIX: Reject income category if text contains "whse" or
                        // "warehouse" (store locations)
                        // "COSTCO WHSE" (Costco Warehouse) is a store, not "Costco Wholesale
                        // Corporation" (payroll)
                        boolean shouldSkip = false;
                        if (INCOME.equals(category)) {
                            if (fuzzyTextLower.contains("whse")
                                    || fuzzyTextLower.contains("warehouse")) {
                                LOGGER.debug(
                                        "Rejecting income category for '{}' - contains 'whse' or 'warehouse' (store location, not corporation)",
                                        fuzzyMatchText);
                                // Skip this match - it's a false positive
                                shouldSkip = true;
                            }
                        }

                        // CRITICAL FIX: Reject utilities category if text contains airport-related
                        // terms
                        // "SEATTLEAP CART/CHAIR" (Seattle Airport cart) should not match "Seattle
                        // Public Utilities"
                        if (!shouldSkip && UTILITIES.equals(category)) {
                            if (fuzzyTextLower.contains("airport")
                                    || fuzzyTextLower.contains("seattleap")
                                    || fuzzyTextLower.contains("seattle ap")
                                    || (fuzzyTextLower.contains("seattle")
                                            && (fuzzyTextLower.contains("cart")
                                                    || fuzzyTextLower.contains("chair")))) {
                                LOGGER.debug(
                                        "Rejecting utilities category for '{}' - contains airport-related terms (airport expense, not utility)",
                                        fuzzyMatchText);
                                // Skip this match - it's a false positive
                                shouldSkip = true;
                            }
                        }

                        // Only add to results if we shouldn't skip this match
                        if (!shouldSkip) {
                            results.add(
                                    new DetectionResult(
                                            category,
                                            fuzzyMatch.combinedScore,
                                            "FUZZY_MATCH",
                                            "Matched "
                                                    + (merchantName != null
                                                            ? "merchant"
                                                            : "description")
                                                    + ": "
                                                    + matchedMerchant));
                            fuzzyMatchFound = true;
                            LOGGER.debug(
                                    "Fuzzy match found: '{}' -> '{}' (confidence: {:.2f})",
                                    fuzzyMatchText,
                                    category,
                                    fuzzyMatch.combinedScore);
                        }
                    } else {
                        LOGGER.warn(
                                "Fuzzy match found for '{}' but category is null", matchedMerchant);
                    }
                } else {
                    LOGGER.debug(
                            "EnhancedCategoryDetection: No fuzzy match found for '{}'",
                            fuzzyMatchText);
                }
            } else {
                LOGGER.debug(
                        "EnhancedCategoryDetection: Skipping fuzzy matching - both merchantName and description are null/empty");
            }

            // Method 3: Semantic matching with context-aware matching (ONLY if fuzzy score is low)
            // Use semantic search as fallback when fuzzy matching doesn't find a good match
            // Threshold: if fuzzy score < 0.5, try semantic search
            if (!fuzzyMatchFound || fuzzyScore < 0.6) {
                if (semanticMatchingService != null
                        && ((merchantName != null && !merchantName.isBlank())
                                || (description != null && !description.isBlank()))) {
                    try {
                        LOGGER.info(
                                "EnhancedCategoryDetection: Fuzzy score low ({}), attempting semantic matching - merchant='{}', description='{}'",
                                fuzzyScore,
                                merchantName,
                                description);

                        // CRITICAL: Use context-aware matching (amount, payment channel, account
                        // type, account subtype)
                        final SemanticMatchingService.SemanticMatchResult semanticMatch =
                                semanticMatchingService.findBestSemanticMatchWithContext(
                                        merchantName,
                                        description,
                                        amount, // Transaction amount for context
                                        paymentChannel, // Payment channel for context
                                        accountType, // Account type for context
                                        accountSubtype // Account subtype for context
                                        );

                        if (semanticMatch != null && semanticMatch.category != null) {
                            if (LOGGER.isInfoEnabled()) {
                                LOGGER.info(
                                        "EnhancedCategoryDetection: ✅ Semantic match found - category='{}', similarity={:.2f}, method='{}'",
                                        semanticMatch.category,
                                        semanticMatch.similarity,
                                        semanticMatch.method != null
                                                ? semanticMatch.method
                                                : "SEMANTIC_MATCH");
                            }
                            results.add(
                                    new DetectionResult(
                                            semanticMatch.category,
                                            semanticMatch.similarity,
                                            "SEMANTIC_MATCH",
                                            "Context-aware semantic similarity match (fuzzy score was low: "
                                                    + String.format("%.2f", fuzzyScore)
                                                    + ")"));
                        } else {
                            if (LOGGER.isInfoEnabled()) {
                                LOGGER.info(
                                        "EnhancedCategoryDetection: Semantic matching returned null or no category match");
                            }
                        }
                    } catch (Exception e) {
                        // CRITICAL: Error handling - log but continue with other methods
                        if (LOGGER.isWarnEnabled()) {
                            LOGGER.warn(
                                    "EnhancedCategoryDetection: Semantic matching failed, continuing with other methods: {}",
                                    e.getMessage());
                        }
                        LOGGER.debug(
                                "EnhancedCategoryDetection: Semantic matching exception details",
                                e);
                        // Don't add to results, continue with other detection methods
                    }
                } else {
                    if (semanticMatchingService == null) {
                        LOGGER.debug(
                                "EnhancedCategoryDetection: Skipping semantic matching - service is null");
                    } else {
                        LOGGER.debug(
                                "EnhancedCategoryDetection: Skipping semantic matching - both merchantName and description are null/empty");
                    }
                }
            } else {
                LOGGER.debug(
                        "EnhancedCategoryDetection: Skipping semantic matching - fuzzy score ({:.2f}) is high enough",
                        fuzzyScore);
            }

            // Method 3b: BERT sentence-embedding match. Runs alongside keyword-based semantic
            // whenever fuzzy was weak. BERT gives real semantic reach (handles unseen merchants,
            // synonyms, paraphrases) that keyword matching can't. If the model isn't loaded
            // (bert.model.path unset), this is a no-op.
            if (!fuzzyMatchFound || fuzzyScore < 0.6) {
                if (bertCategoryMatcher != null
                        && bertCategoryMatcher.isAvailable()
                        && fuzzyMatchText != null) {
                    try {
                        final BertCategoryMatcher.Match bertMatch =
                                bertCategoryMatcher.match(fuzzyMatchText);
                        if (bertMatch != null) {
                            if (LOGGER.isInfoEnabled()) {
                                LOGGER.info(
                                        "EnhancedCategoryDetection: BERT match - category='{}' similarity={}",
                                        bertMatch.category,
                                        String.format("%.3f", bertMatch.similarity));
                            }
                            results.add(
                                    new DetectionResult(
                                            bertMatch.category,
                                            bertMatch.similarity,
                                            BERT_EMBEDDING,
                                            "Sentence-embedding cosine similarity"));
                        }
                    } catch (Exception e) {
                        if (LOGGER.isWarnEnabled()) {
                            LOGGER.warn(
                                    "EnhancedCategoryDetection: BERT matching failed, continuing: {}",
                                    e.getMessage());
                        }
                    }
                }
            }
            /*
            // Method 4: ML prediction
            String amountStr = amount != null ? amount.toString() : null;
            CategoryClassificationModel.PredictionResult mlPrediction = mlModel.predict(
                    merchantName, description, amountStr, paymentChannel);

            if (mlPrediction.category != null && mlPrediction.confidence > 0.3) {
                results.add(new DetectionResult(
                        mlPrediction.category,
                        mlPrediction.confidence,
                        "ML_PREDICTION",
                        "ML model prediction"
                ));
                LOGGER.info("ML prediction: '{}' (confidence: {:.2f})",
                        mlPrediction.category, mlPrediction.confidence);
            }
            */

            // Combine results with confidence weighting
            if (results.isEmpty()) {
                return new DetectionResult(null, 0.0, "NONE", "No detection method found a match");
            }

            // If we have multiple results, combine them
            if (results.size() == 1) {
                return results.getFirst();
            }

            // Weighted combination: fuzzy matching gets higher weight if confidence is high
            // CRITICAL: Prioritize expense categories when amount is negative (expense transaction)
            final Map<String, Double> categoryScores = new HashMap<>();
            for (final DetectionResult result : results) {
                double weight;
                if ("MCC_DIRECTORY".equals(result.method)) {
                    weight = 0.95; // network-authoritative; outranks everything except custom user
                    // mapping
                } else if ("FUZZY_MATCH".equals(result.method) && result.confidence >= 0.85) {
                    weight = 0.7; // strong exact-merchant match — authoritative
                } else if (BERT_EMBEDDING.equals(result.method) && result.confidence >= 0.55) {
                    weight = 0.8; // learned-embedding match with decent similarity — trust it
                } else if (BERT_EMBEDDING.equals(result.method)) {
                    weight = 0.55; // weaker BERT match — still outranks Jaccard semantic
                } else {
                    weight = 0.5;
                }

                // Amount-based priors (sign of amount indicates expense vs. inflow).
                if (amount != null && amount.compareTo(BigDecimal.ZERO) < 0) {
                    if (INCOME.equals(result.category)) {
                        weight *= 0.1;
                        LOGGER.debug(
                                "Penalizing income category for negative amount transaction: merchant='{}', amount={}",
                                merchantName,
                                amount);
                    } else if (!isExpenseCategory(result.category)) {
                        weight *= 0.8;
                    }
                } else if (amount != null && amount.compareTo(BigDecimal.ZERO) > 0) {
                    if (INCOME.equals(result.category)) {
                        weight *= 1.2;
                    }
                }

                // Account-type priors: a category that is implausible for the account type
                // is penalized. Credit/loan/investment accounts have strong category expectations.
                weight *= accountTypeWeightMultiplier(result.category, accountType, accountSubtype);

                // Payment-channel priors: ACH/online biases toward bill pay / subscriptions,
                // in-store POS biases toward physical-world expense categories.
                weight *= paymentChannelWeightMultiplier(result.category, paymentChannel);

                categoryScores.merge(result.category, result.confidence * weight, Double::sum);
            }

            // Find best category
            String bestCategory =
                    categoryScores.entrySet().stream()
                            .max(Map.Entry.comparingByValue())
                            .map(Map.Entry::getKey)
                            .orElse(null);

            double bestConfidence = bestCategory != null ? categoryScores.get(bestCategory) : 0.0;

            // CRITICAL FIX: Final validation - if amount is negative and category is "income",
            // reject it
            if (amount != null
                    && amount.compareTo(BigDecimal.ZERO) < 0
                    && INCOME.equals(bestCategory)) {
                LOGGER.warn(
                        "Rejecting income category for negative amount transaction: merchant='{}', description='{}', amount={}. Using second best category or 'other'",
                        merchantName,
                        description,
                        amount);
                // Find second best category that's not "income"
                bestCategory =
                        categoryScores.entrySet().stream()
                                .filter(e -> !INCOME.equals(e.getKey()))
                                .max(Map.Entry.comparingByValue())
                                .map(Map.Entry::getKey)
                                .orElse("other");
                bestConfidence =
                        bestCategory != null ? categoryScores.getOrDefault(bestCategory, 0.5) : 0.5;
            }
            LOGGER.info(
                    "EnhancedCategoryDetection: merchantName='{}', bestCategory='{}', bestConfidence={}",
                    merchantName,
                    bestCategory,
                    bestConfidence);
            return new DetectionResult(
                    bestCategory,
                    Math.min(bestConfidence, 1.0),
                    "COMBINED",
                    "Combined " + results.size() + " methods");
        } catch (Exception e) {
            LOGGER.error("Error in detectCategory", e);
            // CRITICAL: For null inputs, return NONE instead of ERROR
            // This matches test expectations and provides better UX
            if ((merchantName == null || merchantName.isBlank())
                    && (description == null || description.isBlank())
                    && amount == null
                    && paymentChannel == null
                    && categoryString == null) {
                return new DetectionResult(null, 0.0, "NONE", "No inputs provided");
            }
            // Return safe fallback result for other errors
            return new DetectionResult(
                    null, 0.0, "ERROR", "Error during detection: " + e.getMessage());
        }
    }

    /**
     * Penalize category/account-type combinations that rarely make sense. Returns a multiplier in
     * [0.2, 1.15] applied to the result weight.
     */
    private double accountTypeWeightMultiplier(
            final String category, final String accountType, final String accountSubtype) {
        if (category == null) {
            return 1.0;
        }
        final String cat = category.toLowerCase(Locale.ROOT);
        final String type = accountType != null ? accountType.toLowerCase(Locale.ROOT) : "";
        final String subtype =
                accountSubtype != null ? accountSubtype.toLowerCase(Locale.ROOT) : "";

        if (type.contains("credit") || subtype.contains("credit card")) {
            // Credit cards don't hold savings or investments.
            if ("savings".equals(cat) || INVESTMENT.equals(cat) || "deposit".equals(cat)) {
                return 0.3;
            }
            return 1.0;
        }

        if (type.contains("loan")
                || subtype.contains("mortgage")
                || subtype.contains("student")
                || subtype.contains("auto loan")) {
            // Loan accounts are almost exclusively payment / interest / fee activity.
            if (PAYMENT.equals(cat) || "interest".equals(cat) || "fee".equals(cat)) {
                return 1.15;
            }
            return 0.4;
        }

        if (type.contains(INVESTMENT)
                || type.contains("brokerage")
                || subtype.contains("ira")
                || subtype.contains("401k")
                || subtype.contains("brokerage")) {
            // Investment/retirement accounts: investment + transfer + dividend are normal.
            if (INVESTMENT.equals(cat)
                    || TRANSFER.equals(cat)
                    || "dividend".equals(cat)
                    || INCOME.equals(cat)
                    || "fee".equals(cat)) {
                return 1.10;
            }
            // Heavy penalty for consumer-expense categories on an investment account.
            if (GROCERIES.equals(cat)
                    || DINING.equals(cat)
                    || SHOPPING.equals(cat)
                    || ENTERTAINMENT.equals(cat)) {
                return 0.2;
            }
            return 0.7;
        }

        return 1.0;
    }

    /**
     * Payment-channel priors. Physical channels (in-store POS) argue against online-only categories
     * like subscriptions, and online/ACH argues against cash-back-style categories.
     */
    private double paymentChannelWeightMultiplier(
            final String category, final String paymentChannel) {
        if (category == null || paymentChannel == null) {
            return 1.0;
        }
        final String cat = category.toLowerCase(Locale.ROOT);
        final String channel = paymentChannel.toLowerCase(Locale.ROOT);

        if (channel.contains("in store")
                || channel.contains("in-store")
                || channel.contains("pos")) {
            if ("subscriptions".equals(cat)) {
                return 0.5;
            }
            if (GROCERIES.equals(cat)
                    || DINING.equals(cat)
                    || SHOPPING.equals(cat)
                    || "transportation".equals(cat)) {
                return 1.1;
            }
        }

        if (channel.contains("online") || channel.contains("web")) {
            if ("cash".equals(cat)) {
                return 0.5;
            }
        }

        if (channel.contains("ach")) {
            // ACH is typically bill pay, payroll, transfers — not in-store retail.
            if (PAYMENT.equals(cat)
                    || UTILITIES.equals(cat)
                    || TRANSFER.equals(cat)
                    || INCOME.equals(cat)
                    || "rent".equals(cat)) {
                return 1.15;
            }
            if (DINING.equals(cat) || GROCERIES.equals(cat) || ENTERTAINMENT.equals(cat)) {
                return 0.7;
            }
        }

        return 1.0;
    }

    /** Check if a category is an expense category (not income or investment) */
    private boolean isExpenseCategory(final String category) {
        if (category == null) {
            return false;
        }
        final String catLower = category.toLowerCase(Locale.ROOT);
        // Expense categories
        return GROCERIES.equals(catLower)
                || DINING.equals(catLower)
                || "transportation".equals(catLower)
                || SHOPPING.equals(catLower)
                || ENTERTAINMENT.equals(catLower)
                || "healthcare".equals(catLower)
                || "rent".equals(catLower)
                || UTILITIES.equals(catLower)
                || "subscriptions".equals(catLower)
                || "travel".equals(catLower)
                || "home improvement".equals(catLower)
                || "pet".equals(catLower)
                || "tech".equals(catLower)
                || "other".equals(catLower)
                || PAYMENT.equals(catLower)
                || "cash".equals(catLower)
                || TRANSFER.equals(catLower);
    }

    /** Train ML model with a transaction */
    public void trainModel(
            final String merchantName,
            final String description,
            final String amount,
            final String paymentChannel,
            final String actualCategory) {
        try {
            // CRITICAL: Validate inputs before training
            if (actualCategory == null || actualCategory.isBlank()) {
                LOGGER.debug("Skipping training: category is null or empty");
                return;
            }

            mlModel.train(merchantName, description, amount, paymentChannel, actualCategory);
        } catch (Exception e) {
            LOGGER.error("Error training ML model", e);
            // Don't throw - training failure shouldn't break transaction processing
        }
    }

    /**
     * Add known merchant (for dynamic learning) CRITICAL: Thread-safe, updates both local cache and
     * shared service Race condition prevention: Updates shared service first, then local cache
     */
    public void addKnownMerchant(final String merchantName, final String category) {
        // CRITICAL: Null and empty input validation
        if (merchantName == null || merchantName.isBlank()) {
            LOGGER.warn("Cannot add known merchant: merchant name is null or empty");
            return;
        }
        if (category == null || category.isBlank()) {
            LOGGER.warn("Cannot add known merchant: category is null or empty");
            return;
        }

        try {
            // CRITICAL: Update shared service first (single source of truth)
            if (merchantCategoryDataService != null) {
                merchantCategoryDataService.addMerchantCategory(merchantName, category);
            } else {
                LOGGER.warn("MerchantCategoryDataService is null. Cannot update shared service.");
            }

            // CRITICAL: Update local cache (thread-safe)
            synchronized (knownMerchants) {
                knownMerchants.put(merchantName.toLowerCase(Locale.ROOT).trim(), category);
                LOGGER.debug(
                        "Added known merchant to local cache: '{}' -> '{}'",
                        merchantName,
                        category);
            }
        } catch (Exception e) {
            if (LOGGER.isErrorEnabled()) {
                LOGGER.error(
                        "Error adding known merchant '{}' -> '{}': {}",
                        merchantName,
                        category,
                        e.getMessage(),
                        e);
            }
        }
    }

    /** Detection result */
    public static class DetectionResult {
        public final String category;
        public final double confidence;
        public final String method;
        public final String reason;

        public DetectionResult(
                final String category,
                final double confidence,
                final String method,
                final String reason) {
            this.category = category;
            this.confidence = confidence;
            this.method = method;
            this.reason = reason;
        }

        public boolean isHighConfidence() {
            return confidence >= 0.7;
        }

        public boolean isMediumConfidence() {
            return confidence >= 0.5 && confidence < 0.7;
        }

        public boolean isLowConfidence() {
            return confidence >= 0.3 && confidence < 0.5;
        }

        @Override
        public String toString() {
            return String.format(
                    "DetectionResult{category='%s', confidence=%.2f, method='%s', reason='%s'}",
                    category, confidence, method, reason);
        }
    }
}
