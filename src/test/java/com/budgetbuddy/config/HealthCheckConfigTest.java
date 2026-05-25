package com.budgetbuddy.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.config.StartupReadinessProbe;
import com.budgetbuddy.util.TableInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

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
    private HealthIndicator bbReadinessHealthIndicator;

    @Autowired(required = false)
    private HealthIndicator bbLivenessHealthIndicator;

    @Autowired private DynamoDbClient dynamoDbClient;

    @Autowired private StartupReadinessProbe readinessProbe;

    @BeforeEach
    void seedTablesAndProbe() {
        // The readiness probe runs once at app startup; if it lost the race
        // with table creation (or LocalStack was empty when the probe ran),
        // the indicator stays DOWN for the rest of the test JVM. Initialize
        // the tables and re-trigger the probe so testReadinessHealthIndicatorReturnsUp
        // observes UP regardless of prior test ordering.
        TableInitializer.initializeTables(dynamoDbClient);
        readinessProbe.probeAtStartup();
    }

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
        assertNotNull(bbReadinessHealthIndicator, "Readiness health indicator should be created");
    }

    @Test
    void testReadinessHealthIndicatorReturnsUp() {
        // When
        final var health = bbReadinessHealthIndicator.health();

        // Then
        assertNotNull(health, "Health should not be null");
        assertEquals("UP", health.getStatus().getCode(), "Readiness should be UP");
    }

    @Test
    void testLivenessHealthIndicatorIsCreated() {
        // Then
        assertNotNull(bbLivenessHealthIndicator, "Liveness health indicator should be created");
    }

    @Test
    void testLivenessHealthIndicatorReturnsUp() {
        // When
        final var health = bbLivenessHealthIndicator.health();

        // Then
        assertNotNull(health, "Health should not be null");
        assertEquals("UP", health.getStatus().getCode(), "Liveness should be UP");
    }
}
