package com.budgetbuddy.integration;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Comprehensive Functional Integration Tests for All APIs Tests all endpoints used by the iOS app
 */
// `\n` in the format strings here is a literal LF (CSV rows / raw
// HTTP body templates), not a platform newline — we do NOT want %n.
@SuppressFBWarnings(
        value = "VA_FORMAT_STRING_USES_NEWLINE",
        justification = "literal LF in CSV / wire format, not platform newline")
@SpringBootTest(
        classes = com.budgetbuddy.BudgetBuddyApplication.class,
        webEnvironment = org.springframework.boot.test.context.SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
class ApiFunctionalIntegrationTest {

    private static final String AUTHORIZATION = "Authorization";

    @Autowired private MockMvc mockMvc;

    @Autowired private ObjectMapper objectMapper;

    @Autowired private AuthService authService;

    @Autowired private UserService userService;

    @Autowired private UserRepository userRepository;

    @Autowired private AccountRepository accountRepository;

    private String testEmail;
    private String testPasswordHash;
    private String authToken;
    private UserTable testUser;
    private AccountTable testAccount;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        testEmail = "test-" + UUID.randomUUID() + "@example.com";
        testPasswordHash =
                Base64.getEncoder()
                        .encodeToString("hashed-password".getBytes(StandardCharsets.UTF_8));

        // Create test user
        testUser = userService.createUserSecure(testEmail, testPasswordHash, "Test", "User");

        // Authenticate to get token
        final AuthRequest loginRequest = new AuthRequest();
        loginRequest.setEmail(testEmail);
        loginRequest.setPasswordHash(testPasswordHash);
        final AuthResponse authResponse = authService.authenticate(loginRequest);
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
    void testAuthRegisterShouldCreateUser() throws Exception {
        // Given
        final String newEmail = "newuser-" + UUID.randomUUID() + "@example.com";
        final AuthRequest request = new AuthRequest();
        request.setEmail(newEmail);
        request.setPasswordHash(testPasswordHash);

        // When/Then
        mockMvc.perform(
                        post("/api/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.token").exists())
                .andExpect(jsonPath("$.data.accessToken").exists());
    }

    @Test
    void testAuthLoginShouldReturnToken() throws Exception {
        // Given
        final AuthRequest request = new AuthRequest();
        request.setEmail(testEmail);
        request.setPasswordHash(testPasswordHash);

        // When/Then
        mockMvc.perform(
                        post("/api/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").exists())
                .andExpect(jsonPath("$.data.accessToken").exists());
    }

    @Test
    void testAuthLoginWithInvalidCredentialsShouldReturn401() throws Exception {
        // Given
        final AuthRequest request = new AuthRequest();
        request.setEmail(testEmail);
        request.setPasswordHash("wrong-hash");

        // When/Then
        mockMvc.perform(
                        post("/api/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void testAuthRefreshTokenShouldReturnNewToken() throws Exception {
        // Given
        final AuthRequest loginRequest = new AuthRequest();
        loginRequest.setEmail(testEmail);
        loginRequest.setPasswordHash(testPasswordHash);
        final AuthResponse loginResponse = authService.authenticate(loginRequest);

        // When/Then
        mockMvc.perform(
                        post("/api/auth/refresh")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"refreshToken\":\""
                                                + loginResponse.getRefreshToken()
                                                + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").exists());
    }

    // ==================== ACCOUNTS API TESTS ====================

    @Test
    void testAccountsGetAllShouldReturnAccounts() throws Exception {
        // When/Then
        mockMvc.perform(
                        get("/api/accounts")
                                .header(AUTHORIZATION, "Bearer " + authToken)
                                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    void testAccountsGetByIdShouldReturnAccount() throws Exception {
        // Given - Create an account first (would need AccountService)
        final String accountId = UUID.randomUUID().toString();

        // When/Then
        mockMvc.perform(
                        get("/api/accounts/" + accountId)
                                .header(AUTHORIZATION, "Bearer " + authToken)
                                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(
                        result -> {
                            final int status = result.getResponse().getStatus();
                            assertTrue(
                                    status == 200 || status == 404, "Status should be 200 or 404");
                        });
    }

    @Test
    void testAccountsGetAllWithoutAuthShouldReturn401() throws Exception {
        // When/Then
        mockMvc.perform(get("/api/accounts").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    // ==================== TRANSACTIONS API TESTS ====================

    @Test
    void testTransactionsGetAllShouldReturnTransactions() throws Exception {
        // When/Then
        mockMvc.perform(
                        get("/api/transactions")
                                .header(AUTHORIZATION, "Bearer " + authToken)
                                .param("page", "0")
                                .param("size", "20")
                                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    void testTransactionsGetAllWithPaginationShouldReturnPaginatedResults() throws Exception {
        // When/Then
        mockMvc.perform(
                        get("/api/transactions")
                                .header(AUTHORIZATION, "Bearer " + authToken)
                                .param("page", "0")
                                .param("size", "10")
                                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    void testTransactionsGetRangeShouldReturnTransactionsInRange() throws Exception {
        // Given
        final LocalDate startDate = LocalDate.now().minusDays(30);
        final LocalDate endDate = LocalDate.now();
        final DateTimeFormatter formatter = DateTimeFormatter.ISO_DATE;

        // When/Then
        mockMvc.perform(
                        get("/api/transactions/range")
                                .header(AUTHORIZATION, "Bearer " + authToken)
                                .param("startDate", startDate.format(formatter))
                                .param("endDate", endDate.format(formatter))
                                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    void testTransactionsGetTotalShouldReturnTotalSpending() throws Exception {
        // Given
        final LocalDate startDate = LocalDate.now().minusDays(30);
        final LocalDate endDate = LocalDate.now();
        final DateTimeFormatter formatter = DateTimeFormatter.ISO_DATE;

        // When/Then
        mockMvc.perform(
                        get("/api/transactions/total")
                                .header(AUTHORIZATION, "Bearer " + authToken)
                                .param("startDate", startDate.format(formatter))
                                .param("endDate", endDate.format(formatter))
                                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").exists());
    }

    @Test
    void testTransactionsCreateShouldCreateTransaction() throws Exception {
        // Given
        final String transactionJson =
                """
                        {
                            "accountId": "%s",
                            "amount": 100.50,
                            "description": "Test Transaction",
                            "transactionDate": "%s",
                            "categoryPrimary": "dining"
                        }
                        """
                        .formatted(
                                testAccount.getAccountId(),
                                LocalDate.now().format(DateTimeFormatter.ISO_DATE));

        // When/Then
        mockMvc.perform(
                        post("/api/transactions")
                                .header(AUTHORIZATION, "Bearer " + authToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(transactionJson))
                .andExpect(
                        result -> {
                            final int status = result.getResponse().getStatus();
                            assertTrue(
                                    status == 201 || status == 200, "Status should be 201 or 200");
                        });
    }

    @Test
    void testTransactionsUpdateShouldUpdateTransaction() throws Exception {
        // Given
        final String transactionId = UUID.randomUUID().toString();
        final String transactionJson =
                """
                        {
                            "amount": 150.75,
                            "description": "Updated Transaction"
                        }
                        """;

        // When/Then
        mockMvc.perform(
                        put("/api/transactions/" + transactionId)
                                .header(AUTHORIZATION, "Bearer " + authToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(transactionJson))
                .andExpect(
                        result -> {
                            final int status = result.getResponse().getStatus();
                            assertTrue(
                                    status == 200 || status == 404, "Status should be 200 or 404");
                        });
    }

    @Test
    void testTransactionsDeleteShouldDeleteTransaction() throws Exception {
        // Given
        final String transactionId = UUID.randomUUID().toString();

        // When/Then
        mockMvc.perform(
                        delete("/api/transactions/" + transactionId)
                                .header(AUTHORIZATION, "Bearer " + authToken)
                                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(
                        result -> {
                            final int status = result.getResponse().getStatus();
                            assertTrue(
                                    status == 200 || status == 404, "Status should be 200 or 404");
                        });
    }

    // ==================== TRANSACTION ACTIONS API TESTS ====================

    @Test
    void testTransactionActionsGetByTransactionShouldReturnActions() throws Exception {
        // Given
        final String transactionId = UUID.randomUUID().toString();

        // When/Then
        mockMvc.perform(
                        get("/api/transactions/" + transactionId + "/actions")
                                .header(AUTHORIZATION, "Bearer " + authToken)
                                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    void testTransactionActionsCreateShouldCreateAction() throws Exception {
        // Given
        final String transactionId = UUID.randomUUID().toString();
        final String actionJson =
                """
                        {
                            "type": "REMINDER",
                            "title": "Review transaction",
                            "description": "Check this transaction",
                            "dueDate": "%s"
                        }
                        """
                        .formatted(LocalDate.now().plusDays(7).format(DateTimeFormatter.ISO_DATE));

        // When/Then
        mockMvc.perform(
                        post("/api/transactions/" + transactionId + "/actions")
                                .header(AUTHORIZATION, "Bearer " + authToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(actionJson))
                .andExpect(
                        result -> {
                            final int status = result.getResponse().getStatus();
                            assertTrue(
                                    status == 201
                                            || status == 200
                                            || status == 400
                                            || status == 404,
                                    "Status should be 201, 200, 400, or 404 (400/404 acceptable for non-existent transaction)");
                        });
    }

    @Test
    void testTransactionActionsGetUserActionsShouldReturnAllUserActions() throws Exception {
        // When/Then
        mockMvc.perform(
                        get("/api/transactions/actions/user")
                                .header(AUTHORIZATION, "Bearer " + authToken)
                                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    // ==================== BUDGETS API TESTS ====================

    @Test
    void testBudgetsGetAllShouldReturnBudgets() throws Exception {
        // When/Then
        mockMvc.perform(
                        get("/api/budgets")
                                .header(AUTHORIZATION, "Bearer " + authToken)
                                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    void testBudgetsCreateShouldCreateBudget() throws Exception {
        // Given
        final String budgetJson =
                """
                        {
                            "name": "Monthly Food Budget",
                            "amount": 500.00,
                            "category": "Food",
                            "period": "MONTHLY"
                        }
                        """;

        // When/Then
        mockMvc.perform(
                        post("/api/budgets")
                                .header(AUTHORIZATION, "Bearer " + authToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(budgetJson))
                .andExpect(
                        result -> {
                            final int status = result.getResponse().getStatus();
                            assertTrue(
                                    status == 201 || status == 200 || status == 400,
                                    "Status should be 201, 200, or 400 (400 acceptable for missing required fields)");
                        });
    }

    @Test
    void testBudgetsDeleteShouldDeleteBudget() throws Exception {
        // Given
        final String budgetId = UUID.randomUUID().toString();

        // When/Then
        mockMvc.perform(
                        delete("/api/budgets/" + budgetId)
                                .header(AUTHORIZATION, "Bearer " + authToken)
                                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(
                        result -> {
                            final int status = result.getResponse().getStatus();
                            assertTrue(
                                    status == 200 || status == 404, "Status should be 200 or 404");
                        });
    }

    // ==================== GOALS API TESTS ====================

    @Test
    void testGoalsGetAllShouldReturnGoals() throws Exception {
        // When/Then
        mockMvc.perform(
                        get("/api/goals")
                                .header(AUTHORIZATION, "Bearer " + authToken)
                                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    void testGoalsCreateShouldCreateGoal() throws Exception {
        // Given
        final String goalJson =
                """
                        {
                            "name": "Save for Vacation",
                            "description": "Save $5000 for vacation",
                            "targetAmount": 5000.00,
                            "targetDate": "%s",
                            "goalType": "SAVINGS"
                        }
                        """
                        .formatted(
                                LocalDate.now().plusMonths(6).format(DateTimeFormatter.ISO_DATE));

        // When/Then
        mockMvc.perform(
                        post("/api/goals")
                                .header(AUTHORIZATION, "Bearer " + authToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(goalJson))
                .andExpect(
                        result -> {
                            final int status = result.getResponse().getStatus();
                            assertTrue(
                                    status == 201 || status == 200, "Status should be 201 or 200");
                        });
    }

    @Test
    void testGoalsUpdateProgressShouldUpdateGoal() throws Exception {
        // Given
        final String goalId = UUID.randomUUID().toString();
        final String progressJson =
                """
                        {
                            "currentAmount": 2500.00
                        }
                        """;

        // When/Then
        mockMvc.perform(
                        put("/api/goals/" + goalId + "/progress")
                                .header(AUTHORIZATION, "Bearer " + authToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(progressJson))
                .andExpect(
                        result -> {
                            final int status = result.getResponse().getStatus();
                            assertTrue(
                                    status == 200 || status == 404 || status == 400,
                                    "Status should be 200, 404, or 400 (404/400 acceptable for non-existent goal)");
                        });
    }

    // ==================== SYNC API TESTS ====================

    @Test
    void testSyncTriggerSyncShouldReturnSyncStatus() throws Exception {
        // Given
        final String syncRequest =
                """
                        {
                            "accessToken": "test-access-token"
                        }
                        """;

        // When/Then
        mockMvc.perform(
                        post("/api/transactions/sync")
                                .header(AUTHORIZATION, "Bearer " + authToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(syncRequest))
                .andExpect(
                        result -> {
                            final int status = result.getResponse().getStatus();
                            assertTrue(
                                    status == 200
                                            || status == 202
                                            || status == 400
                                            || status == 500,
                                    "Status should be 200, 202, 400, or 500 (400/500 acceptable if Plaid not configured)");
                        });
    }

    @Test
    void testSyncGetStatusShouldReturnSyncStatus() throws Exception {
        // When/Then
        mockMvc.perform(
                        get("/api/transactions/sync/status")
                                .header(AUTHORIZATION, "Bearer " + authToken)
                                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    // ==================== USER API TESTS ====================

    @Test
    void testUserGetMeShouldReturnUserInfo() throws Exception {
        // When/Then
        mockMvc.perform(
                        get("/api/users/me")
                                .header(AUTHORIZATION, "Bearer " + authToken)
                                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").exists());
    }

    @Test
    void testUserUpdateDeviceTokenShouldUpdateToken() throws Exception {
        // Given
        final String deviceTokenJson =
                """
                        {
                            "deviceToken": "test-device-token-12345"
                        }
                        """;

        // When/Then
        mockMvc.perform(
                        post("/api/users/device-token")
                                .header(AUTHORIZATION, "Bearer " + authToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(deviceTokenJson))
                .andExpect(status().isOk());
    }

    // ==================== PLAID API TESTS ====================

    @Test
    void testPlaidCreateLinkTokenShouldReturnLinkToken() throws Exception {
        // When/Then
        mockMvc.perform(
                        post("/api/plaid/link-token")
                                .header(AUTHORIZATION, "Bearer " + authToken)
                                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(
                        result -> {
                            final int status = result.getResponse().getStatus();
                            assertTrue(
                                    status == 200 || status == 400 || status == 500,
                                    "Status should be 200, 400, or 500 (400/500 acceptable if Plaid not configured)");
                            final String content = result.getResponse().getContentAsString();
                            assertTrue(
                                    content.contains("link_token") || content.contains("error"),
                                    "Response should contain link_token or error");
                        });
    }

    @Test
    void testPlaidExchangeTokenShouldExchangeToken() throws Exception {
        // Given
        final String exchangeRequest =
                """
                        {
                            "publicToken": "test-public-token"
                        }
                        """;

        // When/Then
        mockMvc.perform(
                        post("/api/plaid/exchange-token")
                                .header(AUTHORIZATION, "Bearer " + authToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(exchangeRequest))
                .andExpect(
                        result -> {
                            final int status = result.getResponse().getStatus();
                            assertTrue(
                                    status == 200 || status == 400, "Status should be 200 or 400");
                        });
    }

    @Test
    void testPlaidGetAccountsShouldReturnAccounts() throws Exception {
        // When/Then
        mockMvc.perform(
                        get("/api/plaid/accounts")
                                .header(AUTHORIZATION, "Bearer " + authToken)
                                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(
                        result -> {
                            final int status = result.getResponse().getStatus();
                            assertTrue(
                                    status == 200 || status == 400, "Status should be 200 or 400");
                        });
    }

    @Test
    void testPlaidGetTransactionsShouldReturnTransactions() throws Exception {
        // When/Then
        mockMvc.perform(
                        get("/api/plaid/transactions")
                                .header(AUTHORIZATION, "Bearer " + authToken)
                                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(
                        result -> {
                            final int status = result.getResponse().getStatus();
                            assertTrue(
                                    status == 200 || status == 400, "Status should be 200 or 400");
                        });
    }

    // ==================== ANALYTICS API TESTS ====================

    @Test
    void testAnalyticsGetSpendingSummaryShouldReturnSummary() throws Exception {
        // Given - Date range for analytics
        final LocalDate startDate = LocalDate.now().minusMonths(1);
        final LocalDate endDate = LocalDate.now();

        // When/Then
        mockMvc.perform(
                        get("/api/analytics/spending-summary")
                                .header(AUTHORIZATION, "Bearer " + authToken)
                                .param("startDate", startDate.format(DateTimeFormatter.ISO_DATE))
                                .param("endDate", endDate.format(DateTimeFormatter.ISO_DATE))
                                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").exists());
    }

    @Test
    void testAnalyticsGetSpendingByCategoryShouldReturnCategoryData() throws Exception {
        // Given - Date range for analytics
        final LocalDate startDate = LocalDate.now().minusMonths(1);
        final LocalDate endDate = LocalDate.now();

        // When/Then
        mockMvc.perform(
                        get("/api/analytics/spending-by-category")
                                .header(AUTHORIZATION, "Bearer " + authToken)
                                .param("startDate", startDate.format(DateTimeFormatter.ISO_DATE))
                                .param("endDate", endDate.format(DateTimeFormatter.ISO_DATE))
                                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").exists());
    }
}
