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
    /**
     * AI-5: optional LLM personaliser for cancellation reasons. Null in
     * tests and when the feature flag is off; null path leaves every
     * recommendation's deterministic reason as authoritative.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.budgetbuddy.service.subscription.CancellationReasonAdvisor cancellationReasonAdvisor;
    /**
     * Percent-change threshold above which a price drift counts as a real
     * "price change" worth alerting on. Was hardcoded 5%. Externalized so
     * ops can tune (e.g. lower it for users who want every penny tracked,
     * raise it for users buried in noisy alerts). Per-user override would
     * be the next step.
     */
    private final java.math.BigDecimal priceChangeAlertThresholdPct;

    public SubscriptionInsightsService(
            final TransactionRepository transactionRepository,
            final SubscriptionService subscriptionService,
            @org.springframework.beans.factory.annotation.Value(
                    "${app.subscription.price-change-alert-threshold-pct:5.0}")
                    final double priceChangeAlertThresholdPct) {
        this.transactionRepository = transactionRepository;
        this.subscriptionService = subscriptionService;
        this.priceChangeAlertThresholdPct =
                java.math.BigDecimal.valueOf(priceChangeAlertThresholdPct);
    }

    /**
     * Short-lived per-user transaction snapshot. The iOS client typically
     * calls /insights/unused, /insights/price-changes, /insights/cancel-
     * recommendations back-to-back within a single screen render. Each one
     * used to do its own full findByUserId — now they all share this cache
     * window. TTL is deliberately small (30s) so corrections and new imports
     * surface quickly; bigger TTL would mean the user has to wait for a
     * refresh to see what they just edited.
     */
    private static final long TX_CACHE_TTL_MS = 30_000;
    /**
     * Hard ceiling on cached users — protects against unbounded growth
     * if the service is hit by many distinct user IDs in a short window
     * (load test, batch sync, an attacker probing). When the cap is
     * reached we evict expired entries first, then fall back to evicting
     * the oldest entry by expiry. The cache is an optimization, not a
     * correctness layer, so eviction is safe at any time.
     */
    private static final int TX_CACHE_MAX_USERS = 1_000;
    private final java.util.concurrent.ConcurrentMap<String, TxCacheEntry> txCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static final class TxCacheEntry {
        final long expiresAt;
        final List<TransactionTable> transactions;

        TxCacheEntry(final long expiresAt, final List<TransactionTable> transactions) {
            this.expiresAt = expiresAt;
            this.transactions = transactions;
        }
    }

    private List<TransactionTable> getCachedUserTransactions(final String userId) {
        final TxCacheEntry hit = txCache.get(userId);
        final long now = System.currentTimeMillis();
        if (hit != null && hit.expiresAt > now) {
            return hit.transactions;
        }
        final List<TransactionTable> fresh =
                transactionRepository.findByUserId(userId, 0, 10_000);
        ensureCacheCapacity();
        txCache.put(userId, new TxCacheEntry(now + TX_CACHE_TTL_MS, fresh));
        return fresh;
    }

    private void ensureCacheCapacity() {
        if (txCache.size() < TX_CACHE_MAX_USERS) {
            return;
        }
        final long now = System.currentTimeMillis();
        // First pass: drop everything expired (cheap and usually enough).
        txCache.entrySet().removeIf(e -> e.getValue().expiresAt <= now);
        if (txCache.size() < TX_CACHE_MAX_USERS) {
            return;
        }
        // Still over the cap → evict the entry expiring soonest. Linear
        // scan of <=1000 entries is fine; this only runs at the cap.
        txCache.entrySet().stream()
                .min(java.util.Map.Entry.comparingByValue(
                        java.util.Comparator.comparingLong(e -> e.expiresAt)))
                .ifPresent(e -> txCache.remove(e.getKey(), e.getValue()));
    }

    /**
     * Drop the cached transactions for one user. Call from any service
     * that mutates a user's transactions or subscriptions if the caller
     * needs the next insights request to reflect the mutation
     * immediately instead of waiting up to {@value #TX_CACHE_TTL_MS}ms.
     * Safe to call when no cache entry exists.
     */
    public void invalidateUser(final String userId) {
        if (userId != null) {
            txCache.remove(userId);
        }
    }

    /**
     * Detects potentially unused subscriptions A subscription is considered unused if: - No
     * transaction found in the last 2 billing cycles - Last payment was more than 2x the expected
     * frequency ago
     */
    public List<UnusedSubscriptionInsight> detectUnusedSubscriptions(final String userId) {
        LOGGER.info("Detecting unused subscriptions for user: {}", userId);

        final List<Subscription> subscriptions = subscriptionService.getActiveSubscriptions(userId);
        final List<TransactionTable> transactions = getCachedUserTransactions(userId);

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

                // "Unused" threshold: 1.5× cadence with a sane CEILING per
                // frequency. The prior `cadence * 2` rule meant an ANNUAL
                // sub had to be ~2 years late before flagging — the user
                // would have moved on by then. Per-frequency caps:
                //   DAILY      -> 14 days late
                //   WEEKLY     -> 21 days late
                //   BI_WEEKLY  -> 35 days late
                //   MONTHLY    -> 60 days late
                //   QUARTERLY  -> 150 days late
                //   SEMI_ANNUAL-> 250 days late
                //   ANNUAL     -> 90 days late (cap; long subs flag fast)
                final long unusedThresholdDays =
                        unusedThresholdDays(subscription.getFrequency());
                if (now.isAfter(expectedNextPayment.plusDays(unusedThresholdDays))) {
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

        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("Detected {} unused subscriptions for user: {}", insights.size(), userId);
        }

        // LIFECYCLE STATE + AUTO-DEACTIVATION. Runs over ALL active subs
        // (not just those producing an insight) so the state badge stays
        // accurate even before the "unused" insight criteria fires. The
        // insight criteria measures days PAST expected payment; lifecycle
        // measures days SINCE last payment — different definitions, both
        // useful. PRESUMED_CANCELLED also flips active=false.
        for (final Subscription s : subscriptions) {
            if (s == null || s.getLastPaymentDate() == null) continue;
            if (s.getLifecycleState() == Subscription.LifecycleState.USER_CANCELLED) {
                continue; // Explicit user action wins.
            }
            final long daysSince = ChronoUnit.DAYS.between(s.getLastPaymentDate(), LocalDate.now());
            final long baseThreshold = unusedThresholdDays(s.getFrequency());
            final Subscription.LifecycleState newState;
            if (daysSince >= baseThreshold * 2) {
                newState = Subscription.LifecycleState.PRESUMED_CANCELLED;
            } else if (daysSince >= (baseThreshold * 3) / 2) {
                newState = Subscription.LifecycleState.UNUSED_2_CYCLES;
            } else if (daysSince >= baseThreshold) {
                newState = Subscription.LifecycleState.UNUSED_1_CYCLE;
            } else {
                continue;
            }
            if (newState == s.getLifecycleState()
                    && (newState != Subscription.LifecycleState.PRESUMED_CANCELLED
                            || Boolean.FALSE.equals(s.getActive()))) {
                continue;
            }
            s.setLifecycleState(newState);
            if (newState == Subscription.LifecycleState.PRESUMED_CANCELLED) {
                s.setActive(false);
            }
            try {
                subscriptionService.saveSubscriptions(userId, List.of(s));
                if (LOGGER.isInfoEnabled()) {
                    LOGGER.info(
                            "Lifecycle state {} for subscription {} ({}) — {} days since last payment (base threshold {})",
                            newState, s.getMerchantName(), s.getSubscriptionId(),
                            daysSince, baseThreshold);
                }
            } catch (RuntimeException ex) {
                LOGGER.warn("Lifecycle persist failed for {}: {}",
                        s.getSubscriptionId(), ex.getMessage());
            }
        }

        return insights;
    }

    /**
     * Detects price changes in subscriptions Compares recent transaction amounts with subscription
     * amount
     */
    public List<PriceChangeAlert> detectPriceChanges(final String userId) {
        LOGGER.info("Detecting price changes for user: {}", userId);

        final List<Subscription> subscriptions = subscriptionService.getActiveSubscriptions(userId);
        final List<PriceChangeAlert> alerts = new ArrayList<>();

        // FAST PATH — read price changes off the structured priceHistory
        // field the consolidation already populated. Avoids re-fetching
        // and re-computing the avg-of-3 from raw transactions. If history
        // is empty (older subscription written before consolidation
        // existed, or no price change observed), fall through to the
        // legacy transaction-scan path below per subscription.
        final List<Subscription> needsLegacyScan = new ArrayList<>();
        for (final Subscription subscription : subscriptions) {
            final List<Subscription.PriceHistoryEntry> hist = subscription.getPriceHistory();
            if (hist == null || hist.isEmpty()) {
                needsLegacyScan.add(subscription);
                continue;
            }
            // The latest entry in history is the prior price; subscription.amount is current.
            final Subscription.PriceHistoryEntry last = hist.get(hist.size() - 1);
            final BigDecimal prior = last.getAmount() == null ? null : last.getAmount().abs();
            final BigDecimal current = subscription.getAmount() == null
                    ? null : subscription.getAmount().abs();
            if (prior == null || current == null || prior.signum() == 0) continue;
            final BigDecimal pct = current.subtract(prior)
                    .divide(prior, 4, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100"));
            if (pct.abs().compareTo(priceChangeAlertThresholdPct) > 0) {
                alerts.add(new PriceChangeAlert(
                        subscription, current, prior, pct,
                        last.getObservedAt() == null ? null : last.getObservedAt().toString()));
            }
        }

        // LEGACY PATH for subscriptions with no priceHistory. Only fetch
        // transactions if we actually have any subs that need it.
        if (!needsLegacyScan.isEmpty()) {
            final List<TransactionTable> transactions = getCachedUserTransactions(userId);
            for (final Subscription subscription : needsLegacyScan) {
                final List<TransactionTable> recentTransactions =
                        findSubscriptionTransactions(transactions, subscription).stream()
                                .sorted((a, b) -> {
                                    final LocalDate dateA = parseDate(a.getTransactionDate());
                                    final LocalDate dateB = parseDate(b.getTransactionDate());
                                    if (dateA == null || dateB == null) return 0;
                                    return dateB.compareTo(dateA);
                                })
                                .limit(3)
                                .toList();
                if (recentTransactions.size() < 2) continue;
                final BigDecimal averageRecentAmount = recentTransactions.stream()
                        .map(TransactionTable::getAmount)
                        .filter(amount -> amount != null)
                        .map(amount -> amount.abs())
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(new BigDecimal(recentTransactions.size()), 2, RoundingMode.HALF_UP);
                final BigDecimal subscriptionAmount = subscription.getAmount().abs();
                if (subscriptionAmount.signum() == 0) continue;
                final BigDecimal pct = averageRecentAmount.subtract(subscriptionAmount)
                        .divide(subscriptionAmount, 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100"));
                if (pct.abs().compareTo(priceChangeAlertThresholdPct) > 0) {
                    alerts.add(new PriceChangeAlert(
                            subscription, averageRecentAmount, subscriptionAmount, pct,
                            recentTransactions.get(0).getTransactionDate()));
                }
            }
        }

        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("Detected {} price changes for user: {} (fast-path={}, legacy-scan={})",
                    alerts.size(), userId,
                    subscriptions.size() - needsLegacyScan.size(),
                    needsLegacyScan.size());
        }
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
                                .toList();

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

        if (LOGGER.isInfoEnabled()) {
            LOGGER.info(
                    "Generated {} cancellation recommendations for user: {}",
                    recommendations.size(),
                    userId);
        }
        // AI-5: optionally personalise each reason via the LLM advisor.
        // Wrapped in try/catch — any failure leaves the deterministic
        // reason unchanged so users always see *something*.
        if (cancellationReasonAdvisor != null && !recommendations.isEmpty()) {
            try {
                cancellationReasonAdvisor.annotate(recommendations);
            } catch (Exception ignored) {
                // never let the personaliser break the response
            }
        }
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
                .toList();
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

    /**
     * Per-cadence "we'd expect another charge by now" cutoff. Caps avoid
     * the previous "annual sub has to be 2 years late" failure mode.
     */
    private long unusedThresholdDays(final Subscription.SubscriptionFrequency frequency) {
        if (frequency == null) return 60;
        switch (frequency) {
            case DAILY:        return 14;
            case WEEKLY:       return 21;
            case BI_WEEKLY:    return 35;
            case MONTHLY:      return 60;
            case QUARTERLY:    return 150;
            case SEMI_ANNUAL:  return 250;
            case ANNUAL:       return 90;
            default:           return 60;
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
        /**
         * AI-5: optional LLM-personalised rewrite of {@code reason}.
         * Populated by {@link CancellationReasonAdvisor} when the
         * Anthropic advisor is enabled. {@code reason} stays as the
         * deterministic source of truth for tests + accessibility.
         */
        private String humanMessage;

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

        public String getHumanMessage() {
            return humanMessage;
        }

        public void setHumanMessage(final String humanMessage) {
            this.humanMessage = humanMessage;
        }

        public enum Priority {
            LOW,
            MEDIUM,
            HIGH
        }
    }
}
