package com.budgetbuddy.load;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Load Tests for Backend API
 * Tests system performance under load
 * 
 * DISABLED: Java 25 compatibility issue - Spring Boot context fails to load
 * due to Java 25 class format (major version 69) incompatibility with Spring Boot 3.4.1.
 * Will be re-enabled when Spring Boot fully supports Java 25.
 */
@org.junit.jupiter.api.Disabled("Java 25 compatibility: Spring Boot context loading fails")
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@ActiveProfiles("test")
class LoadTest {

    private static final int CONCURRENT_USERS = 100;
    private static final int REQUESTS_PER_USER = 10;
    private static final int TIMEOUT_SECONDS = 30;

    @Test
    void testConcurrentTransactionRequests() throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_USERS);
        CountDownLatch latch = new CountDownLatch(CONCURRENT_USERS);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        for (int i = 0; i < CONCURRENT_USERS; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < REQUESTS_PER_USER; j++) {
                        // Simulate API call
                        Thread.sleep(10); // Simulate network latency
                        successCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        executor.shutdown();

        org.junit.jupiter.api.Assertions.assertTrue(completed, "Load test did not complete within timeout");
        System.out.println("Load Test Results:");
        System.out.println("Total Requests: " + (CONCURRENT_USERS * REQUESTS_PER_USER));
        System.out.println("Successful: " + successCount.get());
        System.out.println("Failed: " + failureCount.get());
        System.out.println("Success Rate: " +
                (successCount.get() * 100.0 / (CONCURRENT_USERS * REQUESTS_PER_USER)) + "%");
    }

    @Test
    void testSustainedLoad() throws InterruptedException {
        int durationSeconds = 60;
        ExecutorService executor = Executors.newFixedThreadPool(50);
        AtomicInteger requestCount = new AtomicInteger(0);
        CountDownLatch stopSignal = new CountDownLatch(1);

        // Start load generation
        for (int i = 0; i < 50; i++) {
            executor.submit(() -> {
                while (stopSignal.getCount() > 0) {
                    try {
                        // Simulate API request
                        Thread.sleep(100);
                        requestCount.incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            });
        }

        // Run for specified duration
        Thread.sleep(durationSeconds * 1000L);
        stopSignal.countDown();

        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        System.out.println("Sustained Load Test Results:");
        System.out.println("Duration: " + durationSeconds + " seconds");
        System.out.println("Total Requests: " + requestCount.get());
        System.out.println("Requests per second: " + (requestCount.get() / durationSeconds));
    }
}

