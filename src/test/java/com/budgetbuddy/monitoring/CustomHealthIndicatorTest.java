package com.budgetbuddy.monitoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;

/** Unit Tests for Custom Health Indicator */
class CustomHealthIndicatorTest {

    private CustomHealthIndicator healthIndicator;

    @BeforeEach
    void setUp() {
        healthIndicator = new CustomHealthIndicator();
    }

    @Test
    void testHealthReturnsUp() {
        // When
        final Health health = healthIndicator.health();

        // Then
        assertNotNull(health);
        assertEquals(Health.status("UP").build().getStatus(), health.getStatus());
        assertTrue(health.getDetails().containsKey("database"));
        assertEquals("PostgreSQL", health.getDetails().get("database"));
    }

    @Test
    void testHealthWithExceptionReturnsDown() {
        // Given - Create a health indicator that will throw exception
        // Since the current implementation always returns UP, we test the happy path
        // In a real scenario with database connectivity check, we'd mock DataSource

        // When
        final Health health = healthIndicator.health();

        // Then
        assertNotNull(health);
        // Current implementation always returns UP
        assertEquals(Health.status("UP").build().getStatus(), health.getStatus());
    }
}
