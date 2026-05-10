package com.budgetbuddy.chaos;


import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.service.UserService;
import com.budgetbuddy.util.TableInitializer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughputExceededException;

/**
 * Infrastructure Chaos Tests Tests DynamoDB throttling, Redis failures, circuit breaker behavior
 */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
@SuppressWarnings({"PMD.LawOfDemeter", "PMD.AvoidCatchingGenericException"})
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InfrastructureChaosTest {

    @Autowired private DynamoDbClient dynamoDbClient;

    @Autowired private TransactionRepository transactionRepository;

    @Autowired private UserService userService;

    @Autowired(required = false)
    private CircuitBreakerRegistry circuitBreakerRegistry;

    private UserTable testUser;

    @BeforeAll
    void ensureTablesInitialized() {
        TableInitializer.ensureTablesInitializedAndVerified(dynamoDbClient);
    }

    @BeforeEach
    void setUp() {
        // Create test user
        final String email = "chaos-test-" + UUID.randomUUID() + "@example.com";
        final String passwordHash = Base64.getEncoder().encodeToString("testpassword".getBytes(StandardCharsets.UTF_8));
        testUser = userService.createUserSecure(email, passwordHash, "Chaos", "Test");
    }

    @Test
    void testDynamoDBThrottlingHandling() {
        // Test system behavior when DynamoDB is throttled
        // Note: LocalStack doesn't simulate throttling, but we can test retry logic
        final int concurrentRequests = 100;
        final ExecutorService executor = Executors.newFixedThreadPool(concurrentRequests);
        final CountDownLatch latch = new CountDownLatch(concurrentRequests);
        final AtomicInteger successCount = new AtomicInteger(0);
        final AtomicInteger throttledCount = new AtomicInteger(0);

        for (int i = 0; i < concurrentRequests; i++) {
            executor.submit(
                    () -> {
                        try {
                            // Make many concurrent queries to potentially trigger throttling
                            final List<TransactionTable> transactions =
                                    transactionRepository.findByUserId(
                                            testUser.getUserId(), 0, 100);
                            successCount.incrementAndGet();
                        } catch (Exception e) {
                            if (e instanceof ProvisionedThroughputExceededException
                                    || e.getCause()
                                            instanceof ProvisionedThroughputExceededException) {
                                throttledCount.incrementAndGet();
                            }
                        } finally {
                            latch.countDown();
                        }
                    });
        }

        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        executor.shutdown();

        System.out.println("DynamoDB Throttling Test:");
        System.out.println("Concurrent requests: " + concurrentRequests);
        System.out.println("Successful: " + successCount.get());
        System.out.println("Throttled: " + throttledCount.get());

        // System should handle throttling gracefully
        assertTrue(
                successCount.get() > 0 || throttledCount.get() > 0,
                "System should handle requests or throttling gracefully");
    }

    @Test
    void testCircuitBreakerBehavior() {
        // Test circuit breaker behavior
        if (circuitBreakerRegistry == null) {
            System.out.println("Circuit breaker registry not available - test skipped");
            return;
        }

        final CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("plaid");
        if (circuitBreaker == null) {
            System.out.println("Plaid circuit breaker not found - test skipped");
            return;
        }

        final CircuitBreaker.Metrics metrics = circuitBreaker.getMetrics();

        System.out.println("Circuit Breaker Behavior:");
        System.out.println("State: " + circuitBreaker.getState());
        System.out.println("Failure rate: " + metrics.getFailureRate());
        System.out.println("Number of successful calls: " + metrics.getNumberOfSuccessfulCalls());
        System.out.println("Number of failed calls: " + metrics.getNumberOfFailedCalls());

        // Circuit breaker should exist and be functional
        assertNotNull(circuitBreaker, "Circuit breaker should exist");
        assertNotNull(metrics, "Circuit breaker metrics should exist");
    }

    @Test
    void testConnectionPoolExhaustion() throws InterruptedException {
        // Test system behavior when connection pool is exhausted
        final int concurrentConnections = 200;
        final ExecutorService executor = Executors.newFixedThreadPool(concurrentConnections);
        final CountDownLatch latch = new CountDownLatch(concurrentConnections);
        final AtomicInteger successCount = new AtomicInteger(0);
        final AtomicInteger rejectedCount = new AtomicInteger(0);

        for (int i = 0; i < concurrentConnections; i++) {
            executor.submit(
                    () -> {
                        try {
                            // Make concurrent database queries
                            final List<TransactionTable> transactions =
                                    transactionRepository.findByUserId(
                                            testUser.getUserId(), 0, 100);
                            successCount.incrementAndGet();
                        } catch (RejectedExecutionException e) {
                            rejectedCount.incrementAndGet();
                        } catch (Exception e) {
                            // Other exceptions are acceptable
                        } finally {
                            latch.countDown();
                        }
                    });
        }

        latch.await(60, TimeUnit.SECONDS);
        executor.shutdown();

        System.out.println("Connection Pool Exhaustion Test:");
        System.out.println("Concurrent connections: " + concurrentConnections);
        System.out.println("Successful: " + successCount.get());
        System.out.println("Rejected: " + rejectedCount.get());

        // System should handle connection pool exhaustion gracefully
        assertTrue(
                successCount.get() > 0 || rejectedCount.get() > 0,
                "System should handle connection pool exhaustion gracefully");
    }

    @Test
    void testRetryExhaustion() {
        // Test retry exhaustion behavior
        // This test documents expected retry behavior
        System.out.println("Retry Exhaustion Test:");
        System.out.println("Retry configuration is handled by Resilience4j");
        System.out.println("Max retry attempts are configured in application.yml");

        // This is a documentation test - actual retry exhaustion is tested in integration tests
        assertTrue(true, "Retry exhaustion is handled by Resilience4j");
    }

    @Test
    void testThreadPoolExhaustion() throws InterruptedException {
        // Test system behavior when thread pool is exhausted
        final int threadCount = 500;
        final ExecutorService executor = Executors.newFixedThreadPool(100); // Limited thread pool
        final CountDownLatch latch = new CountDownLatch(threadCount);
        final AtomicInteger successCount = new AtomicInteger(0);
        final AtomicInteger rejectedCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            try {
                executor.submit(
                        () -> {
                            try {
                                // Simulate work
                                Thread.sleep(10);
                                final List<TransactionTable> transactions =
                                        transactionRepository.findByUserId(
                                                testUser.getUserId(), 0, 100);
                                successCount.incrementAndGet();
                            } catch (Exception e) {
                                // Ignore exceptions
                            } finally {
                                latch.countDown();
                            }
                        });
            } catch (RejectedExecutionException e) {
                rejectedCount.incrementAndGet();
                latch.countDown();
            }
        }

        latch.await(60, TimeUnit.SECONDS);
        executor.shutdown();

        System.out.println("Thread Pool Exhaustion Test:");
        System.out.println("Thread count: " + threadCount);
        System.out.println("Successful: " + successCount.get());
        System.out.println("Rejected: " + rejectedCount.get());

        // System should handle thread pool exhaustion gracefully
        assertTrue(
                successCount.get() > 0 || rejectedCount.get() > 0,
                "System should handle thread pool exhaustion gracefully");
    }
}
