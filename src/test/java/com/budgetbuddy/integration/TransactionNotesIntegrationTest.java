package com.budgetbuddy.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.repository.dynamodb.UserRepository;
import com.budgetbuddy.service.TransactionService;
import com.budgetbuddy.util.TableInitializer;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
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

/** Integration Tests for Transaction Notes Tests notes creation, update, and failover scenarios */
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TransactionNotesIntegrationTest {

    @Autowired private TransactionService transactionService;

    @Autowired private TransactionRepository transactionRepository;

    @Autowired private UserRepository userRepository;

    @Autowired private AccountRepository accountRepository;

    @Autowired private DynamoDbClient dynamoDbClient;

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
        testUser = new UserTable();
        testUser.setUserId(UUID.randomUUID().toString());
        testUser.setEmail("test-notes-" + UUID.randomUUID() + "@example.com");
        testUser.setPasswordHash("hashed-password");
        testUser.setPreferredCurrency("USD");
        userRepository.save(testUser);

        // Create test account
        testAccount = new AccountTable();
        testAccount.setAccountId(UUID.randomUUID().toString());
        testAccount.setUserId(testUser.getUserId());
        testAccount.setAccountName("Test Account");
        testAccount.setBalance(BigDecimal.valueOf(1000.00));
        testAccount.setCurrencyCode("USD");
        testAccount.setActive(true);
        accountRepository.save(testAccount);
    }

    @Test
    void testCreateTransactionWithNotesSavesNotes() {
        // Given
        final String transactionId = UUID.randomUUID().toString();
        final String notes = "Important transaction note";

        // When
        final TransactionTable transaction =
                transactionService.createTransaction(
                        testUser,
                        testAccount.getAccountId(),
                        BigDecimal.valueOf(-50.00),
                        LocalDate.now(),
                        "Test Transaction",
                        "dining",
                        "dining",
                        transactionId,
                        notes);

        // Then
        assertNotNull(transaction);
        assertEquals(transactionId, transaction.getTransactionId());
        assertEquals(notes, transaction.getNotes());

        // Verify in repository
        final Optional<TransactionTable> saved = transactionRepository.findById(transactionId);
        assertTrue(saved.isPresent());
        assertEquals(notes, saved.get().getNotes());
    }

    @Test
    void testCreateTransactionWithEmptyNotesSavesNull() {
        // Given
        final String transactionId = UUID.randomUUID().toString();

        // When
        final TransactionTable transaction =
                transactionService.createTransaction(
                        testUser,
                        testAccount.getAccountId(),
                        BigDecimal.valueOf(-50.00),
                        LocalDate.now(),
                        "Test Transaction",
                        "dining",
                        "dining",
                        transactionId,
                        "" // Empty notes
                );

        // Then
        assertNotNull(transaction);
        assertNull(transaction.getNotes());

        // Verify in repository
        final Optional<TransactionTable> saved = transactionRepository.findById(transactionId);
        assertTrue(saved.isPresent());
        assertNull(saved.get().getNotes());
    }

    @Test
    void testCreateTransactionWithNullNotesSavesNull() {
        // Given
        final String transactionId = UUID.randomUUID().toString();

        // When
        final TransactionTable transaction =
                transactionService.createTransaction(
                        testUser,
                        testAccount.getAccountId(),
                        BigDecimal.valueOf(-50.00),
                        LocalDate.now(),
                        "Test Transaction",
                        "dining",
                        "dining",
                        transactionId,
                        null // Null notes
                );

        // Then
        assertNotNull(transaction);
        assertNull(transaction.getNotes());
    }

    @Test
    void testUpdateTransactionWithNotesUpdatesNotes() {
        // Given - Create transaction without notes
        final String transactionId = UUID.randomUUID().toString();
        final TransactionTable transaction =
                transactionService.createTransaction(
                        testUser,
                        testAccount.getAccountId(),
                        BigDecimal.valueOf(-50.00),
                        LocalDate.now(),
                        "Test Transaction",
                        "dining",
                        "dining",
                        transactionId,
                        null);
        assertNull(transaction.getNotes());

        // When - Update with notes
        final String notes = "Updated note";
        final TransactionTable updated =
                transactionService.updateTransaction(testUser, transactionId, notes);

        // Then
        assertNotNull(updated);
        assertEquals(notes, updated.getNotes());

        // Verify in repository
        final Optional<TransactionTable> saved = transactionRepository.findById(transactionId);
        assertTrue(saved.isPresent());
        assertEquals(notes, saved.get().getNotes());
    }

    @Test
    void testUpdateTransactionWithEmptyNotesClearsNotes() {
        // Given - Create transaction with notes
        final String transactionId = UUID.randomUUID().toString();
        final TransactionTable transaction =
                transactionService.createTransaction(
                        testUser,
                        testAccount.getAccountId(),
                        BigDecimal.valueOf(-50.00),
                        LocalDate.now(),
                        "Test Transaction",
                        "dining",
                        "dining",
                        transactionId,
                        "Original note");
        assertEquals("Original note", transaction.getNotes());

        // When - Update with empty notes
        final TransactionTable updated =
                transactionService.updateTransaction(testUser, transactionId, "");

        // Then
        assertNotNull(updated);
        assertNull(updated.getNotes());

        // Verify in repository
        final Optional<TransactionTable> saved = transactionRepository.findById(transactionId);
        assertTrue(saved.isPresent());
        assertNull(saved.get().getNotes());
    }

    @Test
    void testUpdateTransactionWithNullNotesClearsNotes() {
        // Given - Create transaction with notes
        final String transactionId = UUID.randomUUID().toString();
        final TransactionTable transaction =
                transactionService.createTransaction(
                        testUser,
                        testAccount.getAccountId(),
                        BigDecimal.valueOf(-50.00),
                        LocalDate.now(),
                        "Test Transaction",
                        "dining",
                        "dining",
                        transactionId,
                        "Original note");
        assertEquals("Original note", transaction.getNotes());

        // When - Update with null notes
        final TransactionTable updated =
                transactionService.updateTransaction(testUser, transactionId, null);

        // Then
        assertNotNull(updated);
        assertNull(updated.getNotes());
    }

    @Test
    void testCreateTransactionWithNotesThenUpdateNotesUpdatesCorrectly() {
        // Given - Create transaction with notes
        final String transactionId = UUID.randomUUID().toString();
        final String originalNotes = "Original note";
        final TransactionTable transaction =
                transactionService.createTransaction(
                        testUser,
                        testAccount.getAccountId(),
                        BigDecimal.valueOf(-50.00),
                        LocalDate.now(),
                        "Test Transaction",
                        "dining",
                        "dining",
                        transactionId,
                        originalNotes);
        assertEquals(originalNotes, transaction.getNotes());

        // When - Update notes
        final String updatedNotes = "Updated note";
        final TransactionTable updated =
                transactionService.updateTransaction(testUser, transactionId, updatedNotes);

        // Then
        assertEquals(updatedNotes, updated.getNotes());

        // Verify in repository
        final Optional<TransactionTable> saved = transactionRepository.findById(transactionId);
        assertTrue(saved.isPresent());
        assertEquals(updatedNotes, saved.get().getNotes());
    }

    @Test
    void testCreateTransactionWithWhitespaceNotesTrimsAndSaves() {
        // Given
        final String transactionId = UUID.randomUUID().toString();
        final String notesWithWhitespace = "   Important note   ";

        // When
        final TransactionTable transaction =
                transactionService.createTransaction(
                        testUser,
                        testAccount.getAccountId(),
                        BigDecimal.valueOf(-50.00),
                        LocalDate.now(),
                        "Test Transaction",
                        "dining",
                        "dining",
                        transactionId,
                        notesWithWhitespace);

        // Then - Notes should be trimmed
        assertNotNull(transaction);
        assertEquals("Important note", transaction.getNotes());
    }

    @Test
    void testCreateTransactionWithOnlyWhitespaceNotesSavesNull() {
        // Given
        final String transactionId = UUID.randomUUID().toString();
        final String whitespaceOnly = "   ";

        // When
        final TransactionTable transaction =
                transactionService.createTransaction(
                        testUser,
                        testAccount.getAccountId(),
                        BigDecimal.valueOf(-50.00),
                        LocalDate.now(),
                        "Test Transaction",
                        "dining",
                        "dining",
                        transactionId,
                        whitespaceOnly);

        // Then - Empty/whitespace notes should be saved as null
        assertNotNull(transaction);
        assertNull(transaction.getNotes());
    }
}
