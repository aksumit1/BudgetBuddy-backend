package com.budgetbuddy.e2e;

import com.budgetbuddy.api.AuthController;
import com.budgetbuddy.api.TransactionController;
import com.budgetbuddy.api.AccountController;
import com.budgetbuddy.api.BudgetController;
import com.budgetbuddy.api.GoalController;
import com.budgetbuddy.api.TransactionActionController;
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
import com.budgetbuddy.service.TransactionService;
import com.budgetbuddy.service.BudgetService;
import com.budgetbuddy.service.GoalService;
import com.budgetbuddy.service.TransactionActionService;
import com.budgetbuddy.service.PlaidSyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.context.annotation.Import;
import com.budgetbuddy.AWSTestConfiguration;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;

/**
 * Comprehensive End-to-End API Test Suite
 * Tests all CRUD operations for all resources
 */
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
@DisplayName("E2E API Test Suite")
public class E2EAPITestSuite {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuthService authService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private PlaidSyncService plaidSyncService;

    @Autowired
    private BudgetRepository budgetRepository;

    @Autowired
    private GoalRepository goalRepository;

    @Autowired
    private TransactionActionRepository transactionActionRepository;

    private String authToken;
    private UserTable testUser;
    private AccountTable testAccount;

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
                "category": "food",
                "notes": "Test notes"
            }
            """.formatted(testAccount.getAccountId());

        mockMvc.perform(post("/api/transactions")
                .header("Authorization", "Bearer " + authToken)
                .contentType(APPLICATION_JSON)
                .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transactionId").exists())
                .andExpect(jsonPath("$.description").value("Test Transaction"))
                .andExpect(jsonPath("$.notes").value("Test notes"));
    }

    @Test
    @DisplayName("Read Transaction - GET /api/transactions/{id}")
    void testReadTransaction() throws Exception {
        // Create transaction first
        TransactionTable transaction = createTestTransaction(testUser, testAccount);
        
        mockMvc.perform(get("/api/transactions/" + transaction.getTransactionId())
                .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionId").value(transaction.getTransactionId()))
                .andExpect(jsonPath("$.description").exists());
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

        mockMvc.perform(put("/api/transactions/" + transaction.getTransactionId())
                .header("Authorization", "Bearer " + authToken)
                .contentType(APPLICATION_JSON)
                .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notes").value("Updated notes"));
    }

    @Test
    @DisplayName("Delete Transaction - DELETE /api/transactions/{id}")
    void testDeleteTransaction() throws Exception {
        // Create transaction first
        TransactionTable transaction = createTestTransaction(testUser, testAccount);
        
        mockMvc.perform(delete("/api/transactions/" + transaction.getTransactionId())
                .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isNoContent());

        // Verify deletion
        mockMvc.perform(get("/api/transactions/" + transaction.getTransactionId())
                .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isNotFound());
    }

    // MARK: - Account CRUD Tests

    @Test
    @DisplayName("Read Account - GET /api/accounts/{id}")
    void testReadAccount() throws Exception {
        mockMvc.perform(get("/api/accounts/" + testAccount.getAccountId())
                .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(testAccount.getAccountId()))
                .andExpect(jsonPath("$.accountName").exists());
    }

    @Test
    @DisplayName("List Accounts - GET /api/accounts")
    void testListAccounts() throws Exception {
        mockMvc.perform(get("/api/accounts")
                .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].accountId").exists());
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

        mockMvc.perform(post("/api/budgets")
                .header("Authorization", "Bearer " + authToken)
                .contentType(APPLICATION_JSON)
                .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.budgetId").exists())
                .andExpect(jsonPath("$.category").value("food"))
                .andExpect(jsonPath("$.monthlyLimit").value(500.00));
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

        mockMvc.perform(post("/api/budgets")
                .header("Authorization", "Bearer " + authToken)
                .contentType(APPLICATION_JSON)
                .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.monthlyLimit").value(750.00));
    }

    @Test
    @DisplayName("Delete Budget - DELETE /api/budgets/{id}")
    void testDeleteBudget() throws Exception {
        // Create budget first
        BudgetTable budget = createTestBudget(testUser);
        
        mockMvc.perform(delete("/api/budgets/" + budget.getBudgetId())
                .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isNoContent());
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

        mockMvc.perform(post("/api/goals")
                .header("Authorization", "Bearer " + authToken)
                .contentType(APPLICATION_JSON)
                .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.goalId").exists())
                .andExpect(jsonPath("$.name").value("Vacation Fund"))
                .andExpect(jsonPath("$.targetAmount").value(5000.00));
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

        mockMvc.perform(put("/api/goals/" + goal.getGoalId() + "/progress")
                .header("Authorization", "Bearer " + authToken)
                .contentType(APPLICATION_JSON)
                .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentAmount").exists());
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

        mockMvc.perform(post("/api/transactions/" + transaction.getTransactionId() + "/actions")
                .header("Authorization", "Bearer " + authToken)
                .contentType(APPLICATION_JSON)
                .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.actionId").exists())
                .andExpect(jsonPath("$.title").value("Review transaction"));
    }

    @Test
    @DisplayName("List Transaction Actions - GET /api/transactions/{id}/actions")
    void testListTransactionActions() throws Exception {
        // Create transaction and action first
        TransactionTable transaction = createTestTransaction(testUser, testAccount);
        createTestTransactionAction(testUser, transaction);
        
        mockMvc.perform(get("/api/transactions/" + transaction.getTransactionId() + "/actions")
                .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].actionId").exists());
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

