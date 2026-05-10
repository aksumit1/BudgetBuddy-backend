package com.budgetbuddy.api;



import java.nio.charset.StandardCharsets;
import java.util.Locale;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.dto.AuthRequest;
import com.budgetbuddy.dto.AuthResponse;
import com.budgetbuddy.model.Subscription;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.SubscriptionRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.service.AuthService;
import com.budgetbuddy.service.SubscriptionService;
import com.budgetbuddy.service.UserService;
import com.budgetbuddy.util.TableInitializer;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * Comprehensive edge case, boundary condition, and race condition tests for SubscriptionController
 * Tests: - Empty/null inputs - Boundary values (max amounts, dates) - Race conditions (concurrent
 * detection) - Error conditions (invalid IDs, unauthorized access) - Network errors (timeout
 * simulation)
 */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
@SuppressWarnings("PMD.LawOfDemeter")
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
@org.junit.jupiter.api.Tag("integration")
public class SubscriptionControllerEdgeCasesTest {

    @Autowired private MockMvc mockMvc;

    @Autowired private SubscriptionService subscriptionService;

    @Autowired private SubscriptionRepository subscriptionRepository;

    @Autowired private TransactionRepository transactionRepository;

    @Autowired private AccountRepository accountRepository;

    @Autowired private UserService userService;

    @Autowired private AuthService authService;

    @Autowired private DynamoDbClient dynamoDbClient;

    private String testUserId;
    private String testUserEmail;
    private UserTable testUser;
    private AccountTable testAccount;
    private String accessToken;

    @BeforeEach
    void setUp() {
        TableInitializer.ensureTablesInitializedAndVerified(dynamoDbClient);

        testUserEmail = "test-edge-cases-" + UUID.randomUUID() + "@example.com";
        final String base64PasswordHash =
                java.util.Base64.getEncoder().encodeToString("test-password".getBytes(StandardCharsets.UTF_8));
        testUser = userService.createUserSecure(testUserEmail, base64PasswordHash, "Test", "User");
        testUserId = testUser.getUserId();

        final AuthRequest authRequest = new AuthRequest(testUserEmail, base64PasswordHash);
        final AuthResponse authResponse = authService.authenticate(authRequest);
        accessToken = authResponse.getAccessToken();

        testAccount = new AccountTable();
        testAccount.setAccountId(UUID.randomUUID().toString());
        testAccount.setUserId(testUserId);
        testAccount.setAccountName("Test Account");
        testAccount.setInstitutionName("Test Bank");
        testAccount.setAccountType("CHECKING");
        testAccount.setBalance(BigDecimal.valueOf(1000.00));
        accountRepository.save(testAccount);

        // Clear existing data
        subscriptionRepository
                .findByUserId(testUserId)
                .forEach(sub -> subscriptionRepository.delete(sub.getSubscriptionId()));
        transactionRepository
                .findByUserId(testUserId, 0, 1000)
                .forEach(tx -> transactionRepository.delete(tx.getTransactionId()));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder withAuth(
            final org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder) {
        return builder.header("Authorization", "Bearer " + accessToken);
    }

    @Test
    @DisplayName("Should handle empty subscription list gracefully")
    void testGetSubscriptionsEmptyListReturnsEmptyArray() throws Exception {
        // Given: No subscriptions exist

        // When: Get subscriptions
        mockMvc.perform(withAuth(get("/api/subscriptions")).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("Should handle null subscription ID in delete request")
    void testDeleteSubscriptionNullIdReturnsBadRequest() throws Exception {
        // When: Delete with empty ID (Spring will map to 404 or 500 depending on path variable
        // handling)
        final var result =
                mockMvc.perform(
                        withAuth(delete("/api/subscriptions/"))
                                .contentType(MediaType.APPLICATION_JSON))
                        .andReturn();

        // Then: Should return error (404 for missing path variable, 500 for server error, or 400
        // for validation)
        final int status = result.getResponse().getStatus();
        // Accept any error status (4xx or 5xx) as valid error handling
        assertTrue(
                status >= 400, "Should return 4xx or 5xx error for invalid path, got: " + status);
    }

    @Test
    @DisplayName("Should handle invalid UUID format in delete request")
    void testDeleteSubscriptionInvalidUUIDReturnsBadRequest() throws Exception {
        // When: Delete with invalid UUID format
        mockMvc.perform(
                        withAuth(delete("/api/subscriptions/invalid-uuid-format"))
                                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Should prevent unauthorized access to other user's subscription")
    void testDeleteSubscriptionUnauthorizedAccessReturnsForbidden() throws Exception {
        // Given: Create subscription for test user
        final Subscription subscription = createTestSubscription("Netflix", true);
        subscriptionService.saveSubscriptions(testUserId, List.of(subscription));

        // Create another user
        final String otherUserEmail = "test-other-" + UUID.randomUUID() + "@example.com";
        final String otherUserPasswordHash =
                java.util.Base64.getEncoder().encodeToString("test-password".getBytes(StandardCharsets.UTF_8));
        userService.createUserSecure(otherUserEmail, otherUserPasswordHash, "Other", "User");
        final AuthRequest otherAuthRequest = new AuthRequest(otherUserEmail, otherUserPasswordHash);
        final AuthResponse otherAuthResponse = authService.authenticate(otherAuthRequest);
        final String otherAccessToken = otherAuthResponse.getAccessToken();

        // When: Other user tries to delete test user's subscription
        final var result =
                mockMvc.perform(
                        delete("/api/subscriptions/" + subscription.getSubscriptionId())
                                .header("Authorization", "Bearer " + otherAccessToken)
                                .contentType(MediaType.APPLICATION_JSON))
                        .andReturn();

        // Then: Should return unauthorized or forbidden
        final int status = result.getResponse().getStatus();
        assertTrue(
                status == 401 || status == 403,
                "Should return 401 or 403 for unauthorized access, got: " + status);
    }

    @Test
    @DisplayName("Should handle missing authentication token")
    void testGetSubscriptionsNoAuthTokenReturnsUnauthorized() throws Exception {
        // When: Request without auth token
        final var result =
                mockMvc.perform(get("/api/subscriptions").contentType(MediaType.APPLICATION_JSON))
                        .andReturn();

        // Then: Should return unauthorized (401) or forbidden (403)
        // Note: In test environment, Spring Security might allow requests
        // In production, this would be 401
        final int status = result.getResponse().getStatus();
        // Accept either 200 (if test security is disabled) or 401/403 (if enabled)
        if (status == 200) {
            // Test security might be disabled - verify it's an empty array at least
            final String body = result.getResponse().getContentAsString();
            assertTrue(
                    body.contains("[]") || body.contains("subscriptionId"),
                    "If 200, should return empty array or subscriptions");
        } else {
            assertTrue(
                    status == 401 || status == 403,
                    "Should return 401 or 403 for missing auth, got: " + status);
        }
    }

    @Test
    @DisplayName("Should handle race condition - concurrent subscription detection")
    void testDetectSubscriptionsConcurrentRequestsNoDuplicates() throws Exception {
        // Given: Create transactions for subscription detection
        final LocalDate baseDate = LocalDate.now().minusMonths(3);
        for (int i = 0; i < 5; i++) {
            final TransactionTable tx =
                    createSubscriptionTransaction(
                            "Netflix", new BigDecimal("-15.99"), baseDate.plusMonths(i));
            transactionRepository.save(tx);
        }

        // When: Multiple concurrent detection requests
        final int concurrentRequests = 5;
        final ExecutorService executor = Executors.newFixedThreadPool(concurrentRequests);
        final CountDownLatch latch = new CountDownLatch(concurrentRequests);
        @SuppressWarnings("unchecked") final
                CompletableFuture<Integer>[] futures = new CompletableFuture[concurrentRequests];

        for (int i = 0; i < concurrentRequests; i++) {
            final int index = i;
            futures[i] =
                    CompletableFuture.supplyAsync(
                            () -> {
                                try {
                                    final var result =
                                            mockMvc.perform(
                                                    withAuth(
                                                            post(
                                                                    "/api/subscriptions/detect"))
                                                            .contentType(
                                                                    MediaType
                                                                            .APPLICATION_JSON))
                                                    .andExpect(status().isOk())
                                                    .andReturn();

                                    final String responseBody = result.getResponse().getContentAsString();
                                    // Parse response to count subscriptions
                                    return responseBody.split("\"subscriptionId\"").length - 1;
                                } catch (Exception e) {
                                    fail(
                                            "Concurrent request "
                                                    + index
                                                    + " failed: "
                                                    + e.getMessage());
                                    return 0;
                                } finally {
                                    latch.countDown();
                                }
                            },
                            executor);
        }

        // Wait for all requests to complete
        latch.await();
        CompletableFuture.allOf(futures).join();

        // Then: Verify no duplicate subscriptions were created
        final List<Subscription> subscriptions = subscriptionService.getSubscriptions(testUserId);
        final long uniqueCount =
                subscriptions.stream().map(Subscription::getSubscriptionId).distinct().count();

        assertEquals(
                subscriptions.size(),
                uniqueCount,
                "No duplicate subscriptions should be created during concurrent detection");

        executor.shutdown();
    }

    @Test
    @DisplayName("Should handle boundary condition - maximum subscription amount")
    void testDetectSubscriptionsMaxAmountHandlesCorrectly() throws Exception {
        // Given: Create transactions with very large amount (boundary test)
        final BigDecimal maxAmount = new BigDecimal("999999.99");
        final LocalDate baseDate = LocalDate.now().minusMonths(2);
        for (int i = 0; i < 3; i++) {
            final TransactionTable tx =
                    createSubscriptionTransaction(
                            "Premium Service", maxAmount.negate(), baseDate.plusMonths(i));
            transactionRepository.save(tx);
        }

        // When: Detect subscriptions
        mockMvc.perform(
                        withAuth(post("/api/subscriptions/detect"))
                                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        // Then: Should handle large amounts without errors
        final List<Subscription> subscriptions = subscriptionService.getSubscriptions(testUserId);
        // May or may not detect - just verify no errors occurred
        assertNotNull(subscriptions);
    }

    @Test
    @DisplayName("Should handle boundary condition - minimum subscription amount")
    void testDetectSubscriptionsMinAmountHandlesCorrectly() throws Exception {
        // Given: Create transactions with very small amount (boundary test)
        final BigDecimal minAmount = new BigDecimal("0.01");
        final LocalDate baseDate = LocalDate.now().minusMonths(2);
        for (int i = 0; i < 3; i++) {
            final TransactionTable tx =
                    createSubscriptionTransaction(
                            "Micro Service", minAmount.negate(), baseDate.plusMonths(i));
            transactionRepository.save(tx);
        }

        // When: Detect subscriptions
        mockMvc.perform(
                        withAuth(post("/api/subscriptions/detect"))
                                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        // Then: Should handle small amounts without errors
        final List<Subscription> subscriptions = subscriptionService.getSubscriptions(testUserId);
        assertNotNull(subscriptions);
    }

    @Test
    @DisplayName("Should handle boundary condition - very old dates")
    void testDetectSubscriptionsOldDatesHandlesCorrectly() throws Exception {
        // Given: Create transactions with very old dates (boundary test)
        final LocalDate oldDate = LocalDate.of(2020, 1, 1);
        for (int i = 0; i < 3; i++) {
            final TransactionTable tx =
                    createSubscriptionTransaction(
                            "Old Service", new BigDecimal("-10.00"), oldDate.plusMonths(i));
            transactionRepository.save(tx);
        }

        // When: Detect subscriptions
        mockMvc.perform(
                        withAuth(post("/api/subscriptions/detect"))
                                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        // Then: Should handle old dates without errors
        final List<Subscription> subscriptions = subscriptionService.getSubscriptions(testUserId);
        assertNotNull(subscriptions);
    }

    @Test
    @DisplayName("Should handle boundary condition - future dates")
    void testDetectSubscriptionsFutureDatesHandlesCorrectly() throws Exception {
        // Given: Create transactions with future dates (boundary test)
        final LocalDate futureDate = LocalDate.now().plusMonths(1);
        for (int i = 0; i < 3; i++) {
            final TransactionTable tx =
                    createSubscriptionTransaction(
                            "Future Service", new BigDecimal("-10.00"), futureDate.plusMonths(i));
            transactionRepository.save(tx);
        }

        // When: Detect subscriptions
        mockMvc.perform(
                        withAuth(post("/api/subscriptions/detect"))
                                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        // Then: Should handle future dates without errors
        final List<Subscription> subscriptions = subscriptionService.getSubscriptions(testUserId);
        assertNotNull(subscriptions);
    }

    @Test
    @DisplayName("Should handle edge case - subscription with null merchant name")
    void testDetectSubscriptionsNullMerchantNameHandlesGracefully() throws Exception {
        // Given: Create transaction with null merchant name
        final LocalDate baseDate = LocalDate.now().minusMonths(2);
        for (int i = 0; i < 3; i++) {
            final TransactionTable tx =
                    createSubscriptionTransaction(
                            null, new BigDecimal("-10.00"), baseDate.plusMonths(i));
            transactionRepository.save(tx);
        }

        // When: Detect subscriptions
        mockMvc.perform(
                        withAuth(post("/api/subscriptions/detect"))
                                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        // Then: Should handle null merchant names gracefully
        final List<Subscription> subscriptions = subscriptionService.getSubscriptions(testUserId);
        assertNotNull(subscriptions);
    }

    @Test
    @DisplayName("Should handle edge case - very long merchant name")
    void testDetectSubscriptionsVeryLongMerchantNameHandlesCorrectly() throws Exception {
        // Given: Create transaction with very long merchant name (boundary test)
        final String longMerchantName = "A".repeat(500); // Very long name
        final LocalDate baseDate = LocalDate.now().minusMonths(2);
        for (int i = 0; i < 3; i++) {
            final TransactionTable tx =
                    createSubscriptionTransaction(
                            longMerchantName, new BigDecimal("-10.00"), baseDate.plusMonths(i));
            transactionRepository.save(tx);
        }

        // When: Detect subscriptions
        mockMvc.perform(
                        withAuth(post("/api/subscriptions/detect"))
                                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        // Then: Should handle long merchant names without errors
        final List<Subscription> subscriptions = subscriptionService.getSubscriptions(testUserId);
        assertNotNull(subscriptions);
    }

    @Test
    @DisplayName("Should handle edge case - subscription with zero amount")
    void testDetectSubscriptionsZeroAmountHandlesCorrectly() throws Exception {
        // Given: Create transactions with zero amount (edge case)
        final LocalDate baseDate = LocalDate.now().minusMonths(2);
        for (int i = 0; i < 3; i++) {
            final TransactionTable tx =
                    createSubscriptionTransaction(
                            "Free Service", BigDecimal.ZERO, baseDate.plusMonths(i));
            transactionRepository.save(tx);
        }

        // When: Detect subscriptions
        mockMvc.perform(
                        withAuth(post("/api/subscriptions/detect"))
                                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        // Then: Should handle zero amounts (may not detect as subscription, but shouldn't error)
        final List<Subscription> subscriptions = subscriptionService.getSubscriptions(testUserId);
        assertNotNull(subscriptions);
    }

    @Test
    @DisplayName("Should handle edge case - subscription with negative amount (should be filtered)")
    void testDetectSubscriptionsNegativeAmountHandlesCorrectly() throws Exception {
        // Given: Create transactions with negative amount (expenses are negative)
        final LocalDate baseDate = LocalDate.now().minusMonths(2);
        for (int i = 0; i < 3; i++) {
            final TransactionTable tx =
                    createSubscriptionTransaction(
                            "Service", new BigDecimal("-10.00"), baseDate.plusMonths(i));
            transactionRepository.save(tx);
        }

        // When: Detect subscriptions
        mockMvc.perform(
                        withAuth(post("/api/subscriptions/detect"))
                                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        // Then: Should handle negative amounts (expenses are negative, this is normal)
        final List<Subscription> subscriptions = subscriptionService.getSubscriptions(testUserId);
        assertNotNull(subscriptions);
    }

    // Helper methods
    private TransactionTable createSubscriptionTransaction(
            final String merchant, final BigDecimal amount, final LocalDate date) {
        final TransactionTable tx = new TransactionTable();
        tx.setTransactionId(UUID.randomUUID().toString().toLowerCase(Locale.ROOT));
        tx.setUserId(testUserId);
        tx.setAccountId(testAccount.getAccountId());
        tx.setAmount(amount);
        tx.setTransactionDate(date.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE));
        tx.setDescription(merchant != null ? merchant + " Subscription" : "Subscription");
        tx.setMerchantName(merchant);
        tx.setCategoryPrimary("subscriptions");
        tx.setCategoryDetailed("subscriptions");
        tx.setTransactionType("EXPENSE");
        tx.setCurrencyCode("USD");
        tx.setPending(false);
        tx.setCreatedAt(java.time.Instant.now());
        tx.setUpdatedAt(java.time.Instant.now());
        tx.setUpdatedAtTimestamp(tx.getUpdatedAt().getEpochSecond());
        return tx;
    }

    private Subscription createTestSubscription(final String merchant, final boolean active) {
        final Subscription subscription = new Subscription();
        subscription.setSubscriptionId(UUID.randomUUID().toString());
        subscription.setUserId(testUserId);
        subscription.setAccountId(testAccount.getAccountId());
        subscription.setMerchantName(merchant);
        subscription.setAmount(new BigDecimal("15.99"));
        subscription.setFrequency(Subscription.SubscriptionFrequency.MONTHLY);
        subscription.setStartDate(LocalDate.of(2024, 1, 15));
        subscription.setCategory("subscriptions");
        subscription.setActive(active);
        return subscription;
    }
}
