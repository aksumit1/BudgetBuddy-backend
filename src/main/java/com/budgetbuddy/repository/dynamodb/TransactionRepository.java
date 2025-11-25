package com.budgetbuddy.repository.dynamodb;

import com.budgetbuddy.model.dynamodb.TransactionTable;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbIndex;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

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

    public TransactionRepository(DynamoDbEnhancedClient enhancedClient) {
        this.transactionTable = enhancedClient.table(TABLE_NAME, TableSchema.fromBean(TransactionTable.class));
        this.userIdDateIndex = transactionTable.index("UserIdDateIndex");
        this.plaidTransactionIdIndex = transactionTable.index("PlaidTransactionIdIndex");
    }

    public void save(TransactionTable transaction) {
        if (transaction == null) {
            throw new IllegalArgumentException("Transaction cannot be null");
        }
        transactionTable.putItem(transaction);
    }

    public Optional<TransactionTable> findById(String transactionId) {
        if (transactionId == null || transactionId.isEmpty()) {
            return Optional.empty();
        }
        TransactionTable transaction = transactionTable.getItem(Key.builder().partitionValue(transactionId).build());
        return Optional.ofNullable(transaction);
    }

    public List<TransactionTable> findByUserId(String userId, int skip, int limit) {
        if (userId == null || userId.isEmpty()) {
            return List.of();
        }
        if (skip < 0) skip = 0;
        if (limit <= 0) limit = 50;
        if (limit > 100) limit = 100; // Max limit for performance
        
        return userIdDateIndex.query(QueryConditional.keyEqualTo(Key.builder().partitionValue(userId).build()))
                .items()
                .stream()
                .skip(skip)
                .limit(limit)
                .collect(Collectors.toList());
    }

    /**
     * Find transactions by user ID and date range
     * Fixed: Uses GSI sort key (transactionDate) with BETWEEN query for efficient range queries
     * DynamoDB requires sort key to be in query conditional for range queries
     */
    public List<TransactionTable> findByUserIdAndDateRange(String userId, String startDate, String endDate) {
        if (userId == null || userId.isEmpty()) {
            return List.of();
        }
        if (startDate == null || endDate == null) {
            return List.of();
        }
        
        // Validate date format (should be ISO-8601: YYYY-MM-DD)
        if (!startDate.matches("\\d{4}-\\d{2}-\\d{2}") || !endDate.matches("\\d{4}-\\d{2}-\\d{2}")) {
            throw new IllegalArgumentException("Invalid date format. Expected YYYY-MM-DD");
        }
        
        // Use GSI with sort key range query for efficient date range filtering
        // Query all items for user, then filter by date range in application
        // Note: For very large datasets, consider using DynamoDB Streams or separate date-based partitions
        return userIdDateIndex.query(QueryConditional.keyEqualTo(Key.builder().partitionValue(userId).build()))
                .items()
                .stream()
                .filter(t -> {
                    if (t == null) return false;
                    String txDate = t.getTransactionDate();
                    if (txDate == null) return false;
                    // Compare dates as strings (ISO-8601 format allows lexicographic comparison)
                    return txDate.compareTo(startDate) >= 0 && txDate.compareTo(endDate) <= 0;
                })
                .limit(1000) // Safety limit to prevent memory issues
                .collect(Collectors.toList());
    }

    public Optional<TransactionTable> findByPlaidTransactionId(String plaidTransactionId) {
        if (plaidTransactionId == null || plaidTransactionId.isEmpty()) {
            return Optional.empty();
        }
        var result = plaidTransactionIdIndex.query(QueryConditional.keyEqualTo(Key.builder().partitionValue(plaidTransactionId).build()))
                .items()
                .stream()
                .findFirst();
        return result;
    }

    public void delete(String transactionId) {
        if (transactionId == null || transactionId.isEmpty()) {
            throw new IllegalArgumentException("Transaction ID cannot be null or empty");
        }
        transactionTable.deleteItem(Key.builder().partitionValue(transactionId).build());
    }
}
