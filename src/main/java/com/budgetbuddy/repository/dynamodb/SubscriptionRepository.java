package com.budgetbuddy.repository.dynamodb;

import com.budgetbuddy.model.dynamodb.SubscriptionTable;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbIndex;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * DynamoDB Repository for Subscriptions
 */
@Repository
public class SubscriptionRepository {

    private final DynamoDbTable<SubscriptionTable> subscriptionTable;
    private final DynamoDbIndex<SubscriptionTable> userIdIndex;
    private final String tableName;

    public SubscriptionRepository(
            final DynamoDbEnhancedClient enhancedClient,
            @org.springframework.beans.factory.annotation.Value("${app.aws.dynamodb.table-prefix:BudgetBuddy}") final String tablePrefix) {
        this.tableName = tablePrefix + "-Subscriptions";
        this.subscriptionTable = enhancedClient.table(this.tableName,
                TableSchema.fromBean(SubscriptionTable.class));
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
        SubscriptionTable subscription = subscriptionTable.getItem(
                Key.builder().partitionValue(subscriptionId).build());
        return Optional.ofNullable(subscription);
    }

    @Cacheable(value = "subscriptions", key = "'user:' + #userId", unless = "#result == null || #result.isEmpty()")
    public List<SubscriptionTable> findByUserId(final String userId) {
        if (userId == null || userId.isEmpty()) {
            return List.of();
        }
        
        try {
            return StreamSupport.stream(
                    userIdIndex.query(QueryConditional.keyEqualTo(
                            Key.builder().partitionValue(userId).build())).spliterator(),
                    false)
                    .flatMap(page -> page.items().stream())
                    .collect(Collectors.toList());
        } catch (software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException e) {
            // GSI not available - return empty list (fallback logic can be added if needed)
            org.slf4j.LoggerFactory.getLogger(SubscriptionRepository.class)
                    .warn("UserIdIndex GSI not found for userId {}. Returning empty list.", userId);
            return List.of();
        }
    }

    @Cacheable(value = "subscriptions", key = "'user:' + #userId + ':active'", unless = "#result == null || #result.isEmpty()")
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

