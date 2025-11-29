package com.budgetbuddy.security.circuitbreaker;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Duration;

/**
 * Circuit Breaker Configuration
 * Prevents cascading failures by breaking the circuit when services are down
 * Implements trust by design - failures are handled gracefully
 */
@Configuration
public class CircuitBreakerConfig {

    @Bean
    @Primary
    @ConditionalOnMissingBean
    public CircuitBreakerRegistry circuitBreakerRegistry() {
        io.github.resilience4j.circuitbreaker.CircuitBreakerConfig config = io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.custom()
                .failureRateThreshold(50) // Open circuit if 50% of requests fail
                .waitDurationInOpenState(Duration.ofSeconds(30)) // Wait 30s before half-open
                .slidingWindowSize(10) // Last 10 requests
                .minimumNumberOfCalls(5) // Need at least 5 calls before opening
                .permittedNumberOfCallsInHalfOpenState(3) // Allow 3 calls in half-open
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .recordExceptions(
                        java.net.ConnectException.class,
                        java.net.SocketTimeoutException.class,
                        java.util.concurrent.TimeoutException.class,
                        org.springframework.web.client.HttpServerErrorException.class
                )
                .build();

        return CircuitBreakerRegistry.of(config);
    }

    @Bean
    public CircuitBreaker plaidCircuitBreaker(final CircuitBreakerRegistry registry) {
        io.github.resilience4j.circuitbreaker.CircuitBreakerConfig config = io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.custom()
                .failureRateThreshold(70) // Higher threshold for external service (70% failure rate)
                .waitDurationInOpenState(Duration.ofMinutes(2)) // Wait 2 minutes before half-open
                .slidingWindowSize(30) // Look at last 30 calls (more buffer)
                .minimumNumberOfCalls(15) // Need at least 15 calls before opening (prevents premature opening)
                .permittedNumberOfCallsInHalfOpenState(5) // Allow 5 test calls in half-open
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .recordExceptions(
                        java.net.ConnectException.class,
                        java.net.SocketTimeoutException.class,
                        java.util.concurrent.TimeoutException.class,
                        org.springframework.web.client.HttpServerErrorException.class,
                        com.budgetbuddy.exception.AppException.class // Record AppException as failure
                )
                .build();
        
        CircuitBreaker circuitBreaker = registry.circuitBreaker("plaid", config);
        
        // Add event listener to log circuit breaker state transitions
        circuitBreaker.getEventPublisher()
                .onStateTransition(event -> {
                    org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(CircuitBreakerConfig.class);
                    logger.warn("⚠️ Plaid Circuit Breaker state transition: {} -> {} (at {})", 
                            event.getStateTransition().getFromState(),
                            event.getStateTransition().getToState(),
                            java.time.Instant.now());
                })
                .onFailureRateExceeded(event -> {
                    org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(CircuitBreakerConfig.class);
                    logger.error("🚨 Plaid Circuit Breaker failure rate exceeded: {}%", 
                            event.getFailureRate());
                })
                .onCallNotPermitted(event -> {
                    org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(CircuitBreakerConfig.class);
                    io.github.resilience4j.circuitbreaker.CircuitBreaker.Metrics metrics = circuitBreaker.getMetrics();
                    logger.warn("🚫 Plaid Circuit Breaker call not permitted - circuit is OPEN. " +
                            "Metrics: failureRate={}%, numberOfBufferedCalls={}, numberOfFailedCalls={}", 
                            metrics.getFailureRate(),
                            metrics.getNumberOfBufferedCalls(),
                            metrics.getNumberOfFailedCalls());
                });
        
        return circuitBreaker;
    }

    @Bean
    public CircuitBreaker dynamoDbCircuitBreaker(final CircuitBreakerRegistry registry) {
        return registry.circuitBreaker("dynamodb", io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.custom()
                .failureRateThreshold(40) // Lower threshold for critical service
                .waitDurationInOpenState(Duration.ofSeconds(15))
                .slidingWindowSize(15)
                .minimumNumberOfCalls(5)
                .build());
    }

    @Bean
    public CircuitBreaker s3CircuitBreaker(final CircuitBreakerRegistry registry) {
        return registry.circuitBreaker("s3", io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .build());
    }
}

