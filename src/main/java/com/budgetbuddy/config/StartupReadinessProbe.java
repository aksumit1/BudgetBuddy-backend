package com.budgetbuddy.config;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;

/**
 * Startup readiness gate. Spring Boot marks the app READY as soon as the application context
 * initialises, which means traffic can hit the service before the DynamoDB tables it relies on are
 * reachable / provisioned. On a rolling deploy where the new task starts ahead of its dependent
 * infra, every request returns 500 until the ALB happens to remove the task — but that takes the
 * full health-check interval.
 *
 * <p>This probe runs at {@link ApplicationReadyEvent}, walks the {@link #CRITICAL_TABLES} list, and
 * only flips the readiness flag once every required table responds to a DescribeTable call. The
 * accompanying readiness {@code HealthIndicator} in {@link HealthCheckConfig} reads {@link
 * #isReady()} and returns DOWN until then; the Spring Boot availability state is also updated so
 * the {@code /actuator/health/readiness} probe — and any ALB target-group health check pointing at
 * it — flips ACCEPTING_TRAFFIC at the same instant.
 *
 * <p>Retry/backoff: the probe attempts up to {@link #MAX_ATTEMPTS} passes with 5-second backoffs
 * between them. If every attempt fails the service stays in REFUSING_TRAFFIC; the orchestrator
 * should then replace the task rather than route requests at a broken pod.
 */
@Component
public class StartupReadinessProbe {

    private static final Logger LOGGER = LoggerFactory.getLogger(StartupReadinessProbe.class);

    /**
     * Tables whose existence we need before serving traffic. Subset of the repository roster — we
     * only block readiness on the tables that primary user flows (auth, account fetch, transaction
     * read) actually hit. Optional tables (Benchmarks, Household) are not gated because a missing
     * one shouldn't keep the rest of the API offline.
     */
    private static final List<String> CRITICAL_TABLES =
            List.of("Users", "Accounts", "Transactions", "Budgets", "Goals");

    private static final int MAX_ATTEMPTS = 5;
    private static final long RETRY_DELAY_MS = 5_000L;

    private final DynamoDbClient dynamoDbClient;
    private final ApplicationContext applicationContext;
    private final String tablePrefix;

    private final AtomicBoolean ready = new AtomicBoolean(false);
    private final AtomicReference<String> lastFailure = new AtomicReference<>("not yet probed");

    public StartupReadinessProbe(
            final DynamoDbClient dynamoDbClient,
            final ApplicationContext applicationContext,
            @Value("${app.aws.dynamodb.table-prefix:BudgetBuddy}") final String tablePrefix) {
        this.dynamoDbClient = dynamoDbClient;
        this.applicationContext = applicationContext;
        this.tablePrefix = tablePrefix;
    }

    /**
     * Returns true once every {@link #CRITICAL_TABLES} entry has been confirmed reachable via
     * DescribeTable.
     */
    public boolean isReady() {
        return ready.get();
    }

    /** Last failure observation surfaced to the readiness HealthIndicator. */
    public String lastFailure() {
        return lastFailure.get();
    }

    @EventListener(ApplicationReadyEvent.class)
    /* default */ void probeAtStartup() {
        LOGGER.info(
                "Startup readiness probe beginning. Tables under check: {} (prefix='{}')",
                CRITICAL_TABLES,
                tablePrefix);

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            final String failure = probeOnce();
            if (failure == null) {
                ready.set(true);
                lastFailure.set(null);
                AvailabilityChangeEvent.publish(
                        applicationContext, ReadinessState.ACCEPTING_TRAFFIC);
                LOGGER.info(
                        "Startup readiness probe passed on attempt {}/{}", attempt, MAX_ATTEMPTS);
                return;
            }
            lastFailure.set(failure);
            LOGGER.warn(
                    "Startup readiness probe attempt {}/{} failed: {}",
                    attempt,
                    MAX_ATTEMPTS,
                    failure);
            if (attempt < MAX_ATTEMPTS) {
                try {
                    Thread.sleep(RETRY_DELAY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        AvailabilityChangeEvent.publish(applicationContext, ReadinessState.REFUSING_TRAFFIC);
        if (LOGGER.isErrorEnabled()) {
            LOGGER.error(
                    "Startup readiness probe failed after {} attempts. Last failure: {}",
                    MAX_ATTEMPTS,
                    lastFailure.get());
        }
    }

    /**
     * Run one pass of the probe. Returns {@code null} on success, otherwise the first failure
     * message encountered.
     */
    private String probeOnce() {
        for (final String table : CRITICAL_TABLES) {
            final String full = tablePrefix + "-" + table;
            try {
                dynamoDbClient.describeTable(
                        DescribeTableRequest.builder().tableName(full).build());
            } catch (Exception e) {
                return full + ": " + e.getClass().getSimpleName() + " " + e.getMessage();
            }
        }
        return null;
    }
}
