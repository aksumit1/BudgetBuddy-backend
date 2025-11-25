package com.budgetbuddy.config;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.ListTablesRequest;

/**
 * Enhanced Health Check Configuration
 * Provides comprehensive health checks for all dependencies
 */
@Configuration
public class HealthCheckConfig {

    @Bean
    public HealthIndicator dynamoDbHealthIndicator(final DynamoDbClient dynamoDbClient) {
        return new HealthIndicator() {
            @Override
            public Health health() {
                try {
                    dynamoDbClient.listTables(ListTablesRequest.builder().build());
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
                return Health.up()
                        .withDetail("status", "ready")
                        .build();
            }
        };
    }

    @Bean
    public HealthIndicator livenessHealthIndicator() {
        return new HealthIndicator() {
            @Override
            public Health health() {
                // Simple liveness check - application is running
                return Health.up()
                        .withDetail("status", "alive")
                        .build();
            }
        };
    }
}

