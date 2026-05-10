package com.budgetbuddy.repository.dynamodb;

import com.budgetbuddy.model.dynamodb.ImportHistoryTable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.core.pagination.sync.SdkIterable;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbIndex;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;

/**
 * DynamoDB Repository for Import History Tracks all import operations for audit trail and history
 */
@Repository
@org.springframework.context.annotation.DependsOn({
    "dynamoDBTableManager",
    "dynamoDbEnhancedClient"
})
public class ImportHistoryRepository {

    private final DynamoDbTable<ImportHistoryTable> importHistoryTable;
    private final DynamoDbIndex<ImportHistoryTable> userIdIndex;
    private final String tableName;

    public ImportHistoryRepository(
            final DynamoDbEnhancedClient enhancedClient,
            @org.springframework.beans.factory.annotation.Value(
                            "${app.aws.dynamodb.table-prefix:BudgetBuddy}")
                    final String tablePrefix) {
        if (enhancedClient == null) {
            throw new IllegalArgumentException("DynamoDbEnhancedClient cannot be null");
        }
        if (tablePrefix == null || tablePrefix.isEmpty()) {
            throw new IllegalArgumentException("tablePrefix cannot be null or empty");
        }
        this.tableName = tablePrefix + "-ImportHistory";
        try {
            this.importHistoryTable =
                    enhancedClient.table(
                            this.tableName, TableSchema.fromBean(ImportHistoryTable.class));
            this.userIdIndex = importHistoryTable.index("UserIdIndex");
        } catch (Exception e) {
            throw new IllegalStateException(
                    String.format(
                            "Failed to initialize ImportHistoryRepository for table '%s'. "
                                    + "This may happen if DynamoDB is not available or table schema is invalid. "
                                    + "Original error: %s",
                            tableName, e.getMessage()),
                    e);
        }
    }

    @CacheEvict(value = "importHistory", key = "#importHistory.userId")
    public void save(final ImportHistoryTable importHistory) {
        if (importHistory == null) {
            throw new IllegalArgumentException("ImportHistory cannot be null");
        }
        // CRITICAL: Add retry logic for DynamoDB throttling and transient errors
        com.budgetbuddy.util.RetryHelper.executeDynamoDbWithRetry(
                () -> {
                    importHistoryTable.putItem(importHistory);
                    return null;
                });
    }

    public Optional<ImportHistoryTable> findById(final String importId) {
        if (importId == null || importId.isEmpty()) {
            return Optional.empty();
        }
        // Normalize ID to lowercase for case-insensitive lookup
        final String normalizedId = com.budgetbuddy.util.IdGenerator.normalizeUUID(importId);
        final ImportHistoryTable importHistory =
                importHistoryTable.getItem(Key.builder().partitionValue(normalizedId).build());
        return Optional.ofNullable(importHistory);
    }

    @Cacheable(
            value = "importHistory",
            key = "#userId",
            unless = "#result == null || #result.isEmpty()")
    public List<ImportHistoryTable> findByUserId(final String userId) {
        if (userId == null || userId.isEmpty()) {
            return List.of();
        }
        final List<ImportHistoryTable> results = new ArrayList<>();
        try {
            final SdkIterable<software.amazon.awssdk.enhanced.dynamodb.model.Page<ImportHistoryTable>>
                    pages =
                            userIdIndex.query(
                                    QueryConditional.keyEqualTo(
                                            Key.builder().partitionValue(userId).build()));
            for (final software.amazon.awssdk.enhanced.dynamodb.model.Page<ImportHistoryTable> page :
                    pages) {
                for (final ImportHistoryTable item : page.items()) {
                    if (item != null) {
                        results.add(item);
                    }
                }
            }
        } catch (software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException e) {
            // GSI not available - return empty list
            org.slf4j.LoggerFactory.getLogger(ImportHistoryRepository.class)
                    .warn("UserIdIndex GSI not found for userId {}. Returning empty list.", userId);
        }
        // Sort by createdAt descending (newest first)
        results.sort(
                (a, b) -> {
                    if (a.getCreatedAt() == null && b.getCreatedAt() == null) {
                        return 0;
                    }
                    if (a.getCreatedAt() == null) {
                        return 1;
                    }
                    if (b.getCreatedAt() == null) {
                        return -1;
                    }
                    return b.getCreatedAt().compareTo(a.getCreatedAt());
                });
        return results;
    }

    public void delete(final String importId) {
        if (importId == null || importId.isEmpty()) {
            return;
        }
        // Normalize ID to lowercase for case-insensitive lookup
        final String normalizedId = com.budgetbuddy.util.IdGenerator.normalizeUUID(importId);
        com.budgetbuddy.util.RetryHelper.executeDynamoDbWithRetry(
                () -> {
                    importHistoryTable.deleteItem(
                            Key.builder().partitionValue(normalizedId).build());
                    return null;
                });
    }
}
