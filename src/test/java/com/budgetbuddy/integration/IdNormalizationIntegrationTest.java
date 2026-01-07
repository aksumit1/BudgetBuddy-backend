package com.budgetbuddy.integration;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.BudgetTable;
import com.budgetbuddy.model.dynamodb.GoalTable;
import com.budgetbuddy.model.dynamodb.TransactionActionTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.BudgetRepository;
import com.budgetbuddy.repository.dynamodb.GoalRepository;
import com.budgetbuddy.repository.dynamodb.TransactionActionRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.repository.dynamodb.UserRepository;
import com.budgetbuddy.service.BudgetService;
import com.budgetbuddy.service.GoalService;
import com.budgetbuddy.service.TransactionActionService;
import com.budgetbuddy.service.TransactionService;
import com.budgetbuddy.service.UserService;
import com.budgetbuddy.util.IdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
 * Integration tests for ID normalization when saving entities
 * Verifies that all entity IDs are normalized to lowercase when saving,
 * ensuring case-insensitive lookup works correctly
 */
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
class IdNormalizationIntegrationTest {

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private BudgetService budgetService;

    @Autowired
    private BudgetRepository budgetRepository;

    @Autowired
    private GoalService goalService;

    @Autowired
    private GoalRepository goalRepository;

    @Autowired
    private TransactionActionService actionService;

    @Autowired
    private TransactionActionRepository actionRepository;

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
        String testEmail = "id-normalization-test-" + UUID.randomUUID() + "@example.com";
        String testPasswordHash = Base64.getEncoder().encodeToString("hashed-password".getBytes());

        testUser = userService.createUserSecure(
                testEmail,
                testPasswordHash, "ID",
                "Normalization"
        );

        // Create test account
        testAccount = new AccountTable();
        String accountId = IdGenerator.generateAccountId("Test Bank", "plaid-acc-" + UUID.randomUUID());
        testAccount.setAccountId(accountId);
        testAccount.setUserId(testUser.getUserId());
        testAccount.setAccountName("Test Checking");
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
    @DisplayName("Transaction ID should be normalized to lowercase when saving with mixed case")
    void testTransactionId_NormalizedWhenSaving_MixedCase() {
        // Given - Transaction ID with mixed case
        String mixedCaseTransactionId = "4191D23A-5E02-4DA5-9FDC-268980951676";
        String lowercaseTransactionId = mixedCaseTransactionId.toLowerCase();
        
        // Clean up any existing transaction with this ID to ensure fresh test
        transactionRepository.findById(lowercaseTransactionId).ifPresent(tx -> transactionRepository.delete(tx.getTransactionId()));

        // When - Create transaction with mixed case ID
        TransactionTable transaction = transactionService.createTransaction(
                testUser,
                testAccount.getAccountId(),
                BigDecimal.valueOf(100.00),
                LocalDate.now(),
                "Test Transaction",
                "FOOD",
                "FOOD",
                mixedCaseTransactionId,
                null
        );

        // Then - ID should be normalized to lowercase
        assertEquals(lowercaseTransactionId, transaction.getTransactionId(),
                "Transaction ID should be normalized to lowercase");

        // And - Should be findable by lowercase ID
        Optional<TransactionTable> found = transactionRepository.findById(lowercaseTransactionId);
        assertTrue(found.isPresent(), "Transaction should be findable by lowercase ID");
        assertEquals(transaction.getTransactionId(), found.get().getTransactionId());

        // And - Should be findable by original mixed case ID (normalized in lookup)
        Optional<TransactionTable> foundByMixedCase = transactionRepository.findById(mixedCaseTransactionId);
        assertTrue(foundByMixedCase.isPresent(), "Transaction should be findable by mixed case ID (normalized in lookup)");
    }

    @Test
    @DisplayName("Budget ID should be normalized to lowercase when saving with mixed case")
    void testBudgetId_NormalizedWhenSaving_MixedCase() {
        // Given - Budget ID with mixed case (must be valid UUID format)
        String mixedCaseBudgetId = "BEC4DC42-CFFF-42DD-B038-D73831892422";
        String lowercaseBudgetId = mixedCaseBudgetId.toLowerCase();
        
        // Clean up any existing budget with this ID to ensure fresh test
        budgetRepository.findById(lowercaseBudgetId).ifPresent(budget -> budgetRepository.delete(budget.getBudgetId()));

        // When - Create budget with mixed case ID
        BudgetTable budget = budgetService.createOrUpdateBudget(
                testUser,
                "FOOD",
                BigDecimal.valueOf(500.00),
                mixedCaseBudgetId
        );

        // Then - ID should be normalized to lowercase
        assertEquals(lowercaseBudgetId, budget.getBudgetId(),
                "Budget ID should be normalized to lowercase");

        // And - Should be findable by lowercase ID
        Optional<BudgetTable> found = budgetRepository.findById(lowercaseBudgetId);
        assertTrue(found.isPresent(), "Budget should be findable by lowercase ID");
        assertEquals(budget.getBudgetId(), found.get().getBudgetId());

        // And - Should be findable by original mixed case ID (normalized in lookup)
        Optional<BudgetTable> foundByMixedCase = budgetRepository.findById(mixedCaseBudgetId);
        assertTrue(foundByMixedCase.isPresent(), "Budget should be findable by mixed case ID (normalized in lookup)");
    }

    @Test
    @DisplayName("Goal ID should be normalized to lowercase when saving with mixed case")
    void testGoalId_NormalizedWhenSaving_MixedCase() {
        // Given - Goal ID with mixed case (must be valid UUID format)
        String mixedCaseGoalId = "A1B2C3D4-E5F6-4789-A012-3456789ABCDE";
        String lowercaseGoalId = mixedCaseGoalId.toLowerCase();
        
        // Clean up any existing goal with this ID to ensure fresh test
        goalRepository.findById(lowercaseGoalId).ifPresent(goal -> goalRepository.delete(goal.getGoalId()));

        // When - Create goal with mixed case ID
        GoalTable goal = goalService.createGoal(
                testUser,
                "Test Goal",
                "Goal description",
                BigDecimal.valueOf(1000.00),
                LocalDate.now().plusMonths(6),
                "SAVINGS",
                mixedCaseGoalId,
                null, // currentAmount
                null // accountIds
        );

        // Then - ID should be normalized to lowercase
        assertEquals(lowercaseGoalId, goal.getGoalId(),
                "Goal ID should be normalized to lowercase");

        // And - Should be findable by lowercase ID
        Optional<GoalTable> found = goalRepository.findById(lowercaseGoalId);
        assertTrue(found.isPresent(), "Goal should be findable by lowercase ID");
        assertEquals(goal.getGoalId(), found.get().getGoalId());

        // And - Should be findable by original mixed case ID (normalized in lookup)
        Optional<GoalTable> foundByMixedCase = goalRepository.findById(mixedCaseGoalId);
        assertTrue(foundByMixedCase.isPresent(), "Goal should be findable by mixed case ID (normalized in lookup)");
    }

    @Test
    @DisplayName("Action ID should be normalized to lowercase when saving with mixed case")
    void testActionId_NormalizedWhenSaving_MixedCase() {
        // Given - Create transaction first
        TransactionTable transaction = transactionService.createTransaction(
                testUser,
                testAccount.getAccountId(),
                BigDecimal.valueOf(100.00),
                LocalDate.now(),
                "Test Transaction",
                "FOOD"
        );

        // Given - Action ID with mixed case (must be valid UUID format)
        String mixedCaseActionId = "EBC9B5FF-F555-48A3-B1A9-F8A91A48AB15";
        String lowercaseActionId = mixedCaseActionId.toLowerCase();
        
        // Clean up any existing action with this ID to ensure fresh test
        actionRepository.findById(lowercaseActionId).ifPresent(action -> actionRepository.delete(action.getActionId()));

        // When - Create action with mixed case ID
        TransactionActionTable action = actionService.createAction(
                testUser,
                transaction.getTransactionId(),
                "Test Action",
                "Action description",
                null,
                null,
                "HIGH",
                mixedCaseActionId,
                null
        );

        // Then - ID should be normalized to lowercase
        assertEquals(lowercaseActionId, action.getActionId(),
                "Action ID should be normalized to lowercase");

        // And - Should be findable by lowercase ID
        Optional<TransactionActionTable> found = actionRepository.findById(lowercaseActionId);
        assertTrue(found.isPresent(), "Action should be findable by lowercase ID");
        assertEquals(action.getActionId(), found.get().getActionId());

        // And - Should be findable by original mixed case ID (normalized in lookup)
        Optional<TransactionActionTable> foundByMixedCase = actionRepository.findById(mixedCaseActionId);
        assertTrue(foundByMixedCase.isPresent(), "Action should be findable by mixed case ID (normalized in lookup)");
    }

    @Test
    @DisplayName("Random UUIDs should be normalized to lowercase when saving")
    void testRandomUUID_NormalizedWhenSaving() {
        // Given - Random UUID (typically uppercase)
        String randomUUID = UUID.randomUUID().toString().toUpperCase();

        // When - Create transaction with random UUID
        TransactionTable transaction = transactionService.createTransaction(
                testUser,
                testAccount.getAccountId(),
                BigDecimal.valueOf(100.00),
                LocalDate.now(),
                "Test Transaction",
                "FOOD",
                "FOOD",
                randomUUID,
                null
        );

        // Then - ID should be normalized to lowercase
        assertEquals(randomUUID.toLowerCase(), transaction.getTransactionId(),
                "Random UUID should be normalized to lowercase");
    }

    @Test
    @DisplayName("Generated deterministic IDs should be normalized to lowercase")
    void testGeneratedDeterministicId_NormalizedWhenSaving() {
        // When - Create budget without providing ID (will generate deterministic ID)
        BudgetTable budget = budgetService.createOrUpdateBudget(
                testUser,
                "TRANSPORTATION",
                BigDecimal.valueOf(300.00),
                null
        );

        // Then - Generated ID should be lowercase
        String budgetId = budget.getBudgetId();
        assertEquals(budgetId.toLowerCase(), budgetId,
                "Generated budget ID should be lowercase");
        assertTrue(IdGenerator.isValidUUID(budgetId), "Budget ID should be valid UUID");

        // When - Create goal without providing ID (will generate deterministic ID)
        GoalTable goal = goalService.createGoal(
                testUser,
                "Vacation Fund",
                "Save for vacation",
                BigDecimal.valueOf(5000.00),
                LocalDate.now().plusMonths(12),
                "SAVINGS",
                null, // goalId - let backend generate
                null, // currentAmount
                null // accountIds
        );

        // Then - Generated ID should be lowercase
        String goalId = goal.getGoalId();
        assertEquals(goalId.toLowerCase(), goalId,
                "Generated goal ID should be lowercase");
        assertTrue(IdGenerator.isValidUUID(goalId), "Goal ID should be valid UUID");
    }

    @Test
    @DisplayName("Action creation should work for newly created transaction")
    void testActionCreation_ForNewlyCreatedTransaction() {
        // Given - Create a new transaction
        TransactionTable transaction = transactionService.createTransaction(
                testUser,
                testAccount.getAccountId(),
                BigDecimal.valueOf(50.00),
                LocalDate.now(),
                "New Transaction",
                "FOOD"
        );

        // When - Immediately create an action for the transaction
        TransactionActionTable action = actionService.createAction(
                testUser,
                transaction.getTransactionId(),
                "Review transaction",
                "Check if correct",
                null,
                null,
                "HIGH",
                null,
                null
        );

        // Then - Action should be created successfully
        assertNotNull(action);
        assertEquals("Review transaction", action.getTitle());
        assertEquals(transaction.getTransactionId().toLowerCase(), action.getTransactionId().toLowerCase(),
                "Action transaction ID should match (case-insensitive)");

        // And - Action should be findable
        Optional<TransactionActionTable> found = actionRepository.findById(action.getActionId());
        assertTrue(found.isPresent(), "Action should be findable after creation");
    }

    @Test
    @DisplayName("Transaction lookup should work with case-insensitive ID")
    void testTransactionLookup_CaseInsensitive() {
        // Given - Create transaction with lowercase ID
        TransactionTable transaction = transactionService.createTransaction(
                testUser,
                testAccount.getAccountId(),
                BigDecimal.valueOf(100.00),
                LocalDate.now(),
                "Test Transaction",
                "FOOD"
        );

        String savedId = transaction.getTransactionId();
        assertTrue(savedId.equals(savedId.toLowerCase()), "Saved ID should be lowercase");

        // When - Look up with uppercase version
        String uppercaseId = savedId.toUpperCase();
        Optional<TransactionTable> found = transactionRepository.findById(uppercaseId);

        // Then - Should find the transaction (normalized in lookup)
        assertTrue(found.isPresent(), "Transaction should be findable with uppercase ID");
        assertEquals(savedId, found.get().getTransactionId());
    }
}

