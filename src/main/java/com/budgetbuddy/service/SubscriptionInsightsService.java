package com.budgetbuddy.service;

import com.budgetbuddy.model.Subscription;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Service for providing smart insights about subscriptions Features: - Unused subscription
 * detection - Price change alerts - Cancellation recommendations - Subscription health scoring
 */
// SpotBugs flags constructor-injected Spring beans as EI_EXPOSE_REP2,
// but Spring's IoC container intentionally shares the same bean across
// callers — defensive-copying it would break dependency injection.
// SDK / Spring integration — the underlying APIs (AWS SDK, Plaid SDK,
// Spring services, reflection) throw arbitrary RuntimeException subtypes
// that can't reasonably be enumerated. Broad catches log + recover (or
// translate to AppException). Suppress at class level since narrowing
// here would mean catch (RuntimeException) which PMD flags identically.
@SuppressFBWarnings(
        value = {"EI_EXPOSE_REP"},
        justification =
                "JSON DTO / DynamoDB entity getters expose lists by reference; "
                        + "the design is value-semantic and Jackson creates fresh instances. "
                        + "Spring constructor injection — beans are shared by design.")
@SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.DataClass", "PMD.OnlyOneReturn"})
@Service
public class SubscriptionInsightsService {

    private static final Logger LOGGER = LoggerFactory.getLogger(SubscriptionInsightsService.class);

    private final TransactionRepository transactionRepository;
    private final SubscriptionService subscriptionService;

    public SubscriptionInsightsService(
            final TransactionRepository transactionRepository,
            final SubscriptionService subscriptionService) {
        this.transactionRepository = transactionRepository;
        this.subscriptionService = subscriptionService;
    }

    /**
     * Detects potentially unused subscriptions A subscription is considered unused if: - No
     * transaction found in the last 2 billing cycles - Last payment was more than 2x the expected
     * frequency ago
     */
    public List<UnusedSubscriptionInsight> detectUnusedSubscriptions(final String userId) {
        LOGGER.info("Detecting unused subscriptions for user: {}", userId);

        final List<Subscription> subscriptions = subscriptionService.getActiveSubscriptions(userId);
        final List<TransactionTable> transactions =
                transactionRepository.findByUserId(userId, 0, 10_000);

        final List<UnusedSubscriptionInsight> insights = new ArrayList<>();

        for (final Subscription subscription : subscriptions) {
            // Find recent transactions for this subscription
            final List<TransactionTable> subscriptionTransactions =
                    findSubscriptionTransactions(transactions, subscription);

            if (subscriptionTransactions.isEmpty()) {
                // No transactions found - likely unused
                insights.add(
                        new UnusedSubscriptionInsight(
                                subscription,
                                "No recent payments found",
                                calculatePotentialSavings(subscription),
                                UnusedSubscriptionInsight.Severity.HIGH));
                continue;
            }

            // Check if last payment is overdue
            final LocalDate lastPaymentDate = subscription.getLastPaymentDate();
            if (lastPaymentDate != null) {
                final LocalDate expectedNextPayment =
                        calculateExpectedNextPayment(lastPaymentDate, subscription.getFrequency());
                final LocalDate now = LocalDate.now();

                // If expected next payment was more than 2 cycles ago, likely unused
                if (now.isAfter(
                        expectedNextPayment.plusDays(
                                getDaysForFrequency(subscription.getFrequency()) * 2))) {
                    insights.add(
                            new UnusedSubscriptionInsight(
                                    subscription,
                                    String.format(
                                            "No payment in last %d days (expected every %s)",
                                            ChronoUnit.DAYS.between(lastPaymentDate, now),
                                            subscription
                                                    .getFrequency()
                                                    .name()
                                                    .toLowerCase(Locale.ROOT)),
                                    calculatePotentialSavings(subscription),
                                    UnusedSubscriptionInsight.Severity.MEDIUM));
                }
            }
        }

        LOGGER.info("Detected {} unused subscriptions for user: {}", insights.size(), userId);
        return insights;
    }

    /**
     * Detects price changes in subscriptions Compares recent transaction amounts with subscription
     * amount
     */
    public List<PriceChangeAlert> detectPriceChanges(final String userId) {
        LOGGER.info("Detecting price changes for user: {}", userId);

        final List<Subscription> subscriptions = subscriptionService.getActiveSubscriptions(userId);
        final List<TransactionTable> transactions =
                transactionRepository.findByUserId(userId, 0, 10_000);

        final List<PriceChangeAlert> alerts = new ArrayList<>();

        for (final Subscription subscription : subscriptions) {
            // Find recent transactions (last 3 payments)
            final List<TransactionTable> recentTransactions =
                    findSubscriptionTransactions(transactions, subscription).stream()
                            .sorted(
                                    (a, b) -> {
                                        final LocalDate dateA = parseDate(a.getTransactionDate());
                                        final LocalDate dateB = parseDate(b.getTransactionDate());
                                        if (dateA == null || dateB == null) {
                                            return 0;
                                        }
                                        return dateB.compareTo(dateA); // Most recent first
                                    })
                            .limit(3)
                            .collect(Collectors.toList());

            if (recentTransactions.size() < 2) {
                continue; // Need at least 2 transactions to detect price change
            }

            // Calculate average recent amount
            final BigDecimal averageRecentAmount =
                    recentTransactions.stream()
                            .map(TransactionTable::getAmount)
                            .filter(amount -> amount != null)
                            .map(amount -> amount.abs()) // Make positive for comparison
                            .reduce(BigDecimal.ZERO, BigDecimal::add)
                            .divide(
                                    new BigDecimal(recentTransactions.size()),
                                    2,
                                    RoundingMode.HALF_UP);

            // Compare with subscription amount
            final BigDecimal subscriptionAmount = subscription.getAmount().abs();
            final BigDecimal difference = averageRecentAmount.subtract(subscriptionAmount);
            final BigDecimal percentChange =
                    difference
                            .divide(subscriptionAmount, 4, RoundingMode.HALF_UP)
                            .multiply(new BigDecimal("100"));

            // Alert if price changed by more than 5%
            if (percentChange.abs().compareTo(new BigDecimal("5")) > 0) {
                alerts.add(
                        new PriceChangeAlert(
                                subscription,
                                averageRecentAmount,
                                subscriptionAmount,
                                percentChange,
                                recentTransactions.get(0).getTransactionDate()));
            }
        }

        LOGGER.info("Detected {} price changes for user: {}", alerts.size(), userId);
        return alerts;
    }

    /**
     * Provides cancellation recommendations Recommends cancellation for: - Unused subscriptions -
     * High-cost, low-value subscriptions - Duplicate subscriptions (same service, multiple tiers)
     */
    public List<CancellationRecommendation> getCancellationRecommendations(final String userId) {
        LOGGER.info("Getting cancellation recommendations for user: {}", userId);

        final List<UnusedSubscriptionInsight> unused = detectUnusedSubscriptions(userId);
        final List<Subscription> subscriptions = subscriptionService.getActiveSubscriptions(userId);

        final List<CancellationRecommendation> recommendations = new ArrayList<>();

        // Add unused subscriptions
        for (final UnusedSubscriptionInsight insight : unused) {
            recommendations.add(
                    new CancellationRecommendation(
                            insight.getSubscription(),
                            "Unused subscription - no recent activity",
                            insight.getPotentialSavings(),
                            CancellationRecommendation.Priority.HIGH));
        }

        // Detect duplicate subscriptions (same merchant, different amounts)
        final Map<String, List<Subscription>> byMerchant =
                subscriptions.stream()
                        .collect(Collectors.groupingBy(Subscription::getMerchantName));

        for (final Map.Entry<String, List<Subscription>> entry : byMerchant.entrySet()) {
            if (entry.getValue().size() > 1) {
                // Multiple subscriptions from same merchant - recommend keeping cheapest
                final List<Subscription> sorted =
                        entry.getValue().stream()
                                .sorted((a, b) -> a.getAmount().compareTo(b.getAmount()))
                                .collect(Collectors.toList());

                // Recommend canceling more expensive ones
                for (int i = 1; i < sorted.size(); i++) {
                    final Subscription expensive = sorted.get(i);
                    final Subscription cheap = sorted.get(0);
                    final BigDecimal savings = expensive.getAmount().subtract(cheap.getAmount());

                    recommendations.add(
                            new CancellationRecommendation(
                                    expensive,
                                    String.format(
                                            "Duplicate subscription - consider keeping %s instead (save %s/month)",
                                            cheap.getMerchantName(), formatCurrency(savings)),
                                    savings,
                                    CancellationRecommendation.Priority.MEDIUM));
                }
            }
        }

        LOGGER.info(
                "Generated {} cancellation recommendations for user: {}",
                recommendations.size(),
                userId);
        return recommendations;
    }

    // ========== HELPER METHODS ==========

    private List<TransactionTable> findSubscriptionTransactions(
            final List<TransactionTable> transactions, final Subscription subscription) {
        return transactions.stream()
                .filter(
                        tx -> {
                            // CRITICAL FIX: Only match expense transactions (negative amounts)
                            // Credits/refunds should not be matched to subscriptions
                            if (tx.getAmount() == null
                                    || tx.getAmount().compareTo(BigDecimal.ZERO) >= 0) {
                                return false; // Skip positive amounts (credits) and zero amounts
                            }

                            // CRITICAL FIX: Use proper merchant name normalization (same as
                            // detection logic)
                            final String txMerchant =
                                    com.budgetbuddy.util.StringUtils.normalizeMerchantName(
                                            tx.getMerchantName());
                            final String subMerchant =
                                    com.budgetbuddy.util.StringUtils.normalizeMerchantName(
                                            subscription.getMerchantName());

                            // CRITICAL FIX: Use fuzzy matching instead of exact match
                            // This handles variations like "DJ*Barrons" vs "D J*BARRONS"
                            if (!areMerchantsSimilar(txMerchant, subMerchant)) {
                                return false;
                            }

                            // Match by amount (within 5% tolerance)
                            // Use absolute value for comparison since both are expenses (negative)
                            final BigDecimal txAmount = tx.getAmount().abs();
                            final BigDecimal subAmount = subscription.getAmount().abs();
                            final BigDecimal difference = txAmount.subtract(subAmount).abs();
                            final BigDecimal tolerance = subAmount.multiply(new BigDecimal("0.05"));

                            return difference.compareTo(tolerance) <= 0;
                        })
                .collect(Collectors.toList());
    }

    /**
     * Checks if two merchant names are similar using fuzzy matching CRITICAL FIX: Improved matching
     * to reduce false positives
     */
    private boolean areMerchantsSimilar(final String merchant1, final String merchant2) {
        if (merchant1 == null || merchant2 == null) {
            return false;
        }

        // Exact match after normalization
        if (merchant1.equals(merchant2)) {
            return true;
        }

        // Check if one contains the other (for cases like "DJ BARRONS" vs "D J BARRONS")
        final String m1Lower = merchant1.toLowerCase(Locale.ROOT);
        final String m2Lower = merchant2.toLowerCase(Locale.ROOT);
        if (m1Lower.contains(m2Lower) || m2Lower.contains(m1Lower)) {
            // Extract key words to avoid false positives
            final String[] words1 = m1Lower.split("\\s+");
            final String[] words2 = m2Lower.split("\\s+");
            // If at least 2 significant words match, consider similar
            int commonWords = 0;
            for (final String w1 : words1) {
                if (w1.length() > 2) { // Significant words only
                    for (final String w2 : words2) {
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
        final int maxLen = Math.max(merchant1.length(), merchant2.length());
        if (maxLen == 0) {
            return false;
        }

        final int distance =
                levenshteinDistance(
                        merchant1.toLowerCase(Locale.ROOT), merchant2.toLowerCase(Locale.ROOT));
        final double similarity = 1.0 - ((double) distance / maxLen);

        // Require at least 80% similarity
        return similarity >= 0.80;
    }

    /** Calculates Levenshtein distance between two strings */
    private int levenshteinDistance(final String s1, final String s2) {
        final int[][] dp = new int[s1.length() + 1][s2.length() + 1];

        for (int i = 0; i <= s1.length(); i++) {
            for (int j = 0; j <= s2.length(); j++) {
                if (i == 0) {
                    dp[i][j] = j;
                } else if (j == 0) {
                    dp[i][j] = i;
                } else {
                    dp[i][j] =
                            Math.min(
                                    Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                                    dp[i - 1][j - 1]
                                            + (s1.charAt(i - 1) == s2.charAt(j - 1) ? 0 : 1));
                }
            }
        }

        return dp[s1.length()][s2.length()];
    }

    private LocalDate calculateExpectedNextPayment(
            final LocalDate lastPayment, final Subscription.SubscriptionFrequency frequency) {
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

    private int getDaysForFrequency(final Subscription.SubscriptionFrequency frequency) {
        if (frequency == null) {
            return 30;
        }

        switch (frequency) {
            case DAILY:
                return 1;
            case WEEKLY:
                return 7;
            case BI_WEEKLY:
                return 14;
            case MONTHLY:
                return 30;
            case QUARTERLY:
                return 90;
            case SEMI_ANNUAL:
                return 180;
            case ANNUAL:
                return 365;
            default:
                return 30;
        }
    }

    private BigDecimal calculatePotentialSavings(final Subscription subscription) {
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

    private LocalDate parseDate(final String dateString) {
        if (dateString == null || dateString.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(dateString);
        } catch (Exception e) {
            LOGGER.debug("Failed to parse date: {}", dateString);
            return null;
        }
    }

    private String formatCurrency(final BigDecimal amount) {
        return "$" + amount.setScale(2, RoundingMode.HALF_UP).toString();
    }

    // ========== INSIGHT MODELS ==========

    public static class UnusedSubscriptionInsight {
        private final Subscription subscription;
        private final String reason;
        private final BigDecimal potentialSavings;
        private final Severity severity;

        public UnusedSubscriptionInsight(
                final Subscription subscription,
                final String reason,
                final BigDecimal potentialSavings,
                final Severity severity) {
            this.subscription = subscription;
            this.reason = reason;
            this.potentialSavings = potentialSavings;
            this.severity = severity;
        }

        public Subscription getSubscription() {
            return subscription;
        }

        public String getReason() {
            return reason;
        }

        public BigDecimal getPotentialSavings() {
            return potentialSavings;
        }

        public Severity getSeverity() {
            return severity;
        }

        public enum Severity {
            LOW,
            MEDIUM,
            HIGH
        }
    }

    public static class PriceChangeAlert {
        private final Subscription subscription;
        private final BigDecimal newAmount;
        private final BigDecimal oldAmount;
        private final BigDecimal percentChange;
        private final String detectedDate;

        public PriceChangeAlert(
                final Subscription subscription,
                final BigDecimal newAmount,
                final BigDecimal oldAmount,
                final BigDecimal percentChange,
                final String detectedDate) {
            this.subscription = subscription;
            this.newAmount = newAmount;
            this.oldAmount = oldAmount;
            this.percentChange = percentChange;
            this.detectedDate = detectedDate;
        }

        public Subscription getSubscription() {
            return subscription;
        }

        public BigDecimal getNewAmount() {
            return newAmount;
        }

        public BigDecimal getOldAmount() {
            return oldAmount;
        }

        public BigDecimal getPercentChange() {
            return percentChange;
        }

        public String getDetectedDate() {
            return detectedDate;
        }
    }

    public static class CancellationRecommendation {
        private final Subscription subscription;
        private final String reason;
        private final BigDecimal potentialSavings;
        private final Priority priority;

        public CancellationRecommendation(
                final Subscription subscription,
                final String reason,
                final BigDecimal potentialSavings,
                final Priority priority) {
            this.subscription = subscription;
            this.reason = reason;
            this.potentialSavings = potentialSavings;
            this.priority = priority;
        }

        public Subscription getSubscription() {
            return subscription;
        }

        public String getReason() {
            return reason;
        }

        public BigDecimal getPotentialSavings() {
            return potentialSavings;
        }

        public Priority getPriority() {
            return priority;
        }

        public enum Priority {
            LOW,
            MEDIUM,
            HIGH
        }
    }
}
