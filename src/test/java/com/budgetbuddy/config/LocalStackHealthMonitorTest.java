package com.budgetbuddy.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit Tests for LocalStackHealthMonitor
 */
class LocalStackHealthMonitorTest {

    private LocalStackHealthMonitor monitor;

    @BeforeEach
    void setUp() {
        monitor = new LocalStackHealthMonitor();
    }

    @Test
    void testIsHealthy_InitialState_ReturnsTrue() {
        // When
        boolean healthy = monitor.isHealthy();

        // Then
        assertTrue(healthy);
    }

    @Test
    void testCheckLocalStackHealth_WithEmptyEndpoint_DoesNotProcess() {
        // Given
        ReflectionTestUtils.setField(monitor, "dynamoDbEndpoint", "");

        // When - should not throw and should return early
        assertDoesNotThrow(() -> {
            // Use reflection to call the private method for testing
            ReflectionTestUtils.invokeMethod(monitor, "checkLocalStackHealth");
        });

        // Then - health status should remain unchanged
        assertTrue(monitor.isHealthy());
    }

    @Test
    void testCheckLocalStackHealth_WithNullEndpoint_DoesNotProcess() {
        // Given
        ReflectionTestUtils.setField(monitor, "dynamoDbEndpoint", null);

        // When - should not throw and should return early
        assertDoesNotThrow(() -> {
            ReflectionTestUtils.invokeMethod(monitor, "checkLocalStackHealth");
        });

        // Then - health status should remain unchanged
        assertTrue(monitor.isHealthy());
    }

    @Test
    void testIsHealthy_WithFailuresBelowThreshold_ReturnsTrue() {
        // Given
        ReflectionTestUtils.setField(monitor, "consecutiveFailures", 2); // Below threshold of 3

        // When
        boolean healthy = monitor.isHealthy();

        // Then
        assertTrue(healthy);
    }

    @Test
    void testIsHealthy_WithFailuresAtThreshold_ReturnsFalse() {
        // Given
        ReflectionTestUtils.setField(monitor, "consecutiveFailures", 3); // At threshold

        // When
        boolean healthy = monitor.isHealthy();

        // Then
        assertFalse(healthy);
    }

    @Test
    void testIsHealthy_WithFailuresAboveThreshold_ReturnsFalse() {
        // Given
        ReflectionTestUtils.setField(monitor, "consecutiveFailures", 5); // Above threshold

        // When
        boolean healthy = monitor.isHealthy();

        // Then
        assertFalse(healthy);
    }
}
