package com.budgetbuddy.e2e;

import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.BudgetTable;
import com.budgetbuddy.model.dynamodb.GoalTable;
import com.budgetbuddy.model.dynamodb.TransactionActionTable;
import com.budgetbuddy.repository.dynamodb.UserRepository;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.repository.dynamodb.BudgetRepository;
import com.budgetbuddy.repository.dynamodb.GoalRepository;
import com.budgetbuddy.repository.dynamodb.TransactionActionRepository;
import com.budgetbuddy.service.AuthService;
import com.budgetbuddy.util.TableInitializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.context.annotation.Import;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import com.budgetbuddy.AWSTestConfiguration;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import java.util.Map;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive End-to-End API Test Suite
 * Tests all CRUD operations for all resources
 * 
 * These are true E2E tests from customer perspective - they use a real HTTP server
 * and make actual HTTP requests, testing the full stack including controllers,
 * services, repositories, and database.
 */
@SpringBootTest(
    classes = com.budgetbuddy.BudgetBuddyApplication.class,
    webEnvironment = org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
)
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("E2E API Test Suite")
public class E2EAPITestSuite {

    private static final Logger logger = LoggerFactory.getLogger(E2EAPITestSuite.class);

    @Autowired
    private org.springframework.boot.test.web.client.TestRestTemplate restTemplate;

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private AuthService authService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;


    @Autowired
    private BudgetRepository budgetRepository;

    @Autowired
    private GoalRepository goalRepository;

    @Autowired
    private TransactionActionRepository transactionActionRepository;

    @Autowired
    private DynamoDbClient dynamoDbClient;

    private String authToken;
    private UserTable testUser;
    private AccountTable testAccount;

    @BeforeAll
    void ensureTablesInitialized() {
        // CRITICAL: Use global synchronized method to ensure tables are initialized
        // This prevents race conditions when tests run in parallel
        TableInitializer.ensureTablesInitializedAndVerified(dynamoDbClient);
    }

    @BeforeEach
    void setUp() {
        // Create test user
        testUser = createTestUser();
        
        // Generate auth token
        authToken = generateAuthToken(testUser);
        
        // Create test account
        testAccount = createTestAccount(testUser);
    }

    // MARK: - Transaction CRUD Tests

    @Test
    @DisplayName("Create Transaction - POST /api/transactions")
    void testCreateTransaction() throws Exception {
        String requestBody = """
            {
                "accountId": "%s",
                "amount": 100.50,
                "transactionDate": "2024-01-15",
                "description": "Test Transaction",
                "categoryPrimary": "Food and Drink",
                "categoryDetailed": "Restaurants",
                "notes": "Test notes"
            }
            """.formatted(testAccount.getAccountId());

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + authToken);
        headers.set("Content-Type", "application/json");
        HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "http://localhost:" + port + "/api/transactions",
                HttpMethod.POST,
                entity,
                new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {}
        );

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertNotNull(response.getBody().get("transactionId"));
        assertEquals("Test Transaction", response.getBody().get("description"));
        assertEquals("Test notes", response.getBody().get("notes"));
    }

    @Test
    @DisplayName("Read Transaction - GET /api/transactions/{id}")
    void testReadTransaction() throws Exception {
        // Create transaction first
        TransactionTable transaction = createTestTransaction(testUser, testAccount);
        
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + authToken);
        HttpEntity<String> entity = new HttpEntity<>(headers);

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "http://localhost:" + port + "/api/transactions/" + transaction.getTransactionId(),
                HttpMethod.GET,
                entity,
                new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {}
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(transaction.getTransactionId(), response.getBody().get("transactionId"));
        assertNotNull(response.getBody().get("description"));
    }

    @Test
    @DisplayName("Update Transaction - PUT /api/transactions/{id}")
    void testUpdateTransaction() throws Exception {
        // Create transaction first
        TransactionTable transaction = createTestTransaction(testUser, testAccount);
        
        String requestBody = """
            {
                "notes": "Updated notes"
            }
            """;

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + authToken);
        headers.set("Content-Type", "application/json");
        HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "http://localhost:" + port + "/api/transactions/" + transaction.getTransactionId(),
                HttpMethod.PUT,
                entity,
                new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {}
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("Updated notes", response.getBody().get("notes"));
    }

    @Test
    @DisplayName("Delete Transaction - DELETE /api/transactions/{id}")
    void testDeleteTransaction() throws Exception {
        // Create transaction first
        TransactionTable transaction = createTestTransaction(testUser, testAccount);
        
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + authToken);
        HttpEntity<String> entity = new HttpEntity<>(headers);

        ResponseEntity<Void> deleteResponse = restTemplate.exchange(
                "http://localhost:" + port + "/api/transactions/" + transaction.getTransactionId(),
                HttpMethod.DELETE,
                entity,
                Void.class
        );

        assertEquals(HttpStatus.NO_CONTENT, deleteResponse.getStatusCode());

        // Verify deletion
        ResponseEntity<Map<String, Object>> getResponse = restTemplate.exchange(
                "http://localhost:" + port + "/api/transactions/" + transaction.getTransactionId(),
                HttpMethod.GET,
                entity,
                new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {}
        );

        assertEquals(HttpStatus.NOT_FOUND, getResponse.getStatusCode());
    }

    // MARK: - Account CRUD Tests

    @Test
    @DisplayName("Read Account - GET /api/accounts/{id}")
    void testReadAccount() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + authToken);
        HttpEntity<String> entity = new HttpEntity<>(headers);

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "http://localhost:" + port + "/api/accounts/" + testAccount.getAccountId(),
                HttpMethod.GET,
                entity,
                new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {}
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(testAccount.getAccountId(), response.getBody().get("accountId"));
        assertNotNull(response.getBody().get("accountName"));
    }

    @Test
    @DisplayName("List Accounts - GET /api/accounts")
    void testListAccounts() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + authToken);
        HttpEntity<String> entity = new HttpEntity<>(headers);

        ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                "http://localhost:" + port + "/api/accounts",
                HttpMethod.GET,
                entity,
                new org.springframework.core.ParameterizedTypeReference<List<Map<String, Object>>>() {}
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody() instanceof List);
        assertFalse(response.getBody().isEmpty());
        assertTrue(response.getBody().get(0) instanceof Map);
        assertNotNull(response.getBody().get(0).get("accountId"));
    }

    // MARK: - Budget CRUD Tests

    @Test
    @DisplayName("Create Budget - POST /api/budgets")
    void testCreateBudget() throws Exception {
        String requestBody = """
            {
                "category": "food",
                "monthlyLimit": 500.00
            }
            """;

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + authToken);
        headers.set("Content-Type", "application/json");
        HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "http://localhost:" + port + "/api/budgets",
                HttpMethod.POST,
                entity,
                new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {}
        );

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertNotNull(response.getBody().get("budgetId"));
        assertEquals("food", response.getBody().get("category"));
        assertEquals(500.00, ((Number) response.getBody().get("monthlyLimit")).doubleValue(), 0.01);
    }

    @Test
    @DisplayName("Update Budget - POST /api/budgets (createOrUpdate)")
    void testUpdateBudget() throws Exception {
        // Create budget first
        BudgetTable budget = createTestBudget(testUser);
        
        String requestBody = """
            {
                "category": "food",
                "monthlyLimit": 750.00,
                "budgetId": "%s"
            }
            """.formatted(budget.getBudgetId());

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + authToken);
        headers.set("Content-Type", "application/json");
        HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "http://localhost:" + port + "/api/budgets",
                HttpMethod.POST,
                entity,
                new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {}
        );

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(750.00, ((Number) response.getBody().get("monthlyLimit")).doubleValue(), 0.01);
    }

    @Test
    @DisplayName("Delete Budget - DELETE /api/budgets/{id}")
    void testDeleteBudget() throws Exception {
        // Create budget first
        BudgetTable budget = createTestBudget(testUser);
        
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + authToken);
        HttpEntity<String> entity = new HttpEntity<>(headers);

        ResponseEntity<Void> response = restTemplate.exchange(
                "http://localhost:" + port + "/api/budgets/" + budget.getBudgetId(),
                HttpMethod.DELETE,
                entity,
                Void.class
        );

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
    }

    // MARK: - Goal CRUD Tests

    @Test
    @DisplayName("Create Goal - POST /api/goals")
    void testCreateGoal() throws Exception {
        String requestBody = """
            {
                "name": "Vacation Fund",
                "targetAmount": 5000.00,
                "targetDate": "2025-12-31",
                "goalType": "SAVINGS"
            }
            """;

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + authToken);
        headers.set("Content-Type", "application/json");
        HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "http://localhost:" + port + "/api/goals",
                HttpMethod.POST,
                entity,
                new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {}
        );

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertNotNull(response.getBody().get("goalId"));
        assertEquals("Vacation Fund", response.getBody().get("name"));
        assertEquals(5000.00, ((Number) response.getBody().get("targetAmount")).doubleValue(), 0.01);
    }

    @Test
    @DisplayName("Update Goal Progress - PUT /api/goals/{id}/progress")
    void testUpdateGoal() throws Exception {
        // Create goal first
        GoalTable goal = createTestGoal(testUser);
        
        String requestBody = """
            {
                "amount": 1000.00
            }
            """;

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + authToken);
        headers.set("Content-Type", "application/json");
        HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "http://localhost:" + port + "/api/goals/" + goal.getGoalId() + "/progress",
                HttpMethod.PUT,
                entity,
                new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {}
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertNotNull(response.getBody().get("currentAmount"));
    }

    // MARK: - Transaction Action CRUD Tests

    @Test
    @DisplayName("Create Transaction Action - POST /api/transactions/{id}/actions")
    void testCreateTransactionAction() throws Exception {
        // Create transaction first
        TransactionTable transaction = createTestTransaction(testUser, testAccount);
        
        String requestBody = """
            {
                "title": "Review transaction",
                "description": "Need to review this transaction",
                "priority": "HIGH"
            }
            """;

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + authToken);
        headers.set("Content-Type", "application/json");
        HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "http://localhost:" + port + "/api/transactions/" + transaction.getTransactionId() + "/actions",
                HttpMethod.POST,
                entity,
                new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {}
        );

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertNotNull(response.getBody().get("actionId"));
        assertEquals("Review transaction", response.getBody().get("title"));
    }

    @Test
    @DisplayName("List Transaction Actions - GET /api/transactions/{id}/actions")
    void testListTransactionActions() throws Exception {
        // Create transaction and action first
        TransactionTable transaction = createTestTransaction(testUser, testAccount);
        createTestTransactionAction(testUser, transaction);
        
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + authToken);
        HttpEntity<String> entity = new HttpEntity<>(headers);

        ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                "http://localhost:" + port + "/api/transactions/" + transaction.getTransactionId() + "/actions",
                HttpMethod.GET,
                entity,
                new org.springframework.core.ParameterizedTypeReference<List<Map<String, Object>>>() {}
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody() instanceof List);
        assertFalse(response.getBody().isEmpty());
        assertTrue(response.getBody().get(0) instanceof Map);
        assertNotNull(response.getBody().get(0).get("actionId"));
    }

    // MARK: - Helper Methods

    private UserTable createTestUser() {
        UserTable user = new UserTable();
        user.setUserId(UUID.randomUUID().toString());
        user.setEmail("test-e2e-" + UUID.randomUUID() + "@test.com");
        user.setPasswordHash("test-hash");
        user.setEnabled(true);
        user.setEmailVerified(true);
        userRepository.save(user);
        return user;
    }

    private String generateAuthToken(UserTable user) {
        // Use AuthService to generate token
        return authService.generateTokensForUser(user).getAccessToken();
    }

    private AccountTable createTestAccount(UserTable user) {
        AccountTable account = new AccountTable();
        account.setAccountId(UUID.randomUUID().toString());
        account.setUserId(user.getUserId());
        account.setAccountName("Test Account");
        account.setInstitutionName("Test Bank");
        account.setBalance(BigDecimal.valueOf(1000.00));
        account.setCurrencyCode("USD");
        accountRepository.save(account);
        return account;
    }

    private TransactionTable createTestTransaction(UserTable user, AccountTable account) {
        TransactionTable transaction = new TransactionTable();
        transaction.setTransactionId(UUID.randomUUID().toString());
        transaction.setUserId(user.getUserId());
        transaction.setAccountId(account.getAccountId());
        transaction.setAmount(BigDecimal.valueOf(100.00));
        transaction.setTransactionDate(LocalDate.now().toString());
        transaction.setDescription("Test Transaction");
        transaction.setCategoryPrimary("dining");
        transaction.setCategoryDetailed("dining");
        transactionRepository.save(transaction);
        return transaction;
    }

    private BudgetTable createTestBudget(UserTable user) {
        BudgetTable budget = new BudgetTable();
        budget.setBudgetId(UUID.randomUUID().toString());
        budget.setUserId(user.getUserId());
        budget.setCategory("food");
        budget.setMonthlyLimit(BigDecimal.valueOf(500.00));
        budgetRepository.save(budget);
        return budget;
    }

    private GoalTable createTestGoal(UserTable user) {
        GoalTable goal = new GoalTable();
        goal.setGoalId(UUID.randomUUID().toString());
        goal.setUserId(user.getUserId());
        goal.setName("Test Goal");
        goal.setTargetAmount(BigDecimal.valueOf(5000.00));
        goal.setCurrentAmount(BigDecimal.ZERO);
        goal.setTargetDate(LocalDate.now().plusMonths(12).toString());
        goalRepository.save(goal);
        return goal;
    }

    private TransactionActionTable createTestTransactionAction(UserTable user, TransactionTable transaction) {
        TransactionActionTable action = new TransactionActionTable();
        action.setActionId(UUID.randomUUID().toString());
        action.setTransactionId(transaction.getTransactionId());
        action.setUserId(user.getUserId());
        action.setTitle("Test Action");
        action.setDescription("Test Description");
        action.setPriority("HIGH");
        transactionActionRepository.save(action);
        return action;
    }
}

