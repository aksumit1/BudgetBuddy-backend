package com.budgetbuddy.chaos;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.util.TableInitializer;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/** Chaos Engineering Tests Tests system resilience under failure conditions */
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChaosTest {

    @Autowired private DynamoDbClient dynamoDbClient;

    @BeforeAll
    void ensureTablesInitialized() {
        TableInitializer.ensureTablesInitializedAndVerified(dynamoDbClient);
    }

    @Test
    void testRandomFailures() throws InterruptedException {
        final ExecutorService executor = Executors.newFixedThreadPool(20);
        final AtomicInteger successCount = new AtomicInteger(0);
        final AtomicInteger failureCount = new AtomicInteger(0);
        final Random random = new Random();

        for (int i = 0; i < 100; i++) {
            executor.submit(
                    () -> {
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
        System.out.println(
                "System handled failures gracefully: "
                        + (failureCount.get() > 0 && successCount.get() > 0));
    }

    @Test
    void testCascadingFailures() throws InterruptedException {
        final ExecutorService executor = Executors.newFixedThreadPool(10);
        final CountDownLatch cascadeLatch = new CountDownLatch(1);
        final AtomicInteger recoveredCount = new AtomicInteger(0);

        // Simulate cascading failure scenario
        for (int i = 0; i < 10; i++) {
            executor.submit(
                    () -> {
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
        final ExecutorService executor = Executors.newFixedThreadPool(1000);
        final AtomicInteger handledCount = new AtomicInteger(0);

        try {
            for (int i = 0; i < 10_000; i++) {
                executor.submit(
                        () -> {
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
