package com.budgetbuddy.repository.dynamodb;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.model.PutItemEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

/**
 * Reusable optimistic-concurrency helper for DynamoDB writes.
 *
 * <p>Every participating table has a {@code Long version} column. The helper reads the row's
 * current version, writes version+1 under a condition that the stored version still equals the one
 * we read. If another writer has committed in between, DynamoDB rejects the write with {@link
 * ConditionalCheckFailedException}, which we translate into {@link OptimisticLockException} so
 * callers can react (re-read + retry, or surface a 409 to the UI).
 *
 * <p>First-time writes (version == null) condition on {@code attribute_not_exists(version)} so two
 * concurrent creates don't double-insert either.
 *
 * <p>Callers must migrate deliberately — wrapping an unprepared call site in this helper will turn
 * a silent lost-update into a visible exception, which is the point, but the caller needs to handle
 * it.
 */
public final class OptimisticLockHelper {

    public static class OptimisticLockException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public OptimisticLockException(final String msg) {
            super(msg);
        }
    }

    private OptimisticLockHelper() {}

    /**
     * Save {@code row} under optimistic concurrency.
     *
     * @param table the enhanced-client table
     * @param row the row to write (will be mutated — version incremented)
     * @param versionGetter e.g. {@code MyRow::getVersion}
     * @param versionSetter e.g. {@code MyRow::setVersion}
     * @param contextForLog short identifier for the exception message (userId, etc.)
     */
    public static <T> T saveWithLock(
            final DynamoDbTable<T> table,
            final T row,
            final Function<T, Long> versionGetter,
            final Consumer<Long> versionSetter,
            final String contextForLog) {
        final Long expected = versionGetter.apply(row);
        versionSetter.accept(expected == null ? 1L : expected + 1L);

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

        @SuppressWarnings("unchecked")
        final Class<T> rowClass = (Class<T>) row.getClass();
        try {
            com.budgetbuddy.util.RetryHelper.executeDynamoDbWithRetry(
                    () -> {
                        table.putItem(
                                PutItemEnhancedRequest.builder(rowClass)
                                        .item(row)
                                        .conditionExpression(condition)
                                        .build());
                        return null;
                    });
        } catch (ConditionalCheckFailedException e) {
            // Restore the previous version so the caller can re-read without
            // the bumped value polluting a retry.
            versionSetter.accept(expected);
            throw new OptimisticLockException(
                    "Row was modified concurrently — re-read and retry. ctx=" + contextForLog);
        }
        return row;
    }
}
