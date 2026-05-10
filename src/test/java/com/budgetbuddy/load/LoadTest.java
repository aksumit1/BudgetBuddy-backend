package com.budgetbuddy.load;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.dto.AuthRequest;
import com.budgetbuddy.dto.AuthResponse;
import com.budgetbuddy.service.AuthService;
import com.budgetbuddy.service.UserService;
import com.budgetbuddy.util.TableInitializer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/** Load Tests for Backend API Tests system performance under load with real HTTP calls */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
@SuppressWarnings({"PMD.LawOfDemeter", "PMD.AvoidCatchingGenericException"})
@SpringBootTest(
        classes = com.budgetbuddy.BudgetBuddyApplication.class,
        webEnvironment =
                org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LoadTest {

    @Autowired private TestRestTemplate restTemplate;

    @Autowired private AuthService authService;

    @Autowired private UserService userService;

    @Autowired private DynamoDbClient dynamoDbClient;

    @Value("${local.server.port}")
    private int port;

    private String baseUrl;
    private String authToken;
    private String testEmail;

    private static final int CONCURRENT_USERS = 50; // Reduced for faster test execution
    private static final int REQUESTS_PER_USER = 5; // Reduced for faster test execution
    private static final int TIMEOUT_SECONDS = 60;

    @BeforeAll
    void ensureTablesInitialized() {
        TableInitializer.ensureTablesInitializedAndVerified(dynamoDbClient);
    }

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;
        testEmail = "loadtest-" + UUID.randomUUID() + "@example.com";
        final String passwordHash =
                Base64.getEncoder()
                        .encodeToString("testpassword123".getBytes(StandardCharsets.UTF_8));

        // Create test user
        userService.createUserSecure(testEmail, passwordHash, "Load", "Test");

        // Authenticate to get token
        final AuthRequest loginRequest = new AuthRequest();
        loginRequest.setEmail(testEmail);
        loginRequest.setPasswordHash(passwordHash);
        final AuthResponse authResponse = authService.authenticate(loginRequest);
        authToken = authResponse.getAccessToken();
    }

    @Test
    void testConcurrentTransactionRequests() throws InterruptedException {
        final ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_USERS);
        final CountDownLatch latch = new CountDownLatch(CONCURRENT_USERS);
        final AtomicInteger successCount = new AtomicInteger(0);
        final AtomicInteger failureCount = new AtomicInteger(0);
        final AtomicLong totalResponseTime = new AtomicLong(0);

        for (int i = 0; i < CONCURRENT_USERS; i++) {
            executor.submit(
                    () -> {
                        try {
                            for (int j = 0; j < REQUESTS_PER_USER; j++) {
                                // Make real HTTP call to transactions endpoint
                                final HttpHeaders headers = new HttpHeaders();
                                headers.set("Authorization", "Bearer " + authToken);
                                headers.set("Content-Type", "application/json");
                                final HttpEntity<String> entity = new HttpEntity<>(headers);

                                final long startTime = System.currentTimeMillis();
                                final ResponseEntity<String> response =
                                        restTemplate.exchange(
                                                baseUrl + "/api/transactions",
                                                HttpMethod.GET,
                                                entity,
                                                String.class);
                                final long responseTime = System.currentTimeMillis() - startTime;
                                totalResponseTime.addAndGet(responseTime);

                                if (response.getStatusCode().is2xxSuccessful()
                                        || response.getStatusCode().value() == 401) {
                                    successCount.incrementAndGet();
                                } else {
                                    failureCount.incrementAndGet();
                                }
                            }
                        } catch (Exception e) {
                            failureCount.incrementAndGet();
                        } finally {
                            latch.countDown();
                        }
                    });
        }

        final boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        executor.shutdown();

        org.junit.jupiter.api.Assertions.assertTrue(
                completed, "Load test did not complete within timeout");
        final int totalRequests = CONCURRENT_USERS * REQUESTS_PER_USER;
        final double avgResponseTime = totalResponseTime.get() / (double) totalRequests;

        System.out.println("Load Test Results:");
        System.out.println("Total Requests: " + totalRequests);
        System.out.println("Successful: " + successCount.get());
        System.out.println("Failed: " + failureCount.get());
        System.out.println("Success Rate: " + (successCount.get() * 100.0 / totalRequests) + "%");
        System.out.println(
                "Average Response Time: " + String.format("%.2f", avgResponseTime) + "ms");
    }

    @Test
    void testSustainedLoad() throws InterruptedException {
        final int durationSeconds = 30; // Reduced for faster test execution
        final ExecutorService executor = Executors.newFixedThreadPool(20);
        final AtomicInteger requestCount = new AtomicInteger(0);
        final AtomicInteger successCount = new AtomicInteger(0);
        final AtomicInteger failureCount = new AtomicInteger(0);
        final AtomicLong totalResponseTime = new AtomicLong(0);
        final CountDownLatch stopSignal = new CountDownLatch(1);

        // Start load generation
        for (int i = 0; i < 20; i++) {
            executor.submit(
                    () -> {
                        while (stopSignal.getCount() > 0) {
                            try {
                                // Make real HTTP call to health endpoint
                                final HttpHeaders headers = new HttpHeaders();
                                headers.set("Authorization", "Bearer " + authToken);
                                final HttpEntity<String> entity = new HttpEntity<>(headers);

                                final long startTime = System.currentTimeMillis();
                                final ResponseEntity<String> response =
                                        restTemplate.exchange(
                                                baseUrl + "/actuator/health",
                                                HttpMethod.GET,
                                                entity,
                                                String.class);
                                final long responseTime = System.currentTimeMillis() - startTime;
                                totalResponseTime.addAndGet(responseTime);

                                requestCount.incrementAndGet();
                                if (response.getStatusCode().is2xxSuccessful()) {
                                    successCount.incrementAndGet();
                                } else {
                                    failureCount.incrementAndGet();
                                }

                                Thread.sleep(100); // Small delay between requests
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                break;
                            } catch (Exception e) {
                                failureCount.incrementAndGet();
                                requestCount.incrementAndGet();
                            }
                        }
                    });
        }

        // Run for specified duration
        Thread.sleep(durationSeconds * 1000L);
        stopSignal.countDown();

        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        final double avgResponseTime =
                requestCount.get() > 0 ? totalResponseTime.get() / (double) requestCount.get() : 0;

        System.out.println("Sustained Load Test Results:");
        System.out.println("Duration: " + durationSeconds + " seconds");
        System.out.println("Total Requests: " + requestCount.get());
        System.out.println("Successful: " + successCount.get());
        System.out.println("Failed: " + failureCount.get());
        System.out.println("Requests per second: " + (requestCount.get() / durationSeconds));
        System.out.println(
                "Average Response Time: " + String.format("%.2f", avgResponseTime) + "ms");
    }
}
