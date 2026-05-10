package com.budgetbuddy.service.ml;


import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Locale;
import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
@SuppressWarnings("PMD.AvoidCatchingGenericException")
@Service
public class EnhancedCategoryDetectionService {

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
        LOGGER.info(
                "EnhancedCategoryDetectionService initialized with {} known merchants",
                knownMerchants.size());
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
                    LOGGER.error(
                            "Shared merchant map size ({}) exceeds safety limit. Loading empty map.",
                            sharedMerchants.size());
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
            LOGGER.error("Error loading merchants from shared service: {}", e.getMessage(), e);
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
                    && (amount.compareTo(java.math.BigDecimal.valueOf(1_000_000_000)) > 0
                            || amount.compareTo(java.math.BigDecimal.valueOf(-1_000_000_000))
                                    < 0)) {
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
            {
                final String mccSource =
                        merchantName != null && !merchantName.isBlank()
                                ? merchantName
                                : description;
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
                if (cleaned != null && !cleaned.isEmpty()) {
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
                        if ("income".equals(category)) {
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
                        if (!shouldSkip && "utilities".equals(category)) {
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
                            LOGGER.info(
                                    "EnhancedCategoryDetection: ✅ Semantic match found - category='{}', similarity={:.2f}, method='{}'",
                                    semanticMatch.category,
                                    semanticMatch.similarity,
                                    semanticMatch.method != null
                                            ? semanticMatch.method
                                            : "SEMANTIC_MATCH");
                            results.add(
                                    new DetectionResult(
                                            semanticMatch.category,
                                            semanticMatch.similarity,
                                            "SEMANTIC_MATCH",
                                            "Context-aware semantic similarity match (fuzzy score was low: "
                                                    + String.format("%.2f", fuzzyScore)
                                                    + ")"));
                        } else {
                            LOGGER.info(
                                    "EnhancedCategoryDetection: Semantic matching returned null or no category match");
                        }
                    } catch (Exception e) {
                        // CRITICAL: Error handling - log but continue with other methods
                        LOGGER.warn(
                                "EnhancedCategoryDetection: Semantic matching failed, continuing with other methods: {}",
                                e.getMessage());
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
                            LOGGER.info(
                                    "EnhancedCategoryDetection: BERT match - category='{}' similarity={}",
                                    bertMatch.category,
                                    String.format("%.3f", bertMatch.similarity));
                            results.add(
                                    new DetectionResult(
                                            bertMatch.category,
                                            bertMatch.similarity,
                                            "BERT_EMBEDDING",
                                            "Sentence-embedding cosine similarity"));
                        }
                    } catch (Exception e) {
                        LOGGER.warn(
                                "EnhancedCategoryDetection: BERT matching failed, continuing: {}",
                                e.getMessage());
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
                return results.get(0);
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
                } else if ("BERT_EMBEDDING".equals(result.method) && result.confidence >= 0.55) {
                    weight = 0.8; // learned-embedding match with decent similarity — trust it
                } else if ("BERT_EMBEDDING".equals(result.method)) {
                    weight = 0.55; // weaker BERT match — still outranks Jaccard semantic
                } else {
                    weight = 0.5;
                }

                // Amount-based priors (sign of amount indicates expense vs. inflow).
                if (amount != null && amount.compareTo(java.math.BigDecimal.ZERO) < 0) {
                    if ("income".equals(result.category)) {
                        weight *= 0.1;
                        LOGGER.debug(
                                "Penalizing income category for negative amount transaction: merchant='{}', amount={}",
                                merchantName,
                                amount);
                    } else if (!isExpenseCategory(result.category)) {
                        weight *= 0.8;
                    }
                } else if (amount != null && amount.compareTo(java.math.BigDecimal.ZERO) > 0) {
                    if ("income".equals(result.category)) {
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
                    && amount.compareTo(java.math.BigDecimal.ZERO) < 0
                    && "income".equals(bestCategory)) {
                LOGGER.warn(
                        "Rejecting income category for negative amount transaction: merchant='{}', description='{}', amount={}. Using second best category or 'other'",
                        merchantName,
                        description,
                        amount);
                // Find second best category that's not "income"
                bestCategory =
                        categoryScores.entrySet().stream()
                                .filter(e -> !"income".equals(e.getKey()))
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
        final String subtype = accountSubtype != null ? accountSubtype.toLowerCase(Locale.ROOT) : "";

        if (type.contains("credit") || subtype.contains("credit card")) {
            // Credit cards don't hold savings or investments.
            if ("savings".equals(cat) || "investment".equals(cat) || "deposit".equals(cat)) {
                return 0.3;
            }
            return 1.0;
        }

        if (type.contains("loan")
                || subtype.contains("mortgage")
                || subtype.contains("student")
                || subtype.contains("auto loan")) {
            // Loan accounts are almost exclusively payment / interest / fee activity.
            if ("payment".equals(cat) || "interest".equals(cat) || "fee".equals(cat)) {
                return 1.15;
            }
            return 0.4;
        }

        if (type.contains("investment")
                || type.contains("brokerage")
                || subtype.contains("ira")
                || subtype.contains("401k")
                || subtype.contains("brokerage")) {
            // Investment/retirement accounts: investment + transfer + dividend are normal.
            if ("investment".equals(cat)
                    || "transfer".equals(cat)
                    || "dividend".equals(cat)
                    || "income".equals(cat)
                    || "fee".equals(cat)) {
                return 1.10;
            }
            // Heavy penalty for consumer-expense categories on an investment account.
            if ("groceries".equals(cat)
                    || "dining".equals(cat)
                    || "shopping".equals(cat)
                    || "entertainment".equals(cat)) {
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
    private double paymentChannelWeightMultiplier(final String category, final String paymentChannel) {
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
            if ("groceries".equals(cat)
                    || "dining".equals(cat)
                    || "shopping".equals(cat)
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
            if ("payment".equals(cat)
                    || "utilities".equals(cat)
                    || "transfer".equals(cat)
                    || "income".equals(cat)
                    || "rent".equals(cat)) {
                return 1.15;
            }
            if ("dining".equals(cat) || "groceries".equals(cat) || "entertainment".equals(cat)) {
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
        return "groceries".equals(catLower)
                || "dining".equals(catLower)
                || "transportation".equals(catLower)
                || "shopping".equals(catLower)
                || "entertainment".equals(catLower)
                || "healthcare".equals(catLower)
                || "rent".equals(catLower)
                || "utilities".equals(catLower)
                || "subscriptions".equals(catLower)
                || "travel".equals(catLower)
                || "home improvement".equals(catLower)
                || "pet".equals(catLower)
                || "tech".equals(catLower)
                || "other".equals(catLower)
                || "payment".equals(catLower)
                || "cash".equals(catLower)
                || "transfer".equals(catLower);
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
     * Initialize known merchants database COMPREHENSIVE: Includes major retailers, food chains,
     * grocery stores, utilities, subscriptions, insurance, tech companies, banks, investment firms,
     * and more
     */
    private void initializeKnownMerchants() {
        // ========== GROCERY STORES ==========
        knownMerchants.put("safeway", "groceries");
        knownMerchants.put("pcc", "groceries");
        knownMerchants.put("amazon fresh", "groceries");
        knownMerchants.put("qfc", "groceries");
        knownMerchants.put("dk market", "groceries");
        knownMerchants.put("whole foods", "groceries");
        knownMerchants.put("trader joe", "groceries");
        knownMerchants.put("kroger", "groceries");
        knownMerchants.put("publix", "groceries");
        knownMerchants.put("wegmans", "groceries");
        knownMerchants.put("costco", "groceries");
        knownMerchants.put("costco whse", "groceries");
        knownMerchants.put("costco warehouse", "groceries");
        knownMerchants.put("costcowhse", "groceries");
        knownMerchants.put("costcowarehouse", "groceries");
        knownMerchants.put("chef store", "groceries");
        knownMerchants.put("town & country", "groceries");
        knownMerchants.put("town and country", "groceries");
        knownMerchants.put("town&country", "groceries");
        knownMerchants.put("mayuri", "groceries");
        knownMerchants.put("meet fresh", "groceries");
        knownMerchants.put("meetfresh", "groceries");
        knownMerchants.put("sunny honey", "groceries");
        knownMerchants.put("sunnyhoney", "groceries");
        knownMerchants.put("walmart", "groceries");
        knownMerchants.put("wmt", "groceries");
        knownMerchants.put("sam's club", "groceries");
        knownMerchants.put("bj's wholesale club", "groceries");
        knownMerchants.put("winco", "groceries");
        knownMerchants.put("target", "groceries");
        knownMerchants.put("aldi", "groceries");
        knownMerchants.put("lidl", "groceries");
        knownMerchants.put("h-e-b", "groceries");
        knownMerchants.put("heb", "groceries");
        knownMerchants.put("stop & shop", "groceries");
        knownMerchants.put("stop and shop", "groceries");
        knownMerchants.put("giant", "groceries");
        knownMerchants.put("giant eagle", "groceries");
        knownMerchants.put("food lion", "groceries");
        knownMerchants.put("harris teeter", "groceries");
        knownMerchants.put("sprouts", "groceries");
        knownMerchants.put("sprouts farmers market", "groceries");
        knownMerchants.put("meijer", "groceries");
        knownMerchants.put("hy-vee", "groceries");
        knownMerchants.put("hyvee", "groceries");
        knownMerchants.put("shoprite", "groceries");
        knownMerchants.put("king soopers", "groceries");
        knownMerchants.put("ralphs", "groceries");
        knownMerchants.put("ralph's", "groceries");
        knownMerchants.put("fred meyer", "groceries");
        knownMerchants.put("fredmeyer", "groceries");
        knownMerchants.put("Giant Food", "groceries");
        knownMerchants.put("The GIANT Company", "groceries");
        knownMerchants.put("Martin's Food Markets", "groceries");
        knownMerchants.put("Hannaford", "groceries");
        knownMerchants.put("Smith's Food and Drug", "groceries");
        knownMerchants.put("Food 4 Less", "groceries");
        knownMerchants.put("Foods Co.", "groceries");
        knownMerchants.put("Mariano's", "groceries");
        knownMerchants.put("Pick 'n Save", "groceries");
        knownMerchants.put("Dillons", "groceries");
        knownMerchants.put("City Market", "groceries");
        knownMerchants.put("Baker's", "groceries");
        knownMerchants.put("Gerbes", "groceries");
        knownMerchants.put("Jay C Food Store", "groceries");
        knownMerchants.put("Metro Market", "groceries");
        knownMerchants.put("Pay-Less Super Markets", "groceries");
        knownMerchants.put("Vons", "groceries");
        knownMerchants.put("Jewel-Osco", "groceries");
        knownMerchants.put("Shaw's", "groceries");
        knownMerchants.put("Acme", "groceries");
        knownMerchants.put("Tom Thumb", "groceries");
        knownMerchants.put("Randalls", "groceries");
        knownMerchants.put(
                "United Supermarkets (including Market Street, Amigos, and United Express formats)",
                "groceries");
        knownMerchants.put("Pavilions", "groceries");
        knownMerchants.put("Star Market", "groceries");
        knownMerchants.put("Jagalchi", "groceries");
        knownMerchants.put("Zion Market", "groceries");
        knownMerchants.put("CU", "groceries");
        knownMerchants.put("Patel Brothers", "groceries");
        knownMerchants.put("Apna Bazar", "groceries");
        knownMerchants.put("Indian CO", "groceries");
        knownMerchants.put("Online Specialty Stores", "groceries");
        knownMerchants.put(" iShopIndian ", "groceries");
        knownMerchants.put("Desi basket", "groceries");
        knownMerchants.put("Holyland Market", "groceries");
        knownMerchants.put("Glatt Mart", "groceries");
        knownMerchants.put("Shufersal ", "groceries");
        knownMerchants.put("India Cash and Carry", "groceries");
        knownMerchants.put("S-Mart", "groceries");
        knownMerchants.put("Asian Family Market", "groceries");
        knownMerchants.put("Fou Lee Market & Deli", "groceries");
        knownMerchants.put("India Supermarket", "groceries");
        knownMerchants.put("India Metro Hypermarket", "groceries");
        knownMerchants.put("International Deli", "groceries");
        knownMerchants.put("Oskoo Persian & Mediterranean market", "groceries");
        knownMerchants.put("LA Superior", "groceries");
        knownMerchants.put("Rose Persian Market & Halal Butchery", "groceries");
        knownMerchants.put("The Souk", "groceries");
        knownMerchants.put("Big John’s PFI", "groceries");
        knownMerchants.put("Haggen", "groceries");
        knownMerchants.put("Carrs", "groceries");
        knownMerchants.put("Kings Food Markets", "groceries");
        knownMerchants.put("Balducci's Food Lovers Market", "groceries");
        knownMerchants.put("99 Ranch Market", "groceries");
        knownMerchants.put("H Mart", "groceries");
        knownMerchants.put("Mitsuwa Marketplace", "groceries");
        knownMerchants.put("T&T Supermarket", "groceries");
        knownMerchants.put("Weee", "groceries");
        knownMerchants.put("Mega Mart", "groceries");
        knownMerchants.put("Dollar Tree", "groceries");
        knownMerchants.put("Dollar General", "groceries");
        knownMerchants.put("Dollarama", "groceries");
        knownMerchants.put("Family Dollar", "groceries");
        knownMerchants.put("Five Below", "groceries");
        knownMerchants.put("Thrive Market", "groceries");
        knownMerchants.put("Erwan's Market", "groceries");
        knownMerchants.put("Sprouts Market", "groceries");
        knownMerchants.put("Erewhon Market", "groceries");
        knownMerchants.put("Market of Choice", "groceries");
        knownMerchants.put("Cumberland Farms", "groceries");
        knownMerchants.put("Tops Friendly Market", "groceries");
        knownMerchants.put("Big Y", "groceries");
        knownMerchants.put("Dillon's", "groceries");
        knownMerchants.put("Gourmet Garage", "groceries");
        knownMerchants.put("WholeFoods", "groceries");
        knownMerchants.put("grocery", "groceries");
        knownMerchants.put("groceries", "groceries");
        knownMerchants.put("market", "groceries");
        knownMerchants.put("pantry", "groceries");
        knownMerchants.put("food", "groceries");
        knownMerchants.put("hypermarket", "groceries");
        knownMerchants.put("super market", "groceries");
        knownMerchants.put("grocery shopping", "groceries");
        knownMerchants.put("food shopping", "groceries");
        knownMerchants.put("produce", "groceries");
        knownMerchants.put("fresh food", "groceries");
        knownMerchants.put("food shop", "groceries");
        knownMerchants.put("superstore", "groceries");

        // ========== FOOD CHAINS / DINING ==========
        knownMerchants.put("subway", "dining");
        knownMerchants.put("panda express", "dining");
        knownMerchants.put("starbucks", "dining");
        knownMerchants.put("chipotle", "dining");
        knownMerchants.put("hoffman", "dining");
        knownMerchants.put("canam", "dining");
        knownMerchants.put("mcdonald", "dining");
        knownMerchants.put("burger", "dining");
        knownMerchants.put("taco", "dining");
        knownMerchants.put("pizza", "dining");
        knownMerchants.put("dominos", "dining");
        knownMerchants.put("domino's", "dining");
        knownMerchants.put("panera", "dining");
        knownMerchants.put("wendy", "dining");
        knownMerchants.put("kfc", "dining");
        knownMerchants.put("kentucky fried chicken", "dining");
        knownMerchants.put("dunkin", "dining");
        knownMerchants.put("dairy queen", "dining");
        knownMerchants.put("dq", "dining");
        knownMerchants.put("papa john", "dining");
        knownMerchants.put("papa murphy", "dining");
        knownMerchants.put("little caesars", "dining");
        knownMerchants.put("olive garden", "dining");
        knownMerchants.put("red lobster", "dining");
        knownMerchants.put("applebee", "dining");
        knownMerchants.put("outback", "dining");
        knownMerchants.put("chili", "dining");
        knownMerchants.put("texas roadhouse", "dining");
        knownMerchants.put("ihop", "dining");
        knownMerchants.put("denny", "dining");
        knownMerchants.put("waffle house", "dining");
        knownMerchants.put("five guys", "dining");
        knownMerchants.put("shake shack", "dining");
        knownMerchants.put("in-n-out", "dining");
        knownMerchants.put("in n out", "dining");
        knownMerchants.put("whataburger", "dining");
        knownMerchants.put("culver", "dining");
        knownMerchants.put("sonic", "dining");
        knownMerchants.put("arby", "dining");
        knownMerchants.put("jack in the box", "dining");
        knownMerchants.put("white castle", "dining");
        knownMerchants.put("qdoba", "dining");
        knownMerchants.put("habit", "dining");
        knownMerchants.put("moe", "dining");
        knownMerchants.put("baja fresh", "dining");
        knownMerchants.put("del taco", "dining");
        knownMerchants.put("carl's jr", "dining");
        knownMerchants.put("carls jr", "dining");
        knownMerchants.put("hardee", "dining");
        knownMerchants.put("pf chang", "dining");
        knownMerchants.put("p.f. chang", "dining");
        knownMerchants.put("cheesecake", "dining");
        knownMerchants.put("red robin", "dining");
        knownMerchants.put("buffalo wild wings", "dining");
        knownMerchants.put("bww", "dining");
        knownMerchants.put("wingstop", "dining");
        knownMerchants.put("zaxby's", "dining");
        knownMerchants.put("zaxbys", "dining");
        // Specific Restaurants
        knownMerchants.put("daeho", "dining");
        knownMerchants.put("tutta bella", "dining");
        knownMerchants.put("tuttabella", "dining");
        knownMerchants.put("simply indian", "dining");
        knownMerchants.put("simply indian restaurant", "dining");
        knownMerchants.put("simplyindian", "dining");
        knownMerchants.put("skills rainbow", "dining");
        knownMerchants.put("skillsrainbow", "dining");
        knownMerchants.put("kyurmaen", "dining");
        knownMerchants.put("kyurmaen ramen", "dining");
        knownMerchants.put("deep dive", "dining");
        knownMerchants.put("deepdive", "dining");
        knownMerchants.put("messina", "dining");
        knownMerchants.put("supreme dumplings", "dining");
        knownMerchants.put("supremedumplings", "dining");
        knownMerchants.put("cucina venti", "dining");
        knownMerchants.put("cucinaventi", "dining");
        knownMerchants.put("desi dhaba", "dining");
        knownMerchants.put("desidhaba", "dining");
        knownMerchants.put("medocinofarms", "dining");
        knownMerchants.put("medocino farms", "dining");
        knownMerchants.put("potbelly", "dining");
        knownMerchants.put("laughing monk", "dining");
        knownMerchants.put("laughingmonk ", "dining");
        knownMerchants.put("indian sizzler", "dining");
        knownMerchants.put("indiansizzler", "dining");
        knownMerchants.put("shana thai", "dining");
        knownMerchants.put("shanathai", "dining");
        knownMerchants.put("tpd", "dining");
        // PayPAMS - online school payments for food
        knownMerchants.put("paypams", "dining");
        knownMerchants.put("pay pams", "dining");
        // Additional Restaurants
        knownMerchants.put("burger and kabob hut", "dining");
        knownMerchants.put("burgerandkabobhut", "dining");
        knownMerchants.put("kabob hut", "dining");
        knownMerchants.put("kabobhut", "dining");
        knownMerchants.put("insomnia cookies", "dining");
        knownMerchants.put("insomniacookies", "dining");
        knownMerchants.put("insomnia cookie", "dining");
        knownMerchants.put("banaras", "dining");
        knownMerchants.put("banarasrestaurant", "dining");
        knownMerchants.put("resy", "dining");
        knownMerchants.put("maxmillen", "dining");
        knownMerchants.put("maxmillian", "dining");
        knownMerchants.put("maximilian", "dining");
        knownMerchants.put("caffe nero", "dining");

        // ========== RETAIL CHAINS ==========
        knownMerchants.put("macy's", "shopping");
        knownMerchants.put("macys", "shopping");
        knownMerchants.put("nordstrom", "shopping");
        knownMerchants.put("nordstrom rack", "shopping");
        knownMerchants.put("best buy", "shopping");
        knownMerchants.put("bestbuy", "shopping");
        knownMerchants.put("amazon", "shopping");
        knownMerchants.put("amazon.com", "shopping");
        knownMerchants.put("ebay", "shopping");
        knownMerchants.put("kohl's", "shopping");
        knownMerchants.put("kohls", "shopping");
        knownMerchants.put("j.c. penney", "shopping");
        knownMerchants.put("jcpenney", "shopping");
        knownMerchants.put("jc penney", "shopping");
        knownMerchants.put("sears", "shopping");
        knownMerchants.put("old navy", "shopping");
        knownMerchants.put("gap", "shopping");
        knownMerchants.put("banana republic", "shopping");
        knownMerchants.put("american eagle", "shopping");
        knownMerchants.put("ae", "shopping");
        knownMerchants.put("h&m", "shopping");
        knownMerchants.put("hm", "shopping");
        knownMerchants.put("zara", "shopping");
        knownMerchants.put("forever 21", "shopping");
        knownMerchants.put("forever21", "shopping");
        knownMerchants.put("ulta", "shopping");
        knownMerchants.put("ulta beauty", "shopping");
        knownMerchants.put("sephora", "shopping");
        knownMerchants.put("bed bath & beyond", "shopping");
        knownMerchants.put("bed bath and beyond", "shopping");
        knownMerchants.put("bbb", "shopping");
        knownMerchants.put("tj maxx", "shopping");
        knownMerchants.put("tjmaxx", "shopping");
        knownMerchants.put("marshalls", "shopping");
        knownMerchants.put("ross", "shopping");
        knownMerchants.put("ross dress for less", "shopping");
        knownMerchants.put("burlington", "shopping");
        knownMerchants.put("burlington coat factory", "shopping");
        knownMerchants.put("dsw", "shopping");
        knownMerchants.put("designer shoe warehouse", "shopping");
        knownMerchants.put("foot locker", "shopping");
        knownMerchants.put("finish line", "shopping");
        knownMerchants.put("dick's sporting goods", "shopping");
        knownMerchants.put("dicks", "shopping");
        knownMerchants.put("dicks sporting goods", "shopping");
        knownMerchants.put("academy sports", "shopping");
        knownMerchants.put("bass pro shops", "shopping");
        knownMerchants.put("cabela's", "shopping");
        knownMerchants.put("cabelas", "shopping");
        knownMerchants.put("gamestop", "shopping");
        knownMerchants.put("game stop", "shopping");
        knownMerchants.put("barnes & noble", "shopping");
        knownMerchants.put("barnes and noble", "shopping");
        knownMerchants.put("books-a-million", "shopping");
        knownMerchants.put("books a million", "shopping");
        knownMerchants.put("michaels", "shopping");
        knownMerchants.put("michael's", "shopping");
        knownMerchants.put("joann", "shopping");
        knownMerchants.put("jo-ann", "shopping");
        knownMerchants.put("joann fabrics", "shopping");
        knownMerchants.put("hobby lobby", "shopping");
        knownMerchants.put("pier 1", "shopping");
        knownMerchants.put("pier 1 imports", "shopping");
        knownMerchants.put("world market", "shopping");
        knownMerchants.put("cost plus world market", "shopping");
        knownMerchants.put("charles Tyrwhitt", "shopping");

        // ========== ENTERTAINMENT (Streaming Services) ==========
        // Streaming services are entertainment, not subscriptions category
        // Subscriptions are detected separately via SubscriptionService
        knownMerchants.put("netflix", "entertainment");
        knownMerchants.put("xfinity tv", "entertainment");
        knownMerchants.put("xfinitytv", "entertainment");
        knownMerchants.put("hulu", "entertainment");
        knownMerchants.put("hlu", "entertainment");
        knownMerchants.put("huluplus", "entertainment");
        knownMerchants.put("hulu plus", "entertainment");
        knownMerchants.put("disney", "entertainment");
        knownMerchants.put("disney+", "entertainment");
        knownMerchants.put("disney plus", "entertainment");
        knownMerchants.put("fubo", "entertainment");
        knownMerchants.put("amazon prime", "entertainment");
        knownMerchants.put("prime video", "entertainment");
        knownMerchants.put("spotify", "entertainment");
        knownMerchants.put("apple music", "entertainment");
        knownMerchants.put("apple tv", "entertainment");
        knownMerchants.put("youtube premium", "entertainment");
        knownMerchants.put("youtube tv", "entertainment");
        knownMerchants.put("youtube", "entertainment");
        knownMerchants.put("ytmusic", "entertainment");
        knownMerchants.put("peacock", "entertainment");
        knownMerchants.put("universal", "entertainment");
        knownMerchants.put("nbc", "entertainment");
        knownMerchants.put("nbc peacock", "entertainment");
        knownMerchants.put("paramount+", "entertainment");
        knownMerchants.put("paramount plus", "entertainment");
        knownMerchants.put("hbo max", "entertainment");
        knownMerchants.put("max", "entertainment");
        knownMerchants.put("hbo", "entertainment");
        knownMerchants.put("showtime", "entertainment");
        knownMerchants.put("starz", "entertainment");
        knownMerchants.put("crunchyroll", "entertainment");
        knownMerchants.put("funimation", "entertainment");
        knownMerchants.put("sling tv", "entertainment");
        knownMerchants.put("fox sports", "entertainment");

        // ========== SUBSCRIPTIONS (Software/Non-Entertainment) ==========
        knownMerchants.put("adobe", "tech");
        knownMerchants.put("adobe creative", "tech");
        knownMerchants.put("microsoft 365", "tech");
        knownMerchants.put("microsoft", "tech");
        knownMerchants.put("apple", "tech");
        knownMerchants.put("google", "tech");
        knownMerchants.put("office 365", "tech");
        knownMerchants.put("dropbox", "tech");
        knownMerchants.put("icloud", "tech");
        knownMerchants.put("google drive", "tech");
        knownMerchants.put("google one", "tech");
        knownMerchants.put("audible", "tech");
        knownMerchants.put("kindle unlimited", "tech");
        knownMerchants.put("scribd", "tech");
        knownMerchants.put("linkedin", "tech");
        knownMerchants.put("linkedin premium", "tech");
        knownMerchants.put("grammarly", "tech");
        knownMerchants.put("nordvpn", "tech");
        knownMerchants.put("expressvpn", "tech");
        knownMerchants.put("surfshark", "tech");
        knownMerchants.put("zoom", "tech");
        knownMerchants.put("slack", "tech");
        knownMerchants.put("github", "tech");
        knownMerchants.put("github pro", "tech");
        knownMerchants.put("canva", "tech");
        knownMerchants.put("canva pro", "tech");
        knownMerchants.put("cursor", "tech");
        knownMerchants.put("openai", "tech");
        knownMerchants.put("chatgpt", "tech");
        knownMerchants.put("anthropic", "tech");
        knownMerchants.put("cohere", "tech");
        knownMerchants.put("perplexity", "tech");
        knownMerchants.put("copilot", "tech");
        knownMerchants.put("replicate", "tech");
        knownMerchants.put("together", "tech");
        knownMerchants.put("midjourney", "tech");
        knownMerchants.put("wikipedia", "tech");

        // ========== INSURANCE PROVIDERS ==========
        // Car Insurance
        knownMerchants.put("geico", "insurance");
        knownMerchants.put("state farm", "insurance");
        knownMerchants.put("progressive", "insurance");
        knownMerchants.put("allstate", "insurance");
        knownMerchants.put("usaa", "insurance");
        knownMerchants.put("farmers", "insurance");
        knownMerchants.put("farmers insurance", "insurance");
        knownMerchants.put("liberty mutual", "insurance");
        knownMerchants.put("nationwide", "insurance");
        knownMerchants.put("travelers", "insurance");
        knownMerchants.put("travelers insurance", "insurance");
        knownMerchants.put("american family", "insurance");
        knownMerchants.put("american family insurance", "insurance");
        knownMerchants.put("amfam", "insurance");
        knownMerchants.put("erie insurance", "insurance");
        knownMerchants.put("erie", "insurance");
        knownMerchants.put("metlife", "insurance");
        knownMerchants.put("met life", "insurance");
        knownMerchants.put("aarp", "insurance");
        knownMerchants.put("aarp insurance", "insurance");
        knownMerchants.put("the hartford", "insurance");
        knownMerchants.put("hartford", "insurance");
        knownMerchants.put("esurance", "insurance");
        knownMerchants.put("safeco", "insurance");
        knownMerchants.put("safeco insurance", "insurance");
        // Home Insurance
        knownMerchants.put("state farm home", "insurance");
        knownMerchants.put("allstate home", "insurance");
        knownMerchants.put("farmers home", "insurance");
        knownMerchants.put("usaa home", "insurance");
        // Life Insurance
        knownMerchants.put("northwestern mutual", "insurance");
        knownMerchants.put("new york life", "insurance");
        knownMerchants.put("massmutual", "insurance");
        knownMerchants.put("mass mutual", "insurance");
        knownMerchants.put("prudential", "insurance");
        knownMerchants.put("prudential financial", "insurance");
        knownMerchants.put("aflac", "insurance");
        knownMerchants.put("aflac insurance", "insurance");
        // Pet Insurance
        knownMerchants.put("petplan", "pet");
        knownMerchants.put("pet plan", "pet");
        knownMerchants.put("healthy paws", "pet");
        knownMerchants.put("trupanion", "pet");
        knownMerchants.put("embrace", "pet");
        knownMerchants.put("embrace pet insurance", "pet");
        knownMerchants.put("nationwide pet", "pet");
        knownMerchants.put("pets best", "pet");
        knownMerchants.put("petsbest", "pet");
        knownMerchants.put("figo", "pet");
        knownMerchants.put("figo pet insurance", "pet");
        // Health Insurance
        knownMerchants.put("blue cross", "healthcare");
        knownMerchants.put("blue cross blue shield", "healthcare");
        knownMerchants.put("bcbs", "healthcare");
        knownMerchants.put("aetna", "healthcare");
        knownMerchants.put("cigna", "healthcare");
        knownMerchants.put("unitedhealthcare", "healthcare");
        knownMerchants.put("united healthcare", "healthcare");
        knownMerchants.put("humana", "healthcare");
        knownMerchants.put("kaiser permanente", "healthcare");
        knownMerchants.put("kaiser", "healthcare");
        knownMerchants.put("anthem", "healthcare");
        knownMerchants.put("anthem blue cross", "healthcare");

        // ========== TRAVEL (Airlines, Hotels, Airport Lounges) ==========
        // Airport Lounges
        knownMerchants.put("centurion lounge", "travel");
        knownMerchants.put("centurionlounge", "travel");
        knownMerchants.put("axp centurion", "travel");
        knownMerchants.put("american express centurion", "travel");
        knownMerchants.put("amex centurion", "travel");
        knownMerchants.put("priority pass", "travel");
        knownMerchants.put("prioritypass", "travel");
        knownMerchants.put("admirals club", "travel");
        knownMerchants.put("admiralsclub", "travel");
        knownMerchants.put("delta sky club", "travel");
        knownMerchants.put("deltaskyclub", "travel");
        knownMerchants.put("united club", "travel");
        knownMerchants.put("unitedclub", "travel");
        knownMerchants.put("american express lounge", "travel");
        knownMerchants.put("amex lounge", "travel");
        knownMerchants.put("plaza premium lounge", "travel");
        knownMerchants.put("plazapremiumlounge", "travel");
        knownMerchants.put("airport lounge", "travel");
        knownMerchants.put("airportlounge", "travel");
        knownMerchants.put("encalm lounge", "travel");
        knownMerchants.put("encalmlounge", "travel");
        knownMerchants.put("encalm", "travel");
        // Airlines
        knownMerchants.put("delta", "travel");
        knownMerchants.put("delta airlines", "travel");
        knownMerchants.put("delta air lines", "travel");
        knownMerchants.put("united", "travel");
        knownMerchants.put("american airlines", "travel");
        knownMerchants.put("americanairlines", "travel");
        knownMerchants.put("southwest", "travel");
        knownMerchants.put("jetblue", "travel");
        knownMerchants.put("alaska", "travel");
        knownMerchants.put("spirit", "travel");
        knownMerchants.put("frontier", "travel");
        knownMerchants.put("allegiant", "travel");
        knownMerchants.put("hawaiian", "travel");
        knownMerchants.put("airline", "travel");
        knownMerchants.put("airlines", "travel");
        // Hotels
        knownMerchants.put("marriott", "travel");
        knownMerchants.put("hilton", "travel");
        knownMerchants.put("hyatt", "travel");
        knownMerchants.put("holiday inn", "travel");
        knownMerchants.put("holidayinn", "travel");
        knownMerchants.put("airbnb", "travel");
        knownMerchants.put("booking.com", "travel");
        knownMerchants.put("expedia", "travel");
        knownMerchants.put("travelocity", "travel");
        knownMerchants.put("priceline", "travel");
        knownMerchants.put("hotel", "travel");
        knownMerchants.put("motel", "travel");
        knownMerchants.put("resort", "travel");
        knownMerchants.put("inn", "travel");
        knownMerchants.put("vrbo", "travel");
        knownMerchants.put("best western", "travel");
        knownMerchants.put("embassy suites", "travel");
        knownMerchants.put("hampton inn", "travel");
        knownMerchants.put("residence inn", "travel");
        knownMerchants.put("courtyard", "travel");
        knownMerchants.put("residential inn", "travel");

        // ========== ELECTRONICS & AI PROVIDERS ==========
        knownMerchants.put("samsung", "tech");
        knownMerchants.put("lg", "tech");
        knownMerchants.put("sony", "tech");
        knownMerchants.put("panasonic", "tech");
        knownMerchants.put("toshiba", "tech");
        knownMerchants.put("hp", "tech");
        knownMerchants.put("hewlett packard", "tech");
        knownMerchants.put("dell", "tech");
        knownMerchants.put("lenovo", "tech");
        knownMerchants.put("asus", "tech");
        knownMerchants.put("acer", "tech");
        knownMerchants.put("msi", "tech");
        knownMerchants.put("nvidia", "tech");
        knownMerchants.put("nvidia corporation", "tech");
        knownMerchants.put("amd", "tech");
        knownMerchants.put("advanced micro devices", "tech");
        knownMerchants.put("intel", "tech");
        knownMerchants.put("intel corporation", "tech");
        knownMerchants.put("qualcomm", "tech");
        knownMerchants.put("broadcom", "tech");
        knownMerchants.put("cisco", "tech");
        knownMerchants.put("cisco systems", "tech");
        knownMerchants.put("ibm", "tech");
        knownMerchants.put("international business machines", "tech");
        knownMerchants.put("oracle", "tech");
        knownMerchants.put("salesforce", "tech");
        knownMerchants.put("adobe systems", "tech");
        // AI Providers
        knownMerchants.put("open ai", "tech");
        knownMerchants.put("anthropic ai", "tech");
        knownMerchants.put("claude", "tech");
        knownMerchants.put("chat gpt", "tech");
        knownMerchants.put("hugging face", "tech");
        knownMerchants.put("huggingface", "tech");
        knownMerchants.put("stability ai", "tech");
        knownMerchants.put("stability", "tech");
        knownMerchants.put("together ai", "tech");
        knownMerchants.put("cursor ai", "tech");
        knownMerchants.put("cursor, ai", "tech");
        knownMerchants.put("cursor ai powered ide", "tech");

        // ========== INTERNET SERVICES / COMPANIES ==========
        knownMerchants.put("alphabet", "tech");
        knownMerchants.put("amazon web services", "tech");
        knownMerchants.put("aws", "tech");
        knownMerchants.put("meta", "tech");
        knownMerchants.put("facebook", "tech");
        knownMerchants.put("instagram", "tech");
        knownMerchants.put("twitter", "tech");
        knownMerchants.put("x", "tech");
        knownMerchants.put("snapchat", "tech");
        knownMerchants.put("snap", "tech");
        knownMerchants.put("tiktok", "tech");
        knownMerchants.put("reddit", "tech");
        knownMerchants.put("discord", "tech");
        knownMerchants.put("shopify", "tech");
        knownMerchants.put("stripe", "tech");
        knownMerchants.put("paypal", "tech");
        knownMerchants.put("square", "tech");
        knownMerchants.put("twilio", "tech");
        knownMerchants.put("cloudflare", "tech");
        knownMerchants.put("akamai", "tech");
        knownMerchants.put("fastly", "tech");
        knownMerchants.put("datadog", "tech");
        knownMerchants.put("splunk", "tech");
        knownMerchants.put("snowflake", "tech");
        knownMerchants.put("databricks", "tech");
        knownMerchants.put("mongodb", "tech");
        knownMerchants.put("redis", "tech");
        knownMerchants.put("elastic", "tech");
        knownMerchants.put("elasticsearch", "tech");

        // ========== HOME IMPROVEMENT PROVIDERS ==========
        knownMerchants.put("home depot", "home improvement");
        knownMerchants.put("homedepot", "home improvement");
        knownMerchants.put("lowes", "home improvement");
        knownMerchants.put("lowes home improvement", "home improvement");
        knownMerchants.put("menards", "home improvement");
        knownMerchants.put("ace hardware", "home improvement");
        knownMerchants.put("ace", "home improvement");
        knownMerchants.put("true value", "home improvement");
        knownMerchants.put("truevalue", "home improvement");
        knownMerchants.put("harbor freight", "home improvement");
        knownMerchants.put("harbor freight tools", "home improvement");
        knownMerchants.put("northern tool", "home improvement");
        knownMerchants.put("northern tool & equipment", "home improvement");
        knownMerchants.put("tractor supply", "home improvement");
        knownMerchants.put("tractor supply company", "home improvement");
        knownMerchants.put("sherwin williams", "home improvement");
        knownMerchants.put("benjamin moore", "home improvement");
        knownMerchants.put("behr", "home improvement");
        knownMerchants.put("valspar", "home improvement");
        knownMerchants.put("ppg", "home improvement");
        knownMerchants.put("ppg paints", "home improvement");

        // ========== SERVICE PROVIDERS ==========
        // ========== TRANSPORTATION SERVICES ==========
        knownMerchants.put("uber", "transportation");
        knownMerchants.put("lyft", "transportation");
        // State Department of Transportation (DOT) - Toll roads, highway authorities
        knownMerchants.put("wsdot", "transportation");
        knownMerchants.put("washington state dot", "transportation");
        knownMerchants.put("washington state department of transportation", "transportation");
        knownMerchants.put("goodtogo", "transportation");
        knownMerchants.put("good to go", "transportation");
        knownMerchants.put("good-to-go", "transportation");
        knownMerchants.put("caltrans", "transportation");
        knownMerchants.put("california dot", "transportation");
        knownMerchants.put("fastrak", "transportation");
        knownMerchants.put("ez pass", "transportation");
        knownMerchants.put("ezpass", "transportation");
        knownMerchants.put("e-zpass", "transportation");
        knownMerchants.put("nysdot", "transportation");
        knownMerchants.put("new york state dot", "transportation");
        knownMerchants.put("new york thruway", "transportation");
        knownMerchants.put("txdot", "transportation");
        knownMerchants.put("texas dot", "transportation");
        knownMerchants.put("ez tag", "transportation");
        knownMerchants.put("txtag", "transportation");
        knownMerchants.put("fdot", "transportation");
        knownMerchants.put("florida dot", "transportation");
        knownMerchants.put("sunpass", "transportation");
        knownMerchants.put("epass", "transportation");
        knownMerchants.put("idot", "transportation");
        knownMerchants.put("illinois dot", "transportation");
        knownMerchants.put("ipass", "transportation");
        knownMerchants.put("massdot", "transportation");
        knownMerchants.put("massachusetts dot", "transportation");
        knownMerchants.put("penn dot", "transportation");
        knownMerchants.put("penndot", "transportation");
        knownMerchants.put("pennsylvania dot", "transportation");
        knownMerchants.put("njdot", "transportation");
        knownMerchants.put("new jersey dot", "transportation");
        knownMerchants.put("garden state parkway", "transportation");
        knownMerchants.put("new jersey turnpike", "transportation");
        knownMerchants.put("mdot", "transportation");
        knownMerchants.put("maryland dot", "transportation");
        knownMerchants.put("vdot", "transportation");
        knownMerchants.put("virginia dot", "transportation");
        // Amex Airlines Fee Reimbursement - transportation (even though it's a credit)
        knownMerchants.put("amex airlines fee reimbursement", "transportation");
        knownMerchants.put("amexairlinesfeereimbursement", "transportation");
        // Toll patterns
        knownMerchants.put("eractoll", "transportation");
        knownMerchants.put("era toll", "transportation");
        // Car Service
        knownMerchants.put("hona ctr", "transportation");
        knownMerchants.put("honactr", "transportation");
        knownMerchants.put("hona car service", "transportation");
        knownMerchants.put("honacarservice", "transportation");
        knownMerchants.put("doordash", "dining");
        knownMerchants.put("door dash", "dining");
        knownMerchants.put("grubhub", "dining");
        knownMerchants.put("grub hub", "dining");
        knownMerchants.put("ubereats", "dining");
        knownMerchants.put("uber eats", "dining");
        knownMerchants.put("postmates", "dining");
        knownMerchants.put("instacart", "groceries");
        knownMerchants.put("shipt", "groceries");
        knownMerchants.put("taskrabbit", "service");
        knownMerchants.put("task rabbit", "service");
        knownMerchants.put("thumbtack", "service");
        knownMerchants.put("angie's list", "service");
        knownMerchants.put("angies list", "service");
        knownMerchants.put("homeadvisor", "service");
        knownMerchants.put("home advisor", "service");
        knownMerchants.put("handy", "service");
        knownMerchants.put("care.com", "service");
        knownMerchants.put("carecom", "service");

        // ========== LOAN PROVIDERS ==========
        knownMerchants.put("sofi", "payment");
        knownMerchants.put("sofi loans", "payment");
        knownMerchants.put("lendingclub", "payment");
        knownMerchants.put("lending club", "payment");
        knownMerchants.put("prosper", "payment");
        knownMerchants.put("prosper marketplace", "payment");
        knownMerchants.put("upstart", "payment");
        knownMerchants.put("lightstream", "payment");
        knownMerchants.put("lightstream loans", "payment");
        knownMerchants.put("discover personal loans", "payment");
        knownMerchants.put("mariner finance", "payment");
        knownMerchants.put("one main financial", "payment");
        knownMerchants.put("onemain", "payment");
        knownMerchants.put("springleaf", "payment");
        knownMerchants.put("aventium", "payment");
        knownMerchants.put("navient", "payment");
        knownMerchants.put("nelnet", "payment");
        knownMerchants.put("great lakes", "payment");
        knownMerchants.put("great lakes educational", "payment");
        knownMerchants.put("fedloan", "payment");
        knownMerchants.put("fedloan servicing", "payment");
        knownMerchants.put("mohela", "payment");
        knownMerchants.put("aidvantage", "payment");
        knownMerchants.put("edfinancial", "payment");
        knownMerchants.put("ed financial", "payment");

        // ========== CREDIT PROVIDERS ==========
        knownMerchants.put("american express", "payment");
        knownMerchants.put("amex", "payment");
        knownMerchants.put("discover", "payment");
        knownMerchants.put("discover card", "payment");
        knownMerchants.put("mastercard", "payment");
        knownMerchants.put("visa", "payment");
        knownMerchants.put("capital one", "payment");
        knownMerchants.put("capitalone", "payment");
        knownMerchants.put("synchrony", "payment");
        knownMerchants.put("synchrony bank", "payment");
        knownMerchants.put("citibank", "payment");
        knownMerchants.put("citi", "payment");
        knownMerchants.put("citi credit", "payment");
        knownMerchants.put("citi card", "payment");
        knownMerchants.put("citicard", "payment");
        knownMerchants.put("chase", "payment");
        knownMerchants.put("chase credit", "payment");
        knownMerchants.put("wells fargo", "payment");
        knownMerchants.put("wf", "payment");
        knownMerchants.put("wf credit", "payment");
        knownMerchants.put("bank of america", "payment");
        knownMerchants.put("bofa", "payment");
        knownMerchants.put("boa", "payment");
        knownMerchants.put("us bank", "payment");
        knownMerchants.put("usbank", "payment");
        knownMerchants.put("pnc", "payment");
        knownMerchants.put("pnc bank", "payment");
        knownMerchants.put("td bank", "payment");
        knownMerchants.put("tdbank", "payment");
        knownMerchants.put("suntrust", "payment");
        knownMerchants.put("truist", "payment");
        knownMerchants.put("regions", "payment");
        knownMerchants.put("regions bank", "payment");
        knownMerchants.put("huntington", "payment");
        knownMerchants.put("huntington bank", "payment");
        knownMerchants.put("keybank", "payment");
        knownMerchants.put("key bank", "payment");
        knownMerchants.put("citizens", "payment");
        knownMerchants.put("citizens bank", "payment");
        knownMerchants.put("fifth third", "payment");
        knownMerchants.put("fifth third bank", "payment");
        knownMerchants.put("53", "payment");
        knownMerchants.put("m&t bank", "payment");
        knownMerchants.put("mt bank", "payment");
        knownMerchants.put("comerica", "payment");
        knownMerchants.put("comerica bank", "payment");
        knownMerchants.put("zions", "payment");
        knownMerchants.put("zions bank", "payment");
        knownMerchants.put("first national", "payment");
        knownMerchants.put("first national bank", "payment");
        knownMerchants.put("bb&t", "payment");
        knownMerchants.put("bbt", "payment");

        // ========== BANK PROVIDERS ==========
        knownMerchants.put("chase bank", "transfer");
        knownMerchants.put("ally bank", "transfer");
        knownMerchants.put("ally", "transfer");
        knownMerchants.put("discover bank", "transfer");
        knownMerchants.put("goldman sachs", "transfer");
        knownMerchants.put("marcus", "transfer");
        knownMerchants.put("marcus by goldman sachs", "transfer");
        knownMerchants.put("schwab bank", "transfer");
        knownMerchants.put("charles schwab bank", "transfer");
        knownMerchants.put("usaa bank", "transfer");
        knownMerchants.put("navy federal", "transfer");
        knownMerchants.put("navy federal credit union", "transfer");
        knownMerchants.put("penfed", "transfer");
        knownMerchants.put("pentagon federal", "transfer");
        knownMerchants.put("alliant", "transfer");
        knownMerchants.put("alliant credit union", "transfer");
        knownMerchants.put("first republic", "transfer");
        knownMerchants.put("first republic bank", "transfer");
        knownMerchants.put("silicon valley bank", "transfer");
        knownMerchants.put("svb", "transfer");
        knownMerchants.put("signature bank", "transfer");

        // ========== INVESTMENT PROVIDERS ==========
        knownMerchants.put("fidelity", "investment");
        knownMerchants.put("fidelity investments", "investment");
        knownMerchants.put("vanguard", "investment");
        knownMerchants.put("vanguard group", "investment");
        knownMerchants.put("charles schwab", "investment");
        knownMerchants.put("schwab", "investment");
        knownMerchants.put("morgan stanley", "investment");
        knownMerchants.put("morganstanley", "investment");
        knownMerchants.put("jpmorgan", "investment");
        knownMerchants.put("jp morgan", "investment");
        knownMerchants.put("jpmorgan chase", "investment");
        knownMerchants.put("merrill lynch", "investment");
        knownMerchants.put("merrill", "investment");
        knownMerchants.put("bank of america merrill", "investment");
        knownMerchants.put("edward jones", "investment");
        knownMerchants.put("edwardjones", "investment");
        knownMerchants.put("raymond james", "investment");
        knownMerchants.put("raymondjames", "investment");
        knownMerchants.put("ubs", "investment");
        knownMerchants.put("ubs financial", "investment");
        knownMerchants.put("credit suisse", "investment");
        knownMerchants.put("deutsche bank", "investment");
        knownMerchants.put("barclays", "investment");
        knownMerchants.put("barclays investment", "investment");
        knownMerchants.put("td ameritrade", "investment");
        knownMerchants.put("etrade", "investment");
        knownMerchants.put("e-trade", "investment");
        knownMerchants.put("etrade financial", "investment");
        knownMerchants.put("interactive brokers", "investment");
        knownMerchants.put("ib", "investment");
        knownMerchants.put("ibkr", "investment");
        knownMerchants.put("robinhood", "investment");
        knownMerchants.put("robin hood", "investment");
        knownMerchants.put("webull", "investment");
        knownMerchants.put("webull securities", "investment");
        knownMerchants.put("tastytrade", "investment");
        knownMerchants.put("tastyworks", "investment");
        knownMerchants.put("ally invest", "investment");
        knownMerchants.put("sofi invest", "investment");
        knownMerchants.put("public", "investment");
        knownMerchants.put("public.com", "investment");
        knownMerchants.put("m1 finance", "investment");
        knownMerchants.put("m1", "investment");
        knownMerchants.put("stash", "investment");
        knownMerchants.put("acorns", "investment");
        knownMerchants.put("betterment", "investment");
        knownMerchants.put("wealthfront", "investment");
        knownMerchants.put("personal capital", "investment");
        knownMerchants.put("empower", "investment");
        knownMerchants.put("empower retirement", "investment");
        knownMerchants.put("t. rowe price", "investment");
        knownMerchants.put("troweprice", "investment");
        knownMerchants.put("t rowe price", "investment");
        knownMerchants.put("franklin templeton", "investment");
        knownMerchants.put("franklin", "investment");
        knownMerchants.put("blackrock", "investment");
        knownMerchants.put("ishares", "investment");
        knownMerchants.put("i shares", "investment");
        knownMerchants.put("state street", "investment");
        knownMerchants.put("state street global", "investment");
        knownMerchants.put("invesco", "investment");
        knownMerchants.put("invesco qqq", "investment");
        knownMerchants.put("proshares", "investment");
        knownMerchants.put("pro shares", "investment");
        knownMerchants.put("directional", "investment");
        knownMerchants.put("directional funds", "investment");

        // ========== TRANSPORTATION ==========
        // CRITICAL: Airport expenses (carts, chairs, parking, etc.) must come BEFORE utilities
        // "SEATTLEAP" (Seattle Airport) should not match "Seattle Public Utilities"
        knownMerchants.put("seattleap cart/chair", "transportation");
        knownMerchants.put("seattleap cart", "transportation");
        knownMerchants.put("seattleap chair", "transportation");
        knownMerchants.put("seattleap", "transportation");
        knownMerchants.put("seattle ap", "transportation");
        knownMerchants.put("seattle airport", "transportation");
        knownMerchants.put("airport cart", "transportation");
        knownMerchants.put("airport chair", "transportation");

        // ========== GAS STATIONS ==========
        // COSTCO GAS must come before general COSTCO (groceries)
        knownMerchants.put("costco gas", "transportation");
        knownMerchants.put("costcogas", "transportation");
        // Travel centers (gas station + grocery + food) - BUC-EE's
        knownMerchants.put("buc-ee", "transportation");
        knownMerchants.put("buc-ee's", "transportation");
        knownMerchants.put("bucee", "transportation");
        knownMerchants.put("bucees", "transportation");
        knownMerchants.put("chevron", "transportation");
        knownMerchants.put("shell", "transportation");
        knownMerchants.put("bp", "transportation");
        knownMerchants.put("british petroleum", "transportation");
        knownMerchants.put("exxon", "transportation");
        knownMerchants.put("exxonmobil", "transportation");
        knownMerchants.put("mobil", "transportation");
        knownMerchants.put("kwik sak", "transportation");
        knownMerchants.put("kwiksak", "transportation");
        knownMerchants.put("kwik-sak", "transportation");
        knownMerchants.put("valero", "transportation");
        knownMerchants.put("speedway", "transportation");
        knownMerchants.put("7-eleven", "transportation");
        knownMerchants.put("7eleven", "transportation");
        knownMerchants.put("circle k", "transportation");
        knownMerchants.put("circlek", "transportation");
        knownMerchants.put("arco", "transportation");
        knownMerchants.put("am/pm", "transportation");
        knownMerchants.put("ampm", "transportation");
        knownMerchants.put("phillips 66", "transportation");
        knownMerchants.put("phillips66", "transportation");
        knownMerchants.put("marathon", "transportation");
        knownMerchants.put("marathon petroleum", "transportation");
        knownMerchants.put("citgo", "transportation");
        knownMerchants.put("sunoco", "transportation");
        knownMerchants.put("conoco", "transportation");
        knownMerchants.put("conocophillips", "transportation");
        knownMerchants.put("76", "transportation");
        knownMerchants.put("unocal 76", "transportation");
        knownMerchants.put("quik trip", "transportation");
        knownMerchants.put("quiktrip", "transportation");
        knownMerchants.put("qt", "transportation");
        knownMerchants.put("wawa", "transportation");
        knownMerchants.put("sheetz", "transportation");
        knownMerchants.put("pilot", "transportation");
        knownMerchants.put("pilot flying j", "transportation");
        knownMerchants.put("flying j", "transportation");
        knownMerchants.put("love's", "transportation");
        knownMerchants.put("loves", "transportation");
        knownMerchants.put("loves travel stops", "transportation");
        knownMerchants.put("ta", "transportation");
        knownMerchants.put("travelcenters of america", "transportation");

        // ========== ENTERTAINMENT ==========
        knownMerchants.put("amc", "entertainment");
        knownMerchants.put("amc theaters", "entertainment");
        knownMerchants.put("cinemark", "entertainment");
        knownMerchants.put("regal", "entertainment");
        knownMerchants.put("regal cinemas", "entertainment");
        knownMerchants.put("carmike", "entertainment");
        knownMerchants.put("carmike cinemas", "entertainment");
        knownMerchants.put("marcus theaters", "entertainment");
        knownMerchants.put("alamo drafthouse", "entertainment");
        knownMerchants.put("arc light", "entertainment");
        knownMerchants.put("arc light cinemas", "entertainment");
        knownMerchants.put("imax", "entertainment");
        knownMerchants.put("top golf", "entertainment");
        knownMerchants.put("topgolf", "entertainment");

        // ========== HEALTHCARE ==========
        knownMerchants.put("cvs", "healthcare");
        knownMerchants.put("walgreens", "healthcare");
        knownMerchants.put("rite aid", "healthcare");
        knownMerchants.put("riteaid", "healthcare");
        knownMerchants.put("walmart pharmacy", "healthcare");
        knownMerchants.put("target pharmacy", "healthcare");
        knownMerchants.put("kroger pharmacy", "healthcare");
        knownMerchants.put("safeway pharmacy", "healthcare");
        knownMerchants.put("costco pharmacy", "healthcare");
        knownMerchants.put("cvs pharmacy", "healthcare");
        knownMerchants.put("walgreens pharmacy", "healthcare");

        // ========== PET ==========
        knownMerchants.put("petsmart", "pet");
        knownMerchants.put("petco", "pet");
        knownMerchants.put("pet supplies plus", "pet");
        knownMerchants.put("pet supplies", "pet");
        knownMerchants.put("petcare clinic", "pet");
        knownMerchants.put("pet care clinic", "pet");
        knownMerchants.put("petcare", "pet");
        knownMerchants.put("pet care", "pet");
        knownMerchants.put("petland", "pet");
        knownMerchants.put("petland discounts", "pet");
        knownMerchants.put("pet supermarket", "pet");
        knownMerchants.put("chewy", "pet");
        knownMerchants.put("chewy.com", "pet");
        knownMerchants.put("petmeds", "pet");
        knownMerchants.put("1800petmeds", "pet");
        // ========== PHONE PROVIDERS ==========
        knownMerchants.put("verizon", "utilities");
        knownMerchants.put("verizon wireless", "utilities");
        knownMerchants.put("at&t", "utilities");
        knownMerchants.put("att", "utilities");
        knownMerchants.put("at and t", "utilities");
        knownMerchants.put("xfinity mobile", "utilities");
        knownMerchants.put("xfinitymobile", "utilities");
        knownMerchants.put("t-mobile", "utilities");
        knownMerchants.put("tmobile", "utilities");
        knownMerchants.put("t mobile", "utilities");
        knownMerchants.put("sprint", "utilities");
        knownMerchants.put("us cellular", "utilities");
        knownMerchants.put("uscellular", "utilities");
        knownMerchants.put("cricket", "utilities");
        knownMerchants.put("cricket wireless", "utilities");
        knownMerchants.put("boost mobile", "utilities");
        knownMerchants.put("metropcs", "utilities");
        knownMerchants.put("metro pcs", "utilities");
        knownMerchants.put("mint mobile", "utilities");
        knownMerchants.put("google fi", "utilities");
        knownMerchants.put("visible", "utilities");
        knownMerchants.put("straight talk", "utilities");

        // ========== UTILITY PROVIDERS (Water, Electricity, Gas, Cable) ==========
        // Electric Companies
        knownMerchants.put("puget sound energy", "utilities");
        knownMerchants.put("pse", "utilities");
        knownMerchants.put("pacific power", "utilities");
        knownMerchants.put("portland general electric", "utilities");
        knownMerchants.put("pge", "utilities");
        knownMerchants.put("southern california edison", "utilities");
        knownMerchants.put("sce", "utilities");
        knownMerchants.put("pg&e", "utilities");
        knownMerchants.put("pacific gas and electric", "utilities");
        knownMerchants.put("san diego gas & electric", "utilities");
        knownMerchants.put("sdge", "utilities");
        knownMerchants.put("duke energy", "utilities");
        knownMerchants.put("dominion energy", "utilities");
        knownMerchants.put("con edison", "utilities");
        knownMerchants.put("coned", "utilities");
        knownMerchants.put("consolidated edison", "utilities");
        knownMerchants.put("national grid", "utilities");
        knownMerchants.put("exelon", "utilities");
        knownMerchants.put("firstenergy", "utilities");
        knownMerchants.put("first energy", "utilities");
        knownMerchants.put("aep", "utilities");
        knownMerchants.put("american electric power", "utilities");
        knownMerchants.put("southern company", "utilities");
        knownMerchants.put("xcel energy", "utilities");
        knownMerchants.put("centerpoint energy", "utilities");
        knownMerchants.put("centerpoint", "utilities");
        knownMerchants.put("entergy", "utilities");
        knownMerchants.put("aes", "utilities");
        knownMerchants.put("aes corporation", "utilities");
        // Water Companies
        knownMerchants.put("city of bellevue", "utilities");
        knownMerchants.put("city of seattle", "utilities");
        knownMerchants.put("seattle public utilities", "utilities");
        knownMerchants.put("spu", "utilities");
        knownMerchants.put("american water", "utilities");
        knownMerchants.put("california water service", "utilities");
        knownMerchants.put("suez water", "utilities");
        knownMerchants.put("aqua america", "utilities");
        // Cable/Internet Providers
        knownMerchants.put("comcast", "utilities");
        knownMerchants.put("xfinity", "utilities");
        knownMerchants.put("spectrum", "utilities");
        knownMerchants.put("charter", "utilities");
        knownMerchants.put("charter spectrum", "utilities");
        knownMerchants.put("cox", "utilities");
        knownMerchants.put("cox communications", "utilities");
        knownMerchants.put("optimum", "utilities");
        knownMerchants.put("altice", "utilities");
        knownMerchants.put("frontier communications", "utilities");
        knownMerchants.put("centurylink", "utilities");
        knownMerchants.put("century link", "utilities");
        knownMerchants.put("windstream", "utilities");
        knownMerchants.put("suddenlink", "utilities");
        knownMerchants.put("mediacom", "utilities");
        knownMerchants.put("dish", "utilities");
        knownMerchants.put("dish network", "utilities");
        knownMerchants.put("directv", "utilities");
        knownMerchants.put("direct tv", "utilities");
        knownMerchants.put("att u-verse", "utilities");
        knownMerchants.put("att uverse", "utilities");
        knownMerchants.put("fios", "utilities");
        knownMerchants.put("verizon fios", "utilities");

        // ========== PAYROLL / PAYCHECK PROVIDERS ==========
        // Major Payroll Companies
        knownMerchants.put("adp", "income");
        knownMerchants.put("automatic data processing", "income");
        knownMerchants.put("paychex", "income");
        knownMerchants.put("paychex inc", "income");
        knownMerchants.put("paycom", "income");
        knownMerchants.put("paycom software", "income");
        knownMerchants.put("paylocity", "income");
        knownMerchants.put("justworks", "income");
        knownMerchants.put("gusto", "income");
        knownMerchants.put("bamboohr", "income");
        knownMerchants.put("workday", "income");
        knownMerchants.put("workday payroll", "income");
        knownMerchants.put("zenefits", "income");
        knownMerchants.put("triple net", "income");
        knownMerchants.put("triplenet", "income");
        knownMerchants.put("ceridian", "income");
        knownMerchants.put("ceridian dayforce", "income");
        knownMerchants.put("kronos", "income");
        knownMerchants.put("ukg", "income");
        knownMerchants.put("ultimate software", "income");
        knownMerchants.put("paycor", "income");
        knownMerchants.put("isolved", "income");
        knownMerchants.put("isolved hcm", "income");
        knownMerchants.put("quickbooks payroll", "income");
        knownMerchants.put("intuit payroll", "income");
        knownMerchants.put("square payroll", "income");
        knownMerchants.put("wave payroll", "income");
        knownMerchants.put("onpay", "income");
        knownMerchants.put("patriot software", "income");
        knownMerchants.put("surepayroll", "income");
        knownMerchants.put("sure payroll", "income");
        knownMerchants.put("payroll plus", "income");
        knownMerchants.put("payroll plus solutions", "income");
        knownMerchants.put("heartland payroll", "income");
        knownMerchants.put("paychex flex", "income");
        knownMerchants.put("adp workforce now", "income");
        knownMerchants.put("adp run", "income");
        knownMerchants.put("adp totalsource", "income");
        knownMerchants.put("adp vantage", "income");
        knownMerchants.put("adp ez labor", "income");
        knownMerchants.put("adp mobile", "income");
        knownMerchants.put("adp portal", "income");
        knownMerchants.put("paychex portal", "income");
        knownMerchants.put("paychex mobile", "income");
        // Direct Deposit / ACH Payroll
        knownMerchants.put("direct deposit", "income");
        knownMerchants.put("directdeposit", "income");
        knownMerchants.put("ach deposit", "income");
        knownMerchants.put("ach credit", "income");
        knownMerchants.put("payroll deposit", "income");
        knownMerchants.put("payroll direct deposit", "income");
        knownMerchants.put("salary deposit", "income");
        knownMerchants.put("salary direct deposit", "income");
        knownMerchants.put("payroll payment", "income");
        knownMerchants.put("payroll ach", "income");
        knownMerchants.put("payroll transfer", "income");
        knownMerchants.put("payroll credit", "income");
        knownMerchants.put("payroll ach credit", "income");
        knownMerchants.put("payroll ach deposit", "income");
        // Major Employers (Fortune 500 companies that issue paychecks)
        knownMerchants.put("walmart stores", "income");
        knownMerchants.put("walmart inc", "income");
        knownMerchants.put("amazon services", "income");
        knownMerchants.put("apple inc", "income");
        knownMerchants.put("microsoft corporation", "income");
        knownMerchants.put("microsoft corp", "income");
        knownMerchants.put("google llc", "income");
        knownMerchants.put("alphabet inc", "income");
        knownMerchants.put("meta platforms", "income");
        knownMerchants.put("facebook inc", "income");
        knownMerchants.put("tesla inc", "income");
        knownMerchants.put("tesla motors", "income");
        knownMerchants.put("jpmorgan chase & co", "income");
        knownMerchants.put("citigroup", "income");
        knownMerchants.put("berkshire hathaway", "income");
        knownMerchants.put("exxon mobil", "income");
        // CRITICAL: Remove "chevron" from income - it's a gas station (expense), not payroll
        // Only "chevron corporation" should be income (for payroll)
        knownMerchants.put("chevron corporation", "income");
        knownMerchants.put("johnson & johnson", "income");
        knownMerchants.put("pfizer", "income");
        knownMerchants.put("unitedhealth group", "income");
        knownMerchants.put("cvs health", "income");
        knownMerchants.put("cardinal health", "income");
        knownMerchants.put("mckesson", "income");
        knownMerchants.put("verizon communications", "income");
        knownMerchants.put("walt disney", "income");
        // CRITICAL: Remove "home depot" from income - it's a home improvement store (expense), not
        // payroll
        // Only "home depot inc" or "home depot corporation" should be income (for payroll)
        knownMerchants.put("home depot inc", "income");
        knownMerchants.put("home depot corporation", "income");
        knownMerchants.put("target corporation", "income");
        // CRITICAL: "costco wholesale" is ONLY for payroll from Costco Wholesale Corporation
        // Must be exact match or "costco wholesale corporation" to avoid matching "COSTCO WHSE"
        // (warehouse stores)
        // "COSTCO WHSE" (Costco Warehouse) is a store location, not the corporation for payroll
        knownMerchants.put("costco wholesale corporation", "income");
        knownMerchants.put("costco wholesale inc", "income");
        knownMerchants.put("costco wholesale company", "income");
        // Only match "costco wholesale" if it's clearly a corporation (not "whse" or "warehouse")
        // Note: This is a fallback - prefer more specific patterns above
        // CRITICAL: Remove "lowes" from income - it's a home improvement store (expense), not
        // payroll
        // Only "lowes companies" or "lowes inc" should be income (for payroll)
        knownMerchants.put("lowes companies", "income");
        knownMerchants.put("lowes inc", "income");
        knownMerchants.put("starbucks corporation", "income");
        knownMerchants.put("mcdonalds corporation", "income");
        knownMerchants.put("nike", "income");
        knownMerchants.put("coca cola", "income");
        knownMerchants.put("pepsico", "income");
        knownMerchants.put("procter & gamble", "income");
        knownMerchants.put("pg", "income");
        knownMerchants.put("general electric", "income");
        knownMerchants.put("ge", "income");
        knownMerchants.put("boeing", "income");
        knownMerchants.put("lockheed martin", "income");
        knownMerchants.put("raytheon", "income");
        knownMerchants.put("northrop grumman", "income");
        knownMerchants.put("general motors", "income");
        knownMerchants.put("gm", "income");
        knownMerchants.put("ford motor", "income");
        knownMerchants.put("ford", "income");
        knownMerchants.put("fiat chrysler", "income");
        knownMerchants.put("stellantis", "income");
        knownMerchants.put("toyota motor", "income");
        knownMerchants.put("honda motor", "income");
        knownMerchants.put("nissan motor", "income");
        knownMerchants.put("volkswagen", "income");
        knownMerchants.put("bmw", "income");
        knownMerchants.put("mercedes benz", "income");
        knownMerchants.put("daimler", "income");
        // Government Payroll
        knownMerchants.put("social security", "income");
        knownMerchants.put("ssa", "income");
        knownMerchants.put("social security administration", "income");
        knownMerchants.put("unemployment", "income");
        knownMerchants.put("unemployment insurance", "income");
        knownMerchants.put("ui", "income");
        knownMerchants.put("state unemployment", "income");
        knownMerchants.put("federal unemployment", "income");
        knownMerchants.put("veterans affairs", "income");
        knownMerchants.put("va", "income");
        knownMerchants.put("department of veterans affairs", "income");
        knownMerchants.put("veterans benefits", "income");
        knownMerchants.put("va benefits", "income");
        knownMerchants.put("military pay", "income");
        knownMerchants.put("military payroll", "income");
        knownMerchants.put("defense finance", "income");
        knownMerchants.put("dfas", "income");
        knownMerchants.put("defense finance and accounting service", "income");
        knownMerchants.put("us treasury", "income");
        knownMerchants.put("treasury department", "income");
        knownMerchants.put("irs refund", "income");
        knownMerchants.put("tax refund", "income");
        knownMerchants.put("federal tax refund", "income");
        knownMerchants.put("state tax refund", "income");
        knownMerchants.put("internal revenue service", "income");
        knownMerchants.put("irs", "income");
        // Financial Data Aggregators / Account Aggregation Services
        knownMerchants.put("plaid", "transfer");
        knownMerchants.put("yodlee", "transfer");
        knownMerchants.put("finicity", "transfer");
        knownMerchants.put("mx", "transfer");
        knownMerchants.put("mx technologies", "transfer");
        knownMerchants.put("akoya", "transfer");
        knownMerchants.put("alloy", "transfer");
        knownMerchants.put("alloy labs", "transfer");
        knownMerchants.put("teller", "transfer");
        knownMerchants.put("teller.io", "transfer");
        knownMerchants.put("sophtron", "transfer");
        knownMerchants.put("quovo", "transfer");
        knownMerchants.put("envestnet yodlee", "transfer");
        knownMerchants.put("envestnet", "transfer");
        knownMerchants.put("intuit", "transfer");
        knownMerchants.put("mint", "transfer");
        knownMerchants.put("credit karma", "transfer");
        // Merchant Category Code (MCC) Common Categories
        // These are standard MCC codes used by financial institutions
        // 5411 - Grocery Stores, Supermarkets
        knownMerchants.put("mcc 5411", "groceries");
        knownMerchants.put("mcc5411", "groceries");
        // 5812 - Eating Places, Restaurants
        knownMerchants.put("mcc 5812", "dining");
        knownMerchants.put("mcc5812", "dining");
        // 5541 - Service Stations (Gas Stations)
        knownMerchants.put("mcc 5541", "transportation");
        knownMerchants.put("mcc5541", "transportation");
        // 4900 - Utilities (Electric, Gas, Water, Sanitary)
        knownMerchants.put("mcc 4900", "utilities");
        knownMerchants.put("mcc4900", "utilities");
        // 4814 - Telecommunications Equipment and Telephone Sales
        knownMerchants.put("mcc 4814", "utilities");
        knownMerchants.put("mcc4814", "utilities");
        // 4816 - Computer Network/Information Services
        knownMerchants.put("mcc 4816", "utilities");
        knownMerchants.put("mcc4816", "utilities");
        // 5999 - Miscellaneous and Specialty Retail Stores
        knownMerchants.put("mcc 5999", "shopping");
        knownMerchants.put("mcc5999", "shopping");
        // 5311 - Department Stores
        knownMerchants.put("mcc 5311", "shopping");
        knownMerchants.put("mcc5311", "shopping");
        // 5912 - Drug Stores, Pharmacies
        knownMerchants.put("mcc 5912", "healthcare");
        knownMerchants.put("mcc5912", "healthcare");
        // 8011 - Doctors, Physicians (Not Elsewhere Classified)
        knownMerchants.put("mcc 8011", "healthcare");
        knownMerchants.put("mcc8011", "healthcare");
        // 8021 - Dentists, Orthodontists
        knownMerchants.put("mcc 8021", "healthcare");
        knownMerchants.put("mcc8021", "healthcare");
        // 8041 - Chiropractors
        knownMerchants.put("mcc 8041", "healthcare");
        knownMerchants.put("mcc8041", "healthcare");
        // 8042 - Optometrists, Ophthalmologists
        knownMerchants.put("mcc 8042", "healthcare");
        knownMerchants.put("mcc8042", "healthcare");
        // 8043 - Opticians, Optical Goods and Eyeglasses
        knownMerchants.put("mcc 8043", "healthcare");
        knownMerchants.put("mcc8043", "healthcare");
        // 8062 - Hospitals
        knownMerchants.put("mcc 8062", "healthcare");
        knownMerchants.put("mcc8062", "healthcare");
        // 7832 - Motion Picture Theaters
        knownMerchants.put("mcc 7832", "entertainment");
        knownMerchants.put("mcc7832", "entertainment");
        // 7922 - Theatrical Producers and Ticket Agencies
        knownMerchants.put("mcc 7922", "entertainment");
        knownMerchants.put("mcc7922", "entertainment");
        // 5995 - Pet Shops, Pet Food, and Supplies Stores
        knownMerchants.put("mcc 5995", "pet");
        knownMerchants.put("mcc5995", "pet");
        // 1520 - General Contractors - Residential and Commercial
        knownMerchants.put("mcc 1520", "home improvement");
        knownMerchants.put("mcc1520", "home improvement");
        // 5211 - Lumber and Building Materials Stores
        knownMerchants.put("mcc 5211", "home improvement");
        knownMerchants.put("mcc5211", "home improvement");
        // 5231 - Paint, Varnish, and Supplies Stores
        knownMerchants.put("mcc 5231", "home improvement");
        knownMerchants.put("mcc5231", "home improvement");
        // 6011 - Automated Cash Disbursements (ATMs)
        knownMerchants.put("mcc 6011", "cash");
        knownMerchants.put("mcc6011", "cash");
        // 6012 - Financial Institutions - Merchandise, Services
        knownMerchants.put("mcc 6012", "transfer");
        knownMerchants.put("mcc6012", "transfer");
        // 6300 - Insurance Sales, Underwriting, and Premiums
        knownMerchants.put("mcc 6300", "insurance");
        knownMerchants.put("mcc6300", "insurance");
        // 6010 - Financial Institutions - Manual Cash Disbursements
        knownMerchants.put("mcc 6010", "cash");
        knownMerchants.put("mcc6010", "cash");

        // ========== ISO 20022 FINANCIAL MESSAGING STANDARD ==========
        // ISO 20022 is the international standard for financial messaging
        // Message types and transaction categories
        knownMerchants.put("iso 20022", "transfer");
        knownMerchants.put("iso20022", "transfer");
        knownMerchants.put("pain.001", "transfer"); // Credit Transfer Initiation
        knownMerchants.put("pain.002", "transfer"); // Credit Transfer Status
        knownMerchants.put("pain.008", "transfer"); // Direct Debit Initiation
        knownMerchants.put("pain.009", "transfer"); // Direct Debit Status
        knownMerchants.put("camt.052", "transfer"); // Bank to Customer Account Report
        knownMerchants.put("camt.053", "transfer"); // Bank to Customer Statement
        knownMerchants.put("camt.054", "transfer"); // Bank to Customer Debit Credit Notification
        knownMerchants.put("camt.056", "transfer"); // Cancellation Request
        knownMerchants.put("camt.057", "transfer"); // Notification to Receive
        knownMerchants.put("pacs.008", "transfer"); // FIToFICustomerCreditTransfer
        knownMerchants.put("pacs.009", "transfer"); // FinancialInstitutionCreditTransfer
        knownMerchants.put("pacs.002", "transfer"); // FIToFIPaymentStatusReport

        // ========== ACH STANDARD ENTRY CLASS (SEC) CODES ==========
        // NACHA (National Automated Clearing House Association) SEC codes
        knownMerchants.put(
                "ppd", "income"); // Prearranged Payment and Deposit Entry (Direct Deposit)
        knownMerchants.put("ppd entry", "income");
        knownMerchants.put("ppd credit", "income");
        knownMerchants.put("ppd debit", "payment");
        knownMerchants.put("ccd", "transfer"); // Corporate Credit or Debit Entry
        knownMerchants.put("ccd entry", "transfer");
        knownMerchants.put("ccd credit", "transfer");
        knownMerchants.put("ccd debit", "transfer");
        knownMerchants.put("iat", "transfer"); // International ACH Transaction
        knownMerchants.put("iat entry", "transfer");
        knownMerchants.put("iat credit", "transfer");
        knownMerchants.put("iat debit", "transfer");
        knownMerchants.put("web", "payment"); // Internet-Initiated Entry
        knownMerchants.put("web entry", "payment");
        knownMerchants.put("web credit", "payment");
        knownMerchants.put("web debit", "payment");
        knownMerchants.put("tel", "payment"); // Telephone-Initiated Entry
        knownMerchants.put("tel entry", "payment");
        knownMerchants.put("tel credit", "payment");
        knownMerchants.put("tel debit", "payment");
        knownMerchants.put("arc", "payment"); // Accounts Receivable Entry
        knownMerchants.put("arc entry", "payment");
        knownMerchants.put("boc", "payment"); // Back Office Conversion Entry
        knownMerchants.put("boc entry", "payment");
        knownMerchants.put("ckd", "payment"); // Check Truncation Entry
        knownMerchants.put("ckd entry", "payment");
        knownMerchants.put("pop", "payment"); // Point of Purchase Entry
        knownMerchants.put("pop entry", "payment");
        knownMerchants.put("pos", "payment"); // Point of Sale Entry
        knownMerchants.put("pos entry", "payment");
        knownMerchants.put("rcp", "payment"); // Re-presented Check Entry
        knownMerchants.put("rcp entry", "payment");
        knownMerchants.put("xck", "payment"); // Destroyed Check Entry
        knownMerchants.put("xck entry", "payment");
        knownMerchants.put("ctx", "transfer"); // Corporate Trade Exchange
        knownMerchants.put("ctx entry", "transfer");
        knownMerchants.put("ctx credit", "transfer");
        knownMerchants.put("ctx debit", "transfer");
        knownMerchants.put("trc", "payment"); // Truncated Check Entry
        knownMerchants.put("trc entry", "payment");
        knownMerchants.put("trx", "payment"); // Check Same Day Settlement Entry
        knownMerchants.put("trx entry", "payment");

        // ========== ACH TRANSACTION CODES ==========
        // NACHA ACH transaction type codes
        knownMerchants.put("ach 21", "income"); // ACH Credit - Deposit
        knownMerchants.put("ach 22", "payment"); // ACH Debit - Payment
        knownMerchants.put("ach 23", "payment"); // ACH Debit - Preauthorized
        knownMerchants.put("ach 24", "payment"); // ACH Debit - Zero Dollar
        knownMerchants.put("ach 31", "income"); // ACH Credit - Deposit
        knownMerchants.put("ach 32", "payment"); // ACH Debit - Payment
        knownMerchants.put("ach 33", "payment"); // ACH Debit - Preauthorized
        knownMerchants.put("ach 34", "payment"); // ACH Debit - Zero Dollar
        knownMerchants.put("ach 41", "income"); // ACH Credit - Deposit
        knownMerchants.put("ach 42", "payment"); // ACH Debit - Payment
        knownMerchants.put("ach 43", "payment"); // ACH Debit - Preauthorized
        knownMerchants.put("ach 44", "payment"); // ACH Debit - Zero Dollar

        // ========== SWIFT / BIC CODES ==========
        // SWIFT (Society for Worldwide Interbank Financial Telecommunication)
        // BIC (Bank Identifier Code) / SWIFT codes for major banks
        knownMerchants.put("swift", "transfer");
        knownMerchants.put("swift code", "transfer");
        knownMerchants.put("bic", "transfer");
        knownMerchants.put("bic code", "transfer");
        knownMerchants.put("bank identifier code", "transfer");
        // SWIFT Message Types (MT codes)
        knownMerchants.put("mt 103", "transfer"); // Single Customer Credit Transfer
        knownMerchants.put("mt103", "transfer");
        knownMerchants.put("mt 202", "transfer"); // General Financial Institution Transfer
        knownMerchants.put("mt202", "transfer");
        knownMerchants.put("mt 940", "transfer"); // Customer Statement Message
        knownMerchants.put("mt940", "transfer");
        knownMerchants.put("mt 942", "transfer"); // Interim Transaction Report
        knownMerchants.put("mt942", "transfer");
        knownMerchants.put("mt 950", "transfer"); // Statement Message
        knownMerchants.put("mt950", "transfer");
        knownMerchants.put("mt 101", "transfer"); // Request for Transfer
        knownMerchants.put("mt101", "transfer");
        knownMerchants.put("mt 102", "transfer"); // Multiple Customer Credit Transfer
        knownMerchants.put("mt102", "transfer");
        knownMerchants.put("mt 104", "transfer"); // Direct Debit Message
        knownMerchants.put("mt104", "transfer");
        knownMerchants.put("mt 110", "transfer"); // Advice of Cheque
        knownMerchants.put("mt110", "transfer");
        knownMerchants.put("mt 111", "transfer"); // Request for Stop Payment
        knownMerchants.put("mt111", "transfer");
        knownMerchants.put("mt 200", "transfer"); // Financial Institution Transfer
        knownMerchants.put("mt200", "transfer");
        knownMerchants.put("mt 201", "transfer"); // Multiple Financial Institution Transfer
        knownMerchants.put("mt201", "transfer");
        knownMerchants.put("mt 210", "transfer"); // Notice to Receive
        knownMerchants.put("mt210", "transfer");
        knownMerchants.put("mt 900", "transfer"); // Confirmation of Debit
        knownMerchants.put("mt900", "transfer");
        knownMerchants.put("mt 910", "transfer"); // Confirmation of Credit
        knownMerchants.put("mt910", "transfer");

        // ========== IBAN (INTERNATIONAL BANK ACCOUNT NUMBER) ==========
        knownMerchants.put("iban", "transfer");
        knownMerchants.put("international bank account number", "transfer");
        knownMerchants.put("iban transfer", "transfer");
        knownMerchants.put("sepa", "transfer"); // Single Euro Payments Area
        knownMerchants.put("sepa transfer", "transfer");
        knownMerchants.put("sepa credit transfer", "transfer");
        knownMerchants.put("sepa direct debit", "payment");
        knownMerchants.put("sepa instant", "transfer");
        knownMerchants.put("sepa instant credit transfer", "transfer");

        // ========== FEDWIRE / FEDERAL RESERVE WIRE TRANSFER ==========
        knownMerchants.put("fedwire", "transfer");
        knownMerchants.put("fed wire", "transfer");
        knownMerchants.put("federal reserve wire", "transfer");
        knownMerchants.put("fedwire funds transfer", "transfer");
        knownMerchants.put("fedwire credit", "transfer");
        knownMerchants.put("fedwire debit", "transfer");
        knownMerchants.put("routing number", "transfer");
        knownMerchants.put("aba routing number", "transfer");
        knownMerchants.put("aba number", "transfer");
        knownMerchants.put("routing transit number", "transfer");
        knownMerchants.put("rtn", "transfer");

        // ========== CHIPS (CLEARING HOUSE INTERBANK PAYMENTS SYSTEM) ==========
        knownMerchants.put("chips", "transfer");
        knownMerchants.put("clearing house interbank payments", "transfer");
        knownMerchants.put("chips transfer", "transfer");
        knownMerchants.put("chips credit", "transfer");
        knownMerchants.put("chips debit", "transfer");

        // ========== BAI2 BANK REPORTING FORMAT ==========
        // Bank Administration Institute format codes
        knownMerchants.put("bai2", "transfer");
        knownMerchants.put("bai 2", "transfer");
        knownMerchants.put("bai format", "transfer");
        knownMerchants.put("bai code", "transfer");
        // BAI2 Transaction Codes
        knownMerchants.put("bai 100", "transfer"); // Deposits
        knownMerchants.put("bai 101", "transfer"); // Checks Paid
        knownMerchants.put("bai 102", "transfer"); // Wire Transfer In
        knownMerchants.put("bai 103", "transfer"); // ACH Credit
        knownMerchants.put("bai 104", "transfer"); // ACH Debit
        knownMerchants.put("bai 105", "transfer"); // Book Transfer Credit
        knownMerchants.put("bai 106", "transfer"); // Book Transfer Debit
        knownMerchants.put("bai 107", "transfer"); // Other Credits
        knownMerchants.put("bai 108", "transfer"); // Other Debits
        knownMerchants.put("bai 109", "transfer"); // Interest Credit
        knownMerchants.put("bai 110", "transfer"); // Interest Debit
        knownMerchants.put("bai 111", "transfer"); // Service Charge Credit
        knownMerchants.put("bai 112", "transfer"); // Service Charge Debit
        knownMerchants.put("bai 115", "transfer"); // DTC Settlement
        knownMerchants.put("bai 116", "transfer"); // Fed Funds Sold
        knownMerchants.put("bai 118", "transfer"); // Fed Funds Purchased
        knownMerchants.put("bai 121", "transfer"); // Cash Letter Credit
        knownMerchants.put("bai 122", "transfer"); // Cash Letter Debit
        knownMerchants.put("bai 123", "transfer"); // Money Transfer
        knownMerchants.put("bai 124", "transfer"); // Automatic Transfer
        knownMerchants.put("bai 125", "transfer"); // International Transfer
        knownMerchants.put("bai 126", "transfer"); // Return Item
        knownMerchants.put("bai 127", "transfer"); // Return Item Fee
        knownMerchants.put("bai 128", "transfer"); // Concentration Credit
        knownMerchants.put("bai 129", "transfer"); // Concentration Debit
        knownMerchants.put("bai 130", "transfer"); // Loan Payment
        knownMerchants.put("bai 131", "transfer"); // Loan Disbursement
        knownMerchants.put("bai 135", "transfer"); // Deposit Correction
        knownMerchants.put("bai 136", "transfer"); // Deposit Correction Reversal
        knownMerchants.put("bai 140", "transfer"); // Credit Card Credit
        knownMerchants.put("bai 141", "transfer"); // Credit Card Debit
        knownMerchants.put("bai 142", "transfer"); // Credit Card Fee
        knownMerchants.put("bai 143", "transfer"); // Credit Card Interest
        knownMerchants.put("bai 150", "transfer"); // Investment Credit
        knownMerchants.put("bai 151", "transfer"); // Investment Debit
        knownMerchants.put("bai 155", "transfer"); // Dividend Credit
        knownMerchants.put("bai 156", "transfer"); // Dividend Debit
        knownMerchants.put("bai 160", "transfer"); // Securities Purchase
        knownMerchants.put("bai 161", "transfer"); // Securities Sale
        knownMerchants.put("bai 162", "transfer"); // Securities Interest
        knownMerchants.put("bai 163", "transfer"); // Securities Dividend
        knownMerchants.put("bai 164", "transfer"); // Securities Maturity
        knownMerchants.put("bai 165", "transfer"); // Securities Call
        knownMerchants.put("bai 170", "transfer"); // Bond Interest
        knownMerchants.put("bai 171", "transfer"); // Bond Principal
        knownMerchants.put("bai 172", "transfer"); // Bond Redemption
        knownMerchants.put("bai 180", "transfer"); // Mutual Fund Purchase
        knownMerchants.put("bai 181", "transfer"); // Mutual Fund Sale
        knownMerchants.put("bai 182", "transfer"); // Mutual Fund Dividend
        knownMerchants.put("bai 183", "transfer"); // Mutual Fund Capital Gain
        knownMerchants.put("bai 190", "transfer"); // Stock Purchase
        knownMerchants.put("bai 191", "transfer"); // Stock Sale
        knownMerchants.put("bai 192", "transfer"); // Stock Dividend
        knownMerchants.put("bai 193", "transfer"); // Stock Split
        knownMerchants.put("bai 194", "transfer"); // Stock Rights
        knownMerchants.put("bai 195", "transfer"); // Stock Warrant
        knownMerchants.put("bai 200", "transfer"); // Options Purchase
        knownMerchants.put("bai 201", "transfer"); // Options Sale
        knownMerchants.put("bai 202", "transfer"); // Options Exercise
        knownMerchants.put("bai 203", "transfer"); // Options Expiration
        knownMerchants.put("bai 210", "transfer"); // Futures Purchase
        knownMerchants.put("bai 211", "transfer"); // Futures Sale
        knownMerchants.put("bai 212", "transfer"); // Futures Settlement
        knownMerchants.put("bai 220", "transfer"); // Forex Purchase
        knownMerchants.put("bai 221", "transfer"); // Forex Sale
        knownMerchants.put("bai 222", "transfer"); // Forex Settlement

        // ========== CFONB (FRENCH BANK FORMAT) ==========
        knownMerchants.put("cfonb", "transfer");
        knownMerchants.put("cfonb 120", "transfer"); // French bank statement format
        knownMerchants.put("cfonb 240", "transfer"); // French bank statement format

        // ========== FINCEN (FINANCIAL CRIMES ENFORCEMENT NETWORK) ==========
        // BSA (Bank Secrecy Act) Transaction Categories
        knownMerchants.put("fincen", "transfer");
        knownMerchants.put("bsa", "transfer"); // Bank Secrecy Act
        knownMerchants.put("bank secrecy act", "transfer");
        knownMerchants.put("sar", "transfer"); // Suspicious Activity Report
        knownMerchants.put("suspicious activity report", "transfer");
        knownMerchants.put("ctr", "transfer"); // Currency Transaction Report
        knownMerchants.put("currency transaction report", "transfer");
        knownMerchants.put("sdn", "transfer"); // Specially Designated Nationals
        knownMerchants.put("specially designated nationals", "transfer");
        knownMerchants.put("ofac", "transfer"); // Office of Foreign Assets Control
        knownMerchants.put("office of foreign assets control", "transfer");
        // FINCEN Transaction Types
        knownMerchants.put("fincen 01", "transfer"); // Deposit
        knownMerchants.put("fincen 02", "transfer"); // Withdrawal
        knownMerchants.put("fincen 03", "transfer"); // Transfer
        knownMerchants.put("fincen 04", "transfer"); // Purchase
        knownMerchants.put("fincen 05", "transfer"); // Sale
        knownMerchants.put("fincen 06", "transfer"); // Exchange
        knownMerchants.put("fincen 07", "transfer"); // Loan
        knownMerchants.put("fincen 08", "transfer"); // Payment
        knownMerchants.put("fincen 09", "transfer"); // Other

        // ========== IRS TAX CATEGORIES ==========
        // IRS Form 1040 Categories and Business Expense Categories
        // IRS Income Categories (Form 1040)
        knownMerchants.put("irs w-2", "income"); // Wages, Salaries, Tips
        knownMerchants.put("irs w2", "income");
        knownMerchants.put("irs 1099", "income"); // Miscellaneous Income
        knownMerchants.put("irs 1099-misc", "income"); // Miscellaneous Income
        knownMerchants.put("irs 1099-int", "income"); // Interest Income
        knownMerchants.put("irs 1099-div", "income"); // Dividend Income
        knownMerchants.put("irs 1099-b", "income"); // Broker Transactions
        knownMerchants.put("irs 1099-r", "income"); // Retirement Distributions
        knownMerchants.put("irs 1099-g", "income"); // Government Payments
        knownMerchants.put("irs 1099-s", "income"); // Real Estate Transactions
        knownMerchants.put("irs 1099-k", "income"); // Payment Card Transactions
        knownMerchants.put("irs schedule c", "income"); // Business Income
        knownMerchants.put("irs schedule e", "income"); // Rental Income
        knownMerchants.put("irs schedule f", "income"); // Farm Income
        // IRS Business Expense Categories (Schedule C)
        knownMerchants.put("irs advertising", "shopping");
        knownMerchants.put("irs car and truck expenses", "transportation");
        knownMerchants.put("irs commissions and fees", "payment");
        knownMerchants.put("irs contract labor", "payment");
        knownMerchants.put("irs depreciation", "payment");
        knownMerchants.put("irs employee benefit programs", "payment");
        knownMerchants.put("irs insurance", "insurance");
        knownMerchants.put("irs interest", "payment");
        knownMerchants.put("irs legal and professional services", "service");
        knownMerchants.put("irs office expenses", "shopping");
        knownMerchants.put("irs pension and profit-sharing plans", "investment");
        knownMerchants.put("irs rent or lease", "rent");
        knownMerchants.put("irs repairs and maintenance", "home improvement");
        knownMerchants.put("irs supplies", "shopping");
        knownMerchants.put("irs taxes and licenses", "payment");
        knownMerchants.put("irs travel", "travel");
        knownMerchants.put("irs meals and entertainment", "dining");
        knownMerchants.put("irs utilities", "utilities");
        knownMerchants.put("irs wages", "income");
        knownMerchants.put("irs other expenses", "payment");
        // IRS Tax Payment Categories
        knownMerchants.put("irs estimated tax", "payment");
        knownMerchants.put("irs tax payment", "payment");
        knownMerchants.put("irs federal tax", "payment");
        knownMerchants.put("irs state tax", "payment");
        knownMerchants.put("irs local tax", "payment");
        knownMerchants.put("irs payroll tax", "payment");
        knownMerchants.put("irs self-employment tax", "payment");
        knownMerchants.put("irs income tax", "payment");
        knownMerchants.put("irs sales tax", "payment");
        knownMerchants.put("irs property tax", "payment");
        knownMerchants.put("irs excise tax", "payment");
        knownMerchants.put("irs gift tax", "payment");
        knownMerchants.put("irs estate tax", "payment");

        // ========== GAAP (GENERALLY ACCEPTED ACCOUNTING PRINCIPLES) ==========
        // Chart of Accounts Categories
        knownMerchants.put("gaap", "transfer");
        knownMerchants.put("generally accepted accounting principles", "transfer");
        // GAAP Account Categories
        knownMerchants.put("gaap assets", "investment");
        knownMerchants.put("gaap current assets", "investment");
        knownMerchants.put("gaap cash", "cash");
        knownMerchants.put("gaap accounts receivable", "income");
        knownMerchants.put("gaap inventory", "shopping");
        knownMerchants.put("gaap prepaid expenses", "payment");
        knownMerchants.put("gaap fixed assets", "investment");
        knownMerchants.put("gaap property plant equipment", "investment");
        knownMerchants.put("gaap liabilities", "payment");
        knownMerchants.put("gaap current liabilities", "payment");
        knownMerchants.put("gaap accounts payable", "payment");
        knownMerchants.put("gaap accrued expenses", "payment");
        knownMerchants.put("gaap long-term debt", "payment");
        knownMerchants.put("gaap equity", "investment");
        knownMerchants.put("gaap revenue", "income");
        knownMerchants.put("gaap sales revenue", "income");
        knownMerchants.put("gaap service revenue", "income");
        knownMerchants.put("gaap interest revenue", "income");
        knownMerchants.put("gaap dividend revenue", "income");
        knownMerchants.put("gaap expenses", "payment");
        knownMerchants.put("gaap cost of goods sold", "shopping");
        knownMerchants.put("gaap operating expenses", "payment");
        knownMerchants.put("gaap selling expenses", "payment");
        knownMerchants.put("gaap administrative expenses", "payment");
        knownMerchants.put("gaap interest expense", "payment");
        knownMerchants.put("gaap tax expense", "payment");
        knownMerchants.put("gaap depreciation expense", "payment");
        knownMerchants.put("gaap amortization expense", "payment");

        // ========== SIC (STANDARD INDUSTRIAL CLASSIFICATION) CODES ==========
        // Major SIC Code Categories (2-digit)
        knownMerchants.put("sic 01", "groceries"); // Agricultural Production - Crops
        knownMerchants.put("sic 02", "groceries"); // Agricultural Production - Livestock
        knownMerchants.put("sic 07", "groceries"); // Agricultural Services
        knownMerchants.put("sic 10", "utilities"); // Metal Mining
        knownMerchants.put("sic 13", "utilities"); // Oil and Gas Extraction
        knownMerchants.put("sic 15", "home improvement"); // General Building Contractors
        knownMerchants.put("sic 17", "home improvement"); // Special Trade Contractors
        knownMerchants.put("sic 20", "groceries"); // Food and Kindred Products
        knownMerchants.put("sic 21", "utilities"); // Tobacco Products
        knownMerchants.put("sic 22", "shopping"); // Textile Mill Products
        knownMerchants.put("sic 23", "shopping"); // Apparel and Other Textile Products
        knownMerchants.put("sic 24", "home improvement"); // Lumber and Wood Products
        knownMerchants.put("sic 25", "home improvement"); // Furniture and Fixtures
        knownMerchants.put("sic 26", "shopping"); // Paper and Allied Products
        knownMerchants.put("sic 27", "shopping"); // Printing and Publishing
        knownMerchants.put("sic 28", "healthcare"); // Chemicals and Allied Products
        knownMerchants.put("sic 29", "utilities"); // Petroleum and Coal Products
        knownMerchants.put("sic 30", "shopping"); // Rubber and Miscellaneous Plastics
        knownMerchants.put("sic 31", "shopping"); // Leather and Leather Products
        knownMerchants.put("sic 32", "shopping"); // Stone, Clay, and Glass Products
        knownMerchants.put("sic 33", "shopping"); // Primary Metal Industries
        knownMerchants.put("sic 34", "shopping"); // Fabricated Metal Products
        knownMerchants.put("sic 35", "tech"); // Industrial Machinery and Equipment
        knownMerchants.put("sic 36", "tech"); // Electronic and Other Electrical Equipment
        knownMerchants.put("sic 37", "transportation"); // Transportation Equipment
        knownMerchants.put("sic 38", "tech"); // Instruments and Related Products
        knownMerchants.put("sic 39", "shopping"); // Miscellaneous Manufacturing Industries
        knownMerchants.put("sic 40", "utilities"); // Railroad Transportation
        knownMerchants.put("sic 41", "utilities"); // Local and Interurban Passenger Transit
        knownMerchants.put("sic 42", "transportation"); // Trucking and Warehousing
        knownMerchants.put("sic 44", "transportation"); // Water Transportation
        knownMerchants.put("sic 45", "transportation"); // Transportation by Air
        knownMerchants.put("sic 46", "transfer"); // Pipelines, Except Natural Gas
        knownMerchants.put("sic 47", "transportation"); // Transportation Services
        knownMerchants.put("sic 48", "utilities"); // Communications
        knownMerchants.put("sic 49", "utilities"); // Electric, Gas, and Sanitary Services
        knownMerchants.put("sic 50", "shopping"); // Wholesale Trade - Durable Goods
        knownMerchants.put("sic 51", "shopping"); // Wholesale Trade - Non-Durable Goods
        knownMerchants.put("sic 52", "shopping"); // Building Materials and Garden Supplies
        knownMerchants.put("sic 53", "shopping"); // General Merchandise Stores
        knownMerchants.put("sic 54", "groceries"); // Food Stores
        knownMerchants.put("sic 55", "shopping"); // Automotive Dealers and Service Stations
        knownMerchants.put("sic 56", "shopping"); // Apparel and Accessory Stores
        knownMerchants.put("sic 57", "shopping"); // Furniture and Home Furnishings Stores
        knownMerchants.put("sic 58", "dining"); // Eating and Drinking Places
        knownMerchants.put("sic 59", "shopping"); // Miscellaneous Retail
        knownMerchants.put("sic 60", "investment"); // Depository Institutions
        knownMerchants.put("sic 61", "investment"); // Non-Depository Institutions
        knownMerchants.put("sic 62", "investment"); // Security and Commodity Brokers
        knownMerchants.put("sic 63", "insurance"); // Insurance Carriers
        knownMerchants.put("sic 64", "insurance"); // Insurance Agents, Brokers, and Service
        knownMerchants.put("sic 65", "investment"); // Real Estate
        knownMerchants.put("sic 67", "investment"); // Holding and Other Investment Offices
        knownMerchants.put("sic 70", "service"); // Hotels and Other Lodging Places
        knownMerchants.put("sic 72", "dining"); // Personal Services
        knownMerchants.put("sic 73", "service"); // Business Services
        knownMerchants.put("sic 75", "transportation"); // Automotive Repair Services
        knownMerchants.put("sic 76", "service"); // Miscellaneous Repair Services
        knownMerchants.put("sic 78", "entertainment"); // Motion Pictures
        knownMerchants.put("sic 79", "entertainment"); // Amusement and Recreation Services
        knownMerchants.put("sic 80", "healthcare"); // Health Services
        knownMerchants.put("sic 81", "service"); // Legal Services
        knownMerchants.put("sic 82", "service"); // Educational Services
        knownMerchants.put("sic 83", "service"); // Social Services
        knownMerchants.put("sic 84", "service"); // Museums, Botanical, Zoological Gardens
        knownMerchants.put("sic 86", "service"); // Membership Organizations
        knownMerchants.put("sic 87", "service"); // Engineering, Accounting, Research
        knownMerchants.put("sic 88", "service"); // Private Households
        knownMerchants.put("sic 89", "service"); // Services, Not Elsewhere Classified
        knownMerchants.put("sic 91", "utilities"); // Executive, Legislative, and General Government
        knownMerchants.put("sic 92", "utilities"); // Justice, Public Order, and Safety
        knownMerchants.put("sic 93", "utilities"); // Finance, Taxation, and Monetary Policy
        knownMerchants.put("sic 94", "utilities"); // Administration of Human Resources
        knownMerchants.put("sic 95", "utilities"); // Environmental Quality and Housing
        knownMerchants.put("sic 96", "utilities"); // Administration of Economic Programs
        knownMerchants.put("sic 97", "utilities"); // National Security and International Affairs

        // ========== NAICS (NORTH AMERICAN INDUSTRY CLASSIFICATION SYSTEM) CODES ==========
        // Major NAICS Code Categories (2-digit)
        knownMerchants.put("naics 11", "groceries"); // Agriculture, Forestry, Fishing and Hunting
        knownMerchants.put(
                "naics 21", "utilities"); // Mining, Quarrying, and Oil and Gas Extraction
        knownMerchants.put("naics 22", "utilities"); // Utilities
        knownMerchants.put("naics 23", "home improvement"); // Construction
        knownMerchants.put("naics 31", "shopping"); // Manufacturing - Food, Beverage, Tobacco
        knownMerchants.put("naics 32", "shopping"); // Manufacturing - Textile, Apparel, Leather
        knownMerchants.put("naics 33", "shopping"); // Manufacturing - Wood, Paper, Printing
        knownMerchants.put("naics 34", "shopping"); // Manufacturing - Petroleum, Chemical, Plastics
        knownMerchants.put("naics 35", "tech"); // Manufacturing - Computer, Electronic, Electrical
        knownMerchants.put(
                "naics 36", "transportation"); // Manufacturing - Transportation Equipment
        knownMerchants.put("naics 42", "shopping"); // Wholesale Trade
        knownMerchants.put(
                "naics 44", "shopping"); // Retail Trade - Building Materials, Garden, Motor Vehicle
        knownMerchants.put(
                "naics 45", "shopping"); // Retail Trade - Furniture, Electronics, Apparel
        knownMerchants.put("naics 48", "transportation"); // Transportation and Warehousing
        knownMerchants.put("naics 49", "utilities"); // Couriers and Messengers
        knownMerchants.put("naics 51", "tech"); // Information
        knownMerchants.put("naics 52", "investment"); // Finance and Insurance
        knownMerchants.put("naics 53", "investment"); // Real Estate and Rental and Leasing
        knownMerchants.put(
                "naics 54", "service"); // Professional, Scientific, and Technical Services
        knownMerchants.put("naics 55", "service"); // Management of Companies and Enterprises
        knownMerchants.put("naics 56", "service"); // Administrative and Support Services
        knownMerchants.put("naics 61", "service"); // Educational Services
        knownMerchants.put("naics 62", "healthcare"); // Health Care and Social Assistance
        knownMerchants.put("naics 71", "entertainment"); // Arts, Entertainment, and Recreation
        knownMerchants.put("naics 72", "dining"); // Accommodation and Food Services
        knownMerchants.put("naics 81", "service"); // Other Services (except Public Administration)
        knownMerchants.put("naics 92", "utilities"); // Public Administration

        // ========== EDUCATION/SCHOOL PAYMENTS ==========
        // School District payments - should be categorized as "education"
        knownMerchants.put("school district", "education");
        knownMerchants.put("schooldistrict", "education");
        knownMerchants.put("bellevue school district", "education");
        knownMerchants.put("bellevueschooldistrict", "education");

        // School types (middle school, high school, elementary school, etc.)
        knownMerchants.put("middle school", "education");
        knownMerchants.put("middleschool", "education");
        knownMerchants.put("high school", "education");
        knownMerchants.put("highschool", "education");
        knownMerchants.put("elementary school", "education");
        knownMerchants.put("elementaryschool", "education");
        knownMerchants.put("elementary", "education");
        knownMerchants.put("secondary school", "education");
        knownMerchants.put("secondaryschool", "education");
        knownMerchants.put("senior secondary school", "education");
        knownMerchants.put("seniorschool", "education");
        knownMerchants.put("tyee middle school", "education");
        knownMerchants.put("tyeemiddleschool", "education");

        // Educational media (newspapers, magazines, journals, books)
        knownMerchants.put("newspaper", "education");
        knownMerchants.put("magazine", "education");
        knownMerchants.put("journal", "education");
        knownMerchants.put("academic journal", "education");
        knownMerchants.put("research journal", "education");
        knownMerchants.put("scientific journal", "education");
        knownMerchants.put("library", "education");

        // Advanced degrees
        knownMerchants.put("phd", "education");
        knownMerchants.put("ph.d", "education");
        knownMerchants.put("ph.d.", "education");
        knownMerchants.put("doctorate", "education");
        knownMerchants.put("graduate school", "education");
        knownMerchants.put("graduateschool", "education");

        // ========== HEALTH/BEAUTY SERVICES ==========
        // Stop 4 Nails - nail salon (health)
        knownMerchants.put("stop 4 nails", "health");
        knownMerchants.put("stop4nails", "health");
        knownMerchants.put("stop four nails", "health");
        knownMerchants.put("stopfournails", "health");
        // Cosmetic Stores - health
        knownMerchants.put("new york cosmetic store", "health");
        knownMerchants.put("newyorkcosmeticstore", "health");
        knownMerchants.put("ny cosmetic store", "health");
        knownMerchants.put("nycosmeticstore", "health");
        knownMerchants.put("cosmetic store", "health");
        knownMerchants.put("cosmeticstore", "health");
        knownMerchants.put("cosmetics", "health");
        knownMerchants.put("makeup store", "health");
        knownMerchants.put("makeupstore", "health");

        // ========== EDUCATION ==========
        // Education items (school, books, reading, etc.) - categorized as "education"
        // Regional school/college names
        knownMerchants.put("gurukul", "education");
        knownMerchants.put("vidyalaya", "education");
        knownMerchants.put("shiksha", "education");
        knownMerchants.put("pathshala", "education");
        knownMerchants.put("escuela", "education");
        knownMerchants.put("colegio", "education");
        knownMerchants.put("universidad", "education");
        knownMerchants.put("école", "education");
        knownMerchants.put("collège", "education");
        knownMerchants.put("université", "education");
        knownMerchants.put("schule", "education");
        knownMerchants.put("universität", "education");
        knownMerchants.put("madrasa", "education");
        knownMerchants.put("madrassa", "education");
        knownMerchants.put("kuttab", "education");
        knownMerchants.put("school", "education");
        knownMerchants.put("university", "education");
        knownMerchants.put("college", "education");
        knownMerchants.put("tuition", "education");
        knownMerchants.put("books", "education");
        knownMerchants.put("bookstore", "education");
        knownMerchants.put("book store", "education");
        knownMerchants.put("reading", "education");
        knownMerchants.put("textbook", "education");
        knownMerchants.put("text book", "education");
        knownMerchants.put("education", "education");
        knownMerchants.put("educational", "education");
        knownMerchants.put("course", "education");
        knownMerchants.put("class", "education");
        knownMerchants.put("lesson", "education");
        knownMerchants.put("training", "education");

        // Exam/testing services and keywords (AAMC, SAT, TOEFL, GRE, GMAT, LSAT, MCAT, etc.)
        knownMerchants.put("pearson vue", "education");
        knownMerchants.put("pearsonvue", "education");
        knownMerchants.put("vue", "education"); // Pearson VUE testing center
        knownMerchants.put("aamc", "education"); // Medical College Admission Test
        knownMerchants.put("sat", "education"); // Scholastic Assessment Test
        knownMerchants.put("toefl", "education"); // Test of English as a Foreign Language
        knownMerchants.put("gre", "education"); // Graduate Record Examination
        knownMerchants.put("gmat", "education"); // Graduate Management Admission Test
        knownMerchants.put("lsat", "education"); // Law School Admission Test
        knownMerchants.put("mcat", "education"); // Medical College Admission Test
        knownMerchants.put("act", "education"); // American College Testing
        knownMerchants.put("ap exam", "education"); // Advanced Placement
        knownMerchants.put("ib exam", "education"); // International Baccalaureate
        knownMerchants.put("clep", "education"); // College Level Examination Program
        knownMerchants.put("praxis", "education"); // Teacher certification exam
        knownMerchants.put("bar exam", "education"); // Bar examination
        knownMerchants.put("nclex", "education"); // Nursing exam
        knownMerchants.put("usmle", "education"); // Medical licensing exam
        knownMerchants.put("comlex", "education"); // Osteopathic medical licensing exam
        knownMerchants.put("ets", "education"); // Educational Testing Service
        knownMerchants.put("prometric", "education"); // Testing center
        knownMerchants.put("test registration", "education");
        knownMerchants.put("test fee", "education");
        knownMerchants.put("test center", "education");
        knownMerchants.put("exam fee", "education");
        knownMerchants.put("exam registration", "education");

        // ========== ANSI X9 FINANCIAL SERVICES STANDARDS ==========
        knownMerchants.put("ansi x9", "transfer");
        knownMerchants.put("ansix9", "transfer");
        knownMerchants.put("ansi x9.13", "transfer"); // Bank Account Numbers
        knownMerchants.put("ansi x9.100", "transfer"); // Check Standards
        knownMerchants.put("ansi x9.100-140", "transfer"); // Check Standards
        knownMerchants.put("ansi x9.37", "transfer"); // Check Image Exchange
        knownMerchants.put("ansi x9.100-187", "transfer"); // Check Image Exchange

        // ========== INTER-BANK TRANSFER SYSTEMS ==========
        // TARGET2 (Trans-European Automated Real-time Gross Settlement Express Transfer)
        knownMerchants.put("target2", "transfer");
        knownMerchants.put("target 2", "transfer");
        knownMerchants.put("target2 transfer", "transfer");
        // CHAPS (Clearing House Automated Payment System - UK)
        knownMerchants.put("chaps", "transfer");
        knownMerchants.put("chaps transfer", "transfer");
        // BACS (Bankers' Automated Clearing Services - UK)
        knownMerchants.put("bacs", "transfer");
        knownMerchants.put("bacs transfer", "transfer");
        knownMerchants.put("bacs direct debit", "payment");
        knownMerchants.put("bacs direct credit", "income");
        // Faster Payments (UK)
        knownMerchants.put("faster payments", "transfer");
        knownMerchants.put("faster payments service", "transfer");
        knownMerchants.put("fps", "transfer");
        // EFT (Electronic Funds Transfer - Canada)
        knownMerchants.put("eft", "transfer");
        knownMerchants.put("electronic funds transfer", "transfer");
        knownMerchants.put("eft canada", "transfer");
        // Interac (Canada)
        knownMerchants.put("interac", "transfer");
        knownMerchants.put("interac e-transfer", "transfer");
        knownMerchants.put("interac etransfer", "transfer");
        // NPP (New Payments Platform - Australia)
        knownMerchants.put("npp", "transfer");
        knownMerchants.put("new payments platform", "transfer");
        knownMerchants.put("npp australia", "transfer");
        // NEFT (National Electronic Funds Transfer - India)
        knownMerchants.put("neft", "transfer");
        knownMerchants.put("national electronic funds transfer", "transfer");
        knownMerchants.put("neft india", "transfer");
        // RTGS (Real Time Gross Settlement - India)
        knownMerchants.put("rtgs", "transfer");
        knownMerchants.put("real time gross settlement", "transfer");
        knownMerchants.put("rtgs india", "transfer");
        // IMPS (Immediate Payment Service - India)
        knownMerchants.put("imps", "transfer");
        knownMerchants.put("immediate payment service", "transfer");
        knownMerchants.put("imps india", "transfer");
        // UPI (Unified Payments Interface - India)
        knownMerchants.put("upi", "transfer");
        knownMerchants.put("unified payments interface", "transfer");
        knownMerchants.put("upi india", "transfer");
        // SEPA Instant (Europe)
        // TIPS (TARGET Instant Payment Settlement - Europe)
        knownMerchants.put("tips", "transfer");
        knownMerchants.put("target instant payment settlement", "transfer");

        // ========== SEMANTIC KEYWORDS FROM SemanticMatchingService (STATICALLY MERGED) ==========
        // All semantic cluster keywords are now statically included here for manual review
        // These were previously merged at runtime via addSemanticCategoriesToFuzzyDatabase()

        // ========== SEMANTIC: GROCERIES KEYWORDS ==========
        // Note: Many grocery store names are already above, adding semantic cluster keywords
        knownMerchants.put("halal", "groceries");
        knownMerchants.put("food market", "groceries");
        knownMerchants.put("grocery store", "groceries");
        knownMerchants.put("food store", "groceries");
        knownMerchants.put("foodmart", "groceries");
        knownMerchants.put("grocery market", "groceries");
        knownMerchants.put("food mart", "groceries");
        knownMerchants.put("food center", "groceries");
        knownMerchants.put("grocery center", "groceries");
        knownMerchants.put("supermarket shopping", "groceries");
        knownMerchants.put("store", "groceries"); // Generic store (context-dependent)

        // ========== SEMANTIC: DINING KEYWORDS ==========
        // Note: Many restaurant names are already above, adding semantic cluster keywords
        knownMerchants.put("restaur", "dining");
        knownMerchants.put("diner", "dining");
        knownMerchants.put("café", "dining");
        knownMerchants.put("caffe", "dining");
        knownMerchants.put("caffee", "dining");
        knownMerchants.put("coffee", "dining");
        knownMerchants.put("bistro", "dining");
        knownMerchants.put("eatery", "dining");
        knownMerchants.put("meal", "dining");
        knownMerchants.put("lunch", "dining");
        knownMerchants.put("dinner", "dining");
        knownMerchants.put("breakfast", "dining");
        knownMerchants.put("brunch", "dining");
        knownMerchants.put("takeout", "dining");
        knownMerchants.put("take-out", "dining");
        knownMerchants.put("delivery", "dining");
        knownMerchants.put("food delivery", "dining");
        knownMerchants.put("tiffin", "dining");
        knownMerchants.put("tst*", "dining"); // Toast POS system
        knownMerchants.put("tst", "dining");
        knownMerchants.put("toast", "dining");
        knownMerchants.put("toast pos", "dining");
        knownMerchants.put("sq*", "dining"); // Square POS system (restaurant context)
        knownMerchants.put("sq", "dining");
        knownMerchants.put("square pos", "dining");
        knownMerchants.put("square inc", "dining");
        knownMerchants.put("dumplings", "dining");
        knownMerchants.put("dumpling", "dining");
        knownMerchants.put("grill", "dining");
        knownMerchants.put("grilled", "dining");
        knownMerchants.put("thai", "dining");
        knownMerchants.put("dhaba", "dining");
        knownMerchants.put("brewing", "dining");
        knownMerchants.put("brewery", "dining");
        knownMerchants.put("brew pub", "dining");
        knownMerchants.put("brewpub", "dining");
        knownMerchants.put("restaurant meal", "dining");
        knownMerchants.put("dining out", "dining");
        knownMerchants.put("eat out", "dining");
        knownMerchants.put("food service", "dining");
        knownMerchants.put("catering", "dining");
        knownMerchants.put("mediterranean", "dining");
        knownMerchants.put("mediterranean food", "dining");
        knownMerchants.put("mediterranean restaurant", "dining");
        knownMerchants.put("mediterranean cuisine", "dining");
        knownMerchants.put("mexican", "dining");
        knownMerchants.put("mexican food", "dining");
        knownMerchants.put("mexican restaurant", "dining");
        knownMerchants.put("mexican cuisine", "dining");
        knownMerchants.put("japanese", "dining");
        knownMerchants.put("japanese food", "dining");
        knownMerchants.put("japanese restaurant", "dining");
        knownMerchants.put("japanese cuisine", "dining");
        knownMerchants.put("korean", "dining");
        knownMerchants.put("korean food", "dining");
        knownMerchants.put("korean restaurant", "dining");
        knownMerchants.put("korean cuisine", "dining");
        knownMerchants.put("indian", "dining");
        knownMerchants.put("indian food", "dining");
        knownMerchants.put("indian restaurant", "dining");
        knownMerchants.put("indian cuisine", "dining");
        knownMerchants.put("persian", "dining");
        knownMerchants.put("persian food", "dining");
        knownMerchants.put("persian restaurant", "dining");
        knownMerchants.put("persian cuisine", "dining");
        knownMerchants.put("vietnamese", "dining");
        knownMerchants.put("vietnamese food", "dining");
        knownMerchants.put("vietnamese restaurant", "dining");
        knownMerchants.put("vietnamese cuisine", "dining");
        knownMerchants.put("indonesian", "dining");
        knownMerchants.put("indonesian food", "dining");
        knownMerchants.put("indonesian restaurant", "dining");
        knownMerchants.put("indonesian cuisine", "dining");
        knownMerchants.put("malaysian", "dining");
        knownMerchants.put("malaysian food", "dining");
        knownMerchants.put("malaysian restaurant", "dining");
        knownMerchants.put("malaysian cuisine", "dining");
        knownMerchants.put("singaporean", "dining");
        knownMerchants.put("singaporean food", "dining");
        knownMerchants.put("singaporean restaurant", "dining");
        knownMerchants.put("singaporean cuisine", "dining");
        knownMerchants.put("filipino", "dining");
        knownMerchants.put("filipino food", "dining");
        knownMerchants.put("filipino restaurant", "dining");
        knownMerchants.put("filipino cuisine", "dining");
        knownMerchants.put("hawaiian food", "dining");
        knownMerchants.put("hawaiian restaurant", "dining");
        knownMerchants.put("hawaiian cuisine", "dining");
        knownMerchants.put("philippine", "dining");
        knownMerchants.put("philippine food", "dining");
        knownMerchants.put("philippine restaurant", "dining");
        knownMerchants.put("philippine cuisine", "dining");
        knownMerchants.put("sushi", "dining");
        knownMerchants.put("sushi restaurant", "dining");
        knownMerchants.put("sushi bar", "dining");
        knownMerchants.put("sushi cuisine", "dining");
        knownMerchants.put("ramen", "dining");
        knownMerchants.put("ramen restaurant", "dining");
        knownMerchants.put("ramen bar", "dining");
        knownMerchants.put("ramen cuisine", "dining");
        knownMerchants.put("tonkatsu", "dining");
        knownMerchants.put("tonkatsu restaurant", "dining");
        knownMerchants.put("katsu", "dining");
        knownMerchants.put("karange", "dining");
        knownMerchants.put("burrito", "dining");
        knownMerchants.put("tacos", "dining");
        knownMerchants.put("yakiudon", "dining");
        knownMerchants.put("yakiudon restaurant", "dining");
        knownMerchants.put("yakiudon bar", "dining");
        knownMerchants.put("yakiudon cuisine", "dining");
        knownMerchants.put("gyudon", "dining");
        knownMerchants.put("gyudon restaurant", "dining");
        knownMerchants.put("gyudon bar", "dining");
        knownMerchants.put("gyudon cuisine", "dining");
        knownMerchants.put("bbq", "dining");
        knownMerchants.put("bbq restaurant", "dining");
        knownMerchants.put("bbq bar", "dining");
        knownMerchants.put("bbq cuisine", "dining");
        knownMerchants.put("noodles", "dining");
        knownMerchants.put("noodle restaurant", "dining");
        knownMerchants.put("noodle bar", "dining");
        knownMerchants.put("noodle cuisine", "dining");
        knownMerchants.put("dim sum", "dining");
        knownMerchants.put("dim sum restaurant", "dining");
        knownMerchants.put("dim sum bar", "dining");
        knownMerchants.put("dim sum cuisine", "dining");
        knownMerchants.put("chinese", "dining");
        knownMerchants.put("chinese food", "dining");
        knownMerchants.put("chinese restaurant", "dining");
        knownMerchants.put("chinese cuisine", "dining");
        knownMerchants.put("cafe coffee", "dining");
        knownMerchants.put("coffee purchase", "dining");
        knownMerchants.put("bread", "dining");
        knownMerchants.put("pastry", "dining");
        knownMerchants.put("baker", "dining");
        knownMerchants.put("cake", "dining");
        knownMerchants.put("takeout delivery", "dining");
        knownMerchants.put("italian", "dining");
        knownMerchants.put("french", "dining");
        knownMerchants.put("spanish", "dining");

        // ========== SEMANTIC: TRANSPORTATION KEYWORDS ==========
        // Note: Many gas stations and transportation services are already above
        knownMerchants.put("gasoline", "transportation");
        knownMerchants.put("petrol", "transportation");
        knownMerchants.put("diesel", "transportation");
        knownMerchants.put("gas station", "transportation");
        knownMerchants.put("fuel station", "transportation");
        knownMerchants.put("petrol station", "transportation");
        knownMerchants.put("filling station", "transportation");
        knownMerchants.put("service station", "transportation");
        knownMerchants.put("fuel purchase", "transportation");
        knownMerchants.put("petrol fill up", "transportation");
        knownMerchants.put("fill up", "transportation");
        knownMerchants.put("toll", "transportation");
        knownMerchants.put("parking fee", "transportation");
        knownMerchants.put("taxi", "transportation");
        knownMerchants.put("cab", "transportation");
        knownMerchants.put("ride share", "transportation");
        knownMerchants.put("ride", "transportation");
        knownMerchants.put("uber ride", "transportation");
        knownMerchants.put("lyft ride", "transportation");
        knownMerchants.put("public transport", "transportation");
        knownMerchants.put("transit", "transportation");
        knownMerchants.put("metro", "transportation");
        knownMerchants.put("bus", "transportation");
        knownMerchants.put("train", "transportation");
        knownMerchants.put("vehicle", "transportation");
        knownMerchants.put("automobile", "transportation");
        knownMerchants.put("transport", "transportation");
        knownMerchants.put("commute", "transportation");
        knownMerchants.put("state dot", "transportation");
        knownMerchants.put("state department of transportation", "transportation");
        knownMerchants.put("department of transportation", "transportation");
        knownMerchants.put("dot toll", "transportation");
        knownMerchants.put("toll road", "transportation");
        knownMerchants.put("tollway", "transportation");
        knownMerchants.put("toll authority", "transportation");
        knownMerchants.put("highway authority", "transportation");
        knownMerchants.put("transportation authority", "transportation");
        knownMerchants.put("turnpike authority", "transportation");
        knownMerchants.put("toll payment", "transportation");
        knownMerchants.put("toll charge", "transportation");
        knownMerchants.put("toll fee", "transportation");
        knownMerchants.put("road toll", "transportation");
        knownMerchants.put("bridge toll", "transportation");
        knownMerchants.put("tunnel toll", "transportation");
        knownMerchants.put("highway toll", "transportation");
        knownMerchants.put("expressway toll", "transportation");
        knownMerchants.put("car service", "transportation");
        knownMerchants.put("carservice", "transportation");
        // Car service centers (already have many above, adding semantic keywords)
        knownMerchants.put("honda ctr", "transportation");
        knownMerchants.put("hondactr", "transportation");
        knownMerchants.put("honda car service", "transportation");
        knownMerchants.put("hondacarservice", "transportation");
        knownMerchants.put("toyota ctr", "transportation");
        knownMerchants.put("toyotactr", "transportation");
        knownMerchants.put("toyota car service", "transportation");
        knownMerchants.put("toyotacarservice", "transportation");
        knownMerchants.put("nissan ctr", "transportation");
        knownMerchants.put("nissactr", "transportation");
        knownMerchants.put("nissan car service", "transportation");
        knownMerchants.put("nissacarservice", "transportation");
        knownMerchants.put("ford ctr", "transportation");
        knownMerchants.put("fordctr", "transportation");
        knownMerchants.put("ford car service", "transportation");
        knownMerchants.put("fordcarservice", "transportation");
        knownMerchants.put("chevrolet ctr", "transportation");
        knownMerchants.put("chevroletctr", "transportation");
        knownMerchants.put("chevrolet car service", "transportation");
        knownMerchants.put("chevroletcarservice", "transportation");
        knownMerchants.put("hyundai ctr", "transportation");
        knownMerchants.put("hyundaictr", "transportation");
        knownMerchants.put("hyundai car service", "transportation");
        knownMerchants.put("hyundaicarservice", "transportation");
        knownMerchants.put("kia ctr", "transportation");
        knownMerchants.put("kiactr", "transportation");
        knownMerchants.put("kia car service", "transportation");
        knownMerchants.put("kiacarservice", "transportation");
        knownMerchants.put("volkswagen ctr", "transportation");
        knownMerchants.put("volkswagenctr", "transportation");
        knownMerchants.put("volkswagen car service", "transportation");
        knownMerchants.put("volkswagencarservice", "transportation");
        knownMerchants.put("bmw ctr", "transportation");
        knownMerchants.put("bmwctr", "transportation");
        knownMerchants.put("bmw car service", "transportation");
        knownMerchants.put("bmwcarservice", "transportation");
        knownMerchants.put("audi ctr", "transportation");
        knownMerchants.put("audictr", "transportation");
        knownMerchants.put("audi car service", "transportation");
        knownMerchants.put("audicarservice", "transportation");
        knownMerchants.put("mercedes-benz ctr", "transportation");
        knownMerchants.put("mercedes-benzctr", "transportation");
        knownMerchants.put("mercedes-benz car service", "transportation");
        knownMerchants.put("mercedes-benzcarservice", "transportation");
        knownMerchants.put("porsche ctr", "transportation");
        knownMerchants.put("porschectr", "transportation");
        knownMerchants.put("porsche car service", "transportation");
        knownMerchants.put("porschecarservice", "transportation");
        knownMerchants.put("volvo ctr", "transportation");
        knownMerchants.put("volvoctr", "transportation");
        knownMerchants.put("volvo car service", "transportation");
        knownMerchants.put("volvocarservice", "transportation");
        knownMerchants.put("land rover ctr", "transportation");
        knownMerchants.put("land roverctr", "transportation");
        knownMerchants.put("land rover car service", "transportation");
        knownMerchants.put("land rovercarservice", "transportation");
        knownMerchants.put("jeep ctr", "transportation");
        knownMerchants.put("jeepctr", "transportation");
        knownMerchants.put("jeep car service", "transportation");
        knownMerchants.put("jeepcarservice", "transportation");
        knownMerchants.put("ram ctr", "transportation");
        knownMerchants.put("ramctr", "transportation");
        knownMerchants.put("ram car service", "transportation");
        knownMerchants.put("ramcarservice", "transportation");
        knownMerchants.put("gmc ctr", "transportation");
        knownMerchants.put("gmcctr", "transportation");
        knownMerchants.put("gmc car service", "transportation");
        knownMerchants.put("gmccarservice", "transportation");
        knownMerchants.put("dodge ctr", "transportation");
        knownMerchants.put("dodgectr", "transportation");
        knownMerchants.put("dodge car service", "transportation");
        knownMerchants.put("dodgecarservice", "transportation");
        knownMerchants.put("chrysler ctr", "transportation");
        knownMerchants.put("chryslerctr", "transportation");
        knownMerchants.put("chrysler car service", "transportation");
        knownMerchants.put("chryslercarservice", "transportation");
        knownMerchants.put("fiat ctr", "transportation");
        knownMerchants.put("fiatctr", "transportation");
        knownMerchants.put("fiat car service", "transportation");
        knownMerchants.put("fiatacarservice", "transportation");
        knownMerchants.put("alfa romeo ctr", "transportation");
        knownMerchants.put("alfa romeoctr", "transportation");
        knownMerchants.put("alfa romeo car service", "transportation");
        knownMerchants.put("alfa romeocarservice", "transportation");
        knownMerchants.put("aston martin ctr", "transportation");
        knownMerchants.put("aston martinctr", "transportation");
        knownMerchants.put("aston martin car service", "transportation");
        knownMerchants.put("aston martincarservice", "transportation");
        knownMerchants.put("bentley ctr", "transportation");
        knownMerchants.put("bentleyctr", "transportation");
        knownMerchants.put("bentley car service", "transportation");
        knownMerchants.put("bentleycarservice", "transportation");
        knownMerchants.put("bugatti ctr", "transportation");
        knownMerchants.put("bugattictr", "transportation");
        knownMerchants.put("bugatti car service", "transportation");
        knownMerchants.put("bugatticarservice", "transportation");
        knownMerchants.put("ferrari ctr", "transportation");
        knownMerchants.put("ferrarictr", "transportation");
        knownMerchants.put("ferrari car service", "transportation");
        knownMerchants.put("ferraricarservice", "transportation");
        knownMerchants.put("lamborghini ctr", "transportation");
        knownMerchants.put("lamborghinictr", "transportation");
        knownMerchants.put("lamborghini car service", "transportation");
        knownMerchants.put("lamborghinicarservice", "transportation");
        knownMerchants.put("mclaren ctr", "transportation");
        knownMerchants.put("mclarenctr", "transportation");
        knownMerchants.put("mclaren car service", "transportation");
        knownMerchants.put("mclarencarservice", "transportation");
        knownMerchants.put("rolls-royce ctr", "transportation");
        knownMerchants.put("rolls-roycectr", "transportation");
        knownMerchants.put("rolls-royce car service", "transportation");
        knownMerchants.put("rolls-roycecarservice", "transportation");
        knownMerchants.put("arco express", "transportation");
        knownMerchants.put("arco express gas", "transportation");

        // ========== SEMANTIC: HEALTHCARE KEYWORDS ==========
        // Note: Many pharmacies and healthcare providers are already above
        knownMerchants.put("drugstore", "healthcare");
        knownMerchants.put("medicine", "healthcare");
        knownMerchants.put("medication", "healthcare");
        knownMerchants.put("prescription", "healthcare");
        knownMerchants.put("physician", "healthcare");
        knownMerchants.put("clinic", "healthcare");
        knownMerchants.put("health", "healthcare");
        knownMerchants.put("healthcare", "healthcare");
        knownMerchants.put("dental", "healthcare");
        knownMerchants.put("dentist", "healthcare");
        knownMerchants.put("optometry", "healthcare");
        knownMerchants.put("optometrist", "healthcare");
        knownMerchants.put("eye care", "healthcare");
        knownMerchants.put("vision", "healthcare");
        knownMerchants.put("health care", "healthcare");
        knownMerchants.put("medical care", "healthcare");
        knownMerchants.put("health service", "healthcare");
        knownMerchants.put("cvs pharmacy store", "healthcare");
        knownMerchants.put("cvs pharmacy store and clinic", "healthcare");
        knownMerchants.put("urgent care", "healthcare");
        knownMerchants.put("emergency care", "healthcare");
        knownMerchants.put("chiropractor", "healthcare");
        knownMerchants.put("orthodontist", "healthcare");
        knownMerchants.put("optician", "healthcare");
        knownMerchants.put("optical goods", "healthcare");
        knownMerchants.put("eyeglasses", "healthcare");
        knownMerchants.put("overlake", "healthcare");
        knownMerchants.put("seattle children's", "healthcare");
        knownMerchants.put("premera", "healthcare");
        knownMerchants.put("swedish hospital", "healthcare");
        knownMerchants.put("providence", "healthcare");
        knownMerchants.put("virginia mason", "healthcare");
        knownMerchants.put("seattle cancer care alliance", "healthcare");
        knownMerchants.put("seattle genetics", "healthcare");

        // ========== SEMANTIC: UTILITIES KEYWORDS ==========
        // Note: Many utility providers are already above, adding semantic cluster keywords
        knownMerchants.put("utilities", "utilities");
        knownMerchants.put("electricity", "utilities");
        knownMerchants.put("power", "utilities");
        knownMerchants.put("energy", "utilities");
        knownMerchants.put("sewer", "utilities");
        knownMerchants.put("sewage", "utilities");
        knownMerchants.put("gas utility", "utilities");
        knownMerchants.put("natural gas", "utilities");
        knownMerchants.put("heating", "utilities");
        knownMerchants.put("water utility", "utilities");
        knownMerchants.put("broadband", "utilities");
        knownMerchants.put("wifi", "utilities");
        knownMerchants.put("wi-fi", "utilities");
        knownMerchants.put("mobile", "utilities");
        knownMerchants.put("telephone", "utilities");
        knownMerchants.put("tv", "utilities");
        knownMerchants.put("television", "utilities");
        knownMerchants.put("streaming", "utilities");
        knownMerchants.put("internet service", "utilities");
        knownMerchants.put("cable tv", "utilities");
        knownMerchants.put("electric bill", "utilities");
        knownMerchants.put("water bill", "utilities");
        knownMerchants.put("gas bill", "utilities");
        knownMerchants.put("utility bill", "utilities");
        knownMerchants.put("power bill", "utilities");
        knownMerchants.put("energy bill", "utilities");
        knownMerchants.put("electric service", "utilities");
        knownMerchants.put("water service", "utilities");
        knownMerchants.put("gas service", "utilities");
        knownMerchants.put("utility payment", "utilities");
        knownMerchants.put("bill payment", "utilities");
        knownMerchants.put("billpay", "utilities");
        knownMerchants.put("bill pay", "utilities");
        knownMerchants.put("billpaying", "utilities");
        knownMerchants.put("ener billpay", "utilities");
        knownMerchants.put("energy billpay", "utilities");
        knownMerchants.put("electric company", "utilities");
        knownMerchants.put("water company", "utilities");
        knownMerchants.put("gas company", "utilities");
        knownMerchants.put("utility company", "utilities");
        knownMerchants.put("power company", "utilities");
        knownMerchants.put("energy company", "utilities");
        knownMerchants.put("municipal utility", "utilities");
        knownMerchants.put("city utility", "utilities");
        knownMerchants.put("public utility", "utilities");
        knownMerchants.put("utility provider", "utilities");
        knownMerchants.put("service provider", "utilities");

        // ========== SEMANTIC: INSURANCE KEYWORDS ==========
        // Note: Many insurance providers are already above
        knownMerchants.put("car insurance", "insurance");
        knownMerchants.put("auto insurance", "insurance");
        knownMerchants.put("vehicle insurance", "insurance");
        knownMerchants.put("home insurance", "insurance");
        knownMerchants.put("homeowner insurance", "insurance");
        knownMerchants.put("renters insurance", "insurance");
        knownMerchants.put("life insurance", "insurance");
        knownMerchants.put("health insurance", "insurance");
        knownMerchants.put("dental insurance", "insurance");
        knownMerchants.put("vision insurance", "insurance");
        knownMerchants.put("pet insurance", "insurance");
        knownMerchants.put("disability insurance", "insurance");
        knownMerchants.put("travel insurance", "insurance");
        knownMerchants.put("legal insurance", "insurance");

        // ========== SEMANTIC: INCOME KEYWORDS ==========
        // Note: Many payroll providers are already above
        knownMerchants.put("wage", "income");
        knownMerchants.put("wages", "income");
        knownMerchants.put("earnings", "income");
        knownMerchants.put("compensation", "income");
        knownMerchants.put("remuneration", "income");
        knownMerchants.put("stipend", "income");

        // ========== SEMANTIC: DEPOSIT KEYWORDS ==========
        knownMerchants.put("deposits", "deposit");
        knownMerchants.put("deposited", "deposit");
        knownMerchants.put("depositing", "deposit");
        knownMerchants.put("depositor", "deposit");
        knownMerchants.put("bank deposit", "deposit");
        knownMerchants.put("checking deposit", "deposit");
        knownMerchants.put("savings deposit", "deposit");
        knownMerchants.put("account deposit", "deposit");
        knownMerchants.put("fund deposit", "deposit");
        knownMerchants.put("money deposit", "deposit");
        knownMerchants.put("check deposit", "deposit");
        knownMerchants.put("wire deposit", "deposit");
        knownMerchants.put("electronic deposit", "deposit");
        knownMerchants.put("online deposit", "deposit");
        knownMerchants.put("mobile deposit", "deposit");
        knownMerchants.put("remote deposit", "deposit");
        knownMerchants.put("deposit transaction", "deposit");
        knownMerchants.put("deposit payment", "deposit");
        knownMerchants.put("deposit transfer", "deposit");
        knownMerchants.put("deposit credit", "deposit");
        // BAI2 Deposit Codes (many already above, adding semantic keywords)
        // ISO 20022 Deposit Messages
        // ACH Deposit Codes

        // ========== SEMANTIC: SHOPPING KEYWORDS ==========
        // Note: Many retail stores are already above
        knownMerchants.put("shop", "shopping");
        knownMerchants.put("merchandise", "shopping");
        knownMerchants.put("buy", "shopping");
        knownMerchants.put("buying", "shopping");
        knownMerchants.put("mall", "shopping");
        knownMerchants.put("department store", "shopping");
        knownMerchants.put("boutique", "shopping");
        knownMerchants.put("outlet", "shopping");
        knownMerchants.put("retail store", "shopping");
        knownMerchants.put("retail shop", "shopping");
        knownMerchants.put("shopping center", "shopping");
        knownMerchants.put("shopping mall", "shopping");
        knownMerchants.put("retail outlet", "shopping");
        knownMerchants.put("store purchase", "shopping");
        knownMerchants.put("retail purchase", "shopping");
        knownMerchants.put("shopping trip", "shopping");
        knownMerchants.put("clothing", "shopping");
        knownMerchants.put("apparel", "shopping");
        knownMerchants.put("men's clothing", "shopping");
        knownMerchants.put("mens clothing", "shopping");
        knownMerchants.put("women's clothing", "shopping");
        knownMerchants.put("womens clothing", "shopping");
        knownMerchants.put("men's apparel", "shopping");
        knownMerchants.put("mens apparel", "shopping");
        knownMerchants.put("women's apparel", "shopping");
        knownMerchants.put("womens apparel", "shopping");
        knownMerchants.put("fashion", "shopping");
        knownMerchants.put("garment", "shopping");
        knownMerchants.put("attire", "shopping");
        knownMerchants.put("wardrobe", "shopping");
        knownMerchants.put("stores", "shopping");
        knownMerchants.put("shops", "shopping");
        knownMerchants.put("convenience store", "shopping");
        knownMerchants.put("electronics store", "shopping");
        knownMerchants.put("hardware store", "shopping");
        knownMerchants.put("home improvement store", "shopping");
        knownMerchants.put("furniture store", "shopping");
        knownMerchants.put("toy store", "shopping");
        knownMerchants.put("sports store", "shopping");
        knownMerchants.put("store transaction", "shopping");
        knownMerchants.put("store payment", "shopping");
        knownMerchants.put("in store", "shopping");
        knownMerchants.put("in-store", "shopping");
        knownMerchants.put("store visit", "shopping");
        knownMerchants.put("store shopping", "shopping");
        knownMerchants.put("mini mountain", "shopping");
        knownMerchants.put("minimountain", "shopping");
        knownMerchants.put("ski gear", "shopping");
        knownMerchants.put("skigear", "shopping");
        knownMerchants.put("ski equipment", "shopping");
        knownMerchants.put("skiequipment", "shopping");
        knownMerchants.put("sports equipment", "shopping");
        knownMerchants.put("sportsequipment", "shopping");
        knownMerchants.put("outdoor gear", "shopping");
        knownMerchants.put("outdoorgear", "shopping");

        // ========== SEMANTIC: ENTERTAINMENT KEYWORDS ==========
        // Note: Many streaming services are already above
        knownMerchants.put("movie", "entertainment");
        knownMerchants.put("cinema", "entertainment");
        knownMerchants.put("theater", "entertainment");
        knownMerchants.put("theatre", "entertainment");
        knownMerchants.put("film", "entertainment");
        knownMerchants.put("music", "entertainment");
        knownMerchants.put("concert", "entertainment");
        knownMerchants.put("show", "entertainment");
        knownMerchants.put("event", "entertainment");
        knownMerchants.put("ticket", "entertainment");
        knownMerchants.put("sports", "entertainment");
        knownMerchants.put("game", "entertainment");
        knownMerchants.put("entertainment service", "entertainment");
        knownMerchants.put("media", "entertainment");
        knownMerchants.put("video", "entertainment");
        knownMerchants.put("audio", "entertainment");

        // ========== SEMANTIC: TECH KEYWORDS ==========
        // Note: Many tech subscriptions are already above
        knownMerchants.put("subscr", "tech");
        knownMerchants.put("subs", "tech");
        knownMerchants.put("subscription", "tech");
        knownMerchants.put("subscriptions", "tech");
        knownMerchants.put("recurring", "tech");
        knownMerchants.put("monthly subscription", "tech");
        knownMerchants.put("annual subscription", "tech");
        knownMerchants.put("yearly subscription", "tech");
        knownMerchants.put("premium", "tech");
        knownMerchants.put("premium membership", "tech");

        // ========== SEMANTIC: EDUCATION KEYWORDS ==========
        // Note: Many education-related entries are already above
        knownMerchants.put("senior secondary", "education");
        knownMerchants.put("secondary", "education");
        knownMerchants.put("graduation", "education");
        knownMerchants.put("post graduation", "education");
        knownMerchants.put("p.h.d", "education");
        knownMerchants.put("school fees", "education");
        knownMerchants.put("university fees", "education");
        knownMerchants.put("graudation fees", "education");
        knownMerchants.put("ptsa", "education");
        knownMerchants.put("dorm", "education");
        knownMerchants.put("barrons", "education");
        knownMerchants.put("barron's", "education");
        knownMerchants.put("barron", "education");
        knownMerchants.put("j*barrons", "education");
        knownMerchants.put("j barrons", "education");
        knownMerchants.put("new york times", "education");
        knownMerchants.put("nytimes", "education");
        knownMerchants.put("ny times", "education");
        knownMerchants.put("the new york times", "education");
        knownMerchants.put("wall street journal", "education");
        knownMerchants.put("wsj", "education");
        knownMerchants.put("the wall street journal", "education");
        knownMerchants.put("financial times", "education");
        knownMerchants.put("ft.com", "education");
        knownMerchants.put("the financial times", "education");
        knownMerchants.put("economist", "education");
        knownMerchants.put("the economist", "education");
        knownMerchants.put("bloomberg", "education");
        knownMerchants.put("bloomberg news", "education");
        knownMerchants.put("reuters", "education");
        knownMerchants.put("reuters news", "education");
        knownMerchants.put("cnn", "education");
        knownMerchants.put("cnn news", "education");
        knownMerchants.put("bbc", "education");
        knownMerchants.put("bbc news", "education");
        knownMerchants.put("washington post", "education");
        knownMerchants.put("wapo", "education");
        knownMerchants.put("the washington post", "education");
        knownMerchants.put("usa today", "education");
        knownMerchants.put("usatoday", "education");
        knownMerchants.put("los angeles times", "education");
        knownMerchants.put("latimes", "education");
        knownMerchants.put("chicago tribune", "education");
        knownMerchants.put("boston globe", "education");
        knownMerchants.put("the boston globe", "education");

        // ========== SEMANTIC: PAYMENT KEYWORDS ==========
        // Note: Many payment-related entries are already above
        knownMerchants.put("paying", "payment");
        knownMerchants.put("autopay", "payment");
        knownMerchants.put("auto pay", "payment");
        knownMerchants.put("auto-pay", "payment");
        knownMerchants.put("automatic payment", "payment");
        knownMerchants.put("credit card payment", "payment");
        knownMerchants.put("card payment", "payment");
        knownMerchants.put("card pay", "payment");
        knownMerchants.put("credit payment", "payment");
        knownMerchants.put("loan payment", "payment");
        knownMerchants.put("loan pay", "payment");
        knownMerchants.put("debt payment", "payment");
        knownMerchants.put("debt pay", "payment");
        knownMerchants.put("installment", "payment");
        knownMerchants.put("installment payment", "payment");
        knownMerchants.put("monthly payment", "payment");
        knownMerchants.put("e-payment", "payment");
        knownMerchants.put("e payment", "payment");
        knownMerchants.put("electronic payment", "payment");
        knownMerchants.put("online payment", "payment");
        knownMerchants.put("payment processing", "payment");
        knownMerchants.put("payment service", "payment");
        knownMerchants.put("payment gateway", "payment");
        knownMerchants.put("credit card autopay", "payment");
        knownMerchants.put("card autopay", "payment");
        knownMerchants.put("credit autopay", "payment");
        knownMerchants.put("loan autopay", "payment");
        knownMerchants.put("debt autopay", "payment");
        knownMerchants.put("auto payment", "payment");
        knownMerchants.put("automatic pay", "payment");
        knownMerchants.put("recurring payment", "payment");
        knownMerchants.put("scheduled payment", "payment");
        knownMerchants.put("payment plan", "payment");
        // ACH SEC Codes for Payments (many already above)
        // ACH Transaction Codes for Payments
        // Credit Card Payment keywords
        knownMerchants.put("creditcard", "payment");
        knownMerchants.put("credit crd", "payment");
        knownMerchants.put("credit card auto pay", "payment");
        knownMerchants.put("card auto pay", "payment");
        knownMerchants.put("credit auto pay", "payment");
        knownMerchants.put("credit card e-payment", "payment");
        knownMerchants.put("card e-payment", "payment");
        knownMerchants.put("credit e-payment", "payment");
        knownMerchants.put("credit card bill", "payment");
        knownMerchants.put("card bill", "payment");
        knownMerchants.put("credit bill", "payment");
        knownMerchants.put("credit card statement", "payment");
        knownMerchants.put("card statement", "payment");
        knownMerchants.put("credit statement", "payment");
        knownMerchants.put("credit card balance", "payment");
        knownMerchants.put("card balance", "payment");
        knownMerchants.put("credit balance", "payment");
        knownMerchants.put("credit card pay", "payment");
        knownMerchants.put("credit pay", "payment");
        knownMerchants.put("discover payment", "payment");
        knownMerchants.put("amex payment", "payment");
        knownMerchants.put("american express payment", "payment");
        knownMerchants.put("visa payment", "payment");
        knownMerchants.put("mastercard payment", "payment");
        knownMerchants.put("credit card pmt", "payment");
        knownMerchants.put("card pmt", "payment");
        knownMerchants.put("amz storecrd pmt", "payment");
        knownMerchants.put("amz storecrd pmt payment", "payment");
        knownMerchants.put("amazon store card payment", "payment");
        knownMerchants.put("amazon store card", "payment");
        knownMerchants.put("amz store card", "payment");
        knownMerchants.put("amazon credit card", "payment");
        knownMerchants.put("amzstorecrd", "payment");
        knownMerchants.put("amz storecrd", "payment");
        knownMerchants.put("storecrd pmt", "payment");
        knownMerchants.put("storecrd payment", "payment");
        knownMerchants.put("amz store card pmt", "payment");
        knownMerchants.put("amazon store card pmt", "payment");
        knownMerchants.put("amz credit card payment", "payment");
        // Loan Payment keywords
        knownMerchants.put("loan", "payment");
        knownMerchants.put("loan repayment", "payment");
        knownMerchants.put("loan repay", "payment");
        knownMerchants.put("debt", "payment");
        knownMerchants.put("debt repayment", "payment");
        knownMerchants.put("debt repay", "payment");
        knownMerchants.put("mortgage", "payment");
        knownMerchants.put("mortgage pay", "payment");
        knownMerchants.put("mortgage repay", "payment");
        knownMerchants.put("car loan", "payment");
        knownMerchants.put("auto loan", "payment");
        knownMerchants.put("vehicle loan", "payment");
        knownMerchants.put("car payment", "payment");
        knownMerchants.put("student loan", "payment");
        knownMerchants.put("student loan payment", "payment");
        knownMerchants.put("education loan", "payment");
        knownMerchants.put("personal loan", "payment");
        knownMerchants.put("personal loan payment", "payment");
        knownMerchants.put("personal loan pay", "payment");
        knownMerchants.put("home loan", "payment");
        knownMerchants.put("home loan payment", "payment");
        knownMerchants.put("housing loan", "payment");
        knownMerchants.put("loan installment", "payment");
        knownMerchants.put("debt installment", "payment");
        knownMerchants.put("loan monthly", "payment");
        knownMerchants.put("debt monthly", "payment");
        knownMerchants.put("loan auto pay", "payment");
        knownMerchants.put("debt auto pay", "payment");
        knownMerchants.put("loan service", "payment");
        knownMerchants.put("debt service", "payment");
        knownMerchants.put("loan provider", "payment");
        knownMerchants.put("debt provider", "payment");
        knownMerchants.put("citi autopay payment", "payment");
        knownMerchants.put("citi autopay", "payment");
        knownMerchants.put("autopay payment", "payment");
        knownMerchants.put("chase autopay", "payment");
        knownMerchants.put("bank autopay", "payment");
        // Check keywords
        knownMerchants.put("check", "payment");
        knownMerchants.put("checks", "payment");
        knownMerchants.put("cheque", "payment");
        knownMerchants.put("cheques", "payment");
        knownMerchants.put("check payment", "payment");
        knownMerchants.put("check pay", "payment");
        knownMerchants.put("check number", "payment");
        knownMerchants.put("check #", "payment");
        knownMerchants.put("check no", "payment");
        knownMerchants.put("check no.", "payment");
        knownMerchants.put("written check", "payment");
        knownMerchants.put("check written", "payment");
        knownMerchants.put("check issued", "payment");
        knownMerchants.put("check transaction", "payment");
        knownMerchants.put("check purchase", "payment");
        knownMerchants.put("check cashing", "payment");
        knownMerchants.put("check clearing", "payment");
        // Bill Pay keywords (merged with utilities)
        knownMerchants.put("bill", "utilities");
        knownMerchants.put("bills", "utilities");
        knownMerchants.put("bill payment service", "utilities");
        knownMerchants.put("bill pay service", "utilities");
        knownMerchants.put("billpay service", "utilities");
        knownMerchants.put("online bill pay", "utilities");
        knownMerchants.put("online bill payment", "utilities");
        knownMerchants.put("electronic bill pay", "utilities");
        knownMerchants.put("automatic bill pay", "utilities");
        knownMerchants.put("auto bill pay", "utilities");
        knownMerchants.put("auto bill payment", "utilities");
        knownMerchants.put("bill payment provider", "utilities");
        knownMerchants.put("bill pay provider", "utilities");
        knownMerchants.put("utility bill pay", "utilities");
        knownMerchants.put("utility bill payment", "utilities");
        knownMerchants.put("phone bill", "utilities");
        knownMerchants.put("internet bill", "utilities");
        knownMerchants.put("cable bill", "utilities");
        knownMerchants.put("tv bill", "utilities");
        knownMerchants.put("insurance bill", "utilities");
        knownMerchants.put("medical bill", "utilities");
        knownMerchants.put("loan bill", "utilities");
        knownMerchants.put("mortgage bill", "utilities");
        knownMerchants.put("recurring bill", "utilities");
        knownMerchants.put("monthly bill", "utilities");
        knownMerchants.put("quarterly bill", "utilities");
        knownMerchants.put("annual bill", "utilities");
        knownMerchants.put("bill reminder", "utilities");
        knownMerchants.put("bill due", "utilities");
        knownMerchants.put("bill statement", "utilities");
        knownMerchants.put("bill invoice", "utilities");
        // IRS Tax Payment Categories
        // IRS Business Expense Categories
        // GAAP Expense Categories

        // ========== SEMANTIC: INVESTMENT KEYWORDS ==========
        // Note: Many investment firms are already above
        knownMerchants.put("invest", "investment");
        knownMerchants.put("investing", "investment");
        knownMerchants.put("invested", "investment");
        knownMerchants.put("investor", "investment");
        knownMerchants.put("stock", "investment");
        knownMerchants.put("share", "investment");
        knownMerchants.put("shares", "investment");
        knownMerchants.put("stock market", "investment");
        knownMerchants.put("stock exchange", "investment");
        knownMerchants.put("securities", "investment");
        knownMerchants.put("security", "investment");
        knownMerchants.put("trading", "investment");
        knownMerchants.put("trade", "investment");
        knownMerchants.put("trader", "investment");
        knownMerchants.put("trading account", "investment");
        knownMerchants.put("brokerage", "investment");
        knownMerchants.put("broker", "investment");
        knownMerchants.put("brokerage account", "investment");
        knownMerchants.put("investment account", "investment");
        knownMerchants.put("portfolio", "investment");
        knownMerchants.put("portfolio management", "investment");
        knownMerchants.put("asset management", "investment");
        knownMerchants.put("mutual funds", "investment");
        knownMerchants.put("fund", "investment");
        knownMerchants.put("funds", "investment");
        knownMerchants.put("etfs", "investment");
        knownMerchants.put("index fund", "investment");
        knownMerchants.put("index funds", "investment");
        knownMerchants.put("treasury", "investment");
        knownMerchants.put("treasuries", "investment");
        knownMerchants.put("certificate", "investment");
        knownMerchants.put("cd investment", "investment");
        knownMerchants.put("cd interest", "investment");
        knownMerchants.put("retirement", "investment");
        knownMerchants.put("retirement account", "investment");
        knownMerchants.put("401 k", "investment");
        knownMerchants.put("401k contribution", "investment");
        knownMerchants.put("401 k contribution", "investment");
        knownMerchants.put("ira", "investment");
        knownMerchants.put("roth ira", "investment");
        knownMerchants.put("ira contribution", "investment");
        knownMerchants.put("roth ira contribution", "investment");
        knownMerchants.put("pension", "investment");
        knownMerchants.put("pension fund", "investment");
        knownMerchants.put("retirement fund", "investment");
        knownMerchants.put("retirement plan", "investment");
        knownMerchants.put("contribution", "investment");
        knownMerchants.put("retirement contribution", "investment");
        knownMerchants.put("hsa", "investment");
        knownMerchants.put("health savings account", "investment");
        knownMerchants.put("hsa investment", "investment");
        knownMerchants.put("investment firm", "investment");
        knownMerchants.put("investment company", "investment");
        knownMerchants.put("asset manager", "investment");
        knownMerchants.put("investment transfer", "investment");
        knownMerchants.put("stock purchase", "investment");
        knownMerchants.put("bond purchase", "investment");
        knownMerchants.put("online transfer from morganstanley", "investment");
        knownMerchants.put("online transfer from morgan stanley", "investment");
        knownMerchants.put("transfer from morganstanley", "investment");
        knownMerchants.put("transfer from morgan stanley", "investment");
        knownMerchants.put("transfer from fidelity", "investment");
        knownMerchants.put("transfer from vanguard", "investment");
        knownMerchants.put("transfer from schwab", "investment");
        knownMerchants.put("fund purchase", "investment");
        knownMerchants.put("investment purchase", "investment");
        knownMerchants.put("securities purchase", "investment");
        knownMerchants.put("dividend", "investment");
        knownMerchants.put("dividends", "investment");
        knownMerchants.put("dividend payment", "investment");
        knownMerchants.put("dividend income", "investment");
        knownMerchants.put("capital gains", "investment");
        knownMerchants.put("capital gain", "investment");
        knownMerchants.put("gain", "investment");
        knownMerchants.put("gains", "investment");
        knownMerchants.put("profit", "investment");
        knownMerchants.put("profits", "investment");
        knownMerchants.put("investment income", "investment");
        knownMerchants.put("investment return", "investment");
        knownMerchants.put("return on investment", "investment");
        knownMerchants.put("roi", "investment");
        knownMerchants.put("nyse", "investment");
        knownMerchants.put("nasdaq", "investment");
        knownMerchants.put("stock trading", "investment");
        knownMerchants.put("equity trading", "investment");
        knownMerchants.put("share trading", "investment");
        knownMerchants.put("equity purchase", "investment");
        knownMerchants.put("share purchase", "investment");
        knownMerchants.put("stock sale", "investment");
        knownMerchants.put("equity sale", "investment");
        knownMerchants.put("share sale", "investment");
        knownMerchants.put("stock broker", "investment");
        knownMerchants.put("equity broker", "investment");
        knownMerchants.put("share broker", "investment");
        knownMerchants.put("stock account", "investment");
        knownMerchants.put("equity account", "investment");
        knownMerchants.put("share account", "investment");
        knownMerchants.put("stock portfolio", "investment");
        knownMerchants.put("equity portfolio", "investment");
        knownMerchants.put("share portfolio", "investment");
        knownMerchants.put("stock investment", "investment");
        knownMerchants.put("equity investment", "investment");
        knownMerchants.put("share investment", "investment");
        knownMerchants.put("stock dividend", "investment");
        knownMerchants.put("equity dividend", "investment");
        knownMerchants.put("share dividend", "investment");
        knownMerchants.put("employer contribution", "investment");
        knownMerchants.put("employer match", "investment");

        // ========== SEMANTIC: TRANSFER KEYWORDS ==========
        // Note: Many transfer-related entries are already above
        knownMerchants.put("transfers", "transfer");
        knownMerchants.put("transferring", "transfer");
        knownMerchants.put("transferred", "transfer");
        knownMerchants.put("transfer to", "transfer");
        knownMerchants.put("transfer from", "transfer");
        knownMerchants.put("transfer between", "transfer");
        knownMerchants.put("money transfer", "transfer");
        knownMerchants.put("fund transfer", "transfer");
        knownMerchants.put("payment transfer", "transfer");
        knownMerchants.put("internal transfer", "transfer");
        knownMerchants.put("external transfer", "transfer");
        knownMerchants.put("inter-account transfer", "transfer");
        knownMerchants.put("inter-bank transfer", "transfer");
        knownMerchants.put("intra-bank transfer", "transfer");
        knownMerchants.put("intra-account transfer", "transfer");
        knownMerchants.put("transfer fee", "transfer");
        knownMerchants.put("transfer service", "transfer");
        knownMerchants.put("transfer processing", "transfer");

        // ========== SEMANTIC: CASH KEYWORDS ==========
        knownMerchants.put("cash withdrawal", "cash");
        knownMerchants.put("cash withdraw", "cash");
        knownMerchants.put("cash out", "cash");
        knownMerchants.put("cashout", "cash");
        knownMerchants.put("atms", "cash");
        knownMerchants.put("atm withdrawal", "cash");
        knownMerchants.put("atm withdraw", "cash");
        knownMerchants.put("atm cash", "cash");
        knownMerchants.put("cash advance", "cash");
        knownMerchants.put("cashback", "cash");
        knownMerchants.put("cash back", "cash");
        knownMerchants.put("cash return", "cash");
        knownMerchants.put("cash payment", "cash");
        knownMerchants.put("cash transaction", "cash");
        knownMerchants.put("cash purchase", "cash");
        knownMerchants.put("cash in", "cash");
        knownMerchants.put("withdrawal", "cash");
        knownMerchants.put("withdraw", "cash");

        // ========== SEMANTIC: PET KEYWORDS ==========
        knownMerchants.put("pets", "pet");
        knownMerchants.put("pet supply", "pet");
        knownMerchants.put("petsupplies", "pet");
        knownMerchants.put("pet food", "pet");
        knownMerchants.put("petfood", "pet");
        knownMerchants.put("dog food", "pet");
        knownMerchants.put("cat food", "pet");
        knownMerchants.put("pet store", "pet");
        knownMerchants.put("petstore", "pet");
        knownMerchants.put("sp farmers", "pet");
        knownMerchants.put("fetch bones", "pet");
        knownMerchants.put("fetchbones", "pet");
        knownMerchants.put("mudbay", "pet");
        knownMerchants.put("mud bay", "pet");
        knownMerchants.put("fido", "pet");
        knownMerchants.put("sea king aquarium", "pet");

        // ========== SEMANTIC: HEALTH KEYWORDS ==========
        knownMerchants.put("fitness", "health");
        knownMerchants.put("gym", "health");
        knownMerchants.put("health club", "health");
        knownMerchants.put("athletic club", "health");
        knownMerchants.put("fitness center", "health");
        knownMerchants.put("workout", "health");
        knownMerchants.put("personal trainer", "health");
        knownMerchants.put("exercise", "health");
        knownMerchants.put("proclub", "health");
        knownMerchants.put("pro club", "health");
        knownMerchants.put("24 hour fitness", "health");
        knownMerchants.put("24hour fitness", "health");
        knownMerchants.put("gold's gym", "health");
        knownMerchants.put("golds gym", "health");
        knownMerchants.put("planet fitness", "health");
        knownMerchants.put("equinox", "health");
        knownMerchants.put("lifetime fitness", "health");
        knownMerchants.put("ymca", "health");
        knownMerchants.put("la fitness", "health");
        knownMerchants.put("crunch fitness", "health");
        knownMerchants.put("anytime fitness", "health");
        knownMerchants.put("orange theory", "health");
        knownMerchants.put("crossfit", "health");
        knownMerchants.put("beauty salon", "health");
        knownMerchants.put("beautysalon", "health");
        knownMerchants.put("beauty parlor", "health");
        knownMerchants.put("beautyparlor", "health");
        knownMerchants.put("hair salon", "health");
        knownMerchants.put("hairsalon", "health");
        knownMerchants.put("hair cut", "health");
        knownMerchants.put("haircut", "health");
        knownMerchants.put("hair cuts", "health");
        knownMerchants.put("haircuts", "health");
        knownMerchants.put("hair color", "health");
        knownMerchants.put("haircolor", "health");
        knownMerchants.put("body waxing", "health");
        knownMerchants.put("bodywaxing", "health");
        knownMerchants.put("waxing", "health");
        knownMerchants.put("makeup", "health");
        knownMerchants.put("make up", "health");
        knownMerchants.put("beauty studio", "health");
        knownMerchants.put("beautystudio", "health");
        knownMerchants.put("salon", "health");
        knownMerchants.put("supercuts", "health");
        knownMerchants.put("super cuts", "health");
        knownMerchants.put("great clips", "health");
        knownMerchants.put("greatclips", "health");
        knownMerchants.put("lucky hair salon", "health");
        knownMerchants.put("luckyhair", "health");
        knownMerchants.put("nails", "health");
        knownMerchants.put("nail salon", "health");
        knownMerchants.put("nailsalon", "health");
        knownMerchants.put("nail", "health");
        knownMerchants.put("manicure", "health");
        knownMerchants.put("pedicure", "health");
        knownMerchants.put("spa", "health");
        knownMerchants.put("massage", "health");
        knownMerchants.put("massages", "health");
        knownMerchants.put("toes", "health");
        knownMerchants.put("skin", "health");
        knownMerchants.put("skin care", "health");
        knownMerchants.put("skincare", "health");
        knownMerchants.put("new york cosmetic", "health");
        knownMerchants.put("ny cosmetic", "health");
        knownMerchants.put("cosmetic shop", "health");
        knownMerchants.put("cosmeticshop", "health");
        knownMerchants.put("golf", "health");
        knownMerchants.put("tennis", "health");
        knownMerchants.put("soccer", "health");
        knownMerchants.put("football", "health");
        knownMerchants.put("basketball", "health");
        knownMerchants.put("baseball", "health");
        knownMerchants.put("swimming", "health");
        knownMerchants.put("yoga", "health");
        knownMerchants.put("pilates", "health");
        knownMerchants.put("martial arts", "health");
        knownMerchants.put("ski resort", "health");
        knownMerchants.put("ski", "health");
        knownMerchants.put("summit at snoqualmie", "health");
        knownMerchants.put("badminton", "health");
        knownMerchants.put("seattle badminton club", "health");
        knownMerchants.put("fitness club", "health");
        knownMerchants.put("sports club", "health");
        knownMerchants.put("health center", "health");
        knownMerchants.put("wellness center", "health");
        knownMerchants.put("recreation center", "health");
        knownMerchants.put("badminton club", "health");
        knownMerchants.put("tennis club", "health");
        knownMerchants.put("swimming club", "health");
        knownMerchants.put("yoga studio", "health");
        knownMerchants.put("pilates studio", "health");
        knownMerchants.put("crossfit gym", "health");
        knownMerchants.put("karate", "health");
        knownMerchants.put("judo", "health");
        knownMerchants.put("taekwondo", "health");
        knownMerchants.put("dance studio", "health");
        knownMerchants.put("dance club", "health");
        knownMerchants.put("cycling", "health");
        knownMerchants.put("cycling club", "health");
        knownMerchants.put("running club", "health");
        knownMerchants.put("sports facility", "health");
        knownMerchants.put("athletic facility", "health");
        knownMerchants.put("fitness facility", "health");

        // ========== SEMANTIC: CHARITY KEYWORDS ==========
        knownMerchants.put("charitable", "charity");
        knownMerchants.put("donation", "charity");
        knownMerchants.put("donate", "charity");
        knownMerchants.put("donating", "charity");
        knownMerchants.put("non-profit", "charity");
        knownMerchants.put("nonprofit", "charity");
        knownMerchants.put("non profit", "charity");
        knownMerchants.put("go fund me", "charity");
        knownMerchants.put("gofundme", "charity");

        // ========== HARD-CODED MERCHANTS FROM CSVImportService ==========
        // Subscription Merchants (from detectCategoryFromMerchantName and
        // detectCategoryFromDescription)

        // Travel Lounges (from detectCategoryFromDescription)
        knownMerchants.put("axpcenturion", "travel");
        knownMerchants.put("airport loung", "travel");
        knownMerchants.put("lounge", "travel");

        // Airlines (from detectCategoryFromDescription)

        // Hotels (from detectCategoryFromDescription)

        // Ride-sharing Services (from detectCategoryFromDescription)
        knownMerchants.put("rideshare", "transportation");
        knownMerchants.put("didi", "transportation");
        knownMerchants.put("grab", "transportation");
        knownMerchants.put("ola", "transportation");
        knownMerchants.put("careem", "transportation");
        knownMerchants.put("gett", "transportation");
        knownMerchants.put("bolt", "transportation");
        // Lyft Pink subscription
        knownMerchants.put("lyft pink", "travel");
        knownMerchants.put("lyftpink", "travel");
        knownMerchants.put("pink subscription", "travel");
        knownMerchants.put("pink membership", "travel");
        // Uber One subscription
        knownMerchants.put("uber one", "travel");
        knownMerchants.put("uberone", "travel");
        knownMerchants.put("uber one subscription", "travel");
        knownMerchants.put("uberone subscription", "travel");
        knownMerchants.put("uber one membership", "travel");
        knownMerchants.put("uberone membership", "travel");
        // Uber Eats (dining, not transportation)
        knownMerchants.put("uber eat", "dining");

        // Gas Stations (from detectCategoryFromDescription)
        knownMerchants.put("esso", "transportation");
        knownMerchants.put("murphy usa", "transportation");
        knownMerchants.put("murphyusa", "transportation");
        knownMerchants.put("flyingj", "transportation");
        knownMerchants.put("travel centers", "transportation");
        knownMerchants.put("truck stop", "transportation");
        knownMerchants.put("buc-ees", "transportation");
        // BP gas station (specific pattern)
        knownMerchants.put("bp gas", "transportation");
        knownMerchants.put("bp fuel", "transportation");
        knownMerchants.put("bp station", "transportation");
        // 76 gas station (specific pattern)
        knownMerchants.put("76 station", "transportation");
        knownMerchants.put("76 gas", "transportation");
        knownMerchants.put("union 76", "transportation");
        knownMerchants.put("union76", "transportation");

        // Parking Services (from detectCategoryFromDescription)
        knownMerchants.put("pay by phone", "transportation");
        knownMerchants.put("paybyphone", "transportation");
        knownMerchants.put("uw pay by phone", "transportation");
        knownMerchants.put("uwpay by phone", "transportation");
        knownMerchants.put("uw paybyphone", "transportation");
        knownMerchants.put("uwpaybyphone", "transportation");
        knownMerchants.put("parkmobile", "transportation");
        knownMerchants.put("park mobile", "transportation");
        knownMerchants.put("impark", "transportation");
        knownMerchants.put("parking", "transportation");
        knownMerchants.put("parking meter", "transportation");
        knownMerchants.put("parkingmeter", "transportation");
        knownMerchants.put("garage", "transportation");
        knownMerchants.put("metropolis parking", "transportation");
        knownMerchants.put("metropolisparking", "transportation");

        // Toll Patterns (from detectCategoryFromMerchantName and detectCategoryFromDescription)
        knownMerchants.put("eratoll", "transportation");

        // Car Service (from detectCategoryFromDescription)

        // LUL Ticket Machine (London Underground)
        knownMerchants.put("lul ticket machine", "transportation");
        knownMerchants.put("lulticketmachine", "transportation");
        knownMerchants.put("lul ticket mach", "transportation");
        knownMerchants.put("london underground", "transportation");
        knownMerchants.put("ticket machine", "transportation");
        knownMerchants.put("ticketmachine", "transportation");

        // Amex Airlines Fee Reimbursement

        // Sports and Fitness (from detectCategoryFromDescription)
        knownMerchants.put("badmintonclub", "health");
        knownMerchants.put("seattlebadmintonclub", "health");
        knownMerchants.put("fitnessclub", "health");
        knownMerchants.put("healthclub", "health");
        knownMerchants.put("athleticclub", "health");
        knownMerchants.put("sportsclub", "health");
        knownMerchants.put("fitnesscenter", "health");
        knownMerchants.put("orangetheory", "health");

        // Gym Keywords (from detectCategoryFromDescription)
        knownMerchants.put("24hr fitness", "health");
        knownMerchants.put("24-hour fitness", "health");

        // Beauty Keywords (from detectCategoryFromDescription)
        knownMerchants.put("lucky hair salin", "health");
        knownMerchants.put("luckyhairsalin", "health");

        // Sports Activities (from detectCategoryFromDescription)
        // Mini Mountain is shopping (ski-gear/equipment)

        // Utility Companies (from detectCategoryFromDescription)
        knownMerchants.put("pacific gas", "utilities");
        knownMerchants.put("san diego gas", "utilities");
        knownMerchants.put("edison", "utilities");
        knownMerchants.put("dukeenergy", "utilities");
        knownMerchants.put("dominionenergy", "utilities");
        knownMerchants.put("american electric", "utilities");
        knownMerchants.put("southerncompany", "utilities");

        // POS System Patterns (from detectCategoryFromDescription)
        // TST* (Toast POS) - dining
        // SQ* (Square POS) - dining
        // RBL* (Restaurant POS) - dining
        knownMerchants.put("rbl*", "dining");
        knownMerchants.put("rbl", "dining");
        // TPD (Top Pot Donuts) - dining
        knownMerchants.put("top pot donuts", "dining");
        knownMerchants.put("toppotdonuts", "dining");
        knownMerchants.put("top pot", "dining");
        knownMerchants.put("toppot", "dining");
        knownMerchants.put("dunkin donuts", "dining");
        knownMerchants.put("dunkindonuts", "dining");

        // Specific Restaurants (from detectCategoryFromDescription)
        knownMerchants.put("simply indian restaur", "dining");
        knownMerchants.put("simplyindian restaur", "dining");
        knownMerchants.put("simplyindian restaurant", "dining");
        knownMerchants.put("skills rainbow room", "dining");
        knownMerchants.put("skillsrainbow room", "dining");
        knownMerchants.put("laughing monk brewing", "dining");
        knownMerchants.put("laughingmonk brewing", "dining");
        knownMerchants.put("laughingmonk", "dining");
        knownMerchants.put("banaras restaurant", "dining");

        // Pet Services (from detectCategoryFromDescription)
        knownMerchants.put("petcareclinic", "pet");
        knownMerchants.put("pet clinic", "pet");
        knownMerchants.put("petclinic", "pet");
        knownMerchants.put("veterinary", "pet");
        knownMerchants.put("vet", "pet");
        knownMerchants.put("animal hospital", "pet");
        knownMerchants.put("animalhospital", "pet");
        knownMerchants.put("animal clinic", "pet");
        knownMerchants.put("animalclinic", "pet");
        knownMerchants.put("pet hospital", "pet");
        knownMerchants.put("pethospital", "pet");
        knownMerchants.put("veterinarian", "pet");
        knownMerchants.put("veterinary clinic", "pet");
        knownMerchants.put("pet pharmacy", "pet");
        knownMerchants.put("petpharmacy", "pet");

        // Education - School Types (from detectCategoryFromDescription)

        // Education - Regional School Terms (from detectCategoryFromDescription)

        // Education - Educational Media (from detectCategoryFromDescription)
        knownMerchants.put("scholarly journal", "education");
        knownMerchants.put("peer-reviewed journal", "education");
        knownMerchants.put("university book store", "education");
        knownMerchants.put("universitybookstore", "education");
        knownMerchants.put("university book", "education");
        knownMerchants.put("universitybook", "education");

        // Education - Exam/Testing Keywords (from detectCategoryFromDescription)
        knownMerchants.put("act exam", "education");
        knownMerchants.put("act test", "education");

        // Education - Financial Education Publications (from detectCategoryFromDescription)
        knownMerchants.put("dj*barrons", "education");
        knownMerchants.put("d j*barrons", "education");

        // Education - SP ANKI REMOTE
        knownMerchants.put("sp anki remote", "education");
        knownMerchants.put("spankiremote", "education");
        knownMerchants.put("anki remote", "education");
        knownMerchants.put("anki", "education");

        // Entertainment - Movie Theaters (from detectCategoryFromDescription)
        knownMerchants.put("harkins", "entertainment");
        knownMerchants.put("movie theater", "entertainment");
        knownMerchants.put("movietheater", "entertainment");
        knownMerchants.put("escape room", "entertainment");
        knownMerchants.put("escaperoom", "entertainment");
        knownMerchants.put("conundroom", "entertainment");
        knownMerchants.put("conundroom.us", "entertainment");
        knownMerchants.put("state fair", "entertainment");
        knownMerchants.put("statefair", "entertainment");
        knownMerchants.put("universal studio", "entertainment");
        knownMerchants.put("universalstudio", "entertainment");
        knownMerchants.put("sea world", "entertainment");
        knownMerchants.put("seaworld", "entertainment");
        knownMerchants.put("camping", "entertainment");
        knownMerchants.put("cape disappointment", "entertainment");
        knownMerchants.put("recreation.gov", "entertainment");

        // Additional Streaming Services
        knownMerchants.put("apple tv plus", "entertainment");
        knownMerchants.put("apple tv+", "entertainment");
        knownMerchants.put("discovery plus", "entertainment");

        // Tech - Cursor AI (from detectCategoryFromDescription)
        knownMerchants.put("ai powered", "tech");
        knownMerchants.put("ide", "tech");
        knownMerchants.put("software", "tech");
        knownMerchants.put("saas", "tech");
        knownMerchants.put("api", "tech");
        knownMerchants.put("developer", "tech");
        knownMerchants.put("integrated development", "tech");

        // ========== POPULAR MISSING MERCHANTS ==========
        // Tech
        knownMerchants.put("linear", "tech");
        knownMerchants.put("notion", "tech");
        knownMerchants.put("obsidian", "tech");
        knownMerchants.put("vercel", "tech");
        knownMerchants.put("vercel inc", "tech");
        // Shopping
        knownMerchants.put("zappos", "shopping");
        knownMerchants.put("wayfair", "shopping");
        knownMerchants.put("overstock", "shopping");
        knownMerchants.put("etsy", "shopping");
        // Travel
        knownMerchants.put("orbitz", "travel");
        knownMerchants.put("kayak", "travel");
        knownMerchants.put("hotels.com", "travel");
        // Health
        knownMerchants.put("mindbody", "health");
        knownMerchants.put("classpass", "health");
        // Groceries
        knownMerchants.put("imperfectfoods", "groceries");
        knownMerchants.put("imperfect foods", "groceries");
        knownMerchants.put("freshdirect", "groceries");
        knownMerchants.put("fresh direct", "groceries");
        LOGGER.info(
                "Initialized comprehensive known merchants database with {} merchants across all categories including payroll providers, MCC codes, ISO 20022, ACH SEC codes, SWIFT, FINCEN, IRS, GAAP, SIC/NAICS, inter-bank transfer systems, all semantic keywords from SemanticMatchingService, and all hard-coded merchants from CSVImportService (statically merged)",
                knownMerchants.size());
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
            LOGGER.error(
                    "Error adding known merchant '{}' -> '{}': {}",
                    merchantName,
                    category,
                    e.getMessage(),
                    e);
        }
    }

    /** Detection result */
    public static class DetectionResult {
        public final String category;
        public final double confidence;
        public final String method;
        public final String reason;

        public DetectionResult(final String category, final double confidence, final String method, final String reason) {
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
