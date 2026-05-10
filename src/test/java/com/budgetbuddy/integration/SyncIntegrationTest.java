package com.budgetbuddy.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.TransactionActionTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.TransactionActionRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.repository.dynamodb.UserRepository;
import com.budgetbuddy.service.PlaidSyncService;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * Integration Tests for Sync Functionality Tests transaction sync, account sync, action sync, and
 * notes sync
 */
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SyncIntegrationTest {

    @Autowired private PlaidSyncService plaidSyncService;

    @Autowired private TransactionService transactionService;

    @Autowired private TransactionActionService actionService;

    @Autowired private AccountRepository accountRepository;

    @Autowired private TransactionRepository transactionRepository;

    @Autowired private TransactionActionRepository actionRepository;

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
        final String email = "sync-test-" + UUID.randomUUID() + "@example.com";
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
    void testTransactionSyncPreservesExistingTransactions() {
        // Given - Existing transaction with notes
        testTransaction.setNotes("Important note");
        transactionRepository.save(testTransaction);

        // When - Create another transaction (simulating sync)
        final TransactionTable newTransaction =
                transactionService.createTransaction(
                        testUser,
                        testAccount.getAccountId(),
                        BigDecimal.valueOf(200.00),
                        LocalDate.now(),
                        "New transaction",
                        "TRANSPORTATION");

        // Then - Both transactions should exist
        final List<TransactionTable> allTransactions =
                transactionRepository.findByUserId(testUser.getUserId(), 0, 100);
        assertEquals(2, allTransactions.size());

        // Verify original transaction notes are preserved
        final TransactionTable original =
                allTransactions.stream()
                        .filter(
                                t ->
                                        t.getTransactionId()
                                                .equals(testTransaction.getTransactionId()))
                        .findFirst()
                        .orElse(null);
        assertNotNull(original);
        assertEquals("Important note", original.getNotes());
    }

    @Test
    void testActionSyncCreatesAndRetrievesActions() {
        // Given - Create action
        final TransactionActionTable action =
                actionService.createAction(
                        testUser,
                        testTransaction.getTransactionId(),
                        "Review transaction",
                        "Check if correct",
                        null,
                        null,
                        "HIGH");

        // When - Fetch actions for transaction
        final List<TransactionActionTable> actions =
                actionService.getActionsByTransactionId(
                        testUser, testTransaction.getTransactionId());

        // Then
        assertEquals(1, actions.size());
        assertEquals("Review transaction", actions.get(0).getTitle());
        assertEquals("HIGH", actions.get(0).getPriority());
    }

    @Test
    void testActionSyncMultipleActionsForTransaction() {
        // Given - Create multiple actions
        actionService.createAction(
                testUser, testTransaction.getTransactionId(), "Action 1", null, null, null, "LOW");
        actionService.createAction(
                testUser, testTransaction.getTransactionId(), "Action 2", null, null, null, "HIGH");

        // When
        final List<TransactionActionTable> actions =
                actionService.getActionsByTransactionId(
                        testUser, testTransaction.getTransactionId());

        // Then
        assertEquals(2, actions.size());
        assertTrue(actions.stream().anyMatch(a -> "Action 1".equals(a.getTitle())));
        assertTrue(actions.stream().anyMatch(a -> "Action 2".equals(a.getTitle())));
    }

    @Test
    void testActionSyncUserActionsAcrossTransactions() {
        // Given - Create second transaction
        final TransactionTable transaction2 =
                transactionService.createTransaction(
                        testUser,
                        testAccount.getAccountId(),
                        BigDecimal.valueOf(300.00),
                        LocalDate.now(),
                        "Second transaction",
                        "ENTERTAINMENT");

        // Create actions for both transactions
        actionService.createAction(
                testUser,
                testTransaction.getTransactionId(),
                "Action for TX1",
                null,
                null,
                null,
                "MEDIUM");
        actionService.createAction(
                testUser,
                transaction2.getTransactionId(),
                "Action for TX2",
                null,
                null,
                null,
                "MEDIUM");

        // When - Fetch all user actions
        final List<TransactionActionTable> allActions = actionService.getActionsByUserId(testUser);

        // Then
        assertEquals(2, allActions.size());
        assertTrue(allActions.stream().anyMatch(a -> "Action for TX1".equals(a.getTitle())));
        assertTrue(allActions.stream().anyMatch(a -> "Action for TX2".equals(a.getTitle())));
    }

    @Test
    void testTransactionNotesSyncUpdateAndRetrieve() {
        // Given
        final String notes = "Updated notes after sync";

        // When - Update transaction notes
        testTransaction.setNotes(notes);
        testTransaction.setUpdatedAt(Instant.now());
        transactionRepository.save(testTransaction);

        // Then - Verify notes are saved
        final TransactionTable retrieved =
                transactionRepository.findById(testTransaction.getTransactionId()).orElse(null);
        assertNotNull(retrieved);
        assertEquals(notes, retrieved.getNotes());
    }

    @Test
    void testSyncMultipleTransactionsWithActions() {
        // Given - Create multiple transactions
        final TransactionTable tx1 =
                transactionService.createTransaction(
                        testUser,
                        testAccount.getAccountId(),
                        BigDecimal.valueOf(100.00),
                        LocalDate.now(),
                        "Transaction 1",
                        "FOOD");
        final TransactionTable tx2 =
                transactionService.createTransaction(
                        testUser,
                        testAccount.getAccountId(),
                        BigDecimal.valueOf(200.00),
                        LocalDate.now(),
                        "Transaction 2",
                        "TRANSPORTATION");

        // Create actions for each
        actionService.createAction(
                testUser, tx1.getTransactionId(), "Action 1", null, null, null, "LOW");
        actionService.createAction(
                testUser, tx2.getTransactionId(), "Action 2", null, null, null, "HIGH");

        // When - Fetch all data
        final List<TransactionTable> transactions =
                transactionRepository.findByUserId(testUser.getUserId(), 0, 100);
        final List<TransactionActionTable> allActions = actionService.getActionsByUserId(testUser);

        // Then
        assertTrue(transactions.size() >= 2);
        assertEquals(2, allActions.size());

        // Verify actions are linked to correct transactions
        final List<TransactionActionTable> tx1Actions =
                actionService.getActionsByTransactionId(testUser, tx1.getTransactionId());
        final List<TransactionActionTable> tx2Actions =
                actionService.getActionsByTransactionId(testUser, tx2.getTransactionId());

        assertEquals(1, tx1Actions.size());
        assertEquals(1, tx2Actions.size());
    }

    @Test
    void testSyncActionUpdatePreservesTransactionLink() {
        // Given - Create action
        final TransactionActionTable action =
                actionService.createAction(
                        testUser,
                        testTransaction.getTransactionId(),
                        "Original Title",
                        null,
                        null,
                        null,
                        "LOW");

        // When - Update action
        final TransactionActionTable updated =
                actionService.updateAction(
                        testUser,
                        action.getActionId(),
                        "Updated Title",
                        null,
                        null,
                        null,
                        true,
                        "HIGH",
                        null);

        // Then - Verify transaction link is preserved
        assertEquals(testTransaction.getTransactionId(), updated.getTransactionId());
        assertEquals("Updated Title", updated.getTitle());
        assertTrue(updated.getIsCompleted());
        assertEquals("HIGH", updated.getPriority());
    }

    @Test
    void testSyncDeleteActionDoesNotAffectTransaction() {
        // Given - Create action
        final TransactionActionTable action =
                actionService.createAction(
                        testUser,
                        testTransaction.getTransactionId(),
                        "Action to Delete",
                        null,
                        null,
                        null,
                        "MEDIUM");

        // When - Delete action
        actionService.deleteAction(testUser, action.getActionId());

        // Then - Transaction should still exist
        final TransactionTable retrieved =
                transactionRepository.findById(testTransaction.getTransactionId()).orElse(null);
        assertNotNull(retrieved);

        // Action should be deleted
        final List<TransactionActionTable> actions =
                actionService.getActionsByTransactionId(
                        testUser, testTransaction.getTransactionId());
        assertEquals(0, actions.size());
    }
}
