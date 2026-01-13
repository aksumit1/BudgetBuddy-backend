package com.budgetbuddy.service;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Transaction Anomaly Detection Service
 * 
 * Identifies suspicious, abnormal, or unusually high expenses that require user attention.
 * Uses statistical analysis, pattern recognition, and financial best practices.
 */
@Service
public class TransactionAnomalyService {

    private static final Logger logger = LoggerFactory.getLogger(TransactionAnomalyService.class);
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;
    
    // Statistical thresholds
    private static final double Z_SCORE_THRESHOLD = 2.5; // Standard deviations
    private static final double CATEGORY_SPIKE_MULTIPLIER = 3.0; // 3x historical average
    private static final double AMOUNT_THRESHOLD_MULTIPLIER = 5.0; // 5x typical transaction
    private static final int MIN_TRANSACTIONS_FOR_ANALYSIS = 10; // Need at least 10 transactions for meaningful analysis
    
    // Time windows for analysis
    private static final int ANALYSIS_WINDOW_DAYS = 90; // Analyze last 90 days
    private static final int HISTORICAL_WINDOW_DAYS = 180; // Compare to previous 180 days
    
    private final TransactionRepository transactionRepository;

    public TransactionAnomalyService(final TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
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

        logger.info("Detecting transaction anomalies for user: {}", userId);

        // Get transactions for analysis
        LocalDate endDate = LocalDate.now();
        LocalDate analysisStartDate = endDate.minusDays(ANALYSIS_WINDOW_DAYS);
        LocalDate historicalStartDate = endDate.minusDays(HISTORICAL_WINDOW_DAYS);

        String analysisStartStr = analysisStartDate.format(DATE_FORMATTER);
        String endDateStr = endDate.format(DATE_FORMATTER);
        String historicalStartStr = historicalStartDate.format(DATE_FORMATTER);
        
        List<TransactionTable> recentTransactions = transactionRepository
                .findByUserIdAndDateRange(userId, analysisStartStr, endDateStr);
        
        List<TransactionTable> historicalTransactions = transactionRepository
                .findByUserIdAndDateRange(userId, historicalStartStr, analysisStartStr);

        if (recentTransactions.size() < MIN_TRANSACTIONS_FOR_ANALYSIS) {
            logger.debug("Insufficient transactions for anomaly detection: {}", recentTransactions.size());
            return Collections.emptyList();
        }

        // Filter to expense transactions only (negative amounts)
        List<TransactionTable> recentExpenses = recentTransactions.stream()
                .filter(tx -> tx.getAmount() != null && tx.getAmount().compareTo(BigDecimal.ZERO) < 0)
                .filter(tx -> tx.getIsHidden() == null || !tx.getIsHidden()) // Exclude hidden transactions
                .collect(Collectors.toList());

        List<TransactionTable> historicalExpenses = historicalTransactions.stream()
                .filter(tx -> tx.getAmount() != null && tx.getAmount().compareTo(BigDecimal.ZERO) < 0)
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

        // Remove duplicates and sort by severity
        return anomalies.stream()
                .collect(Collectors.toMap(
                        TransactionAnomaly::getTransactionId,
                        anomaly -> anomaly,
                        (a1, a2) -> a1.getSeverity().ordinal() > a2.getSeverity().ordinal() ? a1 : a2
                ))
                .values()
                .stream()
                .sorted(Comparator
                        .comparing((TransactionAnomaly a) -> a.getSeverity().ordinal())
                        .reversed()
                        .thenComparing((TransactionAnomaly a) -> a.getAmount().abs(), Comparator.reverseOrder()))
                .collect(Collectors.toList());
    }

    /**
     * Detect statistical outliers using Z-score
     */
    private List<TransactionAnomaly> detectStatisticalOutliers(
            final List<TransactionTable> recentExpenses,
            final List<TransactionTable> historicalExpenses) {
        
        List<TransactionAnomaly> anomalies = new ArrayList<>();

        if (historicalExpenses.size() < MIN_TRANSACTIONS_FOR_ANALYSIS) {
            return anomalies;
        }

        // Calculate mean and standard deviation from historical data
        List<BigDecimal> amounts = historicalExpenses.stream()
                .map(tx -> tx.getAmount().abs())
                .collect(Collectors.toList());

        BigDecimal mean = calculateMean(amounts);
        BigDecimal stdDev = calculateStandardDeviation(amounts, mean);

        if (stdDev.compareTo(BigDecimal.ZERO) == 0) {
            return anomalies; // No variation, can't detect outliers
        }

        // Check recent transactions for outliers
        for (TransactionTable tx : recentExpenses) {
            BigDecimal amount = tx.getAmount().abs();
            BigDecimal zScore = amount.subtract(mean)
                    .divide(stdDev, 4, RoundingMode.HALF_UP);

            if (zScore.abs().compareTo(BigDecimal.valueOf(Z_SCORE_THRESHOLD)) > 0) {
                Severity severity = amount.compareTo(BigDecimal.valueOf(500)) > 0 
                        ? Severity.HIGH 
                        : Severity.MEDIUM;

                anomalies.add(new TransactionAnomaly(
                        tx.getTransactionId(),
                        tx.getAmount(),
                        tx.getDescription(),
                        tx.getMerchantName(),
                        tx.getTransactionDate(),
                        tx.getCategoryPrimary(),
                        AnomalyType.STATISTICAL_OUTLIER,
                        severity,
                        String.format("Transaction amount (%.2f) is %.2f standard deviations from your average (%.2f)",
                                amount.doubleValue(), zScore.doubleValue(), mean.doubleValue())
                ));
            }
        }

        return anomalies;
    }

    /**
     * Detect category spending spikes
     */
    private List<TransactionAnomaly> detectCategoryAnomalies(
            final List<TransactionTable> recentExpenses,
            final List<TransactionTable> historicalExpenses) {
        
        List<TransactionAnomaly> anomalies = new ArrayList<>();

        if (historicalExpenses.isEmpty()) {
            return anomalies;
        }

        // Calculate average spending per category in historical period
        Map<String, List<BigDecimal>> historicalByCategory = historicalExpenses.stream()
                .filter(tx -> tx.getCategoryPrimary() != null && !tx.getCategoryPrimary().isEmpty())
                .collect(Collectors.groupingBy(
                        TransactionTable::getCategoryPrimary,
                        Collectors.mapping(tx -> tx.getAmount().abs(), Collectors.toList())
                ));

        Map<String, BigDecimal> categoryAverages = historicalByCategory.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> calculateMean(entry.getValue())
                ));

        // Group recent expenses by category
        Map<String, List<TransactionTable>> recentByCategory = recentExpenses.stream()
                .filter(tx -> tx.getCategoryPrimary() != null && !tx.getCategoryPrimary().isEmpty())
                .collect(Collectors.groupingBy(TransactionTable::getCategoryPrimary));

        // Check for category spikes
        for (Map.Entry<String, List<TransactionTable>> entry : recentByCategory.entrySet()) {
            String category = entry.getKey();
            List<TransactionTable> categoryTransactions = entry.getValue();

            BigDecimal historicalAverage = categoryAverages.get(category);
            if (historicalAverage == null || historicalAverage.compareTo(BigDecimal.ZERO) == 0) {
                continue; // No historical data for this category
            }

            // Calculate total spending in this category recently
            BigDecimal recentTotal = categoryTransactions.stream()
                    .map(tx -> tx.getAmount().abs())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal recentAverage = recentTotal.divide(
                    BigDecimal.valueOf(categoryTransactions.size()), 2, RoundingMode.HALF_UP);

            // Check if recent average is significantly higher
            if (recentAverage.compareTo(historicalAverage.multiply(BigDecimal.valueOf(CATEGORY_SPIKE_MULTIPLIER))) > 0) {
                // Find the largest transaction in this category
                TransactionTable largestTx = categoryTransactions.stream()
                        .max(Comparator.comparing(tx -> tx.getAmount().abs()))
                        .orElse(null);

                if (largestTx != null) {
                    Severity severity = recentTotal.compareTo(BigDecimal.valueOf(500)) > 0 
                            ? Severity.HIGH 
                            : Severity.MEDIUM;

                    anomalies.add(new TransactionAnomaly(
                            largestTx.getTransactionId(),
                            largestTx.getAmount(),
                            largestTx.getDescription(),
                            largestTx.getMerchantName(),
                            largestTx.getTransactionDate(),
                            category,
                            AnomalyType.CATEGORY_SPIKE,
                            severity,
                            String.format("Spending in %s category is %.1fx higher than your historical average",
                                    category, recentAverage.divide(historicalAverage, 2, RoundingMode.HALF_UP).doubleValue())
                    ));
                }
            }
        }

        return anomalies;
    }

    /**
     * Detect first-time or rare merchant transactions with high amounts
     */
    private List<TransactionAnomaly> detectMerchantAnomalies(
            final List<TransactionTable> recentExpenses,
            final List<TransactionTable> historicalExpenses) {
        
        List<TransactionAnomaly> anomalies = new ArrayList<>();

        // Build set of known merchants from historical data
        Set<String> knownMerchants = historicalExpenses.stream()
                .map(tx -> normalizeMerchantName(tx.getMerchantName(), tx.getDescription()))
                .filter(Objects::nonNull)
                .filter(name -> !name.isEmpty())
                .collect(Collectors.toSet());

        // Check recent transactions for unknown merchants
        for (TransactionTable tx : recentExpenses) {
            String merchant = normalizeMerchantName(tx.getMerchantName(), tx.getDescription());
            if (merchant == null || merchant.isEmpty()) {
                continue;
            }

            boolean isFirstTime = !knownMerchants.contains(merchant);
            BigDecimal amount = tx.getAmount().abs();

            if (isFirstTime && amount.compareTo(BigDecimal.valueOf(100)) > 0) {
                Severity severity = amount.compareTo(BigDecimal.valueOf(500)) > 0 
                        ? Severity.HIGH 
                        : Severity.MEDIUM;

                anomalies.add(new TransactionAnomaly(
                        tx.getTransactionId(),
                        tx.getAmount(),
                        tx.getDescription(),
                        tx.getMerchantName(),
                        tx.getTransactionDate(),
                        tx.getCategoryPrimary(),
                        AnomalyType.FIRST_TIME_MERCHANT,
                        severity,
                        String.format("First-time transaction with %s for $%.2f", merchant, amount.doubleValue())
                ));
            }
        }

        return anomalies;
    }

    /**
     * Detect duplicate or similar transactions
     */
    private List<TransactionAnomaly> detectDuplicates(final List<TransactionTable> recentExpenses) {
        List<TransactionAnomaly> anomalies = new ArrayList<>();

        // Group by merchant and amount (within $5 tolerance)
        Map<String, List<TransactionTable>> byMerchantAndAmount = new HashMap<>();

        for (TransactionTable tx : recentExpenses) {
            String merchant = normalizeMerchantName(tx.getMerchantName(), tx.getDescription());
            if (merchant == null || merchant.isEmpty()) {
                continue;
            }

            BigDecimal amount = tx.getAmount().abs();
            // Round to nearest $5 for grouping
            BigDecimal roundedAmount = amount.divide(BigDecimal.valueOf(5), 0, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(5));
            
            String key = merchant.toLowerCase() + "|" + roundedAmount;

            byMerchantAndAmount.computeIfAbsent(key, k -> new ArrayList<>()).add(tx);
        }

        // Find groups with multiple transactions within 24 hours
        for (List<TransactionTable> group : byMerchantAndAmount.values()) {
            if (group.size() < 2) {
                continue;
            }

            // Sort by date
            group.sort(Comparator.comparing(TransactionTable::getTransactionDate));

            // Check for transactions within 24 hours
            for (int i = 0; i < group.size() - 1; i++) {
                TransactionTable tx1 = group.get(i);
                TransactionTable tx2 = group.get(i + 1);

                LocalDate date1 = LocalDate.parse(tx1.getTransactionDate(), DATE_FORMATTER);
                LocalDate date2 = LocalDate.parse(tx2.getTransactionDate(), DATE_FORMATTER);

                long daysBetween = java.time.temporal.ChronoUnit.DAYS.between(date1, date2);

                if (daysBetween <= 1) {
                    BigDecimal amount = tx1.getAmount().abs();
                    Severity severity = amount.compareTo(BigDecimal.valueOf(200)) > 0 
                            ? Severity.HIGH 
                            : Severity.MEDIUM;

                    anomalies.add(new TransactionAnomaly(
                            tx1.getTransactionId(),
                            tx1.getAmount(),
                            tx1.getDescription(),
                            tx1.getMerchantName(),
                            tx1.getTransactionDate(),
                            tx1.getCategoryPrimary(),
                            AnomalyType.DUPLICATE_TRANSACTION,
                            severity,
                            String.format("Similar transaction detected: $%.2f at %s within 24 hours",
                                    amount.doubleValue(), normalizeMerchantName(tx1.getMerchantName(), tx1.getDescription()))
                    ));
                }
            }
        }

        return anomalies;
    }

    /**
     * Detect transactions exceeding typical amount thresholds
     */
    private List<TransactionAnomaly> detectAmountThresholdAnomalies(
            final List<TransactionTable> recentExpenses,
            final List<TransactionTable> historicalExpenses) {
        
        List<TransactionAnomaly> anomalies = new ArrayList<>();

        if (historicalExpenses.isEmpty()) {
            return anomalies;
        }

        // Calculate typical transaction amount (median)
        List<BigDecimal> amounts = historicalExpenses.stream()
                .map(tx -> tx.getAmount().abs())
                .sorted()
                .collect(Collectors.toList());

        BigDecimal median = calculateMedian(amounts);
        BigDecimal threshold = median.multiply(BigDecimal.valueOf(AMOUNT_THRESHOLD_MULTIPLIER));

        // Check recent transactions
        for (TransactionTable tx : recentExpenses) {
            BigDecimal amount = tx.getAmount().abs();
            if (amount.compareTo(threshold) > 0) {
                Severity severity = amount.compareTo(BigDecimal.valueOf(1000)) > 0 
                        ? Severity.HIGH 
                        : Severity.MEDIUM;

                anomalies.add(new TransactionAnomaly(
                        tx.getTransactionId(),
                        tx.getAmount(),
                        tx.getDescription(),
                        tx.getMerchantName(),
                        tx.getTransactionDate(),
                        tx.getCategoryPrimary(),
                        AnomalyType.AMOUNT_THRESHOLD,
                        severity,
                        String.format("Transaction amount (%.2f) is %.1fx your typical transaction size (%.2f)",
                                amount.doubleValue(), 
                                amount.divide(median, 2, RoundingMode.HALF_UP).doubleValue(),
                                median.doubleValue())
                ));
            }
        }

        return anomalies;
    }

    // Helper methods

    private BigDecimal calculateMean(final List<BigDecimal> values) {
        if (values.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(values.size()), 2, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateStandardDeviation(final List<BigDecimal> values, final BigDecimal mean) {
        if (values.size() < 2) {
            return BigDecimal.ZERO;
        }
        BigDecimal variance = values.stream()
                .map(value -> value.subtract(mean).pow(2))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(values.size()), 4, RoundingMode.HALF_UP);
        return new BigDecimal(Math.sqrt(variance.doubleValue()));
    }

    private BigDecimal calculateMedian(final List<BigDecimal> values) {
        if (values.isEmpty()) {
            return BigDecimal.ZERO;
        }
        int size = values.size();
        if (size % 2 == 0) {
            return values.get(size / 2 - 1).add(values.get(size / 2))
                    .divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
        } else {
            return values.get(size / 2);
        }
    }

    private String normalizeMerchantName(final String merchantName, final String description) {
        if (merchantName != null && !merchantName.trim().isEmpty()) {
            return merchantName.trim();
        }
        if (description != null && !description.trim().isEmpty()) {
            // Extract merchant from description (first part before common separators)
            String desc = description.trim();
            String[] separators = {" - ", " | ", " @ ", " # "};
            for (String sep : separators) {
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

        public String getTransactionId() { return transactionId; }
        public BigDecimal getAmount() { return amount; }
        public String getDescription() { return description; }
        public String getMerchantName() { return merchantName; }
        public String getTransactionDate() { return transactionDate; }
        public String getCategory() { return category; }
        public AnomalyType getType() { return type; }
        public Severity getSeverity() { return severity; }
        public String getReason() { return reason; }
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
