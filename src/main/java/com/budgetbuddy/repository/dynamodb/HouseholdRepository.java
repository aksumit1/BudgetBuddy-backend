package com.budgetbuddy.repository.dynamodb;

import com.budgetbuddy.model.dynamodb.HouseholdTable;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.PutItemEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

/**
 * Persistence for the household-sharing scaffold.
 *
 * <p>Writes use optimistic concurrency via the {@code version} column. Two partners clicking
 * simultaneously will get a {@link OptimisticLockException} on the loser, which the caller should
 * handle by re-reading and retrying — the canonical pattern used elsewhere in the app (budgets,
 * transactions).
 */
@Repository
public class HouseholdRepository {

    /** Raised when a conditional write fails because the row moved under us. */
    public static class OptimisticLockException extends RuntimeException {
        public OptimisticLockException(final String msg) {
            super(msg);
        }
    }

    private final DynamoDbTable<HouseholdTable> table;

    public HouseholdRepository(
            final DynamoDbEnhancedClient enhancedClient,
            @org.springframework.beans.factory.annotation.Value(
                            "${app.aws.dynamodb.table-prefix:BudgetBuddy}")
                    final String tablePrefix) {
        final String tableName = tablePrefix + "-Household";
        this.table = enhancedClient.table(tableName, TableSchema.fromBean(HouseholdTable.class));
    }

    public Optional<HouseholdTable> findByUserId(final String userId) {
        if (userId == null || userId.isEmpty()) {
            return Optional.empty();
        }
        try {
            final HouseholdTable row = table.getItem(Key.builder().partitionValue(userId).build());
            return Optional.ofNullable(row);
        } catch (software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException e) {
            return Optional.empty();
        }
    }

    /**
     * Save with optimistic concurrency. Reads the incoming row's current {@code version},
     * conditions the put on that version matching the stored one, and bumps to version+1 on
     * success. First-time writes (version == null) condition on attribute_not_exists(version) so
     * two parallel creates don't race either.
     */
    public HouseholdTable save(final HouseholdTable row) {
        final Instant now = Instant.now();
        if (row.getCreatedAt() == null) {
            row.setCreatedAt(now);
        }
        row.setUpdatedAt(now);

        final Long expected = row.getVersion();
        row.setVersion(expected == null ? 1L : expected + 1L);

        final Expression condition;
        if (expected == null) {
            condition = Expression.builder().expression("attribute_not_exists(version)").build();
        } else {
            final Map<String, AttributeValue> values = new HashMap<>();
            values.put(":expectedVersion", AttributeValue.fromN(expected.toString()));
            condition =
                    Expression.builder()
                            .expression("version = :expectedVersion")
                            .expressionValues(values)
                            .build();
        }

        try {
            com.budgetbuddy.util.RetryHelper.executeDynamoDbWithRetry(
                    () -> {
                        table.putItem(
                                PutItemEnhancedRequest.builder(HouseholdTable.class)
                                        .item(row)
                                        .conditionExpression(condition)
                                        .build());
                        return null;
                    });
        } catch (ConditionalCheckFailedException e) {
            throw new OptimisticLockException(
                    "Household row was modified concurrently — re-read and retry. userId="
                            + row.getUserId());
        }
        return row;
    }

    public void deleteByUserId(final String userId) {
        if (userId == null || userId.isEmpty()) {
            return;
        }
        try {
            table.deleteItem(Key.builder().partitionValue(userId).build());
        } catch (software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException ignored) {
            // Degrade gracefully when table isn't provisioned.
        }
    }
}
