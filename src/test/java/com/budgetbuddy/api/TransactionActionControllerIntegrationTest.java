package com.budgetbuddy.api;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.dto.AuthRequest;
import com.budgetbuddy.dto.AuthResponse;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.TransactionActionTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.TransactionActionRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.service.AuthService;
import com.budgetbuddy.service.TransactionService;
import com.budgetbuddy.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration Tests for TransactionActionController
 * Tests REST API endpoints with MockMvc
 */
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
class TransactionActionControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserService userService;

    @Autowired
    private AuthService authService;

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private TransactionActionRepository actionRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private UserTable testUser;
    private String testEmail;
    private AccountTable testAccount;
    private TransactionTable testTransaction;
    private String accessToken;

    @BeforeEach
    void setUp() {
        // Clear security context to ensure clean state for each test
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        
        testEmail = "test-" + UUID.randomUUID() + "@example.com";

        // Create test user
        String base64PasswordHash = java.util.Base64.getEncoder().encodeToString("hashed-password".getBytes());
        String base64ClientSalt = java.util.Base64.getEncoder().encodeToString("client-salt".getBytes());
        testUser = userService.createUserSecure(
                testEmail,
                base64PasswordHash,
                base64ClientSalt,
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
        testAccount.setBalance(BigDecimal.valueOf(1000.00));
        testAccount.setCurrencyCode("USD");
        testAccount.setActive(true);
        testAccount.setCreatedAt(Instant.now());
        testAccount.setUpdatedAt(Instant.now());
        accountRepository.save(testAccount);

        // Create test transaction
        testTransaction = transactionService.createTransaction(
                testUser,
                testAccount.getAccountId(),
                BigDecimal.valueOf(100.00),
                LocalDate.now(),
                "Test transaction",
                "FOOD"
        );

        // Authenticate to get JWT token
        AuthRequest authRequest = new AuthRequest();
        authRequest.setEmail(testEmail);
        authRequest.setPasswordHash(base64PasswordHash);
        authRequest
        AuthResponse authResponse = authService.authenticate(authRequest);
        accessToken = authResponse.getAccessToken();
    }

    @Test
    void testCreateAction_WithValidRequest_ReturnsCreated() throws Exception {
        // Given
        TransactionActionController.CreateActionRequest request = new TransactionActionController.CreateActionRequest();
        request.setTitle("Review transaction");
        request.setDescription("Check if correct");
        request.setPriority("HIGH");

        // When/Then
        mockMvc.perform(post("/api/transactions/{transactionId}/actions", testTransaction.getTransactionId())
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("Review transaction"))
                .andExpect(jsonPath("$.description").value("Check if correct"))
                .andExpect(jsonPath("$.priority").value("HIGH"))
                .andExpect(jsonPath("$.isCompleted").value(false));
    }

    @Test
    void testCreateAction_WithEmptyTitle_ReturnsBadRequest() throws Exception {
        // Given
        TransactionActionController.CreateActionRequest request = new TransactionActionController.CreateActionRequest();
        request.setTitle("");

        // When/Then
        mockMvc.perform(post("/api/transactions/{transactionId}/actions", testTransaction.getTransactionId())
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testGetActions_WithValidTransaction_ReturnsActions() throws Exception {
        // Given - Create an action first
        TransactionActionController.CreateActionRequest createRequest = new TransactionActionController.CreateActionRequest();
        createRequest.setTitle("Test Action");
        createRequest.setPriority("MEDIUM");

        mockMvc.perform(post("/api/transactions/{transactionId}/actions", testTransaction.getTransactionId())
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isCreated());

        // When/Then
        mockMvc.perform(get("/api/transactions/{transactionId}/actions", testTransaction.getTransactionId())
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].title").value("Test Action"));
    }

    @Test
    void testUpdateAction_WithValidRequest_ReturnsOk() throws Exception {
        // Given - Create an action first
        TransactionActionController.CreateActionRequest createRequest = new TransactionActionController.CreateActionRequest();
        createRequest.setTitle("Original Title");
        createRequest.setPriority("LOW");

        String createResponse = mockMvc.perform(post("/api/transactions/{transactionId}/actions", testTransaction.getTransactionId())
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        TransactionActionTable createdAction = objectMapper.readValue(createResponse, TransactionActionTable.class);

        // When - Update the action
        TransactionActionController.UpdateActionRequest updateRequest = new TransactionActionController.UpdateActionRequest();
        updateRequest.setTitle("Updated Title");
        updateRequest.setIsCompleted(true);
        updateRequest.setPriority("HIGH");

        // Then
        mockMvc.perform(put("/api/transactions/{transactionId}/actions/{actionId}",
                        testTransaction.getTransactionId(), createdAction.getActionId())
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Updated Title"))
                .andExpect(jsonPath("$.isCompleted").value(true))
                .andExpect(jsonPath("$.priority").value("HIGH"));
    }

    @Test
    void testDeleteAction_WithValidAction_ReturnsNoContent() throws Exception {
        // Given - Create an action first
        TransactionActionController.CreateActionRequest createRequest = new TransactionActionController.CreateActionRequest();
        createRequest.setTitle("Action to Delete");
        createRequest.setPriority("MEDIUM");

        String createResponse = mockMvc.perform(post("/api/transactions/{transactionId}/actions", testTransaction.getTransactionId())
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        TransactionActionTable createdAction = objectMapper.readValue(createResponse, TransactionActionTable.class);

        // When/Then
        mockMvc.perform(delete("/api/transactions/{transactionId}/actions/{actionId}",
                        testTransaction.getTransactionId(), createdAction.getActionId())
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNoContent());

        // Verify action is deleted
        mockMvc.perform(get("/api/transactions/{transactionId}/actions", testTransaction.getTransactionId())
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void testGetActions_WithoutAuthentication_ReturnsUnauthorized() throws Exception {
        // Given - Clear security context to ensure no authentication
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        
        // When/Then
        mockMvc.perform(get("/api/transactions/{transactionId}/actions", testTransaction.getTransactionId())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void testCreateAction_WithNonExistentTransaction_ReturnsNotFound() throws Exception {
        // Given
        TransactionActionController.CreateActionRequest request = new TransactionActionController.CreateActionRequest();
        request.setTitle("Test Action");

        // When/Then
        mockMvc.perform(post("/api/transactions/{transactionId}/actions", UUID.randomUUID().toString())
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound());
    }
}

