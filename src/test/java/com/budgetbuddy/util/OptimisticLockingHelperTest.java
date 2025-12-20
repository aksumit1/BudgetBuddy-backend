package com.budgetbuddy.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for OptimisticLockingHelper
 */
class OptimisticLockingHelperTest {

    @Test
    @DisplayName("Should build condition for new record (null updatedAt)")
    void testBuildOptimisticLockCondition_NullUpdatedAt() {
        // When
        String condition = OptimisticLockingHelper.buildOptimisticLockCondition(null);

        // Then
        assertEquals("attribute_not_exists(updatedAt)", condition);
    }

    @Test
    @DisplayName("Should build condition for existing record")
    void testBuildOptimisticLockCondition_WithUpdatedAt() {
        // Given
        Instant updatedAt = Instant.now();

        // When
        String condition = OptimisticLockingHelper.buildOptimisticLockCondition(updatedAt);

        // Then
        assertEquals("updatedAt = :expectedUpdatedAt", condition);
    }

    @Test
    @DisplayName("Should build empty attribute values for null updatedAt")
    void testBuildOptimisticLockAttributeValues_NullUpdatedAt() {
        // When
        Map<String, AttributeValue> values = OptimisticLockingHelper.buildOptimisticLockAttributeValues(null);

        // Then
        assertNotNull(values);
        assertTrue(values.isEmpty());
    }

    @Test
    @DisplayName("Should build attribute values for existing record")
    void testBuildOptimisticLockAttributeValues_WithUpdatedAt() {
        // Given
        Instant updatedAt = Instant.parse("2024-01-01T00:00:00Z");

        // When
        Map<String, AttributeValue> values = OptimisticLockingHelper.buildOptimisticLockAttributeValues(updatedAt);

        // Then
        assertNotNull(values);
        assertEquals(1, values.size());
        assertTrue(values.containsKey(":expectedUpdatedAt"));
        AttributeValue value = values.get(":expectedUpdatedAt");
        assertNotNull(value);
        assertEquals(updatedAt.toString(), value.s());
    }

    @Test
    @DisplayName("Should handle optimistic lock failure")
    void testHandleOptimisticLockFailure() {
        // Given
        ConditionalCheckFailedException exception = ConditionalCheckFailedException.builder()
                .message("Conditional check failed")
                .build();
        String operation = "updateTransaction";
        String recordId = "txn-123";

        // When/Then
        com.budgetbuddy.exception.AppException appException = assertThrows(
                com.budgetbuddy.exception.AppException.class,
                () -> OptimisticLockingHelper.handleOptimisticLockFailure(exception, operation, recordId)
        );

        assertEquals(com.budgetbuddy.exception.ErrorCode.RECORD_ALREADY_EXISTS, appException.getErrorCode());
        assertTrue(appException.getMessage().contains("modified by another operation"));
    }

    @Test
    @DisplayName("Should return true for existing record (should use optimistic lock)")
    void testShouldUseOptimisticLock_WithUpdatedAt() {
        // Given
        Instant updatedAt = Instant.now();

        // When
        boolean result = OptimisticLockingHelper.shouldUseOptimisticLock(updatedAt);

        // Then
        assertTrue(result);
    }

    @Test
    @DisplayName("Should return false for new record (should not use optimistic lock)")
    void testShouldUseOptimisticLock_NullUpdatedAt() {
        // When
        boolean result = OptimisticLockingHelper.shouldUseOptimisticLock(null);

        // Then
        assertFalse(result);
    }

    @Test
    @DisplayName("Should build complete optimistic lock condition and values")
    void testBuildOptimisticLock_CompleteFlow() {
        // Given
        Instant expectedUpdatedAt = Instant.parse("2024-01-01T12:00:00Z");

        // When
        String condition = OptimisticLockingHelper.buildOptimisticLockCondition(expectedUpdatedAt);
        Map<String, AttributeValue> values = OptimisticLockingHelper.buildOptimisticLockAttributeValues(expectedUpdatedAt);

        // Then
        assertEquals("updatedAt = :expectedUpdatedAt", condition);
        assertEquals(1, values.size());
        assertEquals(expectedUpdatedAt.toString(), values.get(":expectedUpdatedAt").s());
    }
}

