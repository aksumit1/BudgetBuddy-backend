package com.budgetbuddy.service;


import com.budgetbuddy.util.StringUtils;
import com.budgetbuddy.model.Subscription;
import com.budgetbuddy.model.dynamodb.SubscriptionTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.repository.dynamodb.SubscriptionRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.service.category.InMemoryMerchantService;
import com.budgetbuddy.service.category.FuzzyMatchingService;
import com.budgetbuddy.util.IdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Service for detecting and managing subscriptions
 * Identifies recurring transactions based on amount, merchant, and date patterns
 */
@Service
public class SubscriptionService {

    private static final Logger logger = LoggerFactory.getLogger(SubscriptionService.class);
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    
    private final SubscriptionRepository subscriptionRepository;
    private final TransactionRepository transactionRepository;
    private final InMemoryMerchantService merchantService;
    private final FuzzyMatchingService fuzzyMatchingService;
    
    // Subscription-related categories from merchant database
    private static final Set<String> SUBSCRIPTION_CATEGORIES = Set.of(
        "subscriptions", "streaming", "software", "membership", "cloud_storage",
        "tech", "entertainment", "health", "insurance", "education"
    );

    public SubscriptionService(
            final SubscriptionRepository subscriptionRepository,
            final TransactionRepository transactionRepository,
            final InMemoryMerchantService merchantService,
            final FuzzyMatchingService fuzzyMatchingService) {
        this.subscriptionRepository = subscriptionRepository;
        this.transactionRepository = transactionRepository;
        this.merchantService = merchantService;
        this.fuzzyMatchingService = fuzzyMatchingService;
    }

    /**
     * Detects subscriptions from user's transactions
     * Groups transactions by merchant and amount, then identifies recurring patterns
     */
    /**
     * Detects subscriptions using existing merchant database, fuzzy matching, and pattern detection
     * Rule: 3+ transactions with same amount pattern and recurring frequency = subscription
     */
    public List<Subscription> detectSubscriptions(final String userId) {
        logger.info("Detecting subscriptions for user: {}", userId);
        
        // Get all expense transactions
        List<TransactionTable> allExpenses = transactionRepository.findByUserId(userId, 0, 10000).stream()
                .filter(tx -> tx.getAmount() != null && tx.getAmount().compareTo(BigDecimal.ZERO) < 0)
                .collect(Collectors.toList());
        
        // CRITICAL FIX: Pre-filter transactions by category to focus on subscription-related expenses
        // This reduces noise from one-time payments and improves detection accuracy
        List<TransactionTable> subscriptionCandidates = allExpenses.stream()
                .filter(tx -> {
                    String categoryPrimary = tx.getCategoryPrimary();
                    String categoryDetailed = tx.getCategoryDetailed();
                    String merchantName = tx.getMerchantName();
                    String description = tx.getDescription();
                    
                    // Include transactions that match subscription indicators
                    // 1. Already categorized as subscription
                    if ((categoryPrimary != null && "subscriptions".equalsIgnoreCase(categoryPrimary)) ||
                        (categoryDetailed != null && "subscriptions".equalsIgnoreCase(categoryDetailed))) {
                        return true;
                    }
                    
                    // 2. Category matches subscription-related categories
                    if (categoryPrimary != null && SUBSCRIPTION_CATEGORIES.contains(categoryPrimary.toLowerCase())) {
                        return true;
                    }
                    
                    // 3. Known subscription merchant (from merchant database)
                    if (isKnownSubscriptionMerchant(merchantName, description)) {
                        return true;
                    }
                    
                    // 4. Category detailed contains subscription keywords
                    if (categoryDetailed != null) {
                        String detailedLower = categoryDetailed.toLowerCase();
                        if (detailedLower.contains("subscription") ||
                            detailedLower.contains("membership") ||
                            detailedLower.contains("recurring") ||
                            detailedLower.contains("streaming") ||
                            detailedLower.contains("software") ||
                            detailedLower.contains("cloud")) {
                            return true;
                        }
                    }
                    
                    // 5. Include all other expenses for recurring detection (mortgage, utilities, etc.)
                    // But skip very large one-time payments (likely not subscriptions)
                    // Assume subscriptions are typically < $1000/month
                    BigDecimal amount = tx.getAmount();
                    if (amount != null && amount.abs().compareTo(new BigDecimal("1000")) <= 0) {
                        return true;
                    }
                    
                    return false;
                })
                .collect(Collectors.toList());
        
        logger.debug("Pre-filtered {} subscription candidates from {} total expenses", 
                subscriptionCandidates.size(), allExpenses.size());
        
        // Group transactions by similar merchant (using fuzzy matching) and amount
        Map<String, List<TransactionTable>> transactionsByMerchant = groupTransactionsByMerchant(subscriptionCandidates);
        
        List<Subscription> detectedSubscriptions = new ArrayList<>();
        
        for (Map.Entry<String, List<TransactionTable>> entry : transactionsByMerchant.entrySet()) {
            String merchant = entry.getKey();
            List<TransactionTable> merchantTransactions = entry.getValue();
            
            // REQUIREMENT: Need at least 2 transactions to detect a subscription (lowered from 3)
            // This allows detection of newer subscriptions, but frequency validation ensures quality
            if (merchantTransactions.size() < 2) {
                logger.debug("Skipping merchant '{}': only {} transactions (need at least 2)", 
                        merchant, merchantTransactions.size());
                continue;
            }
            
            // Group by amount (within 5% tolerance)
            Map<BigDecimal, List<TransactionTable>> transactionsByAmount = groupByAmount(merchantTransactions);
            
            for (Map.Entry<BigDecimal, List<TransactionTable>> amountEntry : transactionsByAmount.entrySet()) {
                BigDecimal amount = amountEntry.getKey();
                List<TransactionTable> sameAmountTransactions = amountEntry.getValue();
                
                // REQUIREMENT: Need at least 2 transactions with same amount pattern (lowered from 3)
                // Frequency validation will ensure it's truly recurring
                if (sameAmountTransactions.size() < 2) {
                    logger.debug("Skipping amount group {} for merchant '{}': only {} transactions", 
                            amount, merchant, sameAmountTransactions.size());
                    continue;
                }
                
                // Sort by date
                sameAmountTransactions.sort((a, b) -> {
                    LocalDate dateA = parseDate(a.getTransactionDate());
                    LocalDate dateB = parseDate(b.getTransactionDate());
                    if (dateA == null || dateB == null) {
                        return 0;
                    }
                    return dateA.compareTo(dateB);
                });
                
                // Detect frequency
                Subscription.SubscriptionFrequency frequency = detectFrequency(sameAmountTransactions);
                
                if (frequency != null) {
                    TransactionTable firstTransaction = sameAmountTransactions.get(0);
                    LocalDate startDate = parseDate(firstTransaction.getTransactionDate());
                    
                    if (startDate != null) {
                        Subscription subscription = new Subscription();
                        
                        // CRITICAL FIX: Use actual merchantName from transaction, not group key
                        // Group key might be normalized or from description - always use real merchantName
                        String actualMerchantName = firstTransaction.getMerchantName();
                        if (actualMerchantName == null || actualMerchantName.trim().isEmpty()) {
                            // Only use description if merchantName is truly missing
                            actualMerchantName = firstTransaction.getDescription() != null ? 
                                firstTransaction.getDescription() : merchant;
                        }
                        
                        // CRITICAL FIX: Normalize generated subscription ID to lowercase for consistency
                        // Use actual merchant name for ID generation (for uniqueness)
                        String idMerchantKey = actualMerchantName != null ? 
                            StringUtils.normalizeMerchantName(actualMerchantName) : merchant;
                        String generatedId = IdGenerator.generateSubscriptionId(userId, idMerchantKey, amount);
                        String normalizedId = IdGenerator.normalizeUUID(generatedId);
                        subscription.setSubscriptionId(normalizedId);
                        subscription.setUserId(userId);
                        subscription.setAccountId(firstTransaction.getAccountId());
                        
                        // CRITICAL FIX: Always use actual merchantName, never use description as merchant
                        subscription.setMerchantName(actualMerchantName);
                        
                        // Use description from first transaction (most representative)
                        // If description is same as merchantName, keep it empty to avoid duplication
                        String description = firstTransaction.getDescription();
                        if (description != null && !description.trim().isEmpty()) {
                            String normalizedDesc = StringUtils.normalizeMerchantName(description);
                            String normalizedMerchant = StringUtils.normalizeMerchantName(actualMerchantName);
                            // Only set description if it's different from merchantName
                            if (!normalizedDesc.equals(normalizedMerchant) && 
                                !normalizedDesc.contains(normalizedMerchant) &&
                                !normalizedMerchant.contains(normalizedDesc)) {
                                subscription.setDescription(description);
                            } else {
                                // Description is same as merchant - don't duplicate
                                subscription.setDescription(null);
                            }
                        } else {
                            subscription.setDescription(null);
                        }
                        subscription.setAmount(amount); // Store amount as-is (negative for expenses)
                        subscription.setFrequency(frequency);
                        subscription.setStartDate(startDate);
                        
                        // CRITICAL FIX: Calculate nextPaymentDate from lastPaymentDate if available
                        // This ensures active subscriptions show up correctly
                        TransactionTable lastTransaction = sameAmountTransactions.get(sameAmountTransactions.size() - 1);
                        LocalDate lastPaymentDate = parseDate(lastTransaction.getTransactionDate());
                        if (lastPaymentDate != null) {
                            subscription.setLastPaymentDate(lastPaymentDate);
                            // Calculate nextPaymentDate from lastPaymentDate (more accurate for active subscriptions)
                            LocalDate nextPaymentDate = calculateNextPaymentDate(lastPaymentDate, frequency);
                            if (nextPaymentDate != null) {
                                subscription.setNextPaymentDate(nextPaymentDate);
                            } else {
                                // Fallback: calculate from startDate
                                subscription.setNextPaymentDate(calculateNextPaymentDate(startDate, frequency));
                            }
                            subscription.recordPayment(lastPaymentDate);
                        } else {
                            // No last payment date - calculate from startDate
                            subscription.setNextPaymentDate(calculateNextPaymentDate(startDate, frequency));
                        }
                        
                        // Preserve original category information
                        String originalCategoryPrimary = firstTransaction.getCategoryPrimary();
                        String originalCategoryDetailed = firstTransaction.getCategoryDetailed();
                        subscription.setOriginalCategoryPrimary(originalCategoryPrimary);
                        subscription.setOriginalCategoryDetailed(originalCategoryDetailed);
                        
                        // Set category - use detailed if it's "subscriptions", otherwise use "subscriptions" as default
                        // CRITICAL FIX: Even if categorized as "education" (like Barrons), still mark as subscription
                        // The category field is for transaction categorization, but subscription detection is independent
                        if (originalCategoryDetailed != null && "subscriptions".equalsIgnoreCase(originalCategoryDetailed)) {
                            subscription.setCategory(originalCategoryDetailed);
                        } else if (originalCategoryPrimary != null && "subscriptions".equalsIgnoreCase(originalCategoryPrimary)) {
                            subscription.setCategory(originalCategoryPrimary);
                        } else {
                            subscription.setCategory("subscriptions");
                        }
                        
                        // Infer subscription type from categoryDetailed and merchant
                        String subscriptionType = inferSubscriptionType(firstTransaction);
                        subscription.setSubscriptionType(subscriptionType);
                        
                        // CRITICAL FIX: Determine subscriptionCategory: "subscription" vs "recurring"
                        // "subscription" = merchant-based recurring payments (Netflix, Spotify, gym memberships)
                        // "recurring" = bills, loans, mortgage, utilities (contract-based or necessary expenses)
                        String subscriptionCategory = determineSubscriptionCategory(firstTransaction, actualMerchantName, subscriptionType);
                        subscription.setSubscriptionCategory(subscriptionCategory);
                        
                        subscription.setActive(true);
                        subscription.setPlaidTransactionId(firstTransaction.getPlaidTransactionId());
                        
                        detectedSubscriptions.add(subscription);
                        logger.info("Detected subscription: {} - {} - {} ({}) - Type: {} - NextPayment: {}", 
                                merchant, amount, frequency, startDate, subscriptionType, subscription.getNextPaymentDate());
                    }
                }
            }
        }
        
        logger.info("Detected {} subscriptions for user: {}", detectedSubscriptions.size(), userId);
        return detectedSubscriptions;
    }
    
    /**
     * Groups transactions by merchant using fuzzy matching and merchant database
     * CRITICAL FIX: Never mix merchantName and description - use merchantName exclusively
     * Only use description as fallback if merchantName is truly null/empty
     */
    private Map<String, List<TransactionTable>> groupTransactionsByMerchant(final List<TransactionTable> transactions) {
        Map<String, List<TransactionTable>> grouped = new HashMap<>();
        
        for (TransactionTable tx : transactions) {
            String merchantName = tx.getMerchantName();
            String description = tx.getDescription();
            
            // CRITICAL FIX: Always use merchantName as primary identifier
            // Only fall back to description if merchantName is completely missing
            String groupKey;
            
            if (merchantName != null && !merchantName.trim().isEmpty()) {
                // Use merchantName exclusively - normalize it
                String normalizedMerchant = StringUtils.normalizeMerchantName(merchantName);
                
                // Try to find canonical merchant name in database
                TransactionTypeCategoryService.CategoryResult categoryResult = merchantService.detectCategory(
                    merchantName, description, null);
                
                // Use normalized merchant name as group key (consistent grouping)
                groupKey = normalizedMerchant;
                
                // CRITICAL: Log if description differs significantly from merchantName
                // This helps debug merchant/description mixing issues
                if (description != null && !description.trim().isEmpty() && 
                    !normalizedMerchant.toLowerCase().contains(StringUtils.normalizeMerchantName(description).toLowerCase()) &&
                    !StringUtils.normalizeMerchantName(description).toLowerCase().contains(normalizedMerchant.toLowerCase())) {
                    logger.debug("Subscription detection: Merchant '{}' has different description '{}' - using merchant only",
                        merchantName, description);
                }
            } else {
                // Only use description if merchantName is truly missing
                // CRITICAL: Never mix description from one transaction with merchantName from another
                if (description != null && !description.trim().isEmpty()) {
                    groupKey = StringUtils.normalizeMerchantName(description);
                    logger.debug("Subscription detection: Using description '{}' as merchant (merchantName missing)", description);
                } else {
                    groupKey = "unknown";
                    logger.debug("Subscription detection: Both merchantName and description missing for transaction");
                }
            }
            
            // Group by this key - each transaction gets its own merchant-based group
            grouped.computeIfAbsent(groupKey, k -> new ArrayList<>()).add(tx);
        }
        
        // Apply fuzzy matching to merge similar merchant groups
        // CRITICAL FIX: Be more strict with fuzzy matching to avoid false merges
        Map<String, List<TransactionTable>> merged = new HashMap<>();
        Set<String> processed = new HashSet<>();
        
        for (Map.Entry<String, List<TransactionTable>> entry : grouped.entrySet()) {
            String key = entry.getKey();
            if (processed.contains(key)) {
                continue;
            }
            
            List<TransactionTable> group = new ArrayList<>(entry.getValue());
            processed.add(key);
            
            // Find similar groups using fuzzy matching - but be stricter
            for (Map.Entry<String, List<TransactionTable>> otherEntry : grouped.entrySet()) {
                String otherKey = otherEntry.getKey();
                if (processed.contains(otherKey) || key.equals(otherKey)) {
                    continue;
                }
                
                // CRITICAL FIX: Only merge if merchants are truly similar
                // Check that both groups are from merchantName (not mixed with description-based groups)
                boolean bothFromMerchant = entry.getValue().stream().allMatch(tx -> 
                    tx.getMerchantName() != null && !tx.getMerchantName().trim().isEmpty()) &&
                    otherEntry.getValue().stream().allMatch(tx -> 
                        tx.getMerchantName() != null && !tx.getMerchantName().trim().isEmpty());
                
                // Only merge if both are merchantName-based OR fuzzy match is very high
                if (bothFromMerchant && areMerchantsSimilar(key, otherKey)) {
                    group.addAll(otherEntry.getValue());
                    processed.add(otherKey);
                    logger.debug("Subscription detection: Merged merchant groups '{}' and '{}' (fuzzy match)", key, otherKey);
                }
            }
            
            merged.put(key, group);
        }
        
        return merged;
    }
    
    /**
     * Checks if two merchant names are similar using fuzzy matching
     * CRITICAL FIX: Improved matching for merchant name variations (e.g., "DJ*Barrons" vs "D J*BARRONS")
     */
    private boolean areMerchantsSimilar(final String merchant1, final String merchant2) {
        if (merchant1 == null || merchant2 == null) {
            return false;
        }
        
        // Normalize both merchant names for comparison
        String normalized1 = StringUtils.normalizeMerchantName(merchant1);
        String normalized2 = StringUtils.normalizeMerchantName(merchant2);
        
        // Exact match after normalization
        if (normalized1.equals(normalized2)) {
            return true;
        }
        
        // Check if one contains the other (for cases like "DJ BARRONS" vs "D J BARRONS")
        String normalized1Lower = normalized1.toLowerCase();
        String normalized2Lower = normalized2.toLowerCase();
        if (normalized1Lower.contains(normalized2Lower) || normalized2Lower.contains(normalized1Lower)) {
            // Extract key words to avoid false positives
            String[] words1 = normalized1Lower.split("\\s+");
            String[] words2 = normalized2Lower.split("\\s+");
            // If at least 2 significant words match, consider similar
            int commonWords = 0;
            for (String w1 : words1) {
                if (w1.length() > 2) { // Significant words only
                    for (String w2 : words2) {
                        if (w2.length() > 2 && w1.equals(w2)) {
                            commonWords++;
                            break;
                        }
                    }
                }
            }
            if (commonWords >= 2) {
                return true;
            }
        }
        
        // Use fuzzy matching service (category-based, uses Map interface)
        // Create a temporary map for fuzzy matching
        Map<String, InMemoryMerchantService.Merchant> tempMap = new HashMap<>();
        tempMap.put(normalized1, null);
        
        FuzzyMatchingService.FuzzyMatch match = fuzzyMatchingService.findBestMatch(normalized2, tempMap);
        if (match != null && match.getSimilarity() >= 0.95) { // Lowered threshold from 0.85 to 0.80 for better matching
            return true;
        }
        
        // Also check reverse
        tempMap.clear();
        tempMap.put(normalized2, null);
        match = fuzzyMatchingService.findBestMatch(normalized1, tempMap);
        return match != null && match.getSimilarity() >= 0.95; // Lowered threshold from 0.85 to 0.80
    }

    /**
     * Saves or updates subscriptions for a user
     */
    public void saveSubscriptions(final String userId, final List<Subscription> subscriptions) {
        for (Subscription subscription : subscriptions) {
            SubscriptionTable table = toSubscriptionTable(subscription);
            subscriptionRepository.save(table);
        }
        logger.info("Saved {} subscriptions for user: {}", subscriptions.size(), userId);
    }

    /**
     * Gets all subscriptions for a user
     * CRITICAL FIX: Filters out null subscriptions that may result from invalid data conversion
     */
    public List<Subscription> getSubscriptions(final String userId) {
        List<SubscriptionTable> tables = subscriptionRepository.findByUserId(userId);
        return tables.stream()
                .map(this::toSubscription)
                .filter(subscription -> subscription != null) // Filter out null subscriptions
                .filter(subscription -> subscription.getSubscriptionId() != null) // Ensure ID is not null
                .filter(subscription -> subscription.getStartDate() != null) // Ensure startDate is not null (required)
                .filter(subscription -> subscription.getNextPaymentDate() != null) // Ensure nextPaymentDate is not null (required)
                .filter(subscription -> subscription.getFrequency() != null) // Ensure frequency is not null (required)
                .collect(Collectors.toList());
    }

    /**
     * Gets active subscriptions for a user
     * CRITICAL FIX: Filters out null subscriptions and checks if subscription is actually active
     * A subscription is active if:
     * - active flag is true AND
     * - nextPaymentDate is in the future (or within 30 days grace period for overdue payments)
     */
    public List<Subscription> getActiveSubscriptions(final String userId) {
        List<SubscriptionTable> tables = subscriptionRepository.findActiveByUserId(userId);
        LocalDate now = LocalDate.now();
        
        return tables.stream()
                .map(this::toSubscription)
                .filter(subscription -> subscription != null) // Filter out null subscriptions
                .filter(subscription -> subscription.getSubscriptionId() != null) // Ensure ID is not null
                .filter(subscription -> subscription.getStartDate() != null) // Ensure startDate is not null (required)
                .filter(subscription -> subscription.getNextPaymentDate() != null) // Ensure nextPaymentDate is not null (required)
                .filter(subscription -> subscription.getFrequency() != null) // Ensure frequency is not null (required)
                .filter(subscription -> {
                    // CRITICAL FIX: Check if subscription is actually active based on nextPaymentDate
                    // Allow grace period for subscriptions that are slightly overdue (up to 30 days)
                    LocalDate nextPayment = subscription.getNextPaymentDate();
                    // Subscription is active if:
                    // 1. nextPaymentDate is in the future, OR
                    // 2. nextPaymentDate is in the past but within 30 days (grace period for overdue payments)
                    if (nextPayment.isAfter(now)) {
                        return true; // Future payment - definitely active
                    }
                    // Check if within grace period (not more than 30 days overdue)
                    LocalDate gracePeriodStart = now.minusDays(30);
                    return nextPayment.isAfter(gracePeriodStart) || nextPayment.isEqual(gracePeriodStart);
                })
                .collect(Collectors.toList());
    }

    /**
     * Deletes a subscription
     */
    public void deleteSubscription(final String subscriptionId) {
        subscriptionRepository.delete(subscriptionId);
        logger.info("Deleted subscription: {}", subscriptionId);
    }

    /**
     * @deprecated Replaced by groupTransactionsByMerchant which uses existing fuzzy matching service
     * ML-based pattern detection for recurring transactions
     */
    @Deprecated
    private Map<String, List<TransactionTable>> detectRecurringPatterns(
            final List<TransactionTable> allTransactions,
            final Set<String> alreadyMatchedMerchants) {
        Map<String, List<TransactionTable>> patterns = new HashMap<>();
        
        // Filter to expenses only
        List<TransactionTable> expenses = allTransactions.stream()
                .filter(tx -> tx.getAmount() != null && tx.getAmount().compareTo(BigDecimal.ZERO) < 0)
                .collect(Collectors.toList());
        
        // Group by similar amounts (within 5% tolerance) and similar descriptions
        Map<BigDecimal, List<TransactionTable>> byAmount = groupByAmount(expenses);
        
        for (Map.Entry<BigDecimal, List<TransactionTable>> amountEntry : byAmount.entrySet()) {
            List<TransactionTable> sameAmountTxs = amountEntry.getValue();
            
            if (sameAmountTxs.size() < 2) {
                continue; // Need at least 2 transactions
            }
            
            // Group by fuzzy merchant/description similarity
            Map<String, List<TransactionTable>> bySimilarMerchant = new HashMap<>();
            
            for (TransactionTable tx : sameAmountTxs) {
                String merchant = tx.getMerchantName() != null ? tx.getMerchantName() : "";
                String description = tx.getDescription() != null ? tx.getDescription() : "";
                String combined = (merchant + " " + description).toLowerCase().trim();
                
                // Find similar existing group (fuzzy match)
                String matchedKey = null;
                for (String existingKey : bySimilarMerchant.keySet()) {
                    if (isSimilarMerchant(combined, existingKey)) {
                        matchedKey = existingKey;
                        break;
                    }
                }
                
                if (matchedKey != null) {
                    bySimilarMerchant.get(matchedKey).add(tx);
                } else {
                    // Create new group - but skip if already matched by merchant name
                    String normalizedKey = StringUtils.normalizeMerchantName(merchant);
                    if (normalizedKey.isEmpty()) {
                        normalizedKey = description.length() > 30 ? description.substring(0, 30) : description;
                    }
                    
                    // Skip if this merchant was already matched in first pass
                    boolean alreadyMatched = false;
                    for (String matchedMerchant : alreadyMatchedMerchants) {
                        if (isSimilarMerchant(normalizedKey, matchedMerchant)) {
                            alreadyMatched = true;
                            break;
                        }
                    }
                    
                    if (!alreadyMatched) {
                        bySimilarMerchant.put(normalizedKey, new ArrayList<>(List.of(tx)));
                    }
                }
            }
            
            // Check each group for recurring pattern
            for (Map.Entry<String, List<TransactionTable>> merchantEntry : bySimilarMerchant.entrySet()) {
                List<TransactionTable> groupTxs = merchantEntry.getValue();
                
                if (groupTxs.size() < 2) {
                    continue;
                }
                
                // Sort by date
                groupTxs.sort((a, b) -> {
                    LocalDate dateA = parseDate(a.getTransactionDate());
                    LocalDate dateB = parseDate(b.getTransactionDate());
                    if (dateA == null || dateB == null) {
                        return 0;
                    }
                    return dateA.compareTo(dateB);
                });
                
                // Check if dates show recurring pattern
                Subscription.SubscriptionFrequency frequency = detectFrequency(groupTxs);
                
                if (frequency != null) {
                    // This is a recurring pattern - add to patterns map
                    String patternKey = merchantEntry.getKey() + "_" + amountEntry.getKey();
                    patterns.put(patternKey, groupTxs);
                }
            }
        }
        
        return patterns;
    }
    
    /**
     * Checks if two merchant/description strings are similar (fuzzy match)
     * Uses simple similarity check - can be enhanced with Levenshtein distance
     */
    private boolean isSimilarMerchant(final String merchant1, final String merchant2) {
        if (merchant1 == null || merchant2 == null) {
            return false;
        }
        
        String m1 = merchant1.toLowerCase().trim();
        String m2 = merchant2.toLowerCase().trim();
        
        // Exact match
        if (m1.equals(m2)) {
            return true;
        }
        
        // Check if one contains the other (for cases like "OPENAI *CHATGPT" vs "OPENAI CHATGPT")
        if (m1.length() > 10 && m2.length() > 10) {
            // Extract key words (remove special chars, numbers)
            String m1Words = m1.replaceAll("[^a-z\\s]", " ").trim();
            String m2Words = m2.replaceAll("[^a-z\\s]", " ").trim();
            
            // Check word overlap (at least 2 common significant words)
            String[] words1 = m1Words.split("\\s+");
            String[] words2 = m2Words.split("\\s+");
            
            int commonWords = 0;
            for (String w1 : words1) {
                if (w1.length() > 3) { // Only significant words
                    for (String w2 : words2) {
                        if (w2.length() > 3 && w1.equals(w2)) {
                            commonWords++;
                            break;
                        }
                    }
                }
            }
            
            // If at least 2 significant words match, consider similar
            if (commonWords >= 2) {
                return true;
            }
        }
        
        // Check substring match (one contains significant portion of the other)
        if (m1.length() > 5 && m2.length() > 5) {
            int minLen = Math.min(m1.length(), m2.length());
            int matchLen = minLen * 2 / 3; // At least 2/3 of shorter string should match
            
            if (m1.length() >= matchLen && m2.length() >= matchLen) {
                // Check if a significant substring matches
                for (int i = 0; i <= m1.length() - matchLen; i++) {
                    String substring = m1.substring(i, i + matchLen);
                    if (m2.contains(substring)) {
                        return true;
                    }
                }
            }
        }
        
        return false;
    }

    /**
     * Detects frequency from transaction dates
     */
    private Subscription.SubscriptionFrequency detectFrequency(final List<TransactionTable> transactions) {
        if (transactions.size() < 2) {
            return null;
        }
        
        List<LocalDate> dates = transactions.stream()
                .map(tx -> parseDate(tx.getTransactionDate()))
                .filter(date -> date != null)
                .sorted()
                .collect(Collectors.toList());
        
        if (dates.size() < 2) {
            return null;
        }
        
        // Calculate average days between transactions
        long totalDays = 0;
        int intervals = 0;
        for (int i = 1; i < dates.size(); i++) {
            long days = java.time.temporal.ChronoUnit.DAYS.between(dates.get(i - 1), dates.get(i));
            totalDays += days;
            intervals++;
        }
        
        if (intervals == 0) {
            return null;
        }
        
        double averageDays = (double) totalDays / intervals;
        
        // ENHANCED: Determine frequency based on average days with expanded patterns
        // Daily subscriptions (1-2 days)
        if (averageDays >= 0.5 && averageDays <= 2.5) {
            return Subscription.SubscriptionFrequency.DAILY;
        }
        // Weekly subscriptions (6-8 days)
        else if (averageDays >= 6 && averageDays <= 8) {
            return Subscription.SubscriptionFrequency.WEEKLY;
        }
        // Bi-weekly subscriptions (13-15 days)
        else if (averageDays >= 13 && averageDays <= 15) {
            return Subscription.SubscriptionFrequency.BI_WEEKLY;
        }
        // Monthly subscriptions (25-35 days)
        else if (averageDays >= 25 && averageDays <= 35) {
            return Subscription.SubscriptionFrequency.MONTHLY;
        }
        // Quarterly subscriptions (85-95 days)
        else if (averageDays >= 85 && averageDays <= 95) {
            return Subscription.SubscriptionFrequency.QUARTERLY;
        }
        // Semi-annual subscriptions (175-185 days)
        else if (averageDays >= 175 && averageDays <= 185) {
            return Subscription.SubscriptionFrequency.SEMI_ANNUAL;
        }
        // Annual subscriptions (360-370 days)
        else if (averageDays >= 360 && averageDays <= 370) {
            return Subscription.SubscriptionFrequency.ANNUAL;
        }
        
        // ENHANCED: Check for day-of-month patterns (1st, 15th, last day of month)
        // This handles cases where transactions occur on specific days each month
        Subscription.SubscriptionFrequency dayOfMonthFrequency = detectDayOfMonthPattern(dates);
        if (dayOfMonthFrequency != null) {
            return dayOfMonthFrequency;
        }
        
        return null;
    }

    /**
     * Detects day-of-month patterns (1st, 15th, last day of month)
     * This handles subscriptions that occur on specific days each month
     */
    private Subscription.SubscriptionFrequency detectDayOfMonthPattern(final List<LocalDate> dates) {
        if (dates.size() < 3) {
            return null; // Need at least 3 transactions to detect pattern
        }
        
        // Check if transactions occur on same day of month (within 2 days tolerance)
        Map<Integer, Integer> dayOfMonthCounts = new HashMap<>();
        for (LocalDate date : dates) {
            int dayOfMonth = date.getDayOfMonth();
            // Group days: 1-3 (1st), 14-16 (15th), last 3 days of month
            int dayGroup;
            if (dayOfMonth <= 3) {
                dayGroup = 1; // 1st of month
            } else if (dayOfMonth >= 14 && dayOfMonth <= 16) {
                dayGroup = 15; // 15th of month
            } else if (dayOfMonth >= date.lengthOfMonth() - 2) {
                dayGroup = -1; // Last day of month
            } else {
                continue; // Not a common subscription day
            }
            
            dayOfMonthCounts.put(dayGroup, dayOfMonthCounts.getOrDefault(dayGroup, 0) + 1);
        }
        
        // If at least 70% of transactions occur on same day group, it's a monthly subscription
        int totalTransactions = dates.size();
        for (Map.Entry<Integer, Integer> entry : dayOfMonthCounts.entrySet()) {
            if (entry.getValue() >= (totalTransactions * 0.7)) {
                return Subscription.SubscriptionFrequency.MONTHLY;
            }
        }
        
        return null;
    }

    /**
     * Groups transactions by amount (within 5% tolerance)
     */
    private Map<BigDecimal, List<TransactionTable>> groupByAmount(final List<TransactionTable> transactions) {
        Map<BigDecimal, List<TransactionTable>> grouped = new HashMap<>();
        
        for (TransactionTable tx : transactions) {
            BigDecimal amount = tx.getAmount() != null ? tx.getAmount() : BigDecimal.ZERO;
            
            // Find existing group within 5% tolerance
            BigDecimal matchingAmount = null;
            for (BigDecimal existingAmount : grouped.keySet()) {
                BigDecimal difference = amount.subtract(existingAmount);
                if (difference.compareTo(BigDecimal.ZERO) < 0) {
                    difference = difference.negate();
                }
                // Use absolute value of existingAmount for tolerance calculation to handle negative amounts correctly
                BigDecimal absExistingAmount = existingAmount.compareTo(BigDecimal.ZERO) < 0 ? existingAmount.negate() : existingAmount;
                BigDecimal tolerance = absExistingAmount.multiply(new BigDecimal("0.05"));
                if (difference.compareTo(tolerance) <= 0) {
                    matchingAmount = existingAmount;
                    break;
                }
            }
            
            if (matchingAmount != null) {
                grouped.get(matchingAmount).add(tx);
            } else {
                grouped.put(amount, new ArrayList<>(List.of(tx)));
            }
        }
        
        return grouped;
    }

    /**
     * Normalizes merchant name for grouping
     */

    /**
     * Enhanced subscription transaction detection using merchant database
     * Uses category detection from merchant database and category analysis
     */
    private boolean isSubscriptionTransaction(final TransactionTable tx) {
        String categoryPrimary = tx.getCategoryPrimary();
        String categoryDetailed = tx.getCategoryDetailed();
        String description = tx.getDescription();
        String merchantName = tx.getMerchantName();
        
        // 1. Direct subscription category (highest confidence)
        if ((categoryPrimary != null && "subscriptions".equalsIgnoreCase(categoryPrimary)) ||
            (categoryDetailed != null && "subscriptions".equalsIgnoreCase(categoryDetailed))) {
            return true;
        }
        
        // 2. Check categoryDetailed for subscription indicators
        if (categoryDetailed != null) {
            String detailedLower = categoryDetailed.toLowerCase();
            if (detailedLower.contains("subscription") ||
                detailedLower.contains("membership") ||
                detailedLower.contains("recurring")) {
                return true;
            }
        }
        
        // 3. Use merchant database to check if merchant is subscription-related
        if (merchantName != null && !merchantName.trim().isEmpty()) {
            TransactionTypeCategoryService.CategoryResult categoryResult = merchantService.detectCategory(
                merchantName, description, null);
            
            if (categoryResult != null) {
                String detectedCategory = categoryResult.getCategoryPrimary();
                // Check if detected category is subscription-related
                if (detectedCategory != null && SUBSCRIPTION_CATEGORIES.contains(detectedCategory.toLowerCase())) {
                    return true;
                }
            }
        }
        
        // 4. Check transaction categories for subscription-related categories
        if (categoryPrimary != null && SUBSCRIPTION_CATEGORIES.contains(categoryPrimary.toLowerCase())) {
            return true;
        }
        
        // 5. Insurance is typically recurring
        if ("insurance".equalsIgnoreCase(categoryPrimary) ||
            (categoryDetailed != null && categoryDetailed.toLowerCase().contains("insurance"))) {
            return true;
        }
        
        // 6. Check for subscription keywords in description
        if (description != null) {
            String descLower = description.toLowerCase();
            if (descLower.contains("subscription") || descLower.contains("membership") ||
                descLower.contains("recurring") || descLower.contains("premium")) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * @deprecated Use merchant database instead via isSubscriptionTransaction
     * Checks if merchant is a known subscription service using merchant database
     */
    @Deprecated
    private boolean isKnownSubscriptionMerchant(final String merchantName, final String description) {
        if (merchantName == null && description == null) {
            return false;
        }
        
        String combined = ((merchantName != null ? merchantName : "") + " " + 
                          (description != null ? description : "")).toLowerCase();
        
        // Known subscription services - Comprehensive real-world list
        String[] subscriptionServices = {
            // Streaming
            "netflix", "hulu", "huluplus", "hulu plus", "disney", "disney+", "disney plus",
            "hbo", "hbo max", "hbomax", "paramount", "paramount+", "paramount plus",
            "peacock", "spotify", "apple music", "applemusic",
            "youtube premium", "youtubepremium", "youtube tv", "youtubetv", "youtube music", "youtubemusic",
            "amazon prime", "amazonprime", "prime video", "primevideo",
            "showtime", "starz", "crunchyroll", "funimation",
            // Software & AI
            "adobe", "microsoft 365", "office 365", "dropbox", "icloud",
            "google drive", "google one", "github", "canva", "grammarly",
            "openai", "chatgpt", "openai chatgpt",
            "cursor", "cursor ai", "cursorapp",
            "anthropic", "claude", "anthropic claude",
            "meta", "meta ai", "facebook premium",
            // VPN
            "nordvpn", "expressvpn", "surfshark",
            // Productivity
            "zoom", "slack", "notion", "evernote",
            // Cloud Storage
            "onedrive", "box", "pcloud",
            // Newspapers & Magazines
            "barrons", "dj*barrons", "dj barrons", "dow jones barrons",
            "wsj", "wall street journal", "wsj.com",
            "moneycontrol", "money control",
            "marketwatch", "market watch",
            "ny times", "new york times", "nytimes", "nytimes.com",
            "financial times", "ft.com", "ft ",
            "health magazine", "health.com",
            "consumer reports", "consumerreports",
            "the economist", "economist.com",
            "forbes", "forbes.com",
            "time magazine", "time.com",
            "the atlantic", "atlantic.com",
            // Retail Memberships
            "costco", "costco wholesale", "costco.com",
            "sam's club", "sams club", "samsclub",
            "bjs", "bj's", "bjs wholesale", "bjs.com",
            "amazon prime", "amazon prime membership",
            "wmt plus", "walmart plus", "walmart+", "walmart plus membership",
            "target circle", "target circle membership", "target.com",
            "best buy", "bestbuy", "best buy totaltech", "best buy membership",
            // Ride-sharing Subscriptions
            "uber one", "uberone", "uber one membership",
            "lyft pink", "lyftpink", "lyft pink membership",
            // Insurance (recurring payments)
            "insurance", "premium", "policy", "coverage",
            // Health & Fitness
            "gym", "fitness", "health club", "sports club", "athletic club",
            "planet fitness", "24 hour fitness", "equinox", "lifetime fitness",
            "peloton", "classpass", "orange theory", "crossfit",
            "yoga", "pilates", "barre",
            // Parking (recurring)
            "parking", "parking pass", "parking permit", "parking subscription",
            "spothero", "parkmobile", "parkwhiz"
        };
        
        for (String service : subscriptionServices) {
            if (combined.contains(service)) {
                return true;
            }
        }
        
        // CRITICAL: Check for ride-sharing subscriptions (Lyft Pink, Uber One)
        // But NOT regular rides
        if (combined.contains("lyft")) {
            // Only if it's Lyft Pink subscription, not a ride
            if (combined.contains("lyft pink") || combined.contains("lyftpink") ||
                combined.contains("pink subscription") || combined.contains("pink membership")) {
                return true;
            }
            // Regular Lyft ride - NOT a subscription
            return false;
        }
        
        if (combined.contains("uber")) {
            // Only if it's Uber One subscription, not a ride
            if (combined.contains("uber one") || combined.contains("uberone") ||
                combined.contains("uber one subscription") || combined.contains("uberone subscription") ||
                combined.contains("uber one membership") || combined.contains("uberone membership")) {
                return true;
            }
            // Regular Uber ride or Uber Eats - NOT a subscription
            return false;
        }
        
        return false;
    }
    
    /**
     * Checks if description/merchant contains subscription keywords
     * Enhanced to avoid false positives
     */
    private boolean isSubscriptionKeyword(final String description, final String merchantName) {
        if (description == null && merchantName == null) {
            return false;
        }
        
        String combined = ((merchantName != null ? merchantName : "") + " " + 
                          (description != null ? description : "")).toLowerCase();
        
        // Check for explicit subscription keywords (including abbreviations)
        if (combined.contains("subscription") || 
            combined.contains("subscr") || // Common abbreviation (SUBSCR)
            combined.contains("monthly") ||
            combined.contains("annual") || 
            combined.contains("recurring") ||
            combined.contains("membership") ||
            combined.contains("premium")) {
            // CRITICAL: Exclude false positives
            // Don't match if it's a ride-sharing service without subscription keywords
            if (combined.contains("lyft") && 
                !combined.contains("pink") && 
                !combined.contains("subscription") &&
                !combined.contains("membership")) {
                return false; // Regular Lyft ride, not subscription
            }
            if (combined.contains("uber") && 
                !combined.contains("one") && 
                !combined.contains("subscription") &&
                !combined.contains("membership")) {
                return false; // Regular Uber ride, not subscription
            }
            return true;
        }
        
        // Fallback to known subscription services
        return isKnownSubscriptionMerchant(merchantName, description);
    }
    
    /**
     * Infers subscription type from transaction category and merchant
     */
    private String inferSubscriptionType(final TransactionTable tx) {
        String categoryPrimary = tx.getCategoryPrimary();
        String categoryDetailed = tx.getCategoryDetailed();
        String merchant = tx.getMerchantName();
        String description = tx.getDescription();
        
        String combined = ((merchant != null ? merchant : "") + " " + 
                          (description != null ? description : "")).toLowerCase();
        
        // Streaming services
        if ("entertainment".equalsIgnoreCase(categoryPrimary) ||
            (categoryDetailed != null && categoryDetailed.toLowerCase().contains("streaming"))) {
            if (combined.contains("netflix") || combined.contains("hulu") || 
                combined.contains("disney") || combined.contains("hbo") ||
                combined.contains("paramount") || combined.contains("peacock") ||
                combined.contains("spotify") || combined.contains("apple music") ||
                combined.contains("youtube premium") || combined.contains("youtube tv") ||
                combined.contains("youtube music") || combined.contains("youtubemusic") ||
                combined.contains("amazon prime") || combined.contains("prime video") ||
                combined.contains("showtime") || combined.contains("starz") ||
                combined.contains("crunchyroll") || combined.contains("funimation")) {
                return "streaming";
            }
        }
        
        // Also check for YouTube Music even if category doesn't match
        if (combined.contains("youtube music") || combined.contains("youtubemusic")) {
            return "streaming";
        }
        
        // Software subscriptions
        if ("tech".equalsIgnoreCase(categoryPrimary) ||
            (categoryDetailed != null && 
             (categoryDetailed.toLowerCase().contains("software") ||
              categoryDetailed.toLowerCase().contains("saas")))) {
            if (combined.contains("adobe") || combined.contains("microsoft 365") ||
                combined.contains("office 365") || combined.contains("github") ||
                combined.contains("canva") || combined.contains("grammarly") ||
                combined.contains("notion") || combined.contains("evernote") ||
                combined.contains("openai") || combined.contains("chatgpt") ||
                combined.contains("cursor") || combined.contains("anthropic") ||
                combined.contains("meta ai") || combined.contains("claude")) {
                return "software";
            }
        }
        
        // Also check for AI services even if categoryDetailed doesn't contain "software" or "saas"
        if (combined.contains("openai") || combined.contains("chatgpt") ||
            combined.contains("cursor") || combined.contains("anthropic") ||
            combined.contains("meta ai") || combined.contains("claude")) {
            return "software";
        }
        
        // Newspaper/Magazine subscriptions
        if (combined.contains("barrons") || combined.contains("wsj") || 
            combined.contains("wall street journal") || combined.contains("ny times") ||
            combined.contains("new york times") || combined.contains("nytimes") ||
            combined.contains("financial times") || combined.contains("ft.com") ||
            combined.contains("moneycontrol") || combined.contains("marketwatch") ||
            combined.contains("consumer reports") || combined.contains("health magazine") ||
            combined.contains("the economist") || combined.contains("forbes") ||
            combined.contains("time magazine") || combined.contains("the atlantic")) {
            return "membership"; // Newspapers/magazines are memberships
        }
        
        // Retail memberships
        if (combined.contains("costco") || combined.contains("sam's club") ||
            combined.contains("sams club") || combined.contains("bjs") ||
            combined.contains("bj's") || combined.contains("walmart plus") ||
            combined.contains("wmt plus") || combined.contains("target circle") ||
            combined.contains("best buy totaltech") || combined.contains("best buy membership")) {
            return "membership";
        }
        
        // Insurance
        if (combined.contains("insurance") || combined.contains("premium") ||
            (categoryPrimary != null && categoryPrimary.toLowerCase().contains("insurance"))) {
            return "other"; // Insurance is a separate category
        }
        
        // Parking
        if (combined.contains("parking") || combined.contains("spothero") ||
            combined.contains("parkmobile") || combined.contains("parkwhiz")) {
            return "membership";
        }
        
        // Health & Fitness
        if (combined.contains("gym") || combined.contains("fitness") ||
            combined.contains("health club") || combined.contains("sports club") ||
            combined.contains("planet fitness") || combined.contains("equinox") ||
            combined.contains("lifetime fitness") || combined.contains("peloton") ||
            combined.contains("classpass") || combined.contains("orange theory") ||
            combined.contains("crossfit") || combined.contains("yoga") ||
            combined.contains("pilates") || combined.contains("barre")) {
            return "membership";
        }
        
        // Cloud storage
        if (categoryDetailed != null && categoryDetailed.toLowerCase().contains("cloud")) {
            if (combined.contains("dropbox") || combined.contains("icloud") ||
                combined.contains("google drive") || combined.contains("google one") ||
                combined.contains("onedrive") || combined.contains("box") ||
                combined.contains("pcloud")) {
                return "cloud_storage";
            }
        }
        
        // Health/Fitness memberships
        if ("health".equalsIgnoreCase(categoryPrimary) ||
            (categoryDetailed != null && categoryDetailed.toLowerCase().contains("fitness"))) {
            if (combined.contains("gym") || combined.contains("fitness") ||
                combined.contains("peloton") || combined.contains("classpass")) {
                return "membership";
            }
        }
        
        // VPN services
        if (combined.contains("nordvpn") || combined.contains("expressvpn") ||
            combined.contains("surfshark")) {
            return "software"; // VPN is software
        }
        
        // Ride-sharing subscriptions (Lyft Pink, Uber One)
        if (combined.contains("lyft pink") || combined.contains("uber one")) {
            return "membership";
        }
        
        // Default
        return "other";
    }

    /**
     * Determines subscriptionCategory: "subscription" vs "recurring"
     * "subscription" = merchant-based recurring payments (Netflix, Spotify, gym memberships, software)
     * "recurring" = bills, loans, mortgage, utilities (contract-based or necessary expenses)
     */
    private String determineSubscriptionCategory(final TransactionTable tx, final String merchantName, final String subscriptionType) {
        String categoryPrimary = tx.getCategoryPrimary();
        String categoryDetailed = tx.getCategoryDetailed();
        String description = tx.getDescription();
        BigDecimal amount = tx.getAmount();
        String combined = ((merchantName != null ? merchantName : "") + " " + 
                          (description != null ? description : "")).toLowerCase();
        
        // 1. Known subscription merchants (Netflix, Spotify, etc.) = "subscription"
        if (isKnownSubscriptionMerchant(merchantName, description)) {
            return "subscription";
        }
        
        // 2. Subscription-related categories = "subscription"
        if (categoryDetailed != null && "subscriptions".equalsIgnoreCase(categoryDetailed)) {
            return "subscription";
        }
        if (categoryPrimary != null && SUBSCRIPTION_CATEGORIES.contains(categoryPrimary.toLowerCase())) {
            // But exclude insurance and loans
            if (!"insurance".equalsIgnoreCase(categoryPrimary) && 
                !"loans".equalsIgnoreCase(categoryPrimary) &&
                !"mortgage".equalsIgnoreCase(categoryPrimary)) {
                return "subscription";
            }
        }
        
        // 3. Subscription type indicates subscription (not "other" for unknown)
        if (subscriptionType != null && !"other".equalsIgnoreCase(subscriptionType)) {
            // If we identified it as streaming, software, membership, cloud_storage, it's a subscription
            return "subscription";
        }
        
        // 4. Large recurring payments (likely mortgage, loans) = "recurring"
        if (amount != null && amount.abs().compareTo(new BigDecimal("500")) > 0) {
            // Check if it's from a bank/financial institution (likely loan/mortgage)
            if (combined.contains("mortgage") || combined.contains("loan") ||
                combined.contains("auto finance") || combined.contains("car payment") ||
                combined.contains("student loan") || combined.contains("credit card") ||
                categoryPrimary != null && ("loans".equalsIgnoreCase(categoryPrimary) ||
                                            "mortgage".equalsIgnoreCase(categoryPrimary) ||
                                            "payment".equalsIgnoreCase(categoryPrimary))) {
                return "recurring";
            }
        }
        
        // 5. Utilities and bills = "recurring"
        if (categoryPrimary != null && ("utilities".equalsIgnoreCase(categoryPrimary) ||
                                        "bills".equalsIgnoreCase(categoryPrimary) ||
                                        "insurance".equalsIgnoreCase(categoryPrimary))) {
            return "recurring";
        }
        
        if (combined.contains("electric") || combined.contains("gas") ||
            combined.contains("water") || combined.contains("sewer") ||
            combined.contains("trash") || combined.contains("internet") ||
            combined.contains("phone") || combined.contains("cable") ||
            combined.contains("insurance") || combined.contains("premium")) {
            return "recurring";
        }
        
        // 6. Default: If subscriptionType was identified (not "other"), it's likely a subscription
        // Otherwise, treat as recurring (safer default)
        if (subscriptionType != null && !"other".equalsIgnoreCase(subscriptionType)) {
            return "subscription";
        }
        
        // Default to "recurring" for unknown patterns (safer than assuming subscription)
        return "recurring";
    }

    /**
     * Calculates next payment date based on frequency
     * CRITICAL FIX: Helper method to calculate nextPaymentDate (matches Subscription.calculateNextPaymentDate logic)
     */
    private LocalDate calculateNextPaymentDate(final LocalDate baseDate, final Subscription.SubscriptionFrequency frequency) {
        if (baseDate == null || frequency == null) {
            return null;
        }
        
        return switch (frequency) {
            case DAILY -> baseDate.plusDays(1);
            case WEEKLY -> baseDate.plusWeeks(1);
            case BI_WEEKLY -> baseDate.plusWeeks(2);
            case MONTHLY -> baseDate.plusMonths(1);
            case QUARTERLY -> baseDate.plusMonths(3);
            case SEMI_ANNUAL -> baseDate.plusMonths(6);
            case ANNUAL -> baseDate.plusYears(1);
        };
    }
    
    /**
     * Parses date string to LocalDate
     */
    private LocalDate parseDate(final String dateString) {
        if (dateString == null || dateString.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(dateString, DATE_FORMATTER);
        } catch (Exception e) {
            logger.debug("Failed to parse date: {}", dateString);
            return null;
        }
    }

    /**
     * Converts Subscription to SubscriptionTable
     */
    private SubscriptionTable toSubscriptionTable(final Subscription subscription) {
        SubscriptionTable table = new SubscriptionTable();
        // CRITICAL FIX: Normalize subscription ID to lowercase when saving to match lookup behavior
        String normalizedId = com.budgetbuddy.util.IdGenerator.normalizeUUID(subscription.getSubscriptionId());
        table.setSubscriptionId(normalizedId);
        table.setUserId(subscription.getUserId());
        table.setAccountId(subscription.getAccountId());
        table.setMerchantName(subscription.getMerchantName());
        table.setDescription(subscription.getDescription());
        table.setAmount(subscription.getAmount());
        table.setFrequency(subscription.getFrequency() != null ? subscription.getFrequency().name() : null);
        table.setStartDate(subscription.getStartDate() != null ? subscription.getStartDate().format(DATE_FORMATTER) : null);
        table.setNextPaymentDate(subscription.getNextPaymentDate() != null ? subscription.getNextPaymentDate().format(DATE_FORMATTER) : null);
        table.setLastPaymentDate(subscription.getLastPaymentDate() != null ? subscription.getLastPaymentDate().format(DATE_FORMATTER) : null);
        table.setCategory(subscription.getCategory());
        table.setSubscriptionType(subscription.getSubscriptionType());
        table.setSubscriptionCategory(subscription.getSubscriptionCategory());
        table.setOriginalCategoryPrimary(subscription.getOriginalCategoryPrimary());
        table.setOriginalCategoryDetailed(subscription.getOriginalCategoryDetailed());
        table.setActive(subscription.getActive());
        table.setPlaidTransactionId(subscription.getPlaidTransactionId());
        if (subscription.getCreatedAt() != null) {
            table.setCreatedAt(subscription.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant());
        } else {
            table.setCreatedAt(java.time.Instant.now());
        }
        table.setUpdatedAt(java.time.Instant.now());
        return table;
    }

    /**
     * Converts SubscriptionTable to Subscription
     * CRITICAL FIX: Returns null if required fields are missing or invalid to prevent null subscriptions in API responses
     */
    private Subscription toSubscription(final SubscriptionTable table) {
        if (table == null) {
            logger.warn("Cannot convert null SubscriptionTable to Subscription");
            return null;
        }
        
        // CRITICAL: Validate required fields before creating subscription
        if (table.getSubscriptionId() == null || table.getSubscriptionId().trim().isEmpty()) {
            logger.warn("SubscriptionTable has null or empty subscriptionId, skipping conversion");
            return null;
        }
        
        if (table.getStartDate() == null || table.getStartDate().trim().isEmpty()) {
            logger.warn("SubscriptionTable {} has null or empty startDate, skipping conversion", table.getSubscriptionId());
            return null;
        }
        
        if (table.getNextPaymentDate() == null || table.getNextPaymentDate().trim().isEmpty()) {
            logger.warn("SubscriptionTable {} has null or empty nextPaymentDate, skipping conversion", table.getSubscriptionId());
            return null;
        }
        
        LocalDate startDate = parseDate(table.getStartDate());
        LocalDate nextPaymentDate = parseDate(table.getNextPaymentDate());
        
        if (startDate == null) {
            logger.warn("SubscriptionTable {} has invalid startDate format: {}, skipping conversion", 
                table.getSubscriptionId(), table.getStartDate());
            return null;
        }
        
        if (nextPaymentDate == null) {
            logger.warn("SubscriptionTable {} has invalid nextPaymentDate format: {}, skipping conversion", 
                table.getSubscriptionId(), table.getNextPaymentDate());
            return null;
        }
        
        // Validate frequency
        Subscription.SubscriptionFrequency frequency = null;
        if (table.getFrequency() != null && !table.getFrequency().trim().isEmpty()) {
            try {
                frequency = Subscription.SubscriptionFrequency.valueOf(table.getFrequency());
            } catch (IllegalArgumentException e) {
                logger.warn("SubscriptionTable {} has invalid frequency: {}, skipping conversion", 
                    table.getSubscriptionId(), table.getFrequency());
                return null;
            }
        } else {
            logger.warn("SubscriptionTable {} has null or empty frequency, skipping conversion", table.getSubscriptionId());
            return null;
        }
        
        // All required fields validated, create subscription
        try {
            Subscription subscription = new Subscription();
            subscription.setSubscriptionId(table.getSubscriptionId());
            subscription.setUserId(table.getUserId());
            subscription.setAccountId(table.getAccountId());
            subscription.setMerchantName(table.getMerchantName());
            subscription.setDescription(table.getDescription());
            subscription.setAmount(table.getAmount());
            subscription.setFrequency(frequency);
            subscription.setStartDate(startDate);
            subscription.setNextPaymentDate(nextPaymentDate);
            subscription.setLastPaymentDate(parseDate(table.getLastPaymentDate())); // Optional field
            subscription.setCategory(table.getCategory());
            subscription.setSubscriptionType(table.getSubscriptionType());
            subscription.setSubscriptionCategory(table.getSubscriptionCategory()); // May be null for existing records
            subscription.setOriginalCategoryPrimary(table.getOriginalCategoryPrimary());
            subscription.setOriginalCategoryDetailed(table.getOriginalCategoryDetailed());
            subscription.setActive(table.getActive() != null ? table.getActive() : true); // Default to true if null
            subscription.setPlaidTransactionId(table.getPlaidTransactionId());
            
            if (table.getCreatedAt() != null) {
                subscription.setCreatedAt(java.time.LocalDateTime.ofInstant(table.getCreatedAt(), java.time.ZoneId.systemDefault()));
            }
            if (table.getUpdatedAt() != null) {
                subscription.setUpdatedAt(java.time.LocalDateTime.ofInstant(table.getUpdatedAt(), java.time.ZoneId.systemDefault()));
            }
            
            return subscription;
        } catch (Exception e) {
            logger.error("Error converting SubscriptionTable {} to Subscription: {}", 
                table.getSubscriptionId(), e.getMessage(), e);
            return null;
        }
    }
}

