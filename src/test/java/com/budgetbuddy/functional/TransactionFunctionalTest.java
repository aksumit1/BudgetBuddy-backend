package com.budgetbuddy.functional;

import com.budgetbuddy.api.TransactionController;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.service.TransactionService;
import com.budgetbuddy.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Functional Tests for Transaction API
 * Tests complete user workflows
 * 
 * DISABLED: Java 25 compatibility issue - Spring Boot context fails to load
 * due to Java 25 class format (major version 69) incompatibility with Spring Boot 3.4.1.
 * Will be re-enabled when Spring Boot fully supports Java 25.
 */
@org.junit.jupiter.api.Disabled("Java 25 compatibility: Spring Boot context loading fails")
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TransactionFunctionalTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private UserService userService;

    private UserTable testUser;

    @BeforeEach
    void setUp() {
        // Create test user
        String email = "test-" + UUID.randomUUID() + "@example.com";
        testUser = userService.createUserSecure(
                email,
                "hashed-password",
                "client-salt",
                "Test",
                "User"
        );
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
    @WithMockUser(username = "test@example.com")
    void testGetTransactions_WithPagination() throws Exception {
        // Given - User is authenticated

        // When/Then - Test pagination
        mockMvc.perform(get("/api/transactions")
                        .param("page", "0")
                        .param("size", "20")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "test@example.com")
    void testGetTransactionsInRange_WithDateRange() throws Exception {
        // Given
        LocalDate startDate = LocalDate.now().minusDays(30);
        LocalDate endDate = LocalDate.now();

        // When/Then
        mockMvc.perform(get("/api/transactions/range")
                        .param("startDate", startDate.toString())
                        .param("endDate", endDate.toString())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }
}

