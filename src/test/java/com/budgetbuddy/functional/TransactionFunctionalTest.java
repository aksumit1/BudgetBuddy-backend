package com.budgetbuddy.functional;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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

import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Functional Tests for Transaction API
 * Tests complete user workflows
 * 
 */
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
class TransactionFunctionalTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired(required = false)
    private ObjectMapper objectMapper;

    @Autowired
    private UserService userService;
    
    private ObjectMapper getObjectMapper() {
        if (objectMapper == null) {
            objectMapper = new ObjectMapper();
            objectMapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        }
        return objectMapper;
    }

    private UserTable testUser;

    @BeforeEach
    void setUp() {
        // Create test user with base64-encoded password hash
        String email = "test-" + UUID.randomUUID() + "@example.com";
        String passwordHash = java.util.Base64.getEncoder().encodeToString(("hashed-password-" + UUID.randomUUID()).getBytes());

        // Create test user - tables should be initialized before tests run
        // BREAKING CHANGE: firstName and lastName are optional (can be null)
        testUser = userService.createUserSecure(
                email,
                passwordHash,
                null,
                null
        );
        
        // Ensure ObjectMapper has JavaTimeModule for Instant serialization
        ObjectMapper mapper = getObjectMapper();
        if (mapper.getRegisteredModuleIds().stream().noneMatch(id -> id.toString().contains("JavaTimeModule"))) {
            mapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        }
    }

    @Test
    @WithMockUser(username = "test@example.com")
    void testCreateTransaction_CompleteWorkflow() throws Exception {
        // Given - Create account first (simplified for test)
        // In real scenario, account would be created via Plaid or manually

        // When/Then - This is a simplified functional test
        // Full implementation would require account setup
        assertNotNull(testUser);
    }

    @Test
    void testGetTransactions_WithPagination() throws Exception {
        // Given - User is authenticated (use test user's email)
        String testUserEmail = testUser.getEmail();

        // When/Then - Test pagination - tables should be initialized before tests run
        mockMvc.perform(get("/api/transactions")
                        .param("page", "0")
                        .param("size", "20")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(testUserEmail))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @Test
    void testGetTransactionsInRange_WithDateRange() throws Exception {
        // Given
        String testUserEmail = testUser.getEmail();
        LocalDate startDate = LocalDate.now().minusDays(30);
        LocalDate endDate = LocalDate.now();

        // When/Then - tables should be initialized before tests run
        mockMvc.perform(get("/api/transactions/range")
                        .param("startDate", startDate.toString())
                        .param("endDate", endDate.toString())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(testUserEmail))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }
}

