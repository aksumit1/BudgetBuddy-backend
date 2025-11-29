package com.budgetbuddy.security.circuitbreaker;

import com.budgetbuddy.AWSTestConfiguration;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Circuit Breaker Configuration
 * Verifies that Plaid circuit breaker has appropriate settings for handling transient failures
 */
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class, 
        properties = {
            "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration"
        })
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
class CircuitBreakerConfigurationTest {

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Test
    void testPlaidCircuitBreaker_HasCorrectConfiguration() {
        // When - Get Plaid circuit breaker
        CircuitBreaker plaidCircuitBreaker = circuitBreakerRegistry.circuitBreaker("plaid");
        CircuitBreakerConfig config = plaidCircuitBreaker.getCircuitBreakerConfig();

        // Then - Verify configuration values
        assertEquals(70, config.getFailureRateThreshold(), 
                "Failure rate threshold should be 70% (more tolerant of transient errors)");
        
        // Note: getWaitDurationInOpenState() may not be available in all Resilience4j versions
        // Verify through YAML config or by checking actual behavior
        assertEquals(30, config.getSlidingWindowSize(),
                "Sliding window size should be 30 (more buffer for genuine failures)");
        
        assertEquals(15, config.getMinimumNumberOfCalls(),
                "Minimum number of calls should be 15 (prevents premature opening)");
        
        assertEquals(5, config.getPermittedNumberOfCallsInHalfOpenState(),
                "Permitted calls in half-open should be 5 (more test calls to verify recovery)");
        
        assertTrue(config.isAutomaticTransitionFromOpenToHalfOpenEnabled(),
                "Automatic transition from open to half-open should be enabled");
    }

    @Test
    void testPlaidCircuitBreaker_InitialStateIsClosed() {
        // When - Get Plaid circuit breaker
        CircuitBreaker plaidCircuitBreaker = circuitBreakerRegistry.circuitBreaker("plaid");

        // Then - Initial state should be CLOSED
        assertEquals(CircuitBreaker.State.CLOSED, plaidCircuitBreaker.getState(),
                "Circuit breaker should start in CLOSED state");
    }

    @Test
    void testPlaidCircuitBreaker_ConfigurationIsMoreLenientThanDefault() {
        // When - Get Plaid circuit breaker and default config
        CircuitBreaker plaidCircuitBreaker = circuitBreakerRegistry.circuitBreaker("plaid");
        CircuitBreakerConfig plaidConfig = plaidCircuitBreaker.getCircuitBreakerConfig();
        
        // Default config from registry
        CircuitBreakerConfig defaultConfig = circuitBreakerRegistry.getDefaultConfig();

        // Then - Plaid config should be more lenient
        assertTrue(plaidConfig.getFailureRateThreshold() > defaultConfig.getFailureRateThreshold(),
                "Plaid failure rate threshold should be higher than default");
        
        assertTrue(plaidConfig.getSlidingWindowSize() > defaultConfig.getSlidingWindowSize(),
                "Plaid sliding window size should be larger than default");
        
        assertTrue(plaidConfig.getMinimumNumberOfCalls() > defaultConfig.getMinimumNumberOfCalls(),
                "Plaid minimum number of calls should be higher than default");
    }
}

