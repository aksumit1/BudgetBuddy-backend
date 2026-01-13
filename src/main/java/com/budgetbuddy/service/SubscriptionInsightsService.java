package com.budgetbuddy.service;

import com.budgetbuddy.model.Subscription;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Service for providing smart insights about subscriptions
 * Features:
 * - Unused subscription detection
 * - Price change alerts
 * - Cancellation recommendations
 * - Subscription health scoring
 */
@Service
public class SubscriptionInsightsService {

    private static final Logger logger = LoggerFactory.getLogger(SubscriptionInsightsService.class);
    
    private final TransactionRepository transactionRepository;
    private final SubscriptionService subscriptionService;

    public SubscriptionInsightsService(
            final TransactionRepository transactionRepository,
            final SubscriptionService subscriptionService) {
        this.transactionRepository = transactionRepository;
        this.subscriptionService = subscriptionService;
    }

    /**
     * Detects potentially unused subscriptions
     * A subscription is considered unused if:
     * - No transaction found in the last 2 billing cycles
     * - Last payment was more than 2x the expected frequency ago
     */
    public List<UnusedSubscriptionInsight> detectUnusedSubscriptions(final String userId) {
        logger.info("Detecting unused subscriptions for user: {}", userId);
        
        List<Subscription> subscriptions = subscriptionService.getActiveSubscriptions(userId);
        List<TransactionTable> transactions = transactionRepository.findByUserId(userId, 0, 10000);
        
        List<UnusedSubscriptionInsight> insights = new ArrayList<>();
        
        for (Subscription subscription : subscriptions) {
            // Find recent transactions for this subscription
            List<TransactionTable> subscriptionTransactions = findSubscriptionTransactions(
                transactions, subscription);
            
            if (subscriptionTransactions.isEmpty()) {
                // No transactions found - likely unused
                insights.add(new UnusedSubscriptionInsight(
                    subscription,
                    "No recent payments found",
                    calculatePotentialSavings(subscription),
                    UnusedSubscriptionInsight.Severity.HIGH
                ));
                continue;
            }
            
            // Check if last payment is overdue
            LocalDate lastPaymentDate = subscription.getLastPaymentDate();
            if (lastPaymentDate != null) {
                LocalDate expectedNextPayment = calculateExpectedNextPayment(
                    lastPaymentDate, subscription.getFrequency());
                LocalDate now = LocalDate.now();
                
                // If expected next payment was more than 2 cycles ago, likely unused
                if (now.isAfter(expectedNextPayment.plusDays(getDaysForFrequency(subscription.getFrequency()) * 2))) {
                    insights.add(new UnusedSubscriptionInsight(
                        subscription,
                        String.format("No payment in last %d days (expected every %s)",
                            ChronoUnit.DAYS.between(lastPaymentDate, now),
                            subscription.getFrequency().name().toLowerCase()),
                        calculatePotentialSavings(subscription),
                        UnusedSubscriptionInsight.Severity.MEDIUM
                    ));
                }
            }
        }
        
        logger.info("Detected {} unused subscriptions for user: {}", insights.size(), userId);
        return insights;
    }

    /**
     * Detects price changes in subscriptions
     * Compares recent transaction amounts with subscription amount
     */
    public List<PriceChangeAlert> detectPriceChanges(final String userId) {
        logger.info("Detecting price changes for user: {}", userId);
        
        List<Subscription> subscriptions = subscriptionService.getActiveSubscriptions(userId);
        List<TransactionTable> transactions = transactionRepository.findByUserId(userId, 0, 10000);
        
        List<PriceChangeAlert> alerts = new ArrayList<>();
        
        for (Subscription subscription : subscriptions) {
            // Find recent transactions (last 3 payments)
            List<TransactionTable> recentTransactions = findSubscriptionTransactions(transactions, subscription)
                .stream()
                .sorted((a, b) -> {
                    LocalDate dateA = parseDate(a.getTransactionDate());
                    LocalDate dateB = parseDate(b.getTransactionDate());
                    if (dateA == null || dateB == null) return 0;
                    return dateB.compareTo(dateA); // Most recent first
                })
                .limit(3)
                .collect(Collectors.toList());
            
            if (recentTransactions.size() < 2) {
                continue; // Need at least 2 transactions to detect price change
            }
            
            // Calculate average recent amount
            BigDecimal averageRecentAmount = recentTransactions.stream()
                .map(TransactionTable::getAmount)
                .filter(amount -> amount != null)
                .map(amount -> amount.abs()) // Make positive for comparison
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(new BigDecimal(recentTransactions.size()), 2, RoundingMode.HALF_UP);
            
            // Compare with subscription amount
            BigDecimal subscriptionAmount = subscription.getAmount().abs();
            BigDecimal difference = averageRecentAmount.subtract(subscriptionAmount);
            BigDecimal percentChange = difference.divide(subscriptionAmount, 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));
            
            // Alert if price changed by more than 5%
            if (percentChange.abs().compareTo(new BigDecimal("5")) > 0) {
                alerts.add(new PriceChangeAlert(
                    subscription,
                    averageRecentAmount,
                    subscriptionAmount,
                    percentChange,
                    recentTransactions.get(0).getTransactionDate()
                ));
            }
        }
        
        logger.info("Detected {} price changes for user: {}", alerts.size(), userId);
        return alerts;
    }

    /**
     * Provides cancellation recommendations
     * Recommends cancellation for:
     * - Unused subscriptions
     * - High-cost, low-value subscriptions
     * - Duplicate subscriptions (same service, multiple tiers)
     */
    public List<CancellationRecommendation> getCancellationRecommendations(final String userId) {
        logger.info("Getting cancellation recommendations for user: {}", userId);
        
        List<UnusedSubscriptionInsight> unused = detectUnusedSubscriptions(userId);
        List<Subscription> subscriptions = subscriptionService.getActiveSubscriptions(userId);
        
        List<CancellationRecommendation> recommendations = new ArrayList<>();
        
        // Add unused subscriptions
        for (UnusedSubscriptionInsight insight : unused) {
            recommendations.add(new CancellationRecommendation(
                insight.getSubscription(),
                "Unused subscription - no recent activity",
                insight.getPotentialSavings(),
                CancellationRecommendation.Priority.HIGH
            ));
        }
        
        // Detect duplicate subscriptions (same merchant, different amounts)
        Map<String, List<Subscription>> byMerchant = subscriptions.stream()
            .collect(Collectors.groupingBy(Subscription::getMerchantName));
        
        for (Map.Entry<String, List<Subscription>> entry : byMerchant.entrySet()) {
            if (entry.getValue().size() > 1) {
                // Multiple subscriptions from same merchant - recommend keeping cheapest
                List<Subscription> sorted = entry.getValue().stream()
                    .sorted((a, b) -> a.getAmount().compareTo(b.getAmount()))
                    .collect(Collectors.toList());
                
                // Recommend canceling more expensive ones
                for (int i = 1; i < sorted.size(); i++) {
                    Subscription expensive = sorted.get(i);
                    Subscription cheap = sorted.get(0);
                    BigDecimal savings = expensive.getAmount().subtract(cheap.getAmount());
                    
                    recommendations.add(new CancellationRecommendation(
                        expensive,
                        String.format("Duplicate subscription - consider keeping %s instead (save %s/month)",
                            cheap.getMerchantName(), formatCurrency(savings)),
                        savings,
                        CancellationRecommendation.Priority.MEDIUM
                    ));
                }
            }
        }
        
        logger.info("Generated {} cancellation recommendations for user: {}", recommendations.size(), userId);
        return recommendations;
    }

    // ========== HELPER METHODS ==========

    private List<TransactionTable> findSubscriptionTransactions(
            List<TransactionTable> transactions, Subscription subscription) {
        return transactions.stream()
            .filter(tx -> {
                // CRITICAL FIX: Only match expense transactions (negative amounts)
                // Credits/refunds should not be matched to subscriptions
                if (tx.getAmount() == null || tx.getAmount().compareTo(BigDecimal.ZERO) >= 0) {
                    return false; // Skip positive amounts (credits) and zero amounts
                }
                
                // CRITICAL FIX: Use proper merchant name normalization (same as detection logic)
                String txMerchant = com.budgetbuddy.util.StringUtils.normalizeMerchantName(tx.getMerchantName());
                String subMerchant = com.budgetbuddy.util.StringUtils.normalizeMerchantName(subscription.getMerchantName());
                
                // CRITICAL FIX: Use fuzzy matching instead of exact match
                // This handles variations like "DJ*Barrons" vs "D J*BARRONS"
                if (!areMerchantsSimilar(txMerchant, subMerchant)) {
                    return false;
                }
                
                // Match by amount (within 5% tolerance)
                // Use absolute value for comparison since both are expenses (negative)
                BigDecimal txAmount = tx.getAmount().abs();
                BigDecimal subAmount = subscription.getAmount().abs();
                BigDecimal difference = txAmount.subtract(subAmount).abs();
                BigDecimal tolerance = subAmount.multiply(new BigDecimal("0.05"));
                
                return difference.compareTo(tolerance) <= 0;
            })
            .collect(Collectors.toList());
    }

    /**
     * Checks if two merchant names are similar using fuzzy matching
     * CRITICAL FIX: Improved matching to reduce false positives
     */
    private boolean areMerchantsSimilar(String merchant1, String merchant2) {
        if (merchant1 == null || merchant2 == null) {
            return false;
        }
        
        // Exact match after normalization
        if (merchant1.equals(merchant2)) {
            return true;
        }
        
        // Check if one contains the other (for cases like "DJ BARRONS" vs "D J BARRONS")
        String m1Lower = merchant1.toLowerCase();
        String m2Lower = merchant2.toLowerCase();
        if (m1Lower.contains(m2Lower) || m2Lower.contains(m1Lower)) {
            // Extract key words to avoid false positives
            String[] words1 = m1Lower.split("\\s+");
            String[] words2 = m2Lower.split("\\s+");
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
        
        // Use Levenshtein distance for fuzzy matching
        int maxLen = Math.max(merchant1.length(), merchant2.length());
        if (maxLen == 0) return false;
        
        int distance = levenshteinDistance(merchant1.toLowerCase(), merchant2.toLowerCase());
        double similarity = 1.0 - ((double) distance / maxLen);
        
        // Require at least 80% similarity
        return similarity >= 0.80;
    }
    
    /**
     * Calculates Levenshtein distance between two strings
     */
    private int levenshteinDistance(String s1, String s2) {
        int[][] dp = new int[s1.length() + 1][s2.length() + 1];
        
        for (int i = 0; i <= s1.length(); i++) {
            for (int j = 0; j <= s2.length(); j++) {
                if (i == 0) {
                    dp[i][j] = j;
                } else if (j == 0) {
                    dp[i][j] = i;
                } else {
                    dp[i][j] = Math.min(
                        Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                        dp[i - 1][j - 1] + (s1.charAt(i - 1) == s2.charAt(j - 1) ? 0 : 1)
                    );
                }
            }
        }
        
        return dp[s1.length()][s2.length()];
    }

    private LocalDate calculateExpectedNextPayment(LocalDate lastPayment, Subscription.SubscriptionFrequency frequency) {
        if (frequency == null || lastPayment == null) {
            return null;
        }
        
        switch (frequency) {
            case DAILY:
                return lastPayment.plusDays(1);
            case WEEKLY:
                return lastPayment.plusWeeks(1);
            case BI_WEEKLY:
                return lastPayment.plusWeeks(2);
            case MONTHLY:
                return lastPayment.plusMonths(1);
            case QUARTERLY:
                return lastPayment.plusMonths(3);
            case SEMI_ANNUAL:
                return lastPayment.plusMonths(6);
            case ANNUAL:
                return lastPayment.plusYears(1);
            default:
                return lastPayment.plusMonths(1);
        }
    }

    private int getDaysForFrequency(Subscription.SubscriptionFrequency frequency) {
        if (frequency == null) {
            return 30;
        }
        
        switch (frequency) {
            case DAILY: return 1;
            case WEEKLY: return 7;
            case BI_WEEKLY: return 14;
            case MONTHLY: return 30;
            case QUARTERLY: return 90;
            case SEMI_ANNUAL: return 180;
            case ANNUAL: return 365;
            default: return 30;
        }
    }

    private BigDecimal calculatePotentialSavings(Subscription subscription) {
        // Calculate annual savings
        switch (subscription.getFrequency()) {
            case MONTHLY:
                return subscription.getAmount().abs().multiply(new BigDecimal("12"));
            case QUARTERLY:
                return subscription.getAmount().abs().multiply(new BigDecimal("4"));
            case SEMI_ANNUAL:
                return subscription.getAmount().abs().multiply(new BigDecimal("2"));
            case ANNUAL:
                return subscription.getAmount().abs();
            default:
                return subscription.getAmount().abs().multiply(new BigDecimal("12"));
        }
    }

    private LocalDate parseDate(String dateString) {
        if (dateString == null || dateString.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(dateString);
        } catch (Exception e) {
            logger.debug("Failed to parse date: {}", dateString);
            return null;
        }
    }

    private String formatCurrency(BigDecimal amount) {
        return "$" + amount.setScale(2, RoundingMode.HALF_UP).toString();
    }

    // ========== INSIGHT MODELS ==========

    public static class UnusedSubscriptionInsight {
        private final Subscription subscription;
        private final String reason;
        private final BigDecimal potentialSavings;
        private final Severity severity;

        public UnusedSubscriptionInsight(Subscription subscription, String reason,
                                       BigDecimal potentialSavings, Severity severity) {
            this.subscription = subscription;
            this.reason = reason;
            this.potentialSavings = potentialSavings;
            this.severity = severity;
        }

        public Subscription getSubscription() { return subscription; }
        public String getReason() { return reason; }
        public BigDecimal getPotentialSavings() { return potentialSavings; }
        public Severity getSeverity() { return severity; }

        public enum Severity {
            LOW, MEDIUM, HIGH
        }
    }

    public static class PriceChangeAlert {
        private final Subscription subscription;
        private final BigDecimal newAmount;
        private final BigDecimal oldAmount;
        private final BigDecimal percentChange;
        private final String detectedDate;

        public PriceChangeAlert(Subscription subscription, BigDecimal newAmount,
                              BigDecimal oldAmount, BigDecimal percentChange, String detectedDate) {
            this.subscription = subscription;
            this.newAmount = newAmount;
            this.oldAmount = oldAmount;
            this.percentChange = percentChange;
            this.detectedDate = detectedDate;
        }

        public Subscription getSubscription() { return subscription; }
        public BigDecimal getNewAmount() { return newAmount; }
        public BigDecimal getOldAmount() { return oldAmount; }
        public BigDecimal getPercentChange() { return percentChange; }
        public String getDetectedDate() { return detectedDate; }
    }

    public static class CancellationRecommendation {
        private final Subscription subscription;
        private final String reason;
        private final BigDecimal potentialSavings;
        private final Priority priority;

        public CancellationRecommendation(Subscription subscription, String reason,
                                         BigDecimal potentialSavings, Priority priority) {
            this.subscription = subscription;
            this.reason = reason;
            this.potentialSavings = potentialSavings;
            this.priority = priority;
        }

        public Subscription getSubscription() { return subscription; }
        public String getReason() { return reason; }
        public BigDecimal getPotentialSavings() { return potentialSavings; }
        public Priority getPriority() { return priority; }

        public enum Priority {
            LOW, MEDIUM, HIGH
        }
    }
}
