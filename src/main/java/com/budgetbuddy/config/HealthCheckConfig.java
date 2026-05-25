package com.budgetbuddy.config;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
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
@SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.OnlyOneReturn"})
@Configuration
public class HealthCheckConfig {

    private static final String DYNAMO_DB = "DynamoDB";

    private static final String SERVICE = "service";

    private static final String STATUS = "status";

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
                                                    .withDetail(SERVICE, DYNAMO_DB)
                                                    .withDetail(STATUS, "connected")
                                                    .build();
                                        } catch (Exception e) {
                                            return Health.down()
                                                    .withDetail(SERVICE, DYNAMO_DB)
                                                    .withDetail("error", e.getMessage())
                                                    .build();
                                        }
                                    });

                    // Wait with timeout (use DynamoDB timeout or 5 seconds, whichever is smaller)
                    final int timeoutSeconds = Math.min(dynamoDbTimeoutSeconds, 5);
                    return future.get(timeoutSeconds, TimeUnit.SECONDS);
                } catch (Exception e) {
                    return Health.down()
                            .withDetail(SERVICE, DYNAMO_DB)
                            .withDetail("error", "Health check timeout: " + e.getMessage())
                            .build();
                }
            }
        };
    }

    /**
     * Readiness indicator backed by {@link StartupReadinessProbe}. Returns DOWN with the probe's
     * last failure reason until every critical DynamoDB table has been confirmed reachable; UP once
     * the probe succeeds. Wire the ALB / k8s readiness probe at {@code /actuator/health/readiness}
     * — the existing path is gated by this bean's state.
     */
    // Spring Boot 4 reserves the bare names "readiness" and "liveness" for
    // the health groups (/actuator/health/{readiness,liveness}); a custom
    // HealthContributor with either name collides with the group registry.
    // Use bb-prefixed bean names so our DynamoDB-readiness contributor
    // appears at /actuator/health/bbReadiness without colliding.
    @Bean
    public HealthIndicator bbReadinessHealthIndicator(final StartupReadinessProbe probe) {
        return () -> {
            if (probe.isReady()) {
                return Health.up().withDetail(STATUS, "ready").build();
            }
            final String reason = probe.lastFailure();
            return Health.down()
                    .withDetail(STATUS, "not-ready")
                    .withDetail("reason", reason == null ? "probe in progress" : reason)
                    .build();
        };
    }

    /**
     * SecretsManager health probe. The JWT signing key is fetched from Secrets Manager; if Secrets
     * Manager is unreachable after a key rotation, JWT verification falls over for every request.
     * Probing it here lets the orchestrator drop the task before customer impact.
     *
     * <p><b>Reachability vs. retrieval:</b> we test that the SDK can talk to Secrets Manager,
     * not that any particular secret exists. A call to {@code getSecret("budgetbuddy/healthcheck",
     * "")} that fails with {@link ResourceNotFoundException} means the service IS reachable
     * (the request roundtripped and the server responded "no such secret"). We treat that as
     * UP. Only network / IAM / 5xx failures should drop the probe to DOWN, because those are
     * the conditions that would actually break JWT key retrieval at runtime.
     */
    @Bean
    public HealthIndicator secretsManagerHealthIndicator(
            final com.budgetbuddy.aws.secrets.SecretsManagerService secretsManagerService) {
        return () -> {
            try {
                secretsManagerService.getSecret("budgetbuddy/healthcheck", "");
                return Health.up().withDetail(SERVICE, "SecretsManager").build();
            } catch (Exception e) {
                // ResourceNotFoundException means the call reached AWS and got back a
                // "no such secret" response — that's a successful reachability check.
                // The exception may be wrapped in AppException, so unwrap and check the
                // chain.
                Throwable cause = e;
                while (cause != null) {
                    if (cause
                            instanceof software.amazon.awssdk.services.secretsmanager.model
                                    .ResourceNotFoundException) {
                        return Health.up()
                                .withDetail(SERVICE, "SecretsManager")
                                .withDetail(
                                        "note",
                                        "reachable; sentinel secret not provisioned (expected)")
                                .build();
                    }
                    cause = cause.getCause();
                }
                return Health.down()
                        .withDetail(SERVICE, "SecretsManager")
                        .withDetail("error", e.getClass().getSimpleName())
                        .build();
            }
        };
    }

    @Bean
    public HealthIndicator bbLivenessHealthIndicator() {
        return new HealthIndicator() {
            @Override
            public Health health() {
                // Simple liveness check - application is running
                return Health.up().withDetail(STATUS, "alive").build();
            }
        };
    }
}
