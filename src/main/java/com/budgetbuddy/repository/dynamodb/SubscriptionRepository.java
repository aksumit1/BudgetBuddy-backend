package com.budgetbuddy.repository.dynamodb;

import com.budgetbuddy.model.dynamodb.SubscriptionTable;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbIndex;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;

/** DynamoDB Repository for Subscriptions */
// PMD's OnlyOneReturn fights guard-clause idiom — the codebase intentionally
// uses early returns for clarity (validation guards, fail-fast patterns).
@SuppressWarnings("PMD.OnlyOneReturn")
@Repository
public class SubscriptionRepository {
    private static final String SUBSCRIPTIONS = "subscriptions";

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger(SubscriptionRepository.class);

    private final DynamoDbTable<SubscriptionTable> subscriptionTable;
    private final DynamoDbIndex<SubscriptionTable> userIdIndex;
    private final String tableName;

    public SubscriptionRepository(
            final DynamoDbEnhancedClient enhancedClient,
            @org.springframework.beans.factory.annotation.Value(
                            "${app.aws.dynamodb.table-prefix:BudgetBuddy}")
                    final String tablePrefix) {
        this.tableName = tablePrefix + "-Subscriptions";
        this.subscriptionTable =
                enhancedClient.table(this.tableName, TableSchema.fromBean(SubscriptionTable.class));
        this.userIdIndex = subscriptionTable.index("UserIdIndex");
    }

    @CacheEvict(value = SUBSCRIPTIONS, key = "#subscription.userId")
    public void save(final SubscriptionTable subscription) {
        if (subscription == null) {
            throw new IllegalArgumentException("Subscription cannot be null");
        }
        subscriptionTable.putItem(subscription);
    }

    public Optional<SubscriptionTable> findById(final String subscriptionId) {
        if (subscriptionId == null || subscriptionId.isEmpty()) {
            return Optional.empty();
        }
        // CRITICAL FIX: Normalize ID to lowercase for case-insensitive lookup
        final String normalizedId = com.budgetbuddy.util.IdGenerator.normalizeUUID(subscriptionId);
        final SubscriptionTable subscription =
                subscriptionTable.getItem(Key.builder().partitionValue(normalizedId).build());
        return Optional.ofNullable(subscription);
    }

    @Cacheable(
            value = SUBSCRIPTIONS,
            key = "'user:' + #userId",
            unless = "#result == null || #result.isEmpty()")
    public List<SubscriptionTable> findByUserId(final String userId) {
        if (userId == null || userId.isEmpty()) {
            return List.of();
        }

        try {
            return StreamSupport.stream(
                            userIdIndex
                                    .query(
                                            QueryConditional.keyEqualTo(
                                                    Key.builder().partitionValue(userId).build()))
                                    .spliterator(),
                            false)
                    .flatMap(page -> page.items().stream())
                    .collect(Collectors.toList());
        } catch (software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException e) {
            // GSI not available - return empty list (fallback logic can be added if needed)
            LOGGER.warn("UserIdIndex GSI not found for userId {}. Returning empty list.", userId);
            return List.of();
        }
    }

    @Cacheable(
            value = SUBSCRIPTIONS,
            key = "'user:' + #userId + ':active'",
            unless = "#result == null || #result.isEmpty()")
    public List<SubscriptionTable> findActiveByUserId(final String userId) {
        if (userId == null || userId.isEmpty()) {
            return List.of();
        }

        return findByUserId(userId).stream()
                .filter(sub -> sub.getActive() != null && sub.getActive())
                .collect(Collectors.toList());
    }

    @CacheEvict(value = SUBSCRIPTIONS, allEntries = true)
    public void delete(final String subscriptionId) {
        if (subscriptionId == null || subscriptionId.isEmpty()) {
            throw new IllegalArgumentException("Subscription ID cannot be null or empty");
        }
        subscriptionTable.deleteItem(Key.builder().partitionValue(subscriptionId).build());
    }

    public String getTableName() {
        return tableName;
    }

    /**
     * Full table scan returning every subscription row. Used by the
     * nightly precompute worker to collect the set of distinct user-ids
     * that have any subscriptions. Intentionally NOT @Cacheable —
     * the caller pages through this once per night, caching would just
     * blow heap. DynamoDB scan IS expensive at large scale; for now the
     * subscriptions table is small (one row per user-merchant) so this
     * is fine. If it gets large, switch to a paginated scan with a
     * resume token.
     */
    public java.util.stream.Stream<SubscriptionTable> scanAll() {
        try {
            return StreamSupport.stream(
                            subscriptionTable.scan().items().spliterator(), false);
        } catch (software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException e) {
            LOGGER.warn("Subscriptions table not found during scanAll: {}", e.getMessage());
            return java.util.stream.Stream.empty();
        }
    }
}
