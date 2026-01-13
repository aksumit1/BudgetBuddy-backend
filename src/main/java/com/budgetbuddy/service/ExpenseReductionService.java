package com.budgetbuddy.service;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.Subscription;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Expense Reduction Recommendations Service
 * 
 * Identifies expenses that can be cut down to improve financial health.
 * Provides actionable recommendations with potential savings.
 */
@Service
public class ExpenseReductionService {

    private static final Logger logger = LoggerFactory.getLogger(ExpenseReductionService.class);
    
    private static final int ANALYSIS_WINDOW_DAYS = 90;
    private static final double DUPLICATE_SERVICE_THRESHOLD = 0.8; // 80% similarity
    private static final BigDecimal MIN_RECOMMENDATION_AMOUNT = BigDecimal.valueOf(10); // Minimum $10/month savings
    
    private final TransactionRepository transactionRepository;
    private final SubscriptionService subscriptionService;

    public ExpenseReductionService(
            final TransactionRepository transactionRepository,
            final SubscriptionService subscriptionService) {
        this.transactionRepository = transactionRepository;
        this.subscriptionService = subscriptionService;
    }

    /**
     * Get expense reduction recommendations for a user
     */
    public List<ExpenseRecommendation> getRecommendations(final String userId) {
        if (userId == null || userId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User ID is required");
        }

        logger.info("Generating expense reduction recommendations for user: {}", userId);

        List<ExpenseRecommendation> recommendations = new ArrayList<>();

        // 1. Unused/Underutilized Subscriptions
        recommendations.addAll(analyzeSubscriptions(userId));

        // 2. Duplicate Services
        recommendations.addAll(analyzeDuplicateServices(userId));

        // 3. Category Overspending
        recommendations.addAll(analyzeCategoryOverspending(userId));

        // 4. Low-Value High-Cost Expenses
        recommendations.addAll(analyzeLowValueHighCost(userId));

        // 5. Lifestyle Inflation
        recommendations.addAll(analyzeLifestyleInflation(userId));

        // Sort by potential savings (descending)
        return recommendations.stream()
                .sorted(Comparator.comparing(ExpenseRecommendation::getMonthlySavings, Comparator.reverseOrder()))
                .collect(Collectors.toList());
    }

    /**
     * Analyze subscriptions for cancellation/downgrade opportunities
     */
    private List<ExpenseRecommendation> analyzeSubscriptions(final String userId) {
        List<ExpenseRecommendation> recommendations = new ArrayList<>();

        try {
            List<Subscription> subscriptions = subscriptionService.getSubscriptions(userId);
            
            for (Subscription sub : subscriptions) {
                if (sub.getActive() == null || !sub.getActive()) {
                    continue;
                }

                BigDecimal monthlyCost = sub.getAmount() != null ? sub.getAmount() : BigDecimal.ZERO;
                if (monthlyCost.compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }

                // Check if subscription is unused (no recent activity)
                boolean isUnused = isSubscriptionUnused(userId, sub);
                
                if (isUnused) {
                    String merchantName = sub.getMerchantName() != null ? sub.getMerchantName() : "Subscription";
                    recommendations.add(new ExpenseRecommendation(
                            RecommendationType.CANCEL,
                            merchantName,
                            monthlyCost,
                            monthlyCost.multiply(BigDecimal.valueOf(12)), // Annual savings
                            String.format("Cancel unused subscription: %s. Save $%.2f/month ($%.2f/year)",
                                    merchantName, monthlyCost.doubleValue(), 
                                    monthlyCost.multiply(BigDecimal.valueOf(12)).doubleValue()),
                            Priority.HIGH,
                            "subscription",
                            sub.getSubscriptionId()
                    ));
                } else {
                    // Check for downgrade opportunities
                    String downgradeSuggestion = suggestDowngrade(sub);
                    if (downgradeSuggestion != null) {
                        BigDecimal potentialSavings = monthlyCost.multiply(BigDecimal.valueOf(0.3)); // Assume 30% savings
                        String merchantName = sub.getMerchantName() != null ? sub.getMerchantName() : "Subscription";
                        recommendations.add(new ExpenseRecommendation(
                                RecommendationType.DOWNGRADE,
                                merchantName,
                                potentialSavings,
                                potentialSavings.multiply(BigDecimal.valueOf(12)),
                                downgradeSuggestion,
                                Priority.MEDIUM,
                                "subscription",
                                sub.getSubscriptionId()
                        ));
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Error analyzing subscriptions: {}", e.getMessage());
        }

        return recommendations;
    }

    /**
     * Check if subscription appears unused based on transaction patterns
     */
    private boolean isSubscriptionUnused(final String userId, final Subscription subscription) {
        // Get recent transactions for this merchant/service
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(60); // Last 60 days

        String startDateStr = startDate.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);
        String endDateStr = endDate.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);
        
        List<TransactionTable> transactions = transactionRepository
                .findByUserIdAndDateRange(userId, startDateStr, endDateStr);

        String merchantName = subscription.getMerchantName() != null ? subscription.getMerchantName().toLowerCase() : "";
        
        long matchingTransactions = transactions.stream()
                .filter(tx -> {
                    String desc = tx.getDescription() != null ? tx.getDescription().toLowerCase() : "";
                    String merchant = tx.getMerchantName() != null ? tx.getMerchantName().toLowerCase() : "";
                    return desc.contains(merchantName) || merchant.contains(merchantName);
                })
                .count();

        // If no transactions in last 60 days, likely unused
        return matchingTransactions == 0;
    }

    /**
     * Suggest downgrade options for subscription
     */
    private String suggestDowngrade(final Subscription subscription) {
        String merchantName = subscription.getMerchantName() != null ? subscription.getMerchantName().toLowerCase() : "";
        String description = subscription.getDescription() != null ? subscription.getDescription().toLowerCase() : "";
        String combined = merchantName + " " + description;
        
        // Common downgrade opportunities
        if (combined.contains("premium") || combined.contains("pro") || combined.contains("plus")) {
            String displayName = subscription.getMerchantName() != null ? subscription.getMerchantName() : "Subscription";
            return String.format("Consider downgrading %s to a basic tier to save money", displayName);
        }
        
        return null;
    }

    /**
     * Analyze duplicate services (e.g., multiple streaming services)
     */
    private List<ExpenseRecommendation> analyzeDuplicateServices(final String userId) {
        List<ExpenseRecommendation> recommendations = new ArrayList<>();

        try {
            List<Subscription> subscriptions = subscriptionService.getSubscriptions(userId);
            
            // Group subscriptions by type
            Map<String, List<Subscription>> byType = subscriptions.stream()
                    .filter(sub -> sub.getActive() != null && sub.getActive())
                    .collect(Collectors.groupingBy(this::categorizeSubscription));

            for (Map.Entry<String, List<Subscription>> entry : byType.entrySet()) {
                List<Subscription> similarSubs = entry.getValue();
                
                if (similarSubs.size() >= 2) {
                    // Multiple similar services - recommend keeping one
                    BigDecimal totalMonthly = similarSubs.stream()
                            .map(sub -> sub.getAmount() != null ? sub.getAmount() : BigDecimal.ZERO)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);

                    // Assume keeping cheapest one
                    BigDecimal cheapest = similarSubs.stream()
                            .map(sub -> sub.getAmount() != null ? sub.getAmount() : BigDecimal.valueOf(Double.MAX_VALUE))
                            .min(BigDecimal::compareTo)
                            .orElse(BigDecimal.ZERO);

                    BigDecimal savings = totalMonthly.subtract(cheapest);
                    
                    if (savings.compareTo(MIN_RECOMMENDATION_AMOUNT) > 0) {
                        recommendations.add(new ExpenseRecommendation(
                                RecommendationType.CANCEL,
                                "Duplicate " + entry.getKey() + " Services",
                                savings,
                                savings.multiply(BigDecimal.valueOf(12)),
                                String.format("You have %d %s subscriptions. Consider keeping one and canceling others to save $%.2f/month",
                                        similarSubs.size(), entry.getKey(), savings.doubleValue()),
                                Priority.HIGH,
                                "duplicate",
                                null
                        ));
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Error analyzing duplicate services: {}", e.getMessage());
        }

        return recommendations;
    }

    /**
     * Categorize subscription by type
     */
    private String categorizeSubscription(final Subscription subscription) {
        String merchantName = subscription.getMerchantName() != null ? subscription.getMerchantName().toLowerCase() : "";
        String category = subscription.getCategory() != null ? subscription.getCategory().toLowerCase() : "";
        String name = merchantName;

        if (name.contains("netflix") || name.contains("hulu") || name.contains("disney") || 
            name.contains("prime video") || name.contains("hbo") || name.contains("streaming")) {
            return "Streaming";
        }
        if (name.contains("gym") || name.contains("fitness") || name.contains("peloton")) {
            return "Fitness";
        }
        if (name.contains("music") || name.contains("spotify") || name.contains("apple music")) {
            return "Music";
        }
        if (name.contains("cloud") || name.contains("storage") || name.contains("dropbox") || name.contains("drive")) {
            return "Cloud Storage";
        }
        if (category.contains("software") || category.contains("saas")) {
            return "Software";
        }

        return "Other";
    }

    /**
     * Analyze category overspending
     */
    private List<ExpenseRecommendation> analyzeCategoryOverspending(final String userId) {
        List<ExpenseRecommendation> recommendations = new ArrayList<>();

        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(ANALYSIS_WINDOW_DAYS);

        String startDateStr = startDate.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);
        String endDateStr = endDate.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);

        List<TransactionTable> transactions = transactionRepository
                .findByUserIdAndDateRange(userId, startDateStr, endDateStr);

        // Filter to expenses
        List<TransactionTable> expenses = transactions.stream()
                .filter(tx -> tx.getAmount() != null && tx.getAmount().compareTo(BigDecimal.ZERO) < 0)
                .filter(tx -> tx.getIsHidden() == null || !tx.getIsHidden())
                .filter(tx -> tx.getCategoryPrimary() != null && !tx.getCategoryPrimary().isEmpty())
                .collect(Collectors.toList());

        // Group by category
        Map<String, List<TransactionTable>> byCategory = expenses.stream()
                .collect(Collectors.groupingBy(TransactionTable::getCategoryPrimary));

        // Calculate spending per category
        Map<String, BigDecimal> categorySpending = byCategory.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> entry.getValue().stream()
                                .map(tx -> tx.getAmount().abs())
                                .reduce(BigDecimal.ZERO, BigDecimal::add)
                ));

        // Identify high-spending discretionary categories
        Set<String> discretionaryCategories = Set.of(
                "Entertainment", "Dining", "Shopping", "Travel", "Hobbies"
        );

        for (Map.Entry<String, BigDecimal> entry : categorySpending.entrySet()) {
            String category = entry.getKey();
            BigDecimal totalSpent = entry.getValue();
            BigDecimal monthlyAverage = totalSpent.divide(BigDecimal.valueOf(3), 2, RoundingMode.HALF_UP); // 3 months

            if (discretionaryCategories.contains(category) && 
                monthlyAverage.compareTo(BigDecimal.valueOf(200)) > 0) {
                
                BigDecimal potentialSavings = monthlyAverage.multiply(BigDecimal.valueOf(0.2)); // 20% reduction
                
                recommendations.add(new ExpenseRecommendation(
                        RecommendationType.REDUCE,
                        category + " Spending",
                        potentialSavings,
                        potentialSavings.multiply(BigDecimal.valueOf(12)),
                        String.format("Consider reducing %s spending by 20%% to save $%.2f/month. Current average: $%.2f/month",
                                category, potentialSavings.doubleValue(), monthlyAverage.doubleValue()),
                        Priority.MEDIUM,
                        "category",
                        category
                ));
            }
        }

        return recommendations;
    }

    /**
     * Analyze low-value high-cost expenses
     */
    private List<ExpenseRecommendation> analyzeLowValueHighCost(final String userId) {
        List<ExpenseRecommendation> recommendations = new ArrayList<>();

        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(ANALYSIS_WINDOW_DAYS);

        String startDateStr = startDate.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);
        String endDateStr = endDate.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);

        List<TransactionTable> transactions = transactionRepository
                .findByUserIdAndDateRange(userId, startDateStr, endDateStr);

        // Group by merchant
        Map<String, List<TransactionTable>> byMerchant = transactions.stream()
                .filter(tx -> tx.getAmount() != null && tx.getAmount().compareTo(BigDecimal.ZERO) < 0)
                .filter(tx -> tx.getIsHidden() == null || !tx.getIsHidden())
                .filter(tx -> tx.getMerchantName() != null && !tx.getMerchantName().isEmpty())
                .collect(Collectors.groupingBy(TransactionTable::getMerchantName));

        // Find merchants with high total but low frequency
        for (Map.Entry<String, List<TransactionTable>> entry : byMerchant.entrySet()) {
            List<TransactionTable> merchantTx = entry.getValue();
            BigDecimal total = merchantTx.stream()
                    .map(tx -> tx.getAmount().abs())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            
            BigDecimal average = total.divide(BigDecimal.valueOf(merchantTx.size()), 2, RoundingMode.HALF_UP);
            
            // High average but low frequency (underutilized)
            if (average.compareTo(BigDecimal.valueOf(50)) > 0 && merchantTx.size() <= 2) {
                BigDecimal monthlyCost = total.divide(BigDecimal.valueOf(3), 2, RoundingMode.HALF_UP);
                
                recommendations.add(new ExpenseRecommendation(
                        RecommendationType.OPTIMIZE,
                        entry.getKey(),
                        monthlyCost.multiply(BigDecimal.valueOf(0.5)), // 50% reduction
                        monthlyCost.multiply(BigDecimal.valueOf(6)), // 6 months savings
                        String.format("Consider reducing usage of %s. High cost ($%.2f avg) but low frequency (%d transactions in 3 months)",
                                entry.getKey(), average.doubleValue(), merchantTx.size()),
                        Priority.LOW,
                        "merchant",
                        entry.getKey()
                ));
            }
        }

        return recommendations;
    }

    /**
     * Analyze lifestyle inflation (gradual spending increases)
     */
    private List<ExpenseRecommendation> analyzeLifestyleInflation(final String userId) {
        List<ExpenseRecommendation> recommendations = new ArrayList<>();

        LocalDate endDate = LocalDate.now();
        LocalDate recentStart = endDate.minusDays(30);
        LocalDate historicalStart = endDate.minusDays(90);

        String recentStartStr = recentStart.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);
        String endDateStr = endDate.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);
        String historicalStartStr = historicalStart.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);
        
        // Compare recent month to previous 2 months
        List<TransactionTable> recent = transactionRepository
                .findByUserIdAndDateRange(userId, recentStartStr, endDateStr);
        
        List<TransactionTable> historical = transactionRepository
                .findByUserIdAndDateRange(userId, historicalStartStr, recentStartStr);

        BigDecimal recentTotal = recent.stream()
                .filter(tx -> tx.getAmount() != null && tx.getAmount().compareTo(BigDecimal.ZERO) < 0)
                .filter(tx -> tx.getIsHidden() == null || !tx.getIsHidden())
                .map(tx -> tx.getAmount().abs())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal historicalTotal = historical.stream()
                .filter(tx -> tx.getAmount() != null && tx.getAmount().compareTo(BigDecimal.ZERO) < 0)
                .filter(tx -> tx.getIsHidden() == null || !tx.getIsHidden())
                .map(tx -> tx.getAmount().abs())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (historicalTotal.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal historicalMonthly = historicalTotal.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
            BigDecimal increase = recentTotal.subtract(historicalMonthly);
            BigDecimal increasePercent = increase.divide(historicalMonthly, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));

            // If spending increased by more than 15%
            if (increasePercent.compareTo(BigDecimal.valueOf(15)) > 0) {
                BigDecimal potentialSavings = increase.multiply(BigDecimal.valueOf(0.5)); // Save 50% of increase
                
                recommendations.add(new ExpenseRecommendation(
                        RecommendationType.REDUCE,
                        "Lifestyle Inflation",
                        potentialSavings,
                        potentialSavings.multiply(BigDecimal.valueOf(12)),
                        String.format("Your spending increased by %.1f%% this month. Consider returning to previous spending levels to save $%.2f/month",
                                increasePercent.doubleValue(), potentialSavings.doubleValue()),
                        Priority.MEDIUM,
                        "lifestyle",
                        null
                ));
            }
        }

        return recommendations;
    }

    // Model classes

    public static class ExpenseRecommendation {
        private final RecommendationType type;
        private final String title;
        private final BigDecimal monthlySavings;
        private final BigDecimal annualSavings;
        private final String description;
        private final Priority priority;
        private final String category;
        private final String entityId; // Subscription ID, category name, etc.

        public ExpenseRecommendation(
                final RecommendationType type,
                final String title,
                final BigDecimal monthlySavings,
                final BigDecimal annualSavings,
                final String description,
                final Priority priority,
                final String category,
                final String entityId) {
            this.type = type;
            this.title = title;
            this.monthlySavings = monthlySavings;
            this.annualSavings = annualSavings;
            this.description = description;
            this.priority = priority;
            this.category = category;
            this.entityId = entityId;
        }

        public RecommendationType getType() { return type; }
        public String getTitle() { return title; }
        public BigDecimal getMonthlySavings() { return monthlySavings; }
        public BigDecimal getAnnualSavings() { return annualSavings; }
        public String getDescription() { return description; }
        public Priority getPriority() { return priority; }
        public String getCategory() { return category; }
        public String getEntityId() { return entityId; }
    }

    public enum RecommendationType {
        CANCEL,
        DOWNGRADE,
        REDUCE,
        SUBSTITUTE,
        OPTIMIZE,
        NEGOTIATE
    }

    public enum Priority {
        LOW,
        MEDIUM,
        HIGH
    }
}
