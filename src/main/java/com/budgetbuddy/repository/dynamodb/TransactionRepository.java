package com.budgetbuddy.repository.dynamodb;

import com.budgetbuddy.model.dynamodb.TransactionTable;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbIndex;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchGetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.KeysAndAttributes;
import software.amazon.awssdk.services.dynamodb.model.PutRequest;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;
import software.amazon.awssdk.core.pagination.sync.SdkIterable;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
    private final DynamoDbIndex<TransactionTable> userIdUpdatedAtIndex;
    private final DynamoDbIndex<TransactionTable> userIdGoalIdIndex;
    private final DynamoDbClient dynamoDbClient;
    private final String tableName;

    public TransactionRepository(
            final DynamoDbEnhancedClient enhancedClient,
            final DynamoDbClient dynamoDbClient,
            @org.springframework.beans.factory.annotation.Value("${app.aws.dynamodb.table-prefix:BudgetBuddy}") final String tablePrefix) {
        this.tableName = tablePrefix + "-Transactions";
        this.transactionTable = enhancedClient.table(this.tableName,
                TableSchema.fromBean(TransactionTable.class));
        this.userIdDateIndex = transactionTable.index("UserIdDateIndex");
        this.plaidTransactionIdIndex = transactionTable.index("PlaidTransactionIdIndex");
        this.userIdUpdatedAtIndex = transactionTable.index("UserIdUpdatedAtIndex");
        this.userIdGoalIdIndex = transactionTable.index("UserIdGoalIdIndex");
        this.dynamoDbClient = dynamoDbClient;
    }

    @CacheEvict(value = "transactions", allEntries = true)
    public void save(final TransactionTable transaction) {
        if (transaction == null) {
            throw new IllegalArgumentException("Transaction cannot be null");
        }
        // CRITICAL FIX: Add retry logic for DynamoDB throttling and transient errors
        // This improves data durability during transient failures
        com.budgetbuddy.util.RetryHelper.executeDynamoDbWithRetry(() -> {
            transactionTable.putItem(transaction);
            return null;
        });
    }

    public Optional<TransactionTable> findById(final String transactionId) {
        if (transactionId == null || transactionId.isEmpty()) {
            return Optional.empty();
        }
        // Normalize ID to lowercase for case-insensitive lookup
        String normalizedId = com.budgetbuddy.util.IdGenerator.normalizeUUID(transactionId);
        TransactionTable transaction = transactionTable.getItem(
                Key.builder().partitionValue(normalizedId).build());
        return Optional.ofNullable(transaction);
    }

    @Cacheable(value = "transactions", key = "'user:' + #userId + ':skip:' + #skip + ':limit:' + #limit", unless = "#result == null || #result.isEmpty()")
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
        // CRITICAL: Deduplicate by both transactionId and plaidTransactionId
        // Use Set to track seen transactionIds and plaidTransactionIds to prevent duplicates
        // CRITICAL FIX: Deduplication must happen BEFORE skip check to ensure all items are tracked
        java.util.Set<String> seenTransactionIds = new java.util.HashSet<>();
        java.util.Set<String> seenPlaidTransactionIds = new java.util.HashSet<>();
        int duplicateCount = 0;
        int uniqueItemCount = 0; // Track unique items (after deduplication) for skip logic
        
        try {
            SdkIterable<software.amazon.awssdk.enhanced.dynamodb.model.Page<TransactionTable>>
                    pages = userIdDateIndex.query(
                            QueryConditional.keyEqualTo(
                                    Key.builder().partitionValue(userId).build()));
            for (software.amazon.awssdk.enhanced.dynamodb.model.Page<TransactionTable>
                    page : pages) {
                for (TransactionTable item : page.items()) {
                    // CRITICAL FIX: Check for duplicates FIRST, before skip logic
                    // This ensures all items are tracked in seen sets, preventing duplicates
                    // from appearing after skip point
                    String transactionId = item.getTransactionId();
                    String plaidTransactionId = item.getPlaidTransactionId();
                    boolean isDuplicate = false;
                    
                    // Check for duplicates by transactionId
                    if (transactionId != null && seenTransactionIds.contains(transactionId)) {
                        duplicateCount++;
                        org.slf4j.LoggerFactory.getLogger(TransactionRepository.class)
                                .warn("Duplicate transaction detected by transactionId and filtered: transactionId={}, plaidTransactionId={}, description={}", 
                                        transactionId, plaidTransactionId, item.getDescription());
                        isDuplicate = true;
                    }
                    
                    // Check for duplicates by plaidTransactionId (if present)
                    if (!isDuplicate && plaidTransactionId != null && !plaidTransactionId.isEmpty()) {
                        if (seenPlaidTransactionIds.contains(plaidTransactionId)) {
                            duplicateCount++;
                            org.slf4j.LoggerFactory.getLogger(TransactionRepository.class)
                                    .warn("Duplicate transaction detected by plaidTransactionId and filtered: transactionId={}, plaidTransactionId={}, description={}", 
                                            transactionId, plaidTransactionId, item.getDescription());
                            isDuplicate = true;
                        }
                    }
                    
                    // If duplicate, skip to next item (but continue tracking in seen sets if needed)
                    if (isDuplicate) {
                        continue;
                    }
                    
                    // Transaction is unique - track it in seen sets
                    if (transactionId != null) {
                        seenTransactionIds.add(transactionId);
                    }
                    if (plaidTransactionId != null && !plaidTransactionId.isEmpty()) {
                        seenPlaidTransactionIds.add(plaidTransactionId);
                    }
                    
                    // Now apply skip logic after deduplication
                    if (uniqueItemCount >= adjustedSkip) {
                        // Add to results (this is after skip point and unique)
                        results.add(item);
                        if (results.size() >= adjustedLimit) {
                            if (duplicateCount > 0) {
                                org.slf4j.LoggerFactory.getLogger(TransactionRepository.class)
                                        .info("findByUserId({}): Filtered {} duplicate transactions, returning {} unique transactions",
                                                userId, duplicateCount, results.size());
                            }
                            return results;
                        }
                    }
                    uniqueItemCount++; // Count unique items for skip logic
                }
            }
        } catch (ResourceNotFoundException e) {
            // GSI not available - fallback to scan and filter in memory
            org.slf4j.LoggerFactory.getLogger(TransactionRepository.class)
                    .warn("UserIdDateIndex GSI not found for userId {}. Falling back to scan and filtering in memory.", userId);
            try {
                // Fallback: scan table and filter by userId
                SdkIterable<software.amazon.awssdk.enhanced.dynamodb.model.Page<TransactionTable>> scanPages = transactionTable.scan();
                for (software.amazon.awssdk.enhanced.dynamodb.model.Page<TransactionTable> page : scanPages) {
                    for (TransactionTable item : page.items()) {
                        if (item == null || !userId.equals(item.getUserId())) {
                            continue;
                        }
                        
                        // Deduplicate
                        String transactionId = item.getTransactionId();
                        String plaidTransactionId = item.getPlaidTransactionId();
                        boolean isDuplicate = false;
                        
                        if (transactionId != null && seenTransactionIds.contains(transactionId)) {
                            duplicateCount++;
                            isDuplicate = true;
                        }
                        if (!isDuplicate && plaidTransactionId != null && !plaidTransactionId.isEmpty() &&
                            seenPlaidTransactionIds.contains(plaidTransactionId)) {
                            duplicateCount++;
                            isDuplicate = true;
                        }
                        
                        if (!isDuplicate) {
                            if (transactionId != null) {
                                seenTransactionIds.add(transactionId);
                            }
                            if (plaidTransactionId != null && !plaidTransactionId.isEmpty()) {
                                seenPlaidTransactionIds.add(plaidTransactionId);
                            }
                            
                            // Apply skip logic
                            if (uniqueItemCount >= adjustedSkip) {
                                results.add(item);
                                if (results.size() >= adjustedLimit) {
                                    if (duplicateCount > 0) {
                                        org.slf4j.LoggerFactory.getLogger(TransactionRepository.class)
                                                .info("findByUserId({}): Filtered {} duplicate transactions, returning {} unique transactions (fallback scan)",
                                                        userId, duplicateCount, results.size());
                                    }
                                    return results;
                                }
                            }
                            uniqueItemCount++;
                        }
                    }
                }
            } catch (Exception fallbackException) {
                org.slf4j.LoggerFactory.getLogger(TransactionRepository.class)
                        .error("Error in fallback scan for userId {}: {}", userId, fallbackException.getMessage(), fallbackException);
                // Return empty list if fallback also fails
                return List.of();
            }
        } catch (RuntimeException e) {
            // Check if the RuntimeException wraps a ResourceNotFoundException
            Throwable cause = e.getCause();
            String errorMessage = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
            if (cause instanceof ResourceNotFoundException ||
                errorMessage.contains("index not found") ||
                errorMessage.contains("resource not found")) {
                // GSI not available - fallback to scan and filter in memory
                org.slf4j.LoggerFactory.getLogger(TransactionRepository.class)
                        .warn("UserIdDateIndex GSI not found for userId {} (wrapped exception). Falling back to scan and filtering in memory.", userId);
                try {
                    // Fallback: scan table and filter by userId
                    SdkIterable<software.amazon.awssdk.enhanced.dynamodb.model.Page<TransactionTable>> scanPages = transactionTable.scan();
                    for (software.amazon.awssdk.enhanced.dynamodb.model.Page<TransactionTable> page : scanPages) {
                        for (TransactionTable item : page.items()) {
                            if (item == null || !userId.equals(item.getUserId())) {
                                continue;
                            }
                            
                            // Deduplicate
                            String transactionId = item.getTransactionId();
                            String plaidTransactionId = item.getPlaidTransactionId();
                            boolean isDuplicate = false;
                            
                            if (transactionId != null && seenTransactionIds.contains(transactionId)) {
                                duplicateCount++;
                                isDuplicate = true;
                            }
                            if (!isDuplicate && plaidTransactionId != null && !plaidTransactionId.isEmpty() &&
                                seenPlaidTransactionIds.contains(plaidTransactionId)) {
                                duplicateCount++;
                                isDuplicate = true;
                            }
                            
                            if (!isDuplicate) {
                                if (transactionId != null) {
                                    seenTransactionIds.add(transactionId);
                                }
                                if (plaidTransactionId != null && !plaidTransactionId.isEmpty()) {
                                    seenPlaidTransactionIds.add(plaidTransactionId);
                                }
                                
                                // Apply skip logic
                                if (uniqueItemCount >= adjustedSkip) {
                                    results.add(item);
                                    if (results.size() >= adjustedLimit) {
                                        if (duplicateCount > 0) {
                                            org.slf4j.LoggerFactory.getLogger(TransactionRepository.class)
                                                    .info("findByUserId({}): Filtered {} duplicate transactions, returning {} unique transactions (fallback scan)",
                                                            userId, duplicateCount, results.size());
                                        }
                                        return results;
                                    }
                                }
                                uniqueItemCount++;
                            }
                        }
                    }
                } catch (Exception fallbackException) {
                    org.slf4j.LoggerFactory.getLogger(TransactionRepository.class)
                            .error("Error in fallback scan for userId {}: {}", userId, fallbackException.getMessage(), fallbackException);
                    // Return empty list if fallback also fails
                    return List.of();
                }
            } else {
                // Re-throw if it's not a ResourceNotFoundException
                throw e;
            }
        }
        
        if (duplicateCount > 0) {
            org.slf4j.LoggerFactory.getLogger(TransactionRepository.class)
                    .info("findByUserId({}): Filtered {} duplicate transactions, returning {} unique transactions",
                            userId, duplicateCount, results.size());
        }
        return results;
    }

    /**
     * Find all transactions for a user that are assigned to a specific goal
     * Uses UserIdGoalIdIndex GSI for efficient querying
     */
    public List<TransactionTable> findByUserIdAndGoalId(final String userId, final String goalId) {
        if (userId == null || userId.isEmpty()) {
            return List.of();
        }
        if (goalId == null || goalId.isEmpty()) {
            return List.of();
        }

        try {
            List<TransactionTable> results = new ArrayList<>();
            QueryConditional queryConditional = QueryConditional
                    .keyEqualTo(Key.builder()
                            .partitionValue(userId)
                            .sortValue(goalId)
                            .build());

            SdkIterable<software.amazon.awssdk.enhanced.dynamodb.model.Page<TransactionTable>> pages = userIdGoalIdIndex.query(queryConditional);
            for (software.amazon.awssdk.enhanced.dynamodb.model.Page<TransactionTable> page : pages) {
                results.addAll(page.items());
            }
            return results;
        } catch (ResourceNotFoundException e) {
            // GSI not available - fallback to filtering in memory
            org.slf4j.LoggerFactory.getLogger(TransactionRepository.class)
                    .warn("UserIdGoalIdIndex GSI not found. Falling back to filtering in memory.");
            return findByUserId(userId, 0, Integer.MAX_VALUE)
                    .stream()
                    .filter(tx -> goalId.equals(tx.getGoalId()))
                    .collect(java.util.stream.Collectors.toList());
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(TransactionRepository.class)
                    .error("Error querying transactions by userId and goalId: {}", e.getMessage(), e);
            // Fallback to filtering in memory
            return findByUserId(userId, 0, Integer.MAX_VALUE)
                    .stream()
                    .filter(tx -> goalId.equals(tx.getGoalId()))
                    .collect(java.util.stream.Collectors.toList());
        }
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
        // CRITICAL: Deduplicate by both transactionId and plaidTransactionId
        // Use Set to track seen transactionIds and plaidTransactionIds to prevent duplicates
        Set<String> seenTransactionIds = new HashSet<>();
        Set<String> seenPlaidTransactionIds = new HashSet<>();
        int duplicateCount = 0;
        
        try {
            SdkIterable<software.amazon.awssdk.enhanced.dynamodb.model.Page<TransactionTable>>
                    pages = userIdDateIndex.query(
                            QueryConditional.keyEqualTo(
                                    Key.builder().partitionValue(userId).build()));
            for (software.amazon.awssdk.enhanced.dynamodb.model.Page<TransactionTable>
                    page : pages) {
                for (TransactionTable t : page.items()) {
                    if (t == null) {
                        continue;
                    }
                    
                    // CRITICAL FIX: Check for duplicates FIRST, before date range filtering
                    // This ensures all items are tracked in seen sets, preventing duplicates
                    // even if they appear outside the date range
                    String transactionId = t.getTransactionId();
                    String plaidTransactionId = t.getPlaidTransactionId();
                    boolean isDuplicate = false;
                    
                    // Check for duplicates by transactionId
                    if (transactionId != null && seenTransactionIds.contains(transactionId)) {
                        duplicateCount++;
                        org.slf4j.LoggerFactory.getLogger(TransactionRepository.class)
                                .warn("Duplicate transaction detected by transactionId and filtered: transactionId={}, plaidTransactionId={}, date={}, description={}", 
                                        transactionId, plaidTransactionId, t.getTransactionDate(), t.getDescription());
                        isDuplicate = true;
                    }
                    
                    // Check for duplicates by plaidTransactionId (if present)
                    if (!isDuplicate && plaidTransactionId != null && !plaidTransactionId.isEmpty()) {
                        if (seenPlaidTransactionIds.contains(plaidTransactionId)) {
                            duplicateCount++;
                            org.slf4j.LoggerFactory.getLogger(TransactionRepository.class)
                                    .warn("Duplicate transaction detected by plaidTransactionId and filtered: transactionId={}, plaidTransactionId={}, date={}, description={}", 
                                            transactionId, plaidTransactionId, t.getTransactionDate(), t.getDescription());
                            isDuplicate = true;
                        }
                    }
                    
                    // Track transaction in seen sets (even if duplicate or outside date range)
                    // This ensures proper deduplication across all transactions
                    if (!isDuplicate) {
                        if (transactionId != null) {
                            seenTransactionIds.add(transactionId);
                        }
                        if (plaidTransactionId != null && !plaidTransactionId.isEmpty()) {
                            seenPlaidTransactionIds.add(plaidTransactionId);
                        }
                    }
                    
                    // If duplicate, skip to next item
                    if (isDuplicate) {
                        continue;
                    }
                    
                    // Now check date range after deduplication
                    if (t.getTransactionDate() != null) {
                        String txDate = t.getTransactionDate();
                        // Compare dates as strings (ISO-8601 format allows
                        // lexicographic comparison)
                        if (txDate.compareTo(startDate) >= 0
                                && txDate.compareTo(endDate) <= 0) {
                            // Transaction is unique and within date range, add to results
                            results.add(t);
                            
                            final int safetyLimit = 1000;
                            if (results.size() >= safetyLimit) {
                                org.slf4j.LoggerFactory.getLogger(TransactionRepository.class)
                                        .warn("Reached safety limit of {} transactions for user {} in date range {} to {} ({} duplicates filtered)",
                                                safetyLimit, userId, startDate, endDate, duplicateCount);
                                return results;
                            }
                        }
                    }
                }
            }
        } catch (ResourceNotFoundException e) {
            // GSI not available - fallback to findByUserId and filter in memory
            org.slf4j.LoggerFactory.getLogger(TransactionRepository.class)
                    .warn("UserIdDateIndex GSI not found for userId {}. Falling back to findByUserId and filtering in memory.", userId);
            try {
                List<TransactionTable> allTransactions = findByUserId(userId, 0, Integer.MAX_VALUE);
                Set<String> seenTransactionIdsFallback = new HashSet<>();
                Set<String> seenPlaidTransactionIdsFallback = new HashSet<>();
                for (TransactionTable t : allTransactions) {
                    if (t == null) {
                        continue;
                    }
                    // Deduplicate
                    String transactionId = t.getTransactionId();
                    String plaidTransactionId = t.getPlaidTransactionId();
                    boolean isDuplicate = false;
                    if (transactionId != null && seenTransactionIdsFallback.contains(transactionId)) {
                        isDuplicate = true;
                    }
                    if (!isDuplicate && plaidTransactionId != null && !plaidTransactionId.isEmpty() && 
                        seenPlaidTransactionIdsFallback.contains(plaidTransactionId)) {
                        isDuplicate = true;
                    }
                    if (!isDuplicate) {
                        if (transactionId != null) {
                            seenTransactionIdsFallback.add(transactionId);
                        }
                        if (plaidTransactionId != null && !plaidTransactionId.isEmpty()) {
                            seenPlaidTransactionIdsFallback.add(plaidTransactionId);
                        }
                        // Filter by date range
                        if (t.getTransactionDate() != null) {
                            String txDate = t.getTransactionDate();
                            if (txDate.compareTo(startDate) >= 0 && txDate.compareTo(endDate) <= 0) {
                                results.add(t);
                            }
                        }
                    }
                }
            } catch (Exception fallbackException) {
                org.slf4j.LoggerFactory.getLogger(TransactionRepository.class)
                        .error("Error in fallback query for userId {}: {}", userId, fallbackException.getMessage(), fallbackException);
            }
        } catch (RuntimeException e) {
            // Check if the RuntimeException wraps a ResourceNotFoundException or indicates missing index
            Throwable cause = e.getCause();
            String errorMessage = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
            if (cause instanceof ResourceNotFoundException || 
                errorMessage.contains("index not found") || 
                errorMessage.contains("resource not found")) {
                // GSI not available - fallback to findByUserId and filter in memory
                org.slf4j.LoggerFactory.getLogger(TransactionRepository.class)
                        .warn("UserIdDateIndex GSI not found for userId {} (wrapped exception). Falling back to findByUserId and filtering in memory.", userId);
                try {
                    List<TransactionTable> allTransactions = findByUserId(userId, 0, Integer.MAX_VALUE);
                    Set<String> seenTransactionIdsFallback = new HashSet<>();
                    Set<String> seenPlaidTransactionIdsFallback = new HashSet<>();
                    for (TransactionTable t : allTransactions) {
                        if (t == null) {
                            continue;
                        }
                        // Deduplicate
                        String transactionId = t.getTransactionId();
                        String plaidTransactionId = t.getPlaidTransactionId();
                        boolean isDuplicate = false;
                        if (transactionId != null && seenTransactionIdsFallback.contains(transactionId)) {
                            isDuplicate = true;
                        }
                        if (!isDuplicate && plaidTransactionId != null && !plaidTransactionId.isEmpty() && 
                            seenPlaidTransactionIdsFallback.contains(plaidTransactionId)) {
                            isDuplicate = true;
                        }
                        if (!isDuplicate) {
                            if (transactionId != null) {
                                seenTransactionIdsFallback.add(transactionId);
                            }
                            if (plaidTransactionId != null && !plaidTransactionId.isEmpty()) {
                                seenPlaidTransactionIdsFallback.add(plaidTransactionId);
                            }
                            // Filter by date range
                            if (t.getTransactionDate() != null) {
                                String txDate = t.getTransactionDate();
                                if (txDate.compareTo(startDate) >= 0 && txDate.compareTo(endDate) <= 0) {
                                    results.add(t);
                                }
                            }
                        }
                    }
                } catch (Exception fallbackException) {
                    org.slf4j.LoggerFactory.getLogger(TransactionRepository.class)
                            .error("Error in fallback query for userId {}: {}", userId, fallbackException.getMessage(), fallbackException);
                    // Return empty list if fallback fails
                    return results;
                }
            } else {
                // Not a ResourceNotFoundException - rethrow
                throw e;
            }
        }
        
        if (duplicateCount > 0) {
            org.slf4j.LoggerFactory.getLogger(TransactionRepository.class)
                    .info("findByUserIdAndDateRange({}, {} to {}): Filtered {} duplicate transactions, returning {} unique transactions",
                            userId, startDate, endDate, duplicateCount, results.size());
        }
        return results;
    }

    /**
     * Find transactions updated after a specific timestamp using GSI
     * Optimized for incremental sync - queries only changed items
     */
    /**
     * CRITICAL: Do NOT cache this method - incremental sync queries must always be fresh
     * to handle DynamoDB GSI eventual consistency. Caching empty results would prevent
     * finding updated items until cache expires.
     */
    public List<TransactionTable> findByUserIdAndUpdatedAfter(String userId, Long updatedAfterTimestamp, int limit) {
        if (userId == null || userId.isEmpty() || updatedAfterTimestamp == null) {
            return List.of();
        }
        
        int adjustedLimit = limit;
        if (adjustedLimit <= 0) {
            adjustedLimit = 50; // Default limit
        }
        if (adjustedLimit > 100) {
            adjustedLimit = 100; // Max limit
        }
        
        List<TransactionTable> results = new ArrayList<>();
        try {
            // CRITICAL FIX: Cannot use filter expression on sort key (updatedAtTimestamp is GSI sort key)
            // Query all items for user, then filter in application code
            // This is still efficient because we're using the GSI partition key
            SdkIterable<software.amazon.awssdk.enhanced.dynamodb.model.Page<TransactionTable>> pages =
                    userIdUpdatedAtIndex.query(
                            QueryConditional.keyEqualTo(Key.builder().partitionValue(userId).build()));

            int count = 0;
            for (software.amazon.awssdk.enhanced.dynamodb.model.Page<TransactionTable> page : pages) {
                for (TransactionTable item : page.items()) {
                    // Filter in application code: updatedAtTimestamp >= updatedAfterTimestamp
                    // Use >= to include items updated exactly at the timestamp
                    if (item.getUpdatedAtTimestamp() != null && 
                        item.getUpdatedAtTimestamp() >= updatedAfterTimestamp) {
                        if (count >= adjustedLimit) {
                            break;
                        }
                        results.add(item);
                        count++;
                    }
                }
                if (count >= adjustedLimit) {
                    break;
                }
            }
        } catch (ResourceNotFoundException e) {
            // GSI not available - fallback to findByUserId and filter in memory
            org.slf4j.LoggerFactory.getLogger(TransactionRepository.class)
                    .warn("UserIdUpdatedAtIndex GSI not found for userId {}. Falling back to findByUserId and filtering in memory.", userId);
            try {
                List<TransactionTable> allTransactions = findByUserId(userId, 0, Integer.MAX_VALUE);
                int count = 0;
                for (TransactionTable transaction : allTransactions) {
                    if (transaction.getUpdatedAtTimestamp() != null && 
                        transaction.getUpdatedAtTimestamp() >= updatedAfterTimestamp) {
                        if (count >= adjustedLimit) {
                            break;
                        }
                        results.add(transaction);
                        count++;
                    }
                }
            } catch (Exception fallbackException) {
                org.slf4j.LoggerFactory.getLogger(TransactionRepository.class)
                        .error("Error in fallback query for userId {}: {}", userId, fallbackException.getMessage(), fallbackException);
            }
        } catch (RuntimeException e) {
            // Check if the RuntimeException wraps a ResourceNotFoundException or indicates missing index
            Throwable cause = e.getCause();
            String errorMessage = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
            if (cause instanceof ResourceNotFoundException || 
                errorMessage.contains("index not found") || 
                errorMessage.contains("resource not found")) {
                // GSI not available - fallback to findByUserId and filter in memory
                org.slf4j.LoggerFactory.getLogger(TransactionRepository.class)
                        .warn("UserIdUpdatedAtIndex GSI not found for userId {} (wrapped exception). Falling back to findByUserId and filtering in memory.", userId);
                try {
                    List<TransactionTable> allTransactions = findByUserId(userId, 0, Integer.MAX_VALUE);
                    int count = 0;
                    for (TransactionTable transaction : allTransactions) {
                        if (transaction.getUpdatedAtTimestamp() != null && 
                            transaction.getUpdatedAtTimestamp() >= updatedAfterTimestamp) {
                            if (count >= adjustedLimit) {
                                break;
                            }
                            results.add(transaction);
                            count++;
                        }
                    }
                } catch (Exception fallbackException) {
                    org.slf4j.LoggerFactory.getLogger(TransactionRepository.class)
                            .error("Error in fallback query for userId {}: {}", userId, fallbackException.getMessage(), fallbackException);
                    // Return empty list if fallback fails
                    return results;
                }
            } else {
                // Not a ResourceNotFoundException - log as error
                org.slf4j.LoggerFactory.getLogger(TransactionRepository.class)
                        .error("Error finding transactions by userId and updatedAfter {}: {}", userId, e.getMessage(), e);
            }
        } catch (Exception e) {
            // Check if the exception message indicates missing index
            String errorMessage = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
            if (errorMessage.contains("index not found") || errorMessage.contains("resource not found")) {
                // GSI not available - fallback to findByUserId and filter in memory
                org.slf4j.LoggerFactory.getLogger(TransactionRepository.class)
                        .warn("UserIdUpdatedAtIndex GSI not found for userId {} (detected from error message). Falling back to findByUserId and filtering in memory.", userId);
                try {
                    List<TransactionTable> allTransactions = findByUserId(userId, 0, Integer.MAX_VALUE);
                    int count = 0;
                    for (TransactionTable transaction : allTransactions) {
                        if (transaction.getUpdatedAtTimestamp() != null && 
                            transaction.getUpdatedAtTimestamp() >= updatedAfterTimestamp) {
                            if (count >= adjustedLimit) {
                                break;
                            }
                            results.add(transaction);
                            count++;
                        }
                    }
                } catch (Exception fallbackException) {
                    org.slf4j.LoggerFactory.getLogger(TransactionRepository.class)
                            .error("Error in fallback query for userId {}: {}", userId, fallbackException.getMessage(), fallbackException);
                    // Return empty list if fallback fails
                    return results;
                }
            } else {
                org.slf4j.LoggerFactory.getLogger(TransactionRepository.class)
                        .error("Error finding transactions by userId and updatedAfter {}: {}", userId, e.getMessage(), e);
            }
        }
        
        return results;
    }

    public Optional<TransactionTable> findByPlaidTransactionId(
            final String plaidTransactionId) {
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

    @CacheEvict(value = "transactions", allEntries = true)
    public void delete(final String transactionId) {
        if (transactionId == null || transactionId.isEmpty()) {
            throw new IllegalArgumentException("Transaction ID cannot be null or empty");
        }
        transactionTable.deleteItem(Key.builder().partitionValue(transactionId).build());
    }

    /**
     * Save transaction only if it doesn't exist (conditional write)
     * Prevents duplicate transactions (deduplication)
     */
    public boolean saveIfNotExists(final TransactionTable transaction) {
        if (transaction == null) {
            throw new IllegalArgumentException("Transaction cannot be null");
        }
        if (transaction.getTransactionId() == null || transaction.getTransactionId().isEmpty()) {
            throw new IllegalArgumentException("Transaction ID is required");
        }

        try {
            transactionTable.putItem(
                    software.amazon.awssdk.enhanced.dynamodb.model.PutItemEnhancedRequest.builder(TransactionTable.class)
                            .item(transaction)
                            .conditionExpression(
                                    Expression.builder()
                                            .expression("attribute_not_exists(transactionId)")
                                            .build())
                            .build());
            return true;
        } catch (ConditionalCheckFailedException e) {
            // MEDIUM PRIORITY: Conditional check failure indicates transaction already exists
            // Note: Full monitoring integration should be done at service layer with dependency injection
            // This is expected behavior for idempotent operations, so no logging needed here
            return false; // Transaction already exists
        }
    }

    /**
     * Find transaction by composite key (accountId + amount + date + description/merchantName)
     * Used as fallback when plaidTransactionId changes due to reconnection/relinking
     * PERFORMANCE FIX: Uses date range query to limit search scope instead of loading all transactions
     * 
     * @param accountId Account ID
     * @param amount Transaction amount
     * @param transactionDate Transaction date (YYYY-MM-DD)
     * @param description Transaction description (or merchantName if description is null)
     * @param userId User ID (for scoping)
     * @return Optional transaction if found
     */
    public Optional<TransactionTable> findByCompositeKey(
            final String accountId,
            final java.math.BigDecimal amount,
            final String transactionDate,
            final String description,
            final String userId) {
        if (accountId == null || accountId.isEmpty() ||
            amount == null ||
            transactionDate == null || transactionDate.isEmpty() ||
            userId == null || userId.isEmpty()) {
            return Optional.empty();
        }
        
        // PERFORMANCE FIX: Use date range query to limit search scope
        // Search within ±1 day of the transaction date to handle timezone differences
        // This is much more efficient than loading all transactions for the user
        try {
            LocalDate date = LocalDate.parse(transactionDate, java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);
            String startDate = date.minusDays(1).format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);
            String endDate = date.plusDays(1).format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);
            
            // Query transactions in date range (much more efficient than loading all)
            List<TransactionTable> dateRangeTransactions = findByUserIdAndDateRange(userId, startDate, endDate);
            
            // Filter by accountId, amount, exact date, and description/merchantName
            return dateRangeTransactions.stream()
                    .filter(t -> t != null && accountId.equals(t.getAccountId()))
                    .filter(t -> amount.compareTo(t.getAmount()) == 0)
                    .filter(t -> transactionDate.equals(t.getTransactionDate())) // Exact date match
                    .filter(t -> {
                        // Match by description or merchantName
                        if (description != null && !description.isEmpty()) {
                            String txDescription = t.getDescription();
                            String txMerchant = t.getMerchantName();
                            // Match if description matches or merchantName matches
                            return (txDescription != null && txDescription.equals(description)) ||
                                   (txMerchant != null && txMerchant.equals(description));
                        }
                        return true; // If description is null, don't filter by it
                    })
                    .findFirst();
        } catch (Exception e) {
            // Fallback to original method if date parsing fails
            org.slf4j.LoggerFactory.getLogger(TransactionRepository.class)
                    .warn("Failed to parse date for composite key search, falling back to full user query: {}", e.getMessage());
            
            // Get all transactions for this user and account (fallback)
            List<TransactionTable> userTransactions = findByUserId(userId, 0, Integer.MAX_VALUE);
            
            return userTransactions.stream()
                    .filter(t -> t != null && accountId.equals(t.getAccountId()))
                    .filter(t -> amount.compareTo(t.getAmount()) == 0)
                    .filter(t -> transactionDate.equals(t.getTransactionDate()))
                    .filter(t -> {
                        // Match by description or merchantName
                        if (description != null && !description.isEmpty()) {
                            String txDescription = t.getDescription();
                            String txMerchant = t.getMerchantName();
                            // Match if description matches or merchantName matches
                            return (txDescription != null && txDescription.equals(description)) ||
                                   (txMerchant != null && txMerchant.equals(description));
                        }
                        return true; // If description is null, don't filter by it
                    })
                    .findFirst();
        }
    }

    /**
     * Save transaction only if Plaid transaction ID doesn't exist (conditional write)
     * Prevents duplicate Plaid transactions
     * 
     * CRITICAL FIX: DynamoDB condition expressions only check attributes on the specific item
     * being written (keyed by transactionId). Since each new transaction has a unique transactionId,
     * attribute_not_exists(plaidTransactionId) will always pass for new items, even if another
     * item with a different transactionId already has the same plaidTransactionId.
     * 
     * Solution: First check the GSI to see if a transaction with the same plaidTransactionId
     * exists. If it does, return false. If it doesn't, use conditional write with
     * attribute_not_exists(transactionId) to prevent overwrites.
     * 
     * Note: There's still a small TOCTOU window between the GSI check and the write, but it's
     * much smaller than before. For true atomicity, use DynamoDB Transactions (TransactWriteItems).
     */
    public boolean saveIfPlaidTransactionNotExists(final TransactionTable transaction) {
        if (transaction == null) {
            throw new IllegalArgumentException("Transaction cannot be null");
        }
        
        // Ensure transactionId is set (required for primary key)
        if (transaction.getTransactionId() == null || transaction.getTransactionId().isEmpty()) {
            throw new IllegalArgumentException("Transaction ID is required");
        }

        if (transaction.getPlaidTransactionId() == null || transaction.getPlaidTransactionId().isEmpty()) {
            // If no Plaid ID, use regular save with transactionId check only
            return saveIfNotExists(transaction);
        }

        // CRITICAL FIX: Check GSI first to see if plaidTransactionId already exists
        // DynamoDB condition expressions can't check across items, so we must check the GSI
        Optional<TransactionTable> existing = findByPlaidTransactionId(transaction.getPlaidTransactionId());
        if (existing.isPresent()) {
            // Plaid transaction ID already exists
            return false;
        }

        // Plaid transaction ID doesn't exist, use conditional write to prevent overwrites
        // and minimize TOCTOU window
        try {
            // Use conditional write to prevent overwriting existing transaction with same transactionId
            transactionTable.putItem(
                    software.amazon.awssdk.enhanced.dynamodb.model.PutItemEnhancedRequest.builder(TransactionTable.class)
                            .item(transaction)
                            .conditionExpression(
                                    Expression.builder()
                                            .expression("attribute_not_exists(transactionId)")
                                            .build())
                            .build());
            return true;
        } catch (ConditionalCheckFailedException e) {
            // Transaction with same transactionId already exists
            return false;
        }
    }

    /**
     * Batch save transactions using BatchWriteItem (cost-optimized)
     * DynamoDB allows up to 25 items per batch
     */
    @CacheEvict(value = "transactions", allEntries = true)
    public void batchSave(final List<TransactionTable> transactions) {
        if (transactions == null || transactions.isEmpty()) {
            return;
        }

        // DynamoDB batch write limit is 25 items per request
        final int batchSize = 25;
        List<List<TransactionTable>> batches = new ArrayList<>();
        for (int i = 0; i < transactions.size(); i += batchSize) {
            batches.add(transactions.subList(i, Math.min(i + batchSize, transactions.size())));
        }

        for (List<TransactionTable> batch : batches) {
            List<WriteRequest> writeRequests = batch.stream()
                    .map(transaction -> {
                        Map<String, AttributeValue> item = new HashMap<>();
                        item.put("transactionId", AttributeValue.builder().s(transaction.getTransactionId()).build());
                        if (transaction.getUserId() != null) {
                            item.put("userId", AttributeValue.builder().s(transaction.getUserId()).build());
                        }
                        if (transaction.getTransactionDate() != null) {
                            item.put("transactionDate", AttributeValue.builder().s(transaction.getTransactionDate()).build());
                        }
                        if (transaction.getAmount() != null) {
                            item.put("amount", AttributeValue.builder().n(transaction.getAmount().toString()).build());
                        }
                        // Add other attributes as needed
                        return WriteRequest.builder()
                                .putRequest(PutRequest.builder().item(item).build())
                                .build();
                    })
                    .collect(Collectors.toList());

            Map<String, List<WriteRequest>> requestItems = new HashMap<>();
            requestItems.put(this.tableName, writeRequests);

            BatchWriteItemRequest batchRequest = BatchWriteItemRequest.builder()
                    .requestItems(requestItems)
                    .build();

            com.budgetbuddy.util.RetryHelper.executeWithRetry(() -> {
                BatchWriteItemResponse resp = dynamoDbClient.batchWriteItem(batchRequest);
                
                // Retry if there are unprocessed items
                if (!resp.unprocessedItems().isEmpty()) {
                    throw new RuntimeException("Unprocessed items in batch write");
                }
                
                return resp;
            });

            // All items processed successfully
        }
    }

    /**
     * Batch read transactions by IDs using BatchGetItem (cost-optimized)
     * DynamoDB allows up to 100 items per batch
     */
    public List<TransactionTable> batchFindByIds(final List<String> transactionIds) {
        if (transactionIds == null || transactionIds.isEmpty()) {
            return List.of();
        }

        // DynamoDB batch get limit is 100 items per request
        final int batchSize = 100;
        List<TransactionTable> results = new ArrayList<>();

        for (int i = 0; i < transactionIds.size(); i += batchSize) {
            List<String> batch = transactionIds.subList(i, Math.min(i + batchSize, transactionIds.size()));

            List<Map<String, AttributeValue>> keys = batch.stream()
                    .map(id -> {
                        Map<String, AttributeValue> key = new HashMap<>();
                        key.put("transactionId", AttributeValue.builder().s(id).build());
                        return key;
                    })
                    .collect(Collectors.toList());

            KeysAndAttributes keysAndAttributes = KeysAndAttributes.builder()
                    .keys(keys)
                    .build();

            Map<String, KeysAndAttributes> requestItems = new HashMap<>();
            requestItems.put(this.tableName, keysAndAttributes);

            BatchGetItemRequest batchRequest = BatchGetItemRequest.builder()
                    .requestItems(requestItems)
                    .build();

            BatchGetItemResponse response = dynamoDbClient.batchGetItem(batchRequest);

            // Convert response items to TransactionTable objects
            if (response.responses() != null && response.responses().containsKey(this.tableName)) {
                List<Map<String, AttributeValue>> items = response.responses().get(this.tableName);
                for (Map<String, AttributeValue> item : items) {
                    // Use proper conversion method to fully populate all fields
                    TransactionTable transaction = convertAttributeValueMapToTransaction(item);
                    results.add(transaction);
                }
            }

            // Retry if there are unprocessed keys
            if (!response.unprocessedKeys().isEmpty()) {
                // Retry unprocessed keys with exponential backoff
                Map<String, KeysAndAttributes> unprocessed = response.unprocessedKeys();
                BatchGetItemRequest retryRequest = BatchGetItemRequest.builder()
                        .requestItems(unprocessed)
                        .build();
                
                BatchGetItemResponse retryResponse = com.budgetbuddy.util.RetryHelper.executeWithRetry(() -> {
                    BatchGetItemResponse resp = dynamoDbClient.batchGetItem(retryRequest);
                    if (!resp.unprocessedKeys().isEmpty()) {
                        throw new RuntimeException("Unprocessed keys in batch read");
                    }
                    return resp;
                });

                // Process retry response items
                if (retryResponse.responses() != null && retryResponse.responses().containsKey(this.tableName)) {
                    List<Map<String, AttributeValue>> retryItems = retryResponse.responses().get(this.tableName);
                    for (Map<String, AttributeValue> item : retryItems) {
                        TransactionTable transaction = convertAttributeValueMapToTransaction(item);
                        results.add(transaction);
                    }
                }
            }
        }

        return results;
    }

    /**
     * Batch delete transactions using BatchWriteItem
     */
    @CacheEvict(value = "transactions", allEntries = true)
    public void batchDelete(final List<String> transactionIds) {
        if (transactionIds == null || transactionIds.isEmpty()) {
            return;
        }

        final int batchSize = 25;
        for (int i = 0; i < transactionIds.size(); i += batchSize) {
            List<String> batch = transactionIds.subList(i, Math.min(i + batchSize, transactionIds.size()));

            List<WriteRequest> writeRequests = batch.stream()
                    .map(id -> {
                        Map<String, AttributeValue> key = new HashMap<>();
                        key.put("transactionId", AttributeValue.builder().s(id).build());
                        return WriteRequest.builder()
                                .deleteRequest(software.amazon.awssdk.services.dynamodb.model.DeleteRequest.builder()
                                        .key(key)
                                        .build())
                                .build();
                    })
                    .collect(Collectors.toList());

            Map<String, List<WriteRequest>> requestItems = new HashMap<>();
            requestItems.put(this.tableName, writeRequests);

            BatchWriteItemRequest batchRequest = BatchWriteItemRequest.builder()
                    .requestItems(requestItems)
                    .build();

            dynamoDbClient.batchWriteItem(batchRequest);
        }
    }

    /**
     * Convert AttributeValue map to TransactionTable
     * Optimized conversion from DynamoDB AttributeValue maps to domain objects
     */
    private TransactionTable convertAttributeValueMapToTransaction(final Map<String, AttributeValue> item) {
        TransactionTable transaction = new TransactionTable();

        if (item.containsKey("transactionId")) {
            transaction.setTransactionId(item.get("transactionId").s());
        }
        if (item.containsKey("userId")) {
            transaction.setUserId(item.get("userId").s());
        }
        if (item.containsKey("accountId")) {
            transaction.setAccountId(item.get("accountId").s());
        }
        if (item.containsKey("amount")) {
            String amountStr = item.get("amount").n();
            if (amountStr != null) {
                transaction.setAmount(new java.math.BigDecimal(amountStr));
            }
        }
        if (item.containsKey("description")) {
            transaction.setDescription(item.get("description").s());
        }
        if (item.containsKey("merchantName")) {
            transaction.setMerchantName(item.get("merchantName").s());
        }
        // Handle category structure (categoryPrimary, categoryDetailed)
        if (item.containsKey("categoryPrimary")) {
            transaction.setCategoryPrimary(item.get("categoryPrimary").s());
        }
        if (item.containsKey("categoryDetailed")) {
            transaction.setCategoryDetailed(item.get("categoryDetailed").s());
        }
        // Support both old field names (for backward compatibility during migration) and new field names
        if (item.containsKey("importerCategoryPrimary")) {
            transaction.setImporterCategoryPrimary(item.get("importerCategoryPrimary").s());
        } else if (item.containsKey("plaidCategoryPrimary")) {
            // Backward compatibility: migrate old field name to new field name
            transaction.setImporterCategoryPrimary(item.get("plaidCategoryPrimary").s());
        }
        if (item.containsKey("importerCategoryDetailed")) {
            transaction.setImporterCategoryDetailed(item.get("importerCategoryDetailed").s());
        } else if (item.containsKey("plaidCategoryDetailed")) {
            // Backward compatibility: migrate old field name to new field name
            transaction.setImporterCategoryDetailed(item.get("plaidCategoryDetailed").s());
        }
        if (item.containsKey("categoryOverridden")) {
            transaction.setCategoryOverridden(item.get("categoryOverridden").bool());
        }
        if (item.containsKey("transactionDate")) {
            transaction.setTransactionDate(item.get("transactionDate").s());
        }
        if (item.containsKey("currencyCode")) {
            transaction.setCurrencyCode(item.get("currencyCode").s());
        }
        if (item.containsKey("plaidTransactionId")) {
            transaction.setPlaidTransactionId(item.get("plaidTransactionId").s());
        }
        if (item.containsKey("pending")) {
            transaction.setPending(item.get("pending").bool());
        }
        if (item.containsKey("createdAt")) {
            String timestamp = item.get("createdAt").n();
            if (timestamp != null) {
                transaction.setCreatedAt(Instant.ofEpochSecond(Long.parseLong(timestamp)));
            }
        }
        if (item.containsKey("updatedAt")) {
            String timestamp = item.get("updatedAt").n();
            if (timestamp != null) {
                transaction.setUpdatedAt(Instant.ofEpochSecond(Long.parseLong(timestamp)));
            }
        }

        return transaction;
    }
}
