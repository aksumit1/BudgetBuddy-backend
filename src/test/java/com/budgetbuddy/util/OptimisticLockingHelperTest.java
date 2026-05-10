package com.budgetbuddy.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

/** Unit Tests for OptimisticLockingHelper */
class OptimisticLockingHelperTest {

    @Test
    void testBuildOptimisticLockConditionWithNullTimestampReturnsAttributeNotExists() {
        // When
        final String condition = OptimisticLockingHelper.buildOptimisticLockCondition(null);

        // Then
        assertEquals("attribute_not_exists(updatedAt)", condition);
    }

    @Test
    void testBuildOptimisticLockConditionWithTimestampReturnsEqualityCheck() {
        // Given
        final Instant timestamp = Instant.now();

        // When
        final String condition = OptimisticLockingHelper.buildOptimisticLockCondition(timestamp);

        // Then
        assertEquals("updatedAt = :expectedUpdatedAt", condition);
    }

    @Test
    void testBuildOptimisticLockAttributeValuesWithNullTimestampReturnsEmptyMap() {
        // When
        final Map<String, AttributeValue> values =
                OptimisticLockingHelper.buildOptimisticLockAttributeValues(null);

        // Then
        assertNotNull(values);
        assertTrue(values.isEmpty());
    }

    @Test
    void testBuildOptimisticLockAttributeValuesWithTimestampReturnsMapWithValue() {
        // Given
        final Instant timestamp = Instant.now();

        // When
        final Map<String, AttributeValue> values =
                OptimisticLockingHelper.buildOptimisticLockAttributeValues(timestamp);

        // Then
        assertNotNull(values);
        assertTrue(values.containsKey(":expectedUpdatedAt"));
        assertEquals(timestamp.toString(), values.get(":expectedUpdatedAt").s());
    }

    @Test
    void testHandleOptimisticLockFailureThrowsAppException() {
        // Given
        final ConditionalCheckFailedException exception =
                ConditionalCheckFailedException.builder()
                        .message("Conditional check failed")
                        .build();

        // When/Then
        final com.budgetbuddy.exception.AppException appException =
                assertThrows(
                        com.budgetbuddy.exception.AppException.class,
                        () ->
                                OptimisticLockingHelper.handleOptimisticLockFailure(
                                        exception, "update", "record-123"));

        assertEquals(
                com.budgetbuddy.exception.ErrorCode.RECORD_ALREADY_EXISTS,
                appException.getErrorCode());
        assertTrue(appException.getMessage().contains("modified by another operation"));
    }

    @Test
    void testShouldUseOptimisticLockWithNullTimestampReturnsFalse() {
        // When
        final boolean shouldUse = OptimisticLockingHelper.shouldUseOptimisticLock(null);

        // Then
        assertFalse(shouldUse);
    }

    @Test
    void testShouldUseOptimisticLockWithTimestampReturnsTrue() {
        // Given
        final Instant timestamp = Instant.now();

        // When
        final boolean shouldUse = OptimisticLockingHelper.shouldUseOptimisticLock(timestamp);

        // Then
        assertTrue(shouldUse);
    }
}
