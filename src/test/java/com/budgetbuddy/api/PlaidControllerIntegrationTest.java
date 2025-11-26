package com.budgetbuddy.api;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.UserRepository;
import com.budgetbuddy.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration Tests for PlaidController
 * Tests the /api/plaid/accounts endpoint with and without accessToken parameter
 * 
 * DISABLED: Java 25 compatibility issue - Spring Boot context fails to load
 * due to Java 25 class format (major version 69) incompatibility with Spring Boot 3.4.1.
 * Will be re-enabled when Spring Boot fully supports Java 25.
 */
@org.junit.jupiter.api.Disabled("Java 25 compatibility: Spring Boot context loading fails")
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PlaidControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    private UserTable testUser;
    private String testEmail;
    private AccountTable testAccount;

    @BeforeEach
    void setUp() {
        testEmail = "test-" + UUID.randomUUID() + "@example.com";
        
        // Create test user
        testUser = userService.createUserSecure(
                testEmail,
                "hashed-password",
                "client-salt",
                "Test",
                "User"
        );

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
    }

    @Test
    @WithMockUser(username = "test@example.com")
    void testGetAccounts_WithoutAccessToken_ReturnsAccountsFromDatabase() throws Exception {
        // When/Then - Should return accounts from database when accessToken is not provided
        mockMvc.perform(get("/api/plaid/accounts")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accounts").isArray())
                .andExpect(jsonPath("$.accounts[0].accountName").value("Test Account"));
    }

    @Test
    @WithMockUser(username = "test@example.com")
    void testGetAccounts_WithAccessToken_ReturnsAccountsFromPlaid() throws Exception {
        // When/Then - Should attempt to fetch from Plaid API when accessToken is provided
        // Note: This will fail if Plaid API is not configured, but should not throw MissingServletRequestParameterException
        mockMvc.perform(get("/api/plaid/accounts")
                        .param("accessToken", "test-access-token")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk().or(status().is5xxServerError())); // OK if Plaid works, 5xx if not configured
    }

    @Test
    @WithMockUser(username = "test@example.com")
    void testGetAccounts_WithEmptyAccessToken_ReturnsAccountsFromDatabase() throws Exception {
        // When/Then - Empty accessToken should be treated as missing
        mockMvc.perform(get("/api/plaid/accounts")
                        .param("accessToken", "")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accounts").isArray());
    }
}

