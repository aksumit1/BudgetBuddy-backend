package com.budgetbuddy.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.TransactionActionTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.TransactionActionRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.repository.dynamodb.UserRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for TransactionActionService focusing on action creation for newly created
 * transactions (fixing the 404 issue)
 */
@ExtendWith(MockitoExtension.class)
class TransactionActionServiceNewTransactionTest {

    @Mock private TransactionRepository transactionRepository;

    @Mock private TransactionActionRepository actionRepository;

    @Mock private UserRepository userRepository;

    @Mock private AccountRepository accountRepository;

    @InjectMocks private TransactionActionService actionService;

    @Mock private TransactionService transactionService;

    private UserTable testUser;
    private AccountTable testAccount;
    private TransactionTable testTransaction;

    @BeforeEach
    void setUp() {
        // Setup test user
        testUser = new UserTable();
        testUser.setUserId(UUID.randomUUID().toString().toLowerCase(Locale.ROOT));
        testUser.setEmail("test@example.com");

        // Setup test account
        testAccount = new AccountTable();
        testAccount.setAccountId(UUID.randomUUID().toString().toLowerCase(Locale.ROOT));
        testAccount.setUserId(testUser.getUserId());
        testAccount.setAccountName("Test Account");
        testAccount.setInstitutionName("Test Bank");
        testAccount.setAccountType("CHECKING");
        testAccount.setBalance(BigDecimal.valueOf(1000.00));
        testAccount.setCurrencyCode("USD");
        testAccount.setActive(true);
        testAccount.setCreatedAt(Instant.now());
        testAccount.setUpdatedAt(Instant.now());

        // Setup test transaction (newly created)
        testTransaction = new TransactionTable();
        final String transactionId = UUID.randomUUID().toString().toLowerCase(Locale.ROOT);
        testTransaction.setTransactionId(transactionId);
        testTransaction.setUserId(testUser.getUserId());
        testTransaction.setAccountId(testAccount.getAccountId());
        testTransaction.setAmount(BigDecimal.valueOf(100.00));
        testTransaction.setDescription("New Transaction");
        testTransaction.setCategoryPrimary("FOOD");
        testTransaction.setCategoryDetailed("FOOD");
        testTransaction.setTransactionDate(LocalDate.now().toString());
        testTransaction.setCurrencyCode("USD");
        testTransaction.setCreatedAt(Instant.now());
        testTransaction.setUpdatedAt(Instant.now());
    }

    @Test
    @DisplayName("Action creation should succeed for newly created transaction with normalized ID")
    void testCreateActionForNewlyCreatedTransactionWithNormalizedId() {
        // Given - Transaction exists with lowercase ID
        final String transactionId = testTransaction.getTransactionId();
        when(transactionRepository.findById(transactionId))
                .thenReturn(Optional.of(testTransaction));
        // CRITICAL FIX: Only stub if action ID is provided - if null, action ID is generated and
        // findById won't be called
        // Using lenient() to allow unused stubbing
        lenient().when(actionRepository.findById(anyString())).thenReturn(Optional.empty());
        doNothing().when(actionRepository).save(any(TransactionActionTable.class));

        // When - Create action for the transaction
        final TransactionActionTable action =
                actionService.createAction(
                        testUser,
                        transactionId,
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
        assertEquals(transactionId, action.getTransactionId());
        verify(actionRepository, times(1)).save(any(TransactionActionTable.class));
    }

    @Test
    @DisplayName(
            "Action creation should work with mixed case transaction ID (normalized in lookup)")
    void testCreateActionWithMixedCaseTransactionIdNormalizedInLookup() {
        // Given - Transaction exists with lowercase ID
        final String lowercaseTransactionId = testTransaction.getTransactionId();
        final String mixedCaseTransactionId = lowercaseTransactionId.toUpperCase(Locale.ROOT);

        // When looking up with mixed case, repository normalizes it
        when(transactionRepository.findById(mixedCaseTransactionId))
                .thenAnswer(
                        invocation -> {
                            final String id = invocation.getArgument(0);
                            // Simulate normalization in repository
                            return transactionRepository.findById(id.toLowerCase(Locale.ROOT));
                        });
        when(transactionRepository.findById(lowercaseTransactionId))
                .thenReturn(Optional.of(testTransaction));
        // CRITICAL FIX: Use lenient() since findById might not be called if actionId is null
        lenient().when(actionRepository.findById(anyString())).thenReturn(Optional.empty());
        doNothing().when(actionRepository).save(any(TransactionActionTable.class));

        // When - Create action with mixed case transaction ID
        final TransactionActionTable action =
                actionService.createAction(
                        testUser,
                        mixedCaseTransactionId,
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
        // Action should have normalized transaction ID
        assertEquals(lowercaseTransactionId, action.getTransactionId());
        verify(actionRepository, times(1)).save(any(TransactionActionTable.class));
    }

    @Test
    @DisplayName("Action creation should fail if transaction doesn't exist")
    void testCreateActionWithNonExistentTransactionThrowsException() {
        // Given - Transaction doesn't exist
        final String nonExistentTransactionId =
                UUID.randomUUID().toString().toLowerCase(Locale.ROOT);
        when(transactionRepository.findById(nonExistentTransactionId)).thenReturn(Optional.empty());

        // When/Then - Should throw exception
        assertThrows(
                AppException.class,
                () ->
                        actionService.createAction(
                                testUser,
                                nonExistentTransactionId,
                                "Review transaction",
                                "Check if correct",
                                null,
                                null,
                                "HIGH",
                                null,
                                null));
    }

    @Test
    @DisplayName("Action ID should be normalized to lowercase when provided")
    void testCreateActionWithMixedCaseActionIdNormalized() {
        // Given - Transaction exists
        final String transactionId = testTransaction.getTransactionId();
        // Use a valid UUID format (mixed case)
        final String mixedCaseActionId = "EBC9B5FF-F555-48A3-B1A9-F8A91A48AB15";
        final String lowercaseActionId = mixedCaseActionId.toLowerCase(Locale.ROOT);

        when(transactionRepository.findById(transactionId))
                .thenReturn(Optional.of(testTransaction));
        // CRITICAL FIX: Stub with the normalized (lowercase) ID since the code normalizes before
        // calling findById
        // The service now normalizes the ID to lowercase before checking for existing actions
        when(actionRepository.findById(lowercaseActionId)).thenReturn(Optional.empty());
        doNothing().when(actionRepository).save(any(TransactionActionTable.class));

        // When - Create action with mixed case action ID
        final TransactionActionTable action =
                actionService.createAction(
                        testUser,
                        transactionId,
                        "Review transaction",
                        "Check if correct",
                        null,
                        null,
                        "HIGH",
                        mixedCaseActionId,
                        null);

        // Then - Action ID should be normalized to lowercase
        assertEquals(
                lowercaseActionId,
                action.getActionId(),
                "Action ID should be normalized to lowercase");
        verify(actionRepository, times(1)).save(any(TransactionActionTable.class));
    }

    @Test
    @DisplayName("Random action ID should be normalized to lowercase")
    void testCreateActionWithRandomActionIdNormalized() {
        // Given - Transaction exists
        final String transactionId = testTransaction.getTransactionId();
        when(transactionRepository.findById(transactionId))
                .thenReturn(Optional.of(testTransaction));
        // CRITICAL FIX: Use lenient() since findById won't be called when actionId is null
        // (generated)
        lenient().when(actionRepository.findById(anyString())).thenReturn(Optional.empty());
        doNothing().when(actionRepository).save(any(TransactionActionTable.class));

        // When - Create action without providing action ID (will generate random UUID)
        final TransactionActionTable action =
                actionService.createAction(
                        testUser,
                        transactionId,
                        "Review transaction",
                        "Check if correct",
                        null,
                        null,
                        "HIGH",
                        null,
                        null);

        // Then - Generated action ID should be lowercase
        final String actionId = action.getActionId();
        assertEquals(
                actionId.toLowerCase(Locale.ROOT),
                actionId,
                "Generated action ID should be lowercase");
        verify(actionRepository, times(1)).save(any(TransactionActionTable.class));
    }
}
