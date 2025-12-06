package com.budgetbuddy.config;

import com.budgetbuddy.AWSTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for HealthCheckConfig
 */
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
    void testDynamoDbHealthIndicator_IsCreated() {
        // Then
        assertNotNull(dynamoDbHealthIndicator, "DynamoDB health indicator should be created");
    }

    @Test
    void testDynamoDbHealthIndicator_ReturnsHealth() {
        // When
        var health = dynamoDbHealthIndicator.health();

        // Then
        assertNotNull(health, "Health should not be null");
        assertNotNull(health.getStatus(), "Health status should not be null");
    }

    @Test
    void testReadinessHealthIndicator_IsCreated() {
        // Then
        assertNotNull(readinessHealthIndicator, "Readiness health indicator should be created");
    }

    @Test
    void testReadinessHealthIndicator_ReturnsUp() {
        // When
        var health = readinessHealthIndicator.health();

        // Then
        assertNotNull(health, "Health should not be null");
        assertEquals("UP", health.getStatus().getCode(), "Readiness should be UP");
    }

    @Test
    void testLivenessHealthIndicator_IsCreated() {
        // Then
        assertNotNull(livenessHealthIndicator, "Liveness health indicator should be created");
    }

    @Test
    void testLivenessHealthIndicator_ReturnsUp() {
        // When
        var health = livenessHealthIndicator.health();

        // Then
        assertNotNull(health, "Health should not be null");
        assertEquals("UP", health.getStatus().getCode(), "Liveness should be UP");
    }
}

