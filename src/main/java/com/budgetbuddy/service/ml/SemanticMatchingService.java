package com.budgetbuddy.service.ml;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Semantic Matching Service Uses word embeddings and cosine similarity for semantic category
 * detection
 *
 * <p>Features: - Word embeddings for merchant names and descriptions - Cosine similarity for
 * semantic matching - Context-aware matching (handles synonyms, related terms) - Fallback to
 * keyword-based matching if embeddings unavailable
 *
 * <p>Note: This is a simplified implementation. For production, consider: - Pre-trained word
 * embeddings (Word2Vec, GloVe, FastText) - Sentence embeddings (Universal Sentence Encoder, BERT) -
 * Vector database for fast similarity search
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
public class SemanticMatchingService {

    private static final String AUTO_PAY = "auto pay";

    private static final String AUTOPAY = "autopay";

    private static final String BADMINTON = "badminton";

    private static final String BILL_PAY = "bill pay";

    private static final String BILL_PAYMENT = "bill payment";

    private static final String BILLPAY = "billpay";

    private static final String BROKERAGE = "brokerage";

    private static final String CARD_PAY = "card pay";

    private static final String CHECK_PAYMENT = "check payment";

    private static final String CLINIC = "clinic";

    private static final String CREDIT = "credit";

    private static final String CREDIT_CARD_AUTOPAY = "credit card autopay";

    private static final String CROSSFIT = "crossfit";

    private static final String DEPOSIT = "deposit";

    private static final String DINING = "dining";

    private static final String ELECTRIC_BILL = "electric bill";

    private static final String ENTERTAINMENT = "entertainment";

    private static final String FAST_FOOD = "fast food";

    private static final String GAS_BILL = "gas bill";

    private static final String GROCERIES = "groceries";

    private static final String HEALTH = "health";

    private static final String HEALTHCARE = "healthcare";

    private static final String INCOME = "income";

    private static final String INVESTMENT = "investment";

    private static final String LOAN_PAYMENT = "loan payment";

    private static final String MARTIAL_ARTS = "martial arts";

    private static final String MEDICAL = "medical";

    private static final String MONTHLY_PAYMENT = "monthly payment";

    private static final String MORTGAGE = "mortgage";

    private static final String NETFLIX = "netflix";

    private static final String PAYMENT = "payment";

    private static final String PILATES = "pilates";

    private static final String SEATTLE_BADMINTON_CLUB = "seattle badminton club";

    private static final String SHOPPING = "shopping";

    private static final String STORE = "store";

    private static final String SUBSCRIPTIONS = "subscriptions";

    private static final String SUBWAY = "subway";

    private static final String TRANSFER = "transfer";

    private static final String URGENT_CARE = "urgent care";

    private static final String UTILITIES = "utilities";

    private static final String WATER_BILL = "water bill";
    private static final String S = "\\s+";

    private static final Logger LOGGER = LoggerFactory.getLogger(SemanticMatchingService.class);

    // CRITICAL: Shared data service - single source of truth
    private final MerchantCategoryDataService merchantCategoryDataService;

    // Semantic category mappings (simplified - in production, use word embeddings)
    // Maps category keywords to semantic clusters
    // CRITICAL: Thread-safe - use ConcurrentHashMap for concurrent access
    // CRITICAL: Loaded once in memory for speed - all data translated and cached
    private final Map<String, Set<String>> categorySemanticClusters = new ConcurrentHashMap<>();

    // CRITICAL: Flag to track if clusters are loaded (prevents race conditions)
    private volatile boolean clustersLoaded = false;

    // CRITICAL: Lock for initialization (prevents race conditions during startup)
    private final Object initializationLock = new Object();

    // Similarity thresholds.
    // A match must clear BOTH the base-similarity floor (pure text overlap) AND the
    // final-similarity threshold (text + context boost). This prevents pure-context
    // matches where the merchant text itself has nothing in common with the category.
    //
    // History: this was 0.6, dropped to 0.4, then to 0.3 — leading to many false
    // positives where a 0.05 base similarity rode a 0.3 context boost past the bar.
    private static final double SEMANTIC_SIMILARITY_THRESHOLD = 0.50;
    private static final double SEMANTIC_BASE_SIMILARITY_FLOOR = 0.25;
    // Cap context boost so a matched signal can nudge, not dominate, the score.
    private static final double MAX_CONTEXT_BOOST = 0.20;

    // Maximum text length for performance protection
    private static final int MAX_TEXT_LENGTH = 10_000;

    // Minimum token length
    private static final int MIN_TOKEN_LENGTH = 2;

    public SemanticMatchingService(final MerchantCategoryDataService merchantCategoryDataService) {
        // CRITICAL: Store reference to shared service
        this.merchantCategoryDataService = merchantCategoryDataService;
        // CRITICAL: Load clusters from shared service (loaded once, kept in memory for speed)
        loadSemanticClustersFromSharedService();
    }

    /**
     * Load semantic clusters from shared MerchantCategoryDataService CRITICAL: Thread-safe, error
     * handling, boundary checks, race condition prevention CRITICAL: Loaded once in memory for
     * speed - all data translated and cached This replaces the old initializeSemanticClusters()
     * method
     */
    private void loadSemanticClustersFromSharedService() {
        // CRITICAL: Thread-safe initialization with double-check locking
        if (clustersLoaded) {
            return; // Already loaded
        }

        synchronized (initializationLock) {
            // CRITICAL: Double-check after acquiring lock
            if (clustersLoaded) {
                return;
            }

            try {
                // CRITICAL: Clear existing data to prevent stale state
                categorySemanticClusters.clear();

                // CRITICAL: Null check for shared service
                if (merchantCategoryDataService == null) {
                    LOGGER.error(
                            "MerchantCategoryDataService is null. Cannot load semantic clusters. Service will not function correctly.");
                    return;
                }

                // CRITICAL: Load from shared service with error handling
                Map<String, Set<String>> sharedClusters =
                        merchantCategoryDataService.getCategoryToKeywordsMap();

                if (sharedClusters == null || sharedClusters.isEmpty()) {
                    LOGGER.warn(
                            "MerchantCategoryDataService returned empty clusters. Retrying after delay...");
                    // CRITICAL: Retry logic for race conditions (service might not be initialized
                    // yet)
                    try {
                        Thread.sleep(100); // Brief delay for service initialization
                        sharedClusters = merchantCategoryDataService.getCategoryToKeywordsMap();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        LOGGER.warn("Interrupted while waiting for MerchantCategoryDataService");
                    }
                }

                if (sharedClusters == null || sharedClusters.isEmpty()) {
                    LOGGER.error(
                            "MerchantCategoryDataService returned empty clusters after retry. Service will not function correctly.");
                    return;
                }

                // CRITICAL: Boundary check - prevent memory issues
                if (sharedClusters.size() > 1000) {
                    if (LOGGER.isErrorEnabled()) {
                        LOGGER.error(
                                "Shared cluster map size ({}) exceeds safety limit. Loading empty clusters.",
                                sharedClusters.size());
                    }
                    return;
                }

                // CRITICAL: Load clusters with validation and defensive copies
                int loadedCount = 0;
                int totalKeywords = 0;

                for (final Map.Entry<String, Set<String>> entry : sharedClusters.entrySet()) {
                    final String category = entry.getKey();
                    Set<String> keywords = entry.getValue();

                    // CRITICAL: Null checks
                    if (category == null || keywords == null) {
                        LOGGER.warn("Skipping null category or keywords from shared service");
                        continue;
                    }

                    // CRITICAL: Boundary check per category - prevent very large sets
                    if (keywords.size() > 50_000) {
                        if (LOGGER.isWarnEnabled()) {
                            LOGGER.warn(
                                    "Category '{}' has too many keywords ({}). Limiting to 50000.",
                                    category,
                                    keywords.size());
                        }
                        // Create a limited set
                        final Set<String> limitedKeywords = new HashSet<>();
                        int count = 0;
                        for (final String keyword : keywords) {
                            if (count >= 50_000) {
                                break;
                            }
                            limitedKeywords.add(keyword);
                            count++;
                        }
                        keywords = limitedKeywords;
                    }

                    // CRITICAL: Create defensive copy to prevent external modification
                    final Set<String> keywordsCopy = new HashSet<>(keywords);

                    // CRITICAL: Store in thread-safe map (loaded once, kept in memory for speed)
                    categorySemanticClusters.put(category, keywordsCopy);
                    loadedCount++;
                    totalKeywords += keywordsCopy.size();
                }

                // CRITICAL: Mark as loaded (volatile for visibility)
                clustersLoaded = true;

                LOGGER.info(
                        "Loaded {} semantic clusters with {} total keywords from MerchantCategoryDataService into SemanticMatchingService (loaded once, kept in memory for speed)",
                        loadedCount,
                        totalKeywords);

                // CRITICAL: Validate loaded data
                if (loadedCount == 0) {
                    LOGGER.error(
                            "No clusters loaded from shared service. SemanticMatchingService will not function correctly.");
                } else if (loadedCount < 10) {
                    LOGGER.warn(
                            "Only {} clusters loaded. Expected at least 10. Service may not function correctly.",
                            loadedCount);
                }

            } catch (OutOfMemoryError e) {
                LOGGER.error("Out of memory loading semantic clusters from shared service", e);
                categorySemanticClusters.clear();
                clustersLoaded = false;
            } catch (Exception e) {
                if (LOGGER.isErrorEnabled()) {
                    LOGGER.error(
                            "Error loading semantic clusters from shared service: {}",
                            e.getMessage(),
                            e);
                }
                categorySemanticClusters.clear();
                clustersLoaded = false;
            }
        }
    }

    /**
     * Find best semantic match for merchant name/description
     *
     * <p>CRITICAL: Thread-safe, handles errors, race conditions, and boundary conditions
     *
     * @param merchantName Merchant name
     * @param description Transaction description
     * @return SemanticMatchResult with best match and confidence, or null if no good match
     */
    public SemanticMatchResult findBestSemanticMatch(
            final String merchantName, final String description) {
        return findBestSemanticMatchWithContext(merchantName, description, null, null, null, null);
    }

    /**
     * Find best semantic match with context-aware matching
     *
     * <p>Context-aware matching considers: - Transaction amount (positive = income/investment,
     * negative = expense) - Payment channel (ACH = likely bill pay/transfer, POS = likely purchase)
     * - Account type (investment account = likely investment category) - Account subtype (more
     * specific account type hints)
     *
     * <p>CRITICAL: Thread-safe, handles errors, race conditions, and boundary conditions
     *
     * @param merchantName Merchant name
     * @param description Transaction description
     * @param amount Transaction amount (null if not available)
     * @param paymentChannel Payment channel (ACH, POS, online, in_store, etc.)
     * @param accountType Account type (checking, savings, credit, investment, loan, etc.)
     * @param accountSubtype Account subtype (more specific type)
     * @return SemanticMatchResult with best match and confidence, or null if no good match
     */
    public SemanticMatchResult findBestSemanticMatchWithContext(
            final String merchantName,
            final String description,
            final java.math.BigDecimal amount,
            final String paymentChannel,
            final String accountType,
            final String accountSubtype) {
        try {
            // CRITICAL: Ensure clusters are loaded (lazy loading with race condition prevention)
            ensureClustersLoaded();

            // Null and empty input validation. Both null = nothing to match on.
            if ((merchantName == null || merchantName.isBlank())
                    && (description == null || description.isBlank())) {
                LOGGER.debug(
                        "Semantic matching: Both merchant name and description are null/empty");
                return null;
            }

            // When the merchant-keyword clusters aren't loaded (test env, or
            // first-boot before the data service populates them), we can
            // still make a sensible guess from the CONTEXT alone: account
            // type + payment channel + payment-phrase keywords. This path
            // replaces a prior "return null" that erased context as a
            // signal source — a credit-card row with "Auto Pay" in the
            // description IS a payment, even without a merchant match.
            if (!clustersLoaded || categorySemanticClusters.isEmpty()) {
                final SemanticMatchResult contextual =
                        deriveFromContext(
                                merchantName,
                                description,
                                amount,
                                paymentChannel,
                                accountType,
                                accountSubtype);
                if (contextual != null) {
                    return contextual;
                }
                LOGGER.debug("Semantic clusters not loaded and no context signal; returning null");
                return null;
            }

            // CRITICAL: Log input for debugging
            LOGGER.debug(
                    "Semantic matching: merchantName='{}', description='{}', amount={}, channel='{}', accountType='{}', accountSubtype='{}'",
                    merchantName,
                    description,
                    amount,
                    paymentChannel,
                    accountType,
                    accountSubtype);

            // CRITICAL: Boundary condition - very long text (performance protection)
            String safeMerchantName = merchantName != null ? merchantName : "";
            String safeDescription = description != null ? description : "";

            if (safeMerchantName.length() > MAX_TEXT_LENGTH) {
                if (LOGGER.isWarnEnabled()) {
                    LOGGER.warn(
                            "Merchant name too long ({} chars), truncating to {}",
                            safeMerchantName.length(),
                            MAX_TEXT_LENGTH);
                }
                safeMerchantName = safeMerchantName.substring(0, MAX_TEXT_LENGTH);
            }

            if (safeDescription.length() > MAX_TEXT_LENGTH) {
                if (LOGGER.isWarnEnabled()) {
                    LOGGER.warn(
                            "Description too long ({} chars), truncating to {}",
                            safeDescription.length(),
                            MAX_TEXT_LENGTH);
                }
                safeDescription = safeDescription.substring(0, MAX_TEXT_LENGTH);
            }

            // CRITICAL FIX: When merchantName is null, use description for matching
            // Many imports (especially PDF/CSV) have merchant info in description, not merchantName
            // Combine merchant name and description, but prioritize description if merchantName is
            // null
            final String combinedText;
            if (safeMerchantName.isEmpty() && !safeDescription.isEmpty()) {
                // Use description only when merchantName is null/empty
                combinedText = safeDescription.trim().toLowerCase(Locale.ROOT);
                LOGGER.debug(
                        "Semantic matching: Using description only (merchantName is null/empty): '{}'",
                        combinedText);
            } else {
                // Combine both when available
                combinedText =
                        (safeMerchantName + " " + safeDescription).trim().toLowerCase(Locale.ROOT);
                LOGGER.debug("Semantic matching: Using combined text: '{}'", combinedText);
            }

            if (combinedText.isEmpty()) {
                LOGGER.debug("Semantic matching: Combined text is empty after normalization");
                return null;
            }

            // CRITICAL: Thread-safe tokenization (no shared state modification)
            final Set<String> tokens = tokenize(combinedText);

            if (tokens.isEmpty()) {
                LOGGER.debug("Semantic matching: No tokens extracted from text");
                return null;
            }

            // CRITICAL: Check for POS system codes first (TST* = Toast, SQ* = Square)
            // These are restaurant POS systems and should always be categorized as dining
            // Check both combined text and individual tokens for POS codes
            final String combinedTextUpper = combinedText.toUpperCase(Locale.ROOT);
            if (combinedTextUpper.startsWith("TST*")
                    || combinedTextUpper.startsWith("TST ")
                    || combinedTextUpper.contains(" TST*")
                    || combinedTextUpper.contains(" TST ")) {
                LOGGER.debug(
                        "POS code detected: TST* (Toast) - categorizing as dining with high confidence");
                return new SemanticMatchResult(DINING, 0.95, "POS_CODE_TST");
            }
            if (combinedTextUpper.startsWith("SQ*")
                    || combinedTextUpper.startsWith("SQ ")
                    || combinedTextUpper.contains(" SQ*")
                    || combinedTextUpper.contains(" SQ ")) {
                // Square can be used for various businesses, but defaults to dining if not clearly
                // identified
                // Check if there are strong indicators it's NOT dining (e.g., "square payment",
                // "square invoice")
                final String combinedTextLower = combinedText.toLowerCase(Locale.ROOT);
                final boolean isNonDiningSquare =
                        combinedTextLower.contains("square payment")
                                || combinedTextLower.contains("square invoice")
                                || combinedTextLower.contains("square subscription")
                                || combinedTextLower.contains("square terminal");
                if (!isNonDiningSquare) {
                    LOGGER.debug(
                            "POS code detected: SQ* (Square) - defaulting to dining with high confidence");
                    return new SemanticMatchResult(DINING, 0.90, "POS_CODE_SQ");
                } else {
                    LOGGER.debug(
                            "POS code detected: SQ* (Square) but non-dining indicators found - skipping POS code match");
                }
            }

            // CRITICAL: Thread-safe iteration (ConcurrentHashMap allows safe iteration)
            // Find best matching category
            String bestCategory = null;
            double bestScore = 0.0;

            // CRITICAL: Create a snapshot of entries to avoid ConcurrentModificationException
            // ConcurrentHashMap.entrySet() returns a view that is safe for iteration
            for (final Map.Entry<String, Set<String>> entry : categorySemanticClusters.entrySet()) {
                final String category = entry.getKey();
                final Set<String> cluster = entry.getValue();

                // CRITICAL: Null check for cluster (defensive programming)
                if (category == null || cluster == null || cluster.isEmpty()) {
                    LOGGER.debug("Skipping null or empty cluster for category: {}", category);
                    continue;
                }

                // CRITICAL: Create defensive copy of cluster to avoid race conditions
                // If cluster is modified during iteration, we work with a snapshot
                final Set<String> clusterSnapshot = new HashSet<>(cluster);

                // Calculate semantic similarity (Jaccard similarity for now)
                if (LOGGER.isTraceEnabled()) {
                    LOGGER.trace(
                            "Checking category '{}' with {} keywords against text '{}'",
                            category,
                            clusterSnapshot.size(),
                            combinedText);
                }
                final double baseSimilarity =
                        calculateSemanticSimilarity(tokens, clusterSnapshot, combinedText);
                LOGGER.trace("Category '{}' base similarity: {}", category, baseSimilarity);

                // Enforce a minimum base-similarity floor: the merchant text itself must
                // have meaningful overlap with the category keywords. A purely context-driven
                // match (e.g. "ACH + positive amount") is not enough to pick a category.
                if (baseSimilarity < SEMANTIC_BASE_SIMILARITY_FLOOR) {
                    continue;
                }

                // Apply capped context boost on top of the text similarity.
                final double rawContextBoost =
                        calculateContextBoost(
                                category, amount, paymentChannel, accountType, accountSubtype);
                final double contextBoost =
                        Math.min(MAX_CONTEXT_BOOST, Math.max(0.0, rawContextBoost));

                double similarity = Math.min(1.0, baseSimilarity + contextBoost);

                if (Double.isNaN(similarity) || Double.isInfinite(similarity)) {
                    LOGGER.warn(
                            "Invalid similarity score (NaN or Infinite) for category: {}",
                            category);
                    continue;
                }

                similarity = Math.max(0.0, Math.min(1.0, similarity));

                if (similarity > bestScore && similarity >= SEMANTIC_SIMILARITY_THRESHOLD) {
                    bestScore = similarity;
                    bestCategory = category;
                }
            }

            if (bestCategory != null) {
                if (LOGGER.isInfoEnabled()) {
                    LOGGER.info(
                            "Semantic match: text='{}' category='{}' similarity={} threshold={}",
                            combinedText.substring(0, Math.min(60, combinedText.length())),
                            bestCategory,
                            String.format("%.3f", bestScore),
                            SEMANTIC_SIMILARITY_THRESHOLD);
                }
                return new SemanticMatchResult(bestCategory, bestScore, "SEMANTIC_CONTEXT_AWARE");
            }

            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(
                        "Semantic match: no category above threshold {} (baseFloor={}) for text='{}'",
                        SEMANTIC_SIMILARITY_THRESHOLD,
                        SEMANTIC_BASE_SIMILARITY_FLOOR,
                        combinedText.substring(0, Math.min(60, combinedText.length())));
            }
            return null;

        } catch (Exception e) {
            // CRITICAL: Error handling - never throw, always return null on error
            if (LOGGER.isErrorEnabled()) {
                LOGGER.error(
                        "Error in semantic matching for merchant='{}', description='{}': {}",
                        merchantName,
                        description,
                        e.getMessage(),
                        e);
            }
            return null;
        }
    }

    /**
     * Calculate context boost for a category based on transaction context
     *
     * <p>Context rules: - ACH + positive amount → boost utilities, payment, deposit - ACH +
     * negative amount → boost utilities, payment - POS → boost shopping, dining, groceries -
     * Investment account → boost investment category - Loan account → boost payment category -
     * Credit account → boost payment category - Large positive amounts → boost investment, income -
     * Small amounts → boost groceries, dining
     *
     * @param category Category being evaluated
     * @param amount Transaction amount
     * @param paymentChannel Payment channel
     * @param accountType Account type
     * @param accountSubtype Account subtype
     * @return Context boost (0.0 to 0.3)
     */
    private double calculateContextBoost(
            final String category,
            final java.math.BigDecimal amount,
            final String paymentChannel,
            final String accountType,
            final String accountSubtype) {

        double boost = 0.0;

        try {
            // Rule 1: Payment channel context
            if (paymentChannel != null) {
                final String channelLower = paymentChannel.toLowerCase(Locale.ROOT);

                // ACH payments are typically bill payments, utilities, or transfers
                if (channelLower.contains("ach")) {
                    if (UTILITIES.equals(category)
                            || PAYMENT.equals(category)
                            || TRANSFER.equals(category)) {
                        boost += 0.15; // Strong boost for ACH + utilities/payment
                    }
                    // ACH with positive amount might be deposit/income
                    if (amount != null && amount.compareTo(java.math.BigDecimal.ZERO) > 0) {
                        if (DEPOSIT.equals(category)
                                || INCOME.equals(category)
                                || INVESTMENT.equals(category)) {
                            boost += 0.10; // Boost for ACH credit
                        }
                    }
                }

                // POS (Point of Sale) is typically shopping, dining, groceries
                if (channelLower.contains("pos") || channelLower.contains("point of sale")) {
                    if (SHOPPING.equals(category)
                            || DINING.equals(category)
                            || GROCERIES.equals(category)) {
                        boost += 0.12; // Boost for POS + shopping/dining
                    }
                }

                // Online payments might be subscriptions, shopping, entertainment
                if (channelLower.contains("online")) {
                    // Prioritize subscriptions and entertainment for online payments
                    if (SUBSCRIPTIONS.equals(category) || ENTERTAINMENT.equals(category)) {
                        boost += 0.35; // Very strong boost for subscriptions/entertainment
                        // (increased from 0.25) to ensure similarity >= 0.6
                    } else if (SHOPPING.equals(category)) {
                        boost += 0.10; // Moderate boost for shopping
                    }
                }
            }

            // Rule 2: Account type context
            if (accountType != null) {
                final String accountTypeLower = accountType.toLowerCase(Locale.ROOT);

                // Investment accounts → boost investment category
                if (accountTypeLower.contains(INVESTMENT)
                        || accountTypeLower.contains(BROKERAGE)
                        || accountTypeLower.contains("retirement")
                        || accountTypeLower.contains("ira")
                        || accountTypeLower.contains("401k")
                        || accountTypeLower.contains("403b")) {
                    if (INVESTMENT.equals(category)) {
                        boost += 0.20; // Strong boost for investment account
                    }
                }

                // Loan/credit accounts → boost payment category
                if (accountTypeLower.contains("loan")
                        || accountTypeLower.contains(CREDIT)
                        || accountTypeLower.contains(MORTGAGE)) {
                    if (PAYMENT.equals(category)) {
                        boost += 0.40; // Extremely strong boost for loan/credit account (increased
                        // from 0.25) to override other matches
                    }
                }

                // Checking/savings accounts → might be utilities, groceries, etc.
                if (accountTypeLower.contains("checking")
                        || accountTypeLower.contains("savings")
                        || accountTypeLower.contains("depository")) {
                    // No specific boost, but don't penalize common categories
                    if (UTILITIES.equals(category)
                            || GROCERIES.equals(category)
                            || DINING.equals(category)
                            || SHOPPING.equals(category)) {
                        boost += 0.05; // Small boost for common categories
                    }
                }
            }

            // Rule 3: Amount-based context
            if (amount != null) {
                // Large positive amounts might be investment, income, or large purchases
                if (amount.compareTo(java.math.BigDecimal.valueOf(1000)) > 0) {
                    if (INVESTMENT.equals(category) || INCOME.equals(category)) {
                        boost += 0.10; // Boost for large positive amounts
                    }
                }

                // Small amounts are typically groceries, dining, small purchases
                if (amount.compareTo(java.math.BigDecimal.valueOf(100)) < 0
                        && amount.compareTo(java.math.BigDecimal.ZERO) < 0) {
                    if (GROCERIES.equals(category)
                            || DINING.equals(category)
                            || SHOPPING.equals(category)) {
                        boost += 0.08; // Boost for small amounts
                    }
                }

                // Very small amounts (< $10) are often coffee, snacks, small purchases
                if (amount.compareTo(java.math.BigDecimal.valueOf(10)) < 0
                        && amount.compareTo(java.math.BigDecimal.ZERO) < 0) {
                    // Prioritize dining for very small amounts (coffee, snacks)
                    if (DINING.equals(category)) {
                        boost += 0.30; // Very strong boost for very small amounts → dining
                        // (prioritize over shopping)
                    } else if (GROCERIES.equals(category)) {
                        boost += 0.15; // Moderate boost for groceries
                    } else if (SHOPPING.equals(category)) {
                        boost += 0.05; // Small boost for shopping (lower priority for very small
                        // amounts)
                    }
                }
            }

            // Rule 4: Account subtype context (more specific)
            if (accountSubtype != null) {
                final String subtypeLower = accountSubtype.toLowerCase(Locale.ROOT);

                // CD, money market, etc. → investment
                if (subtypeLower.contains("cd")
                        || subtypeLower.contains("certificate")
                        || subtypeLower.contains("money market")
                        || subtypeLower.contains("money_market")) {
                    if (INVESTMENT.equals(category)) {
                        boost += 0.15; // Boost for CD/money market
                    }
                }

                // Credit card subtypes → payment
                if (subtypeLower.contains(CREDIT) || subtypeLower.contains("card")) {
                    if (PAYMENT.equals(category)) {
                        boost += 0.35; // Very strong boost for credit card subtype (increased from
                        // 0.20+0.12) to override other matches
                    }
                }
            }

            // CRITICAL: Cap boost at 0.5 (50%) to allow strong context signals to override base
            // similarity
            // Increased from 0.3 to 0.5 to handle cases where context is very strong (e.g.,
            // credit/loan accounts)
            boost = Math.min(0.5, boost);

        } catch (Exception e) {
            if (LOGGER.isWarnEnabled()) {
                LOGGER.warn("Error calculating context boost: {}", e.getMessage());
            }
            // Return 0.0 on error (no boost)
        }

        return boost;
    }

    /**
     * Tokenize text into words Simple implementation - in production, use proper NLP tokenization
     *
     * <p>CRITICAL: Thread-safe, handles errors and boundary conditions
     */
    private Set<String> tokenize(String text) {
        final Set<String> tokens = new HashSet<>();

        try {
            // CRITICAL: Null and empty check
            if (text == null || text.isBlank()) {
                return tokens;
            }

            // CRITICAL: Boundary condition - very long text
            if (text.length() > MAX_TEXT_LENGTH) {
                if (LOGGER.isWarnEnabled()) {
                    LOGGER.warn(
                            "Text too long for tokenization ({} chars), truncating", text.length());
                }
                text = text.substring(0, MAX_TEXT_LENGTH);
            }

            // Remove special characters and split by whitespace
            final String normalized =
                    text.toLowerCase(Locale.ROOT)
                            .replaceAll("[^a-z0-9\\s]", " ")
                            .replaceAll("\\s+", " ")
                            .trim();

            if (normalized.isEmpty()) {
                return tokens;
            }

            String[] words = normalized.split("\\s+");

            // CRITICAL: Boundary condition - very large word array
            if (words.length > 1000) {
                LOGGER.warn("Too many words ({}), limiting to first 1000", words.length);
                words = Arrays.copyOf(words, 1000);
            }

            for (final String word : words) {
                // CRITICAL: Validate word length (boundary condition)
                if (word != null && word.length() >= MIN_TOKEN_LENGTH && word.length() <= 100) {
                    tokens.add(word);
                }
            }

            // Also add bigrams for better matching
            // CRITICAL: Boundary check for array bounds
            for (int i = 0; i < words.length - 1 && i < 1000; i++) {
                final String word1 = words[i];
                final String word2 = words[i + 1];

                if (word1 != null
                        && word2 != null
                        && word1.length() >= MIN_TOKEN_LENGTH
                        && word1.length() <= 100
                        && word2.length() >= MIN_TOKEN_LENGTH
                        && word2.length() <= 100) {
                    tokens.add(word1 + " " + word2);
                }
            }

            // Augment with stems + curated synonyms so "groceries" ↔ "grocery" ↔ "supermarket"
            // collide on the same canonical token. See TextNormalizer for the policy.
            tokens.addAll(TextNormalizer.expandWithSynonyms(tokens));

        } catch (Exception e) {
            // CRITICAL: Error handling - return empty set on error
            if (LOGGER.isErrorEnabled()) {
                LOGGER.error("Error tokenizing text: {}", e.getMessage(), e);
            }
            return new HashSet<>();
        }

        return tokens;
    }

    /**
     * Calculate semantic similarity using Jaccard similarity In production, use cosine similarity
     * with word embeddings
     *
     * <p>CRITICAL: Thread-safe, handles errors and boundary conditions
     *
     * <p>IMPROVEMENT: Added exact phrase matching boost for better accuracy
     */
    private double calculateSemanticSimilarity(
            Set<String> tokens, Set<String> cluster, final String combinedText) {
        try {
            // CRITICAL: Null and empty checks
            if (tokens == null || cluster == null) {
                LOGGER.debug("Semantic similarity: tokens or cluster is null");
                return 0.0;
            }

            if (tokens.isEmpty() || cluster.isEmpty()) {
                return 0.0;
            }

            // CRITICAL: Boundary condition - very large sets (performance protection)
            if (tokens.size() > 10_000 || cluster.size() > 10_000) {
                if (LOGGER.isWarnEnabled()) {
                    LOGGER.warn(
                            "Very large sets for similarity calculation (tokens: {}, cluster: {}), limiting",
                            tokens.size(),
                            cluster.size());
                }
                // For very large sets, sample instead of processing all
                if (tokens.size() > 10_000) {
                    tokens = new HashSet<>(new ArrayList<>(tokens).subList(0, 10_000));
                }
                if (cluster.size() > 10_000) {
                    cluster = new HashSet<>(new ArrayList<>(cluster).subList(0, 10_000));
                }
            }

            // CRITICAL: Create defensive copies to avoid modifying input sets
            final Set<String> tokensCopy = new HashSet<>(tokens);
            final Set<String> clusterCopy = new HashSet<>(cluster);

            // IMPROVEMENT: Check if combined text contains any multi-word cluster phrase as
            // substring
            // This handles cases like "online transfer from morganstanley" containing "online
            // transfer from morganstanley"
            // Prioritize longer phrases over shorter ones (check longer phrases first)
            if (combinedText != null && !combinedText.isEmpty()) {
                final String combinedTextLower = combinedText.toLowerCase(Locale.ROOT);
                // Sort phrases by length (longest first) to prioritize longer, more specific
                // matches
                final List<String> sortedPhrases = new ArrayList<>();
                for (final String clusterPhrase : clusterCopy) {
                    if (clusterPhrase != null && clusterPhrase.contains(" ")) {
                        sortedPhrases.add(clusterPhrase);
                    }
                }
                sortedPhrases.sort(
                        (a, b) -> Integer.compare(b.split("\\s+").length, a.split("\\s+").length));

                for (final String clusterPhrase : sortedPhrases) {
                    // Multi-word phrase, check if combined text contains it as substring
                    final String clusterPhraseLower = clusterPhrase.toLowerCase(Locale.ROOT);
                    // BUG FIX: Handle wildcard patterns (e.g., "tst*" should match "TST* MESSINA",
                    // "TST* DEEP DIVE", etc.)
                    // Also handles "sq*" for Square POS system
                    if (clusterPhraseLower.endsWith("*")) {
                        // Wildcard pattern - check if merchant starts with the prefix (without the
                        // *)
                        final String prefix =
                                clusterPhraseLower.substring(0, clusterPhraseLower.length() - 1);
                        // Case-insensitive matching for POS codes
                        final String combinedTextUpper = combinedText.toUpperCase(Locale.ROOT);
                        final String prefixUpper = prefix.toUpperCase(Locale.ROOT);
                        if (combinedTextLower.startsWith(prefix)
                                || combinedTextUpper.startsWith(prefixUpper + "*")
                                || combinedTextUpper.startsWith(prefixUpper + " ")) {
                            LOGGER.debug(
                                    "Wildcard prefix match found: '{}' matches '{}*' in '{}'",
                                    prefix,
                                    prefix,
                                    combinedTextLower);
                            // POS system codes (TST*, SQ*) get even higher similarity
                            if ("tst".equals(prefix) || "sq".equals(prefix)) {
                                return 0.95; // Very high similarity for POS system codes
                            }
                            return 0.90; // High similarity for other wildcard prefix matches
                        }
                    }
                    if (combinedTextLower.contains(clusterPhraseLower)) {
                        LOGGER.debug(
                                "Substring phrase match found: '{}' in '{}'",
                                clusterPhraseLower,
                                combinedTextLower);
                        // Higher similarity for longer phrases (more specific matches)
                        final int phraseLength = clusterPhraseLower.split("\\s+").length;
                        final double similarity =
                                0.85
                                        + (phraseLength - 2)
                                                * 0.05; // 0.85 for 2 words, 0.90 for 3, 0.95 for 4,
                        // etc.
                        return Math.min(0.95, similarity); // Cap at 0.95
                    }
                }
            }

            // IMPROVEMENT: Check for exact phrase match first (significant boost)
            // This handles cases like "grocery store" matching the cluster phrase "grocery store"
            // Reconstruct the original phrase from tokens (sorted for consistency)
            final List<String> sortedTokens = new ArrayList<>(tokensCopy);
            Collections.sort(sortedTokens);
            final String reconstructedPhrase =
                    String.join(" ", sortedTokens).toLowerCase(Locale.ROOT);

            // Check if exact phrase exists in cluster (case-insensitive)
            boolean exactPhraseMatch = false;
            for (final String clusterPhrase : clusterCopy) {
                if (clusterPhrase != null
                        && clusterPhrase.toLowerCase(Locale.ROOT).equals(reconstructedPhrase)) {
                    exactPhraseMatch = true;
                    break;
                }
            }

            // If exact phrase match, return high similarity (0.85+)
            if (exactPhraseMatch) {
                LOGGER.debug("Exact phrase match found: '{}'", reconstructedPhrase);
                return 0.85; // High similarity for exact phrase match
            }

            // Also check if any bigram from tokens matches a cluster phrase
            // This handles cases where tokens include bigrams like "grocery store"
            for (final String token : tokensCopy) {
                if (token != null && token.contains(" ")) {
                    // This is a bigram, check if it matches any cluster phrase
                    final String tokenLower = token.toLowerCase(Locale.ROOT);
                    for (final String clusterPhrase : clusterCopy) {
                        if (clusterPhrase != null
                                && clusterPhrase.toLowerCase(Locale.ROOT).equals(tokenLower)) {
                            LOGGER.debug("Bigram phrase match found: '{}'", tokenLower);
                            return 0.80; // High similarity for bigram match
                        }
                    }
                }
            }

            // BUG FIX: Handle wildcard patterns in single-word tokens (e.g., "tst*" should match
            // "TST* MESSINA", "SQ*" should match "SQ* MERCHANT")
            // Check if any token starts with a cluster phrase that ends with "*"
            if (combinedText != null && !combinedText.isEmpty()) {
                final String combinedTextLower = combinedText.toLowerCase(Locale.ROOT);
                final String combinedTextUpper = combinedText.toUpperCase(Locale.ROOT);
                for (final String clusterPhrase : clusterCopy) {
                    if (clusterPhrase != null && clusterPhrase.endsWith("*")) {
                        final String prefix =
                                clusterPhrase
                                        .toLowerCase(Locale.ROOT)
                                        .substring(0, clusterPhrase.length() - 1);
                        final String prefixUpper = prefix.toUpperCase(Locale.ROOT);
                        // Check if any token starts with the prefix (case-insensitive)
                        for (final String token : tokensCopy) {
                            if (token != null) {
                                final String tokenLower = token.toLowerCase(Locale.ROOT);
                                final String tokenUpper = token.toUpperCase(Locale.ROOT);
                                if (tokenLower.startsWith(prefix)
                                        || tokenUpper.startsWith(prefixUpper + "*")
                                        || tokenUpper.startsWith(prefixUpper + " ")) {
                                    LOGGER.debug(
                                            "Wildcard token match found: '{}' matches '{}*'",
                                            token,
                                            prefix);
                                    // POS system codes (TST*, SQ*) get even higher similarity
                                    if ("tst".equals(prefix) || "sq".equals(prefix)) {
                                        return 0.95; // Very high similarity for POS system codes
                                    }
                                    return 0.90; // High similarity for other wildcard token matches
                                }
                            }
                        }
                        // Also check if combined text starts with the prefix (case-insensitive)
                        if (combinedTextLower.startsWith(prefix)
                                || combinedTextUpper.startsWith(prefixUpper + "*")
                                || combinedTextUpper.startsWith(prefixUpper + " ")) {
                            LOGGER.debug(
                                    "Wildcard combined text match found: '{}' matches '{}*'",
                                    combinedTextLower,
                                    prefix);
                            // POS system codes (TST*, SQ*) get even higher similarity
                            if ("tst".equals(prefix) || "sq".equals(prefix)) {
                                return 0.95; // Very high similarity for POS system codes
                            }
                            return 0.90; // High similarity for other wildcard combined text matches
                        }
                    }
                }
            }

            // Jaccard similarity: intersection / union
            final Set<String> intersection = new HashSet<>(tokensCopy);
            intersection.retainAll(clusterCopy);

            final Set<String> union = new HashSet<>(tokensCopy);
            union.addAll(clusterCopy);

            if (union.isEmpty()) {
                return 0.0;
            }

            // CRITICAL: Boundary condition - prevent division by zero
            double similarity = (double) intersection.size() / union.size();

            // IMPROVEMENT: Boost similarity if we have multiple token matches
            // This helps when we have "grocery" and "store" matching separately
            if (intersection.size() >= 2) {
                // Multiple token matches - boost similarity significantly
                similarity = Math.min(1.0, similarity + 0.20); // Increased from 0.15 to 0.20
            }

            // IMPROVEMENT: Additional boost if we have a single strong keyword match
            // This helps with cases like "NETFLIX" matching "netflix" in subscriptions cluster
            if (intersection.size() >= 1 && tokensCopy.size() <= 3) {
                // Small number of tokens with at least one match - likely a good match
                similarity = Math.min(1.0, similarity + 0.10);
            }

            // CRITICAL: Validate result (should be in [0, 1])
            if (Double.isNaN(similarity) || Double.isInfinite(similarity)) {
                if (LOGGER.isWarnEnabled()) {
                    LOGGER.warn(
                            "Invalid similarity calculated: {} (intersection: {}, union: {})",
                            similarity,
                            intersection.size(),
                            union.size());
                }
                return 0.0;
            }

            // CRITICAL: Ensure result is in valid range
            return Math.max(0.0, Math.min(1.0, similarity));

        } catch (Exception e) {
            // CRITICAL: Error handling - return 0.0 on error
            if (LOGGER.isErrorEnabled()) {
                LOGGER.error("Error calculating semantic similarity: {}", e.getMessage(), e);
            }
            return 0.0;
        }
    }

    /**
     * Ensure clusters are loaded (lazy loading with race condition prevention) CRITICAL:
     * Thread-safe, handles errors and boundary conditions
     */
    private void ensureClustersLoaded() {
        if (!clustersLoaded) {
            loadSemanticClustersFromSharedService();
        }
    }

    /**
     * Add semantic cluster (for dynamic learning) CRITICAL: Thread-safe, updates both local cache
     * and shared service Race condition prevention: Updates shared service first, then local cache
     */
    public synchronized void addSemanticCluster(final String category, final Set<String> keywords) {
        try {
            // CRITICAL: Input validation
            if (category == null || category.isBlank()) {
                LOGGER.warn("Cannot add semantic cluster: category is null or empty");
                return;
            }

            if (keywords == null || keywords.isEmpty()) {
                LOGGER.warn(
                        "Cannot add semantic cluster: keywords are null or empty for category: {}",
                        category);
                return;
            }

            // CRITICAL: Boundary condition - very large keyword set
            Set<String> safeKeywords = keywords;
            if (keywords.size() > 10_000) {
                if (LOGGER.isWarnEnabled()) {
                    LOGGER.warn(
                            "Keyword set too large ({}), limiting to first 10000", keywords.size());
                }
                safeKeywords = new HashSet<>(new ArrayList<>(keywords).subList(0, 10_000));
            }

            // CRITICAL: Create defensive copy to avoid external modification
            final Set<String> keywordsCopy = new HashSet<>(safeKeywords);

            // CRITICAL: Normalize category name
            final String normalizedCategory = category.toLowerCase(Locale.ROOT).trim();

            // CRITICAL: Update shared service first (single source of truth)
            if (merchantCategoryDataService != null) {
                // Add each keyword to shared service
                for (final String keyword : keywordsCopy) {
                    merchantCategoryDataService.addMerchantCategory(keyword, normalizedCategory);
                }
            } else {
                LOGGER.warn("MerchantCategoryDataService is null. Cannot update shared service.");
            }

            // CRITICAL: Update local cache (thread-safe, kept in memory for speed)
            categorySemanticClusters.put(normalizedCategory, keywordsCopy);

            if (LOGGER.isInfoEnabled()) {
                LOGGER.info(
                        "Added semantic cluster for category: {} with {} keywords (updated shared service and local cache)",
                        normalizedCategory,
                        keywordsCopy.size());
            }

        } catch (OutOfMemoryError e) {
            LOGGER.error("Out of memory adding semantic cluster for category: {}", category, e);
        } catch (Exception e) {
            // CRITICAL: Error handling - log but don't throw
            if (LOGGER.isErrorEnabled()) {
                LOGGER.error(
                        "Error adding semantic cluster for category: {}: {}",
                        category,
                        e.getMessage(),
                        e);
            }
        }
    }

    /**
     * Get semantic cluster for a category (for testing/debugging) CRITICAL: Thread-safe, returns
     * defensive copy
     */
    public synchronized Set<String> getSemanticCluster(final String category) {
        if (category == null || category.isBlank()) {
            return Collections.emptySet();
        }

        final String normalizedCategory = category.toLowerCase(Locale.ROOT).trim();
        final Set<String> cluster = categorySemanticClusters.get(normalizedCategory);

        if (cluster == null) {
            return Collections.emptySet();
        }

        // CRITICAL: Return defensive copy to prevent external modification
        return new HashSet<>(cluster);
    }

    /**
     * Get all category names (for testing/debugging) CRITICAL: Thread-safe, returns defensive copy
     */
    public synchronized Set<String> getAllCategories() {
        return new HashSet<>(categorySemanticClusters.keySet());
    }

    /** Semantic match result */
    public static class SemanticMatchResult {
        public final String category;
        public final double similarity;
        public final String method;

        public SemanticMatchResult(
                final String category, final double similarity, final String method) {
            this.category = category;
            this.similarity = similarity;
            this.method = method;
        }

        public boolean isHighConfidence() {
            return similarity >= 0.7;
        }

        public boolean isMediumConfidence() {
            return similarity >= 0.5 && similarity < 0.7;
        }

        @Override
        public String toString() {
            return String.format(
                    "SemanticMatchResult{category='%s', similarity=%.2f, method='%s'}",
                    category, similarity, method);
        }
    }

    /**
     * Context-only category inference. Called when the merchant-keyword clusters aren't loaded
     * (test env, first-boot bootstrapping) and therefore we can't do substring matching via the
     * corpus.
     *
     * <p>The logic is <strong>data-driven</strong>: a table of context rules ({@link ContextRule})
     * evaluated in order. To add a new archetype (e.g. a new merchant keyword set, or a new payment
     * channel), add a row to {@link #CONTEXT_RULES} — no new branches in code, no risk of shadowing
     * an earlier branch.
     *
     * <p>Rules higher in the list win ties. Structure:
     *
     * <ol>
     *   <li>Explicit payment-phrase or loan/investment account type → the account context IS the
     *       category.
     *   <li>Payment channel cues (ACH, POS, online).
     *   <li>Amount-magnitude heuristics.
     *   <li>Merchant keyword archetypes (groceries, dining, utilities, …).
     * </ol>
     */
    private SemanticMatchResult deriveFromContext(
            final String merchantName,
            final String description,
            final java.math.BigDecimal amount,
            final String paymentChannel,
            final String accountType,
            final String accountSubtype) {
        final CategorizationContext ctx =
                new CategorizationContext(
                        (safeLower(merchantName) + " " + safeLower(description)).trim(),
                        safeLower(accountType),
                        safeLower(accountSubtype),
                        safeLower(paymentChannel),
                        amount);
        for (final ContextRule rule : CONTEXT_RULES) {
            if (rule.matches(ctx)) {
                return new SemanticMatchResult(rule.category, rule.confidence, rule.source);
            }
        }
        return null;
    }

    private static String safeLower(final String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    // =========================================================================
    // Data-driven context rules — add new archetypes by adding a row here.
    // Order matters: rules higher in the list take priority on ties.
    // =========================================================================

    /** Bag of context fields a rule may inspect. */
    private static final class CategorizationContext {
        final String textLower;
        final String accountTypeLower;
        final String accountSubtypeLower;
        final String channelLower;
        final java.math.BigDecimal amount;

        CategorizationContext(
                final String text,
                final String type,
                final String sub,
                final String channel,
                final java.math.BigDecimal amount) {
            this.textLower = text;
            this.accountTypeLower = type;
            this.accountSubtypeLower = sub;
            this.channelLower = channel;
            this.amount = amount;
        }

        boolean textContainsAny(final java.util.Collection<String> phrases) {
            for (final String p : phrases) {
                if (textLower.contains(p)) {
                    return true;
                }
            }
            return false;
        }

        boolean accountMatchesAny(final java.util.Collection<String> keywords) {
            for (final String k : keywords) {
                if (accountTypeLower.contains(k) || accountSubtypeLower.contains(k)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** A single context-categorisation rule. Data, not code. */
    private static final class ContextRule {
        final String source;
        final String category;
        final double confidence;
        final java.util.function.Predicate<CategorizationContext> predicate;

        ContextRule(
                final String source,
                final String category,
                final double confidence,
                final java.util.function.Predicate<CategorizationContext> predicate) {
            this.source = source;
            this.category = category;
            this.confidence = confidence;
            this.predicate = predicate;
        }

        boolean matches(final CategorizationContext ctx) {
            return predicate.test(ctx);
        }
    }

    /** Phrases that unambiguously indicate a payment-action verb in the text. */
    private static final Set<String> PAYMENT_ACTION_PHRASES =
            Set.of(
                    AUTO_PAY,
                    AUTOPAY,
                    "auto-pay",
                    BILL_PAYMENT,
                    BILL_PAY,
                    MONTHLY_PAYMENT,
                    "direct debit",
                    "pmt");

    /** Account-type keyword sets grouped by the category they resolve to. */
    private static final Set<String> INVESTMENT_ACCOUNT_KEYWORDS =
            Set.of(INVESTMENT, BROKERAGE, "ira", "401k", "cd");

    private static final Set<String> LOAN_ACCOUNT_KEYWORDS = Set.of("loan", MORTGAGE);
    private static final Set<String> CREDIT_ACCOUNT_KEYWORDS = Set.of(CREDIT, "credit card");

    /** Merchant archetype keyword groups. */
    private static final Set<String> GROCERY_KEYWORDS =
            Set.of(
                    "grocery",
                    "supermarket",
                    "food market",
                    "farmers market",
                    "fresh food",
                    "produce market",
                    "food shopping");

    private static final Set<String> DINING_KEYWORDS =
            Set.of(
                    "coffee",
                    "cafe",
                    "restaurant",
                    "diner",
                    FAST_FOOD,
                    DINING,
                    "takeout",
                    "take out",
                    "delivery meal",
                    "lunch");
    private static final Set<String> TRANSPORT_KEYWORDS =
            Set.of(
                    "gas station",
                    "fuel",
                    "petrol",
                    "uber",
                    "lyft",
                    "taxi",
                    "parking",
                    "rideshare",
                    "ride share",
                    "transit",
                    SUBWAY);
    private static final Set<String> HEALTH_KEYWORDS =
            Set.of(
                    "pharmacy",
                    "drug store",
                    "doctor",
                    "hospital",
                    MEDICAL,
                    CLINIC,
                    "dentist",
                    URGENT_CARE);
    private static final Set<String> UTILITIES_KEYWORDS =
            Set.of(
                    ELECTRIC_BILL,
                    WATER_BILL,
                    GAS_BILL,
                    "internet bill",
                    "cable bill",
                    "phone bill",
                    "utility",
                    "electricity",
                    "internet service",
                    "cable service",
                    "trash service",
                    "sewer service",
                    "cable tv",
                    "cabletv");

    private static boolean hasPaymentPhrase(final CategorizationContext ctx) {
        return ctx.textContainsAny(PAYMENT_ACTION_PHRASES)
                || ctx.textLower.contains(" payment")
                || ctx.textLower.startsWith("payment ");
    }

    private static final List<ContextRule> CONTEXT_RULES =
            List.of(
                    // --- Account type ----------------------------------------------------
                    new ContextRule(
                            "CONTEXT_INVESTMENT",
                            INVESTMENT,
                            0.70,
                            ctx -> ctx.accountMatchesAny(INVESTMENT_ACCOUNT_KEYWORDS)),
                    new ContextRule(
                            "CONTEXT_LOAN",
                            PAYMENT,
                            0.70,
                            ctx -> ctx.accountMatchesAny(LOAN_ACCOUNT_KEYWORDS)),
                    // Credit-card account only yields "payment" when the text also
                    // contains a payment-phrase. A bare credit-card charge can be
                    // any category, so we don't force "payment" on all of them.
                    new ContextRule(
                            "CONTEXT_CREDIT_PAYMENT",
                            PAYMENT,
                            0.70,
                            ctx ->
                                    ctx.accountMatchesAny(CREDIT_ACCOUNT_KEYWORDS)
                                            && hasPaymentPhrase(ctx)),

                    // --- Explicit payment phrase (any account) --------------------------
                    new ContextRule(
                            "CONTEXT_PAYMENT_PHRASE",
                            PAYMENT,
                            0.65,
                            SemanticMatchingService::hasPaymentPhrase),

                    // --- Payment channel + amount ---------------------------------------
                    new ContextRule(
                            "CONTEXT_POS_SMALL",
                            DINING,
                            0.65,
                            ctx ->
                                    ("pos".equals(ctx.channelLower)
                                                    || "in_store".equals(ctx.channelLower))
                                            && ctx.amount != null
                                            && ctx.amount
                                                            .abs()
                                                            .compareTo(
                                                                    java.math.BigDecimal.valueOf(
                                                                            10))
                                                    < 0),
                    new ContextRule(
                            "CONTEXT_ACH", UTILITIES, 0.65, ctx -> "ach".equals(ctx.channelLower)),
                    new ContextRule(
                            "CONTEXT_POS",
                            SHOPPING,
                            0.60,
                            ctx ->
                                    "pos".equals(ctx.channelLower)
                                            || "in_store".equals(ctx.channelLower)),
                    new ContextRule(
                            "CONTEXT_ONLINE",
                            SUBSCRIPTIONS,
                            0.60,
                            ctx -> "online".equals(ctx.channelLower)),

                    // --- Amount-magnitude heuristics ------------------------------------
                    new ContextRule(
                            "CONTEXT_LARGE_POSITIVE",
                            DEPOSIT,
                            0.65,
                            ctx ->
                                    ctx.amount != null
                                            && ctx.amount.signum() > 0
                                            && ctx.amount
                                                            .abs()
                                                            .compareTo(
                                                                    java.math.BigDecimal.valueOf(
                                                                            1000))
                                                    >= 0),
                    new ContextRule(
                            "CONTEXT_SMALL_NEGATIVE",
                            "fees",
                            0.65,
                            ctx ->
                                    ctx.amount != null
                                            && ctx.amount.signum() < 0
                                            && ctx.amount.abs().compareTo(java.math.BigDecimal.ONE)
                                                    < 0),

                    // --- Merchant keyword archetypes ------------------------------------
                    new ContextRule(
                            "CONTEXT_GROCERY_KEYWORD",
                            GROCERIES,
                            0.65,
                            ctx -> ctx.textContainsAny(GROCERY_KEYWORDS)),
                    new ContextRule(
                            "CONTEXT_DINING_KEYWORD",
                            DINING,
                            0.65,
                            ctx -> ctx.textContainsAny(DINING_KEYWORDS)),
                    new ContextRule(
                            "CONTEXT_TRANSPORT_KEYWORD",
                            "transportation",
                            0.65,
                            ctx -> ctx.textContainsAny(TRANSPORT_KEYWORDS)),
                    new ContextRule(
                            "CONTEXT_HEALTH_KEYWORD",
                            HEALTH,
                            0.65,
                            ctx -> ctx.textContainsAny(HEALTH_KEYWORDS)),
                    new ContextRule(
                            "CONTEXT_UTILITIES_KEYWORD",
                            UTILITIES,
                            0.65,
                            ctx -> ctx.textContainsAny(UTILITIES_KEYWORDS)));
}
