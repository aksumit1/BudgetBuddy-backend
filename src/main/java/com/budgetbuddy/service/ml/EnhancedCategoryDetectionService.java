package com.budgetbuddy.service.ml;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.util.*;

/**
 * Enhanced Category Detection Service
 * Combines rule-based, fuzzy matching, and ML approaches for best accuracy
 * 
 * Strategy:
 * 1. Rule-based detection (fast, deterministic)
 * 2. Fuzzy matching (handles variations)
 * 3. ML prediction (learns from data)
 * 4. Confidence-weighted combination
 */
@Service
public class EnhancedCategoryDetectionService {
    
    private static final Logger logger = LoggerFactory.getLogger(EnhancedCategoryDetectionService.class);
    
    private final FuzzyMatchingService fuzzyMatchingService;
    private final CategoryClassificationModel mlModel;
    private final SemanticMatchingService semanticMatchingService;
    
    // Known merchant categories for fuzzy matching
    private final Map<String, String> knownMerchants = new HashMap<>();
    
    public EnhancedCategoryDetectionService(FuzzyMatchingService fuzzyMatchingService,
                                           CategoryClassificationModel mlModel,
                                           SemanticMatchingService semanticMatchingService) {
        this.fuzzyMatchingService = fuzzyMatchingService;
        this.mlModel = mlModel;
        this.semanticMatchingService = semanticMatchingService;
        initializeKnownMerchants();
    }
    
    @PostConstruct
    public void init() {
        mlModel.loadModel();
        logger.info("EnhancedCategoryDetectionService initialized with {} known merchants", knownMerchants.size());
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
    public DetectionResult detectCategory(String merchantName, String description, 
                                         BigDecimal amount, String paymentChannel, 
                                         String categoryString) {
        return detectCategoryWithContext(merchantName, description, amount, paymentChannel, 
                                        categoryString, null, null);
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
    public DetectionResult detectCategoryWithContext(String merchantName, String description, 
                                                     BigDecimal amount, String paymentChannel, 
                                                     String categoryString,
                                                     String accountType,
                                                     String accountSubtype) {
        try {
            logger.debug("EnhancedCategoryDetection (context-aware): merchant='{}', description='{}', amount={}, channel='{}', accountType='{}', accountSubtype='{}'",
                    merchantName, description, amount, paymentChannel, accountType, accountSubtype);
            
            // CRITICAL: Validate BigDecimal amount
            if (amount != null && (amount.compareTo(java.math.BigDecimal.valueOf(1_000_000_000)) > 0 ||
                    amount.compareTo(java.math.BigDecimal.valueOf(-1_000_000_000)) < 0)) {
                logger.warn("Amount out of reasonable range: {}", amount);
                // Continue processing but use null for amount string
                amount = null;
            }
            
            List<DetectionResult> results = new ArrayList<>();
        
        // Method 1: Rule-based detection (if we have a rule-based service)
        // This would call the existing parseCategory method
        // For now, we'll skip this as it's handled in CSVImportService
        
        // Method 2: Fuzzy matching on known merchants
        // CRITICAL FIX: Also check description when merchantName is null (common in PDF/CSV imports)
        // Many imports have merchant info in description field, not merchantName field
        String fuzzyMatchText = null;
        if (merchantName != null && !merchantName.trim().isEmpty()) {
            fuzzyMatchText = merchantName;
        } else if (description != null && !description.trim().isEmpty()) {
            // Use description for fuzzy matching when merchantName is null
            fuzzyMatchText = description;
            logger.debug("EnhancedCategoryDetection: Using description for fuzzy matching (merchantName is null): '{}'", description);
        }
        
        if (fuzzyMatchText != null) {
            FuzzyMatchingService.MatchResult fuzzyMatch = fuzzyMatchingService.findBestMatch(
                    fuzzyMatchText, new ArrayList<>(knownMerchants.keySet()));
            
            if (fuzzyMatch != null) {
                String matchedMerchant = fuzzyMatch.original;
                String category = knownMerchants.get(matchedMerchant);
                // CRITICAL: Check for null category (merchant might have been removed)
                if (category != null) {
                    String fuzzyTextLower = fuzzyMatchText.toLowerCase();
                    
                    // CRITICAL FIX: Reject income category if text contains "whse" or "warehouse" (store locations)
                    // "COSTCO WHSE" (Costco Warehouse) is a store, not "Costco Wholesale Corporation" (payroll)
                    boolean shouldSkip = false;
                    if ("income".equals(category)) {
                        if (fuzzyTextLower.contains("whse") || fuzzyTextLower.contains("warehouse")) {
                            logger.debug("Rejecting income category for '{}' - contains 'whse' or 'warehouse' (store location, not corporation)", 
                                    fuzzyMatchText);
                            // Skip this match - it's a false positive
                            shouldSkip = true;
                        }
                    }
                    
                    // CRITICAL FIX: Reject utilities category if text contains airport-related terms
                    // "SEATTLEAP CART/CHAIR" (Seattle Airport cart) should not match "Seattle Public Utilities"
                    if (!shouldSkip && "utilities".equals(category)) {
                        if (fuzzyTextLower.contains("airport") ||
                            fuzzyTextLower.contains("seattleap") ||
                            fuzzyTextLower.contains("seattle ap") ||
                            (fuzzyTextLower.contains("seattle") && 
                             (fuzzyTextLower.contains("cart") || fuzzyTextLower.contains("chair")))) {
                            logger.debug("Rejecting utilities category for '{}' - contains airport-related terms (airport expense, not utility)", 
                                    fuzzyMatchText);
                            // Skip this match - it's a false positive
                            shouldSkip = true;
                        }
                    }
                    
                    // Only add to results if we shouldn't skip this match
                    if (!shouldSkip) {
                        results.add(new DetectionResult(
                                category,
                                fuzzyMatch.combinedScore,
                                "FUZZY_MATCH",
                                "Matched " + (merchantName != null ? "merchant" : "description") + ": " + matchedMerchant
                        ));
                        logger.debug("Fuzzy match found: '{}' -> '{}' (confidence: {:.2f})", 
                                fuzzyMatchText, category, fuzzyMatch.combinedScore);
                    }
                } else {
                    logger.warn("Fuzzy match found for '{}' but category is null", matchedMerchant);
                }
            } else {
                logger.debug("EnhancedCategoryDetection: No fuzzy match found for '{}'", fuzzyMatchText);
            }
        } else {
            logger.debug("EnhancedCategoryDetection: Skipping fuzzy matching - both merchantName and description are null/empty");
        }
        
        // Method 3: Semantic matching with context-aware matching
        // CRITICAL: Error handling - wrap in try-catch to prevent failures from breaking detection
        // CRITICAL FIX: Skip semantic matching when merchantName is null/empty to save resources
        // Semantic matching will still use description, but we skip if both are null
        if (semanticMatchingService != null && 
            ((merchantName != null && !merchantName.trim().isEmpty()) || 
             (description != null && !description.trim().isEmpty()))) {
            try {
                logger.debug("EnhancedCategoryDetection: Attempting semantic matching - merchant='{}', description='{}'", 
                        merchantName, description);
                
                // CRITICAL: Use context-aware matching (amount, payment channel, account type, account subtype)
                SemanticMatchingService.SemanticMatchResult semanticMatch = 
                        semanticMatchingService.findBestSemanticMatchWithContext(
                        merchantName, 
                        description,
                        amount,  // Transaction amount for context
                        paymentChannel,  // Payment channel for context
                        accountType,  // Account type for context
                        accountSubtype  // Account subtype for context
                    );
                
                if (semanticMatch != null && semanticMatch.category != null) {
                    logger.debug("EnhancedCategoryDetection: ✅ Semantic match found - category='{}', similarity={:.2f}, method='{}'", 
                            semanticMatch.category, semanticMatch.similarity, semanticMatch.method != null ? semanticMatch.method : "SEMANTIC_MATCH");
                    results.add(new DetectionResult(
                            semanticMatch.category,
                            semanticMatch.similarity,
                            "SEMANTIC_MATCH",
                            "Context-aware semantic similarity match"
                    ));
                } else {
                    logger.debug("EnhancedCategoryDetection: Semantic matching returned null or no category match");
                }
            } catch (Exception e) {
                // CRITICAL: Error handling - log but continue with other methods
                logger.warn("EnhancedCategoryDetection: Semantic matching failed, continuing with other methods: {}", e.getMessage());
                logger.debug("EnhancedCategoryDetection: Semantic matching exception details", e);
                // Don't add to results, continue with other detection methods
            }
        } else {
            if (semanticMatchingService == null) {
                logger.debug("EnhancedCategoryDetection: Skipping semantic matching - service is null");
            } else {
                logger.debug("EnhancedCategoryDetection: Skipping semantic matching - both merchantName and description are null/empty");
            }
        }
        
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
            logger.debug("ML prediction: '{}' (confidence: {:.2f})", 
                    mlPrediction.category, mlPrediction.confidence);
        }
        
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
        Map<String, Double> categoryScores = new HashMap<>();
        for (DetectionResult result : results) {
            double weight = "FUZZY_MATCH".equals(result.method) && result.confidence >= 0.85 ? 0.7 : 0.5;
            
            // CRITICAL FIX: If amount is negative (expense), heavily penalize "income" category
            // This prevents merchants like "Home Depot", "Chipotle", "Chevron" from being classified as income
            if (amount != null && amount.compareTo(java.math.BigDecimal.ZERO) < 0) {
                // Negative amount = expense transaction
                if ("income".equals(result.category)) {
                    // Heavily penalize income category for negative amounts
                    weight *= 0.1; // Reduce weight by 90%
                    logger.debug("Penalizing income category for negative amount transaction: merchant='{}', amount={}", 
                            merchantName, amount);
                } else if (!isExpenseCategory(result.category)) {
                    // Slightly penalize non-expense categories (investment, etc.) for negative amounts
                    weight *= 0.8;
                }
            } else if (amount != null && amount.compareTo(java.math.BigDecimal.ZERO) > 0) {
                // Positive amount = income transaction
                // Slightly boost income category for positive amounts
                if ("income".equals(result.category)) {
                    weight *= 1.2;
                }
            }
            
            categoryScores.merge(result.category, result.confidence * weight, Double::sum);
        }
        
        // Find best category
        String bestCategory = categoryScores.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
        
        double bestConfidence = bestCategory != null ? categoryScores.get(bestCategory) : 0.0;
        
        // CRITICAL FIX: Final validation - if amount is negative and category is "income", reject it
        if (amount != null && amount.compareTo(java.math.BigDecimal.ZERO) < 0 && "income".equals(bestCategory)) {
            logger.warn("Rejecting income category for negative amount transaction: merchant='{}', description='{}', amount={}. Using second best category or 'other'", 
                    merchantName, description, amount);
            // Find second best category that's not "income"
            bestCategory = categoryScores.entrySet().stream()
                    .filter(e -> !"income".equals(e.getKey()))
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse("other");
            bestConfidence = bestCategory != null ? categoryScores.getOrDefault(bestCategory, 0.5) : 0.5;
        }
        
        return new DetectionResult(
                bestCategory,
                Math.min(bestConfidence, 1.0),
                "COMBINED",
                "Combined " + results.size() + " methods"
        );
        } catch (Exception e) {
            logger.error("Error in detectCategory", e);
            // CRITICAL: For null inputs, return NONE instead of ERROR
            // This matches test expectations and provides better UX
            if ((merchantName == null || merchantName.trim().isEmpty()) &&
                (description == null || description.trim().isEmpty()) &&
                amount == null && paymentChannel == null && categoryString == null) {
                return new DetectionResult(null, 0.0, "NONE", "No inputs provided");
            }
            // Return safe fallback result for other errors
            return new DetectionResult(null, 0.0, "ERROR", "Error during detection: " + e.getMessage());
        }
    }
    
    /**
     * Check if a category is an expense category (not income or investment)
     */
    private boolean isExpenseCategory(String category) {
        if (category == null) return false;
        String catLower = category.toLowerCase();
        // Expense categories
        return catLower.equals("groceries") || catLower.equals("dining") || 
               catLower.equals("transportation") || catLower.equals("shopping") ||
               catLower.equals("entertainment") || catLower.equals("healthcare") ||
               catLower.equals("rent") || catLower.equals("utilities") ||
               catLower.equals("subscriptions") || catLower.equals("travel") ||
               catLower.equals("home improvement") || catLower.equals("pet") ||
               catLower.equals("tech") || catLower.equals("other") ||
               catLower.equals("payment") || catLower.equals("cash") ||
               catLower.equals("transfer");
    }
    
    /**
     * Train ML model with a transaction
     */
    public void trainModel(String merchantName, String description, String amount,
                          String paymentChannel, String actualCategory) {
        try {
            // CRITICAL: Validate inputs before training
            if (actualCategory == null || actualCategory.trim().isEmpty()) {
                logger.debug("Skipping training: category is null or empty");
                return;
            }
            
            mlModel.train(merchantName, description, amount, paymentChannel, actualCategory);
        } catch (Exception e) {
            logger.error("Error training ML model", e);
            // Don't throw - training failure shouldn't break transaction processing
        }
    }
    
    /**
     * Initialize known merchants database
     * COMPREHENSIVE: Includes major retailers, food chains, grocery stores, utilities,
     * subscriptions, insurance, tech companies, banks, investment firms, and more
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
        knownMerchants.put("walmart", "groceries");
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
        knownMerchants.put("aldi", "groceries");
        knownMerchants.put("winco", "groceries");
        knownMerchants.put("meijer", "groceries");
        knownMerchants.put("hy-vee", "groceries");
        knownMerchants.put("hyvee", "groceries");
        knownMerchants.put("shoprite", "groceries");
        knownMerchants.put("king soopers", "groceries");
        knownMerchants.put("ralphs", "groceries");
        knownMerchants.put("ralph's", "groceries");
        knownMerchants.put("fred meyer", "groceries");
        knownMerchants.put("fredmeyer", "groceries");
        knownMerchants.put("Food Lion", "groceries");
        knownMerchants.put("Giant Food", "groceries");
        knownMerchants.put("The GIANT Company", "groceries");
        knownMerchants.put("Martin's Food Markets", "groceries");
        knownMerchants.put("Hannaford", "groceries");
        knownMerchants.put("Stop & Shop", "groceries");
        knownMerchants.put("Ralphs", "groceries");
        knownMerchants.put("Smith's Food and Drug", "groceries");
        knownMerchants.put("King Soopers", "groceries");
        knownMerchants.put("Fred Meyer", "groceries");
        knownMerchants.put("Food 4 Less", "groceries");
        knownMerchants.put("Foods Co.", "groceries");
        knownMerchants.put("Mariano's", "groceries");
        knownMerchants.put("Harris Teeter", "groceries");
        knownMerchants.put("Pick 'n Save", "groceries");
        knownMerchants.put("QFC", "groceries");
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
        knownMerchants.put("United Supermarkets (including Market Street, Amigos, and United Express formats)", "groceries");
        knownMerchants.put("Pavilions", "groceries");
        knownMerchants.put("Star Market", "groceries");
        knownMerchants.put("Jagalchi", "groceries");
        knownMerchants.put("Zion Market", "groceries");
        knownMerchants.put("CU", "groceries");
        knownMerchants.put("Patel Brothers", "groceries");
        knownMerchants.put("DK Market", "groceries");
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
        knownMerchants.put("Giant Eagle", "groceries");
        knownMerchants.put("Erwan's Market", "groceries");
        knownMerchants.put("Sprouts Market", "groceries");
        knownMerchants.put("Erewhon Market", "groceries");
        knownMerchants.put("Market of Choice", "groceries");
        knownMerchants.put("Cumberland Farms", "groceries");
        knownMerchants.put("Tops Friendly Market", "groceries");
        knownMerchants.put("Big Y", "groceries");
        knownMerchants.put("Food 4 Less", "groceries");
        knownMerchants.put("Foods Co.", "groceries");
        knownMerchants.put("Mariano's", "groceries");
        knownMerchants.put("Dillon's", "groceries");
        knownMerchants.put("Gourmet Garage", "groceries");


        
        // ========== FOOD CHAINS / DINING ==========
        knownMerchants.put("subway", "dining");
        knownMerchants.put("panda express", "dining");
        knownMerchants.put("starbucks", "dining");
        knownMerchants.put("starbucks store", "dining");
        knownMerchants.put("starbucks coffee", "dining");
        knownMerchants.put("chipotle", "dining");
        knownMerchants.put("chipotle mex gr", "dining");
        knownMerchants.put("chipotle mexican grill", "dining");
        knownMerchants.put("hoffman", "dining");
        knownMerchants.put("hoffmans", "dining");
        knownMerchants.put("hoffman's", "dining");
        knownMerchants.put("hoffman bakery", "dining");
        knownMerchants.put("canam pizza", "dining");
        knownMerchants.put("canam", "dining");
        knownMerchants.put("mcdonalds", "dining");
        knownMerchants.put("mcdonald's", "dining");
        knownMerchants.put("burger king", "dining");
        knownMerchants.put("taco bell", "dining");
        knownMerchants.put("pizza hut", "dining");
        knownMerchants.put("dominos", "dining");
        knownMerchants.put("domino's", "dining");
        knownMerchants.put("panera", "dining");
        knownMerchants.put("panera bread", "dining");
        knownMerchants.put("wendy's", "dining");
        knownMerchants.put("wendys", "dining");
        knownMerchants.put("kfc", "dining");
        knownMerchants.put("kentucky fried chicken", "dining");
        knownMerchants.put("dunkin", "dining");
        knownMerchants.put("dunkin donuts", "dining");
        knownMerchants.put("dunkin' donuts", "dining");
        knownMerchants.put("dairy queen", "dining");
        knownMerchants.put("dq", "dining");
        knownMerchants.put("papa john's", "dining");
        knownMerchants.put("papa johns", "dining");
        knownMerchants.put("little caesars", "dining");
        knownMerchants.put("olive garden", "dining");
        knownMerchants.put("red lobster", "dining");
        knownMerchants.put("applebees", "dining");
        knownMerchants.put("applebee's", "dining");
        knownMerchants.put("outback", "dining");
        knownMerchants.put("outback steakhouse", "dining");
        knownMerchants.put("chili's", "dining");
        knownMerchants.put("chilis", "dining");
        knownMerchants.put("texas roadhouse", "dining");
        knownMerchants.put("ihop", "dining");
        knownMerchants.put("denny's", "dining");
        knownMerchants.put("dennys", "dining");
        knownMerchants.put("waffle house", "dining");
        knownMerchants.put("five guys", "dining");
        knownMerchants.put("shake shack", "dining");
        knownMerchants.put("in-n-out", "dining");
        knownMerchants.put("in n out", "dining");
        knownMerchants.put("whataburger", "dining");
        knownMerchants.put("culver's", "dining");
        knownMerchants.put("culvers", "dining");
        knownMerchants.put("sonic", "dining");
        knownMerchants.put("sonic drive-in", "dining");
        knownMerchants.put("arby's", "dining");
        knownMerchants.put("arbys", "dining");
        knownMerchants.put("jack in the box", "dining");
        knownMerchants.put("white castle", "dining");
        knownMerchants.put("qdoba", "dining");
        knownMerchants.put("habit grill", "dining");
        knownMerchants.put("moe's", "dining");
        knownMerchants.put("moes", "dining");
        knownMerchants.put("baja fresh", "dining");
        knownMerchants.put("del taco", "dining");
        knownMerchants.put("carl's jr", "dining");
        knownMerchants.put("carls jr", "dining");
        knownMerchants.put("hardee's", "dining");
        knownMerchants.put("hardees", "dining");
        knownMerchants.put("panda express", "dining");
        knownMerchants.put("pf chang's", "dining");
        knownMerchants.put("pf changs", "dining");
        knownMerchants.put("p.f. chang's", "dining");
        knownMerchants.put("cheesecake factory", "dining");
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
        knownMerchants.put("simply indian restaur", "dining");
        knownMerchants.put("simply indian restaurant", "dining");
        knownMerchants.put("simplyindian restaur", "dining");
        knownMerchants.put("simplyindian restaurant", "dining");
        knownMerchants.put("skills rainbow room", "dining");
        knownMerchants.put("skillsrainbow room", "dining");
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
        knownMerchants.put("laughing monk brewing", "dining");
        knownMerchants.put("laughingmonk brewing", "dining");
        knownMerchants.put("laughing monk", "dining");
        knownMerchants.put("laughingmonk", "dining");
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
        knownMerchants.put("banaras restaurant", "dining");
        knownMerchants.put("banarasrestaurant", "dining");
        knownMerchants.put("resy", "dining");
        knownMerchants.put("maxmillen", "dining");
        knownMerchants.put("maxmillian", "dining");
        knownMerchants.put("maximilian", "dining");
        
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
        knownMerchants.put("pge", "utilities");
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
        knownMerchants.put("frontier", "utilities");
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
        
        // ========== ENTERTAINMENT (Streaming Services) ==========
        // Streaming services are entertainment, not subscriptions category
        // Subscriptions are detected separately via SubscriptionService
        knownMerchants.put("netflix", "entertainment");
        knownMerchants.put("xfinity tv", "entertainment");
        knownMerchants.put("xfinitytv", "entertainment");
        knownMerchants.put("hulu", "entertainment");
        knownMerchants.put("huluplus", "entertainment");
        knownMerchants.put("hulu plus", "entertainment");
        knownMerchants.put("disney", "entertainment");
        knownMerchants.put("disney+", "entertainment");
        knownMerchants.put("disney plus", "entertainment");
        knownMerchants.put("amazon prime", "entertainment");
        knownMerchants.put("prime video", "entertainment");
        knownMerchants.put("spotify", "entertainment");
        knownMerchants.put("apple music", "entertainment");
        knownMerchants.put("youtube premium", "entertainment");
        knownMerchants.put("youtube tv", "entertainment");
        knownMerchants.put("peacock", "entertainment");
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
        
        // ========== SUBSCRIPTIONS (Software/Non-Entertainment) ==========
        knownMerchants.put("adobe", "subscriptions");
        knownMerchants.put("adobe creative cloud", "subscriptions");
        knownMerchants.put("microsoft 365", "subscriptions");
        knownMerchants.put("office 365", "subscriptions");
        knownMerchants.put("dropbox", "subscriptions");
        knownMerchants.put("icloud", "subscriptions");
        knownMerchants.put("google drive", "subscriptions");
        knownMerchants.put("google one", "subscriptions");
        knownMerchants.put("audible", "subscriptions");
        knownMerchants.put("kindle unlimited", "subscriptions");
        knownMerchants.put("scribd", "subscriptions");
        knownMerchants.put("linkedin", "subscriptions");
        knownMerchants.put("linkedin premium", "subscriptions");
        knownMerchants.put("grammarly", "subscriptions");
        knownMerchants.put("nordvpn", "subscriptions");
        knownMerchants.put("expressvpn", "subscriptions");
        knownMerchants.put("surfshark", "subscriptions");
        knownMerchants.put("zoom", "subscriptions");
        knownMerchants.put("slack", "subscriptions");
        knownMerchants.put("github", "subscriptions");
        knownMerchants.put("github pro", "subscriptions");
        knownMerchants.put("canva", "subscriptions");
        knownMerchants.put("canva pro", "subscriptions");
        
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
        knownMerchants.put("petplan", "insurance");
        knownMerchants.put("pet plan", "insurance");
        knownMerchants.put("healthy paws", "insurance");
        knownMerchants.put("trupanion", "insurance");
        knownMerchants.put("embrace", "insurance");
        knownMerchants.put("embrace pet insurance", "insurance");
        knownMerchants.put("nationwide pet", "insurance");
        knownMerchants.put("pets best", "insurance");
        knownMerchants.put("petsbest", "insurance");
        knownMerchants.put("figo", "insurance");
        knownMerchants.put("figo pet insurance", "insurance");
        // Health Insurance
        knownMerchants.put("blue cross", "insurance");
        knownMerchants.put("blue cross blue shield", "insurance");
        knownMerchants.put("bcbs", "insurance");
        knownMerchants.put("aetna", "insurance");
        knownMerchants.put("cigna", "insurance");
        knownMerchants.put("unitedhealthcare", "insurance");
        knownMerchants.put("united healthcare", "insurance");
        knownMerchants.put("humana", "insurance");
        knownMerchants.put("kaiser permanente", "insurance");
        knownMerchants.put("kaiser", "insurance");
        knownMerchants.put("anthem", "insurance");
        knownMerchants.put("anthem blue cross", "insurance");
        
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
        knownMerchants.put("adobe", "tech");
        knownMerchants.put("adobe systems", "tech");
        // AI Providers
        knownMerchants.put("openai", "tech");
        knownMerchants.put("open ai", "tech");
        knownMerchants.put("anthropic", "tech");
        knownMerchants.put("anthropic ai", "tech");
        knownMerchants.put("claude", "tech");
        knownMerchants.put("chatgpt", "tech");
        knownMerchants.put("chat gpt", "tech");
        knownMerchants.put("cohere", "tech");
        knownMerchants.put("hugging face", "tech");
        knownMerchants.put("huggingface", "tech");
        knownMerchants.put("stability ai", "tech");
        knownMerchants.put("stability", "tech");
        knownMerchants.put("midjourney", "tech");
        knownMerchants.put("replicate", "tech");
        knownMerchants.put("together ai", "tech");
        knownMerchants.put("together", "tech");
        knownMerchants.put("perplexity", "tech");
        knownMerchants.put("cursor", "tech");
        knownMerchants.put("cursor ai", "tech");
        knownMerchants.put("cursor, ai", "tech");
        knownMerchants.put("cursor ai powered ide", "tech");
        
        // ========== INTERNET SERVICES / COMPANIES ==========
        knownMerchants.put("google", "tech");
        knownMerchants.put("alphabet", "tech");
        knownMerchants.put("microsoft", "tech");
        knownMerchants.put("apple", "tech");
        knownMerchants.put("amazon", "tech");
        knownMerchants.put("amazon web services", "tech");
        knownMerchants.put("aws", "tech");
        knownMerchants.put("meta", "tech");
        knownMerchants.put("facebook", "tech");
        knownMerchants.put("instagram", "tech");
        knownMerchants.put("twitter", "tech");
        knownMerchants.put("x", "tech");
        knownMerchants.put("linkedin", "tech");
        knownMerchants.put("snapchat", "tech");
        knownMerchants.put("snap", "tech");
        knownMerchants.put("tiktok", "tech");
        knownMerchants.put("reddit", "tech");
        knownMerchants.put("discord", "tech");
        knownMerchants.put("slack", "tech");
        knownMerchants.put("zoom", "tech");
        knownMerchants.put("salesforce", "tech");
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
        knownMerchants.put("github", "tech");
        
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
        knownMerchants.put("chase", "transfer");
        knownMerchants.put("chase bank", "transfer");
        knownMerchants.put("bank of america", "transfer");
        knownMerchants.put("bofa", "transfer");
        knownMerchants.put("wells fargo", "transfer");
        knownMerchants.put("wf", "transfer");
        knownMerchants.put("citibank", "transfer");
        knownMerchants.put("citi", "transfer");
        knownMerchants.put("us bank", "transfer");
        knownMerchants.put("usbank", "transfer");
        knownMerchants.put("pnc", "transfer");
        knownMerchants.put("pnc bank", "transfer");
        knownMerchants.put("td bank", "transfer");
        knownMerchants.put("tdbank", "transfer");
        knownMerchants.put("capital one", "transfer");
        knownMerchants.put("capitalone", "transfer");
        knownMerchants.put("ally bank", "transfer");
        knownMerchants.put("ally", "transfer");
        knownMerchants.put("discover bank", "transfer");
        knownMerchants.put("synchrony bank", "transfer");
        knownMerchants.put("synchrony", "transfer");
        knownMerchants.put("goldman sachs", "transfer");
        knownMerchants.put("marcus", "transfer");
        knownMerchants.put("marcus by goldman sachs", "transfer");
        knownMerchants.put("american express", "transfer");
        knownMerchants.put("amex", "transfer");
        knownMerchants.put("schwab bank", "transfer");
        knownMerchants.put("charles schwab bank", "transfer");
        knownMerchants.put("usaa bank", "transfer");
        knownMerchants.put("usaa", "transfer");
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
        knownMerchants.put("citizens bank", "transfer");
        knownMerchants.put("citizens", "transfer");
        knownMerchants.put("huntington bank", "transfer");
        knownMerchants.put("huntington", "transfer");
        knownMerchants.put("keybank", "transfer");
        knownMerchants.put("key bank", "transfer");
        knownMerchants.put("regions bank", "transfer");
        knownMerchants.put("regions", "transfer");
        knownMerchants.put("fifth third", "transfer");
        knownMerchants.put("fifth third bank", "transfer");
        knownMerchants.put("53", "transfer");
        knownMerchants.put("truist", "transfer");
        knownMerchants.put("suntrust", "transfer");
        knownMerchants.put("bb&t", "transfer");
        knownMerchants.put("bbt", "transfer");
        knownMerchants.put("m&t bank", "transfer");
        knownMerchants.put("mt bank", "transfer");
        knownMerchants.put("comerica", "transfer");
        knownMerchants.put("comerica bank", "transfer");
        knownMerchants.put("zions bank", "transfer");
        knownMerchants.put("zions", "transfer");
        knownMerchants.put("first national bank", "transfer");
        knownMerchants.put("first national", "transfer");
        
        // ========== INVESTMENT PROVIDERS ==========
        knownMerchants.put("fidelity", "investment");
        knownMerchants.put("fidelity investments", "investment");
        knownMerchants.put("vanguard", "investment");
        knownMerchants.put("vanguard group", "investment");
        knownMerchants.put("charles schwab", "investment");
        knownMerchants.put("schwab", "investment");
        knownMerchants.put("morgan stanley", "investment");
        knownMerchants.put("morganstanley", "investment");
        knownMerchants.put("goldman sachs", "investment");
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
        knownMerchants.put("ally", "investment");
        knownMerchants.put("sofi invest", "investment");
        knownMerchants.put("sofi", "investment");
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
        knownMerchants.put("paychex flex", "income");
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
        knownMerchants.put("amazon.com", "income");
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
        knownMerchants.put("jpmorgan chase", "income");
        knownMerchants.put("jpmorgan chase & co", "income");
        knownMerchants.put("bank of america", "income");
        knownMerchants.put("wells fargo", "income");
        knownMerchants.put("citigroup", "income");
        knownMerchants.put("citi", "income");
        knownMerchants.put("goldman sachs", "income");
        knownMerchants.put("morgan stanley", "income");
        knownMerchants.put("berkshire hathaway", "income");
        knownMerchants.put("exxon mobil", "income");
        // CRITICAL: Remove "chevron" from income - it's a gas station (expense), not payroll
        // Only "chevron corporation" should be income (for payroll)
        knownMerchants.put("chevron corporation", "income");
        knownMerchants.put("johnson & johnson", "income");
        knownMerchants.put("pfizer", "income");
        knownMerchants.put("unitedhealth group", "income");
        knownMerchants.put("unitedhealthcare", "income");
        knownMerchants.put("cvs health", "income");
        knownMerchants.put("cardinal health", "income");
        knownMerchants.put("mckesson", "income");
        knownMerchants.put("att", "income");
        knownMerchants.put("at&t", "income");
        knownMerchants.put("verizon communications", "income");
        knownMerchants.put("comcast", "income");
        knownMerchants.put("walt disney", "income");
        knownMerchants.put("disney", "income");
        knownMerchants.put("netflix", "income");
        // CRITICAL: Remove "home depot" from income - it's a home improvement store (expense), not payroll
        // Only "home depot inc" or "home depot corporation" should be income (for payroll)
        knownMerchants.put("home depot inc", "income");
        knownMerchants.put("home depot corporation", "income");
        knownMerchants.put("target corporation", "income");
        // CRITICAL: "costco wholesale" is ONLY for payroll from Costco Wholesale Corporation
        // Must be exact match or "costco wholesale corporation" to avoid matching "COSTCO WHSE" (warehouse stores)
        // "COSTCO WHSE" (Costco Warehouse) is a store location, not the corporation for payroll
        knownMerchants.put("costco wholesale corporation", "income");
        knownMerchants.put("costco wholesale inc", "income");
        knownMerchants.put("costco wholesale company", "income");
        // Only match "costco wholesale" if it's clearly a corporation (not "whse" or "warehouse")
        // Note: This is a fallback - prefer more specific patterns above
        // CRITICAL: Remove "lowes" from income - it's a home improvement store (expense), not payroll
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
        knownMerchants.put("personal capital", "transfer");
        knownMerchants.put("empower", "transfer");
        knownMerchants.put("empower retirement", "transfer");
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
        knownMerchants.put("ppd", "income"); // Prearranged Payment and Deposit Entry (Direct Deposit)
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
        knownMerchants.put("irs", "income");
        knownMerchants.put("internal revenue service", "income");
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
        knownMerchants.put("naics 21", "utilities"); // Mining, Quarrying, and Oil and Gas Extraction
        knownMerchants.put("naics 22", "utilities"); // Utilities
        knownMerchants.put("naics 23", "home improvement"); // Construction
        knownMerchants.put("naics 31", "shopping"); // Manufacturing - Food, Beverage, Tobacco
        knownMerchants.put("naics 32", "shopping"); // Manufacturing - Textile, Apparel, Leather
        knownMerchants.put("naics 33", "shopping"); // Manufacturing - Wood, Paper, Printing
        knownMerchants.put("naics 34", "shopping"); // Manufacturing - Petroleum, Chemical, Plastics
        knownMerchants.put("naics 35", "tech"); // Manufacturing - Computer, Electronic, Electrical
        knownMerchants.put("naics 36", "transportation"); // Manufacturing - Transportation Equipment
        knownMerchants.put("naics 42", "shopping"); // Wholesale Trade
        knownMerchants.put("naics 44", "shopping"); // Retail Trade - Building Materials, Garden, Motor Vehicle
        knownMerchants.put("naics 45", "shopping"); // Retail Trade - Furniture, Electronics, Apparel
        knownMerchants.put("naics 48", "transportation"); // Transportation and Warehousing
        knownMerchants.put("naics 49", "utilities"); // Couriers and Messengers
        knownMerchants.put("naics 51", "tech"); // Information
        knownMerchants.put("naics 52", "investment"); // Finance and Insurance
        knownMerchants.put("naics 53", "investment"); // Real Estate and Rental and Leasing
        knownMerchants.put("naics 54", "service"); // Professional, Scientific, and Technical Services
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
        knownMerchants.put("sepa instant", "transfer");
        knownMerchants.put("sepa instant credit transfer", "transfer");
        // TIPS (TARGET Instant Payment Settlement - Europe)
        knownMerchants.put("tips", "transfer");
        knownMerchants.put("target instant payment settlement", "transfer");
        
        logger.info("Initialized comprehensive known merchants database with {} merchants across all categories including payroll providers, MCC codes, ISO 20022, ACH SEC codes, SWIFT, FINCEN, IRS, GAAP, SIC/NAICS, and inter-bank transfer systems", 
            knownMerchants.size());
    }
    
    /**
     * Add known merchant (for dynamic learning)
     * Thread-safe: Uses synchronized block
     */
    public void addKnownMerchant(String merchantName, String category) {
        if (merchantName == null || merchantName.trim().isEmpty()) {
            logger.warn("Cannot add known merchant: merchant name is null or empty");
            return;
        }
        if (category == null || category.trim().isEmpty()) {
            logger.warn("Cannot add known merchant: category is null or empty");
            return;
        }
        
        synchronized (knownMerchants) {
            knownMerchants.put(merchantName.toLowerCase().trim(), category);
            logger.debug("Added known merchant: '{}' -> '{}'", merchantName, category);
        }
    }
    
    /**
     * Detection result
     */
    public static class DetectionResult {
        public final String category;
        public final double confidence;
        public final String method;
        public final String reason;
        
        public DetectionResult(String category, double confidence, String method, String reason) {
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
            return String.format("DetectionResult{category='%s', confidence=%.2f, method='%s', reason='%s'}",
                    category, confidence, method, reason);
        }
    }
}

