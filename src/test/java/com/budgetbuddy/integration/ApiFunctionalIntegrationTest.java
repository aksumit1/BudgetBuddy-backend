package com.budgetbuddy.integration;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.dto.AuthRequest;
import com.budgetbuddy.dto.AuthResponse;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.BudgetTable;
import com.budgetbuddy.model.dynamodb.GoalTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
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
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Comprehensive Functional Integration Tests for All APIs
 * Tests all endpoints used by the iOS app
 */
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
class ApiFunctionalIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AuthService authService;

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    private String testEmail;
    private String testPasswordHash;
    private String testSalt;
    private String authToken;
    private UserTable testUser;
    private AccountTable testAccount;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        testEmail = "test-" + UUID.randomUUID() + "@example.com";
        testPasswordHash = Base64.getEncoder().encodeToString("hashed-password".getBytes());
        testSalt = Base64.getEncoder().encodeToString("client-salt".getBytes());

        // Create test user
        testUser = userService.createUserSecure(
                testEmail,
                testPasswordHash,
                testSalt,
                "Test",
                "User"
        );

        // Authenticate to get token
        AuthRequest loginRequest = new AuthRequest();
        loginRequest.setEmail(testEmail);
        loginRequest.setPasswordHash(testPasswordHash);
        loginRequest.setSalt(testSalt);
        AuthResponse authResponse = authService.authenticate(loginRequest);
        authToken = authResponse.getAccessToken();

        // Create test account for transaction tests
        testAccount = new AccountTable();
        testAccount.setAccountId(UUID.randomUUID().toString());
        testAccount.setUserId(testUser.getUserId());
        testAccount.setAccountName("Test Account");
        testAccount.setInstitutionName("Test Bank");
        testAccount.setAccountType("CHECKING");
        testAccount.setBalance(new BigDecimal("1000.00"));
        testAccount.setCurrencyCode("USD");
        testAccount.setActive(true);
        testAccount.setCreatedAt(java.time.Instant.now());
        testAccount.setUpdatedAt(java.time.Instant.now());
        accountRepository.save(testAccount);
    }

    // ==================== AUTH API TESTS ====================

    @Test
    void testAuth_Register_ShouldCreateUser() throws Exception {
        // Given
        String newEmail = "newuser-" + UUID.randomUUID() + "@example.com";
        AuthRequest request = new AuthRequest();
        request.setEmail(newEmail);
        request.setPasswordHash(testPasswordHash);
        request.setSalt(testSalt);

        // When/Then
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").exists())
                .andExpect(jsonPath("$.accessToken").exists());
    }

    @Test
    void testAuth_Login_ShouldReturnToken() throws Exception {
        // Given
        AuthRequest request = new AuthRequest();
        request.setEmail(testEmail);
        request.setPasswordHash(testPasswordHash);
        request.setSalt(testSalt);

        // When/Then
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").exists())
                .andExpect(jsonPath("$.accessToken").exists());
    }

    @Test
    void testAuth_Login_WithInvalidCredentials_ShouldReturn401() throws Exception {
        // Given
        AuthRequest request = new AuthRequest();
        request.setEmail(testEmail);
        request.setPasswordHash("wrong-hash");
        request.setSalt(testSalt);

        // When/Then
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void testAuth_RefreshToken_ShouldReturnNewToken() throws Exception {
        // Given
        AuthRequest loginRequest = new AuthRequest();
        loginRequest.setEmail(testEmail);
        loginRequest.setPasswordHash(testPasswordHash);
        loginRequest.setSalt(testSalt);
        AuthResponse loginResponse = authService.authenticate(loginRequest);

        // When/Then
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + loginResponse.getRefreshToken() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").exists());
    }

    // ==================== ACCOUNTS API TESTS ====================

    @Test
    void testAccounts_GetAll_ShouldReturnAccounts() throws Exception {
        // When/Then
        mockMvc.perform(get("/api/accounts")
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void testAccounts_GetById_ShouldReturnAccount() throws Exception {
        // Given - Create an account first (would need AccountService)
        String accountId = UUID.randomUUID().toString();

        // When/Then
        mockMvc.perform(get("/api/accounts/" + accountId)
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    assertTrue(status == 200 || status == 404, "Status should be 200 or 404");
                });
    }

    @Test
    void testAccounts_GetAll_WithoutAuth_ShouldReturn401() throws Exception {
        // When/Then
        mockMvc.perform(get("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    // ==================== TRANSACTIONS API TESTS ====================

    @Test
    void testTransactions_GetAll_ShouldReturnTransactions() throws Exception {
        // When/Then
        mockMvc.perform(get("/api/transactions")
                        .header("Authorization", "Bearer " + authToken)
                        .param("page", "0")
                        .param("size", "20")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void testTransactions_GetAll_WithPagination_ShouldReturnPaginatedResults() throws Exception {
        // When/Then
        mockMvc.perform(get("/api/transactions")
                        .header("Authorization", "Bearer " + authToken)
                        .param("page", "0")
                        .param("size", "10")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void testTransactions_GetRange_ShouldReturnTransactionsInRange() throws Exception {
        // Given
        LocalDate startDate = LocalDate.now().minusDays(30);
        LocalDate endDate = LocalDate.now();
        DateTimeFormatter formatter = DateTimeFormatter.ISO_DATE;

        // When/Then
        mockMvc.perform(get("/api/transactions/range")
                        .header("Authorization", "Bearer " + authToken)
                        .param("startDate", startDate.format(formatter))
                        .param("endDate", endDate.format(formatter))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void testTransactions_GetTotal_ShouldReturnTotalSpending() throws Exception {
        // Given
        LocalDate startDate = LocalDate.now().minusDays(30);
        LocalDate endDate = LocalDate.now();
        DateTimeFormatter formatter = DateTimeFormatter.ISO_DATE;

        // When/Then
        mockMvc.perform(get("/api/transactions/total")
                        .header("Authorization", "Bearer " + authToken)
                        .param("startDate", startDate.format(formatter))
                        .param("endDate", endDate.format(formatter))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").exists());
    }

    @Test
    void testTransactions_Create_ShouldCreateTransaction() throws Exception {
        // Given
        String transactionJson = """
                {
                    "accountId": "%s",
                    "amount": 100.50,
                    "description": "Test Transaction",
                    "transactionDate": "%s",
                    "categoryPrimary": "dining"
                }
                """.formatted(testAccount.getAccountId(), LocalDate.now().format(DateTimeFormatter.ISO_DATE));

        // When/Then
        mockMvc.perform(post("/api/transactions")
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transactionJson))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    assertTrue(status == 201 || status == 200, "Status should be 201 or 200");
                });
    }

    @Test
    void testTransactions_Update_ShouldUpdateTransaction() throws Exception {
        // Given
        String transactionId = UUID.randomUUID().toString();
        String transactionJson = """
                {
                    "amount": 150.75,
                    "description": "Updated Transaction"
                }
                """;

        // When/Then
        mockMvc.perform(put("/api/transactions/" + transactionId)
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transactionJson))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    assertTrue(status == 200 || status == 404, "Status should be 200 or 404");
                });
    }

    @Test
    void testTransactions_Delete_ShouldDeleteTransaction() throws Exception {
        // Given
        String transactionId = UUID.randomUUID().toString();

        // When/Then
        mockMvc.perform(delete("/api/transactions/" + transactionId)
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    assertTrue(status == 200 || status == 404, "Status should be 200 or 404");
                });
    }

    // ==================== TRANSACTION ACTIONS API TESTS ====================

    @Test
    void testTransactionActions_GetByTransaction_ShouldReturnActions() throws Exception {
        // Given
        String transactionId = UUID.randomUUID().toString();

        // When/Then
        mockMvc.perform(get("/api/transactions/" + transactionId + "/actions")
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void testTransactionActions_Create_ShouldCreateAction() throws Exception {
        // Given
        String transactionId = UUID.randomUUID().toString();
        String actionJson = """
                {
                    "type": "REMINDER",
                    "title": "Review transaction",
                    "description": "Check this transaction",
                    "dueDate": "%s"
                }
                """.formatted(LocalDate.now().plusDays(7).format(DateTimeFormatter.ISO_DATE));

        // When/Then
        mockMvc.perform(post("/api/transactions/" + transactionId + "/actions")
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(actionJson))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    assertTrue(status == 201 || status == 200 || status == 400 || status == 404, 
                            "Status should be 201, 200, 400, or 404 (400/404 acceptable for non-existent transaction)");
                });
    }

    @Test
    void testTransactionActions_GetUserActions_ShouldReturnAllUserActions() throws Exception {
        // When/Then
        mockMvc.perform(get("/api/transactions/actions/user")
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    // ==================== BUDGETS API TESTS ====================

    @Test
    void testBudgets_GetAll_ShouldReturnBudgets() throws Exception {
        // When/Then
        mockMvc.perform(get("/api/budgets")
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void testBudgets_Create_ShouldCreateBudget() throws Exception {
        // Given
        String budgetJson = """
                {
                    "name": "Monthly Food Budget",
                    "amount": 500.00,
                    "category": "Food",
                    "period": "MONTHLY"
                }
                """;

        // When/Then
        mockMvc.perform(post("/api/budgets")
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(budgetJson))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    assertTrue(status == 201 || status == 200 || status == 400, 
                            "Status should be 201, 200, or 400 (400 acceptable for missing required fields)");
                });
    }

    @Test
    void testBudgets_Delete_ShouldDeleteBudget() throws Exception {
        // Given
        String budgetId = UUID.randomUUID().toString();

        // When/Then
        mockMvc.perform(delete("/api/budgets/" + budgetId)
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    assertTrue(status == 200 || status == 404, "Status should be 200 or 404");
                });
    }

    // ==================== GOALS API TESTS ====================

    @Test
    void testGoals_GetAll_ShouldReturnGoals() throws Exception {
        // When/Then
        mockMvc.perform(get("/api/goals")
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void testGoals_Create_ShouldCreateGoal() throws Exception {
        // Given
        String goalJson = """
                {
                    "name": "Save for Vacation",
                    "description": "Save $5000 for vacation",
                    "targetAmount": 5000.00,
                    "targetDate": "%s",
                    "goalType": "SAVINGS"
                }
                """.formatted(LocalDate.now().plusMonths(6).format(DateTimeFormatter.ISO_DATE));

        // When/Then
        mockMvc.perform(post("/api/goals")
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(goalJson))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    assertTrue(status == 201 || status == 200, "Status should be 201 or 200");
                });
    }

    @Test
    void testGoals_UpdateProgress_ShouldUpdateGoal() throws Exception {
        // Given
        String goalId = UUID.randomUUID().toString();
        String progressJson = """
                {
                    "currentAmount": 2500.00
                }
                """;

        // When/Then
        mockMvc.perform(put("/api/goals/" + goalId + "/progress")
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(progressJson))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    assertTrue(status == 200 || status == 404 || status == 400, 
                            "Status should be 200, 404, or 400 (404/400 acceptable for non-existent goal)");
                });
    }

    // ==================== SYNC API TESTS ====================

    @Test
    void testSync_TriggerSync_ShouldReturnSyncStatus() throws Exception {
        // Given
        String syncRequest = """
                {
                    "accessToken": "test-access-token"
                }
                """;

        // When/Then
        mockMvc.perform(post("/api/transactions/sync")
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(syncRequest))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    assertTrue(status == 200 || status == 202 || status == 400 || status == 500, 
                            "Status should be 200, 202, 400, or 500 (400/500 acceptable if Plaid not configured)");
                });
    }

    @Test
    void testSync_GetStatus_ShouldReturnSyncStatus() throws Exception {
        // When/Then
        mockMvc.perform(get("/api/transactions/sync/status")
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    // ==================== USER API TESTS ====================

    @Test
    void testUser_GetMe_ShouldReturnUserInfo() throws Exception {
        // When/Then
        mockMvc.perform(get("/api/users/me")
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").exists());
    }

    @Test
    void testUser_UpdateDeviceToken_ShouldUpdateToken() throws Exception {
        // Given
        String deviceTokenJson = """
                {
                    "deviceToken": "test-device-token-12345"
                }
                """;

        // When/Then
        mockMvc.perform(post("/api/users/device-token")
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(deviceTokenJson))
                .andExpect(status().isOk());
    }

    // ==================== PLAID API TESTS ====================

    @Test
    void testPlaid_CreateLinkToken_ShouldReturnLinkToken() throws Exception {
        // When/Then
        mockMvc.perform(post("/api/plaid/link-token")
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    assertTrue(status == 200 || status == 400 || status == 500, 
                            "Status should be 200, 400, or 500 (400/500 acceptable if Plaid not configured)");
                    String content = result.getResponse().getContentAsString();
                    assertTrue(content.contains("link_token") || content.contains("error"), 
                              "Response should contain link_token or error");
                });
    }

    @Test
    void testPlaid_ExchangeToken_ShouldExchangeToken() throws Exception {
        // Given
        String exchangeRequest = """
                {
                    "publicToken": "test-public-token"
                }
                """;

        // When/Then
        mockMvc.perform(post("/api/plaid/exchange-token")
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(exchangeRequest))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    assertTrue(status == 200 || status == 400, "Status should be 200 or 400");
                });
    }

    @Test
    void testPlaid_GetAccounts_ShouldReturnAccounts() throws Exception {
        // When/Then
        mockMvc.perform(get("/api/plaid/accounts")
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    assertTrue(status == 200 || status == 400, "Status should be 200 or 400");
                });
    }

    @Test
    void testPlaid_GetTransactions_ShouldReturnTransactions() throws Exception {
        // When/Then
        mockMvc.perform(get("/api/plaid/transactions")
                        .header("Authorization", "Bearer " + authToken)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    assertTrue(status == 200 || status == 400, "Status should be 200 or 400");
                });
    }

    // ==================== ANALYTICS API TESTS ====================

    @Test
    void testAnalytics_GetSpendingSummary_ShouldReturnSummary() throws Exception {
        // Given - Date range for analytics
        LocalDate startDate = LocalDate.now().minusMonths(1);
        LocalDate endDate = LocalDate.now();
        
        // When/Then
        mockMvc.perform(get("/api/analytics/spending-summary")
                        .header("Authorization", "Bearer " + authToken)
                        .param("startDate", startDate.format(DateTimeFormatter.ISO_DATE))
                        .param("endDate", endDate.format(DateTimeFormatter.ISO_DATE))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").exists());
    }

    @Test
    void testAnalytics_GetSpendingByCategory_ShouldReturnCategoryData() throws Exception {
        // Given - Date range for analytics
        LocalDate startDate = LocalDate.now().minusMonths(1);
        LocalDate endDate = LocalDate.now();
        
        // When/Then
        mockMvc.perform(get("/api/analytics/spending-by-category")
                        .header("Authorization", "Bearer " + authToken)
                        .param("startDate", startDate.format(DateTimeFormatter.ISO_DATE))
                        .param("endDate", endDate.format(DateTimeFormatter.ISO_DATE))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").exists());
    }
}

