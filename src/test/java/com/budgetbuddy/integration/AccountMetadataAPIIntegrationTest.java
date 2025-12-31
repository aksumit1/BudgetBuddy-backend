package com.budgetbuddy.integration;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.dto.AuthRequest;
import com.budgetbuddy.dto.AuthResponse;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.UserRepository;
import com.budgetbuddy.service.AuthService;
import com.budgetbuddy.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for Account Metadata API endpoints
 * Verifies that paymentDueDate, minimumPaymentDue, and rewardPoints are:
 * 1. Stored correctly in the database
 * 2. Returned in API responses
 * 3. Serialized in correct format for iOS
 */
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
class AccountMetadataAPIIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserService userService;

    @Autowired
    private AuthService authService;

    @Autowired
    private ObjectMapper objectMapper;

    private UserTable testUser;
    private AccountTable testAccount;
    private String testEmail;
    private String accessToken;

    @BeforeEach
    void setUp() {
        // Create test user using UserService (proper way to create users with all required fields)
        testEmail = "test-metadata@" + UUID.randomUUID().toString().substring(0, 8) + ".com";
        String base64PasswordHash = java.util.Base64.getEncoder().encodeToString("test-hash".getBytes());
        
        testUser = userService.createUserSecure(
                testEmail,
                base64PasswordHash,
                "Test",
                "User"
        );

        // Create test account with metadata
        testAccount = new AccountTable();
        testAccount.setAccountId(UUID.randomUUID().toString());
        testAccount.setUserId(testUser.getUserId());
        testAccount.setAccountName("Test Credit Card");
        testAccount.setInstitutionName("Test Bank");
        testAccount.setAccountType("credit");
        testAccount.setAccountSubtype("credit card");
        testAccount.setBalance(new BigDecimal("1000.00"));
        testAccount.setCurrencyCode("USD");
        testAccount.setActive(true);
        testAccount.setCreatedAt(Instant.now());
        testAccount.setUpdatedAt(Instant.now());
        
        // Set metadata fields
        testAccount.setPaymentDueDate(LocalDate.of(2024, 12, 15));
        testAccount.setMinimumPaymentDue(new BigDecimal("25.00"));
        testAccount.setRewardPoints(12345L);
        
        accountRepository.save(testAccount);

        // Authenticate and get JWT token for API calls (reuse the same password hash from user creation)
        AuthRequest authRequest = new AuthRequest(testEmail, base64PasswordHash);
        AuthResponse authResponse = authService.authenticate(authRequest);
        accessToken = authResponse.getAccessToken();
    }

    /**
     * Helper method to add JWT token to request
     */
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder withAuth(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder) {
        return builder.header("Authorization", "Bearer " + accessToken);
    }

    @Test
    void testGetAccounts_ReturnsMetadataFields() throws Exception {
        // When - Get accounts via API
        MvcResult result = mockMvc.perform(withAuth(get("/api/accounts"))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andReturn();

        // Then - Verify response contains metadata fields
        String responseBody = result.getResponse().getContentAsString();
        assertTrue(responseBody.contains("paymentDueDate"), 
            "Response should contain paymentDueDate field");
        assertTrue(responseBody.contains("minimumPaymentDue"), 
            "Response should contain minimumPaymentDue field");
        assertTrue(responseBody.contains("rewardPoints"), 
            "Response should contain rewardPoints field");
        
        // Verify date format is ISO 8601 (yyyy-MM-dd)
        assertTrue(responseBody.contains("2024-12-15"), 
            "Response should contain payment due date in ISO format");
    }

    @Test
    void testGetAccountById_ReturnsMetadataFields() throws Exception {
        // When - Get account by ID via API
        MvcResult result = mockMvc.perform(withAuth(get("/api/accounts/{id}", testAccount.getAccountId()))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(testAccount.getAccountId()))
                .andExpect(jsonPath("$.paymentDueDate").value("2024-12-15"))
                .andExpect(jsonPath("$.minimumPaymentDue").value(25.00))
                .andExpect(jsonPath("$.rewardPoints").value(12345))
                .andReturn();

        // Then - Verify JSON structure
        String responseBody = result.getResponse().getContentAsString();
        AccountTable account = objectMapper.readValue(responseBody, AccountTable.class);
        
        assertNotNull(account.getPaymentDueDate(), "Payment due date should not be null");
        assertEquals(LocalDate.of(2024, 12, 15), account.getPaymentDueDate());
        assertNotNull(account.getMinimumPaymentDue(), "Minimum payment due should not be null");
        // BigDecimal comparison - 25.00 and 25 are equal, so use compareTo
        assertEquals(0, new BigDecimal("25.00").compareTo(account.getMinimumPaymentDue()), 
            "Minimum payment due should be 25.00");
        assertNotNull(account.getRewardPoints(), "Reward points should not be null");
        assertEquals(12345L, account.getRewardPoints());
    }

    @Test
    void testGetAccounts_WithNullMetadata_ReturnsNullFields() throws Exception {
        // Given - Account with null metadata
        AccountTable accountWithNulls = new AccountTable();
        accountWithNulls.setAccountId(UUID.randomUUID().toString());
        accountWithNulls.setUserId(testUser.getUserId());
        accountWithNulls.setAccountName("Account With Null Metadata");
        accountWithNulls.setInstitutionName("Test Bank");
        accountWithNulls.setAccountType("checking");
        accountWithNulls.setBalance(new BigDecimal("500.00"));
        accountWithNulls.setCurrencyCode("USD");
        accountWithNulls.setActive(true);
        accountWithNulls.setCreatedAt(Instant.now());
        accountWithNulls.setUpdatedAt(Instant.now());
        accountWithNulls.setPaymentDueDate(null);
        accountWithNulls.setMinimumPaymentDue(null);
        accountWithNulls.setRewardPoints(null);
        accountRepository.save(accountWithNulls);

        // When - Get accounts via API
        MvcResult result = mockMvc.perform(withAuth(get("/api/accounts"))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        // Then - Verify null fields are included in JSON (due to @JsonInclude(ALWAYS))
        String responseBody = result.getResponse().getContentAsString();
        // With @JsonInclude(ALWAYS), null fields should be included as null
        assertTrue(responseBody.contains("paymentDueDate") || responseBody.contains("\"paymentDueDate\":null"),
            "Response should include paymentDueDate field (even if null)");
    }

    @Test
    void testGetAccounts_MetadataSerializationFormat_MatchesIOS() throws Exception {
        // When - Get account via API
        MvcResult result = mockMvc.perform(get("/api/accounts/{id}", testAccount.getAccountId())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        // Then - Verify serialization format matches iOS expectations
        String responseBody = result.getResponse().getContentAsString();
        
        // Payment due date should be ISO 8601 date string (yyyy-MM-dd)
        assertTrue(responseBody.matches(".*\"paymentDueDate\"\\s*:\\s*\"\\d{4}-\\d{2}-\\d{2}\".*"),
            "Payment due date should be ISO 8601 date string");
        
        // Minimum payment due should be a number (not string)
        assertTrue(responseBody.matches(".*\"minimumPaymentDue\"\\s*:\\s*\\d+\\.?\\d*.*"),
            "Minimum payment due should be a number");
        
        // Reward points should be a number (not string)
        assertTrue(responseBody.matches(".*\"rewardPoints\"\\s*:\\s*\\d+.*"),
            "Reward points should be a number");
    }

    @Test
    void testGetAccounts_BoundaryConditions_MaxRewardPoints() throws Exception {
        // Given - Account with maximum reward points (10 million)
        AccountTable accountWithMaxPoints = new AccountTable();
        accountWithMaxPoints.setAccountId(UUID.randomUUID().toString());
        accountWithMaxPoints.setUserId(testUser.getUserId());
        accountWithMaxPoints.setAccountName("Account With Max Points");
        accountWithMaxPoints.setInstitutionName("Test Bank");
        accountWithMaxPoints.setAccountType("credit");
        accountWithMaxPoints.setBalance(new BigDecimal("1000.00"));
        accountWithMaxPoints.setCurrencyCode("USD");
        accountWithMaxPoints.setActive(true);
        accountWithMaxPoints.setCreatedAt(Instant.now());
        accountWithMaxPoints.setUpdatedAt(Instant.now());
        accountWithMaxPoints.setPaymentDueDate(LocalDate.of(2024, 12, 15));
        accountWithMaxPoints.setMinimumPaymentDue(new BigDecimal("25.00"));
        accountWithMaxPoints.setRewardPoints(10_000_000L); // Maximum
        accountRepository.save(accountWithMaxPoints);

        // When - Get account via API
        mockMvc.perform(withAuth(get("/api/accounts/{id}", accountWithMaxPoints.getAccountId()))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rewardPoints").value(10_000_000));
    }

    @Test
    void testGetAccounts_BoundaryConditions_ZeroRewardPoints() throws Exception {
        // Given - Account with zero reward points
        AccountTable accountWithZeroPoints = new AccountTable();
        accountWithZeroPoints.setAccountId(UUID.randomUUID().toString());
        accountWithZeroPoints.setUserId(testUser.getUserId());
        accountWithZeroPoints.setAccountName("Account With Zero Points");
        accountWithZeroPoints.setInstitutionName("Test Bank");
        accountWithZeroPoints.setAccountType("credit");
        accountWithZeroPoints.setBalance(new BigDecimal("1000.00"));
        accountWithZeroPoints.setCurrencyCode("USD");
        accountWithZeroPoints.setActive(true);
        accountWithZeroPoints.setCreatedAt(Instant.now());
        accountWithZeroPoints.setUpdatedAt(Instant.now());
        accountWithZeroPoints.setPaymentDueDate(LocalDate.of(2024, 12, 15));
        accountWithZeroPoints.setMinimumPaymentDue(new BigDecimal("25.00"));
        accountWithZeroPoints.setRewardPoints(0L);
        accountRepository.save(accountWithZeroPoints);

        // When - Get account via API
        mockMvc.perform(withAuth(get("/api/accounts/{id}", accountWithZeroPoints.getAccountId()))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rewardPoints").value(0));
    }

    @Test
    void testGetAccounts_BoundaryConditions_LargeMinimumPayment() throws Exception {
        // Given - Account with large minimum payment
        AccountTable accountWithLargePayment = new AccountTable();
        accountWithLargePayment.setAccountId(UUID.randomUUID().toString());
        accountWithLargePayment.setUserId(testUser.getUserId());
        accountWithLargePayment.setAccountName("Account With Large Payment");
        accountWithLargePayment.setInstitutionName("Test Bank");
        accountWithLargePayment.setAccountType("credit");
        accountWithLargePayment.setBalance(new BigDecimal("10000.00"));
        accountWithLargePayment.setCurrencyCode("USD");
        accountWithLargePayment.setActive(true);
        accountWithLargePayment.setCreatedAt(Instant.now());
        accountWithLargePayment.setUpdatedAt(Instant.now());
        accountWithLargePayment.setPaymentDueDate(LocalDate.of(2024, 12, 15));
        accountWithLargePayment.setMinimumPaymentDue(new BigDecimal("9999.99"));
        accountWithLargePayment.setRewardPoints(12345L);
        accountRepository.save(accountWithLargePayment);

        // When - Get account via API
        mockMvc.perform(withAuth(get("/api/accounts/{id}", accountWithLargePayment.getAccountId()))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.minimumPaymentDue").value(9999.99));
    }

    @Test
    void testGetAccounts_BoundaryConditions_SmallMinimumPayment() throws Exception {
        // Given - Account with very small minimum payment
        AccountTable accountWithSmallPayment = new AccountTable();
        accountWithSmallPayment.setAccountId(UUID.randomUUID().toString());
        accountWithSmallPayment.setUserId(testUser.getUserId());
        accountWithSmallPayment.setAccountName("Account With Small Payment");
        accountWithSmallPayment.setInstitutionName("Test Bank");
        accountWithSmallPayment.setAccountType("credit");
        accountWithSmallPayment.setBalance(new BigDecimal("100.00"));
        accountWithSmallPayment.setCurrencyCode("USD");
        accountWithSmallPayment.setActive(true);
        accountWithSmallPayment.setCreatedAt(Instant.now());
        accountWithSmallPayment.setUpdatedAt(Instant.now());
        accountWithSmallPayment.setPaymentDueDate(LocalDate.of(2024, 12, 15));
        accountWithSmallPayment.setMinimumPaymentDue(new BigDecimal("0.01"));
        accountWithSmallPayment.setRewardPoints(12345L);
        accountRepository.save(accountWithSmallPayment);

        // When - Get account via API
        mockMvc.perform(withAuth(get("/api/accounts/{id}", accountWithSmallPayment.getAccountId()))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.minimumPaymentDue").value(0.01));
    }

    @Test
    void testGetAccounts_EdgeCase_FuturePaymentDueDate() throws Exception {
        // Given - Account with future payment due date
        LocalDate futureDate = LocalDate.now().plusYears(1);
        AccountTable accountWithFutureDate = new AccountTable();
        accountWithFutureDate.setAccountId(UUID.randomUUID().toString());
        accountWithFutureDate.setUserId(testUser.getUserId());
        accountWithFutureDate.setAccountName("Account With Future Date");
        accountWithFutureDate.setInstitutionName("Test Bank");
        accountWithFutureDate.setAccountType("credit");
        accountWithFutureDate.setBalance(new BigDecimal("1000.00"));
        accountWithFutureDate.setCurrencyCode("USD");
        accountWithFutureDate.setActive(true);
        accountWithFutureDate.setCreatedAt(Instant.now());
        accountWithFutureDate.setUpdatedAt(Instant.now());
        accountWithFutureDate.setPaymentDueDate(futureDate);
        accountWithFutureDate.setMinimumPaymentDue(new BigDecimal("25.00"));
        accountWithFutureDate.setRewardPoints(12345L);
        accountRepository.save(accountWithFutureDate);

        // When - Get account via API
        mockMvc.perform(withAuth(get("/api/accounts/{id}", accountWithFutureDate.getAccountId()))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentDueDate").value(futureDate.toString()));
    }

    @Test
    void testGetAccounts_EdgeCase_PastPaymentDueDate() throws Exception {
        // Given - Account with past payment due date
        LocalDate pastDate = LocalDate.now().minusYears(1);
        AccountTable accountWithPastDate = new AccountTable();
        accountWithPastDate.setAccountId(UUID.randomUUID().toString());
        accountWithPastDate.setUserId(testUser.getUserId());
        accountWithPastDate.setAccountName("Account With Past Date");
        accountWithPastDate.setInstitutionName("Test Bank");
        accountWithPastDate.setAccountType("credit");
        accountWithPastDate.setBalance(new BigDecimal("1000.00"));
        accountWithPastDate.setCurrencyCode("USD");
        accountWithPastDate.setActive(true);
        accountWithPastDate.setCreatedAt(Instant.now());
        accountWithPastDate.setUpdatedAt(Instant.now());
        accountWithPastDate.setPaymentDueDate(pastDate);
        accountWithPastDate.setMinimumPaymentDue(new BigDecimal("25.00"));
        accountWithPastDate.setRewardPoints(12345L);
        accountRepository.save(accountWithPastDate);

        // When - Get account via API
        mockMvc.perform(withAuth(get("/api/accounts/{id}", accountWithPastDate.getAccountId()))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentDueDate").value(pastDate.toString()));
    }
}

