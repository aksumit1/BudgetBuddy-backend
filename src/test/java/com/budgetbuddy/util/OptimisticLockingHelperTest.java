package com.budgetbuddy.util;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit Tests for OptimisticLockingHelper
 */
class OptimisticLockingHelperTest {

    @Test
    void testBuildOptimisticLockCondition_WithNullTimestamp_ReturnsAttributeNotExists() {
        // When
        String condition = OptimisticLockingHelper.buildOptimisticLockCondition(null);

        // Then
        assertEquals("attribute_not_exists(updatedAt)", condition);
    }

    @Test
    void testBuildOptimisticLockCondition_WithTimestamp_ReturnsEqualityCheck() {
        // Given
        Instant timestamp = Instant.now();

        // When
        String condition = OptimisticLockingHelper.buildOptimisticLockCondition(timestamp);

        // Then
        assertEquals("updatedAt = :expectedUpdatedAt", condition);
    }

    @Test
    void testBuildOptimisticLockAttributeValues_WithNullTimestamp_ReturnsEmptyMap() {
        // When
        Map<String, AttributeValue> values = OptimisticLockingHelper.buildOptimisticLockAttributeValues(null);

        // Then
        assertNotNull(values);
        assertTrue(values.isEmpty());
    }

    @Test
    void testBuildOptimisticLockAttributeValues_WithTimestamp_ReturnsMapWithValue() {
        // Given
        Instant timestamp = Instant.now();

        // When
        Map<String, AttributeValue> values = OptimisticLockingHelper.buildOptimisticLockAttributeValues(timestamp);

        // Then
        assertNotNull(values);
        assertTrue(values.containsKey(":expectedUpdatedAt"));
        assertEquals(timestamp.toString(), values.get(":expectedUpdatedAt").s());
    }

    @Test
    void testHandleOptimisticLockFailure_ThrowsAppException() {
        // Given
        ConditionalCheckFailedException exception = ConditionalCheckFailedException.builder()
                .message("Conditional check failed")
                .build();

        // When/Then
        com.budgetbuddy.exception.AppException appException = assertThrows(
                com.budgetbuddy.exception.AppException.class,
                () -> OptimisticLockingHelper.handleOptimisticLockFailure(exception, "update", "record-123"));

        assertEquals(com.budgetbuddy.exception.ErrorCode.RECORD_ALREADY_EXISTS, appException.getErrorCode());
        assertTrue(appException.getMessage().contains("modified by another operation"));
    }

    @Test
    void testShouldUseOptimisticLock_WithNullTimestamp_ReturnsFalse() {
        // When
        boolean shouldUse = OptimisticLockingHelper.shouldUseOptimisticLock(null);

        // Then
        assertFalse(shouldUse);
    }

    @Test
    void testShouldUseOptimisticLock_WithTimestamp_ReturnsTrue() {
        // Given
        Instant timestamp = Instant.now();

        // When
        boolean shouldUse = OptimisticLockingHelper.shouldUseOptimisticLock(timestamp);

        // Then
        assertTrue(shouldUse);
    }
}
