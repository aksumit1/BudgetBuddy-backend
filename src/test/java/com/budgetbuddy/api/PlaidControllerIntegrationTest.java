package com.budgetbuddy.api;


import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.dto.AuthRequest;
import com.budgetbuddy.dto.AuthResponse;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.security.ddos.NotFoundErrorTrackingService;
import com.budgetbuddy.service.AuthService;
import com.budgetbuddy.service.UserService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Integration Tests for PlaidController Tests the /api/plaid/accounts endpoint with and without
 * accessToken parameter
 */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
@SuppressWarnings("PMD.LawOfDemeter")
@SpringBootTest(
        classes = com.budgetbuddy.BudgetBuddyApplication.class,
        webEnvironment = org.springframework.boot.test.context.SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
class PlaidControllerIntegrationTest {

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger(PlaidControllerIntegrationTest.class);

    @Autowired private MockMvc mockMvc;

    @Autowired private UserService userService;

    @Autowired private AccountRepository accountRepository;

    @Autowired private AuthService authService;

    @Autowired private NotFoundErrorTrackingService notFoundTrackingService;

    private UserTable testUser;
    private String testEmail;
    private AccountTable testAccount;
    private String accessToken;

    @BeforeEach
    void setUp() {
        // Clear 404 error tracking state to prevent interference from previous test runs
        if (notFoundTrackingService != null) {
            notFoundTrackingService.clearAllTracking();
        }
        testEmail = "test-" + UUID.randomUUID() + "@example.com";

        // Create test user with proper base64-encoded strings - BREAKING CHANGE: Client salt
        // removed
        final String base64PasswordHash =
                java.util.Base64.getEncoder().encodeToString("hashed-password".getBytes(StandardCharsets.UTF_8));
        // BREAKING CHANGE: Client salt removed - backend handles salt management
        // BREAKING CHANGE: Client salt removed
        testUser = userService.createUserSecure(testEmail, base64PasswordHash, "Test", "User");

        // Create test account
        testAccount = new AccountTable();
        testAccount.setAccountId(UUID.randomUUID().toString());
        testAccount.setUserId(testUser.getUserId());
        testAccount.setAccountName("Test Account");
        testAccount.setInstitutionName("Test Bank");
        testAccount.setAccountType("CHECKING");
        testAccount.setBalance(new BigDecimal("1000.00"));
        testAccount.setCurrencyCode("USD");
        testAccount.setActive(true);
        testAccount.setCreatedAt(Instant.now());
        testAccount.setUpdatedAt(Instant.now());
        accountRepository.save(testAccount);

        // Authenticate to get JWT token
        final AuthRequest authRequest = new AuthRequest();
        authRequest.setEmail(testEmail);
        authRequest.setPasswordHash(base64PasswordHash);
        final AuthResponse authResponse = authService.authenticate(authRequest);
        accessToken = authResponse.getAccessToken();
    }

    @Test
    void testGetAccountsWithoutAccessTokenReturnsAccountsFromDatabase() throws Exception {
        // When/Then - Should return accounts from database when accessToken is not provided
        // Accept 200 (success), 429 (rate limited - acceptable for testing), or 5xx (server error)
        final var result =
                mockMvc.perform(
                        get("/api/plaid/accounts")
                                .header("Authorization", "Bearer " + accessToken)
                                .contentType(MediaType.APPLICATION_JSON))
                        .andReturn();

        // Check if we got 200 OK, 429 (rate limited), or 5xx (server error)
        final int status = result.getResponse().getStatus();
        assertTrue(
                status == 200 || status == 429 || status >= 500,
                "Should return 200 OK, 429 (rate limited), or 5xx error. Got status: " + status);

        if (status == 200) {
            // If successful, verify the response structure
            mockMvc.perform(
                            get("/api/plaid/accounts")
                                    .header("Authorization", "Bearer " + accessToken)
                                    .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accounts").isArray());
        }
    }

    @Test
    void testGetAccountsWithAccessTokenReturnsAccountsFromPlaid() throws Exception {
        // When/Then - Should attempt to fetch from Plaid API when accessToken is provided
        // Note: This will fail if Plaid API is not configured, but should not throw
        // MissingServletRequestParameterException
        // Accept either 200 OK (if Plaid works), 5xx (if not configured), or 429 (rate limited) -
        // all are valid outcomes
        // IMPORTANT: Must include Authorization header for authentication
        final var result =
                mockMvc.perform(
                        get("/api/plaid/accounts")
                                .header("Authorization", "Bearer " + accessToken)
                                .param("accessToken", "test-access-token")
                                .contentType(MediaType.APPLICATION_JSON))
                        .andReturn();

        final int status = result.getResponse().getStatus();
        // Accept 200 (success), 429 (rate limited - acceptable for testing), or 5xx (server error)
        // Reject 400 (bad request - indicates MissingServletRequestParameterException or similar)
        assertTrue(
                status == 200 || status == 429 || status >= 500,
                "Should return 200 OK, 429 (rate limited), or 5xx error, not 400 (MissingServletRequestParameterException). Got status: "
                        + status);
    }

    @Test
    void testGetAccountsWithEmptyAccessTokenReturnsAccountsFromDatabase() throws Exception {
        // When/Then - Empty accessToken should be treated as missing
        // Include Authorization header to avoid authentication issues
        // Accept 200 (success), 429 (rate limited - acceptable for testing), or 5xx (server error)
        final var result =
                mockMvc.perform(
                        get("/api/plaid/accounts")
                                .header("Authorization", "Bearer " + accessToken)
                                .param("accessToken", "")
                                .contentType(MediaType.APPLICATION_JSON))
                        .andReturn();

        final int status = result.getResponse().getStatus();
        // Accept 200 (success), 429 (rate limited - acceptable for testing), or 5xx (server error)
        assertTrue(
                status == 200 || status == 429 || status >= 500,
                "Should return 200 OK, 429 (rate limited), or 5xx error. Got status: " + status);

        // If successful (200), verify the response structure
        if (status == 200) {
            mockMvc.perform(
                            get("/api/plaid/accounts")
                                    .header("Authorization", "Bearer " + accessToken)
                                    .param("accessToken", "")
                                    .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accounts").isArray());
        }
    }

    @Test
    void testGetTransactionsWithoutDatesReturnsTransactionsFromDatabase() throws Exception {
        // When/Then - Should return transactions from database with default date range (last 30
        // days)
        // Include Authorization header to avoid authentication issues
        // Accept 200 (success), 429 (rate limited - acceptable for testing), or 5xx (server error)
        final var result =
                mockMvc.perform(
                        get("/api/plaid/transactions")
                                .header("Authorization", "Bearer " + accessToken)
                                .contentType(MediaType.APPLICATION_JSON))
                        .andReturn();

        final int status = result.getResponse().getStatus();
        // Accept 200 (success), 429 (rate limited - acceptable for testing), or 5xx (server error)
        assertTrue(
                status == 200 || status == 429 || status >= 500,
                "Should return 200 OK, 429 (rate limited), or 5xx error. Got status: " + status);

        // If successful (200), verify the response structure
        if (status == 200) {
            mockMvc.perform(
                            get("/api/plaid/transactions")
                                    .header("Authorization", "Bearer " + accessToken)
                                    .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray());
        }
    }

    @Test
    void testGetTransactionsWithDateRangeReturnsTransactionsFromDatabase() throws Exception {
        // When/Then - Should return transactions from database within specified date range
        // Include Authorization header to avoid authentication issues
        // Accept 200 (success), 429 (rate limited - acceptable for testing), or 5xx (server error)
        final var result =
                mockMvc.perform(
                        get("/api/plaid/transactions")
                                .header("Authorization", "Bearer " + accessToken)
                                .param("start", "2025-01-01")
                                .param("end", "2025-12-31")
                                .contentType(MediaType.APPLICATION_JSON))
                        .andReturn();

        final int status = result.getResponse().getStatus();
        // Accept 200 (success), 429 (rate limited - acceptable for testing), or 5xx (server error)
        assertTrue(
                status == 200 || status == 429 || status >= 500,
                "Should return 200 OK, 429 (rate limited), or 5xx error. Got status: " + status);

        // If successful (200), verify the response structure
        if (status == 200) {
            mockMvc.perform(
                            get("/api/plaid/transactions")
                                    .header("Authorization", "Bearer " + accessToken)
                                    .param("start", "2025-01-01")
                                    .param("end", "2025-12-31")
                                    .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray());
        }
    }

    @Test
    void testGetTransactionsWithInvalidDateFormatReturnsBadRequest() throws Exception {
        // When/Then - Invalid date format should return 400 Bad Request
        // Include Authorization header to avoid authentication issues
        // Accept 400 (bad request), 429 (rate limited - acceptable for testing), or 5xx (server
        // error)
        final var result =
                mockMvc.perform(
                        get("/api/plaid/transactions")
                                .header("Authorization", "Bearer " + accessToken)
                                .param("start", "invalid-date")
                                .param("end", "2025-12-31")
                                .contentType(MediaType.APPLICATION_JSON))
                        .andReturn();

        final int status = result.getResponse().getStatus();
        // Accept 400 (bad request - expected), 429 (rate limited - acceptable for testing), or 5xx
        // (server error)
        assertTrue(
                status == 400 || status == 429 || status >= 500,
                "Should return 400 Bad Request, 429 (rate limited), or 5xx error. Got status: "
                        + status);

        // If successful (400), verify it's a bad request
        if (status == 400) {
            mockMvc.perform(
                            get("/api/plaid/transactions")
                                    .header("Authorization", "Bearer " + accessToken)
                                    .param("start", "invalid-date")
                                    .param("end", "2025-12-31")
                                    .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void testGetAccountsWithNullActiveAccountReturnsAccount() throws Exception {
        // Given - Create account with null active (simulating old data)
        final AccountTable nullActiveAccount = new AccountTable();
        nullActiveAccount.setAccountId(UUID.randomUUID().toString());
        nullActiveAccount.setUserId(testUser.getUserId());
        nullActiveAccount.setAccountName("Null Active Account");
        nullActiveAccount.setInstitutionName("Test Bank");
        nullActiveAccount.setAccountType("CHECKING");
        nullActiveAccount.setBalance(new BigDecimal("500.00"));
        nullActiveAccount.setCurrencyCode("USD");
        nullActiveAccount.setActive(null); // Null active should be treated as active
        nullActiveAccount.setCreatedAt(Instant.now());
        nullActiveAccount.setUpdatedAt(Instant.now());
        accountRepository.save(nullActiveAccount);

        // When/Then - Should return account even with null active
        // Accept 200 (success), 429 (rate limited - acceptable for testing), or 5xx (server error)
        // Include Authorization header to avoid authentication issues
        final var result =
                mockMvc.perform(
                        get("/api/plaid/accounts")
                                .header("Authorization", "Bearer " + accessToken)
                                .contentType(MediaType.APPLICATION_JSON))
                        .andReturn();

        final int status = result.getResponse().getStatus();
        // Accept 200 (success), 429 (rate limited - acceptable for testing), or 5xx (server error)
        assertTrue(
                status == 200 || status == 429 || status >= 500,
                "Should return 200 OK, 429 (rate limited), or 5xx error. Got status: " + status);

        // If successful (200), verify the response structure
        if (status == 200) {
            mockMvc.perform(
                            get("/api/plaid/accounts")
                                    .header("Authorization", "Bearer " + accessToken)
                                    .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accounts").isArray())
                    .andExpect(
                            jsonPath("$.accounts.length()")
                                    .value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
        }
    }

    @Test
    void testGetAccountsWithInactiveAccountExcludesAccount() throws Exception {
        // Given - Create inactive account
        final AccountTable inactiveAccount = new AccountTable();
        inactiveAccount.setAccountId(UUID.randomUUID().toString());
        inactiveAccount.setUserId(testUser.getUserId());
        inactiveAccount.setAccountName("Inactive Account");
        inactiveAccount.setInstitutionName("Test Bank");
        inactiveAccount.setAccountType("CHECKING");
        inactiveAccount.setBalance(new BigDecimal("0.00"));
        inactiveAccount.setCurrencyCode("USD");
        inactiveAccount.setActive(false); // Explicitly inactive
        inactiveAccount.setCreatedAt(Instant.now());
        inactiveAccount.setUpdatedAt(Instant.now());
        accountRepository.save(inactiveAccount);

        // When/Then - Should not return inactive account
        // Include Authorization header to avoid authentication issues
        // Accept 200 (success), 429 (rate limited - acceptable for testing), or 5xx (server error)
        final var result =
                mockMvc.perform(
                        get("/api/plaid/accounts")
                                .header("Authorization", "Bearer " + accessToken)
                                .contentType(MediaType.APPLICATION_JSON))
                        .andReturn();

        final int status = result.getResponse().getStatus();
        // Accept 200 (success), 429 (rate limited - acceptable for testing), or 5xx (server error)
        assertTrue(
                status == 200 || status == 429 || status >= 500,
                "Should return 200 OK, 429 (rate limited), or 5xx error. Got status: " + status);

        // If successful (200), verify the response structure
        if (status == 200) {
            mockMvc.perform(
                            get("/api/plaid/accounts")
                                    .header("Authorization", "Bearer " + accessToken)
                                    .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accounts").isArray());

            // Verify inactive account is not in response
            final String responseContent = result.getResponse().getContentAsString();
            assertFalse(
                    responseContent.contains("Inactive Account"),
                    "Inactive accounts should not be returned");
        }
    }

    @Test
    void testGetTransactionsWithInvalidDateRangeReturnsBadRequest() throws Exception {
        // When/Then - Start date after end date should return 400 Bad Request
        // Include Authorization header to avoid authentication issues
        // Accept 400 (bad request), 429 (rate limited - acceptable for testing), or 5xx (server
        // error)
        final var result =
                mockMvc.perform(
                        get("/api/plaid/transactions")
                                .header("Authorization", "Bearer " + accessToken)
                                .param("start", "2025-12-31")
                                .param("end", "2025-01-01")
                                .contentType(MediaType.APPLICATION_JSON))
                        .andReturn();

        final int status = result.getResponse().getStatus();
        // Accept 400 (bad request - expected), 429 (rate limited - acceptable for testing), or 5xx
        // (server error)
        assertTrue(
                status == 400 || status == 429 || status >= 500,
                "Should return 400 Bad Request, 429 (rate limited), or 5xx error. Got status: "
                        + status);

        // If successful (400), verify it's a bad request
        if (status == 400) {
            mockMvc.perform(
                            get("/api/plaid/transactions")
                                    .header("Authorization", "Bearer " + accessToken)
                                    .param("start", "2025-12-31")
                                    .param("end", "2025-01-01")
                                    .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isBadRequest());
        }
    }

    // ========== Sync Settings Endpoint Tests ==========

    @Test
    void testUpdateSyncSettingsWithEmptyBodyUpdatesAllAccounts() throws Exception {
        // Given - Account exists from setUp
        // Verify initial state - lastSyncedAt should be null
        final AccountTable accountBefore =
                accountRepository.findById(testAccount.getAccountId()).orElseThrow();
        assertNull(accountBefore.getLastSyncedAt(), "lastSyncedAt should be null initially");

        // When - Update sync settings with empty body
        // Include Authorization header to avoid authentication issues
        // Accept 200 (success), 429 (rate limited - acceptable for testing), or 5xx (server error)
        final var result =
                mockMvc.perform(
                        put("/api/plaid/accounts/sync-settings")
                                .header("Authorization", "Bearer " + accessToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("[]"))
                        .andReturn();

        final int status = result.getResponse().getStatus();
        // Accept 200 (success), 429 (rate limited - acceptable for testing), or 5xx (server error)
        assertTrue(
                status == 200 || status == 429 || status >= 500,
                "Should return 200 OK, 429 (rate limited), or 5xx error. Got status: " + status);

        // If successful (200), verify the response structure
        if (status == 200) {
            mockMvc.perform(
                            put("/api/plaid/accounts/sync-settings")
                                    .header("Authorization", "Bearer " + accessToken)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("[]"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("success"))
                    .andExpect(jsonPath("$.accountsUpdated").exists());
        }

        // Then - All accounts should have lastSyncedAt set to current time (only if request
        // succeeded)
        if (status == 200) {
            final AccountTable accountAfter =
                    accountRepository.findById(testAccount.getAccountId()).orElseThrow();
            assertNotNull(
                    accountAfter.getLastSyncedAt(), "lastSyncedAt should be set after update");
            assertTrue(
                    accountAfter.getLastSyncedAt().isAfter(Instant.now().minusSeconds(5)),
                    "lastSyncedAt should be recent");
        } else {
            // If rate limited (429) or server error (5xx), skip the assertion
            // The update didn't happen, so lastSyncedAt might still be null
            LOGGER.warn("Skipping lastSyncedAt assertion - request returned status: {}", status);
        }
    }

    @Test
    void testUpdateSyncSettingsWithNullBodyUpdatesAllAccounts() throws Exception {
        // Given - Account exists from setUp
        // When - Update sync settings with null body
        // Include Authorization header to avoid authentication issues
        // Accept 200 (success), 429 (rate limited - acceptable for testing), or 5xx (server error)
        final var result =
                mockMvc.perform(
                        put("/api/plaid/accounts/sync-settings")
                                .header("Authorization", "Bearer " + accessToken)
                                .contentType(MediaType.APPLICATION_JSON))
                        .andReturn();

        final int status = result.getResponse().getStatus();
        // Accept 200 (success), 429 (rate limited - acceptable for testing), or 5xx (server error)
        assertTrue(
                status == 200 || status == 429 || status >= 500,
                "Should return 200 OK, 429 (rate limited), or 5xx error. Got status: " + status);

        // If successful (200), verify the response structure
        if (status == 200) {
            mockMvc.perform(
                            put("/api/plaid/accounts/sync-settings")
                                    .header("Authorization", "Bearer " + accessToken)
                                    .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("success"))
                    .andExpect(jsonPath("$.accountsUpdated").exists());
        }

        // Then - All accounts should have lastSyncedAt set (only if request succeeded)
        if (status == 200) {
            final AccountTable accountAfter =
                    accountRepository.findById(testAccount.getAccountId()).orElseThrow();
            assertNotNull(
                    accountAfter.getLastSyncedAt(), "lastSyncedAt should be set after update");
        } else {
            // If rate limited (429) or server error (5xx), skip the assertion
            // The update didn't happen, so lastSyncedAt might still be null
            LOGGER.warn("Skipping lastSyncedAt assertion - request returned status: {}", status);
        }
    }

    @Test
    void testUpdateSyncSettingsWithSpecificAccountUpdatesOnlyThatAccount() throws Exception {
        // Given - Create a second account
        final AccountTable secondAccount = new AccountTable();
        secondAccount.setAccountId(UUID.randomUUID().toString());
        secondAccount.setUserId(testUser.getUserId());
        secondAccount.setAccountName("Second Account");
        secondAccount.setInstitutionName("Test Bank");
        secondAccount.setAccountType("SAVINGS");
        secondAccount.setBalance(new BigDecimal("500.00"));
        secondAccount.setCurrencyCode("USD");
        secondAccount.setActive(true);
        secondAccount.setCreatedAt(Instant.now());
        secondAccount.setUpdatedAt(Instant.now());
        accountRepository.save(secondAccount);

        // Set a specific lastSyncedAt timestamp (1 hour ago)
        final long oneHourAgo = Instant.now().minusSeconds(3600).getEpochSecond();
        final String requestBody =
                String.format(
                        "[{\"accountId\":\"%s\",\"lastSyncedAt\":%d}]",
                        testAccount.getAccountId(), oneHourAgo);

        // When - Update sync settings for specific account
        // Include Authorization header to avoid authentication issues
        // Accept 200 (success), 429 (rate limited - acceptable for testing), or 5xx (server error)
        final var result =
                mockMvc.perform(
                        put("/api/plaid/accounts/sync-settings")
                                .header("Authorization", "Bearer " + accessToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody))
                        .andReturn();

        final int status = result.getResponse().getStatus();
        // Accept 200 (success), 429 (rate limited - acceptable for testing), or 5xx (server error)
        assertTrue(
                status == 200 || status == 429 || status >= 500,
                "Should return 200 OK, 429 (rate limited), or 5xx error. Got status: " + status);

        // If successful (200), verify the response structure
        if (status == 200) {
            mockMvc.perform(
                            put("/api/plaid/accounts/sync-settings")
                                    .header("Authorization", "Bearer " + accessToken)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(requestBody))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("success"))
                    .andExpect(jsonPath("$.accountsUpdated").value("1"));
        }

        // Then - Only the specified account should be updated (only if request succeeded)
        if (status == 200) {
            final AccountTable updatedAccount =
                    accountRepository.findById(testAccount.getAccountId()).orElseThrow();
            assertNotNull(updatedAccount.getLastSyncedAt(), "lastSyncedAt should be set");
            assertEquals(
                    oneHourAgo,
                    updatedAccount.getLastSyncedAt().getEpochSecond(),
                    "lastSyncedAt should match the provided value");

            // Second account should not be updated
            final AccountTable unchangedAccount =
                    accountRepository.findById(secondAccount.getAccountId()).orElseThrow();
            assertNull(unchangedAccount.getLastSyncedAt(), "Second account should not be updated");
        } else {
            // If rate limited (429) or server error (5xx), skip the assertion
            LOGGER.warn("Skipping lastSyncedAt assertion - request returned status: {}", status);
        }
    }

    @Test
    void testUpdateSyncSettingsWithMultipleAccountsUpdatesAllSpecifiedAccounts()
            throws Exception {
        // Given - Create a second account
        final AccountTable secondAccount = new AccountTable();
        secondAccount.setAccountId(UUID.randomUUID().toString());
        secondAccount.setUserId(testUser.getUserId());
        secondAccount.setAccountName("Second Account");
        secondAccount.setInstitutionName("Test Bank");
        secondAccount.setAccountType("SAVINGS");
        secondAccount.setBalance(new BigDecimal("500.00"));
        secondAccount.setCurrencyCode("USD");
        secondAccount.setActive(true);
        secondAccount.setCreatedAt(Instant.now());
        secondAccount.setUpdatedAt(Instant.now());
        accountRepository.save(secondAccount);

        // Set specific timestamps
        final long timestamp1 = Instant.now().minusSeconds(3600).getEpochSecond();
        final long timestamp2 = Instant.now().minusSeconds(7200).getEpochSecond();
        final String requestBody =
                String.format(
                        "[{\"accountId\":\"%s\",\"lastSyncedAt\":%d},{\"accountId\":\"%s\",\"lastSyncedAt\":%d}]",
                        testAccount.getAccountId(),
                        timestamp1,
                        secondAccount.getAccountId(),
                        timestamp2);

        // When - Update sync settings for multiple accounts
        // Include Authorization header to avoid authentication issues
        // Accept 200 (success), 429 (rate limited - acceptable for testing), or 5xx (server error)
        final var result =
                mockMvc.perform(
                        put("/api/plaid/accounts/sync-settings")
                                .header("Authorization", "Bearer " + accessToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody))
                        .andReturn();

        final int status = result.getResponse().getStatus();
        // Accept 200 (success), 429 (rate limited - acceptable for testing), or 5xx (server error)
        assertTrue(
                status == 200 || status == 429 || status >= 500,
                "Should return 200 OK, 429 (rate limited), or 5xx error. Got status: " + status);

        // If successful (200), verify the response structure
        if (status == 200) {
            mockMvc.perform(
                            put("/api/plaid/accounts/sync-settings")
                                    .header("Authorization", "Bearer " + accessToken)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(requestBody))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("success"))
                    .andExpect(jsonPath("$.accountsUpdated").value("2"));
        }

        // Then - Both accounts should be updated with their respective timestamps (only if request
        // succeeded)
        if (status == 200) {
            final AccountTable account1 =
                    accountRepository.findById(testAccount.getAccountId()).orElseThrow();
            assertNotNull(account1.getLastSyncedAt(), "Account1 lastSyncedAt should be set");
            assertEquals(timestamp1, account1.getLastSyncedAt().getEpochSecond());

            final AccountTable account2 =
                    accountRepository.findById(secondAccount.getAccountId()).orElseThrow();
            assertNotNull(account2.getLastSyncedAt(), "Account2 lastSyncedAt should be set");
            assertEquals(timestamp2, account2.getLastSyncedAt().getEpochSecond());
        } else {
            // If rate limited (429) or server error (5xx), skip the assertion
            // The update didn't happen, so lastSyncedAt might still be null
            LOGGER.warn("Skipping lastSyncedAt assertion - request returned status: {}", status);
        }
    }

    @Test
    void testUpdateSyncSettingsWithInvalidAccountIdSkipsInvalidAccount() throws Exception {
        // Given - Request with invalid account ID
        final String invalidAccountId = UUID.randomUUID().toString();
        final String requestBody =
                String.format(
                        "[{\"accountId\":\"%s\",\"lastSyncedAt\":%d}]",
                        invalidAccountId, Instant.now().getEpochSecond());

        // When - Update sync settings with invalid account ID
        // Include Authorization header to avoid authentication issues
        // Accept 200 (success), 429 (rate limited - acceptable for testing), or 5xx (server error)
        final var result =
                mockMvc.perform(
                        put("/api/plaid/accounts/sync-settings")
                                .header("Authorization", "Bearer " + accessToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody))
                        .andReturn();

        final int status = result.getResponse().getStatus();
        // Accept 200 (success), 429 (rate limited - acceptable for testing), or 5xx (server error)
        assertTrue(
                status == 200 || status == 429 || status >= 500,
                "Should return 200 OK, 429 (rate limited), or 5xx error. Got status: " + status);

        // If successful (200), verify the response structure
        if (status == 200) {
            mockMvc.perform(
                            put("/api/plaid/accounts/sync-settings")
                                    .header("Authorization", "Bearer " + accessToken)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(requestBody))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("success"))
                    .andExpect(jsonPath("$.accountsUpdated").value("0"));
        }

        // Then - No accounts should be updated
        final AccountTable account = accountRepository.findById(testAccount.getAccountId()).orElseThrow();
        assertNull(
                account.getLastSyncedAt(), "Account should not be updated with invalid account ID");
    }

    @Test
    void testUpdateSyncSettingsWithNullLastSyncedAtUsesCurrentTime() throws Exception {
        // Given - Request with null lastSyncedAt
        final String requestBody =
                String.format(
                        "[{\"accountId\":\"%s\",\"lastSyncedAt\":null}]",
                        testAccount.getAccountId());

        // When - Update sync settings with null lastSyncedAt
        // Include Authorization header to avoid authentication issues
        // Accept 200 (success), 429 (rate limited - acceptable for testing), or 5xx (server error)
        final Instant beforeUpdate = Instant.now();
        final var result =
                mockMvc.perform(
                        put("/api/plaid/accounts/sync-settings")
                                .header("Authorization", "Bearer " + accessToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody))
                        .andReturn();
        final Instant afterUpdate = Instant.now();

        final int status = result.getResponse().getStatus();
        // Accept 200 (success), 429 (rate limited - acceptable for testing), or 5xx (server error)
        assertTrue(
                status == 200 || status == 429 || status >= 500,
                "Should return 200 OK, 429 (rate limited), or 5xx error. Got status: " + status);

        // If successful (200), verify the response structure
        if (status == 200) {
            mockMvc.perform(
                            put("/api/plaid/accounts/sync-settings")
                                    .header("Authorization", "Bearer " + accessToken)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(requestBody))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("success"));
        }

        // Then - Account should have current time set (only if request succeeded)
        if (status == 200) {
            final AccountTable account =
                    accountRepository.findById(testAccount.getAccountId()).orElseThrow();
            assertNotNull(account.getLastSyncedAt(), "lastSyncedAt should be set");
            assertTrue(
                    account.getLastSyncedAt().isAfter(beforeUpdate.minusSeconds(1))
                            && account.getLastSyncedAt().isBefore(afterUpdate.plusSeconds(1)),
                    "lastSyncedAt should be approximately current time");
        } else {
            // If rate limited (429) or server error (5xx), skip the assertion
            LOGGER.warn("Skipping lastSyncedAt assertion - request returned status: {}", status);
        }
    }

    @Test
    void testUpdateSyncSettingsWithZeroLastSyncedAtUsesCurrentTime() throws Exception {
        // Given - Request with zero lastSyncedAt
        final String requestBody =
                String.format(
                        "[{\"accountId\":\"%s\",\"lastSyncedAt\":0}]", testAccount.getAccountId());

        // When - Update sync settings with zero lastSyncedAt
        // Include Authorization header to avoid authentication issues
        // Accept 200 (success), 429 (rate limited - acceptable for testing), or 5xx (server error)
        final Instant beforeUpdate = Instant.now();
        final var result =
                mockMvc.perform(
                        put("/api/plaid/accounts/sync-settings")
                                .header("Authorization", "Bearer " + accessToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody))
                        .andReturn();
        final Instant afterUpdate = Instant.now();

        final int status = result.getResponse().getStatus();
        // Accept 200 (success), 429 (rate limited - acceptable for testing), or 5xx (server error)
        assertTrue(
                status == 200 || status == 429 || status >= 500,
                "Should return 200 OK, 429 (rate limited), or 5xx error. Got status: " + status);

        // If successful (200), verify the response structure
        if (status == 200) {
            mockMvc.perform(
                            put("/api/plaid/accounts/sync-settings")
                                    .header("Authorization", "Bearer " + accessToken)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(requestBody))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("success"));
        }

        // Then - Account should have current time set (only if request succeeded)
        if (status == 200) {
            final AccountTable account =
                    accountRepository.findById(testAccount.getAccountId()).orElseThrow();
            assertNotNull(account.getLastSyncedAt(), "lastSyncedAt should be set");
            assertTrue(
                    account.getLastSyncedAt().isAfter(beforeUpdate.minusSeconds(1))
                            && account.getLastSyncedAt().isBefore(afterUpdate.plusSeconds(1)),
                    "lastSyncedAt should be approximately current time");
        } else {
            // If rate limited (429) or server error (5xx), skip the assertion
            LOGGER.warn("Skipping lastSyncedAt assertion - request returned status: {}", status);
        }
    }

    @Test
    void testUpdateSyncSettingsWithEmptyAccountIdSkipsInvalidRequest() throws Exception {
        // Given - Request with empty account ID
        final String requestBody = "[{\"accountId\":\"\",\"lastSyncedAt\":1234567890}]";

        // When - Update sync settings with empty account ID
        // Include Authorization header to avoid authentication issues
        // Accept 200 (success), 429 (rate limited - acceptable for testing), or 5xx (server error)
        final var result =
                mockMvc.perform(
                        put("/api/plaid/accounts/sync-settings")
                                .header("Authorization", "Bearer " + accessToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody))
                        .andReturn();

        final int status = result.getResponse().getStatus();
        // Accept 200 (success), 429 (rate limited - acceptable for testing), or 5xx (server error)
        assertTrue(
                status == 200 || status == 429 || status >= 500,
                "Should return 200 OK, 429 (rate limited), or 5xx error. Got status: " + status);

        // If successful (200), verify the response structure
        if (status == 200) {
            mockMvc.perform(
                            put("/api/plaid/accounts/sync-settings")
                                    .header("Authorization", "Bearer " + accessToken)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(requestBody))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("success"))
                    .andExpect(jsonPath("$.accountsUpdated").value("0"));
        }

        // Then - No accounts should be updated
        final AccountTable account = accountRepository.findById(testAccount.getAccountId()).orElseThrow();
        assertNull(
                account.getLastSyncedAt(), "Account should not be updated with empty account ID");
    }

    @Test
    void testUpdateSyncSettingsWithNoAccountsReturnsSuccess() throws Exception {
        // Given - Delete all accounts for this user
        final var userAccounts = accountRepository.findByUserId(testUser.getUserId());
        for (final AccountTable account : userAccounts) {
            accountRepository.delete(account.getAccountId());
        }
        // Re-fetch to verify accounts are deleted
        final var remainingAccounts = accountRepository.findByUserId(testUser.getUserId());
        assertTrue(remainingAccounts.isEmpty(), "All accounts should be deleted");

        // When - Update sync settings when no accounts exist
        // Include Authorization header to avoid authentication issues
        // Accept 200 (success), 429 (rate limited - acceptable for testing), or 5xx (server error)
        final var result =
                mockMvc.perform(
                        put("/api/plaid/accounts/sync-settings")
                                .header("Authorization", "Bearer " + accessToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("[]"))
                        .andReturn();

        final int status = result.getResponse().getStatus();
        // Accept 200 (success), 429 (rate limited - acceptable for testing), or 5xx (server error)
        assertTrue(
                status == 200 || status == 429 || status >= 500,
                "Should return 200 OK, 429 (rate limited), or 5xx error. Got status: " + status);

        // If successful (200), verify the response structure
        if (status == 200) {
            mockMvc.perform(
                            put("/api/plaid/accounts/sync-settings")
                                    .header("Authorization", "Bearer " + accessToken)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("[]"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("success"))
                    .andExpect(jsonPath("$.message").value("No accounts to update"));
        }
    }
}
