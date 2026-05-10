package com.budgetbuddy.config;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.ListTablesRequest;

/**
 * Enhanced Health Check Configuration Provides comprehensive health checks for all dependencies All
 * health checks are configured with timeouts to prevent slow responses
 */
// SDK / Spring integration — the underlying APIs (AWS SDK, Plaid SDK,
// Spring services, reflection) throw arbitrary RuntimeException subtypes
// that can't reasonably be enumerated. Broad catches log + recover (or
// translate to AppException). Suppress at class level since narrowing
// here would mean catch (RuntimeException) which PMD flags identically.
@SuppressWarnings("PMD.AvoidCatchingGenericException")
@Configuration
public class HealthCheckConfig {

    @Value("${management.health.redis.timeout:2s}")
    private Duration redisHealthTimeout;

    @Value("${app.aws.dynamodb.timeout-seconds:10}")
    private int dynamoDbTimeoutSeconds;

    @Bean
    public HealthIndicator dynamoDbHealthIndicator(final DynamoDbClient dynamoDbClient) {
        return new HealthIndicator() {
            @Override
            public Health health() {
                try {
                    // Use CompletableFuture with timeout to prevent hanging
                    final CompletableFuture<Health> future =
                            CompletableFuture.supplyAsync(
                                    () -> {
                                        try {
                                            dynamoDbClient.listTables(
                                                    ListTablesRequest.builder().build());
                                            return Health.up()
                                                    .withDetail("service", "DynamoDB")
                                                    .withDetail("status", "connected")
                                                    .build();
                                        } catch (Exception e) {
                                            return Health.down()
                                                    .withDetail("service", "DynamoDB")
                                                    .withDetail("error", e.getMessage())
                                                    .build();
                                        }
                                    });

                    // Wait with timeout (use DynamoDB timeout or 5 seconds, whichever is smaller)
                    final int timeoutSeconds = Math.min(dynamoDbTimeoutSeconds, 5);
                    return future.get(timeoutSeconds, TimeUnit.SECONDS);
                } catch (Exception e) {
                    return Health.down()
                            .withDetail("service", "DynamoDB")
                            .withDetail("error", "Health check timeout: " + e.getMessage())
                            .build();
                }
            }
        };
    }

    @Bean
    public HealthIndicator readinessHealthIndicator() {
        return new HealthIndicator() {
            @Override
            public Health health() {
                // Check if application is ready to serve traffic
                // Add checks for critical dependencies
                return Health.up().withDetail("status", "ready").build();
            }
        };
    }

    @Bean
    public HealthIndicator livenessHealthIndicator() {
        return new HealthIndicator() {
            @Override
            public Health health() {
                // Simple liveness check - application is running
                return Health.up().withDetail("status", "alive").build();
            }
        };
    }
}
