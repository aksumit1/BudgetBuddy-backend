package com.budgetbuddy.repository.dynamodb;

import com.budgetbuddy.model.dynamodb.TransactionActionTable;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.core.pagination.sync.SdkIterable;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbIndex;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.Page;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * DynamoDB Repository for Transaction Actions
 * Uses GSI for efficient queries by transactionId and userId
 */
@Repository
public class TransactionActionRepository {

    private final DynamoDbTable<TransactionActionTable> actionTable;
    private final DynamoDbIndex<TransactionActionTable> transactionIdIndex;
    private final DynamoDbIndex<TransactionActionTable> userIdIndex;
    private final DynamoDbIndex<TransactionActionTable> reminderDateIndex;
    private static final String TABLE_NAME = "BudgetBuddy-TransactionActions";

    public TransactionActionRepository(final DynamoDbEnhancedClient enhancedClient) {
        this.actionTable = enhancedClient.table(TABLE_NAME,
                TableSchema.fromBean(TransactionActionTable.class));
        this.transactionIdIndex = actionTable.index("TransactionIdIndex");
        this.userIdIndex = actionTable.index("UserIdIndex");
        this.reminderDateIndex = actionTable.index("ReminderDateIndex");
    }

    public void save(final TransactionActionTable action) {
        if (action == null) {
            throw new IllegalArgumentException("Transaction action cannot be null");
        }
        actionTable.putItem(action);
    }

    public Optional<TransactionActionTable> findById(final String actionId) {
        if (actionId == null || actionId.isEmpty()) {
            return Optional.empty();
        }
        // Normalize ID to lowercase for case-insensitive lookup
        String normalizedId = com.budgetbuddy.util.IdGenerator.normalizeUUID(actionId);
        TransactionActionTable action = actionTable.getItem(
                Key.builder().partitionValue(normalizedId).build());
        // If not found with normalized ID, try original (for backward compatibility with mixed-case IDs)
        if (action == null && !normalizedId.equals(actionId)) {
            action = actionTable.getItem(
                    Key.builder().partitionValue(actionId).build());
        }
        return Optional.ofNullable(action);
    }

    /**
     * Find all actions for a transaction using GSI
     */
    public List<TransactionActionTable> findByTransactionId(final String transactionId) {
        if (transactionId == null || transactionId.isEmpty()) {
            return List.of();
        }
        // Normalize ID to lowercase for case-insensitive lookup
        String normalizedId = com.budgetbuddy.util.IdGenerator.normalizeUUID(transactionId);
        List<TransactionActionTable> results = new ArrayList<>();
        SdkIterable<Page<TransactionActionTable>> pages = transactionIdIndex.query(
                QueryConditional.keyEqualTo(Key.builder().partitionValue(normalizedId).build())
        );
        for (Page<TransactionActionTable> page : pages) {
            results.addAll(page.items());
        }
        // If no results with normalized ID, try original (for backward compatibility with mixed-case IDs)
        if (results.isEmpty() && !normalizedId.equals(transactionId)) {
            SdkIterable<Page<TransactionActionTable>> originalPages = transactionIdIndex.query(
                    QueryConditional.keyEqualTo(Key.builder().partitionValue(transactionId).build())
            );
            for (Page<TransactionActionTable> page : originalPages) {
                results.addAll(page.items());
            }
        }
        return results;
    }

    /**
     * Find all actions for a user using GSI
     */
    public List<TransactionActionTable> findByUserId(final String userId) {
        if (userId == null || userId.isEmpty()) {
            return List.of();
        }
        List<TransactionActionTable> results = new ArrayList<>();
        SdkIterable<Page<TransactionActionTable>> pages = userIdIndex.query(
                QueryConditional.keyEqualTo(Key.builder().partitionValue(userId).build())
        );
        for (Page<TransactionActionTable> page : pages) {
            results.addAll(page.items());
        }
        return results;
    }

    public void delete(final String actionId) {
        if (actionId == null || actionId.isEmpty()) {
            throw new IllegalArgumentException("Action ID cannot be null or empty");
        }
        actionTable.deleteItem(Key.builder().partitionValue(actionId).build());
    }
    
    /**
     * Find actions with reminder dates in the specified range using GSI
     * Queries across all date partitions that fall within the range
     * 
     * @param startDate Start of date range (ISO datetime string)
     * @param endDate End of date range (ISO datetime string)
     * @return List of actions with reminder dates in the range
     */
    public List<TransactionActionTable> findByReminderDateRange(final String startDate, final String endDate) {
        if (startDate == null || endDate == null) {
            return List.of();
        }
        
        List<TransactionActionTable> results = new ArrayList<>();
        
        try {
            // Extract date partitions from start and end dates
            String startDatePartition = extractDatePartition(startDate);
            String endDatePartition = extractDatePartition(endDate);
            
            // Query each date partition in the range
            java.time.LocalDate start = java.time.LocalDate.parse(startDatePartition);
            java.time.LocalDate end = java.time.LocalDate.parse(endDatePartition);
            
            java.time.LocalDate current = start;
            while (!current.isAfter(end)) {
                String partitionKey = current.toString(); // YYYY-MM-DD format
                
                // Query this partition with date range filter on sort key
                SdkIterable<Page<TransactionActionTable>> pages = reminderDateIndex.query(
                        QueryConditional.keyEqualTo(
                                Key.builder()
                                        .partitionValue(partitionKey)
                                        .build()
                        )
                );
                
                for (Page<TransactionActionTable> page : pages) {
                    for (TransactionActionTable action : page.items()) {
                        // Filter by actual reminderDate range (sort key)
                        if (action.getReminderDate() != null && 
                            action.getReminderDate().compareTo(startDate) >= 0 &&
                            action.getReminderDate().compareTo(endDate) <= 0) {
                            results.add(action);
                        }
                    }
                }
                
                current = current.plusDays(1);
            }
        } catch (Exception e) {
            // Log error but return empty list
            org.slf4j.LoggerFactory.getLogger(TransactionActionRepository.class)
                    .warn("Error querying reminder date range: {}", e.getMessage());
        }
        
        return results;
    }
    
    /**
     * Extract date partition (YYYY-MM-DD) from ISO datetime string
     */
    private String extractDatePartition(final String dateTimeStr) {
        if (dateTimeStr == null || dateTimeStr.isEmpty()) {
            return null;
        }
        try {
            if (dateTimeStr.contains("T")) {
                return dateTimeStr.substring(0, dateTimeStr.indexOf("T"));
            }
            // Already a date string (YYYY-MM-DD)
            return dateTimeStr.substring(0, Math.min(10, dateTimeStr.length()));
        } catch (Exception e) {
            return null;
        }
    }
}

