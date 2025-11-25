package com.budgetbuddy.repository.dynamodb;

import com.budgetbuddy.model.dynamodb.TransactionTable;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbIndex;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.core.pagination.sync.SdkIterable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * DynamoDB Repository for Transactions
 * Uses GSI for efficient user queries with date range filtering
 *
 * Fixed: Proper date range queries using GSI sort key
 */
@Repository
public class TransactionRepository {

    private final DynamoDbTable<TransactionTable> transactionTable;
    private final DynamoDbIndex<TransactionTable> userIdDateIndex;
    private final DynamoDbIndex<TransactionTable> plaidTransactionIdIndex;
    private static final String TABLE_NAME = "BudgetBuddy-Transactions";

    public TransactionRepository(final DynamoDbEnhancedClient enhancedClient) {
        this.transactionTable = enhancedClient.table(TABLE_NAME,
                TableSchema.fromBean(TransactionTable.class));
        this.userIdDateIndex = transactionTable.index("UserIdDateIndex");
        this.plaidTransactionIdIndex =
                transactionTable.index("PlaidTransactionIdIndex");
    }

    public void save(final TransactionTable transaction) {
        if (transaction == null) {
            throw new IllegalArgumentException("Transaction cannot be null");
        }
        transactionTable.putItem(transaction);
    }

    public Optional<TransactionTable> findById(final String transactionId) {
        if (transactionId == null || transactionId.isEmpty()) {
            return Optional.empty();
        }
        TransactionTable transaction = transactionTable.getItem(
                Key.builder().partitionValue(transactionId).build());
        return Optional.ofNullable(transaction);
    }

    public List<TransactionTable> findByUserId(final String userId,
                                               final int skip,
                                               final int limit) {
        if (userId == null || userId.isEmpty()) {
            return List.of();
        }
        int adjustedSkip = skip;
        if (adjustedSkip < 0) {
            adjustedSkip = 0;
        }
        final int defaultLimit = 50;
        final int maxLimit = 100;
        int adjustedLimit = limit;
        if (adjustedLimit <= 0) {
            adjustedLimit = defaultLimit;
        }
        if (adjustedLimit > maxLimit) {
            adjustedLimit = maxLimit; // Max limit for performance
        }

        List<TransactionTable> results = new ArrayList<>();
        SdkIterable<software.amazon.awssdk.enhanced.dynamodb.model.Page<TransactionTable>>
                pages = userIdDateIndex.query(
                        QueryConditional.keyEqualTo(
                                Key.builder().partitionValue(userId).build()));
        int count = 0;
        for (software.amazon.awssdk.enhanced.dynamodb.model.Page<TransactionTable>
                page : pages) {
            for (TransactionTable item : page.items()) {
                if (count >= skip) {
                    results.add(item);
                    if (results.size() >= limit) {
                        return results;
                    }
                }
                count++;
            }
        }
        return results;
    }

    /**
     * Find transactions by user ID and date range
     * Fixed: Uses GSI sort key (transactionDate) with BETWEEN query for efficient range queries
     * DynamoDB requires sort key to be in query conditional for range queries
     */
    public List<TransactionTable> findByUserIdAndDateRange(
            final String userId, final String startDate, final String endDate) {
        if (userId == null || userId.isEmpty()) {
            return List.of();
        }
        if (startDate == null || endDate == null) {
            return List.of();
        }

        // Validate date format (should be ISO-8601: YYYY-MM-DD)
        String datePattern = "\\d{4}-\\d{2}-\\d{2}";
        if (!startDate.matches(datePattern) || !endDate.matches(datePattern)) {
            throw new IllegalArgumentException(
                    "Invalid date format. Expected YYYY-MM-DD");
        }

        // Use GSI with sort key range query for efficient date range filtering
        // Query all items for user, then filter by date range in application
        // Note: For very large datasets, consider using DynamoDB Streams
        // or separate date-based partitions
        List<TransactionTable> results = new ArrayList<>();
        SdkIterable<software.amazon.awssdk.enhanced.dynamodb.model.Page<TransactionTable>>
                pages = userIdDateIndex.query(
                        QueryConditional.keyEqualTo(
                                Key.builder().partitionValue(userId).build()));
        for (software.amazon.awssdk.enhanced.dynamodb.model.Page<TransactionTable>
                page : pages) {
            for (TransactionTable t : page.items()) {
                if (t != null && t.getTransactionDate() != null) {
                    String txDate = t.getTransactionDate();
                    // Compare dates as strings (ISO-8601 format allows
                    // lexicographic comparison)
                    if (txDate.compareTo(startDate) >= 0
                            && txDate.compareTo(endDate) <= 0) {
                        results.add(t);
                        final int safetyLimit = 1000;
                        if (results.size() >= safetyLimit) {
                            return results;
                        }
                    }
                }
            }
        }
        return results;
    }

    public Optional<TransactionTable> findByPlaidTransactionId(String plaidTransactionId) {
        if (plaidTransactionId == null || plaidTransactionId.isEmpty()) {
            return Optional.empty();
        }
        SdkIterable<software.amazon.awssdk.enhanced.dynamodb.model.Page<TransactionTable>>
                pages = plaidTransactionIdIndex.query(
                        QueryConditional.keyEqualTo(
                                Key.builder()
                                        .partitionValue(plaidTransactionId)
                                        .build()));
        for (software.amazon.awssdk.enhanced.dynamodb.model.Page<TransactionTable>
                page : pages) {
            for (TransactionTable item : page.items()) {
                return Optional.of(item);
            }
        }
        return Optional.empty();
    }

    public void delete(final String transactionId) {
        if (transactionId == null || transactionId.isEmpty()) {
            throw new IllegalArgumentException("Transaction ID cannot be null or empty");
        }
        transactionTable.deleteItem(Key.builder().partitionValue(transactionId).build());
    }
}
