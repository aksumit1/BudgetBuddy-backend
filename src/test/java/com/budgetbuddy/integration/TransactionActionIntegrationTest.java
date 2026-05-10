package com.budgetbuddy.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.TransactionActionTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.TransactionActionRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.repository.dynamodb.UserRepository;
import com.budgetbuddy.service.TransactionActionService;
import com.budgetbuddy.service.TransactionService;
import com.budgetbuddy.util.TableInitializer;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/** Integration Tests for Transaction Action Service Tests with real DynamoDB (LocalStack) */
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TransactionActionIntegrationTest {

    @Autowired private TransactionActionService actionService;

    @Autowired private TransactionActionRepository actionRepository;

    @Autowired private TransactionService transactionService;

    @Autowired private TransactionRepository transactionRepository;

    @Autowired private AccountRepository accountRepository;

    @Autowired private UserRepository userRepository;

    @Autowired private DynamoDbClient dynamoDbClient;

    private UserTable testUser;
    private AccountTable testAccount;
    private TransactionTable testTransaction;

    @BeforeAll
    void ensureTablesInitialized() {
        // CRITICAL: Use global synchronized method to ensure tables are initialized
        // This prevents race conditions when tests run in parallel
        TableInitializer.ensureTablesInitializedAndVerified(dynamoDbClient);
    }

    @BeforeEach
    void setUp() {
        // Create test user
        final String email = "test-" + UUID.randomUUID() + "@example.com";
        testUser = new UserTable();
        testUser.setUserId(UUID.randomUUID().toString());
        testUser.setEmail(email);
        testUser.setPreferredCurrency("USD");
        userRepository.save(testUser);

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
        testTransaction =
                transactionService.createTransaction(
                        testUser,
                        testAccount.getAccountId(),
                        BigDecimal.valueOf(100.00),
                        LocalDate.now(),
                        "Test transaction",
                        "FOOD");
    }

    @Test
    void testCreateAndRetrieveAction() {
        // Given
        final TransactionActionTable action =
                actionService.createAction(
                        testUser,
                        testTransaction.getTransactionId(),
                        "Review transaction",
                        "Check if this transaction is correct",
                        null,
                        null,
                        "HIGH");

        // When
        final List<TransactionActionTable> actions =
                actionService.getActionsByTransactionId(
                        testUser, testTransaction.getTransactionId());

        // Then
        assertNotNull(actions);
        assertTrue(actions.size() > 0);
        assertTrue(actions.stream().anyMatch(a -> a.getActionId().equals(action.getActionId())));
        assertEquals("Review transaction", action.getTitle());
        assertEquals("HIGH", action.getPriority());
        assertFalse(action.getIsCompleted());
    }

    @Test
    void testCreateActionWithDueDateAndReminder() {
        // Given
        final String dueDate = "2024-12-31";
        final String reminderDate = "2024-12-30T10:00:00Z";

        // When
        final TransactionActionTable action =
                actionService.createAction(
                        testUser,
                        testTransaction.getTransactionId(),
                        "Action with due date",
                        "Description",
                        dueDate,
                        reminderDate,
                        "MEDIUM");

        // Then
        assertNotNull(action);
        assertEquals(dueDate, action.getDueDate());
        assertEquals(reminderDate, action.getReminderDate());
    }

    @Test
    void testUpdateActionCompletesAction() {
        // Given
        final TransactionActionTable action =
                actionService.createAction(
                        testUser,
                        testTransaction.getTransactionId(),
                        "Action to complete",
                        null,
                        null,
                        null,
                        "LOW");

        // When
        final TransactionActionTable updated =
                actionService.updateAction(
                        testUser, action.getActionId(), null, null, null, null, true, null, null);

        // Then
        assertTrue(updated.getIsCompleted());
        assertEquals(action.getActionId(), updated.getActionId());
    }

    @Test
    void testUpdateActionChangesTitleAndPriority() {
        // Given
        final TransactionActionTable action =
                actionService.createAction(
                        testUser,
                        testTransaction.getTransactionId(),
                        "Original title",
                        null,
                        null,
                        null,
                        "LOW");

        // When
        final TransactionActionTable updated =
                actionService.updateAction(
                        testUser,
                        action.getActionId(),
                        "Updated title",
                        null,
                        null,
                        null,
                        null,
                        "HIGH",
                        null);

        // Then
        assertEquals("Updated title", updated.getTitle());
        assertEquals("HIGH", updated.getPriority());
    }

    @Test
    void testDeleteActionRemovesAction() {
        // Given
        final TransactionActionTable action =
                actionService.createAction(
                        testUser,
                        testTransaction.getTransactionId(),
                        "Action to delete",
                        null,
                        null,
                        null,
                        "MEDIUM");

        // When
        actionService.deleteAction(testUser, action.getActionId());

        // Then
        final List<TransactionActionTable> actions =
                actionService.getActionsByTransactionId(
                        testUser, testTransaction.getTransactionId());
        assertFalse(actions.stream().anyMatch(a -> a.getActionId().equals(action.getActionId())));
    }

    @Test
    void testGetActionsByTransactionIdReturnsOnlyActionsForTransaction() {
        // Given
        final TransactionTable transaction2 =
                transactionService.createTransaction(
                        testUser,
                        testAccount.getAccountId(),
                        BigDecimal.valueOf(200.00),
                        LocalDate.now(),
                        "Second transaction",
                        "TRANSPORTATION");

        final TransactionActionTable action1 =
                actionService.createAction(
                        testUser,
                        testTransaction.getTransactionId(),
                        "Action 1",
                        null,
                        null,
                        null,
                        "MEDIUM");

        final TransactionActionTable action2 =
                actionService.createAction(
                        testUser,
                        transaction2.getTransactionId(),
                        "Action 2",
                        null,
                        null,
                        null,
                        "MEDIUM");

        // When
        final List<TransactionActionTable> tx1Actions =
                actionService.getActionsByTransactionId(
                        testUser, testTransaction.getTransactionId());
        final List<TransactionActionTable> tx2Actions =
                actionService.getActionsByTransactionId(testUser, transaction2.getTransactionId());

        // Then
        assertEquals(1, tx1Actions.size());
        assertEquals(action1.getActionId(), tx1Actions.get(0).getActionId());
        assertEquals(1, tx2Actions.size());
        assertEquals(action2.getActionId(), tx2Actions.get(0).getActionId());
    }

    @Test
    void testGetActionsByUserIdReturnsAllUserActions() {
        // Given
        final TransactionTable transaction2 =
                transactionService.createTransaction(
                        testUser,
                        testAccount.getAccountId(),
                        BigDecimal.valueOf(200.00),
                        LocalDate.now(),
                        "Second transaction",
                        "TRANSPORTATION");

        actionService.createAction(
                testUser,
                testTransaction.getTransactionId(),
                "Action 1",
                null,
                null,
                null,
                "MEDIUM");

        actionService.createAction(
                testUser, transaction2.getTransactionId(), "Action 2", null, null, null, "MEDIUM");

        // When
        final List<TransactionActionTable> allActions = actionService.getActionsByUserId(testUser);

        // Then
        assertTrue(allActions.size() >= 2);
    }

    @Test
    void testCreateActionWithNonExistentTransactionThrowsException() {
        // When/Then
        assertThrows(
                AppException.class,
                () ->
                        actionService.createAction(
                                testUser,
                                UUID.randomUUID().toString(),
                                "Title",
                                null,
                                null,
                                null,
                                "MEDIUM"));
    }

    @Test
    void testCreateActionWithEmptyTitleThrowsException() {
        // When/Then
        assertThrows(
                AppException.class,
                () ->
                        actionService.createAction(
                                testUser,
                                testTransaction.getTransactionId(),
                                "",
                                null,
                                null,
                                null,
                                null));
    }

    @Test
    void testUpdateActionWithNonExistentActionThrowsException() {
        // When/Then
        assertThrows(
                AppException.class,
                () ->
                        actionService.updateAction(
                                testUser,
                                UUID.randomUUID().toString(),
                                "Title",
                                null,
                                null,
                                null,
                                null,
                                null,
                                null));
    }

    @Test
    void testDeleteActionWithNonExistentActionThrowsException() {
        // When/Then
        assertThrows(
                AppException.class,
                () -> actionService.deleteAction(testUser, UUID.randomUUID().toString()));
    }
}
