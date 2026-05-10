package com.budgetbuddy.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** Unit Tests for LocalStackHealthMonitor */
class LocalStackHealthMonitorTest {

    private LocalStackHealthMonitor monitor;

    @BeforeEach
    void setUp() {
        monitor = new LocalStackHealthMonitor();
    }

    @Test
    void testIsHealthyInitialStateReturnsTrue() {
        // When
        final boolean healthy = monitor.isHealthy();

        // Then
        assertTrue(healthy);
    }

    @Test
    void testCheckLocalStackHealthWithEmptyEndpointDoesNotProcess() {
        // Given
        ReflectionTestUtils.setField(monitor, "dynamoDbEndpoint", "");

        // When - should not throw and should return early
        assertDoesNotThrow(
                () -> {
                    // Use reflection to call the private method for testing
                    ReflectionTestUtils.invokeMethod(monitor, "checkLocalStackHealth");
                });

        // Then - health status should remain unchanged
        assertTrue(monitor.isHealthy());
    }

    @Test
    void testCheckLocalStackHealthWithNullEndpointDoesNotProcess() {
        // Given
        ReflectionTestUtils.setField(monitor, "dynamoDbEndpoint", null);

        // When - should not throw and should return early
        assertDoesNotThrow(
                () -> {
                    ReflectionTestUtils.invokeMethod(monitor, "checkLocalStackHealth");
                });

        // Then - health status should remain unchanged
        assertTrue(monitor.isHealthy());
    }

    @Test
    void testIsHealthyWithFailuresBelowThresholdReturnsTrue() {
        // Given
        ReflectionTestUtils.setField(monitor, "consecutiveFailures", 2); // Below threshold of 3

        // When
        final boolean healthy = monitor.isHealthy();

        // Then
        assertTrue(healthy);
    }

    @Test
    void testIsHealthyWithFailuresAtThresholdReturnsFalse() {
        // Given
        ReflectionTestUtils.setField(monitor, "consecutiveFailures", 3); // At threshold

        // When
        final boolean healthy = monitor.isHealthy();

        // Then
        assertFalse(healthy);
    }

    @Test
    void testIsHealthyWithFailuresAboveThresholdReturnsFalse() {
        // Given
        ReflectionTestUtils.setField(monitor, "consecutiveFailures", 5); // Above threshold

        // When
        final boolean healthy = monitor.isHealthy();

        // Then
        assertFalse(healthy);
    }
}
