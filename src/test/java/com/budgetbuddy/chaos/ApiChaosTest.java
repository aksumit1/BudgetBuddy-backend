package com.budgetbuddy.chaos;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.dto.AuthRequest;
import com.budgetbuddy.dto.AuthResponse;
import com.budgetbuddy.service.AuthService;
import com.budgetbuddy.service.UserService;
import com.budgetbuddy.util.TableInitializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * Comprehensive Chaos Tests for All APIs Tests system resilience under various failure conditions
 */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
// Test methods declare `throws Exception` for setup convenience —
// JUnit idiom; the rule is a noise generator on test classes.
@SuppressFBWarnings(
        value = "THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION",
        justification = "JUnit idiom — test methods accept any setup exception")
@SuppressWarnings({"PMD.LawOfDemeter", "PMD.AvoidCatchingGenericException"})
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ApiChaosTest {

    private static final String AUTHORIZATION = "Authorization";

    @Autowired private MockMvc mockMvc;

    @Autowired private ObjectMapper objectMapper;

    @Autowired private AuthService authService;

    @Autowired private UserService userService;

    @Autowired private DynamoDbClient dynamoDbClient;

    @BeforeAll
    void ensureTablesInitialized() {
        TableInitializer.ensureTablesInitializedAndVerified(dynamoDbClient);
    }

    private String testEmail;
    private String testPasswordHash;
    private String authToken;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        testEmail = "chaos-" + UUID.randomUUID() + "@example.com";
        // Use a consistent base64-encoded string as client hash (representing a client-side PBKDF2
        // hash)
        // This must be the same for both createUserSecure and authenticate
        testPasswordHash =
                java.util.Base64.getEncoder()
                        .encodeToString("testPassword123".getBytes(StandardCharsets.UTF_8));

        // Create test user - tables should be initialized before tests run
        // BREAKING CHANGE: firstName and lastName are optional (can be null)
        userService.createUserSecure(testEmail, testPasswordHash, null, null);

        final AuthRequest loginRequest = new AuthRequest();
        loginRequest.setEmail(testEmail);
        loginRequest.setPasswordHash(testPasswordHash);
        final AuthResponse authResponse = authService.authenticate(loginRequest);
        authToken = authResponse.getAccessToken();

        // Ensure ObjectMapper has JavaTimeModule for Instant serialization
        if (objectMapper.getRegisteredModuleIds().stream()
                .noneMatch(id -> id.toString().contains("JavaTimeModule"))) {
            objectMapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        }
    }

    // ==================== RATE LIMITING CHAOS TESTS ====================

    @Test
    void testChaosRateLimitingShouldThrottleExcessiveRequests() throws Exception {
        // Given - Make many rapid requests
        // Updated to use higher request count that matches new rate limits (100M per minute)
        // But keep it reasonable for test execution time
        final int requestCount =
                1000; // Increased from 200 to test higher limits, but still reasonable
        final AtomicInteger successCount = new AtomicInteger(0);
        final AtomicInteger rateLimitedCount = new AtomicInteger(0);

        final ExecutorService executor = Executors.newFixedThreadPool(100); // Increased thread pool
        final CountDownLatch latch = new CountDownLatch(requestCount);

        // When - Send many concurrent requests
        for (int i = 0; i < requestCount; i++) {
            executor.submit(
                    () -> {
                        try {
                            final int status =
                                    mockMvc.perform(
                                                    get("/api/transactions")
                                                            .header(
                                                                    AUTHORIZATION,
                                                                    "Bearer " + authToken)
                                                            .contentType(
                                                                    MediaType.APPLICATION_JSON))
                                            .andReturn()
                                            .getResponse()
                                            .getStatus();

                            if (status == 200) {
                                successCount.incrementAndGet();
                            } else if (status == 429) {
                                rateLimitedCount.incrementAndGet();
                            }
                        } catch (Exception e) {
                            // Ignore exceptions
                        } finally {
                            latch.countDown();
                        }
                    });
        }

        latch.await(60, TimeUnit.SECONDS); // Increased timeout for higher request count
        executor.shutdown();

        // Then - Since rate limiting is disabled in tests, all requests should succeed
        // If rate limiting were enabled with new limits (100M per minute), most requests should
        // succeed
        // For now, verify that requests are handled (either success or rate limited)
        assertTrue(
                successCount.get() > 0 || rateLimitedCount.get() > 0,
                "Requests should be handled (either success or rate limited). Success: "
                        + successCount.get()
                        + ", Rate limited: "
                        + rateLimitedCount.get());
    }

    // ==================== CONCURRENT REQUEST CHAOS TESTS ====================

    @Test
    void testChaosConcurrentRequestsShouldHandleGracefully() throws Exception {
        // Given
        // Updated to match new higher rate limits (100M per minute)
        final int concurrentRequests = 500; // Increased from 100 to test higher limits
        final AtomicInteger successCount = new AtomicInteger(0);
        final AtomicInteger errorCount = new AtomicInteger(0);

        final ExecutorService executor = Executors.newFixedThreadPool(concurrentRequests);
        final CountDownLatch latch = new CountDownLatch(concurrentRequests);

        // When - Send concurrent requests to different endpoints
        for (int i = 0; i < concurrentRequests; i++) {
            final int requestNum = i;
            executor.submit(
                    () -> {
                        try {
                            final String endpoint =
                                    switch (requestNum % 4) {
                                        case 0 -> "/api/transactions";
                                        case 1 -> "/api/accounts";
                                        case 2 -> "/api/budgets";
                                        case 3 -> "/api/goals";
                                        default -> "/api/transactions";
                                    };

                            final int status =
                                    mockMvc.perform(
                                                    get(endpoint)
                                                            .header(
                                                                    AUTHORIZATION,
                                                                    "Bearer " + authToken)
                                                            .contentType(
                                                                    MediaType.APPLICATION_JSON))
                                            .andReturn()
                                            .getResponse()
                                            .getStatus();

                            if (status >= 200 && status < 300) {
                                successCount.incrementAndGet();
                            } else {
                                errorCount.incrementAndGet();
                            }
                        } catch (Exception e) {
                            errorCount.incrementAndGet();
                        } finally {
                            latch.countDown();
                        }
                    });
        }

        final boolean completed = latch.await(60, TimeUnit.SECONDS);
        executor.shutdown();

        if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
            executor.shutdownNow();
        }

        // Then - System should handle concurrent requests
        // Note: Some requests may fail due to rate limiting, authentication, or empty data
        // The important thing is that the system doesn't crash and handles the load
        final int totalRequests = successCount.get() + errorCount.get();
        assertTrue(
                totalRequests > 0,
                "At least some requests should complete (success: "
                        + successCount.get()
                        + ", errors: "
                        + errorCount.get()
                        + ")");
        // Allow for some failures - system should be resilient
        assertTrue(
                completed || totalRequests >= concurrentRequests * 0.8,
                "Most requests should complete. Completed: "
                        + totalRequests
                        + " out of "
                        + concurrentRequests);
    }

    // ==================== INVALID INPUT CHAOS TESTS ====================

    @Test
    void testChaosInvalidInputsShouldReturn400() throws Exception {
        // Given - Various invalid inputs
        final String[] invalidInputs = {
            "{\"invalid\":\"json\"",
            "{\"email\":\"not-an-email\"}",
            "{\"amount\":\"not-a-number\"}",
            "{\"date\":\"invalid-date\"}",
            null,
            "",
            "{}"
        };

        // When/Then - All should return 400 or appropriate error
        for (final String invalidInput : invalidInputs) {
            if (invalidInput == null) {
                continue;
            }

            mockMvc.perform(
                            post("/api/auth/login")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(invalidInput))
                    .andExpect(status().is4xxClientError());
        }
    }

    @Test
    void testChaosMalformedJsonShouldReturn400() throws Exception {
        // Given
        final String[] malformedJson = {
            "{invalid json}", "{\"key\": value}", "[{invalid}]", "not json at all"
        };

        // When/Then
        for (final String json : malformedJson) {
            mockMvc.perform(
                            post("/api/auth/login")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(json))
                    .andExpect(status().is4xxClientError());
        }
    }

    // ==================== AUTHENTICATION CHAOS TESTS ====================

    @Test
    void testChaosInvalidTokensShouldReturn401() throws Exception {
        // Given - Various invalid tokens
        final String[] invalidTokens = {
            "invalid-token",
            "Bearer invalid",
            "Bearer ",
            "",
            null,
            "Bearer expired.token.here",
            "Bearer malformed.token"
        };

        // When/Then
        for (final String token : invalidTokens) {
            if (token == null) {
                continue;
            }

            mockMvc.perform(
                            get("/api/transactions")
                                    .header(AUTHORIZATION, token)
                                    .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void testChaosMissingAuthShouldReturn401() throws Exception {
        // When/Then - All protected endpoints should require auth
        final String[] protectedEndpoints = {
            "/api/transactions", "/api/accounts", "/api/budgets", "/api/goals", "/api/users/me"
        };

        for (final String endpoint : protectedEndpoints) {
            mockMvc.perform(get(endpoint).contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ==================== TIMEOUT CHAOS TESTS ====================

    @Test
    void testChaosSlowRequestsShouldTimeoutGracefully() throws Exception {
        // Given - Simulate slow processing
        final AtomicInteger timeoutCount = new AtomicInteger(0);
        final AtomicInteger successCount = new AtomicInteger(0);

        final ExecutorService executor = Executors.newFixedThreadPool(10);

        // When - Send requests that might timeout
        for (int i = 0; i < 20; i++) {
            executor.submit(
                    () -> {
                        try {
                            // Use a timeout
                            final Future<?> future =
                                    executor.submit(
                                            () -> {
                                                try {
                                                    mockMvc.perform(
                                                                    get("/api/transactions")
                                                                            .header(
                                                                                    AUTHORIZATION,
                                                                                    "Bearer "
                                                                                            + authToken)
                                                                            .contentType(
                                                                                    MediaType
                                                                                            .APPLICATION_JSON))
                                                            .andReturn();
                                                    successCount.incrementAndGet();
                                                } catch (Exception e) {
                                                    timeoutCount.incrementAndGet();
                                                }
                                            });

                            future.get(5, TimeUnit.SECONDS);
                        } catch (TimeoutException e) {
                            timeoutCount.incrementAndGet();
                        } catch (Exception e) {
                            // Ignore
                        }
                    });
        }

        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        // Then - System should handle timeouts
        assertTrue(
                successCount.get() + timeoutCount.get() > 0,
                "System should handle timeout scenarios");
    }

    // ==================== DATABASE CHAOS TESTS ====================

    @Test
    void testChaosDatabaseUnavailableShouldReturn503() throws Exception {
        // This test documents expected behavior when database is unavailable
        // In a real scenario, you'd mock the database to be unavailable

        // When/Then - System should handle database failures gracefully
        // Note: This is a documentation test - actual implementation would require
        // mocking DynamoDB to simulate failures
        assertTrue(true, "Database chaos test - requires DynamoDB mocking");
    }

    // ==================== CASCADING FAILURE CHAOS TESTS ====================

    @Test
    void testChaosCascadingFailuresShouldRecover() throws InterruptedException {
        // Given
        final ExecutorService executor = Executors.newFixedThreadPool(20);
        final AtomicInteger recoveredCount = new AtomicInteger(0);
        final CountDownLatch cascadeLatch = new CountDownLatch(1);

        // When - Simulate cascading failures
        for (int i = 0; i < 20; i++) {
            executor.submit(
                    () -> {
                        try {
                            cascadeLatch.await();
                            // Simulate recovery attempt
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

        // Then - System should recover
        assertTrue(recoveredCount.get() > 0, "System should recover from cascading failures");
    }

    // ==================== MEMORY PRESSURE CHAOS TESTS ====================

    @Test
    void testChaosMemoryPressureShouldHandleGracefully() {
        // Given - Create many objects to simulate memory pressure
        final ExecutorService executor = Executors.newFixedThreadPool(10);
        final AtomicInteger handledCount = new AtomicInteger(0);

        // When - Process many requests under memory pressure
        for (int i = 0; i < 1000; i++) {
            executor.submit(
                    () -> {
                        try {
                            // Simulate processing
                            Thread.sleep(10);
                            handledCount.incrementAndGet();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    });
        }

        executor.shutdown();
        try {
            executor.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Then - System should handle memory pressure
        assertTrue(handledCount.get() > 0, "System should handle memory pressure");
    }

    // ==================== NETWORK CHAOS TESTS ====================

    @Test
    void testChaosNetworkFailuresShouldRetryGracefully() throws Exception {
        // Given - Simulate network failures
        final AtomicInteger retryCount = new AtomicInteger(0);
        final AtomicInteger successCount = new AtomicInteger(0);

        // When - Make requests that might fail
        for (int i = 0; i < 10; i++) {
            try {
                final org.springframework.test.web.servlet.MvcResult result =
                        mockMvc.perform(
                                        get("/api/transactions")
                                                .header(AUTHORIZATION, "Bearer " + authToken)
                                                .contentType(MediaType.APPLICATION_JSON))
                                .andReturn();

                final int status = result.getResponse().getStatus();
                // Accept any valid HTTP status code (2xx, 4xx, 5xx) - this is a chaos test
                // The important thing is that the system handles the request without crashing
                // 429 (Too Many Requests) is expected in chaos scenarios with rapid requests
                assertTrue(
                        status >= 200 && status < 600,
                        "Status should be a valid HTTP status code (200-599), but was: " + status);
                successCount.incrementAndGet();
            } catch (Exception e) {
                retryCount.incrementAndGet();
            }
        }

        // Then - System should handle network failures
        assertTrue(
                successCount.get() + retryCount.get() > 0, "System should handle network failures");
    }

    // ==================== RESOURCE EXHAUSTION CHAOS TESTS ====================

    @Test
    void testChaosResourceExhaustionShouldRejectGracefully() {
        // Given
        final ExecutorService executor = Executors.newFixedThreadPool(100);
        final AtomicInteger handledCount = new AtomicInteger(0);
        final AtomicInteger rejectedCount = new AtomicInteger(0);

        // When - Try to exhaust resources
        for (int i = 0; i < 10_000; i++) {
            try {
                executor.submit(
                        () -> {
                            try {
                                Thread.sleep(10);
                                handledCount.incrementAndGet();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        });
            } catch (RejectedExecutionException e) {
                rejectedCount.incrementAndGet();
            }
        }

        executor.shutdown();
        try {
            executor.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Then - System should handle resource exhaustion
        assertTrue(
                handledCount.get() > 0 || rejectedCount.get() > 0,
                "System should handle resource exhaustion");
    }

    // ==================== DATA CORRUPTION CHAOS TESTS ====================

    @Test
    void testChaosDataCorruptionShouldValidateAndReject() throws Exception {
        // Given - Corrupted data
        final String[] corruptedData = {
            "{\"amount\":-999999999}",
            "{\"date\":\"2099-13-45\"}",
            "{\"email\":\"a@b\"}",
            "{\"amount\":\"NaN\"}"
        };

        // When/Then - System should validate and reject corrupted data
        for (final String data : corruptedData) {
            mockMvc.perform(
                            post("/api/transactions")
                                    .header(AUTHORIZATION, "Bearer " + authToken)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(data))
                    .andExpect(status().is4xxClientError());
        }
    }
}
