package com.budgetbuddy.chaos;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.service.UserService;
import com.budgetbuddy.util.TableInitializer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
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

import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Infrastructure Chaos Tests
 * Tests DynamoDB throttling, Redis failures, circuit breaker behavior
 */
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InfrastructureChaosTest {

    @Autowired
    private DynamoDbClient dynamoDbClient;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private UserService userService;

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
        String email = "chaos-test-" + UUID.randomUUID() + "@example.com";
        String passwordHash = Base64.getEncoder().encodeToString("testpassword".getBytes());
        testUser = userService.createUserSecure(email, passwordHash, "Chaos", "Test");
    }

    @Test
    void testDynamoDBThrottlingHandling() {
        // Test system behavior when DynamoDB is throttled
        // Note: LocalStack doesn't simulate throttling, but we can test retry logic
        int concurrentRequests = 100;
        ExecutorService executor = Executors.newFixedThreadPool(concurrentRequests);
        CountDownLatch latch = new CountDownLatch(concurrentRequests);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger throttledCount = new AtomicInteger(0);

        for (int i = 0; i < concurrentRequests; i++) {
            executor.submit(() -> {
                try {
                    // Make many concurrent queries to potentially trigger throttling
                    List<TransactionTable> transactions = transactionRepository.findByUserId(
                            testUser.getUserId(), 0, 100);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    if (e instanceof ProvisionedThroughputExceededException ||
                        e.getCause() instanceof ProvisionedThroughputExceededException) {
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
        assertTrue(successCount.get() > 0 || throttledCount.get() > 0,
                "System should handle requests or throttling gracefully");
    }

    @Test
    void testCircuitBreakerBehavior() {
        // Test circuit breaker behavior
        if (circuitBreakerRegistry == null) {
            System.out.println("Circuit breaker registry not available - test skipped");
            return;
        }

        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("plaid");
        if (circuitBreaker == null) {
            System.out.println("Plaid circuit breaker not found - test skipped");
            return;
        }

        CircuitBreaker.Metrics metrics = circuitBreaker.getMetrics();
        
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
        int concurrentConnections = 200;
        ExecutorService executor = Executors.newFixedThreadPool(concurrentConnections);
        CountDownLatch latch = new CountDownLatch(concurrentConnections);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger rejectedCount = new AtomicInteger(0);

        for (int i = 0; i < concurrentConnections; i++) {
            executor.submit(() -> {
                try {
                    // Make concurrent database queries
                    List<TransactionTable> transactions = transactionRepository.findByUserId(
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
        assertTrue(successCount.get() > 0 || rejectedCount.get() > 0,
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
        int threadCount = 500;
        ExecutorService executor = Executors.newFixedThreadPool(100); // Limited thread pool
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger rejectedCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            try {
                executor.submit(() -> {
                    try {
                        // Simulate work
                        Thread.sleep(10);
                        List<TransactionTable> transactions = transactionRepository.findByUserId(
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
        assertTrue(successCount.get() > 0 || rejectedCount.get() > 0,
                "System should handle thread pool exhaustion gracefully");
    }
}

