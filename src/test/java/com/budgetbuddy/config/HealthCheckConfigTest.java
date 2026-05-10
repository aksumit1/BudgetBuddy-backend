package com.budgetbuddy.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.budgetbuddy.AWSTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/** Tests for HealthCheckConfig */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
@SuppressWarnings("PMD.LawOfDemeter")
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
class HealthCheckConfigTest {

    @Autowired(required = false)
    private HealthIndicator dynamoDbHealthIndicator;

    @Autowired(required = false)
    private HealthIndicator readinessHealthIndicator;

    @Autowired(required = false)
    private HealthIndicator livenessHealthIndicator;

    @Test
    void testDynamoDbHealthIndicatorIsCreated() {
        // Then
        assertNotNull(dynamoDbHealthIndicator, "DynamoDB health indicator should be created");
    }

    @Test
    void testDynamoDbHealthIndicatorReturnsHealth() {
        // When
        final var health = dynamoDbHealthIndicator.health();

        // Then
        assertNotNull(health, "Health should not be null");
        assertNotNull(health.getStatus(), "Health status should not be null");
    }

    @Test
    void testReadinessHealthIndicatorIsCreated() {
        // Then
        assertNotNull(readinessHealthIndicator, "Readiness health indicator should be created");
    }

    @Test
    void testReadinessHealthIndicatorReturnsUp() {
        // When
        final var health = readinessHealthIndicator.health();

        // Then
        assertNotNull(health, "Health should not be null");
        assertEquals("UP", health.getStatus().getCode(), "Readiness should be UP");
    }

    @Test
    void testLivenessHealthIndicatorIsCreated() {
        // Then
        assertNotNull(livenessHealthIndicator, "Liveness health indicator should be created");
    }

    @Test
    void testLivenessHealthIndicatorReturnsUp() {
        // When
        final var health = livenessHealthIndicator.health();

        // Then
        assertNotNull(health, "Health should not be null");
        assertEquals("UP", health.getStatus().getCode(), "Liveness should be UP");
    }
}
