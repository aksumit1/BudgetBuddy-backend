package com.budgetbuddy.integration;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration Tests for Sync Functionality
 * Tests transaction sync, account sync, action sync, and notes sync
 */
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
class SyncIntegrationTest {

    @Autowired
    private PlaidSyncService plaidSyncService;

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private TransactionActionService actionService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private TransactionActionRepository actionRepository;

    @Autowired
    private UserRepository userRepository;

    private UserTable testUser;
    private AccountTable testAccount;
    private TransactionTable testTransaction;

    @BeforeEach
    void setUp() {
        // Create test user
        String email = "sync-test-" + UUID.randomUUID() + "@example.com";
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
        testTransaction = transactionService.createTransaction(
                testUser,
                testAccount.getAccountId(),
                BigDecimal.valueOf(100.00),
                LocalDate.now(),
                "Test transaction",
                "FOOD"
        );
    }

    @Test
    void testTransactionSync_PreservesExistingTransactions() {
        // Given - Existing transaction with notes
        testTransaction.setNotes("Important note");
        transactionRepository.save(testTransaction);

        // When - Create another transaction (simulating sync)
        TransactionTable newTransaction = transactionService.createTransaction(
                testUser,
                testAccount.getAccountId(),
                BigDecimal.valueOf(200.00),
                LocalDate.now(),
                "New transaction",
                "TRANSPORTATION"
        );

        // Then - Both transactions should exist
        List<TransactionTable> allTransactions = transactionRepository.findByUserId(
                testUser.getUserId(), 0, 100);
        assertEquals(2, allTransactions.size());
        
        // Verify original transaction notes are preserved
        TransactionTable original = allTransactions.stream()
                .filter(t -> t.getTransactionId().equals(testTransaction.getTransactionId()))
                .findFirst()
                .orElse(null);
        assertNotNull(original);
        assertEquals("Important note", original.getNotes());
    }

    @Test
    void testActionSync_CreatesAndRetrievesActions() {
        // Given - Create action
        TransactionActionTable action = actionService.createAction(
                testUser,
                testTransaction.getTransactionId(),
                "Review transaction",
                "Check if correct",
                null,
                null,
                "HIGH"
        );

        // When - Fetch actions for transaction
        List<TransactionActionTable> actions = actionService.getActionsByTransactionId(
                testUser, testTransaction.getTransactionId());

        // Then
        assertEquals(1, actions.size());
        assertEquals("Review transaction", actions.get(0).getTitle());
        assertEquals("HIGH", actions.get(0).getPriority());
    }

    @Test
    void testActionSync_MultipleActionsForTransaction() {
        // Given - Create multiple actions
        actionService.createAction(
                testUser,
                testTransaction.getTransactionId(),
                "Action 1",
                null,
                null,
                null,
                "LOW"
        );
        actionService.createAction(
                testUser,
                testTransaction.getTransactionId(),
                "Action 2",
                null,
                null,
                null,
                "HIGH"
        );

        // When
        List<TransactionActionTable> actions = actionService.getActionsByTransactionId(
                testUser, testTransaction.getTransactionId());

        // Then
        assertEquals(2, actions.size());
        assertTrue(actions.stream().anyMatch(a -> a.getTitle().equals("Action 1")));
        assertTrue(actions.stream().anyMatch(a -> a.getTitle().equals("Action 2")));
    }

    @Test
    void testActionSync_UserActionsAcrossTransactions() {
        // Given - Create second transaction
        TransactionTable transaction2 = transactionService.createTransaction(
                testUser,
                testAccount.getAccountId(),
                BigDecimal.valueOf(300.00),
                LocalDate.now(),
                "Second transaction",
                "ENTERTAINMENT"
        );

        // Create actions for both transactions
        actionService.createAction(
                testUser,
                testTransaction.getTransactionId(),
                "Action for TX1",
                null,
                null,
                null,
                "MEDIUM"
        );
        actionService.createAction(
                testUser,
                transaction2.getTransactionId(),
                "Action for TX2",
                null,
                null,
                null,
                "MEDIUM"
        );

        // When - Fetch all user actions
        List<TransactionActionTable> allActions = actionService.getActionsByUserId(testUser);

        // Then
        assertEquals(2, allActions.size());
        assertTrue(allActions.stream().anyMatch(a -> a.getTitle().equals("Action for TX1")));
        assertTrue(allActions.stream().anyMatch(a -> a.getTitle().equals("Action for TX2")));
    }

    @Test
    void testTransactionNotesSync_UpdateAndRetrieve() {
        // Given
        String notes = "Updated notes after sync";

        // When - Update transaction notes
        testTransaction.setNotes(notes);
        testTransaction.setUpdatedAt(Instant.now());
        transactionRepository.save(testTransaction);

        // Then - Verify notes are saved
        TransactionTable retrieved = transactionRepository.findById(
                testTransaction.getTransactionId()).orElse(null);
        assertNotNull(retrieved);
        assertEquals(notes, retrieved.getNotes());
    }

    @Test
    void testSync_MultipleTransactionsWithActions() {
        // Given - Create multiple transactions
        TransactionTable tx1 = transactionService.createTransaction(
                testUser,
                testAccount.getAccountId(),
                BigDecimal.valueOf(100.00),
                LocalDate.now(),
                "Transaction 1",
                "FOOD"
        );
        TransactionTable tx2 = transactionService.createTransaction(
                testUser,
                testAccount.getAccountId(),
                BigDecimal.valueOf(200.00),
                LocalDate.now(),
                "Transaction 2",
                "TRANSPORTATION"
        );

        // Create actions for each
        actionService.createAction(
                testUser,
                tx1.getTransactionId(),
                "Action 1",
                null,
                null,
                null,
                "LOW"
        );
        actionService.createAction(
                testUser,
                tx2.getTransactionId(),
                "Action 2",
                null,
                null,
                null,
                "HIGH"
        );

        // When - Fetch all data
        List<TransactionTable> transactions = transactionRepository.findByUserId(
                testUser.getUserId(), 0, 100);
        List<TransactionActionTable> allActions = actionService.getActionsByUserId(testUser);

        // Then
        assertTrue(transactions.size() >= 2);
        assertEquals(2, allActions.size());
        
        // Verify actions are linked to correct transactions
        List<TransactionActionTable> tx1Actions = actionService.getActionsByTransactionId(
                testUser, tx1.getTransactionId());
        List<TransactionActionTable> tx2Actions = actionService.getActionsByTransactionId(
                testUser, tx2.getTransactionId());
        
        assertEquals(1, tx1Actions.size());
        assertEquals(1, tx2Actions.size());
    }

    @Test
    void testSync_ActionUpdatePreservesTransactionLink() {
        // Given - Create action
        TransactionActionTable action = actionService.createAction(
                testUser,
                testTransaction.getTransactionId(),
                "Original Title",
                null,
                null,
                null,
                "LOW"
        );

        // When - Update action
        TransactionActionTable updated = actionService.updateAction(
                testUser,
                action.getActionId(),
                "Updated Title",
                null,
                null,
                null,
                true,
                "HIGH"
        );

        // Then - Verify transaction link is preserved
        assertEquals(testTransaction.getTransactionId(), updated.getTransactionId());
        assertEquals("Updated Title", updated.getTitle());
        assertTrue(updated.getIsCompleted());
        assertEquals("HIGH", updated.getPriority());
    }

    @Test
    void testSync_DeleteActionDoesNotAffectTransaction() {
        // Given - Create action
        TransactionActionTable action = actionService.createAction(
                testUser,
                testTransaction.getTransactionId(),
                "Action to Delete",
                null,
                null,
                null,
                "MEDIUM"
        );

        // When - Delete action
        actionService.deleteAction(testUser, action.getActionId());

        // Then - Transaction should still exist
        TransactionTable retrieved = transactionRepository.findById(
                testTransaction.getTransactionId()).orElse(null);
        assertNotNull(retrieved);
        
        // Action should be deleted
        List<TransactionActionTable> actions = actionService.getActionsByTransactionId(
                testUser, testTransaction.getTransactionId());
        assertEquals(0, actions.size());
    }
}

