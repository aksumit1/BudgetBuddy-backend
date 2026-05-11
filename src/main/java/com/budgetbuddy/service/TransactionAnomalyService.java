package com.budgetbuddy.service;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Transaction Anomaly Detection Service
 *
 * <p>Identifies suspicious, abnormal, or unusually high expenses that require user attention. Uses
 * statistical analysis, pattern recognition, and financial best practices.
 *
 * <p>Flow 7 / O1 + O12 — two feedback loops now feed this detector: - Per-user <em>sensitivity</em>
 * (loose / normal / strict) scales every threshold so users can dial in their own noise floor
 * instead of fighting hardcoded numbers. - Per-user <em>suppression</em> (dismissed-fingerprint
 * set) hides patterns the user has already marked "that's normal for me". Still computed, just
 * filtered out of the response.
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
@SuppressWarnings({"PMD.LawOfDemeter", "PMD.DataClass", "PMD.OnlyOneReturn"})
@Service
public class TransactionAnomalyService {

    private static final Logger LOGGER = LoggerFactory.getLogger(TransactionAnomalyService.class);
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

    // Base thresholds. Effective values get multiplied by sensitivity at runtime.
    private static final double Z_SCORE_THRESHOLD = 2.5;
    private static final double CATEGORY_SPIKE_MULTIPLIER = 3.0;
    private static final double AMOUNT_THRESHOLD_MULTIPLIER = 5.0;
    private static final int MIN_TRANSACTIONS_FOR_ANALYSIS = 10;

    private static final int ANALYSIS_WINDOW_DAYS = 90;
    private static final int HISTORICAL_WINDOW_DAYS = 180;

    private final TransactionRepository transactionRepository;

    // Feedback + user services injected as Optional to keep tests that build the
    // detector with only the transaction repo (the majority) working.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private AnomalyFeedbackService feedbackService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private UserService userService;

    /**
     * Per-detection-pass sensitivity multiplier. ThreadLocal keeps concurrent web requests from
     * stomping each other since Spring MVC handlers run on different threads. Reset at the end of
     * {@link #detectAnomalies}.
     */
    private final ThreadLocal<Double> activeSensitivity = ThreadLocal.withInitial(() -> 1.0);

    public TransactionAnomalyService(final TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    /** Sensitivity knob used to scale the three headline thresholds. */
    private enum Sensitivity {
        LOOSE(1.5), // higher thresholds → fewer anomalies
        NORMAL(1.0),
        STRICT(0.7); // lower thresholds → more anomalies
        final double multiplier;

        Sensitivity(final double m) {
            this.multiplier = m;
        }

        static Sensitivity parse(final String raw) {
            if (raw == null) {
                return NORMAL;
            }
            try {
                return Sensitivity.valueOf(raw.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return NORMAL;
            }
        }
    }

    /** Resolve the active sensitivity for {@code userId}. "normal" when nothing is set. */
    private Sensitivity sensitivityFor(final String userId) {
        if (userService == null) {
            return Sensitivity.NORMAL;
        }
        return userService
                .findById(userId)
                .map(u -> Sensitivity.parse(u.getAnomalySensitivity()))
                .orElse(Sensitivity.NORMAL);
    }

    /**
     * Detect anomalies for a user
     *
     * @param userId User ID
     * @return List of detected anomalies
     */
    public List<TransactionAnomaly> detectAnomalies(final String userId) {
        if (userId == null || userId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User ID is required");
        }

        // O12 + cross-flow audit fix: apply the user's chosen sensitivity for this
        // pass, then ALWAYS clear the ThreadLocal in `finally` below. Without the
        // clear, a thread reused by Spring MVC for a different user's request would
        // inherit the previous user's sensitivity — a state leak.
        final Sensitivity sens = sensitivityFor(userId);
        activeSensitivity.set(sens.multiplier);
        LOGGER.info("Detecting transaction anomalies for user: {} (sensitivity={})", userId, sens);

        try {
            return detectAnomaliesInternal(userId);
        } finally {
            activeSensitivity.remove();
        }
    }

    /** Kept private so callers can't bypass the ThreadLocal cleanup in the public method. */
    private List<TransactionAnomaly> detectAnomaliesInternal(final String userId) {

        // Get transactions for analysis
        final LocalDate endDate = LocalDate.now();
        final LocalDate analysisStartDate = endDate.minusDays(ANALYSIS_WINDOW_DAYS);
        final LocalDate historicalStartDate = endDate.minusDays(HISTORICAL_WINDOW_DAYS);

        final String analysisStartStr = analysisStartDate.format(DATE_FORMATTER);
        final String endDateStr = endDate.format(DATE_FORMATTER);
        final String historicalStartStr = historicalStartDate.format(DATE_FORMATTER);

        final List<TransactionTable> recentTransactions =
                transactionRepository.findByUserIdAndDateRange(
                        userId, analysisStartStr, endDateStr);

        final List<TransactionTable> historicalTransactions =
                transactionRepository.findByUserIdAndDateRange(
                        userId, historicalStartStr, analysisStartStr);

        if (recentTransactions.size() < MIN_TRANSACTIONS_FOR_ANALYSIS) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(
                        "Insufficient transactions for anomaly detection: {}",
                        recentTransactions.size());
            }
            return Collections.emptyList();
        }

        // Filter to expense transactions only (negative amounts). Cross-flow audit fix:
        // also drop soft-deleted rows (Flow 4 / O9) — previously they could still
        // trigger anomaly alerts after the user had explicitly deleted them.
        final List<TransactionTable> recentExpenses =
                recentTransactions.stream()
                        .filter(
                                tx ->
                                        tx.getAmount() != null
                                                && tx.getAmount().compareTo(BigDecimal.ZERO) < 0)
                        .filter(tx -> tx.getDeletedAt() == null)
                        .filter(tx -> tx.getIsHidden() == null || !tx.getIsHidden())
                        .collect(Collectors.toList());

        final List<TransactionTable> historicalExpenses =
                historicalTransactions.stream()
                        .filter(
                                tx ->
                                        tx.getAmount() != null
                                                && tx.getAmount().compareTo(BigDecimal.ZERO) < 0)
                        .filter(tx -> tx.getDeletedAt() == null)
                        .filter(tx -> tx.getIsHidden() == null || !tx.getIsHidden())
                        .collect(Collectors.toList());

        if (recentExpenses.isEmpty()) {
            return Collections.emptyList();
        }

        List<TransactionAnomaly> anomalies = new ArrayList<>();

        // 1. Statistical Outliers (Z-score analysis)
        anomalies.addAll(detectStatisticalOutliers(recentExpenses, historicalExpenses));

        // 2. Category Anomalies
        anomalies.addAll(detectCategoryAnomalies(recentExpenses, historicalExpenses));

        // 3. Merchant Anomalies
        anomalies.addAll(detectMerchantAnomalies(recentExpenses, historicalExpenses));

        // 4. Duplicate Detection
        anomalies.addAll(detectDuplicates(recentExpenses));

        // 5. Amount Threshold Anomalies
        anomalies.addAll(detectAmountThresholdAnomalies(recentExpenses, historicalExpenses));

        // O1: suppress anomalies whose fingerprint the user already dismissed.
        if (feedbackService != null) {
            final Set<String> suppressed = feedbackService.dismissedFingerprintsFor(userId);
            if (!suppressed.isEmpty()) {
                final int before = anomalies.size();
                anomalies =
                        anomalies.stream()
                                .filter(
                                        a ->
                                                !suppressed.contains(
                                                        AnomalyFeedbackService.fingerprintOf(
                                                                a.getMerchantName(),
                                                                a.getCategory(),
                                                                a.getAmount())))
                                .collect(Collectors.toList());
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug(
                            "Suppressed {}/{} anomalies via user dismiss list",
                            before - anomalies.size(),
                            before);
                }
            }
        }

        // Location-based anomalies (e.g. transaction from a country the user hasn't visited)
        // require geo data we don't yet collect: Plaid's transaction location fields are sparse
        // and the iOS client doesn't ship device-location telemetry to the backend. Until that
        // pipeline lands, this detector relies on the amount/merchant/category signals above.

        // Remove duplicates and sort by severity
        return anomalies.stream()
                .collect(
                        Collectors.toMap(
                                TransactionAnomaly::getTransactionId,
                                anomaly -> anomaly,
                                (a1, a2) ->
                                        a1.getSeverity().ordinal() > a2.getSeverity().ordinal()
                                                ? a1
                                                : a2))
                .values()
                .stream()
                .sorted(
                        Comparator.comparing((TransactionAnomaly a) -> a.getSeverity().ordinal())
                                .reversed()
                                .thenComparing(
                                        (TransactionAnomaly a) -> a.getAmount().abs(),
                                        Comparator.reverseOrder()))
                .collect(Collectors.toList());
    }

    /** Detect statistical outliers using Z-score */
    private List<TransactionAnomaly> detectStatisticalOutliers(
            final List<TransactionTable> recentExpenses,
            final List<TransactionTable> historicalExpenses) {

        final List<TransactionAnomaly> anomalies = new ArrayList<>();

        if (historicalExpenses.size() < MIN_TRANSACTIONS_FOR_ANALYSIS) {
            return anomalies;
        }

        // Calculate mean and standard deviation from historical data
        final List<BigDecimal> amounts =
                historicalExpenses.stream()
                        .map(tx -> tx.getAmount().abs())
                        .collect(Collectors.toList());

        final BigDecimal mean = calculateMean(amounts);
        final BigDecimal stdDev = calculateStandardDeviation(amounts, mean);

        if (stdDev.compareTo(BigDecimal.ZERO) == 0) {
            return anomalies; // No variation, can't detect outliers
        }

        // Check recent transactions for outliers
        for (final TransactionTable tx : recentExpenses) {
            final BigDecimal amount = tx.getAmount().abs();
            final BigDecimal zScore = amount.subtract(mean).divide(stdDev, 4, RoundingMode.HALF_UP);

            // O12: scale the z-score threshold by the user's sensitivity multiplier.
            final double scaledZ = Z_SCORE_THRESHOLD * activeSensitivity.get();
            if (zScore.abs().compareTo(BigDecimal.valueOf(scaledZ)) > 0) {
                final Severity severity =
                        amount.compareTo(BigDecimal.valueOf(500)) > 0
                                ? Severity.HIGH
                                : Severity.MEDIUM;

                anomalies.add(
                        new TransactionAnomaly(
                                tx.getTransactionId(),
                                tx.getAmount(),
                                tx.getDescription(),
                                tx.getMerchantName(),
                                tx.getTransactionDate(),
                                tx.getCategoryPrimary(),
                                AnomalyType.STATISTICAL_OUTLIER,
                                severity,
                                String.format(
                                        "Transaction amount (%.2f) is %.2f standard deviations from your average (%.2f)",
                                        amount.doubleValue(),
                                        zScore.doubleValue(),
                                        mean.doubleValue())));
            }
        }

        return anomalies;
    }

    /** Detect category spending spikes */
    private List<TransactionAnomaly> detectCategoryAnomalies(
            final List<TransactionTable> recentExpenses,
            final List<TransactionTable> historicalExpenses) {

        final List<TransactionAnomaly> anomalies = new ArrayList<>();

        if (historicalExpenses.isEmpty()) {
            return anomalies;
        }

        // Calculate average spending per category in historical period
        final Map<String, List<BigDecimal>> historicalByCategory =
                historicalExpenses.stream()
                        .filter(
                                tx ->
                                        tx.getCategoryPrimary() != null
                                                && !tx.getCategoryPrimary().isEmpty())
                        .collect(
                                Collectors.groupingBy(
                                        TransactionTable::getCategoryPrimary,
                                        Collectors.mapping(
                                                tx -> tx.getAmount().abs(), Collectors.toList())));

        final Map<String, BigDecimal> categoryAverages =
                historicalByCategory.entrySet().stream()
                        .collect(
                                Collectors.toMap(
                                        Map.Entry::getKey,
                                        entry -> calculateMean(entry.getValue())));

        // Group recent expenses by category
        final Map<String, List<TransactionTable>> recentByCategory =
                recentExpenses.stream()
                        .filter(
                                tx ->
                                        tx.getCategoryPrimary() != null
                                                && !tx.getCategoryPrimary().isEmpty())
                        .collect(Collectors.groupingBy(TransactionTable::getCategoryPrimary));

        // Check for category spikes
        for (final Map.Entry<String, List<TransactionTable>> entry : recentByCategory.entrySet()) {
            final String category = entry.getKey();
            final List<TransactionTable> categoryTransactions = entry.getValue();

            final BigDecimal historicalAverage = categoryAverages.get(category);
            if (historicalAverage == null || historicalAverage.compareTo(BigDecimal.ZERO) == 0) {
                continue; // No historical data for this category
            }

            // Calculate total spending in this category recently
            final BigDecimal recentTotal =
                    categoryTransactions.stream()
                            .map(tx -> tx.getAmount().abs())
                            .reduce(BigDecimal.ZERO, BigDecimal::add);

            final BigDecimal recentAverage =
                    recentTotal.divide(
                            BigDecimal.valueOf(categoryTransactions.size()),
                            2,
                            RoundingMode.HALF_UP);

            // Check if recent average is significantly higher
            final double scaledSpike = CATEGORY_SPIKE_MULTIPLIER * activeSensitivity.get();
            if (recentAverage.compareTo(historicalAverage.multiply(BigDecimal.valueOf(scaledSpike)))
                    > 0) {
                // Find the largest transaction in this category
                final TransactionTable largestTx =
                        categoryTransactions.stream()
                                .max(Comparator.comparing(tx -> tx.getAmount().abs()))
                                .orElse(null);

                if (largestTx != null) {
                    final Severity severity =
                            recentTotal.compareTo(BigDecimal.valueOf(500)) > 0
                                    ? Severity.HIGH
                                    : Severity.MEDIUM;

                    anomalies.add(
                            new TransactionAnomaly(
                                    largestTx.getTransactionId(),
                                    largestTx.getAmount(),
                                    largestTx.getDescription(),
                                    largestTx.getMerchantName(),
                                    largestTx.getTransactionDate(),
                                    category,
                                    AnomalyType.CATEGORY_SPIKE,
                                    severity,
                                    String.format(
                                            "Spending in %s category is %.1fx higher than your historical average",
                                            category,
                                            recentAverage
                                                    .divide(
                                                            historicalAverage,
                                                            2,
                                                            RoundingMode.HALF_UP)
                                                    .doubleValue())));
                }
            }
        }

        return anomalies;
    }

    /** Detect first-time or rare merchant transactions with high amounts */
    private List<TransactionAnomaly> detectMerchantAnomalies(
            final List<TransactionTable> recentExpenses,
            final List<TransactionTable> historicalExpenses) {

        final List<TransactionAnomaly> anomalies = new ArrayList<>();

        // Build set of known merchants from historical data
        final Set<String> knownMerchants =
                historicalExpenses.stream()
                        .map(tx -> normalizeMerchantName(tx.getMerchantName(), tx.getDescription()))
                        .filter(Objects::nonNull)
                        .filter(name -> !name.isEmpty())
                        .collect(Collectors.toSet());

        // Same-merchant velocity / size-spike flags (e.g. "Costco $50 → Costco $850" or "5x
        // Starbucks in an hour") are handled by the merchant-spend baseline detector earlier in
        // this class — see {@code detectMerchantSpendingAnomalies}. This method is the unknown-
        // merchant pass; keeping it focused avoids double-firing on the same transaction.

        // Check recent transactions for unknown merchants
        for (final TransactionTable tx : recentExpenses) {
            final String merchant =
                    normalizeMerchantName(tx.getMerchantName(), tx.getDescription());
            if (merchant == null || merchant.isEmpty()) {
                continue;
            }

            final boolean isFirstTime = !knownMerchants.contains(merchant);
            final BigDecimal amount = tx.getAmount().abs();

            if (isFirstTime && amount.compareTo(BigDecimal.valueOf(100)) > 0) {
                final Severity severity =
                        amount.compareTo(BigDecimal.valueOf(500)) > 0
                                ? Severity.HIGH
                                : Severity.MEDIUM;

                anomalies.add(
                        new TransactionAnomaly(
                                tx.getTransactionId(),
                                tx.getAmount(),
                                tx.getDescription(),
                                tx.getMerchantName(),
                                tx.getTransactionDate(),
                                tx.getCategoryPrimary(),
                                AnomalyType.FIRST_TIME_MERCHANT,
                                severity,
                                String.format(
                                        "First-time transaction with %s for $%.2f",
                                        merchant, amount.doubleValue())));
            }
        }

        return anomalies;
    }

    /** Detect duplicate or similar transactions */
    private List<TransactionAnomaly> detectDuplicates(final List<TransactionTable> recentExpenses) {
        final List<TransactionAnomaly> anomalies = new ArrayList<>();

        // Group by merchant and amount (within $5 tolerance)
        final Map<String, List<TransactionTable>> byMerchantAndAmount = new HashMap<>();

        for (final TransactionTable tx : recentExpenses) {
            final String merchant =
                    normalizeMerchantName(tx.getMerchantName(), tx.getDescription());
            if (merchant == null || merchant.isEmpty()) {
                continue;
            }

            final BigDecimal amount = tx.getAmount().abs();
            // Round to nearest $5 for grouping
            final BigDecimal roundedAmount =
                    amount.divide(BigDecimal.valueOf(5), 0, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(5));

            final String key = merchant.toLowerCase(Locale.ROOT) + "|" + roundedAmount;

            byMerchantAndAmount.computeIfAbsent(key, k -> new ArrayList<>()).add(tx);
        }

        // Find groups with multiple transactions within 24 hours
        for (final List<TransactionTable> group : byMerchantAndAmount.values()) {
            if (group.size() < 2) {
                continue;
            }

            // Sort by date
            group.sort(Comparator.comparing(TransactionTable::getTransactionDate));

            // Check for transactions within 24 hours
            for (int i = 0; i < group.size() - 1; i++) {
                final TransactionTable tx1 = group.get(i);
                final TransactionTable tx2 = group.get(i + 1);

                final LocalDate date1 = LocalDate.parse(tx1.getTransactionDate(), DATE_FORMATTER);
                final LocalDate date2 = LocalDate.parse(tx2.getTransactionDate(), DATE_FORMATTER);

                final long daysBetween = java.time.temporal.ChronoUnit.DAYS.between(date1, date2);

                if (daysBetween <= 1) {
                    final BigDecimal amount = tx1.getAmount().abs();
                    final Severity severity =
                            amount.compareTo(BigDecimal.valueOf(200)) > 0
                                    ? Severity.HIGH
                                    : Severity.MEDIUM;

                    anomalies.add(
                            new TransactionAnomaly(
                                    tx1.getTransactionId(),
                                    tx1.getAmount(),
                                    tx1.getDescription(),
                                    tx1.getMerchantName(),
                                    tx1.getTransactionDate(),
                                    tx1.getCategoryPrimary(),
                                    AnomalyType.DUPLICATE_TRANSACTION,
                                    severity,
                                    String.format(
                                            "Similar transaction detected: $%.2f at %s within 24 hours",
                                            amount.doubleValue(),
                                            normalizeMerchantName(
                                                    tx1.getMerchantName(), tx1.getDescription()))));
                }
            }
        }

        return anomalies;
    }

    /** Detect transactions exceeding typical amount thresholds */
    private List<TransactionAnomaly> detectAmountThresholdAnomalies(
            final List<TransactionTable> recentExpenses,
            final List<TransactionTable> historicalExpenses) {

        final List<TransactionAnomaly> anomalies = new ArrayList<>();

        if (historicalExpenses.isEmpty()) {
            return anomalies;
        }

        // Calculate typical transaction amount (median)
        final List<BigDecimal> amounts =
                historicalExpenses.stream()
                        .map(tx -> tx.getAmount().abs())
                        .sorted()
                        .collect(Collectors.toList());

        final BigDecimal median = calculateMedian(amounts);
        final double scaledAmount = AMOUNT_THRESHOLD_MULTIPLIER * activeSensitivity.get();
        final BigDecimal threshold = median.multiply(BigDecimal.valueOf(scaledAmount));

        // Check recent transactions
        for (final TransactionTable tx : recentExpenses) {
            final BigDecimal amount = tx.getAmount().abs();
            if (amount.compareTo(threshold) > 0) {
                final Severity severity =
                        amount.compareTo(BigDecimal.valueOf(1000)) > 0
                                ? Severity.HIGH
                                : Severity.MEDIUM;

                anomalies.add(
                        new TransactionAnomaly(
                                tx.getTransactionId(),
                                tx.getAmount(),
                                tx.getDescription(),
                                tx.getMerchantName(),
                                tx.getTransactionDate(),
                                tx.getCategoryPrimary(),
                                AnomalyType.AMOUNT_THRESHOLD,
                                severity,
                                String.format(
                                        "Transaction amount (%.2f) is %.1fx your typical transaction size (%.2f)",
                                        amount.doubleValue(),
                                        amount.divide(median, 2, RoundingMode.HALF_UP)
                                                .doubleValue(),
                                        median.doubleValue())));
            }
        }

        return anomalies;
    }

    // Helper methods

    private BigDecimal calculateMean(final List<BigDecimal> values) {
        if (values.isEmpty()) {
            return BigDecimal.ZERO;
        }
        final BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(values.size()), 2, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateStandardDeviation(
            final List<BigDecimal> values, final BigDecimal mean) {
        if (values.size() < 2) {
            return BigDecimal.ZERO;
        }
        final BigDecimal variance =
                values.stream()
                        .map(value -> value.subtract(mean).pow(2))
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(BigDecimal.valueOf(values.size()), 4, RoundingMode.HALF_UP);
        // Use BigDecimal.sqrt (Java 9+) to avoid the double round-trip; the
        // previous Math.sqrt(variance.doubleValue()) path introduced two
        // float-precision hops AND the `new BigDecimal(double)` tail —
        // enough to shift a z-score across the anomaly threshold.
        return variance.sqrt(new java.math.MathContext(16, RoundingMode.HALF_UP))
                .setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateMedian(final List<BigDecimal> values) {
        if (values.isEmpty()) {
            return BigDecimal.ZERO;
        }
        final int size = values.size();
        if (size % 2 == 0) {
            return values.get(size / 2 - 1)
                    .add(values.get(size / 2))
                    .divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
        } else {
            return values.get(size / 2);
        }
    }

    private String normalizeMerchantName(final String merchantName, final String description) {
        if (merchantName != null && !merchantName.isBlank()) {
            return merchantName.trim();
        }
        if (description != null && !description.isBlank()) {
            // Extract merchant from description (first part before common separators)
            final String desc = description.trim();
            final String[] separators = {" - ", " | ", " @ ", " # "};
            for (final String sep : separators) {
                if (desc.contains(sep)) {
                    return desc.substring(0, desc.indexOf(sep)).trim();
                }
            }
            return desc;
        }
        return null;
    }

    // Model classes

    public static class TransactionAnomaly {
        private final String transactionId;
        private final BigDecimal amount;
        private final String description;
        private final String merchantName;
        private final String transactionDate;
        private final String category;
        private final AnomalyType type;
        private final Severity severity;
        private final String reason;

        public TransactionAnomaly(
                final String transactionId,
                final BigDecimal amount,
                final String description,
                final String merchantName,
                final String transactionDate,
                final String category,
                final AnomalyType type,
                final Severity severity,
                final String reason) {
            this.transactionId = transactionId;
            this.amount = amount;
            this.description = description;
            this.merchantName = merchantName;
            this.transactionDate = transactionDate;
            this.category = category;
            this.type = type;
            this.severity = severity;
            this.reason = reason;
        }

        public String getTransactionId() {
            return transactionId;
        }

        public BigDecimal getAmount() {
            return amount;
        }

        public String getDescription() {
            return description;
        }

        public String getMerchantName() {
            return merchantName;
        }

        public String getTransactionDate() {
            return transactionDate;
        }

        public String getCategory() {
            return category;
        }

        public AnomalyType getType() {
            return type;
        }

        public Severity getSeverity() {
            return severity;
        }

        public String getReason() {
            return reason;
        }
    }

    public enum AnomalyType {
        STATISTICAL_OUTLIER,
        CATEGORY_SPIKE,
        FIRST_TIME_MERCHANT,
        DUPLICATE_TRANSACTION,
        AMOUNT_THRESHOLD
    }

    public enum Severity {
        LOW,
        MEDIUM,
        HIGH
    }
}
