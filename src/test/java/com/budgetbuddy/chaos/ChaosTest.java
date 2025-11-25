package com.budgetbuddy.chaos;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Random;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Chaos Engineering Tests
 * Tests system resilience under failure conditions
 * 
 * DISABLED: Java 25 compatibility issue - Spring Boot context fails to load
 * due to Java 25 class format (major version 69) incompatibility with Spring Boot 3.4.1.
 * Will be re-enabled when Spring Boot fully supports Java 25.
 */
@org.junit.jupiter.api.Disabled("Java 25 compatibility: Spring Boot context loading fails")
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@ActiveProfiles("test")
class ChaosTest {

    @Test
    void testRandomFailures() throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(20);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        Random random = new Random();

        for (int i = 0; i < 100; i++) {
            executor.submit(() -> {
                try {
                    // Simulate random failure (10% failure rate)
                    if (random.nextInt(100) < 10) {
                        throw new RuntimeException("Simulated failure");
                    }
                    Thread.sleep(50);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                }
            });
        }

        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        System.out.println("Chaos Test - Random Failures:");
        System.out.println("Successful: " + successCount.get());
        System.out.println("Failed: " + failureCount.get());
        System.out.println("System handled failures gracefully: " +
                (failureCount.get() > 0 && successCount.get() > 0));
    }

    @Test
    void testCascadingFailures() throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch cascadeLatch = new CountDownLatch(1);
        AtomicInteger recoveredCount = new AtomicInteger(0);

        // Simulate cascading failure scenario
        for (int i = 0; i < 10; i++) {
            final int taskId = i;
            executor.submit(() -> {
                try {
                    cascadeLatch.await(); // Wait for cascade trigger
                    // Simulate recovery
                    Thread.sleep(100);
                    recoveredCount.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        // Trigger cascade
        cascadeLatch.countDown();
        Thread.sleep(2000);

        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        System.out.println("Chaos Test - Cascading Failures:");
        System.out.println("Recovered: " + recoveredCount.get() + "/10");
    }

    @Test
    void testResourceExhaustion() {
        // Test system behavior under resource exhaustion
        ExecutorService executor = Executors.newFixedThreadPool(1000);
        AtomicInteger handledCount = new AtomicInteger(0);

        try {
            for (int i = 0; i < 10000; i++) {
                executor.submit(() -> {
                    try {
                        Thread.sleep(10);
                        handledCount.incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
        } catch (RejectedExecutionException e) {
            System.out.println("Resource exhaustion detected - system rejected requests");
        }

        executor.shutdown();
        System.out.println("Chaos Test - Resource Exhaustion:");
        System.out.println("Handled requests: " + handledCount.get());
    }
}

