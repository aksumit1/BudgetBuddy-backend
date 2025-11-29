package com.budgetbuddy.service;

import com.budgetbuddy.model.dynamodb.TransactionActionTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.TransactionActionRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

/**
 * Integration Tests for TransactionActionService Reminder Validation
 * Tests that reminder date validation is called when creating/updating actions
 */
@ExtendWith(MockitoExtension.class)
class TransactionActionReminderValidationTest {

    @Mock
    private TransactionActionRepository actionRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private ReminderNotificationService reminderNotificationService;

    @InjectMocks
    private TransactionActionService transactionActionService;

    private UserTable testUser;
    private TransactionTable testTransaction;

    @BeforeEach
    void setUp() {
        testUser = new UserTable();
        testUser.setUserId("user-123");
        testUser.setEmail("test@example.com");

        testTransaction = new TransactionTable();
        testTransaction.setTransactionId("transaction-123");
        testTransaction.setUserId("user-123");
        testTransaction.setAmount(new BigDecimal("100.0"));
        testTransaction.setTransactionDate("2024-12-01");
    }

    @Test
    void testCreateAction_WithBothDates_ValidatesReminderDate() {
        // Given: Action with both due date and reminder date
        String dueDate = "2024-12-31";
        String reminderDate = "2024-12-30T10:00:00Z";

        when(transactionRepository.findById("transaction-123"))
                .thenReturn(Optional.of(testTransaction));
        lenient().when(actionRepository.findById(any())).thenReturn(Optional.empty());
        doAnswer(invocation -> {
            TransactionActionTable action = invocation.getArgument(0);
            return null;
        }).when(actionRepository).save(any());

        // When: Create action
        TransactionActionTable action = transactionActionService.createAction(
                testUser,
                "transaction-123",
                "Test Action",
                "Test Description",
                dueDate,
                reminderDate,
                "MEDIUM"
        );

        // Then: Validation should be called
        verify(reminderNotificationService, times(1)).validateReminderDate(reminderDate, dueDate);
        assertNotNull(action);
        assertEquals(reminderDate, action.getReminderDate());
        assertEquals(dueDate, action.getDueDate());
    }

    @Test
    void testCreateAction_WithOnlyReminderDate_NoValidationNeeded() {
        // Given: Action with only reminder date (no due date)
        String reminderDate = "2024-12-30T10:00:00Z";

        when(transactionRepository.findById("transaction-123"))
                .thenReturn(Optional.of(testTransaction));
        lenient().when(actionRepository.findById(any())).thenReturn(Optional.empty());
        doAnswer(invocation -> {
            TransactionActionTable action = invocation.getArgument(0);
            return null;
        }).when(actionRepository).save(any());

        // When: Create action
        TransactionActionTable action = transactionActionService.createAction(
                testUser,
                "transaction-123",
                "Test Action",
                "Test Description",
                null, // No due date
                reminderDate,
                "MEDIUM"
        );

        // Then: No validation needed (reminder date without due date is always valid)
        verify(reminderNotificationService, never()).validateReminderDate(any(), any());
        assertNotNull(action);
        assertEquals(reminderDate, action.getReminderDate());
        assertNull(action.getDueDate());
    }

    @Test
    void testCreateAction_WithOnlyDueDate_NoValidationNeeded() {
        // Given: Action with only due date (no reminder date)
        String dueDate = "2024-12-31";

        when(transactionRepository.findById("transaction-123"))
                .thenReturn(Optional.of(testTransaction));
        lenient().when(actionRepository.findById(any())).thenReturn(Optional.empty());
        doAnswer(invocation -> {
            TransactionActionTable action = invocation.getArgument(0);
            return null;
        }).when(actionRepository).save(any());

        // When: Create action
        TransactionActionTable action = transactionActionService.createAction(
                testUser,
                "transaction-123",
                "Test Action",
                "Test Description",
                dueDate,
                null, // No reminder date
                "MEDIUM"
        );

        // Then: No validation needed (due date without reminder date is always valid)
        verify(reminderNotificationService, never()).validateReminderDate(any(), any());
        assertNotNull(action);
        assertEquals(dueDate, action.getDueDate());
        assertNull(action.getReminderDate());
    }

    @Test
    void testUpdateAction_WithBothDates_ValidatesReminderDate() {
        // Given: Existing action and update with both dates
        TransactionActionTable existingAction = new TransactionActionTable();
        existingAction.setActionId("action-123");
        existingAction.setUserId("user-123");
        existingAction.setTitle("Existing Action");
        existingAction.setDueDate("2024-12-31");
        existingAction.setReminderDate("2024-12-30T10:00:00Z");

        String newDueDate = "2024-12-31";
        String newReminderDate = "2024-12-29T10:00:00Z";

        when(actionRepository.findById("action-123"))
                .thenReturn(Optional.of(existingAction));
        doAnswer(invocation -> {
            TransactionActionTable action = invocation.getArgument(0);
            return null;
        }).when(actionRepository).save(any());

        // When: Update action
        TransactionActionTable updatedAction = transactionActionService.updateAction(
                testUser,
                "action-123",
                null,
                null,
                newDueDate,
                newReminderDate,
                null,
                null
        );

        // Then: Validation should be called
        verify(reminderNotificationService, times(1)).validateReminderDate(newReminderDate, newDueDate);
        assertNotNull(updatedAction);
        assertEquals(newReminderDate, updatedAction.getReminderDate());
        assertEquals(newDueDate, updatedAction.getDueDate());
    }

    @Test
    void testUpdateAction_WithReminderDateAfterDueDate_StillValidates() {
        // Given: Reminder date after due date (user's choice, but not ideal)
        TransactionActionTable existingAction = new TransactionActionTable();
        existingAction.setActionId("action-123");
        existingAction.setUserId("user-123");
        existingAction.setTitle("Existing Action");

        String dueDate = "2024-12-31";
        String reminderDate = "2025-01-01T10:00:00Z"; // After due date

        when(actionRepository.findById("action-123"))
                .thenReturn(Optional.of(existingAction));
        doAnswer(invocation -> {
            TransactionActionTable action = invocation.getArgument(0);
            return null;
        }).when(actionRepository).save(any());

        // When: Update action
        TransactionActionTable updatedAction = transactionActionService.updateAction(
                testUser,
                "action-123",
                null,
                null,
                dueDate,
                reminderDate,
                null,
                null
        );

        // Then: Validation should still be called (logs warning but allows it)
        verify(reminderNotificationService, times(1)).validateReminderDate(reminderDate, dueDate);
        assertNotNull(updatedAction);
        assertEquals(reminderDate, updatedAction.getReminderDate());
        assertEquals(dueDate, updatedAction.getDueDate());
    }
}

