package com.budgetbuddy.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.dto.AuthRequest;
import com.budgetbuddy.dto.AuthResponse;
import com.budgetbuddy.model.Subscription;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.SubscriptionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.SubscriptionRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.repository.dynamodb.UserRepository;
import com.budgetbuddy.service.AuthService;
import com.budgetbuddy.service.SubscriptionService;
import com.budgetbuddy.service.UserService;
import com.budgetbuddy.util.TableInitializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * Integration tests to investigate null subscription returns Tests various scenarios that might
 * cause null subscriptions in API responses
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
@SuppressWarnings("PMD.LawOfDemeter")
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
@Tag("integration")
public class SubscriptionNullHandlingIntegrationTest {

    private static final String NETFLIX = "Netflix";
    private static final String SUBSCRIPTIONS = "subscriptions";

    @Autowired private MockMvc mockMvc;

    @Autowired private SubscriptionService subscriptionService;

    @Autowired private SubscriptionRepository subscriptionRepository;

    @Autowired private TransactionRepository transactionRepository;

    @Autowired private AccountRepository accountRepository;

    @Autowired private UserRepository userRepository;

    @Autowired private UserService userService;

    @Autowired private AuthService authService;

    @Autowired private DynamoDbClient dynamoDbClient;

    @Autowired private ObjectMapper objectMapper;

    private String testUserId;
    private String testUserEmail;
    private UserTable testUser;
    private AccountTable testAccount;
    private String accessToken;

    @BeforeEach
    void setUp() {
        TableInitializer.ensureTablesInitializedAndVerified(dynamoDbClient);

        testUserEmail = "test-null-handling-" + UUID.randomUUID() + "@example.com";
        final String base64PasswordHash =
                java.util.Base64.getEncoder()
                        .encodeToString("test-password".getBytes(StandardCharsets.UTF_8));
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

        // Clean up
        subscriptionRepository
                .findByUserId(testUserId)
                .forEach(sub -> subscriptionRepository.delete(sub.getSubscriptionId()));
        transactionRepository
                .findByUserId(testUserId, 0, 10_000)
                .forEach(tx -> transactionRepository.delete(tx.getTransactionId()));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder withAuth(
            final org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
                    builder) {
        return builder.header("Authorization", "Bearer " + accessToken);
    }

    @Test
    @DisplayName("Should not return null subscriptions in GET /api/subscriptions response")
    void testGetSubscriptionsShouldNotReturnNulls() throws Exception {
        // Given: Create valid subscriptions directly in database
        final SubscriptionTable sub1 =
                createValidSubscriptionTable(
                        NETFLIX,
                        new BigDecimal("-15.99"),
                        Subscription.SubscriptionFrequency.MONTHLY,
                        LocalDate.now().minusMonths(1));
        final SubscriptionTable sub2 =
                createValidSubscriptionTable(
                        "Spotify",
                        new BigDecimal("-9.99"),
                        Subscription.SubscriptionFrequency.MONTHLY,
                        LocalDate.now().minusMonths(1));

        subscriptionRepository.save(sub1);
        subscriptionRepository.save(sub2);

        // When: Get subscriptions via API
        final var result =
                mockMvc.perform(
                                withAuth(get("/api/subscriptions"))
                                        .contentType(MediaType.APPLICATION_JSON))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data").isArray())
                        .andReturn();

        // Then: Response should not contain null subscriptions
        final String responseBody = result.getResponse().getContentAsString();
        assertNotNull(responseBody, "Response body should not be null");

        // Parse and verify - this is the most reliable way to check for nulls
        final List<Subscription> subscriptions =
                objectMapper.readValue(
                        responseBody,
                        objectMapper
                                .getTypeFactory()
                                .constructCollectionType(List.class, Subscription.class));

        assertNotNull(subscriptions, "Subscriptions list should not be null");
        assertFalse(subscriptions.isEmpty(), "Should have at least 2 subscriptions");
        assertEquals(2, subscriptions.size(), "Should have exactly 2 subscriptions");

        // CRITICAL: Verify no null subscriptions in the list
        for (final Subscription sub : subscriptions) {
            assertNotNull(sub, "Subscription object should not be null");
            assertNotNull(sub.getSubscriptionId(), "Subscription ID should not be null");
            assertNotNull(sub.getMerchantName(), "Merchant name should not be null");
            assertNotNull(sub.getAmount(), "Amount should not be null");
            assertNotNull(sub.getFrequency(), "Frequency should not be null");
            assertNotNull(sub.getStartDate(), "Start date should not be null");
            assertNotNull(sub.getNextPaymentDate(), "Next payment date should not be null");
        }
    }

    @Test
    @DisplayName("Should not return null subscriptions in GET /api/subscriptions/active response")
    void testGetActiveSubscriptionsShouldNotReturnNulls() throws Exception {
        // Given: Create active and inactive subscriptions
        final SubscriptionTable active1 =
                createValidSubscriptionTable(
                        NETFLIX,
                        new BigDecimal("-15.99"),
                        Subscription.SubscriptionFrequency.MONTHLY,
                        LocalDate.now().minusMonths(1));
        active1.setActive(true);

        final SubscriptionTable active2 =
                createValidSubscriptionTable(
                        "Spotify",
                        new BigDecimal("-9.99"),
                        Subscription.SubscriptionFrequency.MONTHLY,
                        LocalDate.now().minusMonths(1));
        active2.setActive(true);

        final SubscriptionTable inactive =
                createValidSubscriptionTable(
                        "Cancelled",
                        new BigDecimal("-10.00"),
                        Subscription.SubscriptionFrequency.MONTHLY,
                        LocalDate.now().minusMonths(1));
        inactive.setActive(false);

        subscriptionRepository.save(active1);
        subscriptionRepository.save(active2);
        subscriptionRepository.save(inactive);

        // When: Get active subscriptions via API
        final var result =
                mockMvc.perform(
                                withAuth(get("/api/subscriptions/active"))
                                        .contentType(MediaType.APPLICATION_JSON))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data").isArray())
                        .andReturn();

        // Then: Response should not contain null subscriptions
        final String responseBody = result.getResponse().getContentAsString();
        assertNotNull(responseBody, "Response body should not be null");

        // Parse and verify - this is the most reliable way to check for nulls
        final List<Subscription> subscriptions =
                objectMapper.readValue(
                        responseBody,
                        objectMapper
                                .getTypeFactory()
                                .constructCollectionType(List.class, Subscription.class));

        assertNotNull(subscriptions, "Subscriptions list should not be null");
        assertEquals(2, subscriptions.size(), "Should have exactly 2 active subscriptions");

        // CRITICAL: Verify no null subscriptions in the list
        for (final Subscription sub : subscriptions) {
            assertNotNull(sub, "Subscription object should not be null");
            assertNotNull(sub.getSubscriptionId(), "Subscription ID should not be null");
            assertNotNull(sub.getActive(), "Active flag should not be null");
            assertTrue(sub.getActive(), "All returned subscriptions should be active");
        }
    }

    @Test
    @DisplayName("Should filter out null subscriptions from toSubscription conversion")
    void testGetSubscriptionsFiltersNullConversions() throws Exception {
        // Given: Create subscription with potentially problematic data
        final SubscriptionTable problematicSub = new SubscriptionTable();
        problematicSub.setSubscriptionId(UUID.randomUUID().toString());
        problematicSub.setUserId(testUserId);
        problematicSub.setAccountId(testAccount.getAccountId());
        problematicSub.setMerchantName("Test Merchant");
        problematicSub.setAmount(new BigDecimal("-10.00"));
        problematicSub.setFrequency("MONTHLY");
        problematicSub.setStartDate(LocalDate.now().minusMonths(1).toString());
        problematicSub.setNextPaymentDate(LocalDate.now().plusMonths(1).toString());
        problematicSub.setCategory(SUBSCRIPTIONS);
        problematicSub.setActive(true);
        problematicSub.setCreatedAt(java.time.Instant.now());
        problematicSub.setUpdatedAt(java.time.Instant.now());

        subscriptionRepository.save(problematicSub);

        // When: Get subscriptions via service (direct call)
        final List<Subscription> subscriptions = subscriptionService.getSubscriptions(testUserId);

        // Then: Should not contain null subscriptions
        assertNotNull(subscriptions, "Subscriptions list should not be null");

        // Filter out any nulls that might have been created
        final List<Subscription> nonNullSubscriptions =
                subscriptions.stream().filter(sub -> sub != null).toList();

        assertEquals(
                subscriptions.size(),
                nonNullSubscriptions.size(),
                "All subscriptions should be non-null");

        // Verify via API as well
        final var result =
                mockMvc.perform(
                                withAuth(get("/api/subscriptions"))
                                        .contentType(MediaType.APPLICATION_JSON))
                        .andExpect(status().isOk())
                        .andReturn();

        final String responseBody = result.getResponse().getContentAsString();
        // Parse and verify - this is the most reliable way to check for nulls
        final List<Subscription> apiSubscriptions =
                objectMapper.readValue(
                        responseBody,
                        objectMapper
                                .getTypeFactory()
                                .constructCollectionType(List.class, Subscription.class));
        // Verify no null subscription objects
        for (final Subscription sub : apiSubscriptions) {
            assertNotNull(sub, "Subscription object should not be null");
        }
    }

    @Test
    @DisplayName("Should handle subscriptions with missing optional fields without returning null")
    void testGetSubscriptionsWithMissingOptionalFields() throws Exception {
        // Given: Create subscription with missing optional fields
        final SubscriptionTable subWithMissingFields = new SubscriptionTable();
        subWithMissingFields.setSubscriptionId(UUID.randomUUID().toString());
        subWithMissingFields.setUserId(testUserId);
        subWithMissingFields.setAccountId(testAccount.getAccountId());
        subWithMissingFields.setMerchantName("Test Merchant");
        subWithMissingFields.setAmount(new BigDecimal("-10.00"));
        subWithMissingFields.setFrequency("MONTHLY");
        subWithMissingFields.setStartDate(LocalDate.now().minusMonths(1).toString());
        subWithMissingFields.setNextPaymentDate(LocalDate.now().plusMonths(1).toString());
        subWithMissingFields.setCategory(SUBSCRIPTIONS);
        subWithMissingFields.setActive(true);
        // Intentionally leave description, subscriptionType, lastPaymentDate as null
        subWithMissingFields.setCreatedAt(java.time.Instant.now());
        subWithMissingFields.setUpdatedAt(java.time.Instant.now());

        subscriptionRepository.save(subWithMissingFields);

        // When: Get subscriptions via API
        final var result =
                mockMvc.perform(
                                withAuth(get("/api/subscriptions"))
                                        .contentType(MediaType.APPLICATION_JSON))
                        .andExpect(status().isOk())
                        .andReturn();

        // Then: Should return subscription even with missing optional fields
        final String responseBody = result.getResponse().getContentAsString();
        assertNotNull(responseBody, "Response body should not be null");

        // Parse and verify - this is the most reliable way to check for null subscription objects
        // Note: null field values (like "description": null) are valid JSON and not a problem
        // We only care about null subscription objects in the array
        final List<Subscription> subscriptions =
                objectMapper.readValue(
                        responseBody,
                        objectMapper
                                .getTypeFactory()
                                .constructCollectionType(List.class, Subscription.class));

        assertNotNull(subscriptions, "Subscriptions list should not be null");
        // Subscription with all required fields should be returned (optional fields can be null)
        // Required fields: subscriptionId, startDate, nextPaymentDate, frequency
        // This subscription has all required fields, so it should be returned
        assertEquals(
                1,
                subscriptions.size(),
                "Should have 1 subscription (all required fields present)");

        // CRITICAL: Verify subscription object itself is not null (not null field values)
        final Subscription sub = subscriptions.getFirst();
        assertNotNull(sub, "Subscription object should not be null");
        assertNotNull(sub.getSubscriptionId(), "Subscription ID should not be null");
        assertNotNull(sub.getStartDate(), "Start date should not be null");
        assertNotNull(sub.getNextPaymentDate(), "Next payment date should not be null");
        assertNotNull(sub.getFrequency(), "Frequency should not be null");

        // Optional fields can be null - that's OK
        // description, subscriptionType, lastPaymentDate are optional
    }

    @Test
    @DisplayName("Should handle subscriptions with invalid date formats gracefully")
    void testGetSubscriptionsWithInvalidDateFormats() throws Exception {
        // Given: Create subscription with potentially invalid date format
        final SubscriptionTable subWithInvalidDate = new SubscriptionTable();
        subWithInvalidDate.setSubscriptionId(UUID.randomUUID().toString());
        subWithInvalidDate.setUserId(testUserId);
        subWithInvalidDate.setAccountId(testAccount.getAccountId());
        subWithInvalidDate.setMerchantName("Test Merchant");
        subWithInvalidDate.setAmount(new BigDecimal("-10.00"));
        subWithInvalidDate.setFrequency("MONTHLY");
        subWithInvalidDate.setStartDate("invalid-date-format"); // Invalid date
        subWithInvalidDate.setNextPaymentDate(LocalDate.now().plusMonths(1).toString());
        subWithInvalidDate.setCategory(SUBSCRIPTIONS);
        subWithInvalidDate.setActive(true);
        subWithInvalidDate.setCreatedAt(java.time.Instant.now());
        subWithInvalidDate.setUpdatedAt(java.time.Instant.now());

        subscriptionRepository.save(subWithInvalidDate);

        // When: Get subscriptions via service
        final List<Subscription> subscriptions = subscriptionService.getSubscriptions(testUserId);

        // Then: Should filter out subscriptions that fail conversion (null from toSubscription)
        assertNotNull(subscriptions, "Subscriptions list should not be null");

        // Subscription with invalid date should be filtered out (toSubscription returns null)
        // So we should have 0 subscriptions, not a null subscription
        // Verify no nulls in the list
        for (final Subscription sub : subscriptions) {
            assertNotNull(sub, "Subscription object should not be null");
        }
        assertEquals(
                0,
                subscriptions.size(),
                "Invalid subscriptions should be filtered out, not returned as null");
    }

    @Test
    @DisplayName("Should handle subscriptions with invalid frequency gracefully")
    void testGetSubscriptionsWithInvalidFrequency() throws Exception {
        // Given: Create subscription with invalid frequency
        final SubscriptionTable subWithInvalidFreq = new SubscriptionTable();
        subWithInvalidFreq.setSubscriptionId(UUID.randomUUID().toString());
        subWithInvalidFreq.setUserId(testUserId);
        subWithInvalidFreq.setAccountId(testAccount.getAccountId());
        subWithInvalidFreq.setMerchantName("Test Merchant");
        subWithInvalidFreq.setAmount(new BigDecimal("-10.00"));
        subWithInvalidFreq.setFrequency("INVALID_FREQUENCY"); // Invalid frequency
        subWithInvalidFreq.setStartDate(LocalDate.now().minusMonths(1).toString());
        subWithInvalidFreq.setNextPaymentDate(LocalDate.now().plusMonths(1).toString());
        subWithInvalidFreq.setCategory(SUBSCRIPTIONS);
        subWithInvalidFreq.setActive(true);
        subWithInvalidFreq.setCreatedAt(java.time.Instant.now());
        subWithInvalidFreq.setUpdatedAt(java.time.Instant.now());

        subscriptionRepository.save(subWithInvalidFreq);

        // When: Get subscriptions via service
        final List<Subscription> subscriptions = subscriptionService.getSubscriptions(testUserId);

        // Then: Should filter out subscriptions that fail conversion
        assertNotNull(subscriptions, "Subscriptions list should not be null");
        // Verify no nulls in the list
        for (final Subscription sub : subscriptions) {
            assertNotNull(sub, "Subscription object should not be null");
        }
        assertEquals(
                0,
                subscriptions.size(),
                "Invalid subscriptions should be filtered out, not returned as null");
    }

    @Test
    @DisplayName("Should not return null when detectSubscriptions returns empty list")
    void testDetectSubscriptionsEmptyListShouldNotReturnNull() throws Exception {
        // Given: No transactions exist

        // When: Detect subscriptions
        final var result =
                mockMvc.perform(
                                withAuth(post("/api/subscriptions/detect"))
                                        .contentType(MediaType.APPLICATION_JSON))
                        .andExpect(status().isOk())
                        .andReturn();

        // Then: Should return empty array, not null
        final String responseBody = result.getResponse().getContentAsString();
        assertNotNull(responseBody, "Response body should not be null");
        assertTrue(
                "[]".equals(responseBody) || "[]\n".equals(responseBody),
                "Should return empty array, not null");

        final List<Subscription> subscriptions =
                objectMapper.readValue(
                        responseBody,
                        objectMapper
                                .getTypeFactory()
                                .constructCollectionType(List.class, Subscription.class));

        assertNotNull(subscriptions, "Subscriptions list should not be null");
        assertTrue(subscriptions.isEmpty(), "Should be empty list, not null");
    }

    @Test
    @DisplayName("Should verify toSubscription method never returns null for valid data")
    void testToSubscriptionValidDataShouldNotReturnNull() {
        // Given: Valid subscription table
        final SubscriptionTable table =
                createValidSubscriptionTable(
                        NETFLIX,
                        new BigDecimal("-15.99"),
                        Subscription.SubscriptionFrequency.MONTHLY,
                        LocalDate.now().minusMonths(1));
        subscriptionRepository.save(table);

        // When: Convert to Subscription
        final List<Subscription> subscriptions = subscriptionService.getSubscriptions(testUserId);

        // Then: Should not be null
        assertNotNull(subscriptions, "Subscriptions list should not be null");
        assertEquals(1, subscriptions.size(), "Should have 1 subscription");
        assertNotNull(subscriptions.getFirst(), "Subscription should not be null");
    }

    @Test
    @DisplayName("Should verify API response structure for subscriptions")
    void testGetSubscriptionsResponseStructure() throws Exception {
        // Given: Create valid subscription
        final SubscriptionTable sub =
                createValidSubscriptionTable(
                        NETFLIX,
                        new BigDecimal("-15.99"),
                        Subscription.SubscriptionFrequency.MONTHLY,
                        LocalDate.now().minusMonths(1));
        subscriptionRepository.save(sub);

        // When: Get subscriptions via API
        final var result =
                mockMvc.perform(
                                withAuth(get("/api/subscriptions"))
                                        .contentType(MediaType.APPLICATION_JSON))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data").isArray())
                        .andExpect(jsonPath("$.data[0]").exists())
                        .andExpect(jsonPath("$.data[0].subscriptionId").exists())
                        .andExpect(jsonPath("$.data[0].merchantName").exists())
                        .andExpect(jsonPath("$.data[0].amount").exists())
                        .andExpect(jsonPath("$.data[0].frequency").exists())
                        .andExpect(jsonPath("$.data[0].startDate").exists())
                        .andReturn();

        // Then: Verify response doesn't contain null subscription objects
        final String responseBody = result.getResponse().getContentAsString();

        // Parse and verify - this is the most reliable way to check for nulls
        final List<Subscription> subscriptions =
                objectMapper.readValue(
                        responseBody,
                        objectMapper
                                .getTypeFactory()
                                .constructCollectionType(List.class, Subscription.class));

        assertNotNull(subscriptions, "Subscriptions list should not be null");
        assertEquals(1, subscriptions.size(), "Should have 1 subscription");

        // CRITICAL: Verify subscription object is not null
        final Subscription subscriptionObj = subscriptions.getFirst();
        assertNotNull(subscriptionObj, "Subscription object should not be null");
        assertNotNull(subscriptionObj.getSubscriptionId(), "Subscription ID should not be null");
        assertNotNull(subscriptionObj.getMerchantName(), "Merchant name should not be null");
    }

    @Test
    @DisplayName("Should handle mixed valid and invalid subscriptions correctly")
    void testGetSubscriptionsMixedValidAndInvalid() throws Exception {
        // Given: Create mix of valid and invalid subscriptions
        final SubscriptionTable valid1 =
                createValidSubscriptionTable(
                        NETFLIX,
                        new BigDecimal("-15.99"),
                        Subscription.SubscriptionFrequency.MONTHLY,
                        LocalDate.now().minusMonths(1));
        subscriptionRepository.save(valid1);

        final SubscriptionTable valid2 =
                createValidSubscriptionTable(
                        "Spotify",
                        new BigDecimal("-9.99"),
                        Subscription.SubscriptionFrequency.MONTHLY,
                        LocalDate.now().minusMonths(1));
        subscriptionRepository.save(valid2);

        // Invalid: missing required fields
        final SubscriptionTable invalid1 = new SubscriptionTable();
        invalid1.setSubscriptionId(UUID.randomUUID().toString());
        invalid1.setUserId(testUserId);
        // Missing accountId, merchantName, amount, frequency, startDate
        invalid1.setActive(true);
        subscriptionRepository.save(invalid1);

        // When: Get subscriptions via API
        final var result =
                mockMvc.perform(
                                withAuth(get("/api/subscriptions"))
                                        .contentType(MediaType.APPLICATION_JSON))
                        .andExpect(status().isOk())
                        .andReturn();

        // Then: Should only return valid subscriptions, filter out invalid ones
        final String responseBody = result.getResponse().getContentAsString();
        final List<Subscription> subscriptions =
                objectMapper.readValue(
                        responseBody,
                        objectMapper
                                .getTypeFactory()
                                .constructCollectionType(List.class, Subscription.class));

        assertNotNull(subscriptions, "Subscriptions list should not be null");
        // Should have at least 2 valid subscriptions (invalid one should be filtered out)
        assertTrue(subscriptions.size() >= 2, "Should have at least 2 valid subscriptions");

        // Verify no nulls
        for (final Subscription sub : subscriptions) {
            assertNotNull(sub, "Subscription should not be null");
            assertNotNull(sub.getSubscriptionId(), "Subscription ID should not be null");
        }
    }

    @Test
    @DisplayName("Should verify service layer filters nulls before returning")
    void testServiceLayerFiltersNulls() {
        // Given: Create subscription that might cause null in conversion
        final SubscriptionTable sub =
                createValidSubscriptionTable(
                        "Test",
                        new BigDecimal("-10.00"),
                        Subscription.SubscriptionFrequency.MONTHLY,
                        LocalDate.now().minusMonths(1));
        subscriptionRepository.save(sub);

        // When: Get subscriptions via service
        final List<Subscription> subscriptions = subscriptionService.getSubscriptions(testUserId);

        // Then: Service should filter out any nulls
        assertNotNull(subscriptions, "Service should not return null list");

        // Count non-null subscriptions
        final long nonNullCount =
                subscriptions.stream().filter(subscription -> subscription != null).count();

        assertEquals(
                subscriptions.size(),
                nonNullCount,
                "Service should filter out null subscriptions before returning");
    }

    // Helper method to create valid subscription table
    private SubscriptionTable createValidSubscriptionTable(
            final String merchant,
            final BigDecimal amount,
            final Subscription.SubscriptionFrequency frequency,
            final LocalDate startDate) {
        final SubscriptionTable table = new SubscriptionTable();
        table.setSubscriptionId(UUID.randomUUID().toString());
        table.setUserId(testUserId);
        table.setAccountId(testAccount.getAccountId());
        table.setMerchantName(merchant);
        table.setDescription(merchant + " Subscription");
        table.setAmount(amount);
        table.setFrequency(frequency.name());
        table.setStartDate(startDate.toString());
        table.setNextPaymentDate(startDate.plusMonths(1).toString());
        table.setCategory(SUBSCRIPTIONS);
        table.setSubscriptionType("streaming");
        table.setActive(true);
        table.setCreatedAt(java.time.Instant.now());
        table.setUpdatedAt(java.time.Instant.now());
        return table;
    }
}
