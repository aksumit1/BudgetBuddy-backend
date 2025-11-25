package com.budgetbuddy.security.circuitbreaker;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Circuit Breaker Configuration
 * Prevents cascading failures by breaking the circuit when services are down
 * Implements trust by design - failures are handled gracefully
 */
@Configuration
public class CircuitBreakerConfig {

    @Bean
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
    public CircuitBreaker plaidCircuitBreaker((final CircuitBreakerRegistry registry) {
        return registry.circuitBreaker("plaid", io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.custom()
                .failureRateThreshold(60) // Higher threshold for external service
                .waitDurationInOpenState(Duration.ofMinutes(1))
                .slidingWindowSize(20)
                .minimumNumberOfCalls(10)
                .build());
    }

    @Bean
    public CircuitBreaker dynamoDbCircuitBreaker((final CircuitBreakerRegistry registry) {
        return registry.circuitBreaker("dynamodb", io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.custom()
                .failureRateThreshold(40) // Lower threshold for critical service
                .waitDurationInOpenState(Duration.ofSeconds(15))
                .slidingWindowSize(15)
                .minimumNumberOfCalls(5)
                .build());
    }

    @Bean
    public CircuitBreaker s3CircuitBreaker((final CircuitBreakerRegistry registry) {
        return registry.circuitBreaker("s3", io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .build());
    }
}

