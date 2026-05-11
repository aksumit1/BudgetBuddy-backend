package com.budgetbuddy.util;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

/**
 * Optimistic Locking Helper Uses updatedAt timestamps for optimistic concurrency control
 *
 * <p>MEDIUM PRIORITY: Prevents lost updates in concurrent scenarios
 *
 * <p>Strategy: Use updatedAt timestamp as version number - On update, check that updatedAt matches
 * expected value - If mismatch, throw exception indicating concurrent modification - Client can
 * retry with fresh data
 */
// PMD's OnlyOneReturn fights guard-clause idiom — the codebase intentionally
// uses early returns for clarity (validation guards, fail-fast patterns).
@SuppressWarnings("PMD.OnlyOneReturn")
public final class OptimisticLockingHelper {

    private static final Logger LOGGER = LoggerFactory.getLogger(OptimisticLockingHelper.class);

    private OptimisticLockingHelper() {
        // Utility class
    }

    /**
     * Build condition expression for optimistic locking Checks that updatedAt matches expected
     * value
     *
     * @param expectedUpdatedAt Expected updatedAt timestamp (from read)
     * @return Condition expression string
     */
    public static String buildOptimisticLockCondition(final Instant expectedUpdatedAt) {
        if (expectedUpdatedAt == null) {
            // If no timestamp, check that updatedAt doesn't exist (new record)
            return "attribute_not_exists(updatedAt)";
        }
        // Check that updatedAt matches expected value
        return "updatedAt = :expectedUpdatedAt";
    }

    /**
     * Build condition expression attribute values for optimistic locking
     *
     * @param expectedUpdatedAt Expected updatedAt timestamp
     * @return Map of expression attribute values (can be empty if expectedUpdatedAt is null)
     */
    public static java.util.Map<
                    String, software.amazon.awssdk.services.dynamodb.model.AttributeValue>
            buildOptimisticLockAttributeValues(final Instant expectedUpdatedAt) {
        final java.util.Map<String, software.amazon.awssdk.services.dynamodb.model.AttributeValue>
                values = new java.util.HashMap<>();

        if (expectedUpdatedAt != null) {
            values.put(
                    ":expectedUpdatedAt",
                    software.amazon.awssdk.services.dynamodb.model.AttributeValue.builder()
                            .s(expectedUpdatedAt.toString())
                            .build());
        }

        return values;
    }

    /**
     * Handle conditional check failure for optimistic locking
     *
     * @param exception The ConditionalCheckFailedException
     * @param operation Operation name for logging
     * @param recordId Record ID for logging
     * @throws com.budgetbuddy.exception.AppException with appropriate error code
     */
    public static void handleOptimisticLockFailure(
            final ConditionalCheckFailedException exception,
            final String operation,
            final String recordId) {

        if (LOGGER.isWarnEnabled()) {
            LOGGER.warn(
                    "Optimistic lock failure - Operation: {}, Record: {}, Message: {}",
                    operation,
                    recordId,
                    exception.getMessage());
        }

        // Record in monitoring (if available)
        // Note: Monitoring integration should be done at the service/repository layer
        // where ApplicationContext is available via dependency injection
        LOGGER.debug(
                "Optimistic lock failure recorded - Operation: {}, Record: {}",
                operation,
                recordId);

        // Throw user-friendly exception
        throw new com.budgetbuddy.exception.AppException(
                com.budgetbuddy.exception.ErrorCode.RECORD_ALREADY_EXISTS,
                "Record was modified by another operation. Please refresh and try again.");
    }

    /**
     * Check if update should use optimistic locking Returns true if record has an updatedAt
     * timestamp (not a new record)
     */
    public static boolean shouldUseOptimisticLock(final Instant currentUpdatedAt) {
        return currentUpdatedAt != null;
    }
}
