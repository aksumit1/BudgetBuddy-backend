package com.budgetbuddy.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Integration tests for ID normalization when saving entities Verifies that all entity IDs are
 * normalized to lowercase when saving, ensuring case-insensitive lookup works correctly
 */
// SDK / Spring integration — the underlying APIs (AWS SDK, Plaid SDK,
// Spring services, reflection) throw arbitrary RuntimeException subtypes
// that can't reasonably be enumerated. Broad catches log + recover (or
// translate to AppException). Suppress at class level since narrowing
// here would mean catch (RuntimeException) which PMD flags identically.
@SuppressWarnings("PMD.AvoidCatchingGenericException")
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
class IdNormalizationIntegrationTest {

    @Autowired private UserService userService;

    @Autowired private UserRepository userRepository;

    @Autowired private AccountRepository accountRepository;

    @Autowired private TransactionRepository transactionRepository;

    @Autowired private TransactionService transactionService;

    @Autowired private BudgetService budgetService;

    @Autowired private BudgetRepository budgetRepository;

    @Autowired private GoalService goalService;

    @Autowired private GoalRepository goalRepository;

    @Autowired private TransactionActionService actionService;

    @Autowired private TransactionActionRepository actionRepository;

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
        final String testEmail = "id-normalization-test-" + UUID.randomUUID() + "@example.com";
        final String testPasswordHash =
                Base64.getEncoder()
                        .encodeToString("hashed-password".getBytes(StandardCharsets.UTF_8));

        testUser = userService.createUserSecure(testEmail, testPasswordHash, "ID", "Normalization");

        // Create test account
        testAccount = new AccountTable();
        final String accountId =
                IdGenerator.generateAccountId("Test Bank", "plaid-acc-" + UUID.randomUUID());
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
    void testTransactionIdNormalizedWhenSavingMixedCase() {
        // Given - Transaction ID with mixed case
        final String mixedCaseTransactionId = "4191D23A-5E02-4DA5-9FDC-268980951676";
        final String lowercaseTransactionId = mixedCaseTransactionId.toLowerCase(Locale.ROOT);

        // Clean up any existing transaction with this ID to ensure fresh test
        transactionRepository
                .findById(lowercaseTransactionId)
                .ifPresent(tx -> transactionRepository.delete(tx.getTransactionId()));

        // When - Create transaction with mixed case ID
        final TransactionTable transaction =
                transactionService.createTransaction(
                        testUser,
                        testAccount.getAccountId(),
                        BigDecimal.valueOf(100.00),
                        LocalDate.now(),
                        "Test Transaction",
                        "FOOD",
                        "FOOD",
                        mixedCaseTransactionId,
                        null);

        // Then - ID should be normalized to lowercase
        assertEquals(
                lowercaseTransactionId,
                transaction.getTransactionId(),
                "Transaction ID should be normalized to lowercase");

        // And - Should be findable by lowercase ID
        final Optional<TransactionTable> found =
                transactionRepository.findById(lowercaseTransactionId);
        assertTrue(found.isPresent(), "Transaction should be findable by lowercase ID");
        assertEquals(transaction.getTransactionId(), found.get().getTransactionId());

        // And - Should be findable by original mixed case ID (normalized in lookup)
        final Optional<TransactionTable> foundByMixedCase =
                transactionRepository.findById(mixedCaseTransactionId);
        assertTrue(
                foundByMixedCase.isPresent(),
                "Transaction should be findable by mixed case ID (normalized in lookup)");
    }

    @Test
    @DisplayName("Budget ID should be normalized to lowercase when saving with mixed case")
    void testBudgetIdNormalizedWhenSavingMixedCase() {
        // Given - Budget ID with mixed case (must be valid UUID format)
        final String mixedCaseBudgetId = "BEC4DC42-CFFF-42DD-B038-D73831892422";
        final String lowercaseBudgetId = mixedCaseBudgetId.toLowerCase(Locale.ROOT);

        // Clean up any existing budget with this ID to ensure fresh test
        budgetRepository
                .findById(lowercaseBudgetId)
                .ifPresent(budget -> budgetRepository.delete(budget.getBudgetId()));

        // When - Create budget with mixed case ID
        final BudgetTable budget =
                budgetService.createOrUpdateBudget(
                        testUser, "FOOD", BigDecimal.valueOf(500.00), mixedCaseBudgetId,null, null, null, null, null, null);

        // Then - ID should be normalized to lowercase
        assertEquals(
                lowercaseBudgetId,
                budget.getBudgetId(),
                "Budget ID should be normalized to lowercase");

        // And - Should be findable by lowercase ID
        final Optional<BudgetTable> found = budgetRepository.findById(lowercaseBudgetId);
        assertTrue(found.isPresent(), "Budget should be findable by lowercase ID");
        assertEquals(budget.getBudgetId(), found.get().getBudgetId());

        // And - Should be findable by original mixed case ID (normalized in lookup)
        final Optional<BudgetTable> foundByMixedCase = budgetRepository.findById(mixedCaseBudgetId);
        assertTrue(
                foundByMixedCase.isPresent(),
                "Budget should be findable by mixed case ID (normalized in lookup)");
    }

    @Test
    @DisplayName("Goal ID should be normalized to lowercase when saving with mixed case")
    void testGoalIdNormalizedWhenSavingMixedCase() {
        // Given - Goal ID with mixed case (must be valid UUID format)
        final String mixedCaseGoalId = "A1B2C3D4-E5F6-4789-A012-3456789ABCDE";
        final String lowercaseGoalId = mixedCaseGoalId.toLowerCase(Locale.ROOT);

        // Clean up any existing goal with this ID to ensure fresh test
        goalRepository
                .findById(lowercaseGoalId)
                .ifPresent(goal -> goalRepository.delete(goal.getGoalId()));

        // When - Create goal with mixed case ID
        final GoalTable goal =
                goalService.createGoal(
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
        assertEquals(
                lowercaseGoalId, goal.getGoalId(), "Goal ID should be normalized to lowercase");

        // And - Should be findable by lowercase ID
        final Optional<GoalTable> found = goalRepository.findById(lowercaseGoalId);
        assertTrue(found.isPresent(), "Goal should be findable by lowercase ID");
        assertEquals(goal.getGoalId(), found.get().getGoalId());

        // And - Should be findable by original mixed case ID (normalized in lookup)
        final Optional<GoalTable> foundByMixedCase = goalRepository.findById(mixedCaseGoalId);
        assertTrue(
                foundByMixedCase.isPresent(),
                "Goal should be findable by mixed case ID (normalized in lookup)");
    }

    @Test
    @DisplayName("Action ID should be normalized to lowercase when saving with mixed case")
    void testActionIdNormalizedWhenSavingMixedCase() {
        // Given - Create transaction first
        final TransactionTable transaction =
                transactionService.createTransaction(
                        testUser,
                        testAccount.getAccountId(),
                        BigDecimal.valueOf(100.00),
                        LocalDate.now(),
                        "Test Transaction",
                        "FOOD");

        // Given - Action ID with mixed case (must be valid UUID format)
        final String mixedCaseActionId = "EBC9B5FF-F555-48A3-B1A9-F8A91A48AB15";
        final String lowercaseActionId = mixedCaseActionId.toLowerCase(Locale.ROOT);

        // Clean up any existing action with this ID to ensure fresh test
        actionRepository
                .findById(lowercaseActionId)
                .ifPresent(action -> actionRepository.delete(action.getActionId()));

        // When - Create action with mixed case ID
        final TransactionActionTable action =
                actionService.createAction(
                        testUser,
                        transaction.getTransactionId(),
                        "Test Action",
                        "Action description",
                        null,
                        null,
                        "HIGH",
                        mixedCaseActionId,
                        null);

        // Then - ID should be normalized to lowercase
        assertEquals(
                lowercaseActionId,
                action.getActionId(),
                "Action ID should be normalized to lowercase");

        // And - Should be findable by lowercase ID
        final Optional<TransactionActionTable> found = actionRepository.findById(lowercaseActionId);
        assertTrue(found.isPresent(), "Action should be findable by lowercase ID");
        assertEquals(action.getActionId(), found.get().getActionId());

        // And - Should be findable by original mixed case ID (normalized in lookup)
        final Optional<TransactionActionTable> foundByMixedCase =
                actionRepository.findById(mixedCaseActionId);
        assertTrue(
                foundByMixedCase.isPresent(),
                "Action should be findable by mixed case ID (normalized in lookup)");
    }

    @Test
    @DisplayName("Random UUIDs should be normalized to lowercase when saving")
    void testRandomUUIDNormalizedWhenSaving() {
        // Given - Random UUID (typically uppercase)
        final String randomUUID = UUID.randomUUID().toString().toUpperCase(Locale.ROOT);

        // When - Create transaction with random UUID
        final TransactionTable transaction =
                transactionService.createTransaction(
                        testUser,
                        testAccount.getAccountId(),
                        BigDecimal.valueOf(100.00),
                        LocalDate.now(),
                        "Test Transaction",
                        "FOOD",
                        "FOOD",
                        randomUUID,
                        null);

        // Then - ID should be normalized to lowercase
        assertEquals(
                randomUUID.toLowerCase(Locale.ROOT),
                transaction.getTransactionId(),
                "Random UUID should be normalized to lowercase");
    }

    @Test
    @DisplayName("Generated deterministic IDs should be normalized to lowercase")
    void testGeneratedDeterministicIdNormalizedWhenSaving() {
        // When - Create budget without providing ID (will generate deterministic ID)
        final BudgetTable budget =
                budgetService.createOrUpdateBudget(
                        testUser, "TRANSPORTATION", BigDecimal.valueOf(300.00), null,null, null, null, null, null, null);

        // Then - Generated ID should be lowercase
        final String budgetId = budget.getBudgetId();
        assertEquals(
                budgetId.toLowerCase(Locale.ROOT),
                budgetId,
                "Generated budget ID should be lowercase");
        assertTrue(IdGenerator.isValidUUID(budgetId), "Budget ID should be valid UUID");

        // When - Create goal without providing ID (will generate deterministic ID)
        final GoalTable goal =
                goalService.createGoal(
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
        final String goalId = goal.getGoalId();
        assertEquals(
                goalId.toLowerCase(Locale.ROOT), goalId, "Generated goal ID should be lowercase");
        assertTrue(IdGenerator.isValidUUID(goalId), "Goal ID should be valid UUID");
    }

    @Test
    @DisplayName("Action creation should work for newly created transaction")
    void testActionCreationForNewlyCreatedTransaction() {
        // Given - Create a new transaction
        final TransactionTable transaction =
                transactionService.createTransaction(
                        testUser,
                        testAccount.getAccountId(),
                        BigDecimal.valueOf(50.00),
                        LocalDate.now(),
                        "New Transaction",
                        "FOOD");

        // When - Immediately create an action for the transaction
        final TransactionActionTable action =
                actionService.createAction(
                        testUser,
                        transaction.getTransactionId(),
                        "Review transaction",
                        "Check if correct",
                        null,
                        null,
                        "HIGH",
                        null,
                        null);

        // Then - Action should be created successfully
        assertNotNull(action);
        assertEquals("Review transaction", action.getTitle());
        assertEquals(
                transaction.getTransactionId().toLowerCase(Locale.ROOT),
                action.getTransactionId().toLowerCase(Locale.ROOT),
                "Action transaction ID should match (case-insensitive)");

        // And - Action should be findable
        final Optional<TransactionActionTable> found =
                actionRepository.findById(action.getActionId());
        assertTrue(found.isPresent(), "Action should be findable after creation");
    }

    @Test
    @DisplayName("Transaction lookup should work with case-insensitive ID")
    void testTransactionLookupCaseInsensitive() {
        // Given - Create transaction with lowercase ID
        final TransactionTable transaction =
                transactionService.createTransaction(
                        testUser,
                        testAccount.getAccountId(),
                        BigDecimal.valueOf(100.00),
                        LocalDate.now(),
                        "Test Transaction",
                        "FOOD");

        final String savedId = transaction.getTransactionId();
        assertTrue(
                savedId.equals(savedId.toLowerCase(Locale.ROOT)), "Saved ID should be lowercase");

        // When - Look up with uppercase version
        final String uppercaseId = savedId.toUpperCase(Locale.ROOT);
        final Optional<TransactionTable> found = transactionRepository.findById(uppercaseId);

        // Then - Should find the transaction (normalized in lookup)
        assertTrue(found.isPresent(), "Transaction should be findable with uppercase ID");
        assertEquals(savedId, found.get().getTransactionId());
    }
}
