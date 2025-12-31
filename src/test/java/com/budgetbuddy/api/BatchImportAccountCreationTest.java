package com.budgetbuddy.api;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.dto.AuthRequest;
import com.budgetbuddy.dto.AuthResponse;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.repository.dynamodb.UserRepository;
import com.budgetbuddy.service.AuthService;
import com.budgetbuddy.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration Tests for Batch Import with Account Creation and Matching
 * 
 * Tests the critical fixes for:
 * 1. Account creation during batch import
 * 2. Account matching via matchedAccountId
 * 3. Transaction accountId override when account is created/matched
 * 4. createdAccountId in BatchImportResponse
 * 5. Edge cases: invalid UUIDs, null checks, concurrent imports
 */
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
class BatchImportAccountCreationTest {

    private static final Logger logger = LoggerFactory.getLogger(BatchImportAccountCreationTest.class);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserService userService;

    @Autowired
    private AuthService authService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private UserRepository userRepository;

    private UserTable testUser;
    private String accessToken;
    private String testEmail;

    @BeforeEach
    void setUp() {
        // Create test user
        testEmail = "batch-import-test-" + UUID.randomUUID() + "@example.com";
        String base64PasswordHash = java.util.Base64.getEncoder().encodeToString("test-hash".getBytes());
        testUser = userService.createUserSecure(testEmail, base64PasswordHash, "Test", "User");

        // Authenticate to get token
        AuthRequest authRequest = new AuthRequest();
        authRequest.setEmail(testEmail);
        authRequest.setPasswordHash(base64PasswordHash);
        AuthResponse authResponse = authService.authenticate(authRequest);
        accessToken = authResponse.getAccessToken();
    }

    @Test
    void testBatchImportWithAccountCreation() throws Exception {
        // Given: Batch import request with detectedAccount and createDetectedAccount=true
        TransactionController.BatchImportRequest request = new TransactionController.BatchImportRequest();
        request.setCreateDetectedAccount(true);
        
        TransactionController.DetectedAccountInfo detectedAccount = new TransactionController.DetectedAccountInfo();
        detectedAccount.setAccountName("New Checking Account");
        detectedAccount.setInstitutionName("Test Bank");
        detectedAccount.setAccountType("depository");
        detectedAccount.setAccountSubtype("checking");
        detectedAccount.setAccountNumber("1234567890");
        detectedAccount.setBalance(new BigDecimal("5000.00"));
        request.setDetectedAccount(detectedAccount);

        List<TransactionController.CreateTransactionRequest> transactions = new ArrayList<>();
        TransactionController.CreateTransactionRequest tx1 = new TransactionController.CreateTransactionRequest();
        tx1.setAmount(new BigDecimal("100.00"));
        tx1.setTransactionDate(LocalDate.parse("2024-01-15"));
        tx1.setDescription("Test Transaction 1");
        tx1.setCategoryPrimary("expense");
        tx1.setCategoryDetailed("other");
        tx1.setTransactionType("EXPENSE");
        transactions.add(tx1);

        TransactionController.CreateTransactionRequest tx2 = new TransactionController.CreateTransactionRequest();
        tx2.setAmount(new BigDecimal("200.00"));
        tx2.setTransactionDate(LocalDate.parse("2024-01-16"));
        tx2.setDescription("Test Transaction 2");
        tx2.setCategoryPrimary("expense");
        tx2.setCategoryDetailed("other");
        tx2.setTransactionType("EXPENSE");
        transactions.add(tx2);

        request.setTransactions(transactions);

        // When: Batch import is executed
        MvcResult result = mockMvc.perform(post("/api/transactions/batch-import")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(2))
                .andExpect(jsonPath("$.failed").value(0))
                .andExpect(jsonPath("$.createdAccountId").exists())
                .andReturn();

        // Then: Account should be created
        String responseJson = result.getResponse().getContentAsString();
        TransactionController.BatchImportResponse response = objectMapper.readValue(
                responseJson, TransactionController.BatchImportResponse.class);
        
        assertNotNull(response.getCreatedAccountId(), "createdAccountId should be present");
        assertFalse(response.getCreatedAccountId().trim().isEmpty(), "createdAccountId should not be empty");
        
        // Validate UUID format
        assertDoesNotThrow(() -> UUID.fromString(response.getCreatedAccountId()),
                "createdAccountId should be a valid UUID");

        // Verify account exists in database
        Optional<AccountTable> createdAccount = accountRepository.findById(response.getCreatedAccountId());
        assertTrue(createdAccount.isPresent(), "Account should exist in database");
        assertEquals("New Checking Account", createdAccount.get().getAccountName());
        // Use compareTo for BigDecimal comparison (handles scale differences like 5000 vs 5000.00)
        assertEquals(0, new BigDecimal("5000.00").compareTo(createdAccount.get().getBalance()), 
                "Balance should be 5000.00");

        // Verify transactions are associated with the created account
        List<TransactionTable> savedTransactions = transactionRepository.findByUserId(testUser.getUserId(), 0, 100);
        assertEquals(2, savedTransactions.size(), "Both transactions should be saved");
        for (TransactionTable tx : savedTransactions) {
            assertEquals(response.getCreatedAccountId(), tx.getAccountId(),
                    "Transaction should be associated with created account");
        }
    }

    @Test
    void testBatchImportWithMatchedAccountId() throws Exception {
        // Given: Existing account
        AccountTable existingAccount = new AccountTable();
        existingAccount.setAccountId(UUID.randomUUID().toString().toLowerCase());
        existingAccount.setUserId(testUser.getUserId());
        existingAccount.setAccountName("Existing Account");
        existingAccount.setInstitutionName("Test Bank");
        existingAccount.setAccountType("depository");
        existingAccount.setAccountSubtype("checking");
        existingAccount.setBalance(new BigDecimal("1000.00"));
        existingAccount.setActive(true);
        existingAccount.setCreatedAt(Instant.now());
        existingAccount.setUpdatedAt(Instant.now());
        accountRepository.save(existingAccount);

        // Batch import request with matchedAccountId (but createDetectedAccount=false)
        TransactionController.BatchImportRequest request = new TransactionController.BatchImportRequest();
        request.setCreateDetectedAccount(false);
        
        TransactionController.DetectedAccountInfo detectedAccount = new TransactionController.DetectedAccountInfo();
        detectedAccount.setMatchedAccountId(existingAccount.getAccountId());
        detectedAccount.setAccountName("Existing Account");
        detectedAccount.setInstitutionName("Test Bank");
        request.setDetectedAccount(detectedAccount);

        List<TransactionController.CreateTransactionRequest> transactions = new ArrayList<>();
        TransactionController.CreateTransactionRequest tx1 = new TransactionController.CreateTransactionRequest();
        tx1.setAmount(new BigDecimal("50.00"));
        tx1.setTransactionDate(LocalDate.parse("2024-01-15"));
        tx1.setDescription("Test Transaction");
        tx1.setCategoryPrimary("expense");
        tx1.setCategoryDetailed("other");
        tx1.setTransactionType("EXPENSE");
        // Intentionally set wrong accountId to test override
        tx1.setAccountId(UUID.randomUUID().toString());
        transactions.add(tx1);

        request.setTransactions(transactions);

        // When: Batch import is executed
        MvcResult result = mockMvc.perform(post("/api/transactions/batch-import")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn();

        // Then: Transaction should be associated with matched account (not wrong accountId)
        String responseJson = result.getResponse().getContentAsString();
        TransactionController.BatchImportResponse response = objectMapper.readValue(
                responseJson, TransactionController.BatchImportResponse.class);
        
        // createdAccountId should be set to matched account ID
        assertEquals(existingAccount.getAccountId(), response.getCreatedAccountId(),
                "createdAccountId should be the matched account ID");

        // Verify transaction is associated with matched account
        List<TransactionTable> savedTransactions = transactionRepository.findByUserId(testUser.getUserId(), 0, 100);
        assertEquals(1, savedTransactions.size());
        assertEquals(existingAccount.getAccountId(), savedTransactions.get(0).getAccountId(),
                "Transaction should be associated with matched account, not wrong accountId");
    }

    @Test
    void testBatchImportWithInvalidMatchedAccountId() throws Exception {
        // Given: Batch import request with invalid matchedAccountId
        TransactionController.BatchImportRequest request = new TransactionController.BatchImportRequest();
        request.setCreateDetectedAccount(false);
        
        TransactionController.DetectedAccountInfo detectedAccount = new TransactionController.DetectedAccountInfo();
        detectedAccount.setMatchedAccountId("invalid-uuid");
        request.setDetectedAccount(detectedAccount);

        List<TransactionController.CreateTransactionRequest> transactions = new ArrayList<>();
        TransactionController.CreateTransactionRequest tx1 = new TransactionController.CreateTransactionRequest();
        tx1.setAmount(new BigDecimal("50.00"));
        tx1.setTransactionDate(LocalDate.parse("2024-01-15"));
        tx1.setDescription("Test Transaction");
        tx1.setCategoryPrimary("expense");
        tx1.setCategoryDetailed("other");
        tx1.setTransactionType("EXPENSE");
        transactions.add(tx1);

        request.setTransactions(transactions);

        // When: Batch import is executed
        MvcResult result = mockMvc.perform(post("/api/transactions/batch-import")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn();

        // Then: Import should succeed but accountId should not be set (invalid UUID)
        String responseJson = result.getResponse().getContentAsString();
        TransactionController.BatchImportResponse response = objectMapper.readValue(
                responseJson, TransactionController.BatchImportResponse.class);
        
        // createdAccountId should not be set (invalid UUID)
        assertNull(response.getCreatedAccountId(), 
                "createdAccountId should be null for invalid matchedAccountId");
    }

    @Test
    void testBatchImportWithNullTransactionInList() throws Exception {
        // Given: Batch import request with null transaction (edge case)
        TransactionController.BatchImportRequest request = new TransactionController.BatchImportRequest();
        request.setCreateDetectedAccount(true);
        
        TransactionController.DetectedAccountInfo detectedAccount = new TransactionController.DetectedAccountInfo();
        detectedAccount.setAccountName("Test Account");
        detectedAccount.setInstitutionName("Test Bank");
        detectedAccount.setAccountType("depository");
        request.setDetectedAccount(detectedAccount);

        List<TransactionController.CreateTransactionRequest> transactions = new ArrayList<>();
        TransactionController.CreateTransactionRequest tx1 = new TransactionController.CreateTransactionRequest();
        tx1.setAmount(new BigDecimal("100.00"));
        tx1.setTransactionDate(LocalDate.parse("2024-01-15"));
        tx1.setDescription("Valid Transaction");
        tx1.setCategoryPrimary("expense");
        tx1.setCategoryDetailed("other");
        tx1.setTransactionType("EXPENSE");
        transactions.add(tx1);
        transactions.add(null); // Null transaction (edge case)

        request.setTransactions(transactions);

        // When: Batch import is executed
        // Should not throw exception, should handle null gracefully
        mockMvc.perform(post("/api/transactions/batch-import")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());
        
        // Then: Valid transaction should be saved
        List<TransactionTable> savedTransactions = transactionRepository.findByUserId(testUser.getUserId(), 0, 100);
        assertEquals(1, savedTransactions.size(), "Only valid transaction should be saved");
    }

    @Test
    void testBatchImportAccountIdOverrideWhenAccountCreated() throws Exception {
        // Given: Transactions with wrong accountId
        String wrongAccountId = UUID.randomUUID().toString();
        
        TransactionController.BatchImportRequest request = new TransactionController.BatchImportRequest();
        request.setCreateDetectedAccount(true);
        
        TransactionController.DetectedAccountInfo detectedAccount = new TransactionController.DetectedAccountInfo();
        detectedAccount.setAccountName("New Account");
        detectedAccount.setInstitutionName("Test Bank");
        detectedAccount.setAccountType("depository");
        request.setDetectedAccount(detectedAccount);

        List<TransactionController.CreateTransactionRequest> transactions = new ArrayList<>();
        TransactionController.CreateTransactionRequest tx1 = new TransactionController.CreateTransactionRequest();
        tx1.setAmount(new BigDecimal("100.00"));
        tx1.setTransactionDate(LocalDate.parse("2024-01-15"));
        tx1.setDescription("Test Transaction");
        tx1.setCategoryPrimary("expense");
        tx1.setCategoryDetailed("other");
        tx1.setTransactionType("EXPENSE");
        tx1.setAccountId(wrongAccountId); // Wrong accountId
        transactions.add(tx1);

        request.setTransactions(transactions);

        // When: Batch import is executed
        MvcResult result = mockMvc.perform(post("/api/transactions/batch-import")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn();

        // Then: Transaction should be associated with created account (not wrong accountId)
        String responseJson = result.getResponse().getContentAsString();
        TransactionController.BatchImportResponse response = objectMapper.readValue(
                responseJson, TransactionController.BatchImportResponse.class);
        
        String createdAccountId = response.getCreatedAccountId();
        assertNotNull(createdAccountId);
        assertNotEquals(wrongAccountId, createdAccountId, 
                "Created account ID should be different from wrong accountId");

        List<TransactionTable> savedTransactions = transactionRepository.findByUserId(testUser.getUserId(), 0, 100);
        assertEquals(1, savedTransactions.size());
        assertEquals(createdAccountId, savedTransactions.get(0).getAccountId(),
                "Transaction should be associated with created account, not wrong accountId");
    }
}

