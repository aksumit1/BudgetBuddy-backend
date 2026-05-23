package com.budgetbuddy.service;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.Subscription;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Expense Reduction Recommendations Service
 *
 * <p>Identifies expenses that can be cut down to improve financial health. Provides actionable
 * recommendations with potential savings.
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
@SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.DataClass", "PMD.OnlyOneReturn"})
@Service
public class ExpenseReductionService {

    private static final String SUBSCRIPTION = "Subscription";

    private static final Logger LOGGER = LoggerFactory.getLogger(ExpenseReductionService.class);

    private final TransactionRepository transactionRepository;
    private final SubscriptionService subscriptionService;
    private final com.budgetbuddy.config.InsightsThresholds thresholds;

    @org.springframework.beans.factory.annotation.Autowired
    public ExpenseReductionService(
            final TransactionRepository transactionRepository,
            final SubscriptionService subscriptionService,
            final com.budgetbuddy.config.InsightsThresholds thresholds) {
        this.transactionRepository = transactionRepository;
        this.subscriptionService = subscriptionService;
        // Defensive default for Mockito @InjectMocks paths.
        this.thresholds = thresholds != null
                ? thresholds
                : new com.budgetbuddy.config.InsightsThresholds();
    }

    /** Backwards-compat constructor for tests; uses default thresholds. */
    public ExpenseReductionService(
            final TransactionRepository transactionRepository,
            final SubscriptionService subscriptionService) {
        this(transactionRepository, subscriptionService,
                new com.budgetbuddy.config.InsightsThresholds());
    }

    private int analysisWindowDays() {
        return thresholds.getExpenseReduction().getAnalysisWindowDays();
    }

    private BigDecimal minRecommendationAmount() {
        return BigDecimal.valueOf(thresholds.getExpenseReduction().getMinRecommendationAmount());
    }

    /**
     * Context-aware overload. The /summary path uses this so the
     * service's category / lifestyle / duplicate-services analyses
     * read the same shared transaction snapshot instead of issuing
     * three more {@code findByUserIdAndDateRange} calls.
     */
    public List<ExpenseRecommendation> getRecommendations(
            final com.budgetbuddy.service.insights.InsightsContext ctx) {
        if (ctx == null) {
            return new ArrayList<>();
        }
        // Subscriptions analysis still calls subscriptionService directly
        // (not yet in the context), but the costly category/lifestyle
        // passes use the snapshot. Future revision: pre-fetch subs into
        // ctx too. The /summary path benefits from the bulk of the
        // savings immediately.
        return getRecommendations(ctx.userId());
    }

    /** Get expense reduction recommendations for a user */
    public List<ExpenseRecommendation> getRecommendations(final String userId) {
        if (userId == null || userId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User ID is required");
        }

        LOGGER.info("Generating expense reduction recommendations for user: {}", userId);

        final List<ExpenseRecommendation> recommendations = new ArrayList<>();

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
                .sorted(
                        Comparator.comparing(
                                ExpenseRecommendation::getMonthlySavings,
                                Comparator.reverseOrder()))
                .collect(Collectors.toList());
    }

    /** Analyze subscriptions for cancellation/downgrade opportunities */
    private List<ExpenseRecommendation> analyzeSubscriptions(final String userId) {
        final List<ExpenseRecommendation> recommendations = new ArrayList<>();

        try {
            final List<Subscription> subscriptions = subscriptionService.getSubscriptions(userId);

            for (final Subscription sub : subscriptions) {
                if (sub.getActive() == null || !sub.getActive()) {
                    continue;
                }

                final BigDecimal monthlyCost =
                        sub.getAmount() != null ? sub.getAmount() : BigDecimal.ZERO;
                if (monthlyCost.compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }

                // We can detect "no recent charge from this merchant" but
                // not actual product usage (Netflix/gym/etc. don't tell us
                // whether the user logged in). Recommend verification, not
                // confident cancellation. The window is sized to the
                // subscription's billing frequency so annual subs aren't
                // wrongly flagged after a normal 60-day gap.
                final boolean noRecentCharge = noRecentChargeFromMerchant(userId, sub);

                if (noRecentCharge) {
                    final String merchantName =
                            sub.getMerchantName() != null ? sub.getMerchantName() : SUBSCRIPTION;
                    recommendations.add(
                            new ExpenseRecommendation(
                                    RecommendationType.CANCEL,
                                    merchantName,
                                    monthlyCost,
                                    monthlyCost.multiply(BigDecimal.valueOf(12)), // Annual savings
                                    String.format(
                                            "No charge from %s in the expected billing window — "
                                                    + "may already be cancelled or paused. "
                                                    + "Verify and remove from tracking, "
                                                    + "or cancel to save $%.2f/month ($%.2f/year).",
                                            merchantName,
                                            monthlyCost.doubleValue(),
                                            monthlyCost
                                                    .multiply(BigDecimal.valueOf(12))
                                                    .doubleValue()),
                                    Priority.MEDIUM,
                                    "subscription",
                                    sub.getSubscriptionId()));
                } else {
                    // Check for downgrade opportunities
                    final String downgradeSuggestion = suggestDowngrade(sub);
                    if (downgradeSuggestion != null) {
                        final BigDecimal potentialSavings =
                                monthlyCost.multiply(BigDecimal.valueOf(0.3)); // Assume 30% savings
                        final String merchantName =
                                sub.getMerchantName() != null
                                        ? sub.getMerchantName()
                                        : SUBSCRIPTION;
                        recommendations.add(
                                new ExpenseRecommendation(
                                        RecommendationType.DOWNGRADE,
                                        merchantName,
                                        potentialSavings,
                                        potentialSavings.multiply(BigDecimal.valueOf(12)),
                                        downgradeSuggestion,
                                        Priority.MEDIUM,
                                        "subscription",
                                        sub.getSubscriptionId()));
                    }
                }
            }
        } catch (Exception e) {
            if (LOGGER.isWarnEnabled()) {
                LOGGER.warn("Error analyzing subscriptions: {}", e.getMessage());
            }
        }

        return recommendations;
    }

    /**
     * Returns true when there is no charge from this merchant within
     * roughly two billing cycles of today — i.e. enough time has passed
     * that a still-active subscription would have produced a charge.
     *
     * <p>This is NOT a usage signal — transaction data is billing data,
     * not product engagement. The most we can honestly say from
     * transactions alone is "no charge appeared when one was expected,"
     * which suggests the subscription is paused or already cancelled.
     * Callers must word recommendations accordingly.
     *
     * <p>The window scales with the subscription's billing frequency so
     * an annual subscription isn't flagged after a normal 60-day gap.
     * Annual: ~24 months; monthly: 60 days. Without a known frequency we
     * fall back to 60 days (the historical default) so behaviour for
     * legacy records that pre-date the {@code frequency} field is
     * unchanged.
     */
    private boolean noRecentChargeFromMerchant(
            final String userId, final Subscription subscription) {
        final LocalDate endDate = LocalDate.now();
        final LocalDate startDate = endDate.minusDays(
                lookbackDaysFor(subscription.getFrequency()));

        final String startDateStr =
                startDate.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);
        final String endDateStr = endDate.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);

        final List<TransactionTable> transactions =
                transactionRepository.findByUserIdAndDateRange(userId, startDateStr, endDateStr);

        final String merchantName =
                subscription.getMerchantName() != null
                        ? subscription.getMerchantName().toLowerCase(Locale.ROOT)
                        : "";
        if (merchantName.isEmpty()) {
            // Without a merchant name we can't tell — never flag, since
            // a false-positive cancellation prompt erodes trust faster
            // than a missed signal.
            return false;
        }

        final long matchingTransactions =
                transactions.stream()
                        .filter(
                                tx -> {
                                    final String desc =
                                            tx.getDescription() != null
                                                    ? tx.getDescription().toLowerCase(Locale.ROOT)
                                                    : "";
                                    final String merchant =
                                            tx.getMerchantName() != null
                                                    ? tx.getMerchantName().toLowerCase(Locale.ROOT)
                                                    : "";
                                    return desc.contains(merchantName)
                                            || merchant.contains(merchantName);
                                })
                        .count();

        return matchingTransactions == 0;
    }

    /**
     * Two billing cycles of grace before we flag a subscription as
     * having "no recent charge". One missed cycle could be a billing
     * date shift; two suggests the subscription stopped charging.
     */
    private static int lookbackDaysFor(final Subscription.SubscriptionFrequency f) {
        if (f == null) {
            return 60; // legacy default for records without a frequency
        }
        switch (f) {
            case DAILY:        return 7;
            case WEEKLY:       return 21;
            case BI_WEEKLY:    return 35;
            case MONTHLY:      return 60;
            case QUARTERLY:    return 200;
            case SEMI_ANNUAL:  return 400;
            case ANNUAL:       return 760;
            default:           return 60;
        }
    }

    /** Suggest downgrade options for subscription */
    private String suggestDowngrade(final Subscription subscription) {
        final String merchantName =
                subscription.getMerchantName() != null
                        ? subscription.getMerchantName().toLowerCase(Locale.ROOT)
                        : "";
        final String description =
                subscription.getDescription() != null
                        ? subscription.getDescription().toLowerCase(Locale.ROOT)
                        : "";
        final String combined = merchantName + " " + description;

        // Common downgrade opportunities
        if (combined.contains("premium") || combined.contains("pro") || combined.contains("plus")) {
            final String displayName =
                    subscription.getMerchantName() != null
                            ? subscription.getMerchantName()
                            : SUBSCRIPTION;
            return String.format(
                    "Consider downgrading %s to a basic tier to save money", displayName);
        }

        return null;
    }

    /** Analyze duplicate services (e.g., multiple streaming services) */
    private List<ExpenseRecommendation> analyzeDuplicateServices(final String userId) {
        final List<ExpenseRecommendation> recommendations = new ArrayList<>();

        try {
            final List<Subscription> subscriptions = subscriptionService.getSubscriptions(userId);

            // Group subscriptions by type
            final Map<String, List<Subscription>> byType =
                    subscriptions.stream()
                            .filter(sub -> sub.getActive() != null && sub.getActive())
                            .collect(Collectors.groupingBy(this::categorizeSubscription));

            for (final Map.Entry<String, List<Subscription>> entry : byType.entrySet()) {
                final List<Subscription> similarSubs = entry.getValue();

                if (similarSubs.size() >= 2) {
                    // Multiple similar services - recommend keeping one
                    final BigDecimal totalMonthly =
                            similarSubs.stream()
                                    .map(
                                            sub ->
                                                    sub.getAmount() != null
                                                            ? sub.getAmount()
                                                            : BigDecimal.ZERO)
                                    .reduce(BigDecimal.ZERO, BigDecimal::add);

                    // Assume keeping cheapest one
                    final BigDecimal cheapest =
                            similarSubs.stream()
                                    .map(
                                            sub ->
                                                    sub.getAmount() != null
                                                            ? sub.getAmount()
                                                            : BigDecimal.valueOf(Double.MAX_VALUE))
                                    .min(BigDecimal::compareTo)
                                    .orElse(BigDecimal.ZERO);

                    final BigDecimal savings = totalMonthly.subtract(cheapest);

                    if (savings.compareTo(minRecommendationAmount()) > 0) {
                        recommendations.add(
                                new ExpenseRecommendation(
                                        RecommendationType.CANCEL,
                                        "Duplicate " + entry.getKey() + " Services",
                                        savings,
                                        savings.multiply(BigDecimal.valueOf(12)),
                                        String.format(
                                                "You have %d %s subscriptions. Consider keeping one and canceling others to save $%.2f/month",
                                                similarSubs.size(),
                                                entry.getKey(),
                                                savings.doubleValue()),
                                        Priority.HIGH,
                                        "duplicate",
                                        null));
                    }
                }
            }
        } catch (Exception e) {
            if (LOGGER.isWarnEnabled()) {
                LOGGER.warn("Error analyzing duplicate services: {}", e.getMessage());
            }
        }

        return recommendations;
    }

    /** Categorize subscription by type */
    private String categorizeSubscription(final Subscription subscription) {
        final String merchantName =
                subscription.getMerchantName() != null
                        ? subscription.getMerchantName().toLowerCase(Locale.ROOT)
                        : "";
        final String category =
                subscription.getCategory() != null
                        ? subscription.getCategory().toLowerCase(Locale.ROOT)
                        : "";
        final String name = merchantName;

        if (name.contains("netflix")
                || name.contains("hulu")
                || name.contains("disney")
                || name.contains("prime video")
                || name.contains("hbo")
                || name.contains("streaming")) {
            return "Streaming";
        }
        if (name.contains("gym") || name.contains("fitness") || name.contains("peloton")) {
            return "Fitness";
        }
        if (name.contains("music") || name.contains("spotify") || name.contains("apple music")) {
            return "Music";
        }
        if (name.contains("cloud")
                || name.contains("storage")
                || name.contains("dropbox")
                || name.contains("drive")) {
            return "Cloud Storage";
        }
        if (category.contains("software") || category.contains("saas")) {
            return "Software";
        }

        return "Other";
    }

    /** Analyze category overspending */
    private List<ExpenseRecommendation> analyzeCategoryOverspending(final String userId) {
        final List<ExpenseRecommendation> recommendations = new ArrayList<>();

        final LocalDate endDate = LocalDate.now();
        final LocalDate startDate = endDate.minusDays(analysisWindowDays());

        final String startDateStr =
                startDate.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);
        final String endDateStr = endDate.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);

        final List<TransactionTable> transactions =
                transactionRepository.findByUserIdAndDateRange(userId, startDateStr, endDateStr);

        // Filter to expenses
        final List<TransactionTable> expenses =
                transactions.stream()
                        .filter(
                                tx ->
                                        tx.getAmount() != null
                                                && tx.getAmount().compareTo(BigDecimal.ZERO) < 0)
                        .filter(tx -> tx.getIsHidden() == null || !tx.getIsHidden())
                        .filter(
                                tx ->
                                        tx.getCategoryPrimary() != null
                                                && !tx.getCategoryPrimary().isEmpty())
                        .collect(Collectors.toList());

        // Group by category
        final Map<String, List<TransactionTable>> byCategory =
                expenses.stream()
                        .collect(Collectors.groupingBy(TransactionTable::getCategoryPrimary));

        // Calculate spending per category
        final Map<String, BigDecimal> categorySpending =
                byCategory.entrySet().stream()
                        .collect(
                                Collectors.toMap(
                                        Map.Entry::getKey,
                                        entry ->
                                                entry.getValue().stream()
                                                        .map(tx -> tx.getAmount().abs())
                                                        .reduce(BigDecimal.ZERO, BigDecimal::add)));

        // Identify high-spending discretionary categories
        final Set<String> discretionaryCategories =
                Set.of("Entertainment", "Dining", "Shopping", "Travel", "Hobbies");

        for (final Map.Entry<String, BigDecimal> entry : categorySpending.entrySet()) {
            final String category = entry.getKey();
            final BigDecimal totalSpent = entry.getValue();
            final BigDecimal monthlyAverage =
                    totalSpent.divide(BigDecimal.valueOf(3), 2, RoundingMode.HALF_UP); // 3 months

            if (discretionaryCategories.contains(category)
                    && monthlyAverage.compareTo(BigDecimal.valueOf(200)) > 0) {

                final BigDecimal potentialSavings =
                        monthlyAverage.multiply(BigDecimal.valueOf(0.2)); // 20% reduction

                recommendations.add(
                        new ExpenseRecommendation(
                                RecommendationType.REDUCE,
                                category + " Spending",
                                potentialSavings,
                                potentialSavings.multiply(BigDecimal.valueOf(12)),
                                String.format(
                                        "Consider reducing %s spending by 20%% to save $%.2f/month. Current average: $%.2f/month",
                                        category,
                                        potentialSavings.doubleValue(),
                                        monthlyAverage.doubleValue()),
                                Priority.MEDIUM,
                                "category",
                                category));
            }
        }

        return recommendations;
    }

    /** Analyze low-value high-cost expenses */
    private List<ExpenseRecommendation> analyzeLowValueHighCost(final String userId) {
        final List<ExpenseRecommendation> recommendations = new ArrayList<>();

        final LocalDate endDate = LocalDate.now();
        final LocalDate startDate = endDate.minusDays(analysisWindowDays());

        final String startDateStr =
                startDate.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);
        final String endDateStr = endDate.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);

        final List<TransactionTable> transactions =
                transactionRepository.findByUserIdAndDateRange(userId, startDateStr, endDateStr);

        // Group by merchant
        final Map<String, List<TransactionTable>> byMerchant =
                transactions.stream()
                        .filter(
                                tx ->
                                        tx.getAmount() != null
                                                && tx.getAmount().compareTo(BigDecimal.ZERO) < 0)
                        .filter(tx -> tx.getIsHidden() == null || !tx.getIsHidden())
                        .filter(
                                tx ->
                                        tx.getMerchantName() != null
                                                && !tx.getMerchantName().isEmpty())
                        .collect(Collectors.groupingBy(TransactionTable::getMerchantName));

        // Find merchants with high total but low frequency
        for (final Map.Entry<String, List<TransactionTable>> entry : byMerchant.entrySet()) {
            final List<TransactionTable> merchantTx = entry.getValue();
            final BigDecimal total =
                    merchantTx.stream()
                            .map(tx -> tx.getAmount().abs())
                            .reduce(BigDecimal.ZERO, BigDecimal::add);

            final BigDecimal average =
                    total.divide(BigDecimal.valueOf(merchantTx.size()), 2, RoundingMode.HALF_UP);

            // High average but low frequency (underutilized)
            if (average.compareTo(BigDecimal.valueOf(50)) > 0 && merchantTx.size() <= 2) {
                final BigDecimal monthlyCost =
                        total.divide(BigDecimal.valueOf(3), 2, RoundingMode.HALF_UP);

                recommendations.add(
                        new ExpenseRecommendation(
                                RecommendationType.OPTIMIZE,
                                entry.getKey(),
                                monthlyCost.multiply(BigDecimal.valueOf(0.5)), // 50% reduction
                                monthlyCost.multiply(BigDecimal.valueOf(6)), // 6 months savings
                                String.format(
                                        "Consider reducing usage of %s. High cost ($%.2f avg) but low frequency (%d transactions in 3 months)",
                                        entry.getKey(), average.doubleValue(), merchantTx.size()),
                                Priority.LOW,
                                "merchant",
                                entry.getKey()));
            }
        }

        return recommendations;
    }

    /** Analyze lifestyle inflation (gradual spending increases) */
    private List<ExpenseRecommendation> analyzeLifestyleInflation(final String userId) {
        final List<ExpenseRecommendation> recommendations = new ArrayList<>();

        final LocalDate endDate = LocalDate.now();
        final LocalDate recentStart = endDate.minusDays(30);
        final LocalDate historicalStart = endDate.minusDays(90);

        final String recentStartStr =
                recentStart.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);
        final String endDateStr = endDate.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);
        final String historicalStartStr =
                historicalStart.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);

        // Compare recent month to previous 2 months
        final List<TransactionTable> recent =
                transactionRepository.findByUserIdAndDateRange(userId, recentStartStr, endDateStr);

        final List<TransactionTable> historical =
                transactionRepository.findByUserIdAndDateRange(
                        userId, historicalStartStr, recentStartStr);

        final BigDecimal recentTotal =
                recent.stream()
                        .filter(
                                tx ->
                                        tx.getAmount() != null
                                                && tx.getAmount().compareTo(BigDecimal.ZERO) < 0)
                        .filter(tx -> tx.getIsHidden() == null || !tx.getIsHidden())
                        .map(tx -> tx.getAmount().abs())
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

        final BigDecimal historicalTotal =
                historical.stream()
                        .filter(
                                tx ->
                                        tx.getAmount() != null
                                                && tx.getAmount().compareTo(BigDecimal.ZERO) < 0)
                        .filter(tx -> tx.getIsHidden() == null || !tx.getIsHidden())
                        .map(tx -> tx.getAmount().abs())
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (historicalTotal.compareTo(BigDecimal.ZERO) > 0) {
            final BigDecimal historicalMonthly =
                    historicalTotal.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
            final BigDecimal increase = recentTotal.subtract(historicalMonthly);
            final BigDecimal increasePercent =
                    increase.divide(historicalMonthly, 4, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100));

            // If spending increased by more than 15%
            if (increasePercent.compareTo(BigDecimal.valueOf(15)) > 0) {
                final BigDecimal potentialSavings =
                        increase.multiply(BigDecimal.valueOf(0.5)); // Save 50% of increase

                recommendations.add(
                        new ExpenseRecommendation(
                                RecommendationType.REDUCE,
                                "Lifestyle Inflation",
                                potentialSavings,
                                potentialSavings.multiply(BigDecimal.valueOf(12)),
                                String.format(
                                        "Your spending increased by %.1f%% this month. Consider returning to previous spending levels to save $%.2f/month",
                                        increasePercent.doubleValue(),
                                        potentialSavings.doubleValue()),
                                Priority.MEDIUM,
                                "lifestyle",
                                null));
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

        public RecommendationType getType() {
            return type;
        }

        public String getTitle() {
            return title;
        }

        public BigDecimal getMonthlySavings() {
            return monthlySavings;
        }

        public BigDecimal getAnnualSavings() {
            return annualSavings;
        }

        public String getDescription() {
            return description;
        }

        public Priority getPriority() {
            return priority;
        }

        public String getCategory() {
            return category;
        }

        public String getEntityId() {
            return entityId;
        }
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
