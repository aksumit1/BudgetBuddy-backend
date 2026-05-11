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

    @CacheEvict(value = "subscriptions", key = "#subscription.userId")
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
            value = "subscriptions",
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
            value = "subscriptions",
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

    @CacheEvict(value = "subscriptions", allEntries = true)
    public void delete(final String subscriptionId) {
        if (subscriptionId == null || subscriptionId.isEmpty()) {
            throw new IllegalArgumentException("Subscription ID cannot be null or empty");
        }
        subscriptionTable.deleteItem(Key.builder().partitionValue(subscriptionId).build());
    }

    public String getTableName() {
        return tableName;
    }
}
