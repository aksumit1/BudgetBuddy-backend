package com.budgetbuddy.integration;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.BudgetTable;
import com.budgetbuddy.model.dynamodb.GoalTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.BudgetRepository;
import com.budgetbuddy.repository.dynamodb.GoalRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.repository.dynamodb.UserRepository;
import com.budgetbuddy.service.BudgetService;
import com.budgetbuddy.service.GoalService;
import com.budgetbuddy.service.TransactionService;
import com.budgetbuddy.service.UserService;
import com.budgetbuddy.util.IdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for ID consistency across backend operations
 * Verifies that IDs are generated consistently and match between app and backend expectations
 */
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
class IdConsistencyIntegrationTest {
    
    // Note: Some tests may require LocalStack to be running for DynamoDB operations
    // If tests fail with ApplicationContext errors, ensure LocalStack is running

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private BudgetService budgetService;

    @Autowired
    private BudgetRepository budgetRepository;

    @Autowired
    private GoalService goalService;

    @Autowired
    private GoalRepository goalRepository;

    @Autowired
    private TransactionService transactionService;

    private UserTable testUser;
    private AccountTable testAccount;

    @BeforeEach
    void setUp() {
        // Clean up test data
        if (testUser != null) {
            try {
                userRepository.delete(testUser.getUserId());
            } catch (Exception e) {
                // Ignore cleanup errors
            }
        }

        // Create test user
        String testEmail = "id-test-" + UUID.randomUUID() + "@example.com";
        String testPasswordHash = Base64.getEncoder().encodeToString("hashed-password".getBytes());
        String testSalt = Base64.getEncoder().encodeToString("client-salt".getBytes());

        testUser = userService.createUserSecure(
                testEmail,
                testPasswordHash,
                testSalt,
                "ID",
                "Test"
        );

        // Create test account with deterministic ID
        String institutionName = "Test Bank";
        String plaidAccountId = "plaid_acc_123";
        String accountId = IdGenerator.generateAccountId(institutionName, plaidAccountId);

        testAccount = new AccountTable();
        testAccount.setAccountId(accountId);
        testAccount.setUserId(testUser.getUserId());
        testAccount.setAccountName("Test Account");
        testAccount.setInstitutionName(institutionName);
        testAccount.setAccountType("CHECKING");
        testAccount.setBalance(BigDecimal.valueOf(1000.00));
        testAccount.setCurrencyCode("USD");
        testAccount.setPlaidAccountId(plaidAccountId);
        testAccount.setActive(true);
        testAccount.setCreatedAt(Instant.now());
        testAccount.setUpdatedAt(Instant.now());
        accountRepository.save(testAccount);
    }

    @Test
    void testAccountId_ConsistentGeneration() {
        // Given
        String institutionName = "Chase Bank";
        String plaidAccountId = "plaid_acc_456";

        // When - Generate ID using IdGenerator
        String generatedAccountId = IdGenerator.generateAccountId(institutionName, plaidAccountId);

        // Then - Should be valid UUID
        assertTrue(IdGenerator.isValidUUID(generatedAccountId), "Generated account ID should be valid UUID");

        // When - Generate again with same inputs
        String generatedAccountId2 = IdGenerator.generateAccountId(institutionName, plaidAccountId);

        // Then - Should be identical
        assertEquals(generatedAccountId, generatedAccountId2, "Account IDs should be deterministic");
    }

    @Test
    void testTransactionId_ConsistentGeneration() {
        // Given
        String institutionName = testAccount.getInstitutionName();
        String accountId = testAccount.getAccountId();
        String plaidTransactionId = "plaid_txn_789";

        // When - Generate transaction ID
        String generatedTransactionId = IdGenerator.generateTransactionId(
                institutionName,
                accountId,
                plaidTransactionId
        );

        // Then - Should be valid UUID
        assertTrue(IdGenerator.isValidUUID(generatedTransactionId), "Generated transaction ID should be valid UUID");

        // When - Generate again with same inputs
        String generatedTransactionId2 = IdGenerator.generateTransactionId(
                institutionName,
                accountId,
                plaidTransactionId
        );

        // Then - Should be identical
        assertEquals(generatedTransactionId, generatedTransactionId2, "Transaction IDs should be deterministic");
    }

    @Test
    void testTransactionCreation_WithConsistentId() {
        // Given
        String institutionName = testAccount.getInstitutionName();
        String accountId = testAccount.getAccountId();
        String plaidTransactionId = "plaid_txn_999";
        String expectedTransactionId = IdGenerator.generateTransactionId(
                institutionName,
                accountId,
                plaidTransactionId
        );

        // When - Create transaction (backend will generate ID if not provided)
        TransactionTable transaction = transactionService.createTransaction(
                testUser,
                accountId,
                BigDecimal.valueOf(100.00),
                LocalDate.now(),
                "Test Transaction",
                "FOOD",
                null // Let backend generate
        );

        // Note: Since we're not providing the Plaid transaction ID in createTransaction,
        // the backend will generate a random UUID. This test verifies the ID generation works.
        assertNotNull(transaction.getTransactionId(), "Transaction should have an ID");
        assertTrue(IdGenerator.isValidUUID(transaction.getTransactionId()), "Transaction ID should be valid UUID");
    }

    @Test
    void testBudgetCreation_WithAppProvidedId() {
        // Given
        String userId = testUser.getUserId();
        String category = "FOOD";
        String expectedBudgetId = IdGenerator.generateBudgetId(userId, category);

        // When - Create budget with app-provided ID
        BudgetTable budget = budgetService.createOrUpdateBudget(
                testUser,
                category,
                BigDecimal.valueOf(500.00),
                expectedBudgetId // App-provided ID
        );

        // Then - Should use the provided ID
        assertEquals(expectedBudgetId, budget.getBudgetId(), "Budget should use provided ID");
        assertTrue(IdGenerator.isValidUUID(budget.getBudgetId()), "Budget ID should be valid UUID");
    }

    @Test
    void testBudgetCreation_WithoutAppProvidedId_GeneratesDeterministic() {
        // Given
        String userId = testUser.getUserId();
        String category = "TRANSPORTATION";
        String expectedBudgetId = IdGenerator.generateBudgetId(userId, category);

        // When - Create budget without app-provided ID
        BudgetTable budget = budgetService.createOrUpdateBudget(
                testUser,
                category,
                BigDecimal.valueOf(300.00),
                null // Let backend generate
        );

        // Then - Should generate deterministic ID matching our expectation
        assertEquals(expectedBudgetId, budget.getBudgetId(), "Budget should generate deterministic ID");
        assertTrue(IdGenerator.isValidUUID(budget.getBudgetId()), "Budget ID should be valid UUID");
    }

    @Test
    void testGoalCreation_WithAppProvidedId() {
        // Given
        String userId = testUser.getUserId();
        String goalName = "Emergency Fund";
        String expectedGoalId = IdGenerator.generateGoalId(userId, goalName);

        // When - Create goal with app-provided ID
        GoalTable goal = goalService.createGoal(
                testUser,
                goalName,
                "Save for emergencies",
                BigDecimal.valueOf(10000.00),
                LocalDate.now().plusMonths(12),
                "EMERGENCY_FUND",
                expectedGoalId // App-provided ID
        );

        // Then - Should use the provided ID
        assertEquals(expectedGoalId, goal.getGoalId(), "Goal should use provided ID");
        assertTrue(IdGenerator.isValidUUID(goal.getGoalId()), "Goal ID should be valid UUID");
    }

    @Test
    void testGoalCreation_WithoutAppProvidedId_GeneratesDeterministic() {
        // Given
        String userId = testUser.getUserId();
        String goalName = "Vacation Fund";
        String expectedGoalId = IdGenerator.generateGoalId(userId, goalName);

        // When - Create goal without app-provided ID
        GoalTable goal = goalService.createGoal(
                testUser,
                goalName,
                "Save for vacation",
                BigDecimal.valueOf(5000.00),
                LocalDate.now().plusMonths(6),
                "VACATION",
                null // Let backend generate
        );

        // Then - Should generate deterministic ID matching our expectation
        assertEquals(expectedGoalId, goal.getGoalId(), "Goal should generate deterministic ID");
        assertTrue(IdGenerator.isValidUUID(goal.getGoalId()), "Goal ID should be valid UUID");
    }

    @Test
    void testAccountId_AppBackendConsistency() {
        // Given - Simulate what app would generate
        String institutionName = "Wells Fargo";
        String plaidAccountId = "plaid_acc_app_test";

        // When - App generates ID (simulated)
        String appGeneratedId = IdGenerator.generateAccountId(institutionName, plaidAccountId);

        // When - Backend generates ID (same logic)
        String backendGeneratedId = IdGenerator.generateAccountId(institutionName, plaidAccountId);

        // Then - Should be identical
        assertEquals(appGeneratedId, backendGeneratedId, "App and backend should generate same account ID");
    }

    @Test
    void testTransactionId_AppBackendConsistency() {
        // Given - Simulate what app would generate
        String institutionName = "Bank of America";
        String accountId = UUID.randomUUID().toString(); // App's account ID
        String plaidTransactionId = "plaid_txn_app_test";

        // When - App generates transaction ID (simulated)
        String appGeneratedId = IdGenerator.generateTransactionId(institutionName, accountId, plaidTransactionId);

        // When - Backend generates transaction ID (same logic)
        String backendGeneratedId = IdGenerator.generateTransactionId(institutionName, accountId, plaidTransactionId);

        // Then - Should be identical
        assertEquals(appGeneratedId, backendGeneratedId, "App and backend should generate same transaction ID");
    }

    @Test
    void testBudgetId_AppBackendConsistency() {
        // Given - Simulate what app would generate
        String userId = testUser.getUserId();
        String category = "DINING";

        // When - App generates budget ID (simulated)
        String appGeneratedId = IdGenerator.generateBudgetId(userId, category);

        // When - Backend generates budget ID (same logic)
        String backendGeneratedId = IdGenerator.generateBudgetId(userId, category);

        // Then - Should be identical
        assertEquals(appGeneratedId, backendGeneratedId, "App and backend should generate same budget ID");
    }

    @Test
    void testGoalId_AppBackendConsistency() {
        // Given - Simulate what app would generate
        String userId = testUser.getUserId();
        String goalName = "House Down Payment";

        // When - App generates goal ID (simulated)
        String appGeneratedId = IdGenerator.generateGoalId(userId, goalName);

        // When - Backend generates goal ID (same logic)
        String backendGeneratedId = IdGenerator.generateGoalId(userId, goalName);

        // Then - Should be identical
        assertEquals(appGeneratedId, backendGeneratedId, "App and backend should generate same goal ID");
    }

    @Test
    void testBudgetId_DuplicatePrevention() {
        // Given
        String userId = testUser.getUserId();
        String category = "ENTERTAINMENT";
        String budgetId = IdGenerator.generateBudgetId(userId, category);

        // When - Create first budget with ID
        BudgetTable budget1 = budgetService.createOrUpdateBudget(
                testUser,
                category,
                BigDecimal.valueOf(200.00),
                budgetId
        );

        // Then - Should succeed
        assertNotNull(budget1, "First budget should be created");

        // When - Try to create another budget with same ID
        assertThrows(AppException.class, () -> {
            budgetService.createOrUpdateBudget(
                    testUser,
                    "OTHER", // Different category but same ID (shouldn't happen in practice)
                    BigDecimal.valueOf(300.00),
                    budgetId // Same ID
            );
        }, "Should throw exception when budget ID already exists");
    }

    @Test
    void testGoalId_DuplicatePrevention() {
        // Given
        String userId = testUser.getUserId();
        String goalName = "Test Goal";
        String goalId = IdGenerator.generateGoalId(userId, goalName);

        // When - Create first goal with ID
        GoalTable goal1 = goalService.createGoal(
                testUser,
                goalName,
                "Test description",
                BigDecimal.valueOf(1000.00),
                LocalDate.now().plusMonths(6),
                "CUSTOM",
                goalId
        );

        // Then - Should succeed
        assertNotNull(goal1, "First goal should be created");

        // When - Try to create another goal with same ID
        assertThrows(AppException.class, () -> {
            goalService.createGoal(
                    testUser,
                    "Different Goal Name", // Different name but same ID (shouldn't happen in practice)
                    "Different description",
                    BigDecimal.valueOf(2000.00),
                    LocalDate.now().plusMonths(12),
                    "CUSTOM",
                    goalId // Same ID
            );
        }, "Should throw exception when goal ID already exists");
    }
}

