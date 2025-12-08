package com.budgetbuddy.repository.dynamodb;

import com.budgetbuddy.model.dynamodb.BudgetTable;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
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
 * DynamoDB Repository for Budgets
 */
@Repository
public class BudgetRepository {

    private final DynamoDbTable<BudgetTable> budgetTable;
    private final DynamoDbIndex<BudgetTable> userIdIndex;
    private final DynamoDbIndex<BudgetTable> userIdUpdatedAtIndex;
    private final String tableName;

    public BudgetRepository(
            final DynamoDbEnhancedClient enhancedClient,
            @org.springframework.beans.factory.annotation.Value("${app.aws.dynamodb.table-prefix:BudgetBuddy}") final String tablePrefix) {
        this.tableName = tablePrefix + "-Budgets";
        this.budgetTable = enhancedClient.table(this.tableName, TableSchema.fromBean(BudgetTable.class));
        this.userIdIndex = budgetTable.index("UserIdIndex");
        this.userIdUpdatedAtIndex = budgetTable.index("UserIdUpdatedAtIndex");
    }

    @CacheEvict(value = "budgets", key = "#budget.userId")
    public void save(final BudgetTable budget) {
        budgetTable.putItem(budget);
    }

    public Optional<BudgetTable> findById(String budgetId) {
        if (budgetId == null || budgetId.isEmpty()) {
            return Optional.empty();
        }
        // Normalize ID to lowercase for case-insensitive lookup
        String normalizedId = com.budgetbuddy.util.IdGenerator.normalizeUUID(budgetId);
        BudgetTable budget = budgetTable.getItem(Key.builder().partitionValue(normalizedId).build());
        // If not found with normalized ID, try original (for backward compatibility with mixed-case IDs)
        if (budget == null && !normalizedId.equals(budgetId)) {
            budget = budgetTable.getItem(Key.builder().partitionValue(budgetId).build());
        }
        return Optional.ofNullable(budget);
    }

    @Cacheable(value = "budgets", key = "#userId", unless = "#result == null || #result.isEmpty()")
    public List<BudgetTable> findByUserId(String userId) {
        List<BudgetTable> results = new ArrayList<>();
        SdkIterable<software.amazon.awssdk.enhanced.dynamodb.model.Page<BudgetTable>> pages =
                userIdIndex.query(QueryConditional.keyEqualTo(Key.builder().partitionValue(userId).build()));
        for (software.amazon.awssdk.enhanced.dynamodb.model.Page<BudgetTable> page : pages) {
            for (BudgetTable item : page.items()) {
                results.add(item);
            }
        }
        return results;
    }

    public Optional<BudgetTable> findByUserIdAndCategory(String userId, String category) {
        return findByUserId(userId).stream()
                .filter(b -> category.equals(b.getCategory()))
                .findFirst();
    }

    /**
     * Find budgets updated after a specific timestamp using GSI
     * Optimized for incremental sync - queries only changed items
     */
    @Cacheable(value = "budgets", key = "'user:' + #userId + ':updatedAfter:' + #updatedAfterTimestamp", unless = "#result == null || #result.isEmpty()")
    public List<BudgetTable> findByUserIdAndUpdatedAfter(String userId, Long updatedAfterTimestamp) {
        if (userId == null || userId.isEmpty() || updatedAfterTimestamp == null) {
            return List.of();
        }
        
        List<BudgetTable> results = new ArrayList<>();
        try {
            // CRITICAL FIX: Cannot use filter expression on sort key (updatedAtTimestamp is GSI sort key)
            // Query all items for user, then filter in application code
            // This is still efficient because we're using the GSI partition key
            SdkIterable<software.amazon.awssdk.enhanced.dynamodb.model.Page<BudgetTable>> pages =
                    userIdUpdatedAtIndex.query(
                            QueryConditional.keyEqualTo(Key.builder().partitionValue(userId).build()));

            for (software.amazon.awssdk.enhanced.dynamodb.model.Page<BudgetTable> page : pages) {
                for (BudgetTable budget : page.items()) {
                    // Filter in application code: updatedAtTimestamp >= updatedAfterTimestamp
                    // Use >= to include items updated exactly at the timestamp
                    if (budget.getUpdatedAtTimestamp() != null && 
                        budget.getUpdatedAtTimestamp() >= updatedAfterTimestamp) {
                        results.add(budget);
                    }
                }
            }
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(BudgetRepository.class)
                    .error("Error finding budgets by userId and updatedAfter {}: {}", userId, e.getMessage(), e);
        }
        
        return results;
    }

    @CacheEvict(value = "budgets", allEntries = true)
    public void delete(final String budgetId) {
        budgetTable.deleteItem(Key.builder().partitionValue(budgetId).build());
    }
}

