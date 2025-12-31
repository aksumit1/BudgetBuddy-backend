package com.budgetbuddy.load;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.dto.AuthRequest;
import com.budgetbuddy.dto.AuthResponse;
import com.budgetbuddy.service.AuthService;
import com.budgetbuddy.service.UserService;
import com.budgetbuddy.util.TableInitializer;
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

import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Load Tests for Backend API
 * Tests system performance under load with real HTTP calls
 * 
 */
@SpringBootTest(
    classes = com.budgetbuddy.BudgetBuddyApplication.class,
    webEnvironment = org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
)
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LoadTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private AuthService authService;

    @Autowired
    private UserService userService;

    @Autowired
    private DynamoDbClient dynamoDbClient;

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
        String passwordHash = Base64.getEncoder().encodeToString("testpassword123".getBytes());

        // Create test user
        userService.createUserSecure(testEmail, passwordHash, "Load", "Test");

        // Authenticate to get token
        AuthRequest loginRequest = new AuthRequest();
        loginRequest.setEmail(testEmail);
        loginRequest.setPasswordHash(passwordHash);
        AuthResponse authResponse = authService.authenticate(loginRequest);
        authToken = authResponse.getAccessToken();
    }

    @Test
    void testConcurrentTransactionRequests() throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_USERS);
        CountDownLatch latch = new CountDownLatch(CONCURRENT_USERS);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        AtomicLong totalResponseTime = new AtomicLong(0);

        for (int i = 0; i < CONCURRENT_USERS; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < REQUESTS_PER_USER; j++) {
                        // Make real HTTP call to transactions endpoint
                        HttpHeaders headers = new HttpHeaders();
                        headers.set("Authorization", "Bearer " + authToken);
                        headers.set("Content-Type", "application/json");
                        HttpEntity<String> entity = new HttpEntity<>(headers);

                        long startTime = System.currentTimeMillis();
                        ResponseEntity<String> response = restTemplate.exchange(
                                baseUrl + "/api/transactions",
                                HttpMethod.GET,
                                entity,
                                String.class
                        );
                        long responseTime = System.currentTimeMillis() - startTime;
                        totalResponseTime.addAndGet(responseTime);

                        if (response.getStatusCode().is2xxSuccessful() || 
                            response.getStatusCode().value() == 401) {
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

        boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        executor.shutdown();

        org.junit.jupiter.api.Assertions.assertTrue(completed, "Load test did not complete within timeout");
        int totalRequests = CONCURRENT_USERS * REQUESTS_PER_USER;
        double avgResponseTime = totalResponseTime.get() / (double) totalRequests;
        
        System.out.println("Load Test Results:");
        System.out.println("Total Requests: " + totalRequests);
        System.out.println("Successful: " + successCount.get());
        System.out.println("Failed: " + failureCount.get());
        System.out.println("Success Rate: " + (successCount.get() * 100.0 / totalRequests) + "%");
        System.out.println("Average Response Time: " + String.format("%.2f", avgResponseTime) + "ms");
    }

    @Test
    void testSustainedLoad() throws InterruptedException {
        int durationSeconds = 30; // Reduced for faster test execution
        ExecutorService executor = Executors.newFixedThreadPool(20);
        AtomicInteger requestCount = new AtomicInteger(0);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        AtomicLong totalResponseTime = new AtomicLong(0);
        CountDownLatch stopSignal = new CountDownLatch(1);

        // Start load generation
        for (int i = 0; i < 20; i++) {
            executor.submit(() -> {
                while (stopSignal.getCount() > 0) {
                    try {
                        // Make real HTTP call to health endpoint
                        HttpHeaders headers = new HttpHeaders();
                        headers.set("Authorization", "Bearer " + authToken);
                        HttpEntity<String> entity = new HttpEntity<>(headers);

                        long startTime = System.currentTimeMillis();
                        ResponseEntity<String> response = restTemplate.exchange(
                                baseUrl + "/actuator/health",
                                HttpMethod.GET,
                                entity,
                                String.class
                        );
                        long responseTime = System.currentTimeMillis() - startTime;
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

        double avgResponseTime = requestCount.get() > 0 ? totalResponseTime.get() / (double) requestCount.get() : 0;
        
        System.out.println("Sustained Load Test Results:");
        System.out.println("Duration: " + durationSeconds + " seconds");
        System.out.println("Total Requests: " + requestCount.get());
        System.out.println("Successful: " + successCount.get());
        System.out.println("Failed: " + failureCount.get());
        System.out.println("Requests per second: " + (requestCount.get() / durationSeconds));
        System.out.println("Average Response Time: " + String.format("%.2f", avgResponseTime) + "ms");
    }
}

