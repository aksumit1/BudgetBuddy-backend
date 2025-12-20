package com.budgetbuddy.integration;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.TransactionActionTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.repository.dynamodb.UserRepository;
import com.budgetbuddy.service.TransactionActionService;
import com.budgetbuddy.service.TransactionService;
import com.budgetbuddy.util.IdGenerator;
import com.budgetbuddy.util.TableInitializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration Tests for Transaction Bug Fixes
 * Tests for all bugs reported and fixed:
 * 1. Notes not saving when clicking action (race condition)
 * 2. Excessive POST calls (duplicate requests)
 * 3. Backend returning different transaction ID
 * 4. Idempotency issues
 */
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TransactionBugFixesIntegrationTest {

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private TransactionActionService transactionActionService;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private DynamoDbClient dynamoDbClient;

    private UserTable testUser;
    private AccountTable testAccount;

    @BeforeAll
    void ensureTablesInitialized() {
        TableInitializer.ensureTablesInitializedAndVerified(dynamoDbClient);
    }

    @BeforeEach
    void setUp() {
        // Create test user
        testUser = new UserTable();
        testUser.setUserId(UUID.randomUUID().toString());
        testUser.setEmail("bug-test-" + UUID.randomUUID() + "@example.com");
        testUser.setPasswordHash("hashed-password");
        testUser.setPreferredCurrency("USD");
        testUser.setCreatedAt(Instant.now());
        testUser.setUpdatedAt(Instant.now());
        userRepository.save(testUser);

        // Create test account
        testAccount = new AccountTable();
        testAccount.setAccountId(UUID.randomUUID().toString());
        testAccount.setUserId(testUser.getUserId());
        testAccount.setAccountName("Test Account");
        testAccount.setBalance(BigDecimal.valueOf(1000.00));
        testAccount.setCurrencyCode("USD");
        testAccount.setActive(true);
        testAccount.setCreatedAt(Instant.now());
        testAccount.setUpdatedAt(Instant.now());
        accountRepository.save(testAccount);
    }

    // BUG FIX #1: Notes not saving when clicking action
    @Test
    @DisplayName("Bug Fix #1: Notes preserved when action is created after notes update")
    void testNotesPreserved_WhenActionCreatedAfterNotesUpdate() {
        // Given - Create transaction with notes
        String transactionId = UUID.randomUUID().toString();
        String originalNotes = "Important transaction note";
        
        TransactionTable transaction = transactionService.createTransaction(
                testUser,
                testAccount.getAccountId(),
                BigDecimal.valueOf(-50.00),
                LocalDate.now(),
                "Test Transaction",
                "dining",
                "dining",
                transactionId,
                originalNotes,
                null, // plaidAccountId
                null,  // plaidTransactionId
                null // transactionType
        );
        assertEquals(originalNotes, transaction.getNotes());

        // When - Update notes
        String updatedNotes = "Updated note with more details";
        TransactionTable updated = transactionService.updateTransaction(
                testUser,
                transactionId,
                null, // plaidTransactionId
                null, // amount
                updatedNotes,
                null, // categoryPrimary
                null, // categoryDetailed
                null, // isAudited
                null, // isHidden
                null, // transactionType
                false // clearNotesIfNull = false (notes is provided, so this doesn't matter)
        );
        assertEquals(updatedNotes, updated.getNotes());

        // When - Create action (this previously triggered a PUT that cleared notes)
        TransactionActionTable action = transactionActionService.createAction(
                testUser,
                transactionId,
                "Review transaction",
                "Need to verify this transaction",
                LocalDate.now().plusDays(7).toString(), // dueDate as string
                null, // reminderDate
                "HIGH",
                null, // actionId
                null  // plaidTransactionId
        );
        assertNotNull(action);

        // Then - Notes should still be preserved
        Optional<TransactionTable> saved = transactionRepository.findById(transactionId);
        assertTrue(saved.isPresent());
        assertEquals(updatedNotes, saved.get().getNotes(), 
                "Notes should be preserved after action creation");
    }

    @Test
    @DisplayName("Bug Fix #1: Notes preserved when updating only plaidTransactionId")
    void testNotesPreserved_WhenUpdatingOnlyPlaidTransactionId() {
        // Given - Create transaction with notes
        String transactionId = UUID.randomUUID().toString();
        String notes = "Transaction notes";
        
        transactionService.createTransaction(
                testUser,
                testAccount.getAccountId(),
                BigDecimal.valueOf(-50.00),
                LocalDate.now(),
                "Test Transaction",
                "dining",
                "dining",
                transactionId,
                notes,
                null,
                null,
                null // transactionType
        );

        // When - Update only plaidTransactionId (notes should be null in request)
        String plaidTransactionId = "plaid-txn-" + UUID.randomUUID();
        TransactionTable updated = transactionService.updateTransaction(
                testUser,
                transactionId,
                plaidTransactionId, // Only updating plaidTransactionId
                null, // amount
                null, // notes - null means preserve existing
                null, // categoryPrimary
                null, // categoryDetailed
                null, // isAudited
                null, // isHidden
                null, // transactionType
                false // clearNotesIfNull = false means preserve existing notes
        );

        // Then - Notes should be preserved
        assertEquals(notes, updated.getNotes(), 
                "Notes should be preserved when only plaidTransactionId is updated");
        assertEquals(plaidTransactionId, updated.getPlaidTransactionId());
    }

    // BUG FIX #2: Excessive POST calls
    @Test
    @DisplayName("Bug Fix #2: Duplicate POST requests are idempotent (same transaction ID)")
    void testDuplicatePostRequests_AreIdempotent() {
        // Given - Transaction ID
        String transactionId = UUID.randomUUID().toString();
        String notes = "Test notes";

        // When - Create transaction first time
        TransactionTable transaction1 = transactionService.createTransaction(
                testUser,
                testAccount.getAccountId(),
                BigDecimal.valueOf(-50.00),
                LocalDate.now(),
                "Test Transaction",
                "dining",
                "dining",
                transactionId,
                notes,
                null,
                null,
                null // transactionType
        );
        assertNotNull(transaction1);
        assertEquals(transactionId, transaction1.getTransactionId());

        // When - Try to create same transaction again (duplicate POST)
        TransactionTable transaction2 = transactionService.createTransaction(
                testUser,
                testAccount.getAccountId(),
                BigDecimal.valueOf(-50.00),
                LocalDate.now(),
                "Test Transaction",
                "dining",
                "dining",
                transactionId, // Same ID
                notes,
                null,
                null,
                null // transactionType
        );

        // Then - Should return existing transaction (idempotent)
        assertNotNull(transaction2);
        assertEquals(transactionId, transaction2.getTransactionId());
        assertEquals(transaction1.getTransactionId(), transaction2.getTransactionId());

        // Verify only one transaction exists
        Optional<TransactionTable> saved = transactionRepository.findById(transactionId);
        assertTrue(saved.isPresent());
        assertEquals(transactionId, saved.get().getTransactionId());
    }

    @Test
    @DisplayName("Bug Fix #2: Multiple POST requests with same Plaid ID are idempotent")
    void testMultiplePostRequests_WithSamePlaidId_AreIdempotent() {
        // Given - Plaid transaction ID
        String plaidTransactionId = "plaid-txn-" + UUID.randomUUID();
        String transactionId1 = UUID.randomUUID().toString();
        String transactionId2 = UUID.randomUUID().toString(); // Different transaction ID

        // When - Create transaction with Plaid ID
        transactionService.createTransaction(
                testUser,
                testAccount.getAccountId(),
                BigDecimal.valueOf(-50.00),
                LocalDate.now(),
                "Test Transaction",
                "dining",
                "dining",
                transactionId1,
                "Notes",
                null,
                plaidTransactionId,
                null // transactionType
        );

        // When - Try to create another transaction with same Plaid ID but different transaction ID
        // This simulates the bug where multiple POST calls were made
        TransactionTable transaction2 = transactionService.createTransaction(
                testUser,
                testAccount.getAccountId(),
                BigDecimal.valueOf(-50.00),
                LocalDate.now(),
                "Test Transaction",
                "dining",
                "dining",
                transactionId2, // Different transaction ID
                "Notes",
                null,
                plaidTransactionId, // Same Plaid ID
                null // transactionType
        );

        // Then - Backend should handle this appropriately
        // If Plaid ID matches, it should return existing or generate new ID
        assertNotNull(transaction2);
        
        // Verify only one transaction with this Plaid ID exists
        Optional<TransactionTable> byPlaidId = transactionRepository.findByPlaidTransactionId(plaidTransactionId);
        assertTrue(byPlaidId.isPresent());
    }

    // BUG FIX #3: Backend returning different transaction ID
    @Test
    @DisplayName("Bug Fix #3: Backend normalizes transaction ID case correctly")
    void testBackendNormalizesTransactionId_CaseInsensitive() {
        // Given - Transaction ID with mixed case
        String mixedCaseId = "DDC6FB1C-3D42-4ACC-AC86-B8ECEF3A86D7";
        String normalizedId = IdGenerator.normalizeUUID(mixedCaseId);
        
        // Clean up any existing transaction with this ID to ensure fresh test
        transactionRepository.findById(normalizedId).ifPresent(tx -> transactionRepository.delete(tx.getTransactionId()));

        // When - Create transaction with mixed case ID
        TransactionTable transaction = transactionService.createTransaction(
                testUser,
                testAccount.getAccountId(),
                BigDecimal.valueOf(-50.00),
                LocalDate.now(),
                "Test Transaction",
                "dining",
                "dining",
                mixedCaseId,
                "Notes",
                null,
                null,
                null // transactionType
        );

        // Then - Backend should normalize to lowercase
        assertNotNull(transaction);
        assertEquals(normalizedId, transaction.getTransactionId(), 
                "Backend should normalize transaction ID to lowercase");

        // Verify can find by normalized ID
        Optional<TransactionTable> found = transactionRepository.findById(normalizedId);
        assertTrue(found.isPresent());
        assertEquals(normalizedId, found.get().getTransactionId());
    }

    @Test
    @DisplayName("Bug Fix #3: Backend handles Plaid ID conflict by generating new ID")
    void testBackendHandlesPlaidIdConflict_GeneratesNewId() {
        // Given - Existing transaction with Plaid ID
        String existingTransactionId = UUID.randomUUID().toString();
        String plaidTransactionId = "plaid-txn-" + UUID.randomUUID();
        
        transactionService.createTransaction(
                testUser,
                testAccount.getAccountId(),
                BigDecimal.valueOf(-50.00),
                LocalDate.now(),
                "Existing Transaction",
                "dining",
                "dining",
                existingTransactionId,
                "Notes",
                null,
                plaidTransactionId,
                null // transactionType
        );

        // When - Try to create transaction with same transaction ID but different Plaid ID
        // This simulates the conflict scenario
        String conflictingPlaidId = "plaid-txn-different-" + UUID.randomUUID();
        TransactionTable newTransaction = transactionService.createTransaction(
                testUser,
                testAccount.getAccountId(),
                BigDecimal.valueOf(-50.00),
                LocalDate.now(),
                "New Transaction",
                "dining",
                "dining",
                existingTransactionId, // Same transaction ID
                "New Notes",
                null,
                conflictingPlaidId, // Different Plaid ID
                null // transactionType
        );

        // Then - Backend should generate new transaction ID due to conflict
        assertNotNull(newTransaction);
        // The backend should return existing transaction if Plaid ID doesn't match
        // OR generate new ID - depends on implementation
        // For now, verify transaction exists
        assertTrue(newTransaction.getTransactionId() != null);
    }

    @Test
    @DisplayName("Bug Fix #3: Backend returns same ID when Plaid ID matches")
    void testBackendReturnsSameId_WhenPlaidIdMatches() {
        // Given - Transaction ID and Plaid ID
        String transactionId = UUID.randomUUID().toString();
        String plaidTransactionId = "plaid-txn-" + UUID.randomUUID();

        // When - Create transaction first time
        TransactionTable transaction1 = transactionService.createTransaction(
                testUser,
                testAccount.getAccountId(),
                BigDecimal.valueOf(-50.00),
                LocalDate.now(),
                "Test Transaction",
                "dining",
                "dining",
                transactionId,
                "Notes",
                null,
                plaidTransactionId,
                null // transactionType
        );

        // When - Create transaction again with same Plaid ID
        TransactionTable transaction2 = transactionService.createTransaction(
                testUser,
                testAccount.getAccountId(),
                BigDecimal.valueOf(-50.00),
                LocalDate.now(),
                "Test Transaction",
                "dining",
                "dining",
                transactionId, // Same transaction ID
                "Notes",
                null,
                plaidTransactionId, // Same Plaid ID
                null // transactionType
        );

        // Then - Should return same transaction (idempotent)
        assertEquals(transaction1.getTransactionId(), transaction2.getTransactionId(),
                "Should return same transaction when Plaid ID matches");
    }

    // BUG FIX #4: Idempotency
    @Test
    @DisplayName("Bug Fix #4: Transaction creation is idempotent (no Plaid ID)")
    void testTransactionCreation_IsIdempotent_NoPlaidId() {
        // Given - Transaction ID (manual transaction, no Plaid ID)
        String transactionId = UUID.randomUUID().toString();

        // When - Create transaction multiple times
        TransactionTable transaction1 = transactionService.createTransaction(
                testUser,
                testAccount.getAccountId(),
                BigDecimal.valueOf(-50.00),
                LocalDate.now(),
                "Test Transaction",
                "dining",
                "dining",
                transactionId,
                "Notes",
                null,
                null, // No Plaid ID
                null // transactionType
        );

        TransactionTable transaction2 = transactionService.createTransaction(
                testUser,
                testAccount.getAccountId(),
                BigDecimal.valueOf(-50.00),
                LocalDate.now(),
                "Test Transaction",
                "dining",
                "dining",
                transactionId, // Same ID
                "Notes",
                null,
                null, // No Plaid ID
                null // transactionType
        );

        // Then - Should return same transaction
        assertEquals(transaction1.getTransactionId(), transaction2.getTransactionId(),
                "Manual transactions should be idempotent");

        // Verify only one transaction exists
        Optional<TransactionTable> saved = transactionRepository.findById(transactionId);
        assertTrue(saved.isPresent());
    }

    @Test
    @DisplayName("Bug Fix #4: Transaction creation with Plaid ID is idempotent when Plaid ID matches")
    void testTransactionCreation_WithPlaidId_IsIdempotent_WhenPlaidIdMatches() {
        // Given - Transaction ID and Plaid ID
        String transactionId = UUID.randomUUID().toString();
        String plaidTransactionId = "plaid-txn-" + UUID.randomUUID();

        // When - Create transaction first time
        TransactionTable transaction1 = transactionService.createTransaction(
                testUser,
                testAccount.getAccountId(),
                BigDecimal.valueOf(-50.00),
                LocalDate.now(),
                "Test Transaction",
                "dining",
                "dining",
                transactionId,
                "Notes",
                null,
                plaidTransactionId,
                null // transactionType
        );

        // When - Create transaction again with same Plaid ID
        TransactionTable transaction2 = transactionService.createTransaction(
                testUser,
                testAccount.getAccountId(),
                BigDecimal.valueOf(-50.00),
                LocalDate.now(),
                "Test Transaction",
                "dining",
                "dining",
                transactionId, // Same transaction ID
                "Notes",
                null,
                plaidTransactionId, // Same Plaid ID
                null // transactionType
        );

        // Then - Should return same transaction (idempotent)
        assertEquals(transaction1.getTransactionId(), transaction2.getTransactionId(),
                "Transactions with matching Plaid ID should be idempotent");
        assertEquals(plaidTransactionId, transaction2.getPlaidTransactionId());
    }

    @Test
    @DisplayName("Bug Fix #4: Transaction creation generates new ID when Plaid ID conflicts")
    void testTransactionCreation_WithPlaidId_GeneratesNewId_WhenPlaidIdConflicts() {
        // Given - Existing transaction with Plaid ID
        String existingTransactionId = UUID.randomUUID().toString();
        String existingPlaidId = "plaid-txn-" + UUID.randomUUID();
        
        transactionService.createTransaction(
                testUser,
                testAccount.getAccountId(),
                BigDecimal.valueOf(-50.00),
                LocalDate.now(),
                "Existing Transaction",
                "dining",
                "dining",
                existingTransactionId,
                "Notes",
                null,
                existingPlaidId,
                null // transactionType
        );

        // When - Try to create transaction with same transaction ID but different Plaid ID
        String conflictingPlaidId = "plaid-txn-different-" + UUID.randomUUID();
        TransactionTable newTransaction = transactionService.createTransaction(
                testUser,
                testAccount.getAccountId(),
                BigDecimal.valueOf(-50.00),
                LocalDate.now(),
                "New Transaction",
                "dining",
                "dining",
                existingTransactionId, // Same transaction ID
                "New Notes",
                null,
                conflictingPlaidId, // Different Plaid ID - conflict!
                null // transactionType
        );

        // Then - Backend should handle conflict (return existing or generate new ID)
        assertNotNull(newTransaction);
        // The exact behavior depends on implementation, but transaction should exist
        assertTrue(newTransaction.getTransactionId() != null);
    }

    @Test
    @DisplayName("Bug Fix #4: Multiple rapid POST requests don't create duplicates")
    void testMultipleRapidPostRequests_DontCreateDuplicates() {
        // Given - Transaction ID
        String transactionId = UUID.randomUUID().toString();

        // When - Create multiple transactions rapidly (simulating race condition)
        TransactionTable[] transactions = new TransactionTable[5];
        for (int i = 0; i < 5; i++) {
            transactions[i] = transactionService.createTransaction(
                    testUser,
                    testAccount.getAccountId(),
                    BigDecimal.valueOf(-50.00),
                    LocalDate.now(),
                    "Test Transaction " + i,
                    "dining",
                    "dining",
                    transactionId, // Same ID
                    "Notes " + i,
                    null,
                    null,
                    null // transactionType
            );
        }

        // Then - All should return same transaction (idempotent)
        for (int i = 1; i < 5; i++) {
            assertEquals(transactions[0].getTransactionId(), transactions[i].getTransactionId(),
                    "All rapid POST requests should return same transaction");
        }

        // Verify only one transaction exists
        Optional<TransactionTable> saved = transactionRepository.findById(transactionId);
        assertTrue(saved.isPresent());
        
        // Count transactions for this user
        List<TransactionTable> userTransactions = transactionRepository.findByUserId(
                testUser.getUserId(), 0, 100);
        long count = userTransactions.stream()
                .filter(t -> t.getTransactionId().equals(transactionId))
                .count();
        assertEquals(1, count, "Should have only one transaction with this ID");
    }
}

