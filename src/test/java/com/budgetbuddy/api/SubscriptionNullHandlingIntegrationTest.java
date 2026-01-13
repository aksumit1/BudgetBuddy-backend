package com.budgetbuddy.api;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.dto.AuthRequest;
import com.budgetbuddy.dto.AuthResponse;
import com.budgetbuddy.model.Subscription;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.SubscriptionTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests to investigate null subscription returns
 * Tests various scenarios that might cause null subscriptions in API responses
 */
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
@Tag("integration")
public class SubscriptionNullHandlingIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SubscriptionService subscriptionService;

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserService userService;

    @Autowired
    private AuthService authService;

    @Autowired
    private DynamoDbClient dynamoDbClient;

    @Autowired
    private ObjectMapper objectMapper;

    private String testUserId;
    private String testUserEmail;
    private UserTable testUser;
    private AccountTable testAccount;
    private String accessToken;

    @BeforeEach
    void setUp() {
        TableInitializer.ensureTablesInitializedAndVerified(dynamoDbClient);

        testUserEmail = "test-null-handling-" + UUID.randomUUID() + "@example.com";
        String base64PasswordHash = java.util.Base64.getEncoder().encodeToString("test-password".getBytes());
        testUser = userService.createUserSecure(
                testUserEmail,
                base64PasswordHash,
                "Test",
                "User"
        );
        testUserId = testUser.getUserId();

        AuthRequest authRequest = new AuthRequest(testUserEmail, base64PasswordHash);
        AuthResponse authResponse = authService.authenticate(authRequest);
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
        subscriptionRepository.findByUserId(testUserId).forEach(sub -> 
            subscriptionRepository.delete(sub.getSubscriptionId())
        );
        transactionRepository.findByUserId(testUserId, 0, 10000).forEach(tx ->
            transactionRepository.delete(tx.getTransactionId())
        );
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder withAuth(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder) {
        return builder.header("Authorization", "Bearer " + accessToken);
    }

    @Test
    @DisplayName("Should not return null subscriptions in GET /api/subscriptions response")
    void testGetSubscriptions_ShouldNotReturnNulls() throws Exception {
        // Given: Create valid subscriptions directly in database
        SubscriptionTable sub1 = createValidSubscriptionTable("Netflix", new BigDecimal("-15.99"), 
            Subscription.SubscriptionFrequency.MONTHLY, LocalDate.now().minusMonths(1));
        SubscriptionTable sub2 = createValidSubscriptionTable("Spotify", new BigDecimal("-9.99"), 
            Subscription.SubscriptionFrequency.MONTHLY, LocalDate.now().minusMonths(1));
        
        subscriptionRepository.save(sub1);
        subscriptionRepository.save(sub2);

        // When: Get subscriptions via API
        var result = mockMvc.perform(withAuth(get("/api/subscriptions"))
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andReturn();

        // Then: Response should not contain null subscriptions
        String responseBody = result.getResponse().getContentAsString();
        assertNotNull(responseBody, "Response body should not be null");
        
        // Parse and verify - this is the most reliable way to check for nulls
        List<Subscription> subscriptions = objectMapper.readValue(
            responseBody, 
            objectMapper.getTypeFactory().constructCollectionType(List.class, Subscription.class)
        );
        
        assertNotNull(subscriptions, "Subscriptions list should not be null");
        assertFalse(subscriptions.isEmpty(), "Should have at least 2 subscriptions");
        assertEquals(2, subscriptions.size(), "Should have exactly 2 subscriptions");
        
        // CRITICAL: Verify no null subscriptions in the list
        for (Subscription sub : subscriptions) {
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
    void testGetActiveSubscriptions_ShouldNotReturnNulls() throws Exception {
        // Given: Create active and inactive subscriptions
        SubscriptionTable active1 = createValidSubscriptionTable("Netflix", new BigDecimal("-15.99"), 
            Subscription.SubscriptionFrequency.MONTHLY, LocalDate.now().minusMonths(1));
        active1.setActive(true);
        
        SubscriptionTable active2 = createValidSubscriptionTable("Spotify", new BigDecimal("-9.99"), 
            Subscription.SubscriptionFrequency.MONTHLY, LocalDate.now().minusMonths(1));
        active2.setActive(true);
        
        SubscriptionTable inactive = createValidSubscriptionTable("Cancelled", new BigDecimal("-10.00"), 
            Subscription.SubscriptionFrequency.MONTHLY, LocalDate.now().minusMonths(1));
        inactive.setActive(false);
        
        subscriptionRepository.save(active1);
        subscriptionRepository.save(active2);
        subscriptionRepository.save(inactive);

        // When: Get active subscriptions via API
        var result = mockMvc.perform(withAuth(get("/api/subscriptions/active"))
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andReturn();

        // Then: Response should not contain null subscriptions
        String responseBody = result.getResponse().getContentAsString();
        assertNotNull(responseBody, "Response body should not be null");
        
        // Parse and verify - this is the most reliable way to check for nulls
        List<Subscription> subscriptions = objectMapper.readValue(
            responseBody, 
            objectMapper.getTypeFactory().constructCollectionType(List.class, Subscription.class)
        );
        
        assertNotNull(subscriptions, "Subscriptions list should not be null");
        assertEquals(2, subscriptions.size(), "Should have exactly 2 active subscriptions");
        
        // CRITICAL: Verify no null subscriptions in the list
        for (Subscription sub : subscriptions) {
            assertNotNull(sub, "Subscription object should not be null");
            assertNotNull(sub.getSubscriptionId(), "Subscription ID should not be null");
            assertNotNull(sub.getActive(), "Active flag should not be null");
            assertTrue(sub.getActive(), "All returned subscriptions should be active");
        }
    }

    @Test
    @DisplayName("Should filter out null subscriptions from toSubscription conversion")
    void testGetSubscriptions_FiltersNullConversions() throws Exception {
        // Given: Create subscription with potentially problematic data
        SubscriptionTable problematicSub = new SubscriptionTable();
        problematicSub.setSubscriptionId(UUID.randomUUID().toString());
        problematicSub.setUserId(testUserId);
        problematicSub.setAccountId(testAccount.getAccountId());
        problematicSub.setMerchantName("Test Merchant");
        problematicSub.setAmount(new BigDecimal("-10.00"));
        problematicSub.setFrequency("MONTHLY");
        problematicSub.setStartDate(LocalDate.now().minusMonths(1).toString());
        problematicSub.setNextPaymentDate(LocalDate.now().plusMonths(1).toString());
        problematicSub.setCategory("subscriptions");
        problematicSub.setActive(true);
        problematicSub.setCreatedAt(java.time.Instant.now());
        problematicSub.setUpdatedAt(java.time.Instant.now());
        
        subscriptionRepository.save(problematicSub);

        // When: Get subscriptions via service (direct call)
        List<Subscription> subscriptions = subscriptionService.getSubscriptions(testUserId);

        // Then: Should not contain null subscriptions
        assertNotNull(subscriptions, "Subscriptions list should not be null");
        
        // Filter out any nulls that might have been created
        List<Subscription> nonNullSubscriptions = subscriptions.stream()
            .filter(sub -> sub != null)
            .toList();
        
        assertEquals(subscriptions.size(), nonNullSubscriptions.size(), 
            "All subscriptions should be non-null");
        
        // Verify via API as well
        var result = mockMvc.perform(withAuth(get("/api/subscriptions"))
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();
        
        String responseBody = result.getResponse().getContentAsString();
        // Parse and verify - this is the most reliable way to check for nulls
        List<Subscription> apiSubscriptions = objectMapper.readValue(
            responseBody, 
            objectMapper.getTypeFactory().constructCollectionType(List.class, Subscription.class)
        );
        // Verify no null subscription objects
        for (Subscription sub : apiSubscriptions) {
            assertNotNull(sub, "Subscription object should not be null");
        }
    }

    @Test
    @DisplayName("Should handle subscriptions with missing optional fields without returning null")
    void testGetSubscriptions_WithMissingOptionalFields() throws Exception {
        // Given: Create subscription with missing optional fields
        SubscriptionTable subWithMissingFields = new SubscriptionTable();
        subWithMissingFields.setSubscriptionId(UUID.randomUUID().toString());
        subWithMissingFields.setUserId(testUserId);
        subWithMissingFields.setAccountId(testAccount.getAccountId());
        subWithMissingFields.setMerchantName("Test Merchant");
        subWithMissingFields.setAmount(new BigDecimal("-10.00"));
        subWithMissingFields.setFrequency("MONTHLY");
        subWithMissingFields.setStartDate(LocalDate.now().minusMonths(1).toString());
        subWithMissingFields.setNextPaymentDate(LocalDate.now().plusMonths(1).toString());
        subWithMissingFields.setCategory("subscriptions");
        subWithMissingFields.setActive(true);
        // Intentionally leave description, subscriptionType, lastPaymentDate as null
        subWithMissingFields.setCreatedAt(java.time.Instant.now());
        subWithMissingFields.setUpdatedAt(java.time.Instant.now());
        
        subscriptionRepository.save(subWithMissingFields);

        // When: Get subscriptions via API
        var result = mockMvc.perform(withAuth(get("/api/subscriptions"))
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        // Then: Should return subscription even with missing optional fields
        String responseBody = result.getResponse().getContentAsString();
        assertNotNull(responseBody, "Response body should not be null");
        
        // Parse and verify - this is the most reliable way to check for null subscription objects
        // Note: null field values (like "description": null) are valid JSON and not a problem
        // We only care about null subscription objects in the array
        List<Subscription> subscriptions = objectMapper.readValue(
            responseBody, 
            objectMapper.getTypeFactory().constructCollectionType(List.class, Subscription.class)
        );
        
        assertNotNull(subscriptions, "Subscriptions list should not be null");
        // Subscription with all required fields should be returned (optional fields can be null)
        // Required fields: subscriptionId, startDate, nextPaymentDate, frequency
        // This subscription has all required fields, so it should be returned
        assertEquals(1, subscriptions.size(), "Should have 1 subscription (all required fields present)");
        
        // CRITICAL: Verify subscription object itself is not null (not null field values)
        Subscription sub = subscriptions.get(0);
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
    void testGetSubscriptions_WithInvalidDateFormats() throws Exception {
        // Given: Create subscription with potentially invalid date format
        SubscriptionTable subWithInvalidDate = new SubscriptionTable();
        subWithInvalidDate.setSubscriptionId(UUID.randomUUID().toString());
        subWithInvalidDate.setUserId(testUserId);
        subWithInvalidDate.setAccountId(testAccount.getAccountId());
        subWithInvalidDate.setMerchantName("Test Merchant");
        subWithInvalidDate.setAmount(new BigDecimal("-10.00"));
        subWithInvalidDate.setFrequency("MONTHLY");
        subWithInvalidDate.setStartDate("invalid-date-format"); // Invalid date
        subWithInvalidDate.setNextPaymentDate(LocalDate.now().plusMonths(1).toString());
        subWithInvalidDate.setCategory("subscriptions");
        subWithInvalidDate.setActive(true);
        subWithInvalidDate.setCreatedAt(java.time.Instant.now());
        subWithInvalidDate.setUpdatedAt(java.time.Instant.now());
        
        subscriptionRepository.save(subWithInvalidDate);

        // When: Get subscriptions via service
        List<Subscription> subscriptions = subscriptionService.getSubscriptions(testUserId);

        // Then: Should filter out subscriptions that fail conversion (null from toSubscription)
        assertNotNull(subscriptions, "Subscriptions list should not be null");
        
        // Subscription with invalid date should be filtered out (toSubscription returns null)
        // So we should have 0 subscriptions, not a null subscription
        // Verify no nulls in the list
        for (Subscription sub : subscriptions) {
            assertNotNull(sub, "Subscription object should not be null");
        }
        assertEquals(0, subscriptions.size(), 
            "Invalid subscriptions should be filtered out, not returned as null");
    }

    @Test
    @DisplayName("Should handle subscriptions with invalid frequency gracefully")
    void testGetSubscriptions_WithInvalidFrequency() throws Exception {
        // Given: Create subscription with invalid frequency
        SubscriptionTable subWithInvalidFreq = new SubscriptionTable();
        subWithInvalidFreq.setSubscriptionId(UUID.randomUUID().toString());
        subWithInvalidFreq.setUserId(testUserId);
        subWithInvalidFreq.setAccountId(testAccount.getAccountId());
        subWithInvalidFreq.setMerchantName("Test Merchant");
        subWithInvalidFreq.setAmount(new BigDecimal("-10.00"));
        subWithInvalidFreq.setFrequency("INVALID_FREQUENCY"); // Invalid frequency
        subWithInvalidFreq.setStartDate(LocalDate.now().minusMonths(1).toString());
        subWithInvalidFreq.setNextPaymentDate(LocalDate.now().plusMonths(1).toString());
        subWithInvalidFreq.setCategory("subscriptions");
        subWithInvalidFreq.setActive(true);
        subWithInvalidFreq.setCreatedAt(java.time.Instant.now());
        subWithInvalidFreq.setUpdatedAt(java.time.Instant.now());
        
        subscriptionRepository.save(subWithInvalidFreq);

        // When: Get subscriptions via service
        List<Subscription> subscriptions = subscriptionService.getSubscriptions(testUserId);

        // Then: Should filter out subscriptions that fail conversion
        assertNotNull(subscriptions, "Subscriptions list should not be null");
        // Verify no nulls in the list
        for (Subscription sub : subscriptions) {
            assertNotNull(sub, "Subscription object should not be null");
        }
        assertEquals(0, subscriptions.size(), 
            "Invalid subscriptions should be filtered out, not returned as null");
    }

    @Test
    @DisplayName("Should not return null when detectSubscriptions returns empty list")
    void testDetectSubscriptions_EmptyList_ShouldNotReturnNull() throws Exception {
        // Given: No transactions exist

        // When: Detect subscriptions
        var result = mockMvc.perform(withAuth(post("/api/subscriptions/detect"))
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        // Then: Should return empty array, not null
        String responseBody = result.getResponse().getContentAsString();
        assertNotNull(responseBody, "Response body should not be null");
        assertTrue(responseBody.equals("[]") || responseBody.equals("[]\n"), 
            "Should return empty array, not null");
        
        List<Subscription> subscriptions = objectMapper.readValue(
            responseBody, 
            objectMapper.getTypeFactory().constructCollectionType(List.class, Subscription.class)
        );
        
        assertNotNull(subscriptions, "Subscriptions list should not be null");
        assertTrue(subscriptions.isEmpty(), "Should be empty list, not null");
    }

    @Test
    @DisplayName("Should verify toSubscription method never returns null for valid data")
    void testToSubscription_ValidData_ShouldNotReturnNull() {
        // Given: Valid subscription table
        SubscriptionTable table = createValidSubscriptionTable("Netflix", new BigDecimal("-15.99"), 
            Subscription.SubscriptionFrequency.MONTHLY, LocalDate.now().minusMonths(1));
        subscriptionRepository.save(table);

        // When: Convert to Subscription
        List<Subscription> subscriptions = subscriptionService.getSubscriptions(testUserId);

        // Then: Should not be null
        assertNotNull(subscriptions, "Subscriptions list should not be null");
        assertEquals(1, subscriptions.size(), "Should have 1 subscription");
        assertNotNull(subscriptions.get(0), "Subscription should not be null");
    }

    @Test
    @DisplayName("Should verify API response structure for subscriptions")
    void testGetSubscriptions_ResponseStructure() throws Exception {
        // Given: Create valid subscription
        SubscriptionTable sub = createValidSubscriptionTable("Netflix", new BigDecimal("-15.99"), 
            Subscription.SubscriptionFrequency.MONTHLY, LocalDate.now().minusMonths(1));
        subscriptionRepository.save(sub);

        // When: Get subscriptions via API
        var result = mockMvc.perform(withAuth(get("/api/subscriptions"))
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0]").exists())
                .andExpect(jsonPath("$[0].subscriptionId").exists())
                .andExpect(jsonPath("$[0].merchantName").exists())
                .andExpect(jsonPath("$[0].amount").exists())
                .andExpect(jsonPath("$[0].frequency").exists())
                .andExpect(jsonPath("$[0].startDate").exists())
                .andReturn();

        // Then: Verify response doesn't contain null subscription objects
        String responseBody = result.getResponse().getContentAsString();
        
        // Parse and verify - this is the most reliable way to check for nulls
        List<Subscription> subscriptions = objectMapper.readValue(
            responseBody, 
            objectMapper.getTypeFactory().constructCollectionType(List.class, Subscription.class)
        );
        
        assertNotNull(subscriptions, "Subscriptions list should not be null");
        assertEquals(1, subscriptions.size(), "Should have 1 subscription");
        
        // CRITICAL: Verify subscription object is not null
        Subscription subscriptionObj = subscriptions.get(0);
        assertNotNull(subscriptionObj, "Subscription object should not be null");
        assertNotNull(subscriptionObj.getSubscriptionId(), "Subscription ID should not be null");
        assertNotNull(subscriptionObj.getMerchantName(), "Merchant name should not be null");
    }

    @Test
    @DisplayName("Should handle mixed valid and invalid subscriptions correctly")
    void testGetSubscriptions_MixedValidAndInvalid() throws Exception {
        // Given: Create mix of valid and invalid subscriptions
        SubscriptionTable valid1 = createValidSubscriptionTable("Netflix", new BigDecimal("-15.99"), 
            Subscription.SubscriptionFrequency.MONTHLY, LocalDate.now().minusMonths(1));
        subscriptionRepository.save(valid1);
        
        SubscriptionTable valid2 = createValidSubscriptionTable("Spotify", new BigDecimal("-9.99"), 
            Subscription.SubscriptionFrequency.MONTHLY, LocalDate.now().minusMonths(1));
        subscriptionRepository.save(valid2);
        
        // Invalid: missing required fields
        SubscriptionTable invalid1 = new SubscriptionTable();
        invalid1.setSubscriptionId(UUID.randomUUID().toString());
        invalid1.setUserId(testUserId);
        // Missing accountId, merchantName, amount, frequency, startDate
        invalid1.setActive(true);
        subscriptionRepository.save(invalid1);

        // When: Get subscriptions via API
        var result = mockMvc.perform(withAuth(get("/api/subscriptions"))
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        // Then: Should only return valid subscriptions, filter out invalid ones
        String responseBody = result.getResponse().getContentAsString();
        List<Subscription> subscriptions = objectMapper.readValue(
            responseBody, 
            objectMapper.getTypeFactory().constructCollectionType(List.class, Subscription.class)
        );
        
        assertNotNull(subscriptions, "Subscriptions list should not be null");
        // Should have at least 2 valid subscriptions (invalid one should be filtered out)
        assertTrue(subscriptions.size() >= 2, "Should have at least 2 valid subscriptions");
        
        // Verify no nulls
        for (Subscription sub : subscriptions) {
            assertNotNull(sub, "Subscription should not be null");
            assertNotNull(sub.getSubscriptionId(), "Subscription ID should not be null");
        }
    }

    @Test
    @DisplayName("Should verify service layer filters nulls before returning")
    void testServiceLayer_FiltersNulls() {
        // Given: Create subscription that might cause null in conversion
        SubscriptionTable sub = createValidSubscriptionTable("Test", new BigDecimal("-10.00"), 
            Subscription.SubscriptionFrequency.MONTHLY, LocalDate.now().minusMonths(1));
        subscriptionRepository.save(sub);

        // When: Get subscriptions via service
        List<Subscription> subscriptions = subscriptionService.getSubscriptions(testUserId);

        // Then: Service should filter out any nulls
        assertNotNull(subscriptions, "Service should not return null list");
        
        // Count non-null subscriptions
        long nonNullCount = subscriptions.stream()
            .filter(subscription -> subscription != null)
            .count();
        
        assertEquals(subscriptions.size(), nonNullCount, 
            "Service should filter out null subscriptions before returning");
    }

    // Helper method to create valid subscription table
    private SubscriptionTable createValidSubscriptionTable(String merchant, BigDecimal amount, 
                                                          Subscription.SubscriptionFrequency frequency, 
                                                          LocalDate startDate) {
        SubscriptionTable table = new SubscriptionTable();
        table.setSubscriptionId(UUID.randomUUID().toString());
        table.setUserId(testUserId);
        table.setAccountId(testAccount.getAccountId());
        table.setMerchantName(merchant);
        table.setDescription(merchant + " Subscription");
        table.setAmount(amount);
        table.setFrequency(frequency.name());
        table.setStartDate(startDate.toString());
        table.setNextPaymentDate(startDate.plusMonths(1).toString());
        table.setCategory("subscriptions");
        table.setSubscriptionType("streaming");
        table.setActive(true);
        table.setCreatedAt(java.time.Instant.now());
        table.setUpdatedAt(java.time.Instant.now());
        return table;
    }
}
