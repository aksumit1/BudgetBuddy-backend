package com.budgetbuddy.analytics;

import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.service.TransactionService;
import com.budgetbuddy.service.aws.CloudWatchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Analytics Service
 * Migrated to DynamoDB
 * Computes aggregated metrics to minimize database queries and data transfer
 */
@Service
public class AnalyticsService {

    private static final Logger logger = LoggerFactory.getLogger(AnalyticsService.class);
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

    private final TransactionRepository transactionRepository;
    private final TransactionService transactionService;
    private final CloudWatchService cloudWatchService;

    public AnalyticsService(final TransactionRepository transactionRepository, final TransactionService transactionService, final CloudWatchService cloudWatchService) {
        this.transactionRepository = transactionRepository;
        this.transactionService = transactionService;
        this.cloudWatchService = cloudWatchService;
    }

    /**
     * Get spending summary for a user (cached to reduce database load)
     */
    @Cacheable(value = "analytics", key = "#user.userId + '_spending_' + #startDate + '_' + #endDate")
    public SpendingSummary getSpendingSummary(final UserTable user, final LocalDate startDate, final LocalDate endDate) {
        // Use TransactionService which handles DynamoDB queries
        BigDecimal totalSpending = transactionService.getTotalSpending(user, startDate, endDate);
        List<TransactionTable> transactions = transactionService.getTransactionsInRange(user, startDate, endDate);
        long transactionCount = transactions.size();

        // Send metrics to CloudWatch
        cloudWatchService.putMetric("user.spending.total", totalSpending.doubleValue(), "Count");
        cloudWatchService.putMetric("user.transactions.count", transactionCount, "Count");

        return new SpendingSummary(
                totalSpending != null ? totalSpending : BigDecimal.ZERO,
                transactionCount
        );
    }

    /**
     * Get spending by category (cached)
     */
    @Cacheable(value = "analytics", key = "#user.userId + '_category_' + #startDate + '_' + #endDate")
    public Map<String, BigDecimal> getSpendingByCategory(UserTable user, LocalDate startDate, LocalDate endDate) {
        List<TransactionTable> transactions = transactionService.getTransactionsInRange(user, startDate, endDate);

        Map<String, BigDecimal> categorySpending = new HashMap<>();
        transactions.forEach(transaction -> {
            String category = transaction.getCategory();
            if (category != null) {
                BigDecimal amount = transaction.getAmount();
                if (amount != null) {
                    categorySpending.merge(category, amount, BigDecimal::add);
                }
            }
        });

        return categorySpending;
    }

    /**
     * Spending Summary DTO
     */
    public static class SpendingSummary {
        private final BigDecimal totalSpending;
        private final long transactionCount;

        public SpendingSummary(final BigDecimal totalSpending, final long transactionCount) {
            this.totalSpending = totalSpending;
            this.transactionCount = transactionCount;
        }

        public BigDecimal getTotalSpending() {
            return totalSpending;
        }

        public long getTransactionCount() {
            return transactionCount;
        }
    }
}
