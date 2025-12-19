package com.budgetbuddy.service;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.TransactionActionTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.TransactionActionRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.repository.dynamodb.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for TransactionActionService focusing on action creation
 * for newly created transactions (fixing the 404 issue)
 */
@ExtendWith(MockitoExtension.class)
class TransactionActionServiceNewTransactionTest {

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private TransactionActionRepository actionRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private AccountRepository accountRepository;

    @InjectMocks
    private TransactionActionService actionService;

    @Mock
    private TransactionService transactionService;

    private UserTable testUser;
    private AccountTable testAccount;
    private TransactionTable testTransaction;

    @BeforeEach
    void setUp() {
        // Setup test user
        testUser = new UserTable();
        testUser.setUserId(UUID.randomUUID().toString().toLowerCase());
        testUser.setEmail("test@example.com");

        // Setup test account
        testAccount = new AccountTable();
        testAccount.setAccountId(UUID.randomUUID().toString().toLowerCase());
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
        String transactionId = UUID.randomUUID().toString().toLowerCase();
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
    void testCreateAction_ForNewlyCreatedTransaction_WithNormalizedId() {
        // Given - Transaction exists with lowercase ID
        String transactionId = testTransaction.getTransactionId();
        when(transactionRepository.findById(transactionId)).thenReturn(Optional.of(testTransaction));
        when(actionRepository.findById(anyString())).thenReturn(Optional.empty());
        doNothing().when(actionRepository).save(any(TransactionActionTable.class));

        // When - Create action for the transaction
        TransactionActionTable action = actionService.createAction(
                testUser,
                transactionId,
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
        assertEquals(transactionId, action.getTransactionId());
        verify(actionRepository, times(1)).save(any(TransactionActionTable.class));
    }

    @Test
    @DisplayName("Action creation should work with mixed case transaction ID (normalized in lookup)")
    void testCreateAction_WithMixedCaseTransactionId_NormalizedInLookup() {
        // Given - Transaction exists with lowercase ID
        String lowercaseTransactionId = testTransaction.getTransactionId();
        String mixedCaseTransactionId = lowercaseTransactionId.toUpperCase();

        // When looking up with mixed case, repository normalizes it
        when(transactionRepository.findById(mixedCaseTransactionId))
                .thenAnswer(invocation -> {
                    String id = invocation.getArgument(0);
                    // Simulate normalization in repository
                    return transactionRepository.findById(id.toLowerCase());
                });
        when(transactionRepository.findById(lowercaseTransactionId))
                .thenReturn(Optional.of(testTransaction));
        when(actionRepository.findById(anyString())).thenReturn(Optional.empty());
        doNothing().when(actionRepository).save(any(TransactionActionTable.class));

        // When - Create action with mixed case transaction ID
        TransactionActionTable action = actionService.createAction(
                testUser,
                mixedCaseTransactionId,
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
        // Action should have normalized transaction ID
        assertEquals(lowercaseTransactionId, action.getTransactionId());
        verify(actionRepository, times(1)).save(any(TransactionActionTable.class));
    }

    @Test
    @DisplayName("Action creation should fail if transaction doesn't exist")
    void testCreateAction_WithNonExistentTransaction_ThrowsException() {
        // Given - Transaction doesn't exist
        String nonExistentTransactionId = UUID.randomUUID().toString().toLowerCase();
        when(transactionRepository.findById(nonExistentTransactionId))
                .thenReturn(Optional.empty());

        // When/Then - Should throw exception
        assertThrows(AppException.class, () -> actionService.createAction(
                testUser,
                nonExistentTransactionId,
                "Review transaction",
                "Check if correct",
                null,
                null,
                "HIGH",
                null,
                null
        ));
    }

    @Test
    @DisplayName("Action ID should be normalized to lowercase when provided")
    void testCreateAction_WithMixedCaseActionId_Normalized() {
        // Given - Transaction exists
        String transactionId = testTransaction.getTransactionId();
        String mixedCaseActionId = "ACTION-1234-5678-ABCD-EFGH";
        String lowercaseActionId = mixedCaseActionId.toLowerCase();

        when(transactionRepository.findById(transactionId)).thenReturn(Optional.of(testTransaction));
        when(actionRepository.findById(lowercaseActionId)).thenReturn(Optional.empty());
        doNothing().when(actionRepository).save(any(TransactionActionTable.class));

        // When - Create action with mixed case action ID
        TransactionActionTable action = actionService.createAction(
                testUser,
                transactionId,
                "Review transaction",
                "Check if correct",
                null,
                null,
                "HIGH",
                mixedCaseActionId,
                null
        );

        // Then - Action ID should be normalized to lowercase
        assertEquals(lowercaseActionId, action.getActionId(),
                "Action ID should be normalized to lowercase");
        verify(actionRepository, times(1)).save(any(TransactionActionTable.class));
    }

    @Test
    @DisplayName("Random action ID should be normalized to lowercase")
    void testCreateAction_WithRandomActionId_Normalized() {
        // Given - Transaction exists
        String transactionId = testTransaction.getTransactionId();
        when(transactionRepository.findById(transactionId)).thenReturn(Optional.of(testTransaction));
        when(actionRepository.findById(anyString())).thenReturn(Optional.empty());
        doNothing().when(actionRepository).save(any(TransactionActionTable.class));

        // When - Create action without providing action ID (will generate random UUID)
        TransactionActionTable action = actionService.createAction(
                testUser,
                transactionId,
                "Review transaction",
                "Check if correct",
                null,
                null,
                "HIGH",
                null,
                null
        );

        // Then - Generated action ID should be lowercase
        String actionId = action.getActionId();
        assertEquals(actionId.toLowerCase(), actionId,
                "Generated action ID should be lowercase");
        verify(actionRepository, times(1)).save(any(TransactionActionTable.class));
    }
}

