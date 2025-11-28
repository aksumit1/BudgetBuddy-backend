package com.budgetbuddy.integration;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.repository.dynamodb.UserRepository;
import com.budgetbuddy.service.TransactionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration Tests for Transaction Notes
 * Tests notes creation, update, and failover scenarios
 */
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
class TransactionNotesIntegrationTest {

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    private UserTable testUser;
    private AccountTable testAccount;

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
    void testCreateTransaction_WithNotes_SavesNotes() {
        // Given
        String transactionId = UUID.randomUUID().toString();
        String notes = "Important transaction note";

        // When
        TransactionTable transaction = transactionService.createTransaction(
                testUser,
                testAccount.getAccountId(),
                BigDecimal.valueOf(-50.00),
                LocalDate.now(),
                "Test Transaction",
                "FOOD",
                transactionId,
                notes
        );

        // Then
        assertNotNull(transaction);
        assertEquals(transactionId, transaction.getTransactionId());
        assertEquals(notes, transaction.getNotes());

        // Verify in repository
        Optional<TransactionTable> saved = transactionRepository.findById(transactionId);
        assertTrue(saved.isPresent());
        assertEquals(notes, saved.get().getNotes());
    }

    @Test
    void testCreateTransaction_WithEmptyNotes_SavesNull() {
        // Given
        String transactionId = UUID.randomUUID().toString();

        // When
        TransactionTable transaction = transactionService.createTransaction(
                testUser,
                testAccount.getAccountId(),
                BigDecimal.valueOf(-50.00),
                LocalDate.now(),
                "Test Transaction",
                "FOOD",
                transactionId,
                "" // Empty notes
        );

        // Then
        assertNotNull(transaction);
        assertNull(transaction.getNotes());

        // Verify in repository
        Optional<TransactionTable> saved = transactionRepository.findById(transactionId);
        assertTrue(saved.isPresent());
        assertNull(saved.get().getNotes());
    }

    @Test
    void testCreateTransaction_WithNullNotes_SavesNull() {
        // Given
        String transactionId = UUID.randomUUID().toString();

        // When
        TransactionTable transaction = transactionService.createTransaction(
                testUser,
                testAccount.getAccountId(),
                BigDecimal.valueOf(-50.00),
                LocalDate.now(),
                "Test Transaction",
                "FOOD",
                transactionId,
                null // Null notes
        );

        // Then
        assertNotNull(transaction);
        assertNull(transaction.getNotes());
    }

    @Test
    void testUpdateTransaction_WithNotes_UpdatesNotes() {
        // Given - Create transaction without notes
        String transactionId = UUID.randomUUID().toString();
        TransactionTable transaction = transactionService.createTransaction(
                testUser,
                testAccount.getAccountId(),
                BigDecimal.valueOf(-50.00),
                LocalDate.now(),
                "Test Transaction",
                "FOOD",
                transactionId,
                null
        );
        assertNull(transaction.getNotes());

        // When - Update with notes
        String notes = "Updated note";
        TransactionTable updated = transactionService.updateTransaction(
                testUser,
                transactionId,
                notes
        );

        // Then
        assertNotNull(updated);
        assertEquals(notes, updated.getNotes());

        // Verify in repository
        Optional<TransactionTable> saved = transactionRepository.findById(transactionId);
        assertTrue(saved.isPresent());
        assertEquals(notes, saved.get().getNotes());
    }

    @Test
    void testUpdateTransaction_WithEmptyNotes_ClearsNotes() {
        // Given - Create transaction with notes
        String transactionId = UUID.randomUUID().toString();
        TransactionTable transaction = transactionService.createTransaction(
                testUser,
                testAccount.getAccountId(),
                BigDecimal.valueOf(-50.00),
                LocalDate.now(),
                "Test Transaction",
                "FOOD",
                transactionId,
                "Original note"
        );
        assertEquals("Original note", transaction.getNotes());

        // When - Update with empty notes
        TransactionTable updated = transactionService.updateTransaction(
                testUser,
                transactionId,
                ""
        );

        // Then
        assertNotNull(updated);
        assertNull(updated.getNotes());

        // Verify in repository
        Optional<TransactionTable> saved = transactionRepository.findById(transactionId);
        assertTrue(saved.isPresent());
        assertNull(saved.get().getNotes());
    }

    @Test
    void testUpdateTransaction_WithNullNotes_ClearsNotes() {
        // Given - Create transaction with notes
        String transactionId = UUID.randomUUID().toString();
        TransactionTable transaction = transactionService.createTransaction(
                testUser,
                testAccount.getAccountId(),
                BigDecimal.valueOf(-50.00),
                LocalDate.now(),
                "Test Transaction",
                "FOOD",
                transactionId,
                "Original note"
        );
        assertEquals("Original note", transaction.getNotes());

        // When - Update with null notes
        TransactionTable updated = transactionService.updateTransaction(
                testUser,
                transactionId,
                null
        );

        // Then
        assertNotNull(updated);
        assertNull(updated.getNotes());
    }

    @Test
    void testCreateTransaction_WithNotes_ThenUpdateNotes_UpdatesCorrectly() {
        // Given - Create transaction with notes
        String transactionId = UUID.randomUUID().toString();
        String originalNotes = "Original note";
        TransactionTable transaction = transactionService.createTransaction(
                testUser,
                testAccount.getAccountId(),
                BigDecimal.valueOf(-50.00),
                LocalDate.now(),
                "Test Transaction",
                "FOOD",
                transactionId,
                originalNotes
        );
        assertEquals(originalNotes, transaction.getNotes());

        // When - Update notes
        String updatedNotes = "Updated note";
        TransactionTable updated = transactionService.updateTransaction(
                testUser,
                transactionId,
                updatedNotes
        );

        // Then
        assertEquals(updatedNotes, updated.getNotes());

        // Verify in repository
        Optional<TransactionTable> saved = transactionRepository.findById(transactionId);
        assertTrue(saved.isPresent());
        assertEquals(updatedNotes, saved.get().getNotes());
    }

    @Test
    void testCreateTransaction_WithWhitespaceNotes_TrimsAndSaves() {
        // Given
        String transactionId = UUID.randomUUID().toString();
        String notesWithWhitespace = "   Important note   ";

        // When
        TransactionTable transaction = transactionService.createTransaction(
                testUser,
                testAccount.getAccountId(),
                BigDecimal.valueOf(-50.00),
                LocalDate.now(),
                "Test Transaction",
                "FOOD",
                transactionId,
                notesWithWhitespace
        );

        // Then - Notes should be trimmed
        assertNotNull(transaction);
        assertEquals("Important note", transaction.getNotes());
    }

    @Test
    void testCreateTransaction_WithOnlyWhitespaceNotes_SavesNull() {
        // Given
        String transactionId = UUID.randomUUID().toString();
        String whitespaceOnly = "   ";

        // When
        TransactionTable transaction = transactionService.createTransaction(
                testUser,
                testAccount.getAccountId(),
                BigDecimal.valueOf(-50.00),
                LocalDate.now(),
                "Test Transaction",
                "FOOD",
                transactionId,
                whitespaceOnly
        );

        // Then - Empty/whitespace notes should be saved as null
        assertNotNull(transaction);
        assertNull(transaction.getNotes());
    }
}

