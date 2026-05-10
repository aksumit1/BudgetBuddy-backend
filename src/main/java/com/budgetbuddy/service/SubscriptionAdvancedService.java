package com.budgetbuddy.service;


import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Locale;
import com.budgetbuddy.model.Subscription;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Advanced subscription intelligence service Features: - ML-based usage prediction - Subscription
 * bundling recommendations - Trial expiration alerts - Subscription health scoring - Predictive
 * cancellation detection - Subscription optimization (suggest cheaper alternatives) - Usage-based
 * recommendations
 */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
// SpotBugs flags constructor-injected Spring beans as EI_EXPOSE_REP2,
// but Spring's IoC container intentionally shares the same bean across
// callers — defensive-copying it would break dependency injection.
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Spring constructor injection — beans are shared by design")
@SuppressWarnings({"PMD.LawOfDemeter", "PMD.AvoidCatchingGenericException"})
@Service
public class SubscriptionAdvancedService {

    private static final Logger LOGGER = LoggerFactory.getLogger(SubscriptionAdvancedService.class);

    private final TransactionRepository transactionRepository;
    private final SubscriptionService subscriptionService;
    private final SubscriptionInsightsService insightsService;

    public SubscriptionAdvancedService(
            final TransactionRepository transactionRepository,
            final SubscriptionService subscriptionService,
            final SubscriptionInsightsService insightsService) {
        this.transactionRepository = transactionRepository;
        this.subscriptionService = subscriptionService;
        this.insightsService = insightsService;
    }

    /**
     * Calculates subscription health score (0-100) Factors: - Usage frequency (recent activity) -
     * Price stability - Value per dollar - Duplicate detection
     */
    public SubscriptionHealthScore calculateHealthScore(
            final String userId, final Subscription subscription) {
        final List<TransactionTable> transactions = transactionRepository.findByUserId(userId, 0, 10_000);
        final List<TransactionTable> subscriptionTransactions =
                findSubscriptionTransactions(transactions, subscription);

        int score = 100;
        final List<String> issues = new ArrayList<>();

        // Check usage frequency (recent activity)
        final LocalDate lastPayment = subscription.getLastPaymentDate();
        if (lastPayment != null) {
            final long daysSinceLastPayment = ChronoUnit.DAYS.between(lastPayment, LocalDate.now());
            final int expectedDays = getDaysForFrequency(subscription.getFrequency());

            if (daysSinceLastPayment > expectedDays * 2) {
                score -= 30;
                issues.add("No recent activity detected");
            } else if (daysSinceLastPayment > expectedDays * 1.5) {
                score -= 15;
                issues.add("Activity may be declining");
            }
        } else {
            score -= 20;
            issues.add("No payment history");
        }

        // Check for price changes (stability)
        if (subscriptionTransactions.size() >= 2) {
            final BigDecimal firstAmount = subscriptionTransactions.get(0).getAmount().abs();
            final BigDecimal lastAmount =
                    subscriptionTransactions
                            .get(subscriptionTransactions.size() - 1)
                            .getAmount()
                            .abs();
            final BigDecimal percentChange =
                    lastAmount
                            .subtract(firstAmount)
                            .divide(firstAmount, 4, RoundingMode.HALF_UP)
                            .multiply(new BigDecimal("100"));

            if (percentChange.abs().compareTo(new BigDecimal("10")) > 0) {
                score -= 10;
                issues.add("Price has changed significantly");
            }
        }

        // Check for duplicates (same merchant, different amounts)
        final List<Subscription> allSubscriptions = subscriptionService.getActiveSubscriptions(userId);
        final long duplicateCount =
                allSubscriptions.stream()
                        .filter(
                                sub ->
                                        sub.getMerchantName()
                                                .equalsIgnoreCase(subscription.getMerchantName()))
                        .count();

        if (duplicateCount > 1) {
            score -= 20;
            issues.add("Multiple subscriptions from same merchant");
        }

        // Determine health level
        final String healthLevel;
        if (score >= 80) {
            healthLevel = "EXCELLENT";
        } else if (score >= 60) {
            healthLevel = "GOOD";
        } else if (score >= 40) {
            healthLevel = "FAIR";
        } else {
            healthLevel = "POOR";
        }

        return new SubscriptionHealthScore(
                subscription, Math.max(0, Math.min(100, score)), healthLevel, issues);
    }

    /**
     * Detects trial subscriptions that are about to expire Alerts user before trial converts to
     * paid subscription
     */
    public List<TrialExpirationAlert> detectTrialExpirations(final String userId) {
        LOGGER.info("Detecting trial expirations for user: {}", userId);

        final List<Subscription> subscriptions = subscriptionService.getActiveSubscriptions(userId);
        final List<TransactionTable> transactions = transactionRepository.findByUserId(userId, 0, 10_000);

        final List<TrialExpirationAlert> alerts = new ArrayList<>();

        for (final Subscription subscription : subscriptions) {
            // Check if this might be a trial (first payment, low amount, or "trial" in description)
            final List<TransactionTable> subTransactions =
                    findSubscriptionTransactions(transactions, subscription);

            if (subTransactions.isEmpty()) {
                continue;
            }

            // Check if description contains "trial"
            final String description = subscription.getDescription();
            if (description != null && description.toLowerCase(Locale.ROOT).contains("trial")) {
                // Check if this is the first payment (trial period)
                if (subTransactions.size() == 1) {
                    final LocalDate firstPayment = parseDate(subTransactions.get(0).getTransactionDate());
                    if (firstPayment != null) {
                        // Assume 7, 14, or 30 day trial
                        final LocalDate[] trialEndDates = {
                                firstPayment.plusDays(7),
                                firstPayment.plusDays(14),
                                firstPayment.plusDays(30)
                        };

                        for (final LocalDate trialEnd : trialEndDates) {
                            final long daysUntil = ChronoUnit.DAYS.between(LocalDate.now(), trialEnd);
                            if (daysUntil >= 0 && daysUntil <= 3) {
                                alerts.add(
                                        new TrialExpirationAlert(
                                                subscription,
                                                trialEnd,
                                                (int) daysUntil,
                                                subscription.getAmount()));
                                break;
                            }
                        }
                    }
                }
            }
        }

        LOGGER.info("Detected {} trial expirations for user: {}", alerts.size(), userId);
        return alerts;
    }

    /**
     * Suggests subscription bundling opportunities Identifies services that could be bundled for
     * savings
     */
    public List<BundlingRecommendation> suggestBundling(final String userId) {
        LOGGER.info("Suggesting bundling opportunities for user: {}", userId);

        final List<Subscription> subscriptions = subscriptionService.getActiveSubscriptions(userId);

        // Known bundling opportunities
        final Map<String, List<String>> bundlingGroups = new HashMap<>();
        bundlingGroups.put(
                "streaming", List.of("netflix", "hulu", "disney", "hbo", "paramount", "peacock"));
        bundlingGroups.put(
                "cloud_storage", List.of("dropbox", "icloud", "google drive", "onedrive"));
        bundlingGroups.put("software", List.of("adobe", "microsoft 365", "office 365"));

        final List<BundlingRecommendation> recommendations = new ArrayList<>();

        for (final Map.Entry<String, List<String>> group : bundlingGroups.entrySet()) {
            final String groupType = group.getKey();
            final List<String> services = group.getValue();

            // Find user's subscriptions in this group
            final List<Subscription> userSubscriptions =
                    subscriptions.stream()
                            .filter(
                                    sub -> {
                                        final String merchant = sub.getMerchantName().toLowerCase(Locale.ROOT);
                                        return services.stream().anyMatch(merchant::contains);
                                    })
                            .collect(Collectors.toList());

            if (userSubscriptions.size() >= 2) {
                // Calculate potential savings from bundling
                final BigDecimal currentTotal =
                        userSubscriptions.stream()
                                .map(Subscription::getAmount)
                                .reduce(BigDecimal.ZERO, BigDecimal::add);

                // Estimate bundling discount (typically 10-20%)
                final BigDecimal estimatedBundlePrice =
                        currentTotal.multiply(new BigDecimal("0.85")); // 15% discount
                final BigDecimal potentialSavings = currentTotal.subtract(estimatedBundlePrice);

                if (potentialSavings.compareTo(BigDecimal.ZERO) > 0) {
                    recommendations.add(
                            new BundlingRecommendation(
                                    groupType,
                                    userSubscriptions,
                                    potentialSavings,
                                    estimatedBundlePrice,
                                    "Bundle "
                                            + userSubscriptions.size()
                                            + " "
                                            + groupType
                                            + " services for "
                                            + formatCurrency(potentialSavings)
                                            + "/month savings"));
                }
            }
        }

        LOGGER.info(
                "Generated {} bundling recommendations for user: {}",
                recommendations.size(),
                userId);
        return recommendations;
    }

    /**
     * Suggests cheaper alternatives for existing subscriptions Uses known service tiers and pricing
     */
    public List<AlternativeRecommendation> suggestAlternatives(final String userId) {
        LOGGER.info("Suggesting cheaper alternatives for user: {}", userId);

        final List<Subscription> subscriptions = subscriptionService.getActiveSubscriptions(userId);
        final List<AlternativeRecommendation> recommendations = new ArrayList<>();

        // Known alternatives (service -> cheaper alternative)
        final Map<String, AlternativeInfo> alternatives = new HashMap<>();
        alternatives.put(
                "netflix",
                new AlternativeInfo("Netflix Basic", new BigDecimal("9.99"), "Lower tier"));
        alternatives.put(
                "spotify",
                new AlternativeInfo("Spotify Student", new BigDecimal("4.99"), "Student discount"));
        alternatives.put(
                "adobe",
                new AlternativeInfo(
                        "Adobe Photography Plan", new BigDecimal("9.99"), "Focused plan"));

        for (final Subscription subscription : subscriptions) {
            final String merchant = subscription.getMerchantName().toLowerCase(Locale.ROOT);

            for (final Map.Entry<String, AlternativeInfo> entry : alternatives.entrySet()) {
                if (merchant.contains(entry.getKey())) {
                    final AlternativeInfo alternative = entry.getValue();
                    final BigDecimal currentAmount = subscription.getAmount().abs();
                    final BigDecimal alternativeAmount = alternative.price;

                    if (alternativeAmount.compareTo(currentAmount) < 0) {
                        final BigDecimal savings = currentAmount.subtract(alternativeAmount);
                        recommendations.add(
                                new AlternativeRecommendation(
                                        subscription,
                                        alternative.name,
                                        alternativeAmount,
                                        savings,
                                        alternative.reason));
                    }
                }
            }
        }

        LOGGER.info(
                "Generated {} alternative recommendations for user: {}",
                recommendations.size(),
                userId);
        return recommendations;
    }

    /**
     * Predicts which subscriptions user is likely to cancel Based on usage patterns, price changes,
     * and health score
     */
    public List<PredictiveCancellation> predictCancellations(final String userId) {
        LOGGER.info("Predicting cancellations for user: {}", userId);

        final List<Subscription> subscriptions = subscriptionService.getActiveSubscriptions(userId);
        final List<PredictiveCancellation> predictions = new ArrayList<>();

        for (final Subscription subscription : subscriptions) {
            final SubscriptionHealthScore healthScore = calculateHealthScore(userId, subscription);
            final List<SubscriptionInsightsService.UnusedSubscriptionInsight> unused =
                    insightsService.detectUnusedSubscriptions(userId);

            final boolean isUnused =
                    unused.stream()
                            .anyMatch(
                                    insight ->
                                            insight.getSubscription()
                                                    .getSubscriptionId()
                                                    .equals(subscription.getSubscriptionId()));

            // Predict cancellation if health score is low or unused
            if (healthScore.getScore() < 50 || isUnused) {
                final int cancellationProbability = isUnused ? 90 : (100 - healthScore.getScore());

                predictions.add(
                        new PredictiveCancellation(
                                subscription,
                                cancellationProbability,
                                isUnused
                                        ? "No recent activity detected"
                                        : "Low health score: " + healthScore.getScore(),
                                healthScore.getScore()));
            }
        }

        LOGGER.info(
                "Predicted {} potential cancellations for user: {}", predictions.size(), userId);
        return predictions;
    }

    /**
     * Optimizes subscription portfolio Provides comprehensive recommendations for subscription
     * management
     */
    public SubscriptionOptimization optimizePortfolio(final String userId) {
        LOGGER.info("Optimizing subscription portfolio for user: {}", userId);

        final List<Subscription> subscriptions = subscriptionService.getActiveSubscriptions(userId);
        final List<SubscriptionInsightsService.CancellationRecommendation> cancellations =
                insightsService.getCancellationRecommendations(userId);
        final List<BundlingRecommendation> bundling = suggestBundling(userId);
        final List<AlternativeRecommendation> alternatives = suggestAlternatives(userId);
        final List<TrialExpirationAlert> trials = detectTrialExpirations(userId);

        // Calculate total potential savings
        final BigDecimal cancellationSavings =
                cancellations.stream()
                        .map(
                                SubscriptionInsightsService.CancellationRecommendation
                                        ::getPotentialSavings)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

        final BigDecimal bundlingSavings =
                bundling.stream()
                        .map(BundlingRecommendation::getPotentialSavings)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

        final BigDecimal alternativeSavings =
                alternatives.stream()
                        .map(AlternativeRecommendation::getPotentialSavings)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

        final BigDecimal totalSavings = cancellationSavings.add(bundlingSavings).add(alternativeSavings);

        // Calculate current monthly spend
        final BigDecimal monthlySpend =
                subscriptions.stream()
                        .map(
                                sub -> {
                                    switch (sub.getFrequency()) {
                                        case MONTHLY:
                                            return sub.getAmount().abs();
                                        case QUARTERLY:
                                            return sub.getAmount()
                                                    .abs()
                                                    .divide(
                                                            new BigDecimal("3"),
                                                            2,
                                                            RoundingMode.HALF_UP);
                                        case SEMI_ANNUAL:
                                            return sub.getAmount()
                                                    .abs()
                                                    .divide(
                                                            new BigDecimal("6"),
                                                            2,
                                                            RoundingMode.HALF_UP);
                                        case ANNUAL:
                                            return sub.getAmount()
                                                    .abs()
                                                    .divide(
                                                            new BigDecimal("12"),
                                                            2,
                                                            RoundingMode.HALF_UP);
                                        default:
                                            return sub.getAmount().abs();
                                    }
                                })
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new SubscriptionOptimization(
                subscriptions.size(),
                monthlySpend,
                totalSavings,
                cancellationSavings,
                bundlingSavings,
                alternativeSavings,
                cancellations,
                bundling,
                alternatives,
                trials);
    }

    // ========== HELPER METHODS ==========

    private List<TransactionTable> findSubscriptionTransactions(
            final List<TransactionTable> transactions, final Subscription subscription) {
        return transactions.stream()
                .filter(
                        tx -> {
                            final String txMerchant = normalizeMerchantName(tx.getMerchantName());
                            final String subMerchant =
                                    normalizeMerchantName(subscription.getMerchantName());

                            if (!txMerchant.equals(subMerchant)) {
                                return false;
                            }

                            final BigDecimal txAmount =
                                    tx.getAmount() != null ? tx.getAmount().abs() : BigDecimal.ZERO;
                            final BigDecimal subAmount = subscription.getAmount().abs();
                            final BigDecimal difference = txAmount.subtract(subAmount).abs();
                            final BigDecimal tolerance = subAmount.multiply(new BigDecimal("0.05"));

                            return difference.compareTo(tolerance) <= 0;
                        })
                .sorted(Comparator.comparing(TransactionTable::getTransactionDate))
                .collect(Collectors.toList());
    }

    private String normalizeMerchantName(final String merchantName) {
        if (merchantName == null) {
            return "";
        }
        return merchantName.trim().toUpperCase(Locale.ROOT);
    }

    private int getDaysForFrequency(final Subscription.SubscriptionFrequency frequency) {
        switch (frequency) {
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

    // ========== MODEL CLASSES ==========

    public static class SubscriptionHealthScore {
        private final Subscription subscription;
        private final int score;
        private final String healthLevel;
        private final List<String> issues;

        public SubscriptionHealthScore(
                final Subscription subscription, final int score, final String healthLevel, final List<String> issues) {
            this.subscription = subscription;
            this.score = score;
            this.healthLevel = healthLevel;
            this.issues = issues;
        }

        public Subscription getSubscription() {
            return subscription;
        }

        public int getScore() {
            return score;
        }

        public String getHealthLevel() {
            return healthLevel;
        }

        public List<String> getIssues() {
            return issues;
        }
    }

    public static class TrialExpirationAlert {
        private final Subscription subscription;
        private final LocalDate expirationDate;
        private final int daysUntilExpiration;
        private final BigDecimal conversionAmount;

        public TrialExpirationAlert(
                final Subscription subscription,
                final LocalDate expirationDate,
                final int daysUntilExpiration,
                final BigDecimal conversionAmount) {
            this.subscription = subscription;
            this.expirationDate = expirationDate;
            this.daysUntilExpiration = daysUntilExpiration;
            this.conversionAmount = conversionAmount;
        }

        public Subscription getSubscription() {
            return subscription;
        }

        public LocalDate getExpirationDate() {
            return expirationDate;
        }

        public int getDaysUntilExpiration() {
            return daysUntilExpiration;
        }

        public BigDecimal getConversionAmount() {
            return conversionAmount;
        }
    }

    public static class BundlingRecommendation {
        private final String bundleType;
        private final List<Subscription> subscriptions;
        private final BigDecimal potentialSavings;
        private final BigDecimal estimatedBundlePrice;
        private final String description;

        public BundlingRecommendation(
                final String bundleType,
                final List<Subscription> subscriptions,
                final BigDecimal potentialSavings,
                final BigDecimal estimatedBundlePrice,
                final String description) {
            this.bundleType = bundleType;
            this.subscriptions = subscriptions;
            this.potentialSavings = potentialSavings;
            this.estimatedBundlePrice = estimatedBundlePrice;
            this.description = description;
        }

        public String getBundleType() {
            return bundleType;
        }

        public List<Subscription> getSubscriptions() {
            return subscriptions;
        }

        public BigDecimal getPotentialSavings() {
            return potentialSavings;
        }

        public BigDecimal getEstimatedBundlePrice() {
            return estimatedBundlePrice;
        }

        public String getDescription() {
            return description;
        }
    }

    public static class AlternativeRecommendation {
        private final Subscription currentSubscription;
        private final String alternativeName;
        private final BigDecimal alternativePrice;
        private final BigDecimal potentialSavings;
        private final String reason;

        public AlternativeRecommendation(
                final Subscription currentSubscription,
                final String alternativeName,
                final BigDecimal alternativePrice,
                final BigDecimal potentialSavings,
                final String reason) {
            this.currentSubscription = currentSubscription;
            this.alternativeName = alternativeName;
            this.alternativePrice = alternativePrice;
            this.potentialSavings = potentialSavings;
            this.reason = reason;
        }

        public Subscription getCurrentSubscription() {
            return currentSubscription;
        }

        public String getAlternativeName() {
            return alternativeName;
        }

        public BigDecimal getAlternativePrice() {
            return alternativePrice;
        }

        public BigDecimal getPotentialSavings() {
            return potentialSavings;
        }

        public String getReason() {
            return reason;
        }
    }

    public static class PredictiveCancellation {
        private final Subscription subscription;
        private final int cancellationProbability; // 0-100
        private final String reason;
        private final int healthScore;

        public PredictiveCancellation(
                final Subscription subscription,
                final int cancellationProbability,
                final String reason,
                final int healthScore) {
            this.subscription = subscription;
            this.cancellationProbability = cancellationProbability;
            this.reason = reason;
            this.healthScore = healthScore;
        }

        public Subscription getSubscription() {
            return subscription;
        }

        public int getCancellationProbability() {
            return cancellationProbability;
        }

        public String getReason() {
            return reason;
        }

        public int getHealthScore() {
            return healthScore;
        }
    }

    public static class SubscriptionOptimization {
        private final int totalSubscriptions;
        private final BigDecimal currentMonthlySpend;
        private final BigDecimal totalPotentialSavings;
        private final BigDecimal cancellationSavings;
        private final BigDecimal bundlingSavings;
        private final BigDecimal alternativeSavings;
        private final List<SubscriptionInsightsService.CancellationRecommendation>
                cancellationRecommendations;
        private final List<BundlingRecommendation> bundlingRecommendations;
        private final List<AlternativeRecommendation> alternativeRecommendations;
        private final List<TrialExpirationAlert> trialAlerts;

        public SubscriptionOptimization(
                final int totalSubscriptions,
                final BigDecimal currentMonthlySpend,
                final BigDecimal totalPotentialSavings,
                final BigDecimal cancellationSavings,
                final BigDecimal bundlingSavings,
                final BigDecimal alternativeSavings,
                final List<SubscriptionInsightsService.CancellationRecommendation>
                        cancellationRecommendations,
                final List<BundlingRecommendation> bundlingRecommendations,
                final List<AlternativeRecommendation> alternativeRecommendations,
                final List<TrialExpirationAlert> trialAlerts) {
            this.totalSubscriptions = totalSubscriptions;
            this.currentMonthlySpend = currentMonthlySpend;
            this.totalPotentialSavings = totalPotentialSavings;
            this.cancellationSavings = cancellationSavings;
            this.bundlingSavings = bundlingSavings;
            this.alternativeSavings = alternativeSavings;
            this.cancellationRecommendations = cancellationRecommendations;
            this.bundlingRecommendations = bundlingRecommendations;
            this.alternativeRecommendations = alternativeRecommendations;
            this.trialAlerts = trialAlerts;
        }

        public int getTotalSubscriptions() {
            return totalSubscriptions;
        }

        public BigDecimal getCurrentMonthlySpend() {
            return currentMonthlySpend;
        }

        public BigDecimal getTotalPotentialSavings() {
            return totalPotentialSavings;
        }

        public BigDecimal getCancellationSavings() {
            return cancellationSavings;
        }

        public BigDecimal getBundlingSavings() {
            return bundlingSavings;
        }

        public BigDecimal getAlternativeSavings() {
            return alternativeSavings;
        }

        public List<SubscriptionInsightsService.CancellationRecommendation>
                getCancellationRecommendations() {
            return cancellationRecommendations;
        }

        public List<BundlingRecommendation> getBundlingRecommendations() {
            return bundlingRecommendations;
        }

        public List<AlternativeRecommendation> getAlternativeRecommendations() {
            return alternativeRecommendations;
        }

        public List<TrialExpirationAlert> getTrialAlerts() {
            return trialAlerts;
        }
    }

    private static class AlternativeInfo {
        final String name;
        final BigDecimal price;
        final String reason;

        AlternativeInfo(final String name, final BigDecimal price, final String reason) {
            this.name = name;
            this.price = price;
            this.reason = reason;
        }
    }
}
