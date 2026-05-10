package com.budgetbuddy.repository.dynamodb;

import com.budgetbuddy.model.dynamodb.BudgetTable;
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
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;

/** DynamoDB Repository for Budgets */
// SDK / Spring integration — the underlying APIs (AWS SDK, Plaid SDK,
// Spring services, reflection) throw arbitrary RuntimeException subtypes
// that can't reasonably be enumerated. Broad catches log + recover (or
// translate to AppException). Suppress at class level since narrowing
// here would mean catch (RuntimeException) which PMD flags identically.
@SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.OnlyOneReturn"})
@Repository
public class BudgetRepository {

    private final DynamoDbTable<BudgetTable> budgetTable;
    private final DynamoDbIndex<BudgetTable> userIdIndex;
    private final DynamoDbIndex<BudgetTable> userIdUpdatedAtIndex;
    private final String tableName;

    public BudgetRepository(
            final DynamoDbEnhancedClient enhancedClient,
            @org.springframework.beans.factory.annotation.Value(
                            "${app.aws.dynamodb.table-prefix:BudgetBuddy}")
                    final String tablePrefix) {
        this.tableName = tablePrefix + "-Budgets";
        this.budgetTable =
                enhancedClient.table(this.tableName, TableSchema.fromBean(BudgetTable.class));
        this.userIdIndex = budgetTable.index("UserIdIndex");
        this.userIdUpdatedAtIndex = budgetTable.index("UserIdUpdatedAtIndex");
    }

    @CacheEvict(value = "budgets", key = "#budget.userId")
    public void save(final BudgetTable budget) {
        // CRITICAL FIX: Add retry logic for DynamoDB throttling and transient errors
        com.budgetbuddy.util.RetryHelper.executeDynamoDbWithRetry(
                () -> {
                    budgetTable.putItem(budget);
                    return null;
                });
    }

    /**
     * Save with optimistic concurrency on the {@code version} column. Use on paths where user edits
     * can race with the threshold evaluator or rollover job. Throws {@link
     * OptimisticLockHelper.OptimisticLockException} if another writer beat us; caller should
     * re-read and retry.
     */
    @CacheEvict(value = "budgets", key = "#budget.userId")
    public BudgetTable saveWithLock(final BudgetTable budget) {
        return OptimisticLockHelper.saveWithLock(
                budgetTable,
                budget,
                BudgetTable::getVersion,
                budget::setVersion,
                "budgetId=" + budget.getBudgetId());
    }

    public Optional<BudgetTable> findById(final String budgetId) {
        if (budgetId == null || budgetId.isEmpty()) {
            return Optional.empty();
        }
        // Normalize ID to lowercase for case-insensitive lookup
        final String normalizedId = com.budgetbuddy.util.IdGenerator.normalizeUUID(budgetId);
        final BudgetTable budget =
                budgetTable.getItem(Key.builder().partitionValue(normalizedId).build());
        return Optional.ofNullable(budget);
    }

    @Cacheable(value = "budgets", key = "#userId", unless = "#result == null || #result.isEmpty()")
    public List<BudgetTable> findByUserId(final String userId) {
        final List<BudgetTable> results = new ArrayList<>();
        final SdkIterable<software.amazon.awssdk.enhanced.dynamodb.model.Page<BudgetTable>> pages =
                userIdIndex.query(
                        QueryConditional.keyEqualTo(Key.builder().partitionValue(userId).build()));
        for (final software.amazon.awssdk.enhanced.dynamodb.model.Page<BudgetTable> page : pages) {
            for (final BudgetTable item : page.items()) {
                results.add(item);
            }
        }
        return results;
    }

    public Optional<BudgetTable> findByUserIdAndCategory(final String userId, final String category) {
        return findByUserId(userId).stream()
                .filter(b -> category.equals(b.getCategory()))
                .findFirst();
    }

    /*
     * Find budgets updated after a specific timestamp using GSI Optimized for incremental sync -
     * queries only changed items
     */
    /**
     * CRITICAL: Do NOT cache this method - incremental sync queries must always be fresh to handle
     * DynamoDB GSI eventual consistency. Caching empty results would prevent finding updated items
     * until cache expires.
     */
    public List<BudgetTable> findByUserIdAndUpdatedAfter(
            final String userId, final Long updatedAfterTimestamp) {
        if (userId == null || userId.isEmpty() || updatedAfterTimestamp == null) {
            return List.of();
        }

        final List<BudgetTable> results = new ArrayList<>();
        try {
            // CRITICAL FIX: Cannot use filter expression on sort key (updatedAtTimestamp is GSI
            // sort key)
            // Query all items for user, then filter in application code
            // This is still efficient because we're using the GSI partition key
            final SdkIterable<software.amazon.awssdk.enhanced.dynamodb.model.Page<BudgetTable>> pages =
                    userIdUpdatedAtIndex.query(
                            QueryConditional.keyEqualTo(
                                    Key.builder().partitionValue(userId).build()));

            for (final software.amazon.awssdk.enhanced.dynamodb.model.Page<BudgetTable> page : pages) {
                for (final BudgetTable budget : page.items()) {
                    // Filter in application code: updatedAtTimestamp >= updatedAfterTimestamp
                    // Use >= to include items updated exactly at the timestamp
                    if (budget.getUpdatedAtTimestamp() != null
                            && budget.getUpdatedAtTimestamp() >= updatedAfterTimestamp) {
                        results.add(budget);
                    }
                }
            }
        } catch (ResourceNotFoundException e) {
            // GSI not available - fallback to findByUserId and filter in memory
            org.slf4j.LoggerFactory.getLogger(BudgetRepository.class)
                    .warn(
                            "UserIdUpdatedAtIndex GSI not found for userId {}. Falling back to findByUserId and filtering in memory.",
                            userId);
            try {
                final List<BudgetTable> allBudgets = findByUserId(userId);
                for (final BudgetTable budget : allBudgets) {
                    if (budget.getUpdatedAtTimestamp() != null
                            && budget.getUpdatedAtTimestamp() >= updatedAfterTimestamp) {
                        results.add(budget);
                    }
                }
            } catch (Exception fallbackException) {
                org.slf4j.LoggerFactory.getLogger(BudgetRepository.class)
                        .error(
                                "Error in fallback query for userId {}: {}",
                                userId,
                                fallbackException.getMessage(),
                                fallbackException);
            }
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(BudgetRepository.class)
                    .error(
                            "Error finding budgets by userId and updatedAfter {}: {}",
                            userId,
                            e.getMessage(),
                            e);
        }

        return results;
    }

    @CacheEvict(value = "budgets", allEntries = true)
    public void delete(final String budgetId) {
        budgetTable.deleteItem(Key.builder().partitionValue(budgetId).build());
    }

    /**
     * Flow 5 / O2: returns every budget with {@code rolloverEnabled == true}. Used by the month-end
     * rollover job, which runs once on the 1st of each month and writes the prior month's
     * surplus/deficit into {@code carriedAmount}. A full scan is acceptable here — it runs once a
     * month, off-peak, and the budgets table is the small one.
     */
    public List<BudgetTable> findAllWithRollover() {
        final List<BudgetTable> results = new ArrayList<>();
        final SdkIterable<software.amazon.awssdk.enhanced.dynamodb.model.Page<BudgetTable>> pages =
                budgetTable.scan();
        for (final var page : pages) {
            for (final BudgetTable b : page.items()) {
                if (Boolean.TRUE.equals(b.getRolloverEnabled())) {
                    results.add(b);
                }
            }
        }
        return results;
    }
}
